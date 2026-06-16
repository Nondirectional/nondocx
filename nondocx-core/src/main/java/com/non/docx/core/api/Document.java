package com.non.docx.core.api;

import com.non.docx.core.api.exception.DocxIOException;
import com.non.docx.core.api.section.Section;
import com.non.docx.core.api.table.Table;
import com.non.docx.core.api.text.Paragraph;
import com.non.docx.core.internal.util.Objects;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.xmlbeans.XmlCursor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;

/**
 * A live, mutable docx document wrapping an Apache POI {@code XWPFDocument}.
 *
 * <p>A {@code Document} holds a single {@code XWPFDocument} delegate and exposes a domain view over
 * it. All reads go straight through to the delegate (there is no cached snapshot), and every
 * mutation is write-through — the underlying POI document changes immediately.
 *
 * <p>The <em>structural source of truth</em> is the ordered body: {@link #bodyElements()} returns
 * paragraphs and tables in their true Word-body order. The {@link #paragraphs()} and {@link #tables()}
 * views are type-filtered projections of that order. Paragraph and table indices used by
 * {@link #paragraph(int)} / {@link #removeParagraph(int)} / {@link #tables()} refer to the filtered
 * views, while {@link #insertParagraph(int)} takes a body-order index to stay unambiguous when
 * paragraphs and tables are interleaved.
 *
 * <p><b>Sections ({@code sections()}, {@code section(int)}) are wired and cover page properties
 * (paper size, orientation, margins) plus section-scoped {@link com.non.docx.core.api.header.Header}
 * / {@link com.non.docx.core.api.header.Footer} accessors. {@link #header()} and {@link #footer()}
 * are conveniences that return the first section's default header / footer.</b>
 *
 * <p>{@code Document} implements {@link AutoCloseable}; closing it releases the underlying POI
 * resources.
 *
 * <p><b>Content equality.</b> {@code equals} / {@code hashCode} compare content derived from the
 * delegate — the ordered {@link #bodyElements()} sequence and the ordered {@link #sections()}
 * sequence — and never the delegate reference itself. Two documents are equal when their body
 * element sequence (paragraphs and tables in true Word-body order) and their section sequence
 * (page properties plus section-scoped default header / footer content) are element-by-element
 * content-equal. This is exactly what round-trip assertions need: a document saved then reopened
 * is necessarily backed by a different {@code XWPFDocument} instance yet compares equal to the
 * original. {@code Document} is a mutable live object; {@code equals} / {@code hashCode} serve
 * comparison and testing and are not suited as a long-lived {@code HashMap} key, since the
 * underlying content can change at any time.
 */
public final class Document implements AutoCloseable {

    private final XWPFDocument delegate;

    /**
     * Wraps the given POI document.
     *
     * <p>This constructor is the internal seam by which the {@code Docx} factory produces document
     * instances, so it accepts a POI type by design. Users obtain documents via
     * {@code Docx.open(...)} / {@code Docx.create()} rather than constructing them directly.
     *
     * @param delegate the backing POI document (not {@code null})
     * @throws IllegalArgumentException if {@code delegate} is {@code null}
     */
    public Document(XWPFDocument delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    // ---------- ordered body view (structural source of truth) ----------

    /**
     * Returns a live view of the document body in true Word-body order.
     *
     * <p>The returned list contains only the body constructs nondocx models — paragraphs and
     * tables — preserving their relative order. Other body constructs (for example structured
     * document tags) are excluded; they remain reachable via {@code raw().getBodyElements()}.
     * The view is re-read from the delegate on every access, so mutations are reflected live.
     *
     * @return a live, unmodifiable list of body elements in document order
     */
    public List<BodyElement> bodyElements() {
        return new AbstractList<BodyElement>() {
            @Override
            public BodyElement get(int index) {
                return wrap(modeledBody().get(index));
            }

            @Override
            public int size() {
                return modeledBody().size();
            }
        };
    }

    /**
     * Returns the body element at the given body-order index.
     *
     * @param index body-order index (0-based, into {@link #bodyElements()})
     * @return the body element at that position
     * @throws IndexOutOfBoundsException if {@code index} is out of range
     */
    public BodyElement bodyElement(int index) {
        return bodyElements().get(index);
    }

    // ---------- paragraph views ----------

    /**
     * Returns a live, type-filtered view of the body paragraphs (in document order).
     *
     * @return a live, unmodifiable list of paragraphs
     */
    public List<Paragraph> paragraphs() {
        return new AbstractList<Paragraph>() {
            private final List<XWPFParagraph> backing = delegate.getParagraphs();

            @Override
            public Paragraph get(int index) {
                return new Paragraph(backing.get(index));
            }

            @Override
            public int size() {
                return backing.size();
            }
        };
    }

    /**
     * Returns the paragraph at the given filtered paragraph index.
     *
     * @param index paragraph index (0-based, into {@link #paragraphs()})
     * @return the paragraph at that position
     * @throws IndexOutOfBoundsException if {@code index} is out of range
     */
    public Paragraph paragraph(int index) {
        return paragraphs().get(index);
    }

    /**
     * Appends a new, empty paragraph at the end of the body and returns a live wrapper for it.
     *
     * @return the newly appended paragraph
     */
    public Paragraph addParagraph() {
        return new Paragraph(delegate.createParagraph());
    }

    /**
     * Appends a new paragraph containing the given text and returns a live wrapper for it. This is
     * a convenience for {@code addParagraph()} followed by adding a run with the text.
     *
     * @param text the paragraph text (not {@code null})
     * @return the newly appended paragraph
     * @throws IllegalArgumentException if {@code text} is {@code null}
     */
    public Paragraph addParagraph(String text) {
        Objects.requireNonNull(text, "text");
        XWPFParagraph paragraph = delegate.createParagraph();
        paragraph.createRun().setText(text);
        return new Paragraph(paragraph);
    }

    /**
     * Inserts a new, empty paragraph at the given body-order index and returns a live wrapper for
     * it. The new paragraph takes that index, shifting the element previously there (and all later
     * elements) one position toward the end.
     *
     * @param bodyIndex body-order index (0-based, into {@link #bodyElements()}); {@code size()}
     *                  appends at the end
     * @return the newly inserted paragraph
     * @throws IndexOutOfBoundsException if {@code bodyIndex < 0} or {@code bodyIndex > size()}
     */
    public Paragraph insertParagraph(int bodyIndex) {
        List<IBodyElement> modeled = modeledBody();
        if (bodyIndex < 0 || bodyIndex > modeled.size()) {
            throw new IndexOutOfBoundsException("bodyIndex " + bodyIndex
                    + " out of bounds (body has " + modeled.size() + " modeled elements)");
        }
        if (bodyIndex == modeled.size()) {
            return new Paragraph(delegate.createParagraph());
        }
        IBodyElement target = modeled.get(bodyIndex);
        XmlCursor cursor;
        if (target instanceof XWPFParagraph) {
            cursor = ((XWPFParagraph) target).getCTP().newCursor();
        } else if (target instanceof XWPFTable) {
            cursor = ((XWPFTable) target).getCTTbl().newCursor();
        } else {
            throw new IllegalArgumentException(
                    "Cannot insert before body element of type " + target.getClass().getName());
        }
        try {
            return new Paragraph(delegate.insertNewParagraph(cursor));
        } finally {
            cursor.dispose();
        }
    }

    /**
     * Removes the paragraph at the given filtered paragraph index.
     *
     * @param paragraphIndex paragraph index (0-based, into {@link #paragraphs()})
     * @throws IndexOutOfBoundsException if {@code paragraphIndex} is out of range
     */
    public void removeParagraph(int paragraphIndex) {
        List<XWPFParagraph> paragraphs = delegate.getParagraphs();
        if (paragraphIndex < 0 || paragraphIndex >= paragraphs.size()) {
            throw new IndexOutOfBoundsException("paragraphIndex " + paragraphIndex
                    + " out of bounds (document has " + paragraphs.size() + " paragraphs)");
        }
        XWPFParagraph target = paragraphs.get(paragraphIndex);
        int bodyPos = delegate.getPosOfParagraph(target);
        delegate.removeBodyElement(bodyPos);
    }

    // ---------- table views ----------

    /**
     * Returns a live, type-filtered view of the body tables (in document order).
     *
     * @return a live, unmodifiable list of tables
     */
    public List<Table> tables() {
        return new AbstractList<Table>() {
            private final List<XWPFTable> backing = delegate.getTables();

            @Override
            public Table get(int index) {
                return new Table(backing.get(index));
            }

            @Override
            public int size() {
                return backing.size();
            }
        };
    }

    /**
     * Appends a new, empty table at the end of the body and returns a live wrapper for it.
     *
     * @return the newly appended table
     */
    public Table addTable() {
        XWPFTable created = delegate.createTable();
        // POI pre-populates a new table with a default row; clear it so addTable() yields a
        // genuinely empty table, matching nondocx's addX-exactly-one semantics (addParagraph /
        // addRun add exactly one element with no phantom defaults).
        while (created.getRows().size() > 0) {
            created.removeRow(0);
        }
        return new Table(created);
    }

    // ---------- section views ----------

    /**
     * Returns a live view of the document's sections, in document order.
     *
     * <p>Each mid-document section break is stored on the paragraph that ends the section (its
     * {@code <w:pPr>/<w:sectPr>}); the document body's final {@code <w:sectPr>} is the last
     * section. This view walks the body in order, yielding one {@link Section} per section break
     * followed by the final body section. A document therefore always has at least one section —
     * the body section is created on first access if absent.
     *
     * <p>The returned list re-reads the underlying {@code XWPFDocument} on every access, so
     * mutations (including those made via {@code raw()}) are reflected live.
     *
     * @return a live, unmodifiable list of sections in document order (never empty)
     */
    public List<Section> sections() {
        return new AbstractList<Section>() {
            @Override
            public Section get(int index) {
                List<CTSectPr> sectPrs = resolveSectionProperties();
                if (index < 0 || index >= sectPrs.size()) {
                    throw new IndexOutOfBoundsException("section index " + index
                            + " out of bounds (document has " + sectPrs.size() + " sections)");
                }
                return new Section(delegate, sectPrs.get(index));
            }

            @Override
            public int size() {
                return resolveSectionProperties().size();
            }
        };
    }

    /**
     * Returns the section at the given document-order index.
     *
     * @param index section index (0-based, into {@link #sections()})
     * @return the section at that position
     * @throws IndexOutOfBoundsException if {@code index} is out of range
     */
    public Section section(int index) {
        return sections().get(index);
    }

    // ---------- header / footer (convenience) ----------

    /**
     * Returns the default (odd-page) header of the document's first section (section 0).
     *
     * <p>This is a convenience for {@code section(0).header()}. In a single-section document this is
     * the document's header. In a multi-section document, prefer {@link Section#header()} to address
     * each section's header individually.
     *
     * @return the default header of the first section (never {@code null})
     * @throws com.non.docx.core.api.exception.DocxIOException if the header part cannot be created or attached
     */
    public com.non.docx.core.api.header.Header header() {
        return section(0).header();
    }

    /**
     * Returns the default (odd-page) footer of the document's first section (section 0).
     *
     * <p>This is a convenience for {@code section(0).footer()}. In a single-section document this is
     * the document's footer. In a multi-section document, prefer {@link Section#footer()} to address
     * each section's footer individually.
     *
     * @return the default footer of the first section (never {@code null})
     * @throws com.non.docx.core.api.exception.DocxIOException if the footer part cannot be created or attached
     */
    public com.non.docx.core.api.header.Footer footer() {
        return section(0).footer();
    }

    // ---------- save ----------

    /**
     * Writes this document to the given file. The file stream is opened and closed by this method.
     *
     * @param file the destination file (not {@code null})
     * @throws DocxIOException        if the file cannot be written
     * @throws IllegalArgumentException if {@code file} is {@code null}
     */
    public void save(File file) {
        Objects.requireNonNull(file, "file");
        save(file.toPath());
    }

    /**
     * Writes this document to the given path. The file stream is opened and closed by this method.
     *
     * @param path the destination path (not {@code null})
     * @throws DocxIOException        if the file cannot be written
     * @throws IllegalArgumentException if {@code path} is {@code null}
     */
    public void save(Path path) {
        Objects.requireNonNull(path, "path");
        try (OutputStream out = Files.newOutputStream(path)) {
            writeTo(out, path.toString());
        } catch (IOException e) {
            throw new DocxIOException("Failed to save document to " + path, e);
        }
    }

    /**
     * Writes this document to the given stream. The stream is <em>not</em> closed; the caller
     * retains ownership.
     *
     * @param out the destination stream (not {@code null}, not closed by this method)
     * @throws DocxIOException        if the document cannot be serialized
     * @throws IllegalArgumentException if {@code out} is {@code null}
     */
    public void save(OutputStream out) {
        Objects.requireNonNull(out, "out");
        writeTo(out, null);
    }

    private void writeTo(OutputStream out, String context) {
        try {
            delegate.write(out);
        } catch (IOException | POIXMLException e) {
            String message = "Failed to save document" + (context == null ? "" : " to " + context);
            throw new DocxIOException(message, e);
        }
    }

    // ---------- lifecycle ----------

    /**
     * Releases the underlying POI resources.
     *
     * @throws DocxIOException if closing the underlying document fails
     */
    @Override
    public void close() {
        try {
            delegate.close();
        } catch (IOException e) {
            throw new DocxIOException("Failed to close document", e);
        }
    }

    // ---------- escape hatch ----------

    /**
     * Returns the underlying POI document.
     * <p>
     * Modifications to the returned object affect the document immediately. Use with caution.
     *
     * @return the backing {@code XWPFDocument} instance (same instance for the wrapper's lifetime)
     */
    public XWPFDocument raw() {
        return delegate;
    }

    // ---------- content equality ----------

    /**
     * Compares this document to another for content equality.
     *
     * <p>Two documents are equal when their ordered body element sequences (paragraphs and tables
     * in true Word-body order) are element-by-element content-equal <em>and</em> their ordered
     * section sequences (page properties plus section-scoped default header / footer content) are
     * element-by-element content-equal. The comparison is driven entirely by the content views
     * {@link #bodyElements()} and {@link #sections()} and never touches the backing
     * {@code XWPFDocument} reference, so a document saved then reopened — necessarily backed by a
     * different {@code XWPFDocument} instance — compares equal to the original. This is the
     * round-trip fidelity contract.
     *
     * <p>{@code Document} is a mutable live object; this method and {@link #hashCode()} serve
     * comparison and testing and are not suited as a long-lived {@code HashMap} key, since the
     * underlying content can change at any time.
     *
     * @param o the object to compare against
     * @return {@code true} if {@code o} is a {@code Document} with equal body and section content
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Document)) {
            return false;
        }
        Document that = (Document) o;
        return java.util.Objects.equals(this.bodyElements(), that.bodyElements())
                && java.util.Objects.equals(this.sections(), that.sections());
    }

    /**
     * Returns a content-based hash code for this document, consistent with {@link #equals(Object)}.
     *
     * @return the hash code over the body element and section sequences
     */
    @Override
    public int hashCode() {
        return java.util.Objects.hash(bodyElements(), sections());
    }

    @Override
    public String toString() {
        return "Document{bodyElements=" + bodyElements().size() + '}';
    }

    // ---------- internals ----------

    /**
     * Returns the modeled body elements (paragraphs and tables) in true Word-body order, excluding
     * body constructs nondocx does not yet model (for example structured document tags). Re-derived
     * on each call so the resulting view stays live.
     */
    private List<IBodyElement> modeledBody() {
        List<IBodyElement> modeled = new ArrayList<>();
        for (IBodyElement element : delegate.getBodyElements()) {
            if (element instanceof XWPFParagraph || element instanceof XWPFTable) {
                modeled.add(element);
            }
        }
        return modeled;
    }

    /**
     * Collects the document's section-property elements in true document order: first every
     * paragraph-level {@code <w:sectPr>} (each marks where a mid-document section ends), then the
     * body's final {@code <w:sectPr>} as the last section. The body section is created on demand if
     * absent, so the returned list is never empty and the document always exposes at least one
     * section. Re-derived on every call so the {@code sections()} view stays live.
     */
    private List<CTSectPr> resolveSectionProperties() {
        List<CTSectPr> sectPrs = new ArrayList<>();
        for (XWPFParagraph paragraph : delegate.getParagraphs()) {
            CTPPr pPr = paragraph.getCTP().getPPr();
            if (pPr != null && pPr.isSetSectPr()) {
                sectPrs.add(pPr.getSectPr());
            }
        }
        CTBody body = delegate.getDocument().getBody();
        sectPrs.add(body.isSetSectPr() ? body.getSectPr() : body.addNewSectPr());
        return sectPrs;
    }

    private static BodyElement wrap(IBodyElement element) {
        if (element instanceof XWPFParagraph) {
            return new Paragraph((XWPFParagraph) element);
        }
        if (element instanceof XWPFTable) {
            return new Table((XWPFTable) element);
        }
        throw new IllegalArgumentException(
                "Unsupported body element type: " + element.getClass().getName());
    }
}
