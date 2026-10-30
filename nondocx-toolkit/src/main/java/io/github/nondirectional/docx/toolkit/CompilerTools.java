package io.github.nondirectional.docx.toolkit;

import com.non.chain.tool.ToolDef;
import com.non.chain.tool.ToolParam;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.toolkit.capability.CapabilityLevel;
import io.github.nondirectional.docx.toolkit.capability.CapabilityOperation;
import io.github.nondirectional.docx.toolkit.capability.NestedParamCapability;
import io.github.nondirectional.docx.toolkit.capability.ParamCapability;
import io.github.nondirectional.docx.toolkit.capability.ParamType;
import io.github.nondirectional.docx.toolkit.capability.ToolCapability;
import io.github.nondirectional.docx.toolkit.compiler.DocumentCompiler;
import io.github.nondirectional.docx.toolkit.compiler.MarkdownDoc;
import io.github.nondirectional.docx.toolkit.compiler.MarkdownParser;
import io.github.nondirectional.docx.toolkit.compiler.MarkdownView;
import io.github.nondirectional.docx.toolkit.compiler.PatchEngine;
import io.github.nondirectional.docx.toolkit.compiler.StyleProfile;
import io.github.nondirectional.docx.toolkit.result.ToolResult;
import io.github.nondirectional.docx.toolkit.result.ToolResultCode;
import io.github.nondirectional.docx.toolkit.result.ToolResultRenderer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编译器工具组（第 9 组，实验性）：「文档 IR + 编译器」路线的 Agent 入口。
 *
 * <p><b>它解决什么问题。</b> 既有工具面把「写文档」拆成几十个细粒度 API 调用，新建一份 文档要 10+ 次 insert/update；本组把「整篇文档的表达」收敛为一次
 * Markdown 提交—— {@code create_document(markdown, style_profile)} 解析、套用样式 profile、经 nondocx-core 编译成
 * docx，排版脏活（CJK 双字体槽、行距换算、首行缩进、表头底纹、 WPS 安全列宽）全部由编译器兜底。读侧 {@code read_document} 返回带短 ID 的 Markdown
 * 视图，与写侧同构（读到什么格式，就能照着写什么格式）。
 *
 * <p><b>会话语义。</b> {@code create_document} 用编译产物<b>替换</b> {@code doc_id} 会话
 * 句柄背后的文档：句柄不变、generation+1、旧元素引用失效——与 {@code SessionTools.reopen} 同一条换血路径（{@code
 * ToolkitToolContext#replaceSessionDocument}）。失败路径（解析/ 编译错误）不触碰会话，旧文档原样保留。
 *
 * <p><b>实验性标记。</b> 本组处于路线图 P1 spike 阶段（{@code docs/11}），默认不进 demo/eval 的 full 工具面；能力清单以
 * EXPERIMENTAL 级别收录。
 */
public final class CompilerTools extends ToolkitToolContext {

  private final QualityCheckTools qualityCheck;

  /**
   * @param sharedSessions 门面注入的共享会话
   * @param sharedSeq 共享 docId 序号
   * @param sharedReferences 共享元素引用上下文
   * @param sharedGenerations 共享 generation 状态
   * @param qualityCheck 质检工具组（validate_document 复用其 10 项检查）
   */
  public CompilerTools(
      Map<String, Document> sharedSessions,
      java.util.concurrent.atomic.AtomicInteger sharedSeq,
      io.github.nondirectional.docx.toolkit.ref.ReferenceContext sharedReferences,
      Map<String, Long> sharedGenerations,
      QualityCheckTools qualityCheck) {
    super(sharedSessions, sharedSeq, sharedReferences, sharedGenerations);
    this.qualityCheck = qualityCheck;
  }

  // ==================== 工具方法 ====================

  /**
   * 用 Markdown 编译生成一份新文档，替换 doc_id 当前会话文档。
   *
   * @param docId 文档句柄（保持不变，generation+1）
   * @param markdown 整份文档的 Markdown（支持 # 标题、粗体、斜体、行内代码、列表、表格、{align=…} 属性）
   * @param styleProfile 样式 profile 名：report-cn（中文报告）/ plain（纯结构）
   * @return 编译结果（段落数/表格数/warnings）
   */
  @ToolDef(
      name = "create_document",
      description =
          "用一份 Markdown 一次性生成整份 docx 文档，替换 doc_id 当前的文档（句柄不变）。"
              + "Markdown 支持：# ~ ###### 标题（可加 {align=center} 属性）、段落、**粗体**、"
              + "*斜体*、`代码`、- 无序列表、1. 有序列表、|表格|（首行为表头）。"
              + "style_profile=report-cn 时自动套中文报告排版（黑体标题、宋体小四正文、"
              + "1.5 倍行距、首行缩进 2 字符、表头加粗灰底）；plain 只做结构。"
              + "新建/整篇重写文档时用它，不要逐段 insert。")
  @ToolCapability(
      operation = CapabilityOperation.ADD,
      element = "document",
      level = CapabilityLevel.EXPERIMENTAL)
  public String createDocument(
      @ToolParam(name = "doc_id", description = "文档句柄（生成的新文档将替换它）")
          @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "markdown", description = "整份文档的 Markdown 文本")
          @ParamCapability(type = ParamType.STRING)
          String markdown,
      @ToolParam(
              name = "style_profile",
              description = "样式 profile：report-cn（中文报告）/ plain（纯结构）",
              required = false)
          @ParamCapability(type = ParamType.STRING)
          String styleProfile) {
    Document doc = document(docId);
    if (doc == null) {
      return renderDocNotFound(docId);
    }
    try {
      StyleProfile profile =
          styleProfile == null || styleProfile.isBlank()
              ? StyleProfile.builtin("report-cn")
              : StyleProfile.builtin(styleProfile.trim());
      if (profile == null) {
        return renderInvalidArgument(
            "未知样式 profile: "
                + styleProfile
                + "（可选: "
                + String.join("/", StyleProfile.builtins().keySet())
                + "）");
      }
      // 先完整解析+编译，成功后才替换会话——失败路径不触碰旧文档。
      MarkdownDoc parsed = MarkdownParser.parse(markdown);
      DocumentCompiler.CompiledDocument compiled = DocumentCompiler.compile(parsed, profile);
      replaceSessionDocument(docId, compiled.document());
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("docId", docId);
      data.put("generation", generations.getOrDefault(docId, 1L));
      data.put("styleProfile", profile.name());
      data.put("paragraphCount", compiled.paragraphCount());
      data.put("tableCount", compiled.tableCount());
      data.put("warnings", compiled.warnings());
      String message =
          "已用 Markdown 编译新文档并替换 "
              + docId
              + "：段落数 "
              + compiled.paragraphCount()
              + "，表格数 "
              + compiled.tableCount()
              + "，profile="
              + profile.name()
              + (compiled.warnings().isEmpty() ? "" : "，警告 " + compiled.warnings().size() + " 条");
      return ToolResultRenderer.render(ToolResult.ok(data, message));
    } catch (IllegalArgumentException e) {
      return renderInvalidArgument(e.getMessage());
    } catch (RuntimeException e) {
      return ToolResultRenderer.render(
          ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "编译失败: " + rootMessage(e)));
    }
  }

  /**
   * 读取整份文档的 Markdown 视图（带短 ID）。
   *
   * @param docId 文档句柄
   * @param maxChars 输出上限（默认 6000，超过按行截断）
   * @return Markdown 视图 + 段落/表格计数
   */
  @ToolDef(
      name = "read_document",
      description =
          "读取整份文档的紧凑 Markdown 视图：标题层级、粗斜体、列表、表格全保留，"
              + "每个段落/表格带 <!-- pN --> / <!-- tN --> 短 ID。"
              + "了解整篇文档结构/内容时优先用它，不要逐段 read_paragraph。")
  @ToolCapability(
      operation = CapabilityOperation.READ,
      element = "document",
      level = CapabilityLevel.EXPERIMENTAL)
  public String readDocument(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "max_chars", description = "输出字符上限（默认 6000）", required = false)
          @ParamCapability(type = ParamType.NUMBER)
          Integer maxChars) {
    Document doc = document(docId);
    if (doc == null) {
      return renderDocNotFound(docId);
    }
    MarkdownView.Rendered rendered =
        MarkdownView.render(doc, maxChars == null ? MarkdownView.DEFAULT_MAX_CHARS : maxChars);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("docId", docId);
    data.put("markdown", rendered.markdown());
    data.put("paragraphCount", rendered.paragraphs());
    data.put("tableCount", rendered.tables());
    data.put("truncated", rendered.truncated());
    String message =
        "文档 Markdown 视图（段落 "
            + rendered.paragraphs()
            + "，表格 "
            + rendered.tables()
            + (rendered.truncated() ? "，已截断" : "")
            + "）";
    return ToolResultRenderer.render(ToolResult.ok(data, message));
  }

  /**
   * 对整份文档跑 10 项确定性质量检查，返回带编号的问题清单。
   *
   * @param docId 文档句柄
   * @return 问题数组（id/severity/message/suggestion），通过时 issues 为空
   */
  @ToolDef(
      name = "validate_document",
      description =
          "对文档跑结构/版式/兼容性质量检查（空白页、行距、SOLID 底纹、表格分页、图片溢出等），" + "返回带 v1/v2 编号的问题清单。修改完成后调用它确认无版式问题。")
  @ToolCapability(
      operation = CapabilityOperation.QUALITY,
      element = "document",
      level = CapabilityLevel.EXPERIMENTAL)
  public String validateDocument(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId) {
    Document doc = document(docId);
    if (doc == null) {
      return renderDocNotFound(docId);
    }
    List<QualityCheckTools.CheckResult> results = qualityCheck.runAllChecks(doc);
    List<Map<String, Object>> issues = new ArrayList<>();
    int failed = 0;
    for (QualityCheckTools.CheckResult r : results) {
      if (r.passed()) {
        continue;
      }
      failed++;
      Map<String, Object> issue = new LinkedHashMap<>();
      issue.put("id", "v" + failed);
      issue.put("check", r.name());
      issue.put("severity", r.severity());
      issue.put("message", r.message());
      issues.add(issue);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("docId", docId);
    data.put("totalChecks", results.size());
    data.put("failed", failed);
    data.put("issues", issues);
    String message =
        failed == 0
            ? "质量检查全部通过（" + results.size() + " 项）"
            : "质量检查发现 " + failed + " 个问题（共 " + results.size() + " 项检查）";
    return ToolResultRenderer.render(ToolResult.ok(data, message));
  }

  /**
   * 对既有文档应用一批原子编辑（patch）。
   *
   * @param docId 文档句柄
   * @param ops op 数组，每个 op 至少含 {@code op} 字段；锚点用 {@code read_document} 的短 ID
   * @param dryRun 只返回计划不写入
   * @param styleProfile set_style 套用的样式 profile
   * @param expectedGeneration 调用方持有的 generation（不匹配则拒绝）
   * @return applied/changed/warnings（dry_run 时为 planned 清单）
   */
  @ToolDef(
      name = "edit_document",
      description =
          "对当前文档应用一批原子编辑 ops。锚点是 read_document 输出的短 ID（pN=段落，tN=表格）。"
              + "op 类型：insert（position=before/after/start/end + block=Markdown 块，含表格时仅 end）、"
              + "replace_text（anchor+find+text，段落内精确子串）、delete（anchor 段落）、"
              + "set_style（anchor + style=body/heading 或 align/line_spacing）、"
              + "table.set_cell（anchor+row+col+text）、table.add_row（anchor+cells）、"
              + "tracked.insert/tracked.delete/tracked.replace（修订模式，anchor+find/text+author 必传，"
              + "修订可被 Word 审阅 accept/reject）、"
              + "footer.set_page_number（页脚页码，format 含 {page}/{pages} 占位符，默认「第 {page} 页」，不占 anchor）、"
              + "comment.add（批注，anchor+text+author 必传，给整段加范围批注，不改正文）。"
              + "先校验全部 op 再写入：任一 op 非法则整体拒绝、零改动。"
              + "每个段落锚点一批里至多一个改文本 op（tracked.*/replace_text），要改多处请拆两次调用。"
              + "多步编辑优先用它一次表达，不要逐段调用。dry_run=true 只返回计划。")
  @ToolCapability(
      operation = CapabilityOperation.UPDATE,
      element = "document",
      level = CapabilityLevel.EXPERIMENTAL)
  public String editDocument(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(
              name = "ops",
              description =
                  "op 对象数组，如 [{\"op\":\"insert\",\"anchor\":\"p3\",\"position\":\"after\","
                      + "\"block\":\"# 小节 {align=center}\"}, {\"op\":\"table.set_cell\",\"anchor\":\"t0\","
                      + "\"row\":1,\"col\":2,\"text\":\"完成\"}, {\"op\":\"tracked.replace\","
                      + "\"anchor\":\"p1\",\"find\":\"一百万元\",\"text\":\"两百万元\",\"author\":\"审查员\"}, "
                      + "{\"op\":\"comment.add\",\"anchor\":\"p1\",\"text\":\"金额需复核\","
                      + "\"author\":\"审查员\"}, {\"op\":\"footer.set_page_number\"}]")
          @NestedParamCapability(path = "ops.op", type = ParamType.STRING)
          @NestedParamCapability(path = "ops.anchor", type = ParamType.STRING)
          @NestedParamCapability(path = "ops.position", type = ParamType.STRING)
          @NestedParamCapability(path = "ops.block", type = ParamType.STRING)
          @NestedParamCapability(path = "ops.find", type = ParamType.STRING)
          @NestedParamCapability(path = "ops.text", type = ParamType.STRING)
          @NestedParamCapability(path = "ops.style", type = ParamType.STRING)
          @NestedParamCapability(path = "ops.align", type = ParamType.STRING)
          @NestedParamCapability(path = "ops.line_spacing", type = ParamType.NUMBER)
          @NestedParamCapability(path = "ops.row", type = ParamType.INTEGER)
          @NestedParamCapability(path = "ops.col", type = ParamType.INTEGER)
          @NestedParamCapability(path = "ops.cells", type = ParamType.STRING_ARRAY)
          @NestedParamCapability(path = "ops.author", type = ParamType.STRING)
          @NestedParamCapability(path = "ops.format", type = ParamType.STRING)
          List<Map<String, Object>> ops,
      @ToolParam(name = "dry_run", description = "只返回计划不写入（默认 false）", required = false)
          @ParamCapability(type = ParamType.BOOLEAN)
          Boolean dryRun,
      @ToolParam(
              name = "style_profile",
              description = "set_style 套用的样式 profile（默认 report-cn）",
              required = false)
          @ParamCapability(type = ParamType.STRING)
          String styleProfile,
      @ToolParam(
              name = "expected_generation",
              description = "调用方持有的 session generation，与当前不符则拒绝写入",
              required = false)
          @ParamCapability(type = ParamType.INTEGER)
          Integer expectedGeneration) {
    Document doc = document(docId);
    if (doc == null) {
      return renderDocNotFound(docId);
    }
    if (!checkExpectedGeneration(docId, expectedGeneration)) {
      long current = generations.getOrDefault(docId, 1L);
      return renderGenerationMismatch(
          expectedGeneration == null ? current : expectedGeneration, current);
    }
    StyleProfile profile =
        styleProfile == null || styleProfile.isBlank()
            ? StyleProfile.builtin("report-cn")
            : StyleProfile.builtin(styleProfile.trim());
    if (profile == null) {
      return renderInvalidArgument(
          "未知样式 profile: "
              + styleProfile
              + "（可选: "
              + String.join("/", StyleProfile.builtins().keySet())
              + "）");
    }
    try {
      PatchEngine.PatchResult result =
          PatchEngine.apply(doc, coerceList(ops), profile, Boolean.TRUE.equals(dryRun));
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("docId", docId);
      data.put("generation", generations.getOrDefault(docId, 1L));
      if (result.dryRun()) {
        data.put("dryRun", true);
        data.put("planned", result.planned());
        data.put("warnings", result.warnings());
        String message = "dry-run 通过：将应用 " + result.applied() + " 个 op（未写入）";
        return ToolResultRenderer.render(ToolResult.ok(data, message));
      }
      data.put("applied", result.applied());
      data.put("changed", result.changed());
      data.put("warnings", result.warnings());
      String message =
          "已应用 "
              + result.applied()
              + " 个 op，触碰 "
              + result.changed().size()
              + " 个锚点"
              + (result.warnings().isEmpty() ? "" : "，警告 " + result.warnings().size() + " 条");
      return ToolResultRenderer.render(ToolResult.ok(data, message));
    } catch (PatchEngine.PatchException e) {
      // preflight 失败 = 零写入，明确告知
      return renderInvalidArgument(e.getMessage() + "（已拒绝，文档未被修改）");
    } catch (RuntimeException e) {
      return ToolResultRenderer.render(
          ToolResult.fail(
              ToolResultCode.INVALID_ARGUMENT,
              "patch 执行异常（文档可能被部分修改，建议 read_document 核对）: " + rootMessage(e)));
    }
  }
}
