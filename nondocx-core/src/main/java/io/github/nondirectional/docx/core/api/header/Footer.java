package io.github.nondirectional.docx.core.api.header;

import io.github.nondirectional.docx.core.api.table.Table;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import io.github.nondirectional.docx.core.internal.util.Objects;
import java.util.AbstractList;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFFooter;

/**
 * 文档页脚 — 章节级别的段落容器，渲染在每页底部。
 *
 * <p>持有 Apache POI {@code XWPFFooter} 委托，并在其上暴露活跃视图。读取 直接穿透到委托；没有缓存快照。每次修改都是直接写入。
 *
 * <p>页脚 <em>不是</em> 正文元素 — 它位于文档正文之外，通过页脚引用附加到 {@link
 * io.github.nondirectional.docx.core.api.section.Section}。其内容是 由 {@link #paragraphs()}
 * 返回的有序段落序列。内容相等性（{@code equals} / {@code hashCode}）比较该有序段落序列，从不比较委托引用，因此两个 基于不同 POI
 * 实例但具有相同段落的页脚是相等的 — 这就是 往返断言能正常工作的原因。
 *
 * <p>{@link #text()} 返回页脚的拼接纯文本。{@link #addParagraph()} 追加 一个新的空段落并返回其活跃包装器。
 *
 * <p>这是一个 <em>可变的活动对象</em>。其 {@code equals} / {@code hashCode} 用于比较 和往返断言；它们不适合作为长期存在的 {@code
 * HashMap} 键，因为 底层内容随时可能改变。
 *
 * <p><b>范围。</b> MVP 只暴露默认（奇数页）页脚。首页和偶数页 页脚变体不在范围内，可通过 {@code raw()} 访问。
 */
public final class Footer {

  private final XWPFFooter delegate;

  /**
   * 封装给定的 POI 页脚。
   *
   * <p>此构造函数是 {@link io.github.nondirectional.docx.core.api.section.Section} 生成活跃页脚包装器的内部接缝，
   * 因此它有意接受 POI 类型。用户通过 {@code Section.footer()} / {@code Document.footer()} 获取页脚，而不是直接构造它们。
   *
   * @param delegate 底层的 POI 页脚（不能为 {@code null}）
   * @throws IllegalArgumentException 如果 {@code delegate} 为 {@code null}
   */
  public Footer(XWPFFooter delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /**
   * 返回此页脚的段落的活跃视图，按阅读顺序排列。
   *
   * <p>每次访问时都会从委托重新读取视图，因此变更会实时反映。
   *
   * @return 活跃、不可修改的段落列表
   */
  public List<Paragraph> paragraphs() {
    return new AbstractList<Paragraph>() {
      @Override
      public Paragraph get(int index) {
        return new Paragraph(delegate.getParagraphs().get(index));
      }

      @Override
      public int size() {
        return delegate.getParagraphs().size();
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
   * 向此页脚追加一个新的空段落，并返回其活跃包装器。
   *
   * @return 新追加的段落
   */
  public Paragraph addParagraph() {
    return new Paragraph(delegate.createParagraph());
  }

  /**
   * 返回此页脚的表格的活跃视图，按阅读顺序排列。
   *
   * <p>每次访问时都会从委托重新读取视图，因此变更会实时反映。语义同 {@code Document.tables()}。
   *
   * @return 活跃、不可修改的表格列表
   */
  public List<Table> tables() {
    return new AbstractList<Table>() {
      @Override
      public Table get(int index) {
        return new Table(delegate.getTables().get(index));
      }

      @Override
      public int size() {
        return delegate.getTables().size();
      }
    };
  }

  /**
   * 向此页脚追加一个新的空表格，并返回其活跃包装器。
   *
   * <p><b>OOXML。</b> 页脚与正文一样是块容器（{@code <w:ftr>} 内部结构同 {@code <w:body>}，可含段落与表格）。
   *
   * <p><b>POI。</b> {@code XWPFHeaderFooter.createTable(int rows, int cols)} 的签名与 {@code
   * XWPFDocument.createTable()}（无参）<b>不同</b> —— 必须传行列数，且会预填 {@code rows×cols} 个单元格。
   *
   * <p><b>nondocx。</b> 与 {@code Document.addTable} 的「剥掉 POI 预填」语义一致 —— 本方法用 {@code createTable(1,
   * 1)} 创建后剥掉那一行，得到真空表，符合 nondocx 的「addX = exactly one X」契约。
   *
   * @return 新追加的空表格
   */
  public Table addTable() {
    org.apache.poi.xwpf.usermodel.XWPFTable created = delegate.createTable(1, 1);
    while (created.getRows().size() > 0) {
      created.removeRow(0);
    }
    return new Table(created);
  }

  /**
   * 返回此页脚的拼接纯文本（所有段落按阅读顺序连接）。
   *
   * @return 页脚文本（可能为空，从不返回 {@code null}）
   */
  public String text() {
    return delegate.getText();
  }

  /**
   * 返回底层的 POI 页脚。
   *
   * <p>对返回对象的修改会立即影响文档。请谨慎使用。
   *
   * @return 底层的 {@code XWPFFooter} 实例（包装器生命周期内同一实例）
   */
  public XWPFFooter raw() {
    return delegate;
  }

  // ---------- 内容相等 ----------

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Footer)) {
      return false;
    }
    Footer that = (Footer) o;
    return java.util.Objects.equals(this.paragraphs(), that.paragraphs());
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(paragraphs());
  }
}
