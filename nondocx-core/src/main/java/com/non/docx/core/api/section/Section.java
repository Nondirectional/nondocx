package com.non.docx.core.api.section;

import com.non.docx.core.api.exception.DocxIOException;
import com.non.docx.core.api.header.Footer;
import com.non.docx.core.api.header.Header;
import com.non.docx.core.api.text.Paragraph;
import com.non.docx.core.internal.poi.Mappers;
import com.non.docx.core.internal.util.Objects;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STPageOrientation;

import java.math.BigInteger;
import java.util.Collections;
import java.util.List;

/**
 * A document section — the carrier of page properties (paper size, orientation, margins) for a span
 * of the document body.
 *
 * <p>Holds an OOXML {@code CTSectPr} delegate and exposes a live view over it. Every read goes
 * straight through to the delegate (there is no cached snapshot); every mutation writes through
 * and returns {@code this} for chaining. The page-size, orientation and margin mutators keep the
 * underlying {@code <w:pgSz>} / {@code <w:pgMar>} elements self-consistent — for example switching
 * to {@link Orientation#LANDSCAPE} swaps the stored width/height so the larger dimension is the
 * width, matching Word's own storage convention.
 *
 * <p><b>Paper size and orientation interact as follows.</b> {@link #paperSize(PaperSize)} stores the
 * size's portrait dimensions. {@link #orientation(Orientation)} swaps those dimensions when the
 * target orientation differs from the current aspect, so calling the two in either order leaves the
 * section in a consistent state. {@link #paperSize()} resolves the logical paper size back from the
 * stored dimensions using {@link PaperSize#fromDimensions(int, int)}, which is orientation-agnostic.
 *
 * <p><b>Defaults.</b> A {@code <w:pgSz>} with no stored dimensions is treated as A4 portrait for the
 * purposes of orientation swapping; an unset {@code <w:pgMar>} attribute reads back as {@code 0};
 * an unset {@code orient} attribute reads back as {@link Orientation#PORTRAIT} (Word's default).
 *
 * <p><b>Header / footer.</b> {@link #header()} and {@link #footer()} expose the section-scoped
 * default (odd-page) header and footer. Each is resolved through a section-scoped
 * {@code XWPFHeaderFooterPolicy} bound to this section's {@code CTSectPr}: if a default header /
 * footer is already attached it is returned, otherwise an empty one is created and attached on
 * first access. First-page and even-page variants are out of scope for the MVP and remain reachable
 * via {@code raw()}. The owning {@code XWPFDocument} is held only to build that policy; it is an
 * internal helper, never exposed publicly and never part of content equality.
 *
 * <p>Content equality ({@code equals} / {@code hashCode}) compares the page properties — paper size,
 * orientation and the four margins — <em>and</em> the section-scoped default header / footer
 * paragraph content, never the delegate reference nor the owning document. The header / footer
 * content is resolved read-only via {@code getDefaultHeader()} / {@code getDefaultFooter()}, which
 * return {@code null} (and contribute an empty list) without creating anything when absent — so an
 * {@code equals} call never mutates the document. This is what makes round-trip assertions work
 * across two distinct {@code CTSectPr} instances. These methods serve comparison and testing; they
 * are not suited as a long-lived {@code HashMap} key, since the underlying content can change at
 * any time.
 */
public final class Section {

    private final XWPFDocument document;
    private final CTSectPr delegate;

    /**
     * Wraps the given OOXML section properties.
     *
     * <p>This constructor is the internal seam by which {@code Document} produces live section
     * wrappers, so it accepts POI / XmlBeans types by design (the same way other wrappers accept
     * their backing {@code XWPF*} type). Users obtain sections via
     * {@code Document.sections()} / {@code Document.section(int)} rather than constructing them.
     *
     * <p>The {@code document} argument is the owning POI document; it is held only so this section
     * can build a section-scoped {@code XWPFHeaderFooterPolicy} for {@link #header()} /
     * {@link #footer()}. It is never exposed publicly and never participates in content equality.
     *
     * @param document the owning POI document (not {@code null})
     * @param delegate the backing {@code CTSectPr} (not {@code null})
     * @throws IllegalArgumentException if either argument is {@code null}
     */
    public Section(XWPFDocument document, CTSectPr delegate) {
        this.document = Objects.requireNonNull(document, "document");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    // ---------- paper size ----------

    /**
     * Sets the paper size and returns {@code this}. Stores the size's portrait dimensions on the
     * underlying {@code <w:pgSz>}; the {@code orient} flag is left untouched.
     *
     * @param size the paper size (not {@code null})
     * @return this section
     * @throws IllegalArgumentException if {@code size} is {@code null}
     */
    public Section paperSize(PaperSize size) {
        Objects.requireNonNull(size, "size");
        CTPageSz pgSz = ensurePgSz();
        pgSz.setW(BigInteger.valueOf(size.widthTwips()));
        pgSz.setH(BigInteger.valueOf(size.heightTwips()));
        return this;
    }

    /**
     * Resolves the logical paper size from the stored {@code <w:pgSz>} dimensions, or {@code null}
     * if the dimensions do not match a known {@link PaperSize}.
     *
     * <p>Matching is orientation-agnostic (see {@link PaperSize#fromDimensions(int, int)}), so the
     * same paper size is resolved whether the section is portrait or landscape. A section with no
     * {@code <w:pgSz>} at all resolves to {@code null}.
     *
     * @return the paper size, or {@code null} if unset or unrecognized
     */
    public PaperSize paperSize() {
        if (!delegate.isSetPgSz()) {
            return null;
        }
        CTPageSz pgSz = delegate.getPgSz();
        return PaperSize.fromDimensions((int) twipsOf(pgSz.getW()), (int) twipsOf(pgSz.getH()));
    }

    // ---------- orientation ----------

    /**
     * Sets the page orientation and returns {@code this}. Ensures {@code <w:pgSz>} exists, swaps the
     * stored width/height when the target orientation requires it (landscape keeps the larger
     * dimension as width; portrait keeps the smaller dimension as width), and writes the
     * {@code orient} flag.
     *
     * <p>If {@code <w:pgSz>} has no stored dimensions, A4 portrait dimensions are assumed as the
     * base before swapping, so orientation is always well-defined.
     *
     * @param orientation the orientation (not {@code null})
     * @return this section
     * @throws IllegalArgumentException if {@code orientation} is {@code null}
     */
    public Section orientation(Orientation orientation) {
        Objects.requireNonNull(orientation, "orientation");
        CTPageSz pgSz = ensurePgSz();
        long w = dimOrDefault(pgSz.getW(), PaperSize.A4.widthTwips());
        long h = dimOrDefault(pgSz.getH(), PaperSize.A4.heightTwips());
        if (orientation == Orientation.LANDSCAPE && w <= h) {
            long swap = w;
            w = h;
            h = swap;
        } else if (orientation == Orientation.PORTRAIT && w > h) {
            long swap = w;
            w = h;
            h = swap;
        }
        pgSz.setW(BigInteger.valueOf(w));
        pgSz.setH(BigInteger.valueOf(h));
        pgSz.setOrient(Mappers.toPoi(orientation));
        return this;
    }

    /**
     * Returns the page orientation. A section with no {@code <w:pgSz>}, or one whose {@code orient}
     * flag is unset, is reported as {@link Orientation#PORTRAIT} (Word's default).
     *
     * @return the orientation (never {@code null})
     */
    public Orientation orientation() {
        if (!delegate.isSetPgSz()) {
            return Orientation.PORTRAIT;
        }
        STPageOrientation.Enum orient = delegate.getPgSz().getOrient();
        Orientation mapped = Mappers.fromPoi(orient);
        return mapped == null ? Orientation.PORTRAIT : mapped;
    }

    // ---------- margins ----------

    /**
     * Sets the four page margins (in twips) and returns {@code this}.
     *
     * @param topTwips    the top margin in twips
     * @param rightTwips  the right margin in twips
     * @param bottomTwips the bottom margin in twips
     * @param leftTwips   the left margin in twips
     * @return this section
     */
    public Section margins(int topTwips, int rightTwips, int bottomTwips, int leftTwips) {
        CTPageMar pgMar = delegate.isSetPgMar() ? delegate.getPgMar() : delegate.addNewPgMar();
        pgMar.setTop(BigInteger.valueOf(topTwips));
        pgMar.setRight(BigInteger.valueOf(rightTwips));
        pgMar.setBottom(BigInteger.valueOf(bottomTwips));
        pgMar.setLeft(BigInteger.valueOf(leftTwips));
        return this;
    }

    /**
     * Returns the top margin in twips, or {@code 0} if not explicitly set.
     *
     * @return the top margin in twips, or {@code 0} if unset
     */
    public int marginTop() {
        return marginOf(CTPageMar::getTop);
    }

    /**
     * Returns the right margin in twips, or {@code 0} if not explicitly set.
     *
     * @return the right margin in twips, or {@code 0} if unset
     */
    public int marginRight() {
        return marginOf(CTPageMar::getRight);
    }

    /**
     * Returns the bottom margin in twips, or {@code 0} if not explicitly set.
     *
     * @return the bottom margin in twips, or {@code 0} if unset
     */
    public int marginBottom() {
        return marginOf(CTPageMar::getBottom);
    }

    /**
     * Returns the left margin in twips, or {@code 0} if not explicitly set.
     *
     * @return the left margin in twips, or {@code 0} if unset
     */
    public int marginLeft() {
        return marginOf(CTPageMar::getLeft);
    }

    // ---------- header / footer ----------

    /**
     * Returns the section-scoped default (odd-page) header, creating and attaching an empty one on
     * first access if none is present.
     *
     * <p>The header is resolved through a section-scoped {@code XWPFHeaderFooterPolicy} bound to this
     * section's {@code CTSectPr}, so the returned header belongs to <em>this</em> section: in a
     * multi-section document each {@code Section} carries its own default header. On first access an
     * empty default header part is created and a {@code default} header reference is written onto
     * this section's {@code CTSectPr}; subsequent calls return that same header (create-once).
     *
     * <p>POI exceptions raised while creating or attaching the header part are wrapped into a
     * {@link DocxIOException}. First-page and even-page header variants are out of scope for the
     * MVP and remain reachable via {@code raw()}.
     *
     * @return the default header for this section (never {@code null})
     * @throws DocxIOException if the header part cannot be created or attached
     */
    public Header header() {
        try {
            XWPFHeaderFooterPolicy policy = new XWPFHeaderFooterPolicy(document, delegate);
            XWPFHeader header = policy.getDefaultHeader();
            if (header == null) {
                header = policy.createHeader(XWPFHeaderFooterPolicy.DEFAULT);
            }
            return new Header(header);
        } catch (POIXMLException e) {
            throw new DocxIOException("Failed to create section header", e);
        }
    }

    /**
     * Returns the section-scoped default (odd-page) footer, creating and attaching an empty one on
     * first access if none is present.
     *
     * <p>The footer is resolved through a section-scoped {@code XWPFHeaderFooterPolicy} bound to this
     * section's {@code CTSectPr}, so the returned footer belongs to <em>this</em> section: in a
     * multi-section document each {@code Section} carries its own default footer. On first access an
     * empty default footer part is created and a {@code default} footer reference is written onto
     * this section's {@code CTSectPr}; subsequent calls return that same footer (create-once).
     *
     * <p>POI exceptions raised while creating or attaching the footer part are wrapped into a
     * {@link DocxIOException}. First-page and even-page footer variants are out of scope for the
     * MVP and remain reachable via {@code raw()}.
     *
     * @return the default footer for this section (never {@code null})
     * @throws DocxIOException if the footer part cannot be created or attached
     */
    public Footer footer() {
        try {
            XWPFHeaderFooterPolicy policy = new XWPFHeaderFooterPolicy(document, delegate);
            XWPFFooter footer = policy.getDefaultFooter();
            if (footer == null) {
                footer = policy.createFooter(XWPFHeaderFooterPolicy.DEFAULT);
            }
            return new Footer(footer);
        } catch (POIXMLException e) {
            throw new DocxIOException("Failed to create section footer", e);
        }
    }

    // ---------- escape hatch ----------

    /**
     * Returns the underlying OOXML section properties.
     * <p>
     * Modifications to the returned object affect the document immediately. Use with caution.
     *
     * @return the backing {@code CTSectPr} instance (same instance for the wrapper's lifetime)
     */
    public CTSectPr raw() {
        return delegate;
    }

    // ---------- content equality ----------

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Section)) {
            return false;
        }
        Section that = (Section) o;
        return java.util.Objects.equals(this.paperSize(), that.paperSize())
                && this.orientation() == that.orientation()
                && this.marginTop() == that.marginTop()
                && this.marginRight() == that.marginRight()
                && this.marginBottom() == that.marginBottom()
                && this.marginLeft() == that.marginLeft()
                && java.util.Objects.equals(this.defaultHeaderParagraphs(), that.defaultHeaderParagraphs())
                && java.util.Objects.equals(this.defaultFooterParagraphs(), that.defaultFooterParagraphs());
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(paperSize(), orientation(),
                marginTop(), marginRight(), marginBottom(), marginLeft(),
                defaultHeaderParagraphs(), defaultFooterParagraphs());
    }

    @Override
    public String toString() {
        return "Section{paperSize=" + paperSize()
                + ", orientation=" + orientation()
                + ", margins=[" + marginTop() + "," + marginRight()
                + "," + marginBottom() + "," + marginLeft() + "]}";
    }

    // ---------- internals ----------

    /**
     * Returns the {@code <w:pgSz>} element, creating it if absent.
     */
    private CTPageSz ensurePgSz() {
        return delegate.isSetPgSz() ? delegate.getPgSz() : delegate.addNewPgSz();
    }

    /**
     * Reads a single margin, returning {@code 0} when {@code <w:pgMar>} or the specific attribute
     * is unset.
     */
    private int marginOf(java.util.function.Function<CTPageMar, Object> getter) {
        if (!delegate.isSetPgMar()) {
            return 0;
        }
        Object value = getter.apply(delegate.getPgMar());
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }

    /**
     * Coerces a raw XmlBeans dimension ({@code BigInteger}-as-{@code Object}) to a long, returning
     * {@code 0} when unset.
     */
    private static long twipsOf(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    /**
     * Coerces a raw XmlBeans dimension to a long, returning {@code defaultTwips} when unset.
     */
    private static long dimOrDefault(Object value, int defaultTwips) {
        return value instanceof Number ? ((Number) value).longValue() : defaultTwips;
    }

    // ---------- header / footer (read-only, for equality) ----------

    /**
     * Resolves this section's default header paragraphs read-only — without creating a header part
     * when none is attached. Used by {@code equals} / {@code hashCode} so that a comparison never
     * mutates the document. Returns an empty list when no default header is present or when
     * resolution fails, so {@code equals} never throws.
     */
    private List<Paragraph> defaultHeaderParagraphs() {
        try {
            XWPFHeaderFooterPolicy policy = new XWPFHeaderFooterPolicy(document, delegate);
            XWPFHeader header = policy.getDefaultHeader();
            return header == null ? Collections.emptyList() : new Header(header).paragraphs();
        } catch (POIXMLException e) {
            return Collections.emptyList();
        }
    }

    /**
     * Resolves this section's default footer paragraphs read-only — without creating a footer part
     * when none is attached. Used by {@code equals} / {@code hashCode} so that a comparison never
     * mutates the document. Returns an empty list when no default footer is present or when
     * resolution fails, so {@code equals} never throws.
     */
    private List<Paragraph> defaultFooterParagraphs() {
        try {
            XWPFHeaderFooterPolicy policy = new XWPFHeaderFooterPolicy(document, delegate);
            XWPFFooter footer = policy.getDefaultFooter();
            return footer == null ? Collections.emptyList() : new Footer(footer).paragraphs();
        } catch (POIXMLException e) {
            return Collections.emptyList();
        }
    }
}
