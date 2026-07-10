package com.non.docx.core.api.table;

import com.non.docx.core.api.BodyElement;
import com.non.docx.core.internal.poi.TableNodes;
import com.non.docx.core.internal.poi.TableWidthNodes;
import com.non.docx.core.internal.util.Objects;
import java.util.AbstractList;
import java.util.List;
import java.util.function.Consumer;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

/**
 * 表格 — 行和单元格的正文级别块。
 *
 * <p>持有 Apache POI {@code XWPFTable} 委托，并在其上暴露活跃视图。读取 直接穿透到委托；没有缓存快照。每次修改都是直接写入 — 底层 POI 表格会立即改变。
 *
 * <p>表格内容的 <em>结构真实来源</em> 是 {@link #rows()}：从顶部到底部的有序 行序列，每行都是其单元格的活跃视图。内容相等性（{@code equals} /
 * {@code hashCode}）比较该有序行序列，从不比较委托引用，因此 两个基于不同 POI 实例但具有相同行的表格是相等的 — 这就是 往返断言能正常工作的原因。
 *
 * <p>这是一个 <em>可变的活动对象</em>。其 {@code equals} / {@code hashCode} 用于比较 和往返断言；它们不适合作为长期存在的 {@code
 * HashMap} 键，因为 底层内容随时可能改变。
 */
public final class Table implements BodyElement {

  private final XWPFTable delegate;

  /**
   * 封装给定的 POI 表格。
   *
   * <p>此构造函数是 {@code Document} 生成活跃表格包装器的内部接缝， 因此它有意接受 POI 类型。用户通常通过 {@code Document.tables()} /
   * {@code Document.addTable()} 获取表格，而不是直接构造它们。
   *
   * @param delegate 底层的 POI 表格（不能为 {@code null}）
   * @throws IllegalArgumentException 如果 {@code delegate} 为 {@code null}
   */
  public Table(XWPFTable delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /**
   * 返回此表格的行在从上到下顺序中的活跃视图。
   *
   * <p>每次访问时都会从委托重新读取视图，因此变更（添加或删除行） 会实时反映。
   *
   * @return 活跃、不可修改的行列表
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
   * 返回指定索引处的行。
   *
   * @param index 行索引（从 0 开始，指向 {@link #rows()}）
   * @return 该位置的行
   * @throws IndexOutOfBoundsException 如果 {@code index} 超出范围
   */
  public Row row(int index) {
    return rows().get(index);
  }

  /** 显式设置表格为无边框，并返回 {@code this}。 */
  public Table noBorders() {
    TableNodes.applyNoBorders(delegate.getCTTbl());
    return this;
  }

  /**
   * 向此表格追加一个新的空行，并返回其活跃包装器。
   *
   * @return 新追加的行
   */
  public Row addRow() {
    XWPFTableRow created = delegate.createRow();
    // POI 会用一行或多个默认单元格预填充新行（一旦建立了表格网格，
    // 它就会镜像）；清除它们以使 addRow() 产生一个空行。
    while (created.getTableCells().size() > 0) {
      created.removeCell(0);
    }
    return new Row(created);
  }

  /**
   * 移除指定索引处的行。
   *
   * @param index 行索引（从 0 开始，指向 {@link #rows()}）
   * @throws IndexOutOfBoundsException 如果 {@code index} 超出范围
   */
  public void removeRow(int index) {
    int size = delegate.getRows().size();
    if (index < 0 || index >= size) {
      throw new IndexOutOfBoundsException("行索引 " + index + " 超出范围（表格有 " + size + " 行）");
    }
    delegate.removeRow(index);
  }

  /**
   * 追加一个新行，应用给定的配置器，并返回此表格以支持链式调用。
   *
   * <p>这是 {@link #addRow()} 的构造便捷方法：它追加一行并将 活跃的 {@link Row} 交给配置器，因此调用者可以直接填充其单元格（例如 {@code
   * table.row(r -> r.cell("A1").cell("B1"))}）。没有引入新的领域逻辑 — 此方法仅编排 {@code addRow()}。
   *
   * @param config 行配置器，操作活跃的行（不能为 {@code null}）
   * @return 此表格
   * @throws IllegalArgumentException 如果 {@code config} 为 {@code null}
   */
  public Table row(Consumer<Row> config) {
    Objects.requireNonNull(config, "config");
    Row appended = addRow();
    config.accept(appended);
    return this;
  }

  /**
   * 横向合并同一行内从 {@code fromCellIndex} 到 {@code toCellIndex} 的连续单元格。
   *
   * <p>OOXML 使用起始单元格的 {@code <w:gridSpan>} 表达横向跨度；被覆盖的右侧单元格会从行中移除。
   */
  public Table mergeCellsHorizontal(int rowIndex, int fromCellIndex, int toCellIndex) {
    Row row = row(rowIndex);
    int size = row.cells().size();
    if (fromCellIndex < 0 || toCellIndex >= size || fromCellIndex >= toCellIndex) {
      throw new IndexOutOfBoundsException(
          "横向合并范围无效：行 "
              + rowIndex
              + " 有 "
              + size
              + " 个单元格，范围 "
              + fromCellIndex
              + ".."
              + toCellIndex);
    }
    int span = toCellIndex - fromCellIndex + 1;
    TableNodes.applyGridSpan(row.cell(fromCellIndex).raw().getCTTc(), span);
    for (int c = toCellIndex; c > fromCellIndex; c--) {
      row.removeCell(c);
    }
    return this;
  }

  /**
   * 纵向合并同一列内从 {@code fromRowIndex} 到 {@code toRowIndex} 的连续单元格。
   *
   * <p>OOXML 使用每个参与单元格的 {@code <w:vMerge>} 表达：首格 restart，后续格 continue。
   */
  public Table mergeCellsVertical(int columnIndex, int fromRowIndex, int toRowIndex) {
    int rowCount = rows().size();
    if (fromRowIndex < 0 || toRowIndex >= rowCount || fromRowIndex >= toRowIndex) {
      throw new IndexOutOfBoundsException(
          "纵向合并范围无效：表格有 " + rowCount + " 行，范围 " + fromRowIndex + ".." + toRowIndex);
    }
    for (int r = fromRowIndex; r <= toRowIndex; r++) {
      Row row = row(r);
      if (columnIndex < 0 || columnIndex >= row.cells().size()) {
        throw new IndexOutOfBoundsException(
            "列索引 " + columnIndex + " 越界（第 " + r + " 行有 " + row.cells().size() + " 个单元格）");
      }
      TableNodes.applyVMerge(row.cell(columnIndex).raw().getCTTc(), r == fromRowIndex);
    }
    return this;
  }

  /**
   * 按<b>百分比</b>设置表格各列宽度,并返回 {@code this} 以支持链式调用。
   *
   * <p>这是 nondocx 的<b>主推列宽路径</b>——百分比(PCT)在 Microsoft Word 与 WPS 两个渲染引擎下行为一致。 同时写 {@code
   * <w:tblGrid>} 内每个 {@code <w:gridCol>} 的 {@code w:w}(PCT 单位,即五十分之一百分比) 与 {@code <w:tblW
   * w:type="pct">}(列百分比之和)。
   *
   * <p><b>OOXML</b>:PCT 的 {@code w:w} 以「五十分之一百分比」编码({@code w:w="5000"} = 100%)。 本方法把 {@code
   * percents[i]}(0-100 的整数百分比)内部乘以 50 转换。
   *
   * <p><b>WPS/Word 兼容性</b>:纯 DXA 在 WPS 触发 tblGrid 错位 bug (见 {@code
   * renderer-compatibility.md#table-width-dxa});百分比是跨引擎安全选择。若必须用绝对宽度,用 {@link
   * #columnWidths(int[])}。
   *
   * <p>覆盖此表格上已有的列宽设置(后调覆盖前调)。{@code tblGrid} 的 {@code gridCol} 数量会被调整为 {@code
   * percents.length}——不足则补、多余则删。
   *
   * @param percents 各列百分比(0-100 的整数;数组长度即列数;不能为 {@code null} 或空)
   * @return 此表格(链式)
   * @throws IllegalArgumentException 如果 {@code percents} 为 {@code null} 或空
   */
  public Table columnPercents(int[] percents) {
    TableWidthNodes.applyColumnPercents(delegate.getCTTbl(), percents);
    return this;
  }

  /**
   * 按<b>twips(绝对宽度)</b>设置表格各列宽度,并返回 {@code this} 以支持链式调用。
   *
   * <p>这是 nondocx 的<b>显式 DXA 覆盖路径</b>——当需要精确的绝对宽度时使用。同时写 {@code <w:tblGrid>} 内每个 {@code
   * <w:gridCol>} 的 {@code w:w}(twips)与 {@code <w:tblW w:type="dxa">}(列宽之和)。
   *
   * <p><b>WPS/Word 兼容性</b>:纯 DXA 在 WPS 的某些版本会触发 tblGrid 错位 bug (见 {@code
   * renderer-compatibility.md#table-width-dxa})。跨引擎场景请优先使用 {@link #columnPercents(int[])}。
   *
   * <p>覆盖此表格上已有的列宽设置(后调覆盖前调)。{@code tblGrid} 的 {@code gridCol} 数量会被调整为 {@code dxa.length}。
   *
   * @param dxa 各列宽度(twips,1 twip = 1/20 点;数组长度即列数;不能为 {@code null} 或空)
   * @return 此表格(链式)
   * @throws IllegalArgumentException 如果 {@code dxa} 为 {@code null} 或空
   */
  public Table columnWidths(int[] dxa) {
    TableWidthNodes.applyColumnWidths(delegate.getCTTbl(), dxa);
    return this;
  }

  /**
   * 返回各列宽度(twips)的列表。
   *
   * <p>每次访问都从委托重新读取。读取时 PCT 类型的列宽按 A4 可用宽度(9026 twips)近似换算回 twips; DXA 类型的列宽原样返回。若 {@code tblGrid}
   * 未设则返回空列表。
   *
   * @return 各列 twips 宽度列表(可能为空,从不为 {@code null})
   */
  public List<Integer> columnWidths() {
    return TableWidthNodes.readColumnWidths(delegate.getCTTbl());
  }

  /**
   * 返回底层的 POI 表格。
   *
   * <p>对返回对象的修改会立即影响文档。请谨慎使用。
   *
   * @return 底层的 {@code XWPFTable} 实例（包装器生命周期内同一实例）
   */
  public XWPFTable raw() {
    return delegate;
  }

  // ---------- 内容相等 ----------

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
