package io.github.nondirectional.docx.toolkit.capability.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 完整能力清单。{@link io.github.nondirectional.docx.toolkit.capability.CapabilityCollector} 的输出。
 *
 * <p>不可变值对象。包含 schema 版本、内容摘要（digest，排除 generatedAt 以保证稳定）、 工具列表与元素索引。
 *
 * <p>digest 的设计：只 hash tools + elementIndex 的 canonical JSON， <b>排除</b> generatedAt， 让 Agent
 * 据此判断"能力是否变化"而不被时间戳干扰。
 */
public final class CapabilityManifest {

  public static final String SCHEMA_VERSION = "nondocx-capability/v1";

  private final String schemaVersion;
  private final String digest;
  private final String generatedAt;
  private final List<ToolCapabilityDescriptor> tools;
  private final Map<String, List<String>> elementIndex;

  public CapabilityManifest(
      String digest,
      String generatedAt,
      List<ToolCapabilityDescriptor> tools,
      Map<String, List<String>> elementIndex) {
    this.schemaVersion = SCHEMA_VERSION;
    this.digest = digest == null ? "" : digest;
    this.generatedAt = generatedAt == null ? "" : generatedAt;
    this.tools = tools == null ? List.of() : List.copyOf(tools);
    // elementIndex 的 value 列表也需要不可变拷贝
    java.util.Map<String, List<String>> copy = new java.util.LinkedHashMap<>();
    if (elementIndex != null) {
      for (var e : elementIndex.entrySet()) {
        copy.put(e.getKey(), List.copyOf(e.getValue()));
      }
    }
    this.elementIndex = java.util.Collections.unmodifiableMap(copy);
  }

  public String schemaVersion() {
    return schemaVersion;
  }

  public String digest() {
    return digest;
  }

  public String generatedAt() {
    return generatedAt;
  }

  public List<ToolCapabilityDescriptor> tools() {
    return tools;
  }

  /** 元素 → 工具名列表的聚合索引，自动从工具的 element 字段生成。 */
  public Map<String, List<String>> elementIndex() {
    return elementIndex;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof CapabilityManifest)) return false;
    CapabilityManifest that = (CapabilityManifest) o;
    // digest 已是 tools+elementIndex 的摘要，相等即内容相等（不含 generatedAt）
    return digest.equals(that.digest);
  }

  @Override
  public int hashCode() {
    return Objects.hash(digest);
  }
}
