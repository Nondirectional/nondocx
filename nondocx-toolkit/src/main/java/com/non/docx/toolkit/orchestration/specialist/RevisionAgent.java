package com.non.docx.toolkit.orchestration.specialist;

import com.non.docx.toolkit.orchestration.DocumentSnapshot;
import com.non.docx.toolkit.orchestration.ExpertPlan;
import com.non.docx.toolkit.orchestration.Operation;
import com.non.docx.toolkit.orchestration.agent.ExpertAgent;
import com.non.docx.toolkit.orchestration.agent.LlmTraceEvent;
import com.non.docx.toolkit.orchestration.session.OrchestratorSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 修订领域专家：把「接受/拒绝修订」类用户意图翻译成 revision 域的 {@link Operation}。
 *
 * <p><b>OOXML 三层递进（修订专家）：</b>
 *
 * <ul>
 *   <li><b>OOXML</b>：修订是 {@code <w:ins>}/{@code <w:del>}/{@code <w:rPrChange>} 等带作者信息的元素。
 *   <li><b>POI</b>：{@code TrackedChanges} 提供 list/accept/reject。
 *   <li><b>nondocx</b>：{@code RevisionAgent} 读快照修订摘要（数量级），按用户意图产出 accept/reject operation。
 * </ul>
 *
 * <p><b>review 倾向。</b> 修订类操作天然高风险（影响文档历史），RouterAgent 的 REVIEW 阶段对 revision 域 operation 会条件触发
 * review（见 {@code RouterAgent.shouldReview}）。
 */
public final class RevisionAgent implements ExpertAgent {

  private final AtomicLong opIdSeq = new AtomicLong();

  @Override
  public String name() {
    return "RevisionAgent";
  }

  @Override
  public boolean relevantTo(String intent, DocumentSnapshot snapshot) {
    if (intent == null) return false;
    String lower = intent.toLowerCase(Locale.ROOT);
    return lower.contains("修订")
        || lower.contains("接受")
        || lower.contains("拒绝")
        || lower.contains("accept")
        || lower.contains("reject")
        || (snapshot.revisionSummary().totalCount() > 0
            && (lower.contains("track") || lower.contains("变更")));
  }

  @Override
  public ExpertPlan plan(
      OrchestratorSession session,
      DocumentSnapshot snapshot,
      String intent,
      Consumer<LlmTraceEvent> traceCallback) {
    // 本专家用关键词启发式（非 LLM），不产生 LLM trace，忽略 traceCallback。
    List<Operation> ops = new ArrayList<>();

    // 启发式：「接受所有修订」/「全部接受修订」/「拒绝所有修订」
    String lower = intent.toLowerCase(Locale.ROOT);
    if ((lower.contains("接受") || lower.contains("accept")) && lower.contains("修")) {
      // 第一版：接受所有文本/移动类修订（不指定 id，用 TEXT_OR_MOVE + 空 ids 表示全量语义）
      // 注意：applyTrackedChanges 需要具体 ids；全量场景应由上层先 list 再逐条。这里产出 ALL 语义 operation。
      ops.add(
          RevisionExecutor.applyRevision(
              nextOpId(), "ACCEPT", "TEXT_OR_MOVE", List.of("__ALL__"), "接受所有文本类修订"));
    } else if ((lower.contains("拒绝") || lower.contains("reject")) && lower.contains("修")) {
      ops.add(
          RevisionExecutor.applyRevision(
              nextOpId(), "REJECT", "TEXT_OR_MOVE", List.of("__ALL__"), "拒绝所有文本类修订"));
    }

    return new ExpertPlan(
        name(),
        "revision-plan-" + session.sessionGeneration(),
        session.conversationId(),
        snapshot.snapshotVersion(),
        session.sessionGeneration(),
        ops);
  }

  String nextOpId() {
    return "revision-op-" + opIdSeq.incrementAndGet();
  }
}
