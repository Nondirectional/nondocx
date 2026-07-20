package io.github.nondirectional.docx.toolkit.result;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 {@link ToolResult} 渲染为双段 String：中文人类可读消息 + JSON fence 块。
 *
 * <p>nonchain 框架对 {@code @ToolDef} 返回值只做 {@code Object.toString()}，不会 JSON 序列化 POJO。因此
 * {@code @ToolDef} 方法内部构建 {@link ToolResult} 后，调用 {@link #render(ToolResult)} 得到 String 再返回。
 *
 * <h3>输出格式</h3>
 *
 * <pre>{@code
 * <中文消息>
 * ```json
 * {"success":true,"code":"ok","data":{...},"matchedCount":1,"changedRefs":[...]}
 * ```
 * }</pre>
 *
 * <p>失败时消息尾部追加 {@code [code]}，并输出 {@code suggestion} 字段：
 *
 * <pre>{@code
 * run 索引 5 越界（共 2）[index_out_of_range]
 * ```json
 * {"success":false,"code":"index_out_of_range","message":"...","suggestion":"使用 0..1"}
 * ```
 * }</pre>
 *
 * <p>无状态工具类，线程安全。
 */
public final class ToolResultRenderer {

  /** JSON fence 开始标记。 */
  public static final String JSON_FENCE_START = "```json";

  /** JSON fence 结束标记。 */
  public static final String JSON_FENCE_END = "```";

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .setSerializationInclusion(JsonInclude.Include.NON_NULL)
          .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
          .setVisibility(
              com.fasterxml.jackson.annotation.PropertyAccessor.FIELD,
              com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
          .setVisibility(
              com.fasterxml.jackson.annotation.PropertyAccessor.GETTER,
              com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE);

  private ToolResultRenderer() {}

  /**
   * 渲染双段 String。
   *
   * @param result 结构化结果
   * @return 中文消息 + JSON fence
   */
  public static String render(ToolResult<?> result) {
    if (result == null) {
      return "null";
    }
    StringBuilder sb = new StringBuilder();
    sb.append(renderMessage(result));
    sb.append('\n');
    sb.append(JSON_FENCE_START).append('\n');
    sb.append(renderJson(result));
    sb.append('\n').append(JSON_FENCE_END);
    return sb.toString();
  }

  /**
   * 只渲染中文消息（不含 JSON fence）。
   *
   * <p>失败时尾部追加 {@code [code]}。
   */
  public static String renderMessage(ToolResult<?> result) {
    if (result == null) {
      return "null";
    }
    StringBuilder sb = new StringBuilder(result.message());
    if (!result.success()) {
      sb.append('[').append(result.code().value()).append(']');
    }
    return sb.toString();
  }

  /**
   * 只渲染 JSON 块（不含 fence 标记）。
   *
   * <p>序列化失败时回退为最小 JSON，保证不抛异常。
   */
  public static String renderJson(ToolResult<?> result) {
    if (result == null) {
      return "{\"success\":false,\"code\":\"invalid_argument\",\"message\":\"result 为 null\"}";
    }
    Map<String, Object> envelope = buildEnvelope(result);
    try {
      return MAPPER.writeValueAsString(envelope);
    } catch (Exception e) {
      // 序列化兜底：只输出已知安全字段，避免 data 不可序列化导致工具崩溃
      Map<String, Object> fallback = new LinkedHashMap<>();
      fallback.put("success", result.success());
      fallback.put("code", result.code().value());
      fallback.put("message", result.message());
      fallback.put("__serializeError", e.getClass().getSimpleName() + ": " + e.getMessage());
      try {
        return MAPPER.writeValueAsString(fallback);
      } catch (Exception e2) {
        return "{\"success\":" + result.success() + ",\"code\":\"" + result.code().value() + "\"}";
      }
    }
  }

  private static Map<String, Object> buildEnvelope(ToolResult<?> result) {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("success", result.success());
    envelope.put("code", result.code().value());
    if (!result.message().isEmpty()) {
      envelope.put("message", result.message());
    }
    if (result.data() != null) {
      envelope.put("data", result.data());
    }
    if (!result.warnings().isEmpty()) {
      envelope.put("warnings", renderWarnings(result.warnings()));
    }
    if (!result.changedRefs().isEmpty()) {
      envelope.put("changedRefs", result.changedRefs());
    }
    if (result.matchedCount() != null) {
      envelope.put("matchedCount", result.matchedCount());
    }
    if (result.changedCount() != null) {
      envelope.put("changedCount", result.changedCount());
    }
    if (result.skippedCount() != null) {
      envelope.put("skippedCount", result.skippedCount());
    }
    if (!result.suggestion().isEmpty()) {
      envelope.put("suggestion", result.suggestion());
    }
    return envelope;
  }

  private static List<Map<String, Object>> renderWarnings(List<ToolWarning> warnings) {
    java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
    for (ToolWarning w : warnings) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("code", w.code());
      m.put("message", w.message());
      if (w.ref() != null) {
        m.put("ref", w.ref());
      }
      out.add(m);
    }
    return out;
  }
}
