package io.github.nondirectional.docx.examples.eval;

import java.nio.file.Path;

/**
 * 一次评测运行的配置。
 *
 * <p>由 {@link EvalMain} 从 CLI / 环境变量组装；不可变，便于在运行器与报告之间传递。
 */
public final class EvalConfig {

  private final String baseUrl;
  private final String model;
  private final String apiKey;
  private final int maxIterations;
  private final int timeoutSeconds;
  private final int thinkingBudget;
  private final double temperature;
  private final Path outputDir;

  public EvalConfig(
      String baseUrl,
      String model,
      String apiKey,
      int maxIterations,
      int timeoutSeconds,
      int thinkingBudget,
      double temperature,
      Path outputDir) {
    this.baseUrl = baseUrl;
    this.model = model;
    this.apiKey = apiKey == null ? "" : apiKey;
    this.maxIterations = maxIterations;
    this.timeoutSeconds = timeoutSeconds;
    this.thinkingBudget = thinkingBudget;
    this.temperature = temperature;
    this.outputDir = outputDir;
  }

  public String baseUrl() {
    return baseUrl;
  }

  public String model() {
    return model;
  }

  public String apiKey() {
    return apiKey;
  }

  public int maxIterations() {
    return maxIterations;
  }

  public int timeoutSeconds() {
    return timeoutSeconds;
  }

  public int thinkingBudget() {
    return thinkingBudget;
  }

  public double temperature() {
    return temperature;
  }

  public Path outputDir() {
    return outputDir;
  }
}
