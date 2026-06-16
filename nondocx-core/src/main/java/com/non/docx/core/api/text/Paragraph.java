package com.non.docx.core.api.text;

import com.non.docx.core.api.BodyElement;
import com.non.docx.core.api.InlineElement;
import com.non.docx.core.api.exception.DocxIOException;
import com.non.docx.core.api.exception.DocxOperationException;
import com.non.docx.core.api.image.Image;
import com.non.docx.core.api.image.ImageType;
import com.non.docx.core.api.style.Alignment;
import com.non.docx.core.api.style.HeadingLevel;
import com.non.docx.core.api.style.ListKind;
import com.non.docx.core.internal.poi.Mappers;
import com.non.docx.core.internal.poi.Numbering;
import com.non.docx.core.internal.poi.Pictures;
import com.non.docx.core.internal.util.Objects;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.xwpf.usermodel.IRunElement;
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFRun;

/**
 * A paragraph — a body-level block of inline content.
 *
 * <p>Holds an Apache POI {@code XWPFParagraph} delegate and exposes a live view over it. Reads go
 * straight through to the delegate; there is no cached snapshot. Paragraph-level style mutators
 * (heading, alignment, indentation, line spacing) return {@code this} for chaining.
 *
 * <p>The <em>structural source of truth</em> for a paragraph's content is {@link
 * #inlineElements()}: the ordered sequence of runs, hyperlinks and inline images in reading order.
 * A run that carries an embedded picture is surfaced in that view as an {@link Image} (not a {@link
 * Run}), so images take part in the ordering and in content equality. {@link #runs()} is a
 * type-filtered view that keeps only the plain runs; round-trip equality is based on the full
 * {@code inlineElements()} order, so a run followed by a hyperlink followed by a run stays in that
 * order.
 *
 * <p>Content equality ({@code equals} / {@code hashCode}) compares the ordered inline elements, the
 * paragraph-level style (heading, alignment, indentation, line spacing) and list membership (kind
 * and nesting level), never the delegate reference. This is what makes round-trip assertions work.
 *
 * <p><b>List membership:</b> {@link #list(ListKind, int)} marks this paragraph as a member of a
 * bulleted or numbered list at a 0-based nesting level (0..8); {@link #clearList()} removes that
 * membership. {@link #listKind()} and {@link #listLevel()} read it back and report {@code null} for
 * a paragraph that is not part of any list.
 */
public final class Paragraph implements BodyElement {

  private final XWPFParagraph delegate;

  /**
   * Wraps the given POI paragraph.
   *
   * <p>This constructor is the internal seam by which {@code Document} produces live paragraph
   * wrappers, so it accepts a POI type by design. Users normally obtain paragraphs via {@code
   * Document.paragraph(...)} / {@code Document.addParagraph(...)} rather than constructing them
   * directly.
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

  // ---------- inline ordered view (structural source of truth) ----------

  /**
   * Returns a live view of this paragraph's inline content in true reading order.
   *
   * <p>The returned list contains the inline constructs nondocx models — runs, hyperlinks and
   * inline images — preserving their order. A run that carries an embedded picture is surfaced here
   * as an {@link Image} (a hyperlink run is always surfaced as a {@link Hyperlink}). Other inline
   * constructs (for example structured document tags) are excluded; they remain reachable via
   * {@code raw().getIRuns()}. The view is re-read from the delegate on every access, so mutations
   * are reflected live.
   *
   * @return a live, unmodifiable list of inline elements in reading order
   */
  public List<InlineElement> inlineElements() {
    return new AbstractList<InlineElement>() {
      @Override
      public InlineElement get(int index) {
        return wrap(modeledIruns().get(index));
      }

      @Override
      public int size() {
        return modeledIruns().size();
      }
    };
  }

  /**
   * Returns the inline element at the given reading-order index.
   *
   * @param index reading-order index (0-based, into {@link #inlineElements()})
   * @return the inline element at that position
   * @throws IndexOutOfBoundsException if {@code index} is out of range
   */
  public InlineElement inlineElement(int index) {
    return inlineElements().get(index);
  }

  /**
   * Returns a live, type-filtered view of this paragraph's plain runs (in reading order).
   * Hyperlinks are excluded — they are their own inline element type.
   *
   * @return a live, unmodifiable list of runs
   */
  public List<Run> runs() {
    final List<InlineElement> all = inlineElements();
    return new AbstractList<Run>() {
      @Override
      public Run get(int index) {
        int seen = 0;
        for (InlineElement element : all) {
          if (element instanceof Run) {
            if (seen == index) {
              return (Run) element;
            }
            seen++;
          }
        }
        throw new IndexOutOfBoundsException(
            "run index " + index + " out of bounds (paragraph has " + size() + " runs)");
      }

      @Override
      public int size() {
        int count = 0;
        for (InlineElement element : all) {
          if (element instanceof Run) {
            count++;
          }
        }
        return count;
      }
    };
  }

  /**
   * Returns the run at the given filtered run index.
   *
   * @param index run index (0-based, into {@link #runs()})
   * @return the run at that position
   * @throws IndexOutOfBoundsException if {@code index} is out of range
   */
  public Run run(int index) {
    return runs().get(index);
  }

  /**
   * Appends a new, empty run to this paragraph and returns a live wrapper for it.
   *
   * @return the newly appended run
   */
  public Run addRun() {
    return new Run(delegate.createRun());
  }

  /**
   * Appends a new run carrying the given text and returns a live wrapper for it.
   *
   * @param text the run's text (not {@code null})
   * @return the newly appended run
   * @throws IllegalArgumentException if {@code text} is {@code null}
   */
  public Run addRun(String text) {
    Objects.requireNonNull(text, "text");
    XWPFRun run = delegate.createRun();
    run.setText(text);
    return new Run(run);
  }

  /**
   * Appends a new hyperlink carrying the given display text and target URL, and returns a live
   * wrapper for it.
   *
   * @param text the hyperlink's visible text (not {@code null})
   * @param url the hyperlink's target URL (not {@code null})
   * @return the newly appended hyperlink
   * @throws IllegalArgumentException if {@code text} or {@code url} is {@code null}
   */
  public Hyperlink addHyperlink(String text, String url) {
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(url, "url");
    XWPFHyperlinkRun hyperlink = delegate.createHyperlinkRun(url);
    hyperlink.setText(text);
    return new Hyperlink(hyperlink);
  }

  /**
   * Appends a new inline image to this paragraph and returns a live wrapper for it.
   *
   * <p>OOXML embeds an inline picture <em>inside</em> a run (as a drawing). This method creates a
   * fresh run holding only that picture (no text), so it appears as an {@link Image} in {@link
   * #inlineElements()}. The {@code width} and {@code height} are in <em>pixels</em> at 96&nbsp;DPI
   * and are converted to EMU internally (Apache POI's {@code addPicture} stores them as EMU); they
   * survive a save → open round-trip exactly. POI / IO failures while embedding the picture are
   * wrapped into a {@link DocxIOException} on this public surface.
   *
   * @param bytes the raw picture bytes (not {@code null})
   * @param type the image format (not {@code null})
   * @param width the picture width in pixels (96&nbsp;DPI)
   * @param height the picture height in pixels (96&nbsp;DPI)
   * @return the newly appended image
   * @throws IllegalArgumentException if {@code bytes} or {@code type} is {@code null}
   * @throws DocxIOException if the picture cannot be embedded
   */
  public Image addImage(byte[] bytes, ImageType type, int width, int height) {
    Objects.requireNonNull(bytes, "bytes");
    Objects.requireNonNull(type, "type");
    XWPFRun run = delegate.createRun();
    try {
      // Apache POI's addPicture stores width/height verbatim as EMU on the <wp:extent>, so
      // convert pixel inputs (the unit this API exposes) to EMU first for an exact round-trip.
      XWPFPicture picture =
          run.addPicture(
              new ByteArrayInputStream(bytes),
              Mappers.toPoi(type),
              "image",
              Pictures.emuFromPixels(width),
              Pictures.emuFromPixels(height));
      return new Image(picture);
    } catch (IOException | InvalidFormatException | POIXMLException e) {
      throw new DocxIOException("Failed to add inline image", e);
    }
  }

  /**
   * Removes the inline element at the given reading-order index.
   *
   * @param index reading-order index (0-based, into {@link #inlineElements()})
   * @throws IndexOutOfBoundsException if {@code index} is out of range
   */
  public void removeInlineElement(int index) {
    List<IRunElement> modeled = modeledIruns();
    if (index < 0 || index >= modeled.size()) {
      throw new IndexOutOfBoundsException(
          "inline element index "
              + index
              + " out of bounds (paragraph has "
              + modeled.size()
              + " inline elements)");
    }
    IRunElement target = modeled.get(index);
    // POI's removeRun(int) indexes into getRuns(), which holds run + hyperlink-run instances.
    // Locate the target by identity, then drop it at that position.
    List<XWPFRun> poiRuns = delegate.getRuns();
    int pos = -1;
    for (int i = 0; i < poiRuns.size(); i++) {
      if (poiRuns.get(i) == target) {
        pos = i;
        break;
      }
    }
    if (pos < 0) {
      throw new DocxOperationException(
          "Inline element at index " + index + " could not be located for removal", "paragraph");
    }
    delegate.removeRun(pos);
  }

  // ---------- paragraph-level style ----------

  /**
   * Applies a heading level to this paragraph and returns {@code this}. This sets the paragraph's
   * style to the matching built-in heading style ({@code Heading1} … {@code Heading6}).
   *
   * @param level the heading level (not {@code null})
   * @return this paragraph
   * @throws IllegalArgumentException if {@code level} is {@code null}
   */
  public Paragraph heading(HeadingLevel level) {
    Objects.requireNonNull(level, "level");
    delegate.setStyle(Mappers.toStyleId(level));
    return this;
  }

  /**
   * Clears any heading style from this paragraph (restoring it to body/non-heading text) and
   * returns {@code this}.
   *
   * @return this paragraph
   */
  public Paragraph clearHeading() {
    delegate.setStyle(null);
    return this;
  }

  /**
   * Returns this paragraph's heading level, or {@code null} if it is not a heading (including when
   * it carries a non-heading style).
   *
   * @return the heading level, or {@code null} if this is not a heading paragraph
   */
  public HeadingLevel heading() {
    return Mappers.headingFromStyle(delegate.getStyle());
  }

  /**
   * Sets the horizontal alignment and returns {@code this}.
   *
   * @param alignment the alignment (not {@code null})
   * @return this paragraph
   * @throws IllegalArgumentException if {@code alignment} is {@code null}
   */
  public Paragraph alignment(Alignment alignment) {
    Objects.requireNonNull(alignment, "alignment");
    delegate.setAlignment(Mappers.toPoi(alignment));
    return this;
  }

  /**
   * Returns the horizontal alignment. A paragraph with no explicit alignment is reported as {@link
   * Alignment#LEFT} (Word's default).
   *
   * @return the alignment (never {@code null})
   */
  public Alignment alignment() {
    return Mappers.fromPoi(delegate.getAlignment());
  }

  /**
   * Sets the left and first-line indentation (in twips, 1/20 of a point) and returns {@code this}.
   *
   * @param leftTwips the left indentation in twips
   * @param firstLineTwips the first-line indentation in twips (may be negative for a hanging
   *     indent)
   * @return this paragraph
   */
  public Paragraph indent(int leftTwips, int firstLineTwips) {
    delegate.setIndentationLeft(leftTwips);
    delegate.setIndentationFirstLine(firstLineTwips);
    return this;
  }

  /**
   * Returns the left indentation in twips, as stored on this paragraph.
   *
   * @return the left indentation in twips
   */
  public int indentationLeft() {
    return delegate.getIndentationLeft();
  }

  /**
   * Returns the first-line indentation in twips, as stored on this paragraph.
   *
   * @return the first-line indentation in twips
   */
  public int indentationFirstLine() {
    return delegate.getIndentationFirstLine();
  }

  /**
   * Sets the line spacing as a multiple of single-line height and returns {@code this}. For
   * example, {@code 1.0} is single spacing, {@code 1.5} is 1.5 lines, and {@code 2.0} is double.
   *
   * @param multiple the line spacing as a multiple of single-line height
   * @return this paragraph
   */
  public Paragraph lineSpacing(double multiple) {
    delegate.setSpacingBetween(multiple);
    return this;
  }

  /**
   * Returns the line spacing as a multiple of single-line height, or {@code -1.0} if line spacing
   * is not explicitly set on this paragraph.
   *
   * @return the line spacing multiple, or {@code -1.0} if unset
   */
  public double lineSpacing() {
    return delegate.getSpacingBetween();
  }

  // ---------- list membership ----------

  /**
   * Marks this paragraph as a member of a list of the given kind at the given 0-based nesting
   * level, and returns {@code this}. The level must be in the range {@code 0..8}. All paragraphs in
   * the same list kind share one numbering definition on the document; they differ only by nesting
   * level.
   *
   * @param kind the list kind (not {@code null})
   * @param level the 0-based nesting level ({@code 0..8})
   * @return this paragraph
   * @throws IllegalArgumentException if {@code kind} is {@code null} or {@code level} is out of
   *     range
   * @throws DocxOperationException if this paragraph is not attached to a document
   */
  public Paragraph list(ListKind kind, int level) {
    Objects.requireNonNull(kind, "kind");
    Numbering.apply(delegate, kind, level);
    return this;
  }

  /**
   * Removes list membership from this paragraph and returns {@code this}. After this call {@link
   * #listKind()} and {@link #listLevel()} report {@code null}.
   *
   * @return this paragraph
   */
  public Paragraph clearList() {
    Numbering.clear(delegate);
    return this;
  }

  /**
   * Returns the list kind this paragraph belongs to, or {@code null} if it is not part of any list.
   *
   * @return the list kind, or {@code null} if this paragraph is not a list member
   */
  public ListKind listKind() {
    return Numbering.kindOf(delegate);
  }

  /**
   * Returns the 0-based nesting level of this paragraph within its list, or {@code null} if it is
   * not part of any list. A list paragraph with no explicit level is reported as {@code 0}.
   *
   * @return the nesting level, or {@code null} if this paragraph is not a list member
   */
  public Integer listLevel() {
    return Numbering.levelOf(delegate);
  }

  // ---------- escape hatch ----------

  /**
   * Returns the underlying POI paragraph.
   *
   * <p>Modifications to the returned object affect the document immediately. Use with caution.
   *
   * @return the backing {@code XWPFParagraph} instance (same instance for the wrapper's lifetime)
   */
  public XWPFParagraph raw() {
    return delegate;
  }

  // ---------- content equality ----------

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Paragraph)) {
      return false;
    }
    Paragraph that = (Paragraph) o;
    return java.util.Objects.equals(this.inlineElements(), that.inlineElements())
        && java.util.Objects.equals(this.heading(), that.heading())
        && this.alignment() == that.alignment()
        && this.indentationLeft() == that.indentationLeft()
        && this.indentationFirstLine() == that.indentationFirstLine()
        && Double.doubleToLongBits(this.lineSpacing())
            == Double.doubleToLongBits(that.lineSpacing())
        && java.util.Objects.equals(this.listKind(), that.listKind())
        && java.util.Objects.equals(this.listLevel(), that.listLevel());
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(
        inlineElements(),
        heading(),
        alignment(),
        indentationLeft(),
        indentationFirstLine(),
        lineSpacing(),
        listKind(),
        listLevel());
  }

  // ---------- internals ----------

  /**
   * Returns the modeled inline elements (runs and hyperlink runs) in reading order, excluding
   * inline constructs nondocx does not model (for example structured document tags). Re-derived on
   * each call so the resulting view stays live.
   */
  private List<IRunElement> modeledIruns() {
    List<IRunElement> modeled = new ArrayList<>();
    for (IRunElement element : delegate.getIRuns()) {
      // XWPFHyperlinkRun extends XWPFRun, so this also catches hyperlink runs and field runs.
      if (element instanceof XWPFRun) {
        modeled.add(element);
      }
    }
    return modeled;
  }

  /**
   * Wraps a POI inline element as a nondocx inline element. {@code XWPFHyperlinkRun} is checked
   * before {@code XWPFRun} because the former subclasses the latter. A plain run that carries an
   * embedded picture is surfaced as an {@link Image}; otherwise it is surfaced as a {@link Run}.
   */
  private static InlineElement wrap(IRunElement element) {
    if (element instanceof XWPFHyperlinkRun) {
      return new Hyperlink((XWPFHyperlinkRun) element);
    }
    if (element instanceof XWPFRun) {
      XWPFRun run = (XWPFRun) element;
      if (!run.getEmbeddedPictures().isEmpty()) {
        return new Image(run.getEmbeddedPictures().get(0));
      }
    }
    return new Run((XWPFRun) element);
  }
}
