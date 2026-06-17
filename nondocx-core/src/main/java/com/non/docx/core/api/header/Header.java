package com.non.docx.core.api.header;

import com.non.docx.core.api.text.Paragraph;
import com.non.docx.core.internal.util.Objects;
import java.util.AbstractList;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFHeader;

/**
 * 文档页眉 — 章节级别的段落容器，渲染在每页顶部。
 *
 * <p>持有 Apache POI {@code XWPFHeader} 委托，并在其上暴露活跃视图。读取 直接穿透到委托；没有缓存快照。每次修改都是直接写入。
 *
 * <p>页眉 <em>不是</em> 正文元素 — 它位于文档正文之外，通过页眉引用附加到 {@link com.non.docx.core.api.section.Section}。其内容是 由
 * {@link #paragraphs()} 返回的有序段落序列。内容相等性（{@code equals} / {@code hashCode}）比较该有序段落序列，从不比较委托引用，因此两个
 * 基于不同 POI 实例但具有相同段落的页眉是相等的 — 这就是 往返断言能正常工作的原因。
 *
 * <p>{@link #text()} 返回页眉的拼接纯文本。{@link #addParagraph()} 追加 一个新的空段落并返回其活跃包装器。
 *
 * <p>这是一个 <em>可变的活动对象</em>。其 {@code equals} / {@code hashCode} 用于比较 和往返断言；它们不适合作为长期存在的 {@code
 * HashMap} 键，因为 底层内容随时可能改变。
 *
 * <p><b>范围。</b> MVP 只暴露默认（奇数页）页眉。首页和偶数页 页眉变体不在范围内，可通过 {@code raw()} 访问。
 */
public final class Header {

  private final XWPFHeader delegate;

  /**
   * 封装给定的 POI 页眉。
   *
   * <p>此构造函数是 {@link com.non.docx.core.api.section.Section} 生成活跃页眉包装器的内部接缝， 因此它有意接受 POI 类型。用户通过
   * {@code Section.header()} / {@code Document.header()} 获取页眉，而不是直接构造它们。
   *
   * @param delegate 底层的 POI 页眉（不能为 {@code null}）
   * @throws IllegalArgumentException 如果 {@code delegate} 为 {@code null}
   */
  public Header(XWPFHeader delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /**
   * 返回此页眉的段落的活跃视图，按阅读顺序排列。
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
   * 向此页眉追加一个新的空段落，并返回其活跃包装器。
   *
   * @return 新追加的段落
   */
  public Paragraph addParagraph() {
    return new Paragraph(delegate.createParagraph());
  }

  /**
   * 返回此页眉的拼接纯文本（所有段落按阅读顺序连接）。
   *
   * @return 页眉文本（可能为空，从不返回 {@code null}）
   */
  public String text() {
    return delegate.getText();
  }

  /**
   * 返回底层的 POI 页眉。
   *
   * <p>对返回对象的修改会立即影响文档。请谨慎使用。
   *
   * @return 底层的 {@code XWPFHeader} 实例（包装器生命周期内同一实例）
   */
  public XWPFHeader raw() {
    return delegate;
  }

  // ---------- 内容相等 ----------

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Header)) {
      return false;
    }
    Header that = (Header) o;
    return java.util.Objects.equals(this.paragraphs(), that.paragraphs());
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(paragraphs());
  }
}
