package com.non.docx.demo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.non.docx.toolkit.result.ToolResult;
import com.non.docx.toolkit.result.ToolResultCode;
import com.non.docx.toolkit.result.ToolResultRenderer;
import org.junit.jupiter.api.Test;

/**
 * 验证单 Agent 的 dirty 检测与结果瘦身（α/β 污染治理）。
 *
 * <p>覆盖 {@link AgentBridge#isReadonly}（决定是否置 dirty）、{@link AgentBridge#slimWriteResult}（写工具瘦身）、
 * {@link AgentBridge#summarizeQuality}（质检摘要 + note 隔离的 LLM 可见部分）。
 */
class DirtyDetectionTest {

  // ---- isReadonly：dirty 检测的根 ----

  @Test
  void readonlyPrefixesAreNotDirty() {
    assertTrue(AgentBridge.isReadonly("view_stats"));
    assertTrue(AgentBridge.isReadonly("view_text"));
    assertTrue(AgentBridge.isReadonly("read_paragraph"));
    assertTrue(AgentBridge.isReadonly("get_document_overview"));
    assertTrue(AgentBridge.isReadonly("list_tracked_changes"));
    assertTrue(AgentBridge.isReadonly("search_text"));
    assertTrue(AgentBridge.isReadonly("check_quality"));
    assertTrue(AgentBridge.isReadonly("current_document"));
    assertTrue(AgentBridge.isReadonly("describe_capabilities"));
  }

  @Test
  void writeToolsAreDirty() {
    assertFalse(AgentBridge.isReadonly("insert_paragraph"));
    assertFalse(AgentBridge.isReadonly("replace_run_text"));
    assertFalse(AgentBridge.isReadonly("create_table"));
    assertFalse(AgentBridge.isReadonly("update_paragraph_alignment"));
    assertFalse(AgentBridge.isReadonly("insert_tracked_run"));
    assertFalse(AgentBridge.isReadonly("apply_tracked_changes"));
  }

  /** 安全默认：未知工具视为写（漏标 dirty 会丢编辑；多标只是浪费一次 flush）。 */
  @Test
  void unknownToolDefaultsToWrite() {
    assertFalse(AgentBridge.isReadonly("some_future_write_tool"));
    assertFalse(AgentBridge.isReadonly(null));
    assertFalse(AgentBridge.isReadonly(""));
  }

  // ---- slimWriteResult：α 瘦身 ----

  @Test
  void slimWriteResultKeepsStatusDropsData() {
    String bulky =
        ToolResultRenderer.render(
            ToolResult.ok(
                "{\"body_index\":0,\"text\":\"很长很长的段落内容...\",\"heading_level\":\"H1\"}", "已插入"));
    String slim = AgentBridge.slimWriteResult("insert_paragraph", bulky);
    assertTrue(slim.startsWith("✓ insert_paragraph"), "应保留成败与工具名: " + slim);
    assertFalse(slim.contains("很长很长的段落内容"), "不应回显 data 全文: " + slim);
  }

  @Test
  void slimWriteResultHandlesFailure() {
    String fail =
        ToolResultRenderer.render(ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "索引越界"));
    String slim = AgentBridge.slimWriteResult("insert_paragraph", fail);
    assertTrue(slim.startsWith("✗ insert_paragraph"));
    assertTrue(slim.contains("索引越界"), "失败应保留原因: " + slim);
  }

  // ---- summarizeQuality：β 摘要（note 原文隔离的 LLM 可见部分） ----

  @Test
  void summarizeQualityTruncatesLongReport() {
    String report = ToolResultRenderer.render(ToolResult.ok("ok", "标题".repeat(200)));
    String summary = AgentBridge.summarizeQuality(report);
    assertTrue(summary.length() < 300, "摘要应远短于原文: " + summary.length());
    assertTrue(summary.contains("完整报告已存档"), "应提示原文已 note 存档: " + summary);
  }

  @Test
  void summarizeQualityMarksSuccess() {
    String report = ToolResultRenderer.render(ToolResult.ok("ok", "无问题"));
    assertTrue(AgentBridge.summarizeQuality(report).contains("✓"));
  }
}
