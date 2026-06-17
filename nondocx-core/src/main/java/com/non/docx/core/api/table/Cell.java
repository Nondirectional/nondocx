package com.non.docx.core.api.table;

import com.non.docx.core.api.text.Paragraph;
import com.non.docx.core.internal.util.Objects;
import java.util.AbstractList;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;

/**
 * {@link Row} 中的单元格 — 段落的容器，表格的最小可寻址单元。
 *
 * <p>持有 Apache POI {@code XWPFTableCell} 委托，并在其上暴露活跃视图。读取 直接穿透到委托；没有缓存快照。每次修改都是直接写入。
 *
 * <p>单元格 <em>不是</em> 正文元素 — 它位于行内部、表格内部。其内容是 由 {@link #paragraphs()} 返回的有序段落序列。内容相等性（{@code equals}
 * / {@code hashCode}）比较该有序段落序列，从不比较委托 引用，因此两个基于不同 POI 实例但具有相同段落的单元格是相等的 — 这就是 往返断言能正常工作的原因。
 *
 * <p>{@link #text()} 返回单元格的拼接纯文本。{@link #text(String)} 将 给定文本写入单元格的第一个段落（如果单元格为空则创建段落，
 * 并清除该段落现有的运行）并返回 {@code this} 以支持链式调用；这镜像了 POI 的 {@code XWPFTableCell.setText}。第一个段落之后的段落保持不变。
 *
 * <p>这是一个 <em>可变的活动对象</em>。其 {@code equals} / {@code hashCode} 用于比较 和往返断言；它们不适合作为长期存在的 {@code
 * HashMap} 键，因为 底层内容随时可能改变。
 */
public final class Cell {

  private final XWPFTableCell delegate;

  /**
   * 封装给定的 POI 单元格。
   *
   * <p>此构造函数是 {@link Row} 生成活跃单元格包装器的内部接缝， 因此它有意接受 POI 类型。用户通常通过 {@code Row.cells()} / {@code
   * Row.addCell()} 获取单元格，而不是直接构造它们。
   *
   * @param delegate 底层的 POI 单元格（不能为 {@code null}）
   * @throws IllegalArgumentException 如果 {@code delegate} 为 {@code null}
   */
  public Cell(XWPFTableCell delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /**
   * 返回此单元格的段落的活跃视图，按阅读顺序排列。
   *
   * <p>每次访问时都会从委托重新读取视图，因此变更会实时反映。
   *
   * @return 活跃、不可修改的段落列表
   */
  public List<Paragraph> paragraphs() {
    return new AbstractList<Paragraph>() {
      private final List<XWPFParagraph> backing = delegate.getParagraphs();

      @Override
      public Paragraph get(int index) {
        return new Paragraph(backing.get(index));
      }

      @Override
      public int size() {
        return backing.size();
      }
    };
  }

  /**
   * 返回指定索引处的段落。
   *
   * @param index 段落索引（从 0 开始，指向 {@link #paragraphs()}）
   * @return 该位置的段落
   * @throws IndexOutOfBoundsException 如果 {@code index} 超出范围
   */
  public Paragraph paragraph(int index) {
    return paragraphs().get(index);
  }

  /**
   * 向此单元格追加一个新的空段落，并返回其活跃包装器。
   *
   * @return 新追加的段落
   */
  public Paragraph addParagraph() {
    return new Paragraph(delegate.addParagraph());
  }

  /**
   * 返回此单元格的拼接纯文本（所有段落按阅读顺序连接）。
   *
   * @return 单元格的文本（可能为空，从不返回 {@code null}）
   */
  public String text() {
    return delegate.getText();
  }

  /**
   * 将给定文本写入此单元格的第一个段落，并返回 {@code this} 以支持链式调用。
   *
   * <p>如果单元格为空，则创建一个段落；否则清除第一个段落现有的运行 并添加一个携带文本的单个运行。第一个段落之后的段落保持 不变（多段落内容请使用 {@link #paragraphs()}
   * / {@link #addParagraph()}）。 这镜像了 POI 的 {@code XWPFTableCell.setText}。
   *
   * @param text 要写入第一个段落的文本（不能为 {@code null}）
   * @return 此单元格
   * @throws IllegalArgumentException 如果 {@code text} 为 {@code null}
   */
  public Cell text(String text) {
    Objects.requireNonNull(text, "text");
    delegate.setText(text);
    return this;
  }

  /**
   * 返回底层的 POI 单元格。
   *
   * <p>对返回对象的修改会立即影响文档。请谨慎使用。
   *
   * @return 底层的 {@code XWPFTableCell} 实例（包装器生命周期内同一实例）
   */
  public XWPFTableCell raw() {
    return delegate;
  }

  // ---------- 内容相等 ----------

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Cell)) {
      return false;
    }
    Cell that = (Cell) o;
    return java.util.Objects.equals(this.paragraphs(), that.paragraphs());
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(paragraphs());
  }
}
