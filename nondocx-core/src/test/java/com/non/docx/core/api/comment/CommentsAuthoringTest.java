package com.non.docx.core.api.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.text.Paragraph;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;

/**
 * 批注(comments)创作侧的验收测试。
 *
 * <p>覆盖单条整段范围批注的显式创作:{@link Paragraph#addComment}。验证创作出的批注能被 {@link Comments#list()} 读回、round-trip
 * 结构完整、参数校验、id 自增、空段边界,以及不污染普通写 API。
 *
 * <p>测试用 XmlBeans 手搓 fixture(确定性、不依赖 Word) + nondocx {@link Docx#open} round-trip,覆盖 prd
 * AC1–AC3、AC5。AC4(Word/WPS 人工验收)留作 review gate 的人工项。
 */
class CommentsAuthoringTest {

  // ---------- AC1 + AC2: 创作后返回 Comment,list() 能读回 ----------

  @Test
  void addCommentReturnsCreatedCommentAndListReadsItBack(@TempDir Path tmp) throws Exception {
    Path file = writeDocxWithContent(tmp, "段A内容", "段B内容");
    try (Document doc = Docx.open(file)) {
      Comment created = doc.paragraph(0).addComment("审阅者甲", "这段需要补充背景");

      // AC1: 返回的 Comment 元数据正确
      assertThat(created.author()).isEqualTo("审阅者甲");
      assertThat(created.text()).isEqualTo("这段需要补充背景");
      assertThat(created.id()).isNotEmpty();

      // AC2: doc.comments().list() 能读回该批注
      List<Comment> list = doc.comments().list();
      assertThat(list).hasSize(1);
      assertThat(list.get(0).author()).isEqualTo("审阅者甲");
      assertThat(list.get(0).text()).isEqualTo("这段需要补充背景");
    }
  }

  // ---------- AC3: round-trip 结构完整 + 可读 ----------

  @Test
  void roundTripPreservesCommentAndStructure(@TempDir Path tmp) throws Exception {
    Path file = writeDocxWithContent(tmp, "已有文本A", "已有文本B");
    try (Document doc = Docx.open(file)) {
      doc.paragraph(0).addComment("non", "round-trip 测试批注");
      doc.save(file.resolveSibling("round-trip-out.docx"));
    }

    Path out = file.resolveSibling("round-trip-out.docx");
    try (Document doc = Docx.open(out)) {
      // AC3: reopen 后 list() 仍读到,author/text 正确
      List<Comment> list = doc.comments().list();
      assertThat(list).hasSize(1);
      Comment c = list.get(0);
      assertThat(c.author()).isEqualTo("non");
      assertThat(c.text()).isEqualTo("round-trip 测试批注");
    }

    // 结构断言:document.xml 里 commentRangeStart 在段首、commentRangeEnd 在 run 之后、引用 run 段末
    try (XWPFDocument poi = new XWPFDocument(Files.newInputStream(out))) {
      XWPFParagraph p = poi.getParagraphs().get(0);
      List<String> childLocals = childLocalNames(p.getCTP());
      // paragraph(0) 只含"已有文本A"一个 run;期望: start, r(已有文本A), end, r(引用)
      assertThat(childLocals).containsExactly("commentRangeStart", "r", "commentRangeEnd", "r");
      // 锚点 id 一致
      assertThat(p.getCTP().getCommentRangeStartArray(0).getId())
          .isEqualTo(p.getCTP().getCommentRangeEndArray(0).getId());
    }
  }

  // ---------- AC5: 参数校验 ----------

  @Test
  void addCommentRejectsNullOrBlankAuthor(@TempDir Path tmp) throws Exception {
    Path file = writeDocxWithContent(tmp, "内容");
    try (Document doc = Docx.open(file)) {
      assertThatThrownBy(() -> doc.paragraph(0).addComment(null, "批注"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> doc.paragraph(0).addComment("   ", "批注"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> doc.paragraph(0).addComment("", "批注"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void addCommentRejectsNullText(@TempDir Path tmp) throws Exception {
    Path file = writeDocxWithContent(tmp, "内容");
    try (Document doc = Docx.open(file)) {
      assertThatThrownBy(() -> doc.paragraph(0).addComment("作者", null))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  // ---------- 3.4: 多条批注 id 自增 ----------

  @Test
  void multipleCommentsGetIncrementingIds(@TempDir Path tmp) throws Exception {
    Path file = writeDocxWithContent(tmp, "段A", "段B");
    try (Document doc = Docx.open(file)) {
      Comment first = doc.paragraph(0).addComment("甲", "第一条");
      Comment second = doc.paragraph(1).addComment("乙", "第二条");

      long firstId = Long.parseLong(first.id());
      long secondId = Long.parseLong(second.id());
      assertThat(secondId).isEqualTo(firstId + 1);
    }
  }

  // ---------- 3.5: 空段批注边界 ----------

  @Test
  void addCommentOnEmptyParagraph(@TempDir Path tmp) throws Exception {
    // 构造一个无 run 的空段
    Path file = tmp.resolve("empty-para.docx");
    try (XWPFDocument poi = new XWPFDocument();
        java.io.OutputStream out = Files.newOutputStream(file)) {
      poi.createParagraph(); // 空段,无任何 run
      poi.write(out);
    }
    Path out = file.resolveSibling("empty-para-out.docx");
    try (Document doc = Docx.open(file)) {
      Comment created = doc.paragraph(0).addComment("non", "空段批注");
      assertThat(created.text()).isEqualTo("空段批注");

      List<Comment> list = doc.comments().list();
      assertThat(list).hasSize(1);
      doc.save(out);
    }
    // round-trip 结构断言:空段无子,toFirstChild 返 false 不 move,start 已在首位
    try (XWPFDocument poi = new XWPFDocument(Files.newInputStream(out))) {
      XWPFParagraph p = poi.getParagraphs().get(0);
      List<String> locals = childLocalNames(p.getCTP());
      assertThat(locals).containsExactly("commentRangeStart", "commentRangeEnd", "r");
    }
  }

  // ---------- 3.6: 不污染普通写 API ----------

  @Test
  void addCommentDoesNotPolluteRunsView(@TempDir Path tmp) throws Exception {
    Path file = writeDocxWithContent(tmp, "原有文本");
    try (Document doc = Docx.open(file)) {
      doc.paragraph(0).addComment("non", "批注");
      // addComment 不改变 paragraph.runs() —— 批注锚点/引用 run 不进入普通 runs 视图
      // 原有 1 个文本 run 仍是 1 个
      assertThat(doc.paragraph(0).runs()).hasSize(1);
    }
  }

  // ---------- helpers ----------

  /** 用 XmlBeans 手搓一个含若干带内容段落的 docx(确定性,不依赖 Word)。 */
  private Path writeDocxWithContent(Path tmp, String... paragraphTexts) throws Exception {
    Path file = tmp.resolve("with-content.docx");
    try (XWPFDocument poi = new XWPFDocument();
        java.io.OutputStream out = Files.newOutputStream(file)) {
      for (String text : paragraphTexts) {
        poi.createParagraph().createRun().setText(text);
      }
      poi.write(out);
    }
    return file;
  }

  /** dump 一个 CTP 的直接子元素的 local name 序列(用于结构断言)。 */
  private static List<String> childLocalNames(CTP ctp) {
    java.util.List<String> names = new java.util.ArrayList<>();
    org.apache.xmlbeans.XmlCursor cur = ctp.newCursor();
    try {
      if (cur.toFirstChild()) {
        do {
          names.add(cur.getName().getLocalPart());
        } while (cur.toNextSibling());
      }
    } finally {
      cur.dispose();
    }
    return names;
  }
}
