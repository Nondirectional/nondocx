package com.non.docx.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.non.chain.Message;
import com.non.chain.agent.AfterResult;
import com.non.chain.agent.ToolCallContext;
import org.junit.jupiter.api.Test;

/**
 * 验证 {@link AgentBridge#correctSubAgentResult} 的权威更正逻辑。
 *
 * <p>根因场景：SubAgent 的 LLM 自述 {@code success:true}（幻觉），但服务端 {@code execution.saved=false}。本测试确保
 * 拦截器强制用服务端真相覆盖，使主 Agent 只能据实报告，避免"成功了但被回滚"的矛盾。
 */
class AgentBridgeCorrectionTest {

  private static ToolCallContext subAgentCall(String subAgentResult) {
    return new ToolCallContext(
        "call-1",
        "invoke_subagent",
        "{\"task\":\"x\"}",
        Message.user("test"),
        subAgentResult,
        false);
  }

  /** SubAgent 自称成功但 execution.saved=false（LLM 幻觉）→ 必须更正为 success:false。 */
  @Test
  void correctsHallucinatedSuccessWhenNotSaved() {
    String hallucinated =
        "{\"success\":true,\"summary\":\"已添加标题\",\"changed\":true,\"qualityReport\":\"✅ 无问题\",\"error\":\"\"}";
    DocumentExecutionState state = new DocumentExecutionState();
    state.delegated = true;
    state.saved = false;

    AfterResult result = AgentBridge.correctSubAgentResult(subAgentCall(hallucinated), state);

    assertTrue(result.modified(), "应被更正");
    String content = result.content();
    assertFalse(content.contains("\"success\":true"), "不得保留幻觉的 success:true");
    assertTrue(content.contains("\"success\":false"), "必须强制 success:false");
    assertTrue(content.contains("\"changed\":false"), "必须强制 changed:false");
    assertTrue(
        content.contains("save_current_document"),
        "error 应说明未保存原因，实际: " + content);
    assertTrue(content.contains("已添加标题"), "应保留 SubAgent 自述 summary 作为辅证");
  }

  /** execution 为 null（异常路径）→ 更正为 success:false，不抛异常。 */
  @Test
  void correctsWhenExecutionIsNull() {
    AfterResult result = AgentBridge.correctSubAgentResult(subAgentCall("{\"success\":true}"), null);
    assertTrue(result.modified());
    assertTrue(result.content().contains("\"success\":false"));
  }

  /** cancelled=true → error 应为取消原因。 */
  @Test
  void correctsWithCancelReason() {
    DocumentExecutionState state = new DocumentExecutionState();
    state.cancelled = true;
    AfterResult result = AgentBridge.correctSubAgentResult(subAgentCall("{\"success\":true}"), state);
    assertTrue(result.content().contains("用户已取消"));
  }

  /** failed=true → error 应为实施失败原因。 */
  @Test
  void correctsWithFailureReason() {
    DocumentExecutionState state = new DocumentExecutionState();
    state.failed = true;
    AfterResult result = AgentBridge.correctSubAgentResult(subAgentCall("{\"success\":true}"), state);
    assertTrue(result.content().contains("实施失败"));
  }

  /** execution.saved=true（真正保存成功）→ 信任自述，keep 不改。 */
  @Test
  void keepsResultWhenActuallySaved() {
    String honestSuccess =
        "{\"success\":true,\"summary\":\"已添加\",\"changed\":true,\"qualityReport\":\"✅ 无问题\",\"error\":\"\"}";
    DocumentExecutionState state = new DocumentExecutionState();
    state.saved = true;
    AfterResult result = AgentBridge.correctSubAgentResult(subAgentCall(honestSuccess), state);
    assertFalse(result.modified(), "真正保存时应保留自述，不改");
  }

  /** 非 invoke_subagent 工具 → 不干预。 */
  @Test
  void ignoresOtherTools() {
    DocumentExecutionState state = new DocumentExecutionState();
    state.saved = false;
    ToolCallContext otherCall =
        new ToolCallContext("call-2", "view_stats", "{}", Message.user("t"), "some result", false);
    AfterResult result = AgentBridge.correctSubAgentResult(otherCall, state);
    assertFalse(result.modified(), "非 subagent 工具不应被干预");
  }

  /** 修复后：更正结果的 success 字段与 runStream 发的 subagent_result 帧一致（都 false）。 */
  @Test
  void correctedSuccessAlignsWithServerFrame() {
    DocumentExecutionState state = new DocumentExecutionState();
    state.delegated = true;
    state.saved = false;
    AfterResult result =
        AgentBridge.correctSubAgentResult(
            subAgentCall("{\"success\":true,\"changed\":true,\"qualityReport\":\"\",\"error\":\"\"}"),
            state);
    // runStream 的 subagent_result 帧用 execution.saved 算 success；拦截器更正后也应为 false
    boolean frameSuccess = state.saved && !state.failed && !state.cancelled;
    assertEquals(frameSuccess, false, "服务端帧 success 应为 false");
    assertTrue(result.content().contains("\"success\":false"), "更正后也应为 false，二者一致");
  }
}
