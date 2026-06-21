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
 * <p><b>id 是寻址凭证。</b> 所有单条 accept/reject 工具都按 stable id 定位。Agent 必须先 list_tracked_changes 拿到
 * id，再调对应 accept/reject（同 search_text 之于 replace_run_text）。
 */
public final class TrackedChangeQueryTools extends ToolkitToolContext {

  /** 接收门面注入的共享会话状态（与 SessionTools 共享同一份 sessions/seq）。 */
  TrackedChangeQueryTools(Map<String, Document> sharedSessions, AtomicInteger sharedSeq) {
    super(sharedSessions, sharedSeq);
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
              + "accept/reject 前先用它拿到 id。四种 family:TEXT(ins/del)、MOVE(moveFrom/moveTo)、"
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
    StringBuilder sb = new StringBuilder();
    sb.append("共 ").append(list.size()).append(" 条修订:\n");
    for (int i = 0; i < list.size(); i++) {
      sb.append('[').append(i).append("] ").append(describeRevision(list.get(i)));
      if (i < list.size() - 1) {
        sb.append('\n');
      }
    }
    return sb.toString();
  }

  /** 按稳定 id 取单条修订详情。未命中返回错误串(不要靠它枚举,枚举用 list_tracked_changes)。 */
  @ToolDef(
      name = "get_tracked_change",
      description = "按 stable id 取单条修订的详情(列表里看到的 id)。枚举请用 list_tracked_changes")
  public String getTrackedChange(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "id", description = "list_tracked_changes 返回的 stable id") String id) {
    Document doc = document(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    try {
      return describeRevision(doc.trackedChanges().get(id));
    } catch (RuntimeException e) {
      return "错误:" + rootMessage(e);
    }
  }

  /**
   * 批量应用(accept)文本/移动类修订:插入生效、删除生效;移动类两端联动配对。
   *
   * <p><b>批量语义（v2）。</b> 入参是<b>stable id 数组</b> {@code ids},长度 1 即处理单条,多个即一次处理多条
   * ——典型场景:list_tracked_changes 后想接受<b>特定几条</b>(非全量 accept_all,也非按作者),目前只能逐条调, 批量版压成一次。
   *
   * <p>仅作用于 TEXT(ins/del)与 MOVE(moveFrom/moveTo)family;属性类用 {@code accept_property_change},单元格类用
   * {@code accept_cell_change}。
   *
   * <p><b>失败语义:collect-errors。</b> 逐条尝试,某条 family 不符/id 不存在记错误串不中断整批;末尾汇总成功/失败条数。 移动类 id 在
   * accept/reject 时由 core 自动联动配对的另一端(moveFrom↔moveTo),无需 Agent 手动配对。
   */
  @ToolDef(
      name = "accept_text_or_move_revision",
      description =
          "批量应用(accept)文本/移动类修订(ids 来自 list_tracked_changes)。"
              + "ins/插入保留、del/删除生效;move 两端联动配对。"
              + "ids 是 stable id 数组,长度 1 即处理单条。"
              + "属性类/单元格类请改用对应专用工具;部分失败不中断,返回每条成功/失败明细。")
  public String acceptTextOrMoveRevision(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "ids", description = "stable id 数组,如 [\"ins:1\",\"del:2\"];单条传 [\"ins:1\"]")
          List<String> ids) {
    return applyRevisionsBatch(docId, ids, /* accept= */ true);
  }

  /**
   * 批量撤销(reject)文本/移动类修订:插入丢弃、删除恢复;移动类两端联动。
   *
   * <p><b>批量语义（v2）。</b> 入参是<b>stable id 数组</b> {@code ids},长度 1 即处理单条,多个即一次处理多条。语义、失败语义、 family 限定与
   * {@link #acceptTextOrMoveRevision} 完全对称,只是动作从 accept 变 reject。
   */
  @ToolDef(
      name = "reject_text_or_move_revision",
      description =
          "批量撤销(reject)文本/移动类修订(ids 来自 list_tracked_changes)。"
              + "ins/插入丢弃、del/删除恢复;move 两端联动。"
              + "ids 是 stable id 数组,长度 1 即处理单条。"
              + "属性类/单元格类请改用对应专用工具;部分失败不中断,返回每条成功/失败明细。")
  public String rejectTextOrMoveRevision(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "ids", description = "stable id 数组,如 [\"ins:1\",\"del:2\"];单条传 [\"ins:1\"]")
          List<String> ids) {
    return applyRevisionsBatch(docId, ids, /* accept= */ false);
  }

  /**
   * 应用(accept)全部文本/移动类修订,返回处理条数。
   *
   * <p><b>仅作用于 TEXT+MOVE</b>:属性类(rPrChange 等)与单元格类(cellIns/cellDel/cellMerge)<b>不受影响</b>,不会批量删单元格。
   */
  @ToolDef(
      name = "accept_all_text_revisions",
      description = "应用(accept)全部文本/移动类修订,返回处理条数。仅作用于文本(ins/del)与移动类;" + "属性类与单元格类不受影响(不会批量删单元格)。")
  public String acceptAllTextRevisions(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId) {
    Document doc = document(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    try {
      int n = doc.trackedChanges().acceptAll();
      return "已应用 " + n + " 条文本/移动类修订";
    } catch (RuntimeException e) {
      return "错误:" + rootMessage(e);
    }
  }

  /** 撤销(reject)全部文本/移动类修订,返回处理条数。仅作用于 TEXT+MOVE,不动 property/cell。 */
  @ToolDef(
      name = "reject_all_text_revisions",
      description = "撤销(reject)全部文本/移动类修订,返回处理条数。仅作用于文本与移动类;属性类与单元格类不受影响。")
  public String rejectAllTextRevisions(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId) {
    Document doc = document(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    try {
      int n = doc.trackedChanges().rejectAll();
      return "已撤销 " + n + " 条文本/移动类修订";
    } catch (RuntimeException e) {
      return "错误:" + rootMessage(e);
    }
  }

  /**
   * 应用(accept)指定作者的全部文本/移动类修订。作者大小写敏感精确匹配。
   *
   * <p>仅作用于 TEXT+MOVE;property/cell 不受影响。
   */
  @ToolDef(
      name = "accept_text_revisions_by_author",
      description = "应用(accept)指定 author 的全部文本/移动类修订(大小写敏感精确匹配),返回处理条数。" + "仅文本与移动类;属性类与单元格类不受影响。")
  public String acceptTextRevisionsByAuthor(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "author", description = "修订作者(大小写敏感精确匹配)") String author) {
    Document doc = document(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    try {
      int n = doc.trackedChanges().acceptByAuthor(author);
      return "已应用作者「" + author + "」的 " + n + " 条文本/移动类修订";
    } catch (RuntimeException e) {
      return "错误:" + rootMessage(e);
    }
  }

  /** 撤销(reject)指定作者的全部文本/移动类修订。仅作用于 TEXT+MOVE。 */
  @ToolDef(
      name = "reject_text_revisions_by_author",
      description = "撤销(reject)指定 author 的全部文本/移动类修订(大小写敏感精确匹配),返回处理条数。" + "仅文本与移动类;属性类与单元格类不受影响。")
  public String rejectTextRevisionsByAuthor(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "author", description = "修订作者(大小写敏感精确匹配)") String author) {
    Document doc = document(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    try {
      int n = doc.trackedChanges().rejectByAuthor(author);
      return "已撤销作者「" + author + "」的 " + n + " 条文本/移动类修订";
    } catch (RuntimeException e) {
      return "错误:" + rootMessage(e);
    }
  }

  /**
   * 批量应用(accept)属性类修订(rPrChange 等):保留新(当前)属性树,移除 *PrChange 标记。
   *
   * <p><b>批量语义（v2）。</b> 入参是<b>id 数组</b> {@code ids}(来自 list_tracked_changes),长度 1 即处理单条。仅作用于
   * PROPERTY family;文本/移动类用 {@code accept_text_or_move_revision},单元格类用 {@code accept_cell_change}。
   *
   * <p><b>失败语义:collect-errors。</b> 探针验证:id 是路径坐标编码、accept 一条后其余 id 不漂移,故可安全逐条循环。 family 不符/id
   * 不存在的条目记错误不中断。
   */
  @ToolDef(
      name = "accept_property_change",
      description =
          "批量应用(accept)属性类修订(rPrChange:ids 来自 list_tracked_changes)。"
              + "ids 是 stable id 数组,长度 1 即处理单条。保留新属性树、移除 *PrChange 标记。"
              + "仅属性类;文本/单元格类请用对应工具。部分失败不中断,返回每条成功/失败明细。")
  public String acceptPropertyChange(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "ids", description = "stable id 数组,如 [\"rpr:...\"];单条传 [\"rpr:...\"]")
          List<String> ids) {
    return applyRevisionsByIds(docId, ids, TrackedChanges::acceptProperty, "应用属性类");
  }

  /**
   * 批量撤销(reject)属性类修订:用旧(pristine)属性树覆盖新树,移除 *PrChange 标记。
   *
   * <p><b>批量语义（v2）。</b> 与 {@link #acceptPropertyChange} 对称,动作变 reject。仅 PROPERTY family。
   */
  @ToolDef(
      name = "reject_property_change",
      description =
          "批量撤销(reject)属性类修订(rPrChange:ids 来自 list_tracked_changes)。"
              + "ids 是 stable id 数组,长度 1 即处理单条。用旧属性树覆盖新树、移除 *PrChange 标记。"
              + "仅属性类。部分失败不中断,返回每条成功/失败明细。")
  public String rejectPropertyChange(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "ids", description = "stable id 数组,如 [\"rpr:...\"];单条传 [\"rpr:...\"]")
          List<String> ids) {
    return applyRevisionsByIds(docId, ids, TrackedChanges::rejectProperty, "撤销属性类");
  }

  /**
   * 批量应用(accept)单元格结构类修订:作用于<b>整个 {@code <w:tc>} 单元格</b>(不是标记本身)。
   *
   * <p><b>批量语义（v2）。</b> 入参是<b>id 数组</b> {@code ids}(来自 list_tracked_changes),长度 1 即处理单条。
   *
   * <p>语义:cellIns accept=保留单元格、删标记;cellDel accept=<b>移除整个单元格</b>。
   *
   * <p><b>cellMerge 不支持</b>(其 CT 类型在 POI 精简 schema 下缺失),命中 cellMerge 的 id 会返回错误串。仅 CELL family。
   *
   * <p><b>失败语义:collect-errors。</b> 探针验证:即使 accept 一条 cellDel 移除了整个单元格,其余修订的 id(路径坐标编码)仍不漂移,
   * 故可安全逐条循环。family 不符/id 不存在的条目记错误不中断。
   */
  @ToolDef(
      name = "accept_cell_change",
      description =
          "批量应用(accept)单元格结构类修订(cellIns/cellDel:ids 来自 list_tracked_changes)。"
              + "ids 是 stable id 数组,长度 1 即处理单条。作用于整个单元格:"
              + "cellIns=保留单元格、cellDel=移除整个单元格。"
              + "cellMerge 的 accept 不支持(会返回错误串)。仅单元格类。"
              + "部分失败不中断,返回每条成功/失败明细。")
  public String acceptCellChange(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(
              name = "ids",
              description = "stable id 数组,如 [\"cell_ins:...\"];单条传 [\"cell_ins:...\"]")
          List<String> ids) {
    return applyRevisionsByIds(docId, ids, TrackedChanges::acceptCell, "应用单元格类");
  }

  /**
   * 批量撤销(reject)单元格结构类修订:作用于整个 {@code <w:tc>}。
   *
   * <p><b>批量语义（v2）。</b> 与 {@link #acceptCellChange} 对称。语义:cellIns reject=<b>移除整个单元格</b>(插入被撤销);
   * cellDel reject=保留单元格、删标记。cellMerge 不支持。
   */
  @ToolDef(
      name = "reject_cell_change",
      description =
          "批量撤销(reject)单元格结构类修订(cellIns/cellDel:ids 来自 list_tracked_changes)。"
              + "ids 是 stable id 数组,长度 1 即处理单条。作用于整个单元格:"
              + "cellIns=移除整个单元格、cellDel=保留单元格。cellMerge 不支持。仅单元格类。"
              + "部分失败不中断,返回每条成功/失败明细。")
  public String rejectCellChange(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(
              name = "ids",
              description = "stable id 数组,如 [\"cell_ins:...\"];单条传 [\"cell_ins:...\"]")
          List<String> ids) {
    return applyRevisionsByIds(docId, ids, TrackedChanges::rejectCell, "撤销单元格类");
  }

  // ==================== 组内辅助 ====================

  /** 把一条修订渲染为一行中文摘要(type/family/author/details/id)。 */
  private static String describeRevision(TrackedChange change) {
    StringBuilder sb = new StringBuilder();
    sb.append("type=").append(change.type());
    sb.append(", family=").append(change.family());
    sb.append(", author=\"").append(change.author()).append("\"");
    appendDetails(sb, change.details());
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
    StringBuilder sb = new StringBuilder();
    int ok = 0;
    int fail = 0;
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      String id = String.valueOf(list.get(i));
      try {
        action.accept(tc, id);
        sb.append("[").append(i).append("] ").append(id).append(" 已").append(verb).append(" ✓");
        ok++;
      } catch (RuntimeException e) {
        sb.append("[")
            .append(i)
            .append("] ")
            .append(id)
            .append(": 错误(")
            .append(rootMessage(e))
            .append(")");
        fail++;
      }
    }
    sb.append("\n成功 ").append(ok).append(" 条,失败 ").append(fail).append(" 条");
    return sb.toString();
  }
}
