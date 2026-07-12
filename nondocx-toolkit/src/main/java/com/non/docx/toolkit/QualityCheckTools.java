package com.non.docx.toolkit;

import com.non.chain.tool.ToolDef;
import com.non.chain.tool.ToolParam;
import com.non.docx.core.api.BodyElement;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.InlineElement;
import com.non.docx.core.api.image.Image;
import com.non.docx.core.api.section.PaperSize;
import com.non.docx.core.api.section.Section;
import com.non.docx.core.api.style.HeadingLevel;
import com.non.docx.core.api.table.Cell;
import com.non.docx.core.api.table.Row;
import com.non.docx.core.api.table.Table;
import com.non.docx.core.api.text.Paragraph;
import com.non.docx.core.api.text.Run;
import com.non.docx.toolkit.capability.CapabilityOperation;
import com.non.docx.toolkit.capability.ParamCapability;
import com.non.docx.toolkit.capability.ParamType;
import com.non.docx.toolkit.capability.ToolCapability;
import com.non.docx.toolkit.ref.ReferenceContext;
import com.non.docx.toolkit.result.ToolResult;
import com.non.docx.toolkit.result.ToolResultCode;
import com.non.docx.toolkit.result.ToolResultRenderer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STShd;

/**
 * 文档质量自检工具组（第 7 个工具类）。
 *
 * <p>对内存中的 {@link Document} 跑版式/兼容性自检，返回结构化字符串报告给 Agent。让 LLM 写完文档后能自查「版式有没有问题」， 而不必肉眼排查或反复打开
 * Word/WPS 验证。
 *
 * <p><b>OOXML 三层递进（检查执行模型）：</b>
 *
 * <ul>
 *   <li><b>OOXML</b>：docx 的版式属性散落在 {@code word/document.xml} 的各元素（{@code <w:p>} 行距、{@code <w:tbl>}
 *       分页控制、{@code <w:tc>} 底纹等）。
 *   <li><b>POI</b>：{@code XWPFDocument} 持有这些元素的 CT bean 树，但部分属性（如 {@code tblHeader}/{@code
 *       cantSplit}） 无高级 API。
 *   <li><b>nondocx</b>：已建模了行距、标题、底纹、表格分页等属性的活对象 API。本工具<b>主体走 nondocx 活对象 API</b>
 *       （复用投资，会话不跟踪文件路径，磁盘方案成本高）；少数 XML 级检查（如 SOLID 底纹误用）走 {@code raw()} 兜底。
 * </ul>
 *
 * <p><b>借鉴来源</b>：检查规则借鉴自 docx skill 的 {@code scripts/postcheck.py}（15 项业务规则自检），但实现 走 nondocx
 * 内存活对象而非 postcheck.py 的「解包读 XML」磁盘方案。兼容性类检查引用子任务 1 沉淀的 {@code renderer-compatibility.md} 锚点。
 *
 * <p>本类<b>只报告，不修复</b>——修复留给 Agent 调用其它工具方法。
 */
public final class QualityCheckTools extends ToolkitToolContext {

  /** 全部内置检查项的注册名（按执行顺序）。 */
  static final List<String> ALL_CHECKS =
      List.of(
          "blank-pages",
          "line-spacing",
          "table-pagination",
          "image-overflow",
          "font-fallback",
          "cjk-indent",
          "heading-levels",
          "shading-solid",
          "toc",
          "cleanliness");

  /** font-fallback 检查的安全字体白名单（跨平台常见预装字体）。 */
  private static final Set<String> SAFE_FONTS =
      Set.of(
          "宋体",
          "黑体",
          "simsun",
          "simhei",
          "microsoft yahei",
          "微软雅黑",
          "arial",
          "times new roman",
          "calibri",
          "cambria",
          "helvetica",
          "fangsong",
          "仿宋",
          "kaiti",
          "楷体");

  /** cleanliness 检查的占位符/草稿措辞正则。 */
  private static final Pattern PLACEHOLDER =
      Pattern.compile(
          "TODO|FIXME|XXX|待填写|待补充|占位|placeholder|lorem ipsum", Pattern.CASE_INSENSITIVE);

  /** cleanliness 检查的 Markdown 残留正则。 */
  private static final Pattern MARKDOWN =
      Pattern.compile("(^|\\n)#{1,6}\\s|\\*\\*[^*]+\\*\\*|__[^_]+__|\\[[^]]+]\\([^)]+\\)|`[^`]+`");

  /** CJK 字符检测正则。 */
  private static final Pattern CJK = Pattern.compile("[\u4e00-\u9fff]");

  /** 接收门面注入的共享会话状态（与 SessionTools 共享同一份 sessions/seq）。 */
  QualityCheckTools(Map<String, Document> sharedSessions, AtomicInteger sharedSeq) {
    super(sharedSessions, sharedSeq);
  }

  QualityCheckTools(
      Map<String, Document> sharedSessions,
      AtomicInteger sharedSeq,
      ReferenceContext sharedReferences,
      Map<String, Long> sharedGenerations) {
    super(sharedSessions, sharedSeq, sharedReferences, sharedGenerations);
  }

  /**
   * 对 {@code doc_id} 指定的文档跑版式/兼容性自检，返回 ❌/⚠️/✅ 报告。
   *
   * <p>主体走 nondocx 内存活对象 API（行距、标题、底纹、表格分页等）；少数 XML 级检查走 {@code raw()} 兜底。 兼容性类检查引用 {@code
   * renderer-compatibility.md} 锚点。只报告，不修复。
   *
   * @param docId 文档句柄
   * @param checks 检查项数组（空或 null 则跑全量）；未知项会在报告里标注为跳过
   * @return 结构化报告字符串；docId 不存在则返回 {@code docNotFound} 错误串
   */
  @ToolDef(
      name = "check_quality",
      description =
          "对 doc_id 指定的文档跑版式/兼容性自检，返回 ❌/⚠️/✅ 报告。"
              + "可选 checks 数组指定跑哪些（空则全量）：blank-pages, line-spacing, table-pagination, "
              + "image-overflow, font-fallback, cjk-indent, heading-levels, shading-solid, toc, cleanliness。"
              + "兼容性问题会引用 renderer-compatibility.md 锚点。只报告不修复。")
  @ToolCapability(operation = CapabilityOperation.QUALITY, element = "document")
  public String checkQuality(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "checks", description = "检查项数组（空则全量）", required = false)
          @ParamCapability(
              type = ParamType.STRING_ARRAY,
              enumValues = {
                "blank-pages", "line-spacing", "table-pagination", "image-overflow",
                "font-fallback", "cjk-indent", "heading-levels", "shading-solid",
                "toc", "cleanliness"
              })
          List<String> checks) {
    Document doc = document(docId);
    if (doc == null) {
      ToolResult<Void> result =
          ToolResult.fail(
              ToolResultCode.DOCUMENT_CLOSED, "文档句柄 " + docId + " 不存在（未 open_docx 或已 close_docx）");
      return ToolResultRenderer.render(result);
    }
    List<String> selected = resolveChecks(checks);
    List<CheckResult> results = new ArrayList<>();
    for (String name : selected) {
      try {
        results.add(runCheck(name, doc));
      } catch (Exception e) {
        results.add(new CheckResult(name, false, "检查异常: " + rootMessage(e), "error"));
      }
    }
    return formatReport(docId, results);
  }

  /** 解析 checks 入参（null/空 → 全量；未知项保留，交给 runCheck 生成可见提示）。 */
  private List<String> resolveChecks(List<String> checks) {
    if (checks == null || checks.isEmpty()) {
      return ALL_CHECKS;
    }
    List<String> selected = new ArrayList<>();
    for (String c : checks) {
      if (c != null && !c.isBlank()) {
        selected.add(c);
      }
    }
    return selected;
  }

  /** 按检查名分发到对应方法。 */
  private CheckResult runCheck(String name, Document doc) {
    switch (name) {
      case "blank-pages":
        return checkBlankPages(doc);
      case "line-spacing":
        return checkLineSpacing(doc);
      case "table-pagination":
        return checkTablePagination(doc);
      case "image-overflow":
        return checkImageOverflow(doc);
      case "font-fallback":
        return checkFontFallback(doc);
      case "cjk-indent":
        return checkCjkIndent(doc);
      case "heading-levels":
        return checkHeadingLevels(doc);
      case "shading-solid":
        return checkShadingSolid(doc);
      case "toc":
        return checkToc(doc);
      case "cleanliness":
        return checkCleanliness(doc);
      default:
        return new CheckResult(name, true, "未知检查项，已跳过");
    }
  }

  // ==================== 10 项检查 ====================

  /** 检查 1：连续空段（≥3 个）。 */
  private CheckResult checkBlankPages(Document doc) {
    int consecutive = 0;
    int maxRun = 0;
    int blankRuns = 0;
    for (Paragraph p : doc.paragraphs()) {
      if (p.text().trim().isEmpty()) {
        consecutive++;
        if (consecutive > maxRun) {
          maxRun = consecutive;
        }
      } else {
        if (consecutive >= 3) {
          blankRuns++;
        }
        consecutive = 0;
      }
    }
    if (consecutive >= 3) {
      blankRuns++;
    }
    if (blankRuns == 0) {
      return new CheckResult("blank-pages", true, "无连续空段（≥3）");
    }
    return new CheckResult(
        "blank-pages",
        false,
        "发现 " + blankRuns + " 处连续 ≥3 空段（最长 " + maxRun + " 段），可能产生空白页",
        "warning");
  }

  /** 检查 2：行距一致性（正文段落行距是否统一）。 */
  private CheckResult checkLineSpacing(Document doc) {
    Map<Double, Integer> counts = new HashMap<>();
    for (Paragraph p : bodyParagraphs(doc)) {
      if (p.heading() != null) {
        continue; // 跳过标题
      }
      double s = p.lineSpacing();
      // -1.0 表示未设，归到"未设"桶
      counts.merge(s, 1, Integer::sum);
    }
    // 只看显式设置的行距种类（排除"未设"桶 -1.0）
    Set<Double> setSpacings = new HashSet<>();
    for (Double s : counts.keySet()) {
      if (s >= 0) {
        setSpacings.add(s);
      }
    }
    if (setSpacings.size() <= 1) {
      return new CheckResult("line-spacing", true, "正文行距一致");
    }
    StringBuilder detail = new StringBuilder();
    for (Map.Entry<Double, Integer> e : counts.entrySet()) {
      if (e.getKey() < 0) {
        detail.append("未设（").append(e.getValue()).append("段）、");
      } else {
        detail.append(e.getKey()).append("（").append(e.getValue()).append("段）、");
      }
    }
    return new CheckResult(
        "line-spacing",
        false,
        "正文存在 " + setSpacings.size() + " 种行距：" + stripTrailing(detail.toString()),
        "warning");
  }

  /** 检查 4：表格分页控制（首行 headerRow + 数据行 cantSplit）。 */
  private CheckResult checkTablePagination(Document doc) {
    List<String> issues = new ArrayList<>();
    List<Table> tables = doc.tables();
    for (int ti = 0; ti < tables.size(); ti++) {
      Table t = tables.get(ti);
      if (t.rows().size() < 2) {
        continue; // 单行表格无需分页控制
      }
      Row first = t.row(0);
      if (!first.headerRow()) {
        issues.add("表格 " + (ti + 1) + " 首行未设 headerRow（跨页时表头不重复）");
      }
      int missingCantSplit = 0;
      for (int ri = 1; ri < t.rows().size(); ri++) {
        if (!t.row(ri).cantSplit()) {
          missingCantSplit++;
        }
      }
      if (missingCantSplit > 0) {
        issues.add("表格 " + (ti + 1) + " 有 " + missingCantSplit + " 个数据行未设 cantSplit（可能跨页拆分）");
      }
    }
    if (issues.isEmpty()) {
      return new CheckResult("table-pagination", true, "表格分页控制设置正确");
    }
    return new CheckResult("table-pagination", false, String.join("；", issues), "warning");
  }

  /** 检查 5：图片溢出（宽度超页面可用区）。 */
  private CheckResult checkImageOverflow(Document doc) {
    List<Section> sections = doc.sections();
    if (sections.isEmpty()) {
      return new CheckResult("image-overflow", true, "无章节可计算可用宽度");
    }
    Section sect = sections.get(0);
    PaperSize paper = sect.paperSize();
    if (paper == null) {
      paper = PaperSize.A4; // 未设纸张时按 A4 兜底（OOXML 默认）
    }
    int usableTwips = paper.widthTwips() - sect.marginLeft() - sect.marginRight();
    List<String> overflows = new ArrayList<>();
    int imgIdx = 0;
    for (Paragraph p : doc.paragraphs()) {
      for (InlineElement ie : p.inlineElements()) {
        if (ie instanceof Image) {
          imgIdx++;
          Image img = (Image) ie;
          // width() 是 96 DPI 像素，换算到 twips：px * 1440 / 96 = px * 15
          int widthTwips = img.width() * 15;
          if (widthTwips > usableTwips) {
            overflows.add(
                "图片 "
                    + imgIdx
                    + "（宽 "
                    + img.width()
                    + "px/"
                    + widthTwips
                    + "twips > 可用 "
                    + usableTwips
                    + "twips）");
          }
        }
      }
    }
    if (overflows.isEmpty()) {
      return new CheckResult("image-overflow", true, "图片宽度均在可用区域内");
    }
    return new CheckResult(
        "image-overflow", false, String.join("；", overflows) + "，超出页面可用宽度", "error");
  }

  /** 检查 6：字体回退（罕见字体白名单外）。 */
  private CheckResult checkFontFallback(Document doc) {
    Set<String> risky = new HashSet<>();
    for (Paragraph p : doc.paragraphs()) {
      for (InlineElement ie : p.inlineElements()) {
        if (ie instanceof Run) {
          String font = ((Run) ie).style().font();
          if (font != null && !SAFE_FONTS.contains(font.toLowerCase())) {
            risky.add(font);
          }
        }
      }
    }
    if (risky.isEmpty()) {
      return new CheckResult("font-fallback", true, "字体均在常见白名单内");
    }
    return new CheckResult(
        "font-fallback", false, "使用了目标系统可能缺失的字体：" + String.join("、", risky), "warning");
  }

  /** 检查 7：CJK 首行缩进（中文正文是否有首行缩进）。 */
  private CheckResult checkCjkIndent(Document doc) {
    List<String> issues = new ArrayList<>();
    int idx = 0;
    for (Paragraph p : bodyParagraphs(doc)) {
      idx++;
      if (p.heading() != null) {
        continue; // 标题不查
      }
      String text = p.text();
      if (!CJK.matcher(text).find()) {
        continue; // 非 CJK 段落不查
      }
      if ("CENTER".equals(p.alignment().name())) {
        continue; // 居中段落（如落款）不查
      }
      if (p.indentationFirstLine() <= 0) {
        issues.add("段落 " + idx + "（CJK 正文）无首行缩进");
      }
    }
    if (issues.isEmpty()) {
      return new CheckResult("cjk-indent", true, "CJK 正文首行缩进正确");
    }
    return new CheckResult("cjk-indent", false, String.join("；", issues), "warning");
  }

  /** 检查 8：标题层级连续性（是否跳级）。 */
  private CheckResult checkHeadingLevels(Document doc) {
    List<String> issues = new ArrayList<>();
    int lastLevel = 0;
    int idx = 0;
    for (Paragraph p : doc.paragraphs()) {
      idx++;
      HeadingLevel h = p.heading();
      if (h == null) {
        continue;
      }
      int level = headingLevel(h);
      if (lastLevel > 0 && level > lastLevel + 1) {
        issues.add("段落 " + idx + " 标题从 " + headingName(lastLevel) + " 跳到 " + headingName(level));
      }
      lastLevel = level;
    }
    if (issues.isEmpty()) {
      return new CheckResult("heading-levels", true, "标题层级连续无跳级");
    }
    return new CheckResult("heading-levels", false, String.join("；", issues), "warning");
  }

  /** 检查 11：ShadingType SOLID 误用（WPS 黑块）。 */
  private CheckResult checkShadingSolid(Document doc) {
    List<String> issues = new ArrayList<>();
    for (int ti = 0; ti < doc.tables().size(); ti++) {
      Table t = doc.tables().get(ti);
      for (int ri = 0; ri < t.rows().size(); ri++) {
        Row row = t.row(ri);
        for (int ci = 0; ci < row.cells().size(); ci++) {
          Cell cell = row.cell(ci);
          if (hasSolidShading(cell)) {
            issues.add("单元格（表格" + (ti + 1) + ",行" + (ri + 1) + ",列" + (ci + 1) + "）");
          }
        }
      }
    }
    // 段落底纹也查（raw 兜底）
    int pi = 0;
    for (Paragraph p : doc.paragraphs()) {
      pi++;
      if (hasSolidShading(p)) {
        issues.add("段落 " + pi);
      }
    }
    if (issues.isEmpty()) {
      return new CheckResult("shading-solid", true, "无 SOLID 底纹误用");
    }
    return new CheckResult(
        "shading-solid",
        false,
        "误用 SOLID 底纹（WPS 显示黑块）："
            + String.join("、", issues)
            + "。见 renderer-compatibility.md#shading-solid",
        "error");
  }

  /** 检查 12：TOC 质量（域存在 + 标题用 Heading）。 */
  private CheckResult checkToc(Document doc) {
    com.non.docx.core.api.toc.TableOfContents toc = doc.toc();
    boolean hasHeadings = false;
    for (Paragraph p : doc.paragraphs()) {
      if (p.heading() != null) {
        hasHeadings = true;
        break;
      }
    }
    if (toc == null || toc.size() == 0) {
      if (hasHeadings) {
        return new CheckResult("toc", false, "文档有标题但无 TOC 域，建议插入目录", "warning");
      }
      return new CheckResult("toc", true, "无 TOC 域（文档也无标题，可接受）");
    }
    if (!hasHeadings) {
      return new CheckResult("toc", false, "有 TOC 域但正文无 Heading 样式标题，TOC 会为空", "warning");
    }
    return new CheckResult("toc", true, "TOC 域存在且正文有 Heading 标题");
  }

  /** 检查 14：文档清洁度（占位符/Markdown/草稿残留）。 */
  private CheckResult checkCleanliness(Document doc) {
    List<String> issues = new ArrayList<>();
    int idx = 0;
    for (Paragraph p : allParagraphsIncludingCells(doc)) {
      idx++;
      String text = p.text();
      if (PLACEHOLDER.matcher(text).find()) {
        issues.add("段落 " + idx + " 含占位符/草稿措辞");
      } else if (MARKDOWN.matcher(text).find()) {
        issues.add("段落 " + idx + " 疑似残留 Markdown 语法");
      }
    }
    if (issues.isEmpty()) {
      return new CheckResult("cleanliness", true, "文档清洁无占位符/Markdown 残留");
    }
    return new CheckResult("cleanliness", false, String.join("；", issues), "warning");
  }

  // ==================== 辅助 ====================

  /** 取正文直属段落（非表格内）。 */
  private List<Paragraph> bodyParagraphs(Document doc) {
    List<Paragraph> result = new ArrayList<>();
    for (BodyElement el : doc.bodyElements()) {
      if (el instanceof Paragraph) {
        result.add((Paragraph) el);
      }
    }
    return result;
  }

  /** 取所有段落（含表格单元格内的段落，用于 cleanliness 检查）。 */
  private List<Paragraph> allParagraphsIncludingCells(Document doc) {
    List<Paragraph> result = new ArrayList<>(doc.paragraphs());
    for (Table t : doc.tables()) {
      for (Row r : t.rows()) {
        for (Cell c : r.cells()) {
          result.addAll(c.paragraphs());
        }
      }
    }
    return result;
  }

  /** HeadingLevel → 数字层级。 */
  private int headingLevel(HeadingLevel h) {
    switch (h) {
      case H1:
        return 1;
      case H2:
        return 2;
      case H3:
        return 3;
      case H4:
        return 4;
      case H5:
        return 5;
      case H6:
        return 6;
      default:
        return 0;
    }
  }

  private String headingName(int level) {
    return level > 0 ? "H" + level : "正文";
  }

  /** raw 兜底：检测 cell 是否有 SOLID 底纹（公开 API 读侧把 SOLID 归并 NIL，故需直读 OOXML）。 */
  private boolean hasSolidShading(Cell cell) {
    CTTc tc = cell.raw().getCTTc();
    if (!tc.isSetTcPr()) {
      return false;
    }
    CTTcPr tcPr = tc.getTcPr();
    if (!tcPr.isSetShd()) {
      return false;
    }
    CTShd shd = tcPr.getShd();
    return shd.getVal() == STShd.SOLID;
  }

  /** raw 兜底：检测段落是否有 SOLID 底纹。 */
  private boolean hasSolidShading(Paragraph p) {
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP ctP = p.raw().getCTP();
    if (!ctP.isSetPPr() || !ctP.getPPr().isSetShd()) {
      return false;
    }
    return ctP.getPPr().getShd().getVal() == STShd.SOLID;
  }

  private static String stripTrailing(String s) {
    return s.endsWith("、") ? s.substring(0, s.length() - 1) : s;
  }

  /** 拼装最终报告字符串，渲染为双段格式。 */
  private String formatReport(String docId, List<CheckResult> results) {
    StringBuilder sb = new StringBuilder();
    sb.append("📋 文档质量自检报告: ").append(docId).append('\n');
    int passed = 0;
    int errors = 0;
    int warnings = 0;
    for (CheckResult r : results) {
      sb.append("  ").append(r.toReportLine()).append('\n');
      if (r.passed) {
        passed++;
      } else if ("error".equals(r.severity)) {
        errors++;
      } else {
        warnings++;
      }
    }
    sb.append("  ───\n");
    sb.append("  通过 ")
        .append(passed)
        .append('/')
        .append(results.size())
        .append(" | ❌ ")
        .append(errors)
        .append(" errors | ⚠️ ")
        .append(warnings)
        .append(" warnings");
    Map<String, Integer> data = new LinkedHashMap<>();
    data.put("passed", passed);
    data.put("errors", errors);
    data.put("warnings", warnings);
    data.put("total", results.size());
    ToolResult<Map<String, Integer>> result = ToolResult.ok(data, sb.toString());
    return ToolResultRenderer.render(result);
  }

  /** 单项检查结果（toolkit 内部值对象）。 */
  static final class CheckResult {
    final String name;
    final boolean passed;
    final String message;
    final String severity; // "error" | "warning"，passed=true 时未用

    CheckResult(String name, boolean passed, String message) {
      this(name, passed, message, "warning");
    }

    CheckResult(String name, boolean passed, String message, String severity) {
      this.name = name;
      this.passed = passed;
      this.message = message;
      this.severity = severity;
    }

    String toReportLine() {
      String icon = passed ? "✅" : ("error".equals(severity) ? "❌" : "⚠️");
      return icon + " [" + name + "] " + message;
    }
  }
}
