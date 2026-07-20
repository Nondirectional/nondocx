package io.github.nondirectional.docx.toolkit;

import com.non.chain.tool.ToolDef;
import com.non.chain.tool.ToolParam;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.track.CellChangeDetails;
import io.github.nondirectional.docx.core.api.track.ChangeDetails;
import io.github.nondirectional.docx.core.api.track.PropertyChangeDetails;
import io.github.nondirectional.docx.core.api.track.TextChangeDetails;
import io.github.nondirectional.docx.core.api.track.TrackedChange;
import io.github.nondirectional.docx.core.api.track.TrackedChanges;
import io.github.nondirectional.docx.toolkit.capability.CapabilityOperation;
import io.github.nondirectional.docx.toolkit.capability.ParamCapability;
import io.github.nondirectional.docx.toolkit.capability.ParamType;
import io.github.nondirectional.docx.toolkit.capability.ToolCapability;
import io.github.nondirectional.docx.toolkit.ref.ElementRef;
import io.github.nondirectional.docx.toolkit.ref.ElementRefs;
import io.github.nondirectional.docx.toolkit.ref.ElementResolver;
import io.github.nondirectional.docx.toolkit.ref.RefResolutionCode;
import io.github.nondirectional.docx.toolkit.ref.RefResolutionException;
import io.github.nondirectional.docx.toolkit.ref.ReferenceContext;
import io.github.nondirectional.docx.toolkit.ref.RevisionRef;
import io.github.nondirectional.docx.toolkit.result.ToolResult;
import io.github.nondirectional.docx.toolkit.result.ToolResultCode;
import io.github.nondirectional.docx.toolkit.result.ToolResultRenderer;
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
  @ToolCapability(operation = CapabilityOperation.QUERY, element = "tracked_change")
  public String getTrackedChangesEnabled(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId) {
    Document doc = document(docId);
    if (doc == null) {
      return renderDocNotFound(docId);
    }
    boolean enabled = doc.trackedChanges().enabled();
    String message = "修订记录: " + (enabled ? "已开启" : "未开启");
    ToolResult<Boolean> result = ToolResult.ok(enabled, message);
    return ToolResultRenderer.render(result);
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
              + "用于文档交还人接力编辑时让后续手动改动也被追踪;对 Agent 已创作的修订无影响。幂等。"
              + "可选 expected_generation 防止旧快照修改新状态。")
  @ToolCapability(operation = CapabilityOperation.UPDATE, element = "document")
  public String setTrackedChangesEnabled(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "enabled", description = "true=开启修订模式,false=关闭")
          @ParamCapability(type = ParamType.BOOLEAN)
          boolean enabled,
      @ToolParam(
              name = "expected_generation",
              description = "可选。调用方持有的 session generation,与当前不符则拒绝写入。不传则跳过校验。",
              required = false)
          @ParamCapability(type = ParamType.INTEGER)
          Integer expectedGeneration) {
    Document doc = document(docId);
    if (doc == null) {
      return renderDocNotFound(docId);
    }
    if (!checkExpectedGeneration(docId, expectedGeneration)) {
      return renderGenerationMismatch(expectedGeneration, generations.getOrDefault(docId, 1L));
    }
    try {
      if (enabled) {
        doc.trackedChanges().enable();
      } else {
        doc.trackedChanges().disable();
      }
      String message =
          "修订记录: " + (doc.trackedChanges().enabled() ? "已开启" : "已关闭") + "(改完需 save_docx 落盘)";
      ToolResult<Void> result = ToolResult.ok(message);
      return ToolResultRenderer.render(result);
    } catch (RuntimeException e) {
      ToolResult<Void> result = ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, rootMessage(e));
      return ToolResultRenderer.render(result);
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
  @ToolCapability(operation = CapabilityOperation.READ, element = "tracked_change")
  public String listTrackedChanges(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId) {
    Document doc = document(docId);
    if (doc == null) {
      return renderDocNotFound(docId);
    }
    List<TrackedChange> list = doc.trackedChanges().list();
    if (list.isEmpty()) {
      ToolResult<Integer> result = ToolResult.ok(0, "无修订");
      return ToolResultRenderer.render(result);
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
    ToolResult<Integer> result = ToolResult.ok(list.size(), sb.toString());
    return ToolResultRenderer.render(result);
  }

  /** 按稳定 id 取单条修订详情。未命中返回错误串(不要靠它枚举,枚举用 list_tracked_changes)。 */
  @ToolDef(
      name = "get_tracked_change",
      description = "按 canonical RevisionRef 或兼容 stable id 取单条修订详情。枚举请用 list_tracked_changes")
  @ToolCapability(operation = CapabilityOperation.READ, element = "tracked_change")
  public String getTrackedChange(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "id", description = "list_tracked_changes 返回的 RevisionRef 或 stable id")
          @ParamCapability(type = ParamType.REF)
          String id) {
    Document doc = document(docId);
    if (doc == null) {
      return renderDocNotFound(docId);
    }
    try {
      ElementResolver resolver = elementResolver(docId);
      TrackedChange change = resolveRevisionInput(docId, doc, id);
      String description = describeRevision(change, resolver.reference(change));
      ToolResult<String> result = ToolResult.ok(change.id(), description);
      return ToolResultRenderer.render(result);
    } catch (RefResolutionException e) {
      ToolResult<Void> result = ToolResult.fail(e.code().toToolResultCode(), e.render());
      return ToolResultRenderer.render(result);
    } catch (RuntimeException e) {
      ToolResult<Void> result = ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, rootMessage(e));
      return ToolResultRenderer.render(result);
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
              + "target 支持 TEXT_OR_MOVE/PROPERTY/CELL。部分失败不中断,返回每条成功/失败明细。"
              + "on_error=stop 时遇首条失败即停;expected_generation 防止旧快照修改新状态。")
  @ToolCapability(operation = CapabilityOperation.UPDATE, element = "tracked_change")
  public String applyTrackedChanges(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "action", description = "ACCEPT=应用修订,REJECT=撤销修订")
          @ParamCapability(
              type = ParamType.ENUM,
              enumValues = {"ACCEPT", "REJECT"})
          String action,
      @ToolParam(name = "target", description = "TEXT_OR_MOVE/PROPERTY/CELL")
          @ParamCapability(
              type = ParamType.ENUM,
              enumValues = {"TEXT_OR_MOVE", "PROPERTY", "CELL"})
          String target,
      @ToolParam(name = "ids", description = "stable id 数组,如 [\"ins:1\",\"rpr_change:...\"]")
          @ParamCapability(type = ParamType.STRING_ARRAY)
          List<String> ids,
      @ToolParam(
              name = "on_error",
              description = "continue=失败不中断(默认),stop=遇首条失败即停",
              required = false)
          @ParamCapability(
              type = ParamType.ENUM,
              enumValues = {"continue", "stop"})
          String onError,
      @ToolParam(
              name = "expected_generation",
              description = "可选。调用方持有的 session generation,与当前不符则拒绝写入。不传则跳过校验。",
              required = false)
          @ParamCapability(type = ParamType.INTEGER)
          Integer expectedGeneration) {
    boolean accept;
    if ("ACCEPT".equalsIgnoreCase(action)) {
      accept = true;
    } else if ("REJECT".equalsIgnoreCase(action)) {
      accept = false;
    } else {
      ToolResult<Void> result =
          ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "action 仅支持 ACCEPT/REJECT");
      return ToolResultRenderer.render(result);
    }
    boolean stopOnError = "stop".equalsIgnoreCase(onError);
    Document doc = document(docId);
    if (doc == null) {
      return renderDocNotFound(docId);
    }
    if (!checkExpectedGeneration(docId, expectedGeneration)) {
      return renderGenerationMismatch(expectedGeneration, generations.getOrDefault(docId, 1L));
    }

    if ("TEXT_OR_MOVE".equalsIgnoreCase(target) || "TEXT".equalsIgnoreCase(target)) {
      return applyRevisionsBatch(docId, ids, accept, stopOnError);
    }
    if ("PROPERTY".equalsIgnoreCase(target)) {
      return applyRevisionsByIds(
          docId,
          ids,
          accept ? TrackedChanges::acceptProperty : TrackedChanges::rejectProperty,
          accept ? "应用属性类" : "撤销属性类",
          stopOnError);
    }
    if ("CELL".equalsIgnoreCase(target)) {
      return applyRevisionsByIds(
          docId,
          ids,
          accept ? TrackedChanges::acceptCell : TrackedChanges::rejectCell,
          accept ? "应用单元格类" : "撤销单元格类",
          stopOnError);
    }
    ToolResult<Void> result =
        ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "target 仅支持 TEXT_OR_MOVE/PROPERTY/CELL");
    return ToolResultRenderer.render(result);
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
              + "仅作用于文本(ins/del)与移动类;属性类与单元格类不受影响。"
              + "scope=ALL 时需显式传 confirm_all=true 确认(防止意外全量修改)。"
              + "expected_generation 防止旧快照修改新状态。")
  @ToolCapability(operation = CapabilityOperation.UPDATE, element = "tracked_change")
  public String applyTextRevisions(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "action", description = "ACCEPT=应用修订,REJECT=撤销修订")
          @ParamCapability(
              type = ParamType.ENUM,
              enumValues = {"ACCEPT", "REJECT"})
          String action,
      @ToolParam(name = "scope", description = "ALL=全部文本/移动类,AUTHOR=指定作者")
          @ParamCapability(
              type = ParamType.ENUM,
              enumValues = {"ALL", "AUTHOR"})
          String scope,
      @ToolParam(name = "author", description = "scope=AUTHOR 时必填,大小写敏感精确匹配", required = false)
          @ParamCapability(type = ParamType.STRING)
          String author,
      @ToolParam(
              name = "confirm_all",
              description = "scope=ALL 时必填 true 以确认全量修改(防止意外批量处理全部修订)",
              required = false)
          @ParamCapability(type = ParamType.BOOLEAN)
          Boolean confirmAll,
      @ToolParam(
              name = "expected_generation",
              description = "可选。调用方持有的 session generation,与当前不符则拒绝写入。不传则跳过校验。",
              required = false)
          @ParamCapability(type = ParamType.INTEGER)
          Integer expectedGeneration) {
    if (action == null || action.isBlank()) {
      ToolResult<Void> r =
          ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "action 仅支持 ACCEPT 或 REJECT");
      return ToolResultRenderer.render(r);
    }
    if (scope == null || scope.isBlank()) {
      ToolResult<Void> r =
          ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "scope 仅支持 ALL 或 AUTHOR");
      return ToolResultRenderer.render(r);
    }
    String normalizedAction = action.trim().toUpperCase(java.util.Locale.ROOT);
    String normalizedScope = scope.trim().toUpperCase(java.util.Locale.ROOT);
    if ("ALL".equals(normalizedScope)) {
      if (!Boolean.TRUE.equals(confirmAll)) {
        ToolResult<Void> r =
            ToolResult.fail(
                ToolResultCode.INVALID_ARGUMENT,
                "scope=ALL 会一次性处理全部文本/移动类修订,需显式传 confirm_all=true 确认",
                "传 confirm_all=true 确认全量修改,或改用 scope=AUTHOR 限定范围");
        return ToolResultRenderer.render(r);
      }
      Document doc = document(docId);
      if (doc == null) {
        return renderDocNotFound(docId);
      }
      if (!checkExpectedGeneration(docId, expectedGeneration)) {
        return renderGenerationMismatch(expectedGeneration, generations.getOrDefault(docId, 1L));
      }
      return applyAllTextRevisions(docId, normalizedAction);
    }
    if ("AUTHOR".equals(normalizedScope)) {
      if (author == null || author.isBlank()) {
        ToolResult<Void> r =
            ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "scope=AUTHOR 时 author 必填");
        return ToolResultRenderer.render(r);
      }
      Document doc = document(docId);
      if (doc == null) {
        return renderDocNotFound(docId);
      }
      if (!checkExpectedGeneration(docId, expectedGeneration)) {
        return renderGenerationMismatch(expectedGeneration, generations.getOrDefault(docId, 1L));
      }
      return applyTextRevisionsByAuthor(docId, normalizedAction, author);
    }
    ToolResult<Void> r = ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "scope 仅支持 ALL 或 AUTHOR");
    return ToolResultRenderer.render(r);
  }

  private String applyAllTextRevisions(String docId, String normalizedAction) {
    Document doc = document(docId);
    if (doc == null) {
      return renderDocNotFound(docId);
    }
    try {
      int n;
      String verb;
      if ("ACCEPT".equals(normalizedAction)) {
        n = doc.trackedChanges().acceptAll();
        verb = "应用";
      } else if ("REJECT".equals(normalizedAction)) {
        n = doc.trackedChanges().rejectAll();
        verb = "撤销";
      } else {
        ToolResult<Void> r =
            ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "action 仅支持 ACCEPT 或 REJECT");
        return ToolResultRenderer.render(r);
      }
      ToolResult<Integer> result = ToolResult.ok(n, "已" + verb + " " + n + " 条文本/移动类修订");
      return ToolResultRenderer.render(result);
    } catch (RuntimeException e) {
      ToolResult<Void> r = ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, rootMessage(e));
      return ToolResultRenderer.render(r);
    }
  }

  private String applyTextRevisionsByAuthor(String docId, String normalizedAction, String author) {
    Document doc = document(docId);
    if (doc == null) {
      return renderDocNotFound(docId);
    }
    try {
      int n;
      String verb;
      if ("ACCEPT".equals(normalizedAction)) {
        n = doc.trackedChanges().acceptByAuthor(author);
        verb = "应用";
      } else if ("REJECT".equals(normalizedAction)) {
        n = doc.trackedChanges().rejectByAuthor(author);
        verb = "撤销";
      } else {
        ToolResult<Void> r =
            ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "action 仅支持 ACCEPT 或 REJECT");
        return ToolResultRenderer.render(r);
      }
      ToolResult<Integer> result =
          ToolResult.ok(n, "已" + verb + "作者「" + author + "」的 " + n + " 条文本/移动类修订");
      return ToolResultRenderer.render(result);
    } catch (RuntimeException e) {
      ToolResult<Void> r = ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, rootMessage(e));
      return ToolResultRenderer.render(r);
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
  private String applyRevisionsBatch(
      String docId, List<String> ids, boolean accept, boolean stopOnError) {
    // 结果串用自然的"已应用/已撤销"。
    return applyRevisionsByIds(
        docId,
        ids,
        accept ? TrackedChanges::accept : TrackedChanges::reject,
        accept ? "应用" : "撤销",
        stopOnError);
  }

  /**
   * 三类 accept/reject(text/move、property、cell)的统一批量实现。
   *
   * <p>{@code action} 是对单个 id 执行的动作回调(如 {@code TrackedChanges::acceptProperty}),由各工具传入,从而把三套几乎相同的
   * 循环收成一处。{@code verb} 是结果串里的动词(如"应用属性类")。
   *
   * <p><b>为何可安全逐条循环(id 不漂移)。</b> 探针验证:修订 id 是路径坐标编码(如 {@code cell_ins:body0.table0.row0.cell0:1}),
   * accept/reject 一条后,其余修订的 id 不变——即使 cellDel 的 accept 会移除整个单元格,剩余修订的坐标与 w:id 也不受影响。 故无需排序、去重或重新
   * list。某条抛异常(family 不符/id 不存在)记错误不中断(collect-errors)。 {@code stopOnError=true} 时遇首条失败即停。
   */
  private String applyRevisionsByIds(
      String docId,
      List<String> ids,
      BiConsumer<TrackedChanges, String> action,
      String verb,
      boolean stopOnError) {
    Document doc = document(docId);
    if (doc == null) {
      return renderDocNotFound(docId);
    }
    List<Object> list = coerceList(ids);
    if (list.isEmpty()) {
      ToolResult<Void> r = ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "ids 为空");
      return ToolResultRenderer.render(r);
    }
    TrackedChanges tc = doc.trackedChanges();
    ElementResolver resolver = elementResolver(docId);
    StringBuilder sb = new StringBuilder();
    int ok = 0;
    int fail = 0;
    int stoppedAt = -1;
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
        if (stopOnError) {
          stoppedAt = i;
          break;
        }
      } catch (RuntimeException e) {
        sb.append("[")
            .append(i)
            .append("] ")
            .append(input)
            .append(": 错误(")
            .append(rootMessage(e))
            .append(")");
        fail++;
        if (stopOnError) {
          stoppedAt = i;
          break;
        }
      }
    }
    int skipped = stoppedAt >= 0 ? list.size() - stoppedAt - 1 : 0;
    sb.append("\n成功 ").append(ok).append(" 条,失败 ").append(fail).append(" 条");
    int matchedCount = ok + fail;
    ToolResult<Integer> result =
        fail > 0
            ? ToolResult.partial(
                ToolResultCode.PARTIAL_FAILURE,
                ok,
                sb.toString(),
                java.util.Collections.emptyList(),
                matchedCount,
                ok,
                skipped > 0 ? skipped : null)
            : ToolResult.ok(ok, sb.toString(), matchedCount, ok, null);
    return ToolResultRenderer.render(result);
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

  /** 兼容旧 Java 调用；等价于未传 expected_generation。 */
  @Deprecated
  public String setTrackedChangesEnabled(String docId, boolean enabled) {
    return setTrackedChangesEnabled(docId, enabled, null);
  }

  /** 兼容旧 Java 调用；等价于 on_error=continue 且未传 expected_generation。 */
  @Deprecated
  public String applyTrackedChanges(String docId, String action, String target, List<String> ids) {
    return applyTrackedChanges(docId, action, target, ids, null, null);
  }

  /** 兼容旧 Java 调用；scope=ALL 仍必须通过新 API 显式传 confirmAll=true。 */
  @Deprecated
  public String applyTextRevisions(String docId, String action, String scope, String author) {
    return applyTextRevisions(docId, action, scope, author, false, null);
  }
}
