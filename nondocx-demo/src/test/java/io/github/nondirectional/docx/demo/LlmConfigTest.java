package io.github.nondirectional.docx.demo;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link LlmConfig} 启动校验测试：本地起一个 OpenAI 兼容的 {@code /models} 端点，
 * 锁定三种结果——模型存在（null）、模型缺失（错误含可用清单）、端点不可达（错误含根因）。
 *
 * <p>背景：写死 {@code qwen3-14b} 的时代，端点换模型要等第一次对话才 404；校验移到启动期后， 「配置错了」在启动日志就能看到，且不拖垮非 AI 功能。
 */
class LlmConfigTest {

  private HttpServer server;
  private String baseUrl;

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newSingleThreadExecutor());
    server.createContext(
        "/v1/models",
        exchange -> {
          byte[] body =
              "{\"data\":[{\"id\":\"qwen3.8-27b\"},{\"id\":\"qwen2.5-72b\"}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void validatePassesWhenModelIsServed() {
    LlmConfig config = new LlmConfig("sk-test", baseUrl, "qwen3.8-27b");
    assertNull(config.validateModelAvailable());
  }

  @Test
  void validateListsAvailableModelsWhenConfiguredModelMissing() {
    LlmConfig config = new LlmConfig("sk-test", baseUrl, "qwen3-14b");
    String problem = config.validateModelAvailable();
    assertNotNull(problem);
    assertTrue(problem.contains("qwen3-14b"), problem);
    assertTrue(problem.contains("qwen3.8-27b"), problem);
    assertTrue(problem.contains("qwen2.5-72b"), problem);
    assertTrue(problem.contains("NONDOCX_LLM_MODEL"), problem);
  }

  @Test
  void validateReportsUnreachableEndpointWithRootCause() {
    // 端口 1 上基本不会有 HTTP 服务；连接失败应给出「无法连接 + 环境变量提示」
    LlmConfig config = new LlmConfig("sk-test", "http://127.0.0.1:1/v1", "qwen3.8-27b");
    String problem = config.validateModelAvailable();
    assertNotNull(problem);
    assertTrue(problem.contains("无法连接"), problem);
    assertTrue(problem.contains("NONDOCX_LLM_BASE_URL"), problem);
  }

  @Test
  void baseUrlWithoutTrailingSlashAlsoValidated() {
    LlmConfig config = new LlmConfig("sk-test", baseUrl + "/", "qwen3.8-27b");
    assertNull(config.validateModelAvailable());
  }
}
