package io.github.nondirectional.docx.toolkit.compiler;

import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.style.Alignment;
import io.github.nondirectional.docx.core.api.table.Table;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import io.github.nondirectional.docx.core.api.text.Run;
import io.github.nondirectional.docx.toolkit.compiler.MarkdownDoc.Block;
import io.github.nondirectional.docx.toolkit.compiler.MarkdownDoc.Heading;
import io.github.nondirectional.docx.toolkit.compiler.MarkdownDoc.ListItem;
import io.github.nondirectional.docx.toolkit.compiler.MarkdownDoc.Para;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文档 patch 引擎：对<b>既有</b>文档应用一批原子编辑操作。
 *
 * <p>它是「文档 IR + 编译器」路线的编辑侧（`docs/11` §3.3）：模型用一次 {@code edit_document} 表达一批编辑（ops 数组），锚点是 {@code
 * read_document} 输出的 短 ID（{@code pN}/{@code tN}），插入内容用 Markdown 块表达（复用 {@link MarkdownParser} +
 * {@link DocumentCompiler}，不发明第二套内容语言）。
 *
 * <p><b>原子性。</b> 先 preflight 全部 op：锚点存在、参数合法、{@code find} 在段落文本中、 行列在界内、表格块只允许插到文档末尾、被本 patch 前序 op
 * 删除的锚点不得再引用。任一 失败抛 {@link PatchException}，<b>零写入</b>。执行期不存在可预期失败分支；POI 意外异常 直接上抛（由工具层报告「可能部分应用」）。
 *
 * <p><b>op 集（v1，6 种）：</b>
 *
 * <ul>
 *   <li>{@code insert}——Markdown 块插入 {@code before}/{@code after} 锚点或文档 {@code start}/{code
 *       end}；含表格块时仅允许 {@code end}（core 无定位插表 API）；
 *   <li>{@code replace_text}——段落内精确子串替换；跨 run 时整段重建为单 run（丢段内直接 格式，记 warning）；
 *   <li>{@code delete}——删除段落（删表不在 v1：core 无 API）；
 *   <li>{@code set_style}——套 profile 的 {@code body}/{@code heading} 样式，或显式 {@code align}/{@code
 *       line_spacing}；
 *   <li>{@code table.set_cell}——改单元格文本；
 *   <li>{@code table.add_row}——末尾加一行（可带初始文本）。
 * </ul>
 *
 * <p>op 数组来自 nonchain 还原的 {@code List<LinkedHashMap>}；本类只读 Map，不依赖 JSON 库。
 */
public final class PatchEngine {

  /** preflight 失败：消息含第几个 op 与原因；抛出时保证零写入。 */
  public static final class PatchException extends IllegalArgumentException {

    PatchException(String message) {
      super(message);
    }
  }

  /** 一次 patch 的结果（执行或 dry-run）。 */
  public static final class PatchResult {

    private final boolean dryRun;
    private final int applied;
    private final List<String> changed;
    private final List<String> warnings;
    private final List<String> planned;

    PatchResult(
        boolean dryRun,
        int applied,
        List<String> changed,
        List<String> warnings,
        List<String> planned) {
      this.dryRun = dryRun;
      this.applied = applied;
      this.changed = List.copyOf(changed);
      this.warnings = List.copyOf(warnings);
      this.planned = List.copyOf(planned);
    }

    /** 是否 dry-run（未写入）。 */
    public boolean dryRun() {
      return dryRun;
    }

    /** 实际应用的 op 数（dry-run 时为计划数）。 */
    public int applied() {
      return applied;
    }

    /** 被触碰的锚点（执行模式）。 */
    public List<String> changed() {
      return changed;
    }

    /** 宽容降级/格式丢失警告。 */
    public List<String> warnings() {
      return warnings;
    }

    /** dry-run 的逐条计划描述。 */
    public List<String> planned() {
      return planned;
    }
  }

  private PatchEngine() {}

  /**
   * 对文档应用一批 ops。
   *
   * @param doc 活文档
   * @param ops op 的 Map 列表（顺序执行）
   * @param profile set_style 用的样式 profile
   * @param dryRun {@code true} 只返回计划，不写入
   * @return 执行结果
   * @throws PatchException preflight 失败（零写入）
   */
  public static PatchResult apply(
      Document doc, List<Object> ops, StyleProfile profile, boolean dryRun) {
    if (ops == null || ops.isEmpty()) {
      throw new PatchException("ops 为空，至少传一条操作");
    }
    StyleProfile p = profile == null ? StyleProfile.builtin("plain") : profile;
    Snapshot snapshot = new Snapshot(doc);
    Set<String> deleted = new HashSet<>();
    List<PlannedOp> plannedOps = new ArrayList<>();
    List<String> plannedText = new ArrayList<>();
    for (int i = 0; i < ops.size(); i++) {
      Object raw = ops.get(i);
      if (!(raw instanceof Map)) {
        throw new PatchException("op[" + i + "] 不是对象: " + raw);
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> op = (Map<String, Object>) raw;
      PlannedOp planned = planOp(i, op, snapshot, deleted, p);
      plannedOps.add(planned);
      plannedText.add("op[" + i + "] " + planned.describe());
    }
    if (dryRun) {
      return new PatchResult(true, plannedOps.size(), List.of(), List.of(), plannedText);
    }
    List<String> changed = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    for (PlannedOp planned : plannedOps) {
      planned.execute(doc, snapshot, changed, warnings, p);
    }
    return new PatchResult(false, plannedOps.size(), changed, warnings, List.of());
  }

  // ==================== preflight：解析 + 校验 ====================

  private static PlannedOp planOp(
      int index, Map<String, Object> op, Snapshot snapshot, Set<String> deleted, StyleProfile p) {
    String kind = str(op, "op", index);
    String anchor = op.get("anchor") == null ? null : String.valueOf(op.get("anchor")).trim();
    if (anchor != null && !anchor.isEmpty() && deleted.contains(anchor)) {
      throw new PatchException("op[" + index + "] 锚点 " + anchor + " 已被本 patch 前序 op 删除，不能再用");
    }
    switch (kind) {
      case "insert":
        return planInsert(index, op, anchor, snapshot, p);
      case "replace_text":
        return planReplaceText(index, op, anchor, snapshot);
      case "delete":
        return planDelete(index, op, anchor, snapshot, deleted);
      case "set_style":
        return planSetStyle(index, op, anchor, snapshot);
      case "table.set_cell":
        return planTableSetCell(index, op, anchor, snapshot);
      case "table.add_row":
        return planTableAddRow(index, op, anchor, snapshot);
      default:
        throw new PatchException(
            "op["
                + index
                + "] 未知操作类型: "
                + kind
                + "（可用: insert/replace_text/delete/set_style/table.set_cell/table.add_row）");
    }
  }

  private static PlannedOp planInsert(
      int index, Map<String, Object> op, String anchor, Snapshot snapshot, StyleProfile p) {
    String position =
        op.get("position") == null ? "end" : String.valueOf(op.get("position")).trim();
    String block = str(op, "block", index);
    MarkdownDoc parsed = MarkdownParser.parse(block);
    if (parsed.blocks().isEmpty()) {
      throw new PatchException("op[" + index + "] block 为空或全是空白，无内容可插入");
    }
    boolean hasTable = parsed.blocks().stream().anyMatch(b -> b instanceof MarkdownDoc.Table);
    if (hasTable && !"end".equals(position)) {
      throw new PatchException("op[" + index + "] block 含表格，只支持 position=end（core 无定位插表 API）");
    }
    Paragraph anchorParagraph;
    switch (position) {
      case "before":
      case "after":
        anchorParagraph = snapshot.requireParagraph(anchor, index);
        break;
      case "start":
      case "end":
        anchorParagraph = null;
        break;
      default:
        throw new PatchException(
            "op[" + index + "] 未知 position: " + position + "（可用 before/after/start/end）");
    }
    List<Block> blocks = parsed.blocks();
    List<String> warnings = new ArrayList<>(parsed.warnings());
    return new PlannedOp("insert " + position + " " + describeBlocks(blocks)) {
      @Override
      void execute(
          Document doc,
          Snapshot snap,
          List<String> changed,
          List<String> warningsOut,
          StyleProfile profile) {
        warningsOut.addAll(warnings);
        // 单元素数组承载可变插入位置（匿名类只能捕获 effectively final 局部变量）
        int[] insertAt = {resolveBodyIndex(doc, snap, anchorParagraph, position)};
        for (Block b : blocks) {
          if (b instanceof Heading) {
            Paragraph paragraph = doc.insertParagraph(insertAt[0]);
            paragraph.heading(DocumentCompiler.headingLevelOf((Heading) b));
            DocumentCompiler.fillHeading(paragraph, (Heading) b, profile);
            insertAt[0]++;
          } else if (b instanceof Para) {
            DocumentCompiler.fillPara(doc.insertParagraph(insertAt[0]), (Para) b, profile);
            insertAt[0]++;
          } else if (b instanceof ListItem) {
            ListItem item = (ListItem) b;
            Paragraph paragraph = doc.insertParagraph(insertAt[0]);
            paragraph.list(item.kind(), Math.min(item.level(), 8));
            DocumentCompiler.fillListItem(paragraph, item, profile);
            insertAt[0]++;
          } else if (b instanceof MarkdownDoc.Table) {
            DocumentCompiler.compileTableInto(doc, (MarkdownDoc.Table) b, profile);
          }
        }
        if (anchor != null && anchorParagraph != null) {
          changed.add(anchor);
        }
      }
    };
  }

  private static PlannedOp planReplaceText(
      int index, Map<String, Object> op, String anchor, Snapshot snapshot) {
    Paragraph paragraph = snapshot.requireParagraph(anchor, index);
    String find = str(op, "find", index);
    String text = str(op, "text", index);
    String current = paragraph.text();
    if (!current.contains(find)) {
      throw new PatchException(
          "op[" + index + "] 段落 " + anchor + " 文本中找不到「" + find + "」（实际: " + current + "）");
    }
    return new PlannedOp("replace_text " + anchor + " 「" + find + "」→「" + text + "」") {
      @Override
      void execute(
          Document doc,
          Snapshot snap,
          List<String> changed,
          List<String> warningsOut,
          StyleProfile profile) {
        // 单 run 内命中：原位替换保样式；跨 run：整段重建为单 run（丢段内直接格式）。
        int runIndex = runContaining(paragraph, find);
        if (runIndex >= 0) {
          Run run = paragraph.run(runIndex);
          run.text(run.text().replace(find, text));
        } else {
          String rebuilt = paragraph.text().replace(find, text);
          while (paragraph.runs().size() > 0) {
            paragraph.removeInlineElement(paragraph.runs().size() - 1);
          }
          paragraph.addRun(rebuilt);
          warningsOut.add("replace_text " + anchor + " 跨 run 命中，段落已重建为单 run（段内直接格式丢失，段落属性保留）");
        }
        changed.add(anchor);
      }
    };
  }

  private static PlannedOp planDelete(
      int index, Map<String, Object> op, String anchor, Snapshot snapshot, Set<String> deleted) {
    Paragraph paragraph = snapshot.requireParagraph(anchor, index);
    deleted.add(anchor);
    return new PlannedOp("delete " + anchor) {
      @Override
      void execute(
          Document doc,
          Snapshot snap,
          List<String> changed,
          List<String> warningsOut,
          StyleProfile profile) {
        for (int i = 0; i < doc.paragraphs().size(); i++) {
          if (doc.paragraphs().get(i).raw() == paragraph.raw()) {
            doc.removeParagraph(i);
            changed.add(anchor);
            return;
          }
        }
        throw new IllegalStateException("段落 " + anchor + " 在执行期找不到（不应发生）");
      }
    };
  }

  private static PlannedOp planSetStyle(
      int index, Map<String, Object> op, String anchor, Snapshot snapshot) {
    Paragraph paragraph = snapshot.requireParagraph(anchor, index);
    String style = op.get("style") == null ? null : String.valueOf(op.get("style")).trim();
    Object alignRaw = op.get("align");
    Object spacingRaw = op.get("line_spacing");
    if (style == null && alignRaw == null && spacingRaw == null) {
      throw new PatchException(
          "op[" + index + "] set_style 需要 style（body/heading）或 align/line_spacing 至少其一");
    }
    if (style != null && !"body".equals(style) && !"heading".equals(style)) {
      String hint =
          style.contains("=")
              ? "；对齐/行距不要写进 style，作为独立字段传：{\"op\":\"set_style\",\"anchor\":\"p0\",\"align\":\"center\"}"
              : "";
      throw new PatchException("op[" + index + "] 未知 style: " + style + "（可用 body/heading）" + hint);
    }
    Alignment align = alignRaw == null ? null : MarkdownParser.alignmentOf(strValue(alignRaw));
    Double spacing = spacingRaw == null ? null : doubleValue(spacingRaw, index);
    return new PlannedOp("set_style " + anchor + (style == null ? "" : " style=" + style)) {
      @Override
      void execute(
          Document doc,
          Snapshot snap,
          List<String> changed,
          List<String> warningsOut,
          StyleProfile profile) {
        if ("body".equals(style)) {
          if (profile.bodyLineSpacing() != null) {
            paragraph.lineSpacing(profile.bodyLineSpacing());
          }
          if (profile.firstLineIndentTwips() > 0) {
            paragraph.indent(0, profile.firstLineIndentTwips());
          }
          for (Run run : paragraph.runs()) {
            DocumentCompiler.applyRunFonts(run, profile.bodyLatin(), profile.bodyEastAsia());
            if (profile.bodySizePt() != null) {
              run.fontSize(profile.bodySizePt());
            }
          }
        } else if ("heading".equals(style)) {
          Integer level = paragraph.heading() == null ? 1 : paragraph.heading().ordinal() + 1;
          for (Run run : paragraph.runs()) {
            DocumentCompiler.applyRunFonts(run, profile.headingLatin(), profile.headingEastAsia());
            if (profile.headingSizePt(level) != null) {
              run.fontSize(profile.headingSizePt(level));
            }
          }
        }
        if (align != null) {
          paragraph.alignment(align);
        }
        if (spacing != null && spacing > 0) {
          paragraph.lineSpacing(spacing);
        }
        changed.add(anchor);
      }
    };
  }

  private static PlannedOp planTableSetCell(
      int index, Map<String, Object> op, String anchor, Snapshot snapshot) {
    Table table = snapshot.requireTable(anchor, index);
    int row = intValue(op.get("row"), index, "row");
    int col = intValue(op.get("col"), index, "col");
    String text = str(op, "text", index);
    if (row < 0 || row >= table.rows().size()) {
      throw new PatchException(
          "op[" + index + "] " + anchor + " row " + row + " 越界（共 " + table.rows().size() + " 行）");
    }
    int cols = table.rows().get(row).cells().size();
    if (col < 0 || col >= cols) {
      throw new PatchException(
          "op[" + index + "] " + anchor + " col " + col + " 越界（该行共 " + cols + " 列）");
    }
    // text 按 Markdown 行内标记解析：**任务** → 文本「任务」+ 加粗 run（Markdown 是内容语言）。
    List<MarkdownDoc.Span> spans = MarkdownParser.parseInline(text);
    return new PlannedOp("table.set_cell " + anchor + " r" + row + "c" + col) {
      @Override
      void execute(
          Document doc,
          Snapshot snap,
          List<String> changed,
          List<String> warningsOut,
          StyleProfile profile) {
        var cell = table.rows().get(row).cells().get(col);
        boolean plain =
            spans.size() == 1
                && !spans.get(0).bold()
                && !spans.get(0).italic()
                && !spans.get(0).code();
        if (plain) {
          cell.text(spans.get(0).text());
        } else {
          // 重建单元格首段为多 run，逐 span 套行内格式 + profile 字体
          var paragraph = cell.paragraph(0);
          while (paragraph.runs().size() > 0) {
            paragraph.removeInlineElement(paragraph.runs().size() - 1);
          }
          for (MarkdownDoc.Span span : spans) {
            Run run = paragraph.addRun(span.text());
            if (span.bold()) {
              run.bold();
            }
            if (span.italic()) {
              run.italic();
            }
            if (span.code()) {
              DocumentCompiler.applyRunFonts(run, profile.codeFont(), profile.codeFont());
            } else {
              DocumentCompiler.applyRunFonts(run, profile.bodyLatin(), profile.bodyEastAsia());
            }
            if (profile.bodySizePt() != null) {
              run.fontSize(profile.bodySizePt());
            }
          }
        }
        changed.add(anchor);
      }
    };
  }

  private static PlannedOp planTableAddRow(
      int index, Map<String, Object> op, String anchor, Snapshot snapshot) {
    Table table = snapshot.requireTable(anchor, index);
    List<String> cells = new ArrayList<>();
    Object raw = op.get("cells");
    if (raw instanceof List) {
      for (Object cell : (List<?>) raw) {
        cells.add(cell == null ? "" : String.valueOf(cell));
      }
    }
    if (table.rows().isEmpty()) {
      throw new PatchException("op[" + index + "] " + anchor + " 是空表格，无法 add_row");
    }
    int colCount = table.rows().get(0).cells().size();
    if (cells.size() > colCount) {
      throw new PatchException(
          "op[" + index + "] " + anchor + " cells 有 " + cells.size() + " 个，超过表列数 " + colCount);
    }
    return new PlannedOp("table.add_row " + anchor + " (" + cells.size() + " 格)") {
      @Override
      void execute(
          Document doc,
          Snapshot snap,
          List<String> changed,
          List<String> warningsOut,
          StyleProfile profile) {
        var row = table.addRow();
        row.cantSplit(true);
        for (int c = 0; c < colCount; c++) {
          String text = c < cells.size() ? cells.get(c) : "";
          // addRow() 建出的行没有单元格，按 index 逐个补建（与编译器 writeCell 同一陷阱）
          io.github.nondirectional.docx.core.api.table.Cell cell;
          if (c < row.cells().size()) {
            cell = row.cells().get(c);
          } else {
            cell = row.addCell();
          }
          cell.text(text);
        }
        changed.add(anchor);
      }
    };
  }

  // ==================== 执行期辅助 ====================

  /** 定位插入的 body 序号：start=0；end=末尾；before/after=锚点当前 body 位置（±1）。 */
  private static int resolveBodyIndex(
      Document doc, Snapshot snap, Paragraph anchorParagraph, String position) {
    if (anchorParagraph == null) {
      return "start".equals(position) ? 0 : doc.bodyElements().size();
    }
    for (int i = 0; i < doc.bodyElements().size(); i++) {
      var element = doc.bodyElements().get(i);
      if (element instanceof Paragraph && ((Paragraph) element).raw() == anchorParagraph.raw()) {
        return "after".equals(position) ? i + 1 : i;
      }
    }
    throw new IllegalStateException("插入锚点在执行期找不到（不应发生）");
  }

  /** 命中 find 的 run 序号（find 完整落在单个 run 内返回索引，否则 -1）。 */
  private static int runContaining(Paragraph paragraph, String find) {
    for (int i = 0; i < paragraph.runs().size(); i++) {
      String text = paragraph.run(i).text();
      if (text != null && text.contains(find)) {
        return i;
      }
    }
    return -1;
  }

  private static String describeBlocks(List<Block> blocks) {
    List<String> parts = new ArrayList<>();
    for (Block b : blocks) {
      if (b instanceof Heading) {
        parts.add("H" + ((Heading) b).level());
      } else if (b instanceof Para) {
        parts.add("段落");
      } else if (b instanceof ListItem) {
        parts.add("列表项");
      } else if (b instanceof MarkdownDoc.Table) {
        parts.add("表格");
      }
    }
    return String.join("+", parts);
  }

  // ==================== 入参归一化 ====================

  private static String str(Map<String, Object> op, String key, int index) {
    Object value = op.get(key);
    if (value == null || String.valueOf(value).isEmpty()) {
      throw new PatchException("op[" + index + "] 缺少必填字段 " + key);
    }
    return String.valueOf(value);
  }

  private static String strValue(Object value) {
    return String.valueOf(value).trim();
  }

  private static int intValue(Object value, int index, String key) {
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(value).trim());
    } catch (NumberFormatException e) {
      throw new PatchException("op[" + index + "] " + key + " 不是整数: " + value);
    }
  }

  private static Double doubleValue(Object value, int index) {
    if (value instanceof Number) {
      return ((Number) value).doubleValue();
    }
    try {
      return Double.parseDouble(String.valueOf(value).trim());
    } catch (NumberFormatException e) {
      throw new PatchException("op[" + index + "] line_spacing 不是数字: " + value);
    }
  }

  // ==================== 内部结构 ====================

  /** preflight 期对文档的一次快照：短 ID → 活对象。 */
  private static final class Snapshot {

    final Map<String, Paragraph> paragraphs = new HashMap<>();
    final Map<String, Table> tables = new HashMap<>();

    Snapshot(Document doc) {
      int p = 0;
      int t = 0;
      for (var element : doc.bodyElements()) {
        if (element instanceof Paragraph) {
          paragraphs.put("p" + p++, (Paragraph) element);
        } else if (element instanceof Table) {
          tables.put("t" + t++, (Table) element);
        }
      }
    }

    Paragraph requireParagraph(String anchor, int index) {
      Paragraph paragraph = anchor == null ? null : paragraphs.get(anchor);
      if (paragraph == null) {
        throw new PatchException(
            "op["
                + index
                + "] 段落锚点 "
                + anchor
                + " 不存在（read_document 后再引用，共 "
                + paragraphs.size()
                + " 段）");
      }
      return paragraph;
    }

    Table requireTable(String anchor, int index) {
      Table table = anchor == null ? null : tables.get(anchor);
      if (table == null) {
        throw new PatchException(
            "op[" + index + "] 表格锚点 " + anchor + " 不存在（共 " + tables.size() + " 个表格）");
      }
      return table;
    }
  }

  /** preflight 产出的可执行计划（执行期不再有可预期失败）。 */
  abstract static class PlannedOp {

    private final String description;

    PlannedOp(String description) {
      this.description = description;
    }

    String describe() {
      return description;
    }

    abstract void execute(
        Document doc,
        Snapshot snap,
        List<String> changed,
        List<String> warnings,
        StyleProfile profile);
  }
}
