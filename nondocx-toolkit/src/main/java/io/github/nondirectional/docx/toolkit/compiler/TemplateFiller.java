package io.github.nondirectional.docx.toolkit.compiler;

import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.table.Cell;
import io.github.nondirectional.docx.core.api.table.Row;
import io.github.nondirectional.docx.core.api.table.Table;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import io.github.nondirectional.docx.core.api.text.Run;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模板填充引擎：把「mustache 子集」数据填进当前文档的占位符（docs/11 §3.6 模板优先路线）。
 *
 * <p><b>语法（v1 子集，不支嵌套）：</b>
 *
 * <ul>
 *   <li>{@code {{key}}}——值替换（String/Number/Boolean）。Word 常把占位符拆进多个 run （拼写检查/rsid 边界），本引擎按 {@code
 *       paragraph.text()} 合并判定，重写用「首 run 承载全文、其余 run 清空」——保留首 run 的格式与字体槽，不做跨 run 拼接。
 *   <li>{@code {{#key}}…{{/key}}} 整段标记——<b>段落级条件块</b>：key 为 falsy（false/缺失/空列表/空串） 时删除块内全部段落，truthy
 *       时保留内容（标记行总是删除）。
 *   <li><b>表格行级重复</b>：任一单元格文本含 {@code {{#key}}} 的行是模板行；data[key] 为列表时 按元素复制（core {@code
 *       Table.copyRowTo} XML 深拷贝，保留单元格底纹/边框/行属性）， 行内 {@code {{field}}} 优先取元素字段、回退外层 data，标量元素用
 *       {@code {{.}}}； 空列表删行；key 缺失/非列表保留原样并记 warning。
 * </ul>
 *
 * <p><b>宽容语义。</b> 缺 key 的占位符<b>保留原样</b>并记 warning（模型可补数据重试，比静默清空 安全）；没有结构性失败路径——本引擎不做 preflight
 * 拒绝，所有异常输入降级为 warning。
 *
 * <p><b>已知边界（v1）。</b> 页眉/页脚里的占位符不处理；嵌套条件块不支持（内层标记按普通文本 保留）；段落里除文本 run 外的内联元素（超链接等）参与 {@code text()}
 * 合并但不参与重写分布。
 */
public final class TemplateFiller {

  /** 整段起始标记：{{#key}}。 */
  private static final Pattern SECTION_START =
      Pattern.compile("^\\{\\{\\s*#\\s*([\\w.-]+)\\s*\\}\\}$");

  /** 整段结束标记：{{/key}}。 */
  private static final Pattern SECTION_END =
      Pattern.compile("^\\{\\{\\s*/\\s*([\\w.-]+)\\s*\\}\\}$");

  /** 值占位符：{{key}} 或 {{.}}（列表元素为标量时指代元素本身）。 */
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([\\w.-]+|\\.)\\s*\\}\\}");

  /** 行内任意位置的区段 token（识别模板行 + 剥标记用）。 */
  private static final Pattern SECTION_TOKEN =
      Pattern.compile("\\{\\{\\s*[#/]\\s*[\\w.-]+\\s*\\}\\}");

  private TemplateFiller() {}

  /** 填充结果计数。 */
  public static final class FillResult {

    private final int placeholdersFilled;
    private final int rowsExpanded;
    private final int paragraphsDropped;
    private final List<String> warnings;

    FillResult(
        int placeholdersFilled, int rowsExpanded, int paragraphsDropped, List<String> warnings) {
      this.placeholdersFilled = placeholdersFilled;
      this.rowsExpanded = rowsExpanded;
      this.paragraphsDropped = paragraphsDropped;
      this.warnings = List.copyOf(warnings);
    }

    /** 完成值替换的占位符个数。 */
    public int placeholdersFilled() {
      return placeholdersFilled;
    }

    /** 表格模板行复制出的行数。 */
    public int rowsExpanded() {
      return rowsExpanded;
    }

    /** 条件块删除的段落数（含标记行）。 */
    public int paragraphsDropped() {
      return paragraphsDropped;
    }

    /** 宽容降级警告（缺 key / 非列表 / 无结束标记等）。 */
    public List<String> warnings() {
      return warnings;
    }
  }

  /** 填充过程中的可变计数。 */
  private static final class Counters {

    int filled;
    int rows;
    int dropped;
    final List<String> warnings = new java.util.ArrayList<>();

    void warn(String message) {
      if (warnings.size() < 50) {
        warnings.add(message);
      }
    }
  }

  /**
   * 对文档应用模板数据。
   *
   * @param doc 活文档（原地修改）
   * @param data 键值数据（String/Number/Boolean 值；列表值驱动条件块与表格模板行）
   * @return 填充结果计数与警告
   */
  public static FillResult fill(Document doc, Map<String, Object> data) {
    Counters c = new Counters();
    fillBody(doc, data, c);
    fillTables(doc, data, c);
    return new FillResult(c.filled, c.rows, c.dropped, c.warnings);
  }

  // ==================== 正文：条件块 + 值替换 ====================

  private static void fillBody(Document doc, Map<String, Object> data, Counters c) {
    List<Paragraph> paras = doc.paragraphs();
    TreeSet<Integer> drop = new TreeSet<>();
    int i = 0;
    while (i < paras.size()) {
      String text = paras.get(i).text().trim();
      Matcher start = SECTION_START.matcher(text);
      if (start.matches()) {
        String key = start.group(1);
        int end = findSectionEnd(paras, i + 1, key);
        if (end < 0) {
          c.warn("条件块 {{" + key + "}} 缺结束标记 {{/" + key + "}}，标记行按普通文本保留");
          fillParagraph(paras.get(i), data, "段落", c);
          i++;
          continue;
        }
        boolean keep = truthy(data.get(key));
        drop.add(i);
        drop.add(end);
        if (keep) {
          for (int k = i + 1; k < end; k++) {
            fillParagraph(paras.get(k), data, "段落", c);
          }
        } else {
          for (int k = i + 1; k < end; k++) {
            drop.add(k);
          }
        }
        c.dropped += keep ? 2 : (end - i + 1);
        i = end + 1;
        continue;
      }
      fillParagraph(paras.get(i), data, "段落", c);
      i++;
    }
    // 自底向上删除，低索引不受影响
    for (Integer index : drop.descendingSet()) {
      doc.removeParagraph(index);
    }
  }

  /** 从 from 开始找与 key 配对的整段结束标记；找不到返回 -1。 */
  private static int findSectionEnd(List<Paragraph> paras, int from, String key) {
    for (int j = from; j < paras.size(); j++) {
      Matcher end = SECTION_END.matcher(paras.get(j).text().trim());
      if (end.matches() && end.group(1).equals(key)) {
        return j;
      }
    }
    return -1;
  }

  // ==================== 表格：模板行展开 + 值替换 ====================

  private static void fillTables(Document doc, Map<String, Object> data, Counters c) {
    for (Table table : doc.tables()) {
      int rowIndex = 0;
      while (rowIndex < table.rows().size()) {
        Row row = table.rows().get(rowIndex);
        String sectionKey = rowSectionKey(row);
        if (sectionKey == null) {
          fillRowValues(row, data, c);
          rowIndex++;
          continue;
        }
        Object value = data.get(sectionKey);
        if (!(value instanceof List)) {
          c.warn("表格行区段 {{" + sectionKey + "}} 的数据不是列表（实际 " + typeName(value) + "），模板行保留原样");
          fillRowValues(row, data, c);
          rowIndex++;
          continue;
        }
        List<?> items = (List<?>) value;
        if (items.isEmpty()) {
          table.removeRow(rowIndex);
          c.warn("表格行区段 {{" + sectionKey + "}} 为空列表，模板行已删除");
          continue;
        }
        int insertAt = rowIndex + 1;
        for (Object item : items) {
          Map<String, Object> merged = mergedScope(data, item);
          Row copy = table.copyRowTo(rowIndex, insertAt);
          fillRowValues(copy, merged, c);
          insertAt++;
          c.rows++;
        }
        table.removeRow(rowIndex);
        // 删除模板行后复制行整体前移一位，从复制区之后继续扫描
        rowIndex = insertAt - 1;
      }
    }
  }

  /** 行内任一单元格文本含 {{#key}} 时返回该 key（此行是模板行）；否则 null。 */
  private static String rowSectionKey(Row row) {
    for (Cell cell : row.cells()) {
      Matcher inCell = SECTION_TOKEN.matcher(cell.text());
      while (inCell.find()) {
        String token = inCell.group();
        if (token.contains("#")) {
          Matcher key = SECTION_START.matcher(token.trim());
          if (key.matches()) {
            return key.group(1);
          }
        }
      }
    }
    return null;
  }

  /** 元素作用域：外层 data 打底，元素字段覆盖；标量元素绑定到 {@code "."}。 */
  private static Map<String, Object> mergedScope(Map<String, Object> data, Object item) {
    Map<String, Object> merged = new LinkedHashMap<>(data);
    if (item instanceof Map) {
      merged.putAll((Map<String, Object>) item);
    } else {
      merged.put(".", item);
    }
    return merged;
  }

  /** 逐单元格逐段落做值替换（模板行副本会先剥掉 {{#key}}/{{/key}} 标记）。 */
  private static void fillRowValues(Row row, Map<String, Object> scope, Counters c) {
    for (Cell cell : row.cells()) {
      String where = "表格单元格";
      for (Paragraph p : cell.paragraphs()) {
        String text = p.text();
        if (!text.contains("{{")) {
          continue;
        }
        String stripped = SECTION_TOKEN.matcher(text).replaceAll("");
        fillParagraphText(p, stripped, scope, where, c);
      }
    }
  }

  // ==================== 段落级填充 ====================

  private static void fillParagraph(
      Paragraph p, Map<String, Object> scope, String where, Counters c) {
    String text = p.text();
    if (!text.contains("{{")) {
      return;
    }
    if (!SECTION_START.matcher(text.trim()).matches()
        && !SECTION_END.matcher(text.trim()).matches()
        && (text.contains("{{#") || text.contains("{{/"))) {
      c.warn(where + "含行内区段标记（{{" + "#…}}/{{/…}} 只支持整段或表格行），按普通文本保留");
    }
    fillParagraphText(p, text, scope, where, c);
  }

  private static void fillParagraphText(
      Paragraph p, String text, Map<String, Object> scope, String where, Counters c) {
    Matcher m = PLACEHOLDER.matcher(text);
    StringBuilder sb = new StringBuilder();
    boolean changed = false;
    while (m.find()) {
      String key = m.group(1);
      Object value = scope.get(key);
      if (value == null) {
        c.warn(where + " 占位符 {{" + key + "}} 无对应数据，保留原样");
        m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
      } else {
        m.appendReplacement(sb, Matcher.quoteReplacement(scalarToString(value)));
        c.filled++;
        changed = true;
      }
    }
    m.appendTail(sb);
    if (changed) {
      rewriteParagraphText(p, sb.toString());
    }
  }

  /**
   * 用填充后的整段文本重写段落：首个 run 承载全文（保留其格式与 CJK/西文字体槽），其余 run 清空文本。
   *
   * <p>不用「删光重建单 run」：重建需要克隆样式，而 {@code Run.font()} 写侧是四槽同写，会把西文字体名 写进 eastAsia 槽污染中文；首 run
   * 承载避开样式复制。被清空的 run 留在 XML 里（无文本，无害）。
   */
  private static void rewriteParagraphText(Paragraph p, String newText) {
    List<Run> runs = p.runs();
    if (runs.isEmpty()) {
      if (!newText.isEmpty()) {
        p.addRun(newText);
      }
      return;
    }
    runs.get(0).text(newText);
    for (int i = 1; i < runs.size(); i++) {
      runs.get(i).text("");
    }
  }

  // ==================== 值语义 ====================

  /** falsy：null / false / 空列表 / 空白串；其余 truthy。 */
  static boolean truthy(Object value) {
    if (value == null || Boolean.FALSE.equals(value)) {
      return false;
    }
    if (value instanceof List && ((List<?>) value).isEmpty()) {
      return false;
    }
    if (value instanceof String && ((String) value).isBlank()) {
      return false;
    }
    return true;
  }

  private static String scalarToString(Object value) {
    return String.valueOf(value);
  }

  private static String typeName(Object value) {
    return value == null ? "缺失" : value.getClass().getSimpleName();
  }
}
