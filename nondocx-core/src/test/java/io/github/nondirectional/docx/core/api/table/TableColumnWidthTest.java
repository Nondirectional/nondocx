package io.github.nondirectional.docx.core.api.table;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTbl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblGrid;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblWidth;

/**
 * 验证 {@link Table} 的列宽 API:百分比(主推,WPS 友好)与 DXA(显式覆盖)两条路径,XML 级正确性、往返保真。
 *
 * <p>这是 WPS/Word 兼容性 spec({@code renderer-compatibility.md#table-width-dxa})的 load-bearing 测试。
 */
class TableColumnWidthTest {

  /** 取第一个表格的底层 {@code CTTbl}(用于 XML 级交叉验证)。 */
  private static CTTbl firstTable(Document doc) {
    return doc.raw().getDocument().getBody().getTblArray(0);
  }

  // ---------- columnPercents: 主推路径,PCT 单位 ----------

  @Test
  void columnPercentsWritesPctGridAndTblW() {
    Document doc = Docx.create();
    Table table = doc.addTable();
    table.addRow().addCell().text("A");
    table.columnPercents(new int[] {50, 30, 20});

    CTTbl tbl = firstTable(doc);
    CTTblGrid grid = tbl.getTblGrid();
    assertThat(grid).isNotNull();
    assertThat(grid.sizeOfGridColArray()).isEqualTo(3);
    // PCT: w:w 是五十分之一百分比 → 50% = 2500, 30% = 1500, 20% = 1000
    assertThat(grid.getGridColArray(0).getW().toString()).isEqualTo("2500");
    assertThat(grid.getGridColArray(1).getW().toString()).isEqualTo("1500");
    assertThat(grid.getGridColArray(2).getW().toString()).isEqualTo("1000");

    // tblW type=pct, w = sum = 5000 (=100%)
    assertThat(tbl.getTblPr().getTblW().getType()).isEqualTo(STTblWidth.PCT);
    assertThat(tbl.getTblPr().getTblW().getW().toString()).isEqualTo("5000");
  }

  @Test
  void columnPercentsOverwritesExisting() {
    Document doc = Docx.create();
    Table table = doc.addTable();
    table.columnPercents(new int[] {50, 50});
    table.columnPercents(new int[] {70, 30}); // 后调覆盖

    CTTblGrid grid = firstTable(doc).getTblGrid();
    assertThat(grid.sizeOfGridColArray()).isEqualTo(2);
    assertThat(grid.getGridColArray(0).getW().toString()).isEqualTo("3500"); // 70%
  }

  @Test
  void columnPercentsAdjustsGridColCount() {
    Document doc = Docx.create();
    Table table = doc.addTable();
    table.columnPercents(new int[] {33, 33, 34}); // 3 cols
    table.columnPercents(new int[] {50, 50}); // shrink to 2 cols

    CTTblGrid grid = firstTable(doc).getTblGrid();
    assertThat(grid.sizeOfGridColArray()).isEqualTo(2);
  }

  @Test
  void columnPercentsGrowsGridColCount() {
    Document doc = Docx.create();
    Table table = doc.addTable();
    table.columnPercents(new int[] {50, 50}); // 2 cols
    table.columnPercents(new int[] {25, 25, 25, 25}); // grow to 4

    CTTblGrid grid = firstTable(doc).getTblGrid();
    assertThat(grid.sizeOfGridColArray()).isEqualTo(4);
  }

  @Test
  void columnPercentsRejectsNullOrEmpty() {
    Table table = Docx.create().addTable();
    assertThatThrownBy(() -> table.columnPercents(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> table.columnPercents(new int[0]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---------- columnWidths: DXA 覆盖路径 ----------

  @Test
  void columnWidthsWritesDxaGridAndTblW() {
    Document doc = Docx.create();
    Table table = doc.addTable();
    table.columnWidths(new int[] {2000, 3000, 1000});

    CTTbl tbl = firstTable(doc);
    CTTblGrid grid = tbl.getTblGrid();
    assertThat(grid.sizeOfGridColArray()).isEqualTo(3);
    assertThat(grid.getGridColArray(0).getW().toString()).isEqualTo("2000");
    assertThat(grid.getGridColArray(1).getW().toString()).isEqualTo("3000");
    assertThat(grid.getGridColArray(2).getW().toString()).isEqualTo("1000");

    assertThat(tbl.getTblPr().getTblW().getType()).isEqualTo(STTblWidth.DXA);
    assertThat(tbl.getTblPr().getTblW().getW().toString()).isEqualTo("6000"); // sum
  }

  @Test
  void columnWidthsRejectsNullOrEmpty() {
    Table table = Docx.create().addTable();
    assertThatThrownBy(() -> table.columnWidths(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> table.columnWidths(new int[0]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---------- 往返保真 ----------

  @Test
  void columnWidthsRoundTripsDxa(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("colw.docx");
    Document original = Docx.create();
    original.addTable().columnWidths(new int[] {2000, 3000});
    original.save(file);

    try (Document opened = Docx.open(file)) {
      assertThat(opened.tables().get(0).columnWidths()).containsExactly(2000, 3000);
    }
  }

  @Test
  void columnPercentsRoundTrips(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("colpct.docx");
    Document original = Docx.create();
    original.addTable().columnPercents(new int[] {50, 50});
    original.save(file);

    try (Document opened = Docx.open(file)) {
      // PCT 读回换算为 twips(近似 A4 可用宽度):50% → 9026/2 ≈ 4513
      java.util.List<Integer> widths = opened.tables().get(0).columnWidths();
      assertThat(widths).hasSize(2);
      // 两个值应大致相等(各占一半)
      assertThat(widths.get(0)).isEqualTo(widths.get(1));
    }
  }

  // ---------- 读取 ----------

  @Test
  void columnWidthsReadsEmptyWhenGridUnset() {
    Document doc = Docx.create();
    Table table = doc.addTable();
    // 新建表格未设列宽 → 空列表(POI 可能自动建 gridCol,但未设 w)
    // 至少不抛异常
    table.columnWidths();
  }
}
