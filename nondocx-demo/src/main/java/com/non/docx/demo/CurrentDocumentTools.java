package com.non.docx.demo;

import com.non.chain.tool.ToolDef;
import com.non.docx.toolkit.result.ToolResult;
import com.non.docx.toolkit.result.ToolResultRenderer;
import java.util.function.Supplier;

/**
 * 只读会话工具：返回当前文档句柄 {@code doc_id}。
 *
 * <p>独立成类是因为 {@code ToolRegistry.scan} 全量扫描无选择性——若 {@code current_document} 与 {@code
 * save_current_document} 同处 {@link DocumentSessionTools}，主 Agent 一旦 scan 它就会连带获得保存能力，违反"主 Agent
 * 无写工具"的约束。拆开后：
 *
 * <ul>
 *   <li>主 Agent scan 本类 + {@code toolkit.view}：能查 docId 做轻量只读查询，无保存能力。
 *   <li>SubAgent scan {@link DocumentSessionTools}（含 {@code save_current_document}）+ 本类：能编辑并保存。
 * </ul>
 *
 * <p>{@code doc_id} 由服务端注入（{@code AgentBridge.docId}），模型不接触文件路径。
 */
final class CurrentDocumentTools {

  private final Supplier<String> docId;

  CurrentDocumentTools(Supplier<String> docId) {
    this.docId = docId;
  }

  @ToolDef(
      name = "current_document",
      description = "返回当前文档句柄 doc_id（如 doc-1），供 view/body 等工具定位文档。先调用它获取 doc_id。")
  public String currentDocument() {
    String id = docId.get();
    if (id == null) {
      return ToolResultRenderer.render(
          ToolResult.fail(
              com.non.docx.toolkit.result.ToolResultCode.DOCUMENT_CLOSED, "当前没有打开的文档"));
    }
    return ToolResultRenderer.render(ToolResult.ok(id, "当前文档句柄 " + id));
  }
}
