package com.non.docx.demo;

import com.non.chain.tool.ToolDef;
import com.non.docx.toolkit.DocxToolkit;
import com.non.docx.toolkit.result.ToolResult;
import com.non.docx.toolkit.result.ToolResultCode;
import com.non.docx.toolkit.result.ToolResultParser;
import com.non.docx.toolkit.result.ToolResultRenderer;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * 文档会话工具：只读句柄查询 + 应用层强制保存。
 *
 * <p>单 Agent 回归后，本类合并了原 {@code CurrentDocumentTools}（只读 {@code current_document}）与 {@code
 * DocumentSessionTools}（保存）。关键变化：
 *
 * <ul>
 *   <li>{@code current_document} 仍是 {@code @ToolDef}——单 Agent 需要它定位文档。
 *   <li>{@code saveCurrentDocument} <b>不再是工具</b>（无 {@code @ToolDef}）。它是应用层方法，由 {@link AgentBridge}
 *       在 agent 主循环结束（{@code AgentEvent.Complete}）时强制调用，避免 LLM "漏调 save / 谎报成功"带来的自述与磁盘真相鸿沟。见
 *       {@code agent-single.md} spec。
 * </ul>
 *
 * <p>原 {@code CurrentDocumentTools} 为隔离 {@code save_current_document} 而独立成类——因为 {@code
 * ToolRegistry.scan} 全量扫描无选择性，主 Agent 一旦 scan 含 save 的类就会获得保存能力。 单 Agent 模式下"主 Agent
 * 无写工具"的约束不复存在，拆分理由消失，故合并。
 */
final class DocumentTools {

  private final DocxToolkit toolkit;
  private final Supplier<String> docId;
  private final Supplier<Path> outputPath;

  DocumentTools(DocxToolkit toolkit, Supplier<String> docId, Supplier<Path> outputPath) {
    this.toolkit = toolkit;
    this.docId = docId;
    this.outputPath = outputPath;
  }

  /**
   * 返回当前文档句柄 {@code doc_id}。单 Agent 调用 view/body 等工具前先用它获取 doc_id。
   *
   * <p>{@code doc_id} 由服务端注入（{@link AgentBridge#docId}），模型不接触文件路径。
   */
  @ToolDef(
      name = "current_document",
      description = "返回当前文档句柄 doc_id（如 doc-1），供 view/body 等工具定位文档。先调用它获取 doc_id。")
  public String currentDocument() {
    String id = docId.get();
    if (id == null) {
      return ToolResultRenderer.render(
          ToolResult.fail(ToolResultCode.DOCUMENT_CLOSED, "当前没有打开的文档"));
    }
    return ToolResultRenderer.render(ToolResult.ok(id, "当前文档句柄 " + id));
  }

  /**
   * 应用层强制保存：dirty 即落盘，无质检门控。
   *
   * <p>由 {@link AgentBridge} 的 {@code Complete} flush 调用，<b>不是</b> LLM 工具。
   *
   * <p><b>质检语义变更（2026-07-17）</b>：原实现保存前跑全量规则质检，按 error 拒绝落盘。现质检改为「只读复审
   * SubAgent」复审意图达成度，且复审为<b>软警告</b>（不拦截保存）——故本方法不再做任何质检门控，dirty 即落盘。 复审结论由 {@link AgentBridge} 在
   * flush 时另行收集，随 {@code edit_outcome} 回传，不在此产出。
   *
   * @param cancelled 本轮是否已取消（取消则拒绝保存）
   * @return 保存结果（成功 / 失败原因）；{@link SaveOutcome#qualityReport} 始终为空——复审结论由 AgentBridge 填充
   */
  SaveOutcome saveCurrentDocument(boolean cancelled) {
    if (cancelled) {
      return SaveOutcome.cancelled();
    }
    String currentDocId = docId.get();
    if (toolkit.session.getDocument(currentDocId) == null) {
      return SaveOutcome.failed("当前文档句柄不存在");
    }
    String result = toolkit.session.saveDocx(currentDocId, outputPath.get().toString());
    ToolResultParser.Snapshot parsed = ToolResultParser.parse(result);
    boolean saved = parsed != null && parsed.success();
    return new SaveOutcome(
        saved, saved, "", saved ? "" : "保存失败：" + (parsed == null ? result : parsed.message()));
  }

  /** 保存结果：携带成败、质检报告与原因，供 {@link AgentBridge} 组装 {@code edit_outcome} 帧。 */
  static final class SaveOutcome {
    final boolean saved;
    final boolean changed;
    final String qualityReport;
    final String error;

    private SaveOutcome(boolean saved, boolean changed, String qualityReport, String error) {
      this.saved = saved;
      this.changed = changed;
      this.qualityReport = qualityReport;
      this.error = error;
    }

    static SaveOutcome cancelled() {
      return new SaveOutcome(false, false, "", "用户已取消，未保存");
    }

    static SaveOutcome failed(String reason) {
      return new SaveOutcome(false, false, "", reason);
    }

    /** 测试用工厂：按指定字段构造。 */
    static SaveOutcome of(boolean saved, boolean changed, String qualityReport, String error) {
      return new SaveOutcome(saved, changed, qualityReport, error);
    }
  }
}
