package com.non.docx.core.api.style;

import java.util.Objects;

/**
 * An immutable snapshot of a run's inline character formatting.
 *
 * <p>Captures the six inline-style attributes nondocx models on a run: bold, italic, underline,
 * font name, font size (in points), and text color (hex). Instances are value objects with
 * <em>content equality</em> — two {@code RunStyle}s are equal when and only when all six
 * attributes match, regardless of object identity.
 *
 * <p>The boolean attributes default to {@code false}; {@code font}, {@code size}, and
 * {@code color} are nullable to represent "not explicitly set". Use {@link #empty()} to obtain
 * the baseline style with all attributes cleared.
 *
 * <p>This is a POI-free value object; it does not reference {@code org.apache.poi.*}.
 */
public final class RunStyle {

    private static final RunStyle EMPTY =
            new RunStyle(false, false, false, null, null, null);

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
     * Returns the baseline style with all attributes cleared (bold/italic/underline {@code false},
     * font/size/color {@code null}).
     *
     * @return the empty style (a shared, immutable singleton)
     */
    public static RunStyle empty() {
        return EMPTY;
    }

    /**
     * Creates a new style with the specified attributes.
     *
     * @param bold      whether the text is bold
     * @param italic    whether the text is italic
     * @param underline whether the text is underlined
     * @param font      the font name, or {@code null} if unset
     * @param size      the font size in points, or {@code null} if unset
     * @param color     the text color as a hex string (e.g. {@code "FF0000"}), or {@code null}
     * @return a new style
     */
    public static RunStyle of(
            boolean bold, boolean italic, boolean underline, String font, Integer size, String color) {
        return new RunStyle(bold, italic, underline, font, size, color);
    }

    /**
     * Returns a copy of this style with the bold attribute replaced.
     *
     * @param bold the new bold value
     * @return a new style (this instance is unchanged)
     */
    public RunStyle bold(boolean bold) {
        return new RunStyle(bold, this.italic, this.underline, this.font, this.size, this.color);
    }

    /**
     * Returns a copy of this style with the italic attribute replaced.
     *
     * @param italic the new italic value
     * @return a new style (this instance is unchanged)
     */
    public RunStyle italic(boolean italic) {
        return new RunStyle(this.bold, italic, this.underline, this.font, this.size, this.color);
    }

    /**
     * Returns a copy of this style with the underline attribute replaced.
     *
     * @param underline the new underline value
     * @return a new style (this instance is unchanged)
     */
    public RunStyle underline(boolean underline) {
        return new RunStyle(this.bold, this.italic, underline, this.font, this.size, this.color);
    }

    /**
     * Returns a copy of this style with the font name replaced.
     *
     * @param font the new font name, or {@code null} to clear
     * @return a new style (this instance is unchanged)
     */
    public RunStyle font(String font) {
        return new RunStyle(this.bold, this.italic, this.underline, font, this.size, this.color);
    }

    /**
     * Returns a copy of this style with the font size replaced.
     *
     * @param size the new font size in points, or {@code null} to clear
     * @return a new style (this instance is unchanged)
     */
    public RunStyle size(Integer size) {
        return new RunStyle(this.bold, this.italic, this.underline, this.font, size, this.color);
    }

    /**
     * Returns a copy of this style with the text color replaced.
     *
     * @param color the new text color as a hex string (e.g. {@code "FF0000"}), or {@code null} to clear
     * @return a new style (this instance is unchanged)
     */
    public RunStyle color(String color) {
        return new RunStyle(this.bold, this.italic, this.underline, this.font, this.size, color);
    }

    /** Returns whether the text is bold. */
    public boolean isBold() {
        return bold;
    }

    /** Returns whether the text is italic. */
    public boolean isItalic() {
        return italic;
    }

    /** Returns whether the text is underlined. */
    public boolean isUnderline() {
        return underline;
    }

    /** Returns the font name, or {@code null} if unset. */
    public String font() {
        return font;
    }

    /** Returns the font size in points, or {@code null} if unset. */
    public Integer size() {
        return size;
    }

    /** Returns the text color as a hex string (e.g. {@code "FF0000"}), or {@code null} if unset. */
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
        return "RunStyle{bold=" + bold
                + ", italic=" + italic
                + ", underline=" + underline
                + ", font=" + font
                + ", size=" + size
                + ", color=" + color
                + '}';
    }
}
