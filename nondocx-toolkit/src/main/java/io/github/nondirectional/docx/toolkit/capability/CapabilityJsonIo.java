package io.github.nondirectional.docx.toolkit.capability;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.nondirectional.docx.toolkit.capability.model.CapabilityManifest;
import io.github.nondirectional.docx.toolkit.capability.model.ParamCapabilityDescriptor;
import io.github.nondirectional.docx.toolkit.capability.model.ToolCapabilityDescriptor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link CapabilityManifest} ↔ JSON 序列化。
 *
 * <p>复用 P0-02 {@code ToolResultRenderer} 的 Jackson 风格（静态 ObjectMapper、NON_NULL、 LinkedHashMap
 * 保持字段顺序）。manifest 序列化为 {@code capabilities.json}，供 Agent 离线读取与构建期落盘。
 *
 * <p>无状态工具类，线程安全。
 */
public final class CapabilityJsonIo {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .setSerializationInclusion(JsonInclude.Include.NON_NULL)
          .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

  private CapabilityJsonIo() {}

  /** 序列化整个 manifest 为 JSON 字符串（格式化缩进，便于人工审阅）。 */
  public static String toJson(CapabilityManifest manifest) {
    try {
      return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(toMap(manifest));
    } catch (Exception e) {
      throw new CapabilityDeclarationException("manifest 序列化失败", e);
    }
  }

  /** 序列化为紧凑 JSON（无缩进），用于 digest 或网络传输。 */
  public static String toCompactJson(CapabilityManifest manifest) {
    try {
      return MAPPER.writeValueAsString(toMap(manifest));
    } catch (Exception e) {
      throw new CapabilityDeclarationException("manifest 序列化失败", e);
    }
  }

  /** manifest → 有序 Map（保证字段顺序稳定）。 */
  static Map<String, Object> toMap(CapabilityManifest manifest) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("schemaVersion", manifest.schemaVersion());
    root.put("digest", manifest.digest());
    root.put("generatedAt", manifest.generatedAt());
    root.put("toolCount", manifest.tools().size());
    List<Object> tools = new ArrayList<>();
    for (ToolCapabilityDescriptor t : manifest.tools()) {
      tools.add(toolToMap(t));
    }
    root.put("tools", tools);
    root.put("elementIndex", manifest.elementIndex());
    return root;
  }

  /** 单个工具描述符 → 有序 Map（公开，供 describe_capabilities 工具复用）。 */
  public static Map<String, Object> toolToMapPublic(ToolCapabilityDescriptor t) {
    return toolToMap(t);
  }

  private static Map<String, Object> toolToMap(ToolCapabilityDescriptor t) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", t.name());
    m.put("description", t.description());
    m.put("operation", t.operation().value());
    m.put("element", t.element());
    m.put("level", t.level().value());
    m.put("needsRecalc", t.needsRecalc());
    m.put("since", t.since());
    if (!t.examples().isEmpty()) {
      m.put("examples", t.examples());
    }
    List<Object> params = new ArrayList<>();
    for (ParamCapabilityDescriptor p : t.params()) {
      params.add(paramToMap(p));
    }
    m.put("params", params);
    if (!t.nestedParams().isEmpty()) {
      List<Object> nested = new ArrayList<>();
      for (ParamCapabilityDescriptor p : t.nestedParams()) {
        nested.add(paramToMap(p));
      }
      m.put("nestedParams", nested);
    }
    return m;
  }

  private static Map<String, Object> paramToMap(ParamCapabilityDescriptor p) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", p.name());
    m.put("description", p.description());
    m.put("type", p.type().value());
    m.put("required", p.required());
    if (!p.enumValues().isEmpty()) {
      m.put("enumValues", p.enumValues());
    }
    if (!p.unit().isEmpty()) {
      m.put("unit", p.unit());
    }
    if (!p.defaultValue().isEmpty()) {
      m.put("defaultValue", p.defaultValue());
    }
    return m;
  }
}
