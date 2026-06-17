package com.non.docx.core.api.style;

import java.util.Objects;

/**
 * 一个运行（run）的内联字符格式的不可变快照。
 *
 * <p>捕获 nondocx 在运行上建模的六种内联样式属性：粗体、斜体、下划线、 字体名称、字体大小（磅值）和文本颜色（十六进制）。实例是值对象，具有 <em>内容相等性</em> — 两个
 * {@code RunStyle} 在所有六个属性匹配时才相等， 与对象标识无关。
 *
 * <p>布尔属性默认为 {@code false}；{@code font}、{@code size} 和 {@code color} 可为 null 以表示"未显式设置"。使用 {@link
 * #empty()} 获取所有属性已清除的基线样式。
 *
 * <p>这是一个无 POI 的值对象；它不引用 {@code org.apache.poi.*}。
 */
public final class RunStyle {

  private static final RunStyle EMPTY = new RunStyle(false, false, false, null, null, null);

  private final boolean bold;
  private final boolean italic;
  private final boolean underline;
  private final String font;
  private final Integer size;
  private final String color;

  private RunStyle(
      boolean bold, boolean italic, boolean underline, String font, Integer size, String color) {
    this.bold = bold;
    this.italic = italic;
    this.underline = underline;
    this.font = font;
    this.size = size;
    this.color = color;
  }

  /**
   * 返回所有属性已清除的基线样式（粗体/斜体/下划线为 {@code false}， 字体/大小/颜色为 {@code null}）。
   *
   * @return 空样式（一个共享的、不可变的单例）
   */
  public static RunStyle empty() {
    return EMPTY;
  }

  /**
   * 使用指定的属性创建一个新样式。
   *
   * @param bold 文本是否粗体
   * @param italic 文本是否斜体
   * @param underline 文本是否带下划线
   * @param font 字体名称，如果未设置则为 {@code null}
   * @param size 字体大小（磅值），如果未设置则为 {@code null}
   * @param color 文本颜色（十六进制字符串，例如 {@code "FF0000"}），如果未设置则为 {@code null}
   * @return 一个新的样式
   */
  public static RunStyle of(
      boolean bold, boolean italic, boolean underline, String font, Integer size, String color) {
    return new RunStyle(bold, italic, underline, font, size, color);
  }

  /**
   * 返回此样式的一个副本，其中的粗体属性已被替换。
   *
   * @param bold 新的粗体值
   * @return 一个新的样式（此实例保持不变）
   */
  public RunStyle bold(boolean bold) {
    return new RunStyle(bold, this.italic, this.underline, this.font, this.size, this.color);
  }

  /**
   * 返回此样式的一个副本，其中的斜体属性已被替换。
   *
   * @param italic 新的斜体值
   * @return 一个新的样式（此实例保持不变）
   */
  public RunStyle italic(boolean italic) {
    return new RunStyle(this.bold, italic, this.underline, this.font, this.size, this.color);
  }

  /**
   * 返回此样式的一个副本，其中的下划线属性已被替换。
   *
   * @param underline 新的下划线值
   * @return 一个新的样式（此实例保持不变）
   */
  public RunStyle underline(boolean underline) {
    return new RunStyle(this.bold, this.italic, underline, this.font, this.size, this.color);
  }

  /**
   * 返回此样式的一个副本，其中的字体名称已被替换。
   *
   * @param font 新的字体名称，或 {@code null} 以清除
   * @return 一个新的样式（此实例保持不变）
   */
  public RunStyle font(String font) {
    return new RunStyle(this.bold, this.italic, this.underline, font, this.size, this.color);
  }

  /**
   * 返回此样式的一个副本，其中的字体大小已被替换。
   *
   * @param size 新的字体大小（磅值），或 {@code null} 以清除
   * @return 一个新的样式（此实例保持不变）
   */
  public RunStyle size(Integer size) {
    return new RunStyle(this.bold, this.italic, this.underline, this.font, size, this.color);
  }

  /**
   * 返回此样式的一个副本，其中的文本颜色已被替换。
   *
   * @param color 新的文本颜色（十六进制字符串，例如 {@code "FF0000"}），或 {@code null} 以清除
   * @return 一个新的样式（此实例保持不变）
   */
  public RunStyle color(String color) {
    return new RunStyle(this.bold, this.italic, this.underline, this.font, this.size, color);
  }

  /** 返回文本是否粗体。 */
  public boolean isBold() {
    return bold;
  }

  /** 返回文本是否斜体。 */
  public boolean isItalic() {
    return italic;
  }

  /** 返回文本是否带下划线。 */
  public boolean isUnderline() {
    return underline;
  }

  /** 返回字体名称，如果未设置则为 {@code null}。 */
  public String font() {
    return font;
  }

  /** 返回字体大小（磅值），如果未设置则为 {@code null}。 */
  public Integer size() {
    return size;
  }

  /** 返回文本颜色（十六进制字符串，例如 {@code "FF0000"}），如果未设置则为 {@code null}。 */
  public String color() {
    return color;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof RunStyle)) {
      return false;
    }
    RunStyle that = (RunStyle) o;
    return bold == that.bold
        && italic == that.italic
        && underline == that.underline
        && Objects.equals(font, that.font)
        && Objects.equals(size, that.size)
        && Objects.equals(color, that.color);
  }

  @Override
  public int hashCode() {
    return Objects.hash(bold, italic, underline, font, size, color);
  }

  @Override
  public String toString() {
    return "RunStyle{bold="
        + bold
        + ", italic="
        + italic
        + ", underline="
        + underline
        + ", font="
        + font
        + ", size="
        + size
        + ", color="
        + color
        + '}';
  }
}
