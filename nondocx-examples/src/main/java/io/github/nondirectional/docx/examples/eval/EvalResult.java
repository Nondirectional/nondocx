package io.github.nondirectional.docx.examples.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测结果模型。
 *
 * <p>这一组不可变值对象只负责承载数据；JSON/Markdown 渲染在 {@link ReportWriter}，运行逻辑在 {@link
 * EvalRunner}。字段刻意保持扁平，方便报告与后续脚本消费。
 */
public final class EvalResult {

  /** 单任务失败类型；成功为 {@link #NONE}。 */
  public enum FailureKind {
    NONE("none"),
    CHECK_FAILED("check_failed"),
    AGENT_ERROR("agent_error"),
    SAVE_ERROR("save_error"),
    TIMEOUT("timeout"),
    TOOL_ERROR("tool_error");

    private final String value;

    FailureKind(String value) {
      this.value = value;
    }

    public String value() {
      return value;
    }
  }

  /** 一条 check 的结果。 */
  public static final class CheckResult {

    private final String name;
    private final boolean passed;
    private final String message;

    public CheckResult(String name, boolean passed, String message) {
      this.name = name;
      this.passed = passed;
      this.message = message == null ? "" : message;
    }

    public String name() {
      return name;
    }

    public boolean passed() {
      return passed;
    }

    public String message() {
      return message;
    }

    public Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("name", name);
      map.put("passed", passed);
      if (!passed) {
        map.put("message", message);
      }
      return map;
    }
  }

  /** 一次工具调用。 */
  public static final class ToolCall {

    private final String name;
    private final String arguments;
    private final String result;
    private final boolean success;
    private final long durationMs;

    public ToolCall(
        String name, String arguments, String result, boolean success, long durationMs) {
      this.name = name;
      this.arguments = arguments == null ? "" : arguments;
      this.result = result == null ? "" : result;
      this.success = success;
      this.durationMs = durationMs;
    }

    public String name() {
      return name;
    }

    public String arguments() {
      return arguments;
    }

    public String result() {
      return result;
    }

    public boolean success() {
      return success;
    }

    public long durationMs() {
      return durationMs;
    }

    public Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("name", name);
      map.put("arguments", arguments);
      map.put("result", result);
      map.put("success", success);
      map.put("durationMs", durationMs);
      return map;
    }
  }

  /** 单条任务结果。 */
  public static final class TaskResult {

    private final EvalTask task;
    private final boolean passed;
    private final FailureKind failureKind;
    private final List<CheckResult> checks;
    private final List<ToolCall> toolCalls;
    private final int invalidToolCalls;
    private final long promptTokens;
    private final long completionTokens;
    private final long durationMs;
    private final String finalMessage;
    private final String error;
    private final String outputPath;

    public TaskResult(
        EvalTask task,
        boolean passed,
        FailureKind failureKind,
        List<CheckResult> checks,
        List<ToolCall> toolCalls,
        int invalidToolCalls,
        long promptTokens,
        long completionTokens,
        long durationMs,
        String finalMessage,
        String error,
        String outputPath) {
      this.task = task;
      this.passed = passed;
      this.failureKind = failureKind;
      this.checks = List.copyOf(checks);
      this.toolCalls = List.copyOf(toolCalls);
      this.invalidToolCalls = invalidToolCalls;
      this.promptTokens = promptTokens;
      this.completionTokens = completionTokens;
      this.durationMs = durationMs;
      this.finalMessage = finalMessage == null ? "" : finalMessage;
      this.error = error == null ? "" : error;
      this.outputPath = outputPath == null ? "" : outputPath;
    }

    public EvalTask task() {
      return task;
    }

    public boolean passed() {
      return passed;
    }

    public FailureKind failureKind() {
      return failureKind;
    }

    public List<CheckResult> checks() {
      return checks;
    }

    public List<ToolCall> toolCalls() {
      return toolCalls;
    }

    public int invalidToolCalls() {
      return invalidToolCalls;
    }

    public long promptTokens() {
      return promptTokens;
    }

    public long completionTokens() {
      return completionTokens;
    }

    public long durationMs() {
      return durationMs;
    }

    public String finalMessage() {
      return finalMessage;
    }

    public String error() {
      return error;
    }

    public String outputPath() {
      return outputPath;
    }

    public long totalTokens() {
      return promptTokens + completionTokens;
    }

    public Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("taskId", task.id());
      map.put("title", task.title());
      map.put("prompt", task.prompt());
      map.put("capabilityGap", task.capabilityGap());
      map.put("passed", passed);
      map.put("failureKind", failureKind.value());
      map.put("checks", mapList(checks));
      map.put("toolCalls", mapList(toolCalls));
      map.put("toolCallCount", toolCalls.size());
      map.put("invalidToolCalls", invalidToolCalls);
      map.put("promptTokens", promptTokens);
      map.put("completionTokens", completionTokens);
      map.put("totalTokens", totalTokens());
      map.put("durationMs", durationMs);
      map.put("finalMessage", finalMessage);
      map.put("error", error);
      map.put("outputPath", outputPath);
      return map;
    }
  }

  /** 运行汇总。 */
  public static final class Summary {

    private final int total;
    private final int passed;
    private final int failed;
    private final double passRate;
    private final double avgToolCalls;
    private final long promptTokens;
    private final long completionTokens;
    private final long durationMs;

    public Summary(
        int total,
        int passed,
        int failed,
        double passRate,
        double avgToolCalls,
        long promptTokens,
        long completionTokens,
        long durationMs) {
      this.total = total;
      this.passed = passed;
      this.failed = failed;
      this.passRate = passRate;
      this.avgToolCalls = avgToolCalls;
      this.promptTokens = promptTokens;
      this.completionTokens = completionTokens;
      this.durationMs = durationMs;
    }

    public static Summary from(List<TaskResult> results) {
      int passed = 0;
      int toolCalls = 0;
      long prompt = 0;
      long completion = 0;
      long duration = 0;
      for (TaskResult result : results) {
        if (result.passed()) {
          passed++;
        }
        toolCalls += result.toolCalls().size();
        prompt += result.promptTokens();
        completion += result.completionTokens();
        duration += result.durationMs();
      }
      int total = results.size();
      return new Summary(
          total,
          passed,
          total - passed,
          total == 0 ? 0.0 : (double) passed / total,
          total == 0 ? 0.0 : (double) toolCalls / total,
          prompt,
          completion,
          duration);
    }

    public int total() {
      return total;
    }

    public int passed() {
      return passed;
    }

    public int failed() {
      return failed;
    }

    public double passRate() {
      return passRate;
    }

    public double avgToolCalls() {
      return avgToolCalls;
    }

    public long promptTokens() {
      return promptTokens;
    }

    public long completionTokens() {
      return completionTokens;
    }

    public long durationMs() {
      return durationMs;
    }

    public Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("total", total);
      map.put("passed", passed);
      map.put("failed", failed);
      map.put("passRate", passRate);
      map.put("avgToolCalls", avgToolCalls);
      map.put("promptTokens", promptTokens);
      map.put("completionTokens", completionTokens);
      map.put("totalTokens", promptTokens + completionTokens);
      map.put("durationMs", durationMs);
      return map;
    }
  }

  /** 一次完整运行的报告。 */
  public static final class RunReport {

    private final String runId;
    private final String startedAt;
    private final String gitCommit;
    private final String baseUrl;
    private final String model;
    private final int toolCount;
    private final int skillCount;
    private final String surface;
    private final String group;
    private final List<TaskResult> tasks;
    private final Summary summary;

    public RunReport(
        String runId,
        String startedAt,
        String gitCommit,
        String baseUrl,
        String model,
        int toolCount,
        int skillCount,
        String surface,
        String group,
        List<TaskResult> tasks) {
      this.runId = runId;
      this.startedAt = startedAt;
      this.gitCommit = gitCommit;
      this.baseUrl = baseUrl;
      this.model = model;
      this.toolCount = toolCount;
      this.skillCount = skillCount;
      this.surface = surface == null || surface.isBlank() ? "full" : surface;
      this.group = group == null || group.isBlank() ? "baseline" : group;
      this.tasks = List.copyOf(tasks);
      this.summary = Summary.from(tasks);
    }

    /** 工具面名：full / compiler。 */
    public String surface() {
      return surface;
    }

    /** 任务组名：baseline / create。 */
    public String group() {
      return group;
    }

    public String runId() {
      return runId;
    }

    public String startedAt() {
      return startedAt;
    }

    public String gitCommit() {
      return gitCommit;
    }

    public String baseUrl() {
      return baseUrl;
    }

    public String model() {
      return model;
    }

    public int toolCount() {
      return toolCount;
    }

    public int skillCount() {
      return skillCount;
    }

    public List<TaskResult> tasks() {
      return tasks;
    }

    public Summary summary() {
      return summary;
    }

    public Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("runId", runId);
      map.put("startedAt", startedAt);
      map.put("gitCommit", gitCommit);
      map.put("baseUrl", baseUrl);
      map.put("model", model);
      map.put("toolCount", toolCount);
      map.put("skillCount", skillCount);
      map.put("surface", surface);
      map.put("group", group);
      map.put("tasks", mapList(tasks));
      map.put("summary", summary.toMap());
      return map;
    }
  }

  private EvalResult() {}

  private static List<Object> mapList(List<?> values) {
    List<Object> out = new ArrayList<>(values.size());
    for (Object value : values) {
      if (value instanceof CheckResult) {
        out.add(((CheckResult) value).toMap());
      } else if (value instanceof ToolCall) {
        out.add(((ToolCall) value).toMap());
      } else if (value instanceof TaskResult) {
        out.add(((TaskResult) value).toMap());
      } else {
        out.add(value);
      }
    }
    return out;
  }
}
