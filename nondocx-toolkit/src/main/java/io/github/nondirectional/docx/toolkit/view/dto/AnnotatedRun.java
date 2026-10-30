package io.github.nondirectional.docx.toolkit.view.dto;

import java.util.Objects;

/**
 * annotated 视图的 run 级明细：文本 + 直接格式（bold/italic/font/size/color）+ canonical ref。
 *
 * <p><b>浅层先行（P0-04 D1）</b>：只给 run 直接格式，不解析样式链来源（direct/style/docDefaults/theme）。
 * effective-format-source 留 P1-02。
 *
 * @param ref canonical RunRef 字符串
 * @param text run 文本（截断）
 * @param bold 是否加粗
 * @param italic 是否斜体
 * @param font 字体名（ascii/西文槽，可能为 null）
 * @param fontEastAsia 中文字体槽名（可能为 null；与 font 分离供读者核对「西文 + 中文」双槽排版）
 * @param fontSize 字号 pt（可能为 null）
 * @param color 颜色 hex（可能为 null）
 */
public final class AnnotatedRun {

  private final String ref;
  private final String text;
  private final boolean bold;
  private final boolean italic;
  private final String font;
  private final String fontEastAsia;
  private final Integer fontSize;
  private final String color;

  public AnnotatedRun(
      String ref,
      String text,
      boolean bold,
      boolean italic,
      String font,
      String fontEastAsia,
      Integer fontSize,
      String color) {
    this.ref = Objects.requireNonNull(ref, "ref 不能为空");
    this.text = Objects.requireNonNull(text, "text 不能为空");
    this.bold = bold;
    this.italic = italic;
    this.font = font;
    this.fontEastAsia = fontEastAsia;
    this.fontSize = fontSize;
    this.color = color;
  }

  public String ref() {
    return ref;
  }

  public String text() {
    return text;
  }

  public boolean bold() {
    return bold;
  }

  public boolean italic() {
    return italic;
  }

  public String font() {
    return font;
  }

  /** eastAsia（中文）字体槽名；未设置为 {@code null}。编译器的槽分离排版（西文 TNR + 中文宋体）需要它才可核对。 */
  public String fontEastAsia() {
    return fontEastAsia;
  }

  public Integer fontSize() {
    return fontSize;
  }

  public String color() {
    return color;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof AnnotatedRun)) return false;
    AnnotatedRun that = (AnnotatedRun) o;
    return bold == that.bold
        && italic == that.italic
        && ref.equals(that.ref)
        && text.equals(that.text)
        && Objects.equals(font, that.font)
        && Objects.equals(fontEastAsia, that.fontEastAsia)
        && Objects.equals(fontSize, that.fontSize)
        && Objects.equals(color, that.color);
  }

  @Override
  public int hashCode() {
    return Objects.hash(ref, text, bold, italic, font, fontEastAsia, fontSize, color);
  }
}
