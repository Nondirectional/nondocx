package com.non.docx.core.api.table;

import com.non.docx.core.api.BodyElement;
import com.non.docx.core.internal.util.Objects;
import org.apache.poi.xwpf.usermodel.XWPFTable;

/**
 * A table — a body-level block of rows and cells.
 *
 * <p>Holds an Apache POI {@code XWPFTable} delegate and exposes a live view over it. Reads go
 * straight through to the delegate; there is no cached snapshot.
 *
 * <p><b>Minimal at this phase; completed in Phase 4.</b> Only the construction seam and the
 * {@link #raw()} escape hatch are present here. Row/cell access and content equality are added in
 * Phase 4.
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
     * Returns the underlying POI table.
     * <p>
     * Modifications to the returned object affect the document immediately. Use with caution.
     *
     * @return the backing {@code XWPFTable} instance (same instance for the wrapper's lifetime)
     */
    public XWPFTable raw() {
        return delegate;
    }
}
