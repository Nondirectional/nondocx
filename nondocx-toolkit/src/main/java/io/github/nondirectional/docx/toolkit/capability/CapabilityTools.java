package io.github.nondirectional.docx.toolkit.capability;

import com.non.chain.tool.ToolDef;
import com.non.chain.tool.ToolParam;
import io.github.nondirectional.docx.toolkit.capability.model.CapabilityManifest;
import io.github.nondirectional.docx.toolkit.capability.model.ToolCapabilityDescriptor;
import io.github.nondirectional.docx.toolkit.result.ToolResult;
import io.github.nondirectional.docx.toolkit.result.ToolResultCode;
import io.github.nondirectional.docx.toolkit.result.ToolResultRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 能力契约工具组（第 8 组）：提供 {@code describe_capabilities} 工具，让 Agent 查询 nondocx-toolkit 的机器可读能力清单。
 *
 * <p><b>无会话状态。</b> 本类不持有文档会话（sessions/seq），只持有 7 个工具实例的引用用于反射收集能力元数据。
 * 它不参与文档读写，因此构造时只需传入工具实例，无需共享会话状态。
 *
 * <p><b>自身参与 manifest。</b> {@code describe_capabilities} 自身也标注 {@link ToolCapability}，
 * 被纳入完整能力清单（operation=QUERY）。但为避免自引用循环，它不把自己作为反射目标再次收集—— {@link #collectManifest()} 显式传入 7 个文档工具实例
 * + 本类实例。
 */
public final class CapabilityTools {

  private final Object[] documentTools;
  private volatile CapabilityManifest cached;

  /**
   * @param documentTools 7 个文档工具实例（session/body/table/headerFooterToc/trackedChangeQuery/
   *     trackedChangeAuthoring/qualityCheck），用于反射收集能力。
   */
  public CapabilityTools(Object... documentTools) {
    this.documentTools = documentTools;
  }

  /** 收集完整能力清单（含本类的 describe_capabilities）。结果惰性缓存。 */
  public CapabilityManifest collectManifest() {
    CapabilityManifest c = cached;
    if (c == null) {
      synchronized (this) {
        c = cached;
        if (c == null) {
          // 收集 7 个文档工具 + 本类（让 describe_capabilities 也进 manifest）
          List<Object> all = new ArrayList<>();
          for (Object t : documentTools) {
            if (t != null) {
              all.add(t);
            }
          }
          all.add(this);
          c = CapabilityCollector.collect(all.toArray());
          cached = c;
        }
      }
    }
    return c;
  }

  /** 清除缓存（测试或强制刷新用）。 */
  public void invalidateCache() {
    cached = null;
  }

  @ToolDef(
      name = "describe_capabilities",
      description =
          "查询 nondocx-toolkit 的能力清单(机器可读)。可按 element(如 paragraph/table/run)、"
              + "operation(READ/ADD/UPDATE/REMOVE/QUERY/SESSION/QUALITY)、level(STABLE/WORD_ONLY/EXPERIMENTAL)"
              + "过滤。返回每个工具的名称、操作类别、元素、参数类型/枚举值/单位。"
              + "Agent 改文档前应先调用它确认参数名与枚举值,不要猜测。")
  @ToolCapability(operation = CapabilityOperation.QUERY)
  public String describeCapabilities(
      @ToolParam(
              name = "element",
              description = "按元素过滤,如 paragraph/table/run/cell/header_footer/tracked_change",
              required = false)
          @ParamCapability(type = ParamType.STRING)
          String element,
      @ToolParam(
              name = "operation",
              description = "按操作过滤:READ/ADD/UPDATE/REMOVE/QUERY/SESSION/QUALITY",
              required = false)
          @ParamCapability(
              type = ParamType.ENUM,
              enumValues = {"READ", "ADD", "UPDATE", "REMOVE", "QUERY", "SESSION", "QUALITY"})
          String operation,
      @ToolParam(
              name = "level",
              description = "按稳定性过滤:STABLE/WORD_ONLY/EXPERIMENTAL",
              required = false)
          @ParamCapability(
              type = ParamType.ENUM,
              enumValues = {"STABLE", "WORD_ONLY", "EXPERIMENTAL"})
          String level) {

    CapabilityManifest manifest = collectManifest();
    List<ToolCapabilityDescriptor> filtered = new ArrayList<>(manifest.tools());

    // element 过滤（大小写不敏感）
    if (element != null && !element.isBlank()) {
      String e = element.trim().toLowerCase(Locale.ROOT);
      filtered.removeIf(t -> !t.element().toLowerCase(Locale.ROOT).equals(e));
    }
    // operation 过滤
    if (operation != null && !operation.isBlank()) {
      CapabilityOperation op =
          CapabilityOperation.fromValue(operation.trim().toLowerCase(Locale.ROOT));
      if (op == null) {
        // 兼容大写输入
        try {
          op = CapabilityOperation.valueOf(operation.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
          // 留空，下面报错
        }
      }
      if (op == null) {
        ToolResult<Void> r =
            ToolResult.fail(
                ToolResultCode.INVALID_ARGUMENT,
                "operation 不支持: " + operation,
                "合法值: READ/ADD/UPDATE/REMOVE/QUERY/SESSION/QUALITY");
        return ToolResultRenderer.render(r);
      }
      final CapabilityOperation finalOp = op;
      filtered.removeIf(t -> t.operation() != finalOp);
    }
    // level 过滤
    if (level != null && !level.isBlank()) {
      CapabilityLevel lv = CapabilityLevel.fromValue(level.trim().toLowerCase(Locale.ROOT));
      if (lv == null) {
        try {
          lv = CapabilityLevel.valueOf(level.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
          // 留空
        }
      }
      if (lv == null) {
        ToolResult<Void> r =
            ToolResult.fail(
                ToolResultCode.INVALID_ARGUMENT,
                "level 不支持: " + level,
                "合法值: STABLE/WORD_ONLY/EXPERIMENTAL");
        return ToolResultRenderer.render(r);
      }
      final CapabilityLevel finalLv = lv;
      filtered.removeIf(t -> t.level() != finalLv);
    }

    // 构建过滤后的视图：data 用 Map 结构（Jackson 可序列化），digest 用完整 manifest 的
    String summary =
        "能力清单（"
            + filtered.size()
            + "/"
            + manifest.tools().size()
            + " 个工具，digest="
            + manifest.digest().substring(0, Math.min(12, manifest.digest().length()))
            + "）";

    List<Object> toolMaps = new ArrayList<>();
    for (ToolCapabilityDescriptor t : filtered) {
      toolMaps.add(CapabilityJsonIo.toolToMapPublic(t));
    }
    java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
    data.put("schemaVersion", manifest.schemaVersion());
    data.put("digest", manifest.digest());
    data.put("toolCount", filtered.size());
    data.put("totalToolCount", manifest.tools().size());
    data.put("tools", toolMaps);

    ToolResult<java.util.Map<String, Object>> result = ToolResult.ok(data, summary);
    return ToolResultRenderer.render(result);
  }
}
