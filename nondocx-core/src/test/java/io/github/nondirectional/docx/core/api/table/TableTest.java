package io.github.nondirectional.docx.core.api.table;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;

/** 验证 {@link Table}、{@link Row} 和 {@link Cell} 的表格往返、 活动行/单元格变更、越界处理和内容相等性。 */
class TableTest {

  private static final String[][] GRID = {
    {"A1", "B1", "C1"},
    {"A2", "B2", "C2"}
  };

  /** 构建一个填充了 {@link #GRID} 值的新 2×3 表格。 */
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
          assertThat(row.cell(c).text()).as("cell [%d][%d] text", r, c).isEqualTo(GRID[r][c]);
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
    table.addRow(); // 实时追加
    assertThat(table.rows()).hasSize(3);
    table.removeRow(0); // 实时移除第一行
    assertThat(table.rows()).hasSize(2);
    // 原本是第二行（现在是第一行）的行携带 A2
    assertThat(table.row(0).cell(0).text()).isEqualTo("A2");
  }

  @Test
  void removeRowOutOfBoundsThrows() {
    Table table = buildGridTable(Docx.create());
    assertThatThrownBy(() -> table.removeRow(5))
        .isInstanceOf(IndexOutOfBoundsException.class)
        .hasMessageContaining("行索引 5");
    assertThatThrownBy(() -> table.removeRow(-1)).isInstanceOf(IndexOutOfBoundsException.class);
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
        .hasMessageContaining("单元格索引 99");
    assertThatThrownBy(() -> row.removeCell(-1)).isInstanceOf(IndexOutOfBoundsException.class);
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

    // text(String) 针对第一个段落：清除其 run 并写入新文本。
    // 第一个之后的段落保持不变（镜像了 POI 的 setText）。
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

    // 不同的委托实例，但在每个级别上内容相等
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

    // 实时：修改段落包装器会在重新读取单元格时反映出来
    paragraph.addRun(" world");
    assertThat(cell.text()).isEqualTo("hello world");
  }

  @Test
  void noBordersWritesNilTableBorders() {
    Table table = buildGridTable(Docx.create());

    table.noBorders();

    var borders = table.raw().getCTTbl().getTblPr().getTblBorders();
    assertThat(borders.getTop().getVal()).isEqualTo(STBorder.NIL);
    assertThat(borders.getLeft().getVal()).isEqualTo(STBorder.NIL);
    assertThat(borders.getBottom().getVal()).isEqualTo(STBorder.NIL);
    assertThat(borders.getRight().getVal()).isEqualTo(STBorder.NIL);
    assertThat(borders.getInsideH().getVal()).isEqualTo(STBorder.NIL);
    assertThat(borders.getInsideV().getVal()).isEqualTo(STBorder.NIL);
  }

  @Test
  void mergeCellsHorizontalWritesGridSpanAndRemovesCoveredCells() {
    Table table = buildGridTable(Docx.create());

    table.mergeCellsHorizontal(0, 0, 2);

    assertThat(table.row(0).cells()).hasSize(1);
    assertThat(table.row(0).cell(0).text()).isEqualTo("A1");
    assertThat(table.row(0).cell(0).raw().getCTTc().getTcPr().getGridSpan().getVal())
        .isEqualTo(java.math.BigInteger.valueOf(3));
  }

  @Test
  void mergeCellsVerticalWritesRestartAndContinue() {
    Table table = buildGridTable(Docx.create());

    table.mergeCellsVertical(1, 0, 1);

    assertThat(table.row(0).cell(1).raw().getCTTc().getTcPr().getVMerge().getVal())
        .isEqualTo(STMerge.RESTART);
    assertThat(table.row(1).cell(1).raw().getCTTc().getTcPr().getVMerge().getVal())
        .isEqualTo(STMerge.CONTINUE);
  }

  @Test
  void mergeCellsRejectsInvalidRanges() {
    Table table = buildGridTable(Docx.create());

    assertThatThrownBy(() -> table.mergeCellsHorizontal(0, 1, 1))
        .isInstanceOf(IndexOutOfBoundsException.class)
        .hasMessageContaining("横向合并范围无效");
    assertThatThrownBy(() -> table.mergeCellsVertical(9, 0, 1))
        .isInstanceOf(IndexOutOfBoundsException.class)
        .hasMessageContaining("列索引 9");
  }

  // ---------- copyRowTo：定位深拷贝（fill_template 的重复行基建） ----------

  @Test
  void copyRowToInsertsDeepCopyAtPositionAndPreservesSource() {
    Document doc = Docx.create();
    try {
      Table table = buildGridTable(doc);
      // 拷贝第 0 行（A1/B1/C1）插到位置 1：新行在源行之后、原第 1 行之前
      Row copied = table.copyRowTo(0, 1);
      assertThat(table.rows()).hasSize(3);
      assertThat(copied.cell(0).text()).isEqualTo("A1");
      assertThat(table.row(0).cell(0).text()).isEqualTo("A1");
      assertThat(table.row(1).cell(0).text()).isEqualTo("A1");
      assertThat(table.row(2).cell(0).text()).isEqualTo("A2");
      // 改拷贝行不影响源行（深拷贝）
      copied.cell(0).text("改");
      assertThat(table.row(0).cell(0).text()).isEqualTo("A1");
    } finally {
      doc.close();
    }
  }

  @Test
  void copyRowToSupportsAppendPositionAndKeepsCantSplit(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("copy.docx");
    Document doc = Docx.create();
    try {
      Table table = buildGridTable(doc);
      table.row(1).cantSplit(true);
      // targetIndex == size：末尾追加
      Row appended = table.copyRowTo(1, table.rows().size());
      assertThat(table.rows()).hasSize(3);
      // 行属性随 XML 深拷贝保留
      assertThat(appended.cantSplit()).isTrue();
      doc.save(file);
    } finally {
      doc.close();
    }
    try (Document reopened = Docx.open(file)) {
      // POI 行缓存同步正确：reopen 后行数与文本稳定
      Table table = reopened.tables().get(0);
      assertThat(table.rows()).hasSize(3);
      assertThat(table.row(2).cell(0).text()).isEqualTo("A2");
      assertThat(table.row(2).cantSplit()).isTrue();
    }
  }

  @Test
  void copyRowToRejectsOutOfBounds() {
    Table table = buildGridTable(Docx.create());
    assertThatThrownBy(() -> table.copyRowTo(9, 0)).isInstanceOf(IndexOutOfBoundsException.class);
    assertThatThrownBy(() -> table.copyRowTo(0, 9)).isInstanceOf(IndexOutOfBoundsException.class);
    assertThatThrownBy(() -> table.copyRowTo(-1, 0)).isInstanceOf(IndexOutOfBoundsException.class);
  }

  /**
   * 经返回值写入必须真正落进文档树：POI 的 addRow(row,pos) 把内容拷进自建 CTRow、不复用传入 wrapper——若 copyRowTo 返回传入
   * wrapper，写入会落在脱链对象上（内存读正确、落盘原样）。 fill_template 首版 eval 踩中，本用例锁死修复。
   */
  @Test
  void copyRowToReturnedRowWritesIntoDocumentTree(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("persist.docx");
    Document doc = Docx.create();
    try {
      Table table = buildGridTable(doc);
      Row copied = table.copyRowTo(0, 1);
      copied.cell(0).text("经返回值改写");
      doc.save(file);
    } finally {
      doc.close();
    }
    try (Document reopened = Docx.open(file)) {
      assertThat(reopened.tables().get(0).row(1).cell(0).text()).isEqualTo("经返回值改写");
    }
  }
}
