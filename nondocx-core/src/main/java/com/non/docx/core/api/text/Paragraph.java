package com.non.docx.core.api.text;

import com.non.docx.core.api.BodyElement;
import com.non.docx.core.internal.util.Objects;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

/**
 * A paragraph — a body-level block of inline content.
 *
 * <p>Holds an Apache POI {@code XWPFParagraph} delegate and exposes a live view over it. Reads go
 * straight through to the delegate; there is no cached snapshot.
 *
 * <p><b>Minimal at this phase; completed in Phase 3.</b> Only the construction seam, a plain-text
 * accessor, and the {@link #raw()} escape hatch are present here. Inline elements (runs, hyperlinks,
 * images), paragraph-level styling, and content equality are added in Phase 3.
 */
public final class Paragraph implements BodyElement {

    private final XWPFParagraph delegate;

    /**
     * Wraps the given POI paragraph.
     *
     * <p>This constructor is the internal seam by which {@code Document} produces live paragraph
     * wrappers, so it accepts a POI type by design. Users normally obtain paragraphs via
     * {@code Document.paragraph(...)} / {@code Document.addParagraph(...)} rather than constructing
     * them directly.
     *
     * @param delegate the backing POI paragraph (not {@code null})
     * @throws IllegalArgumentException if {@code delegate} is {@code null}
     */
    public Paragraph(XWPFParagraph delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * Returns the paragraph's concatenated plain-text content.
     *
     * @return the text of this paragraph (possibly empty, never {@code null})
     */
    public String text() {
        return delegate.getText();
    }

    /**
     * Returns the underlying POI paragraph.
     * <p>
     * Modifications to the returned object affect the document immediately. Use with caution.
     *
     * @return the backing {@code XWPFParagraph} instance (same instance for the wrapper's lifetime)
     */
    public XWPFParagraph raw() {
        return delegate;
    }
}
