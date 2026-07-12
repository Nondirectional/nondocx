package com.non.docx.toolkit.capability;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明一个 {@code @ToolParam} 参数的能力元数据：类型、枚举值、单位、默认值。
 *
 * <p>与 nonchain {@code @ToolParam}（提供 name/description/required）同位标注。 本注解只补 nonchain
 * 注解缺失的维度（type/enumValues/unit/defaultValue），不重复 name/description/required。
 *
 * <p>校验：{@code enumValues} 非空时 {@link #type()} 必须为 {@link ParamType#ENUM}，否则 {@link
 * CapabilityCollector} 抛出 {@link CapabilityDeclarationException}。
 *
 * @see NestedParamCapability 嵌套对象参数（如 {@code List<Map>} 的子字段）用此注解
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ParamCapability {

  /** 参数类型。 */
  ParamType type();

  /** 枚举合法值，仅当 {@link #type()} 为 {@link ParamType#ENUM} 时有效。 非空时 type 必须为 ENUM。 */
  String[] enumValues() default {};

  /** 单位（如 {@code "twip"}/{@code "pt"}/{@code "percent"}），可为空。 */
  String unit() default "";

  /** 默认值（文档化用途，非运行时注入），可为空。 */
  String defaultValue() default "";
}
