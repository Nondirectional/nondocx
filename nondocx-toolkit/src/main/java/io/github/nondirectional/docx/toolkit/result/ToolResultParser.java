package io.github.nondirectional.docx.toolkit.result;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 {@code @ToolDef} 方法返回的 String 中提取结构化信息。
 *
 * <p>解析 {@link ToolResultRenderer} 产出的 JSON fence 块，提取 {@code success}、 {@code code}、{@code
 * message}、{@code suggestion} 等字段，供 executor 消费。 不嗅探中文前缀（{@code startsWith("错误")}）。
 *
 * <p>双模式：优先解析 JSON fence；解析失败返回 {@code null}，交由调用方回退旧路径 （迁移混合期兼容层）。
 *
 * <p>无状态工具类，线程安全。
 */
public final class ToolResultParser {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * 匹配 {@code ```json ... ``` } 块。
   *
   * <p>DOTALL 让 {@code .} 匹配换行；非贪婪避免跨多个 fence。
   */
  private static final Pattern JSON_FENCE =
      Pattern.compile("```json\\s*(\\{.*?})\\s*```", Pattern.DOTALL);

  private ToolResultParser() {}

  /**
   * 解析 JSON fence，返回结构化快照。
   *
   * @param toolOutput {@code @ToolDef} 方法的返回 String
   * @return 结构化快照；无 JSON fence 或解析失败返回 {@code null}
   */
  public static Snapshot parse(String toolOutput) {
    if (toolOutput == null || toolOutput.isEmpty()) {
      return null;
    }
    JsonNode node = extractJsonNode(toolOutput);
    if (node == null) {
      return null;
    }
    if (!node.has("success") && !node.has("code")) {
      return null;
    }
    boolean success = node.has("success") && node.get("success").asBoolean();
    String codeValue = node.has("code") ? node.get("code").asText() : null;
    ToolResultCode code = codeValue != null ? ToolResultCode.fromValue(codeValue) : null;
    String message = node.has("message") ? node.get("message").asText() : "";
    String suggestion = node.has("suggestion") ? node.get("suggestion").asText() : "";
    String dataText = extractDataText(node);
    return new Snapshot(success, code, message, suggestion, dataText);
  }

  /**
   * 解析 success 字段。
   *
   * @return true/false；无 JSON fence 返回 {@code null}
   */
  public static Boolean parseSuccess(String toolOutput) {
    Snapshot snap = parse(toolOutput);
    if (snap == null) {
      return null;
    }
    return snap.success();
  }

  /**
   * 解析 code 字段。
   *
   * @return 结果码；无 JSON fence 返回 {@code null}
   */
  public static ToolResultCode parseCode(String toolOutput) {
    Snapshot snap = parse(toolOutput);
    return snap != null ? snap.code() : null;
  }

  /** 是否包含 JSON fence。 */
  public static boolean hasStructuredEnvelope(String toolOutput) {
    if (toolOutput == null || toolOutput.isEmpty()) {
      return false;
    }
    return JSON_FENCE.matcher(toolOutput).find();
  }

  private static JsonNode extractJsonNode(String toolOutput) {
    Matcher matcher = JSON_FENCE.matcher(toolOutput);
    if (!matcher.find()) {
      return null;
    }
    String json = matcher.group(1);
    try {
      return MAPPER.readTree(json);
    } catch (Exception e) {
      return null;
    }
  }

  /** 提取 {@code data} 字段为字符串。标量直接返回文本值；对象/数组返回 JSON 字符串；无 data 返回 null。 */
  private static String extractDataText(JsonNode node) {
    if (!node.has("data")) {
      return null;
    }
    JsonNode data = node.get("data");
    if (data.isTextual()) {
      return data.asText();
    }
    try {
      return MAPPER.writeValueAsString(data);
    } catch (Exception e) {
      return data.toString();
    }
  }

  /** 结构化快照，只读。 */
  public static final class Snapshot {

    private final boolean success;
    private final ToolResultCode code;
    private final String message;
    private final String suggestion;
    private final String dataText;

    /** 构造快照。 */
    public Snapshot(boolean success, ToolResultCode code, String message, String suggestion) {
      this(success, code, message, suggestion, null);
    }

    /** 构造快照（含 data 文本）。 */
    public Snapshot(
        boolean success, ToolResultCode code, String message, String suggestion, String dataText) {
      this.success = success;
      this.code = code;
      this.message = message == null ? "" : message;
      this.suggestion = suggestion == null ? "" : suggestion;
      this.dataText = dataText;
    }

    /** 是否成功。 */
    public boolean success() {
      return success;
    }

    /** 结果码。 */
    public ToolResultCode code() {
      return code;
    }

    /** 中文消息。 */
    public String message() {
      return message;
    }

    /** 可重试建议。 */
    public String suggestion() {
      return suggestion;
    }

    /** data 字段的字符串表示；无 data 返回 null。 */
    public String dataText() {
      return dataText;
    }

    /** 是否失败。 */
    public boolean isFailure() {
      return !success;
    }
  }
}
