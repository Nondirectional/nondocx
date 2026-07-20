package io.github.nondirectional.docx.core.api.comment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 批注(comments)现代兼容基础设施验收测试(子任务 4:people.xml / w14:paraId / RSID)。
 *
 * <p>覆盖三项「锦上添花」元数据自动注入的 AC1–AC3、AC5、AC6。验证手段:创作批注 → save → 用 {@link ZipFile} 直接读 docx 产物,断言 OOXML
 * 结构(people.xml/paraId/RSID/settings.xml rsids)。AC4(Word 审阅面板人工验收)留 review gate。
 *
 * <p>对照 {@link CommentsAuthoringTest}(批注创作本身)与 {@link CommentsReplyThreadsTest}(线程)——本测试专注基础设施注入,
 * 不重复创作/线程的语义验证。
 */
class CommentsInfrastructureTest {

  // ---------- AC1 + AC5: people.xml 自动维护 + 幂等 ----------

  @Test
  void addCommentCreatesPeopleXmlWithAuthorEntry(@TempDir Path tmp) throws Exception {
    Path file = writeDocxWithContent(tmp, "正文内容");
    Path out = tmp.resolve("out.docx");
    try (Document doc = Docx.open(file)) {
      doc.paragraph(0).addComment("审阅者甲", "批注正文");
      doc.save(out);
    }
    // AC1: people.xml 存在且含 author 条目
    String people = readPart(out, "word/people.xml");
    assertThat(people).contains("<w15:people");
    assertThat(people).contains("w15:author=\"审阅者甲\"");
    assertThat(people).contains("w15:providerId=\"None\"");
    assertThat(people).contains("w15:userId=\"审阅者甲\"");
  }

  @Test
  void peopleXmlIsIdempotentForSameAuthor(@TempDir Path tmp) throws Exception {
    // AC5: 同一 author 连续注册,people.xml 只一条 person 条目
    Path file = writeDocxWithContent(tmp, "段A", "段B", "段C");
    Path out = tmp.resolve("out.docx");
    try (Document doc = Docx.open(file)) {
      doc.paragraph(0).addComment("甲", "第一条");
      doc.paragraph(1).addComment("甲", "第二条(同 author)");
      doc.paragraph(2).addComment("乙", "第三条(不同 author)");
      doc.save(out);
    }
    String people = readPart(out, "word/people.xml");
    long personCount = countOccurrences(people, "<w15:person ");
    assertThat(personCount).isEqualTo(2); // 甲 + 乙,甲不重复
  }

  @Test
  void peopleXmlIdempotencySurvivesRoundTrip(@TempDir Path tmp) throws Exception {
    // reopen 后再加同 author,仍不重复(验证 ensurePart 读回现有 part + personExists 去重)
    Path file = writeDocxWithContent(tmp, "段A", "段B");
    Path out1 = tmp.resolve("out1.docx");
    try (Document doc = Docx.open(file)) {
      doc.paragraph(0).addComment("甲", "第一条");
      doc.save(out1);
    }
    Path out2 = tmp.resolve("out2.docx");
    try (Document doc = Docx.open(out1)) {
      doc.paragraph(1).addComment("甲", "reopen 后同 author");
      doc.save(out2);
    }
    String people = readPart(out2, "word/people.xml");
    assertThat(countOccurrences(people, "<w15:person ")).isEqualTo(1);
  }

  // ---------- AC2: w14:paraId 注入 ----------

  @Test
  void addCommentStampsParaIdOnCommentParagraph(@TempDir Path tmp) throws Exception {
    Path file = writeDocxWithContent(tmp, "正文");
    Path out = tmp.resolve("out.docx");
    try (Document doc = Docx.open(file)) {
      doc.paragraph(0).addComment("甲", "批注");
      doc.save(out);
    }
    // AC2: 批注内段落有 w14:paraId(8 位 hex)
    String comments = readPart(out, "word/comments.xml");
    Matcher m = Pattern.compile("paraId=\"([0-9A-Fa-f]{8})\"").matcher(comments);
    assertThat(m.find()).as("批注内段落应有 w14:paraId").isTrue();
  }

  @Test
  void replyStampsParaIdOnReplyParagraph(@TempDir Path tmp) throws Exception {
    Path file = writeDocxWithContent(tmp, "正文");
    Path out = tmp.resolve("out.docx");
    try (Document doc = Docx.open(file)) {
      Comment parent = doc.paragraph(0).addComment("甲", "父批注");
      doc.comments().reply(parent.id(), "乙", "回复");
      doc.save(out);
    }
    // reply 批注内段落也应有 paraId(reply-threads 已做,本测试确认收敛后仍正确)
    String comments = readPart(out, "word/comments.xml");
    // 两条批注(父+回复)各有 paraId → 至少 2 个 paraId
    assertThat(countOccurrences(comments, "paraId=\"")).isGreaterThanOrEqualTo(2);
  }

  // ---------- AC3: RSID 注入 ----------

  @Test
  void addCommentStampsRsidOnCommentParagraphAndReferenceRun(@TempDir Path tmp) throws Exception {
    Path file = writeDocxWithContent(tmp, "正文");
    Path out = tmp.resolve("out.docx");
    try (Document doc = Docx.open(file)) {
      doc.paragraph(0).addComment("甲", "批注");
      doc.save(out);
    }
    // AC3: 批注内段落有 w:rsidR + w:rsidRDefault
    String comments = readPart(out, "word/comments.xml");
    assertThat(comments).contains("w:rsidR=\"");
    assertThat(comments).contains("w:rsidRDefault=\"");
    // 正文引用 run 有 w:rsidR
    String document = readPart(out, "word/document.xml");
    assertThat(document).contains("w:rsidR=\"");
  }

  @Test
  void rsidIsRegisteredInSettingsXmlRsids(@TempDir Path tmp) throws Exception {
    Path file = writeDocxWithContent(tmp, "正文");
    Path out = tmp.resolve("out.docx");
    try (Document doc = Docx.open(file)) {
      doc.paragraph(0).addComment("甲", "批注");
      doc.save(out);
    }
    // AC3: settings.xml 的 rsids 段含该 RSID(rsidRoot)
    String settings = readPart(out, "word/settings.xml");
    assertThat(settings).contains("<w:rsids>");
    assertThat(settings).contains("<w:rsidRoot");
    // rsidRoot 的 val 应与 comments.xml 的 rsidR 一致(文档级单例)
    String comments = readPart(out, "word/comments.xml");
    String rsidInComment = extractFirst(comments, "w:rsidR=\"([0-9A-Fa-f]{8})\"");
    String rsidRoot = extractFirst(settings, "<w:rsidRoot w:val=\"([0-9A-Fa-f]{8})\"");
    assertThat(rsidRoot).as("settings.xml 应有 rsidRoot").isNotNull();
    assertThat(rsidInComment).as("comments.xml 应有 rsidR").isNotNull();
    assertThat(rsidRoot).isEqualTo(rsidInComment);
  }

  @Test
  void rsidIsDocumentLevelSingletonAcrossComments(@TempDir Path tmp) throws Exception {
    // 同一文档多次 addComment,RSID 相同(design §5 文档级单例)
    Path file = writeDocxWithContent(tmp, "段A", "段B");
    Path out = tmp.resolve("out.docx");
    try (Document doc = Docx.open(file)) {
      doc.paragraph(0).addComment("甲", "第一条");
      doc.paragraph(1).addComment("乙", "第二条");
      doc.save(out);
    }
    String comments = readPart(out, "word/comments.xml");
    String first = extractNth(comments, "w:rsidR=\"([0-9A-Fa-f]{8})\"", 0);
    String second = extractNth(comments, "w:rsidR=\"([0-9A-Fa-f]{8})\"", 1);
    assertThat(first).as("第一条批注应有 rsidR").isNotNull();
    assertThat(second).as("第二条批注应有 rsidR").isNotNull();
    assertThat(second).as("文档级 RSID 单例:同文档多次创作应相同").isEqualTo(first);
  }

  @Test
  void rsidSingletonSurvivesRoundTrip(@TempDir Path tmp) throws Exception {
    // save→reopen 后,新增批注的 RSID 仍是同一个(持久化在 settings.xml)
    Path file = writeDocxWithContent(tmp, "段A", "段B");
    Path out1 = tmp.resolve("out1.docx");
    try (Document doc = Docx.open(file)) {
      doc.paragraph(0).addComment("甲", "第一条");
      doc.save(out1);
    }
    Path out2 = tmp.resolve("out2.docx");
    try (Document doc = Docx.open(out1)) {
      doc.paragraph(1).addComment("乙", "reopen 后第二条");
      doc.save(out2);
    }
    String comments = readPart(out2, "word/comments.xml");
    String first = extractNth(comments, "w:rsidR=\"([0-9A-Fa-f]{8})\"", 0);
    String second = extractNth(comments, "w:rsidR=\"([0-9A-Fa-f]{8})\"", 1);
    assertThat(second).as("reopen 后 RSID 应持久化(仍是同一个)").isEqualTo(first);
  }

  @Test
  void differentDocumentsGetDifferentRsids(@TempDir Path tmp) throws Exception {
    // 两个独立文档,各自 addComment,RSID 应不同(概率上)
    Path out1 = tmp.resolve("d1.docx");
    Path out2 = tmp.resolve("d2.docx");
    try (Document doc = Docx.open(writeDocxWithContent(tmp, "A"))) {
      doc.paragraph(0).addComment("甲", "x");
      doc.save(out1);
    }
    try (Document doc = Docx.open(writeDocxWithContent2(tmp, "B"))) {
      doc.paragraph(0).addComment("甲", "y");
      doc.save(out2);
    }
    String r1 = extractFirst(readPart(out1, "word/comments.xml"), "w:rsidR=\"([0-9A-Fa-f]{8})\"");
    String r2 = extractFirst(readPart(out2, "word/comments.xml"), "w:rsidR=\"([0-9A-Fa-f]{8})\"");
    // 概率上不同(8 位 hex 空间大);若极端相同,重跑即可。不作为硬断言,仅 log
    assertThat(r1).isNotEqualTo(r2);
  }

  // ---------- AC6: tracked-changes 隔离 ----------

  @Test
  void trackedChangesAuthoringDoesNotInjectInfrastructure(@TempDir Path tmp) throws Exception {
    // AC6: tracked-changes 创作路径产出的节点无 RSID/people.xml(父任务 Q4 隔离)
    Path file = writeDocxWithContent(tmp, "正文");
    Path out = tmp.resolve("out.docx");
    try (Document doc = Docx.open(file)) {
      doc.paragraph(0).addInsertion("修订者", "插入内容");
      doc.save(out);
    }
    // tracked insertion 产出的 <w:ins> 内 run 无 w:rsidR(不接入 AuthoringInfra)
    String document = readPart(out, "word/document.xml");
    // 文档只有 tracked insertion,无批注 → 不应有 RSID 属性(addComment 才标)
    assertThat(document).doesNotContain("w:rsidR=\"");
    // 无批注 → 无 people.xml
    assertThat(zipHasEntry(out, "word/people.xml")).isFalse();
    // settings.xml 无 rsids 段(addComment 才注册)
    if (zipHasEntry(out, "word/settings.xml")) {
      assertThat(readPart(out, "word/settings.xml")).doesNotContain("<w:rsids>");
    }
  }

  // ---------- helpers ----------

  /** 用 POI 手搓一个含若干带内容段落的 docx(确定性,不依赖 Word)。 */
  private Path writeDocxWithContent(Path tmp, String... paragraphTexts) throws Exception {
    Path file = tmp.resolve("with-content.docx");
    try (org.apache.poi.xwpf.usermodel.XWPFDocument poi =
            new org.apache.poi.xwpf.usermodel.XWPFDocument();
        java.io.OutputStream o = Files.newOutputStream(file)) {
      for (String text : paragraphTexts) {
        poi.createParagraph().createRun().setText(text);
      }
      poi.write(o);
    }
    return file;
  }

  /** 第二个 writeDocxWithContent,避免同一 TempDir 文件名冲突(用于不同文档 RSID 对比)。 */
  private Path writeDocxWithContent2(Path tmp, String... paragraphTexts) throws Exception {
    Path file = tmp.resolve("with-content-2.docx");
    try (org.apache.poi.xwpf.usermodel.XWPFDocument poi =
            new org.apache.poi.xwpf.usermodel.XWPFDocument();
        java.io.OutputStream o = Files.newOutputStream(file)) {
      for (String text : paragraphTexts) {
        poi.createParagraph().createRun().setText(text);
      }
      poi.write(o);
    }
    return file;
  }

  /** 读 docx 内某个 part 的 UTF-8 文本内容。 */
  private static String readPart(Path docx, String partName) throws Exception {
    try (ZipFile zf = new ZipFile(docx.toFile())) {
      ZipEntry e = zf.getEntry(partName);
      if (e == null) {
        throw new AssertionError("docx 内无 " + partName);
      }
      return new String(
          zf.getInputStream(e).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  /** docx 是否含某 part。 */
  private static boolean zipHasEntry(Path docx, String partName) throws Exception {
    try (ZipFile zf = new ZipFile(docx.toFile())) {
      return zf.getEntry(partName) != null;
    }
  }

  /** 统计子串出现次数。 */
  private static long countOccurrences(String s, String sub) {
    long count = 0;
    int idx = 0;
    while ((idx = s.indexOf(sub, idx)) >= 0) {
      count++;
      idx += sub.length();
    }
    return count;
  }

  /** 提正则第一个捕获组的首个匹配;无返回 null。 */
  private static String extractFirst(String s, String regex) {
    return extractNth(s, regex, 0);
  }

  /** 提正则第 n 个匹配的第 1 捕获组;无返回 null。 */
  private static String extractNth(String s, String regex, int n) {
    Matcher m = Pattern.compile(regex).matcher(s);
    int found = 0;
    while (m.find()) {
      if (found == n) {
        return m.group(1);
      }
      found++;
    }
    return null;
  }
}
