package com.non.docx.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.non.chain.tool.ToolRegistry;
import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.toolkit.DocxToolkit;
import com.non.docx.toolkit.result.ToolResultParser;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证 {@link DocumentTools}：{@code current_document} 只读句柄 + {@code saveCurrentDocument} 应用层保存。
 *
 * <p>2026-07-17 重构后，{@code saveCurrentDocument} <b>无质检门控</b>——dirty 即落盘（复审改为软警告，不拦截保存）。 覆盖：dirty
 * 落盘、取消拒绝、save 不暴露为工具。
 */
class DocumentToolsTest {

  @Test
  void currentDocumentReturnsHandle(@TempDir Path temp) throws Exception {
    Path file = temp.resolve("current.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("正文");
      doc.save(file);
    }
    DocxToolkit toolkit = new DocxToolkit();
    String docId = data(toolkit.session.openDocx(file.toString()));
    DocumentTools tools = new DocumentTools(toolkit, () -> docId, () -> file);

    ToolResultParser.Snapshot snapshot = ToolResultParser.parse(tools.currentDocument());
    assertTrue(snapshot.success());
    assertEquals(docId, snapshot.dataText());
  }

  @Test
  void saveCurrentDocumentPersistsDirtyNoGate(@TempDir Path temp) throws Exception {
    Path file = temp.resolve("current.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("正文");
      doc.save(file);
    }
    DocxToolkit toolkit = new DocxToolkit();
    String docId = data(toolkit.session.openDocx(file.toString()));
    DocumentTools tools = new DocumentTools(toolkit, () -> docId, () -> file);

    // 内存写入标题
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

    // dirty 即落盘，无质检门控；qualityReport 由 AgentBridge 复审结论填充，save 内部产空
    DocumentTools.SaveOutcome outcome = tools.saveCurrentDocument(false);
    assertTrue(outcome.saved, "dirty 应直接落盘（无门控）");
    assertEquals("", outcome.qualityReport, "save 内部不再产出质检报告");
    try (Document reopened = Docx.open(file)) {
      assertEquals("项目周汇报", reopened.paragraph(0).text());
    }
  }

  @Test
  void saveRefusedWhenCancelled(@TempDir Path temp) throws Exception {
    Path file = temp.resolve("current.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("正文");
      doc.save(file);
    }
    DocxToolkit toolkit = new DocxToolkit();
    String docId = data(toolkit.session.openDocx(file.toString()));
    DocumentTools tools = new DocumentTools(toolkit, () -> docId, () -> file);

    DocumentTools.SaveOutcome outcome = tools.saveCurrentDocument(true);
    assertFalse(outcome.saved);
    assertTrue(outcome.error.contains("取消"), "取消应说明原因: " + outcome.error);
  }

  /** save_current_document 已从工具表移除——注册表里只剩 current_document。 */
  @Test
  void saveIsNotExposedAsTool(@TempDir Path temp) throws Exception {
    Path file = temp.resolve("current.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("正文");
      doc.save(file);
    }
    DocxToolkit toolkit = new DocxToolkit();
    String docId = data(toolkit.session.openDocx(file.toString()));
    DocumentTools tools = new DocumentTools(toolkit, () -> docId, () -> file);

    ToolRegistry registry = new ToolRegistry().scan(tools);
    assertTrue(registry.hasTool("current_document"), "current_document 应注册");
    assertFalse(registry.hasTool("save_current_document"), "save 不应是 LLM 工具");
    assertFalse(registry.hasTool("save_docx"), "save_docx 不应注册");
    assertTrue(registry.subAgentNames().isEmpty(), "单 Agent 模式不应注册任何 SubAgent");
  }

  private static String data(String raw) {
    ToolResultParser.Snapshot result = ToolResultParser.parse(raw);
    if (result == null || !result.success() || result.dataText() == null) {
      throw new AssertionError("工具调用失败: " + raw);
    }
    return result.dataText();
  }
}
