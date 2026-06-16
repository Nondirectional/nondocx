package com.non.docx.core.api.header;

import com.non.docx.core.api.text.Paragraph;
import com.non.docx.core.internal.util.Objects;
import org.apache.poi.xwpf.usermodel.XWPFFooter;

import java.util.AbstractList;
import java.util.List;

/**
 * A document footer — a section-scoped container of paragraphs rendered at the bottom of each page.
 *
 * <p>Holds an Apache POI {@code XWPFFooter} delegate and exposes a live view over it. Reads go
 * straight through to the delegate; there is no cached snapshot. Every mutation is write-through.
 *
 * <p>A footer is <em>not</em> a body element — it lives outside the document body, attached to a
 * {@link com.non.docx.core.api.section.Section} via a footer reference. Its content is an ordered
 * sequence of paragraphs, returned by {@link #paragraphs()}. Content equality
 * ({@code equals} / {@code hashCode}) compares that ordered paragraph sequence, never the delegate
 * reference, so two footers over distinct POI instances but with the same paragraphs are equal —
 * this is what makes round-trip assertions work.
 *
 * <p>{@link #text()} returns the footer's concatenated plain text. {@link #addParagraph()} appends
 * a new, empty paragraph and returns a live wrapper for it.
 *
 * <p>This is a <em>mutable live object</em>. Its {@code equals} / {@code hashCode} serve comparison
 * and round-trip assertions; they are not suited as a long-lived {@code HashMap} key, since the
 * underlying content can change at any time.
 *
 * <p><b>Scope.</b> The MVP exposes the default (odd-page) footer only. First-page and even-page
 * footer variants are out of scope and reachable via {@code raw()}.
 */
public final class Footer {

    private final XWPFFooter delegate;

    /**
     * Wraps the given POI footer.
     *
     * <p>This constructor is the internal seam by which {@link com.non.docx.core.api.section.Section}
     * produces live footer wrappers, so it accepts a POI type by design. Users obtain footers via
     * {@code Section.footer()} / {@code Document.footer()} rather than constructing them directly.
     *
     * @param delegate the backing POI footer (not {@code null})
     * @throws IllegalArgumentException if {@code delegate} is {@code null}
     */
    public Footer(XWPFFooter delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * Returns a live view of this footer's paragraphs in reading order.
     *
     * <p>The view is re-read from the delegate on every access, so mutations are reflected live.
     *
     * @return a live, unmodifiable list of paragraphs
     */
    public List<Paragraph> paragraphs() {
        return new AbstractList<Paragraph>() {
            @Override
            public Paragraph get(int index) {
                return new Paragraph(delegate.getParagraphs().get(index));
            }

            @Override
            public int size() {
                return delegate.getParagraphs().size();
            }
        };
    }

    /**
     * Returns the paragraph at the given index.
     *
     * @param index paragraph index (0-based, into {@link #paragraphs()})
     * @return the paragraph at that position
     * @throws IndexOutOfBoundsException if {@code index} is out of range
     */
    public Paragraph paragraph(int index) {
        return paragraphs().get(index);
    }

    /**
     * Appends a new, empty paragraph to this footer and returns a live wrapper for it.
     *
     * @return the newly appended paragraph
     */
    public Paragraph addParagraph() {
        return new Paragraph(delegate.createParagraph());
    }

    /**
     * Returns this footer's concatenated plain text (all paragraphs joined in reading order).
     *
     * @return the footer text (possibly empty, never {@code null})
     */
    public String text() {
        return delegate.getText();
    }

    /**
     * Returns the underlying POI footer.
     * <p>
     * Modifications to the returned object affect the document immediately. Use with caution.
     *
     * @return the backing {@code XWPFFooter} instance (same instance for the wrapper's lifetime)
     */
    public XWPFFooter raw() {
        return delegate;
    }

    // ---------- content equality ----------

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Footer)) {
            return false;
        }
        Footer that = (Footer) o;
        return java.util.Objects.equals(this.paragraphs(), that.paragraphs());
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(paragraphs());
    }
}
