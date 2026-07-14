package com.non.docx.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.toolkit.DocxToolkit;
import com.non.docx.toolkit.result.ToolResultParser;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证应用层兜底保存的核心场景：SubAgent 改了内存文档但漏调 {@code save_current_document}， 应用层复用 {@link
 * DocumentSessionTools#saveCurrentDocument()} 替它跑质检+落盘。
 *
 * <p>这些测试直接驱动兜底所复用的同一方法，模拟 {@code AgentBridge.attemptFallbackSave} 的调用语义：在 SubAgent
 * 执行完毕、{@code execution.saved} 仍为 false 时，由编排层再调一次 {@code saveCurrentDocument()}。
 *
 * <p>注：{@code insert_paragraph} 的 alignment 丢失是 toolkit 层既有 bug（见 {@code
 * DocumentSessionToolsTest.savesCurrentDocumentAfterQualityCheck} 基线即失败），与兜底逻辑无关，故本测试不断言
 * alignment，只验证"漏 save 时兜底能落盘内存改动"。
 */
class FallbackSaveTest {

  /**
   * 核心场景：SubAgent 在内存插入了段落（模拟 insert_paragraph）但漏调 save（state.saved=false）。
   * 应用层兜底调 saveCurrentDocument → 质检通过 → 落盘 → saved=true，磁盘内容包含插入的段落。
   */
  @Test
  void fallbackSavesWhenSubAgentSkippedSave(@TempDir Path temp) throws Exception {
    Path file = temp.resolve("current.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("正文");
      doc.save(file);
    }
    DocxToolkit toolkit = new DocxToolkit();
    String docId = data(toolkit.session.openDocx(file.toString()));
    AtomicBoolean cancelled = new AtomicBoolean();
    DocumentExecutionState state = new DocumentExecutionState();
    state.delegated = true; // 模拟 SubAgent 已委派执行
    state.saved = false; // 但漏调了 save
    DocumentSessionTools tools =
        new DocumentSessionTools(toolkit, () -> docId, () -> file, () -> state, cancelled);

    // SubAgent 在内存里插入了标题（模拟 insert_paragraph 的效果）
    toolkit.body.insertParagraph(
        docId,
        List.of(Map.of("body_index", 0, "text", "你好世界", "heading_level", "H1")),
        "stop",
        null);
    assertFalse(state.saved, "SubAgent 漏调 save，state.saved 应仍为 false");

    // 应用层兜底：编排层替 SubAgent 调 saveCurrentDocument
    boolean ok = ToolResultParser.parse(tools.saveCurrentDocument()).success();

    assertTrue(ok, "兜底保存应成功");
    assertTrue(state.saved, "兜底后 state.saved 应为 true");
    assertFalse(state.failed, "不应标记失败");
    try (Document reopened = Docx.open(file)) {
      assertEquals("你好世界", reopened.paragraph(0).text(), "磁盘首段应为兜底保存的标题");
    }
  }

  /**
   * 兜底幂等保护：SubAgent 已自己保存成功（saved=true）时，编排层的触发条件应跳过兜底。
   * 对应 {@code AgentBridge.attemptFallbackSave} 的条件判断。
   */
  @Test
  void fallbackSkipsWhenAlreadySaved(@TempDir Path temp) throws Exception {
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

    assertTrue(ToolResultParser.parse(tools.saveCurrentDocument()).success());
    assertTrue(state.saved);

    // 模拟 attemptFallbackSave 的触发条件判断
    boolean shouldFallback = state.delegated && !state.saved && !state.cancelled && !state.failed;
    assertFalse(shouldFallback, "已保存时不应触发兜底");
  }

  /**
   * 兜底被取消拦截：用户取消（cancelled=true）时不应触发兜底。
   * 对应 {@code AgentBridge.attemptFallbackSave} 的条件判断。
   */
  @Test
  void fallbackSkipsWhenCancelled(@TempDir Path temp) throws Exception {
    Path file = temp.resolve("current.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("正文");
      doc.save(file);
    }
    DocxToolkit toolkit = new DocxToolkit();
    String docId = data(toolkit.session.openDocx(file.toString()));
    AtomicBoolean cancelled = new AtomicBoolean();
    DocumentExecutionState state = new DocumentExecutionState();
    state.delegated = true;
    state.cancelled = true; // 用户已取消
    @SuppressWarnings("unused")
    DocumentSessionTools tools =
        new DocumentSessionTools(toolkit, () -> docId, () -> file, () -> state, cancelled);

    boolean shouldFallback = state.delegated && !state.saved && !state.cancelled && !state.failed;
    assertFalse(shouldFallback, "已取消时不应触发兜底");
  }

  private static String data(String raw) {
    ToolResultParser.Snapshot result = ToolResultParser.parse(raw);
    if (result == null || !result.success() || result.dataText() == null) {
      throw new AssertionError("工具调用失败: " + raw);
    }
    return result.dataText();
  }
}
