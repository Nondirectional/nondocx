package com.non.docx.core.api.text;

import com.non.docx.core.api.InlineElement;
import com.non.docx.core.api.style.RunStyle;
import com.non.docx.core.internal.util.Objects;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFRun;

/**
 * A run — a contiguous fragment of text within a paragraph, carrying inline character formatting.
 *
 * <p>Holds an Apache POI {@code XWPFRun} delegate and exposes a live, chainable view over it. Every
 * mutator writes straight through to the delegate and returns {@code this} for chaining; every
 * getter reads straight through. There is no cached snapshot.
 *
 * <p>Inline formatting is modeled with six attributes — bold, italic, underline, font name, font
 * size (in points), and color (hex). {@link #style()} returns an immutable {@link RunStyle}
 * snapshot of all six, which is what content equality ({@code equals} / {@code hashCode}) compares,
 * together with the run's text. The delegate reference is never part of equality, so two runs over
 * distinct POI instances but with the same text and formatting are equal — this is what makes
 * round-trip assertions work.
 *
 * <p>This is a <em>mutable live object</em>. Its {@code equals} / {@code hashCode} serve comparison
 * and round-trip assertions; they are not suited as a long-lived {@code HashMap} key, since the
 * underlying content can change at any time.
 */
public final class Run implements InlineElement {

    private final XWPFRun delegate;

    /**
     * Wraps the given POI run.
     *
     * <p>This constructor is the internal seam by which {@link Paragraph} produces live run wrappers,
     * so it accepts a POI type by design. Users normally obtain runs via
     * {@code Paragraph.addRun(...)} / {@code Paragraph.run(...)} rather than constructing them.
     *
     * @param delegate the backing POI run (not {@code null})
     * @throws IllegalArgumentException if {@code delegate} is {@code null}
     */
    public Run(XWPFRun delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * Sets this run's text and returns {@code this} for chaining.
     *
     * <p>This delegates to {@code XWPFRun.setText(String)}; pass an empty string to produce a run
     * with no visible text.
     *
     * @param text the new text (not {@code null}; use {@code ""} to clear)
     * @return this run
     * @throws IllegalArgumentException if {@code text} is {@code null}
     */
    public Run text(String text) {
        Objects.requireNonNull(text, "text");
        delegate.setText(text);
        return this;
    }

    /**
     * Returns this run's full plain text.
     *
     * @return the run's text (possibly empty, never {@code null})
     */
    public String text() {
        return delegate.text();
    }

    /**
     * Sets or clears bold and returns {@code this}.
     *
     * @param bold whether the text is bold
     * @return this run
     */
    public Run bold(boolean bold) {
        delegate.setBold(bold);
        return this;
    }

    /** Convenience for {@code bold(true)}. */
    public Run bold() {
        return bold(true);
    }

    /** Returns whether the text is bold. */
    public boolean isBold() {
        return delegate.isBold();
    }

    /**
     * Sets or clears italic and returns {@code this}.
     *
     * @param italic whether the text is italic
     * @return this run
     */
    public Run italic(boolean italic) {
        delegate.setItalic(italic);
        return this;
    }

    /** Convenience for {@code italic(true)}. */
    public Run italic() {
        return italic(true);
    }

    /** Returns whether the text is italic. */
    public boolean isItalic() {
        return delegate.isItalic();
    }

    /**
     * Sets or clears underlining and returns {@code this}.
     *
     * <p>Enabling underlining applies a single underline (Word's most common variant). Disabling it
     * removes any underline.
     *
     * @param underline whether the text is underlined
     * @return this run
     */
    public Run underline(boolean underline) {
        delegate.setUnderline(underline ? UnderlinePatterns.SINGLE : UnderlinePatterns.NONE);
        return this;
    }

    /** Convenience for {@code underline(true)}. */
    public Run underline() {
        return underline(true);
    }

    /** Returns whether the text carries any underline. */
    public boolean isUnderline() {
        UnderlinePatterns pattern = delegate.getUnderline();
        return pattern != null && pattern != UnderlinePatterns.NONE;
    }

    /**
     * Sets the font size in points and returns {@code this}.
     *
     * @param points the font size in points
     * @return this run
     */
    public Run fontSize(int points) {
        delegate.setFontSize((double) points);
        return this;
    }

    /**
     * Returns the font size in points, or {@code null} if not explicitly set on this run.
     *
     * @return the font size in points, or {@code null} if unset
     */
    public Integer fontSize() {
        Double size = delegate.getFontSizeAsDouble();
        return size == null ? null : size.intValue();
    }

    /**
     * Sets the font name and returns {@code this}.
     *
     * @param name the font name (not {@code null})
     * @return this run
     * @throws IllegalArgumentException if {@code name} is {@code null}
     */
    public Run font(String name) {
        Objects.requireNonNull(name, "name");
        delegate.setFontFamily(name);
        return this;
    }

    /** Returns the font name, or {@code null} if not explicitly set on this run. */
    public String font() {
        return delegate.getFontFamily();
    }

    /**
     * Sets the text color and returns {@code this}.
     *
     * @param hex the color as a 6-digit hex RGB string (e.g. {@code "FF0000"}), not {@code null}
     * @return this run
     * @throws IllegalArgumentException if {@code hex} is {@code null}
     */
    public Run color(String hex) {
        Objects.requireNonNull(hex, "hex");
        delegate.setColor(hex);
        return this;
    }

    /** Returns the text color as a hex RGB string, or {@code null} if not explicitly set on this run. */
    public String color() {
        return delegate.getColor();
    }

    /**
     * Returns an immutable snapshot of this run's inline character formatting (the six style
     * attributes). The snapshot is taken live on each call.
     *
     * @return a {@link RunStyle} reflecting the current formatting (never {@code null})
     */
    public RunStyle style() {
        return RunStyle.of(isBold(), isItalic(), isUnderline(), font(), fontSize(), color());
    }

    /**
     * Returns the underlying POI run.
     * <p>
     * Modifications to the returned object affect the document immediately. Use with caution.
     *
     * @return the backing {@code XWPFRun} instance (same instance for the wrapper's lifetime)
     */
    public XWPFRun raw() {
        return delegate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Run)) {
            return false;
        }
        Run that = (Run) o;
        return java.util.Objects.equals(this.text(), that.text())
                && java.util.Objects.equals(this.style(), that.style());
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(text(), style());
    }

    @Override
    public String toString() {
        return "Run{text=" + text() + ", style=" + style() + '}';
    }
}
