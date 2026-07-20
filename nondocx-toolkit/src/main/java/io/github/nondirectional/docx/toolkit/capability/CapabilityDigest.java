package io.github.nondirectional.docx.toolkit.capability;

import io.github.nondirectional.docx.toolkit.capability.model.CapabilityManifest;
import io.github.nondirectional.docx.toolkit.capability.model.ToolCapabilityDescriptor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 能力清单内容摘要（SHA-256）。
 *
 * <p>摘要输入为 tools + elementIndex 的 canonical 表示，<b>排除</b> generatedAt 与 schemaVersion， 保证"能力未变则
 * digest 不变"，让 Agent 据此判断是否复用缓存。
 *
 * <p>canonical 化：tools 按 name 排序，每个 tool 的字段按固定顺序拼接；elementIndex 按 key 排序。
 */
public final class CapabilityDigest {

  private CapabilityDigest() {}

  /** 计算 digest（SHA-256 的十六进制小写）。 */
  public static String compute(
      List<ToolCapabilityDescriptor> tools, Map<String, List<String>> elementIndex) {
    String canonical = canonicalize(tools, elementIndex);
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new CapabilityDeclarationException("SHA-256 不可用", e);
    }
  }

  /** 便捷重载：从 manifest 取 tools/elementIndex 计算。 */
  public static String compute(CapabilityManifest manifest) {
    return compute(manifest.tools(), manifest.elementIndex());
  }

  /** 生成 canonical 字符串，供 digest 与测试稳定性验证。 */
  static String canonicalize(
      List<ToolCapabilityDescriptor> tools, Map<String, List<String>> elementIndex) {
    StringBuilder sb = new StringBuilder();
    // tools 按 name 排序
    List<ToolCapabilityDescriptor> sorted = new ArrayList<>(tools);
    sorted.sort((a, b) -> a.name().compareTo(b.name()));
    for (ToolCapabilityDescriptor t : sorted) {
      sb.append("tool{");
      sb.append("name=").append(t.name()).append(';');
      sb.append("operation=").append(t.operation().value()).append(';');
      sb.append("element=").append(t.element()).append(';');
      sb.append("level=").append(t.level().value()).append(';');
      sb.append("needsRecalc=").append(t.needsRecalc()).append(';');
      sb.append("since=").append(t.since()).append(';');
      // examples 排序
      List<String> ex = new ArrayList<>(t.examples());
      Collections.sort(ex);
      sb.append("examples=").append(String.join(",", ex)).append(';');
      // params 按 name 排序
      sb.append("params=[");
      List<ParamSig> paramSigs = new ArrayList<>();
      for (var p : t.params()) {
        paramSigs.add(
            new ParamSig(
                p.name(),
                p.type().value(),
                p.required(),
                sortJoin(p.enumValues()),
                p.unit(),
                p.defaultValue()));
      }
      paramSigs.sort((a, b) -> a.name.compareTo(b.name));
      for (int i = 0; i < paramSigs.size(); i++) {
        if (i > 0) sb.append(',');
        sb.append(paramSigs.get(i).toCanonical());
      }
      sb.append("];");
      // nestedParams 按 path 排序
      sb.append("nested=[");
      List<NestedSig> nestedSigs = new ArrayList<>();
      for (var p : t.nestedParams()) {
        nestedSigs.add(
            new NestedSig(
                p.name(),
                p.type().value(),
                p.required(),
                sortJoin(p.enumValues()),
                p.unit(),
                p.defaultValue()));
      }
      nestedSigs.sort((a, b) -> a.path.compareTo(b.path));
      for (int i = 0; i < nestedSigs.size(); i++) {
        if (i > 0) sb.append(',');
        sb.append(nestedSigs.get(i).toCanonical());
      }
      sb.append("]}");
    }
    // elementIndex 按 key 排序
    sb.append("elementIndex{");
    List<String> keys = new ArrayList<>(elementIndex.keySet());
    Collections.sort(keys);
    for (int i = 0; i < keys.size(); i++) {
      if (i > 0) sb.append(',');
      String k = keys.get(i);
      List<String> names = new ArrayList<>(elementIndex.get(k));
      Collections.sort(names);
      sb.append(k).append("=").append(String.join(",", names));
    }
    sb.append('}');
    return sb.toString();
  }

  private static String sortJoin(List<String> values) {
    List<String> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    return String.join("|", sorted);
  }

  private static final class ParamSig {
    final String name;
    final String type;
    final boolean required;
    final String enumValues;
    final String unit;
    final String defaultValue;

    ParamSig(
        String name,
        String type,
        boolean required,
        String enumValues,
        String unit,
        String defaultValue) {
      this.name = name;
      this.type = type;
      this.required = required;
      this.enumValues = enumValues;
      this.unit = unit;
      this.defaultValue = defaultValue;
    }

    String toCanonical() {
      return name
          + ":"
          + type
          + ":req="
          + required
          + ":enum="
          + enumValues
          + ":unit="
          + unit
          + ":default="
          + defaultValue;
    }
  }

  private static final class NestedSig {
    final String path;
    final String type;
    final boolean required;
    final String enumValues;
    final String unit;
    final String defaultValue;

    NestedSig(
        String path,
        String type,
        boolean required,
        String enumValues,
        String unit,
        String defaultValue) {
      this.path = path;
      this.type = type;
      this.required = required;
      this.enumValues = enumValues;
      this.unit = unit;
      this.defaultValue = defaultValue;
    }

    String toCanonical() {
      return path
          + ":"
          + type
          + ":req="
          + required
          + ":enum="
          + enumValues
          + ":unit="
          + unit
          + ":default="
          + defaultValue;
    }
  }
}
