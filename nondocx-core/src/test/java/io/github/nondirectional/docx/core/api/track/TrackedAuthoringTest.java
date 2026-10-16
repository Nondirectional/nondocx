package io.github.nondirectional.docx.core.api.track;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.exception.UnsupportedFeatureException;
import io.github.nondirectional.docx.core.api.image.ImageType;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import io.github.nondirectional.docx.core.api.text.Run;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRunTrackChange;

/**
 * tracked changes 创作侧(authoring)显式 API 的验收测试。
 *
 * <p>验证 {@code Paragraph.addInsertion} / {@code Paragraph.addDeletion} / {@code Run.replaceTracked}
 * 写出的文本类修订:能被 {@code TrackedChanges.list()} 读回、携带正确元数据(author/date/w:id)、与开关正交、round-trip 存活,且能被
 * accept/reject 命中。
 *
 * <p>这些测试覆盖 authoring 子任务的 AC1–AC6。
 */
class TrackedAuthoringTest {

  /** addInsertion 写出的 ins 能被 list() 读回,type/family/author/details 正确。 */
  @Test
  void addInsertionIsReadableByList(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("author-ins.docx");
    try (Document doc = Docx.open(newDoc())) {
      doc.paragraph(0).addInsertion("non", "新增内容");
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      List<TrackedChange> list = doc.trackedChanges().list();
      assertThat(list).hasSize(1);
      TrackedChange c = list.get(0);
      assertThat(c.type()).isEqualTo(TrackedChangeType.INS);
      assertThat(c.family()).isEqualTo(TrackedChangeFamily.TEXT);
      assertThat(c.author()).isEqualTo("non");
      assertThat(((TextChangeDetails) c.details()).text()).isEqualTo("新增内容");
    }
  }

  /** addInsertion 返回的新 run 可继续链式修改,且修改反映到读回的 details。 */
  @Test
  void addInsertionReturnsMutableRun(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("author-ins-run.docx");
    try (Document doc = Docx.open(newDoc())) {
      Run inserted = doc.paragraph(0).addInsertion("non", "可继续改");
      inserted.bold();
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      assertThat(((TextChangeDetails) doc.trackedChanges().list().get(0).details()).text())
          .isEqualTo("可继续改");
    }
  }

  /** addDeletion 把既有 run 标记为删除,能被 list() 读回为 del。 */
  @Test
  void addDeletionMarksExistingRun(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("author-del.docx");
    try (Document doc = Docx.open(newDocWithRun("要删的文本"))) {
      doc.paragraph(0).addDeletion("non", doc.paragraph(0).run(0));
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      List<TrackedChange> list = doc.trackedChanges().list();
      assertThat(list).hasSize(1);
      TrackedChange c = list.get(0);
      assertThat(c.type()).isEqualTo(TrackedChangeType.DEL);
      assertThat(((TextChangeDetails) c.details()).text()).isEqualTo("要删的文本");
    }
  }

  /** 跨多个普通 run 删除一段文本，保留前后顺序，并可通过 reject 恢复原文。 */
  @Test
  void deleteTrackedRangeAcrossRunsPreservesOrder(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("author-range-del.docx");
    try (Document doc = Docx.create()) {
      Paragraph paragraph = doc.addParagraph();
      paragraph.addRun("前缀");
      paragraph.addRun("目标甲");
      paragraph.addRun("目标乙");
      paragraph.addRun("后缀");
      paragraph.deleteTrackedRange("non", 2, 8);
      doc.save(file);
    }

    try (Document doc = Docx.open(file)) {
      assertThat(doc.trackedChanges().list()).hasSize(2);
      assertThat(doc.trackedChanges().list())
          .extracting(c -> ((TextChangeDetails) c.details()).text())
          .containsExactlyInAnyOrder("目标甲", "目标乙");
      assertThat(doc.trackedChanges().rejectAll()).isEqualTo(2);
      doc.save(file);
    }

    try (Document doc = Docx.open(file)) {
      assertThat(doc.paragraph(0).text()).isEqualTo("前缀目标甲目标乙后缀");
    }
  }

  /** 范围删除后接受修订只保留未命中的前缀和后缀。 */
  @Test
  void acceptTrackedRangeLeavesSurroundingText(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("author-range-accept.docx");
    try (Document doc = Docx.create()) {
      Paragraph paragraph = doc.addParagraph();
      paragraph.addRun("前");
      paragraph.addRun("删除");
      paragraph.addRun("后");
      paragraph.deleteTrackedRange("non", 1, 3);
      doc.save(file);
    }

    try (Document doc = Docx.open(file)) {
      assertThat(doc.trackedChanges().acceptAll()).isEqualTo(1);
      doc.save(file);
    }

    try (Document doc = Docx.open(file)) {
      assertThat(doc.paragraph(0).text()).isEqualTo("前后");
    }
  }

  /** 只删除一个普通 run 内部的部分文本，并保留边界字符属性。 */
  @Test
  void deleteTrackedRangeSplitsBoundaryRun(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("author-range-split.docx");
    try (Document doc = Docx.create()) {
      Paragraph paragraph = doc.addParagraph();
      paragraph.addRun("前缀目标后缀").bold();
      paragraph.deleteTrackedRange("non", 2, 4);
      assertThat(paragraph.text()).isEqualTo("前缀后缀");
      doc.save(file);
    }

    try (Document doc = Docx.open(file)) {
      assertThat(doc.trackedChanges().list()).hasSize(1);
      assertThat(doc.trackedChanges().rejectAll()).isEqualTo(1);
      doc.save(file);
    }

    try (Document doc = Docx.open(file)) {
      assertThat(doc.paragraph(0).text()).isEqualTo("前缀目标后缀");
      assertThat(doc.paragraph(0).runs()).hasSize(3);
      assertThat(doc.paragraph(0).runs()).extracting(Run::isBold).containsOnly(true);
    }
  }

  /** 跨 run 的首尾部分命中会分别切片，修订节点和普通 run 按原文顺序排列。 */
  @Test
  void deleteTrackedRangeHandlesPartialEdgesInOrder(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("partial-edges.docx");
    try (Document doc = Docx.create()) {
      Paragraph paragraph = doc.addParagraph();
      paragraph.addRun("甲目标").bold();
      paragraph.addRun("中间").italic();
      paragraph.addRun("目标乙").underline();

      paragraph.deleteTrackedRange("non", 1, 7);

      assertThat(paragraph.text()).doesNotContain("中间");
      assertThat(doc.trackedChanges().list())
          .extracting(TrackedChange::details)
          .extracting(details -> ((TextChangeDetails) details).text())
          .containsExactly("目标", "中间", "目标");
      assertThat(childLocalNames(paragraph.raw().getCTP()))
          .containsExactly("r", "del", "del", "del", "r");
      assertThat(paragraph.raw().getCTP().getRArray()).hasSize(2);
      assertThat(paragraph.raw().getCTP().getRArray(0).getRPr().sizeOfBArray() > 0).isTrue();
      assertThat(paragraph.raw().getCTP().getRArray(1).getRPr().sizeOfUArray() > 0).isTrue();
      assertThat(paragraph.raw().getCTP().getDelArray()).hasSize(3);
      assertThat(paragraph.raw().getCTP().getDelArray(0).getRList()).hasSize(1);
      assertThat(
              paragraph.raw().getCTP().getDelArray(0).getRList().get(0).getRPr().sizeOfBArray() > 0)
          .isTrue();
      assertThat(
              paragraph.raw().getCTP().getDelArray(1).getRList().get(0).getRPr().sizeOfIArray() > 0)
          .isTrue();
      assertThat(
              paragraph.raw().getCTP().getDelArray(2).getRList().get(0).getRPr().sizeOfUArray() > 0)
          .isTrue();
      for (CTRunTrackChange deletion : paragraph.raw().getCTP().getDelList()) {
        assertThat(deletion.getRList().get(0).sizeOfDelTextArray()).isEqualTo(1);
        assertThat(deletion.getRList().get(0).sizeOfTArray()).isZero();
      }
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      assertThat(doc.trackedChanges().list()).hasSize(3);
      assertThat(doc.trackedChanges().acceptAll()).isEqualTo(3);
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      assertThat(doc.paragraph(0).text()).isEqualTo("甲乙");
    }
  }

  /** 范围不能落在 UTF-16 代理对中间，合法的完整代理对删除后可往返。 */
  @Test
  void rejectsSurrogatePairSplit(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("surrogate-range.docx");
    try (Document doc = Docx.create()) {
      Paragraph paragraph = doc.addParagraph("A😀B");
      assertThatThrownBy(() -> paragraph.deleteTrackedRange("non", 1, 2))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("代理对");
      assertThat(paragraph.text()).isEqualTo("A😀B");
      paragraph.deleteTrackedRange("non", 1, 3);
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      assertThat(doc.trackedChanges().list())
          .extracting(TrackedChange::details)
          .extracting(details -> ((TextChangeDetails) details).text())
          .containsExactly("😀");
      assertThat(doc.trackedChanges().rejectAll()).isEqualTo(1);
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      assertThat(doc.paragraph(0).text()).isEqualTo("A😀B");
    }
  }

  /** tracked deletion 拒绝时保留目标首尾空白的 xml:space 属性。 */
  @Test
  void rejectsTrackedDeletionWithBoundarySpacesWithoutLoss(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("tracked-space.docx");
    String original = "前 目标  后";
    try (Document doc = Docx.create()) {
      Paragraph paragraph = doc.addParagraph();
      paragraph.addRun("前 ");
      paragraph.addRun("目标 ");
      paragraph.addRun(" 后");
      paragraph.deleteTrackedRange("non", 2, 5);
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      assertThat(doc.paragraph(0).text()).isEqualTo("前  后");
      assertThat(doc.trackedChanges().rejectAll()).isEqualTo(1);
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      assertThat(doc.paragraph(0).text()).isEqualTo(original);
    }
  }

  /** hyperlink、图片、域均显式拒绝，且失败不会改动正文或已存在的修订。 */
  @Test
  void rejectsUnsupportedInlineContentWithoutMutation() {
    Paragraph hyperlinkParagraph = Docx.create().addParagraph();
    hyperlinkParagraph.addRun("前");
    hyperlinkParagraph.addHyperlink("链接", "https://example.com");
    hyperlinkParagraph.addRun("后");
    assertUnsupportedWithoutMutation(hyperlinkParagraph, 0, 1, "前链接后");

    Paragraph imageParagraph = Docx.create().addParagraph();
    imageParagraph.addRun("前");
    imageParagraph.addImage(solidPng(), ImageType.PNG, 1, 1);
    imageParagraph.addRun("后");
    assertUnsupportedWithoutMutation(imageParagraph, 0, 1, "前后");

    Paragraph fieldParagraph = Docx.create().addParagraph();
    fieldParagraph.addSimpleField("PAGE");
    fieldParagraph.addRun("后");
    assertUnsupportedWithoutMutation(fieldParagraph, 0, 1, "后");
  }

  private static void assertUnsupportedWithoutMutation(
      Paragraph paragraph, int start, int end, String expectedText) {
    assertThatThrownBy(() -> paragraph.deleteTrackedRange("non", start, end))
        .isInstanceOf(UnsupportedFeatureException.class);
    assertThat(paragraph.text()).isEqualTo(expectedText);
    assertThat(paragraph.raw().getCTP().sizeOfDelArray()).isZero();
  }

  private static List<String> childLocalNames(CTP ctp) {
    List<String> names = new ArrayList<>();
    org.apache.xmlbeans.XmlCursor cursor = ctp.newCursor();
    try {
      if (cursor.toFirstChild()) {
        do {
          names.add(cursor.getName().getLocalPart());
        } while (cursor.toNextSibling());
      }
    } finally {
      cursor.dispose();
    }
    return names;
  }

  private static byte[] solidPng() {
    BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setColor(Color.BLACK);
      graphics.fillRect(0, 0, 1, 1);
    } finally {
      graphics.dispose();
    }
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      javax.imageio.ImageIO.write(image, "png", out);
      return out.toByteArray();
    } catch (java.io.IOException e) {
      throw new AssertionError(e);
    }
  }

  /** addDeletion 对不属于本段落的 run 抛异常(防误用)。 */
  @Test
  void addDeletionRejectsForeignRun(@TempDir Path tmp) throws Exception {
    try (Document doc = Docx.open(newDoc())) {
      doc.addParagraph().addRun("第二段");
      Paragraph p0 = doc.paragraph(0);
      Run foreign = doc.paragraph(1).run(0);
      assertThatThrownBy(() -> p0.addDeletion("non", foreign))
          .isInstanceOf(java.util.NoSuchElementException.class);
    }
  }

  /** replaceTracked 生成 del + ins 两条修订,新 run 复制源 run 文本样式。 */
  @Test
  void replaceTrackedProducesDelAndIns(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("author-replace.docx");
    try (Document doc = Docx.open(newDocWithRun("旧文本"))) {
      Run replacement = doc.paragraph(0).run(0).bold().replaceTracked("non", "新文本");
      assertThat(replacement.text()).isEqualTo("新文本");
      assertThat(replacement.isBold()).isTrue(); // 样式已复制
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      List<TrackedChange> list = doc.trackedChanges().list();
      assertThat(list).hasSize(2);
      // del(旧文本) + ins(新文本)
      assertThat(list)
          .extracting(TrackedChange::type)
          .containsExactlyInAnyOrder(TrackedChangeType.DEL, TrackedChangeType.INS);
    }
  }

  /** 修订元数据:date 自动写入(非 null),w:id 自动分配且两条不冲突。 */
  @Test
  void writesDateAndDistinctRevisionIds(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("author-meta.docx");
    try (Document doc = Docx.open(newDoc())) {
      Paragraph p = doc.paragraph(0);
      p.addInsertion("non", "第一条");
      p.addInsertion("non", "第二条");
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      List<TrackedChange> list = doc.trackedChanges().list();
      assertThat(list).hasSize(2);
      for (TrackedChange c : list) {
        assertThat(c.date()).isNotNull(); // date 自动写入
      }
      // 两条 w:id 不同(底层原始 id,通过 raw() 取)
      long id0 = list.get(0).raw().getId().longValue();
      long id1 = list.get(1).raw().getId().longValue();
      assertThat(id0).isNotEqualTo(id1);
    }
  }

  /** 与开关正交:开关未开启时,显式 authoring 仍写出修订。 */
  @Test
  void authoringIsOrthogonalToTrackSwitch(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("author-orthogonal.docx");
    try (Document doc = Docx.open(newDoc())) {
      assertThat(doc.trackedChanges().enabled()).isFalse(); // 开关未开启
      doc.paragraph(0).addInsertion("non", "仍写出修订");
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      // 开关仍关,但修订确已写入
      assertThat(doc.trackedChanges().enabled()).isFalse();
      assertThat(doc.trackedChanges().list()).hasSize(1);
    }
  }

  /** 普通 addRun 不带修订(authoring 不污染普通写路径)。 */
  @Test
  void plainAddRunProducesNoRevision(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("author-plain.docx");
    try (Document doc = Docx.open(newDoc())) {
      doc.paragraph(0).addRun("普通文本");
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      assertThat(doc.trackedChanges().list()).isEmpty();
    }
  }

  /** authoring 写出的修订能被 accept-text 命中(跨子任务集成)。 */
  @Test
  void authoredInsertionAcceptableById(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("author-accept.docx");
    try (Document doc = Docx.open(newDoc())) {
      doc.paragraph(0).addInsertion("non", "可接受");
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      String id = doc.trackedChanges().list().get(0).id();
      doc.trackedChanges().accept(id);
      assertThat(doc.trackedChanges().list()).isEmpty();
    }
  }

  /** author 非法(null / 空白)抛 IllegalArgumentException。 */
  @Test
  void rejectsBlankAndNullAuthor(@TempDir Path tmp) throws Exception {
    try (Document doc = Docx.open(newDoc())) {
      Paragraph p = doc.paragraph(0);
      assertThatThrownBy(() -> p.addInsertion(null, "x"))
          .isInstanceOf(java.lang.IllegalArgumentException.class);
      assertThatThrownBy(() -> p.addInsertion("  ", "x"))
          .isInstanceOf(java.lang.IllegalArgumentException.class);
    }
  }

  // ---------- fixture ----------

  /** 一个空段落的新文档。 */
  private static Path newDoc() throws Exception {
    Path tmp = java.nio.file.Files.createTempFile("authoring-", ".docx");
    try (org.apache.poi.xwpf.usermodel.XWPFDocument poi =
        new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
      poi.createParagraph(); // 一个空段落
      try (var out = java.nio.file.Files.newOutputStream(tmp)) {
        poi.write(out);
      }
    }
    return tmp;
  }

  /** 一个含单个带文本 run 的段落的文档。 */
  private static Path newDocWithRun(String text) throws Exception {
    Path tmp = java.nio.file.Files.createTempFile("authoring-run-", ".docx");
    try (org.apache.poi.xwpf.usermodel.XWPFDocument poi =
        new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
      poi.createParagraph().createRun().setText(text);
      try (var out = java.nio.file.Files.newOutputStream(tmp)) {
        poi.write(out);
      }
    }
    return tmp;
  }
}
