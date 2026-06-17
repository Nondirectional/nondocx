package com.non.docx.core.builder;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.style.HeadingLevel;
import com.non.docx.core.api.table.Table;
import com.non.docx.core.api.text.Paragraph;
import com.non.docx.core.internal.util.Objects;
import java.util.function.Consumer;

/**
 * 流式构建轨道，用于从零组装一个 {@link Document}。
 *
 * <p>这是设计文档（design.md §4.5）中定义的"构建轨道"：对 nondocx 可变活动对象的轻量编排器。 每个方法都委托给活动对象 {@link Document} /
 * {@link Paragraph} / {@link Table} 的构建块 （{@code addParagraph}、{@code addTable}、{@code
 * addRun}……）。这里不重复 run、段落或表格的 任何行为——构建器只组合这些块，因此所有样式或往返语义都只存在于一个位置。
 *
 * <p>典型用法：
 *
 * <pre>{@code
 * Document doc = DocumentBuilder.start()
 *     .heading(HeadingLevel.H1, "Title")
 *     .paragraph(p -> p.addRun("body").bold())
 *     .table(t -> t.row(r -> r.cell("A1").cell("B1")))
 *     .build();
 * }</pre>
 *
 * <p>配置器 lambda（{@link #paragraph(Consumer)}、{@link #table(Consumer)}）直接接收活动对象 {@code Paragraph} /
 * {@code Table}，因此调用方可以免费获得完整的 run / cell / 样式 API—— 无需学习一套并行的"仅构建器"词汇。
 *
 * <p>构建器在整个链中累积到一个底层 {@link Document}。{@link #build()} 返回的 {@link Document} 正是 nondocx
 * 中一直使用的可变活动对象文档（其委托是一个 {@code XWPFDocument}）；调用方拥有 它并负责关闭它。
 *
 * <p>此类仅引用 {@code api/} 类型及 {@link Docx}——其签名中不出现 POI 类型。
 */
public final class DocumentBuilder {

  private final Document document;

  private DocumentBuilder(Document document) {
    this.document = document;
  }

  /**
   * 在一个新的空文档上启动一个新构建器。
   *
   * @return 由 {@code Docx.create()} 支持的新构建器
   */
  public static DocumentBuilder start() {
    return new DocumentBuilder(Docx.create());
  }

  /**
   * 追加一个具有给定级别和文本的标题段落，并返回此构建器。
   *
   * <p>这是 {@code addParagraph().heading(level).addRun(text)} 的便捷方法。
   *
   * @param level 标题级别（不能为 {@code null}）
   * @param text 标题文本（不能为 {@code null}）
   * @return 此构建器
   * @throws IllegalArgumentException 如果 {@code level} 或 {@code text} 为 {@code null}
   */
  public DocumentBuilder heading(HeadingLevel level, String text) {
    Objects.requireNonNull(level, "level");
    Objects.requireNonNull(text, "text");
    document.addParagraph().heading(level).addRun(text);
    return this;
  }

  /**
   * 追加一个携带给定纯文本的新段落，并返回此构建器。
   *
   * <p>这是 {@link Document#addParagraph(String)} 的便捷方法。
   *
   * @param text 段落文本（不能为 {@code null}）
   * @return 此构建器
   * @throws IllegalArgumentException 如果 {@code text} 为 {@code null}
   */
  public DocumentBuilder paragraph(String text) {
    document.addParagraph(text);
    return this;
  }

  /**
   * 追加一个新的空段落，对其应用给定配置器，并返回此构建器。
   *
   * <p>配置器操作活动对象 {@link Paragraph}，因此它拥有完整的 run 和段落样式 API—— 例如 {@code .paragraph(p ->
   * p.addRun("hi").bold().fontSize(14))} 或 {@code .paragraph(p ->
   * p.heading(HeadingLevel.H2).addRun("section"))}。 此处不重复任何 run 或样式逻辑；每次调用都到达活动对象 {@code
   * Paragraph}。
   *
   * @param config 段落配置器，操作活动段落（不能为 {@code null}）
   * @return 此构建器
   * @throws IllegalArgumentException 如果 {@code config} 为 {@code null}
   */
  public DocumentBuilder paragraph(Consumer<Paragraph> config) {
    Objects.requireNonNull(config, "config");
    Paragraph appended = document.addParagraph();
    config.accept(appended);
    return this;
  }

  /**
   * 追加一个新的空表格，对其应用给定配置器，并返回此构建器。
   *
   * <p>配置器操作活动对象 {@link Table}，因此它拥有完整的行/单元格 API—— 例如 {@code .table(t -> t.row(r ->
   * r.cell("A1").cell("B1")).row(r -> r.cell("A2").cell("B2")))}。此处不重复任何行或单元格逻辑；每次调用都到达 活动对象 {@code
   * Table}。
   *
   * @param config 表格配置器，操作活动表格（不能为 {@code null}）
   * @return 此构建器
   * @throws IllegalArgumentException 如果 {@code config} 为 {@code null}
   */
  public DocumentBuilder table(Consumer<Table> config) {
    Objects.requireNonNull(config, "config");
    Table appended = document.addTable();
    config.accept(appended);
    return this;
  }

  /**
   * 返回已组装好的文档。
   *
   * <p>返回的文档是在此构建器链中累积的活动可变文档。它与从 {@link Docx#create()} 或 {@link Docx#open} 获得的 {@link Document}
   * 是同一类型，因此可与 API 的其他部分组合 （保存它、继续修改它等）。调用方拥有它，并应在使用完毕后关闭它。
   *
   * @return 已组装的文档（从不 {@code null}）
   */
  public Document build() {
    return document;
  }
}
