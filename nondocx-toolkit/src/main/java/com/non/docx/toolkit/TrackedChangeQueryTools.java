package com.non.docx.toolkit;

import com.non.chain.tool.ToolDef;
import com.non.chain.tool.ToolParam;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.track.CellChangeDetails;
import com.non.docx.core.api.track.ChangeDetails;
import com.non.docx.core.api.track.PropertyChangeDetails;
import com.non.docx.core.api.track.TextChangeDetails;
import com.non.docx.core.api.track.TrackedChange;
import com.non.docx.core.api.track.TrackedChanges;
import com.non.docx.toolkit.ref.ElementRef;
import com.non.docx.toolkit.ref.ElementRefs;
import com.non.docx.toolkit.ref.ElementResolver;
import com.non.docx.toolkit.ref.RefResolutionCode;
import com.non.docx.toolkit.ref.RefResolutionException;
import com.non.docx.toolkit.ref.ReferenceContext;
import com.non.docx.toolkit.ref.RevisionRef;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * 修订<b>读取 / 处理</b>工具组（原 H 组）：修订开关查询、枚举、accept/reject。
 *
 * <p>与 {@link TrackedChangeAuthoringTools}（创作）<b>正交</b>：本类不创作修订，只读和处理文档里已有的修订；
 * 后者创作的修订随后可被本类读回与处理。两组代码零交叉，故独立成类。
 *
 * <p><b>OOXML 三层递进（修订）：</b>
 *
 * <ul>
 *   <li><b>OOXML</b>：修订标记散落在 {@code word/document.xml} 正文各处——文本类 {@code <w:ins>}/{@code
 *       <w:del>}、移动类 {@code <w:moveFrom>}/{@code <w:moveTo>}、属性类 {@code rPrChange} 等（嵌在 {@code
 *       <w:rPr>} 内）、单元格类 {@code cellIns}/{@code cellDel}（嵌在 {@code <w:tcPr>} 内）。开关在 {@code
 *       settings.xml} 的 {@code <w:trackChanges/>}。
 *   <li><b>POI</b>：没有 {@code XWPFTrackedChanges} 高层 API；nondocx 用 {@code XmlCursor} 按文档顺序遍历 {@code
 *       CTBody}，按本地名识别修订类型，解析为领域视图。
 *   <li><b>nondocx</b>：统一门面 {@code doc.trackedChanges()}；每条修订有 stable id（进程内稳定）， accept/reject 按
 *       family 分专用方法。
 * </ul>
 *
 * <p><b>RevisionRef 是首选寻址凭证。</b> {@code list_tracked_changes} 同时返回 canonical {@code RevisionRef}
 * 与兼容 stable id。新调用方应把 ref 传给 get/accept/reject；旧 stable id 路径继续可用。
 */
public final class TrackedChangeQueryTools extends ToolkitToolContext {

  /** 接收门面注入的共享会话状态（与 SessionTools 共享同一份 sessions/seq）。 */
  TrackedChangeQueryTools(Map<String, Document> sharedSessions, AtomicInteger sharedSeq) {
    super(sharedSessions, sharedSeq);
  }

  TrackedChangeQueryTools(
      Map<String, Document> sharedSessions,
      AtomicInteger sharedSeq,
      ReferenceContext sharedReferences,
      Map<String, Long> sharedGenerations) {
    super(sharedSessions, sharedSeq, sharedReferences, sharedGenerations);
  }

  /** 读取文档是否开启修订记录（{@code settings.xml} 的 {@code <w:trackChanges/>}）。 */
  @ToolDef(
      name = "get_tracked_changes_enabled",
      description = "返回文档是否开启了修订记录(Word 里勾选「修订」开关)。true=已开启")
  public String getTrackedChangesEnabled(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId) {
    Document doc = document(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    return "修订记录: " + (doc.trackedChanges().enabled() ? "已开启" : "未开启");
  }

  /**
   * 写入修订模式开关（{@code settings.xml} 的 {@code <w:trackChanges/>}）。
   *
   * <p><b>何时用。</b> 文档要交还给人<b>接力编辑</b>、且希望人在 Word 里的后续手动改动也被自动追踪时，把开关打开。 对 Agent 自己用 {@code
   * insert_tracked_run} 等创作的修订<b>无影响</b>(开关只管后续手动改动是否被追踪;已有修订的可见性/可接受性与开关无关)。
   *
   * <p>幂等:重复设为同值不会产生多余写。
   */
  @ToolDef(
      name = "set_tracked_changes_enabled",
      description =
          "开启或关闭修订模式开关(enabled=true 写入 <w:trackChanges/>,=false 移除)。"
              + "用于文档交还人接力编辑时让后续手动改动也被追踪;对 Agent 已创作的修订无影响。幂等。")
  public String setTrackedChangesEnabled(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "enabled", description = "true=开启修订模式,false=关闭") boolean enabled) {
    Document doc = document(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    try {
      if (enabled) {
        doc.trackedChanges().enable();
      } else {
        doc.trackedChanges().disable();
      }
      return "修订记录: " + (doc.trackedChanges().enabled() ? "已开启" : "已关闭") + "(改完需 save_docx 落盘)";
    } catch (RuntimeException e) {
      return "错误:" + rootMessage(e);
    }
  }

  /**
   * 按文档顺序枚举全部修订,每条一行(type/family/author/details 摘要/stable id)。
   *
   * <p><b>返回的 stable id 是后续 accept/reject 工具的寻址凭证</b>。一次调用拿全,不要逐个 get。
   */
  @ToolDef(
      name = "list_tracked_changes",
      description =
          "按文档顺序枚举全部修订(tracked changes),每条返回 type/family/author/details 摘要与 stable id。"
              + "每条同时返回 canonical RevisionRef;accept/reject 优先使用 ref,stable id 继续兼容。"
              + "四种 family:TEXT(ins/del)、MOVE(moveFrom/moveTo)、"
              + "PROPERTY(rPrChange 等)、CELL(cellIns/cellDel/cellMerge)。")
  public String listTrackedChanges(@ToolParam(name = "doc_id", description = "文档句柄") String docId) {
    Document doc = document(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    List<TrackedChange> list = doc.trackedChanges().list();
    if (list.isEmpty()) {
      return "无修订";
    }
    ElementResolver resolver = elementResolver(docId);
    StringBuilder sb = new StringBuilder();
    sb.append("共 ").append(list.size()).append(" 条修订:\n");
    for (int i = 0; i < list.size(); i++) {
      TrackedChange change = list.get(i);
      sb.append('[')
          .append(i)
          .append("] ")
          .append(describeRevision(change, resolver.reference(change)));
      if (i < list.size() - 1) {
        sb.append('\n');
      }
    }
    return sb.toString();
  }

  /** 按稳定 id 取单条修订详情。未命中返回错误串(不要靠它枚举,枚举用 list_tracked_changes)。 */
  @ToolDef(
      name = "get_tracked_change",
      description = "按 canonical RevisionRef 或兼容 stable id 取单条修订详情。枚举请用 list_tracked_changes")
  public String getTrackedChange(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "id", description = "list_tracked_changes 返回的 RevisionRef 或 stable id")
          String id) {
    Document doc = document(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    try {
      ElementResolver resolver = elementResolver(docId);
      TrackedChange change = resolveRevisionInput(docId, doc, id);
      return describeRevision(change, resolver.reference(change));
    } catch (RefResolutionException e) {
      return e.render();
    } catch (RuntimeException e) {
      return "错误:" + rootMessage(e);
    }
  }

  /**
   * 批量处理指定 family 的修订 ids。
   *
   * <p>工具面规划：accept/reject 以及 text/property/cell 是同一个「处理修订」意图的两个维度。 合成一个工具后，Agent 只需选 {@code
   * action=ACCEPT/REJECT} 与 {@code target=TEXT_OR_MOVE/PROPERTY/CELL}， 不再在 6 个近似工具名之间选择。
   */
  @ToolDef(
      name = "apply_tracked_changes",
      description =
          "批量处理修订(ids 可传 list_tracked_changes 返回的 RevisionRef 或兼容 stable id)。"
              + "action 支持 ACCEPT/REJECT;"
              + "target 支持 TEXT_OR_MOVE/PROPERTY/CELL。部分失败不中断,返回每条成功/失败明细。")
  public String applyTrackedChanges(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "action", description = "ACCEPT=应用修订,REJECT=撤销修订") String action,
      @ToolParam(name = "target", description = "TEXT_OR_MOVE/PROPERTY/CELL") String target,
      @ToolParam(name = "ids", description = "stable id 数组,如 [\"ins:1\",\"rpr_change:...\"]")
          List<String> ids) {
    boolean accept;
    if ("ACCEPT".equalsIgnoreCase(action)) {
      accept = true;
    } else if ("REJECT".equalsIgnoreCase(action)) {
      accept = false;
    } else {
      return "错误：action 仅支持 ACCEPT/REJECT";
    }

    if ("TEXT_OR_MOVE".equalsIgnoreCase(target) || "TEXT".equalsIgnoreCase(target)) {
      return applyRevisionsBatch(docId, ids, accept);
    }
    if ("PROPERTY".equalsIgnoreCase(target)) {
      return applyRevisionsByIds(
          docId,
          ids,
          accept ? TrackedChanges::acceptProperty : TrackedChanges::rejectProperty,
          accept ? "应用属性类" : "撤销属性类");
    }
    if ("CELL".equalsIgnoreCase(target)) {
      return applyRevisionsByIds(
          docId,
          ids,
          accept ? TrackedChanges::acceptCell : TrackedChanges::rejectCell,
          accept ? "应用单元格类" : "撤销单元格类");
    }
    return "错误：target 仅支持 TEXT_OR_MOVE/PROPERTY/CELL";
  }

  /**
   * 应用(accept)全部文本/移动类修订,返回处理条数。
   *
   * <p><b>仅作用于 TEXT+MOVE</b>:属性类(rPrChange 等)与单元格类(cellIns/cellDel/cellMerge)<b>不受影响</b>,不会批量删单元格。
   */
  @ToolDef(
      name = "apply_text_revisions",
      description =
          "按范围批量处理文本/移动类修订。action=ACCEPT/REJECT。"
              + "scope=ALL 表示全部文本/移动类修订;scope=AUTHOR 表示只处理指定 author。"
              + "仅作用于文本(ins/del)与移动类;属性类与单元格类不受影响。")
  public String applyTextRevisions(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "action", description = "ACCEPT=应用修订,REJECT=撤销修订") String action,
      @ToolParam(name = "scope", description = "ALL=全部文本/移动类,AUTHOR=指定作者") String scope,
      @ToolParam(name = "author", description = "scope=AUTHOR 时必填,大小写敏感精确匹配", required = false)
          String author) {
    if (action == null || action.isBlank()) {
      return "错误:action 仅支持 ACCEPT 或 REJECT";
    }
    if (scope == null || scope.isBlank()) {
      return "错误:scope 仅支持 ALL 或 AUTHOR";
    }
    String normalizedAction = action.trim().toUpperCase(java.util.Locale.ROOT);
    String normalizedScope = scope.trim().toUpperCase(java.util.Locale.ROOT);
    if ("ALL".equals(normalizedScope)) {
      return applyAllTextRevisions(docId, normalizedAction);
    }
    if ("AUTHOR".equals(normalizedScope)) {
      if (author == null || author.isBlank()) {
        return "错误:scope=AUTHOR 时 author 必填";
      }
      return applyTextRevisionsByAuthor(docId, normalizedAction, author);
    }
    return "错误:scope 仅支持 ALL 或 AUTHOR";
  }

  private String applyAllTextRevisions(String docId, String normalizedAction) {
    Document doc = document(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    try {
      if ("ACCEPT".equals(normalizedAction)) {
        int n = doc.trackedChanges().acceptAll();
        return "已应用 " + n + " 条文本/移动类修订";
      }
      if ("REJECT".equals(normalizedAction)) {
        int n = doc.trackedChanges().rejectAll();
        return "已撤销 " + n + " 条文本/移动类修订";
      }
      return "错误:action 仅支持 ACCEPT 或 REJECT";
    } catch (RuntimeException e) {
      return "错误:" + rootMessage(e);
    }
  }

  private String applyTextRevisionsByAuthor(String docId, String normalizedAction, String author) {
    Document doc = document(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    try {
      if ("ACCEPT".equals(normalizedAction)) {
        int n = doc.trackedChanges().acceptByAuthor(author);
        return "已应用作者「" + author + "」的 " + n + " 条文本/移动类修订";
      }
      if ("REJECT".equals(normalizedAction)) {
        int n = doc.trackedChanges().rejectByAuthor(author);
        return "已撤销作者「" + author + "」的 " + n + " 条文本/移动类修订";
      }
      return "错误:action 仅支持 ACCEPT 或 REJECT";
    } catch (RuntimeException e) {
      return "错误:" + rootMessage(e);
    }
  }

  // ==================== 组内辅助 ====================

  /** 把一条修订渲染为一行中文摘要(type/family/author/details/id/ref)。 */
  private static String describeRevision(TrackedChange change, RevisionRef ref) {
    StringBuilder sb = new StringBuilder();
    sb.append("type=").append(change.type());
    sb.append(", family=").append(change.family());
    sb.append(", author=\"").append(change.author()).append("\"");
    appendDetails(sb, change.details());
    sb.append(", ref=").append(ref.canonical());
    // 保持 id 在行尾，兼容既有调用方按 "id=" 截取整段 stable id。
    sb.append(", id=").append(change.id());
    return sb.toString();
  }

  /** 按 details 子类型把 payload 摘要拼进描述。 */
  private static void appendDetails(StringBuilder sb, ChangeDetails details) {
    if (details instanceof TextChangeDetails) {
      sb.append(", text=\"").append(((TextChangeDetails) details).text()).append("\"");
    } else if (details instanceof PropertyChangeDetails) {
      PropertyChangeDetails p = (PropertyChangeDetails) details;
      sb.append(", property=")
          .append(p.kind())
          .append("(新 ")
          .append(p.newSummary())
          .append("/旧 ")
          .append(p.oldSummary())
          .append(")");
    } else if (details instanceof CellChangeDetails) {
      sb.append(", cell=").append(((CellChangeDetails) details).kind());
    }
  }

  /**
   * 批量文本/移动类 accept/reject 的共享实现(走门面 accept(id)/reject(id),自动处理 move 配对)。
   *
   * <p>逐条尝试:某条抛异常(family 不符/id 不存在)记错误串不中断,core 的 accept/reject 对 move 类会自动 联动配对端。返回每条结果 +
   * 末尾成功/失败汇总,与其它 collect-errors 工具格式一致。
   *
   * <p>委托给通用 {@link #applyRevisionsByIds},传入 {@code TrackedChanges::accept}/{@code reject} 回调。
   */
  private String applyRevisionsBatch(String docId, List<String> ids, boolean accept) {
    // 结果串用自然的"已应用/已撤销"。
    return applyRevisionsByIds(
        docId, ids, accept ? TrackedChanges::accept : TrackedChanges::reject, accept ? "应用" : "撤销");
  }

  /**
   * 三类 accept/reject(text/move、property、cell)的统一批量实现。
   *
   * <p>{@code action} 是对单个 id 执行的动作回调(如 {@code TrackedChanges::acceptProperty}),由各工具传入,从而把三套几乎相同的
   * 循环收成一处。{@code verb} 是结果串里的动词(如"应用属性类")。
   *
   * <p><b>为何可安全逐条循环(id 不漂移)。</b> 探针验证:修订 id 是路径坐标编码(如 {@code cell_ins:body0.table0.row0.cell0:1}),
   * accept/reject 一条后,其余修订的 id 不变——即使 cellDel 的 accept 会移除整个单元格,剩余修订的坐标与 w:id 也不受影响。 故无需排序、去重或重新
   * list。某条抛异常(family 不符/id 不存在)记错误不中断(collect-errors)。
   */
  private String applyRevisionsByIds(
      String docId, List<String> ids, BiConsumer<TrackedChanges, String> action, String verb) {
    Document doc = document(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    List<Object> list = coerceList(ids);
    if (list.isEmpty()) {
      return "ids 为空";
    }
    TrackedChanges tc = doc.trackedChanges();
    ElementResolver resolver = elementResolver(docId);
    StringBuilder sb = new StringBuilder();
    int ok = 0;
    int fail = 0;
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      String input = String.valueOf(list.get(i));
      try {
        TrackedChange change = resolveRevisionInput(docId, doc, input);
        RevisionRef ref = resolver.reference(change);
        action.accept(tc, change.id());
        sb.append("[")
            .append(i)
            .append("] ")
            .append(change.id())
            .append(" 已")
            .append(verb)
            .append(" ref=")
            .append(ref.canonical())
            .append(" ✓");
        ok++;
      } catch (RefResolutionException e) {
        sb.append("[").append(i).append("] ").append(e.render());
        fail++;
      } catch (RuntimeException e) {
        sb.append("[")
            .append(i)
            .append("] ")
            .append(input)
            .append(": 错误(")
            .append(rootMessage(e))
            .append(")");
        fail++;
      }
    }
    sb.append("\n成功 ").append(ok).append(" 条,失败 ").append(fail).append(" 条");
    return sb.toString();
  }

  private TrackedChange resolveRevisionInput(String docId, Document doc, String input) {
    if (input == null || input.isBlank()) {
      throw new IllegalArgumentException("修订 ref/id 不能为空");
    }
    String normalized = input.trim();
    if (!normalized.startsWith("doc:")) {
      return doc.trackedChanges().get(normalized);
    }
    ElementRef parsed = ElementRefs.parse(normalized);
    if (!(parsed instanceof RevisionRef)) {
      throw new RefResolutionException(RefResolutionCode.REF_TYPE_MISMATCH, "该操作只接受 RevisionRef");
    }
    return elementResolver(docId).resolve((RevisionRef) parsed);
  }
}
