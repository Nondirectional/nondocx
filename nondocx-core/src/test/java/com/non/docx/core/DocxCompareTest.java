package com.non.docx.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.non.docx.core.api.BodyElement;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.table.Table;
import com.non.docx.core.api.text.Hyperlink;
import com.non.docx.core.api.text.Paragraph;
import com.non.docx.core.api.track.TextChangeDetails;
import com.non.docx.core.api.track.TrackedChange;
import com.non.docx.core.api.track.TrackedChangeType;
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

  private static void writeSingleParagraph(Path file, String text) throws Exception {
    try (Document doc = Docx.create()) {
      doc.addParagraph(text);
      doc.save(file);
    }
  }
}
