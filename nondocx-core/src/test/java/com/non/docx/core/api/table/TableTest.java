package com.non.docx.core.api.table;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.text.Paragraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies table round-trip, live row/cell mutation, out-of-bounds handling, and content equality
 * for {@link Table}, {@link Row}, and {@link Cell}.
 */
class TableTest {

    private static final String[][] GRID = {
            {"A1", "B1", "C1"},
            {"A2", "B2", "C2"}
    };

    /** Builds a fresh 2x3 table populated with the {@link #GRID} values. */
    private static Table buildGridTable(Document doc) {
        Table table = doc.addTable();
        for (String[] rowValues : GRID) {
            Row row = table.addRow();
            for (String value : rowValues) {
                row.addCell().text(value);
            }
        }
        return table;
    }

    @Test
    void roundTripsCellsTextAndCounts(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("table.docx");

        Document original = Docx.create();
        buildGridTable(original);
        original.save(file);

        try (Document opened = Docx.open(file)) {
            assertThat(opened.tables()).hasSize(1);
            Table table = opened.tables().get(0);

            assertThat(table.rows()).hasSize(GRID.length);
            for (int r = 0; r < GRID.length; r++) {
                Row row = table.row(r);
                assertThat(row.cells()).as("row %d cell count", r).hasSize(GRID[r].length);
                for (int c = 0; c < GRID[r].length; c++) {
                    assertThat(row.cell(c).text())
                            .as("cell [%d][%d] text", r, c)
                            .isEqualTo(GRID[r][c]);
                    assertThat(row.cell(c).paragraphs())
                            .as("cell [%d][%d] has one paragraph", r, c)
                            .hasSize(1);
                }
            }
        }
    }

    @Test
    void addRowAndRemoveRowMutateLive() {
        Document doc = Docx.create();
        Table table = buildGridTable(doc);

        assertThat(table.rows()).hasSize(2);
        table.addRow();            // live append
        assertThat(table.rows()).hasSize(3);
        table.removeRow(0);        // live remove first row
        assertThat(table.rows()).hasSize(2);
        // the row that was originally second (now first) carries A2
        assertThat(table.row(0).cell(0).text()).isEqualTo("A2");
    }

    @Test
    void removeRowOutOfBoundsThrows() {
        Table table = buildGridTable(Docx.create());
        assertThatThrownBy(() -> table.removeRow(5))
                .isInstanceOf(IndexOutOfBoundsException.class)
                .hasMessageContaining("row index 5");
        assertThatThrownBy(() -> table.removeRow(-1))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void addCellAndRemoveCellMutateLive() {
        Document doc = Docx.create();
        Row row = doc.addTable().addRow();
        row.addCell().text("x");
        row.addCell().text("y");

        assertThat(row.cells()).hasSize(2);
        row.removeCell(0);
        assertThat(row.cells()).hasSize(1);
        assertThat(row.cell(0).text()).isEqualTo("y");
    }

    @Test
    void removeCellOutOfBoundsThrows() {
        Row row = buildGridTable(Docx.create()).row(0);
        assertThatThrownBy(() -> row.removeCell(99))
                .isInstanceOf(IndexOutOfBoundsException.class)
                .hasMessageContaining("cell index 99");
        assertThatThrownBy(() -> row.removeCell(-1))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void textSetterOnEmptyCellCreatesSingleParagraph() {
        Cell cell = Docx.create().addTable().addRow().addCell();

        Cell returned = cell.text("solo");

        assertThat(returned).isSameAs(cell);
        assertThat(cell.paragraphs()).hasSize(1);
        assertThat(cell.text()).isEqualTo("solo");
    }

    @Test
    void textSetterWritesFirstParagraphAndLeavesOthers() {
        Cell cell = Docx.create().addTable().addRow().addCell();
        cell.addParagraph().addRun("first");
        cell.addParagraph().addRun("second");
        assertThat(cell.paragraphs()).hasSize(2);

        cell.text("replaced");

        // text(String) targets the first paragraph: clears its runs and writes the new text.
        // Paragraphs beyond the first are left in place (mirrors POI's setText).
        assertThat(cell.paragraphs()).hasSize(2);
        assertThat(cell.paragraph(0).text()).isEqualTo("replaced");
        assertThat(cell.paragraph(1).text()).isEqualTo("second");
    }

    @Test
    void tablesBuiltIdenticallyAreContentEqual() {
        Document a = Docx.create();
        Document b = Docx.create();
        Table t1 = buildGridTable(a);
        Table t2 = buildGridTable(b);

        // distinct delegate instances, but content-equal at every level
        assertThat(t1.raw()).isNotSameAs(t2.raw());
        assertThat(t1).isEqualTo(t2);
        assertThat(t1.hashCode()).isEqualTo(t2.hashCode());
        assertThat(t1.row(0)).isEqualTo(t2.row(0));
        assertThat(t1.row(0).cell(0)).isEqualTo(t2.row(0).cell(0));
    }

    @Test
    void tablesNotEqualWhenCellContentDiffers() {
        Document a = Docx.create();
        Document b = Docx.create();

        Table t1 = a.addTable();
        t1.addRow().addCell().text("A1");

        Table same = b.addTable();
        same.addRow().addCell().text("A1");

        Table diff = b.addTable();
        diff.addRow().addCell().text("DIFFERENT");

        assertThat(t1).isEqualTo(same);
        assertThat(t1).isNotEqualTo(diff);
    }

    @Test
    void cellParagraphsAreLiveParagraphWrappers() {
        Cell cell = Docx.create().addTable().addRow().addCell();
        cell.text("hello");

        Paragraph paragraph = cell.paragraph(0);
        assertThat(paragraph.text()).isEqualTo("hello");

        // live: mutating the paragraph wrapper is reflected when reading the cell back
        paragraph.addRun(" world");
        assertThat(cell.text()).isEqualTo("hello world");
    }
}
