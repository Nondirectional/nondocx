package io.github.nondirectional.docx.examples.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class EvalResultTest {

  @Test
  void summaryAggregatesPassRateCallsAndTokens() {
    EvalTask task = dummyTask("a", "任务 A");
    EvalResult.TaskResult passed = taskResult(task, true, EvalResult.FailureKind.NONE, 2, 100, 20);
    EvalResult.TaskResult failed =
        taskResult(task, false, EvalResult.FailureKind.CHECK_FAILED, 1, 50, 10);

    EvalResult.Summary summary = EvalResult.Summary.from(List.of(passed, failed));

    assertThat(summary.total()).isEqualTo(2);
    assertThat(summary.passed()).isEqualTo(1);
    assertThat(summary.failed()).isEqualTo(1);
    assertThat(summary.passRate()).isEqualTo(0.5);
    assertThat(summary.avgToolCalls()).isEqualTo(1.5);
    assertThat(summary.promptTokens()).isEqualTo(150);
    assertThat(summary.completionTokens()).isEqualTo(30);
  }

  @Test
  void reportSerializesConfigAndSummary() {
    EvalTask task = dummyTask("a", "任务 A");
    EvalResult.TaskResult result = taskResult(task, true, EvalResult.FailureKind.NONE, 1, 10, 2);
    EvalResult.RunReport report =
        new EvalResult.RunReport(
            "run-1",
            "2026-10-29T00:00:00+08:00",
            "abc123",
            "http://localhost/v1",
            "m",
            58,
            0,
            "full",
            "baseline",
            List.of(result));

    assertThat(report.summary().passed()).isEqualTo(1);
    assertThat(report.toMap()).containsKeys("runId", "model", "toolCount", "tasks", "summary");
    assertThat(report.toMap().get("skillCount")).isEqualTo(0);
    assertThat(report.surface()).isEqualTo("full");
    assertThat(report.group()).isEqualTo("baseline");
    assertThat(report.toMap()).containsEntry("surface", "full").containsEntry("group", "baseline");
  }

  private static EvalTask dummyTask(String id, String title) {
    return new EvalTask(
        id, title, "prompt", doc -> {}, List.of(EvalTask.NamedCheck.of("noop", doc -> {})));
  }

  private static EvalResult.TaskResult taskResult(
      EvalTask task,
      boolean passed,
      EvalResult.FailureKind kind,
      int toolCalls,
      long promptTokens,
      long completionTokens) {
    return new EvalResult.TaskResult(
        task,
        passed,
        kind,
        List.of(new EvalResult.CheckResult("noop", passed, passed ? "" : "failed")),
        toolCallList(toolCalls),
        0,
        promptTokens,
        completionTokens,
        100,
        "final",
        "",
        "/tmp/out.docx");
  }

  private static List<EvalResult.ToolCall> toolCallList(int count) {
    List<EvalResult.ToolCall> calls = new java.util.ArrayList<>();
    for (int i = 0; i < count; i++) {
      calls.add(new EvalResult.ToolCall("current_document", "{}", "ok", true, 1));
    }
    return calls;
  }
}
