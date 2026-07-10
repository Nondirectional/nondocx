package com.non.docx.demo;

import com.non.docx.toolkit.orchestration.Operation;
import java.util.Map;

/**
 * 把 {@link Operation} 的技术标识（kind + payload）翻译成面向用户的人话描述。
 *
 * <p>替代旧渲染里直接暴露的 {@code body/insert_heading@heading:1}——用户看不懂这种内部路径。 本类按 kind 分发，从 payload
 * 提取具体内容（text/heading_level/alignment 等），拼成人话。
 *
 * <p><b>示例映射：</b>
 *
 * <ul>
 *   <li>{@code insert_heading} + {text=项目周报, heading_level=H1, alignment=CENTER} → 「插入 H1
 *       标题「项目周报」，居中」
 *   <li>{@code replace_run_text} + {text=Hello} → 「替换文字为「Hello」」
 *   <li>{@code replace_table_cell_run_text} + {text=完成, table_index=0, row_index=1, cell_index=2} →
 *       「表格(0,1,2)改为「完成」」
 * </ul>
 *
 * <p>未知 kind 降级为 operation 的 intent 字段（LLM 产出的人话意图）；intent 也为空时降级为 kind 名。
 */
final class OperationDescriptor {

  private OperationDescriptor() {}

  /**
   * 生成 operation 的人话描述。
   *
   * @param op 操作
   * @return 面向用户的描述串
   */
  static String describe(Operation op) {
    Map<String, Object> p = op.payload();
    switch (op.kind()) {
      case "insert_heading":
        return describeInsertHeading(p);
      case "insert_paragraph":
        return "插入段落「" + str(p, "text") + "」";
      case "replace_run_text":
        return "替换文字为「" + str(p, "text") + "」";
      case "update_run_style":
        return describeUpdateRunStyle(p);
      case "update_paragraph_alignment":
        return "设置" + translateAlignment(str(p, "alignment")) + "对齐";
      case "replace_table_cell_run_text":
        return describeTableCellText(p);
      case "update_table_cell_shading":
        return "表格("
            + intStr(p, "table_index")
            + ","
            + intStr(p, "row_index")
            + ","
            + intStr(p, "cell_index")
            + ")设底纹 "
            + str(p, "fill");
      case "merge_table_cells":
        return describeMergeCells(p);
      case "set_table_borders":
        return describeSetTableBorders(p);
      case "check_quality":
        return "质量检查";
      case "read_header_toc":
        return "读取页眉页脚与目录";
      case "apply_revision":
        return translateAction(str(p, "action")) + "修订";
      default:
        // 未知 kind：降级到 intent，再降级到 kind 名
        String intent = op.intent();
        return (intent != null && !intent.isBlank()) ? intent : op.kind();
    }
  }

  private static String describeInsertHeading(Map<String, Object> p) {
    StringBuilder sb = new StringBuilder("插入 ");
    String level = str(p, "heading_level");
    if (!level.isEmpty()) {
      sb.append(translateHeadingLevel(level)).append(" ");
    }
    sb.append("标题「").append(str(p, "text")).append("」");
    String alignment = str(p, "alignment");
    if (!alignment.isEmpty()) {
      sb.append("，").append(translateAlignment(alignment));
    }
    if (p.containsKey("font_size")) {
      sb.append("，").append(intStr(p, "font_size")).append("pt");
    }
    return sb.toString();
  }

  private static String describeUpdateRunStyle(Map<String, Object> p) {
    StringBuilder sb = new StringBuilder("修改样式");
    java.util.List<String> parts = new java.util.ArrayList<>();
    if (boolVal(p.get("bold"))) parts.add("加粗");
    if (boolVal(p.get("italic"))) parts.add("斜体");
    if (boolVal(p.get("underline"))) parts.add("下划线");
    if (p.containsKey("font_size")) parts.add("字号 " + intStr(p, "font_size"));
    if (p.containsKey("color")) parts.add("颜色 " + str(p, "color"));
    if (!parts.isEmpty()) {
      sb.append("：").append(String.join("，", parts));
    }
    return sb.toString();
  }

  private static String describeTableCellText(Map<String, Object> p) {
    return "表格("
        + intStr(p, "table_index")
        + ","
        + intStr(p, "row_index")
        + ","
        + intStr(p, "cell_index")
        + ")改为「"
        + str(p, "text")
        + "」";
  }

  /** 合并单元格的人话描述：区分纵向/横向，显示坐标范围。 */
  private static String describeMergeCells(Map<String, Object> p) {
    String direction = str(p, "direction").toUpperCase(java.util.Locale.ROOT);
    String tableIdx = intStr(p, "table_index");
    if ("VERTICAL".equals(direction)) {
      return "表格("
          + tableIdx
          + ") 纵向合并 第"
          + intStr(p, "from_row_index")
          + "~"
          + intStr(p, "to_row_index")
          + "行（列"
          + intStr(p, "cell_index")
          + "）";
    }
    if ("HORIZONTAL".equals(direction)) {
      return "表格("
          + tableIdx
          + ") 横向合并 第"
          + intStr(p, "from_cell_index")
          + "~"
          + intStr(p, "to_cell_index")
          + "列（行"
          + intStr(p, "row_index")
          + "）";
    }
    return "表格(" + tableIdx + ") 合并单元格";
  }

  /** 设置表格边框的人话描述。 */
  private static String describeSetTableBorders(Map<String, Object> p) {
    String tableIdx = intStr(p, "table_index");
    String style = str(p, "border_style").toUpperCase(java.util.Locale.ROOT);
    if ("NONE".equals(style)) {
      return "表格(" + tableIdx + ") 设为无边框";
    }
    return "表格(" + tableIdx + ") 边框设为 " + style;
  }

  /** 把 H1/Heading1/heading1 等归一化为「H1」。 */
  private static String translateHeadingLevel(String raw) {
    String digits = raw.replaceAll("[^0-9]", "");
    return digits.isEmpty() ? raw : "H" + digits;
  }

  /** 把 CENTER/center 等归一化为中文「居中」。 */
  private static String translateAlignment(String raw) {
    switch (raw.trim().toUpperCase(java.util.Locale.ROOT)) {
      case "CENTER":
        return "居中";
      case "LEFT":
        return "左";
      case "RIGHT":
        return "右";
      case "JUSTIFY":
        return "两端";
      default:
        return raw;
    }
  }

  private static String translateAction(String action) {
    if (action == null) return "处理";
    switch (action.trim().toUpperCase(java.util.Locale.ROOT)) {
      case "ACCEPT":
        return "接受";
      case "REJECT":
        return "拒绝";
      default:
        return action;
    }
  }

  private static String str(Map<String, Object> p, String key) {
    Object v = p.get(key);
    return v == null ? "" : String.valueOf(v);
  }

  private static String intStr(Map<String, Object> p, String key) {
    Object v = p.get(key);
    return v == null ? "?" : String.valueOf(v);
  }

  private static boolean boolVal(Object v) {
    if (v instanceof Boolean) return (Boolean) v;
    if (v instanceof String) return Boolean.parseBoolean(((String) v).trim());
    return false;
  }
}
