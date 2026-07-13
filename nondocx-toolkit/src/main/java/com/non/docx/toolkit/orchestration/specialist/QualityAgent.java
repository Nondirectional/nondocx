package com.non.docx.toolkit.orchestration.specialist;

import com.non.docx.toolkit.QualityCheckTools;
import com.non.docx.toolkit.orchestration.DocumentSnapshot;
import com.non.docx.toolkit.orchestration.ExpertPlan;
import com.non.docx.toolkit.orchestration.Operation;
import com.non.docx.toolkit.orchestration.agent.ExpertAgent;
import com.non.docx.toolkit.orchestration.agent.LlmTraceEvent;
import com.non.docx.toolkit.orchestration.review.ReviewReason;
import com.non.docx.toolkit.orchestration.review.ReviewResult;
import com.non.docx.toolkit.orchestration.session.OrchestratorSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 质量审查专家：跑 {@link QualityCheckTools#checkQuality}，把结果直接映射到统一 review 模型。
 *
 * <p><b>OOXML 三层递进（质量审查）：</b>
 *
 * <ul>
 *   <li><b>OOXML</b>：.docx 没有内建「质量」概念——版式/兼容性问题是渲染时的观察结果，不在 XML 里显式表达。
 *   <li><b>POI</b>：不提供质量检查；需自行遍历结构判断（空白页、行距、表格分页等）。
 *   <li><b>nondocx</b>：{@code QualityCheckTools} 封装了 10 项版式/兼容性自检，返回 ❌/⚠️/✅ 报告； 本专家把报告映射成一条 {@code
 *       check_quality} operation，其 review 结果直连统一模型：
 *       <ul>
 *         <li>有 ❌ error → {@code BLOCKED(QUALITY_GATE_FAILED)}
 *         <li>无 error 但有 ⚠️ warning → {@code WARNED(QUALITY_RISK)}
 *         <li>全 ✅ → APPROVED
 *       </ul>
 * </ul>
 *
 * <p><b>软闸门语义（父任务决策）。</b> {@code QualityAgent} 第一版作为软闸门：输出分级审查结果， 不直接硬阻止提交；是否继续执行由 RouterAgent
 * 综合用户意图、风险等级与操作类型决定—— 映射到统一 review 后，BLOCKED 才真正阻断整批（由 RouterAgent 的 BLOCKED 闸门实现）。
 */
public final class QualityAgent implements ExpertAgent {

  private final QualityCheckTools qualityCheck;
  private final AtomicLong opIdSeq = new AtomicLong();

  public QualityAgent(QualityCheckTools qualityCheck) {
    this.qualityCheck = qualityCheck;
  }

  @Override
  public String name() {
    return "QualityAgent";
  }

  @Override
  public boolean relevantTo(String intent, DocumentSnapshot snapshot) {
    if (intent == null) return false;
    String lower = intent.toLowerCase(Locale.ROOT);
    return lower.contains("质量")
        || lower.contains("检查")
        || lower.contains("自检")
        || lower.contains("quality")
        || lower.contains("版式")
        || lower.contains("兼容");
  }

  @Override
  public ExpertPlan plan(
      OrchestratorSession session,
      DocumentSnapshot snapshot,
      String intent,
      Consumer<LlmTraceEvent> traceCallback) {
    // 本专家跑本地质量检查（非 LLM），不产生 LLM trace，忽略 traceCallback。
    // 跑质量检查（全量）
    String report = qualityCheck.checkQuality(session.docId(), List.of());
    int errors = countErrors(report);
    int warnings = countWarnings(report);

    Operation op =
        Operation.of(
            nextOpId(),
            "quality",
            "check_quality",
            "doc",
            java.util.Map.of("report", report),
            new com.non.docx.toolkit.orchestration.ConflictKey("quality", "check_quality", "doc"),
            "质量自检",
            "跑版式/兼容性自检",
            "");

    // 直连 review 映射
    ReviewResult review;
    if (errors > 0) {
      review =
          ReviewResult.of(
              ReviewReason.QUALITY_GATE_FAILED,
              "质量门禁失败：❌ "
                  + errors
                  + " 项 error"
                  + (warnings > 0 ? "，⚠️ " + warnings + " 项 warning" : ""));
    } else if (warnings > 0) {
      review = ReviewResult.of(ReviewReason.QUALITY_RISK, "质量风险：⚠️ " + warnings + " 项 warning");
    } else {
      review = ReviewResult.approved("质量自检全部通过");
    }
    op = op.withReview(review);

    return new ExpertPlan(
        name(),
        "quality-plan-" + session.sessionGeneration(),
        session.conversationId(),
        snapshot.snapshotVersion(),
        session.sessionGeneration(),
        new ArrayList<>(List.of(op)));
  }

  String nextOpId() {
    return "quality-op-" + opIdSeq.incrementAndGet();
  }

  /** 从 checkQuality 报告解析 ❌ error 数量。报告末尾形如「❌ 2 errors」。 */
  static int countErrors(String report) {
    return countTail(report, "❌ (\\d+) errors");
  }

  /** 从 checkQuality 报告解析 ⚠️ warning 数量。报告末尾形如「⚠️ 3 warnings」。 */
  static int countWarnings(String report) {
    return countTail(report, "⚠️ (\\d+) warnings");
  }

  private static int countTail(String report, String pattern) {
    if (report == null) return 0;
    Matcher m = Pattern.compile(pattern).matcher(report);
    if (m.find()) {
      try {
        return Integer.parseInt(m.group(1));
      } catch (NumberFormatException ignored) {
        return 0;
      }
    }
    return 0;
  }
}
