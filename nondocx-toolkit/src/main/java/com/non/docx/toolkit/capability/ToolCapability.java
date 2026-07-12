package com.non.docx.toolkit.capability;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明一个 {@code @ToolDef} 工具的能力元数据：操作类别、元素类型、稳定性等级、是否需要 Word/WPS 重新计算。
 *
 * <p>与 nonchain {@code @ToolDef}（提供 name/description）同位标注。{@code @ToolDef} 方法 <b>必须</b>有且仅有一个本注解，否则
 * {@link CapabilityCollector} 在收集时抛出 {@link CapabilityDeclarationException}，构建失败。
 *
 * <p>本注解只补 nonchain 注解缺失的维度（operation/element/level/needsRecalc）， 不重复 name/description。
 *
 * @see ParamCapability 参数级伴生注解
 * @see NestedParamCapability 嵌套对象参数的子字段声明
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ToolCapability {

  /** 操作类别，如 READ/ADD/UPDATE/REMOVE。 */
  CapabilityOperation operation();

  /**
   * 元素类型名（小写字符串），如 {@code "paragraph"}/{@code "table"}/{@code "run"}。
   *
   * <p>空字符串表示会话/质量等元工具（无具体文档元素）。值与 {@code com.non.docx.toolkit.ref.ElementKind}
   * 语义对齐（PARAGRAPH→"paragraph"）， 但用字符串以便 序列化进 manifest 供 Agent 稳定读取。
   */
  String element() default "";

  /** 稳定性与兼容性等级，默认 {@link CapabilityLevel#STABLE}。 */
  CapabilityLevel level() default CapabilityLevel.STABLE;

  /** 是否需要 Word/WPS 重新计算才能呈现正确结果（如字段刷新、TOC 更新）。 默认 false。 */
  boolean needsRecalc() default false;

  /** 引入版本，默认 {@code "0.0.1"}。 */
  String since() default "0.0.1";

  /** 调用示例（CLI/JSON 片段），可为空。 */
  String[] examples() default {};
}
