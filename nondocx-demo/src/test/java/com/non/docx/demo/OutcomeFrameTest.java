package com.non.docx.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.non.docx.demo.DocumentTools.SaveOutcome;
import org.junit.jupiter.api.Test;

/**
 * 验证 {@code edit_outcome} SSE 帧的 status 派生逻辑（{@link AgentBridge#deriveStatus}）。
 *
 * <p>这是取消真相机制（② SSE 系统帧）的核心：服务端按 dirty/saved/cancelled 真相钉死 status， 前端以此为准渲染成败口径， 不依赖 agent
 * 流式文本（文本不可事后改写）。
 */
class OutcomeFrameTest {

  @Test
  void cancelledStatusWhenCancelled() {
    assertEquals("cancelled", AgentBridge.deriveStatus(true, SaveOutcome.cancelled()));
  }

  @Test
  void savedStatusWhenSaved() {
    assertEquals(
        "saved", AgentBridge.deriveStatus(false, SaveOutcome.of(true, true, "report", "")));
  }

  @Test
  void rolledBackStatusWhenSaveFailed() {
    assertEquals(
        "rolled_back", AgentBridge.deriveStatus(false, SaveOutcome.failed("质量检查发现错误，未保存")));
    assertEquals(
        "rolled_back",
        AgentBridge.deriveStatus(false, SaveOutcome.of(false, false, "", "保存失败：磁盘错误")));
  }

  /** 取消优先于其它——即使 outcome 恰好 saved，cancelled 仍应胜出（实际不会发生，但语义明确）。 */
  @Test
  void cancelledTakesPrecedence() {
    assertEquals("cancelled", AgentBridge.deriveStatus(true, SaveOutcome.of(true, true, "", "")));
  }
}
