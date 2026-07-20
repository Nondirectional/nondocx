package io.github.nondirectional.docx.toolkit.ref;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.header.Header;
import io.github.nondirectional.docx.core.api.table.Cell;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import io.github.nondirectional.docx.core.api.text.Run;
import io.github.nondirectional.docx.core.internal.poi.AuthoringInfra;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ElementResolverTest {

  @TempDir Path tempDir;

  @Test
  void sessionParagraphRefSurvivesInsertionBeforeTarget() {
    try (Document doc = Docx.create()) {
      Paragraph target = doc.addParagraph();
      target.addRun("target");
      doc.addParagraph().addRun("tail");
      ElementResolver resolver = new ElementResolver(new DocumentRef("conversation", 1L), doc);
      ParagraphRef ref = resolver.reference(target);

      doc.insertParagraph(0).addRun("new");

      assertThat(resolver.resolve(ref).raw()).isSameAs(target.raw());
      assertThat(resolver.resolve(ref).text()).isEqualTo("target");
    }
  }

  @Test
  void removedParagraphReturnsElementRemoved() {
    try (Document doc = Docx.create()) {
      Paragraph target = doc.addParagraph();
      target.addRun("target");
      ElementResolver resolver = new ElementResolver(new DocumentRef("conversation", 1L), doc);
      ParagraphRef ref = resolver.reference(target);

      doc.removeParagraph(0);

      assertThatThrownBy(() -> resolver.resolve(ref))
          .isInstanceOfSatisfying(
              RefResolutionException.class,
              e -> assertThat(e.code()).isEqualTo(RefResolutionCode.ELEMENT_REMOVED));
    }
  }

  @Test
  void sessionRefFromOldGenerationIsRejected() {
    try (Document doc = Docx.create()) {
      Paragraph target = doc.addParagraph();
      target.addRun("target");
      ParagraphRef ref =
          new ElementResolver(new DocumentRef("conversation", 1L), doc).reference(target);
      ElementResolver reopened = new ElementResolver(new DocumentRef("conversation", 2L), doc);

      assertThatThrownBy(() -> reopened.resolve(ref))
          .isInstanceOfSatisfying(
              RefResolutionException.class,
              e -> assertThat(e.code()).isEqualTo(RefResolutionCode.GENERATION_MISMATCH));
    }
  }

  @Test
  void persistentParagraphRefResolvesAfterSaveAndReopen() {
    Path path = tempDir.resolve("persistent-ref.docx");
    ParagraphRef ref;
    try (Document original = Docx.create()) {
      Paragraph paragraph = original.addParagraph();
      paragraph.addRun("persistent");
      AuthoringInfra.setParaId(paragraph.raw(), "00A1B2C3");
      ref = new ElementResolver(new DocumentRef("conversation", 1L), original).reference(paragraph);
      assertThat(ref.stability()).isEqualTo(RefStability.PERSISTENT);
      original.save(path);
    }

    try (Document reopened = Docx.open(path)) {
      ElementResolver resolver = new ElementResolver(new DocumentRef("conversation", 2L), reopened);

      assertThat(resolver.resolve(ref).text()).isEqualTo("persistent");
    }
  }

  @Test
  void issuingSessionRefDoesNotModifyParagraphXml() {
    try (Document doc = Docx.create()) {
      Paragraph paragraph = doc.addParagraph();
      paragraph.addRun("plain");
      String before = paragraph.raw().getCTP().xmlText();

      ParagraphRef ref =
          new ElementResolver(new DocumentRef("conversation", 1L), doc).reference(paragraph);

      assertThat(ref.stability()).isEqualTo(RefStability.SESSION);
      assertThat(paragraph.raw().getCTP().xmlText()).isEqualTo(before);
    }
  }

  @Test
  void removedRunReturnsElementRemoved() {
    try (Document doc = Docx.create()) {
      Paragraph paragraph = doc.addParagraph();
      Run run = paragraph.addRun("target");
      ElementResolver resolver = new ElementResolver(new DocumentRef("conversation", 1L), doc);
      RunRef ref = resolver.reference(run);

      paragraph.removeInlineElement(0);

      assertThatThrownBy(() -> resolver.resolve(ref))
          .isInstanceOfSatisfying(
              RefResolutionException.class,
              e -> assertThat(e.code()).isEqualTo(RefResolutionCode.ELEMENT_REMOVED));
    }
  }

  @Test
  void removedCellReturnsElementRemoved() {
    try (Document doc = Docx.create()) {
      var row = doc.addTable().addRow();
      Cell cell = row.addCell();
      ElementResolver resolver = new ElementResolver(new DocumentRef("conversation", 1L), doc);
      CellRef ref = resolver.reference(cell);

      row.removeCell(0);

      assertThatThrownBy(() -> resolver.resolve(ref))
          .isInstanceOfSatisfying(
              RefResolutionException.class,
              e -> assertThat(e.code()).isEqualTo(RefResolutionCode.ELEMENT_REMOVED));
    }
  }

  @Test
  void removedTableReturnsElementRemoved() {
    try (Document doc = Docx.create()) {
      var table = doc.addTable();
      table.addRow().addCell();
      ElementResolver resolver = new ElementResolver(new DocumentRef("conversation", 1L), doc);
      TableRef ref = resolver.reference(table);

      int bodyPosition = doc.raw().getPosOfTable(table.raw());
      doc.raw().removeBodyElement(bodyPosition);

      assertThatThrownBy(() -> resolver.resolve(ref))
          .isInstanceOfSatisfying(
              RefResolutionException.class,
              e -> assertThat(e.code()).isEqualTo(RefResolutionCode.ELEMENT_REMOVED));
    }
  }

  @Test
  void headerRefResolvesByDelegateIdentity() {
    try (Document doc = Docx.create()) {
      Header header = doc.ensureHeader();
      header.addParagraph().addRun("header");
      ElementResolver resolver = new ElementResolver(new DocumentRef("conversation", 1L), doc);
      HeaderFooterRef ref = resolver.reference(header);

      assertThat(resolver.resolveHeader(ref).raw()).isSameAs(header.raw());
    }
  }
}
