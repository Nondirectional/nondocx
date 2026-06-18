package com.non.docx.core.internal.poi;

import com.non.docx.core.api.track.ChangeDetails;
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
 * <p><b>职责。</b> 提供两件只读能力:
 *
 * <ol>
 *   <li>{@link #isEnabled(XWPFDocument)} —— 读取 {@code settings.xml} 的 {@code <w:trackChanges/>} 开关。
 *   <li>{@link #collect(XWPFDocument)} —— 按文档顺序枚举正文里的修订节点,解析为 {@link TrackedChange} 领域视图。
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
   */
  private static void walkParagraph(
      XmlCursor parent,
      List<TrackedChangeSegment> path,
      int paragraphIdx,
      List<TrackedChange> out) {
    List<TrackedChangeSegment> segs =
        withSegment(path, TrackedChangeSegmentKind.PARAGRAPH, paragraphIdx);
    XmlCursor cur = parent.getObject().newCursor();
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

  /** 遍历单元格:下钻到其内的 {@code <w:p>}(单元格内段落),再走段落遍历。 */
  private static void walkCell(
      Object cell, List<TrackedChangeSegment> path, int cellIdx, List<TrackedChange> out) {
    List<TrackedChangeSegment> segs = withSegment(path, TrackedChangeSegmentKind.CELL, cellIdx);
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc tc =
        (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc) cell;
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
    StringBuilder loc = new StringBuilder();
    List<TrackedChangeSegment> segs = location.segments();
    for (int i = 0; i < segs.size(); i++) {
      TrackedChangeSegment s = segs.get(i);
      if (i > 0) {
        loc.append('.');
      }
      loc.append(s.kind().name().toLowerCase()).append(s.index());
    }
    String wId = node.getId() == null ? "?" : node.getId().toString();
    return type.name().toLowerCase() + ":" + loc + ":" + wId;
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
