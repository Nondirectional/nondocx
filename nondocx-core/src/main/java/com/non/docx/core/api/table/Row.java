package com.non.docx.core.api.table;

import com.non.docx.core.internal.util.Objects;
import java.util.AbstractList;
import java.util.List;
import java.util.function.Consumer;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

/**
 * {@link Table} 中的一行 — 从左到右的有序单元格序列。
 *
 * <p>持有 Apache POI {@code XWPFTableRow} 委托，并在其上暴露活跃视图。读取 直接穿透到委托；没有缓存快照。每次修改都是直接写入。
 *
 * <p>行内容的 <em>结构真实来源</em> 是 {@link #cells()}：从左到右的有序 单元格序列。内容相等性（{@code equals} / {@code hashCode}）
 * 比较该有序单元格序列，从不比较委托引用，因此两个基于不同 POI 实例但具有相同单元格的行是相等的 — 这就是往返断言能正常工作的原因。
 *
 * <p>这是一个 <em>可变的活动对象</em>。其 {@code equals} / {@code hashCode} 用于比较 和往返断言；它们不适合作为长期存在的 {@code
 * HashMap} 键，因为 底层内容随时可能改变。
 */
public final class Row {

  private final XWPFTableRow delegate;

  /**
   * 封装给定的 POI 行。
   *
   * <p>此构造函数是 {@link Table} 生成活跃行包装器的内部接缝， 因此它有意接受 POI 类型。用户通常通过 {@code Table.rows()} / {@code
   * Table.addRow()} 获取行，而不是直接构造它们。
   *
   * @param delegate 底层的 POI 行（不能为 {@code null}）
   * @throws IllegalArgumentException 如果 {@code delegate} 为 {@code null}
   */
  public Row(XWPFTableRow delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /**
   * 返回此行单元格的活跃视图，按从左到右的顺序排列。
   *
   * <p>每次访问时都会从委托重新读取视图，因此变更（添加或删除单元格） 会实时反映。
   *
   * @return 活跃、不可修改的单元格列表
   */
  public List<Cell> cells() {
    return new AbstractList<Cell>() {
      private final List<XWPFTableCell> backing = delegate.getTableCells();

      @Override
      public Cell get(int index) {
        return new Cell(backing.get(index));
      }

      @Override
      public int size() {
        return backing.size();
      }
    };
  }

  /**
   * 返回指定索引处的单元格。
   *
   * @param index 单元格索引（从 0 开始，指向 {@link #cells()}）
   * @return 该位置的单元格
   * @throws IndexOutOfBoundsException 如果 {@code index} 超出范围
   */
  public Cell cell(int index) {
    return cells().get(index);
  }

  /**
   * 向此行追加一个新的空单元格，并返回其活跃包装器。
   *
   * @return 新追加的单元格
   */
  public Cell addCell() {
    XWPFTableCell created = delegate.createCell();
    // POI 会用默认空段落预填充新单元格；清除它以使 addCell()
    // 产生一个空单元格 — 然后通过 text(String) 或 addParagraph() 添加内容。
    while (created.getParagraphs().size() > 0) {
      created.removeParagraph(0);
    }
    return new Cell(created);
  }

  /**
   * 移除指定索引处的单元格。
   *
   * @param index 单元格索引（从 0 开始，指向 {@link #cells()}）
   * @throws IndexOutOfBoundsException 如果 {@code index} 超出范围
   */
  public void removeCell(int index) {
    int size = delegate.getTableCells().size();
    if (index < 0 || index >= size) {
      throw new IndexOutOfBoundsException("单元格索引 " + index + " 超出范围（行有 " + size + " 个单元格）");
    }
    delegate.removeCell(index);
  }

  /**
   * 追加一个新单元格，将给定文本写入其中，并返回此行以支持链式调用。
   *
   * <p>这是 {@link #addCell()} 后跟 {@link Cell#text(String)} 的构造便捷方法：它追加一个单元格并设置其文本，因此单值 单元格的常见情况写作
   * {@code row.cell("A1")}。没有引入新的领域逻辑。
   *
   * @param text 要写入新单元格的文本（不能为 {@code null}）
   * @return 此行
   * @throws IllegalArgumentException 如果 {@code text} 为 {@code null}
   */
  public Row cell(String text) {
    Objects.requireNonNull(text, "text");
    addCell().text(text);
    return this;
  }

  /**
   * 追加一个新单元格，应用给定的配置器，并返回此行以支持链式调用。
   *
   * <p>这是 {@link #addCell()} 的构造便捷方法：它追加一个单元格并将 活跃的 {@link Cell} 交给配置器，因此调用者可以直接填充多段落或带样式
   * 的单元格。没有引入新的领域逻辑。
   *
   * @param config 单元格配置器，操作活跃的单元格（不能为 {@code null}）
   * @return 此行
   * @throws IllegalArgumentException 如果 {@code config} 为 {@code null}
   */
  public Row cell(Consumer<Cell> config) {
    Objects.requireNonNull(config, "config");
    Cell appended = addCell();
    config.accept(appended);
    return this;
  }

  /**
   * 返回底层的 POI 行。
   *
   * <p>对返回对象的修改会立即影响文档。请谨慎使用。
   *
   * @return 底层的 {@code XWPFTableRow} 实例（包装器生命周期内同一实例）
   */
  public XWPFTableRow raw() {
    return delegate;
  }

  // ---------- 内容相等 ----------

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Row)) {
      return false;
    }
    Row that = (Row) o;
    return java.util.Objects.equals(this.cells(), that.cells());
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(cells());
  }
}
