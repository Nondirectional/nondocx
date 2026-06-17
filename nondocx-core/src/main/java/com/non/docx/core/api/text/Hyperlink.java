package com.non.docx.core.api.text;

import com.non.docx.core.api.InlineElement;
import com.non.docx.core.api.exception.DocxIOException;
import com.non.docx.core.api.exception.DocxOperationException;
import com.non.docx.core.internal.util.Objects;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHyperlink;
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun;
import org.apache.poi.xwpf.usermodel.XWPFRelation;

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
 * <p><b>可写性。</b> {@link #text(String)} 修改显示文本；{@link #url(String)} 修改目标 URL。 改文本直接委托 {@code
 * setText}（{@code XWPFHyperlinkRun} 继承自 {@code XWPFRun}）；改 URL 需重建文档关系（删除旧 rId 对应的外部关系、新建一条指向新 URL
 * 的外部关系、 把运行上的 rId 更新为新建关系分配的 id）。
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
   * 设置超链接的可见（显示）文本，返回 {@code this} 以支持链式调用。
   *
   * <p><b>POI 行为注意：</b> {@code XWPFHyperlinkRun.setText(String)}（继承自 {@code XWPFRun}）在底层调用 {@code
   * setText(text, sizeOfTArray())}—— 当位置等于现有 {@code <w:t>} 数量时会 <em>追加</em> 一个新的 {@code
   * <w:t>}，而非替换。因此对一个已有文本的超链接 调用 {@code setText} 会把新文本拼到旧文本后面。 本方法先清空运行的底层 {@code CTR} 上所有 {@code
   * <w:t>}，再调用 {@code setText}（此时 sizeOfTArray()==0，会新建一个携带新文本的 {@code <w:t>}）， 确保是“替换”语义，与用户对
   * setter 的直觉一致。详见 poi-bridge.md N9。
   *
   * @param text 新的可见文本（不能为 {@code null}；使用 {@code ""} 清空）
   * @return 此超链接
   * @throws IllegalArgumentException 如果 {@code text} 为 {@code null}
   */
  public Hyperlink text(String text) {
    Objects.requireNonNull(text, "text");
    // XWPFRun 的 run 字段是 protected CTR；通过 getCTR()（POI 公开方法）拿到底层 run
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR ctr = delegate.getCTR();
    int tCount = ctr.sizeOfTArray();
    for (int i = tCount - 1; i >= 0; i--) {
      ctr.removeT(i);
    }
    delegate.setText(text);
    return this;
  }

  /**
   * 设置超链接的目标 URL，返回 {@code this} 以支持链式调用。
   *
   * <p><b>OOXML：</b> 目标 URL 不在 {@code <w:hyperlink>} 元素上，而在文档关系部分 {@code
   * word/_rels/document.xml.rels} 中，通过运行上的 {@code r:id} 引用。
   *
   * <p><b>POI：</b> 没有直接改超链接 target 的 API。本方法按 POI 创建超链接的 同一路径重建关系：取旧 rId → 从所属 {@code PackagePart}
   * 删除旧外部关系 → 新建一条指向 {@code url} 的外部关系（{@link XWPFRelation#HYPERLINK}）→ 把运行上的 rId 更新为 新分配的
   * id。POI/OpenXML4J/XmlBeans 异常被封装为 {@link DocxIOException}（保留 cause），不泄露 {@code
   * org.apache.poi.*}。
   *
   * <p><b>缓存注意：</b> {@code XWPFDocument} 在 open 时会缓存已解析的超链接，本方法重建底层关系后该缓存不刷新； 因此对 <em>重新打开</em>
   * 的文档在同一实例内调用 {@code url()} 可能读回旧值。核心契约是 {@code save → reopen} 读回新值（落盘正确）。 对 {@code
   * Docx.create()} 新建的文档，缓存未预填充，内存读回不受影响。详见 poi-bridge.md N10。
   *
   * @param url 新的目标 URL（不能为 {@code null}）
   * @return 此超链接
   * @throws IllegalArgumentException 如果 {@code url} 为 {@code null}
   * @throws DocxOperationException 如果超链接未附加到任何文档
   * @throws DocxIOException 如果重建关系时发生 OpenXML4J/关系部分错误
   * @see poi-bridge.md N9（XWPFHyperlinkRun.setText 的追加行为）
   * @see poi-bridge.md N10（XWPFDocument 超链接缓存与 url 重建）
   */
  public Hyperlink url(String url) {
    Objects.requireNonNull(url, "url");
    XWPFDocument document = delegate.getDocument();
    if (document == null) {
      throw new DocxOperationException("超链接未附加到任何文档，无法修改其目标 URL");
    }
    try {
      PackagePart part = document.getPackagePart();
      String oldRid = delegate.getHyperlinkId();
      if (oldRid != null) {
        part.removeRelationship(oldRid);
      }
      // 与 POI 自身 createHyperlinkRun 同一路径：用 XWPFRelation.HYPERLINK.getRelation()
      // 拿到正确的关系 type URI（注意不是 toString()，那只是调试字符串）；
      // 让 OpenXML4J 自动分配 rId（不要假设能复用旧 rId），再写回运行。
      PackageRelationship newRel =
          part.addExternalRelationship(url, XWPFRelation.HYPERLINK.getRelation());
      delegate.setHyperlinkId(newRel.getId());
      return this;
    } catch (RuntimeException e) {
      // 涵盖 OpenXML4J 的 InvalidOperationException 以及 XmlBeans 异常。
      // 按 error-handling.md，OpenXML4J/关系部分失败归 DocxIOException（包装 POI 异常、保留 cause）。
      throw new DocxIOException("无法修改超链接目标 URL 为 " + url, e);
    }
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
