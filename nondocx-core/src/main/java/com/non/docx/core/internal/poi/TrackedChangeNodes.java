package com.non.docx.core.internal.poi;

import com.non.docx.core.api.track.CellChangeDetails;
import com.non.docx.core.api.track.CellChangeKind;
import com.non.docx.core.api.track.ChangeDetails;
import com.non.docx.core.api.track.PropertyChangeDetails;
import com.non.docx.core.api.track.PropertyChangeKind;
import com.non.docx.core.api.track.TextChangeDetails;
import com.non.docx.core.api.track.TrackedChange;
import com.non.docx.core.api.track.TrackedChangeLocation;
import com.non.docx.core.api.track.TrackedChangeSegment;
import com.non.docx.core.api.track.TrackedChangeSegmentKind;
import com.non.docx.core.api.track.TrackedChangeType;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.xmlbeans.XmlCursor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRunTrackChange;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText;

/**
 * 内部 API——恕不另行通知即可更改。
 *
 * <p>这是 nondocx 里<b>唯一</b>接触 tracked changes 的 OOXML 结构的地方,因此公有类型 {@link TrackedChange} / {@link
 * com.non.docx.core.api.track.TrackedChanges} 在源代码层面保持无 {@code org.apache.poi.*}。
 *
 * <p><b>职责。</b> 提供只读能力与文本类破坏性写能力:
 *
 * <ol>
 *   <li>{@link #isEnabled(XWPFDocument)} —— 读取 {@code settings.xml} 的 {@code <w:trackChanges/>} 开关。
 *   <li>{@link #collect(XWPFDocument)} —— 按文档顺序枚举正文里的修订节点,解析为 {@link TrackedChange} 领域视图。
 *   <li>{@link #acceptText(CTRunTrackChange)} / {@link #rejectText(CTRunTrackChange)} ——
 *       对文本类修订做破坏性应用或撤销。
 *   <li>{@link #addInsertion} / {@link #addDeletion} / {@link #nextRevisionId} —— 创作文本类 tracked
 *       修订节点。
 * </ol>
 *
 * <p><b>OOXML 结构(教学要点)。</b> 修订标记是带属性的容器元素:
 *
 * <pre>{@code
 * <w:p>
 *   <w:ins w:id="1" w:author="non" w:date="2026-06-18T10:00:00Z">
 *     <w:r><w:t>新增文本</w:t></w:r>
 *   </w:ins>
 *   <w:del w:id="2" w:author="non" w:date="...">
 *     <w:r><w:delText>被删文本</w:delText></w:r>     ← 注意是 delText,不是 t
 *   </w:del>
 * </w:p>
 * }</pre>
 *
 * 四种文本/移动类元素 {@code <w:ins>} / {@code <w:del>} / {@code <w:moveFrom>} / {@code <w:moveTo>} 在精简
 * schema 下统一由 {@link CTRunTrackChange} 承载——它们共享同一 Java 类型,区别只在 OOXML 元素本地名。
 *
 * <p><b>POI 的坑(都得绕过)。</b>
 *
 * <ol>
 *   <li>POI 没有 {@code XWPFTrackedChanges} 高级 API,也没有遍历修订的现成方法。本类用 {@link XmlCursor} 按文档顺序 遍历 {@code
 *       CTBody} 子树(与 {@code TocFields} 同一套路),对每个节点用<b>本地名</b>判断是否修订标记。
 *   <li>修订标记可出现在<b>任意层级</b>:段落内、表格行/单元格内、甚至嵌套在其它修订里(罕见,先不展开)。本类对 body → 段落/表格 → 行 → 单元格 → 段落
 *       这条主干做深度遍历,沿途累计 location path,命中修订节点即产出。
 * </ol>
 *
 * <p><b>当前范围。</b> 稳定覆盖 {@code ins} / {@code del} / {@code moveFrom} / {@code moveTo} 四种(文本/移动类)。
 * 属性类({@code *PrChange})与单元格类({@code cellIns} / {@code cellDel})的完整建模留给 {@code advanced-types} 子任务;
 * 若遍历中遇到它们,目前<b>跳过</b>(不产出),避免过度承诺。
 *
 * <p><b>防御式。</b> 整个 walk 不在本类抛 POI 异常——单个节点解析失败时跳过该节点而非整体失败,保证一份文档即便局部 畸形也能尽量给出其余修订。
 */
public final class TrackedChangeNodes {

  /** 文本/移动类修订标记的 OOXML 本地名 → 细粒度 type。 */
  private static final java.util.Map<String, TrackedChangeType> TEXT_LOCAL_NAMES = textLocalNames();

  private TrackedChangeNodes() {}

  /**
   * 读取文档是否开启修订记录。
   *
   * <p>读 {@code settings.xml} 的 {@code <w:trackChanges/>}:POI 把它暴露成 {@code
   * XWPFSettings.isTrackRevisions()}。存在该元素且值为真即视为开启;缺失或为假即视为未开启。
   *
   * @param document POI 文档(不能为 {@code null})
   * @return {@code true} 表示开启修订记录;读不到 settings 或开关未置时返回 {@code false}
   */
  public static boolean isEnabled(XWPFDocument document) {
    try {
      return document.getSettings().isTrackRevisions();
    } catch (RuntimeException e) {
      // 读不到 settings(罕见,如 part 缺失或损坏)按「未开启」处理,保证只读语义永不抛异常。
      return false;
    }
  }

  /**
   * 按文档顺序枚举正文里的修订节点,解析为 {@link TrackedChange} 列表。
   *
   * <p>从 {@code CTBody} 出发用 {@link XmlCursor} 深度遍历主干结构,沿途累计 location path(body → paragraph/table →
   * row → cell → paragraph → ...),命中 {@code ins}/{@code del}/{@code moveFrom}/{@code moveTo} 即产出一条
   * {@link TrackedChange}。顺序严格按文档出现顺序。
   *
   * <p>稳定 id 由 {@link #buildId} 生成(混合版:type + location + 原始 w:id),进程内稳定。
   *
   * @param document POI 文档(不能为 {@code null})
   * @return 按文档顺序排列的修订列表(可能为空;从不为 {@code null})
   */
  public static List<TrackedChange> collect(XWPFDocument document) {
    List<TrackedChange> out = new ArrayList<>();
    CTBody body = document.getDocument().getBody();
    if (body == null) {
      return out;
    }
    List<TrackedChangeSegment> path = new ArrayList<>();
    path.add(new TrackedChangeSegment(TrackedChangeSegmentKind.BODY, 0));
    walkBody(body, path, out);
    return out;
  }

  // ---------- 破坏性写:accept / reject 文本类 ----------

  /**
   * 应用(accept)一条文本类修订,使其成为正文永久内容或被永久删除。
   *
   * <p>语义:
   *
   * <ul>
   *   <li>{@code ins} / {@code moveTo}:拆除包装,保留内部 run(插入生效)。
   *   <li>{@code del} / {@code moveFrom}:移除整个包装子树(删除生效)。
   * </ul>
   *
   * <p>调用方负责在调用前确认 {@code node} 的 type 属于本子任务范围(文本类);否则应改抛 {@code
   * UnsupportedFeatureException},而不是调用本方法。
   *
   * @param node 要 accept 的修订节点(不能为 {@code null})
   */
  public static void acceptText(CTRunTrackChange node) {
    java.util.Objects.requireNonNull(node, "node");
    String local = localName(node);
    if ("ins".equals(local) || "moveTo".equals(local)) {
      unwrapRunsAndRemove(node);
    } else {
      // del / moveFrom:accept 即删除生效,整个包装移除
      removeNode(node);
    }
  }

  /**
   * 拒绝(reject)一条文本类修订,撤销该次变更。
   *
   * <p>语义:
   *
   * <ul>
   *   <li>{@code ins} / {@code moveTo}:移除整个包装子树(插入被撤销)。
   *   <li>{@code del} / {@code moveFrom}:拆除包装,并把内部 {@code delText} 恢复为普通 {@code t}(删除被撤销,原文本回正文)。
   * </ul>
   *
   * @param node 要 reject 的修订节点(不能为 {@code null})
   */
  public static void rejectText(CTRunTrackChange node) {
    java.util.Objects.requireNonNull(node, "node");
    String local = localName(node);
    if ("ins".equals(local) || "moveTo".equals(local)) {
      removeNode(node);
    } else {
      // del / moveFrom:reject 即删除被撤销,恢复正文
      restoreDelTextToT(node);
      unwrapRunsAndRemove(node);
    }
  }

  /** 返回修订节点的 OOXML 本地名(ins / del / moveFrom / moveTo)。 */
  private static String localName(CTRunTrackChange node) {
    XmlCursor c = node.newCursor();
    try {
      return c.getName().getLocalPart();
    } finally {
      c.dispose();
    }
  }

  /** 删除整个节点(连同其子树)。用于 reject ins / accept del。 */
  private static void removeNode(CTRunTrackChange node) {
    XmlCursor c = node.newCursor();
    try {
      c.removeXml();
    } finally {
      c.dispose();
    }
  }

  /**
   * 把包装内的 {@code <w:r>} 移到包装之前(即挂回父级),再删除空的包装。
   *
   * <p>用于 accept ins / accept moveTo。{@code moveXml(anchor)} 把 cursor 指向的节点移到 {@code anchor} 之前,并 让
   * cursor 自动指向下一个兄弟(因此 {@code toNextSibling()} 继续推进而不重复)。
   */
  private static void unwrapRunsAndRemove(CTRunTrackChange wrapper) {
    XmlCursor anchor = wrapper.newCursor();
    try {
      XmlCursor child = wrapper.newCursor();
      try {
        if (child.toFirstChild()) {
          do {
            if ("r".equals(child.getName().getLocalPart())) {
              child.moveXml(anchor);
            }
          } while (child.toNextSibling());
        }
      } finally {
        child.dispose();
      }
      anchor.removeXml();
    } finally {
      anchor.dispose();
    }
  }

  /**
   * 把节点内各 run 的 {@code <w:delText>} 恢复为普通 {@code <w:t>}(删除被撤销的文本回到正文)。
   *
   * <p>用于 reject del / reject moveFrom。{@code delText} 与 {@code t} 在 OOXML 里是不同元素(本地名 {@code
   * delText} vs {@code t}),仅靠 moveXml 无法完成类型转换,因此先读出文本、删旧 {@code delText}、再新建 {@code t}。
   */
  private static void restoreDelTextToT(CTRunTrackChange wrapper) {
    for (CTR r : wrapper.getRList()) {
      for (int i = r.sizeOfDelTextArray() - 1; i >= 0; i--) {
        String s = r.getDelTextArray(i).getStringValue();
        r.removeDelText(i);
        CTText t = r.addNewT();
        t.setStringValue(s == null ? "" : s);
      }
    }
  }

  // ---------- 破坏性写:文本类 authoring ----------

  /**
   * 在段落末尾创建一条 tracked insertion(插入)修订,内含一个承载 {@code text} 的 run。
   *
   * <p>OOXML 形态:在 {@code <w:p>} 下新建 {@code <w:ins w:id=.. w:author=..
   * w:date=..><w:r><w:t>text</w:t></w:r></w:ins>}。
   *
   * <p>返回新建的 {@code <w:ins>} 节点;调用方通过 {@link CTRunTrackChange#getRList()} 取其内部 run(再用 {@code new
   * XWPFRun(ctr, paragraph)} 包成 POI run——POI 的 {@code XWPFParagraph.getRuns()} <b>不</b>暴露 ins 内的
   * run,见 N14)。
   *
   * <p>新 run 的 {@code <w:r>} 是「普通新 run」起步(无样式);若需要复制外部 run 样式,由调用方在拿到 run 后自行处理。
   *
   * @param paragraph 目标段落的 POI 句柄(不能为 {@code null})
   * @param text 插入的文本(不能为 {@code null})
   * @param author 修订作者(不能为 {@code null})
   * @param date 修订时间(不能为 {@code null})
   * @param revisionId 底层 OOXML {@code w:id}(不能为 {@code null})
   * @return 新建的 {@code <w:ins>} 节点
   */
  public static CTRunTrackChange addInsertion(
      org.apache.poi.xwpf.usermodel.XWPFParagraph paragraph,
      String text,
      String author,
      java.util.Calendar date,
      java.math.BigInteger revisionId) {
    java.util.Objects.requireNonNull(paragraph, "paragraph");
    java.util.Objects.requireNonNull(text, "text");
    java.util.Objects.requireNonNull(author, "author");
    java.util.Objects.requireNonNull(date, "date");
    java.util.Objects.requireNonNull(revisionId, "revisionId");
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP p = paragraph.getCTP();
    CTRunTrackChange ins = p.addNewIns();
    ins.setId(revisionId);
    ins.setAuthor(author);
    ins.setDate(date);
    CTR r = ins.addNewR();
    CTText t = r.addNewT();
    t.setStringValue(text);
    return ins;
  }

  /**
   * 把段落中一个<b>已有</b>的普通 run 显式标记为 tracked deletion(删除)。
   *
   * <p>OOXML 形态:在 {@code <w:p>} 下新建 {@code <w:del>},把目标 run 的 {@code <w:t>} 转成 {@code
   * <w:delText>}(被删文本才用 delText,见 N12),并把该 {@code <w:r>} 从段落直接子位置迁入 {@code <w:del>} 内部。
   *
   * <p>迁移用 {@link XmlCursor}:把 {@code <w:del>} cursor 下移到结束 token({@code toEndToken} 指向其内部末尾),再
   * {@code moveXml} 目标 run 至该位置——这样 run 成为 {@code <w:del>} 的子节点。详见 N14。
   *
   * @param paragraph 目标 run 所在段落的 POI 句柄(不能为 {@code null})
   * @param targetRun 要标记删除的目标 run 的底层 {@code CTR}(不能为 {@code null})
   * @param author 修订作者(不能为 {@code null})
   * @param date 修订时间(不能为 {@code null})
   * @param revisionId 底层 OOXML {@code w:id}(不能为 {@code null})
   * @return 新建的 {@code <w:del>} 节点
   */
  public static CTRunTrackChange addDeletion(
      org.apache.poi.xwpf.usermodel.XWPFParagraph paragraph,
      CTR targetRun,
      String author,
      java.util.Calendar date,
      java.math.BigInteger revisionId) {
    java.util.Objects.requireNonNull(paragraph, "paragraph");
    java.util.Objects.requireNonNull(targetRun, "targetRun");
    java.util.Objects.requireNonNull(author, "author");
    java.util.Objects.requireNonNull(date, "date");
    java.util.Objects.requireNonNull(revisionId, "revisionId");
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP p = paragraph.getCTP();
    // 先建 <w:del>,再把目标 run 迁入(注意:迁入前目标 run 仍在段落直接子位置)
    CTRunTrackChange del = p.addNewDel();
    del.setId(revisionId);
    del.setAuthor(author);
    del.setDate(date);
    // t → delText(被删文本用 delText)
    for (int i = targetRun.sizeOfTArray() - 1; i >= 0; i--) {
      String s = targetRun.getTArray(i).getStringValue();
      targetRun.removeT(i);
      targetRun.addNewDelText().setStringValue(s == null ? "" : s);
    }
    // 把目标 run 迁入 <w:del> 内部末尾
    XmlCursor delCur = del.newCursor();
    XmlCursor runCur = targetRun.newCursor();
    try {
      delCur.toEndToken();
      runCur.moveXml(delCur);
    } finally {
      delCur.dispose();
      runCur.dispose();
    }
    return del;
  }

  /**
   * 计算文档下一个可用的修订 {@code w:id}(扫描已有 {@code ins}/{@code del} 等的 {@code w:id},取最大值 +1;无任何修订时返回 0)。
   *
   * <p>这是底层 OOXML 修订 id,与 read 子任务对外的 nondocx 稳定 id <b>不是</b>同一概念(见 design §5.3)。
   *
   * @param document POI 文档(不能为 {@code null})
   * @return 下一个可用的 {@code w:id}
   */
  public static java.math.BigInteger nextRevisionId(XWPFDocument document) {
    java.util.Objects.requireNonNull(document, "document");
    long max = -1;
    for (TrackedChange c : collect(document)) {
      java.math.BigInteger id = c.raw().getId();
      if (id != null) {
        long v = id.longValue();
        if (v > max) {
          max = v;
        }
      }
    }
    return java.math.BigInteger.valueOf(max + 1);
  }

  // ---------- body 遍历 ----------

  /**
   * 遍历 {@code CTBody} 的直接子,维护从 body 起的 path 索引。
   *
   * <p>body 的直接子主要是 {@code <w:p>}(段落)、{@code <w:tbl>}(表格)、{@code <w:sdt>}(内容控件,先不下钻,留给后续)。
   * 每种子结构维护各自层级的 0-based 索引计数。
   */
  private static void walkBody(
      CTBody body, List<TrackedChangeSegment> path, List<TrackedChange> out) {
    XmlCursor cur = body.newCursor();
    try {
      if (!cur.toFirstChild()) {
        return;
      }
      int paragraphIdx = 0;
      int tableIdx = 0;
      do {
        String local = cur.getName().getLocalPart();
        if ("p".equals(local)) {
          walkParagraph(cur, path, paragraphIdx, out);
          paragraphIdx++;
        } else if ("tbl".equals(local)
            && cur.getObject()
                instanceof org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTbl) {
          walkTable(cur.getObject(), path, tableIdx, out);
          tableIdx++;
        }
        // 其它 body 直接子(sdt 等)暂不下钻。
      } while (cur.toNextSibling());
    } finally {
      cur.dispose();
    }
  }

  /**
   * 遍历一个段落:先在该段落层级产出段落直属的修订节点(如直接挂在 {@code <w:p>} 下的 {@code <w:ins>}), 再下钻 run 层级(修订也可能包裹在段落直属 run
   * 序列里,但 run 层修订通常是 {@code <w:r>} 的兄弟, 已被段落直属遍历覆盖;这里为稳妥仍尝试 run 层)。
   *
   * <p>同时下钻两类属性树枚举属性类修订(见 research/ooxml-forms.md §1.2):
   *
   * <ul>
   *   <li>段落自身的 {@code <w:pPr>} 内的 {@code pPrChange}。
   *   <li>段落直属每个 {@code <w:r>} 的 {@code <w:rPr>} 内的 {@code rPrChange}。
   * </ul>
   */
  private static void walkParagraph(
      XmlCursor parent,
      List<TrackedChangeSegment> path,
      int paragraphIdx,
      List<TrackedChange> out) {
    List<TrackedChangeSegment> segs =
        withSegment(path, TrackedChangeSegmentKind.PARAGRAPH, paragraphIdx);
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP p =
        (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP) parent.getObject();
    // 先枚举段落直属的文本/移动类修订节点。
    XmlCursor cur = p.newCursor();
    try {
      if (!cur.toFirstChild()) {
        return;
      }
      do {
        String local = cur.getName().getLocalPart();
        if (TEXT_LOCAL_NAMES.containsKey(local) && cur.getObject() instanceof CTRunTrackChange) {
          produce((CTRunTrackChange) cur.getObject(), local, segs, out);
        }
      } while (cur.toNextSibling());
    } finally {
      cur.dispose();
    }
    // 再下钻属性树,枚举属性类修订。
    collectPropertyChanges(p, segs, out);
  }

  /**
   * 在一个段落里枚举属性类修订(rPrChange / pPrChange)。
   *
   * <p>结构(见 research/ooxml-forms.md §1.2):{@code pPrChange} 装在段落的 {@code <w:pPr>} 内;{@code
   * rPrChange} 装在段落直属各 run 的 {@code <w:rPr>} 内。属性类节点类型是 {@code CTRPrChange} / {@code
   * CTPPrChange},都继承自 {@code CTTrackChange}(共同父),故产出时按 {@code CTTrackChange} 委托。
   */
  private static void collectPropertyChanges(
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP p,
      List<TrackedChangeSegment> segs,
      List<TrackedChange> out) {
    // 运行属性:每个直属 run 的 rPr → rPrChange
    // (段落属性 pPrChange 的 CT 类型 CTPPrChange 在 POI 精简 schema 下尚未被引用、暂不在 classpath,
    //  故本子任务 v1 仅覆盖 rPrChange;pPrChange 留作已知边界,见 research/ooxml-forms.md。)
    for (CTR r : p.getRList()) {
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr rPr = r.getRPr();
      if (rPr != null && rPr.isSetRPrChange()) {
        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPrChange change =
            rPr.getRPrChange();
        produceProperty(
            change,
            "rPrChange",
            TrackedChangeType.RPR_CHANGE,
            PropertyChangeKind.RUN_PROPERTIES,
            summarizeChildren(rPr),
            summarizeChildren(change.getRPr()),
            segs,
            out);
      }
    }
  }

  /**
   * 产出一个属性类修订 {@link TrackedChange}。
   *
   * <p>属性类节点(具体是 {@code CTRPrChange} / {@code CTPPrChange},共同父 {@code CTTrackChange})走 {@link
   * TrackedChange} 的属性构造函数; {@code newSummary}/{@code oldSummary} 来自新旧属性树的直接子本地名摘要。
   */
  private static void produceProperty(
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTrackChange node,
      String local,
      TrackedChangeType type,
      PropertyChangeKind kind,
      String newSummary,
      String oldSummary,
      List<TrackedChangeSegment> path,
      List<TrackedChange> out) {
    TrackedChangeLocation location = new TrackedChangeLocation(path);
    ChangeDetails details = new PropertyChangeDetails(kind, newSummary, oldSummary);
    String id = buildPropertyId(type, location, node, local);
    out.add(new TrackedChange(id, type, location, details, node));
  }

  // ---------- 破坏性写:属性类 accept / reject ----------

  /**
   * 应用(accept)一条属性类修订:保留<b>新(当前)属性树</b>,移除 {@code *PrChange} 标记。
   *
   * <p>OOXML 形态(以 rPrChange 为例):外层 {@code <w:rPr>} 表达新样式,{@code <w:rPrChange>} 内的 {@code <w:rPr>}
   * 表达旧样式。accept = 删 {@code <w:rPrChange>} 节点(外层 rPr 的新样式保留不变)。
   *
   * @param node 属性类修订节点(具体是 {@code CTRPrChange};共同父 {@code CTTrackChange}。不能为 {@code null})
   */
  public static void acceptProperty(
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTrackChange node) {
    java.util.Objects.requireNonNull(node, "node");
    XmlCursor c = node.newCursor();
    try {
      c.removeXml();
    } finally {
      c.dispose();
    }
  }

  /**
   * 撤销(reject)一条属性类修订:用 {@code *PrChange} 内的<b>旧(pristine)属性树</b>覆盖外层新树,再移除 {@code *PrChange} 标记。
   *
   * <p>OOXML 形态:把 {@code <w:rPrChange>} 内旧 {@code <w:rPr>} 的全部直接子,搬到外层 {@code <w:rPr>}(替换新样式),再删
   * {@code <w:rPrChange>}。若旧属性树为空,则外层 rPr 也将被清空(即撤销成"无属性")。
   *
   * <p>注意:本方法先把外层属性树的<b>现有直接子</b>(不含 {@code *PrChange} 本身)全部移除,再把旧属性树的子搬入,实现"整树替换"(design §5.2)。
   *
   * @param node 属性类修订节点(不能为 {@code null})
   */
  public static void rejectProperty(
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTrackChange node) {
    java.util.Objects.requireNonNull(node, "node");
    // node 是 rPrChange;其父是外层 rPr;node 内第一个子是旧属性元素(rPr,类型为 CTRPrOriginal,与 CTRPr 不同)。
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPrChange asRPrChange =
        (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPrChange) node;
    // 外层 rPr 是 *PrChange 的父
    XmlCursor parentCur = node.newCursor();
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr outerRPr;
    try {
      parentCur.toParent();
      outerRPr =
          (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr) parentCur.getObject();
    } finally {
      parentCur.dispose();
    }
    // 清空外层 rPr 的现有直接子(除 rPrChange 外),为新旧树替换腾位
    XmlCursor outerCur = outerRPr.newCursor();
    try {
      if (outerCur.toFirstChild()) {
        do {
          if (!"rPrChange".equals(outerCur.getName().getLocalPart())) {
            outerCur.removeXml();
          }
        } while (outerCur.toNextSibling());
      }
    } finally {
      outerCur.dispose();
    }
    // 把旧 rPr(CTRPrOriginal,与 CTRPr 不同类型,故走 XmlCursor 通用搬运)的直接子搬入外层 rPr
    XmlCursor oldCur = asRPrChange.newCursor();
    XmlCursor anchor = outerRPr.newCursor();
    try {
      // 下钻到 rPrChange 内的旧 rPr(第一个子)
      if (oldCur.toFirstChild()) {
        do {
          oldCur.moveXml(anchor); // 搬到外层 rPr 内部末尾
        } while (oldCur.toNextSibling());
      }
    } finally {
      oldCur.dispose();
      anchor.dispose();
    }
    // 删 *PrChange 标记
    XmlCursor c = node.newCursor();
    try {
      c.removeXml();
    } finally {
      c.dispose();
    }
  }

  // ---------- 破坏性写:单元格结构类 accept / reject ----------

  /**
   * 应用(accept)一条单元格结构类修订({@code cellIns} / {@code cellDel}),使单元格的存亡修订生效。
   *
   * <p><b>OOXML 语义(见 research/cell-forms.md §3)</b>:{@code cellIns}/{@code cellDel} 标记的是「单元格本身被
   * 插入/删除」,accept 即让该存亡生效:
   *
   * <ul>
   *   <li>{@code cellIns}(单元格被插入)accept:<b>保留整个 {@code <w:tc>}</b>(插入生效),仅删 {@code cellIns} 标记。
   *   <li>{@code cellDel}(单元格被删除)accept:<b>移除整个 {@code <w:tc>}</b>(删除生效),标记随之而去。
   * </ul>
   *
   * <p><b>与文本类 / 属性类 accept 的本质差异</b>:文本类 ins 的 accept 操作 run(unwrap),属性类 rPrChange 的 accept 操作
   * 属性子树(删标记);而 cell 类的 accept 操作<b>整个 {@code <w:tc>} 祖父节点</b>。这是 cell 修订独有的结构语义,误当文本
   * 类处理会写出「本应删除却仍存在」的单元格(advanced-types research 点名的最高风险点)。
   *
   * @param node 单元格结构类修订节点({@code CTTrackChange},来自 {@code CTTcPr.getCellIns()/getCellDel()};不能为
   *     {@code null})
   * @param type 修订 kind({@link TrackedChangeType#CELL_INS CELL_INS} 或 {@link
   *     TrackedChangeType#CELL_DEL CELL_DEL})
   */
  public static void acceptCell(
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTrackChange node,
      TrackedChangeType type) {
    java.util.Objects.requireNonNull(node, "node");
    java.util.Objects.requireNonNull(type, "type");
    if (type == TrackedChangeType.CELL_INS) {
      // 保留 tc、删标记
      removeCellMarker(node);
    } else {
      // cellDel:移除整个 tc(删除生效)
      removeCellNode(node);
    }
  }

  /**
   * 撤销(reject)一条单元格结构类修订,使单元格回到修订前的存亡状态。
   *
   * <p>语义(与 accept 对称):
   *
   * <ul>
   *   <li>{@code cellIns}(单元格被插入)reject:<b>移除整个 {@code <w:tc>}</b>(插入被撤销,单元格本不该存在)。
   *   <li>{@code cellDel}(单元格被删除)reject:<b>保留整个 {@code <w:tc>}</b>(删除被撤销,单元格恢复),仅删 {@code cellDel}
   *       标记。
   * </ul>
   *
   * @param node 单元格结构类修订节点(不能为 {@code null})
   * @param type 修订 kind({@link TrackedChangeType#CELL_INS CELL_INS} 或 {@link
   *     TrackedChangeType#CELL_DEL CELL_DEL})
   */
  public static void rejectCell(
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTrackChange node,
      TrackedChangeType type) {
    java.util.Objects.requireNonNull(node, "node");
    java.util.Objects.requireNonNull(type, "type");
    if (type == TrackedChangeType.CELL_INS) {
      // 插入被撤销:移除整个 tc
      removeCellNode(node);
    } else {
      // cellDel:删除被撤销,保留 tc、删标记
      removeCellMarker(node);
    }
  }

  /**
   * 从一个 {@code cellIns}/{@code cellDel} 节点提升到祖父 {@code <w:tc>},返回指向 {@code tc} 的 cursor。
   *
   * <p>结构(见 research/cell-forms.md §3.2 探针验证):{@code cellIns}/{@code cellDel} 嵌在 {@code <w:tcPr>}
   * 内,故 {@code toParent()}×2 准确到达 {@code <w:tc>}(探针确认祖父本地名为 {@code tc},不是 {@code tr} 或 {@code
   * tcPr})。
   *
   * <p>防御式:若父链异常(如节点直接挂在 {@code tc} 而非 {@code tcPr},畸形文档),返回的 cursor 指向的本地名不是 {@code tc}
   * 时,调用方应跳过而非强删(避免误删错误层级)。
   */
  private static XmlCursor cursorToCell(
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTrackChange node) {
    XmlCursor cur = node.newCursor();
    cur.toParent(); // -> tcPr
    cur.toParent(); // -> tc
    return cur;
  }

  /** 删 {@code cellIns}/{@code cellDel} 标记本身(保留 {@code tc})。用于 accept cellIns / reject cellDel。 */
  private static void removeCellMarker(
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTrackChange node) {
    XmlCursor c = node.newCursor();
    try {
      c.removeXml();
    } finally {
      c.dispose();
    }
  }

  /**
   * 移除整个 {@code <w:tc>}(含其内段落/run/标记)。用于 reject cellIns / accept cellDel。
   *
   * <p>先 {@link #cursorToCell} 提升到 {@code tc};若祖父本地名不是 {@code tc}(父链异常,畸形文档),抛 {@link
   * com.non.docx.core.api.exception.DocxOperationException} 提示结构异常,不静默删错层级。
   */
  private static void removeCellNode(
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTrackChange node) {
    XmlCursor c = cursorToCell(node);
    try {
      String local = c.getName() == null ? "" : c.getName().getLocalPart();
      if (!"tc".equals(local)) {
        throw new com.non.docx.core.api.exception.DocxOperationException(
            "单元格结构类修订的祖父节点应为 <w:tc>,实际为 <w:" + local + ">(文档结构异常,拒绝执行删除)");
      }
      c.removeXml();
    } finally {
      c.dispose();
    }
  }

  /** 收集一个属性元素(rPr/pPr)直接子的本地名,排序拼成摘要(见 {@link PropertyChangeDetails#summarize})。 */
  private static String summarizeChildren(org.apache.xmlbeans.XmlObject props) {
    if (props == null) {
      return PropertyChangeDetails.summarize(java.util.Collections.emptyList());
    }
    java.util.List<String> names = new java.util.ArrayList<>();
    XmlCursor c = props.newCursor();
    try {
      if (c.toFirstChild()) {
        do {
          // 跳过 *PrChange 自身(它出现在 rPr/pPr 的子里,但不是"属性",是修订标记)
          String local = c.getName().getLocalPart();
          if (!local.endsWith("PrChange")) {
            names.add(local);
          }
        } while (c.toNextSibling());
      }
    } finally {
      c.dispose();
    }
    return PropertyChangeDetails.summarize(names);
  }

  /** 遍历表格:下钻到 {@code <w:tr>}(行)。 */
  private static void walkTable(
      Object tbl, List<TrackedChangeSegment> path, int tableIdx, List<TrackedChange> out) {
    List<TrackedChangeSegment> segs = withSegment(path, TrackedChangeSegmentKind.TABLE, tableIdx);
    XmlCursor cur =
        ((org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTbl) tbl).newCursor();
    try {
      if (!cur.toFirstChild()) {
        return;
      }
      int rowIdx = 0;
      do {
        if ("tr".equals(cur.getName().getLocalPart())
            && cur.getObject()
                instanceof org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRow) {
          walkRow(cur.getObject(), segs, rowIdx, out);
          rowIdx++;
        }
      } while (cur.toNextSibling());
    } finally {
      cur.dispose();
    }
  }

  /** 遍历行:下钻到 {@code <w:tc>}(单元格)。 */
  private static void walkRow(
      Object row, List<TrackedChangeSegment> path, int rowIdx, List<TrackedChange> out) {
    List<TrackedChangeSegment> segs = withSegment(path, TrackedChangeSegmentKind.ROW, rowIdx);
    XmlCursor cur =
        ((org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRow) row).newCursor();
    try {
      if (!cur.toFirstChild()) {
        return;
      }
      int cellIdx = 0;
      do {
        if ("tc".equals(cur.getName().getLocalPart())
            && cur.getObject()
                instanceof org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc) {
          walkCell(cur.getObject(), segs, cellIdx, out);
          cellIdx++;
        }
      } while (cur.toNextSibling());
    } finally {
      cur.dispose();
    }
  }

  /**
   * 遍历单元格:先下钻 {@code <w:tcPr>}(单元格属性)枚举单元格结构类修订({@code cellIns}/{@code cellDel}/{@code
   * cellMerge}),再下钻其内的 {@code <w:p>}(单元格内段落)走段落遍历。
   *
   * <p><b>OOXML 结构(见 research/cell-forms.md §2)</b>:单元格结构类修订嵌在 {@code <w:tcPr>} 内,是裸属性元素(只有
   * id/author/date,无 run、无文本),标记「这个单元格本身是被插入/删除/合并的」(表格结构修订)。
   *
   * <p><b>location path 不含 {@code paragraph} segment</b>:cell 结构修订挂在 {@code tcPr},比单元格内段落高一层,故产出时
   * path 停在 {@code [BODY, TABLE, ROW, CELL]}(与单元格内文本类修订的 {@code [..., CELL, PARAGRAPH]} 区分)。
   *
   * <p><b>cellMerge 的只读路径</b>:其 CT 类型 {@code CTCellMergeTrackChange} 在 POI 精简 schema
   * 下编译期不可达(dangling reference,见 research/cell-forms.md §1),typed 访问器 {@code getCellMerge()} 连
   * javac 都过不了,故 cellMerge 走 XmlCursor 在 {@code tcPr} 子里按本地名探测,只读出「存在一次未确认的合并」,不持可写委托。
   */
  private static void walkCell(
      Object cell, List<TrackedChangeSegment> path, int cellIdx, List<TrackedChange> out) {
    List<TrackedChangeSegment> segs = withSegment(path, TrackedChangeSegmentKind.CELL, cellIdx);
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc tc =
        (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc) cell;
    // 先下钻 tcPr 读单元格结构类修订(cellIns/cellDel/cellMerge)。
    collectCellChanges(tc, segs, out);
    // 再下钻单元格内的段落(单元格内文本类/属性类修订)。
    XmlCursor cur = tc.newCursor();
    try {
      if (!cur.toFirstChild()) {
        return;
      }
      int paragraphIdx = 0;
      do {
        if ("p".equals(cur.getName().getLocalPart())) {
          walkParagraph(cur, segs, paragraphIdx, out);
          paragraphIdx++;
        }
      } while (cur.toNextSibling());
    } finally {
      cur.dispose();
    }
  }

  /**
   * 在一个单元格里枚举单元格结构类修订({@code cellIns} / {@code cellDel} / {@code cellMerge})。
   *
   * <p>结构(见 research/cell-forms.md):三种都嵌在 {@code <w:tcPr>} 内。{@code cellIns}/{@code cellDel} 的节点类型是
   * {@code CTTrackChange}(与 property 类同委托),走 typed 访问器 {@code getCellIns()}/{@code getCellDel()};
   * {@code cellMerge} 的 CT 类型 {@code CTCellMergeTrackChange} 编译期不可达,走 XmlCursor 按本地名探测。
   *
   * <p>防御式:若 {@code tcPr} 缺失或解析失败,跳过该 cell 的结构类枚举,不影响其余修订产出。
   */
  private static void collectCellChanges(
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc tc,
      List<TrackedChangeSegment> segs,
      List<TrackedChange> out) {
    if (!tc.isSetTcPr()) {
      return;
    }
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr tcPr = tc.getTcPr();
    // cellIns / cellDel:typed 访问器,CTTrackChange 委托(复用 property 类的产出路径)。
    if (tcPr.isSetCellIns()) {
      produceCell(tcPr.getCellIns(), "cellIns", TrackedChangeType.CELL_INS, segs, out);
    }
    if (tcPr.isSetCellDel()) {
      produceCell(tcPr.getCellDel(), "cellDel", TrackedChangeType.CELL_DEL, segs, out);
    }
    // cellMerge:CT 类型编译期不可达,走 XmlCursor 按本地名探测(只读)。
    produceCellMergeIfPresent(tcPr, segs, out);
  }

  /**
   * 产出一个 {@code cellIns}/{@code cellDel} 修订 {@link TrackedChange}。
   *
   * <p>节点类型是 {@code CTTrackChange}(与 property 类 {@code CTRPrChange} 的共同父),走 {@link TrackedChange}
   * 的属性 构造函数(持 {@code CTTrackChange} 委托);{@code raw()} 对 cell 类抛 {@code
   * UnsupportedFeatureException}(方案 C, 同 property),accept/reject 经门面专用方法 {@code acceptCell}/{@code
   * rejectCell} 走。
   */
  private static void produceCell(
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTrackChange node,
      String local,
      TrackedChangeType type,
      List<TrackedChangeSegment> path,
      List<TrackedChange> out) {
    TrackedChangeLocation location = new TrackedChangeLocation(path);
    CellChangeKind kind =
        type == TrackedChangeType.CELL_INS
            ? CellChangeKind.CELL_INSERTION
            : CellChangeKind.CELL_DELETION;
    ChangeDetails details = new CellChangeDetails(kind);
    String id = buildPropertyId(type, location, node, local);
    out.add(new TrackedChange(id, type, location, details, node));
  }

  /**
   * 用 XmlCursor 在 {@code tcPr} 子里探测 {@code cellMerge};命中则产出只读 {@code CELL_MERGE} 修订(纯值,不持委托)。
   *
   * <p><b>cellMerge 的双重阻塞(见 research/cell-forms.md §4)</b>:其 CT 类型 {@code CTCellMergeTrackChange} 在
   * POI 精简 schema 下<b>既无 Java 类文件(编译期不可达),也无 XmlBeans 的 {@code .xsb} schema 资源(运行期不可反序列化)</b>。 因此:
   *
   * <ul>
   *   <li>不能走 typed 访问器 {@code getCellMerge()}(javac 拒绝)。
   *   <li><b>不能</b>对 cellMerge 节点调 {@code cur.getObject()}——XmlBeans 一旦要把它当类型化对象,就会查 {@code
   *       CTCellMergeTrackChange} 的 schema 资源,查不到即抛 {@code SchemaTypeLoaderException}。
   * </ul>
   *
   * <p>故本方法全程用 {@link XmlCursor} 裸读:按本地名 {@code cellMerge} 命中后,直接读 {@code w:id}/{@code w:author}
   * 属性文本({@code getAttributeText},不触发类型化),产出一条经「无委托构造」的纯值 {@code TrackedChange}。该修订的 {@code raw()}
   * 与 {@code acceptCell}/{@code rejectCell} 都抛 {@code UnsupportedFeatureException}。
   *
   * <p>防御式:探测或读属性失败时跳过,不抛异常。
   */
  private static void produceCellMergeIfPresent(
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr tcPr,
      List<TrackedChangeSegment> path,
      List<TrackedChange> out) {
    boolean found = false;
    String wId = "?";
    String author = "";
    XmlCursor cur = tcPr.newCursor();
    try {
      if (cur.toFirstChild()) {
        do {
          if ("cellMerge".equals(cur.getName().getLocalPart())) {
            found = true;
            // 直接读属性文本,不调 getObject()(否则触发 CTCellMergeTrackChange 的 schema 查找,运行期抛异常)
            wId = readWAttribute(cur, "id");
            author = readWAttribute(cur, "author");
            break;
          }
        } while (cur.toNextSibling());
      }
    } finally {
      cur.dispose();
    }
    if (!found) {
      return;
    }
    TrackedChangeLocation location = new TrackedChangeLocation(path);
    ChangeDetails details = new CellChangeDetails(CellChangeKind.UNCONFIRMED_MERGE);
    String id = buildIdFromStrings(TrackedChangeType.CELL_MERGE, location, wId);
    out.add(new TrackedChange(id, TrackedChangeType.CELL_MERGE, location, details, author, null));
  }

  /**
   * 从 cursor 指向的元素读 {@code w:<localName>} 属性的文本值;缺失返回 {@code defaultValue}。
   *
   * <p>OOXML 的修订属性({@code w:id}/{@code w:author}/{@code w:date})都在 {@code w} 命名空间下,故按带命名空间的 QName
   * 读。
   */
  private static String readWAttribute(XmlCursor cur, String localName) {
    String text =
        cur.getAttributeText(
            javax.xml.namespace.QName.valueOf(
                "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}" + localName));
    return text == null ? "" : text;
  }

  // ---------- 产出一条 TrackedChange ----------

  /**
   * 把一个 {@link CTRunTrackChange} 节点解析为 {@link TrackedChange} 并加入结果列表。
   *
   * <p>解析 type(由本地名查表)、details(文本类:按文档顺序拼接 {@code <w:t>}/{@code <w:delText>} 文本)、id(混合版)。
   */
  private static void produce(
      CTRunTrackChange node,
      String local,
      List<TrackedChangeSegment> path,
      List<TrackedChange> out) {
    TrackedChangeType type = TEXT_LOCAL_NAMES.get(local);
    if (type == null) {
      return;
    }
    TrackedChangeLocation location = new TrackedChangeLocation(path);
    ChangeDetails details = new TextChangeDetails(extractText(node, type));
    String id = buildId(type, location, node);
    out.add(new TrackedChange(id, type, location, details, node));
  }

  /**
   * 提取文本类修订的被插入/删除文本。
   *
   * <p>按文档顺序遍历 {@code CTRunTrackChange} 内的 {@code <w:r>},取其 {@code <w:t>}(插入类)或 {@code
   * <w:delText>}(删除类) 文本拼接。删除类({@link TrackedChangeType#DEL DEL} / {@link
   * TrackedChangeType#MOVE_FROM MOVE_FROM})用 {@code delText}; 插入类({@link TrackedChangeType#INS INS}
   * / {@link TrackedChangeType#MOVE_TO MOVE_TO})用 {@code t}。
   */
  private static String extractText(CTRunTrackChange node, TrackedChangeType type) {
    boolean deleted = type == TrackedChangeType.DEL || type == TrackedChangeType.MOVE_FROM;
    StringBuilder sb = new StringBuilder();
    for (CTR ctr : node.getRList()) {
      if (deleted) {
        for (int k = 0; k < ctr.sizeOfDelTextArray(); k++) {
          String s = ctr.getDelTextArray(k).getStringValue();
          if (s != null) {
            sb.append(s);
          }
        }
      } else {
        for (int k = 0; k < ctr.sizeOfTArray(); k++) {
          CTText t = ctr.getTArray(k);
          String s = t.getStringValue();
          if (s != null) {
            sb.append(s);
          }
        }
      }
    }
    return sb.toString();
  }

  /**
   * 混合版稳定 id 生成(type + location + 原始 w:id)。
   *
   * <p>格式为 {@code <type>:<locationPath>:<w:id>},其中 locationPath 用 segment 的 {@code kind+index}
   * 拼接。对调用方是不透明 字符串(见 {@link TrackedChange#id()} 的契约),不作为可解析公共契约;同文档同会话内稳定。
   */
  private static String buildId(
      TrackedChangeType type, TrackedChangeLocation location, CTRunTrackChange node) {
    return buildIdFromNode(type, location, node);
  }

  /**
   * 属性类修订的稳定 id 生成(同 {@link #buildId} 的格式:type + location + w:id)。{@code CTTrackChange} 与 {@code
   * CTRunTrackChange} 都有 {@code getId()},故共用同一生成逻辑。
   */
  private static String buildPropertyId(
      TrackedChangeType type,
      TrackedChangeLocation location,
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTrackChange node,
      String local) {
    return buildIdFromNode(type, location, node);
  }

  /** 共用的 id 生成:type + locationPath + 原始 w:id(对调用方不透明,同文档同会话稳定)。 */
  private static String buildIdFromNode(
      TrackedChangeType type,
      TrackedChangeLocation location,
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTrackChange node) {
    String wId = node.getId() == null ? "?" : node.getId().toString();
    return buildIdFromStrings(type, location, wId);
  }

  /**
   * id 生成的「字符串版」:用于无类型化委托的修订(如 cellMerge,其节点不能经 {@code getObject()} 取 id)。
   *
   * <p>与 {@link #buildIdFromNode} 同格式(type + locationPath + w:id),w:id 直接以字符串传入。
   */
  private static String buildIdFromStrings(
      TrackedChangeType type, TrackedChangeLocation location, String wId) {
    StringBuilder loc = new StringBuilder();
    List<TrackedChangeSegment> segs = location.segments();
    for (int i = 0; i < segs.size(); i++) {
      TrackedChangeSegment s = segs.get(i);
      if (i > 0) {
        loc.append('.');
      }
      loc.append(s.kind().name().toLowerCase()).append(s.index());
    }
    String id = wId == null || wId.isEmpty() ? "?" : wId;
    return type.name().toLowerCase() + ":" + loc + ":" + id;
  }

  // ---------- 辅助 ----------

  /** 在 path 末尾追加一个 segment,返回新列表(不改原 path,便于回溯)。 */
  private static List<TrackedChangeSegment> withSegment(
      List<TrackedChangeSegment> path, TrackedChangeSegmentKind kind, int index) {
    List<TrackedChangeSegment> copy = new ArrayList<>(path.size() + 1);
    copy.addAll(path);
    copy.add(new TrackedChangeSegment(kind, index));
    return copy;
  }

  private static java.util.Map<String, TrackedChangeType> textLocalNames() {
    java.util.Map<String, TrackedChangeType> m = new java.util.HashMap<>();
    m.put("ins", TrackedChangeType.INS);
    m.put("del", TrackedChangeType.DEL);
    m.put("moveFrom", TrackedChangeType.MOVE_FROM);
    m.put("moveTo", TrackedChangeType.MOVE_TO);
    return java.util.Collections.unmodifiableMap(m);
  }
}
