package com.non.docx.examples.agent;

import com.non.chain.tool.ToolDef;
import com.non.chain.tool.ToolParam;
import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.InlineElement;
import com.non.docx.core.api.table.Cell;
import com.non.docx.core.api.text.Hyperlink;
import com.non.docx.core.api.text.Paragraph;
import com.non.docx.core.api.text.Run;
import com.non.docx.core.api.toc.TableOfContents;
import com.non.docx.core.api.toc.TocEntry;
import com.non.docx.core.api.track.CellChangeDetails;
import com.non.docx.core.api.track.ChangeDetails;
import com.non.docx.core.api.track.PropertyChangeDetails;
import com.non.docx.core.api.track.TextChangeDetails;
import com.non.docx.core.api.track.TrackedChange;
import com.non.docx.core.api.track.TrackedChanges;
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
   * 读取正文多个段落的结构摘要（文本 + run 数 + 是否含超链接）。
   *
   * <p><b>批量语义（v2）。</b> 入参是<b>段落索引数组</b> {@code paragraph_indexes},长度 1 即单次读取,
   * 多个即一次读多段——避免"了解文档结构"这类场景里逐段调用造成大量 LLM 往返。 越界的索引不会中断整批,
   * 而是标在结果里("索引越界,共 N"),让 Agent 据此修正后重读。
   *
   * <p>摘要里带上 run 数与超链接数，让 Agent 一次读到寻址所需的上下文，不必再盲猜索引。
   *
   * <p><b>OOXML → POI → nondocx 三层</b>:读多段在三层上都没有结构变化—— OOXML 仍是 {@code <w:p>}
   * 序列,POI 仍是 {@code XWPFParagraph} 列表,nondocx 仍是 {@code doc.paragraphs()}; 工具层只是把
   * "取一段"循环 N 次,活对象链与单次版完全一致。
   */
  @ToolDef(
      name = "read_paragraph",
      description =
          "读取正文多个段落的结构摘要(文本、run 数、是否含超链接)。"
              + "paragraph_indexes 是段落索引数组(0 起),长度 1 即单次读,可一次读多段。"
              + "越界索引不中断整批,会在结果里标注。")
  public String readParagraph(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(
              name = "paragraph_indexes",
              description = "段落索引数组(0 起),如 [0,1,2];单次传 [0]")
          List<Integer> paragraphIndexes) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    var paragraphs = doc.paragraphs();
    List<Object> indexes = coerceList(paragraphIndexes);
    if (indexes.isEmpty()) {
      return "段落索引数组为空";
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < indexes.size(); i++) {
      int idx = ((Number) indexes.get(i)).intValue();
      if (i > 0) {
        sb.append('\n');
      }
      if (outOfBounds(idx, paragraphs.size())) {
        sb.append("段落 ").append(idx).append(": ").append(indexError("段落索引", idx, paragraphs.size()));
        continue;
      }
      Paragraph p = paragraphs.get(idx);
      int runCount = p.runs().size();
      long hyperlinkCount = hyperlinkCount(p);
      sb.append("段落 ").append(idx).append('\n');
      sb.append("文本: ").append(p.text()).append('\n');
      sb.append("run 数: ").append(runCount).append('\n');
      sb.append("超链接数: ").append(hyperlinkCount);
    }
    return sb.toString();
  }

  /**
   * 批量读取正文若干 run 的文本与样式摘要。
   *
   * <p><b>批量语义（v2）。</b> 入参是<b>对象数组</b> {@code runs},每个对象含 {@code paragraph_index}(int)、{@code run_index}(int)。
   * 数组长度 1 即读单个 run。读类幂等,越界坐标标注不中断。
   */
  @ToolDef(
      name = "read_run",
      description =
          "批量读取正文若干 run 的文本与样式摘要。"
              + "runs 是对象数组,每个对象含 paragraph_index(int,段落索引 0 起)、run_index(int,run 索引 0 起,不含超链接)。"
              + "单个对象用长度 1 的数组。越界坐标不中断,会在结果里标注。")
  public String readRun(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(
              name = "runs",
              description =
                  "对象数组,每个对象含 paragraph_index(int)、run_index(int),"
                      + "如 [{\"paragraph_index\":0,\"run_index\":0}]")
          List<Map<String, Object>> runs) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    var paragraphs = doc.paragraphs();
    List<Object> list = coerceList(runs);
    if (list.isEmpty()) {
      return "runs 为空";
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      Object item = list.get(i);
      String tag = "[" + i + "] ";
      if (!(item instanceof Map)) {
        sb.append(tag).append("错误:该条不是对象(").append(item).append(")");
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) item;
      int paragraphIndex;
      int runIndex;
      try {
        paragraphIndex = getInt(m, "paragraph_index");
        runIndex = getInt(m, "run_index");
      } catch (RuntimeException e) {
        sb.append(tag).append("错误:").append(e.getMessage());
        continue;
      }
      if (outOfBounds(paragraphIndex, paragraphs.size())) {
        sb.append(tag).append(indexError("段落索引", paragraphIndex, paragraphs.size()));
        continue;
      }
      var paraRuns = paragraphs.get(paragraphIndex).runs();
      if (outOfBounds(runIndex, paraRuns.size())) {
        sb.append(tag).append(indexError("run 索引", runIndex, paraRuns.size()));
        continue;
      }
      Run run = paraRuns.get(runIndex);
      sb.append(tag).append("段落 ").append(paragraphIndex).append(" run ").append(runIndex)
          .append("\n文本: ").append(run.text()).append("\n样式: ").append(run.style());
    }
    return sb.toString();
  }

  /**
   * 批量替换正文若干 run 的文本（活对象直写，需 save_docx 落盘）。
   *
   * <p><b>批量语义（v2）。</b> 入参是<b>对象数组</b> {@code edits},每个对象描述一次替换:
   *
   * <ul>
   *   <li>{@code paragraph_index}:整数,必填,段落索引(0 起)
   *   <li>{@code run_index}:整数,必填,run 索引(0 起,不含超链接)
   *   <li>{@code text}:字符串,必填,新文本
   * </ul>
   *
   * <p>数组长度 1 即单次替换;多个即一次改多处(如同时改标题、日期、负责人)。 与"先 search_text 定位再批量
   * replace_run_text"是天然搭档。
   *
   * <p><b>失败语义:collect-errors。</b> 逐条尝试,某条越界/缺字段不会中断整批—— 成功的真写入(活对象直写),
   * 失败的记中文错误串;末尾汇总成功/失败条数。理由:活对象改动难以整体回滚,逐条收集错误更贴合 Agent 循环
   * "读回错误自行修正"的现有约定,也不浪费整批调用。
   *
   * <p><b>无需逆序。</b> 文本替换只改 run 的文本内容,不增删 run 列表,故同段多次替换不影响后续条目的 run 索引。
   */
  @ToolDef(
      name = "replace_run_text",
      description =
          "批量替换正文若干 run 的文本(改完需 save_docx 落盘)。edits 是对象数组,每个对象含字段:"
              + "paragraph_index(整数,段落索引 0 起)、run_index(整数,run 索引 0 起,不含超链接)、"
              + "text(字符串,新文本)。单个对象用长度 1 的数组。可一次改多处;部分失败不中断,"
              + "返回每条成功/失败明细。")
  public String replaceRunText(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(
              name = "edits",
              description =
                  "对象数组,每个对象含 paragraph_index(int)、run_index(int)、text(string),"
                      + "如 [{\"paragraph_index\":0,\"run_index\":0,\"text\":\"新文本\"}]")
          List<Map<String, Object>> edits) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    var paragraphs = doc.paragraphs();
    List<Object> list = coerceList(edits);
    if (list.isEmpty()) {
      return "edits 为空";
    }
    StringBuilder sb = new StringBuilder();
    int ok = 0;
    int fail = 0;
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      Object item = list.get(i);
      String tag = "[" + i + "] ";
      if (!(item instanceof Map)) {
        sb.append(tag).append("错误:该条不是对象(").append(item).append(")");
        fail++;
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) item;
      int paragraphIndex;
      int runIndex;
      String text;
      try {
        paragraphIndex = getInt(m, "paragraph_index");
        runIndex = getInt(m, "run_index");
        text = getStr(m, "text");
      } catch (RuntimeException e) {
        sb.append(tag).append("错误:").append(e.getMessage());
        fail++;
        continue;
      }
      if (outOfBounds(paragraphIndex, paragraphs.size())) {
        sb.append(tag).append(indexError("段落索引", paragraphIndex, paragraphs.size()));
        fail++;
        continue;
      }
      var runs = paragraphs.get(paragraphIndex).runs();
      if (outOfBounds(runIndex, runs.size())) {
        sb.append(tag).append(indexError("run 索引", runIndex, runs.size()));
        fail++;
        continue;
      }
      runs.get(runIndex).text(text);
      sb.append(tag)
          .append("段落 ")
          .append(paragraphIndex)
          .append(" run ")
          .append(runIndex)
          .append(" → \"")
          .append(text)
          .append("\" ✓");
      ok++;
    }
    sb.append("\n成功 ").append(ok).append(" 条,失败 ").append(fail).append(" 条");
    return sb.toString();
  }

  /**
   * 在正文末尾批量追加若干单 run 段落。
   *
   * <p><b>批量语义（v2）。</b> 入参是<b>文本数组</b> {@code texts},长度 1 即追加一段,多个即一次追加多段。
   * 追加是"无索引"操作(总是加在末尾),天然全部成功、无越界、无索引漂移,是批量工具里最简单的一类。
   *
   * <p>返回按数组顺序列出每段内容,如 {@code 已追加 2 段:[0] "甲" [1] "乙"}。
   */
  @ToolDef(
      name = "append_paragraph",
      description =
          "在正文末尾批量追加若干单 run 段落(改完需 save_docx 落盘)。texts 是文本数组,"
              + "长度 1 即追加一段,可一次追加多段。")
  public String appendParagraph(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "texts", description = "段落文本数组,如 [\"第一段\",\"第二段\"];单段传 [\"甲\"]")
          List<String> texts) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    List<Object> list = coerceList(texts);
    if (list.isEmpty()) {
      return "texts 为空";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("已追加 ").append(list.size()).append(" 段:");
    for (int i = 0; i < list.size(); i++) {
      String text = String.valueOf(list.get(i));
      doc.addParagraph(text);
      sb.append(" [").append(i).append("] \"").append(text).append("\"");
    }
    return sb.toString();
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
   * 批量读取表格若干单元格的结构摘要（文本 + 段落数 + 各段 run 数）。
   *
   * <p><b>批量语义（v2）。</b> 入参是<b>对象数组</b> {@code cells},每个对象含三个坐标字段:
   *
   * <ul>
   *   <li>{@code table_index}:整数,必填,表格索引(0 起)
   *   <li>{@code row_index}:整数,必填,行索引(0 起)
   *   <li>{@code cell_index}:整数,必填,单元格索引(0 起)
   * </ul>
   *
   * <p>数组长度 1 即读单个单元格;多个即一次读多处(如通读一整行的所有单元格)。表格寻址链 table→row→cell→paragraph→run
   * 较深,批量版把"读 N 个单元格"从 N 轮 LLM 往返压成 1 轮。
   *
   * <p><b>读类幂等,无失败中断。</b> 越界的坐标在结果里标注("...索引越界,共 N"),不中断整批。
   */
  @ToolDef(
      name = "read_table_cell",
      description =
          "批量读取表格若干单元格的结构摘要(文本、段落数、各段 run 数)。"
              + "cells 是对象数组,每个对象含 table_index(int,表格索引 0 起)、"
              + "row_index(int,行索引 0 起)、cell_index(int,单元格索引 0 起)。"
              + "单个对象用长度 1 的数组。越界坐标不中断,会在结果里标注。")
  public String readTableCell(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(
              name = "cells",
              description =
                  "对象数组,每个对象含 table_index、row_index、cell_index(int),"
                      + "如 [{\"table_index\":0,\"row_index\":0,\"cell_index\":0}]")
          List<Map<String, Object>> cells) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    var tables = doc.tables();
    List<Object> list = coerceList(cells);
    if (list.isEmpty()) {
      return "cells 为空";
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      Object item = list.get(i);
      if (!(item instanceof Map)) {
        sb.append("[").append(i).append("] 错误:该条不是对象(").append(item).append(")");
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) item;
      int tableIndex;
      int rowIndex;
      int cellIndex;
      try {
        tableIndex = getInt(m, "table_index");
        rowIndex = getInt(m, "row_index");
        cellIndex = getInt(m, "cell_index");
      } catch (RuntimeException e) {
        sb.append("[").append(i).append("] 错误:").append(e.getMessage());
        continue;
      }
      String coord = "(" + tableIndex + "," + rowIndex + "," + cellIndex + ")";
      String cellResult = locateCell(doc, tableIndex, rowIndex, cellIndex);
      if (cellResult.startsWith("错误")) {
        sb.append("[").append(i).append("] 单元格 ").append(coord).append(": ").append(cellResult);
        continue;
      }
      var cell = locateCellObj(doc, tableIndex, rowIndex, cellIndex);
      var paras = cell.paragraphs();
      sb.append("[").append(i).append("] 单元格 ").append(coord).append('\n');
      sb.append("文本: ").append(cell.text()).append('\n');
      sb.append("段落数: ").append(paras.size());
      for (int p = 0; p < paras.size(); p++) {
        sb.append("\n  段落 ").append(p).append(": run 数 ").append(paras.get(p).runs().size());
      }
    }
    return sb.toString();
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

  /**
   * 批量替换表格若干单元格内 run 的文本（活对象直写，需 save_docx 落盘）。
   *
   * <p><b>批量语义（v2）。</b> 入参是<b>对象数组</b> {@code edits},每个对象描述一次单元格 run 替换:
   *
   * <ul>
   *   <li>{@code table_index}:整数,必填,表格索引(0 起)
   *   <li>{@code row_index}:整数,必填,行索引(0 起)
   *   <li>{@code cell_index}:整数,必填,单元格索引(0 起)
   *   <li>{@code paragraph_index}:整数,必填,单元格内段落索引(0 起)
   *   <li>{@code run_index}:整数,必填,run 索引(0 起)
   *   <li>{@code text}:字符串,必填,新文本
   * </ul>
   *
   * <p>数组长度 1 即单次替换;多个即一次改多处(如批量更新状态表的某一列)。 表格寻址链
   * table→row→cell→paragraph→run 较深,批量版把"改 N 个单元格"从 N 轮 LLM 往返压成 1 轮。
   *
   * <p><b>失败语义:collect-errors。</b> 逐条尝试,坐标越界/缺字段的条目记错误串不中断整批;末尾汇总成功/失败条数。
   *
   * <p><b>无需逆序。</b> 文本替换不增删 run/cell 列表结构,条目间互不影响。
   */
  @ToolDef(
      name = "replace_table_cell_run_text",
      description =
          "批量替换表格若干单元格内 run 的文本(改完需 save_docx 落盘)。edits 是对象数组,每个对象含字段:"
              + "table_index(int,表格索引 0 起)、row_index(int,行索引 0 起)、"
              + "cell_index(int,单元格索引 0 起)、paragraph_index(int,单元格内段落索引 0 起)、"
              + "run_index(int,run 索引 0 起)、text(string,新文本)。"
              + "单个对象用长度 1 的数组。部分失败不中断,返回每条成功/失败明细。")
  public String replaceTableCellRunText(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(
              name = "edits",
              description =
                  "对象数组,每个对象含 table_index、row_index、cell_index、paragraph_index、run_index(int)"
                      + "与 text(string),如 [{\"table_index\":0,\"row_index\":0,\"cell_index\":0,"
                      + "\"paragraph_index\":0,\"run_index\":0,\"text\":\"已完成\"}]")
          List<Map<String, Object>> edits) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    var tables = doc.tables();
    List<Object> list = coerceList(edits);
    if (list.isEmpty()) {
      return "edits 为空";
    }
    StringBuilder sb = new StringBuilder();
    int ok = 0;
    int fail = 0;
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      Object item = list.get(i);
      String tag = "[" + i + "] ";
      if (!(item instanceof Map)) {
        sb.append(tag).append("错误:该条不是对象(").append(item).append(")");
        fail++;
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) item;
      int tableIndex;
      int rowIndex;
      int cellIndex;
      int paragraphIndex;
      int runIndex;
      String text;
      try {
        tableIndex = getInt(m, "table_index");
        rowIndex = getInt(m, "row_index");
        cellIndex = getInt(m, "cell_index");
        paragraphIndex = getInt(m, "paragraph_index");
        runIndex = getInt(m, "run_index");
        text = getStr(m, "text");
      } catch (RuntimeException e) {
        sb.append(tag).append("错误:").append(e.getMessage());
        fail++;
        continue;
      }
      // 复用 locateCell/locateCellObj 的逐层边界检查(它们返回中文错误串 / 活对象)。
      String cellResult = locateCell(doc, tableIndex, rowIndex, cellIndex);
      if (cellResult.startsWith("错误")) {
        sb.append(tag).append(cellResult);
        fail++;
        continue;
      }
      var cell = locateCellObj(doc, tableIndex, rowIndex, cellIndex);
      var paras = cell.paragraphs();
      if (outOfBounds(paragraphIndex, paras.size())) {
        sb.append(tag).append(indexError("单元格内段落索引", paragraphIndex, paras.size()));
        fail++;
        continue;
      }
      var runs = paras.get(paragraphIndex).runs();
      if (outOfBounds(runIndex, runs.size())) {
        sb.append(tag).append(indexError("run 索引", runIndex, runs.size()));
        fail++;
        continue;
      }
      runs.get(runIndex).text(text);
      sb.append(tag)
          .append("单元格 (")
          .append(tableIndex)
          .append(',')
          .append(rowIndex)
          .append(',')
          .append(cellIndex)
          .append(") 段落 ")
          .append(paragraphIndex)
          .append(" run ")
          .append(runIndex)
          .append(" → \"")
          .append(text)
          .append("\" ✓");
      ok++;
    }
    sb.append("\n成功 ").append(ok).append(" 条,失败 ").append(fail).append(" 条");
    return sb.toString();
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

  /**
   * 修改正文某段某超链接的显示文本和/或目标 URL（活对象直写，需 save_docx 落盘）。
   *
   * <p><b>合并说明（v2）。</b> 旧版有 {@code update_hyperlink_text} 和 {@code update_hyperlink_url} 两个工具,改一个超链接
   * 要调两次。现合并为 {@code update_hyperlink}:{@code text} 与 {@code url} 都<b>可选</b>,至少传一个——
   * 传 {@code text} 改显示文本、传 {@code url} 改目标地址、两个都传则一次改齐。
   *
   * <p>超链接是段落 {@code inlineElements()} 里的一类(而非 {@code runs()}),与 nondocx 模型一致。
   */
  @ToolDef(
      name = "update_hyperlink",
      description =
          "修改正文第 paragraph_index 段第 hyperlink_index 个超链接(均 0 起)的显示文本和/或目标 URL。"
              + "text 与 url 都可选,至少传一个:只传 text 改显示文本、只传 url 改地址、都传则一次改齐。"
              + "改完需 save_docx 落盘。")
  public String updateHyperlink(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "paragraph_index", description = "段落索引（0 起）") int paragraphIndex,
      @ToolParam(name = "hyperlink_index", description = "超链接索引（0 起）") int hyperlinkIndex,
      @ToolParam(name = "text", description = "新的显示文本(可选,不传则不改)", required = false) String text,
      @ToolParam(name = "url", description = "新的目标 URL(可选,不传则不改)", required = false) String url) {
    if ((text == null || text.isEmpty()) && (url == null || url.isEmpty())) {
      return "错误:text 和 url 至少传一个";
    }
    Hyperlink link = locateHyperlink(docId, paragraphIndex, hyperlinkIndex);
    if (link == null) {
      return locateHyperlinkFailed(docId, paragraphIndex, hyperlinkIndex);
    }
    List<String> done = new ArrayList<>();
    if (text != null && !text.isEmpty()) {
      link.text(text);
      done.add("显示文本 → \"" + text + "\"");
    }
    if (url != null && !url.isEmpty()) {
      try {
        link.url(url);
      } catch (RuntimeException e) {
        return "错误：无法修改超链接 URL（" + rootMessage(e) + "）";
      }
      done.add("URL → " + url);
    }
    return "已修改：段落 " + paragraphIndex + " 超链接 " + hyperlinkIndex + " 的 " + String.join("、", done);
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
   * 索引，如需改超链接用 {@code update_hyperlink}。
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

  // ==================== H. 修订(tracked changes) ====================
  //
  // OOXML 三层递进(整组共用):
  //   OOXML —— 修订标记散落在 word/document.xml 正文各处:文本类 <w:ins>/<w:del>、移动类 <w:moveFrom>/<w:moveTo>、
  //            属性类 rPrChange 等(嵌在 <w:rPr> 内)、单元格类 cellIns/cellDel(嵌在 <w:tcPr> 内)。开关在
  //            settings.xml 的 <w:trackChanges/>。
  //   POI   —— 没有 XWPFTrackedChanges 高层 API;nondocx 用 XmlCursor 按文档顺序遍历 CTBody,按本地名识别修订类型,
  //            解析为领域视图。
  //   nondocx —— 统一门面 doc.trackedChanges();每条修订有 stable id(进程内稳定),accept/reject 按 family 分专用方法。
  //
  // id 是寻址凭证:所有单条 accept/reject 工具都按 stable id 定位。Agent 必须先 list_tracked_changes 拿到 id,
  // 再调对应 accept/reject(同 search_text 之于 replace_run_text)。

  /** 读取文档是否开启修订记录({@code settings.xml} 的 {@code <w:trackChanges/>})。 */
  @ToolDef(
      name = "get_tracked_changes_enabled",
      description = "返回文档是否开启了修订记录(Word 里勾选「修订」开关)。true=已开启")
  public String getTrackedChangesEnabled(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    return "修订记录: " + (doc.trackedChanges().enabled() ? "已开启" : "未开启");
  }

  /**
   * 写入修订模式开关({@code settings.xml} 的 {@code <w:trackChanges/>})。
   *
   * <p><b>何时用。</b> 文档要交还给人<b>接力编辑</b>、且希望人在 Word 里的后续手动改动也被自动追踪时,把开关打开。 对 Agent 自己用 {@code
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
    Document doc = sessions.get(docId);
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
    Document doc = sessions.get(docId);
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
    Document doc = sessions.get(docId);
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
   * ——典型场景:list_tracked_changes 后想接受<b>特定几条</b>(非全量 accept_all,也非按作者),目前只能逐条调,
   * 批量版压成一次。
   *
   * <p>仅作用于 TEXT(ins/del)与 MOVE(moveFrom/moveTo)family;属性类用 {@code accept_property_change},单元格类用
   * {@code accept_cell_change}。
   *
   * <p><b>失败语义:collect-errors。</b> 逐条尝试,某条 family 不符/id 不存在记错误串不中断整批;末尾汇总成功/失败条数。
   * 移动类 id 在 accept/reject 时由 core 自动联动配对的另一端(moveFrom↔moveTo),无需 Agent 手动配对。
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
    return applyRevisionsBatch(docId, ids, /* accept= */ true, /* family= */ "text_or_move");
  }

  /**
   * 批量撤销(reject)文本/移动类修订:插入丢弃、删除恢复;移动类两端联动。
   *
   * <p><b>批量语义（v2）。</b> 入参是<b>stable id 数组</b> {@code ids},长度 1 即处理单条,多个即一次处理多条。语义、失败语义、
   * family 限定与 {@link #acceptTextOrMoveRevision} 完全对称,只是动作从 accept 变 reject。
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
    return applyRevisionsBatch(docId, ids, /* accept= */ false, /* family= */ "text_or_move");
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
    Document doc = sessions.get(docId);
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
    Document doc = sessions.get(docId);
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
    Document doc = sessions.get(docId);
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
    Document doc = sessions.get(docId);
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
   * <p><b>批量语义（v2）。</b> 入参是<b>id 数组</b> {@code ids}(来自 list_tracked_changes),长度 1 即处理单条。仅作用于 PROPERTY
   * family;文本/移动类用 {@code accept_text_or_move_revision},单元格类用 {@code accept_cell_change}。
   *
   * <p><b>失败语义:collect-errors。</b> 探针验证:id 是路径坐标编码、accept 一条后其余 id 不漂移,故可安全逐条循环。
   * family 不符/id 不存在的条目记错误不中断。
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
      @ToolParam(name = "ids", description = "stable id 数组,如 [\"cell_ins:...\"];单条传 [\"cell_ins:...\"]")
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
      @ToolParam(name = "ids", description = "stable id 数组,如 [\"cell_ins:...\"];单条传 [\"cell_ins:...\"]")
          List<String> ids) {
    return applyRevisionsByIds(docId, ids, TrackedChanges::rejectCell, "撤销单元格类");
  }

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
   * <p>逐条尝试:某条抛异常(family 不符/id 不存在)记错误串不中断,core 的 accept/reject 对 move 类会自动
   * 联动配对端。返回每条结果 + 末尾成功/失败汇总,与其它 collect-errors 工具格式一致。
   *
   * <p>委托给通用 {@link #applyRevisionsByIds},传入 {@code TrackedChanges::accept}/{@code reject} 回调。
   */
  private String applyRevisionsBatch(String docId, List<String> ids, boolean accept, String family) {
    // family 仅用于文档语义,不拼进 verb(避免"已应用text_or_move"这种生硬措辞);结果串用自然的"已应用/已撤销"。
    return applyRevisionsByIds(
        docId,
        ids,
        accept ? TrackedChanges::accept : TrackedChanges::reject,
        accept ? "应用" : "撤销");
  }

  /**
   * 三类 accept/reject(text/move、property、cell)的统一批量实现。
   *
   * <p>{@code action} 是对单个 id 执行的动作回调(如 {@code TrackedChanges::acceptProperty}),由各工具传入,从而把三套几乎相同的
   * 循环收成一处。{@code verb} 是结果串里的动词(如"应用属性类")。
   *
   * <p><b>为何可安全逐条循环(id 不漂移)。</b> 探针验证:修订 id 是路径坐标编码(如 {@code cell_ins:body0.table0.row0.cell0:1}),
   * accept/reject 一条后,其余修订的 id 不变——即使 cellDel 的 accept 会移除整个单元格,剩余修订的坐标与 w:id 也不受影响。
   * 故无需排序、去重或重新 list。某条抛异常(family 不符/id 不存在)记错误不中断(collect-errors)。
   */
  private String applyRevisionsByIds(
      String docId,
      List<String> ids,
      java.util.function.BiConsumer<TrackedChanges, String> action,
      String verb) {
    Document doc = sessions.get(docId);
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
        sb.append("[").append(i).append("] ").append(id).append(": 错误(").append(rootMessage(e)).append(")");
        fail++;
      }
    }
    sb.append("\n成功 ").append(ok).append(" 条,失败 ").append(fail).append(" 条");
    return sb.toString();
  }

  // ==================== I. 修订创作(tracked changes authoring) ====================
  //
  // 与 H 组(读 + accept/reject)正交。四类创作能力(见 poi-bridge.md N17):
  //   带格式插入、rPrChange、cellIns/cellDel、move。
  // 全部沿用「显式 tracked 方法」:author 必传,date/w:id 自动分配,与 <w:trackChanges/> 开关正交。
  // 创作出的修订随后可被 H 组工具(list/accept/reject)读回与处理。

  /**
   * 批量在若干段落末尾插入 tracked 插入修订(<w:ins>,带可选内联样式)。
   *
   * <p><b>批量语义（v2）。</b> {@code author} 是<b>共享顶层参数</b>(同一批插入通常同一作者);{@code edits} 是<b>对象数组</b>,每个对象含:
   *
   * <ul>
   *   <li>{@code paragraph_index}:整数,必填,目标段落索引(0 起,正文段落)
   *   <li>{@code text}:字符串,必填,插入的文本
   *   <li>{@code bold}:布尔,可选,默认 false
   *   <li>{@code italic}:布尔,可选,默认 false
   *   <li>{@code color}:字符串,可选,颜色十六进制如 FF0000
   * </ul>
   *
   * <p>数组长度 1 即单段插入;多个即一次插多处(如给若干段落各加一句批注)。
   *
   * <p><b>失败语义:collect-errors。</b> 逐条尝试,段落越界/缺字段的条目记错误不中断;末尾汇总成功/失败条数。
   *
   * <p><b>无索引漂移。</b> insert 是"往段末追加新 run",不改既有 run 列表,条目间互不影响。创作出的修订可被
   * {@code list_tracked_changes} 读回、{@code accept_text_or_move_revision} 处理。
   */
  @ToolDef(
      name = "insert_tracked_run",
      description =
          "批量在若干段落末尾插入被追踪的插入修订(<w:ins>),可选带内联样式(bold/italic/color)。"
              + "author 是共享修订作者(必填)。edits 是对象数组,每个对象含:"
              + "paragraph_index(int,目标段落索引 0 起)、text(string,插入文本),"
              + "以及可选 bold(bool)、italic(bool)、color(string,十六进制如 FF0000)。"
              + "单个对象用长度 1 的数组。部分失败不中断,返回每条成功/失败明细。")
  public String insertTrackedRun(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "author", description = "修订作者(整批共享)") String author,
      @ToolParam(
              name = "edits",
              description =
                  "对象数组,每个对象含 paragraph_index(int)、text(string),"
                      + "可选 bold(bool)、italic(bool)、color(string),"
                      + "如 [{\"paragraph_index\":0,\"text\":\"插入\",\"bold\":true}]")
          List<Map<String, Object>> edits) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    List<Paragraph> paragraphs = doc.paragraphs();
    List<Object> list = coerceList(edits);
    if (list.isEmpty()) {
      return "edits 为空";
    }
    StringBuilder sb = new StringBuilder();
    int ok = 0;
    int fail = 0;
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      Object item = list.get(i);
      String tag = "[" + i + "] ";
      if (!(item instanceof Map)) {
        sb.append(tag).append("错误:该条不是对象(").append(item).append(")");
        fail++;
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) item;
      int paragraphIndex;
      String text;
      try {
        paragraphIndex = getInt(m, "paragraph_index");
        text = getStr(m, "text");
      } catch (RuntimeException e) {
        sb.append(tag).append("错误:").append(e.getMessage());
        fail++;
        continue;
      }
      if (outOfBounds(paragraphIndex, paragraphs.size())) {
        sb.append(tag).append(indexError("段落索引", paragraphIndex, paragraphs.size()));
        fail++;
        continue;
      }
      boolean bold = boolVal(m.get("bold"));
      boolean italic = boolVal(m.get("italic"));
      String color = m.get("color") == null ? null : String.valueOf(m.get("color"));
      try {
        Run r = paragraphs.get(paragraphIndex).addInsertion(author, text);
        if (bold) {
          r.bold();
        }
        if (italic) {
          r.italic();
        }
        if (color != null && !color.isBlank()) {
          r.color(color);
        }
        sb.append(tag).append("段落 ").append(paragraphIndex).append(" 插入 tracked run(文本=\"").append(text).append("\") ✓");
        ok++;
      } catch (RuntimeException e) {
        sb.append(tag).append("错误:").append(rootMessage(e));
        fail++;
      }
    }
    sb.append("\n成功 ").append(ok).append(" 条,失败 ").append(fail).append(" 条");
    return sb.toString();
  }

  /**
   * 批量把若干已有 run 标记为被删除(tracked del,文字转为 {@code <w:delText>}、划删除线标记)。
   *
   * <p><b>批量语义（v2）。</b> {@code author} 共享;{@code edits} 是对象数组,每个对象含 {@code paragraph_index}(int)、
   * {@code run_index}(int)。数组长度 1 即单条删除。
   *
   * <p>核心语义:这不是立即删除——文字仍在文档里,只是被标为「待删除的修订」。accept 后才真正消失,reject 则恢复。
   *
   * <p><b>为什么是「快照 + 去重」而非「逆序」。</b> 探针验证:{@code addDeletion} 后 POI 仍把 {@code <w:del>} 里的 run 计入
   * {@code runs()},故<b>索引不漂移</b>(run0 永远是 run0);但被删 run 的 wrapper 会失效, 重复删同一 wrapper 会抛
   * {@code XmlValueDisconnectedException}。因此正确做法是:先一次性按原始索引快照所有目标 wrapper, 再用 identity 去重后逐个删除——既规避
   * wrapper 失效,又让同段多个删除互不干扰。
   *
   * <p><b>失败语义:collect-errors。</b> 越界/缺字段/重复的条目记错误或提示不中断;末尾汇总成功/失败条数。
   */
  @ToolDef(
      name = "delete_run_tracked",
      description =
          "批量把正文若干 run 标记为被删除(tracked del:文字转 <w:delText>、划删除线)。"
              + "author 是共享修订作者(必填)。edits 是对象数组,每个对象含 "
              + "paragraph_index(int,段落索引 0 起)、run_index(int,run 索引 0 起)。"
              + "单个对象用长度 1 的数组。不是立即删除,accept 后才真正消失。"
              + "部分失败不中断,返回每条成功/失败明细。")
  public String deleteRunTracked(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "author", description = "修订作者(整批共享)") String author,
      @ToolParam(
              name = "edits",
              description =
                  "对象数组,每个对象含 paragraph_index(int)、run_index(int),"
                      + "如 [{\"paragraph_index\":0,\"run_index\":0}]")
          List<Map<String, Object>> edits) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    List<Object> list = coerceList(edits);
    if (list.isEmpty()) {
      return "edits 为空";
    }
    var paragraphs = doc.paragraphs();
    StringBuilder sb = new StringBuilder();
    int ok = 0;
    int fail = 0;
    // 按坐标去重:同一 (paragraphIndex, runIndex) 只删一次。
    // 注意 runs().get(i) 每次返回新的 Run 包装对象,不能用 identity 比较;改用坐标字符串。
    // 重复 addDeletion 同一 run 会抛 XmlValueDisconnectedException(见探针验证),故必须去重。
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      String tag = "[" + i + "] ";
      Object item = list.get(i);
      if (!(item instanceof Map)) {
        sb.append(tag).append("错误:该条不是对象(").append(item).append(")");
        fail++;
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) item;
      int paragraphIndex;
      int runIndex;
      try {
        paragraphIndex = getInt(m, "paragraph_index");
        runIndex = getInt(m, "run_index");
      } catch (RuntimeException e) {
        sb.append(tag).append("错误:").append(e.getMessage());
        fail++;
        continue;
      }
      if (outOfBounds(paragraphIndex, paragraphs.size())) {
        sb.append(tag).append(indexError("段落索引", paragraphIndex, paragraphs.size()));
        fail++;
        continue;
      }
      var runs = paragraphs.get(paragraphIndex).runs();
      if (outOfBounds(runIndex, runs.size())) {
        sb.append(tag).append(indexError("run 索引", runIndex, runs.size()));
        fail++;
        continue;
      }
      String coord = paragraphIndex + ":" + runIndex;
      if (!seen.add(coord)) {
        // 同一坐标已在本批删除过——再删会抛 XmlValueDisconnectedException,跳过并提示。
        sb.append(tag).append("跳过:段落 ").append(paragraphIndex).append(" run ").append(runIndex).append(" 在本批已处理");
        fail++;
        continue;
      }
      Run target = runs.get(runIndex);
      try {
        // 先快照文本:addDeletion 会把 run 迁入 <w:del>、其 <w:t> 转为 <w:delText>,
        // 迁移后原 Run wrapper 已不可靠(读 text() 会 NPE),故必须先取。
        String text = target.text();
        paragraphs.get(paragraphIndex).addDeletion(author, target);
        sb.append(tag).append("段落 ").append(paragraphIndex).append(" run ").append(runIndex).append(" tracked del(文本=\"").append(text).append("\") ✓");
        ok++;
      } catch (RuntimeException e) {
        sb.append(tag).append("错误:").append(rootMessage(e));
        fail++;
      }
    }
    sb.append("\n成功 ").append(ok).append(" 条,失败 ").append(fail).append(" 条");
    return sb.toString();
  }

  /**
   * 批量以 tracked 方式替换若干 run 的文本(每个删旧 + 插新两条配对修订)。
   *
   * <p><b>批量语义（v2）。</b> {@code author} 共享;{@code edits} 是对象数组,每个对象含 {@code paragraph_index}(int)、
   * {@code run_index}(int)、{@code new_text}(string)。数组长度 1 即单条替换。
   *
   * <p>OOXML 没有「替换」元素——替换就是紧挨着的 {@code <w:del>}(旧文本)+{@code <w:ins>}(新文本)。新 run 复制旧 run 的样式,
   * 贴近「改字但保留格式」的直觉。
   *
   * <p><b>索引不漂移 + 快照去重</b>(同 {@link #deleteRunTracked}):{@code replaceTracked} 后 POI 仍把 run 计入 {@code runs()},
   * 故索引稳定;但 wrapper 会失效,重复替换同一 wrapper 会抛异常。故先快照、identity 去重、再逐个替换。
   *
   * <p><b>失败语义:collect-errors。</b>
   */
  @ToolDef(
      name = "replace_run_tracked",
      description =
          "批量以修订方式替换正文若干 run 的文本(tracked:每个删旧 + 插新两条配对修订)。"
              + "author 是共享修订作者(必填)。edits 是对象数组,每个对象含 "
              + "paragraph_index(int,段落索引 0 起)、run_index(int,run 索引 0 起)、new_text(string,新文本)。"
              + "新文本复制原 run 样式。单个对象用长度 1 的数组。部分失败不中断,返回每条成功/失败明细。")
  public String replaceRunTracked(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "author", description = "修订作者(整批共享)") String author,
      @ToolParam(
              name = "edits",
              description =
                  "对象数组,每个对象含 paragraph_index(int)、run_index(int)、new_text(string),"
                      + "如 [{\"paragraph_index\":0,\"run_index\":0,\"new_text\":\"新文本\"}]")
          List<Map<String, Object>> edits) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    // replace 比 delete 多一个 new_text 参数,无法直接复用三参回调;在回调闭包里按条解析。
    List<Object> list = coerceList(edits);
    if (list.isEmpty()) {
      return "edits 为空";
    }
    // 先把每条解析成 (para,run,newText) 或错误标记,再交给共享执行框架。
    // 这里复用 applyRunTrackedBatch 的核心思路,但需要 newText;为避免过度抽象,就地实现(结构与共享方法一致)。
    var paragraphs = doc.paragraphs();
    StringBuilder sb = new StringBuilder();
    int ok = 0;
    int fail = 0;
    // 按坐标去重:同一 (paragraphIndex, runIndex) 只替换一次。runs().get(i) 每次返回新包装,
    // 不能 identity 比较,改用坐标字符串。重复 replaceTracked 同一 run 会抛 XmlValueDisconnectedException。
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      String tag = "[" + i + "] ";
      Object item = list.get(i);
      if (!(item instanceof Map)) {
        sb.append(tag).append("错误:该条不是对象(").append(item).append(")");
        fail++;
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) item;
      int paragraphIndex;
      int runIndex;
      String newText;
      try {
        paragraphIndex = getInt(m, "paragraph_index");
        runIndex = getInt(m, "run_index");
        newText = getStr(m, "new_text");
      } catch (RuntimeException e) {
        sb.append(tag).append("错误:").append(e.getMessage());
        fail++;
        continue;
      }
      if (outOfBounds(paragraphIndex, paragraphs.size())) {
        sb.append(tag).append(indexError("段落索引", paragraphIndex, paragraphs.size()));
        fail++;
        continue;
      }
      var runs = paragraphs.get(paragraphIndex).runs();
      if (outOfBounds(runIndex, runs.size())) {
        sb.append(tag).append(indexError("run 索引", runIndex, runs.size()));
        fail++;
        continue;
      }
      String coord = paragraphIndex + ":" + runIndex;
      if (!seen.add(coord)) {
        // 同一坐标已在本批替换过——再替换会抛 XmlValueDisconnectedException,跳过并提示。
        sb.append(tag).append("跳过:段落 ").append(paragraphIndex).append(" run ").append(runIndex).append(" 在本批已处理");
        fail++;
        continue;
      }
      Run target = runs.get(runIndex);
      try {
        String oldText = target.text();
        target.replaceTracked(author, newText);
        sb.append(tag).append("段落 ").append(paragraphIndex).append(" run ").append(runIndex).append(" 替换:\"").append(oldText).append("\" → \"").append(newText).append("\"(del+ins) ✓");
        ok++;
      } catch (RuntimeException e) {
        sb.append(tag).append("错误:").append(rootMessage(e));
        fail++;
      }
    }
    sb.append("\n成功 ").append(ok).append(" 条,失败 ").append(fail).append(" 条");
    return sb.toString();
  }

  /**
   * 把一个 run 的内联样式变更记为 tracked rPrChange(属性修订)。
   *
   * <p>Agent 友好包装:一步到位。内部先快照改前样式、再应用目标样式、再 commitStyleAsTracked(底层两步式, 见 poi-bridge.md
   * N17)。未提供的样式参数(false/null)表示该属性「不显式设置」。
   */
  @ToolDef(
      name = "mark_style_change_tracked",
      description =
          "把一个 run 的样式变更记为被追踪的属性修订(rPrChange)。"
              + "内部:快照改前样式→应用目标样式→commitStyleAsTracked。reject 会回到旧样式。仅改你显式提供的样式参数。")
  public String markStyleChangeTracked(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "paragraph_index", description = "段落索引(0 起)") int paragraphIndex,
      @ToolParam(name = "run_index", description = "run 索引(0 起)") int runIndex,
      @ToolParam(name = "author", description = "修订作者") String author,
      @ToolParam(name = "bold", description = "目标是否粗体(可选)", required = false) boolean bold,
      @ToolParam(name = "italic", description = "目标是否斜体(可选)", required = false) boolean italic,
      @ToolParam(name = "color", description = "目标颜色十六进制(可选)", required = false) String color) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    List<Paragraph> paragraphs = doc.paragraphs();
    if (outOfBounds(paragraphIndex, paragraphs.size())) {
      return indexError("段落索引", paragraphIndex, paragraphs.size());
    }
    var runs = paragraphs.get(paragraphIndex).runs();
    if (outOfBounds(runIndex, runs.size())) {
      return indexError("run 索引", runIndex, runs.size());
    }
    try {
      Run r = runs.get(runIndex);
      com.non.docx.core.api.style.RunStyle before = r.style(); // 快照改前样式
      if (bold) {
        r.bold();
      }
      if (italic) {
        r.italic();
      }
      if (color != null && !color.isBlank()) {
        r.color(color);
      }
      r.commitStyleAsTracked(author, before);
      return "已把段落 " + paragraphIndex + " run " + runIndex + " 的样式变更记为 rPrChange";
    } catch (RuntimeException e) {
      return "错误:" + rootMessage(e);
    }
  }

  /**
   * 批量把若干表格单元格标记为被插入(tracked cellIns:这些单元格本身是被新增的)。
   *
   * <p><b>批量语义（v2）。</b> {@code author} 共享;{@code cells} 是对象数组,每个对象含 {@code table_index}(int)、
   * {@code row_index}(int)、{@code cell_index}(int)。数组长度 1 即标记单个单元格。
   *
   * <p>作用于整个单元格:accept=保留、reject=移除。
   *
   * <p><b>无索引漂移。</b> 标记是给单元格加 cellIns 属性,不增删 cell 列表结构,条目间互不影响。
   *
   * <p><b>失败语义:collect-errors。</b> 坐标越界/缺字段的条目记错误不中断;末尾汇总。
   */
  @ToolDef(
      name = "mark_cell_inserted",
      description =
          "批量把表格若干单元格标记为被插入(tracked cellIns:这些单元格本身是被新增的)。"
              + "author 是共享修订作者(必填)。cells 是对象数组,每个对象含 "
              + "table_index(int,表格索引 0 起)、row_index(int,行索引 0 起)、cell_index(int,单元格索引 0 起)。"
              + "单个对象用长度 1 的数组。accept=保留、reject=移除。"
              + "部分失败不中断,返回每条成功/失败明细。")
  public String markCellInserted(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "author", description = "修订作者(整批共享)") String author,
      @ToolParam(
              name = "cells",
              description =
                  "对象数组,每个对象含 table_index、row_index、cell_index(int),"
                      + "如 [{\"table_index\":0,\"row_index\":0,\"cell_index\":0}]")
          List<Map<String, Object>> cells) {
    return markCellsBatch(docId, author, cells, /* inserter= */ true);
  }

  /**
   * 批量把若干表格单元格标记为被删除(tracked cellDel)。
   *
   * <p><b>批量语义（v2）。</b> 与 {@link #markCellInserted} 结构对称,只是标记为 cellDel。作用于整个单元格:accept=移除、reject=保留。
   */
  @ToolDef(
      name = "mark_cell_deleted",
      description =
          "批量把表格若干单元格标记为被删除(tracked cellDel:这些单元格本身是被删除的)。"
              + "author 是共享修订作者(必填)。cells 是对象数组,每个对象含 "
              + "table_index(int)、row_index(int)、cell_index(int)。"
              + "单个对象用长度 1 的数组。accept=移除、reject=保留。"
              + "部分失败不中断,返回每条成功/失败明细。")
  public String markCellDeleted(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "author", description = "修订作者(整批共享)") String author,
      @ToolParam(
              name = "cells",
              description =
                  "对象数组,每个对象含 table_index、row_index、cell_index(int),"
                      + "如 [{\"table_index\":0,\"row_index\":0,\"cell_index\":0}]")
          List<Map<String, Object>> cells) {
    return markCellsBatch(docId, author, cells, /* inserter= */ false);
  }

  /**
   * 单元格标记批量执行的共享实现:解析 {@code cells} 坐标 → 逐个 resolveCell → {@code markInserted}/{@code markDeleted}。
   *
   * <p>{@code inserter=true} 调 {@code cell.markInserted}(cellIns),{@code false} 调 {@code cell.markDeleted}(cellDel)。 collect-errors:越界/缺字段记错误不中断。
   * 标记不增删 cell 列表,无索引漂移,无需去重(重复标记同一 cell 由 core 自身语义决定,这里不做额外拦截)。
   */
  private String markCellsBatch(String docId, String author, List<Map<String, Object>> cells, boolean inserter) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    List<Object> list = coerceList(cells);
    if (list.isEmpty()) {
      return "cells 为空";
    }
    String tag = inserter ? "cellIns" : "cellDel";
    StringBuilder sb = new StringBuilder();
    int ok = 0;
    int fail = 0;
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      String prefix = "[" + i + "] ";
      Object item = list.get(i);
      if (!(item instanceof Map)) {
        sb.append(prefix).append("错误:该条不是对象(").append(item).append(")");
        fail++;
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) item;
      int tableIndex;
      int rowIndex;
      int cellIndex;
      try {
        tableIndex = getInt(m, "table_index");
        rowIndex = getInt(m, "row_index");
        cellIndex = getInt(m, "cell_index");
      } catch (RuntimeException e) {
        sb.append(prefix).append("错误:").append(e.getMessage());
        fail++;
        continue;
      }
      Cell cell = resolveCell(docId, tableIndex, rowIndex, cellIndex);
      if (cell == null) {
        sb.append(prefix).append(cellResolveError(docId, tableIndex, rowIndex, cellIndex));
        fail++;
        continue;
      }
      try {
        if (inserter) {
          cell.markInserted(author);
        } else {
          cell.markDeleted(author);
        }
        sb.append(prefix)
            .append("单元格 table[")
            .append(tableIndex)
            .append("].row[")
            .append(rowIndex)
            .append("].cell[")
            .append(cellIndex)
            .append("] 标记为 ")
            .append(tag)
            .append(" ✓");
        ok++;
      } catch (RuntimeException e) {
        sb.append(prefix).append("错误:").append(rootMessage(e));
        fail++;
      }
    }
    sb.append("\n成功 ").append(ok).append(" 条,失败 ").append(fail).append(" 条");
    return sb.toString();
  }

  /**
   * 把源段的一个 run 移动到目标段(tracked move 修订,产出配对的 moveFrom/moveTo)。
   *
   * <p>接受方是目标段(与 addInsertion 同类型)。移动后可被 list_tracked_changes 读回为 MOVE_FROM + MOVE_TO。
   */
  @ToolDef(
      name = "move_run_tracked",
      description =
          "把源段(source_paragraph_index)的第 run_index 个 run 移动到目标段(target_paragraph_index),"
              + "产出配对的 tracked move 修订(moveFrom + moveTo)。author 必填。"
              + "可被 list 读回为 MOVE_FROM/MOVE_TO、accept/reject 联动处理。")
  public String moveRunTracked(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "source_paragraph_index", description = "源段索引(0 起)")
          int sourceParagraphIndex,
      @ToolParam(name = "run_index", description = "源段中要移动的 run 索引(0 起)") int runIndex,
      @ToolParam(name = "target_paragraph_index", description = "目标段索引(0 起)")
          int targetParagraphIndex,
      @ToolParam(name = "author", description = "修订作者") String author) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    List<Paragraph> paragraphs = doc.paragraphs();
    if (outOfBounds(sourceParagraphIndex, paragraphs.size())) {
      return indexError("源段索引", sourceParagraphIndex, paragraphs.size());
    }
    if (outOfBounds(targetParagraphIndex, paragraphs.size())) {
      return indexError("目标段索引", targetParagraphIndex, paragraphs.size());
    }
    var sourceRuns = paragraphs.get(sourceParagraphIndex).runs();
    if (outOfBounds(runIndex, sourceRuns.size())) {
      return indexError("run 索引", runIndex, sourceRuns.size());
    }
    try {
      Run moving = sourceRuns.get(runIndex);
      paragraphs
          .get(targetParagraphIndex)
          .moveRunsFrom(author, paragraphs.get(sourceParagraphIndex), List.of(moving));
      return "已把段 "
          + sourceParagraphIndex
          + " run "
          + runIndex
          + " 移到段 "
          + targetParagraphIndex
          + "(moveFrom + moveTo 配对)";
    } catch (RuntimeException e) {
      return "错误:" + rootMessage(e);
    }
  }

  /** 解析表格单元格;越界返回 null(供 mark_cell_* 用)。 */
  private Cell resolveCell(String docId, int tableIndex, int rowIndex, int cellIndex) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return null;
    }
    var tables = doc.tables();
    if (outOfBounds(tableIndex, tables.size())) {
      return null;
    }
    var rows = tables.get(tableIndex).rows();
    if (outOfBounds(rowIndex, rows.size())) {
      return null;
    }
    var cells = rows.get(rowIndex).cells();
    if (outOfBounds(cellIndex, cells.size())) {
      return null;
    }
    return cells.get(cellIndex);
  }

  /** resolveCell 失败时的中文错误串。 */
  private String cellResolveError(String docId, int tableIndex, int rowIndex, int cellIndex) {
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
    return indexError("单元格索引", cellIndex, cells.size());
  }

  // ==================== 内部辅助 ====================

  // ---- 批量工具的入参归一化与对象取值 ----
  //
  // 背景:nonchain 0.8.4 把 LLM 传来的 JSON 数组还原成 ArrayList<LinkedHashMap>,
  // 数字可能是 Integer/Long/Double(按大小选),不能用 (int)/(Integer) 强转(会 CCE)。
  // 另外 LLM 偶尔会把"单次调用"误传成标量(如 paragraph_indexes: 0 而非 [0]),
  // 故统一在入口归一化为 List 后再循环处理,提升健壮性。

  /**
   * 把入参归一化为 {@code List}。批量工具的统一入口预处理:
   *
   * <ul>
   *   <li>{@code null} → 空列表(等价于"无操作")。
   *   <li>已是 {@code List} → 原样返回。
   *   <li>单个非 List 元素(LLM 误传标量)→ 包成单元素列表,让单次调用语义不被破坏。
   * </ul>
   */
  private static List<Object> coerceList(Object raw) {
    if (raw == null) {
      return List.of();
    }
    if (raw instanceof List) {
      @SuppressWarnings("unchecked")
      List<Object> list = (List<Object>) raw;
      return list;
    }
    return List.of(raw);
  }

  /**
   * 从对象 Map 里取一个 int 字段。走 {@code ((Number) ...).intValue()},兼容 Jackson 还原出的
   * Integer/Long/Double;null 或类型不符时抛 {@link IllegalArgumentException},由调用方 catch 后
   * 转中文错误串(沿用本类"不抛异常给框架、返回错误串给 Agent"的约定)。
   */
  private static int getInt(Map<String, Object> m, String key) {
    Object v = m.get(key);
    if (v instanceof Number) {
      return ((Number) v).intValue();
    }
    if (v instanceof String) {
      try {
        return Integer.parseInt(((String) v).trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("字段 " + key + " 不是合法整数:\"" + v + "\"");
      }
    }
    if (v == null) {
      throw new IllegalArgumentException("缺少必填字段 " + key);
    }
    throw new IllegalArgumentException("字段 " + key + " 不是整数:" + v);
  }

  /**
   * 从对象 Map 里取一个 String 字段。null 或非 String 时抛 {@link IllegalArgumentException}
   * (理由同 {@link #getInt}),由调用方 catch 转中文错误串。
   */
  private static String getStr(Map<String, Object> m, String key) {
    Object v = m.get(key);
    if (v == null) {
      throw new IllegalArgumentException("缺少必填字段 " + key);
    }
    return v.toString();
  }

  /**
   * 从对象 Map 里取一个<b>可选</b>布尔字段。LLM 可能传 Boolean 或 String("true"/"false"); null 或缺省视为 false。
   * 与 {@link #getInt}/{@link #getStr} 不同,这里<b>不抛异常</b>(可选字段),非法值一律当 false。
   */
  private static boolean boolVal(Object v) {
    if (v instanceof Boolean) {
      return (Boolean) v;
    }
    if (v instanceof String) {
      return Boolean.parseBoolean(((String) v).trim());
    }
    return false;
  }

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
