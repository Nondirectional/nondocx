package com.non.docx.core.api.table;

import static org.assertj.core.api.Assertions.assertThat;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTrPr;

/**
 * 验证 {@link Row} 的表格分页控制 API：{@code headerRow}（表头行跨页重复）与 {@code cantSplit} （禁止跨页拆分）。覆盖 XML
 * 级正确性、live 读写、往返保真、内容相等性扩展。
 */
class RowTablePaginationTest {

  /** 取第一个表格第一行的底层 {@code CTRow}（用于 XML 级交叉验证）。 */
  private static CTRow firstRow(Document doc) {
    return doc.raw().getDocument().getBody().getTblArray(0).getTrArray(0);
  }

  // ---------- headerRow ----------

  @Test
  void headerRowTrueWritesTblHeaderXml() {
    Document doc = Docx.create();
    Row row = doc.addTable().addRow();
    row.headerRow(true);

    CTRow tr = firstRow(doc);
    assertThat(tr.isSetTrPr()).isTrue();
    CTTrPr trPr = tr.getTrPr();
    assertThat(trPr.sizeOfTblHeaderArray()).isEqualTo(1);
    assertThat(trPr.getTblHeaderArray(0).getVal()).isEqualTo(Boolean.TRUE);
  }

  @Test
  void headerRowFalseRemovesExisting() {
    Document doc = Docx.create();
    Row row = doc.addTable().addRow();
    row.headerRow(true);
    assertThat(row.headerRow()).isTrue();

    row.headerRow(false);
    assertThat(row.headerRow()).isFalse();
    CTTrPr trPr = firstRow(doc).getTrPr();
    assertThat(trPr.sizeOfTblHeaderArray()).isEqualTo(0);
  }

  @Test
  void headerRowDefaultsFalseWhenUnset() {
    Document doc = Docx.create();
    Row row = doc.addTable().addRow();
    assertThat(row.headerRow()).isFalse();
  }

  @Test
  void headerRowTrueIsIdempotent() {
    // 多次设 true 不应重复添加 tblHeader
    Document doc = Docx.create();
    Row row = doc.addTable().addRow();
    row.headerRow(true);
    row.headerRow(true);
    row.headerRow(true);
    CTTrPr trPr = firstRow(doc).getTrPr();
    assertThat(trPr.sizeOfTblHeaderArray()).isEqualTo(1);
  }

  // ---------- cantSplit ----------

  @Test
  void cantSplitTrueWritesCantSplitXml() {
    Document doc = Docx.create();
    Row row = doc.addTable().addRow();
    row.cantSplit(true);

    CTTrPr trPr = firstRow(doc).getTrPr();
    assertThat(trPr.sizeOfCantSplitArray()).isEqualTo(1);
    assertThat(trPr.getCantSplitArray(0).getVal()).isEqualTo(Boolean.TRUE);
  }

  @Test
  void cantSplitFalseRemovesExisting() {
    Document doc = Docx.create();
    Row row = doc.addTable().addRow();
    row.cantSplit(true);
    assertThat(row.cantSplit()).isTrue();

    row.cantSplit(false);
    assertThat(row.cantSplit()).isFalse();
    assertThat(firstRow(doc).getTrPr().sizeOfCantSplitArray()).isEqualTo(0);
  }

  @Test
  void cantSplitDefaultsFalseWhenUnset() {
    Document doc = Docx.create();
    Row row = doc.addTable().addRow();
    assertThat(row.cantSplit()).isFalse();
  }

  // ---------- 往返保真 ----------

  @Test
  void headerRowAndCantSplitRoundTrip(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("pagination.docx");
    Document original = Docx.create();
    Table table = original.addTable();
    Row header = table.addRow();
    header.cell("列1").cell("列2");
    header.headerRow(true).cantSplit(true);
    Row data = table.addRow();
    data.cell("A1").cell("A2");
    data.cantSplit(true);

    original.save(file);
    try (Document opened = Docx.open(file)) {
      Table openedTable = opened.tables().get(0);
      assertThat(openedTable.row(0).headerRow()).isTrue();
      assertThat(openedTable.row(0).cantSplit()).isTrue();
      assertThat(openedTable.row(1).headerRow()).isFalse();
      assertThat(openedTable.row(1).cantSplit()).isTrue();
      // 内容相等性：含分页标记的行相等
      assertThat(openedTable.row(0)).isEqualTo(header);
      assertThat(openedTable.row(1)).isEqualTo(data);
    }
  }

  // ---------- 内容相等性扩展 ----------

  @Test
  void equalsIncludesHeaderRowAndCantSplit() {
    Document docA = Docx.create();
    Document docB = Docx.create();
    Row a = docA.addTable().addRow().cell("x");
    Row b = docB.addTable().addRow().cell("x");
    assertThat(a).isEqualTo(b); // 都无标记

    a.headerRow(true);
    assertThat(a).isNotEqualTo(b); // 加了 headerRow 后不等

    b.headerRow(true);
    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b); // 同 headerRow 再相等

    a.cantSplit(true);
    assertThat(a).isNotEqualTo(b); // 加了 cantSplit 后不等

    b.cantSplit(true);
    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b); // 全等
  }
}
