package com.non.docx.toolkit.orchestration.snapshot;

import java.util.List;
import java.util.Objects;

/**
 * 表格预览：表格索引 + body 索引 + 行列尺寸 + 少量关键单元格短文本预览。
 *
 * <p><b>两个索引的区别。</b>
 *
 * <ul>
 *   <li>{@code index}——<b>表格投影索引</b>：只数表格、跳过段落，即 {@code doc.tables()} 列表里的下标。 用于表格编辑操作（如
 *       replace_table_cell_run_text）的 {@code table_index}。
 *   <li>{@code bodyIndex}——<b>body 顺序索引</b>：段落和表格在 {@code <w:body>} 里交错排列，各占一个 slot； bodyIndex
 *       是该表格在 body 交错序列中的绝对位置。让 LLM 理解插入段落时应落在表格前还是后。
 * </ul>
 *
 * <p>无表格时表格不存在；有多个表格时 index 与 bodyIndex 可能不同。例如 body 顺序为 {@code [段落A, 表格, 段落B]} 时， 表格的 {@code
 * index=0}（第一个表格）、{@code bodyIndex=1}（body 里排在段落A之后）。
 *
 * <p><b>粒度。</b> 第一版给 table/cell 级摘要——尺寸 + 第一行/首列的短预览；cell 内段落/run 细节由 {@code ReadCoordinator} 按需补读。
 */
public final class TablePreview {

  private final int index;
  private final int bodyIndex;
  private final int rowCount;
  private final int columnCount;

  /** 关键单元格预览：外层=行，内层=列；仅放首行/首列等少量样本，非全量。 */
  private final List<List<String>> cellSamples;

  /**
   * @param index 表格投影索引（{@code doc.tables()} 列表下标，跳过段落）
   * @param bodyIndex body 顺序索引（含段落的交错序列位置）
   * @param rowCount 行数
   * @param columnCount 列数
   * @param cellSamples 关键单元格短文本预览样本
   */
  public TablePreview(
      int index, int bodyIndex, int rowCount, int columnCount, List<List<String>> cellSamples) {
    this.index = index;
    this.bodyIndex = bodyIndex;
    this.rowCount = rowCount;
    this.columnCount = columnCount;
    this.cellSamples = List.copyOf(cellSamples);
  }

  /** 表格投影索引（{@code doc.tables()} 列表下标，跳过段落）。 */
  public int index() {
    return index;
  }

  /** body 顺序索引（含段落的交错序列位置）。 */
  public int bodyIndex() {
    return bodyIndex;
  }

  public int rowCount() {
    return rowCount;
  }

  public int columnCount() {
    return columnCount;
  }

  public List<List<String>> cellSamples() {
    return cellSamples;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof TablePreview)) return false;
    TablePreview that = (TablePreview) o;
    return index == that.index
        && bodyIndex == that.bodyIndex
        && rowCount == that.rowCount
        && columnCount == that.columnCount
        && cellSamples.equals(that.cellSamples);
  }

  @Override
  public int hashCode() {
    return Objects.hash(index, bodyIndex, rowCount, columnCount, cellSamples);
  }
}
