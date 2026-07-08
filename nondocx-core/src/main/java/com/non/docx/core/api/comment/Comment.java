package com.non.docx.core.api.comment;

import com.non.docx.core.internal.util.Objects;
import java.util.Calendar;
import java.util.Optional;
import org.apache.poi.xwpf.usermodel.XWPFComment;

/**
 * 文档里的一条批注(comment)——一个<b>holding-wrapper</b>,持有底层 POI 批注对象。
 *
 * <p>每条批注是对文档某处内容的一段评论:有人选中一段正文、写下了意见。OOXML 把批注的<b>正文</b>(评论 内容)与<b>锚点</b>(被评论的范围)分两个地方存,nondocx
 * 把它们统一包装成本类型,对外暴露稳定的领域视图。
 *
 * <p><b>OOXML / POI / nondocx 三层。</b>
 *
 * <ul>
 *   <li><b>OOXML</b>:批注正文存在独立的 {@code word/comments.xml} 里,每条是一个 {@code <w:comment w:id="0"
 *       w:author="non" w:date="..." w:initials="n">...</w:comment>} 元素,其内是评论段落 ({@code
 *       <w:p>/<w:r>/<w:t>},与正文同构)。被评论的范围在 {@code word/document.xml} 正文里用 {@code
 *       <w:commentRangeStart w:id="0"/>} ... {@code <w:commentRangeEnd w:id="0"/>} 包裹, 并用 {@code
 *       <w:commentReference w:id="0"/>} 引用。两端用同一个 {@code w:id} 配对。
 *   <li><b>POI</b>:有完整的高级类型 {@link XWPFComment}(实现 {@code IBody}),提供 {@code
 *       getId/getAuthor/getInitials/getDate/getText/getParagraphs/getCtComment} 等。文档级入口是 {@code
 *       XWPFDocument.getDocComments()} → {@code XWPFComments}(无批注时返回 {@code null})。
 *   <li><b>nondocx</b>:把「扫 document.xml 找批注锚点顺序 + 从 comments.xml 取正文」的脏活收进 {@code
 *       internal/poi/CommentNodes},对外只暴露本类与 {@link Comments} 等 POI-free 类型。
 * </ul>
 *
 * <p><b>包装形态 —— holding-wrapper 持 {@link XWPFComment}(design §4)。</b> 本类持有单个 {@code final
 * XWPFComment} 委托,读写穿透。这与 {@code TrackedChange}(持 {@code CTRunTrackChange} 委托)、{@code Section}(持
 * {@code XWPFDocument + CTSectPr})是同构的先例,而<b>不是</b>像 {@code TableOfContents}/{@code TocEntry} 那样对
 * poi-bridge.md Rule 1 的偏差——因为批注<b>有</b>干净的 per-comment POI 句柄({@code XWPFComment}),TOC 没有。
 *
 * <p><b>字段职责。</b> 五个只读字段全部穿透到委托:
 *
 * <ul>
 *   <li>{@link #id()} —— OOXML {@code w:id} 的字符串形式(批注的稳定外部标识,用于配对锚点)。
 *   <li>{@link #author()} —— 批注作者(派生自 {@code XWPFComment.getAuthor()});缺失为空串。
 *   <li>{@link #initials()} —— 作者缩写(派生自 {@code XWPFComment.getInitials()});缺失为空串。
 *   <li>{@link #date()} —— 批注时间(派生自 {@code XWPFComment.getDate()});缺失为 {@code null}。
 *   <li>{@link #text()} —— 批注正文拼接文本(委托 {@code XWPFComment.getText()})。
 * </ul>
 *
 * <p><b>内容相等。</b> {@code equals} / {@code hashCode} 比较上述五个字段(均派生自委托的内容,从不比较委托 引用),与 nondocx 的
 * round-trip 相等性约定一致(quality-guidelines.md Rule 2 + poi-bridge.md N7)。
 *
 * <p><b>只读。</b> 本子任务({@code 06-22-comments-read})只交付消费侧;创作批注、回复、resolve 状态属于后续 子任务。
 */
public final class Comment {

  private final XWPFComment delegate;
  // 线程字段(reply-threads 子任务新增):POI 委托不提供,由 CommentNodes.collect 从 commentsExtended.xml
  // 解析后注入。无线程信息时(根批注/旧文档)paraId 为 null、parentId 为 null。
  private final String paraId;
  private final String parentId;

  /**
   * 包装一个 POI 批注对象(无线程字段)。
   *
   * <p>等价于 {@code Comment(delegate, null, null)}——paraId/parentId 为 null(根批注或旧文档场景)。保留此构造 供既有调用方兼容。
   *
   * @param delegate 底层的 POI 批注(不能为 {@code null})
   * @throws IllegalArgumentException 如果 {@code delegate} 为 {@code null}
   */
  public Comment(XWPFComment delegate) {
    this(delegate, null, null);
  }

  /**
   * 包装一个 POI 批注对象,并注入线程字段(paraId / parentId)。
   *
   * <p>此构造函数是 {@code internal/poi/CommentNodes} 生成领域视图的<b>内部接缝</b>,因此它有意接受 POI 类型 (与 {@code
   * TrackedChange(CTRunTrackChange)} 接受 {@code CTRunTrackChange} 的方式一致,见 poi-bridge.md N1)。用户通过
   * {@link Comments#list()} / {@link Comments#get(String)} 获取批注,而不是直接构造。
   *
   * <p>线程字段由 {@code CommentNodes.collect} 从 {@code commentsExtended.xml} 解析后注入:paraId 派生自批注内首段的
   * {@code w14:paraId},parentId 经 paraId→parentParaId→commentId join 得到。无线程信息(根批注/无
   * commentsExtended 的旧文档)时两者为 {@code null}。
   *
   * @param delegate 底层的 POI 批注(不能为 {@code null})
   * @param paraId 批注的 {@code w14:paraId}(可为 {@code null})
   * @param parentId 父批注的 OOXML {@code w:id}(可为 {@code null} = 根批注)
   * @throws IllegalArgumentException 如果 {@code delegate} 为 {@code null}
   */
  public Comment(XWPFComment delegate, String paraId, String parentId) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.paraId = paraId;
    this.parentId = parentId;
  }

  /**
   * 返回批注的 OOXML {@code w:id} 的字符串形式。
   *
   * <p>这是批注的稳定外部标识——{@code document.xml} 里的 {@code commentRangeStart}/{@code commentReference} 用同一个
   * {@code w:id} 与本批注配对。与 tracked-changes 的 nondocx 稳定 id 不同,批注的 {@code w:id} 在 OOXML
   * 语义里本身就是跨会话稳定的外部标识,nondocx 直接透传,不另造 id 体系。
   *
   * @return 批注 id(从不为 {@code null})
   */
  public String id() {
    return delegate.getId() == null ? "" : delegate.getId().toString();
  }

  /**
   * 返回批注作者(派生自 OOXML {@code w:author} 属性)。文档未记录作者时为空串。
   *
   * @return 作者(从不为 {@code null},可能为空)
   */
  public String author() {
    return delegate.getAuthor() == null ? "" : delegate.getAuthor();
  }

  /**
   * 返回作者缩写(派生自 OOXML {@code w:initials} 属性)。文档未记录缩写时为空串。
   *
   * @return 缩写(从不为 {@code null},可能为空)
   */
  public String initials() {
    return delegate.getInitials() == null ? "" : delegate.getInitials();
  }

  /**
   * 返回批注时间(派生自 OOXML {@code w:date} 属性)。文档未记录时间时为 {@code null}。
   *
   * <p>POI 探针确认批注的 {@code w:date} 可能缺失,故本方法返回可空 {@link Calendar}(与 {@code TrackedChange.date()}
   * 一致)。
   *
   * @return 批注时间,或 {@code null}
   */
  public Calendar date() {
    return delegate.getDate();
  }

  /**
   * 返回批注正文的拼接文本。
   *
   * <p>委托 {@link XWPFComment#getText()}——POI 把批注内全部段落的文本拼好返回。多段批注会拼成一段文本。
   *
   * <p><b>回退条款(design §4.5)</b>:若后续测试发现 POI 的 {@code getText()} 在某些批注形态(嵌套 SDT、含图片 run
   * 等)下行为异常,实现可回退到自拼 {@code getParagraphs()} 的 {@code getText()}——这是实现细节,不改变 公共契约(返回值仍是 {@code
   * String})。
   *
   * @return 批注正文文本(从不为 {@code null},无正文时为空串)
   */
  public String text() {
    return delegate.getText() == null ? "" : delegate.getText();
  }

  /**
   * 返回批注的 {@code w14:paraId}(线程关系的链 key)。
   *
   * <p>派生自批注内首段落的 {@code w14:paraId} 属性。{@code commentsExtended.xml} 的 {@code w15:paraIdParent}
   * 用父批注的 paraId 表达线程关系,故 paraId 是 {@link #parentId()} 解析的中间 key。
   *
   * <p><b>可缺失。</b> 旧文档、authoring 子任务产出的批注(未补 paraId)、或解析失败时返回 {@code null}。paraId 缺失意味
   * 着该批注无法参与线程链,{@link #parentId()} 也会是 empty。
   *
   * @return 批注的 paraId,或 {@code null}(无 paraId)
   */
  public String paraId() {
    return paraId;
  }

  /**
   * 返回父批注的 OOXML {@code w:id}(线程关系),根批注返回 {@link Optional#empty()}。
   *
   * <p>派生自 {@code commentsExtended.xml} 的 {@code w15:paraIdParent}:本批注的 paraId → 父批注 paraId → 父批注
   * comment id(经 {@code CommentNodes.collect} 的 join 解析)。根批注(无 paraIdParent)、无 {@code
   * commentsExtended.xml} 的旧文档、或 paraId 缺失时返回 {@link Optional#empty()}。
   *
   * <p><b>线程语义。</b> {@code isPresent()} 为 true 即「本批注是某批注的回复」;为 empty 即「根批注」。
   *
   * @return 父批注的 {@code w:id},或 {@link Optional#empty()}(根批注/无线程信息)
   */
  public Optional<String> parentId() {
    return parentId == null ? Optional.empty() : Optional.of(parentId);
  }

  /**
   * 返回底层的 POI 批注对象。
   *
   * <p>对返回对象的修改会立即影响文档。请谨慎使用。authoring / reply 子任务经此方法拿到可写委托做创作 与回复(见 {@code
   * internal/poi/CommentNodes})。
   *
   * @return 底层的 {@link XWPFComment} 实例(包装器生命周期内同一实例)
   */
  public XWPFComment raw() {
    return delegate;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Comment)) {
      return false;
    }
    Comment that = (Comment) o;
    return java.util.Objects.equals(this.id(), that.id())
        && java.util.Objects.equals(this.author(), that.author())
        && java.util.Objects.equals(this.initials(), that.initials())
        && java.util.Objects.equals(this.date(), that.date())
        && java.util.Objects.equals(this.text(), that.text());
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(id(), author(), initials(), date(), text());
  }

  @Override
  public String toString() {
    return "Comment{id=" + id() + ", author=\"" + author() + "\", text=\"" + text() + "\"}";
  }
}
