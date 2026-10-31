package io.github.nondirectional.docx.examples.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 评测 CLI 入口。
 *
 * <pre>{@code
 * mvn -q -pl nondocx-examples \
 *   -Dexec.mainClass=io.github.nondirectional.docx.examples.eval.EvalMain \
 *   exec:java
 * }</pre>
 *
 * <p>默认对当前内网 vLLM 跑全部 10 条任务。可用 {@code --tasks} 只跑子集， {@code --list} 查看任务目录。真实运行需要可用的模型端点；单测不依赖模型。
 */
public final class EvalMain {

  private static final DateTimeFormatter RUN_ID_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

  private EvalMain() {}

  public static void main(String[] args) {
    Options options;
    try {
      options = Options.parse(args);
    } catch (IllegalArgumentException e) {
      System.err.println("参数错误: " + e.getMessage());
      System.err.println();
      printUsage();
      System.exit(2);
      return;
    }

    if (options.help) {
      printUsage();
      return;
    }
    if (options.list) {
      System.out.println("可用评测任务：");
      for (Map.Entry<String, String> entry : EvalTasks.catalog().entrySet()) {
        System.out.println("  " + entry.getKey() + "  " + entry.getValue());
      }
      return;
    }

    if (!"full".equals(options.surface) && !"compiler".equals(options.surface)) {
      System.err.println("未知 --surface: " + options.surface + "（可选 full / compiler）");
      System.exit(2);
      return;
    }
    List<EvalTask> selected = selectTasks(options.tasks, options.group);
    if (selected.isEmpty()) {
      System.err.println("没有匹配的任务；用 --list 查看可用 id。");
      System.exit(2);
      return;
    }

    EvalConfig config =
        new EvalConfig(
            options.baseUrl,
            options.model,
            options.apiKey,
            options.maxIterations,
            options.timeoutSeconds,
            options.thinkingBudget,
            options.temperature,
            options.outputDir,
            options.surface,
            options.group);

    String runId = LocalDateTime.now().format(RUN_ID_FORMAT);
    Path runDir = config.outputDir().resolve(runId);
    String startedAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    String gitCommit = gitCommit();

    int toolCount =
        "compiler".equals(config.surface())
            ? EvalRunner.compilerToolCount()
            : EvalRunner.exposedToolCount();
    System.out.println("nondocx Agent 评测基线");
    System.out.println("  runId:        " + runId);
    System.out.println("  model:        " + config.model());
    System.out.println("  baseUrl:      " + config.baseUrl());
    System.out.println("  surface:      " + config.surface() + "（工具 " + toolCount + "，不含 Skill）");
    System.out.println("  group:        " + config.group());
    System.out.println("  tasks:        " + selected.size());
    System.out.println("  output:       " + runDir.toAbsolutePath());
    System.out.println();

    EvalRunner runner = new EvalRunner(config);
    List<EvalResult.TaskResult> results = new ArrayList<>();
    for (EvalTask task : selected) {
      System.out.println("[RUN] " + task.id() + " · " + task.title());
      long started = System.currentTimeMillis();
      EvalResult.TaskResult result;
      try {
        result = runner.run(task, runDir.resolve(task.id()));
      } catch (RuntimeException e) {
        result =
            new EvalResult.TaskResult(
                task,
                false,
                EvalResult.FailureKind.AGENT_ERROR,
                List.of(new EvalResult.CheckResult("运行器异常", false, rootMessage(e))),
                List.of(),
                0,
                0,
                0,
                System.currentTimeMillis() - started,
                "",
                rootMessage(e),
                "");
      }
      results.add(result);
      System.out.println(
          "  -> "
              + (result.passed() ? "PASS" : "FAIL")
              + " kind="
              + result.failureKind().value()
              + " calls="
              + result.toolCalls().size()
              + " tokens="
              + result.totalTokens()
              + " duration="
              + result.durationMs()
              + "ms");
      if (!result.passed()) {
        for (EvalResult.CheckResult check : result.checks()) {
          if (!check.passed()) {
            System.out.println("     ✗ " + check.name() + ": " + check.message());
          }
        }
      }
    }

    EvalResult.RunReport report =
        new EvalResult.RunReport(
            runId,
            startedAt,
            gitCommit,
            config.baseUrl(),
            config.model(),
            toolCount,
            0,
            config.surface(),
            config.group(),
            results);

    try {
      ReportWriter.write(runDir, report);
    } catch (IOException e) {
      System.err.println("写报告失败: " + e.getMessage());
      System.exit(1);
      return;
    }

    EvalResult.Summary summary = report.summary();
    System.out.println();
    System.out.println("完成：");
    System.out.printf(
        Locale.ROOT,
        "  通过 %d/%d (%.1f%%)，平均工具调用 %.2f，token %d，总耗时 %.1fs%n",
        summary.passed(),
        summary.total(),
        summary.passRate() * 100.0,
        summary.avgToolCalls(),
        summary.promptTokens() + summary.completionTokens(),
        summary.durationMs() / 1000.0);
    System.out.println("  报告: " + runDir.resolve("report.md").toAbsolutePath());
    System.out.println("  JSON: " + runDir.resolve("report.json").toAbsolutePath());
  }

  private static List<EvalTask> selectTasks(String csv, String group) {
    if (csv == null || csv.isBlank()) {
      List<EvalTask> byGroup = EvalTasks.group(group);
      if (byGroup == null) {
        System.err.println("未知 --group: " + group + "（可选 baseline / create）");
        System.exit(2);
      }
      return byGroup;
    }
    List<EvalTask> selected = new ArrayList<>();
    for (String raw : csv.split(",")) {
      String id = raw.trim();
      if (id.isEmpty()) {
        continue;
      }
      EvalTask task = EvalTasks.byId(id);
      if (task == null) {
        System.err.println("未知任务 id: " + id);
        System.err.println("可用任务: " + EvalTasks.catalog().keySet());
        System.exit(2);
      }
      selected.add(task);
    }
    return selected;
  }

  private static String gitCommit() {
    try {
      Process process =
          new ProcessBuilder("git", "rev-parse", "--short", "HEAD")
              .directory(Path.of("").toAbsolutePath().toFile())
              .redirectErrorStream(true)
              .start();
      String output =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      process.waitFor();
      return output;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return "";
    }
  }

  private static String rootMessage(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
  }

  private static void printUsage() {
    System.out.println(
        "用法: EvalMain [选项]\n"
            + "\n"
            + "  --list                       列出任务\n"
            + "  --tasks id1,id2              只跑指定任务（默认取 --group 全组）\n"
            + "  --surface full|compiler      工具面（默认 full；compiler=编译器 5 入口）\n"
            + "  --group baseline|create      任务组（默认 baseline；create=从零生成文档）\n"
            + "  --base-url URL               vLLM base URL（env NONDOCX_EVAL_BASE_URL）\n"
            + "  --model NAME                 模型名（env NONDOCX_EVAL_MODEL）\n"
            + "  --api-key KEY                API key（env NONDOCX_EVAL_API_KEY）\n"
            + "  --max-iterations N           Agent 最大轮数（默认 24）\n"
            + "  --timeout-seconds N          单任务超时（默认 180）\n"
            + "  --thinking-budget N          thinking budget（默认 512）\n"
            + "  --temperature N              温度（默认 0.0）\n"
            + "  --output-dir DIR             输出根目录（默认 target/eval）\n"
            + "  --help                       显示帮助");
  }

  /** 解析后的 CLI 选项。 */
  private static final class Options {

    private String baseUrl = env("NONDOCX_EVAL_BASE_URL", "http://10.100.10.21:40002/v1");
    private String model = env("NONDOCX_EVAL_MODEL", "qwen3.8-27b");
    private String apiKey = env("NONDOCX_EVAL_API_KEY", "");
    private int maxIterations = 24;
    private int timeoutSeconds = 180;
    private int thinkingBudget = 512;
    private double temperature = 0.0;
    private Path outputDir = Path.of("target", "eval");
    private String tasks = "";
    private String surface = "full";
    private String group = "baseline";
    private boolean list;
    private boolean help;

    private static Options parse(String[] args) {
      Options options = new Options();
      for (int i = 0; i < args.length; i++) {
        String arg = args[i];
        switch (arg) {
          case "--help":
          case "-h":
            options.help = true;
            break;
          case "--list":
            options.list = true;
            break;
          case "--tasks":
            options.tasks = value(args, ++i, arg);
            break;
          case "--surface":
            options.surface = value(args, ++i, arg);
            break;
          case "--group":
            options.group = value(args, ++i, arg);
            break;
          case "--base-url":
            options.baseUrl = value(args, ++i, arg);
            break;
          case "--model":
            options.model = value(args, ++i, arg);
            break;
          case "--api-key":
            options.apiKey = value(args, ++i, arg);
            break;
          case "--max-iterations":
            options.maxIterations = parseInt(value(args, ++i, arg), arg);
            break;
          case "--timeout-seconds":
            options.timeoutSeconds = parseInt(value(args, ++i, arg), arg);
            break;
          case "--thinking-budget":
            options.thinkingBudget = parseInt(value(args, ++i, arg), arg);
            break;
          case "--temperature":
            options.temperature = parseDouble(value(args, ++i, arg), arg);
            break;
          case "--output-dir":
            options.outputDir = Path.of(value(args, ++i, arg));
            break;
          default:
            throw new IllegalArgumentException("未知参数: " + arg);
        }
      }
      return options;
    }

    private static String value(String[] args, int index, String option) {
      if (index >= args.length || args[index].startsWith("--")) {
        throw new IllegalArgumentException(option + " 需要一个值");
      }
      return args[index];
    }

    private static int parseInt(String value, String option) {
      try {
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) {
          throw new IllegalArgumentException(option + " 必须为正整数: " + value);
        }
        return parsed;
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(option + " 不是整数: " + value);
      }
    }

    private static double parseDouble(String value, String option) {
      try {
        return Double.parseDouble(value);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(option + " 不是数字: " + value);
      }
    }

    private static String env(String key, String fallback) {
      String value = System.getenv(key);
      return value == null || value.isBlank() ? fallback : value;
    }
  }
}
