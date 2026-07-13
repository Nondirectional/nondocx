package com.non.docx.demo;

import com.non.chain.tool.ToolDef;
import com.non.docx.core.api.Document;
import com.non.docx.toolkit.DocxToolkit;
import com.non.docx.toolkit.QualityCheckTools.CheckResult;
import com.non.docx.toolkit.result.ToolResult;
import com.non.docx.toolkit.result.ToolResultCode;
import com.non.docx.toolkit.result.ToolResultParser;
import com.non.docx.toolkit.result.ToolResultRenderer;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** SubAgent 的受限会话工具：只能读取当前句柄并保存到当前文档。 */
final class DocumentSessionTools {

  private final DocxToolkit toolkit;
  private final Supplier<String> docId;
  private final Supplier<Path> outputPath;
  private final Supplier<DocumentExecutionState> state;
  private final AtomicBoolean cancelRequested;

  DocumentSessionTools(
      DocxToolkit toolkit,
      Supplier<String> docId,
      Supplier<Path> outputPath,
      Supplier<DocumentExecutionState> state,
      AtomicBoolean cancelRequested) {
    this.toolkit = toolkit;
    this.docId = docId;
    this.outputPath = outputPath;
    this.state = state;
    this.cancelRequested = cancelRequested;
  }

  @ToolDef(name = "current_document", description = "返回本次任务唯一允许操作的文档句柄。")
  public String currentDocument() {
    return ToolResultRenderer.render(ToolResult.ok(docId.get(), "当前文档句柄 " + docId.get()));
  }

  @ToolDef(name = "save_current_document", description = "检查当前文档；没有错误时保存到服务端当前文档。必须作为最后一个工具调用。")
  public String saveCurrentDocument() {
    DocumentExecutionState execution = state.get();
    if (execution == null) {
      return fail(ToolResultCode.INVALID_ARGUMENT, "没有活跃实施任务");
    }
    if (cancelRequested.get()) {
      execution.cancelled = true;
      return fail(ToolResultCode.INVALID_ARGUMENT, "用户已取消，禁止保存");
    }
    String currentDocId = docId.get();
    Document document = toolkit.session.getDocument(currentDocId);
    if (document == null) {
      execution.failed = true;
      return fail(ToolResultCode.DOCUMENT_CLOSED, "当前文档句柄不存在");
    }
    execution.qualityReport = toolkit.qualityCheck.checkQuality(currentDocId, null);
    List<CheckResult> checks = toolkit.qualityCheck.runAllChecks(document);
    boolean hasError =
        checks.stream().anyMatch(check -> !check.passed() && "error".equals(check.severity()));
    if (hasError) {
      execution.failed = true;
      return fail(ToolResultCode.INVALID_ARGUMENT, "质量检查发现错误，未保存当前文档");
    }
    String result = toolkit.session.saveDocx(currentDocId, outputPath.get().toString());
    ToolResultParser.Snapshot parsed = ToolResultParser.parse(result);
    execution.saved = parsed != null && parsed.success();
    if (!execution.saved) {
      execution.failed = true;
    }
    return result;
  }

  private static String fail(ToolResultCode code, String message) {
    return ToolResultRenderer.render(ToolResult.fail(code, message));
  }
}
