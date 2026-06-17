package com.non.docx.core.builder;

import com.non.docx.core.api.style.Alignment;
import com.non.docx.core.api.style.HeadingLevel;
import com.non.docx.core.api.style.ListKind;
import com.non.docx.core.api.text.Paragraph;
import com.non.docx.core.api.text.Run;
import com.non.docx.core.internal.util.Objects;
import java.util.function.Consumer;

/**
 * 构建轨道辅助类，用于组装单个 {@link Paragraph}。
 *
 * <p>这是一个对活动对象 {@link Paragraph} 的薄包装器。段落级样式方法（标题、对齐方式、缩进、 行距、列表成员资格）直接委托给活动段落并返回此构建器以支持链式调用；run
 * 创建委托给 {@link Paragraph#addRun()} / {@link Paragraph#addRun(String)} 并返回活动 {@link Run}，
 * 因此调用方可以链式调用 run 级格式化（{@code bold()}、{@code fontSize(int)}……）。 此处不重复任何 run 或段落行为——每次调用都到达活动对象
 * {@code Paragraph} / {@code Run}。
 *
 * <p>示例：
 *
 * <pre>{@code
 * ParagraphBuilder.on(paragraph)
 *     .heading(HeadingLevel.H2)
 *     .text("Chapter 1")
 *     .italic();
 * }</pre>
 *
 * <p>这里 {@code .text("Chapter 1")} 返回活动 {@link Run}，因此 {@code .italic()} 应用于 该 run。如需从零组装段落，推荐使用
 * {@link DocumentBuilder#paragraph(Consumer)}，它将活动段落直接交给 lambda；此类适用于希望使用 显式构建器对象而非 lambda 的调用方。
 *
 * <p>此类仅引用 {@code api/} 类型——其签名中不出现 POI 类型。
 */
public final class ParagraphBuilder {

  private final Paragraph paragraph;

  private ParagraphBuilder(Paragraph paragraph) {
    this.paragraph = paragraph;
  }

  /**
   * 在给定的活动段落上创建一个构建器。
   *
   * @param paragraph 要组装成的活动段落（不能为 {@code null}）
   * @return 新构建器
   * @throws IllegalArgumentException 如果 {@code paragraph} 为 {@code null}
   */
  public static ParagraphBuilder on(Paragraph paragraph) {
    Objects.requireNonNull(paragraph, "paragraph");
    return new ParagraphBuilder(paragraph);
  }

  /**
   * 为段落应用标题级别并返回此构建器。
   *
   * @param level 标题级别（不能为 {@code null}）
   * @return 此构建器
   * @throws IllegalArgumentException 如果 {@code level} 为 {@code null}
   */
  public ParagraphBuilder heading(HeadingLevel level) {
    paragraph.heading(level);
    return this;
  }

  /** 清除段落上的所有标题样式并返回此构建器。 */
  public ParagraphBuilder clearHeading() {
    paragraph.clearHeading();
    return this;
  }

  /**
   * 设置水平对齐方式并返回此构建器。
   *
   * @param alignment 对齐方式（不能为 {@code null}）
   * @return 此构建器
   * @throws IllegalArgumentException 如果 {@code alignment} 为 {@code null}
   */
  public ParagraphBuilder alignment(Alignment alignment) {
    paragraph.alignment(alignment);
    return this;
  }

  /**
   * 设置左缩进和首行缩进（以缇为单位）并返回此构建器。
   *
   * @param leftTwips 左缩进（缇）
   * @param firstLineTwips 首行缩进（缇，可以为负数以表示悬挂缩进）
   * @return 此构建器
   */
  public ParagraphBuilder indent(int leftTwips, int firstLineTwips) {
    paragraph.indent(leftTwips, firstLineTwips);
    return this;
  }

  /**
   * 将行距设置为单行高度的倍数并返回此构建器。
   *
   * @param multiple 行距倍数（如 {@code 1.5}）
   * @return 此构建器
   */
  public ParagraphBuilder lineSpacing(double multiple) {
    paragraph.lineSpacing(multiple);
    return this;
  }

  /**
   * 将段落标记为给定类型和嵌套级别的列表成员，并返回此构建器。
   *
   * @param kind 列表类型（不能为 {@code null}）
   * @param level 从 0 开始的嵌套级别（{@code 0..8}）
   * @return 此构建器
   * @throws IllegalArgumentException 如果 {@code kind} 为 {@code null}
   */
  public ParagraphBuilder list(ListKind kind, int level) {
    paragraph.list(kind, level);
    return this;
  }

  /** 移除段落的列表成员资格并返回此构建器。 */
  public ParagraphBuilder clearList() {
    paragraph.clearList();
    return this;
  }

  /**
   * 追加一个携带给定文本的新 run 并返回活动 run，以便调用方可以直接链式调用 run 级格式化 （例如 {@code .text("hi").bold().fontSize(14)}）。
   *
   * @param text run 的文本（不能为 {@code null}）
   * @return 新追加的活动 run
   * @throws IllegalArgumentException 如果 {@code text} 为 {@code null}
   */
  public Run text(String text) {
    return paragraph.addRun(text);
  }

  /**
   * 追加一个新的空 run 并返回活动 run。
   *
   * @return 新追加的活动 run
   */
  public Run run() {
    return paragraph.addRun();
  }

  /**
   * 追加一个新的空 run，对其应用给定配置器，并返回此构建器。
   *
   * @param config run 配置器，操作活动 run（不能为 {@code null}）
   * @return 此构建器
   * @throws IllegalArgumentException 如果 {@code config} 为 {@code null}
   */
  public ParagraphBuilder run(Consumer<Run> config) {
    Objects.requireNonNull(config, "config");
    Run appended = paragraph.addRun();
    config.accept(appended);
    return this;
  }

  /**
   * 返回此构建器组装的活动段落。
   *
   * @return 底层的活动段落（从不 {@code null}）
   */
  public Paragraph paragraph() {
    return paragraph;
  }
}
