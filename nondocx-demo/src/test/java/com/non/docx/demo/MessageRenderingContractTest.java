package com.non.docx.demo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 锁定 Demo 消息渲染的三层信息结构，避免 Trace 再次直接占据主消息区。 */
class MessageRenderingContractTest {

  private static final Path APP_JS = Path.of("src", "main", "resources", "static", "app.js");
  private static final Path STYLE_CSS = Path.of("src", "main", "resources", "static", "style.css");

  @Test
  void rendersChatBubbleExecutionCardAndCollapsedTrace() throws Exception {
    String app = Files.readString(APP_JS, StandardCharsets.UTF_8);
    String css = Files.readString(STYLE_CSS, StandardCharsets.UTF_8);

    assertTrue(app.contains("className = 'msg assistant empty'"));
    assertTrue(app.contains("className = 'execution-card'"));
    assertTrue(app.contains("查看执行过程（"));
    assertTrue(app.contains("run.reply += data.delta || ''"));
    assertTrue(app.contains("if (data.message) run.reply = data.message;"));
    assertTrue(app.contains("card.open = false"));
    // 2026-07-17：质检改为意图复审，前端解析 review_intent 的 <verdict>+<diff> 三态结论
    assertTrue(app.contains("parseReviewReport"));
    assertTrue(app.contains("reviewVerdictMeta"));
    assertTrue(app.contains("review-verdict"));
    assertTrue(app.contains("quality-stats"));
    assertTrue(app.contains("buildTraceSteps"));
    assertTrue(app.contains("查看参数和返回值"));
    assertTrue(app.contains("查看模型原始日志"));
    assertTrue(css.contains(".execution-card"));
    assertTrue(css.contains(".execution-step.active .step-icon"));
    assertTrue(css.contains(".quality-stats"));
    assertTrue(css.contains(".review-verdict"));
    assertTrue(css.contains(".trace-step"));
    assertTrue(css.contains(".trace-panel"));
  }

  @Test
  void doesNotRenderLegacyTimelineStructure() throws Exception {
    String app = Files.readString(APP_JS, StandardCharsets.UTF_8);
    String css = Files.readString(STYLE_CSS, StandardCharsets.UTF_8);

    assertFalse(app.contains("className = 'timeline-run'"));
    assertFalse(css.contains(".timeline-run"));
    assertFalse(css.contains(".tool-call"));
    // 2026-07-17：旧的规则质检 JSON 解析（parseQualityReport/10项标签）已移除，改为复审三态
    assertFalse(app.contains("parseQualityReport"));
    assertFalse(app.contains("QUALITY_CHECK_LABELS"));
    assertFalse(app.contains("appendQualityStat"));
  }
}
