package io.github.nondirectional.docx.core.internal.poi;

import io.github.nondirectional.docx.core.api.track.CellChangeDetails;
import io.github.nondirectional.docx.core.api.track.CellChangeKind;
import io.github.nondirectional.docx.core.api.track.ChangeDetails;
import io.github.nondirectional.docx.core.api.track.PropertyChangeDetails;
import io.github.nondirectional.docx.core.api.track.PropertyChangeKind;
import io.github.nondirectional.docx.core.api.track.TextChangeDetails;
import io.github.nondirectional.docx.core.api.track.TrackedChange;
import io.github.nondirectional.docx.core.api.track.TrackedChangeLocation;
import io.github.nondirectional.docx.core.api.track.TrackedChangeSegment;
import io.github.nondirectional.docx.core.api.track.TrackedChangeSegmentKind;
import io.github.nondirectional.docx.core.api.track.TrackedChangeType;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRunTrackChange;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText;

/**
 * 内部 API——恕不另行通知即可更改。
 *
 * <p>这是 nondocx 里<b>唯一</b>接触 tracked changes 的 OOXML 结构的地方,因此公有类型 {@link TrackedChange} / {@link
 * io.github.nondirectional.docx.core.api.track.TrackedChanges} 在源代码层面保持无 {@code org.apache.poi.*}。
 *
 * <p><b>职责。</b> 提供只读能力与文本类破坏性写能力:
 *
 * <ol>
 *   <li>{@link #isEnabled(XWPFDocument)} / {@link #setEnabled(XWPFDocument, boolean)} —— 读写 {@code
 *       settings.xml} 的 {@code <w:trackChanges/>} 开关。
 *   <li>{@link #collect(XWPFDocument)} —— 按文档顺序枚举正文里的修订节点,解析为 {@link TrackedChange} 领域视图。
 *   <li>{@link #acceptText(XWPFDocument, CTRunTrackChange)} / {@link #rejectText(XWPFDocument,
 *       CTRunTrackChange)} —— 对文本类修订做破坏性应用或撤销。
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
   * 写入 {@code settings.xml} 的修订模式开关({@code <w:trackChanges/>})。
   *
   * <p><b>OOXML → POI 映射:</b>
   *
   * <ul>
   *   <li><b>OOXML</b>:开关是 {@code word/settings.xml} 里一个<b>无属性空元素</b> {@code <w:trackChanges/>}。
   *       元素存在即表示「修订模式 ON」,元素缺失即「OFF」。这与正文里散落的修订标记(<{@code w:ins}> / <{@code w:del}>
   *       等)<b>完全独立</b>——开关只 影响后续在 Word 里手动改动是否被追踪,不影响已有修订的可见性或可接受性。
   *   <li><b>POI</b>:{@code XWPFSettings.setTrackRevisions(boolean)} 是这个开关的标准入口。 其字节码语义(已校验):{@code
   *       true} 时若未设置则 {@code addNewTrackRevisions()}(写入空元素),已设置则 no-op; {@code false} 时若已设置则
   *       {@code unsetTrackRevisions()}(移除元素),未设置则 no-op。<b>故本方法天然幂等</b>——重复调相同值不会产生多余写。
   *   <li><b>nondocx</b>:封装为 {@code TrackedChanges.enable()} / {@code disable()},与只读 {@link
   *       #isEnabled} 对称,消除「有读无写」的不对称。
   * </ul>
   *
   * <p><b>写副作用。</b> 本方法会修改 {@code settings.xml}:开启时新增 {@code <w:trackChanges/>} 元素,关闭时移除它。 与
   * accept/reject 同属破坏性写。落盘前在内存生效,需 {@code Document.save()} 才持久化。
   *
   * @param document POI 文档(不能为 {@code null})
   * @param enabled {@code true} 开启修订模式,{@code false} 关闭
   */
  public static void setEnabled(XWPFDocument document, boolean enabled) {
    document.getSettings().setTrackRevisions(enabled);
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
   * <p>手术后经 {@code XWPFParagraphCacheBridge} 按需修复所在段落的 POI 包装缓存(包装在文档打开时已 存在时,其内 run
   * 已入缓存,unwrap/remove 会断连句柄,不修复则 live 读必崩)。
   *
   * @param document 修订所在文档(手术后定位受影响段落用;不能为 {@code null})
   * @param node 要 accept 的修订节点(不能为 {@code null})
   */
  public static void acceptText(XWPFDocument document, CTRunTrackChange node) {
    java.util.Objects.requireNonNull(document, "document");
    java.util.Objects.requireNonNull(node, "node");
    String local = localName(node);
    if ("ins".equals(local) || "moveTo".equals(local)) {
      unwrapRunsAndRemove(document, node);
    } else {
      // del / moveFrom:accept 即删除生效,整个包装移除
      removeNode(document, node);
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
   * <p>手术后经 {@code XWPFParagraphCacheBridge} 按需修复所在段落的 POI 包装缓存(同 {@link #acceptText})。
   *
   * @param document 修订所在文档(手术后定位受影响段落用;不能为 {@code null})
   * @param node 要 reject 的修订节点(不能为 {@code null})
   */
  public static void rejectText(XWPFDocument document, CTRunTrackChange node) {
    java.util.Objects.requireNonNull(document, "document");
    java.util.Objects.requireNonNull(node, "node");
    String local = localName(node);
    if ("ins".equals(local) || "moveTo".equals(local)) {
      removeNode(document, node);
    } else {
      // del / moveFrom:reject 即删除被撤销,恢复正文
      restoreDelTextToT(document, node);
      unwrapRunsAndRemove(document, node);
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

  /**
   * 删除整个节点(连同其子树)。用于 reject ins / accept del。
   *
   * <p>删除会让「文档打开时已入 POI 段落缓存」的包装内 run 句柄断连,故手术后按需重建所在段落的缓存。
   */
  private static void removeNode(XWPFDocument document, CTRunTrackChange node) {
    // 手术前捕获所在段落(删除后节点断连,无法再上溯)
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP owner = ownerParagraphCtp(node);
    XmlCursor c = node.newCursor();
    try {
      c.removeXml();
    } finally {
      c.dispose();
    }
    refreshOwnerParagraph(document, owner);
  }

  /**
   * 把包装内的 {@code <w:r>} 移到包装之前(即挂回父级),再删除空的包装。
   *
   * <p>用于 accept ins / accept moveTo。{@code moveXml(anchor)} 把 cursor 指向的节点移到 {@code anchor} 之前,并 让
   * cursor 自动指向下一个兄弟(因此 {@code toNextSibling()} 继续推进而不重复)。
   *
   * <p>moveXml/removeXml 会让「文档打开时已入 POI 段落缓存」的包装内 run 句柄断连,故手术后按需重建所在段落的缓存; 本会话新建的包装(run
   * 不在缓存)无断连,重建不触发。
   */
  private static void unwrapRunsAndRemove(XWPFDocument document, CTRunTrackChange wrapper) {
    // 手术前捕获所在段落(迁移+删除后 wrapper 断连,无法再上溯)
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP owner = ownerParagraphCtp(wrapper);
    XmlCursor anchor = wrapper.newCursor();
    try {
      // 逐 run 独立 cursor 迁移。原实现的「child.moveXml + toNextSibling」循环在首个 moveXml 后
      // 游标失效(toNextSibling 提前结束),多 run ins 的后续 run 会留在 wrapper 内随 removeXml
      // 一起被删——单 run 时代码路径从未暴露该 bug(2026-10-30 多 run ins 测试踩实)。
      // 先快照 run 引用再逐个迁移(getRArray 是活视图,边迁边读会漂移);迁移后顺序由
      // TrackedAuthoringTest/PatchEngineTest 的 accept 断言锁定。
      java.util.List<CTR> runs = new java.util.ArrayList<>(wrapper.sizeOfRArray());
      for (int i = 0; i < wrapper.sizeOfRArray(); i++) {
        runs.add(wrapper.getRArray(i));
      }
      for (CTR run : runs) {
        XmlCursor rc = run.newCursor();
        try {
          rc.moveXml(anchor);
        } finally {
          rc.dispose();
        }
      }
      anchor.removeXml();
    } finally {
      anchor.dispose();
    }
    refreshOwnerParagraph(document, owner);
  }

  /**
   * 把节点内各 run 的 {@code <w:delText>} 恢复为普通 {@code <w:t>}(删除被撤销的文本回到正文)。
   *
   * <p>用于 reject del / reject moveFrom。{@code delText} 与 {@code t} 在 OOXML 里是不同元素(本地名 {@code
   * delText} vs {@code t}),仅靠 moveXml 无法完成类型转换,因此先读出文本、删旧 {@code delText}、再新建 {@code t}。
   *
   * <p>类型转换本身是原地操作、不产生断连;尾部刷新属 reject 分支的防御性修复(若所在段缓存此前已被别的手术悬空,顺手修好)。
   */
  private static void restoreDelTextToT(XWPFDocument document, CTRunTrackChange wrapper) {
    for (CTR r : wrapper.getRList()) {
      for (int i = r.sizeOfDelTextArray() - 1; i >= 0; i--) {
        CTText source = r.getDelTextArray(i);
        String s = source.getStringValue();
        org.apache.xmlbeans.impl.xb.xmlschema.SpaceAttribute.Space.Enum space = source.getSpace();
        r.removeDelText(i);
        CTText t = r.addNewT();
        t.setStringValue(s == null ? "" : s);
        if (space != null) {
          t.setSpace(space);
        }
      }
    }
    refreshOwnerParagraph(document, ownerParagraphCtp(wrapper));
  }

  // ---------- 手术后 POI 段落包装缓存修复 ----------

  /**
   * 从手术节点上溯取其所在段落的 {@code CTP};父不是段落(畸形结构)时返回 {@code null}。
   *
   * <p>必须在手术(moveXml/removeXml)之前调用——断连后的节点无法再向上导航。
   */
  private static org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP ownerParagraphCtp(
      XmlObject node) {
    XmlCursor c = node.newCursor();
    try {
      if (!c.toParent() || c.getName() == null || !"p".equals(c.getName().getLocalPart())) {
        return null;
      }
      XmlObject parent = c.getObject();
      return parent instanceof org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP
          ? (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP) parent
          : null;
    } finally {
      c.dispose();
    }
  }

  /**
   * 定位 {@code owner} 对应的 {@code XWPFParagraph} 并按需重建其 POI 包装缓存。
   *
   * <p>回查兜底:遍历 body 直属段落 + 表格单元格段落(含嵌套表,按 {@code getCTP()} 引用比对)。accept/reject 只能命中 body
   * 可达的修订,页眉页脚不在查找范围;找不到(如段落已随手术消失)时静默跳过,不抛。
   */
  private static void refreshOwnerParagraph(
      XWPFDocument document, org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP owner) {
    if (owner == null) {
      return;
    }
    org.apache.poi.xwpf.usermodel.XWPFParagraph paragraph = findParagraph(document, owner);
    if (paragraph != null) {
      org.apache.poi.xwpf.usermodel.XWPFParagraphCacheBridge.refreshIfDisconnected(paragraph);
    }
  }

  /** 在 body 直属段落 + 表格(含嵌套)单元格段落里按 {@code getCTP()} 引用比对定位段落。 */
  private static org.apache.poi.xwpf.usermodel.XWPFParagraph findParagraph(
      XWPFDocument document, org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP ctp) {
    for (org.apache.poi.xwpf.usermodel.XWPFParagraph p : document.getParagraphs()) {
      if (p.getCTP() == ctp) {
        return p;
      }
    }
    for (org.apache.poi.xwpf.usermodel.XWPFTable table : document.getTables()) {
      org.apache.poi.xwpf.usermodel.XWPFParagraph found = findParagraphInTable(table, ctp);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  /** {@link #findParagraph} 的表格分支:单元格段落 + 嵌套表递归。 */
  private static org.apache.poi.xwpf.usermodel.XWPFParagraph findParagraphInTable(
      org.apache.poi.xwpf.usermodel.XWPFTable table,
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP ctp) {
    for (org.apache.poi.xwpf.usermodel.XWPFTableRow row : table.getRows()) {
      for (org.apache.poi.xwpf.usermodel.XWPFTableCell cell : row.getTableCells()) {
        for (org.apache.poi.xwpf.usermodel.XWPFParagraph p : cell.getParagraphs()) {
          if (p.getCTP() == ctp) {
            return p;
          }
        }
        for (org.apache.poi.xwpf.usermodel.XWPFTable nested : cell.getTables()) {
          org.apache.poi.xwpf.usermodel.XWPFParagraph found = findParagraphInTable(nested, ctp);
          if (found != null) {
            return found;
          }
        }
      }
    }
    return null;
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
    return addInsertion(paragraph, java.util.List.of(text), author, date, revisionId);
  }

  /**
   * 在段落末尾创建一条 tracked insertion(插入)修订,内含<b>每段文本一个 run</b>(共 N 个)。
   *
   * <p>与单文本版同一 OOXML 形态,只是 {@code <w:ins>} 内有多个 {@code <w:r>}——读侧 {@code collect} 按 ins 节点聚合文本,仍是一条
   * INS 修订;样式按 run 独立(为带格式插入 服务:粗体段/普通段各自持 {@code rPr})。
   *
   * @param texts 每个元素一个 run 的文本(不能为 {@code null};列表非空且元素均非 {@code null})
   */
  public static CTRunTrackChange addInsertion(
      org.apache.poi.xwpf.usermodel.XWPFParagraph paragraph,
      java.util.List<String> texts,
      String author,
      java.util.Calendar date,
      java.math.BigInteger revisionId) {
    java.util.Objects.requireNonNull(paragraph, "paragraph");
    requireTexts(texts);
    java.util.Objects.requireNonNull(author, "author");
    java.util.Objects.requireNonNull(date, "date");
    java.util.Objects.requireNonNull(revisionId, "revisionId");
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP p = paragraph.getCTP();
    CTRunTrackChange ins = p.addNewIns();
    ins.setId(revisionId);
    ins.setAuthor(author);
    ins.setDate(date);
    for (String text : texts) {
      CTR r = ins.addNewR();
      CTText t = r.addNewT();
      t.setStringValue(text);
    }
    return ins;
  }

  /** 多 run 文本段的公共校验:列表非空、元素非 null(空串由上层 API 语义决定,这里不拦)。 */
  private static void requireTexts(java.util.List<String> texts) {
    java.util.Objects.requireNonNull(texts, "texts");
    if (texts.isEmpty()) {
      throw new IllegalArgumentException("texts 不能为空列表");
    }
    for (String text : texts) {
      java.util.Objects.requireNonNull(text, "texts 元素");
    }
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
   * <p><b>手术后缓存修复。</b> {@code moveXml} 迁移的是段落<b>直接子</b> run——它已在 POI 段落缓存里被包成 {@code XWPFRun}(open
   * 时固化,或 {@code createRun} 时登记),迁入 {@code <w:del>} 的瞬间其句柄即断连,此后 live 读该段落必抛 {@code
   * XmlValueDisconnectedException}。故本方法尾部对所在段落调用 {@code
   * XWPFParagraphCacheBridge.refreshIfDisconnected}(按需重建,与本类 move/accept/reject 手术点同一修复)。重建后 {@code
   * getText()} 跳过 delText,live 读值与 save+reopen 口径一致。deleteTrackedRange/replaceTrackedRange 走
   * detached 副本 + {@code replacePlainTextRunViews} 整体重建,不经本方法,无此问题。
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
    // 手术后修复 POI 段落包装缓存:被迁走的直接子 run 在缓存里的旧句柄已断连(见方法 Javadoc)。
    org.apache.poi.xwpf.usermodel.XWPFParagraphCacheBridge.refreshIfDisconnected(paragraph);
    return del;
  }

  /**
   * 在一个普通纯文本 run 的指定局部范围上创建 tracked deletion，并保持该 run 在段落中的位置。
   *
   * <p>该方法是 {@link io.github.nondirectional.docx.core.api.text.Paragraph#deleteTrackedRange} 的 XML
   * 下沉实现。目标 run 会被拆为前缀、删除片段和后缀；所有新片段复制原 run 的完整 {@code rPr}。
   *
   * <p>内部 API——仅供 core 活对象调用。调用前必须已完成段落范围映射与 author/range 校验。
   */
  public static CTRunTrackChange addDeletionSlice(
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP paragraph,
      CTR targetRun,
      int start,
      int end,
      String author,
      java.util.Calendar date,
      java.math.BigInteger revisionId) {
    java.util.Objects.requireNonNull(paragraph, "paragraph");
    java.util.Objects.requireNonNull(targetRun, "targetRun");
    java.util.Objects.requireNonNull(author, "author");
    java.util.Objects.requireNonNull(date, "date");
    java.util.Objects.requireNonNull(revisionId, "revisionId");
    requirePlainTextRun(targetRun);

    String original = runText(targetRun);
    if (start < 0 || end <= start || end > original.length()) {
      throw new IllegalArgumentException(
          "run 局部 tracked deletion 范围无效:[" + start + "," + end + "),文本长度=" + original.length());
    }
    String prefix = original.substring(0, start);
    String selected = original.substring(start, end);
    String suffix = original.substring(end);
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP ctp = paragraph;

    // 必须在追加 prefix/suffix/del 之前捕获原目标的下一个兄弟，否则新建节点会被误当作 next。
    XmlCursor nextAnchor = targetRun.newCursor();
    boolean hasNext;
    try {
      hasNext = nextAnchor.toNextSibling();
    } catch (RuntimeException e) {
      nextAnchor.dispose();
      throw e;
    }

    // 先把拆分片段追加到段落，使它们拥有可被 XmlCursor 移动的实际 XML 节点。
    CTR prefixRun = prefix.isEmpty() ? null : appendRunCopy(ctp, targetRun, prefix);
    CTR suffixRun = suffix.isEmpty() ? null : appendRunCopy(ctp, targetRun, suffix);
    CTRunTrackChange del = ctp.addNewDel();
    del.setId(revisionId);
    del.setAuthor(author);
    del.setDate(date);

    // 先把原目标转换为 deletion 文本并迁入 wrapper。迁入后原 CTR 句柄会失效，之后只操作
    // 仍然连接的 prefix/suffix/del 节点；这是 XmlBeans moveXml 的既有行为。
    clearTextNodes(targetRun);
    CTText deletedText = targetRun.addNewDelText();
    setTextValue(deletedText, selected);
    XmlCursor delEnd = del.newCursor();
    XmlCursor targetCursor = targetRun.newCursor();
    try {
      delEnd.toEndToken();
      targetCursor.moveXml(delEnd);
    } finally {
      delEnd.dispose();
      targetCursor.dispose();
    }

    try {
      if (hasNext) {
        if (prefixRun != null) {
          XmlCursor prefixCursor = prefixRun.newCursor();
          try {
            prefixCursor.moveXml(nextAnchor);
          } finally {
            prefixCursor.dispose();
          }
        }
        XmlCursor delCursor = del.newCursor();
        try {
          delCursor.moveXml(nextAnchor);
        } finally {
          delCursor.dispose();
        }
        if (suffixRun != null) {
          XmlCursor suffixCursor = suffixRun.newCursor();
          try {
            suffixCursor.moveXml(nextAnchor);
          } finally {
            suffixCursor.dispose();
          }
        }
      } else if (suffixRun != null) {
        // 没有后续兄弟时以 suffix 为稳定锚点，显式按 prefix、del、suffix 重排，
        // 不依赖 XmlBeans 的 addNewR/addNewDel 数组插入位置。
        XmlCursor suffixAnchor = suffixRun.newCursor();
        try {
          if (prefixRun != null) {
            XmlCursor prefixCursor = prefixRun.newCursor();
            try {
              prefixCursor.moveXml(suffixAnchor);
            } finally {
              prefixCursor.dispose();
            }
          }
          XmlCursor delCursor = del.newCursor();
          try {
            delCursor.moveXml(suffixAnchor);
          } finally {
            delCursor.dispose();
          }
        } finally {
          suffixAnchor.dispose();
        }
      } else if (prefixRun != null) {
        // 没有 suffix 时以 del 为稳定锚点，确保 prefix 在 deletion 前。
        XmlCursor prefixCursor = prefixRun.newCursor();
        XmlCursor delAnchor = del.newCursor();
        try {
          prefixCursor.moveXml(delAnchor);
        } finally {
          prefixCursor.dispose();
          delAnchor.dispose();
        }
      }
    } finally {
      nextAnchor.dispose();
    }
    return del;
  }

  /** 在任何写入前校验目标 CTR 只含 rPr/t，避免范围删除进行到一半才发现不可切片节点。 */
  public static void validateDeletionSlice(CTR targetRun) {
    java.util.Objects.requireNonNull(targetRun, "targetRun");
    requirePlainTextRun(targetRun);
  }

  /**
   * 在段落内<b>指定锚点节点之后</b>（而不是段落末尾）创建一条 tracked insertion，内含一个承载 {@code text} 的 run。
   *
   * <p>它是「范围替换」({@code Paragraph.replaceTrackedRange})的定位插入下沉：{@link #addInsertion} 只能把 {@code
   * <w:ins>} 追加到段落末尾，而替换需要 ins 紧跟在被替换范围的 {@code <w:del>} 之后。实现上先在段末建 ins，再用 {@link XmlCursor}
   * 把它移到锚点的结束 token 之后（moveXml 把节点放到 cursor 位置之前，故先 {@code toEndToken()} 再 {@code toNextToken()}
   * 即「锚点之后、锚点下一兄弟之前」）。
   *
   * <p>{@code styleSource} 非空时，新 run 复制它的完整 {@code rPr}（「替换保留格式」语义）；传 {@code null} 则新 run
   * 无样式起步。调用方需自行推进 {@code nextRevisionId}。
   *
   * <p>内部 API——仅供 core 活对象调用；调用方保证锚点属于该段落且已完成 author/text 校验。
   *
   * <p><b>返回值陷阱。</b> ins 建立后会被 {@code moveXml} 移动定位，返回的句柄可能已断连（XmlBeans 既有行为）—— 只能立即读元数据；
   * 需要稳定句柄的调用方应记下 {@code revisionId}，事后按 id 重新查询。
   *
   * @param paragraph 目标段落的底层 CTP（不能为 {@code null}）
   * @param anchor 定位锚点（通常是范围替换里最右侧的 {@code <w:del>}；不能为 {@code null}）
   * @param styleSource 新 run 的样式来源（可 {@code null}）
   * @param text 插入的文本（不能为 {@code null}）
   * @param author 修订作者（不能为 {@code null}）
   * @param date 修订时间（不能为 {@code null}）
   * @param revisionId 底层 OOXML {@code w:id}（不能为 {@code null}）
   * @return 新建的 {@code <w:ins>} 节点
   */
  public static CTRunTrackChange addInsertionAfter(
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP paragraph,
      XmlObject anchor,
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr styleSource,
      String text,
      String author,
      java.util.Calendar date,
      java.math.BigInteger revisionId) {
    return addInsertionAfter(
        paragraph, anchor, styleSource, java.util.List.of(text), author, date, revisionId);
  }

  /**
   * {@link #addInsertionAfter(CTP, XmlObject, CTRPr, String, String, Calendar, BigInteger)} 的多 run
   * 版:一条 {@code <w:ins>} 内每段文本一个 run(共 N 个),<b>每个</b> run 都复制 {@code styleSource} 的完整 {@code
   * rPr}(与单文本版同语义);调用方随后可对个别 run 叠加粗/斜等格式。
   */
  public static CTRunTrackChange addInsertionAfter(
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP paragraph,
      XmlObject anchor,
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr styleSource,
      java.util.List<String> texts,
      String author,
      java.util.Calendar date,
      java.math.BigInteger revisionId) {
    java.util.Objects.requireNonNull(paragraph, "paragraph");
    java.util.Objects.requireNonNull(anchor, "anchor");
    requireTexts(texts);
    java.util.Objects.requireNonNull(author, "author");
    java.util.Objects.requireNonNull(date, "date");
    java.util.Objects.requireNonNull(revisionId, "revisionId");
    CTRunTrackChange ins = paragraph.addNewIns();
    ins.setId(revisionId);
    ins.setAuthor(author);
    ins.setDate(date);
    for (String text : texts) {
      CTR r = ins.addNewR();
      if (styleSource != null) {
        r.setRPr((org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr) styleSource.copy());
      }
      setTextValue(r.addNewT(), text);
    }
    XmlCursor anchorCur = anchor.newCursor();
    XmlCursor insCur = ins.newCursor();
    try {
      anchorCur.toEndToken();
      anchorCur.toNextToken();
      insCur.moveXml(anchorCur);
    } finally {
      anchorCur.dispose();
      insCur.dispose();
    }
    return ins;
  }

  /** 复制原 run 的属性并在段落末尾创建一条纯文本 run。 */
  private static CTR appendRunCopy(
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP paragraph,
      CTR source,
      String text) {
    CTR copy = paragraph.addNewR();
    copy.set(source.copy());
    clearTextNodes(copy);
    setTextValue(copy.addNewT(), text);
    return copy;
  }

  /** 删除一个 CTR 内的普通文本子节点；调用方已确保不存在其它需要保留的内容。 */
  private static void clearTextNodes(CTR run) {
    for (int i = run.sizeOfTArray() - 1; i >= 0; i--) {
      run.removeT(i);
    }
    for (int i = run.sizeOfDelTextArray() - 1; i >= 0; i--) {
      run.removeDelText(i);
    }
  }

  /** 设置文本并为首尾空白保留 xml:space。 */
  private static void setTextValue(CTText text, String value) {
    text.setStringValue(value);
    if (!value.isEmpty()
        && (Character.isWhitespace(value.charAt(0))
            || Character.isWhitespace(value.charAt(value.length() - 1)))) {
      text.setSpace(org.apache.xmlbeans.impl.xb.xmlschema.SpaceAttribute.Space.PRESERVE);
    }
  }

  /** 返回普通 t 子节点拼接后的文本。 */
  private static String runText(CTR run) {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < run.sizeOfTArray(); i++) {
      String value = run.getTArray(i).getStringValue();
      if (value != null) {
        text.append(value);
      }
    }
    return text.toString();
  }

  /** 拒绝含有图片、域、制表符、换行或其它非普通文本子节点的 run。 */
  private static void requirePlainTextRun(CTR run) {
    XmlCursor cursor = run.newCursor();
    try {
      if (cursor.toFirstChild()) {
        do {
          String local = cursor.getName() == null ? "" : cursor.getName().getLocalPart();
          if (!"rPr".equals(local) && !"t".equals(local)) {
            throw new io.github.nondirectional.docx.core.api.exception.UnsupportedFeatureException(
                "tracked deletion 暂不支持含有非普通文本子节点的 run:<w:" + local + ">");
          }
        } while (cursor.toNextSibling());
      }
    } finally {
      cursor.dispose();
    }
  }

  /**
   * 把一个单元格标记为 tracked cellIns(单元格被插入)。
   *
   * <p>OOXML:在 {@code <w:tcPr>} 内写 {@code <w:cellIns w:id=.. w:author=.. w:date=../>}(裸属性,无
   * run)。节点类型 {@code CTTrackChange}({@code CTTcPr.addNewCellIns()}),与 cell 读侧(N16)同委托。
   *
   * <p>{@code w:id} 经 {@link #nextRevisionId} 自动分配。创作出的修订随后能被 {@code collect} 读回、被 {@code
   * acceptCell}/{@code rejectCell} 处理(作用于整个 {@code <w:tc>})。
   *
   * @param tc 单元格的底层 {@code CTTc}(不能为 {@code null})
   * @param author 修订作者(不能为 {@code null})
   * @param date 修订时间(不能为 {@code null})
   */
  public static void markCellIns(
      XWPFDocument document,
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc tc,
      String author,
      java.util.Calendar date) {
    java.util.Objects.requireNonNull(document, "document");
    java.util.Objects.requireNonNull(tc, "tc");
    java.util.Objects.requireNonNull(author, "author");
    java.util.Objects.requireNonNull(date, "date");
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr tcPr =
        tc.isSetTcPr() ? tc.getTcPr() : tc.addNewTcPr();
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTrackChange cellIns =
        tcPr.addNewCellIns();
    cellIns.setId(nextRevisionId(document));
    cellIns.setAuthor(author);
    cellIns.setDate(date);
  }

  /**
   * 把一个单元格标记为 tracked cellDel(单元格被删除)。
   *
   * <p>语义:标记此单元格本身是被删除的。accept 时移除整个 {@code <w:tc>},reject 时保留。其余同 {@link #markCellIns}。
   *
   * @param document 所属文档(用于分配 {@code w:id})
   * @param tc 单元格的底层 {@code CTTc}(不能为 {@code null})
   * @param author 修订作者(不能为 {@code null})
   * @param date 修订时间(不能为 {@code null})
   */
  public static void markCellDel(
      XWPFDocument document,
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc tc,
      String author,
      java.util.Calendar date) {
    java.util.Objects.requireNonNull(document, "document");
    java.util.Objects.requireNonNull(tc, "tc");
    java.util.Objects.requireNonNull(author, "author");
    java.util.Objects.requireNonNull(date, "date");
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr tcPr =
        tc.isSetTcPr() ? tc.getTcPr() : tc.addNewTcPr();
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTrackChange cellDel =
        tcPr.addNewCellDel();
    cellDel.setId(nextRevisionId(document));
    cellDel.setAuthor(author);
    cellDel.setDate(date);
  }

  /**
   * 把一个 run 的内联样式变更记为 tracked rPrChange。
   *
   * <p>OOXML:在 run 的 {@code <w:rPr>} 内写 {@code <w:rPrChange w:id=.. w:author=.. w:date=..>},其内嵌一个
   * {@code <w:rPr>} 存<b>旧值树</b>(pristine)。 当前 rPr 即「新值」(调用方已改完样式)。
   *
   * <p>探针验证(research/authoring-forms.md §1.2):{@code rPrChange.getRPr()} 返回 {@code CTRPrOriginal},其
   * schema 天然不含 {@code rPrChange} 子元素——架构层防递归,无需手动剔除。
   *
   * @param document 所属文档(用于分配 {@code w:id})
   * @param run 目标 run 的底层 {@code CTR}(不能为 {@code null})
   * @param previousStyle 修改<b>前</b>的样式快照(由调用方在改样式前 {@code run.style()} 取得);写进 rPrChange 作为旧值树
   * @param author 修订作者(不能为 {@code null})
   * @param date 修订时间(不能为 {@code null})
   */
  public static void commitRPrChange(
      XWPFDocument document,
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR run,
      io.github.nondirectional.docx.core.api.style.RunStyle previousStyle,
      String author,
      java.util.Calendar date) {
    java.util.Objects.requireNonNull(document, "document");
    java.util.Objects.requireNonNull(run, "run");
    java.util.Objects.requireNonNull(previousStyle, "previousStyle");
    java.util.Objects.requireNonNull(author, "author");
    java.util.Objects.requireNonNull(date, "date");
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr rPr =
        run.isSetRPr() ? run.getRPr() : run.addNewRPr();
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPrChange change =
        rPr.addNewRPrChange();
    change.setId(nextRevisionId(document));
    change.setAuthor(author);
    change.setDate(date);
    // 旧值树:把 previousStyle 渲染进 CTRPrOriginal(架构层不含 rPrChange,天然防递归)
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPrOriginal oldRPr =
        change.addNewRPr();
    if (previousStyle.isBold()) {
      oldRPr.addNewB();
    }
    if (previousStyle.isItalic()) {
      oldRPr.addNewI();
    }
    if (previousStyle.isUnderline()) {
      oldRPr
          .addNewU()
          .setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STUnderline.SINGLE);
    }
    if (previousStyle.font() != null) {
      oldRPr.addNewRFonts().setAscii(previousStyle.font());
    }
    if (previousStyle.size() != null) {
      // POI 的 size 是磅值,OOXML sz 是半磅(half-points)
      oldRPr.addNewSz().setVal(java.math.BigInteger.valueOf(previousStyle.size().longValue() * 2));
    }
    if (previousStyle.color() != null) {
      oldRPr.addNewColor().setVal(previousStyle.color());
    }
  }

  /**
   * 把一组 run 从源段移动到目标段,产出配对的 tracked move 修订(moveFrom + moveTo,各自带 rangeStart/End)。
   *
   * <p><b>OOXML 结构</b>(探针验证见 research/authoring-forms.md §2):move 靠 {@code rangeStart} 的 {@code
   * w:name} 配对(两端同名),不是靠 moveFrom/moveTo 的 id。源端 run 的文本用 {@code delText},目标端用 {@code t}(同 del/ins
   * 规则)。
   *
   * <pre>{@code
   * 源段:  <moveFromRangeStart name="N"/> <moveFrom><r><delText>...</delText></r></moveFrom> <moveFromRangeEnd/>
   * 目标段:<moveToRangeStart name="N"/>   <moveTo><r><t>...</t></r></moveTo>             <moveToRangeEnd/>
   * }</pre>
   *
   * <p>本方法把<b>整组 runs 包在一个 moveFrom/moveTo</b> 内(一个范围,而非每个 run 一对),贴近 Word「一次移动一段连续内容」的语义。源端 runs
   * 被迁入 moveFrom(文本转 delText);目标端新建 moveTo(文本为 t)。配对 name 用 {@code _move_<baseId>} 保证文档内唯一。
   *
   * <p>需分配 6 个 {@code w:id}(rangeStart/End ×2 + moveFrom/moveTo),全走 {@link #nextRevisionId} 递增。
   *
   * <p><b>手术后缓存修复。</b> 源段直接子 run 被 {@code moveXml} 迁入 {@code moveFrom} 的瞬间,POI 段落缓存 (open 时固化)里的旧
   * {@code XWPFRun} 句柄即断连——此后 live 读源段必抛 {@code
   * XmlValueDisconnectedException}。故本方法尾部对源段(必断连,重建)与目标段(moveTo 内 run 是新建、 不在缓存,防御性同检)调用 {@code
   * XWPFParagraphCacheBridge.refreshIfDisconnected}。
   *
   * @param document 所属文档(分配 {@code w:id})
   * @param sourceParagraph 源段落的 POI 句柄(手术链下传,手术后修复其包装缓存;不能为 {@code null})
   * @param targetParagraph 目标段落的 POI 句柄(手术链下传,防御性同检;不能为 {@code null})
   * @param runs 要移动的源 runs(不能为 {@code null} 或空;每个 run 的文本会被转 delText)
   * @param author 修订作者(不能为 {@code null})
   * @param date 修订时间(不能为 {@code null})
   * @return 新插入到目标段的 run 列表(在 {@code moveTo} 内,按原文本顺序)
   */
  public static List<org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR> moveRuns(
      XWPFDocument document,
      org.apache.poi.xwpf.usermodel.XWPFParagraph sourceParagraph,
      org.apache.poi.xwpf.usermodel.XWPFParagraph targetParagraph,
      List<org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR> runs,
      String author,
      java.util.Calendar date) {
    java.util.Objects.requireNonNull(document, "document");
    java.util.Objects.requireNonNull(sourceParagraph, "sourceParagraph");
    java.util.Objects.requireNonNull(targetParagraph, "targetParagraph");
    java.util.Objects.requireNonNull(runs, "runs");
    java.util.Objects.requireNonNull(author, "author");
    java.util.Objects.requireNonNull(date, "date");
    if (runs.isEmpty()) {
      throw new IllegalArgumentException("runs 不能为空");
    }
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP source = sourceParagraph.getCTP();
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP target = targetParagraph.getCTP();
    java.math.BigInteger baseId = nextRevisionId(document);
    String moveName = "_move_" + baseId;

    // 先预捕获每个源 run 的文本(按 run 分组),再开始移动——
    // moveXml 后源 CTR 句柄会 XmlValueDisconnected,不能在移动后再读它的 delText。
    List<List<String>> textsPerRun = new java.util.ArrayList<>();
    for (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR run : runs) {
      List<String> texts = new java.util.ArrayList<>();
      for (int i = 0; i < run.sizeOfTArray(); i++) {
        texts.add(run.getTArray(i).getStringValue());
      }
      textsPerRun.add(texts);
    }

    // ---- 源段:moveFromRangeStart + moveFrom(迁入 runs,文本转 delText) + moveFromRangeEnd ----
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTMoveBookmark fromStart =
        source.addNewMoveFromRangeStart();
    fromStart.setId(baseId);
    fromStart.setName(moveName);
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRunTrackChange moveFrom =
        source.addNewMoveFrom();
    moveFrom.setId(baseId.add(java.math.BigInteger.ONE));
    moveFrom.setAuthor(author);
    moveFrom.setDate(date);
    // 把每个源 run 的 t 转成 delText,再迁入 moveFrom 内部末尾
    XmlCursor fromCur = moveFrom.newCursor();
    try {
      fromCur.toEndToken();
      for (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR run : runs) {
        for (int i = run.sizeOfTArray() - 1; i >= 0; i--) {
          String s = run.getTArray(i).getStringValue();
          run.removeT(i);
          run.addNewDelText().setStringValue(s == null ? "" : s);
        }
        XmlCursor runCur = run.newCursor();
        try {
          runCur.moveXml(fromCur);
        } finally {
          runCur.dispose();
        }
      }
    } finally {
      fromCur.dispose();
    }
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTMarkupRange fromEnd =
        source.addNewMoveFromRangeEnd();
    fromEnd.setId(baseId.add(java.math.BigInteger.valueOf(2)));

    // ---- 目标段:moveToRangeStart + moveTo(新建 runs,文本为 t) + moveToRangeEnd ----
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTMoveBookmark toStart =
        target.addNewMoveToRangeStart();
    toStart.setId(baseId.add(java.math.BigInteger.valueOf(3)));
    toStart.setName(moveName);
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRunTrackChange moveTo =
        target.addNewMoveTo();
    moveTo.setId(baseId.add(java.math.BigInteger.valueOf(4)));
    moveTo.setAuthor(author);
    moveTo.setDate(date);
    // 目标端新建 run,文本用预捕获的原文(源 run 已 moveXml 断连,不能读)
    List<org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR> moved =
        new java.util.ArrayList<>();
    for (List<String> texts : textsPerRun) {
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR newRun = moveTo.addNewR();
      for (String t : texts) {
        newRun.addNewT().setStringValue(t == null ? "" : t);
      }
      moved.add(newRun);
    }
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTMarkupRange toEnd =
        target.addNewMoveToRangeEnd();
    toEnd.setId(baseId.add(java.math.BigInteger.valueOf(5)));
    // ---- 手术后修复 POI 段落包装缓存(live 会话崩溃的根因修复) ----
    // 源段:直接子 run 被 moveXml 迁入 moveFrom,open 时固化的 XWPFRun 句柄断连,必须重建;
    // 目标段:moveTo 内 run 是新建(不在缓存),防御性同检(通常无断连、不动)。
    org.apache.poi.xwpf.usermodel.XWPFParagraphCacheBridge.refreshIfDisconnected(sourceParagraph);
    org.apache.poi.xwpf.usermodel.XWPFParagraphCacheBridge.refreshIfDisconnected(targetParagraph);
    return moved;
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
      // 用 wId() 而非 raw().getId()——raw() 对属性/单元格类会抛 UnsupportedFeatureException。
      java.math.BigInteger id = c.wId();
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
   * io.github.nondirectional.docx.core.api.exception.DocxOperationException} 提示结构异常,不静默删错层级。
   */
  private static void removeCellNode(
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTrackChange node) {
    XmlCursor c = cursorToCell(node);
    try {
      String local = c.getName() == null ? "" : c.getName().getLocalPart();
      if (!"tc".equals(local)) {
        throw new io.github.nondirectional.docx.core.api.exception.DocxOperationException(
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
