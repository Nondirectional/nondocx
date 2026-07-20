package io.github.nondirectional.docx.core.api.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Calendar;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFComment;
import org.apache.poi.xwpf.usermodel.XWPFComments;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTMarkupRange;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText;

/**
 * 批注(comments)只读消费侧的验收测试。
 *
 * <p>用 XmlBeans 手搓带批注的 docx(确定性、不依赖 Word),验证 nondocx 的读取:body 顺序枚举、按 id 精确查、
 * 无批注空列表、孤儿批注降级、五字段读值、活视图、未命中抛异常。
 *
 * <p>这些测试覆盖 read 子任务的 AC1–AC7。
 */
class CommentsTest {

  /**
   * 构造一条带批注的 docx:在 body 里用 commentRangeStart/Reference/End 锚定正文范围,在 comments.xml 里写批注
   * 正文。写文件后返回路径,供 {@link Docx#open} 重开。
   *
   * @param tmp 临时目录
   * @param name 文件名
   * @param bodyCommentIds body 里锚点的 id 顺序(决定 body 顺序);每个 id 对应一个段落
   * @param commentSpecs comments.xml 里的批注,按「创建顺序」传入(id/author/initials/date/text);顺序决定部件顺序
   * @return 写好的 docx 文件路径
   */
  private Path buildDocx(
      Path tmp, String name, long[] bodyCommentIds, List<CommentSpec> commentSpecs)
      throws Exception {
    Path file = tmp.resolve(name);
    try (XWPFDocument poi = new XWPFDocument()) {
      // comments.xml:按 commentSpecs 顺序创建(部件顺序 = 此处顺序)
      XWPFComments xcomments = null;
      if (!commentSpecs.isEmpty()) {
        xcomments = poi.createComments();
        for (CommentSpec spec : commentSpecs) {
          XWPFComment c = xcomments.createComment(BigInteger.valueOf(spec.id));
          if (spec.author != null) {
            c.setAuthor(spec.author);
          }
          if (spec.initials != null) {
            c.setInitials(spec.initials);
          }
          if (spec.date != null) {
            c.setDate(spec.date);
          }
          // 批注正文:支持多段(用 \n 分隔)
          String[] paragraphs = spec.text.split("\n");
          for (String para : paragraphs) {
            c.createParagraph().createRun().setText(para);
          }
        }
      }
      // body:按 bodyCommentIds 顺序建段落,每段锚定一个批注
      CTBody body = poi.getDocument().getBody();
      for (long id : bodyCommentIds) {
        CTP p = body.addNewP();
        CTMarkupRange start = p.addNewCommentRangeStart();
        start.setId(BigInteger.valueOf(id));
        CTR r = p.addNewR();
        CTText t = r.addNewT();
        t.setStringValue("被评论的正文 " + id);
        r.addNewCommentReference().setId(BigInteger.valueOf(id));
        CTMarkupRange end = p.addNewCommentRangeEnd();
        end.setId(BigInteger.valueOf(id));
      }
      try (OutputStream out = Files.newOutputStream(file)) {
        poi.write(out);
      }
    }
    return file;
  }

  /** 批注规格(id/author/initials/date/text),用于 {@link #buildDocx} 构造 fixture。 */
  private static final class CommentSpec {
    final long id;
    final String author;
    final String initials;
    final Calendar date;
    final String text;

    CommentSpec(long id, String author, String initials, Calendar date, String text) {
      this.id = id;
      this.author = author;
      this.initials = initials;
      this.date = date;
      this.text = text;
    }
  }

  // ---------- AC3: 无批注文档 list() 返回空列表 ----------

  @Test
  void listReturnsEmptyWhenNoComments(@TempDir Path tmp) throws Exception {
    Path file = buildDocx(tmp, "no-comments.docx", new long[0], List.of());
    try (Document doc = Docx.open(file)) {
      assertThat(doc.comments().list()).isEmpty();
    }
  }

  @Test
  void listReturnsEmptyWhenNoCommentsEvenWithBody(@TempDir Path tmp) throws Exception {
    // body 有普通段落、但完全无批注
    Path file = tmp.resolve("plain.docx");
    try (XWPFDocument poi = new XWPFDocument()) {
      poi.createParagraph().createRun().setText("普通文档,无批注");
      try (OutputStream out = Files.newOutputStream(file)) {
        poi.write(out);
      }
    }
    try (Document doc = Docx.open(file)) {
      assertThat(doc.comments().list()).isEmpty();
    }
  }

  // ---------- AC1 + AC4: 单条批注能读出,五字段正确 ----------

  @Test
  void singleCommentReadsAllFields(@TempDir Path tmp) throws Exception {
    Calendar date = Calendar.getInstance();
    date.set(2026, Calendar.JUNE, 22, 10, 0, 0);
    date.set(Calendar.MILLISECOND, 0);
    long expectedMillis = date.getTimeInMillis();
    Path file =
        buildDocx(
            tmp,
            "single.docx",
            new long[] {0},
            List.of(new CommentSpec(0, "non", "n", date, "这是批注内容")));
    try (Document doc = Docx.open(file)) {
      List<Comment> list = doc.comments().list();
      assertThat(list).hasSize(1);
      Comment c = list.get(0);
      assertThat(c.id()).isEqualTo("0");
      assertThat(c.author()).isEqualTo("non");
      assertThat(c.initials()).isEqualTo("n");
      // POI 返回 XmlCalendar(非 GregorianCalendar),按时间戳比较而非类型相等
      assertThat(c.date()).isNotNull();
      assertThat(c.date().getTimeInMillis()).isEqualTo(expectedMillis);
      assertThat(c.text()).isEqualTo("这是批注内容");
    }
  }

  // ---------- AC4: date / initials 缺失 ----------

  @Test
  void missingDateReturnsNull(@TempDir Path tmp) throws Exception {
    Path file =
        buildDocx(
            tmp,
            "no-date.docx",
            new long[] {0},
            List.of(new CommentSpec(0, "non", "n", null, "无日期批注")));
    try (Document doc = Docx.open(file)) {
      Comment c = doc.comments().list().get(0);
      assertThat(c.date()).isNull();
    }
  }

  @Test
  void missingInitialsReturnsEmpty(@TempDir Path tmp) throws Exception {
    Path file =
        buildDocx(
            tmp,
            "no-initials.docx",
            new long[] {0},
            List.of(new CommentSpec(0, "non", null, null, "无缩写批注")));
    try (Document doc = Docx.open(file)) {
      Comment c = doc.comments().list().get(0);
      assertThat(c.initials()).isEmpty();
    }
  }

  // ---------- 核心契约:body 顺序 ≠ comments.xml 部件顺序 ----------

  /**
   * fixture 里 comments.xml 按 id 2,0,1 顺序创建(部件顺序);body 按 id 0,1,2 顺序锚定(正文顺序)。 list() 必须按 body 顺序返回
   * [0,1,2],而不是部件顺序 [2,0,1]。这是 design §5 的核心契约。
   */
  @Test
  void listReturnsBodyOrderNotPartOrder(@TempDir Path tmp) throws Exception {
    Path file =
        buildDocx(
            tmp,
            "ordering.docx",
            new long[] {0, 1, 2},
            List.of(
                new CommentSpec(2, "non2", null, null, "comment id=2(先创建)"),
                new CommentSpec(0, "non0", null, null, "comment id=0"),
                new CommentSpec(1, "non1", null, null, "comment id=1")));
    try (Document doc = Docx.open(file)) {
      List<Comment> list = doc.comments().list();
      assertThat(list).extracting(Comment::id).containsExactly("0", "1", "2");
    }
  }

  // ---------- 孤儿批注降级排末尾 ----------

  /** comments.xml 有 id=0,1,2 三条;body 只锚定 id=0。id=1,2 是孤儿(无锚点),按部件顺序追加到末尾,不丢弃。 */
  @Test
  void orphanCommentsAppendedAtEnd(@TempDir Path tmp) throws Exception {
    Path file =
        buildDocx(
            tmp,
            "orphans.docx",
            new long[] {0},
            List.of(
                new CommentSpec(1, "non1", null, null, "孤儿 1"),
                new CommentSpec(0, "non0", null, null, "有锚点"),
                new CommentSpec(2, "non2", null, null, "孤儿 2")));
    try (Document doc = Docx.open(file)) {
      List<Comment> list = doc.comments().list();
      // body 命中的 id=0 在前;孤儿按 comments.xml 部件顺序(1,2)追加
      assertThat(list).extracting(Comment::id).containsExactly("0", "1", "2");
    }
  }

  // ---------- 多段批注 text() 拼接 ----------

  @Test
  void multiParagraphTextIsConcatenated(@TempDir Path tmp) throws Exception {
    Path file =
        buildDocx(
            tmp,
            "multi-para.docx",
            new long[] {0},
            List.of(new CommentSpec(0, "non", null, null, "第一段\n第二段")));
    try (Document doc = Docx.open(file)) {
      Comment c = doc.comments().list().get(0);
      // POI getText() 拼接多段:两段文本都应在(可能含换行或直接拼接)
      assertThat(c.text()).contains("第一段").contains("第二段");
    }
  }

  // ---------- AC2: get(id) 命中 / 未命中 ----------

  @Test
  void getByIdHits(@TempDir Path tmp) throws Exception {
    Path file =
        buildDocx(
            tmp,
            "get-hit.docx",
            new long[] {0, 1},
            List.of(
                new CommentSpec(0, "non0", null, null, "批注 0"),
                new CommentSpec(1, "non1", null, null, "批注 1")));
    try (Document doc = Docx.open(file)) {
      Comment c = doc.comments().get("1");
      assertThat(c.id()).isEqualTo("1");
      assertThat(c.author()).isEqualTo("non1");
      assertThat(c.text()).isEqualTo("批注 1");
    }
  }

  @Test
  void getByIdMissThrowsNoSuchElement(@TempDir Path tmp) throws Exception {
    Path file =
        buildDocx(
            tmp,
            "get-miss.docx",
            new long[] {0},
            List.of(new CommentSpec(0, "non", null, null, "批注 0")));
    try (Document doc = Docx.open(file)) {
      assertThatThrownBy(() -> doc.comments().get("99"))
          .isInstanceOf(java.util.NoSuchElementException.class)
          .hasMessageContaining("99");
    }
  }

  @Test
  void getByIdMissOnNoCommentDocThrows(@TempDir Path tmp) throws Exception {
    Path file = buildDocx(tmp, "empty.docx", new long[0], List.of());
    try (Document doc = Docx.open(file)) {
      assertThatThrownBy(() -> doc.comments().get("0"))
          .isInstanceOf(java.util.NoSuchElementException.class);
    }
  }

  @Test
  void getWithNullIdThrowsIllegalArgument(@TempDir Path tmp) throws Exception {
    Path file =
        buildDocx(
            tmp,
            "null-id.docx",
            new long[] {0},
            List.of(new CommentSpec(0, "non", null, null, "x")));
    try (Document doc = Docx.open(file)) {
      assertThatThrownBy(() -> doc.comments().get(null))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  // ---------- AC5: 活视图 (save→reopen 反映新状态) ----------

  @Test
  void listIsLiveViewAcrossReopen(@TempDir Path tmp) throws Exception {
    // 第一次写:1 条批注
    Path file =
        buildDocx(
            tmp,
            "live.docx",
            new long[] {0},
            List.of(new CommentSpec(0, "non", null, null, "初始批注")));
    try (Document doc = Docx.open(file)) {
      assertThat(doc.comments().list()).hasSize(1);
      doc.save(file);
    }
    // reopen:仍是 1 条(save 不丢批注)
    try (Document doc = Docx.open(file)) {
      assertThat(doc.comments().list()).hasSize(1);
      assertThat(doc.comments().list().get(0).text()).isEqualTo("初始批注");
    }
  }

  // ---------- 同会话多次 list() 结果一致 ----------

  @Test
  void listIsStableWithinSession(@TempDir Path tmp) throws Exception {
    Path file =
        buildDocx(
            tmp,
            "stable.docx",
            new long[] {0, 1, 2},
            List.of(
                new CommentSpec(2, "non2", null, null, "c2"),
                new CommentSpec(0, "non0", null, null, "c0"),
                new CommentSpec(1, "non1", null, null, "c1")));
    try (Document doc = Docx.open(file)) {
      List<String> ids1 =
          doc.comments().list().stream()
              .map(Comment::id)
              .collect(java.util.stream.Collectors.toList());
      List<String> ids2 =
          doc.comments().list().stream()
              .map(Comment::id)
              .collect(java.util.stream.Collectors.toList());
      assertThat(ids1).containsExactly("0", "1", "2");
      assertThat(ids2).isEqualTo(ids1);
    }
  }

  // ---------- 内容相等 ----------

  @Test
  void commentsAreContentEqual(@TempDir Path tmp) throws Exception {
    Path file =
        buildDocx(
            tmp,
            "equals.docx",
            new long[] {0},
            List.of(new CommentSpec(0, "non", "n", null, "相同批注")));
    try (Document doc = Docx.open(file)) {
      Comment c1 = doc.comments().get("0");
      Comment c2 = doc.comments().list().get(0);
      assertThat(c1).isEqualTo(c2);
      assertThat(c1.hashCode()).isEqualTo(c2.hashCode());
    }
  }

  // ---------- raw() 返回同一委托 ----------

  @Test
  void rawReturnsSameDelegate(@TempDir Path tmp) throws Exception {
    Path file =
        buildDocx(
            tmp, "raw.docx", new long[] {0}, List.of(new CommentSpec(0, "non", null, null, "x")));
    try (Document doc = Docx.open(file)) {
      Comment c = doc.comments().get("0");
      assertThat(c.raw()).isNotNull();
      assertThat(c.raw()).isSameAs(c.raw());
    }
  }

  // ---------- 表格内批注也能读到(body 顺序覆盖表格层级) ----------

  /** 批注锚点在表格单元格的段落里(非 body 直接子段落)。验证 CommentNodes 的深度优先遍历覆盖表格层级。 */
  @Test
  void commentInsideTableIsRead(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("in-table.docx");
    try (XWPFDocument poi = new XWPFDocument()) {
      XWPFComments xcomments = poi.createComments();
      XWPFComment c = xcomments.createComment(BigInteger.valueOf(5));
      c.setAuthor("non");
      c.createParagraph().createRun().setText("表格内批注");

      // body 先一个普通段落(无批注)
      poi.createParagraph().createRun().setText("普通段落");

      // 表格:单元格内段落带批注锚点
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTbl tbl =
          poi.getDocument().getBody().addNewTbl();
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRow tr = tbl.addNewTr();
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc tc = tr.addNewTc();
      CTP p = tc.addNewP();
      CTMarkupRange start = p.addNewCommentRangeStart();
      start.setId(BigInteger.valueOf(5));
      CTR r = p.addNewR();
      CTText t = r.addNewT();
      t.setStringValue("单元格内被评论的文本");
      r.addNewCommentReference().setId(BigInteger.valueOf(5));
      CTMarkupRange end = p.addNewCommentRangeEnd();
      end.setId(BigInteger.valueOf(5));

      try (OutputStream out = Files.newOutputStream(file)) {
        poi.write(out);
      }
    }
    try (Document doc = Docx.open(file)) {
      List<Comment> list = doc.comments().list();
      assertThat(list).hasSize(1);
      assertThat(list.get(0).id()).isEqualTo("5");
      assertThat(list.get(0).author()).isEqualTo("non");
      assertThat(list.get(0).text()).isEqualTo("表格内批注");
    }
  }
}
