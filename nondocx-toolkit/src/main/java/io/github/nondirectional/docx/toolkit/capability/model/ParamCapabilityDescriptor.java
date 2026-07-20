package io.github.nondirectional.docx.toolkit.capability.model;

import io.github.nondirectional.docx.toolkit.capability.ParamType;
import java.util.List;
import java.util.Objects;

/**
 * 单个工具参数的能力描述。
 *
 * <p>不可变值对象。合并自 nonchain {@code @ToolParam}（name/description/required）与项目
 * {@code @ParamCapability}（type/enumValues/unit/defaultValue）。
 */
public final class ParamCapabilityDescriptor {

  private final String name;
  private final String description;
  private final ParamType type;
  private final boolean required;
  private final List<String> enumValues;
  private final String unit;
  private final String defaultValue;

  public ParamCapabilityDescriptor(
      String name,
      String description,
      ParamType type,
      boolean required,
      List<String> enumValues,
      String unit,
      String defaultValue) {
    this.name = name;
    this.description = description == null ? "" : description;
    this.type = type;
    this.required = required;
    this.enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
    this.unit = unit == null ? "" : unit;
    this.defaultValue = defaultValue == null ? "" : defaultValue;
  }

  public String name() {
    return name;
  }

  public String description() {
    return description;
  }

  public ParamType type() {
    return type;
  }

  public boolean required() {
    return required;
  }

  public List<String> enumValues() {
    return enumValues;
  }

  public String unit() {
    return unit;
  }

  public String defaultValue() {
    return defaultValue;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ParamCapabilityDescriptor)) return false;
    ParamCapabilityDescriptor that = (ParamCapabilityDescriptor) o;
    return required == that.required
        && Objects.equals(name, that.name)
        && description.equals(that.description)
        && type == that.type
        && enumValues.equals(that.enumValues)
        && unit.equals(that.unit)
        && defaultValue.equals(that.defaultValue);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, description, type, required, enumValues, unit, defaultValue);
  }
}
