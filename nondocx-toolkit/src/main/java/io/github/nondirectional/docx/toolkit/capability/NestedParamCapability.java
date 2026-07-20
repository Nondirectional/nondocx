package io.github.nondirectional.docx.toolkit.capability;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明嵌套对象参数（如 {@code List<Map<String,Object>>} 的 {@code edits}）的子字段能力元数据。
 *
 * <p>nonchain {@code @ToolParam} 只能标注参数本身，无法表达 {@code edits[].alignment} 这类
 * 子字段的枚举值。本注解可重复标注在同一参数上，每个实例声明一条子字段路径。
 *
 * <p>路径约定：{@link #path()} 是点分路径，首段必须与同位 {@code @ToolParam(name=X)} 的 X 一致 （如 {@code
 * "edits.alignment"}，对应 {@code @ToolParam(name="edits")}）。 {@link CapabilityCollector} 会校验此前缀一致性。
 *
 * <p>例：{@code update_paragraph_alignment} 的 {@code edits} 参数：
 *
 * <pre>{@code
 * @ToolParam(name = "edits", description = "对象数组...")
 * @NestedParamCapability(path = "edits.paragraph_index", type = ParamType.INTEGER)
 * @NestedParamCapability(
 *     path = "edits.alignment",
 *     type = ParamType.ENUM,
 *     enumValues = {"LEFT", "CENTER", "RIGHT", "JUSTIFY"})
 * List<Map<String, Object>> edits
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@Repeatable(NestedParamCapabilities.class)
public @interface NestedParamCapability {

  /** 子字段点分路径，如 {@code "edits.alignment"}。 */
  String path();

  /** 子字段类型。 */
  ParamType type();

  /** 枚举合法值，仅当 {@link #type()} 为 {@link ParamType#ENUM} 时有效。 */
  String[] enumValues() default {};

  /** 单位，可为空。 */
  String unit() default "";

  /** 默认值（文档化用途），可为空。 */
  String defaultValue() default "";
}
