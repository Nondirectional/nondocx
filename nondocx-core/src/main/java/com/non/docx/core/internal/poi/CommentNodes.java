package com.non.docx.core.internal.poi;

import com.non.docx.core.api.comment.Comment;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.xwpf.usermodel.XWPFComment;
import org.apache.poi.xwpf.usermodel.XWPFComments;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.xmlbeans.XmlCursor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTMarkupRange;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;

/**
 * 内部 API——恕不另行通知即可更改。
 *
 * <p>这是 nondocx 里<b>唯一</b>接触批注 OOXML 结构的地方,因此公有类型 {@link Comment} / {@link
 * com.non.docx.core.api.comment.Comments} 在源代码层面保持无 {@code org.apache.poi.*}(构造函数接缝除外)。
 *
 * <p><b>职责。</b> 提供读 + 创作两类能力:
 *
 * <ul>
 *   <li><b>读</b>({@link #collect}):按文档顺序枚举批注,解析为 {@link Comment} 领域视图。
 *   <li><b>创作</b>({@link #nextCommentId} / {@link #addWholeParagraphComment}):分配批注 {@code w:id},给整段
 *       插入范围批注(锚点 + comments.xml 条目)。
 * </ul>
 *
 * <p><b>OOXML 结构(教学要点)。</b> 批注在 OOXML 里是「正文 + 锚点」分离的两 part 结构:
 *
 * <pre>{@code
 * word/document.xml(锚点):
 *   <w:p>
 *     <w:commentRangeStart w:id="0"/>          ← 批注范围起点
 *     <w:r><w:t>被评论的正文</w:t></w:r>
 *     <w:commentReference w:id="0"/>           ← 引用(在 run 里)
 *     <w:commentRangeEnd w:id="0"/>            ← 批注范围终点
 *   </w:p>
 *
 * word/comments.xml(正文):
 *   <w:comment w:id="0" w:author="non" w:date="..." w:initials="n">
 *     <w:p><w:r><w:t>这是批注内容</w:t></w:r></w:p>
 *   </w:comment>
 * }</pre>
 *
 * 两端用同一个 {@code w:id} 配对。{@code comments.xml} 里 {@code <w:comment>} 的顺序是<b>创建顺序</b>, <b>不等于</b>
 * {@code document.xml} 里 {@code commentRangeStart} 的出现顺序(正文顺序)。
 *
 * <p><b>POI 的坑(探针验证见 research/ordering.md)。</b>
 *
 * <ol>
 *   <li>{@code XWPFDocument.getDocComments()} 在文档<b>无任何批注</b>时返回 {@code null}(POI 不自动创建空 part)。本类
 *       null-guard 返回空列表。
 *   <li>{@code XWPFComments.getComments()} 返回 {@code comments.xml} 部件顺序(创建顺序),<b>不等于</b>正文 顺序。本类自己扫
 *       {@code document.xml} 的 {@code commentRangeStart} 重排。
 *   <li>{@code commentRangeStart} 可出现在任意层级——段落内、表格单元格内段落里、嵌套结构里。本类用 {@link XmlCursor} 对 {@code
 *       CTBody} 整棵子树做深度优先遍历,按出现顺序收集 {@code commentRangeStart} 的 {@code w:id},保证覆盖所有层级。
 * </ol>
 *
 * <p><b>防御式。</b> 整个 walk 不在本类抛 POI 异常——单个批注解析失败时跳过该条而非整体失败,保证一份文档即便 局部畸形也能尽量给出其余批注。
 */
public final class CommentNodes {

  private CommentNodes() {}

  /**
   * 按文档顺序枚举批注,解析为 {@link Comment} 列表。
   *
   * <p>算法(design §5.4):
   *
   * <ol>
   *   <li>从 {@code getDocComments().getComments()} 建 {@code Map<id 字符串, XWPFComment>}({@code
   *       getDocComments()} 为 null 时返回空列表)。
   *   <li>用 {@link XmlCursor} 深度优先遍历 {@code CTBody} 整棵子树,按出现顺序收集 {@code commentRangeStart} 的 {@code
   *       w:id}。
   *   <li>按 body 出现顺序从 Map 取 {@code XWPFComment},包装成 {@link Comment} 产出(取出后从 Map 移除防重复)。
   *   <li>Map 剩余的是孤儿批注({@code comments.xml} 有、{@code document.xml} 无锚点),按 {@code comments.xml}
   *       部件顺序追加到末尾,不丢弃。
   * </ol>
   *
   * @param document POI 文档(不能为 {@code null})
   * @return 按文档顺序排列的批注列表(可能为空;从不为 {@code null})
   */
  public static List<Comment> collect(XWPFDocument document) {
    java.util.Objects.requireNonNull(document, "document");
    List<Comment> out = new ArrayList<>();
    XWPFComments xcomments = document.getDocComments();
    if (xcomments == null) {
      return out;
    }
    // comments.xml 全部批注,按部件顺序入 Map(保留顺序用于孤儿降级)
    List<XWPFComment> allByPartOrder = readAllSafe(xcomments);
    Map<String, XWPFComment> byId = new HashMap<>();
    for (XWPFComment c : allByPartOrder) {
      String id = commentIdOf(c);
      if (id != null) {
        byId.putIfAbsent(id, c);
      }
    }
    if (byId.isEmpty()) {
      return out;
    }
    // 线程解析器:解析 commentsExtended.xml 得 paraId→parentParaId,产出时注入 Comment(reply-threads)
    ThreadResolver threads = ThreadResolver.build(document, byId);
    // 扫 document.xml 的 commentRangeStart,按出现顺序产出
    CTBody body = document.getDocument().getBody();
    if (body == null) {
      appendOrphans(allByPartOrder, byId, new HashSet<>(), out, threads);
      return out;
    }
    Set<String> seen = new HashSet<>();
    XmlCursor cur = body.newCursor();
    try {
      collectRangeStartIds(cur, byId, seen, out, threads);
    } finally {
      cur.dispose();
    }
    // 孤儿降级:Map 里没被 body 锚点命中的批注,按部件顺序追加末尾
    appendOrphans(allByPartOrder, byId, seen, out, threads);
    return out;
  }

  /**
   * 用 {@link XmlCursor} 深度优先遍历 cursor 所指元素的整棵子树,遇到 {@code commentRangeStart} 就取其 {@code w:id}、从 Map
   * 取批注产出。
   *
   * <p>深度优先(而非只扫直接子)是因为 {@code commentRangeStart} 可能在任意层级——段落内、表格单元格内段落里。 用 {@code
   * toFirstChild/toNextSibling} 的递归下降覆盖整棵树,按文档顺序(前序遍历)收集。
   *
   * <p><b>cursor 状态管理(关键)。</b> 递归下钻前用 {@link XmlCursor#push()} 保存当前位置,下钻返回后用 {@link
   * XmlCursor#pop()} 恢复——否则 {@code toFirstChild()} 会把 cursor 移到子树深处,外层的 {@code toNextSibling()}
   * 会从错误位置继续,漏掉同层后续兄弟。({@code TrackedChangeNodes} 的 walk 不踩这坑 是因为它命中修订节点后不下钻;comments 的 {@code
   * commentRangeStart} 是叶子元素,遍历必须继续走过兄弟。)
   *
   * @param cur 指向子树根的 cursor(调用前已定位;本方法 {@code toFirstChild} 进入第一层子并遍历整棵子树)
   */
  private static void collectRangeStartIds(
      XmlCursor cur,
      Map<String, XWPFComment> byId,
      Set<String> seen,
      List<Comment> out,
      ThreadResolver threads) {
    if (!cur.toFirstChild()) {
      return;
    }
    do {
      String local = cur.getName() == null ? "" : cur.getName().getLocalPart();
      if ("commentRangeStart".equals(local)) {
        String id = readWAttribute(cur, "id");
        if (id != null && !seen.contains(id)) {
          seen.add(id);
          XWPFComment c = byId.get(id);
          if (c != null) {
            produceSafe(c, out, threads);
          }
        }
      }
      // 下钻子树前 push 保存位置,返回后 pop 恢复,保证 toNextSibling 在正确的兄弟层推进
      cur.push();
      collectRangeStartIds(cur, byId, seen, out, threads);
      cur.pop();
    } while (cur.toNextSibling());
  }

  /**
   * 把 {@code comments.xml} 里有、但 {@code document.xml} 无 {@code commentRangeStart} 锚点的孤儿批注, 按 {@code
   * comments.xml} 部件顺序追加到结果末尾。
   *
   * <p>孤儿批注通常出现在损坏文档或手工删了锚点的场景;不丢弃是为了让用户能看到所有批注内容。
   *
   * @param allByPartOrder 全部批注(部件顺序)
   * @param byId id → 批注 Map
   * @param seen body 已命中过的 id 集合
   */
  private static void appendOrphans(
      List<XWPFComment> allByPartOrder,
      Map<String, XWPFComment> byId,
      Set<String> seen,
      List<Comment> out,
      ThreadResolver threads) {
    for (XWPFComment c : allByPartOrder) {
      String id = commentIdOf(c);
      if (id == null || seen.contains(id)) {
        continue;
      }
      seen.add(id);
      produceSafe(c, out, threads);
    }
  }

  /** 读 {@code XWPFComments.getComments()},防御式:POI 抛异常时返回空列表而非整体失败。 */
  private static List<XWPFComment> readAllSafe(XWPFComments xcomments) {
    try {
      List<XWPFComment> list = xcomments.getComments();
      return list == null ? new ArrayList<>() : new ArrayList<>(list);
    } catch (RuntimeException e) {
      // 读不到批注列表(罕见,如 part 损坏)按「无批注」处理
      return new ArrayList<>();
    }
  }

  /** 把一个 {@link XWPFComment} 包装成 {@link Comment} 加入结果列表,注入线程字段(paraId/parentId),防御式:解析失败时 跳过该条。 */
  private static void produceSafe(XWPFComment c, List<Comment> out, ThreadResolver threads) {
    try {
      String paraId = threads.paraIdOf(c);
      String parentId = threads.parentIdOf(c);
      out.add(new Comment(c, paraId, parentId));
    } catch (RuntimeException e) {
      // 单条批注解析失败时跳过,不影响其余批注产出(PRD R3.3)
    }
  }

  /**
   * 返回批注的 {@code w:id} 字符串形式;无 id 返回 {@code null}。
   *
   * <p>{@code XWPFComment.getId()} 返回 {@code BigInteger},转成字符串作 Map key(与 {@link Comment#id()} 的
   * 透传口径一致)。
   */
  private static String commentIdOf(XWPFComment c) {
    return c.getId() == null ? null : c.getId().toString();
  }

  /**
   * 从 cursor 指向的元素读 {@code w:<localName>} 属性的文本值;缺失返回 {@code null}。
   *
   * <p>OOXML 的批注锚点属性({@code w:id})在 {@code w} 命名空间下,故按带命名空间的 QName 读。与 {@code
   * TrackedChangeNodes.readWAttribute} 同套路,但缺失时返回 {@code null} 以区分「无值」与「空串」。
   */
  private static String readWAttribute(XmlCursor cur, String localName) {
    return cur.getAttributeText(
        javax.xml.namespace.QName.valueOf(
            "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}" + localName));
  }

  /**
   * 给创作产出的批注补现代兼容基础设施(子任务 4):paraId + RSID + people.xml。
   *
   * <p>三项注入都委托 {@link AuthoringInfra}(本类的内部 helper,集中 comments 路径的现代 Word 兼容脏活)。
   *
   * <ul>
   *   <li><b>paraId</b>:给 {@code comment} 内首段设 {@code w14:paraId}(若该段无 paraId)。reply 路径已在创作时设过,
   *       本方法幂等(已设则跳过);addComment 路径靠这里补。
   *   <li><b>RSID</b>:给 {@code comment} 内首段 + (若给定){@code refRun} 标 Document 级单例 RSID。
   *   <li><b>people.xml</b>:注册 {@code author}(幂等)。
   * </ul>
   *
   * @param document POI 文档
   * @param comment 刚创建的批注(paraId/RSID 标在其内首段)
   * @param refRun 正文引用 run 的 CTR(标 RSID);{@code null} 则跳过(reply 路径的引用 run 结构不同)
   * @param author 要注册到 people.xml 的 author
   */
  private static void stampAuthoringInfrastructure(
      XWPFDocument document, XWPFComment comment, CTR refRun, String author) {
    try {
      java.util.List<XWPFParagraph> paras = comment.getParagraphs();
      if (!paras.isEmpty()) {
        XWPFParagraph first = paras.get(0);
        // paraId 幂等:已设则 setParaId 覆盖也无害,但为保留 reply 路径已设的 paraId,先查再补
        if (paraIdOfComment(comment) == null) {
          AuthoringInfra.setParaId(first, AuthoringInfra.newParaId());
        }
        // RSID:标批注内首段
        String rsid = AuthoringInfra.documentRsid(document);
        AuthoringInfra.stampRsid(first, rsid);
        // RSID:标正文引用 run(若有)
        if (refRun != null) {
          AuthoringInfra.stampRsid(refRun, rsid);
        }
      }
      // people.xml:注册 author(幂等)
      AuthoringInfra.registerAuthor(document, author);
    } catch (RuntimeException e) {
      // 基础设施注入失败不阻断主创作流程:批注正文已完整写出(prd R5 防御式)
    }
  }

  // ---------- 破坏性写:批注创作 ----------

  /**
   * 计算文档下一个可用的批注 {@code w:id}(扫描 {@code comments.xml} 已有批注的 {@code w:id},取最大值 +1;无任何 批注时返回 0)。
   *
   * <p>这是底层 OOXML 批注 id,与 {@link TrackedChangeNodes#nextRevisionId 修订 id} <b>不是</b>同一套计数器——批注 的
   * {@code CTMarkup} {@code w:id} 与修订的 {@code CTTrackChange} {@code w:id} 在 OOXML 里是两个独立命名空间,互不
   * 影响(见 design §5)。
   *
   * <p>扫 {@code comments.xml} 的全部批注取 max(而非扫 {@code document.xml} 的锚点),因为 {@code w:id} 的真源是 {@code
   * comments.xml} 的 {@code <w:comment w:id=..>};锚点只是引用同一个 id。{@code XWPFComment.getId()} 在 POI
   * 5.2.5 返回 {@code String},本方法解析为 {@code long} 取 max;非数字 id(罕见)跳过。
   *
   * @param document POI 文档(不能为 {@code null})
   * @return 下一个可用的批注 {@code w:id}(无批注时返回 0)
   */
  public static BigInteger nextCommentId(XWPFDocument document) {
    java.util.Objects.requireNonNull(document, "document");
    XWPFComments xcomments = document.getDocComments();
    if (xcomments == null) {
      return BigInteger.ZERO;
    }
    List<XWPFComment> all = readAllSafe(xcomments);
    long max = -1;
    for (XWPFComment c : all) {
      // XWPFComment.getId() 返回 String(POI 5.2.5),解析为 long 取 max;非数字 id 跳过
      String idStr = commentIdOf(c);
      if (idStr != null) {
        try {
          long v = Long.parseLong(idStr);
          if (v > max) {
            max = v;
          }
        } catch (NumberFormatException e) {
          // 非数字 id(罕见)跳过,不影响数字 id 的 max 计算
        }
      }
    }
    return BigInteger.valueOf(max + 1);
  }

  /**
   * 给一个<b>已有内容</b>的段落加整条范围批注(范围 = 整段),返回新建的 POI 批注对象。
   *
   * <p>OOXML 形态(教学要点,见 {@code research/insert-position.md} 探针):
   *
   * <pre>{@code
   * word/document.xml(正文锚点):
   *   <w:p>
   *     <w:commentRangeStart w:id="0"/>     ← 必须在段首(包住整段)
   *     <w:r>已有文本</w:r>
   *     <w:commentRangeEnd w:id="0"/>       ← run 之后(段末语义位置)
   *     <w:r><w:commentReference w:id="0"/></w:r>   ← 引用 run(新建,段末)
   *   </w:p>
   * word/comments.xml(批注正文):
   *   <w:comment w:id="0" w:author=.. w:date=.. w:initials=..>
   *     <w:p><w:r><w:t>批注正文</w:t></w:r></w:p>
   *   </w:comment>
   * }</pre>
   *
   * <p><b>POI 的坑(探针验证见 {@code research/insert-position.md} §3.1)。</b> {@link
   * XWPFComments#createComment} 只建 {@code comments.xml} 的条目,<b>不</b>动正文;正文锚点要自己用 {@link
   * org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP#addNewCommentRangeStart} 等建。而 POI 的
   * {@code addNew}/{@code insertNew} 都<b>不</b>按 OOXML schema 顺序排序——{@code commentRangeStart}
   * 会被追加到段末 (在所有已有 run 之后),导致范围实际为空(包住 0 个 run)。故必须用 {@link XmlCursor} 把 {@code commentRangeStart}
   * 手动 move 到 {@code CTP} 第一个子之前。这是批注创作区别于 {@link TrackedChangeNodes#addInsertion}(新建容器包新
   * run,顺序天然正确)的核心脏活。
   *
   * <p><b>边界:空段。</b> 若 {@code CTP} 无任何子元素,{@code addNew} 自然把 {@code commentRangeStart} 放在首位,无需
   * move({@code toFirstChild} 返回 false 时跳过 move)。
   *
   * @param target 目标语段落的 POI 句柄(不能为 {@code null});文档从 {@code target.getDocument()} 取
   * @param author 批注作者(不能为 {@code null})
   * @param text 批注正文(不能为 {@code null};允许空串,写出空正文批注)
   * @param date 批注时间(不能为 {@code null})
   * @param id 批注的 OOXML {@code w:id}(不能为 {@code null};由 {@link #nextCommentId} 分配)
   * @return 新建的 {@link XWPFComment}(已设 author/date/initials + 单段正文)
   */
  public static XWPFComment addWholeParagraphComment(
      XWPFParagraph target, String author, String text, Calendar date, BigInteger id) {
    java.util.Objects.requireNonNull(target, "target");
    java.util.Objects.requireNonNull(author, "author");
    java.util.Objects.requireNonNull(text, "text");
    java.util.Objects.requireNonNull(date, "date");
    java.util.Objects.requireNonNull(id, "id");

    XWPFDocument document = target.getDocument();
    // 首条批注时 comments part 尚不存在(POI 不自动创建空 part),getDocComments() 返回 null
    XWPFComments xcomments = document.getDocComments();
    if (xcomments == null) {
      xcomments = document.createComments();
    }
    // 1) comments.xml 条目:设 author/date/initials + 单段正文
    XWPFComment comment = xcomments.createComment(id);
    comment.setAuthor(author);
    comment.setDate(date);
    comment.setInitials(""); // 不派生 initials(见 design §4.1);空串不影响 Word 显示
    comment.createParagraph().createRun().setText(text);

    // 2) 正文 CTP 三锚点:addNew 都追加到段末
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP ctp = target.getCTP();
    CTMarkupRange start = ctp.addNewCommentRangeStart();
    start.setId(id);
    CTMarkupRange end = ctp.addNewCommentRangeEnd();
    end.setId(id);
    CTR refRun = ctp.addNewR();
    refRun.addNewCommentReference().setId(id);

    // 3) XmlCursor 把 commentRangeStart move 到 CTP 第一个子之前(包住整段)。
    //    commentRangeEnd + 引用 run 留在段末(addNew 自然位置即正确语义)。
    //    空段(toFirstChild 返 false)时无需 move——start 已在首位。
    XmlCursor pCur = ctp.newCursor();
    XmlCursor startCur = start.newCursor();
    try {
      if (pCur.toFirstChild()) {
        startCur.moveXml(pCur); // 把 start 移到 pCur(原第一个子)之前
      }
    } finally {
      pCur.dispose();
      startCur.dispose();
    }

    // 4) 现代兼容基础设施自动注入(子任务 4):
    //    - paraId:给批注内首段补(POI createParagraph 不写;reply 路径已补,addComment 路径这里补齐)
    //    - RSID:给批注内首段 + 正文引用 run 标 Document 级单例 RSID
    //    - people.xml:注册 author(Word @mention 提示)
    stampAuthoringInfrastructure(document, comment, refRun, author);
    return comment;
  }

  /**
   * 对一条已有批注回复,返回新建的回复批注。
   *
   * <p>回复 = 普通批注 + 线程关系。创作四步(design §4):
   *
   * <ol>
   *   <li>comments.xml 加回复条目(同普通批注),给其内首段补 {@code w14:paraId}。
   *   <li>正文锚点:在父批注 {@code commentRangeStart} 后插新 {@code commentRangeStart};在父批注引用 run 后插 新 {@code
   *       commentRangeEnd} + 引用 run(对照 docx skill {@code reply_to_comment})。
   *   <li>检查父批注 paraId:无则补(否则 paraIdParent 链断)。
   *   <li>{@link CommentExtendedParts#appendEntries} 四 part 追加线程关系 + durableId/dateUtc。
   * </ol>
   *
   * @param document POI 文档(不能为 {@code null})
   * @param parent 父批注(不能为 {@code null},需已存在于 comments.xml 与正文)
   * @param author 回复作者(不能为 {@code null})
   * @param text 回复正文(不能为 {@code null})
   * @return 新建的回复批注(含线程字段)
   */
  public static XWPFComment replyToComment(
      XWPFDocument document, XWPFComment parent, String author, String text) {
    java.util.Objects.requireNonNull(document, "document");
    java.util.Objects.requireNonNull(parent, "parent");
    java.util.Objects.requireNonNull(author, "author");
    java.util.Objects.requireNonNull(text, "text");

    BigInteger replyId = nextCommentId(document);
    String replyParaId = CommentExtendedParts.randomHexId();
    String durableId = CommentExtendedParts.randomHexId();
    String dateUtc = CommentExtendedParts.dateUtcNow();
    java.util.Calendar now = java.util.Calendar.getInstance();

    // 父批注 paraId:无则补一个(否则 paraIdParent 链断)
    String parentParaId = ensureCommentParaId(parent);

    // 1) comments.xml 回复条目
    XWPFComments xcomments = document.getDocComments();
    if (xcomments == null) {
      xcomments = document.createComments();
    }
    XWPFComment reply = xcomments.createComment(replyId);
    reply.setAuthor(author);
    reply.setDate(now);
    reply.setInitials("");
    reply.createParagraph().createRun().setText(text);
    // 给回复批注内首段补 w14:paraId(POI createParagraph 不写)。收敛到 AuthoringInfra.setParaId(子任务 4)。
    AuthoringInfra.setParaId(reply.getParagraphs().get(0), replyParaId);

    // 2) 正文锚点:父 commentRangeStart 后插新 commentRangeStart;父引用 run 后插新 commentRangeEnd + 引用 run
    String parentIdStr = commentIdOf(parent);
    String rsid = AuthoringInfra.documentRsid(document); // Document 级单例 RSID(子任务 4)
    insertReplyAnchors(document, parentIdStr, replyId, rsid);

    // 3) 四 part 追加线程关系(根批注也要在 commentsExtended 里有条目?——docx skill 只在 reply 时给子条目
    //    标 paraIdParent;父批注若无 commentsExtended 条目,Word 仍能按 paraIdParent 显示线程。故只追加回复条目)
    CommentExtendedParts.appendEntries(document, replyParaId, parentParaId, durableId, dateUtc);

    // 4) 现代兼容基础设施(paraId 已在上方设过故幂等跳过;这里补 RSID 标到回复批注内首段 + people.xml 注册 author)。
    //    正文引用 run 的 RSID 已在 insertReplyAnchors 内就地标(该 run 由 XmlCursor 创建,无 CTR 句柄)。
    stampAuthoringInfrastructure(document, reply, null, author);
    return reply;
  }

  /**
   * 在正文里插回复批注的锚点:父批注 {@code commentRangeStart(id=parentId)} 后插新 {@code
   * commentRangeStart(id=replyId)}; 父批注引用 run(含 {@code commentReference(id=parentId)}) 后插新 {@code
   * commentRangeEnd(id=replyId)} + 引用 run。
   *
   * <p>用 XmlCursor 扫 body 定位父锚点。父锚点位置参考 docx skill {@code reply_to_comment}:回复范围紧跟父范围、几乎重合。
   */
  private static void insertReplyAnchors(
      XWPFDocument document, String parentIdStr, BigInteger replyId, String rsid) {
    CTBody body = document.getDocument().getBody();
    if (body == null) {
      return;
    }
    XmlCursor cur = body.newCursor();
    try {
      if (!cur.toFirstChild()) {
        return;
      }
      // 第一遍:定位父 commentRangeStart,在其后插新 commentRangeStart
      boolean[] insertedStart = {false};
      do {
        if ("commentRangeStart".equals(localOf(cur))) {
          if (parentIdStr.equals(readWAttribute(cur, "id"))) {
            // 在 cursor(父 start)后插新 start。moveXml/end 不可用(会动现有节点),用 beginElement 新建
            cur.push();
            cur.toEndToken(); // 父 start 是自闭合/空元素,toEndToken 到其末尾
            cur.beginElement(
                javax.xml.namespace.QName.valueOf(
                    "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}commentRangeStart"));
            cur.insertAttributeWithValue(
                javax.xml.namespace.QName.valueOf(
                    "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}id"),
                replyId.toString());
            cur.pop();
            insertedStart[0] = true;
            break;
          }
        }
        cur.push();
        descendForCommentRangeStart(cur, parentIdStr, replyId, insertedStart);
        cur.pop();
      } while (cur.toNextSibling() && !insertedStart[0]);
    } finally {
      cur.dispose();
    }

    // 第二遍:定位父 commentReference 所在 run,在其后插新 commentRangeEnd + 引用 run
    cur = body.newCursor();
    try {
      if (!cur.toFirstChild()) {
        return;
      }
      boolean[] insertedEnd = {false};
      do {
        if (containsCommentReference(cur, parentIdStr)) {
          // 找到父引用 run,在其后插 commentRangeEnd + 引用 run
          cur.push();
          cur.toEndToken(); // 到 run 末尾
          // 先插 commentRangeEnd
          cur.beginElement(
              javax.xml.namespace.QName.valueOf(
                  "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}commentRangeEnd"));
          cur.insertAttributeWithValue(
              javax.xml.namespace.QName.valueOf(
                  "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}id"),
              replyId.toString());
          cur.toNextSibling(); // 移过刚插的 commentRangeEnd
          // 再插引用 run(<w:r><w:commentReference id=../></w:r>)
          cur.beginElement(
              javax.xml.namespace.QName.valueOf(
                  "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}r"));
          // cursor 在新 r 的 START:标 Document 级 RSID(子任务 4,与 addComment 路径的引用 run 对称)
          stampRsidOnCursor(cur, rsid);
          cur.toFirstChild(); // 进入 r 内
          cur.beginElement(
              javax.xml.namespace.QName.valueOf(
                  "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}commentReference"));
          cur.insertAttributeWithValue(
              javax.xml.namespace.QName.valueOf(
                  "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}id"),
              replyId.toString());
          cur.pop();
          insertedEnd[0] = true;
          break;
        }
        cur.push();
        descendForCommentReference(cur, parentIdStr, replyId, insertedEnd, rsid);
        cur.pop();
      } while (cur.toNextSibling() && !insertedEnd[0]);
    } finally {
      cur.dispose();
    }
  }

  /** 深度优先在子树里找父 commentRangeStart,找到则在其后插新 commentRangeStart。 */
  private static void descendForCommentRangeStart(
      XmlCursor cur, String parentIdStr, BigInteger replyId, boolean[] inserted) {
    if (inserted[0] || !cur.toFirstChild()) {
      return;
    }
    do {
      if ("commentRangeStart".equals(localOf(cur))
          && parentIdStr.equals(readWAttribute(cur, "id"))) {
        cur.push();
        cur.toEndToken();
        cur.beginElement(
            javax.xml.namespace.QName.valueOf(
                "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}commentRangeStart"));
        cur.insertAttributeWithValue(
            javax.xml.namespace.QName.valueOf(
                "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}id"),
            replyId.toString());
        cur.pop();
        inserted[0] = true;
        return;
      }
      cur.push();
      descendForCommentRangeStart(cur, parentIdStr, replyId, inserted);
      cur.pop();
    } while (cur.toNextSibling() && !inserted[0]);
  }

  /** 深度优先在子树里找父 commentReference 所在 run,找到则在其后插新 commentRangeEnd + 引用 run。 */
  private static void descendForCommentReference(
      XmlCursor cur, String parentIdStr, BigInteger replyId, boolean[] inserted, String rsid) {
    if (inserted[0] || !cur.toFirstChild()) {
      return;
    }
    do {
      if (containsCommentReference(cur, parentIdStr)) {
        cur.push();
        cur.toEndToken();
        cur.beginElement(
            javax.xml.namespace.QName.valueOf(
                "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}commentRangeEnd"));
        cur.insertAttributeWithValue(
            javax.xml.namespace.QName.valueOf(
                "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}id"),
            replyId.toString());
        cur.toNextSibling();
        cur.beginElement(
            javax.xml.namespace.QName.valueOf(
                "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}r"));
        // cursor 在新 r 的 START:标 Document 级 RSID(子任务 4)
        stampRsidOnCursor(cur, rsid);
        cur.toFirstChild();
        cur.beginElement(
            javax.xml.namespace.QName.valueOf(
                "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}commentReference"));
        cur.insertAttributeWithValue(
            javax.xml.namespace.QName.valueOf(
                "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}id"),
            replyId.toString());
        cur.pop();
        inserted[0] = true;
        return;
      }
      cur.push();
      descendForCommentReference(cur, parentIdStr, replyId, inserted, rsid);
      cur.pop();
    } while (cur.toNextSibling() && !inserted[0]);
  }

  /** cursor 指向的元素是否为 {@code r}(run)且其内含 {@code commentReference(id=parentIdStr)}。 */
  private static boolean containsCommentReference(XmlCursor cur, String parentIdStr) {
    if (!"r".equals(localOf(cur))) {
      return false;
    }
    cur.push();
    try {
      if (!cur.toFirstChild()) {
        return false;
      }
      do {
        if ("commentReference".equals(localOf(cur))
            && parentIdStr.equals(readWAttribute(cur, "id"))) {
          return true;
        }
      } while (cur.toNextSibling());
      return false;
    } finally {
      cur.pop();
    }
  }

  /**
   * cursor 指向某元素 START 时,给该元素标 {@code w:rsidR} 属性(子任务 4:reply 路径的引用 run 标 RSID)。 防御式:设失败不抛。w 命名空间
   * URI 与 {@link #readWAttribute} 同源。
   */
  private static void stampRsidOnCursor(XmlCursor cur, String rsid) {
    try {
      cur.insertAttributeWithValue(
          javax.xml.namespace.QName.valueOf(
              "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}rsidR"),
          rsid);
    } catch (RuntimeException e) {
      // RSID 设失败不阻断(reply 锚点已建好,批注正文完整;prd R5 防御式)
    }
  }

  /** cursor 当前元素的 local name;cursor 在 END_TOKEN 时返回父元素的(回退一格)。 */
  private static String localOf(XmlCursor cur) {
    org.apache.xmlbeans.XmlCursor.TokenType t = cur.currentTokenType();
    if (t.isStart()) {
      return cur.getName() == null ? "" : cur.getName().getLocalPart();
    }
    return "";
  }

  /**
   * 返回批注内首段的 w14:paraId;无则补一个并返回(否则 paraIdParent 链断)。
   *
   * <p>paraId 生成/设值收敛到 {@link AuthoringInfra}(子任务 4),不再用本类的私有副本。
   */
  private static String ensureCommentParaId(XWPFComment c) {
    String existing = paraIdOfComment(c);
    if (existing != null) {
      return existing;
    }
    // 补一个
    String newParaId = AuthoringInfra.newParaId();
    if (!c.getParagraphs().isEmpty()) {
      AuthoringInfra.setParaId(c.getParagraphs().get(0), newParaId);
    }
    return newParaId;
  }

  // ---------- 线程读侧解析(reply-threads) ----------

  /**
   * 批注线程解析器:把 {@code commentsExtended.xml} 的 {@code w15:paraIdParent} 线索解析为 {@code parentId}(父批注的
   * OOXML {@code w:id}),供 {@link #produceSafe} 注入 {@link Comment}。
   *
   * <p><b>解析链(两步 join)。</b>
   *
   * <ol>
   *   <li>{@link CommentExtendedParts#parseParents} 解析 commentsExtended,得 {@code paraId →
   *       parentParaId}。
   *   <li>批注的 paraId(批注内首段的 {@code w14:paraId})经 paraId→parentParaId 得父 paraId,再反查「父 paraId → 父
   *       comment id」得 {@code parentId}。
   * </ol>
   *
   * <p><b>防御式。</b> 无 commentsExtended、paraId 缺失、join 失败时,paraId/parentId 均为 {@code null}(根批注语义),
   * 不抛——保证畸形/旧文档不破坏读侧。
   */
  static final class ThreadResolver {
    private final Map<String, String> paraIdToParentParaId; // 批注 paraId → 父批注 paraId
    private final Map<String, String> paraIdToCommentId; // 批注 paraId → 本批注 comment id
    private final Map<String, String> commentIdToParaId; // 本批注 comment id → 本批注 paraId

    private ThreadResolver(
        Map<String, String> paraIdToParentParaId,
        Map<String, String> paraIdToCommentId,
        Map<String, String> commentIdToParaId) {
      this.paraIdToParentParaId = paraIdToParentParaId;
      this.paraIdToCommentId = paraIdToCommentId;
      this.commentIdToParaId = commentIdToParaId;
    }

    /** 从 document 构建:解析 commentsExtended + 扫每条批注的 paraId,建三张映射。 */
    static ThreadResolver build(XWPFDocument document, Map<String, XWPFComment> byId) {
      Map<String, String> paraToParent = CommentExtendedParts.parseParents(document);
      Map<String, String> paraToComment = new HashMap<>();
      Map<String, String> commentToPara = new HashMap<>();
      for (Map.Entry<String, XWPFComment> e : byId.entrySet()) {
        String commentId = e.getKey();
        String paraId = paraIdOfComment(e.getValue());
        if (paraId != null) {
          paraToComment.put(paraId, commentId);
          commentToPara.put(commentId, paraId);
        }
      }
      return new ThreadResolver(paraToParent, paraToComment, commentToPara);
    }

    /** 返回批注的 paraId(无则 {@code null})。 */
    String paraIdOf(XWPFComment c) {
      String commentId = commentIdOf(c);
      return commentId == null ? null : commentIdToParaId.get(commentId);
    }

    /**
     * 返回批注的 parentId(父批注 comment id),根批注/无 paraId/join 失败时返回 {@code null}。
     *
     * <p>join 链:本批注 paraId → paraIdToParentParaId 得父 paraId → paraIdToComment 反查得父 comment id。
     */
    String parentIdOf(XWPFComment c) {
      String commentId = commentIdOf(c);
      if (commentId == null) {
        return null;
      }
      String paraId = commentIdToParaId.get(commentId);
      if (paraId == null) {
        return null;
      }
      String parentParaId = paraIdToParentParaId.get(paraId);
      if (parentParaId == null) {
        return null; // 根批注(无 paraIdParent)
      }
      return paraIdToCommentId.get(parentParaId); // 父 paraId → 父 comment id(可能 null:父批注无 paraId)
    }
  }

  /**
   * 读批注内<b>首段落</b>的 {@code w14:paraId} 属性;无(POI createParagraph 不写、或旧文档)返回 {@code null}。
   *
   * <p>批注的 paraId 不在 {@code <w:comment>} 上,而在批注内段落的 {@code <w:p w14:paraId=..>} 上(线程链的 key)。
   */
  private static String paraIdOfComment(XWPFComment c) {
    try {
      List<org.apache.poi.xwpf.usermodel.XWPFParagraph> paras = c.getParagraphs();
      if (paras.isEmpty()) {
        return null;
      }
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP ctp = paras.get(0).getCTP();
      org.apache.xmlbeans.XmlCursor cur = ctp.newCursor();
      try {
        // w14 命名空间:http://schemas.microsoft.com/office/word/2010/wordml
        return cur.getAttributeText(
            javax.xml.namespace.QName.valueOf(
                "{http://schemas.microsoft.com/office/word/2010/wordml}paraId"));
      } finally {
        cur.dispose();
      }
    } catch (RuntimeException e) {
      // 解析失败视为无 paraId
      return null;
    }
  }
}
