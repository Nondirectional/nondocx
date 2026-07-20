package io.github.nondirectional.docx.toolkit.capability.model;

import io.github.nondirectional.docx.toolkit.capability.CapabilityLevel;
import io.github.nondirectional.docx.toolkit.capability.CapabilityOperation;
import java.util.List;
import java.util.Objects;

/**
 * 单个工具的能力描述。
 *
 * <p>不可变值对象。合并自 nonchain {@code @ToolDef}（name/description）、项目
 * {@code @ToolCapability}（operation/element/level/needsRecalc/since/examples）、 参数层
 * {@code @ToolParam}+{@code @ParamCapability}（params）与
 * {@code @NestedParamCapability}（nestedParams）。
 */
public final class ToolCapabilityDescriptor {

  private final String name;
  private final String description;
  private final CapabilityOperation operation;
  private final String element;
  private final CapabilityLevel level;
  private final boolean needsRecalc;
  private final String since;
  private final List<String> examples;
  private final List<ParamCapabilityDescriptor> params;
  private final List<ParamCapabilityDescriptor> nestedParams;

  public ToolCapabilityDescriptor(
      String name,
      String description,
      CapabilityOperation operation,
      String element,
      CapabilityLevel level,
      boolean needsRecalc,
      String since,
      List<String> examples,
      List<ParamCapabilityDescriptor> params,
      List<ParamCapabilityDescriptor> nestedParams) {
    this.name = name;
    this.description = description == null ? "" : description;
    this.operation = operation;
    this.element = element == null ? "" : element;
    this.level = level;
    this.needsRecalc = needsRecalc;
    this.since = since == null ? "" : since;
    this.examples = examples == null ? List.of() : List.copyOf(examples);
    this.params = params == null ? List.of() : List.copyOf(params);
    this.nestedParams = nestedParams == null ? List.of() : List.copyOf(nestedParams);
  }

  public String name() {
    return name;
  }

  public String description() {
    return description;
  }

  public CapabilityOperation operation() {
    return operation;
  }

  public String element() {
    return element;
  }

  public CapabilityLevel level() {
    return level;
  }

  public boolean needsRecalc() {
    return needsRecalc;
  }

  public String since() {
    return since;
  }

  public List<String> examples() {
    return examples;
  }

  public List<ParamCapabilityDescriptor> params() {
    return params;
  }

  public List<ParamCapabilityDescriptor> nestedParams() {
    return nestedParams;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ToolCapabilityDescriptor)) return false;
    ToolCapabilityDescriptor that = (ToolCapabilityDescriptor) o;
    return needsRecalc == that.needsRecalc
        && Objects.equals(name, that.name)
        && description.equals(that.description)
        && operation == that.operation
        && element.equals(that.element)
        && level == that.level
        && since.equals(that.since)
        && examples.equals(that.examples)
        && params.equals(that.params)
        && nestedParams.equals(that.nestedParams);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        name,
        description,
        operation,
        element,
        level,
        needsRecalc,
        since,
        examples,
        params,
        nestedParams);
  }
}
