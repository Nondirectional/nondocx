package com.non.docx.core.internal.poi;

import com.non.docx.core.api.comment.Comment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.xwpf.usermodel.XWPFComment;
import org.apache.poi.xwpf.usermodel.XWPFComments;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.xmlbeans.XmlCursor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;

/**
 * 内部 API——恕不另行通知即可更改。
 *
 * <p>这是 nondocx 里<b>唯一</b>接触批注 OOXML 结构的地方,因此公有类型 {@link Comment} / {@link
 * com.non.docx.core.api.comment.Comments} 在源代码层面保持无 {@code org.apache.poi.*}(构造函数接缝除外)。
 *
 * <p><b>职责。</b> 提供只读能力:按文档顺序枚举批注,解析为 {@link Comment} 领域视图。
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
 * 两端用同一个 {@code w:id} 配对。{@code comments.xml} 里 {@code <w:comment>} 的顺序是<b>创建顺序</b>,
 * <b>不等于</b> {@code document.xml} 里 {@code commentRangeStart} 的出现顺序(正文顺序)。
 *
 * <p><b>POI 的坑(探针验证见 research/ordering.md)。</b>
 *
 * <ol>
 *   <li>{@code XWPFDocument.getDocComments()} 在文档<b>无任何批注</b>时返回 {@code null}(POI 不自动创建空
 *       part)。本类 null-guard 返回空列表。
 *   <li>{@code XWPFComments.getComments()} 返回 {@code comments.xml} 部件顺序(创建顺序),<b>不等于</b>正文
 *       顺序。本类自己扫 {@code document.xml} 的 {@code commentRangeStart} 重排。
 *   <li>{@code commentRangeStart} 可出现在任意层级——段落内、表格单元格内段落里、嵌套结构里。本类用
 *       {@link XmlCursor} 对 {@code CTBody} 整棵子树做深度优先遍历,按出现顺序收集 {@code commentRangeStart}
 *       的 {@code w:id},保证覆盖所有层级。
 * </ol>
 *
 * <p><b>防御式。</b> 整个 walk 不在本类抛 POI 异常——单个批注解析失败时跳过该条而非整体失败,保证一份文档即便
 * 局部畸形也能尽量给出其余批注。
 */
public final class CommentNodes {

  private CommentNodes() {}

  /**
   * 按文档顺序枚举批注,解析为 {@link Comment} 列表。
   *
   * <p>算法(design §5.4):
   *
   * <ol>
   *   <li>从 {@code getDocComments().getComments()} 建 {@code Map<id 字符串, XWPFComment>}({@code getDocComments()}
   *       为 null 时返回空列表)。
   *   <li>用 {@link XmlCursor} 深度优先遍历 {@code CTBody} 整棵子树,按出现顺序收集 {@code commentRangeStart}
   *       的 {@code w:id}。
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
    // 扫 document.xml 的 commentRangeStart,按出现顺序产出
    CTBody body = document.getDocument().getBody();
    if (body == null) {
      appendOrphans(allByPartOrder, byId, new HashSet<>(), out);
      return out;
    }
    Set<String> seen = new HashSet<>();
    XmlCursor cur = body.newCursor();
    try {
      collectRangeStartIds(cur, byId, seen, out);
    } finally {
      cur.dispose();
    }
    // 孤儿降级:Map 里没被 body 锚点命中的批注,按部件顺序追加末尾
    appendOrphans(allByPartOrder, byId, seen, out);
    return out;
  }

  /**
   * 用 {@link XmlCursor} 深度优先遍历 cursor 所指元素的整棵子树,遇到 {@code commentRangeStart} 就取其
   * {@code w:id}、从 Map 取批注产出。
   *
   * <p>深度优先(而非只扫直接子)是因为 {@code commentRangeStart} 可能在任意层级——段落内、表格单元格内段落里。
   * 用 {@code toFirstChild/toNextSibling} 的递归下降覆盖整棵树,按文档顺序(前序遍历)收集。
   *
   * <p><b>cursor 状态管理(关键)。</b> 递归下钻前用 {@link XmlCursor#push()} 保存当前位置,下钻返回后用
   * {@link XmlCursor#pop()} 恢复——否则 {@code toFirstChild()} 会把 cursor 移到子树深处,外层的
   * {@code toNextSibling()} 会从错误位置继续,漏掉同层后续兄弟。({@code TrackedChangeNodes} 的 walk 不踩这坑
   * 是因为它命中修订节点后不下钻;comments 的 {@code commentRangeStart} 是叶子元素,遍历必须继续走过兄弟。)
   *
   * @param cur 指向子树根的 cursor(调用前已定位;本方法 {@code toFirstChild} 进入第一层子并遍历整棵子树)
   */
  private static void collectRangeStartIds(
      XmlCursor cur, Map<String, XWPFComment> byId, Set<String> seen, List<Comment> out) {
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
            produceSafe(c, out);
          }
        }
      }
      // 下钻子树前 push 保存位置,返回后 pop 恢复,保证 toNextSibling 在正确的兄弟层推进
      cur.push();
      collectRangeStartIds(cur, byId, seen, out);
      cur.pop();
    } while (cur.toNextSibling());
  }

  /**
   * 把 {@code comments.xml} 里有、但 {@code document.xml} 无 {@code commentRangeStart} 锚点的孤儿批注,
   * 按 {@code comments.xml} 部件顺序追加到结果末尾。
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
      List<Comment> out) {
    for (XWPFComment c : allByPartOrder) {
      String id = commentIdOf(c);
      if (id == null || seen.contains(id)) {
        continue;
      }
      seen.add(id);
      produceSafe(c, out);
    }
  }

  /**
   * 读 {@code XWPFComments.getComments()},防御式:POI 抛异常时返回空列表而非整体失败。
   */
  private static List<XWPFComment> readAllSafe(XWPFComments xcomments) {
    try {
      List<XWPFComment> list = xcomments.getComments();
      return list == null ? new ArrayList<>() : new ArrayList<>(list);
    } catch (RuntimeException e) {
      // 读不到批注列表(罕见,如 part 损坏)按「无批注」处理
      return new ArrayList<>();
    }
  }

  /**
   * 把一个 {@link XWPFComment} 包装成 {@link Comment} 加入结果列表,防御式:解析失败时跳过该条。
   */
  private static void produceSafe(XWPFComment c, List<Comment> out) {
    try {
      out.add(new Comment(c));
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
   * <p>OOXML 的批注锚点属性({@code w:id})在 {@code w} 命名空间下,故按带命名空间的 QName 读。与
   * {@code TrackedChangeNodes.readWAttribute} 同套路,但缺失时返回 {@code null} 以区分「无值」与「空串」。
   */
  private static String readWAttribute(XmlCursor cur, String localName) {
    return cur.getAttributeText(
        javax.xml.namespace.QName.valueOf(
            "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}" + localName));
  }
}
