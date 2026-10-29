package io.github.nondirectional.docx.examples.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 评测报告渲染：{@code report.json}（机器可读）+ {@code report.md}（人可读）。
 *
 * <p>报告不参与断言，只负责把 {@link EvalResult.RunReport} 落成可对比的产物。
 */
public final class ReportWriter {

  private ReportWriter() {}

  /**
   * 写入一次运行的报告。
   *
   * @param runDir 运行目录
   * @param report 运行结果
   * @throws IOException 写文件失败
   */
  public static void write(Path runDir, EvalResult.RunReport report) throws IOException {
    Files.createDirectories(runDir);
    Files.writeString(
        runDir.resolve("report.json"), JsonWriter.write(report.toMap()), StandardCharsets.UTF_8);
    Files.writeString(runDir.resolve("report.md"), markdown(report), StandardCharsets.UTF_8);
  }

  /** 生成 Markdown 报告。 */
  public static String markdown(EvalResult.RunReport report) {
    StringBuilder sb = new StringBuilder();
    sb.append("# nondocx Agent 评测基线\n\n");
    sb.append("> runId: `").append(report.runId()).append("`  \n");
    sb.append("> 开始时间: ").append(report.startedAt()).append("  \n");
    sb.append("> git: `").append(emptyAs(report.gitCommit(), "unknown")).append("`  \n");
    sb.append("> 模型: `").append(report.model()).append("`  \n");
    sb.append("> baseUrl: `").append(report.baseUrl()).append("`  \n");
    sb.append("> 工具数: ").append(report.toolCount());
    sb.append("（Skill 数: ").append(report.skillCount()).append("）  \n");
    sb.append("> 判定口径：所有 check 通过才算任务成功；不使用 LLM 判断。  \n");
    if (summaryTokensUnavailable(report)) {
      sb.append(
          "> 注：当前 chain 0.11.0 的 VLLM streaming 未返回 usage，token 记为 0；" + "升级 provider 后自动恢复。\n");
    }
    sb.append('\n');

    EvalResult.Summary summary = report.summary();
    sb.append("## 汇总\n\n");
    sb.append("| 指标 | 值 |\n|---|---|\n");
    sb.append("| 任务数 | ").append(summary.total()).append(" |\n");
    sb.append("| 通过 | ").append(summary.passed()).append(" |\n");
    sb.append("| 失败 | ").append(summary.failed()).append(" |\n");
    sb.append("| 通过率 | ").append(percent(summary.passRate())).append(" |\n");
    sb.append("| 平均工具调用 | ").append(formatDouble(summary.avgToolCalls())).append(" |\n");
    sb.append("| prompt tokens | ").append(summary.promptTokens()).append(" |\n");
    sb.append("| completion tokens | ").append(summary.completionTokens()).append(" |\n");
    sb.append("| 总耗时 | ").append(formatMs(summary.durationMs())).append(" |\n");
    sb.append("| 已知能力缺口失败 | ").append(capabilityGapFailures(report)).append(" |\n");
    sb.append('\n');

    sb.append("### 失败类型分布\n\n");
    Map<String, Integer> kinds = new LinkedHashMap<>();
    for (EvalResult.TaskResult task : report.tasks()) {
      String key = task.failureKind().value();
      kinds.put(key, kinds.getOrDefault(key, 0) + 1);
    }
    for (Map.Entry<String, Integer> entry : kinds.entrySet()) {
      sb.append("- `").append(entry.getKey()).append("`: ").append(entry.getValue()).append('\n');
    }
    sb.append('\n');

    sb.append("## 任务结果\n\n");
    sb.append("| 任务 | 结果 | 能力缺口 | 失败类型 | 工具调用 | 无效调用 | 耗时 | 失败 check |\n");
    sb.append("|---|---|---|---|---:|---:|---:|---|\n");
    for (EvalResult.TaskResult task : report.tasks()) {
      int failedChecks = 0;
      StringBuilder failedNames = new StringBuilder();
      for (EvalResult.CheckResult check : task.checks()) {
        if (!check.passed()) {
          if (failedChecks > 0) {
            failedNames.append("、");
          }
          failedNames.append(check.name());
          failedChecks++;
        }
      }
      sb.append("| `")
          .append(task.task().id())
          .append("` ")
          .append(escape(task.task().title()))
          .append(" | ")
          .append(task.passed() ? "✅" : "❌")
          .append(" | ")
          .append(task.task().capabilityGap() ? "是" : "—")
          .append(" | `")
          .append(task.failureKind().value())
          .append("` | ")
          .append(task.toolCalls().size())
          .append(" | ")
          .append(task.invalidToolCalls())
          .append(" | ")
          .append(formatMs(task.durationMs()))
          .append(" | ")
          .append(failedChecks == 0 ? "—" : escape(failedNames.toString()))
          .append(" |\n");
    }
    sb.append('\n');

    boolean hasFailures = report.tasks().stream().anyMatch(task -> !task.passed());
    if (hasFailures) {
      sb.append("## 失败详情\n\n");
      for (EvalResult.TaskResult task : report.tasks()) {
        if (task.passed()) {
          continue;
        }
        sb.append("### ❌ ")
            .append(task.task().id())
            .append(" · ")
            .append(task.task().title())
            .append("\n\n");
        sb.append("- prompt: ").append(escape(task.task().prompt())).append('\n');
        sb.append("- 已知能力缺口: ")
            .append(task.task().capabilityGap() ? "是（该任务用于暴露工具面缺口）" : "否")
            .append('\n');
        if (!task.error().isBlank()) {
          sb.append("- error: ").append(escape(truncate(task.error(), 800))).append('\n');
        }
        for (EvalResult.CheckResult check : task.checks()) {
          if (!check.passed()) {
            sb.append("- check `")
                .append(check.name())
                .append("`: ")
                .append(escape(truncate(check.message(), 800)))
                .append('\n');
          }
        }
        if (!task.finalMessage().isBlank()) {
          sb.append("- 最终回复: ").append(escape(truncate(task.finalMessage(), 500))).append('\n');
        }
        sb.append('\n');
        if (!task.toolCalls().isEmpty()) {
          sb.append("工具调用：\n\n");
          for (EvalResult.ToolCall call : task.toolCalls()) {
            sb.append("- `")
                .append(call.name())
                .append("` ")
                .append(call.success() ? "✅" : "❌")
                .append(" ")
                .append(call.durationMs())
                .append("ms");
            if (!call.arguments().isBlank()) {
              sb.append(" args=")
                  .append("`")
                  .append(escape(truncate(oneLine(call.arguments()), 240)))
                  .append("`");
            }
            sb.append('\n');
          }
          sb.append('\n');
        }
      }
    }

    sb.append("## 产物\n\n");
    sb.append("每条任务的 `in.docx` / `out.docx` / `trace.jsonl` / `result.json` 位于 run 目录下。\n");
    return sb.toString();
  }

  private static long capabilityGapFailures(EvalResult.RunReport report) {
    return report.tasks().stream()
        .filter(task -> !task.passed() && task.task().capabilityGap())
        .count();
  }

  private static boolean summaryTokensUnavailable(EvalResult.RunReport report) {
    return report.summary().promptTokens() == 0 && report.summary().completionTokens() == 0;
  }

  private static String percent(double value) {
    return String.format(Locale.ROOT, "%.1f%%", value * 100.0);
  }

  private static String formatDouble(double value) {
    return String.format(Locale.ROOT, "%.2f", value);
  }

  private static String formatMs(long ms) {
    if (ms < 1000) {
      return ms + "ms";
    }
    return String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
  }

  private static String emptyAs(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static String escape(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("\\", "\\\\").replace("|", "\\|").replace("\n", " ").replace("\r", " ");
  }

  private static String oneLine(String value) {
    return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return "";
    }
    return value.length() <= max ? value : value.substring(0, max) + "…";
  }
}
