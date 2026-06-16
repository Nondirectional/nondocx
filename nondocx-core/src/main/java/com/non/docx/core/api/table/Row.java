package com.non.docx.core.api.table;

import com.non.docx.core.internal.util.Objects;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.util.AbstractList;
import java.util.List;

/**
 * A row within a {@link Table} — an ordered sequence of cells from left to right.
 *
 * <p>Holds an Apache POI {@code XWPFTableRow} delegate and exposes a live view over it. Reads go
 * straight through to the delegate; there is no cached snapshot. Every mutation is write-through.
 *
 * <p>The <em>structural source of truth</em> for a row's content is {@link #cells()}: the ordered
 * sequence of cells from left to right. Content equality ({@code equals} / {@code hashCode}) compares
 * that ordered cell sequence, never the delegate reference, so two rows over distinct POI instances
 * but with the same cells are equal — this is what makes round-trip assertions work.
 *
 * <p>This is a <em>mutable live object</em>. Its {@code equals} / {@code hashCode} serve comparison
 * and round-trip assertions; they are not suited as a long-lived {@code HashMap} key, since the
 * underlying content can change at any time.
 */
public final class Row {

    private final XWPFTableRow delegate;

    /**
     * Wraps the given POI row.
     *
     * <p>This constructor is the internal seam by which {@link Table} produces live row wrappers,
     * so it accepts a POI type by design. Users normally obtain rows via {@code Table.rows()} /
     * {@code Table.addRow()} rather than constructing them directly.
     *
     * @param delegate the backing POI row (not {@code null})
     * @throws IllegalArgumentException if {@code delegate} is {@code null}
     */
    public Row(XWPFTableRow delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * Returns a live view of this row's cells in left-to-right order.
     *
     * <p>The view is re-read from the delegate on every access, so mutations (added or removed cells)
     * are reflected live.
     *
     * @return a live, unmodifiable list of cells
     */
    public List<Cell> cells() {
        return new AbstractList<Cell>() {
            private final List<XWPFTableCell> backing = delegate.getTableCells();

            @Override
            public Cell get(int index) {
                return new Cell(backing.get(index));
            }

            @Override
            public int size() {
                return backing.size();
            }
        };
    }

    /**
     * Returns the cell at the given index.
     *
     * @param index cell index (0-based, into {@link #cells()})
     * @return the cell at that position
     * @throws IndexOutOfBoundsException if {@code index} is out of range
     */
    public Cell cell(int index) {
        return cells().get(index);
    }

    /**
     * Appends a new, empty cell to this row and returns a live wrapper for it.
     *
     * @return the newly appended cell
     */
    public Cell addCell() {
        XWPFTableCell created = delegate.createCell();
        // POI pre-populates a new cell with a default empty paragraph; clear it so addCell()
        // yields an empty cell — content is then added via text(String) or addParagraph().
        while (created.getParagraphs().size() > 0) {
            created.removeParagraph(0);
        }
        return new Cell(created);
    }

    /**
     * Removes the cell at the given index.
     *
     * @param index cell index (0-based, into {@link #cells()})
     * @throws IndexOutOfBoundsException if {@code index} is out of range
     */
    public void removeCell(int index) {
        int size = delegate.getTableCells().size();
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("cell index " + index
                    + " out of bounds (row has " + size + " cells)");
        }
        delegate.removeCell(index);
    }

    /**
     * Returns the underlying POI row.
     * <p>
     * Modifications to the returned object affect the document immediately. Use with caution.
     *
     * @return the backing {@code XWPFTableRow} instance (same instance for the wrapper's lifetime)
     */
    public XWPFTableRow raw() {
        return delegate;
    }

    // ---------- content equality ----------

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Row)) {
            return false;
        }
        Row that = (Row) o;
        return java.util.Objects.equals(this.cells(), that.cells());
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(cells());
    }
}
