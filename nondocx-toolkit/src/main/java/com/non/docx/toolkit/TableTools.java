package com.non.docx.toolkit;

import com.non.chain.tool.ToolDef;
import com.non.chain.tool.ToolParam;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.style.Alignment;
import com.non.docx.core.api.style.HeadingLevel;
import com.non.docx.core.api.style.ListKind;
import com.non.docx.core.api.style.VerticalAlign;
import com.non.docx.core.api.table.Cell;
import com.non.docx.core.api.table.Row;
import com.non.docx.core.api.table.Table;
import com.non.docx.core.api.text.Paragraph;
import com.non.docx.core.api.text.Run;
import com.non.docx.toolkit.capability.CapabilityOperation;
import com.non.docx.toolkit.capability.NestedParamCapability;
import com.non.docx.toolkit.capability.ParamCapability;
import com.non.docx.toolkit.capability.ParamType;
import com.non.docx.toolkit.capability.ToolCapability;
import com.non.docx.toolkit.ref.CellRef;
import com.non.docx.toolkit.ref.ElementRef;
import com.non.docx.toolkit.ref.ElementRefs;
import com.non.docx.toolkit.ref.ElementResolver;
import com.non.docx.toolkit.ref.RefResolutionCode;
import com.non.docx.toolkit.ref.RefResolutionException;
import com.non.docx.toolkit.ref.ReferenceContext;
import com.non.docx.toolkit.ref.RunRef;
import com.non.docx.toolkit.result.BatchItemResult;
import com.non.docx.toolkit.result.ToolResult;
import com.non.docx.toolkit.result.ToolResultCode;
import com.non.docx.toolkit.result.ToolResultRenderer;
import com.non.docx.toolkit.result.ToolWarning;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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

  TableTools(
      Map<String, Document> sharedSessions,
      AtomicInteger sharedSeq,
      ReferenceContext sharedReferences,
      Map<String, Long> sharedGenerations) {
    super(sharedSessions, sharedSeq, sharedReferences, sharedGenerations);
  }

  /**
   * 在正文末尾创建一个表格，并按二维数组填充单元格文本。
   *
   * <p><b>OOXML → POI → nondocx 三层</b>:表格在正文里是 {@code <w:tbl>},内部是 {@code <w:tr>} 行、 {@code <w:tc>}
   * 单元格、单元格内的 {@code <w:p>/<w:r>} 文本。POI 的 {@code XWPFDocument#createTable()} 会预填默认行；nondocx 的
   * {@link Document#addTable()} 已剥离这个默认行,保证这里传入几行几列就创建几行几列。
   *
   * <p><b>矩阵语义。</b> {@code rows} 是二维数组,外层为行,内层为单元格文本。各行列数可以不同,toolkit 会照传入结构创建。
   */
  @ToolDef(
      name = "create_table",
      description =
          "在正文末尾创建一个表格(改完需 save_docx 落盘)。rows 是二维数组,外层为行、内层为单元格文本,"
              + "如 [[\"姓名\",\"分数\"],[\"张三\",\"95\"]]。各行列数可不同。返回新表格的 table_index。")
  @ToolCapability(operation = CapabilityOperation.ADD, element = "table")
  public String createTable(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(
              name = "rows",
              description = "二维数组,外层为行、内层为单元格文本," + "如 [[\"姓名\",\"分数\"],[\"张三\",\"95\"]]")
          @ParamCapability(type = ParamType.OBJECT_ARRAY)
          List<List<String>> rows) {
    Document doc = document(docId);
    if (doc == null) {
      ToolResult<Void> result = docNotFoundResult(docId);
      return ToolResultRenderer.render(result);
    }
    List<Object> rowList = coerceList(rows);
    if (rowList.isEmpty()) {
      ToolResult<Void> result = ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "rows 为空");
      return ToolResultRenderer.render(result);
    }
    for (int r = 0; r < rowList.size(); r++) {
      if (!(rowList.get(r) instanceof List)) {
        ToolResult<Void> result =
            ToolResult.fail(
                ToolResultCode.INVALID_ARGUMENT, "错误:第 " + r + " 行不是数组(" + rowList.get(r) + ")");
        return ToolResultRenderer.render(result);
      }
    }
    for (int r = 0; r < rowList.size(); r++) {
      if (coerceList(rowList.get(r)).isEmpty()) {
        ToolResult<Void> result =
            ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "错误:第 " + r + " 行为空");
        return ToolResultRenderer.render(result);
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
    ToolResult<Void> result =
        ToolResult.ok("已创建表格 " + tableIndex + ": " + rowList.size() + " 行," + cellCount + " 个单元格");
    return ToolResultRenderer.render(result);
  }

  /**
   * 设置表格边框。当前支持 {@code NONE},即显式写入无边框。
   *
   * <p><b>OOXML → POI → nondocx 三层</b>:表格边框位于 {@code <w:tblPr>/<w:tblBorders>}。无边框不是删节点, 而是把
   * top/left/bottom/right/insideH/insideV 写成 {@code w:val="nil"},避免渲染器按默认边框处理。 POI 无友好高层
   * API,nondocx 在 core 的 {@link Table#noBorders()} 收口。
   */
  @ToolDef(
      name = "set_table_borders",
      description =
          "设置表格边框(改完需 save_docx 落盘)。当前 border_style 仅支持 NONE,即显式无边框。"
              + "参数:table_index(int,表格索引 0 起)、border_style(string,NONE)。")
  @ToolCapability(operation = CapabilityOperation.UPDATE, element = "table")
  public String setTableBorders(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "table_index", description = "表格索引(0 起)")
          @ParamCapability(type = ParamType.INTEGER)
          int tableIndex,
      @ToolParam(name = "border_style", description = "边框样式,当前仅支持 NONE")
          @ParamCapability(
              type = ParamType.ENUM,
              enumValues = {"NONE"})
          String borderStyle) {
    Document doc = document(docId);
    if (doc == null) {
      return ToolResultRenderer.render(docNotFoundResult(docId));
    }
    var tables = doc.tables();
    if (outOfBounds(tableIndex, tables.size())) {
      return ToolResultRenderer.render(indexErrorResult("表格索引", tableIndex, tables.size()));
    }
    if (borderStyle == null || !"NONE".equalsIgnoreCase(borderStyle.trim())) {
      return ToolResultRenderer.render(
          ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "错误:border_style 仅支持 NONE"));
    }
    try {
      tables.get(tableIndex).noBorders();
      return ToolResultRenderer.render(ToolResult.ok("已设置表格 " + tableIndex + " 为无边框"));
    } catch (RuntimeException e) {
      return ToolResultRenderer.render(
          ToolResult.fail(ToolResultCode.UNSUPPORTED_FEATURE, "错误:" + rootMessage(e)));
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
  @ToolCapability(operation = CapabilityOperation.UPDATE, element = "cell")
  public String mergeTableCells(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(
              name = "merges",
              description =
                  "对象数组。横向示例:[{\"table_index\":0,\"direction\":\"HORIZONTAL\",\"row_index\":0,"
                      + "\"from_cell_index\":0,\"to_cell_index\":2}];纵向示例:"
                      + "[{\"table_index\":0,\"direction\":\"VERTICAL\",\"cell_index\":1,"
                      + "\"from_row_index\":0,\"to_row_index\":2}]")
          @NestedParamCapability(path = "merges.table_index", type = ParamType.INTEGER)
          @NestedParamCapability(
              path = "merges.direction",
              type = ParamType.ENUM,
              enumValues = {"HORIZONTAL", "VERTICAL"})
          @NestedParamCapability(path = "merges.row_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "merges.cell_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "merges.from_cell_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "merges.to_cell_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "merges.from_row_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "merges.to_row_index", type = ParamType.INTEGER)
          Map<String, Object>[] merges) {
    Document doc = document(docId);
    if (doc == null) {
      return ToolResultRenderer.render(docNotFoundResult(docId));
    }
    List<Object> list = coerceObjectArray(merges);
    if (list.isEmpty()) {
      return ToolResultRenderer.render(
          ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "merges 为空"));
    }
    StringBuilder sb = new StringBuilder();
    List<BatchItemResult<Void>> items = new ArrayList<>();
    int ok = 0;
    int fail = 0;
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      Object item = list.get(i);
      String tag = "[" + i + "] ";
      if (!(item instanceof Map)) {
        String msg = "错误:该条不是对象(" + item + ")";
        sb.append(tag).append(msg);
        items.add(BatchItemResult.of(i, ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, msg)));
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
        String msg = "错误:" + e.getMessage();
        sb.append(tag).append(msg);
        items.add(BatchItemResult.of(i, ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, msg)));
        fail++;
        continue;
      }
      var tables = doc.tables();
      if (outOfBounds(tableIndex, tables.size())) {
        String msg = indexError("表格索引", tableIndex, tables.size());
        sb.append(tag).append(msg);
        items.add(BatchItemResult.of(i, indexErrorResult("表格索引", tableIndex, tables.size())));
        fail++;
        continue;
      }
      try {
        if ("HORIZONTAL".equalsIgnoreCase(direction.trim())) {
          int rowIndex = getInt(m, "row_index");
          int fromCellIndex = getInt(m, "from_cell_index");
          int toCellIndex = getInt(m, "to_cell_index");
          tables.get(tableIndex).mergeCellsHorizontal(rowIndex, fromCellIndex, toCellIndex);
          String msg =
              "已横向合并表格 "
                  + tableIndex
                  + " 行 "
                  + rowIndex
                  + " 单元格 "
                  + fromCellIndex
                  + ".."
                  + toCellIndex
                  + " ✓";
          sb.append(tag).append(msg);
          items.add(BatchItemResult.of(i, ToolResult.ok(msg)));
          ok++;
          continue;
        }
        if ("VERTICAL".equalsIgnoreCase(direction.trim())) {
          int cellIndex = getInt(m, "cell_index");
          int fromRowIndex = getInt(m, "from_row_index");
          int toRowIndex = getInt(m, "to_row_index");
          tables.get(tableIndex).mergeCellsVertical(cellIndex, fromRowIndex, toRowIndex);
          String msg =
              "已纵向合并表格 "
                  + tableIndex
                  + " 列 "
                  + cellIndex
                  + " 行 "
                  + fromRowIndex
                  + ".."
                  + toRowIndex
                  + " ✓";
          sb.append(tag).append(msg);
          items.add(BatchItemResult.of(i, ToolResult.ok(msg)));
          ok++;
          continue;
        }
        String msg = "错误:direction 仅支持 HORIZONTAL/VERTICAL";
        sb.append(tag).append(msg);
        items.add(BatchItemResult.of(i, ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, msg)));
        fail++;
      } catch (RuntimeException e) {
        String msg = "错误:" + rootMessage(e);
        sb.append(tag).append(msg);
        items.add(BatchItemResult.of(i, ToolResult.fail(ToolResultCode.UNSUPPORTED_FEATURE, msg)));
        fail++;
      }
    }
    sb.append("\n成功 ").append(ok).append(" 条,失败 ").append(fail).append(" 条");
    if (fail == 0) {
      return ToolResultRenderer.render(ToolResult.ok(null, sb.toString(), ok, null));
    }
    return ToolResultRenderer.render(ToolResult.partial(items, sb.toString(), List.of()));
  }

  private static List<Object> coerceObjectArray(Map<String, Object>[] raw) {
    if (raw == null) {
      return List.of();
    }
    return new java.util.ArrayList<Object>(Arrays.asList(raw));
  }

  // ==================== ToolResult 辅助工厂 ====================

  /** {@link #locateCell} 的结构化版本：成功返回 ok，失败返回带 code 的 ToolResult。 */
  private static ToolResult<Void> locateCellResult(
      Document doc, int tableIndex, int rowIndex, int cellIndex) {
    var tables = doc.tables();
    if (outOfBounds(tableIndex, tables.size())) {
      return indexErrorResult("表格索引", tableIndex, tables.size());
    }
    var rows = tables.get(tableIndex).rows();
    if (outOfBounds(rowIndex, rows.size())) {
      return indexErrorResult("行索引", rowIndex, rows.size());
    }
    var cells = rows.get(rowIndex).cells();
    if (outOfBounds(cellIndex, cells.size())) {
      return indexErrorResult("单元格索引", cellIndex, cells.size());
    }
    return ToolResult.ok(null);
  }

  /** 单条单元格编辑动作（ToolResult 边界），由各写工具 lambda 提供。 */
  @FunctionalInterface
  private interface CellEditResultAction {
    ToolResult<Void> apply(Map<String, Object> m, com.non.docx.core.api.table.Cell cell);
  }

  /**
   * 单元格级写工具的批量循环骨架（ToolResult 版）。
   *
   * <p>负责：解析坐标 + locateCell 边界检查 + 调 lambda 写入 + collect-errors 汇总。全部成功返回 OK +
   * matchedCount/changedCount；有失败返回 PARTIAL_FAILURE，{@code data} 为 {@code List<BatchItemResult>}。
   * {@code stopOnError=true} 时遇第一条失败即停，未执行条目计入 {@code skippedCount}。
   */
  private static ToolResult<List<BatchItemResult<Void>>> applyCellEditResults(
      Document doc,
      List<Map<String, Object>> edits,
      boolean stopOnError,
      CellEditResultAction action) {
    List<Object> list = coerceList(edits);
    if (list.isEmpty()) {
      return ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "edits 为空")
          .<List<BatchItemResult<Void>>>mapData(null);
    }
    StringBuilder sb = new StringBuilder();
    List<BatchItemResult<Void>> items = new ArrayList<>();
    int ok = 0;
    int fail = 0;
    int stoppedAt = -1;
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      Object item = list.get(i);
      String tag = "[" + i + "] ";
      if (!(item instanceof Map)) {
        String msg = "错误:该条不是对象(" + item + ")";
        sb.append(tag).append(msg);
        ToolResult<Void> r = ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, msg);
        items.add(BatchItemResult.of(i, r));
        fail++;
        if (stopOnError) {
          stoppedAt = i;
          break;
        }
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
        String msg = "错误:" + e.getMessage();
        sb.append(tag).append(msg);
        ToolResult<Void> r = ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, msg);
        items.add(BatchItemResult.of(i, r));
        fail++;
        if (stopOnError) {
          stoppedAt = i;
          break;
        }
        continue;
      }
      ToolResult<Void> locate = locateCellResult(doc, tableIndex, rowIndex, cellIndex);
      if (!locate.success()) {
        sb.append(tag).append(locate.message());
        items.add(BatchItemResult.of(i, locate));
        fail++;
        if (stopOnError) {
          stoppedAt = i;
          break;
        }
        continue;
      }
      var cell = locateCellObj(doc, tableIndex, rowIndex, cellIndex);
      ToolResult<Void> r;
      try {
        r = action.apply(m, cell);
      } catch (RuntimeException e) {
        r = ToolResult.fail(ToolResultCode.UNSUPPORTED_FEATURE, "错误:" + rootMessage(e));
      }
      sb.append(tag).append(r.message());
      items.add(BatchItemResult.of(i, r));
      if (r.success()) {
        ok++;
      } else {
        fail++;
        if (stopOnError) {
          stoppedAt = i;
          break;
        }
      }
    }
    int skipped = stoppedAt >= 0 ? list.size() - stoppedAt - 1 : 0;
    sb.append("\n成功 ").append(ok).append(" 条,失败 ").append(fail).append(" 条");
    if (fail == 0) {
      return ToolResult.ok(items, sb.toString(), ok, ok, null);
    }
    return ToolResult.partial(
        ToolResultCode.PARTIAL_FAILURE,
        items,
        sb.toString(),
        List.of(),
        ok + fail,
        ok,
        skipped > 0 ? skipped : null);
  }

  /** 单条行编辑动作（ToolResult 边界），由各写工具 lambda 提供。 */
  @FunctionalInterface
  private interface RowEditResultAction {
    ToolResult<Void> apply(Map<String, Object> m, Row row);
  }

  /** 行级写工具的批量循环骨架（ToolResult 版），与 {@link #applyCellEditResults} 对称。 */
  private static ToolResult<List<BatchItemResult<Void>>> applyRowEditResults(
      Document doc,
      List<Map<String, Object>> edits,
      boolean stopOnError,
      RowEditResultAction action) {
    List<Object> list = coerceList(edits);
    if (list.isEmpty()) {
      return ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "edits 为空")
          .<List<BatchItemResult<Void>>>mapData(null);
    }
    StringBuilder sb = new StringBuilder();
    List<BatchItemResult<Void>> items = new ArrayList<>();
    int ok = 0;
    int fail = 0;
    int stoppedAt = -1;
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      Object item = list.get(i);
      String tag = "[" + i + "] ";
      if (!(item instanceof Map)) {
        String msg = "错误:该条不是对象(" + item + ")";
        sb.append(tag).append(msg);
        ToolResult<Void> r = ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, msg);
        items.add(BatchItemResult.of(i, r));
        fail++;
        if (stopOnError) {
          stoppedAt = i;
          break;
        }
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) item;
      int tableIndex;
      int rowIndex;
      try {
        tableIndex = getInt(m, "table_index");
        rowIndex = getInt(m, "row_index");
      } catch (RuntimeException e) {
        String msg = "错误:" + e.getMessage();
        sb.append(tag).append(msg);
        ToolResult<Void> r = ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, msg);
        items.add(BatchItemResult.of(i, r));
        fail++;
        if (stopOnError) {
          stoppedAt = i;
          break;
        }
        continue;
      }
      var tables = doc.tables();
      if (outOfBounds(tableIndex, tables.size())) {
        String msg = indexError("表格索引", tableIndex, tables.size());
        sb.append(tag).append(msg);
        ToolResult<Void> r = indexErrorResult("表格索引", tableIndex, tables.size());
        items.add(BatchItemResult.of(i, r));
        fail++;
        if (stopOnError) {
          stoppedAt = i;
          break;
        }
        continue;
      }
      var rows = tables.get(tableIndex).rows();
      if (outOfBounds(rowIndex, rows.size())) {
        String msg = indexError("行索引", rowIndex, rows.size());
        sb.append(tag).append(msg);
        ToolResult<Void> r = indexErrorResult("行索引", rowIndex, rows.size());
        items.add(BatchItemResult.of(i, r));
        fail++;
        if (stopOnError) {
          stoppedAt = i;
          break;
        }
        continue;
      }
      Row row = rows.get(rowIndex);
      ToolResult<Void> r;
      try {
        r = action.apply(m, row);
      } catch (RuntimeException e) {
        r = ToolResult.fail(ToolResultCode.UNSUPPORTED_FEATURE, "错误:" + rootMessage(e));
      }
      sb.append(tag).append(r.message());
      items.add(BatchItemResult.of(i, r));
      if (r.success()) {
        ok++;
      } else {
        fail++;
        if (stopOnError) {
          stoppedAt = i;
          break;
        }
      }
    }
    int skipped = stoppedAt >= 0 ? list.size() - stoppedAt - 1 : 0;
    sb.append("\n成功 ").append(ok).append(" 条,失败 ").append(fail).append(" 条");
    if (fail == 0) {
      return ToolResult.ok(items, sb.toString(), ok, ok, null);
    }
    return ToolResult.partial(
        ToolResultCode.PARTIAL_FAILURE,
        items,
        sb.toString(),
        List.of(),
        ok + fail,
        ok,
        skipped > 0 ? skipped : null);
  }

  /** 单条段落编辑动作（ToolResult 边界），由各段落样式工具 lambda 提供。 */
  @FunctionalInterface
  private interface CellParagraphEditResultAction {
    ToolResult<Void> apply(Map<String, Object> m, Paragraph p);
  }

  /** 单元格内段落级写工具的批量循环骨架（ToolResult 版）。 */
  private static ToolResult<List<BatchItemResult<Void>>> applyCellParagraphEditResults(
      Document doc,
      List<Map<String, Object>> edits,
      boolean stopOnError,
      CellParagraphEditResultAction action) {
    List<Object> list = coerceList(edits);
    if (list.isEmpty()) {
      return ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "edits 为空")
          .<List<BatchItemResult<Void>>>mapData(null);
    }
    StringBuilder sb = new StringBuilder();
    List<BatchItemResult<Void>> items = new ArrayList<>();
    int ok = 0;
    int fail = 0;
    int stoppedAt = -1;
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      Object item = list.get(i);
      String tag = "[" + i + "] ";
      if (!(item instanceof Map)) {
        String msg = "错误:该条不是对象(" + item + ")";
        sb.append(tag).append(msg);
        ToolResult<Void> r = ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, msg);
        items.add(BatchItemResult.of(i, r));
        fail++;
        if (stopOnError) {
          stoppedAt = i;
          break;
        }
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) item;
      int tableIndex;
      int rowIndex;
      int cellIndex;
      int paragraphIndex;
      try {
        tableIndex = getInt(m, "table_index");
        rowIndex = getInt(m, "row_index");
        cellIndex = getInt(m, "cell_index");
        paragraphIndex = getInt(m, "paragraph_index");
      } catch (RuntimeException e) {
        String msg = "错误:" + e.getMessage();
        sb.append(tag).append(msg);
        ToolResult<Void> r = ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, msg);
        items.add(BatchItemResult.of(i, r));
        fail++;
        if (stopOnError) {
          stoppedAt = i;
          break;
        }
        continue;
      }
      ToolResult<Void> locate = locateCellResult(doc, tableIndex, rowIndex, cellIndex);
      if (!locate.success()) {
        sb.append(tag).append(locate.message());
        items.add(BatchItemResult.of(i, locate));
        fail++;
        if (stopOnError) {
          stoppedAt = i;
          break;
        }
        continue;
      }
      var paras = locateCellObj(doc, tableIndex, rowIndex, cellIndex).paragraphs();
      if (outOfBounds(paragraphIndex, paras.size())) {
        String msg = indexError("单元格内段落索引", paragraphIndex, paras.size());
        sb.append(tag).append(msg);
        ToolResult<Void> r = indexErrorResult("单元格内段落索引", paragraphIndex, paras.size());
        items.add(BatchItemResult.of(i, r));
        fail++;
        if (stopOnError) {
          stoppedAt = i;
          break;
        }
        continue;
      }
      Paragraph p = paras.get(paragraphIndex);
      ToolResult<Void> r;
      try {
        r = action.apply(m, p);
      } catch (RuntimeException e) {
        r = ToolResult.fail(ToolResultCode.UNSUPPORTED_FEATURE, "错误:" + rootMessage(e));
      }
      sb.append(tag).append(r.message());
      items.add(BatchItemResult.of(i, r));
      if (r.success()) {
        ok++;
      } else {
        fail++;
        if (stopOnError) {
          stoppedAt = i;
          break;
        }
      }
    }
    int skipped = stoppedAt >= 0 ? list.size() - stoppedAt - 1 : 0;
    sb.append("\n成功 ").append(ok).append(" 条,失败 ").append(fail).append(" 条");
    if (fail == 0) {
      return ToolResult.ok(items, sb.toString(), ok, ok, null);
    }
    return ToolResult.partial(
        ToolResultCode.PARTIAL_FAILURE,
        items,
        sb.toString(),
        List.of(),
        ok + fail,
        ok,
        skipped > 0 ? skipped : null);
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
  @ToolCapability(operation = CapabilityOperation.READ, element = "cell")
  public String readTableCell(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(
              name = "cells",
              description =
                  "对象数组,每个对象含 table_index、row_index、cell_index(int),"
                      + "如 [{\"table_index\":0,\"row_index\":0,\"cell_index\":0}]")
          @NestedParamCapability(path = "cells.table_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "cells.row_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "cells.cell_index", type = ParamType.INTEGER)
          List<Map<String, Object>> cells) {
    Document doc = document(docId);
    if (doc == null) {
      return ToolResultRenderer.render(docNotFoundResult(docId));
    }
    List<Object> list = coerceList(cells);
    if (list.isEmpty()) {
      return ToolResultRenderer.render(
          ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "cells 为空"));
    }
    StringBuilder sb = new StringBuilder();
    ElementResolver resolver = elementResolver(docId);
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
      Cell cell;
      try {
        if (m.containsKey("ref")) {
          ElementRef parsed = ElementRefs.parse(getStr(m, "ref"));
          if (!(parsed instanceof CellRef)) {
            throw new RefResolutionException(
                RefResolutionCode.REF_TYPE_MISMATCH, "read_table_cell 只接受 CellRef");
          }
          cell = resolver.resolve((CellRef) parsed);
          int[] coordinates = cellCoordinates(doc, cell);
          tableIndex = coordinates[0];
          rowIndex = coordinates[1];
          cellIndex = coordinates[2];
          validateCoordinate(m, "table_index", tableIndex);
          validateCoordinate(m, "row_index", rowIndex);
          validateCoordinate(m, "cell_index", cellIndex);
        } else {
          tableIndex = getInt(m, "table_index");
          rowIndex = getInt(m, "row_index");
          cellIndex = getInt(m, "cell_index");
          ToolResult<Void> locate = locateCellResult(doc, tableIndex, rowIndex, cellIndex);
          if (!locate.success()) {
            sb.append("[").append(i).append("] ").append(locate.message());
            continue;
          }
          cell = locateCellObj(doc, tableIndex, rowIndex, cellIndex);
        }
      } catch (RuntimeException e) {
        sb.append("[").append(i).append("] ");
        if (e instanceof RefResolutionException) {
          sb.append(((RefResolutionException) e).render());
        } else {
          sb.append("错误:").append(e.getMessage());
        }
        continue;
      }
      String coord = "(" + tableIndex + "," + rowIndex + "," + cellIndex + ")";
      var paras = cell.paragraphs();
      sb.append("[").append(i).append("] 单元格 ").append(coord).append('\n');
      sb.append("ref: ").append(resolver.reference(cell).canonical()).append('\n');
      sb.append("文本: ").append(cell.text()).append('\n');
      sb.append("段落数: ").append(paras.size());
      appendCellStyleSummary(sb, cell);
      for (int p = 0; p < paras.size(); p++) {
        sb.append("\n  段落 ").append(p).append(": run 数 ").append(paras.get(p).runs().size());
      }
    }
    return ToolResultRenderer.render(ToolResult.ok(sb.toString()));
  }

  private static int[] cellCoordinates(Document doc, Cell target) {
    for (int t = 0; t < doc.tables().size(); t++) {
      List<Row> rows = doc.tables().get(t).rows();
      for (int r = 0; r < rows.size(); r++) {
        List<Cell> cells = rows.get(r).cells();
        for (int c = 0; c < cells.size(); c++) {
          if (cells.get(c).raw() == target.raw()) {
            return new int[] {t, r, c};
          }
        }
      }
    }
    throw new RefResolutionException(RefResolutionCode.ELEMENT_REMOVED, "CellRef 目标已从表格中删除");
  }

  private static void validateCoordinate(Map<String, Object> payload, String field, int actual) {
    if (payload.containsKey(field) && getInt(payload, field) != actual) {
      throw new RefResolutionException(RefResolutionCode.STALE_REF, "ref 与 " + field + " 指向不同单元格");
    }
  }

  /** 保留旧坐标调用签名。新工具协议优先使用带 {@code ref} 的重载。 */
  public String readTableCellRun(
      String docId, int tableIndex, int rowIndex, int cellIndex, int paragraphIndex, int runIndex) {
    return readTableCellRun(docId, tableIndex, rowIndex, cellIndex, paragraphIndex, runIndex, null);
  }

  /** 读取表格某单元格内某段某 run 的文本，支持 RunRef 与旧坐标双入口。 */
  @ToolDef(
      name = "read_table_cell_run",
      description =
          "读取表格单元格内 run。优先传 canonical RunRef 字段 ref;也兼容"
              + " table_index/row_index/cell_index/paragraph_index/run_index 五级坐标。"
              + "ref 与坐标同时提供时必须指向同一 run。返回实际 ref。")
  @ToolCapability(operation = CapabilityOperation.READ, element = "run")
  public String readTableCellRun(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "table_index", description = "表格索引（0 起）", required = false)
          @ParamCapability(type = ParamType.INTEGER)
          Integer tableIndex,
      @ToolParam(name = "row_index", description = "行索引（0 起）", required = false)
          @ParamCapability(type = ParamType.INTEGER)
          Integer rowIndex,
      @ToolParam(name = "cell_index", description = "单元格索引（0 起）", required = false)
          @ParamCapability(type = ParamType.INTEGER)
          Integer cellIndex,
      @ToolParam(name = "paragraph_index", description = "单元格内段落索引（0 起）", required = false)
          @ParamCapability(type = ParamType.INTEGER)
          Integer paragraphIndex,
      @ToolParam(name = "run_index", description = "run 索引（0 起）", required = false)
          @ParamCapability(type = ParamType.INTEGER)
          Integer runIndex,
      @ToolParam(name = "ref", description = "canonical RunRef", required = false)
          @ParamCapability(type = ParamType.REF)
          String ref) {
    Document doc = document(docId);
    if (doc == null) {
      return ToolResultRenderer.render(docNotFoundResult(docId));
    }
    TableRunTarget target;
    try {
      target =
          resolveTableRunTarget(
              docId, doc, ref, tableIndex, rowIndex, cellIndex, paragraphIndex, runIndex);
    } catch (RuntimeException e) {
      return ToolResultRenderer.render(refErrorResult(e));
    }
    StringBuilder sb = new StringBuilder();
    sb.append("单元格 (")
        .append(target.tableIndex)
        .append(',')
        .append(target.rowIndex)
        .append(',')
        .append(target.cellIndex)
        .append(") 段落 ")
        .append(target.paragraphIndex)
        .append(" run ")
        .append(target.runIndex)
        .append(" ref=")
        .append(target.ref.canonical())
        .append("\n文本: ")
        .append(target.run.text());
    appendRunStyleSummary(sb, target.run);
    return ToolResultRenderer.render(ToolResult.ok(sb.toString()));
  }

  /**
   * 批量替换表格若干单元格内 run 的文本（活对象直写，需 save_docx 落盘）。
   *
   * <p><b>批量语义（v2）。</b> 入参是<b>对象数组</b> {@code edits},每个对象描述一次单元格 run 替换:
   *
   * <ul>
   *   <li>{@code ref}:canonical {@code RunRef}，推荐
   *   <li>{@code table_index}/{@code row_index}/{@code cell_index}/{@code paragraph_index}/{@code
   *       run_index}:旧五级坐标兼容入口
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
              + "canonical RunRef 字段 ref,或 table_index(int,表格索引 0 起)、row_index(int,行索引 0 起)、"
              + "cell_index(int,单元格索引 0 起)、paragraph_index(int,单元格内段落索引 0 起)、"
              + "run_index(int,run 索引 0 起)、text(string,新文本)。"
              + "ref 与坐标同时提供时必须指向同一 run。"
              + "单个对象用长度 1 的数组。部分失败不中断,返回每条成功/失败明细。")
  @ToolCapability(operation = CapabilityOperation.UPDATE, element = "run")
  public String replaceTableCellRunText(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(
              name = "edits",
              description =
                  "对象数组,每个对象含 table_index、row_index、cell_index、paragraph_index、run_index(int)"
                      + "与 text(string),如 [{\"table_index\":0,\"row_index\":0,\"cell_index\":0,"
                      + "\"paragraph_index\":0,\"run_index\":0,\"text\":\"已完成\"}]")
          @NestedParamCapability(path = "edits.ref", type = ParamType.REF)
          @NestedParamCapability(path = "edits.table_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.row_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.cell_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.paragraph_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.run_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.text", type = ParamType.STRING)
          List<Map<String, Object>> edits) {
    Document doc = document(docId);
    if (doc == null) {
      return ToolResultRenderer.render(docNotFoundResult(docId));
    }
    List<Object> list = coerceList(edits);
    if (list.isEmpty()) {
      return ToolResultRenderer.render(
          ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "edits 为空"));
    }
    StringBuilder sb = new StringBuilder();
    List<BatchItemResult<Void>> items = new ArrayList<>();
    int ok = 0;
    int fail = 0;
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      Object item = list.get(i);
      String tag = "[" + i + "] ";
      if (!(item instanceof Map)) {
        String msg = "错误:该条不是对象(" + item + ")";
        sb.append(tag).append(msg);
        items.add(BatchItemResult.of(i, ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, msg)));
        fail++;
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) item;
      TableRunTarget target;
      String text;
      try {
        target =
            resolveTableRunTarget(
                docId,
                doc,
                m.containsKey("ref") ? getStr(m, "ref") : null,
                optionalInt(m, "table_index"),
                optionalInt(m, "row_index"),
                optionalInt(m, "cell_index"),
                optionalInt(m, "paragraph_index"),
                optionalInt(m, "run_index"));
        text = getStr(m, "text");
      } catch (RuntimeException e) {
        ToolResult<Void> r = refErrorResult(e);
        sb.append(tag).append(r.message());
        items.add(BatchItemResult.of(i, r));
        fail++;
        continue;
      }
      target.run.text(text);
      String msg =
          "单元格 ("
              + target.tableIndex
              + ","
              + target.rowIndex
              + ","
              + target.cellIndex
              + ") 段落 "
              + target.paragraphIndex
              + " run "
              + target.runIndex
              + " → \""
              + text
              + "\" ref="
              + target.ref.canonical()
              + " ✓";
      sb.append(tag).append(msg);
      items.add(BatchItemResult.of(i, ToolResult.ok(msg)));
      ok++;
    }
    sb.append("\n成功 ").append(ok).append(" 条,失败 ").append(fail).append(" 条");
    if (fail == 0) {
      return ToolResultRenderer.render(ToolResult.ok(null, sb.toString(), ok, null));
    }
    return ToolResultRenderer.render(ToolResult.partial(items, sb.toString(), List.of()));
  }

  private TableRunTarget resolveTableRunTarget(
      String docId,
      Document doc,
      String ref,
      Integer tableIndex,
      Integer rowIndex,
      Integer cellIndex,
      Integer paragraphIndex,
      Integer runIndex) {
    ElementResolver resolver = elementResolver(docId);
    if (ref != null && !ref.isBlank()) {
      ElementRef parsed = ElementRefs.parse(ref);
      if (!(parsed instanceof RunRef)) {
        throw new RefResolutionException(RefResolutionCode.REF_TYPE_MISMATCH, "该操作只接受 RunRef");
      }
      Run run = resolver.resolve((RunRef) parsed);
      int[] coordinates = tableRunCoordinates(doc, run);
      validateCoordinate(tableIndex, "table_index", coordinates[0]);
      validateCoordinate(rowIndex, "row_index", coordinates[1]);
      validateCoordinate(cellIndex, "cell_index", coordinates[2]);
      validateCoordinate(paragraphIndex, "paragraph_index", coordinates[3]);
      validateCoordinate(runIndex, "run_index", coordinates[4]);
      return new TableRunTarget(
          run,
          coordinates[0],
          coordinates[1],
          coordinates[2],
          coordinates[3],
          coordinates[4],
          resolver.reference(run));
    }
    requireCoordinates(tableIndex, rowIndex, cellIndex, paragraphIndex, runIndex);
    ToolResult<Void> locate = locateCellResult(doc, tableIndex, rowIndex, cellIndex);
    if (!locate.success()) {
      throw new RefTargetException(locate);
    }
    Cell cell = locateCellObj(doc, tableIndex, rowIndex, cellIndex);
    List<Paragraph> paragraphs = cell.paragraphs();
    if (outOfBounds(paragraphIndex, paragraphs.size())) {
      throw new RefTargetException(indexErrorResult("单元格内段落索引", paragraphIndex, paragraphs.size()));
    }
    List<Run> runs = paragraphs.get(paragraphIndex).runs();
    if (outOfBounds(runIndex, runs.size())) {
      throw new RefTargetException(indexErrorResult("run 索引", runIndex, runs.size()));
    }
    Run run = runs.get(runIndex);
    return new TableRunTarget(
        run, tableIndex, rowIndex, cellIndex, paragraphIndex, runIndex, resolver.reference(run));
  }

  private static int[] tableRunCoordinates(Document doc, Run target) {
    for (int t = 0; t < doc.tables().size(); t++) {
      List<Row> rows = doc.tables().get(t).rows();
      for (int r = 0; r < rows.size(); r++) {
        List<Cell> cells = rows.get(r).cells();
        for (int c = 0; c < cells.size(); c++) {
          List<Paragraph> paragraphs = cells.get(c).paragraphs();
          for (int p = 0; p < paragraphs.size(); p++) {
            List<Run> runs = paragraphs.get(p).runs();
            for (int u = 0; u < runs.size(); u++) {
              if (runs.get(u).raw() == target.raw()) {
                return new int[] {t, r, c, p, u};
              }
            }
          }
        }
      }
    }
    throw new RefResolutionException(RefResolutionCode.DOCUMENT_MISMATCH, "RunRef 不属于表格单元格 run");
  }

  private static void validateCoordinate(Integer provided, String field, int actual) {
    if (provided != null && provided != actual) {
      throw new RefResolutionException(RefResolutionCode.STALE_REF, "ref 与 " + field + " 指向不同 run");
    }
  }

  private static void requireCoordinates(Integer... coordinates) {
    for (Integer coordinate : coordinates) {
      if (coordinate == null) {
        throw new IllegalArgumentException("未提供 ref 时必须提供完整表格 run 坐标");
      }
    }
  }

  private static Integer optionalInt(Map<String, Object> payload, String field) {
    return payload.containsKey(field) ? getInt(payload, field) : null;
  }

  /** 把 ref 域异常映射成 {@link ToolResult}（内部方法返回结构化结果，不嗅探中文前缀）。 */
  private static ToolResult<Void> refErrorResult(RuntimeException e) {
    if (e instanceof RefResolutionException) {
      RefResolutionException rre = (RefResolutionException) e;
      return ToolResult.fail(rre.code().toToolResultCode(), rre.render());
    }
    if (e instanceof RefTargetException) {
      return ((RefTargetException) e).result;
    }
    String msg = e.getMessage() != null ? e.getMessage() : "";
    return ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "错误:" + msg);
  }

  /** 包装结构化 {@link ToolResult} 的内部异常，用于从 helper 抛出到 @ToolDef 边界。 */
  private static final class RefTargetException extends RuntimeException {
    final ToolResult<Void> result;

    RefTargetException(ToolResult<Void> result) {
      super(result.message());
      this.result = result;
    }
  }

  private static final class TableRunTarget {
    private final Run run;
    private final int tableIndex;
    private final int rowIndex;
    private final int cellIndex;
    private final int paragraphIndex;
    private final int runIndex;
    private final RunRef ref;

    private TableRunTarget(
        Run run,
        int tableIndex,
        int rowIndex,
        int cellIndex,
        int paragraphIndex,
        int runIndex,
        RunRef ref) {
      this.run = run;
      this.tableIndex = tableIndex;
      this.rowIndex = rowIndex;
      this.cellIndex = cellIndex;
      this.paragraphIndex = paragraphIndex;
      this.runIndex = runIndex;
      this.ref = ref;
    }
  }

  // ==================== 单元格视觉样式(子任务 1:底纹/垂直对齐/run 样式/段落对齐) ====================

  /**
   * 批量给表格单元格设置纯色背景底纹(活对象直写,需 save_docx 落盘)。
   *
   * <p><b>OOXML → POI → nondocx 三层</b>:底纹写在单元格属性 {@code <w:tcPr>} 内的 {@code <w:shd w:val="clear"
   * w:fill="...">}。nondocx 的 {@code Cell.shading(String)} <b>强制 w:val="clear"</b> (纯背景色填充,跨
   * Word/WPS 安全,不会出 WPS 黑块——见 {@code renderer-compatibility.md#shading-solid}),不暴露 SOLID。
   * 故本工具只传一个颜色字符串即可,无图案参数。
   *
   * <p><b>批量语义。</b> {@code edits} 是对象数组,每条含 {@code table_index}、{@code row_index}、{@code
   * cell_index} 与 {@code fill}(十六进制 RGB,如 {@code F1F5F9},不带 {@code #})。长度 1 即单次,多个即一次改多处。
   * collect-errors:越界/缺字段记中文错误不中断,末尾汇总。
   */
  @ToolDef(
      name = "update_table_cell_shading",
      description =
          "批量给表格单元格设置纯色背景底纹(改完需 save_docx 落盘)。edits 是对象数组,每个对象含 "
              + "table_index(int)、row_index(int)、cell_index(int)、fill(string,十六进制 RGB 如 F1F5F9,不带 #)。"
              + "强制纯色填充(跨 Word/WPS 安全,不为黑块)。单个对象用长度 1 的数组。部分失败不中断。"
              + "可选 on_error=stop 遇首条失败即停;expected_generation 防止旧快照修改新状态。")
  @ToolCapability(operation = CapabilityOperation.UPDATE, element = "cell")
  public String updateTableCellShading(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(
              name = "edits",
              description =
                  "对象数组,每个对象含 table_index、row_index、cell_index(int)与 fill(string),"
                      + "如 [{\"table_index\":0,\"row_index\":0,\"cell_index\":0,\"fill\":\"F1F5F9\"}]")
          @NestedParamCapability(path = "edits.table_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.row_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.cell_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.fill", type = ParamType.STRING)
          List<Map<String, Object>> edits,
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
    Document doc = document(docId);
    if (doc == null) {
      return ToolResultRenderer.render(docNotFoundResult(docId));
    }
    if (!checkExpectedGeneration(docId, expectedGeneration)) {
      return renderGenerationMismatch(expectedGeneration, generations.getOrDefault(docId, 1L));
    }
    return ToolResultRenderer.render(
        applyCellEditResults(
            doc,
            edits,
            "stop".equalsIgnoreCase(onError),
            (m, cell) -> {
              String fill = getStr(m, "fill");
              cell.shading(fill);
              return ToolResult.ok("单元格 " + coord(m) + " 底纹 → " + fill + " ✓");
            }));
  }

  /**
   * 批量设置表格单元格内容的垂直对齐(活对象直写,需 save_docx 落盘)。
   *
   * <p><b>OOXML → POI → nondocx 三层</b>:垂直对齐写在 {@code <w:tcPr>} 内的 {@code <w:vAlign
   * w:val="top|center|bottom">}。 nondocx 的 {@code Cell.verticalAlign(VerticalAlign)} 收口。
   *
   * <p><b>WPS 兼容性提示</b>:固定(exact)行高时 {@code CENTER}/{@code BOTTOM} 在 WPS 可能不生效 (见 {@code
   * renderer-compatibility.md#exact-row-valign});本工具不兜底,仅按要求写入。
   */
  @ToolDef(
      name = "update_table_cell_vertical_align",
      description =
          "批量设置表格单元格内容的垂直对齐(改完需 save_docx 落盘)。edits 是对象数组,每个对象含 "
              + "table_index(int)、row_index(int)、cell_index(int)、vertical_align(string,TOP/CENTER/BOTTOM,大小写不敏感)。"
              + "注意:固定(exact)行高时 CENTER/BOTTOM 在 WPS 可能不生效。单个对象用长度 1 的数组。部分失败不中断。"
              + "可选 on_error=stop 遇首条失败即停;expected_generation 防止旧快照修改新状态。")
  @ToolCapability(operation = CapabilityOperation.UPDATE, element = "cell", needsRecalc = true)
  public String updateTableCellVerticalAlign(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(
              name = "edits",
              description =
                  "对象数组,每个对象含 table_index、row_index、cell_index(int)与 vertical_align(string),"
                      + "如 [{\"table_index\":0,\"row_index\":0,\"cell_index\":0,\"vertical_align\":\"CENTER\"}]")
          @NestedParamCapability(path = "edits.table_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.row_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.cell_index", type = ParamType.INTEGER)
          @NestedParamCapability(
              path = "edits.vertical_align",
              type = ParamType.ENUM,
              enumValues = {"TOP", "CENTER", "BOTTOM"})
          List<Map<String, Object>> edits,
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
    Document doc = document(docId);
    if (doc == null) {
      return ToolResultRenderer.render(docNotFoundResult(docId));
    }
    if (!checkExpectedGeneration(docId, expectedGeneration)) {
      return renderGenerationMismatch(expectedGeneration, generations.getOrDefault(docId, 1L));
    }
    return ToolResultRenderer.render(
        applyCellEditResults(
            doc,
            edits,
            "stop".equalsIgnoreCase(onError),
            (m, cell) -> {
              VerticalAlign va = parseVerticalAlign(getStr(m, "vertical_align"));
              cell.verticalAlign(va);
              return ToolResult.ok("单元格 " + coord(m) + " 垂直对齐 → " + va + " ✓");
            }));
  }

  /**
   * 批量改表格单元格内 run 的内联字符样式(活对象直写,需 save_docx 落盘)。
   *
   * <p>与 {@link BodyTools#updateRunStyle} 在<b>参数结构、布尔字段语义(显式 false 清除、未传不改)、collect-errors</b>
   * 上完全一致,只是寻址链深三层 (多了 table/row/cell,再到 paragraph/run)。
   */
  @ToolDef(
      name = "update_table_cell_run_style",
      description =
          "批量改表格单元格内 run 的内联字符样式(改完需 save_docx 落盘)。edits 是对象数组,每个对象含 "
              + "table_index(int)、row_index(int)、cell_index(int)、paragraph_index(int,单元格内段落 0 起)、run_index(int,run 0 起),"
              + "以及可选样式字段:bold(bool)、italic(bool)、underline(bool)、font(string)、font_size(int)、color(string,十六进制如 FF0000)。"
              + "布尔字段显式传 false 可清除样式;未传字段不改。部分失败不中断。")
  @ToolCapability(operation = CapabilityOperation.UPDATE, element = "run")
  public String updateTableCellRunStyle(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(
              name = "edits",
              description =
                  "对象数组,每个对象含 table_index、row_index、cell_index、paragraph_index、run_index(int)"
                      + "与可选 bold/italic/underline/font/font_size/color,"
                      + "如 [{\"table_index\":0,\"row_index\":0,\"cell_index\":0,\"paragraph_index\":0,\"run_index\":0,\"bold\":true}]")
          @NestedParamCapability(path = "edits.table_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.row_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.cell_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.paragraph_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.run_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.bold", type = ParamType.BOOLEAN)
          @NestedParamCapability(path = "edits.italic", type = ParamType.BOOLEAN)
          @NestedParamCapability(path = "edits.underline", type = ParamType.BOOLEAN)
          @NestedParamCapability(path = "edits.font", type = ParamType.STRING)
          @NestedParamCapability(path = "edits.font_size", type = ParamType.INTEGER, unit = "pt")
          @NestedParamCapability(path = "edits.color", type = ParamType.STRING)
          List<Map<String, Object>> edits) {
    Document doc = document(docId);
    if (doc == null) {
      return ToolResultRenderer.render(docNotFoundResult(docId));
    }
    List<Object> list = coerceList(edits);
    if (list.isEmpty()) {
      return ToolResultRenderer.render(
          ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "edits 为空"));
    }
    StringBuilder sb = new StringBuilder();
    List<BatchItemResult<Void>> items = new ArrayList<>();
    int ok = 0;
    int fail = 0;
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      Object item = list.get(i);
      String tag = "[" + i + "] ";
      if (!(item instanceof Map)) {
        String msg = "错误:该条不是对象(" + item + ")";
        sb.append(tag).append(msg);
        items.add(BatchItemResult.of(i, ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, msg)));
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
      try {
        tableIndex = getInt(m, "table_index");
        rowIndex = getInt(m, "row_index");
        cellIndex = getInt(m, "cell_index");
        paragraphIndex = getInt(m, "paragraph_index");
        runIndex = getInt(m, "run_index");
      } catch (RuntimeException e) {
        String msg = "错误:" + e.getMessage();
        sb.append(tag).append(msg);
        items.add(BatchItemResult.of(i, ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, msg)));
        fail++;
        continue;
      }
      ToolResult<Void> locate = locateCellResult(doc, tableIndex, rowIndex, cellIndex);
      if (!locate.success()) {
        sb.append(tag).append(locate.message());
        items.add(BatchItemResult.of(i, locate));
        fail++;
        continue;
      }
      var paras = locateCellObj(doc, tableIndex, rowIndex, cellIndex).paragraphs();
      if (outOfBounds(paragraphIndex, paras.size())) {
        String msg = indexError("单元格内段落索引", paragraphIndex, paras.size());
        sb.append(tag).append(msg);
        items.add(
            BatchItemResult.of(i, indexErrorResult("单元格内段落索引", paragraphIndex, paras.size())));
        fail++;
        continue;
      }
      var runs = paras.get(paragraphIndex).runs();
      if (outOfBounds(runIndex, runs.size())) {
        String msg = indexError("run 索引", runIndex, runs.size());
        sb.append(tag).append(msg);
        items.add(BatchItemResult.of(i, indexErrorResult("run 索引", runIndex, runs.size())));
        fail++;
        continue;
      }
      Run run = runs.get(runIndex);
      List<String> changed = applyRunStyleFields(m, run);
      if (changed == null) {
        String msg = "未提供任何样式字段";
        sb.append(tag).append(msg);
        items.add(BatchItemResult.of(i, noChangesAppliedResult(msg)));
        fail++;
        continue;
      }
      String msg =
          "单元格 ("
              + tableIndex
              + ","
              + rowIndex
              + ","
              + cellIndex
              + ") 段落 "
              + paragraphIndex
              + " run "
              + runIndex
              + " 样式 → "
              + String.join("、", changed)
              + " ✓";
      sb.append(tag).append(msg);
      items.add(BatchItemResult.of(i, ToolResult.ok(msg)));
      ok++;
    }
    sb.append("\n成功 ").append(ok).append(" 条,失败 ").append(fail).append(" 条");
    if (fail == 0) {
      return ToolResultRenderer.render(ToolResult.ok(null, sb.toString(), ok, null));
    }
    return ToolResultRenderer.render(ToolResult.partial(items, sb.toString(), List.of()));
  }

  /**
   * 批量改表格单元格内段落的水平对齐(活对象直写,需 save_docx 落盘)。
   *
   * <p>与 {@link BodyTools#updateParagraphAlignment} 在参数结构与失败语义上一致,只是寻址链深三层。
   */
  @ToolDef(
      name = "update_table_cell_paragraph_alignment",
      description =
          "批量改表格单元格内段落的水平对齐(改完需 save_docx 落盘)。edits 是对象数组,每个对象含 "
              + "table_index(int)、row_index(int)、cell_index(int)、paragraph_index(int,单元格内段落 0 起)、"
              + "alignment(string,LEFT/CENTER/RIGHT/JUSTIFY,大小写不敏感)。单个对象用长度 1 的数组。部分失败不中断。")
  @ToolCapability(operation = CapabilityOperation.UPDATE, element = "paragraph")
  public String updateTableCellParagraphAlignment(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(
              name = "edits",
              description =
                  "对象数组,每个对象含 table_index、row_index、cell_index、paragraph_index(int)与 alignment(string),"
                      + "如 [{\"table_index\":0,\"row_index\":0,\"cell_index\":0,\"paragraph_index\":0,\"alignment\":\"CENTER\"}]")
          @NestedParamCapability(path = "edits.table_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.row_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.cell_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.paragraph_index", type = ParamType.INTEGER)
          @NestedParamCapability(
              path = "edits.alignment",
              type = ParamType.ENUM,
              enumValues = {"LEFT", "CENTER", "RIGHT", "JUSTIFY"})
          List<Map<String, Object>> edits) {
    Document doc = document(docId);
    if (doc == null) {
      return ToolResultRenderer.render(docNotFoundResult(docId));
    }
    List<Object> list = coerceList(edits);
    if (list.isEmpty()) {
      return ToolResultRenderer.render(
          ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "edits 为空"));
    }
    StringBuilder sb = new StringBuilder();
    List<BatchItemResult<Void>> items = new ArrayList<>();
    int ok = 0;
    int fail = 0;
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      Object item = list.get(i);
      String tag = "[" + i + "] ";
      if (!(item instanceof Map)) {
        String msg = "错误:该条不是对象(" + item + ")";
        sb.append(tag).append(msg);
        items.add(BatchItemResult.of(i, ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, msg)));
        fail++;
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) item;
      int tableIndex;
      int rowIndex;
      int cellIndex;
      int paragraphIndex;
      Alignment alignment;
      try {
        tableIndex = getInt(m, "table_index");
        rowIndex = getInt(m, "row_index");
        cellIndex = getInt(m, "cell_index");
        paragraphIndex = getInt(m, "paragraph_index");
        alignment = parseAlignment(getStr(m, "alignment"));
      } catch (RuntimeException e) {
        String msg = "错误:" + e.getMessage();
        sb.append(tag).append(msg);
        items.add(BatchItemResult.of(i, ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, msg)));
        fail++;
        continue;
      }
      ToolResult<Void> locate = locateCellResult(doc, tableIndex, rowIndex, cellIndex);
      if (!locate.success()) {
        sb.append(tag).append(locate.message());
        items.add(BatchItemResult.of(i, locate));
        fail++;
        continue;
      }
      var paras = locateCellObj(doc, tableIndex, rowIndex, cellIndex).paragraphs();
      if (outOfBounds(paragraphIndex, paras.size())) {
        String msg = indexError("单元格内段落索引", paragraphIndex, paras.size());
        sb.append(tag).append(msg);
        items.add(
            BatchItemResult.of(i, indexErrorResult("单元格内段落索引", paragraphIndex, paras.size())));
        fail++;
        continue;
      }
      try {
        paras.get(paragraphIndex).alignment(alignment);
      } catch (RuntimeException e) {
        String msg = "错误:" + rootMessage(e);
        sb.append(tag).append(msg);
        items.add(BatchItemResult.of(i, ToolResult.fail(ToolResultCode.UNSUPPORTED_FEATURE, msg)));
        fail++;
        continue;
      }
      String msg =
          "单元格 ("
              + tableIndex
              + ","
              + rowIndex
              + ","
              + cellIndex
              + ") 段落 "
              + paragraphIndex
              + " 对齐 → "
              + alignment
              + " ✓";
      sb.append(tag).append(msg);
      items.add(BatchItemResult.of(i, ToolResult.ok(msg)));
      ok++;
    }
    sb.append("\n成功 ").append(ok).append(" 条,失败 ").append(fail).append(" 条");
    if (fail == 0) {
      return ToolResultRenderer.render(ToolResult.ok(null, sb.toString(), ok, null));
    }
    return ToolResultRenderer.render(ToolResult.partial(items, sb.toString(), List.of()));
  }

  // ==================== 单元格视觉样式·读侧补强 ====================

  /**
   * 把单元格底纹与垂直对齐信息补进 {@link #readTableCell} 的摘要。
   *
   * <p>原摘要只有文本+段落数+run 数;现补上 {@code 底纹}(十六进制 RGB 或「无」)与 {@code 垂直对齐}(TOP/CENTER/BOTTOM 或「默认(TOP)」)。
   */
  private static void appendCellStyleSummary(
      StringBuilder sb, com.non.docx.core.api.table.Cell cell) {
    var shd = cell.shading();
    sb.append("\n底纹: ").append(shd == null ? "无" : shd.fill());
    var va = cell.verticalAlign();
    sb.append("\n垂直对齐: ").append(va == null ? "默认(TOP)" : va);
  }

  /**
   * 把 run 样式摘要拼成一行(与 {@code BodyTools.read_run} 的「样式:」行对称)。
   *
   * <p>{@code Run.style()} 返回的 {@code RunStyle#toString()} 形如 {@code bold=true, italic=false, ...}。
   */
  private static void appendRunStyleSummary(StringBuilder sb, Run run) {
    sb.append("\n样式: ").append(run.style());
  }

  // ==================== 单元格写工具共享:批量循环骨架 ====================

  /** 把一条 edit 的坐标格式化为 {@code (t,r,c)} 串,供成功消息复用。 */
  private static String coord(Map<String, Object> m) {
    return "(" + m.get("table_index") + "," + m.get("row_index") + "," + m.get("cell_index") + ")";
  }

  /**
   * 把 edit Map 里的 6 个可选样式字段写入 run,返回已改字段名列表;若一个样式字段都没传返回 {@code null}(调用方据此报错)。
   *
   * <p>布尔字段按「是否存在」判断:显式 {@code false} 清除、未传不改——与 {@link BodyTools#updateRunStyle} 完全一致。
   */
  private static List<String> applyRunStyleFields(Map<String, Object> m, Run run) {
    List<String> changed = new ArrayList<>();
    if (m.containsKey("bold")) {
      boolean value = boolVal(m.get("bold"));
      run.bold(value);
      changed.add("bold=" + value);
    }
    if (m.containsKey("italic")) {
      boolean value = boolVal(m.get("italic"));
      run.italic(value);
      changed.add("italic=" + value);
    }
    if (m.containsKey("underline")) {
      boolean value = boolVal(m.get("underline"));
      run.underline(value);
      changed.add("underline=" + value);
    }
    if (m.containsKey("font")) {
      String value = getStr(m, "font");
      run.font(value);
      changed.add("font=" + value);
    }
    if (m.containsKey("font_size")) {
      int value = getInt(m, "font_size");
      run.fontSize(value);
      changed.add("font_size=" + value);
    }
    if (m.containsKey("color")) {
      String value = getStr(m, "color");
      run.color(value);
      changed.add("color=" + value);
    }
    return changed.isEmpty() ? null : changed;
  }

  /** 解析 vertical_align 字符串(大小写不敏感),非法值抛 IAE 由调用方转错误串。 */
  private static VerticalAlign parseVerticalAlign(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("vertical_align 不能为空");
    }
    try {
      return VerticalAlign.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("vertical_align 仅支持 TOP/CENTER/BOTTOM:" + raw);
    }
  }

  /** 解析 alignment 字符串(大小写不敏感),非法值抛 IAE。与 {@link BodyTools} 的 parseAlignment 同义。 */
  private static Alignment parseAlignment(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("alignment 不能为空");
    }
    try {
      return Alignment.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("alignment 仅支持 LEFT/CENTER/RIGHT/JUSTIFY:" + raw);
    }
  }

  // ==================== 表格行属性与列宽(子任务 2) ====================

  /**
   * 批量标记/取消表格表头行(活对象直写,需 save_docx 落盘)。
   *
   * <p><b>OOXML → POI → nondocx 三层</b>:表头行标记在行属性 {@code <w:trPr>} 内的 {@code <w:tblHeader
   * w:val="true"/>}。 表头行在表格跨页时会在每页顶部重复显示。nondocx 的 {@code Row.headerRow(boolean)} 收口,跨 Word/WPS
   * 行为一致。
   *
   * <p><b>闭环价值</b>:{@code QualityCheck} 已经在<b>检查</b> headerRow 是否缺失,本工具让 Agent
   * 在收到检查报告后能<b>修正</b>——形成「检查 → 改 → 复检」闭环。
   */
  @ToolDef(
      name = "update_table_header_row",
      description =
          "批量标记/取消表格表头行(改完需 save_docx 落盘)。edits 是对象数组,每个对象含 "
              + "table_index(int)、row_index(int,0 起)、header_row(bool,true=标记表头行跨页重复、false=取消)。"
              + "单个对象用长度 1 的数组。部分失败不中断。"
              + "可选 on_error=stop 遇首条失败即停;expected_generation 防止旧快照修改新状态。")
  @ToolCapability(operation = CapabilityOperation.UPDATE, element = "row")
  public String updateTableHeaderRow(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(
              name = "edits",
              description =
                  "对象数组,每个对象含 table_index、row_index(int)与 header_row(bool),"
                      + "如 [{\"table_index\":0,\"row_index\":0,\"header_row\":true}]")
          @NestedParamCapability(path = "edits.table_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.row_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.header_row", type = ParamType.BOOLEAN)
          List<Map<String, Object>> edits,
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
    Document doc = document(docId);
    if (doc == null) {
      return ToolResultRenderer.render(docNotFoundResult(docId));
    }
    if (!checkExpectedGeneration(docId, expectedGeneration)) {
      return renderGenerationMismatch(expectedGeneration, generations.getOrDefault(docId, 1L));
    }
    return ToolResultRenderer.render(
        applyRowEditResults(
            doc,
            edits,
            "stop".equalsIgnoreCase(onError),
            (m, row) -> {
              boolean on = boolVal(m.get("header_row"));
              row.headerRow(on);
              return ToolResult.ok(
                  "表格 "
                      + m.get("table_index")
                      + " 行 "
                      + m.get("row_index")
                      + (on ? " → 表头行 ✓" : " → 取消表头行 ✓"));
            }));
  }

  /**
   * 批量标记/取消行的禁止跨页拆分(活对象直写,需 save_docx 落盘)。
   *
   * <p><b>OOXML → POI → nondocx 三层</b>:禁止跨页拆分在 {@code <w:trPr>} 内的 {@code <w:cantSplit
   * w:val="true"/>}。 设后此行内容保持在同一页,不会被分页符拆到两页。nondocx 的 {@code Row.cantSplit(boolean)} 收口,跨引擎一致。
   */
  @ToolDef(
      name = "update_table_row_cant_split",
      description =
          "批量标记/取消行的禁止跨页拆分(改完需 save_docx 落盘)。edits 是对象数组,每个对象含 "
              + "table_index(int)、row_index(int,0 起)、cant_split(bool,true=禁止跨页拆分、false=允许)。"
              + "单个对象用长度 1 的数组。部分失败不中断。"
              + "可选 on_error=stop 遇首条失败即停;expected_generation 防止旧快照修改新状态。")
  @ToolCapability(operation = CapabilityOperation.UPDATE, element = "row")
  public String updateTableRowCantSplit(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(
              name = "edits",
              description =
                  "对象数组,每个对象含 table_index、row_index(int)与 cant_split(bool),"
                      + "如 [{\"table_index\":0,\"row_index\":0,\"cant_split\":true}]")
          @NestedParamCapability(path = "edits.table_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.row_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.cant_split", type = ParamType.BOOLEAN)
          List<Map<String, Object>> edits,
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
    Document doc = document(docId);
    if (doc == null) {
      return ToolResultRenderer.render(docNotFoundResult(docId));
    }
    if (!checkExpectedGeneration(docId, expectedGeneration)) {
      return renderGenerationMismatch(expectedGeneration, generations.getOrDefault(docId, 1L));
    }
    return ToolResultRenderer.render(
        applyRowEditResults(
            doc,
            edits,
            "stop".equalsIgnoreCase(onError),
            (m, row) -> {
              boolean on = boolVal(m.get("cant_split"));
              row.cantSplit(on);
              return ToolResult.ok(
                  "表格 "
                      + m.get("table_index")
                      + " 行 "
                      + m.get("row_index")
                      + (on ? " → 禁止跨页拆分 ✓" : " → 允许跨页拆分 ✓"));
            }));
  }

  /**
   * 批量读取表格若干行的属性摘要(header_row、cant_split)。
   *
   * <p>让 Agent 在 {@code QualityCheck} 报「未设 headerRow/cantSplit」后能<b>先读确认、再改</b>,形成「检查 → 读确认 → 改 →
   * 复检」闭环。
   */
  @ToolDef(
      name = "read_table_row",
      description =
          "批量读取表格若干行的属性摘要(header_row、cant_split)。rows 是对象数组,每个对象含 "
              + "table_index(int)、row_index(int,0 起)。单个对象用长度 1 的数组。越界坐标不中断,会在结果里标注。")
  @ToolCapability(operation = CapabilityOperation.READ, element = "row")
  public String readTableRow(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(
              name = "rows",
              description =
                  "对象数组,每个对象含 table_index、row_index(int),"
                      + "如 [{\"table_index\":0,\"row_index\":0}]")
          @NestedParamCapability(path = "rows.table_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "rows.row_index", type = ParamType.INTEGER)
          List<Map<String, Object>> rows) {
    Document doc = document(docId);
    if (doc == null) {
      return ToolResultRenderer.render(docNotFoundResult(docId));
    }
    List<Object> list = coerceList(rows);
    if (list.isEmpty()) {
      return ToolResultRenderer.render(ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "rows 为空"));
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
      int tableIndex;
      int rowIndex;
      try {
        tableIndex = getInt(m, "table_index");
        rowIndex = getInt(m, "row_index");
      } catch (RuntimeException e) {
        sb.append(tag).append("错误:").append(e.getMessage());
        continue;
      }
      var tables = doc.tables();
      if (outOfBounds(tableIndex, tables.size())) {
        sb.append(tag).append(indexError("表格索引", tableIndex, tables.size()));
        continue;
      }
      var tableRows = tables.get(tableIndex).rows();
      if (outOfBounds(rowIndex, tableRows.size())) {
        sb.append(tag).append(indexError("行索引", rowIndex, tableRows.size()));
        continue;
      }
      Row row = tableRows.get(rowIndex);
      sb.append(tag)
          .append("表格 ")
          .append(tableIndex)
          .append(" 行 ")
          .append(rowIndex)
          .append("\n表头行: ")
          .append(row.headerRow())
          .append("\n禁止跨页拆分: ")
          .append(row.cantSplit());
    }
    return ToolResultRenderer.render(ToolResult.ok(sb.toString()));
  }

  /**
   * 按百分比设置表格各列宽度(主推路径,活对象直写,需 save_docx 落盘)。
   *
   * <p><b>OOXML → POI → nondocx 三层</b>:列宽在表格 {@code <w:tblGrid>} 内每个 {@code <w:gridCol>} 的 {@code
   * w:w}。 百分比(PCT)以「五十分之一百分比」编码,跨 Word/WPS 行为一致;DXA(twips 绝对值)在 WPS 触发 tblGrid 错位 bug。nondocx 的
   * {@code Table.columnPercents(int[])} 是主推路径。
   *
   * <p><b>单表作用域</b>:列宽作用于整张表的 tblGrid(一张表只有一份),故用 {@code table_index} + 一个数组,不用批量对象数组——与 {@code
   * set_table_borders} 一致。
   */
  @ToolDef(
      name = "set_table_column_percents",
      description =
          "按百分比设置表格各列宽度(改完需 save_docx 落盘)。百分比(PCT)是跨 Word/WPS 安全的主推路径。"
              + "参数:table_index(int,表格索引 0 起)、percents(int 数组,每列 0-100 的整数百分比,数组长度即列数)。"
              + "若需绝对宽度用 set_table_column_widths。")
  @ToolCapability(operation = CapabilityOperation.UPDATE, element = "table")
  public String setTableColumnPercents(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "table_index", description = "表格索引(0 起)")
          @ParamCapability(type = ParamType.INTEGER)
          int tableIndex,
      @ToolParam(name = "percents", description = "各列百分比数组(0-100 整数,长度即列数),如 [50,50]")
          @ParamCapability(type = ParamType.INTEGER_ARRAY, unit = "percent")
          List<Integer> percents) {
    Document doc = document(docId);
    if (doc == null) {
      return ToolResultRenderer.render(docNotFoundResult(docId));
    }
    int[] pct;
    try {
      pct = toIntArray(percents, "percents");
    } catch (RuntimeException e) {
      return ToolResultRenderer.render(
          ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "错误:" + e.getMessage()));
    }
    var tables = doc.tables();
    if (outOfBounds(tableIndex, tables.size())) {
      return ToolResultRenderer.render(indexErrorResult("表格索引", tableIndex, tables.size()));
    }
    try {
      tables.get(tableIndex).columnPercents(pct);
    } catch (RuntimeException e) {
      return ToolResultRenderer.render(
          ToolResult.fail(ToolResultCode.UNSUPPORTED_FEATURE, "错误:" + rootMessage(e)));
    }
    return ToolResultRenderer.render(
        ToolResult.ok("已设置表格 " + tableIndex + " 列宽百分比 → " + Arrays.toString(pct)));
  }

  /**
   * 按 twips 绝对宽度设置表格各列宽度(活对象直写,需 save_docx 落盘)。
   *
   * <p><b>WPS 兼容性风险</b>:纯 DXA 在 WPS 某些版本触发 tblGrid 错位 bug (见 {@code
   * renderer-compatibility.md#table-width-dxa})。跨引擎场景请优先用 {@code set_table_column_percents}。
   */
  @ToolDef(
      name = "set_table_column_widths",
      description =
          "按 twips 绝对宽度设置表格各列宽度(改完需 save_docx 落盘)。1 twip = 1/20 点。"
              + "参数:table_index(int,表格索引 0 起)、widths(int 数组,每列 twips 宽度,长度即列数)。"
              + "注意:纯 DXA 在 WPS 某些版本触发 tblGrid 错位,跨引擎优先用 set_table_column_percents。")
  @ToolCapability(operation = CapabilityOperation.UPDATE, element = "table")
  public String setTableColumnWidths(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "table_index", description = "表格索引(0 起)")
          @ParamCapability(type = ParamType.INTEGER)
          int tableIndex,
      @ToolParam(name = "widths", description = "各列 twips 宽度数组,长度即列数,如 [4513,4513]")
          @ParamCapability(type = ParamType.INTEGER_ARRAY, unit = "twip")
          List<Integer> widths) {
    Document doc = document(docId);
    if (doc == null) {
      return ToolResultRenderer.render(docNotFoundResult(docId));
    }
    int[] dxa;
    try {
      dxa = toIntArray(widths, "widths");
    } catch (RuntimeException e) {
      return ToolResultRenderer.render(
          ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "错误:" + e.getMessage()));
    }
    var tables = doc.tables();
    if (outOfBounds(tableIndex, tables.size())) {
      return ToolResultRenderer.render(indexErrorResult("表格索引", tableIndex, tables.size()));
    }
    try {
      tables.get(tableIndex).columnWidths(dxa);
    } catch (RuntimeException e) {
      return ToolResultRenderer.render(
          ToolResult.fail(ToolResultCode.UNSUPPORTED_FEATURE, "错误:" + rootMessage(e)));
    }
    return ToolResultRenderer.render(
        ToolResult.ok("已设置表格 " + tableIndex + " 列宽(twips) → " + Arrays.toString(dxa)));
  }

  /**
   * 读取表格各列宽度(twips)列表。
   *
   * <p>读取时 PCT 类型的列宽按 A4 可用宽度(9026 twips)近似换算回 twips;DXA 类型的列宽原样返回。
   */
  @ToolDef(
      name = "read_table_column_widths",
      description =
          "读取指定表格各列宽度(twips)列表。PCT 类型按 A4 可用宽度(9026 twips)近似换算,DXA 原样返回。"
              + "参数:table_index(int,表格索引 0 起)。")
  @ToolCapability(operation = CapabilityOperation.READ, element = "table")
  public String readTableColumnWidths(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "table_index", description = "表格索引(0 起)")
          @ParamCapability(type = ParamType.INTEGER)
          int tableIndex) {
    Document doc = document(docId);
    if (doc == null) {
      return ToolResultRenderer.render(docNotFoundResult(docId));
    }
    var tables = doc.tables();
    if (outOfBounds(tableIndex, tables.size())) {
      return ToolResultRenderer.render(indexErrorResult("表格索引", tableIndex, tables.size()));
    }
    var widths = tables.get(tableIndex).columnWidths();
    if (widths.isEmpty()) {
      return ToolResultRenderer.render(
          ToolResult.ok("表格 " + tableIndex + " 未设列宽(tblGrid 无 gridCol)"));
    }
    StringBuilder sb = new StringBuilder();
    sb.append("表格 ").append(tableIndex).append(" 列宽(twips),共 ").append(widths.size()).append(" 列:");
    for (int c = 0; c < widths.size(); c++) {
      sb.append("\n  列 ").append(c).append(": ").append(widths.get(c));
    }
    return ToolResultRenderer.render(ToolResult.ok(sb.toString()));
  }

  // ==================== 行级写工具共享:批量循环骨架 ====================

  /** 把 LLM 传来的数组(JSON 还原为 List&lt;Integer&gt;/List&lt;Number&gt;)归一化为 int[],空或 null 报错。 */
  private static int[] toIntArray(List<Integer> raw, String name) {
    List<Object> list = coerceList(raw);
    if (list.isEmpty()) {
      throw new IllegalArgumentException(name + " 不能为空");
    }
    int[] arr = new int[list.size()];
    for (int i = 0; i < list.size(); i++) {
      Object v = list.get(i);
      if (v instanceof Number) {
        arr[i] = ((Number) v).intValue();
      } else if (v instanceof String) {
        try {
          arr[i] = Integer.parseInt(((String) v).trim());
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException(name + " 第 " + i + " 个元素不是合法整数:\"" + v + "\"");
        }
      } else if (v == null) {
        throw new IllegalArgumentException(name + " 第 " + i + " 个元素为 null");
      } else {
        throw new IllegalArgumentException(name + " 第 " + i + " 个元素不是整数:" + v);
      }
    }
    return arr;
  }

  // ==================== 表格结构编辑与段落长尾样式(子任务 3) ====================

  /**
   * 在指定表格末尾追加一个空行(活对象直写,需 save_docx 落盘)。返回新行索引。
   *
   * <p><b>OOXML → POI → nondocx 三层</b>:追加行即在 {@code <w:tbl>} 末尾加一个 {@code <w:tr>}。POI 的 {@code
   * createRow()} 会预填镜像单元格,nondocx 的 {@code Table.addRow()} 已剥离预填(poi-bridge.md N2),故返回的是<b>空行</b>(0
   * 个单元格),需后续用 {@code add_table_cell} 填充。
   */
  @ToolDef(
      name = "add_table_row",
      description =
          "在指定表格末尾追加一个空行(改完需 save_docx 落盘)。返回新行索引。"
              + "新行是空行(0 个单元格),需用 add_table_cell 逐个追加单元格。参数:table_index(int,表格索引 0 起)。")
  @ToolCapability(operation = CapabilityOperation.ADD, element = "row")
  public String addTableRow(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "table_index", description = "表格索引(0 起)")
          @ParamCapability(type = ParamType.INTEGER)
          int tableIndex) {
    Document doc = document(docId);
    if (doc == null) {
      return ToolResultRenderer.render(docNotFoundResult(docId));
    }
    var tables = doc.tables();
    if (outOfBounds(tableIndex, tables.size())) {
      return ToolResultRenderer.render(indexErrorResult("表格索引", tableIndex, tables.size()));
    }
    try {
      tables.get(tableIndex).addRow();
    } catch (RuntimeException e) {
      return ToolResultRenderer.render(
          ToolResult.fail(ToolResultCode.UNSUPPORTED_FEATURE, "错误:" + rootMessage(e)));
    }
    int newRow = tables.get(tableIndex).rows().size() - 1;
    return ToolResultRenderer.render(
        ToolResult.ok("已追加空行,新行索引 " + newRow + "(表格 " + tableIndex + ")"));
  }

  /**
   * 删除指定表格的某行(活对象直写,需 save_docx 落盘)。
   *
   * <p><b>索引漂移提醒</b>:删行后<b>后续行的索引会前移</b>——例如删第 0 行后,原第 1 行变成第 0 行。 批量删行时建议从大到小删(或删后重新读行数),避免索引错位。
   */
  @ToolDef(
      name = "remove_table_row",
      description =
          "删除指定表格的某行(改完需 save_docx 落盘)。参数:table_index(int,表格索引 0 起)、row_index(int,行索引 0 起)。"
              + "注意:删行后后续行索引前移,批量删建议从大到小删或删后重读行数。"
              + "可选 expected_generation 防止旧快照修改新状态。返回含 index_shifted warning。")
  @ToolCapability(operation = CapabilityOperation.REMOVE, element = "row")
  public String removeTableRow(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "table_index", description = "表格索引(0 起)")
          @ParamCapability(type = ParamType.INTEGER)
          int tableIndex,
      @ToolParam(name = "row_index", description = "行索引(0 起)")
          @ParamCapability(type = ParamType.INTEGER)
          int rowIndex,
      @ToolParam(
              name = "expected_generation",
              description = "可选。调用方持有的 session generation,与当前不符则拒绝写入。不传则跳过校验。",
              required = false)
          @ParamCapability(type = ParamType.INTEGER)
          Integer expectedGeneration) {
    Document doc = document(docId);
    if (doc == null) {
      return ToolResultRenderer.render(docNotFoundResult(docId));
    }
    if (!checkExpectedGeneration(docId, expectedGeneration)) {
      return renderGenerationMismatch(expectedGeneration, generations.getOrDefault(docId, 1L));
    }
    var tables = doc.tables();
    if (outOfBounds(tableIndex, tables.size())) {
      return ToolResultRenderer.render(indexErrorResult("表格索引", tableIndex, tables.size()));
    }
    var rows = tables.get(tableIndex).rows();
    if (outOfBounds(rowIndex, rows.size())) {
      return ToolResultRenderer.render(indexErrorResult("行索引", rowIndex, rows.size()));
    }
    try {
      tables.get(tableIndex).removeRow(rowIndex);
    } catch (RuntimeException e) {
      return ToolResultRenderer.render(
          ToolResult.fail(ToolResultCode.UNSUPPORTED_FEATURE, "错误:" + rootMessage(e)));
    }
    int remain = tables.get(tableIndex).rows().size();
    ToolResult<Void> result =
        ToolResult.ok("已删除表格 " + tableIndex + " 第 " + rowIndex + " 行,剩余 " + remain + " 行(后续行索引已前移)")
            .withWarning(ToolWarning.of("index_shifted", "删除行后该表后续行索引前移,批量删除请从大到小执行或删后重读行数"));
    return ToolResultRenderer.render(result);
  }

  /**
   * 在指定行末尾追加一个空单元格(活对象直写,需 save_docx 落盘)。返回新单元格索引。
   *
   * <p>新单元格是空的(无段落),可用 {@code replace_table_cell_run_text} 等填充内容前先确保至少有一个段落—— POI 的 cell 通常默认含一段,但
   * nondocx 的 {@code Row.addCell()} 已剥离预填段落。 若单元格无段落,写 run 会失败;建议先 {@code read_table_cell} 确认结构或用
   * {@code create_table} 直接建表。
   */
  @ToolDef(
      name = "add_table_cell",
      description =
          "在指定行末尾追加一个空单元格(改完需 save_docx 落盘)。返回新单元格索引。"
              + "参数:table_index(int)、row_index(int)。新单元格是空的,需后续填充内容(可配合 add_table_row 先加空行再加单元格)。")
  @ToolCapability(operation = CapabilityOperation.ADD, element = "cell")
  public String addTableCell(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "table_index", description = "表格索引(0 起)")
          @ParamCapability(type = ParamType.INTEGER)
          int tableIndex,
      @ToolParam(name = "row_index", description = "行索引(0 起)")
          @ParamCapability(type = ParamType.INTEGER)
          int rowIndex) {
    Document doc = document(docId);
    if (doc == null) {
      return ToolResultRenderer.render(docNotFoundResult(docId));
    }
    var tables = doc.tables();
    if (outOfBounds(tableIndex, tables.size())) {
      return ToolResultRenderer.render(indexErrorResult("表格索引", tableIndex, tables.size()));
    }
    var rows = tables.get(tableIndex).rows();
    if (outOfBounds(rowIndex, rows.size())) {
      return ToolResultRenderer.render(indexErrorResult("行索引", rowIndex, rows.size()));
    }
    Row row = rows.get(rowIndex);
    try {
      row.addCell();
    } catch (RuntimeException e) {
      return ToolResultRenderer.render(
          ToolResult.fail(ToolResultCode.UNSUPPORTED_FEATURE, "错误:" + rootMessage(e)));
    }
    int newCell = rows.get(rowIndex).cells().size() - 1;
    return ToolResultRenderer.render(
        ToolResult.ok("已追加空单元格,新单元格索引 " + newCell + "(表格 " + tableIndex + " 行 " + rowIndex + ")"));
  }

  /**
   * 删除指定单元格(活对象直写,需 save_docx 落盘)。
   *
   * <p><b>索引漂移提醒</b>:删单元格后同行的<b>后续单元格索引前移</b>——与 {@code remove_table_row} 同类提醒。
   */
  @ToolDef(
      name = "remove_table_cell",
      description =
          "删除指定单元格(改完需 save_docx 落盘)。参数:table_index(int)、row_index(int)、cell_index(int,单元格索引 0 起)。"
              + "注意:删单元格后同行后续单元格索引前移。"
              + "可选 expected_generation 防止旧快照修改新状态。返回含 index_shifted warning。")
  @ToolCapability(operation = CapabilityOperation.REMOVE, element = "cell")
  public String removeTableCell(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "table_index", description = "表格索引(0 起)")
          @ParamCapability(type = ParamType.INTEGER)
          int tableIndex,
      @ToolParam(name = "row_index", description = "行索引(0 起)")
          @ParamCapability(type = ParamType.INTEGER)
          int rowIndex,
      @ToolParam(name = "cell_index", description = "单元格索引(0 起)")
          @ParamCapability(type = ParamType.INTEGER)
          int cellIndex,
      @ToolParam(
              name = "expected_generation",
              description = "可选。调用方持有的 session generation,与当前不符则拒绝写入。不传则跳过校验。",
              required = false)
          @ParamCapability(type = ParamType.INTEGER)
          Integer expectedGeneration) {
    Document doc = document(docId);
    if (doc == null) {
      return ToolResultRenderer.render(docNotFoundResult(docId));
    }
    if (!checkExpectedGeneration(docId, expectedGeneration)) {
      return renderGenerationMismatch(expectedGeneration, generations.getOrDefault(docId, 1L));
    }
    ToolResult<Void> locate = locateCellResult(doc, tableIndex, rowIndex, cellIndex);
    if (!locate.success()) {
      return ToolResultRenderer.render(locate);
    }
    Row row = tablesRow(doc, tableIndex, rowIndex);
    try {
      row.removeCell(cellIndex);
    } catch (RuntimeException e) {
      return ToolResultRenderer.render(
          ToolResult.fail(ToolResultCode.UNSUPPORTED_FEATURE, "错误:" + rootMessage(e)));
    }
    int remain = tablesRow(doc, tableIndex, rowIndex).cells().size();
    ToolResult<Void> result =
        ToolResult.ok(
                "已删除表格 "
                    + tableIndex
                    + " 行 "
                    + rowIndex
                    + " 第 "
                    + cellIndex
                    + " 单元格,剩余 "
                    + remain
                    + " 个(后续单元格索引已前移)")
            .withWarning(ToolWarning.of("index_shifted", "删除单元格后同行后续单元格索引前移,批量删除请从大到小执行或删后重读行数"));
    return ToolResultRenderer.render(result);
  }

  /**
   * 批量设单元格内段落的标题级别(活对象直写,需 save_docx 落盘)。
   *
   * <p><b>OOXML → POI → nondocx 三层</b>:标题级别写段落属性 {@code <w:pStyle>} 为 {@code Heading1..6}。nondocx 的
   * {@code Paragraph.heading(HeadingLevel)} 收口。清除标题用独立字段 {@code clear=true}(调 {@code
   * clearHeading()}),不依赖不存在的枚举值。
   */
  @ToolDef(
      name = "update_table_cell_paragraph_heading",
      description =
          "批量设单元格内段落的标题级别(改完需 save_docx 落盘)。edits 是对象数组,每个对象含 "
              + "table_index(int)、row_index(int)、cell_index(int)、paragraph_index(int,单元格内段落 0 起),"
              + "以及 heading(string,H1/H2/H3/H4/H5/H6,大小写不敏感)。"
              + "清除标题用 clear=true(bool,此时忽略 heading)。单个对象用长度 1 的数组。部分失败不中断。")
  @ToolCapability(operation = CapabilityOperation.UPDATE, element = "paragraph")
  public String updateTableCellParagraphHeading(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(
              name = "edits",
              description =
                  "对象数组,每个对象含 table_index、row_index、cell_index、paragraph_index(int)"
                      + "与 heading(string)或 clear(bool),如 [{\"table_index\":0,\"row_index\":0,\"cell_index\":0,"
                      + "\"paragraph_index\":0,\"heading\":\"H2\"}]")
          @NestedParamCapability(path = "edits.table_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.row_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.cell_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.paragraph_index", type = ParamType.INTEGER)
          @NestedParamCapability(
              path = "edits.heading",
              type = ParamType.ENUM,
              enumValues = {"H1", "H2", "H3", "H4", "H5", "H6"})
          @NestedParamCapability(path = "edits.clear", type = ParamType.BOOLEAN)
          List<Map<String, Object>> edits) {
    Document doc = document(docId);
    if (doc == null) {
      return ToolResultRenderer.render(docNotFoundResult(docId));
    }
    return ToolResultRenderer.render(
        applyCellParagraphEditResults(
            doc,
            edits,
            false,
            (m, p) -> {
              boolean clear = boolVal(m.get("clear"));
              if (clear) {
                p.clearHeading();
                return ToolResult.ok("段落标题已清除 ✓");
              }
              HeadingLevel hl = parseHeading(getStr(m, "heading"));
              p.heading(hl);
              return ToolResult.ok("段落标题 → " + hl + " ✓");
            }));
  }

  /**
   * 批量设单元格内段落缩进(活对象直写,需 save_docx 落盘)。
   *
   * <p><b>OOXML → POI → nondocx 三层</b>:缩进在段落属性 {@code <w:ind w:left=.. w:firstLine=..>}(twips,1
   * twip = 1/20 点)。 nondocx 的 {@code Paragraph.indent(int, int)} 收口。
   */
  @ToolDef(
      name = "update_table_cell_paragraph_indent",
      description =
          "批量设单元格内段落缩进(改完需 save_docx 落盘)。edits 是对象数组,每个对象含 "
              + "table_index(int)、row_index(int)、cell_index(int)、paragraph_index(int),"
              + "以及 left_twips(int,左缩进)、first_line_twips(int,首行缩进,可负数表示悬挂缩进)。单位 twips(1 twip = 1/20 点)。"
              + "单个对象用长度 1 的数组。部分失败不中断。")
  @ToolCapability(operation = CapabilityOperation.UPDATE, element = "paragraph")
  public String updateTableCellParagraphIndent(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(
              name = "edits",
              description =
                  "对象数组,每个对象含 table_index、row_index、cell_index、paragraph_index(int)"
                      + "与 left_twips、first_line_twips(int),如 [{\"table_index\":0,\"row_index\":0,\"cell_index\":0,"
                      + "\"paragraph_index\":0,\"left_twips\":720,\"first_line_twips\":360}]")
          @NestedParamCapability(path = "edits.table_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.row_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.cell_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.paragraph_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.left_twips", type = ParamType.INTEGER, unit = "twip")
          @NestedParamCapability(
              path = "edits.first_line_twips",
              type = ParamType.INTEGER,
              unit = "twip")
          List<Map<String, Object>> edits) {
    Document doc = document(docId);
    if (doc == null) {
      return ToolResultRenderer.render(docNotFoundResult(docId));
    }
    return ToolResultRenderer.render(
        applyCellParagraphEditResults(
            doc,
            edits,
            false,
            (m, p) -> {
              int left = getInt(m, "left_twips");
              int firstLine = getInt(m, "first_line_twips");
              p.indent(left, firstLine);
              return ToolResult.ok("段落缩进 → left=" + left + " firstLine=" + firstLine + " ✓");
            }));
  }

  /**
   * 批量设单元格内段落行距(活对象直写,需 save_docx 落盘)。
   *
   * <p><b>OOXML → POI → nondocx 三层</b>:行距在段落属性 {@code <w:spacing>},以单倍行高的倍数表示(1.0 单倍、1.5 一倍半、2.0
   * 双倍)。 nondocx 的 {@code Paragraph.lineSpacing(double)} 收口。
   */
  @ToolDef(
      name = "update_table_cell_paragraph_spacing",
      description =
          "批量设单元格内段落行距(改完需 save_docx 落盘)。edits 是对象数组,每个对象含 "
              + "table_index(int)、row_index(int)、cell_index(int)、paragraph_index(int)、line_spacing(number,单倍行高的倍数,如 1.5)。"
              + "单个对象用长度 1 的数组。部分失败不中断。")
  @ToolCapability(operation = CapabilityOperation.UPDATE, element = "paragraph")
  public String updateTableCellParagraphSpacing(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(
              name = "edits",
              description =
                  "对象数组,每个对象含 table_index、row_index、cell_index、paragraph_index(int)"
                      + "与 line_spacing(number),如 [{\"table_index\":0,\"row_index\":0,\"cell_index\":0,"
                      + "\"paragraph_index\":0,\"line_spacing\":1.5}]")
          @NestedParamCapability(path = "edits.table_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.row_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.cell_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.paragraph_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.line_spacing", type = ParamType.NUMBER)
          List<Map<String, Object>> edits) {
    Document doc = document(docId);
    if (doc == null) {
      return ToolResultRenderer.render(docNotFoundResult(docId));
    }
    return ToolResultRenderer.render(
        applyCellParagraphEditResults(
            doc,
            edits,
            false,
            (m, p) -> {
              double ls = getDouble(m, "line_spacing");
              p.lineSpacing(ls);
              return ToolResult.ok("段落行距 → " + ls + " ✓");
            }));
  }

  /**
   * 批量设单元格内段落的列表成员(活对象直写,需 save_docx 落盘)。
   *
   * <p><b>OOXML → POI → nondocx 三层</b>:列表成员走 numbering,段落属性 {@code <w:numPr>}(numId + ilvl)。nondocx
   * 的 {@code Paragraph.list(ListKind, int)} 收口。清除列表用独立字段 {@code clear=true}(调 {@code clearList()},用
   * unsetNumPr 而非 setNumID(null),见 poi-bridge.md N4)。
   */
  @ToolDef(
      name = "update_table_cell_paragraph_list",
      description =
          "批量设单元格内段落的列表成员(改完需 save_docx 落盘)。edits 是对象数组,每个对象含 "
              + "table_index(int)、row_index(int)、cell_index(int)、paragraph_index(int),"
              + "以及 list_kind(string,BULLET/NUMBERED)与 level(int,0-8 嵌套层级)。"
              + "清除列表用 clear=true(bool,此时忽略 list_kind/level)。单个对象用长度 1 的数组。部分失败不中断。")
  @ToolCapability(operation = CapabilityOperation.UPDATE, element = "paragraph")
  public String updateTableCellParagraphList(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(
              name = "edits",
              description =
                  "对象数组,每个对象含 table_index、row_index、cell_index、paragraph_index(int)"
                      + "与 list_kind(string)、level(int)或 clear(bool),如 [{\"table_index\":0,\"row_index\":0,\"cell_index\":0,"
                      + "\"paragraph_index\":0,\"list_kind\":\"BULLET\",\"level\":0}]")
          @NestedParamCapability(path = "edits.table_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.row_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.cell_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.paragraph_index", type = ParamType.INTEGER)
          @NestedParamCapability(
              path = "edits.list_kind",
              type = ParamType.ENUM,
              enumValues = {"BULLET", "NUMBERED"})
          @NestedParamCapability(path = "edits.level", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.clear", type = ParamType.BOOLEAN)
          List<Map<String, Object>> edits) {
    Document doc = document(docId);
    if (doc == null) {
      return ToolResultRenderer.render(docNotFoundResult(docId));
    }
    return ToolResultRenderer.render(
        applyCellParagraphEditResults(
            doc,
            edits,
            false,
            (m, p) -> {
              boolean clear = boolVal(m.get("clear"));
              if (clear) {
                p.clearList();
                return ToolResult.ok("段落列表已清除 ✓");
              }
              ListKind kind = parseListKind(getStr(m, "list_kind"));
              int level = getInt(m, "level");
              p.list(kind, level);
              return ToolResult.ok("段落列表 → " + kind + "(level " + level + ") ✓");
            }));
  }

  /**
   * 批量设单元格内段落底纹(活对象直写,需 save_docx 落盘)。
   *
   * <p><b>OOXML → POI → nondocx 三层</b>:段落底纹在 {@code <w:pPr>/<w:shd>},与单元格底纹同元素但位置不同。nondocx 的
   * {@code Paragraph.shading(String)} 同样强制 {@code w:val="clear"},跨引擎安全。
   */
  @ToolDef(
      name = "update_table_cell_paragraph_shading",
      description =
          "批量设单元格内段落底纹(改完需 save_docx 落盘)。edits 是对象数组,每个对象含 "
              + "table_index(int)、row_index(int)、cell_index(int)、paragraph_index(int)、fill(string,十六进制 RGB 如 F1F5F9,不带 #)。"
              + "强制纯色填充(跨 Word/WPS 安全)。单个对象用长度 1 的数组。部分失败不中断。")
  @ToolCapability(operation = CapabilityOperation.UPDATE, element = "paragraph")
  public String updateTableCellParagraphShading(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(
              name = "edits",
              description =
                  "对象数组,每个对象含 table_index、row_index、cell_index、paragraph_index(int)"
                      + "与 fill(string),如 [{\"table_index\":0,\"row_index\":0,\"cell_index\":0,"
                      + "\"paragraph_index\":0,\"fill\":\"F1F5F9\"}]")
          @NestedParamCapability(path = "edits.table_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.row_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.cell_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.paragraph_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.fill", type = ParamType.STRING)
          List<Map<String, Object>> edits) {
    Document doc = document(docId);
    if (doc == null) {
      return ToolResultRenderer.render(docNotFoundResult(docId));
    }
    return ToolResultRenderer.render(
        applyCellParagraphEditResults(
            doc,
            edits,
            false,
            (m, p) -> {
              String fill = getStr(m, "fill");
              p.shading(fill);
              return ToolResult.ok("段落底纹 → " + fill + " ✓");
            }));
  }

  // ==================== 段落级写工具共享:批量循环骨架 ====================

  /** 取表格某行的活 Row(已通过边界检查)。 */
  private static Row tablesRow(Document doc, int tableIndex, int rowIndex) {
    return doc.tables().get(tableIndex).rows().get(rowIndex);
  }

  /** 解析 heading 字符串(大小写不敏感)。 */
  private static HeadingLevel parseHeading(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("heading 不能为空(或用 clear=true 清除标题)");
    }
    try {
      return HeadingLevel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("heading 仅支持 H1/H2/H3/H4/H5/H6:" + raw);
    }
  }

  /** 解析 list_kind 字符串(大小写不敏感)。 */
  private static ListKind parseListKind(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("list_kind 不能为空(或用 clear=true 清除列表)");
    }
    try {
      return ListKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("list_kind 仅支持 BULLET/NUMBERED:" + raw);
    }
  }

  /** 从 Map 取一个 double 字段(兼容 Integer/Long/Double)。 */
  private static double getDouble(Map<String, Object> m, String key) {
    Object v = m.get(key);
    if (v instanceof Number) {
      return ((Number) v).doubleValue();
    }
    if (v instanceof String) {
      try {
        return Double.parseDouble(((String) v).trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("字段 " + key + " 不是合法数值:\"" + v + "\"");
      }
    }
    if (v == null) {
      throw new IllegalArgumentException("缺少必填字段 " + key);
    }
    throw new IllegalArgumentException("字段 " + key + " 不是数值:" + v);
  }

  /** 兼容旧 Java 调用；等价于 on_error=continue 且未传 expected_generation。 */
  @Deprecated
  public String updateTableCellShading(String docId, List<Map<String, Object>> edits) {
    return updateTableCellShading(docId, edits, null, null);
  }

  /** 兼容旧 Java 调用；等价于 on_error=continue 且未传 expected_generation。 */
  @Deprecated
  public String updateTableCellVerticalAlign(String docId, List<Map<String, Object>> edits) {
    return updateTableCellVerticalAlign(docId, edits, null, null);
  }

  /** 兼容旧 Java 调用；等价于 on_error=continue 且未传 expected_generation。 */
  @Deprecated
  public String updateTableHeaderRow(String docId, List<Map<String, Object>> edits) {
    return updateTableHeaderRow(docId, edits, null, null);
  }

  /** 兼容旧 Java 调用；等价于 on_error=continue 且未传 expected_generation。 */
  @Deprecated
  public String updateTableRowCantSplit(String docId, List<Map<String, Object>> edits) {
    return updateTableRowCantSplit(docId, edits, null, null);
  }

  /** 兼容旧 Java 调用；等价于未传 expected_generation。 */
  @Deprecated
  public String removeTableRow(String docId, int tableIndex, int rowIndex) {
    return removeTableRow(docId, tableIndex, rowIndex, null);
  }

  /** 兼容旧 Java 调用；等价于未传 expected_generation。 */
  @Deprecated
  public String removeTableCell(String docId, int tableIndex, int rowIndex, int cellIndex) {
    return removeTableCell(docId, tableIndex, rowIndex, cellIndex, null);
  }
}
