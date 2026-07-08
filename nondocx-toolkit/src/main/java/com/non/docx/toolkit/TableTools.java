package com.non.docx.toolkit;

import com.non.chain.tool.ToolDef;
import com.non.chain.tool.ToolParam;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.table.Row;
import com.non.docx.core.api.table.Table;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 表格工具组（原 C 组）：下钻到 cell 内 paragraph / run 的读与改。
 *
 * <p><b>OOXML 三层递进（表格结构）：</b>
 *
 * <ul>
 *   <li><b>OOXML</b>：{@code word/document.xml} 里 {@code <w:tbl>}（表格）→ {@code <w:tr>}（行）→ {@code
 *       <w:tc>}（单元格）→ 单元格内又是 {@code <w:p>}（段落）序列，段落内才是 {@code <w:r>}。 所以表格寻址链比正文段落深三层。
 *   <li><b>POI</b>：{@code XWPFTable} → {@code XWPFTableRow} → {@code XWPFTableCell} → {@code
 *       XWPFParagraph} → {@code XWPFRun}。
 *   <li><b>nondocx</b>：{@code
 *       doc.tables().get(t).rows().get(r).cells().get(c).paragraphs().get(p).runs().get(u)}。
 * </ul>
 *
 * <p>单元格定位辅助（{@code locateCell}/{@code locateCellObj}/{@code resolveCell}/{@code
 * cellResolveError}）由 {@link ToolkitToolContext} 提供，与 {@link TrackedChangesTools} 的单元格标记 共用同一条寻址链。
 */
public final class TableTools extends ToolkitToolContext {

  /** 接收门面注入的共享会话状态（与 SessionTools 共享同一份 sessions/seq）。 */
  TableTools(Map<String, Document> sharedSessions, AtomicInteger sharedSeq) {
    super(sharedSessions, sharedSeq);
  }

  /**
   * 在正文末尾创建一个表格，并按二维数组填充单元格文本。
   *
   * <p><b>OOXML → POI → nondocx 三层</b>:表格在正文里是 {@code <w:tbl>},内部是 {@code <w:tr>} 行、
   * {@code <w:tc>} 单元格、单元格内的 {@code <w:p>/<w:r>} 文本。POI 的 {@code XWPFDocument#createTable()}
   * 会预填默认行；nondocx 的 {@link Document#addTable()} 已剥离这个默认行,保证这里传入几行几列就创建几行几列。
   *
   * <p><b>矩阵语义。</b> {@code rows} 是二维数组,外层为行,内层为单元格文本。各行列数可以不同,toolkit 会照传入结构创建。
   */
  @ToolDef(
      name = "create_table",
      description =
          "在正文末尾创建一个表格(改完需 save_docx 落盘)。rows 是二维数组,外层为行、内层为单元格文本,"
              + "如 [[\"姓名\",\"分数\"],[\"张三\",\"95\"]]。各行列数可不同。返回新表格的 table_index。")
  public String createTable(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(
              name = "rows",
              description =
                  "二维数组,外层为行、内层为单元格文本,"
                      + "如 [[\"姓名\",\"分数\"],[\"张三\",\"95\"]]")
          List<List<String>> rows) {
    Document doc = document(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    List<Object> rowList = coerceList(rows);
    if (rowList.isEmpty()) {
      return "rows 为空";
    }
    for (int r = 0; r < rowList.size(); r++) {
      if (!(rowList.get(r) instanceof List)) {
        return "错误:第 " + r + " 行不是数组(" + rowList.get(r) + ")";
      }
    }
    for (int r = 0; r < rowList.size(); r++) {
      if (coerceList(rowList.get(r)).isEmpty()) {
        return "错误:第 " + r + " 行为空";
      }
    }
    int tableIndex = doc.tables().size();
    Table table = doc.addTable();
    int cellCount = 0;
    for (Object rowObj : rowList) {
      Row row = table.addRow();
      for (Object cellObj : coerceList(rowObj)) {
        row.addCell().text(String.valueOf(cellObj));
        cellCount++;
      }
    }
    return "已创建表格 "
        + tableIndex
        + ": "
        + rowList.size()
        + " 行,"
        + cellCount
        + " 个单元格";
  }

  /**
   * 设置表格边框。当前支持 {@code NONE},即显式写入无边框。
   *
   * <p><b>OOXML → POI → nondocx 三层</b>:表格边框位于 {@code <w:tblPr>/<w:tblBorders>}。无边框不是删节点,
   * 而是把 top/left/bottom/right/insideH/insideV 写成 {@code w:val="nil"},避免渲染器按默认边框处理。
   * POI 无友好高层 API,nondocx 在 core 的 {@link Table#noBorders()} 收口。
   */
  @ToolDef(
      name = "set_table_borders",
      description =
          "设置表格边框(改完需 save_docx 落盘)。当前 border_style 仅支持 NONE,即显式无边框。"
              + "参数:table_index(int,表格索引 0 起)、border_style(string,NONE)。")
  public String setTableBorders(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "table_index", description = "表格索引(0 起)") int tableIndex,
      @ToolParam(name = "border_style", description = "边框样式,当前仅支持 NONE") String borderStyle) {
    Document doc = document(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    var tables = doc.tables();
    if (outOfBounds(tableIndex, tables.size())) {
      return indexError("表格索引", tableIndex, tables.size());
    }
    if (borderStyle == null || !"NONE".equalsIgnoreCase(borderStyle.trim())) {
      return "错误:border_style 仅支持 NONE";
    }
    try {
      tables.get(tableIndex).noBorders();
      return "已设置表格 " + tableIndex + " 为无边框";
    } catch (RuntimeException e) {
      return "错误:" + rootMessage(e);
    }
  }

  /**
   * 批量合并表格单元格。支持横向 {@code HORIZONTAL} 与纵向 {@code VERTICAL}。
   *
   * <p>横向合并使用首格 {@code gridSpan} 并删除右侧覆盖单元格；纵向合并使用同列单元格 {@code vMerge}
   * restart/continue。两者均要求连续矩形边界的一条线段,不处理复杂跨行跨列矩形。
   *
   * <p><b>为什么用对象数组。</b> 不把横向/纵向坐标做成多个顶层可选 {@code int} 参数,因为部分工具框架会在可选 primitive
   * 缺失时于方法调用前绑定失败,导致用户只看到"工具执行失败",看不到本方法返回的中文错误。对象数组让缺字段校验留在工具内部完成。
   */
  @ToolDef(
      name = "merge_table_cells",
      description =
          "批量合并表格单元格(改完需 save_docx 落盘)。merges 是对象数组,每个对象含 table_index(int)、direction(string)。"
              + "direction=HORIZONTAL 时还需 row_index/from_cell_index/to_cell_index;"
              + "direction=VERTICAL 时还需 cell_index/from_row_index/to_row_index。索引均 0 起。"
              + "部分失败不中断,返回每条成功/失败明细。")
  public String mergeTableCells(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(
              name = "merges",
              description =
                  "对象数组。横向示例:[{\"table_index\":0,\"direction\":\"HORIZONTAL\",\"row_index\":0,"
                      + "\"from_cell_index\":0,\"to_cell_index\":2}];纵向示例:"
                      + "[{\"table_index\":0,\"direction\":\"VERTICAL\",\"cell_index\":1,"
                      + "\"from_row_index\":0,\"to_row_index\":2}]")
          Map<String, Object>[] merges) {
    Document doc = document(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    List<Object> list = coerceObjectArray(merges);
    if (list.isEmpty()) {
      return "merges 为空";
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
      String direction;
      try {
        tableIndex = getInt(m, "table_index");
        direction = getStr(m, "direction");
      } catch (RuntimeException e) {
        sb.append(tag).append("错误:").append(e.getMessage());
        fail++;
        continue;
      }
      var tables = doc.tables();
      if (outOfBounds(tableIndex, tables.size())) {
        sb.append(tag).append(indexError("表格索引", tableIndex, tables.size()));
        fail++;
        continue;
      }
      try {
        if ("HORIZONTAL".equalsIgnoreCase(direction.trim())) {
          int rowIndex = getInt(m, "row_index");
          int fromCellIndex = getInt(m, "from_cell_index");
          int toCellIndex = getInt(m, "to_cell_index");
          tables.get(tableIndex).mergeCellsHorizontal(rowIndex, fromCellIndex, toCellIndex);
          sb.append(tag)
              .append("已横向合并表格 ")
              .append(tableIndex)
              .append(" 行 ")
              .append(rowIndex)
              .append(" 单元格 ")
              .append(fromCellIndex)
              .append("..")
              .append(toCellIndex)
              .append(" ✓");
          ok++;
          continue;
        }
        if ("VERTICAL".equalsIgnoreCase(direction.trim())) {
          int cellIndex = getInt(m, "cell_index");
          int fromRowIndex = getInt(m, "from_row_index");
          int toRowIndex = getInt(m, "to_row_index");
          tables.get(tableIndex).mergeCellsVertical(cellIndex, fromRowIndex, toRowIndex);
          sb.append(tag)
              .append("已纵向合并表格 ")
              .append(tableIndex)
              .append(" 列 ")
              .append(cellIndex)
              .append(" 行 ")
              .append(fromRowIndex)
              .append("..")
              .append(toRowIndex)
              .append(" ✓");
          ok++;
          continue;
        }
        sb.append(tag).append("错误:direction 仅支持 HORIZONTAL/VERTICAL");
        fail++;
      } catch (RuntimeException e) {
        sb.append(tag).append("错误:").append(rootMessage(e));
        fail++;
      }
    }
    sb.append("\n成功 ").append(ok).append(" 条,失败 ").append(fail).append(" 条");
    return sb.toString();
  }

  private static List<Object> coerceObjectArray(Map<String, Object>[] raw) {
    if (raw == null) {
      return List.of();
    }
    return new java.util.ArrayList<Object>(Arrays.asList(raw));
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
   * <p>数组长度 1 即读单个单元格;多个即一次读多处(如通读一整行的所有单元格)。表格寻址链 table→row→cell→paragraph→run 较深,批量版把"读 N 个单元格"从
   * N 轮 LLM 往返压成 1 轮。
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
    Document doc = document(docId);
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
    Document doc = document(docId);
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
   * <p>数组长度 1 即单次替换;多个即一次改多处(如批量更新状态表的某一列)。 表格寻址链 table→row→cell→paragraph→run 较深,批量版把"改 N 个单元格"从 N
   * 轮 LLM 往返压成 1 轮。
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
    Document doc = document(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
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
}
