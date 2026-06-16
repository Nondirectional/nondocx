package com.non.docx.core.api.table;

import com.non.docx.core.api.BodyElement;
import com.non.docx.core.internal.util.Objects;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.util.AbstractList;
import java.util.List;

/**
 * A table — a body-level block of rows and cells.
 *
 * <p>Holds an Apache POI {@code XWPFTable} delegate and exposes a live view over it. Reads go
 * straight through to the delegate; there is no cached snapshot. Every mutation is write-through —
 * the underlying POI table changes immediately.
 *
 * <p>The <em>structural source of truth</em> for a table's content is {@link #rows()}: the ordered
 * sequence of rows from top to bottom, each a live view over its cells. Content equality
 * ({@code equals} / {@code hashCode}) compares that ordered row sequence, never the delegate
 * reference, so two tables over distinct POI instances but with the same rows are equal — this is
 * what makes round-trip assertions work.
 *
 * <p>This is a <em>mutable live object</em>. Its {@code equals} / {@code hashCode} serve comparison
 * and round-trip assertions; they are not suited as a long-lived {@code HashMap} key, since the
 * underlying content can change at any time.
 */
public final class Table implements BodyElement {

    private final XWPFTable delegate;

    /**
     * Wraps the given POI table.
     *
     * <p>This constructor is the internal seam by which {@code Document} produces live table
     * wrappers, so it accepts a POI type by design. Users normally obtain tables via
     * {@code Document.tables()} / {@code Document.addTable()} rather than constructing them directly.
     *
     * @param delegate the backing POI table (not {@code null})
     * @throws IllegalArgumentException if {@code delegate} is {@code null}
     */
    public Table(XWPFTable delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * Returns a live view of this table's rows in top-to-bottom order.
     *
     * <p>The view is re-read from the delegate on every access, so mutations (added or removed rows)
     * are reflected live.
     *
     * @return a live, unmodifiable list of rows
     */
    public List<Row> rows() {
        return new AbstractList<Row>() {
            private final List<XWPFTableRow> backing = delegate.getRows();

            @Override
            public Row get(int index) {
                return new Row(backing.get(index));
            }

            @Override
            public int size() {
                return backing.size();
            }
        };
    }

    /**
     * Returns the row at the given index.
     *
     * @param index row index (0-based, into {@link #rows()})
     * @return the row at that position
     * @throws IndexOutOfBoundsException if {@code index} is out of range
     */
    public Row row(int index) {
        return rows().get(index);
    }

    /**
     * Appends a new, empty row to this table and returns a live wrapper for it.
     *
     * @return the newly appended row
     */
    public Row addRow() {
        XWPFTableRow created = delegate.createRow();
        // POI pre-populates a new row with one or more default cells (it mirrors the table grid
        // once one is established); clear them so addRow() yields an empty row.
        while (created.getTableCells().size() > 0) {
            created.removeCell(0);
        }
        return new Row(created);
    }

    /**
     * Removes the row at the given index.
     *
     * @param index row index (0-based, into {@link #rows()})
     * @throws IndexOutOfBoundsException if {@code index} is out of range
     */
    public void removeRow(int index) {
        int size = delegate.getRows().size();
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("row index " + index
                    + " out of bounds (table has " + size + " rows)");
        }
        delegate.removeRow(index);
    }

    /**
     * Returns the underlying POI table.
     * <p>
     * Modifications to the returned object affect the document immediately. Use with caution.
     *
     * @return the backing {@code XWPFTable} instance (same instance for the wrapper's lifetime)
     */
    public XWPFTable raw() {
        return delegate;
    }

    // ---------- content equality ----------

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Table)) {
            return false;
        }
        Table that = (Table) o;
        return java.util.Objects.equals(this.rows(), that.rows());
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(rows());
    }
}
