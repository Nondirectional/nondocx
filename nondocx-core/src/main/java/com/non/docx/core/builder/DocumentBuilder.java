package com.non.docx.core.builder;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.style.HeadingLevel;
import com.non.docx.core.api.table.Table;
import com.non.docx.core.api.text.Paragraph;
import com.non.docx.core.internal.util.Objects;
import java.util.function.Consumer;

/**
 * Fluent construction track for assembling a {@link Document} from scratch.
 *
 * <p>This is the "construction track" from the design (design.md §4.5): a thin orchestrator over
 * nondocx's mutable live objects. Every method delegates to the live {@link Document} / {@link
 * Paragraph} / {@link Table} building blocks ({@code addParagraph}, {@code addTable}, {@code
 * addRun}, ...). No run, paragraph, or table behavior is duplicated here — the builder only
 * composes those blocks, so any style or round-trip semantics live in exactly one place.
 *
 * <p>Typical usage:
 *
 * <pre>{@code
 * Document doc = DocumentBuilder.start()
 *     .heading(HeadingLevel.H1, "Title")
 *     .paragraph(p -> p.addRun("body").bold())
 *     .table(t -> t.row(r -> r.cell("A1").cell("B1")))
 *     .build();
 * }</pre>
 *
 * <p>The configurator lambdas ({@link #paragraph(Consumer)}, {@link #table(Consumer)}) receive the
 * live {@code Paragraph} / {@code Table} directly, so callers get the full run / cell / style API
 * for free — there is no parallel "builder-only" vocabulary to learn.
 *
 * <p>The builder accumulates into a single underlying {@link Document} across the whole chain. The
 * {@link Document} returned by {@link #build()} is the very same mutable live-object document used
 * throughout nondocx (its delegate is an {@code XWPFDocument}); the caller owns it and is
 * responsible for closing it.
 *
 * <p>This class references only {@code api/} types plus {@link Docx} — no POI types appear in its
 * signatures.
 */
public final class DocumentBuilder {

  private final Document document;

  private DocumentBuilder(Document document) {
    this.document = document;
  }

  /**
   * Starts a new builder over a fresh, empty document.
   *
   * @return a new builder backed by {@code Docx.create()}
   */
  public static DocumentBuilder start() {
    return new DocumentBuilder(Docx.create());
  }

  /**
   * Appends a heading paragraph with the given level and text, and returns this builder.
   *
   * <p>This is a convenience for {@code addParagraph().heading(level).addRun(text)}.
   *
   * @param level the heading level (not {@code null})
   * @param text the heading text (not {@code null})
   * @return this builder
   * @throws IllegalArgumentException if {@code level} or {@code text} is {@code null}
   */
  public DocumentBuilder heading(HeadingLevel level, String text) {
    Objects.requireNonNull(level, "level");
    Objects.requireNonNull(text, "text");
    document.addParagraph().heading(level).addRun(text);
    return this;
  }

  /**
   * Appends a new paragraph carrying the given plain text, and returns this builder.
   *
   * <p>This is a convenience for {@link Document#addParagraph(String)}.
   *
   * @param text the paragraph text (not {@code null})
   * @return this builder
   * @throws IllegalArgumentException if {@code text} is {@code null}
   */
  public DocumentBuilder paragraph(String text) {
    document.addParagraph(text);
    return this;
  }

  /**
   * Appends a new, empty paragraph, applies the given configurator to it, and returns this builder.
   *
   * <p>The configurator operates on the live {@link Paragraph}, so it has the full run and
   * paragraph-style API available — for example {@code .paragraph(p ->
   * p.addRun("hi").bold().fontSize(14))} or {@code .paragraph(p ->
   * p.heading(HeadingLevel.H2).addRun("section"))}. No run or style logic is duplicated here; every
   * call reaches the live {@code Paragraph}.
   *
   * @param config the paragraph configurator, operating on the live paragraph (not {@code null})
   * @return this builder
   * @throws IllegalArgumentException if {@code config} is {@code null}
   */
  public DocumentBuilder paragraph(Consumer<Paragraph> config) {
    Objects.requireNonNull(config, "config");
    Paragraph appended = document.addParagraph();
    config.accept(appended);
    return this;
  }

  /**
   * Appends a new, empty table, applies the given configurator to it, and returns this builder.
   *
   * <p>The configurator operates on the live {@link Table}, so it has the full row / cell API
   * available — for example {@code .table(t -> t.row(r -> r.cell("A1").cell("B1")).row(r ->
   * r.cell("A2").cell("B2")))}. No row or cell logic is duplicated here; every call reaches the
   * live {@code Table}.
   *
   * @param config the table configurator, operating on the live table (not {@code null})
   * @return this builder
   * @throws IllegalArgumentException if {@code config} is {@code null}
   */
  public DocumentBuilder table(Consumer<Table> config) {
    Objects.requireNonNull(config, "config");
    Table appended = document.addTable();
    config.accept(appended);
    return this;
  }

  /**
   * Returns the assembled document.
   *
   * <p>The returned document is the live, mutable document accumulated throughout this builder
   * chain. It is the same kind of {@link Document} obtained from {@link Docx#create()} or {@link
   * Docx#open}, so it composes with the rest of the API (save it, keep mutating it, etc.). The
   * caller owns it and should close it when done.
   *
   * @return the assembled document (never {@code null})
   */
  public Document build() {
    return document;
  }
}
