package io.github.nondirectional.docx.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nondirectional.docx.core.api.BodyElement;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.table.Table;
import io.github.nondirectional.docx.core.api.text.Hyperlink;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import io.github.nondirectional.docx.core.api.text.Run;
import io.github.nondirectional.docx.core.api.track.TextChangeDetails;
import io.github.nondirectional.docx.core.api.track.TrackedChange;
import io.github.nondirectional.docx.core.api.track.TrackedChangeType;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** compare MVP 的回归测试。 */
class DocxCompareTest {

  @Test
  void compareNoDiffReturnsEquivalentOldBaseline(@TempDir Path tmp) throws Exception {
    Path oldFile = tmp.resolve("old.docx");
    Path newFile = tmp.resolve("new.docx");
    try (Document oldDoc = Docx.create()) {
      oldDoc.addParagraph("第一段");
      oldDoc.addParagraph("第二段");
      oldDoc.save(oldFile);
      oldDoc.save(newFile);
    }

    try (Document result = Docx.compare(oldFile, newFile);
        Document reopenedOld = Docx.open(oldFile)) {
      assertThat(result).isEqualTo(reopenedOld);
      assertThat(result.trackedChanges().list()).isEmpty();
    }
  }

  @Test
  void compareUsesDefaultAuthorWhenNotProvided(@TempDir Path tmp) throws Exception {
    Path oldFile = tmp.resolve("old.docx");
    Path newFile = tmp.resolve("new.docx");
    writeSingleParagraph(oldFile, "甲");
    writeSingleParagraph(newFile, "甲乙");

    try (Document result = Docx.compare(oldFile, newFile)) {
      List<TrackedChange> changes = result.trackedChanges().list();
      assertThat(changes).hasSize(1);
      assertThat(changes.get(0).author()).isEqualTo(Docx.DEFAULT_COMPARE_AUTHOR);
    }
  }

  @Test
  void compareUsesExplicitAuthorWhenProvided(@TempDir Path tmp) throws Exception {
    Path oldFile = tmp.resolve("old.docx");
    Path newFile = tmp.resolve("new.docx");
    writeSingleParagraph(oldFile, "甲");
    writeSingleParagraph(newFile, "乙");

    try (Document result = Docx.compare(oldFile, newFile, "业务用户")) {
      assertThat(result.trackedChanges().list())
          .extracting(TrackedChange::author)
          .containsOnly("业务用户");
    }
  }

  @Test
  void compareProducesDelAndInsForReplacement(@TempDir Path tmp) throws Exception {
    Path oldFile = tmp.resolve("old.docx");
    Path newFile = tmp.resolve("new.docx");
    writeSingleParagraph(oldFile, "中文A");
    writeSingleParagraph(newFile, "中文B");

    try (Document result = Docx.compare(oldFile, newFile, "比较器")) {
      List<TrackedChange> changes = result.trackedChanges().list();
      assertThat(changes).hasSize(2);
      assertThat(changes)
          .extracting(TrackedChange::type)
          .containsExactlyInAnyOrder(TrackedChangeType.DEL, TrackedChangeType.INS);
      assertThat(changes)
          .extracting(c -> ((TextChangeDetails) c.details()).text())
          .containsExactlyInAnyOrder("A", "B");
    }
  }

  @Test
  void compareInsertsParagraphBeforeBodyAnchorAfterTable(@TempDir Path tmp) throws Exception {
    Path oldFile = tmp.resolve("old.docx");
    Path newFile = tmp.resolve("new.docx");
    try (Document oldDoc = Docx.create()) {
      oldDoc.addParagraph("段1");
      oldDoc.addTable().row(r -> r.cell("表格单元格"));
      oldDoc.addParagraph("段2");
      oldDoc.save(oldFile);
    }
    try (Document newDoc = Docx.create()) {
      newDoc.addParagraph("段1");
      newDoc.addParagraph("新增段");
      newDoc.addParagraph("段2");
      newDoc.save(newFile);
    }

    try (Document result = Docx.compare(oldFile, newFile)) {
      List<BodyElement> body = result.bodyElements();
      assertThat(body).hasSize(4);
      assertThat(((Paragraph) body.get(0)).text()).isEqualTo("段1");
      assertThat(body.get(1)).isInstanceOf(Table.class);
      Paragraph inserted = (Paragraph) body.get(2);
      assertThat(inserted.inlineElements()).hasSize(1);
      assertThat(result.trackedChanges().list()).hasSize(1);
      TrackedChange change = result.trackedChanges().list().get(0);
      assertThat(change.type()).isEqualTo(TrackedChangeType.INS);
      assertThat(((TextChangeDetails) change.details()).text()).isEqualTo("新增段");
      assertThat(((Paragraph) body.get(3)).text()).isEqualTo("段2");
    }
  }

  @Test
  void compareKeepsOldTableUntouched(@TempDir Path tmp) throws Exception {
    Path oldFile = tmp.resolve("old.docx");
    Path newFile = tmp.resolve("new.docx");
    try (Document oldDoc = Docx.create()) {
      oldDoc.addParagraph("前");
      oldDoc.addTable().row(r -> r.cell("旧表格"));
      oldDoc.addParagraph("后");
      oldDoc.save(oldFile);
    }
    try (Document newDoc = Docx.create()) {
      newDoc.addParagraph("前");
      newDoc.addParagraph("后修改");
      newDoc.save(newFile);
    }

    try (Document result = Docx.compare(oldFile, newFile)) {
      assertThat(result.tables()).hasSize(1);
      assertThat(result.tables().get(0).row(0).cell(0).text()).isEqualTo("旧表格");
    }
  }

  @Test
  void compareSkipsChangedHyperlinkParagraph(@TempDir Path tmp) throws Exception {
    Path oldFile = tmp.resolve("old.docx");
    Path newFile = tmp.resolve("new.docx");
    try (Document oldDoc = Docx.create()) {
      oldDoc.addParagraph().addHyperlink("旧链接", "https://old.example");
      oldDoc.save(oldFile);
    }
    try (Document newDoc = Docx.create()) {
      newDoc.addParagraph().addHyperlink("新链接", "https://new.example");
      newDoc.save(newFile);
    }

    try (Document result = Docx.compare(oldFile, newFile)) {
      assertThat(result.paragraph(0).inlineElement(0)).isInstanceOf(Hyperlink.class);
      Hyperlink link = (Hyperlink) result.paragraph(0).inlineElement(0);
      assertThat(link.text()).isEqualTo("旧链接");
      assertThat(link.url()).isEqualTo("https://old.example");
      assertThat(result.trackedChanges().list()).isEmpty();
    }
  }

  @Test
  void compareRejectsBlankAuthor(@TempDir Path tmp) throws Exception {
    Path oldFile = tmp.resolve("old.docx");
    Path newFile = tmp.resolve("new.docx");
    writeSingleParagraph(oldFile, "甲");
    writeSingleParagraph(newFile, "乙");

    assertThatThrownBy(() -> Docx.compare(oldFile, newFile, "  "))
        .isInstanceOf(java.lang.IllegalArgumentException.class)
        .hasMessageContaining("author");
  }

  @Test
  void comparePreservesNewStyleForInsertedTextInUniformParagraph(@TempDir Path tmp)
      throws Exception {
    Path oldFile = tmp.resolve("old.docx");
    Path newFile = tmp.resolve("new.docx");
    try (Document oldDoc = Docx.create()) {
      oldDoc.addParagraph().addRun("甲").bold();
      oldDoc.save(oldFile);
    }
    try (Document newDoc = Docx.create()) {
      newDoc.addParagraph().addRun("甲乙").italic().color("FF0000");
      newDoc.save(newFile);
    }

    Path resultFile = tmp.resolve("result.docx");
    try (Document result = Docx.compare(oldFile, newFile, "比较器")) {
      assertThat(result.trackedChanges().list())
          .extracting(TrackedChange::type)
          .containsExactly(TrackedChangeType.INS);
      result.save(resultFile);
    }
    try (Document reopened = Docx.open(resultFile)) {
      Run equal = reopened.paragraph(0).run(0);
      assertThat(equal.text()).isEqualTo("甲");
      assertThat(equal.isBold()).isTrue();

      String insId = reopened.trackedChanges().list().get(0).id();
      reopened.trackedChanges().accept(insId);
      reopened.save(resultFile);
    }
    try (Document accepted = Docx.open(resultFile)) {
      assertThat(accepted.paragraph(0).text()).isEqualTo("甲乙");
      assertThat(accepted.paragraph(0).run(0).text()).isEqualTo("甲");
      assertThat(accepted.paragraph(0).run(0).isBold()).isTrue();
      assertThat(accepted.paragraph(0).run(1).text()).isEqualTo("乙");
      assertThat(accepted.paragraph(0).run(1).isItalic()).isTrue();
      assertThat(accepted.paragraph(0).run(1).color()).isEqualTo("FF0000");
    }
  }

  @Test
  void comparePreservesOldStyleForDeletedTextInUniformParagraph(@TempDir Path tmp)
      throws Exception {
    Path oldFile = tmp.resolve("old.docx");
    Path newFile = tmp.resolve("new.docx");
    try (Document oldDoc = Docx.create()) {
      oldDoc.addParagraph().addRun("甲乙").bold().fontSize(16);
      oldDoc.save(oldFile);
    }
    try (Document newDoc = Docx.create()) {
      newDoc.addParagraph().addRun("甲").italic();
      newDoc.save(newFile);
    }

    Path resultFile = tmp.resolve("result.docx");
    try (Document result = Docx.compare(oldFile, newFile, "比较器")) {
      assertThat(result.trackedChanges().list())
          .extracting(TrackedChange::type)
          .containsExactly(TrackedChangeType.DEL);
      result.save(resultFile);
    }
    try (Document reopened = Docx.open(resultFile)) {
      Run equal = reopened.paragraph(0).run(0);
      assertThat(equal.text()).isEqualTo("甲");
      assertThat(equal.isBold()).isTrue();
      assertThat(equal.fontSize()).isEqualTo(16);

      String delId = reopened.trackedChanges().list().get(0).id();
      reopened.trackedChanges().reject(delId);
      reopened.save(resultFile);
    }
    try (Document restored = Docx.open(resultFile)) {
      assertThat(restored.paragraph(0).text()).isEqualTo("甲乙");
      assertThat(restored.paragraph(0).runs()).allMatch(r -> r.isBold() && r.fontSize() == 16);
    }
  }

  @Test
  void compareReplacementUsesOldStyleForDelAndNewStyleForIns(@TempDir Path tmp) throws Exception {
    Path oldFile = tmp.resolve("old.docx");
    Path newFile = tmp.resolve("new.docx");
    try (Document oldDoc = Docx.create()) {
      oldDoc.addParagraph().addRun("甲").bold();
      oldDoc.save(oldFile);
    }
    try (Document newDoc = Docx.create()) {
      newDoc.addParagraph().addRun("乙").italic().color("0000FF");
      newDoc.save(newFile);
    }

    Path resultFile = tmp.resolve("result.docx");
    try (Document result = Docx.compare(oldFile, newFile, "比较器")) {
      assertThat(result.trackedChanges().list())
          .extracting(TrackedChange::type)
          .containsExactlyInAnyOrder(TrackedChangeType.DEL, TrackedChangeType.INS);
      result.save(resultFile);
    }
    try (Document reopened = Docx.open(resultFile)) {
      List<TrackedChange> changes = reopened.trackedChanges().list();
      String delId =
          changes.stream()
              .filter(c -> c.type() == TrackedChangeType.DEL)
              .findFirst()
              .orElseThrow()
              .id();
      reopened.trackedChanges().reject(delId);
      reopened.save(resultFile);
    }
    try (Document restored = Docx.open(resultFile)) {
      assertThat(restored.paragraph(0).text()).isEqualTo("甲乙");
      assertThat(restored.paragraph(0).run(0).text()).isEqualTo("甲");
      assertThat(restored.paragraph(0).run(0).isBold()).isTrue();
    }

    try (Document reopened = Docx.open(resultFile)) {
      String insId =
          reopened.trackedChanges().list().stream()
              .filter(c -> c.type() == TrackedChangeType.INS)
              .findFirst()
              .orElseThrow()
              .id();
      reopened.trackedChanges().accept(insId);
      reopened.save(resultFile);
    }
    try (Document accepted = Docx.open(resultFile)) {
      assertThat(accepted.paragraph(0).text()).isEqualTo("甲乙");
      assertThat(accepted.paragraph(0).runs()).anyMatch(r -> r.text().contains("乙"));
      assertThat(accepted.paragraph(0).runs()).anyMatch(r -> r.isItalic());
      assertThat(accepted.paragraph(0).runs()).extracting(Run::color).contains("0000FF");
    }
  }

  @Test
  void compareInsertedParagraphUsesNewUniformStyle(@TempDir Path tmp) throws Exception {
    Path oldFile = tmp.resolve("old.docx");
    Path newFile = tmp.resolve("new.docx");
    try (Document oldDoc = Docx.create()) {
      oldDoc.addParagraph("前");
      oldDoc.addParagraph("后");
      oldDoc.save(oldFile);
    }
    try (Document newDoc = Docx.create()) {
      newDoc.addParagraph("前");
      Paragraph inserted = newDoc.addParagraph();
      inserted.addRun("新").italic();
      inserted.addRun("增").italic();
      newDoc.addParagraph("后");
      newDoc.save(newFile);
    }

    Path resultFile = tmp.resolve("result.docx");
    try (Document result = Docx.compare(oldFile, newFile, "比较器")) {
      result.save(resultFile);
    }
    try (Document reopened = Docx.open(resultFile)) {
      String insId = reopened.trackedChanges().list().get(0).id();
      reopened.trackedChanges().accept(insId);
      reopened.save(resultFile);
    }
    try (Document accepted = Docx.open(resultFile)) {
      assertThat(accepted.paragraph(1).text()).isEqualTo("新增");
      assertThat(accepted.paragraph(1).runs()).allMatch(Run::isItalic);
    }
  }

  @Test
  void compareDeletedParagraphUsesOldUniformStyle(@TempDir Path tmp) throws Exception {
    Path oldFile = tmp.resolve("old.docx");
    Path newFile = tmp.resolve("new.docx");
    try (Document oldDoc = Docx.create()) {
      oldDoc.addParagraph("前");
      Paragraph removed = oldDoc.addParagraph();
      removed.addRun("删").bold();
      removed.addRun("除").bold();
      oldDoc.addParagraph("后");
      oldDoc.save(oldFile);
    }
    try (Document newDoc = Docx.create()) {
      newDoc.addParagraph("前");
      newDoc.addParagraph("后");
      newDoc.save(newFile);
    }

    Path resultFile = tmp.resolve("result.docx");
    try (Document result = Docx.compare(oldFile, newFile, "比较器")) {
      result.save(resultFile);
    }
    try (Document reopened = Docx.open(resultFile)) {
      String delId = reopened.trackedChanges().list().get(0).id();
      reopened.trackedChanges().reject(delId);
      reopened.save(resultFile);
    }
    try (Document restored = Docx.open(resultFile)) {
      assertThat(restored.paragraph(1).text()).isEqualTo("删除");
      assertThat(restored.paragraph(1).runs()).allMatch(Run::isBold);
    }
  }

  @Test
  void compareUniformParagraphMayContainMultipleEquivalentRuns(@TempDir Path tmp) throws Exception {
    Path oldFile = tmp.resolve("old.docx");
    Path newFile = tmp.resolve("new.docx");
    try (Document oldDoc = Docx.create()) {
      Paragraph paragraph = oldDoc.addParagraph();
      paragraph.addRun("甲").bold();
      paragraph.addRun("乙").bold();
      oldDoc.save(oldFile);
    }
    try (Document newDoc = Docx.create()) {
      Paragraph paragraph = newDoc.addParagraph();
      paragraph.addRun("甲").bold();
      paragraph.addRun("乙").bold();
      paragraph.addRun("丙").bold();
      newDoc.save(newFile);
    }

    Path resultFile = tmp.resolve("result.docx");
    try (Document result = Docx.compare(oldFile, newFile, "比较器")) {
      result.save(resultFile);
    }
    try (Document reopened = Docx.open(resultFile)) {
      String insId = reopened.trackedChanges().list().get(0).id();
      reopened.trackedChanges().accept(insId);
      reopened.save(resultFile);
    }
    try (Document accepted = Docx.open(resultFile)) {
      assertThat(accepted.paragraph(0).text()).isEqualTo("甲乙丙");
      assertThat(accepted.paragraph(0).runs()).allMatch(Run::isBold);
    }
  }

  @Test
  void compareMixedStyleParagraphStillSkipsRewrite(@TempDir Path tmp) throws Exception {
    Path oldFile = tmp.resolve("old.docx");
    Path newFile = tmp.resolve("new.docx");
    try (Document oldDoc = Docx.create()) {
      Paragraph paragraph = oldDoc.addParagraph();
      paragraph.addRun("甲").bold();
      paragraph.addRun("乙").italic();
      oldDoc.save(oldFile);
    }
    try (Document newDoc = Docx.create()) {
      Paragraph paragraph = newDoc.addParagraph();
      paragraph.addRun("甲").bold();
      paragraph.addRun("丙").italic();
      newDoc.save(newFile);
    }

    try (Document result = Docx.compare(oldFile, newFile, "比较器")) {
      assertThat(result.trackedChanges().list()).isEmpty();
      assertThat(result.paragraph(0).runs()).hasSize(2);
      assertThat(result.paragraph(0).run(0).text()).isEqualTo("甲");
      assertThat(result.paragraph(0).run(0).isBold()).isTrue();
      assertThat(result.paragraph(0).run(1).text()).isEqualTo("乙");
      assertThat(result.paragraph(0).run(1).isItalic()).isTrue();
    }
  }

  @Test
  void compareStyleOnlyChangeStillProducesNoDiff(@TempDir Path tmp) throws Exception {
    Path oldFile = tmp.resolve("old.docx");
    Path newFile = tmp.resolve("new.docx");
    try (Document oldDoc = Docx.create()) {
      oldDoc.addParagraph().addRun("相同文本").bold();
      oldDoc.save(oldFile);
    }
    try (Document newDoc = Docx.create()) {
      newDoc.addParagraph().addRun("相同文本").italic().color("FF0000");
      newDoc.save(newFile);
    }

    try (Document result = Docx.compare(oldFile, newFile, "比较器");
        Document reopenedOld = Docx.open(oldFile)) {
      assertThat(result.trackedChanges().list()).isEmpty();
      assertThat(result).isEqualTo(reopenedOld);
    }
  }

  @Test
  void compareResultMayCoalesceRunBoundariesButKeepsVisualStyle(@TempDir Path tmp)
      throws Exception {
    Path oldFile = tmp.resolve("old.docx");
    Path newFile = tmp.resolve("new.docx");
    try (Document oldDoc = Docx.create()) {
      Paragraph paragraph = oldDoc.addParagraph();
      paragraph.addRun("甲").bold();
      paragraph.addRun("乙").bold();
      oldDoc.save(oldFile);
    }
    try (Document newDoc = Docx.create()) {
      Paragraph paragraph = newDoc.addParagraph();
      paragraph.addRun("甲乙丙").bold();
      newDoc.save(newFile);
    }

    Path resultFile = tmp.resolve("result.docx");
    try (Document result = Docx.compare(oldFile, newFile, "比较器")) {
      result.save(resultFile);
    }
    try (Document reopened = Docx.open(resultFile)) {
      String insId = reopened.trackedChanges().list().get(0).id();
      reopened.trackedChanges().accept(insId);
      reopened.save(resultFile);
    }
    try (Document accepted = Docx.open(resultFile)) {
      List<Run> runs = accepted.paragraph(0).runs();
      assertThat(runs).isNotEmpty();
      assertThat(accepted.paragraph(0).text()).isEqualTo("甲乙丙");
      assertThat(runs).allMatch(Run::isBold);
    }
  }

  private static void writeSingleParagraph(Path file, String text) throws Exception {
    try (Document doc = Docx.create()) {
      doc.addParagraph(text);
      doc.save(file);
    }
  }
}
