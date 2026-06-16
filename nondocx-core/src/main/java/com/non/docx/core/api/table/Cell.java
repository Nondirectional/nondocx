package com.non.docx.core.api.table;

import com.non.docx.core.api.text.Paragraph;
import com.non.docx.core.internal.util.Objects;
import java.util.AbstractList;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;

/**
 * A cell within a {@link Row} — a container of paragraphs, the smallest addressable unit of a
 * table.
 *
 * <p>Holds an Apache POI {@code XWPFTableCell} delegate and exposes a live view over it. Reads go
 * straight through to the delegate; there is no cached snapshot. Every mutation is write-through.
 *
 * <p>A cell is <em>not</em> a body element — it lives inside a row, inside a table. Its content is
 * an ordered sequence of paragraphs, returned by {@link #paragraphs()}. Content equality ({@code
 * equals} / {@code hashCode}) compares that ordered paragraph sequence, never the delegate
 * reference, so two cells over distinct POI instances but with the same paragraphs are equal — this
 * is what makes round-trip assertions work.
 *
 * <p>{@link #text()} returns the cell's concatenated plain text. {@link #text(String)} writes the
 * given text into the cell's first paragraph (creating the paragraph if the cell is empty, and
 * clearing that paragraph's existing runs) and returns {@code this} for chaining; this mirrors
 * POI's {@code XWPFTableCell.setText}. Paragraphs beyond the first are left in place.
 *
 * <p>This is a <em>mutable live object</em>. Its {@code equals} / {@code hashCode} serve comparison
 * and round-trip assertions; they are not suited as a long-lived {@code HashMap} key, since the
 * underlying content can change at any time.
 */
public final class Cell {

  private final XWPFTableCell delegate;

  /**
   * Wraps the given POI cell.
   *
   * <p>This constructor is the internal seam by which {@link Row} produces live cell wrappers, so
   * it accepts a POI type by design. Users normally obtain cells via {@code Row.cells()} / {@code
   * Row.addCell()} rather than constructing them directly.
   *
   * @param delegate the backing POI cell (not {@code null})
   * @throws IllegalArgumentException if {@code delegate} is {@code null}
   */
  public Cell(XWPFTableCell delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /**
   * Returns a live view of this cell's paragraphs in reading order.
   *
   * <p>The view is re-read from the delegate on every access, so mutations are reflected live.
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
   * Appends a new, empty paragraph to this cell and returns a live wrapper for it.
   *
   * @return the newly appended paragraph
   */
  public Paragraph addParagraph() {
    return new Paragraph(delegate.addParagraph());
  }

  /**
   * Returns this cell's concatenated plain text (all paragraphs joined in reading order).
   *
   * @return the cell's text (possibly empty, never {@code null})
   */
  public String text() {
    return delegate.getText();
  }

  /**
   * Writes the given text into this cell's first paragraph and returns {@code this} for chaining.
   *
   * <p>If the cell is empty a paragraph is created; otherwise the first paragraph's existing runs
   * are cleared and a single run carrying the text is added. Paragraphs beyond the first are left
   * untouched (use {@link #paragraphs()} / {@link #addParagraph()} for multi-paragraph content).
   * This mirrors POI's {@code XWPFTableCell.setText}.
   *
   * @param text the text to write into the first paragraph (not {@code null})
   * @return this cell
   * @throws IllegalArgumentException if {@code text} is {@code null}
   */
  public Cell text(String text) {
    Objects.requireNonNull(text, "text");
    delegate.setText(text);
    return this;
  }

  /**
   * Returns the underlying POI cell.
   *
   * <p>Modifications to the returned object affect the document immediately. Use with caution.
   *
   * @return the backing {@code XWPFTableCell} instance (same instance for the wrapper's lifetime)
   */
  public XWPFTableCell raw() {
    return delegate;
  }

  // ---------- content equality ----------

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Cell)) {
      return false;
    }
    Cell that = (Cell) o;
    return java.util.Objects.equals(this.paragraphs(), that.paragraphs());
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(paragraphs());
  }
}
