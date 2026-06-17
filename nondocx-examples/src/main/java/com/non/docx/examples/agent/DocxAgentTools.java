package com.non.docx.examples.agent;

import com.non.chain.tool.ToolDef;
import com.non.chain.tool.ToolParam;
import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.InlineElement;
import com.non.docx.core.api.text.Hyperlink;
import com.non.docx.core.api.text.Paragraph;
import com.non.docx.core.api.text.Run;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
