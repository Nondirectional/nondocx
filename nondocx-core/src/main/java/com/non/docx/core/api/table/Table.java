package com.non.docx.core.api.table;

import com.non.docx.core.api.BodyElement;
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
