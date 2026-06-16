package com.non.docx.core.builder;

import com.non.docx.core.api.style.Alignment;
import com.non.docx.core.api.style.HeadingLevel;
import com.non.docx.core.api.style.ListKind;
import com.non.docx.core.api.text.Paragraph;
import com.non.docx.core.api.text.Run;
import com.non.docx.core.internal.util.Objects;

import java.util.function.Consumer;

/**
 * Construction-track helper for assembling a single {@link Paragraph}.
 *
 * <p>This is a thin wrapper over a live {@link Paragraph}. Paragraph-level style methods
 * (heading, alignment, indentation, line spacing, list membership) delegate straight to the live
 * paragraph and return this builder for chaining; run creation delegates to
 * {@link Paragraph#addRun()} / {@link Paragraph#addRun(String)} and returns the live {@link Run},
 * so callers can chain run-level formatting ({@code bold()}, {@code fontSize(int)}, ...). No run or
 * paragraph behavior is duplicated here — every call reaches the live {@code Paragraph} / {@code Run}.
 *
 * <p>Example:
 * <pre>{@code
 * ParagraphBuilder.on(paragraph)
 *     .heading(HeadingLevel.H2)
 *     .text("Chapter 1")
 *     .italic();
 * }</pre>
 * Here {@code .text("Chapter 1")} returns the live {@link Run}, so {@code .italic()} applies to
 * that run. To assemble a paragraph from scratch, prefer {@link DocumentBuilder#paragraph(Consumer)},
 * which hands the live paragraph straight to a lambda; this class is for callers who prefer an
 * explicit builder object over a lambda.
 *
 * <p>This class references only {@code api/} types — no POI types appear in its signatures.
 */
public final class ParagraphBuilder {

    private final Paragraph paragraph;

    private ParagraphBuilder(Paragraph paragraph) {
        this.paragraph = paragraph;
    }

    /**
     * Creates a builder over the given live paragraph.
     *
     * @param paragraph the live paragraph to assemble into (not {@code null})
     * @return a new builder
     * @throws IllegalArgumentException if {@code paragraph} is {@code null}
     */
    public static ParagraphBuilder on(Paragraph paragraph) {
        Objects.requireNonNull(paragraph, "paragraph");
        return new ParagraphBuilder(paragraph);
    }

    /**
     * Applies a heading level to the paragraph and returns this builder.
     *
     * @param level the heading level (not {@code null})
     * @return this builder
     * @throws IllegalArgumentException if {@code level} is {@code null}
     */
    public ParagraphBuilder heading(HeadingLevel level) {
        paragraph.heading(level);
        return this;
    }

    /** Clears any heading style from the paragraph and returns this builder. */
    public ParagraphBuilder clearHeading() {
        paragraph.clearHeading();
        return this;
    }

    /**
     * Sets the horizontal alignment and returns this builder.
     *
     * @param alignment the alignment (not {@code null})
     * @return this builder
     * @throws IllegalArgumentException if {@code alignment} is {@code null}
     */
    public ParagraphBuilder alignment(Alignment alignment) {
        paragraph.alignment(alignment);
        return this;
    }

    /**
     * Sets the left and first-line indentation (in twips) and returns this builder.
     *
     * @param leftTwips      the left indentation in twips
     * @param firstLineTwips the first-line indentation in twips (may be negative for a hanging indent)
     * @return this builder
     */
    public ParagraphBuilder indent(int leftTwips, int firstLineTwips) {
        paragraph.indent(leftTwips, firstLineTwips);
        return this;
    }

    /**
     * Sets the line spacing as a multiple of single-line height and returns this builder.
     *
     * @param multiple the line spacing multiple (e.g. {@code 1.5})
     * @return this builder
     */
    public ParagraphBuilder lineSpacing(double multiple) {
        paragraph.lineSpacing(multiple);
        return this;
    }

    /**
     * Marks the paragraph as a list member of the given kind at the given nesting level, and
     * returns this builder.
     *
     * @param kind  the list kind (not {@code null})
     * @param level the 0-based nesting level ({@code 0..8})
     * @return this builder
     * @throws IllegalArgumentException if {@code kind} is {@code null}
     */
    public ParagraphBuilder list(ListKind kind, int level) {
        paragraph.list(kind, level);
        return this;
    }

    /** Removes list membership from the paragraph and returns this builder. */
    public ParagraphBuilder clearList() {
        paragraph.clearList();
        return this;
    }

    /**
     * Appends a new run carrying the given text and returns the live run, so the caller can chain
     * run-level formatting directly (for example {@code .text("hi").bold().fontSize(14)}).
     *
     * @param text the run's text (not {@code null})
     * @return the newly appended live run
     * @throws IllegalArgumentException if {@code text} is {@code null}
     */
    public Run text(String text) {
        return paragraph.addRun(text);
    }

    /**
     * Appends a new, empty run and returns the live run.
     *
     * @return the newly appended live run
     */
    public Run run() {
        return paragraph.addRun();
    }

    /**
     * Appends a new, empty run, applies the given configurator to it, and returns this builder.
     *
     * @param config the run configurator, operating on the live run (not {@code null})
     * @return this builder
     * @throws IllegalArgumentException if {@code config} is {@code null}
     */
    public ParagraphBuilder run(Consumer<Run> config) {
        Objects.requireNonNull(config, "config");
        Run appended = paragraph.addRun();
        config.accept(appended);
        return this;
    }

    /**
     * Returns the live paragraph assembled by this builder.
     *
     * @return the backing live paragraph (never {@code null})
     */
    public Paragraph paragraph() {
        return paragraph;
    }
}
