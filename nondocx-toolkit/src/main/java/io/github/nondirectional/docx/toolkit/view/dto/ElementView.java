package io.github.nondirectional.docx.toolkit.view.dto;

import java.util.Map;
import java.util.Objects;

/**
 * 单元素详情视图：按 ref 获取一个元素的结构化详情。
 *
 * <p>{@code properties} 用 {@code Map<String,Object>} 因为不同元素类型属性集不同。 第一版支持
 * paragraph/table/run；其余类型返回 kind + ref + 空 properties。
 *
 * @param meta 视图元信息
 * @param kind 元素类型小写名（{@code "paragraph"/"run"/"table"/"cell"/...}）
 * @param ref canonical 引用字符串
 * @param properties 元素特定属性（段落: text/headingLevel/listItem；表格: rowCount/columnCount；run:
 *     text/bold/italic/font/fontSize/color）
 */
public final class ElementView {

  private final ViewMeta meta;
  private final String kind;
  private final String ref;
  private final Map<String, Object> properties;

  public ElementView(ViewMeta meta, String kind, String ref, Map<String, Object> properties) {
    this.meta = Objects.requireNonNull(meta, "meta 不能为空");
    this.kind = Objects.requireNonNull(kind, "kind 不能为空");
    this.ref = Objects.requireNonNull(ref, "ref 不能为空");
    this.properties = Map.copyOf(properties);
  }

  public ViewMeta meta() {
    return meta;
  }

  public String kind() {
    return kind;
  }

  public String ref() {
    return ref;
  }

  public Map<String, Object> properties() {
    return properties;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ElementView)) return false;
    ElementView that = (ElementView) o;
    return meta.equals(that.meta)
        && kind.equals(that.kind)
        && ref.equals(that.ref)
        && properties.equals(that.properties);
  }

  @Override
  public int hashCode() {
    return Objects.hash(meta, kind, ref, properties);
  }
}
