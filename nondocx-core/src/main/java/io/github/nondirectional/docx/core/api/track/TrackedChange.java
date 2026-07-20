package io.github.nondirectional.docx.core.api.track;

import io.github.nondirectional.docx.core.internal.util.Objects;
import java.util.Calendar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRunTrackChange;

/**
 * 文档里的一条修订(tracked change)——一个<b>holding-wrapper</b>,持有底层 OOXML 修订标记节点。
 *
 * <p>每条修订是对文档某处内容的一次被记录的变更:有人插入了文本、删除了文本、改了属性等。nondocx 把这些散落在 OOXML 各处的修订标记节点({@code <w:ins>} /
 * {@code <w:del>} 等)统一包装成本类型,对外暴露稳定的领域视图。
 *
 * <p><b>OOXML / POI / nondocx 三层。</b>
 *
 * <ul>
 *   <li><b>OOXML</b>:修订标记是带属性的容器元素,如 {@code <w:ins w:id="1" w:author="non"
 *       w:date="...">...</w:ins>}, 内部包裹被修订的内容(插入的是 {@code <w:r>/<w:t>},删除的是 {@code
 *       <w:r>/<w:delText>})。文档开关在 {@code settings.xml} 的 {@code <w:trackChanges/>}。
 *   <li><b>POI</b>:没有 {@code XWPFIns} / {@code XWPFDel} 这类高级类型。修订标记由 XmlBeans 的 CT 类型承载——
 *       文本与移动类四种元素({@code ins}/{@code del}/{@code moveFrom}/{@code moveTo})在精简 schema 下统一由 {@link
 *       CTRunTrackChange} 承载(它继承 {@code CTTrackChange},后者提供 {@code author}/{@code date}/{@code
 *       id})。
 *   <li><b>nondocx</b>:把「按文档顺序找修订节点 → 解析为领域视图」的脏活收进 {@code internal/poi/},对外只暴露 本类与 {@link
 *       TrackedChangeLocation} / {@link ChangeDetails} 等 POI-free 类型。
 * </ul>
 *
 * <p><b>包装形态 —— holding-wrapper 持 CT 节点(design §4.2)。</b> 本类持有单个 {@code final CTRunTrackChange}
 * 委托,读写穿透。这与 {@code Section}({@code Section(XWPFDocument, CTSectPr)},{@code raw()} 返回 CT)是同构的先例,
 * 而<b>不是</b>像 {@code TableOfContents}/{@code TocEntry} 那样对 poi-bridge.md Rule 1 的偏差——因为 tracked
 * changes <b>有</b>干净的 per-revision CT 节点句柄,TOC 没有。
 *
 * <p><b>顶层字段职责。</b> 顶层只放各 family 共有的稳定字段;family / type 之间的差异交给 {@link #details()}:
 *
 * <ul>
 *   <li>{@link #id()} —— nondocx 对外稳定 id(<b>进程内稳定</b>,见 design §4.5;不承诺跨 save/reopen 稳定)。
 *   <li>{@link #author()} —— 修订作者(派生自 {@code CTTrackChange.getAuthor()})。
 *   <li>{@link #date()} —— 修订时间(派生自 {@code CTTrackChange.getDate()})。
 *   <li>{@link #type()} —— 具体修订 kind(由 OOXML 元素本地名决定,如 {@link TrackedChangeType#INS INS})。
 *   <li>{@link #family()} —— 粗粒度分组(派生自 {@code type})。
 *   <li>{@link #location()} —— 结构化位置值对象。
 *   <li>{@link #details()} —— 具体 payload(如 {@link TextChangeDetails})。
 * </ul>
 *
 * <p><b>内容相等。</b> {@code equals} / {@code hashCode} 比较上述顶层字段(均派生自委托的内容,从不比较 CT 节点引用), 与 nondocx 的
 * round-trip 相等性约定一致(quality-guidelines.md Rule 2 + poi-bridge.md N7)。
 */
public final class TrackedChange {

  private final String id;
  private final TrackedChangeType type;
  private final TrackedChangeLocation location;
  private final ChangeDetails details;
  private final String author;
  private final Calendar date;
  private final CTRunTrackChange runDelegate;
  private final org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTrackChange
      propertyDelegate;

  /**
   * 包装一个 OOXML 修订标记节点。
   *
   * <p>此构造函数是 {@code internal/poi/} 生成领域视图的<b>内部接缝</b>,因此它有意接受 XmlBeans CT 类型(与 {@code
   * Section(XWPFDocument, CTSectPr)} 接受 {@code CTSectPr} 的方式一致,见 poi-bridge.md N1)。用户通过 {@link
   * TrackedChanges#list()} / {@link TrackedChanges#get(String)} 获取修订,而不是直接构造。
   *
   * <p>本构造函数<b>不</b>从委托解析 author/date——那些直接穿透到委托读取(活对象语义);只接收已经解析好的稳定字段 (id / type / location /
   * details)。id 由门面集中生成并传入(见 design §4.5)。
   *
   * @param id nondocx 稳定 id(不能为 {@code null})
   * @param type 具体修订 kind(不能为 {@code null})
   * @param location 结构化位置(不能为 {@code null})
   * @param details 具体 payload(不能为 {@code null};高级类型若无稳定 details,实现尚未交付)
   * @param delegate 底层的 {@code CTRunTrackChange}(不能为 {@code null})
   * @throws IllegalArgumentException 如果任一参数为 {@code null}
   */
  public TrackedChange(
      String id,
      TrackedChangeType type,
      TrackedChangeLocation location,
      ChangeDetails details,
      CTRunTrackChange delegate) {
    this.id = Objects.requireNonNull(id, "id");
    this.type = Objects.requireNonNull(type, "type");
    this.location = Objects.requireNonNull(location, "location");
    this.details = Objects.requireNonNull(details, "details");
    this.runDelegate = Objects.requireNonNull(delegate, "delegate");
    this.propertyDelegate = null;
    this.author = delegate.getAuthor() == null ? "" : delegate.getAuthor();
    this.date = delegate.getDate();
  }

  /**
   * 包装一个<b>属性类</b>修订节点(委托是 {@code CTTrackChange};属性类的具体子类型 {@code CTRPrChange} / {@code
   * CTPPrChange} 都继承自它)。
   *
   * <p>包内构造,由 {@code internal/poi/} 在枚举属性类修订时使用。对属性类, {@link #raw()} 抛 {@link
   * io.github.nondirectional.docx.core.api.exception.UnsupportedFeatureException}——属性类的
   * accept/reject 走门面专用方法,不通用 {@code raw()}。
   */
  public TrackedChange(
      String id,
      TrackedChangeType type,
      TrackedChangeLocation location,
      ChangeDetails details,
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTrackChange delegate) {
    this.id = Objects.requireNonNull(id, "id");
    this.type = Objects.requireNonNull(type, "type");
    this.location = Objects.requireNonNull(location, "location");
    this.details = Objects.requireNonNull(details, "details");
    this.propertyDelegate = Objects.requireNonNull(delegate, "delegate");
    this.runDelegate = null;
    this.author = delegate.getAuthor() == null ? "" : delegate.getAuthor();
    this.date = delegate.getDate();
  }

  /**
   * 包装一个<b>无底层委托</b>的修订(只读值对象)。
   *
   * <p>用于 {@code cellMerge}:其 CT 类型 {@code CTCellMergeTrackChange} 在 POI 精简 schema 下连 XmlBeans 的
   * {@code .xsb} schema 资源都缺失(research/cell-forms.md §4),既不能经类型化对象读 author/date,也不能持可写委托。 因此
   * cellMerge 的 author/date 在 read 期用 XmlCursor 直接读属性文本传入,产出一条纯值 {@code TrackedChange}。
   *
   * <p>对这类修订,{@link #raw()}、{@code acceptCell}/{@code rejectCell} 都会抛 {@code
   * UnsupportedFeatureException}(无委托可写)。{@link #propertyNode()} 返回 {@code null}。
   *
   * @param id nondocx 稳定 id(不能为 {@code null})
   * @param type 具体修订 kind(不能为 {@code null})
   * @param location 结构化位置(不能为 {@code null})
   * @param details 具体 payload(不能为 {@code null})
   * @param author 修订作者(已解析;不能为 {@code null},无作者时传 {@code ""})
   * @param date 修订时间(已解析;可为 {@code null})
   */
  public TrackedChange(
      String id,
      TrackedChangeType type,
      TrackedChangeLocation location,
      ChangeDetails details,
      String author,
      Calendar date) {
    this.id = Objects.requireNonNull(id, "id");
    this.type = Objects.requireNonNull(type, "type");
    this.location = Objects.requireNonNull(location, "location");
    this.details = Objects.requireNonNull(details, "details");
    this.author = Objects.requireNonNull(author, "author");
    this.date = date;
    this.runDelegate = null;
    this.propertyDelegate = null;
  }

  /**
   * 返回 nondocx 对外稳定 id。
   *
   * <p><b>进程内稳定</b>(design §4.5):对同一 {@link TrackedChanges} 门面对象,同一修订的 id 在多次 {@code list()}/
   * {@code get(id)} 间相等。但<b>不承诺</b>文档 {@code save()} 后重新 {@code Docx.open()} 仍相等。后续 accept/reject
   * 子任务默认在同会话内操作。
   *
   * <p><b>字符串格式不作为公共契约。</b> id 对外是不透明引用标识,调用者不应依赖其可读结构、长度或前缀;理解与调试 主要信息应来自 {@link #type()} / {@link
   * #family()} / {@link #location()} / {@link #details()}。
   *
   * @return 稳定 id(从不为 {@code null})
   */
  public String id() {
    return id;
  }

  /**
   * 返回修订作者(派生自 OOXML {@code w:author} 属性)。文档未记录作者时为空串。
   *
   * @return 作者(从不为 {@code null},可能为空)
   */
  public String author() {
    return author;
  }

  /**
   * 返回修订时间(派生自 OOXML {@code w:date} 属性)。文档未记录时间时为 {@code null}。
   *
   * @return 修订时间,或 {@code null}
   */
  public Calendar date() {
    return date;
  }

  /**
   * 返回具体修订 kind(由 OOXML 元素本地名决定,如 {@link TrackedChangeType#INS INS})。
   *
   * @return 修订 kind(从不为 {@code null})
   */
  public TrackedChangeType type() {
    return type;
  }

  /**
   * 返回粗粒度分组(派生自 {@link #type()})。
   *
   * @return family(从不为 {@code null})
   */
  public TrackedChangeFamily family() {
    return type.family();
  }

  /**
   * 返回结构化位置值对象。
   *
   * @return 位置(从不为 {@code null})
   */
  public TrackedChangeLocation location() {
    return location;
  }

  /**
   * 返回具体 payload。调用方通常用 {@code instanceof} 判断子类型(如 {@link TextChangeDetails})再读取其字段。
   *
   * @return details(从不为 {@code null})
   */
  public ChangeDetails details() {
    return details;
  }

  /**
   * 返回底层的 OOXML 修订标记节点(文本类 / 移动类)。
   *
   * <p>对返回对象的修改会立即影响文档。请谨慎使用。文本类与移动类修订标记的底层类型是 XmlBeans 的 {@code CTRunTrackChange};想直接操作修订的 OOXML
   * 结构时(例如手工 accept/reject),从此处拿到节点后走其 {@code getRList()} 等。
   *
   * <p><b>属性类与单元格类修订不支持</b>:这两类的底层节点是 {@code CTTrackChange}(属性类是其子类型 {@code CTRPrChange}; 单元格类
   * {@code cellIns}/{@code cellDel} 直接是 {@code CTTrackChange}),都不走本方法;对它们调用本方法抛 {@link
   * io.github.nondirectional.docx.core.api.exception.UnsupportedFeatureException}。属性类的
   * accept/reject 用门面专用方法 {@code acceptProperty}/{@code rejectProperty},单元格类用 {@code
   * acceptCell}/{@code rejectCell},二者内部经包内接缝 {@link #propertyNode()} 取节点。
   *
   * @return 底层的 {@code CTRunTrackChange} 实例(包装器生命周期内同一实例)
   * @throws io.github.nondirectional.docx.core.api.exception.UnsupportedFeatureException
   *     若本修订是属性类({@link #family()} == {@link TrackedChangeFamily#PROPERTY PROPERTY})或单元格类({@link
   *     #family()} == {@link TrackedChangeFamily#CELL CELL})
   */
  public CTRunTrackChange raw() {
    if (runDelegate == null) {
      throw new io.github.nondirectional.docx.core.api.exception.UnsupportedFeatureException(
          "本修订没有 CTRunTrackChange 节点(raw() 仅支持文本/移动类);属性类请使用 acceptProperty/rejectProperty,"
              + "单元格类请使用 acceptCell/rejectCell");
    }
    return runDelegate;
  }

  /**
   * 返回底层 OOXML 修订标记的 {@code w:id}。
   *
   * <p>所有 family(文本/移动的 {@code CTRunTrackChange}、属性/单元格的 {@code CTTrackChange})都继承自 {@code
   * CTMarkup},共享 {@code getId()}。本方法在两个委托槽中取非空的那个读 id,供 {@code
   * internal/poi/TrackedChangeNodes.nextRevisionId} 扫描最大 {@code w:id} 时使用——它不能走 {@link #raw()} (raw
   * 对属性/单元格类会抛 {@code UnsupportedFeatureException})。
   *
   * <p>注意:这是底层 OOXML id,与 {@link #id()}(nondocx 对外稳定 id)<b>不是</b>同一概念。
   *
   * @return 底层 {@code w:id};节点未设 id 时返回 {@code null}
   */
  public java.math.BigInteger wId() {
    if (runDelegate != null) {
      return runDelegate.getId();
    }
    if (propertyDelegate != null) {
      return propertyDelegate.getId();
    }
    return null;
  }

  /**
   * 返回属性类 / 单元格类修订的底层 {@code CTTrackChange} 节点(包内接缝)。
   *
   * <p>对属性类({@link #family()} == {@link TrackedChangeFamily#PROPERTY PROPERTY},具体子类型 {@code
   * CTRPrChange}) 与单元格类({@link #family()} == {@link TrackedChangeFamily#CELL CELL},{@code
   * cellIns}/{@code cellDel} 的节点直接是 {@code CTTrackChange})有效;文本/移动类返回 {@code null}。供门面的 {@code
   * acceptProperty}/{@code rejectProperty} 与 {@code acceptCell}/{@code rejectCell} 经 {@code
   * internal/poi} 取节点做写穿透。
   */
  org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTrackChange propertyNode() {
    return propertyDelegate;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TrackedChange)) {
      return false;
    }
    TrackedChange that = (TrackedChange) o;
    return java.util.Objects.equals(this.id, that.id)
        && this.type == that.type
        && java.util.Objects.equals(this.location, that.location)
        && java.util.Objects.equals(this.details, that.details)
        && java.util.Objects.equals(this.author(), that.author())
        && java.util.Objects.equals(this.date(), that.date());
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(id, type, location, details, author(), date());
  }

  @Override
  public String toString() {
    return "TrackedChange{type="
        + type
        + ", id="
        + id
        + ", author=\""
        + author()
        + "\""
        + ", location="
        + location
        + ", "
        + details
        + '}';
  }
}
