package com.non.docx.toolkit.view;

import com.non.chain.tool.ToolDef;
import com.non.chain.tool.ToolParam;
import com.non.docx.core.api.Document;
import com.non.docx.toolkit.QualityCheckTools;
import com.non.docx.toolkit.ToolkitToolContext;
import com.non.docx.toolkit.capability.CapabilityOperation;
import com.non.docx.toolkit.capability.ParamCapability;
import com.non.docx.toolkit.capability.ParamType;
import com.non.docx.toolkit.capability.ToolCapability;
import com.non.docx.toolkit.ref.RefResolutionException;
import com.non.docx.toolkit.ref.ReferenceContext;
import com.non.docx.toolkit.result.ToolResult;
import com.non.docx.toolkit.result.ToolResultCode;
import com.non.docx.toolkit.result.ToolResultRenderer;
import com.non.docx.toolkit.view.dto.AnnotatedView;
import com.non.docx.toolkit.view.dto.ElementView;
import com.non.docx.toolkit.view.dto.IssuesView;
import com.non.docx.toolkit.view.dto.OutlineView;
import com.non.docx.toolkit.view.dto.StatsView;
import com.non.docx.toolkit.view.dto.TextView;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 文档语义视图工具组（第 9 个工具类）：6 个 {@code view_*} 工具，为 Agent 提供低成本、可控上下文的语义视图。
 *
 * <p>每个工具是 {@link DocumentViewService} 的薄适配层——只做 docId 解析 + 参数校验 + {@link ToolResult} envelope
 * 包装，不包含业务逻辑。全部返回双段 String（中文消息 + JSON envelope），经 {@link ToolResultRenderer} 序列化。
 *
 * <p><b>6 个工具：</b>
 *
 * <ul>
 *   <li>{@code view_outline}——标题树/section/表格/图片/TOC/页眉页脚/修订概览。
 *   <li>{@code view_text}——按文档顺序输出文本 + 元素引用。
 *   <li>{@code view_annotated}——文本 + run 直接格式 + ref。
 *   <li>{@code view_stats}——段落/表格/图片/字体/字号/修订统计。
 *   <li>{@code view_issues}——质量检查问题列表。
 *   <li>{@code view_element}——按 ref 获取单元素结构化详情。
 * </ul>
 */
public final class ViewTools extends ToolkitToolContext {

  private final DocumentViewService viewService;

  /**
   * 接收门面注入的共享会话状态 + QualityCheckTools 引用（供 issues 视图复用检查逻辑）。
   *
   * @param sharedSessions 与 SessionTools 共享的文档会话 Map
   * @param sharedSeq 与 SessionTools 共享的 docId 序号
   * @param sharedReferences 与 SessionTools 共享的引用上下文
   * @param sharedGenerations 与 SessionTools 共享的会话代次 Map
   * @param qualityCheckTools 质量检查工具组（供 issues 视图复用）
   */
  public ViewTools(
      Map<String, Document> sharedSessions,
      AtomicInteger sharedSeq,
      ReferenceContext sharedReferences,
      Map<String, Long> sharedGenerations,
      QualityCheckTools qualityCheckTools) {
    super(sharedSessions, sharedSeq, sharedReferences, sharedGenerations);
    this.viewService = new DocumentViewService(sharedReferences, qualityCheckTools);
  }

  /** 暴露内部视图服务供测试直接调用（不经 String 边界）。 */
  DocumentViewService viewService() {
    return viewService;
  }

  /** 大纲视图：标题/段落/表格 + section/TOC/页眉页脚/修订概览。 */
  @ToolDef(
      name = "view_outline",
      description =
          "返回文档大纲视图：按 body 顺序的标题/段落/表格 + section/TOC/页眉页脚/修订概览。"
              + "每项带 canonical ref，可用于后续修改定位。支持 max_items（默认 200）和 text_truncate（默认 120）控制上下文。")
  @ToolCapability(operation = CapabilityOperation.READ, element = "document")
  public String viewOutline(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "max_items", description = "最大返回条数（默认 200）", required = false)
          @ParamCapability(type = ParamType.INTEGER)
          Integer maxItems,
      @ToolParam(name = "text_truncate", description = "文本截断长度（默认 120）", required = false)
          @ParamCapability(type = ParamType.INTEGER)
          Integer textTruncate) {
    Document doc = document(docId);
    if (doc == null) {
      return renderDocClosed(docId);
    }
    long generation = generations.getOrDefault(docId, 1L);
    ViewQuery query = buildQuery(maxItems, textTruncate, null);
    OutlineView view = viewService.outline(doc, docId, generation, query);
    String message =
        "大纲视图\n"
            + view.entries().size()
            + " 项"
            + (view.meta().truncated() ? "（已截断，共 " + view.meta().totalCount() + "）" : "");
    ToolResult<OutlineView> result = ToolResult.ok(view, message);
    return ToolResultRenderer.render(result);
  }

  /** 文本视图：按文档顺序输出文本 + 元素引用。 */
  @ToolDef(
      name = "view_text",
      description =
          "返回文档文本视图：按 body 顺序输出段落/表格文本，每项带 canonical ref。"
              + "支持 max_items（默认 200）和 text_truncate（默认 120）控制上下文。")
  @ToolCapability(operation = CapabilityOperation.READ, element = "document")
  public String viewText(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "max_items", description = "最大返回条数（默认 200）", required = false)
          @ParamCapability(type = ParamType.INTEGER)
          Integer maxItems,
      @ToolParam(name = "text_truncate", description = "文本截断长度（默认 120）", required = false)
          @ParamCapability(type = ParamType.INTEGER)
          Integer textTruncate) {
    Document doc = document(docId);
    if (doc == null) {
      return renderDocClosed(docId);
    }
    long generation = generations.getOrDefault(docId, 1L);
    ViewQuery query = buildQuery(maxItems, textTruncate, null);
    TextView view = viewService.text(doc, docId, generation, query);
    String message =
        "文本视图\n"
            + view.entries().size()
            + " 项"
            + (view.meta().truncated() ? "（已截断，共 " + view.meta().totalCount() + "）" : "");
    ToolResult<TextView> result = ToolResult.ok(view, message);
    return ToolResultRenderer.render(result);
  }

  /** annotated 视图：文本 + run 直接格式 + ref。 */
  @ToolDef(
      name = "view_annotated",
      description =
          "返回文档标注视图：段落级文本 + 按需展开的 run 直接格式（bold/italic/font/size/color）。"
              + "expand_runs 默认 false（只给段落级文本）；定位到目标后设 expand_runs=true 取 run 明细。"
              + "支持 max_items（默认 200）。")
  @ToolCapability(operation = CapabilityOperation.READ, element = "paragraph")
  public String viewAnnotated(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "max_items", description = "最大返回条数（默认 200）", required = false)
          @ParamCapability(type = ParamType.INTEGER)
          Integer maxItems,
      @ToolParam(name = "expand_runs", description = "是否展开 run 级明细（默认 false）", required = false)
          @ParamCapability(type = ParamType.BOOLEAN)
          Boolean expandRuns) {
    Document doc = document(docId);
    if (doc == null) {
      return renderDocClosed(docId);
    }
    long generation = generations.getOrDefault(docId, 1L);
    ViewQuery query = buildQuery(maxItems, null, expandRuns);
    AnnotatedView view = viewService.annotated(doc, docId, generation, query);
    String message =
        "标注视图\n"
            + view.paragraphs().size()
            + " 段"
            + (view.meta().truncated() ? "（已截断，共 " + view.meta().totalCount() + "）" : "")
            + (expandRuns != null && expandRuns ? "，已展开 run" : "");
    ToolResult<AnnotatedView> result = ToolResult.ok(view, message);
    return ToolResultRenderer.render(result);
  }

  /** 统计视图：段落/表格/图片/字体/字号/修订统计。 */
  @ToolDef(
      name = "view_stats",
      description = "返回文档统计视图：段落/表格/图片/section/修订数量 + 字体/字号聚合。" + "了解文档规模和格式分布时优先用它。")
  @ToolCapability(operation = CapabilityOperation.READ, element = "document")
  public String viewStats(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId) {
    Document doc = document(docId);
    if (doc == null) {
      return renderDocClosed(docId);
    }
    long generation = generations.getOrDefault(docId, 1L);
    StatsView view = viewService.stats(doc, docId, generation);
    String message =
        "统计视图\n"
            + "段落数: "
            + view.paragraphCount()
            + "\n表格数: "
            + view.tableCount()
            + "\n图片数: "
            + view.imageCount()
            + "\nsection 数: "
            + view.sectionCount();
    ToolResult<StatsView> result = ToolResult.ok(view, message);
    return ToolResultRenderer.render(result);
  }

  /** 问题视图：质量检查问题列表。 */
  @ToolDef(
      name = "view_issues",
      description =
          "返回文档问题视图：跑 10 项内置质量检查（空白页/行距/表格分页/图片溢出/字体/CJK缩进/标题层级/底纹/TOC/清洁度），"
              + "返回未通过项的结构化列表。可选 severity 过滤（error/warning）。")
  @ToolCapability(operation = CapabilityOperation.QUALITY, element = "document")
  public String viewIssues(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "severity", description = "按严重级别过滤：error 或 warning（空则全部）", required = false)
          @ParamCapability(
              type = ParamType.ENUM,
              enumValues = {"error", "warning"})
          String severity) {
    Document doc = document(docId);
    if (doc == null) {
      return renderDocClosed(docId);
    }
    long generation = generations.getOrDefault(docId, 1L);
    IssuesView view = viewService.issues(doc, docId, generation, severity);
    String message =
        "问题视图\n"
            + "通过: "
            + view.passedCount()
            + "，警告: "
            + view.warningCount()
            + "，错误: "
            + view.errorCount()
            + "，未通过项: "
            + view.issues().size();
    ToolResult<IssuesView> result = ToolResult.ok(view, message);
    return ToolResultRenderer.render(result);
  }

  /** 单元素详情视图：按 ref 获取一个元素的结构化详情。 */
  @ToolDef(
      name = "view_element",
      description =
          "按 canonical ref 获取单个元素的结构化详情（段落/表格/run）。"
              + "ref 来自 view_outline/view_text/view_annotated 的返回值。支持 paragraph/table/run 三种元素。")
  @ToolCapability(operation = CapabilityOperation.READ, element = "element")
  public String viewElement(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "ref", description = "canonical 元素引用字符串")
          @ParamCapability(type = ParamType.REF)
          String ref) {
    Document doc = document(docId);
    if (doc == null) {
      return renderDocClosed(docId);
    }
    long generation = generations.getOrDefault(docId, 1L);
    try {
      ElementView view = viewService.element(doc, docId, generation, ref);
      String message = "元素详情\n类型: " + view.kind() + "\nref: " + view.ref();
      ToolResult<ElementView> result = ToolResult.ok(view, message);
      return ToolResultRenderer.render(result);
    } catch (RefResolutionException e) {
      ToolResult<Void> result =
          ToolResult.fail(e.code().toToolResultCode(), e.getMessage(), "使用 view_outline 获取有效 ref");
      return ToolResultRenderer.render(result);
    } catch (IllegalArgumentException e) {
      ToolResult<Void> result =
          ToolResult.fail(ToolResultCode.INVALID_REF, "无法解析 ref：" + e.getMessage());
      return ToolResultRenderer.render(result);
    }
  }

  // ==================== 内部辅助 ====================

  private static ViewQuery buildQuery(Integer maxItems, Integer textTruncate, Boolean expandRuns) {
    ViewQuery query = ViewQuery.defaults();
    if (maxItems != null && maxItems > 0) {
      query = query.withMaxItems(maxItems);
    }
    if (textTruncate != null && textTruncate > 0) {
      query = query.withTextTruncate(textTruncate);
    }
    if (expandRuns != null) {
      query = query.withExpandRuns(expandRuns);
    }
    return query;
  }

  private static String renderDocClosed(String docId) {
    ToolResult<Void> result =
        ToolResult.fail(
            ToolResultCode.DOCUMENT_CLOSED, "文档句柄 " + docId + " 不存在（未 open_docx 或已 close_docx）");
    return ToolResultRenderer.render(result);
  }
}
