package io.github.nondirectional.docx.core.api.toc;

import io.github.nondirectional.docx.core.internal.poi.TocFields;
import io.github.nondirectional.docx.core.internal.util.Objects;
import java.util.Collections;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/**
 * 文档的目录(Table of Contents)—— 一个<b>只读</b>视图,解析 Word TOC 域后给出有序条目。
 *
 * <p><b>对 poi-bridge.md Rule 1 的明确偏差(已记录,见 N11)。</b> Rule 1 要求每个 {@code api/} 类型持有单个 {@code final
 * XWPF*} 委托、读写穿透、集合 wrap-on-get。本类持有单个 {@code final XWPFDocument} 委托且 {@link #entries()}
 * 每次当场重算(不在字段里缓存,守住「无字段快照」精神),这一点符合 Rule 1。<b>偏差在于</b>: TOC 没有专属 POI 委托类型——它是一个横跨多个段落、由 {@code
 * fldChar} 界定的<b>域</b>,POI 也没有 {@code XWPFToc}。因此本类的「委托」就是整份文档,且 {@link TocEntry} 是<b>不可变解析值</b>而非
 * holding-wrapper (POI 无 per-entry 句柄,条目本质是 Word 渲染分页后的缓存)。这是诚实建模:把没有干净 POI 句柄的东西
 * 硬包成活对象会得到一个会撒谎的抽象。
 *
 * <p><b>只读。</b> 创建或刷新目录(需 Word 的分页引擎计算页码)超出 nondocx 范围,属 {@code raw()} 范畴。 本类的所有方法都不修改文档。
 *
 * <p><b>OOXML / POI / nondocx 三层。</b>
 *
 * <ul>
 *   <li><b>OOXML</b>:TOC 是正文里的一个<b>域</b>,由 {@code <w:fldChar>} 的 begin/separate/end 与 一条以 {@code
 *       "TOC "} 开头的 {@code <w:instrText>} 指令文本(含大纲级别范围、超链接等开关)界定; begin 与 end 之间的段落是条目,用 {@code
 *       pStyle=TOC1..TOC9} 标层级,内容常包在 {@code <w:hyperlink w:anchor="_Toc...">} 里。
 *   <li><b>POI</b>:没有任何 {@code XWPFToc} 高级 API。域字符当普通 run 吐出;条目内容在 CTP 级 {@code <w:hyperlink>}
 *       内,POI 的 {@code getRuns()} 不暴露。
 *   <li><b>nondocx</b>:把「找域 → 切条目 → 取层级/标题/页码/锚点」的 XmlBeans 脏活收进 {@link
 *       TocFields}(internal/poi),对外只给本类 + {@link TocEntry} 两个干净类型。
 * </ul>
 *
 * <p><b>两种形态都已支持。</b> TOC 在 OOXML 里有两种形态,本类都解析:① <b>大域(field)</b>形态(较早 Word),begin/separate/end
 * 跨多段;② <b>SDT 内容控件</b>形态(较新 Word),整个 TOC 包进 {@code <w:sdt>/<w:sdtContent>},其内每个条目段落自带嵌套 {@code
 * PAGEREF} 子域。后者对 {@code XWPFDocument.getParagraphs()} 完全不可见(POI 不返回 SDT 内段落),故解析器会<b>穿透 SDT</b>
 * (下钻 {@code <w:sdtContent>} 取段落)——这是读 1072.docx 这类真实文档能成功的关键。详见 poi-bridge.md N11。
 *
 * <p><b>多 TOC 文档。</b> v1 只取首个 TOC 域。一份文档里有多个 TOC(罕见)时,后续的不可见,需走 {@code raw()}。
 *
 * <p>{@code equals}/{@code hashCode} 比较 {@link #entries()} 序列与 {@link #dirty()}(均来自委托派生的内容,
 * 从不比较委托引用),使 save→reopen 的往返相等性成立。
 */
public final class TableOfContents {

  private final XWPFDocument delegate;

  /**
   * 封装给定的 POI 文档以解析其 TOC。
   *
   * <p>此构造函数是 {@link io.github.nondirectional.docx.core.api.Document} 生成 TOC 视图的内部接缝,因此它有意接受 POI 类型
   * (与其他包装器接受其底层 {@code XWPF*} 的方式一致,见 poi-bridge.md N1)。用户通过 {@code Document.toc()} 获取
   * TOC,而不是直接构造。
   *
   * @param delegate 底层的 POI 文档(不能为 {@code null})
   * @throws IllegalArgumentException 如果 {@code delegate} 为 {@code null}
   */
  public TableOfContents(XWPFDocument delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /**
   * 返回 TOC 的有序条目(文档顺序),每次调用当场重算、不在字段缓存。
   *
   * <p>每次访问都重新走一遍 {@link TocFields} 解析,因此文档改动会实时反映(活对象语义)。文档无 TOC 域时返回 <b>空列表</b>(而非抛异常)——调用方通常先由
   * {@link io.github.nondirectional.docx.core.api.Document#toc()} 的 {@code null} 判定「根本没有
   * TOC」,本方法仅在确认存在 TOC 后被调用。
   *
   * @return 不可修改的条目列表(可能为空)
   */
  public List<TocEntry> entries() {
    return TocFields.findToc(delegate)
        .map(toc -> Collections.unmodifiableList(toc.entries()))
        .orElse(Collections.emptyList());
  }

  /**
   * 返回条目数,等价于 {@code entries().size()}。提供独立方法便于上层工具不构造列表就能问边界。
   *
   * @return 条目数
   */
  public int size() {
    return entries().size();
  }

  /**
   * 返回 TOC 域的 {@code w:dirty} 标志:为真表示源文档改动后目录<b>可能已过期</b>(Word 下次打开会提示刷新)。 对 Agent
   * 场景尤其有用——可据此提醒用户「页码可能不准」。
   *
   * @return dirty 标志;无 TOC 域时为 {@code false}
   */
  public boolean dirty() {
    return TocFields.findToc(delegate).map(TocFields.Toc::dirty).orElse(false);
  }

  /**
   * 返回底层的 POI 文档。
   *
   * <p>对返回对象的修改会立即影响文档。请谨慎使用。注意:TOC 没有专属 POI 委托类型,这里的「委托」就是整份文档; 想直接操作 TOC 域的 OOXML 结构时,从此处拿到
   * {@code XWPFDocument} 后走 {@code getCTP()} 等。
   *
   * @return 底层的 {@code XWPFDocument} 实例(包装器生命周期内同一实例)
   */
  public XWPFDocument raw() {
    return delegate;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TableOfContents)) {
      return false;
    }
    TableOfContents that = (TableOfContents) o;
    return java.util.Objects.equals(this.entries(), that.entries()) && this.dirty() == that.dirty();
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(entries(), dirty());
  }

  @Override
  public String toString() {
    return "TableOfContents{entries=" + entries().size() + ", dirty=" + dirty() + '}';
  }
}
