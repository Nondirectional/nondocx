package io.github.nondirectional.docx.core.api.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 批注回复 + 线程(commentsExtended 四 part 自维护)的验收测试。
 *
 * <p>覆盖 {@link Comments#reply} 的回复创作、{@link Comment#parentId()}/{@link Comment#paraId()} 的线程读侧、 四
 * part 的幂等/round-trip、参数校验、兼容性(无 commentsExtended 的旧文档)。
 *
 * <p>测试用 nondocx {@link Docx#open} round-trip + XmlBeans/unzip 结构断言(确定性,不依赖 Word)。AC4(Word 线程 显示)留作
 * review gate 人工项。
 */
class CommentsReplyThreadsTest {

  // ---------- AC1 + AC2: reply 返回 Comment,parentId 命中 ----------

  @Test
  void replyReturnsCommentWithParentId(@TempDir Path tmp) throws Exception {
    Path file = writeDocWithRootComment(tmp);
    try (Document doc = Docx.open(file)) {
      Comment parent = doc.comments().list().get(0);
      Comment reply = doc.comments().reply(parent.id(), "回复者", "同意,但需补充数据");

      // AC1: 返回了 Comment
      assertThat(reply).isNotNull();
      // AC2: parentId 命中父 id
      assertThat(reply.parentId()).hasValue(parent.id());
      assertThat(reply.author()).isEqualTo("回复者");
      assertThat(reply.text()).isEqualTo("同意,但需补充数据");
    }
  }

  // ---------- AC3: round-trip 后 list() 还原线程 ----------

  @Test
  void roundTripPreservesThreadRelation(@TempDir Path tmp) throws Exception {
    Path file = writeDocWithRootComment(tmp);
    Path out = file.resolveSibling("reply-out.docx");
    String parentId;
    try (Document doc = Docx.open(file)) {
      parentId = doc.comments().list().get(0).id();
      doc.comments().reply(parentId, "乙", "第一条回复");
      doc.save(out);
    }
    try (Document doc = Docx.open(out)) {
      List<Comment> list = doc.comments().list();
      // 至少 2 条:根 + 回复
      assertThat(list).hasSizeGreaterThanOrEqualTo(2);
      // 找到回复批注,验证 parentId 链完整
      Comment reply = list.stream().filter(c -> c.parentId().isPresent()).findFirst().orElseThrow();
      assertThat(reply.parentId()).hasValue(parentId);
      assertThat(reply.text()).isEqualTo("第一条回复");
      // 根批注 parentId 仍为 empty
      Comment root = list.stream().filter(c -> c.parentId().isEmpty()).findFirst().orElseThrow();
      assertThat(root.id()).isEqualTo(parentId);
    }
  }

  // ---------- AC5: 幂等——多次 reply 不重复注册 part/relationship ----------

  @Test
  void multipleRepliesDoNotDuplicateParts(@TempDir Path tmp) throws Exception {
    Path file = writeDocWithRootComment(tmp);
    Path out = file.resolveSibling("multi-reply.docx");
    try (Document doc = Docx.open(file)) {
      String pid = doc.comments().list().get(0).id();
      doc.comments().reply(pid, "甲", "回复1");
      doc.comments().reply(pid, "乙", "回复2");
      doc.comments().reply(pid, "丙", "回复3");
      doc.save(out);
    }
    // unzip 检查:四 part 各只 1 个文件,Content_Types 各只 1 个 Override
    try (ZipFile zf = new ZipFile(out.toFile())) {
      // 三个 part 文件各存在(且只 1 个)
      assertThat(zf.getEntry("word/commentsExtended.xml")).isNotNull();
      assertThat(zf.getEntry("word/commentsIds.xml")).isNotNull();
      assertThat(zf.getEntry("word/commentsExtensible.xml")).isNotNull();
      String contentTypes =
          new String(zf.getInputStream(zf.getEntry("[Content_Types].xml")).readAllBytes());
      // 每个 Override 只出现一次(幂等:不重复注册)
      assertThat(countOccurrences(contentTypes, "commentsExtended+xml")).isEqualTo(1);
      assertThat(countOccurrences(contentTypes, "commentsIds+xml")).isEqualTo(1);
      assertThat(countOccurrences(contentTypes, "commentsExtensible+xml")).isEqualTo(1);
      // rels 每个 relationship 只 1 个(Target 用相对路径,无前导斜杠)
      String rels =
          new String(zf.getInputStream(zf.getEntry("word/_rels/document.xml.rels")).readAllBytes());
      assertThat(countOccurrences(rels, "commentsExtended.xml")).isEqualTo(1);
      assertThat(countOccurrences(rels, "commentsIds.xml")).isEqualTo(1);
      assertThat(countOccurrences(rels, "commentsExtensible.xml")).isEqualTo(1);
      // commentsExtended 内有 3 条 commentEx(3 次回复)
      String extended =
          new String(zf.getInputStream(zf.getEntry("word/commentsExtended.xml")).readAllBytes());
      assertThat(countOccurrences(extended, "commentEx")).isEqualTo(3);
    }
    // 功能验证:reopen 后 3 条回复都能读回且 parentId 都指向根
    try (Document doc = Docx.open(out)) {
      List<Comment> replies =
          doc.comments().list().stream()
              .filter(c -> c.parentId().isPresent())
              .collect(java.util.stream.Collectors.toList());
      assertThat(replies).hasSize(3);
      assertThat(replies).allSatisfy(c -> assertThat(c.parentId()).isPresent());
    }
  }

  // ---------- AC6: parentId 未命中抛 NoSuchElementException ----------

  @Test
  void replyRejectsUnknownParentId(@TempDir Path tmp) throws Exception {
    Path file = writeDocWithRootComment(tmp);
    try (Document doc = Docx.open(file)) {
      assertThatThrownBy(() -> doc.comments().reply("999", "甲", "回复"))
          .isInstanceOf(java.util.NoSuchElementException.class);
    }
  }

  @Test
  void replyRejectsNullOrBlankAuthor(@TempDir Path tmp) throws Exception {
    Path file = writeDocWithRootComment(tmp);
    try (Document doc = Docx.open(file)) {
      String pid = doc.comments().list().get(0).id();
      assertThatThrownBy(() -> doc.comments().reply(pid, null, "回复"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> doc.comments().reply(pid, "  ", "回复"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> doc.comments().reply(pid, "", "回复"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void replyRejectsNullParentId(@TempDir Path tmp) throws Exception {
    Path file = writeDocWithRootComment(tmp);
    try (Document doc = Docx.open(file)) {
      assertThatThrownBy(() -> doc.comments().reply(null, "甲", "回复"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> doc.comments().reply("0", "甲", null))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  // ---------- 兼容性:无 commentsExtended 的文档(authoring 产出) ----------

  @Test
  void documentWithoutExtendedHasAllRootComments(@TempDir Path tmp) throws Exception {
    // authoring 产出:有 comments.xml,无 commentsExtended
    Path file = writeDocWithRootComment(tmp);
    try (Document doc = Docx.open(file)) {
      for (Comment c : doc.comments().list()) {
        // 无线程信息:parentId 全 empty, paraId 可能为 null(authoring 未补)或补后的值
        assertThat(c.parentId()).isEmpty();
      }
    }
    // unzip 确认无 commentsExtended
    try (ZipFile zf = new ZipFile(file.toFile())) {
      assertThat(zf.getEntry("word/commentsExtended.xml")).isNull();
    }
  }

  // ---------- 多级回复:A → B → C ----------

  @Test
  void multiLevelReplyChain(@TempDir Path tmp) throws Exception {
    Path file = writeDocWithRootComment(tmp);
    Path out = file.resolveSibling("chain.docx");
    try (Document doc = Docx.open(file)) {
      String aId = doc.comments().list().get(0).id();
      Comment b = doc.comments().reply(aId, "B", "回复A");
      doc.comments().reply(b.id(), "C", "回复B");
      doc.save(out);
    }
    try (Document doc = Docx.open(out)) {
      List<Comment> list = doc.comments().list();
      // 找 A、B、C
      Comment a = findById(list, "0").orElseThrow();
      Comment c = list.stream().filter(x -> "回复B".equals(x.text())).findFirst().orElseThrow();
      Comment b = list.stream().filter(x -> "回复A".equals(x.text())).findFirst().orElseThrow();
      // 链:A←B←C
      assertThat(a.parentId()).isEmpty();
      assertThat(b.parentId()).hasValue(a.id());
      assertThat(c.parentId()).hasValue(b.id());
    }
  }

  // ---------- helpers ----------

  /** 建一个含内容的段落 + 1 条根批注(用 authoring 的 addComment)。返回写好的 docx 路径。 */
  private Path writeDocWithRootComment(Path tmp) throws Exception {
    Path file = tmp.resolve("with-root.docx");
    try (Document doc = Docx.create()) {
      Paragraph p = doc.addParagraph("一段被批注的内容");
      p.addComment("原作者", "这是根批注");
      doc.save(file);
    }
    return file;
  }

  private static Optional<Comment> findById(List<Comment> list, String id) {
    return list.stream().filter(c -> c.id().equals(id)).findFirst();
  }

  private static int countOccurrences(String haystack, String needle) {
    int count = 0;
    int idx = 0;
    while ((idx = haystack.indexOf(needle, idx)) >= 0) {
      count++;
      idx += needle.length();
    }
    return count;
  }
}
