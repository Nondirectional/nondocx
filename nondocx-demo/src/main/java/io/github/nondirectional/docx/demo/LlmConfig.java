package io.github.nondirectional.docx.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * demo 的 LLM 端点配置：环境变量解析 + 启动时模型存在性校验。
 *
 * <p><b>为什么需要启动校验。</b> 此前 {@code AgentBridge} 把 base URL 和模型名写死，端点 下线旧模型后（{@code qwen3-14b} 已
 * 404）demo 要到<b>第一次对话</b>才报错，且报错是上游 的 JSON 而不是可操作的提示。规则改为：配置从环境变量来，启动时调一次 OpenAI 兼容的 {@code GET
 * /models}，模型不存在就<b>禁用 Agent</b>并把「可用模型清单」打进日志/状态—— 预览等非 AI 功能不受影响。
 *
 * <p><b>环境变量：</b>
 *
 * <ul>
 *   <li>{@code DASHSCOPE_API_KEY}——启用 Agent 的开关（缺失则禁用，行为不变）；
 *   <li>{@code NONDOCX_LLM_BASE_URL}——默认 {@code http://10.100.10.21:40002/v1}；
 *   <li>{@code NONDOCX_LLM_MODEL}——默认 {@code qwen3.8-27b}（端点当前实际提供的模型）。
 * </ul>
 */
final class LlmConfig {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String baseUrl;
  private final String model;
  private final String apiKey;

  LlmConfig(String apiKey) {
    this(
        apiKey,
        envOrDefault("NONDOCX_LLM_BASE_URL", "http://10.100.10.21:40002/v1"),
        envOrDefault("NONDOCX_LLM_MODEL", "qwen3.8-27b"));
  }

  /** 测试用完整构造：直接指定三项配置，不读环境变量。 */
  LlmConfig(String apiKey, String baseUrl, String model) {
    this.apiKey = apiKey == null ? "" : apiKey.trim();
    this.baseUrl = baseUrl == null ? "http://10.100.10.21:40002/v1" : baseUrl.trim();
    this.model = model == null || model.isBlank() ? "qwen3.8-27b" : model.trim();
  }

  private static String envOrDefault(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  String baseUrl() {
    return baseUrl;
  }

  String model() {
    return model;
  }

  String apiKey() {
    return apiKey;
  }

  /**
   * 校验端点确实提供配置的模型。
   *
   * <p>调 {@code GET {baseUrl}/models}（OpenAI 兼容），检查 {@code data[].id} 里是否有 {@link
   * #model()}。网络失败按「不可达」处理，同样返回错误——启动期就暴露问题， 而不是留到第一次对话。
   *
   * @return {@code null} 表示校验通过；否则为中文错误描述（含可用模型清单或根因）
   */
  String validateModelAvailable() {
    String url = baseUrl.endsWith("/") ? baseUrl + "models" : baseUrl + "/models";
    try {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET();
      if (!apiKey.isEmpty()) {
        request.header("Authorization", "Bearer " + apiKey);
      }
      HttpResponse<String> response =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(5))
              .build()
              .send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return "模型校验失败：GET " + url + " 返回 HTTP " + response.statusCode();
      }
      List<String> models = parseModelIds(response.body());
      if (models.isEmpty()) {
        return "模型校验失败：" + url + " 响应里没有 data[].id 模型清单";
      }
      if (!models.contains(model)) {
        return "端点不提供模型「"
            + model
            + "」，可用模型："
            + String.join("、", models)
            + "（用 NONDOCX_LLM_MODEL 指定）";
      }
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "模型校验被中断";
    } catch (Exception e) {
      Throwable root = e;
      while (root.getCause() != null && root.getCause() != root) {
        root = root.getCause();
      }
      return "无法连接 " + url + "（" + root.getMessage() + "），用 NONDOCX_LLM_BASE_URL 指定端点";
    }
  }

  /** 解析 OpenAI 兼容 {@code /models} 响应的 {@code data[].id}；解析失败返回空列表。 */
  private static List<String> parseModelIds(String body) {
    List<String> ids = new ArrayList<>();
    try {
      JsonNode data = MAPPER.readTree(body).path("data");
      if (data.isArray()) {
        for (JsonNode item : data) {
          String id = item.path("id").asText(null);
          if (id != null && !id.isBlank()) {
            ids.add(id);
          }
        }
      }
    } catch (Exception ignored) {
      // 响应不是合法 JSON：按「无模型清单」处理，由调用方报错
    }
    return ids;
  }
}
