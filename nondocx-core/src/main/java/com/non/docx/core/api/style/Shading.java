package com.non.docx.core.api.style;

import java.util.Objects;

/**
 * 单元格/段落的不可变底纹值对象，对应 OOXML {@code <w:shd>} 元素。
 *
 * <p>一个 {@code Shading} 由三部分组成：
 *
 * <ul>
 *   <li><b>fill</b>——背景色（十六进制 RGB 字符串，如 {@code "F1F5F9"}，<b>不带</b> {@code #}）。 对应 OOXML {@code
 *       w:fill}。
 *   <li><b>pattern</b>——图案类型（{@link ShadingPattern}）。对应 OOXML {@code w:val}。 <b>剔除 {@code
 *       SOLID}</b>（参见 {@link ShadingPattern}）。
 *   <li><b>color</b>——图案前景色（可选，十六进制 RGB）。对应 OOXML {@code w:color}。仅在有图案叠加时 才有意义；纯色背景填充（{@link
 *       ShadingPattern#CLEAR}）通常不需要。
 * </ul>
 *
 * <p>实例是值对象，具有 <em>内容相等性</em>——三个字段全等时才相等。不可变；以 {@code of} 静态工厂 构造。便捷构造 {@link #of(String)} 默认
 * {@code pattern=CLEAR}、{@code color=null}，是最常见的「给一个纯色底」 的快捷方式，也是 {@code Cell.shading(String)} /
 * {@code Paragraph.shading(String)} 单参重载的语义来源。
 *
 * <p>这是一个无 POI 依赖的值对象；它不引用 {@code org.apache.poi.*}。
 */
public final class Shading {

  private final String fill;
  private final ShadingPattern pattern;
  private final String color;

  private Shading(String fill, ShadingPattern pattern, String color) {
    this.fill = fill;
    this.pattern = pattern;
    this.color = color;
  }

  /**
   * 创建一个<b>纯色背景填充</b>的底纹（最常见的便捷形态）。
   *
   * <p>等价于 {@code Shading.of(fill, ShadingPattern.CLEAR, null)}——图案为 {@link ShadingPattern#CLEAR}
   * （跨引擎安全的默认），无前景色。{@code Cell.shading(String)} / {@code Paragraph.shading(String)} 单参重载内部调用此工厂。
   *
   * @param fill 背景色（十六进制 RGB 字符串，如 {@code "F1F5F9"}，不带 {@code #}；不能为 {@code null}）
   * @return 一个新的底纹值对象
   * @throws IllegalArgumentException 如果 {@code fill} 为 {@code null}
   */
  public static Shading of(String fill) {
    return of(fill, ShadingPattern.CLEAR, null);
  }

  /**
   * 创建一个指定背景色与图案的底纹（无前景色）。
   *
   * @param fill 背景色（十六进制 RGB 字符串，不带 {@code #}；不能为 {@code null}）
   * @param pattern 图案类型（不能为 {@code null}）
   * @return 一个新的底纹值对象
   * @throws IllegalArgumentException 如果 {@code fill} 或 {@code pattern} 为 {@code null}
   */
  public static Shading of(String fill, ShadingPattern pattern) {
    return of(fill, pattern, null);
  }

  /**
   * 创建一个完整指定的底纹。
   *
   * @param fill 背景色（十六进制 RGB 字符串，不带 {@code #}；不能为 {@code null}）
   * @param pattern 图案类型（不能为 {@code null}）
   * @param color 图案前景色（十六进制 RGB 字符串，不带 {@code #}；可为 {@code null} 表示不设）
   * @return 一个新的底纹值对象
   * @throws IllegalArgumentException 如果 {@code fill} 或 {@code pattern} 为 {@code null}
   */
  public static Shading of(String fill, ShadingPattern pattern, String color) {
    if (fill == null) {
      throw new IllegalArgumentException("fill 不能为 null");
    }
    if (pattern == null) {
      throw new IllegalArgumentException("pattern 不能为 null");
    }
    return new Shading(fill, pattern, color);
  }

  /**
   * 返回背景色（十六进制 RGB 字符串，不带 {@code #}）。
   *
   * @return 背景色（从不为 {@code null}）
   */
  public String fill() {
    return fill;
  }

  /**
   * 返回图案类型。
   *
   * @return 图案（从不为 {@code null}）
   */
  public ShadingPattern pattern() {
    return pattern;
  }

  /**
   * 返回图案前景色（十六进制 RGB 字符串，不带 {@code #}）。
   *
   * @return 前景色，未设为 {@code null}
   */
  public String color() {
    return color;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Shading)) {
      return false;
    }
    Shading that = (Shading) o;
    return Objects.equals(this.fill, that.fill)
        && this.pattern == that.pattern
        && Objects.equals(this.color, that.color);
  }

  @Override
  public int hashCode() {
    return Objects.hash(fill, pattern, color);
  }

  @Override
  public String toString() {
    return "Shading{fill=" + fill + ", pattern=" + pattern + ", color=" + color + "}";
  }
}
