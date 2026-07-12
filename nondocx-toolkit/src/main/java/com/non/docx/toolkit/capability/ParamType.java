package com.non.docx.toolkit.capability;

/**
 * 工具参数类型。覆盖 nondocx-toolkit 现有参数的 Java 类型分类。
 *
 * <p>与 {@code @ToolParam} 的 Java 参数类型对齐，但用枚举以便序列化进 manifest 供 Agent 稳定读取。
 */
public enum ParamType {
  /** 字符串。 */
  STRING("string"),

  /** 整数。 */
  INTEGER("integer"),

  /** 浮点数。 */
  NUMBER("number"),

  /** 布尔。 */
  BOOLEAN("boolean"),

  /** 枚举（必须配 enumValues）。 */
  ENUM("enum"),

  /** 元素引用字符串（canonical ElementRef）。 */
  REF("ref"),

  /** 字符串数组（如段落索引数组的字符串形式、canonical ref 列表）。 */
  STRING_ARRAY("string_array"),

  /** 整数数组（如 paragraph_indexes）。 */
  INTEGER_ARRAY("integer_array"),

  /** 对象数组（如 edits，子字段由 NestedParamCapability 声明）。 */
  OBJECT_ARRAY("object_array"),

  /** 文件路径。 */
  PATH("path");

  private final String value;

  ParamType(String value) {
    this.value = value;
  }

  /** 对外契约字符串，稳定不变。 */
  public String value() {
    return value;
  }

  /** 按 value 反查；找不到返回 null。 */
  public static ParamType fromValue(String value) {
    if (value == null) {
      return null;
    }
    for (ParamType type : values()) {
      if (type.value.equals(value)) {
        return type;
      }
    }
    return null;
  }
}
