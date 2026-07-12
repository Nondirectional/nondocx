package com.non.docx.toolkit.view.dto;

import java.util.Objects;

/**
 * 字体统计单项：字体名 + 使用该字体的 run 数。
 *
 * @param fontName 字体名
 * @param count 使用该字体的 run 数
 */
public final class FontStat {

  private final String fontName;
  private final int count;

  public FontStat(String fontName, int count) {
    this.fontName = Objects.requireNonNull(fontName, "fontName 不能为空");
    this.count = count;
  }

  public String fontName() {
    return fontName;
  }

  public int count() {
    return count;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof FontStat)) return false;
    FontStat that = (FontStat) o;
    return count == that.count && fontName.equals(that.fontName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(fontName, count);
  }
}
