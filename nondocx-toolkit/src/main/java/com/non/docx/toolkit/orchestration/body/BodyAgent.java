package com.non.docx.toolkit.orchestration.body;

import com.non.docx.toolkit.orchestration.DocumentSnapshot;
import com.non.docx.toolkit.orchestration.ExpertPlan;
import com.non.docx.toolkit.orchestration.Operation;
import com.non.docx.toolkit.orchestration.agent.ExpertAgent;
import com.non.docx.toolkit.orchestration.session.OrchestratorSession;
import com.non.docx.toolkit.orchestration.snapshot.ParagraphPreview;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 正文领域专家：把「正文修改」类用户意图翻译成 body 域的 {@link Operation} 列表。
 *
 * <p><b>OOXML 三层递进（正文专家）：</b>
 *
 * <ul>
 *   <li><b>OOXML</b>：正文是 {@code word/document.xml} 的 {@code <w:body>} 里一系列 {@code <w:p>} （段落），每段含若干
 *       {@code <w:r>}（run）。
 *   <li><b>POI</b>：{@code XWPFDocument.getParagraphs()} 给出段落列表，按顺序索引；改正文就是改某段某 run。
 *   <li><b>nondocx</b>：{@code BodyAgent} 读 {@link DocumentSnapshot} 的段落预览，按用户意图定位目标段落/run， 产出
 *       {@code replace_run_text} / {@code update_run_style} 等 operation。第一版用关键词启发式匹配 （非 LLM），为后续接入
 *       LLM 留稳定契约。
 * </ul>
 *
 * <p><b>补读策略。</b> 默认只看 paragraph 级摘要；涉及 run 细节时经 {@code ReadCoordinator} 补读（第一版 BodyAgent
 * 直接基于快照段落预览工作，run 级补读留给后续增强）。
 *
 * <p><b>边界。</b> 只产出 body 域原子 operation，不跨组，不决定保存时机。
 */
public final class BodyAgent implements ExpertAgent {

  private final AtomicLong opIdSeq = new AtomicLong();

  @Override
  public String name() {
    return "BodyAgent";
  }

  @Override
  public boolean relevantTo(String intent, DocumentSnapshot snapshot) {
    if (intent == null) return false;
    String lower = intent.toLowerCase(Locale.ROOT);
    return lower.contains("正文")
        || lower.contains("段落")
        || lower.contains("文字")
        || lower.contains("run")
        || lower.contains("替换")
        || lower.contains("加粗")
        || lower.contains("对齐")
        || lower.contains("插入段")
        || lower.contains("改成")
        || lower.contains("改为")
        || lower.contains("插入")
        || lower.contains("修改");
  }

  @Override
  public ExpertPlan plan(OrchestratorSession session, DocumentSnapshot snapshot, String intent) {
    List<Operation> ops = new ArrayList<>();

    // 启发式 1：替换正文文本——「把 X 改成 Y」/「替换 X 为 Y」
    String[] replaceParts = parseReplaceIntent(intent);
    if (replaceParts != null) {
      String from = replaceParts[0];
      String to = replaceParts[1];
      int hit = findParagraphContaining(snapshot.paragraphs(), from);
      if (hit >= 0) {
        // 默认操作 run 0（第一版不细查 run 分布；真实 LLM 场景会先补读 run 再精确指定）
        ops.add(
            BodyExecutor.replaceRunText(nextOpId(), hit, 0, to, "把「" + from + "」改成「" + to + "」"));
      }
    }

    // 启发式 2：插入段落——「在末尾/第 N 段后插入 X」
    String[] insertParts = parseInsertIntent(intent, snapshot);
    if (insertParts != null) {
      int bodyIndex = Integer.parseInt(insertParts[0]);
      String text = insertParts[1];
      ops.add(BodyExecutor.insertParagraph(nextOpId(), bodyIndex, text, "插入段落「" + text + "」"));
    }

    return new ExpertPlan(
        name(),
        "body-plan-" + session.sessionGeneration(),
        session.conversationId(),
        snapshot.snapshotVersion(),
        session.sessionGeneration(),
        ops);
  }

  String nextOpId() {
    return "body-op-" + opIdSeq.incrementAndGet();
  }

  /** 解析「把 X 改成 Y」/「替换 X 为 Y」/「将 X 替换为 Y」；不匹配返回 null。书名号/引号会被剥离。 */
  static String[] parseReplaceIntent(String intent) {
    if (intent == null) return null;
    String cleaned = stripQuotes(intent);
    // 把 X 改成 Y / 把 X 改为 Y
    int i = cleaned.indexOf("把");
    if (i >= 0) {
      int j = indexOfAny(cleaned, i, "改成", "改为", "替换成", "替换为");
      if (j > i) {
        String from = cleaned.substring(i + 1, j).trim();
        String rest = cleaned.substring(j).replaceFirst("^(改成|改为|替换成|替换为)", "").trim();
        if (!from.isEmpty() && !rest.isEmpty()) {
          return new String[] {from, rest};
        }
      }
    }
    // 替换 X 为 Y
    int k = cleaned.indexOf("替换");
    if (k >= 0) {
      int j2 = indexOfAny(cleaned, k + 2, "为", "成");
      if (j2 > k + 2) {
        String from = cleaned.substring(k + 2, j2).trim();
        String rest = cleaned.substring(j2 + 1).trim();
        if (!from.isEmpty() && !rest.isEmpty()) {
          return new String[] {from, rest};
        }
      }
    }
    return null;
  }

  /** 剥离书名号/引号，便于把「把「你好」改成「Hello」」归一成「把你好改成Hello」。 */
  private static String stripQuotes(String s) {
    return s.replaceAll("[「」『』\"\"'']", "");
  }

  /** 解析「在末尾插入 X」/「插入段 X」/「在第 N 段后插入 X」；不匹配返回 null。返回 [bodyIndex, text]。 */
  static String[] parseInsertIntent(String intent, DocumentSnapshot snapshot) {
    if (intent == null) return null;
    String cleaned = stripQuotes(intent);
    int i = cleaned.indexOf("插入");
    if (i < 0) return null;
    String rest = cleaned.substring(i + 2).trim();
    // 「末尾」→ body 元素总数
    if (rest.startsWith("末尾")) {
      String text = rest.substring(2).replaceFirst("^[，,。：: ]+", "").trim();
      if (text.isEmpty()) return null;
      int bodyCount = snapshot.overview().paragraphCount() + snapshot.overview().tableCount();
      return new String[] {String.valueOf(bodyCount), text};
    }
    // 「段 X」直接取段后的文本
    String text = rest.replaceFirst("^段[落]?", "").replaceFirst("^[，,。：: ]+", "").trim();
    if (text.isEmpty()) return null;
    // 默认插在末尾
    int bodyCount = snapshot.overview().paragraphCount() + snapshot.overview().tableCount();
    return new String[] {String.valueOf(bodyCount), text};
  }

  /** 找到第一个文本包含 {@code needle} 的段落索引；找不到返回 -1。 */
  static int findParagraphContaining(List<ParagraphPreview> paragraphs, String needle) {
    for (int i = 0; i < paragraphs.size(); i++) {
      if (paragraphs.get(i).text().contains(needle)) {
        return i;
      }
    }
    return -1;
  }

  private static int indexOfAny(String s, int from, String... needles) {
    int best = -1;
    for (String n : needles) {
      int idx = s.indexOf(n, from);
      if (idx >= 0 && (best < 0 || idx < best)) {
        best = idx;
      }
    }
    return best;
  }
}
