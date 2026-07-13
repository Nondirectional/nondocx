package com.non.docx.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.style.Alignment;
import com.non.docx.toolkit.DocxToolkit;
import com.non.docx.toolkit.result.ToolResultParser;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentSessionToolsTest {

  @Test
  void savesCurrentDocumentAfterQualityCheck(@TempDir Path temp) throws Exception {
    Path file = temp.resolve("current.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("正文");
      doc.save(file);
    }
    DocxToolkit toolkit = new DocxToolkit();
    String docId = data(toolkit.session.openDocx(file.toString()));
    AtomicBoolean cancelled = new AtomicBoolean();
    DocumentExecutionState state = new DocumentExecutionState();
    DocumentSessionTools tools =
        new DocumentSessionTools(toolkit, () -> docId, () -> file, () -> state, cancelled);

    toolkit.body.insertParagraph(
        docId,
        List.of(
            Map.of(
                "body_index", 0,
                "text", "项目周汇报",
                "heading_level", "H1",
                "alignment", "CENTER")),
        "stop",
        null);

    assertTrue(ToolResultParser.parse(tools.saveCurrentDocument()).success());
    assertTrue(state.saved);
    try (Document reopened = Docx.open(file)) {
      assertEquals("项目周汇报", reopened.paragraph(0).text());
      assertEquals(Alignment.CENTER, reopened.paragraph(0).alignment());
    }
  }

  @Test
  void refusesSaveAfterCancellation(@TempDir Path temp) throws Exception {
    Path file = temp.resolve("current.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("正文");
      doc.save(file);
    }
    DocxToolkit toolkit = new DocxToolkit();
    String docId = data(toolkit.session.openDocx(file.toString()));
    AtomicBoolean cancelled = new AtomicBoolean(true);
    DocumentExecutionState state = new DocumentExecutionState();
    DocumentSessionTools tools =
        new DocumentSessionTools(toolkit, () -> docId, () -> file, () -> state, cancelled);

    ToolResultParser.Snapshot result = ToolResultParser.parse(tools.saveCurrentDocument());

    assertFalse(result.success());
    assertTrue(state.cancelled);
  }

  private static String data(String raw) {
    ToolResultParser.Snapshot result = ToolResultParser.parse(raw);
    if (result == null || !result.success() || result.dataText() == null) {
      throw new AssertionError("工具调用失败: " + raw);
    }
    return result.dataText();
  }
}
