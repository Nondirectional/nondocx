package com.non.docx.toolkit.orchestration.table;

import com.non.docx.toolkit.orchestration.DocumentSnapshot;
import com.non.docx.toolkit.orchestration.ExpertPlan;
import com.non.docx.toolkit.orchestration.Operation;
import com.non.docx.toolkit.orchestration.agent.ExpertAgent;
import com.non.docx.toolkit.orchestration.session.OrchestratorSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表格领域专家：把「表格修改」类用户意图翻译成 table 域的 {@link Operation} 列表。
 *
 * <p><b>OOXML 三层递进（表格专家）：</b>
 *
 * <ul>
 *   <li><b>OOXML</b>：表格是 {@code <w:tbl>}，单元格是 {@code <w:tc>}，单元格内段落/run 与正文同构。
 *   <li><b>POI</b>：{@code XWPFTable} 按行列索引定位单元格。
 *   <li><b>nondocx</b>：{@code TableAgent} 读 {@link DocumentSnapshot} 的表格预览，按用户意图定位单元格， 产出 {@code
 *       replace_table_cell_run_text} / {@code update_table_cell_shading} 等 operation。
 * </ul>
 *
 * <p><b>补读策略。</b> 默认看 table/cell 级摘要（首行首列样本）；涉及 cell 内段落/run 细节时补读 （第一版直接操作 cell 内第 0 段第 0 run，真实
 * LLM 场景会先补读）。
 */
public final class TableAgent implements ExpertAgent {

  private final AtomicLong opIdSeq = new AtomicLong();

  @Override
  public String name() {
    return "TableAgent";
  }

  @Override
  public boolean relevantTo(String intent, DocumentSnapshot snapshot) {
    if (intent == null) return false;
    String lower = intent.toLowerCase(Locale.ROOT);
    return lower.contains("表格")
        || lower.contains("单元格")
        || lower.contains("cell")
        || lower.contains("底纹")
        || (!snapshot.tables().isEmpty() && mentionsCellCoord(intent));
  }

  @Override
  public ExpertPlan plan(OrchestratorSession session, DocumentSnapshot snapshot, String intent) {
    List<Operation> ops = new ArrayList<>();

    // 启发式：「表格 T 行 R 列 C 写成 X」/「单元格 (T,R,C) 改成 X」
    int[] coord = parseCellCoord(intent);
    String newText = parseCellWriteText(intent);
    if (coord != null && newText != null) {
      int tableIndex = coord[0];
      int rowIndex = coord[1];
      int cellIndex = coord[2];
      // 校验表格存在
      if (tableIndex < snapshot.tables().size()) {
        ops.add(
            TableExecutor.replaceCellRunText(
                nextOpId(),
                tableIndex,
                rowIndex,
                cellIndex,
                0,
                0,
                newText,
                "把单元格(" + tableIndex + "," + rowIndex + "," + cellIndex + ")写成「" + newText + "」"));
      }
    }

    // 启发式：「给单元格 (T,R,C) 加底纹 #XXXXXX」
    String[] shading = parseCellShading(intent);
    if (shading != null && shading.length == 4) {
      int tableIndex = Integer.parseInt(shading[0]);
      int rowIndex = Integer.parseInt(shading[1]);
      int cellIndex = Integer.parseInt(shading[2]);
      String fill = shading[3];
      if (tableIndex < snapshot.tables().size()) {
        ops.add(
            TableExecutor.updateCellShading(
                nextOpId(),
                tableIndex,
                rowIndex,
                cellIndex,
                fill,
                "给单元格(" + tableIndex + "," + rowIndex + "," + cellIndex + ")加底纹 " + fill));
      }
    }

    return new ExpertPlan(
        name(),
        "table-plan-" + session.sessionGeneration(),
        session.conversationId(),
        snapshot.snapshotVersion(),
        session.sessionGeneration(),
        ops);
  }

  String nextOpId() {
    return "table-op-" + opIdSeq.incrementAndGet();
  }

  /** 是否提到单元格坐标（如「表格0行1列2」/「单元格(0,1,2)」）。 */
  static boolean mentionsCellCoord(String intent) {
    return parseCellCoord(intent) != null;
  }

  /** 解析单元格坐标：支持「表格 T 行 R 列 C」「单元格(T,R,C)」「(T,R,C)」。返回 [table,row,cell] 或 null。 */
  static int[] parseCellCoord(String intent) {
    if (intent == null) return null;
    // (T,R,C) 格式
    Matcher m1 =
        Pattern.compile("\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)").matcher(intent);
    if (m1.find()) {
      return new int[] {
        Integer.parseInt(m1.group(1)), Integer.parseInt(m1.group(2)), Integer.parseInt(m1.group(3))
      };
    }
    // 表格 T 行 R 列 C 格式
    Matcher m2 = Pattern.compile("表格\\s*(\\d+).*?行\\s*(\\d+).*?列\\s*(\\d+)").matcher(intent);
    if (m2.find()) {
      return new int[] {
        Integer.parseInt(m2.group(1)), Integer.parseInt(m2.group(2)), Integer.parseInt(m2.group(3))
      };
    }
    return null;
  }

  /** 解析「...写成 X」「...改为 X」「...改成 X」中的 X。书名号/引号会被剥离。 */
  static String parseCellWriteText(String intent) {
    if (intent == null) return null;
    String cleaned = intent.replaceAll("[「」『』\"\"'']", "");
    Matcher m = Pattern.compile("(?:写成|改为|改成)\\s*([^\\n，,；;。]+)").matcher(cleaned);
    if (m.find()) {
      return m.group(1).trim();
    }
    return null;
  }

  /** 解析「单元格 (T,R,C) 加底纹 #XXXXXX / 底纹 XXXXXX」。返回 [T,R,C,fill] 或 null。 */
  static String[] parseCellShading(String intent) {
    if (intent == null) return null;
    if (!intent.contains("底纹")) return null;
    int[] coord = parseCellCoord(intent);
    if (coord == null) return null;
    Matcher m = Pattern.compile("(?:底纹|#)\\s*([0-9A-Fa-f]{6})").matcher(intent);
    if (m.find()) {
      return new String[] {
        String.valueOf(coord[0]), String.valueOf(coord[1]), String.valueOf(coord[2]), m.group(1)
      };
    }
    return null;
  }
}
