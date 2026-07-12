package com.non.docx.toolkit.orchestration.table;

import com.non.docx.toolkit.TableTools;
import com.non.docx.toolkit.orchestration.ConflictKey;
import com.non.docx.toolkit.orchestration.Operation;
import com.non.docx.toolkit.orchestration.commit.OperationExecutionException;
import com.non.docx.toolkit.orchestration.commit.OperationExecutor;
import com.non.docx.toolkit.orchestration.session.OrchestratorSession;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表格工具组的 {@link OperationExecutor}：把 table 域的 {@link Operation} 落到 {@link TableTools} 调用上。
 *
 * <p><b>OOXML 三层递进（表格 operation 映射）：</b>
 *
 * <ul>
 *   <li><b>OOXML</b>：表格是 {@code <w:tbl>}，行是 {@code <w:tr>}，单元格是 {@code <w:tc>}；单元格内仍有 段落 {@code
 *       <w:p>} 和 run {@code <w:r>}，结构上和正文同构。
 *   <li><b>POI</b>：{@code XWPFTable.getRows().get(r).getCell(c)} 给单元格，单元格再 {@code getParagraphs()}
 *       取段落、{@code getRuns()} 取 run。{@code TableTools} 把这些封装成 {@code (table,row,cell,para,run)}
 *       五元组批量 edit。
 *   <li><b>nondocx</b>：本执行器把单条 operation 的 payload 包装成单元素 edit 数组，调用 {@code TableTools} 的批量方法。
 * </ul>
 *
 * <p><b>支持的 operation kind：</b>
 *
 * <ul>
 *   <li>{@code replace_table_cell_run_text}——替换单元格 run 文本。
 *   <li>{@code update_table_cell_shading}——设单元格底纹。
 *   <li>{@code update_table_cell_run_style}——改单元格 run 样式。
 *   <li>{@code merge_table_cells}——合并单元格（HORIZONTAL/VERTICAL）。
 *   <li>{@code set_table_borders}——设置表格边框（当前仅 NONE/无边框）。
 * </ul>
 */
public final class TableExecutor implements OperationExecutor {

  private final TableTools table;

  public TableExecutor(TableTools table) {
    this.table = table;
  }

  @Override
  public boolean canHandle(Operation operation) {
    return "table".equals(operation.toolGroup());
  }

  @Override
  public String execute(OrchestratorSession session, Operation operation)
      throws OperationExecutionException {
    String docId = session.docId();
    Map<String, Object> payload = new LinkedHashMap<>(operation.payload());
    String kind = operation.kind();
    try {
      switch (kind) {
        case "replace_table_cell_run_text":
          {
            Map<String, Object> edit =
                pickedEdit(
                    payload,
                    "table_index",
                    "row_index",
                    "cell_index",
                    "paragraph_index",
                    "run_index",
                    "text");
            String result = table.replaceTableCellRunText(docId, List.of(edit));
            return checkResult(result, operation);
          }
        case "update_table_cell_shading":
          {
            Map<String, Object> edit =
                pickedEdit(payload, "table_index", "row_index", "cell_index", "fill");
            String result = table.updateTableCellShading(docId, List.of(edit));
            return checkResult(result, operation);
          }
        case "update_table_cell_run_style":
          {
            Map<String, Object> edit =
                pickedEdit(
                    payload,
                    "table_index",
                    "row_index",
                    "cell_index",
                    "paragraph_index",
                    "run_index",
                    "bold",
                    "italic",
                    "underline",
                    "font",
                    "font_size",
                    "color");
            String result = table.updateTableCellRunStyle(docId, List.of(edit));
            return checkResult(result, operation);
          }
        case "merge_table_cells":
          {
            Map<String, Object> edit = normalizeMergePayload(payload);
            // merge_table_cells 的批量参数是 merges 数组，单条操作包成单元素数组
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object>[] merges = new java.util.Map[] {edit};
            String result = table.mergeTableCells(docId, merges);
            return checkResult(result, operation);
          }
        case "set_table_borders":
          {
            // setTableBorders 是 (docId, int tableIndex, String borderStyle) 签名，
            // 不是 List<Map> 批量模式，直接从 payload 取字段
            int tableIdx = intPayload(payload, "table_index");
            String borderStyle = strPayload(payload, "border_style");
            String result = table.setTableBorders(docId, tableIdx, borderStyle);
            return checkResult(result, operation);
          }
        default:
          throw new OperationExecutionException("table 域不支持的 operation kind: " + kind);
      }
    } catch (OperationExecutionException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new OperationExecutionException("table/" + kind + " 执行异常", e);
    }
  }

  private static Map<String, Object> pickedEdit(Map<String, Object> payload, String... keys) {
    Map<String, Object> edit = new LinkedHashMap<>();
    for (String k : keys) {
      if (payload.containsKey(k)) {
        edit.put(k, payload.get(k));
      }
    }
    return edit;
  }

  private static int intPayload(Map<String, Object> payload, String key)
      throws OperationExecutionException {
    Object v = payload.get(key);
    if (v == null) {
      throw new OperationExecutionException("缺少必填字段 " + key);
    }
    if (v instanceof Number) return ((Number) v).intValue();
    try {
      return Integer.parseInt(String.valueOf(v).trim());
    } catch (NumberFormatException e) {
      throw new OperationExecutionException("字段 " + key + " 不是合法整数:\"" + v + "\"");
    }
  }

  private static String strPayload(Map<String, Object> payload, String key)
      throws OperationExecutionException {
    Object v = payload.get(key);
    if (v == null) {
      throw new OperationExecutionException("缺少必填字段 " + key);
    }
    return String.valueOf(v);
  }

  /**
   * 把 LLM 产出的 merge payload 归一化为 {@link TableTools#mergeTableCells} 要求的字段名。
   *
   * <p><b>OOXML 背景。</b> 合并单元格有两种方向：横向（同一行内多列合并，用 {@code gridSpan}） 和纵向（同一列内多行合并，用 {@code
   * vMerge}）。{@link TableTools#mergeTableCells} 要求 payload 带 {@code direction} 字段
   * (HORIZONTAL/VERTICAL) + 对应方向的坐标字段。
   *
   * <p><b>LLM 常见偏差与容错。</b>
   *
   * <ul>
   *   <li>字段名偏差：LLM 常产出 {@code start_row_index}/{@code end_row_index}（直觉命名），而 TableTools 要求 {@code
   *       from_row_index}/{@code to_row_index}。本方法做翻译。
   *   <li>缺少 direction：LLM 常省略 direction 字段。本方法根据 payload 里有哪些坐标字段来推断： 有 {@code cell_index} + 行范围 →
   *       VERTICAL；有 {@code row_index} + cell 范围 → HORIZONTAL。
   * </ul>
   */
  private static Map<String, Object> normalizeMergePayload(Map<String, Object> payload) {
    Map<String, Object> m = new LinkedHashMap<>(payload);

    // 字段名容错：start_*/end_* → from_*/to_*（四个方向都覆盖）
    renameKey(m, "start_row_index", "from_row_index");
    renameKey(m, "end_row_index", "to_row_index");
    renameKey(m, "start_cell_index", "from_cell_index");
    renameKey(m, "end_cell_index", "to_cell_index");

    // direction 推断（仅当 LLM 没给 direction 时）
    if (!m.containsKey("direction")) {
      if (m.containsKey("from_row_index") || m.containsKey("to_row_index")) {
        m.put("direction", "VERTICAL");
      } else if (m.containsKey("from_cell_index") || m.containsKey("to_cell_index")) {
        m.put("direction", "HORIZONTAL");
      }
    }
    return m;
  }

  /** 若 src 键存在而 dst 不存在，把 src 重命名为 dst（原地修改 map）。 */
  private static void renameKey(Map<String, Object> m, String src, String dst) {
    if (m.containsKey(src) && !m.containsKey(dst)) {
      m.put(dst, m.remove(src));
    }
  }

  /**
   * 检查 TableTools 返回串是否表示执行失败。
   *
   * <p>委托 {@link com.non.docx.toolkit.orchestration.commit.ToolResultChecks#checkResult}，
   * 双模式：优先解析结构化 envelope，回退旧中文前缀（混合期，切片 8 移除）。
   */
  private static String checkResult(String result, Operation operation)
      throws OperationExecutionException {
    return com.non.docx.toolkit.orchestration.commit.ToolResultChecks.checkResult(
        result, "table", operation.kind());
  }

  // ==================== Operation 构造便捷方法 ====================

  /** 构造一条 replace_table_cell_run_text operation。 */
  public static Operation replaceCellRunText(
      String opId,
      int tableIndex,
      int rowIndex,
      int cellIndex,
      int paragraphIndex,
      int runIndex,
      String text,
      String intent) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("table_index", tableIndex);
    payload.put("row_index", rowIndex);
    payload.put("cell_index", cellIndex);
    payload.put("paragraph_index", paragraphIndex);
    payload.put("run_index", runIndex);
    payload.put("text", text);
    String target = cellRef(tableIndex, rowIndex, cellIndex);
    return Operation.of(
        opId,
        "table",
        "replace_table_cell_run_text",
        target,
        payload,
        new ConflictKey("table", "replace_table_cell_run_text", target),
        intent,
        "替换单元格 run 文本",
        "");
  }

  /** 构造一条 update_table_cell_shading operation。 */
  public static Operation updateCellShading(
      String opId, int tableIndex, int rowIndex, int cellIndex, String fill, String intent) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("table_index", tableIndex);
    payload.put("row_index", rowIndex);
    payload.put("cell_index", cellIndex);
    payload.put("fill", fill);
    String target = cellRef(tableIndex, rowIndex, cellIndex);
    return Operation.of(
        opId,
        "table",
        "update_table_cell_shading",
        target,
        payload,
        new ConflictKey("table", "update_table_cell_shading", target),
        intent,
        "设单元格底纹",
        "");
  }

  /** 构造一条纵向合并单元格 operation（同一列内多行合并）。 */
  public static Operation mergeCellsVertical(
      String opId, int tableIndex, int cellIndex, int fromRowIndex, int toRowIndex, String intent) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("table_index", tableIndex);
    payload.put("direction", "VERTICAL");
    payload.put("cell_index", cellIndex);
    payload.put("from_row_index", fromRowIndex);
    payload.put("to_row_index", toRowIndex);
    String target =
        "t:" + tableIndex + "/c:" + cellIndex + "/r:" + fromRowIndex + ".." + toRowIndex;
    return Operation.of(
        opId,
        "table",
        "merge_table_cells",
        target,
        payload,
        new ConflictKey("table", "merge_table_cells", target),
        intent,
        "纵向合并单元格",
        "");
  }

  /** 构造一条横向合并单元格 operation（同一行内多列合并）。 */
  public static Operation mergeCellsHorizontal(
      String opId,
      int tableIndex,
      int rowIndex,
      int fromCellIndex,
      int toCellIndex,
      String intent) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("table_index", tableIndex);
    payload.put("direction", "HORIZONTAL");
    payload.put("row_index", rowIndex);
    payload.put("from_cell_index", fromCellIndex);
    payload.put("to_cell_index", toCellIndex);
    String target =
        "t:" + tableIndex + "/r:" + rowIndex + "/c:" + fromCellIndex + ".." + toCellIndex;
    return Operation.of(
        opId,
        "table",
        "merge_table_cells",
        target,
        payload,
        new ConflictKey("table", "merge_table_cells", target),
        intent,
        "横向合并单元格",
        "");
  }

  /** 构造一条 set_table_borders operation（设置表格边框）。 */
  public static Operation setTableBorders(
      String opId, int tableIndex, String borderStyle, String intent) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("table_index", tableIndex);
    payload.put("border_style", borderStyle);
    String target = "t:" + tableIndex + "/borders";
    return Operation.of(
        opId,
        "table",
        "set_table_borders",
        target,
        payload,
        new ConflictKey("table", "set_table_borders", target),
        intent,
        "设表格边框",
        "");
  }

  static String cellRef(int tableIndex, int rowIndex, int cellIndex) {
    return "t:" + tableIndex + "/r:" + rowIndex + "/c:" + cellIndex;
  }
}
