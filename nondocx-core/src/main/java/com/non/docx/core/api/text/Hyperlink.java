package com.non.docx.core.api.text;

import com.non.docx.core.api.InlineElement;
import com.non.docx.core.internal.util.Objects;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHyperlink;
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun;

/**
 * 超链接 — 其可见文本链接到目标（通常是外部 URL）的内联片段。
 *
 * <p>持有 Apache POI {@code XWPFHyperlinkRun} 委托（它本身扩展了 {@code XWPFRun}） 并暴露其显示文本和目标 URL。读取直接穿透到委托；
 * 没有缓存快照。
 *
 * <p><b>URL 解析。</b> OOXML 将超链接存储为携带关系 id（rId）的运行； 实际目标存在于文档的关系部分。{@link #url()} 通过所属文档跟踪该 rId 到
 * 外部目标。通过运行自己的 {@code getDocument()} 引用访问文档， 因此不需要额外参数；如果无法解析关系 （例如运行与其文档分离，或超链接指向内部锚点 而非
 * URL），{@code url()} 返回 {@code null}。
 *
 * <p>内容相等性比较可见文本和已解析的 URL，从不比较委托引用。
 */
public final class Hyperlink implements InlineElement {

  private final XWPFHyperlinkRun delegate;

  /**
   * 封装给定的 POI 超链接运行。
   *
   * <p>此构造函数是 {@link Paragraph} 生成活跃超链接包装器的内部接缝， 因此它有意接受 POI 类型。用户通常通过 {@code
   * Paragraph.addHyperlink(...)} 获取超链接，而不是直接构造它们。
   *
   * @param delegate 底层的 POI 超链接运行（不能为 {@code null}）
   * @throws IllegalArgumentException 如果 {@code delegate} 为 {@code null}
   */
  public Hyperlink(XWPFHyperlinkRun delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /**
   * 返回超链接的可见（显示）文本。
   *
   * @return 可见文本（可能为空，从不返回 {@code null}）
   */
  public String text() {
    return delegate.text();
  }

  /**
   * 解析并返回超链接的目标 URL，跟踪运行的关系 id 到 文档的关系部分。
   *
   * @return 目标 URL，如果此超链接指向内部锚点或无法解析关系则返回 {@code null}
   */
  public String url() {
    XWPFDocument document = delegate.getDocument();
    if (document == null) {
      return null;
    }
    XWPFHyperlink link = delegate.getHyperlink(document);
    return link == null ? null : link.getURL();
  }

  /**
   * 返回底层的 POI 超链接运行。
   *
   * <p>对返回对象的修改会立即影响文档。请谨慎使用。
   *
   * @return 底层的 {@code XWPFHyperlinkRun} 实例（包装器生命周期内同一实例）
   */
  public XWPFHyperlinkRun raw() {
    return delegate;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Hyperlink)) {
      return false;
    }
    Hyperlink that = (Hyperlink) o;
    return java.util.Objects.equals(this.text(), that.text())
        && java.util.Objects.equals(this.url(), that.url());
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(text(), url());
  }

  @Override
  public String toString() {
    return "Hyperlink{text=" + text() + ", url=" + url() + '}';
  }
}
