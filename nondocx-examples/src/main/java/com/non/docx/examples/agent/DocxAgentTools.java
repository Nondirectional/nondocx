package com.non.docx.examples.agent;

import com.non.chain.tool.ToolDef;
import com.non.chain.tool.ToolParam;
import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.InlineElement;
import com.non.docx.core.api.text.Hyperlink;
import com.non.docx.core.api.text.Paragraph;
import com.non.docx.core.api.text.Run;
import com.non.docx.core.api.toc.TableOfContents;
import com.non.docx.core.api.toc.TocEntry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;

/**
 * 把 nondocx 的 docx 读写能力包装为一组细粒度工具，交给 nonchain Agent 调用。
 *
 * <p><b>三层结构对应关系</b>（OOXML → POI → nondocx）：
 *
 * <ul>
 *   <li><b>OOXML</b>：{@code word/document.xml} 的正文是 {@code <w:p>}（段落）与 {@code <w:tbl>}（表格）
 *       的有序序列；表格内是 {@code <w:tr>}（行）→ {@code <w:tc>}（单元格）→ 又是一串 {@code <w:p>}； 段落内的内联内容是 {@code
 *       <w:r>}（run）和 {@code <w:hyperlink>}（超链接，其内仍含 {@code <w:r>}）。
 *   <li><b>POI</b>：对应 {@code XWPFDocument} → {@code XWPFTable} → {@code XWPFTableRow} → {@code
 *       XWPFTableCell} → {@code XWPFParagraph} → {@code XWPFRun} / {@code XWPFHyperlinkRun}。
 *   <li><b>nondocx 封装</b>：同一条链是活对象 {@code doc.table(i).row(j).cell(k).paragraph(p).run(r)}。
 *       本类不新增领域逻辑，只把这条链按 Agent 友好的粒度逐段暴露，让 LLM 像操作"文档编辑器"那样读写 docx。
 * </ul>
 *
 * <p><b>会话模型（docId）。</b> 一次 {@code open_docx} 返回一个字符串句柄 {@code "doc-<n>"}， 后续所有读/写工具只传 {@code
 * docId} + 索引，不传文件路径。这对应 nondocx 的活对象语义：一次打开、多次读写、 {@code save_docx} 才落盘（与 poi-bridge.md「holding
 * wrapper、活对象直写」一致）。
 *
 * <p><b>返回值约定（design §3.2）。</b> 所有工具统一返回 {@code String}（nonchain 的 {@code ToolRegistry} 会走 {@code
 * result.toString()}）。结构化信息用简短的「键: 值」多行纯文本， 不引入 JSON 依赖。越界 / docId 不存在时返回 <em>中文错误描述串</em>
 * 而非抛异常——Agent 能把错误读回并自行修正，更贴合 Agent 循环语义。
 *
 * <p><b>线程模型。</b> 本类为单 Agent 实例设计，内部状态未做并发保护；不要跨 Agent 共享。
 */
public final class DocxAgentTools {

  /** 打开的文档会话：docId → 活文档。 */
  private final Map<String, Document> sessions = new HashMap<>();

  /** docId 自增序号，产出 {@code "doc-1"}、{@code "doc-2"}、… */
  private final AtomicInteger seq = new AtomicInteger();

  // ==================== A. 文档会话 ====================

  /**
   * 打开一个 .docx 文件，返回文档句柄 docId。
   *
   * @param path 文档路径（绝对路径或相对工作目录的路径）
   * @return 形如 {@code "doc-1"} 的句柄；打开失败返回中文错误串
   */
  @ToolDef(name = "open_docx", description = "打开一个 .docx 文件，返回文档句柄 docId，后续工具用它定位文档")
  public String openDocx(@ToolParam(name = "path", description = "文档路径（绝对路径）") String path) {
    try {
      Document doc = Docx.open(Path.of(path));
      String docId = "doc-" + seq.incrementAndGet();
      sessions.put(docId, doc);
      return docId;
    } catch (RuntimeException e) {
      return "错误：无法打开文档 " + path + "（" + rootMessage(e) + "）";
    }
  }

  /**
   * 把指定文档保存到输出路径（落盘）。
   *
   * @param docId 文档句柄
   * @param outputPath 输出文件路径
   * @return 保存结果（含输出路径）；失败返回中文错误串
   */
  @ToolDef(name = "save_docx", description = "把指定 docId 的文档保存到 output_path（覆盖写），返回保存结果")
  public String saveDocx(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "output_path", description = "输出文件路径（绝对路径）") String outputPath) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    try {
      Path out = Path.of(outputPath);
      out.toFile().getParentFile().mkdirs();
      doc.save(out);
      return "已保存到 " + out.toAbsolutePath();
    } catch (RuntimeException e) {
      return "错误：无法保存到 " + outputPath + "（" + rootMessage(e) + "）";
    }
  }

  /**
   * 关闭并移除文档会话（幂等）。
   *
   * @param docId 文档句柄
   * @return 关闭结果；句柄不存在视为已关闭
   */
  @ToolDef(name = "close_docx", description = "关闭并释放指定 docId 的文档会话（幂等：未打开也返回成功）")
  public String closeDocx(@ToolParam(name = "doc_id", description = "文档句柄") String docId) {
    Document doc = sessions.remove(docId);
    if (doc == null) {
      return "文档 " + docId + " 未打开（视为已关闭）";
    }
    try {
      doc.close();
    } catch (RuntimeException e) {
      return "文档 " + docId + " 已从会话移除，但关闭时出错：" + rootMessage(e);
    }
    return "已关闭 " + docId;
  }

  // ==================== B. 正文段落 / run ====================

  /** 返回正文段落数。 */
  @ToolDef(name = "get_paragraph_count", description = "返回文档正文的段落数（不含表格内段落）")
  public String getParagraphCount(@ToolParam(name = "doc_id", description = "文档句柄") String docId) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    return "段落数: " + doc.paragraphs().size();
  }

  /**
   * 读取正文第 paragraph_index 段的结构摘要（文本 + run 数 + 是否含超链接）。
   *
   * <p>摘要里带上 run 数与超链接数，让 Agent 一次读到寻址所需的上下文，不必再盲猜索引。
   */
  @ToolDef(
      name = "read_paragraph",
      description = "读取正文第 paragraph_index 段（0 起）的结构摘要：文本、run 数、是否含超链接")
  public String readParagraph(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "paragraph_index", description = "段落索引（0 起）") int paragraphIndex) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    var paragraphs = doc.paragraphs();
    if (outOfBounds(paragraphIndex, paragraphs.size())) {
      return indexError("段落索引", paragraphIndex, paragraphs.size());
    }
    Paragraph p = paragraphs.get(paragraphIndex);
    int runCount = p.runs().size();
    long hyperlinkCount = hyperlinkCount(p);
    StringBuilder sb = new StringBuilder();
    sb.append("段落 ").append(paragraphIndex).append('\n');
    sb.append("文本: ").append(p.text()).append('\n');
    sb.append("run 数: ").append(runCount).append('\n');
    sb.append("超链接数: ").append(hyperlinkCount);
    return sb.toString();
  }

  /** 读取正文某段某 run 的文本与样式摘要。 */
  @ToolDef(name = "read_run", description = "读取正文第 paragraph_index 段第 run_index 个 run（0 起）的文本与样式摘要")
  public String readRun(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "paragraph_index", description = "段落索引（0 起）") int paragraphIndex,
      @ToolParam(name = "run_index", description = "run 索引（0 起，不含超链接）") int runIndex) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    var paragraphs = doc.paragraphs();
    if (outOfBounds(paragraphIndex, paragraphs.size())) {
      return indexError("段落索引", paragraphIndex, paragraphs.size());
    }
    var runs = paragraphs.get(paragraphIndex).runs();
    if (outOfBounds(runIndex, runs.size())) {
      return indexError("run 索引", runIndex, runs.size());
    }
    Run run = runs.get(runIndex);
    return "文本: " + run.text() + "\n样式: " + run.style();
  }

  /** 替换正文某段某 run 的文本（活对象直写，需 save_docx 落盘）。 */
  @ToolDef(
      name = "replace_run_text",
      description = "把正文第 paragraph_index 段第 run_index 个 run 的文本替换为 text（改完需 save_docx 落盘）")
  public String replaceRunText(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "paragraph_index", description = "段落索引（0 起）") int paragraphIndex,
      @ToolParam(name = "run_index", description = "run 索引（0 起，不含超链接）") int runIndex,
      @ToolParam(name = "text", description = "新文本") String text) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    var paragraphs = doc.paragraphs();
    if (outOfBounds(paragraphIndex, paragraphs.size())) {
      return indexError("段落索引", paragraphIndex, paragraphs.size());
    }
    var runs = paragraphs.get(paragraphIndex).runs();
    if (outOfBounds(runIndex, runs.size())) {
      return indexError("run 索引", runIndex, runs.size());
    }
    runs.get(runIndex).text(text);
    return "已替换：段落 " + paragraphIndex + " 的 run " + runIndex + " → \"" + text + "\"";
  }

  /** 在正文末尾追加一个单 run 段落。 */
  @ToolDef(name = "append_paragraph", description = "在正文末尾追加一个单 run 段落（改完需 save_docx 落盘）")
  public String appendParagraph(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "text", description = "段落文本") String text) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    doc.addParagraph(text);
    return "已追加段落：\"" + text + "\"";
  }

  // ==================== C. 表格（下钻到 cell 内 paragraph / run） ====================

  /** 返回正文表格数。 */
  @ToolDef(name = "get_table_count", description = "返回文档正文的表格数")
  public String getTableCount(@ToolParam(name = "doc_id", description = "文档句柄") String docId) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    return "表格数: " + doc.tables().size();
  }

  /**
   * 读取表格某单元格的结构摘要（文本 + 段落数 + 各段 run 数）。
   *
   * <p>表格寻址链 table → row → cell → paragraph → run 较深；本工具在返回值里给出每个 cell 的段落数与各段 run 数，让 Agent
   * 不必盲猜索引。
   */
  @ToolDef(
      name = "read_table_cell",
      description = "读取表格 table_index 第 row_index 行 cell_index 单元格（均 0 起）的结构摘要")
  public String readTableCell(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "table_index", description = "表格索引（0 起）") int tableIndex,
      @ToolParam(name = "row_index", description = "行索引（0 起）") int rowIndex,
      @ToolParam(name = "cell_index", description = "单元格索引（0 起）") int cellIndex) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    var tables = doc.tables();
    if (outOfBounds(tableIndex, tables.size())) {
      return indexError("表格索引", tableIndex, tables.size());
    }
    var rows = tables.get(tableIndex).rows();
    if (outOfBounds(rowIndex, rows.size())) {
      return indexError("行索引", rowIndex, rows.size());
    }
    var cells = rows.get(rowIndex).cells();
    if (outOfBounds(cellIndex, cells.size())) {
      return indexError("单元格索引", cellIndex, cells.size());
    }
    var cell = cells.get(cellIndex);
    var paras = cell.paragraphs();
    StringBuilder sb = new StringBuilder();
    sb.append("单元格 (")
        .append(tableIndex)
        .append(',')
        .append(rowIndex)
        .append(',')
        .append(cellIndex)
        .append(")\n");
    sb.append("文本: ").append(cell.text()).append('\n');
    sb.append("段落数: ").append(paras.size()).append('\n');
    for (int i = 0; i < paras.size(); i++) {
      sb.append("  段落 ")
          .append(i)
          .append(": run 数 ")
          .append(paras.get(i).runs().size())
          .append('\n');
    }
    return sb.toString().stripTrailing();
  }

  /** 读取表格某单元格内某段某 run 的文本。 */
  @ToolDef(
      name = "read_table_cell_run",
      description = "读取表格某单元格内 paragraph_index 段 run_index 个 run（均 0 起）的文本")
  public String readTableCellRun(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "table_index", description = "表格索引（0 起）") int tableIndex,
      @ToolParam(name = "row_index", description = "行索引（0 起）") int rowIndex,
      @ToolParam(name = "cell_index", description = "单元格索引（0 起）") int cellIndex,
      @ToolParam(name = "paragraph_index", description = "单元格内段落索引（0 起）") int paragraphIndex,
      @ToolParam(name = "run_index", description = "run 索引（0 起）") int runIndex) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    var cellResult = locateCell(doc, tableIndex, rowIndex, cellIndex);
    if (cellResult.startsWith("错误")) {
      return cellResult;
    }
    var cell = locateCellObj(doc, tableIndex, rowIndex, cellIndex);
    var paras = cell.paragraphs();
    if (outOfBounds(paragraphIndex, paras.size())) {
      return indexError("单元格内段落索引", paragraphIndex, paras.size());
    }
    var runs = paras.get(paragraphIndex).runs();
    if (outOfBounds(runIndex, runs.size())) {
      return indexError("run 索引", runIndex, runs.size());
    }
    return "文本: " + runs.get(runIndex).text();
  }

  /** 替换表格某单元格内某段某 run 的文本（活对象直写，需 save_docx 落盘）。 */
  @ToolDef(
      name = "replace_table_cell_run_text",
      description = "替换表格某单元格内 paragraph_index 段 run_index 个 run 的文本（改完需 save_docx 落盘）")
  public String replaceTableCellRunText(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "table_index", description = "表格索引（0 起）") int tableIndex,
      @ToolParam(name = "row_index", description = "行索引（0 起）") int rowIndex,
      @ToolParam(name = "cell_index", description = "单元格索引（0 起）") int cellIndex,
      @ToolParam(name = "paragraph_index", description = "单元格内段落索引（0 起）") int paragraphIndex,
      @ToolParam(name = "run_index", description = "run 索引（0 起）") int runIndex,
      @ToolParam(name = "text", description = "新文本") String text) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    var cellResult = locateCell(doc, tableIndex, rowIndex, cellIndex);
    if (cellResult.startsWith("错误")) {
      return cellResult;
    }
    var cell = locateCellObj(doc, tableIndex, rowIndex, cellIndex);
    var paras = cell.paragraphs();
    if (outOfBounds(paragraphIndex, paras.size())) {
      return indexError("单元格内段落索引", paragraphIndex, paras.size());
    }
    var runs = paras.get(paragraphIndex).runs();
    if (outOfBounds(runIndex, runs.size())) {
      return indexError("run 索引", runIndex, runs.size());
    }
    runs.get(runIndex).text(text);
    return "已替换：单元格 ("
        + tableIndex
        + ","
        + rowIndex
        + ","
        + cellIndex
        + ") 段落 "
        + paragraphIndex
        + " 的 run "
        + runIndex
        + " → \""
        + text
        + "\"";
  }

  // ==================== D. 超链接（显示文本 + URL 双向改） ====================

  /**
   * 读取正文某段的第 hyperlink_index 个超链接（0 起），返回显示文本与目标 URL。
   *
   * <p>超链接是段落 {@code inlineElements()} 里的一类（而非 {@code runs()}），与 nondocx 模型一致。
   */
  @ToolDef(
      name = "read_hyperlink",
      description = "读取正文第 paragraph_index 段第 hyperlink_index 个超链接（0 起）的显示文本与目标 URL")
  public String readHyperlink(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "paragraph_index", description = "段落索引（0 起）") int paragraphIndex,
      @ToolParam(name = "hyperlink_index", description = "超链接索引（0 起）") int hyperlinkIndex) {
    Hyperlink link = locateHyperlink(docId, paragraphIndex, hyperlinkIndex);
    if (link == null) {
      return locateHyperlinkFailed(docId, paragraphIndex, hyperlinkIndex);
    }
    return "显示文本: " + link.text() + "\n目标 URL: " + link.url();
  }

  /** 修改正文某段某超链接的显示文本（活对象直写，需 save_docx 落盘）。 */
  @ToolDef(name = "update_hyperlink_text", description = "修改正文某段某超链接的显示文本（改完需 save_docx 落盘）")
  public String updateHyperlinkText(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "paragraph_index", description = "段落索引（0 起）") int paragraphIndex,
      @ToolParam(name = "hyperlink_index", description = "超链接索引（0 起）") int hyperlinkIndex,
      @ToolParam(name = "text", description = "新的显示文本") String text) {
    Hyperlink link = locateHyperlink(docId, paragraphIndex, hyperlinkIndex);
    if (link == null) {
      return locateHyperlinkFailed(docId, paragraphIndex, hyperlinkIndex);
    }
    link.text(text);
    return "已修改：段落 " + paragraphIndex + " 超链接 " + hyperlinkIndex + " 的显示文本 → \"" + text + "\"";
  }

  /** 修改正文某段某超链接的目标 URL（活对象直写，需 save_docx 落盘）。 */
  @ToolDef(name = "update_hyperlink_url", description = "修改正文某段某超链接的目标 URL（改完需 save_docx 落盘）")
  public String updateHyperlinkUrl(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "paragraph_index", description = "段落索引（0 起）") int paragraphIndex,
      @ToolParam(name = "hyperlink_index", description = "超链接索引（0 起）") int hyperlinkIndex,
      @ToolParam(name = "url", description = "新的目标 URL") String url) {
    Hyperlink link = locateHyperlink(docId, paragraphIndex, hyperlinkIndex);
    if (link == null) {
      return locateHyperlinkFailed(docId, paragraphIndex, hyperlinkIndex);
    }
    try {
      link.url(url);
    } catch (RuntimeException e) {
      return "错误：无法修改超链接 URL（" + rootMessage(e) + "）";
    }
    return "已修改：段落 " + paragraphIndex + " 超链接 " + hyperlinkIndex + " 的 URL → " + url;
  }

  // ==================== E. 文本搜索（横切所有容器，一次定位） ====================

  /** search_text 的默认命中数上限（max_results 未传时使用），平衡"够用"与返回体长度。 */
  private static final int SEARCH_DEFAULT_MAX = 50;

  /**
   * 在整份文档里搜索 keyword，一次返回所有命中位置的坐标。
   *
   * <p><b>为什么需要这个工具。</b> 现有的 {@code read_paragraph} / {@code read_table_cell} 都是
   * <em>按索引寻址</em>——知道位置才能读。但 Agent 要改某段文字时，往往不知道它在第几段、第几个单元格， 只能 {@code get_paragraph_count} → 逐个
   * {@code read_paragraph} 盲读，每步都是一轮 LLM 往返， 定位特别慢。本工具把"线性扫描"从 Agent 循环里搬出来：一次调用遍历正文段落、表格所有单元格、 各
   * section 的页眉页脚段落，直接吐出所有命中坐标。
   *
   * <p><b>遍历范围（OOXML 三层对应）：</b>
   *
   * <ul>
   *   <li><b>正文段落</b> —— {@code doc.paragraphs()}，对应 {@code word/document.xml} 里 body 直属的 {@code
   *       <w:p>}。
   *   <li><b>表格单元格内段落</b> —— {@code doc.tables().get(t).rows().get(r).cells().get(c).paragraphs()}，
   *       对应 {@code <w:tbl>} → {@code <w:tr>} → {@code <w:tc>} 内的 {@code <w:p>}。表格 cell 内才再有段落，
   *       这就是为什么表格寻址比段落深三层。
   *   <li><b>页眉 / 页脚段落</b> —— {@code doc.sections().get(s).header()/footer()}（只读，null=不存在）， 对应独立
   *       ZIP part（{@code header1.xml} / {@code footer1.xml}），通过 section 的 {@code <w:sectPr>} 引用。
   * </ul>
   *
   * <p><b>命中粒度。</b> 用段落 {@code text()}（POI 拼好的纯文本）做匹配——天然跨 run， 即使"项"+"目进度"分属两个 run
   * 也能命中整词"项目进度"。返回里另附"哪个 run 含命中" （逐 run 找首个 {@code text()} 含关键词的），便于直接喂给 {@code
   * replace_run_text}。
   *
   * <p><b>匹配规则。</b> {@code exact=false}（默认）忽略大小写 + 子串包含；{@code exact=true} 精确相等。
   *
   * <p><b>命中上限。</b> 由 {@code max_results} 控制：{@code >0} 为上限；{@code 0} 或负数表示不限（全部返回）。 默认 {@value
   * #SEARCH_DEFAULT_MAX}。命中数达到上限时会提示"可能还有更多，请缩小关键词"。 某个词在文档里分布极广时，Agent 可主动传更大的 max_results（或 0
   * 不限）拿全量，自行取舍。
   *
   * @param keyword 要找的文本
   * @param exact 是否精确匹配（默认 false=忽略大小写的子串包含）
   * @param maxResults 命中数上限；>0 为上限，0 或负数表示不限（默认 {@value #SEARCH_DEFAULT_MAX}）
   * @return 所有命中坐标的多行纯文本；无命中时返回提示串
   */
  @ToolDef(
      name = "search_text",
      description =
          "在整份文档（正文段落 + 表格单元格 + 页眉 + 页脚）里搜索 keyword，"
              + "一次返回所有命中位置坐标（段落级匹配，标注含命中的 run）。"
              + "max_results 控制上限：>0 为上限，0 或负数=不限（默认 50）。"
              + "按文本改内容前优先用它定位，不要逐段 read 盲读。")
  public String searchText(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "keyword", description = "要查找的文本") String keyword,
      @ToolParam(name = "exact", description = "true=精确相等；false（默认）=忽略大小写的子串包含") boolean exact,
      @ToolParam(name = "max_results", description = "命中数上限：>0 为上限，0 或负数=不限（默认 50）。命中很多时可调大或传 0")
          Integer maxResults) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    // 用 Integer 而非 int：LLM 不传该参数时 nonchain 会注入 null，
    // 包装类型能安全接住 null，这里再归一化为默认值（避免基本类型收到 null 触发 NPE）。
    // 归一化：null 或 <=0 视为不限（用极大值，循环自然由真实命中数收尾）；>0 原样用。
    int limit = (maxResults == null || maxResults <= 0) ? Integer.MAX_VALUE : maxResults;
    List<String> hits = new ArrayList<>();

    // 1) 正文段落
    var paragraphs = doc.paragraphs();
    for (int i = 0; i < paragraphs.size() && hits.size() < limit; i++) {
      Paragraph p = paragraphs.get(i);
      String hit = matchBodyParagraph(i, p, keyword, exact);
      if (hit != null) {
        hits.add(hit);
      }
    }

    // 2) 表格单元格内段落
    var tables = doc.tables();
    for (int t = 0; t < tables.size() && hits.size() < limit; t++) {
      var rows = tables.get(t).rows();
      for (int r = 0; r < rows.size() && hits.size() < limit; r++) {
        var cells = rows.get(r).cells();
        for (int c = 0; c < cells.size() && hits.size() < limit; c++) {
          var paras = cells.get(c).paragraphs();
          for (int pi = 0; pi < paras.size() && hits.size() < limit; pi++) {
            String hit = matchCellParagraph(t, r, c, pi, paras.get(pi), keyword, exact);
            if (hit != null) {
              hits.add(hit);
            }
          }
        }
      }
    }

    // 3) 页眉 / 页脚。读写分离后 Section.header()/footer() 本身就是只读的（null=不存在），
    //    所以这里直接遍历、null 跳过即可，不会再像旧 API 那样"读一遍凭空创建空页眉"。
    var sections = doc.sections();
    for (int s = 0; s < sections.size() && hits.size() < limit; s++) {
      searchHeaderFooter(sections.get(s).header(), "页眉", s, keyword, exact, hits, limit);
      searchHeaderFooter(sections.get(s).footer(), "页脚", s, keyword, exact, hits, limit);
    }

    // 大文档可能命中数远超上限。上面三段循环都以 hits.size() < limit 为条件提前退出，
    // 所以一旦 hits.size() 达到 limit，就说明至少还没扫完，提示 Agent 缩小关键词。
    // （limit == Integer.MAX_VALUE 即"不限"时，hits.size() 不可能 >= 它，自然不会误报。）
    boolean possiblyMore = hits.size() >= limit;

    if (hits.isEmpty()) {
      return "未找到「" + keyword + "」";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("找到 ").append(hits.size()).append(" 处「").append(keyword).append("」：\n");
    for (int i = 0; i < hits.size(); i++) {
      sb.append('[').append(i + 1).append("] ").append(hits.get(i));
      if (i < hits.size() - 1) {
        sb.append('\n');
      }
    }
    if (possiblyMore) {
      sb.append("\n（已达 ").append(limit).append(" 处上限，可能还有更多；请用更长的关键词缩小范围，或调大 max_results）");
    }
    return sb.toString();
  }

  /** 正文段落命中 → "正文段落 N · run R 含命中\n文本: ..."；未命中返回 null。 */
  private static String matchBodyParagraph(
      int paragraphIndex, Paragraph p, String keyword, boolean exact) {
    if (!matches(p.text(), keyword, exact)) {
      return null;
    }
    int runIdx = firstRunContaining(p, keyword, exact);
    return "正文段落 " + paragraphIndex + " · run " + runIdx + " 含命中\n文本: " + p.text();
  }

  /** 表格单元格段落命中 → "表格(t,r,c) 段落 P · run R 含命中\n文本: ..."；未命中返回 null。 */
  private static String matchCellParagraph(
      int t, int r, int c, int paragraphIndex, Paragraph p, String keyword, boolean exact) {
    if (!matches(p.text(), keyword, exact)) {
      return null;
    }
    int runIdx = firstRunContaining(p, keyword, exact);
    return "表格("
        + t
        + ","
        + r
        + ","
        + c
        + ") 段落 "
        + paragraphIndex
        + " · run "
        + runIdx
        + " 含命中\n文本: "
        + p.text();
  }

  /** 页眉/页脚段落命中 → "[页眉|页脚] section=S 段落 P · run R 含命中\n文本: ..."；未命中返回 null。 */
  private static String matchHeaderFooterParagraph(
      String kind,
      int sectionIndex,
      int paragraphIndex,
      Paragraph p,
      String keyword,
      boolean exact) {
    if (!matches(p.text(), keyword, exact)) {
      return null;
    }
    int runIdx = firstRunContaining(p, keyword, exact);
    return kind
        + " section="
        + sectionIndex
        + " 段落 "
        + paragraphIndex
        + " · run "
        + runIdx
        + " 含命中\n文本: "
        + p.text();
  }

  /**
   * 遍历一个页眉/页脚的段落做搜索匹配，命中追加进 hits。{@code headerOrFooter} 为 null（该 section 无此 part）时直接返回。
   *
   * <p>读写分离后 {@code Section.header()}/{@code footer()} 不存在时返回 null 而非创建空 part， 所以这里只需 null
   * 跳过即可安全只读遍历，无需像旧版那样自建 POI 解析。
   */
  private static void searchHeaderFooter(
      Object headerOrFooter,
      String kind,
      int sectionIndex,
      String keyword,
      boolean exact,
      List<String> hits,
      int limit) {
    if (headerOrFooter == null) {
      return;
    }
    List<Paragraph> paras =
        headerOrFooter instanceof com.non.docx.core.api.header.Header
            ? ((com.non.docx.core.api.header.Header) headerOrFooter).paragraphs()
            : ((com.non.docx.core.api.header.Footer) headerOrFooter).paragraphs();
    for (int pi = 0; pi < paras.size() && hits.size() < limit; pi++) {
      String hit =
          matchHeaderFooterParagraph(kind, sectionIndex, pi, paras.get(pi), keyword, exact);
      if (hit != null) {
        hits.add(hit);
      }
    }
  }

  /** 段落文本是否命中关键词。 */
  private static boolean matches(String text, String keyword, boolean exact) {
    if (exact) {
      return text.equals(keyword);
    }
    return text.toLowerCase().contains(keyword.toLowerCase());
  }

  /**
   * 段落内首个 text() 含关键词的 run 索引；找不到（例如关键词横跨多个 run）返回 -1。
   *
   * <p>这里按 {@code runs()}（普通 run）计数，与 {@code replace_run_text} 的 run_index 语义一致。 超链接里的文本不计入 run
   * 索引，如需改超链接文本用 {@code update_hyperlink_text}。
   */
  private static int firstRunContaining(Paragraph p, String keyword, boolean exact) {
    var runs = p.runs();
    for (int i = 0; i < runs.size(); i++) {
      if (matches(runs.get(i).text(), keyword, exact)) {
        return i;
      }
    }
    return -1;
  }

  // ==================== F. 页眉 / 页脚（读取） ====================

  /**
   * 读取页眉某段的结构摘要（文本 + run 数 + 超链接数），风格对齐 {@code read_paragraph}。
   *
   * @param sectionIndex section 索引（0 起）；单 section 文档用 0
   * @param paragraphIndex 页眉内段落索引（0 起）
   */
  @ToolDef(
      name = "read_header",
      description = "读取第 section_index 个 section 的默认页眉里第 paragraph_index 段（均 0 起）的结构摘要")
  public String readHeader(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "section_index", description = "section 索引（0 起）") int sectionIndex,
      @ToolParam(name = "paragraph_index", description = "页眉内段落索引（0 起）") int paragraphIndex) {
    return readHeaderFooterParagraph(docId, sectionIndex, paragraphIndex, /* isHeader= */ true);
  }

  /** 读取页脚某段的结构摘要，语义同 {@link #readHeader} 但针对页脚。 */
  @ToolDef(
      name = "read_footer",
      description = "读取第 section_index 个 section 的默认页脚里第 paragraph_index 段（均 0 起）的结构摘要")
  public String readFooter(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "section_index", description = "section 索引（0 起）") int sectionIndex,
      @ToolParam(name = "paragraph_index", description = "页脚内段落索引（0 起）") int paragraphIndex) {
    return readHeaderFooterParagraph(docId, sectionIndex, paragraphIndex, /* isHeader= */ false);
  }

  /**
   * 页眉/页脚段落读取的共享实现。
   *
   * <p><b>OOXML 三层递进：</b>
   *
   * <ul>
   *   <li><b>OOXML</b>：页眉页脚不在 {@code word/document.xml} 的 body 里，而是独立 ZIP part （{@code header1.xml}
   *       / {@code footer1.xml}），通过 section 的 {@code <w:sectPr>} 里的 {@code <w:headerReference>} /
   *       {@code <w:footerReference>} 引用。
   *   <li><b>POI</b>：{@link XWPFHeaderFooterPolicy} 负责按某个 {@code sectPr} 解析出已附加的页眉页脚 part。
   *       关键区别：{@code getDefaultHeader()} 只读返回（不存在返回 null），而 {@code createHeader(DEFAULT)}
   *       会<em>新建</em>并附加一个 part——后者会修改文档。
   *   <li><b>nondocx</b>：读写分离后，{@code Section.header()}/{@code footer()} 就是 POI {@code
   *       getDefaultHeader/Footer()} 的只读映射（null=不存在）；需要创建时用 {@code ensureHeader()}/{@code
   *       ensureFooter()}。所以本工具直接调 {@code header()}/{@code footer()} 即可安全只读， 不必像旧版那样在 core
   *       外自建一份只读解析——这正是读写分离带来的简化。
   * </ul>
   */
  private String readHeaderFooterParagraph(
      String docId, int sectionIndex, int paragraphIndex, boolean isHeader) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    var sections = doc.sections();
    if (outOfBounds(sectionIndex, sections.size())) {
      return indexError("section 索引", sectionIndex, sections.size());
    }
    // 读写分离后 header()/footer() 只读返回，null 表示该 section 未设置页眉/页脚。
    Object hf =
        isHeader ? sections.get(sectionIndex).header() : sections.get(sectionIndex).footer();
    if (hf == null) {
      // 用提示串而非"错误"，让 Agent 知道这里确实没有内容，而不是索引算错。
      return (isHeader ? "页眉" : "页脚") + " section=" + sectionIndex + " 不存在（该 section 未设置页眉/页脚）";
    }
    List<Paragraph> paras =
        isHeader
            ? ((com.non.docx.core.api.header.Header) hf).paragraphs()
            : ((com.non.docx.core.api.header.Footer) hf).paragraphs();
    if (outOfBounds(paragraphIndex, paras.size())) {
      return indexError((isHeader ? "页眉" : "页脚") + "内段落索引", paragraphIndex, paras.size());
    }
    Paragraph p = paras.get(paragraphIndex);
    int runCount = p.runs().size();
    long hyperlinkCount = hyperlinkCount(p);
    return (isHeader ? "页眉" : "页脚")
        + " section="
        + sectionIndex
        + " 段落 "
        + paragraphIndex
        + "\n文本: "
        + p.text()
        + "\nrun 数: "
        + runCount
        + "\n超链接数: "
        + hyperlinkCount;
  }

  // ==================== G. 目录（TOC，只读） ====================

  /**
   * 读取文档的目录(Table of Contents),一次返回所有条目(标题 / 层级 / 页码 / 内部锚点)。
   *
   * <p><b>OOXML 三层递进</b>:
   *
   * <ul>
   *   <li><b>OOXML</b>:TOC 不是独立元素,而是正文里的一个<b>域</b>(由 {@code fldChar} 的 begin/separate/end 界定,指令文本以
   *       {@code "TOC "} 开头);begin 与 end 之间的段落是条目, 用 {@code pStyle=TOC1..TOC9} 标层级,内容常包在 {@code
   *       <w:hyperlink w:anchor="_Toc...">} 里。
   *   <li><b>POI</b>:没有 {@code XWPFToc} 高级 API,域字符当普通 run 吐出;条目内容在 CTP 级 {@code <w:hyperlink>} 内,
   *       {@code getRuns()} 不暴露。
   *   <li><b>nondocx</b>:把「找域 → 解析条目」收进 core 的 {@code internal/poi/TocFields}, 这里只调 {@code
   *       doc.toc()} 拿到 {@link TableOfContents} 后逐条拼成纯文本。
   * </ul>
   *
   * <p><b>只读 / 不刷新</b>: POI 没有 Word 的分页引擎,算不出页码,故创建/刷新目录不在能力范围。 这里返回的页码是文档里<b>已缓存的</b>值——若目录被标记为
   * {@code dirty}（源文档改动后未刷新）， 会在结果顶部提示「可能已过期」，Agent 应据此提醒用户页码可能不准。
   *
   * @param docId 文档句柄
   * @return 目录条目列表（多行纯文本）；无目录时返回提示串
   */
  @ToolDef(
      name = "read_toc",
      description =
          "读取文档的目录(TOC),一次返回所有条目:每条含标题、层级(1-9)、页码、内部锚点。" + "无目录时返回提示。需要看文档结构/目录时优先用它,不要逐段 read 盲读。")
  public String readToc(@ToolParam(name = "doc_id", description = "文档句柄") String docId) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    TableOfContents toc = doc.toc();
    if (toc == null) {
      return "该文档没有目录(TOC 域)。";
    }
    List<TocEntry> entries = toc.entries();
    if (entries.isEmpty()) {
      // 有 TOC 域但解析不出条目:罕见,可能 TOC 尚未在 Word 里生成(只有空域壳)。提示用户去 Word 刷新。
      return "存在目录域,但未能解析出条目(可能尚未在 Word 中生成,请打开文档刷新目录)。";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("目录(").append(entries.size()).append(" 条)");
    if (toc.dirty()) {
      sb.append(" · 已标记过期(页码可能不准,需在 Word 里刷新)");
    }
    sb.append('\n');
    for (int i = 0; i < entries.size(); i++) {
      TocEntry e = entries.get(i);
      sb.append('[').append(i).append("] ").append(e.level()).append("级 「").append(e.title());
      appendPageAndAnchor(sb, e);
      if (i < entries.size() - 1) {
        sb.append('\n');
      }
    }
    return sb.toString();
  }

  /** 把条目的页码与锚点追加进结果串:页码非空则附「· 第N页」,有锚点则附「· 锚点 _Toc...」。 */
  private static void appendPageAndAnchor(StringBuilder sb, TocEntry e) {
    if (!e.pageNumber().isEmpty()) {
      sb.append("」· 第 ").append(e.pageNumber()).append(" 页");
    } else {
      sb.append("」");
    }
    if (e.anchor() != null) {
      sb.append(" · 锚点 ").append(e.anchor());
    }
  }

  // ==================== 内部辅助 ====================

  /** 段落内超链接计数（从 inlineElements 过滤 Hyperlink，而非 runs()）。 */
  private static long hyperlinkCount(Paragraph p) {
    return p.inlineElements().stream().filter(e -> e instanceof Hyperlink).count();
  }

  /** 定位段落内第 hyperlinkIndex 个超链接；docId/段落/超链接任一无效返回 {@code null}。 调用方据此决定返回哪个中文错误串。 */
  private Hyperlink locateHyperlink(String docId, int paragraphIndex, int hyperlinkIndex) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return null;
    }
    var paragraphs = doc.paragraphs();
    if (outOfBounds(paragraphIndex, paragraphs.size())) {
      return null;
    }
    int seen = 0;
    for (InlineElement e : paragraphs.get(paragraphIndex).inlineElements()) {
      if (e instanceof Hyperlink) {
        if (seen == hyperlinkIndex) {
          return (Hyperlink) e;
        }
        seen++;
      }
    }
    return null;
  }

  /** 配合 {@link #locateHyperlink}：返回定位失败时的中文错误串（需重新解析边界以给准确数字）。 */
  private String locateHyperlinkFailed(String docId, int paragraphIndex, int hyperlinkIndex) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    var paragraphs = doc.paragraphs();
    if (outOfBounds(paragraphIndex, paragraphs.size())) {
      return indexError("段落索引", paragraphIndex, paragraphs.size());
    }
    long count = hyperlinkCount(paragraphs.get(paragraphIndex));
    return "错误：超链接索引 " + hyperlinkIndex + " 越界（该段含 " + count + " 个超链接）";
  }

  /** 定位表格单元格；成功返回 "ok"，失败返回中文错误串（沿用工具返回值约定）。 */
  private static String locateCell(Document doc, int tableIndex, int rowIndex, int cellIndex) {
    var tables = doc.tables();
    if (outOfBounds(tableIndex, tables.size())) {
      return indexError("表格索引", tableIndex, tables.size());
    }
    var rows = tables.get(tableIndex).rows();
    if (outOfBounds(rowIndex, rows.size())) {
      return indexError("行索引", rowIndex, rows.size());
    }
    var cells = rows.get(rowIndex).cells();
    if (outOfBounds(cellIndex, cells.size())) {
      return indexError("单元格索引", cellIndex, cells.size());
    }
    return "ok";
  }

  /** {@link #locateCell} 已校验过边界，这里按同样链路再取一次活 Cell。 */
  private static com.non.docx.core.api.table.Cell locateCellObj(
      Document doc, int tableIndex, int rowIndex, int cellIndex) {
    return doc.tables().get(tableIndex).rows().get(rowIndex).cells().get(cellIndex);
  }

  private static boolean outOfBounds(int index, int size) {
    return index < 0 || index >= size;
  }

  private static String indexError(String what, int index, int size) {
    return "错误：" + what + " " + index + " 越界（共 " + size + "）";
  }

  private static String docNotFound(String docId) {
    return "错误：文档句柄 " + docId + " 不存在（未 open_docx 或已 close_docx）";
  }

  /** 取异常根因消息，避免把 POI/XmlBeans 长栈抛回 LLM。 */
  private static String rootMessage(Throwable e) {
    Throwable cur = e;
    while (cur.getCause() != null && cur.getCause() != cur) {
      cur = cur.getCause();
    }
    return cur.getMessage();
  }
}
