package com.non.docx.core.builder;

import com.non.docx.core.api.table.Row;
import com.non.docx.core.api.table.Table;
import com.non.docx.core.internal.util.Objects;

import java.util.function.Consumer;

/**
 * Construction-track helper for assembling a single {@link Table}.
 *
 * <p>This is a thin wrapper over a live {@link Table}. Row creation delegates to the live table's
 * building blocks ({@link Table#addRow()} and the lambda convenience {@link Table#row(Consumer)});
 * no row or cell behavior is duplicated here — every call reaches the live {@code Table} /
 * {@code Row} / {@code Cell}.
 *
 * <p>Example:
 * <pre>{@code
 * TableBuilder.on(table)
 *     .row(r -> r.cell("A1").cell("B1"))
 *     .row(r -> r.cell("A2").cell("B2"));
 * }</pre>
 * To assemble a table from scratch, prefer {@link DocumentBuilder#table(Consumer)}, which hands the
 * live table straight to a lambda; this class is for callers who prefer an explicit builder object
 * over a lambda.
 *
 * <p>This class references only {@code api/} types — no POI types appear in its signatures.
 */
public final class TableBuilder {

    private final Table table;

    private TableBuilder(Table table) {
        this.table = table;
    }

    /**
     * Creates a builder over the given live table.
     *
     * @param table the live table to assemble into (not {@code null})
     * @return a new builder
     * @throws IllegalArgumentException if {@code table} is {@code null}
     */
    public static TableBuilder on(Table table) {
        Objects.requireNonNull(table, "table");
        return new TableBuilder(table);
    }

    /**
     * Appends a new, empty row and returns the live row, so the caller can populate its cells
     * directly (for example {@code .row().cell("A1").cell("B1")}).
     *
     * @return the newly appended live row
     */
    public Row row() {
        return table.addRow();
    }

    /**
     * Appends a new row, applies the given configurator to it, and returns this builder. The
     * configurator operates on the live {@link Row}.
     *
     * <p>This delegates to {@link Table#row(Consumer)}; no row or cell logic is duplicated.
     *
     * @param config the row configurator, operating on the live row (not {@code null})
     * @return this builder
     * @throws IllegalArgumentException if {@code config} is {@code null}
     */
    public TableBuilder row(Consumer<Row> config) {
        Objects.requireNonNull(config, "config");
        table.row(config);
        return this;
    }

    /**
     * Returns the live table assembled by this builder.
     *
     * @return the backing live table (never {@code null})
     */
    public Table table() {
        return table;
    }
}
