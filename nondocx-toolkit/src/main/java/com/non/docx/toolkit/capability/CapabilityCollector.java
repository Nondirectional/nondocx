package com.non.docx.toolkit.capability;

import com.non.chain.tool.ToolDef;
import com.non.chain.tool.ToolParam;
import com.non.docx.toolkit.capability.model.CapabilityManifest;
import com.non.docx.toolkit.capability.model.ParamCapabilityDescriptor;
import com.non.docx.toolkit.capability.model.ToolCapabilityDescriptor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 反射收集器：遍历工具实例，合并 nonchain 注解（{@link ToolDef}/{@link ToolParam}）与项目伴生注解 （{@link
 * ToolCapability}/{@link ParamCapability}/{@link NestedParamCapability}）， 构建完整的 {@link
 * CapabilityManifest}。
 *
 * <p>单一真实来源 = Java 代码 + 项目注解。本类是 manifest 的唯一构造入口， 从不被手写 manifest 反向覆盖。
 *
 * <h3>校验规则（违反即抛 {@link CapabilityDeclarationException}）</h3>
 *
 * <ul>
 *   <li>每个 {@link ToolDef} 方法<b>必须</b>有且仅有一个 {@link ToolCapability}。
 *   <li>{@link ParamCapability} 标注的参数<b>必须</b>同时有 {@link ToolParam}。
 *   <li>{@link ParamCapability#enumValues} 非空时 {@link ParamCapability#type} 必须为 {@link
 *       ParamType#ENUM}。
 *   <li>{@link NestedParamCapability#path} 首段必须与同位 {@link ToolParam#name} 一致。
 *   <li>{@link NestedParamCapability#enumValues} 非空时 type 必须为 ENUM。
 * </ul>
 *
 * <p>无状态工具类，线程安全（反射只读）。
 */
public final class CapabilityCollector {

  private CapabilityCollector() {}

  /**
   * 收集多个工具实例的能力清单。
   *
   * @param toolInstances 工具类实例（如 {@code DocxToolkit} 的 session/body/... 字段）
   * @return 完整能力清单
   */
  public static CapabilityManifest collect(Object... toolInstances) {
    List<ToolCapabilityDescriptor> tools = new ArrayList<>();
    for (Object instance : toolInstances) {
      if (instance == null) {
        continue;
      }
      collectFromInstance(instance, tools);
    }
    // 按 name 去重（同一工具不应重复声明）
    Map<String, ToolCapabilityDescriptor> byName = new LinkedHashMap<>();
    for (ToolCapabilityDescriptor t : tools) {
      if (byName.containsKey(t.name())) {
        throw new CapabilityDeclarationException(
            "工具能力重复声明: " + t.name() + "（检查多个工具类是否声明了同名 @ToolDef）");
      }
      byName.put(t.name(), t);
    }
    // elementIndex 聚合
    Map<String, List<String>> elementIndex = buildElementIndex(byName.values());
    // digest（排除 generatedAt）
    String digest = CapabilityDigest.compute(new ArrayList<>(byName.values()), elementIndex);
    String generatedAt = Instant.now().toString();
    return new CapabilityManifest(
        digest, generatedAt, new ArrayList<>(byName.values()), elementIndex);
  }

  private static void collectFromInstance(Object instance, List<ToolCapabilityDescriptor> out) {
    Class<?> clazz = instance.getClass();
    for (Method method : clazz.getDeclaredMethods()) {
      ToolDef toolDef = method.getAnnotation(ToolDef.class);
      if (toolDef == null) {
        continue;
      }
      out.add(describeTool(method, toolDef, instance.getClass()));
    }
  }

  private static ToolCapabilityDescriptor describeTool(
      Method method, ToolDef toolDef, Class<?> toolClass) {
    ToolCapability tc = method.getAnnotation(ToolCapability.class);
    if (tc == null) {
      throw new CapabilityDeclarationException(
          "@ToolDef 方法缺少 @ToolCapability: "
              + toolClass.getSimpleName()
              + "."
              + method.getName()
              + "（name="
              + toolDef.name()
              + "）。每个 @ToolDef 必须标注 @ToolCapability。");
    }

    List<ParamCapabilityDescriptor> params = new ArrayList<>();
    List<ParamCapabilityDescriptor> nestedParams = new ArrayList<>();
    for (Parameter param : method.getParameters()) {
      ToolParam tp = param.getAnnotation(ToolParam.class);
      if (tp == null) {
        // 非 @ToolParam 参数（如内部未暴露的），跳过；但若有 ParamCapability 却无 ToolParam 则报错
        ParamCapability pc = param.getAnnotation(ParamCapability.class);
        if (pc != null) {
          throw new CapabilityDeclarationException(
              "@ParamCapability 标注的参数缺少 @ToolParam: "
                  + toolClass.getSimpleName()
                  + "."
                  + method.getName()
                  + " 参数 "
                  + param.getName());
        }
        continue;
      }
      ParamCapabilityDescriptor paramDesc = describeParam(param, tp, method, toolClass);
      params.add(paramDesc);
      // 收集嵌套子字段
      collectNested(param, tp, method, toolClass, nestedParams);
    }

    return new ToolCapabilityDescriptor(
        toolDef.name(),
        toolDef.description(),
        tc.operation(),
        tc.element(),
        tc.level(),
        tc.needsRecalc(),
        tc.since(),
        Arrays.asList(tc.examples()),
        params,
        nestedParams);
  }

  private static ParamCapabilityDescriptor describeParam(
      Parameter param, ToolParam tp, Method method, Class<?> toolClass) {
    ParamCapability pc = param.getAnnotation(ParamCapability.class);
    ParamType type = pc != null ? pc.type() : inferType(param.getType());
    String[] enumValues = pc != null ? pc.enumValues() : new String[0];
    String unit = pc != null ? pc.unit() : "";
    String defaultValue = pc != null ? pc.defaultValue() : "";
    // 校验：enumValues 非空时 type 必须为 ENUM 或数组类型（多选枚举容器）
    if (enumValues.length > 0 && !allowsEnumValues(type)) {
      throw new CapabilityDeclarationException(
          "@ParamCapability.enumValues 非空但 type 不支持枚举值: "
              + toolClass.getSimpleName()
              + "."
              + method.getName()
              + " 参数 "
              + tp.name()
              + "（type="
              + type.value()
              + "，需为 ENUM/STRING_ARRAY/INTEGER_ARRAY/OBJECT_ARRAY）");
    }
    return new ParamCapabilityDescriptor(
        tp.name(),
        tp.description(),
        type,
        tp.required(),
        Arrays.asList(enumValues),
        unit,
        defaultValue);
  }

  private static void collectNested(
      Parameter param,
      ToolParam tp,
      Method method,
      Class<?> toolClass,
      List<ParamCapabilityDescriptor> out) {
    NestedParamCapability single = param.getAnnotation(NestedParamCapability.class);
    NestedParamCapabilities multi = param.getAnnotation(NestedParamCapabilities.class);
    List<NestedParamCapability> all = new ArrayList<>();
    if (single != null) {
      all.add(single);
    }
    if (multi != null) {
      all.addAll(Arrays.asList(multi.value()));
    }
    for (NestedParamCapability npc : all) {
      // 校验：path 首段必须与 ToolParam.name 一致
      String firstSegment = npc.path().split("\\.")[0];
      if (!firstSegment.equals(tp.name())) {
        throw new CapabilityDeclarationException(
            "@NestedParamCapability.path 首段与 @ToolParam.name 不一致: "
                + toolClass.getSimpleName()
                + "."
                + method.getName()
                + " 参数 "
                + tp.name()
                + " 的 nested path="
                + npc.path()
                + "（首段应为 '"
                + tp.name()
                + "'）");
      }
      // 校验：enumValues 非空时 type 必须为 ENUM 或数组类型
      if (npc.enumValues().length > 0 && !allowsEnumValues(npc.type())) {
        throw new CapabilityDeclarationException(
            "@NestedParamCapability.enumValues 非空但 type 不支持枚举值: "
                + toolClass.getSimpleName()
                + "."
                + method.getName()
                + " nested path="
                + npc.path()
                + "（type="
                + npc.type().value()
                + "）");
      }
      out.add(
          new ParamCapabilityDescriptor(
              npc.path(),
              "", // nested 无独立 description，由父参数 description 承载
              npc.type(),
              false, // nested 子字段 required 暂不单独声明
              Arrays.asList(npc.enumValues()),
              npc.unit(),
              npc.defaultValue()));
    }
  }

  /** enumValues 是否允许配该 type（ENUM 单选，或数组类型作多选枚举容器）。 */
  static boolean allowsEnumValues(ParamType type) {
    return type == ParamType.ENUM
        || type == ParamType.STRING_ARRAY
        || type == ParamType.INTEGER_ARRAY
        || type == ParamType.OBJECT_ARRAY;
  }

  /** 无 @ParamCapability 时，从 Java 参数类型推断 ParamType。 */
  static ParamType inferType(Class<?> javaType) {
    if (javaType == String.class) {
      return ParamType.STRING;
    }
    if (javaType == Integer.class || javaType == int.class) {
      return ParamType.INTEGER;
    }
    if (javaType == Double.class
        || javaType == double.class
        || javaType == Float.class
        || javaType == float.class) {
      return ParamType.NUMBER;
    }
    if (javaType == Boolean.class || javaType == boolean.class) {
      return ParamType.BOOLEAN;
    }
    if (List.class.isAssignableFrom(javaType)) {
      return ParamType.OBJECT_ARRAY;
    }
    return ParamType.STRING;
  }

  private static Map<String, List<String>> buildElementIndex(
      java.util.Collection<ToolCapabilityDescriptor> tools) {
    Map<String, List<String>> index = new LinkedHashMap<>();
    for (ToolCapabilityDescriptor t : tools) {
      String elem = t.element().isEmpty() ? "(meta)" : t.element();
      index.computeIfAbsent(elem, k -> new ArrayList<>()).add(t.name());
    }
    // 每个 element 的工具名排序，保证 digest 稳定
    Map<String, List<String>> sorted = new LinkedHashMap<>();
    for (var e : index.entrySet()) {
      List<String> names = new ArrayList<>(e.getValue());
      java.util.Collections.sort(names);
      sorted.put(e.getKey(), names);
    }
    return sorted;
  }
}
