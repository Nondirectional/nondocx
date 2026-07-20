package io.github.nondirectional.docx.toolkit.view.dto;

import java.util.List;
import java.util.Objects;

/** 文本视图：按文档顺序输出文本条目列表，每项带 canonical ref。 */
public final class TextView {

  private final ViewMeta meta;
  private final List<TextEntry> entries;

  public TextView(ViewMeta meta, List<TextEntry> entries) {
    this.meta = Objects.requireNonNull(meta, "meta 不能为空");
    this.entries = List.copyOf(entries);
  }

  public ViewMeta meta() {
    return meta;
  }

  public List<TextEntry> entries() {
    return entries;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof TextView)) return false;
    TextView that = (TextView) o;
    return meta.equals(that.meta) && entries.equals(that.entries);
  }

  @Override
  public int hashCode() {
    return Objects.hash(meta, entries);
  }
}
