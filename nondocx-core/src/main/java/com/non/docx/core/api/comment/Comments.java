package com.non.docx.core.api.comment;

import com.non.docx.core.internal.poi.CommentNodes;
import com.non.docx.core.internal.util.Objects;
import java.util.AbstractList;
import java.util.List;
import java.util.NoSuchElementException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/**
 * 文档的批注(comments)只读消费能力门面 —— 持有 {@link XWPFDocument} 委托,提供批注枚举与按 id 查询。
 *
 * <p>由 {@link com.non.docx.core.api.Document#comments()} 返回。本门面负责两件只读事:
 *
 * <ul>
 *   <li>{@link #list()} —— 按文档顺序({@code document.xml} 内 {@code commentRangeStart} 的出现顺序)枚举全部批注。
 *   <li>{@link #get(String)} —— 按 OOXML {@code w:id} 精确命中单条批注;未命中抛 {@link NoSuchElementException}。
 * </ul>
 *
 * <p><b>OOXML / POI / nondocx 三层。</b>
 *
 * <ul>
 *   <li><b>OOXML</b>:批注正文存独立的 {@code word/comments.xml},每条 {@code <w:comment>};被评论的范围在 {@code
 *       word/document.xml} 正文里用 {@code <w:commentRangeStart>}/{@code <w:commentRangeEnd>} 包裹,
 *       {@code <w:commentReference>} 引用,三者用同一 {@code w:id} 配对。
 *   <li><b>POI</b>:有完整高级 API {@code XWPFDocument.getDocComments()} → {@code XWPFComments}(无批注时返
 *       {@code null})、{@code getComments()} 枚举、{@code getCommentByID(id)} 按 id 查。但 {@code
 *       getComments()} 返回 {@code comments.xml} 部件顺序(创建顺序),<b>不等于</b>正文顺序。
 *   <li><b>nondocx</b>:把「扫 document.xml 找锚点顺序 + 从 comments.xml 取正文」的脏活收进 {@code
 *       internal/poi/CommentNodes},对外只暴露本类与 {@link Comment} 等 POI-free 类型。
 * </ul>
 *
 * <p><b>list() 顺序契约 —— body 顺序(design §5)。</b> POI 的 {@code getComments()} 返回 {@code comments.xml}
 * 部件顺序(创建顺序),可能与正文阅读顺序不一致(探针验证见 {@code research/ordering.md})。nondocx 的 {@link #list()} 按 {@code
 * document.xml} 内 {@code commentRangeStart} 的出现顺序返回,符合「先被评论的正文 → 先返回的 批注」的直觉。孤儿批注({@code
 * comments.xml} 有、{@code document.xml} 无锚点)降级排到末尾,不丢弃。
 *
 * <p><b>活对象语义(无字段快照)。</b> 本门面持有单个 {@code final XWPFDocument} 委托;{@code list()} 与 {@code get(id)}
 * 每次调用都<b>当场重算</b>,不缓存批注列表——因此文档改动会实时反映,守住「无字段快照」精神 (与 {@code TrackedChanges.list()}
 * 一致,poi-bridge.md Rule 1)。
 *
 * <p><b>不参与 {@code Document.equals}。</b> 与 tracked changes / TOC 类似,批注列表不纳入 {@code Document}
 * 的内容相等性。
 *
 * <p><b>只读。</b> 创作批注、回复、resolve 状态均不属于当前 read 子任务。
 */
public final class Comments {

  private final XWPFDocument delegate;

  /**
   * 封装给定的 POI 文档以解析其批注。
   *
   * <p>此构造函数是 {@link com.non.docx.core.api.Document} 生成批注视图的<b>内部接缝</b>,因此它有意接受 POI 类型(与 {@code
   * TrackedChanges(XWPFDocument)} 接受 {@code XWPFDocument} 的方式一致,见 poi-bridge.md N1)。 用户通过 {@code
   * Document.comments()} 获取,而不是直接构造。
   *
   * @param delegate 底层的 POI 文档(不能为 {@code null})
   * @throws IllegalArgumentException 如果 {@code delegate} 为 {@code null}
   */
  public Comments(XWPFDocument delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /**
   * 返回按文档顺序排列的全部批注(活跃视图)。
   *
   * <p>返回的列表是<b>活跃的</b>:每次访问(如 {@code size()}、{@code get(i)})都从委托重读,因此文档改动会实时
   * 反映。顺序严格按<b>文档顺序</b>({@code document.xml} 内 {@code commentRangeStart} 的出现顺序),不按 {@code
   * comments.xml} 部件顺序、不按 {@code w:id} 数值重排。
   *
   * <p>孤儿批注({@code comments.xml} 有批注但 {@code document.xml} 无对应 {@code commentRangeStart} 锚点,
   * 如损坏文档或手工删了锚点)按 {@code comments.xml} 部件顺序追加到末尾,不丢弃。
   *
   * <p>文档无任何批注时返回空列表(不抛异常)。
   *
   * @return 不可修改的、按文档顺序排列的活跃批注列表(可能为空)
   */
  public List<Comment> list() {
    List<Comment> snapshot = CommentNodes.collect(delegate);
    return new AbstractList<Comment>() {
      @Override
      public Comment get(int index) {
        return snapshot.get(index);
      }

      @Override
      public int size() {
        return snapshot.size();
      }
    };
  }

  /**
   * 按 OOXML {@code w:id} 精确获取单条批注。
   *
   * <p>语义是「命中式访问」:精确按 {@link Comment#id()} 定位。命中即返回;未命中抛 {@link NoSuchElementException} (而不是返回
   * {@code null})——按稳定标识精确定位的读取被视为「该有就有、没有就是错」。
   *
   * <p>内部会重新扫描一次批注列表(每次调用独立重算,活对象语义)。
   *
   * @param id OOXML {@code w:id} 的字符串形式(不能为 {@code null})
   * @return 对应的批注
   * @throws IllegalArgumentException 如果 {@code id} 为 {@code null}
   * @throws NoSuchElementException 如果没有 id 等于 {@code id} 的批注
   */
  public Comment get(String id) {
    Objects.requireNonNull(id, "id");
    for (Comment comment : CommentNodes.collect(delegate)) {
      if (comment.id().equals(id)) {
        return comment;
      }
    }
    throw new NoSuchElementException("找不到 id 为 " + id + " 的批注");
  }

  /**
   * 对一条已有批注回复,返回新建的回复批注。
   *
   * <p>回复 = 普通批注 + 线程关系。创作入口在<b>门面</b>(本方法),不在内容类型——对照 {@code tc.accept}(处理已有结构的 破坏性写在门面)。与 {@code
   * Paragraph.addComment}(创作新根批注)分工:addComment 建无父的根批注,reply 建挂父的子批注。
   *
   * <p><b>OOXML / POI / nondocx 三层。</b>
   *
   * <ul>
   *   <li><b>OOXML</b>:回复在 comments.xml 里就是新的 {@code <w:comment>}(同普通批注);线程关系靠 {@code
   *       commentsExtended.xml} 的 {@code <w15:commentEx w15:paraId=..
   *       w15:paraIdParent=父paraId/>};正文锚点紧贴父批注 锚点。durableId/dateUtc 协作元数据同步写入
   *       commentsIds/commentsExtensible。
   *   <li><b>POI</b>:comments.xml 用 {@code createComment}(复用);三个扩展 part POI <b>无 API</b>,nondocx 用
   *       OPC 层自维护 (探针见 {@code research/part-lifecycle.md})。
   *   <li><b>nondocx</b>:comments.xml 条目 + 正文锚点 + 四 part 追加全收进 {@code
   *       internal/poi/CommentNodes.replyToComment} 与 {@code
   *       internal/poi/CommentExtendedParts},对外只暴露本方法,返回 POI-free 的 {@link Comment}。
   * </ul>
   *
   * <p><b>返回值。</b> 返回新建的回复批注,{@link Comment#parentId()} 命中 {@code parentId} 参数。author 必传(与 {@code
   * addComment} 对称);date/{@code w:id}/paraId/durableId 自动分配。
   *
   * @param parentId 父批注的 OOXML {@code w:id}(不能为 {@code null},需已存在)
   * @param author 回复作者(不能为 {@code null} 或空白)
   * @param text 回复正文(不能为 {@code null};允许空串)
   * @return 新建的回复批注
   * @throws IllegalArgumentException 如果 {@code parentId} 为 {@code null},{@code author} 为 {@code
   *     null} 或空白, 或 {@code text} 为 {@code null}
   * @throws NoSuchElementException 如果没有 id 等于 {@code parentId} 的批注
   */
  public Comment reply(String parentId, String author, String text) {
    Objects.requireNonNull(parentId, "parentId");
    Objects.requireNonNull(author, "author");
    if (author.isBlank()) {
      throw new IllegalArgumentException("author 不能为空白");
    }
    Objects.requireNonNull(text, "text");
    // 校验父批注存在(miss 抛 NoSuchElementException)
    Comment parent = get(parentId);
    org.apache.poi.xwpf.usermodel.XWPFComment created =
        CommentNodes.replyToComment(delegate, parent.raw(), author, text);
    // 重新 collect 一次拿到注入了 parentId 的 Comment(活对象语义,与 list/get 同)
    String newId = created.getId() == null ? "" : created.getId().toString();
    return get(newId);
  }

  /**
   * 返回底层的 POI 文档。
   *
   * <p>对返回对象的修改会立即影响文档。请谨慎使用。批注没有专属单一委托类型,这里的「委托」就是整份文档; 后续 authoring / reply
   * 子任务会经此方法拿到可写委托做创作与回复。想直接操作批注的 OOXML 结构时,从此处 拿到 {@link XWPFDocument} 后走 {@code getDocComments()}
   * 等到 {@code XWPFComments}。
   *
   * @return 底层的 {@link XWPFDocument} 实例(包装器生命周期内同一实例)
   */
  public XWPFDocument raw() {
    return delegate;
  }
}
