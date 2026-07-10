package com.non.docx.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.non.chain.ChatResult;
import com.non.chain.Message;
import com.non.chain.provider.LLM;
import com.non.docx.toolkit.orchestration.ConflictKey;
import com.non.docx.toolkit.orchestration.DocumentSnapshot;
import com.non.docx.toolkit.orchestration.ExpertPlan;
import com.non.docx.toolkit.orchestration.Operation;
import com.non.docx.toolkit.orchestration.agent.ExpertAgent;
import com.non.docx.toolkit.orchestration.session.OrchestratorSession;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * demo 专用的 LLM-backed 通用专家：用 nonchain Agent 让 LLM 基于快照与意图产出 JSON operation， 再解析为强类型 {@link
 * Operation}。
 *
 * <p><b>为什么是「通用」而非按工具组拆。</b> demo 场景下用户意图多变，按工具组拆多个 LLM 专家会重复发 LLM 请求且协调复杂。第一版用一个通用 LLM
 * 专家承接所有意图，让它产出跨 body/table/revision 等域的 operation——RouterAgent 仍负责合并、review 与提交，安全边界不变（子代理只产出
 * plan，写仍走 CommitCoordinator）。
 *
 * <p><b>LLM 产出格式。</b> system prompt 约束 LLM 输出严格 JSON：
 *
 * <pre>{@code
 * {
 *   "operations": [
 *     {"toolGroup":"body","kind":"replace_run_text","targetRef":"p:0/r:0",
 *      "payload":{"paragraph_index":0,"run_index":0,"text":"新文本"},
 *      "intent":"...","reason":"...","riskNote":"..."}
 *   ]
 * }
 * }</pre>
 *
 * <p>本专家负责把 JSON 解析为强类型 Operation（含 ConflictKey），非法/缺字段项被跳过并记日志。
 */
final class LlmDocxExpert implements ExpertAgent {

  private static final Logger log = LoggerFactory.getLogger(LlmDocxExpert.class);

  private final LLM llm;
  private final ObjectMapper json = new ObjectMapper();
  private final AtomicLong opIdSeq = new AtomicLong();

  LlmDocxExpert(LLM llm) {
    this.llm = llm;
  }

  @Override
  public String name() {
    return "LlmDocxExpert";
  }

  @Override
  public boolean relevantTo(String intent, DocumentSnapshot snapshot) {
    // 通用专家：对任何非空意图都 relevant
    return intent != null && !intent.isBlank();
  }

  @Override
  public ExpertPlan plan(OrchestratorSession session, DocumentSnapshot snapshot, String intent) {
    log.debug(
        "开始规划: paragraphs={}, tables={}, intent={}",
        snapshot.overview().paragraphCount(),
        snapshot.overview().tableCount(),
        intent.length() > 60 ? intent.substring(0, 60) + "..." : intent);
    // 用 nonchain Agent 让 LLM 产出 JSON operation
    // Agent 无工具注册——只让它基于 snapshot 文本 + intent 纯推理产出 JSON（不调 toolkit）
    String prompt = buildPrompt(snapshot, intent);
    String llmOutput = callLlm(prompt);
    List<Operation> ops = parseOperations(llmOutput);
    log.info("规划完成: 产出 {} 条 operation", ops.size());
    for (Operation op : ops) {
      log.info(
          "  operation: id={}, toolGroup={}, kind={}, targetRef={}, payload={}",
          op.operationId(),
          op.toolGroup(),
          op.kind(),
          op.targetRef(),
          op.payload());
    }
    return new ExpertPlan(
        name(),
        "llm-plan-" + session.sessionGeneration(),
        session.conversationId(),
        snapshot.snapshotVersion(),
        session.sessionGeneration(),
        ops);
  }

  /** 构造给 LLM 的 prompt：注入快照摘要 + 意图 + 输出格式约束。 */
  private String buildPrompt(DocumentSnapshot snapshot, String intent) {
    StringBuilder sb = new StringBuilder();
    sb.append("你是一个 docx 文档编辑计划生成器。基于以下文档快照与用户意图，" + "产出要执行的编辑操作列表（严格 JSON，不要输出任何解释文本）。\n\n");
    sb.append("## 文档快照\n");
    sb.append("- 段落数: ").append(snapshot.overview().paragraphCount()).append('\n');
    sb.append("- 表格数: ").append(snapshot.overview().tableCount()).append('\n');
    sb.append("- body 顺序预览（段落和表格交错排列，各占一个位置）：\n");
    appendBodyOrderView(sb, snapshot);
    sb.append("\n**索引说明（重要）**：\n");
    sb.append("- body:N = body 顺序索引（含表格的绝对位置）。insert_paragraph 的 body_index 用这个。\n");
    sb.append(
        "- para:N = 段落索引（跳过表格的投影序号）。replace_run_text/update_run_style/"
            + "update_paragraph_alignment 的 paragraph_index 用这个。\n");
    sb.append("- table:N = 表格索引（跳过段落的投影序号）。replace_table_cell_run_text 的 table_index 用这个。\n");
    sb.append(
        "- 例如 body 顺序为 [表格, 段落A, 段落B] 时，段落A 的 para=0/body=1，"
            + "在表格前插入用 body_index=0，在末尾插入用 body_index=3。\n");
    sb.append("\n## 用户意图\n").append(intent).append('\n');
    sb.append("\n## 支持的操作类型（必须严格匹配字段名）\n\n");
    sb.append("### 插入标题（用于「加标题」「加大标题」等）\n");
    sb.append("toolGroup=\"body\", kind=\"insert_heading\"\n");
    sb.append("payload 必填: {\"text\":\"标题文字\",\"heading_level\":\"H1\"}\n");
    sb.append("payload 可选: {\"alignment\":\"CENTER\",\"font_size\":28,\"position\":\"start\"}\n");
    sb.append("heading_level: H1/H2/H3/H4/H5/H6；position: start(开头,默认)/end(末尾)/after:N(第N段后)\n");
    sb.append(
        "示例: {\"text\":\"项目周报\",\"heading_level\":\"H1\",\"alignment\":\"CENTER\",\"position\":\"start\"}\n\n");
    sb.append("### 插入普通段落\n");
    sb.append("toolGroup=\"body\", kind=\"insert_paragraph\"\n");
    sb.append(
        "payload: {\"body_index\":N,\"text\":\"段落文字\"}（N 是 body 顺序索引，"
            + "从上面的 body 顺序预览中读取；body_index=0 插在最前面，"
            + "body_index=段落数+表格数 插在最后面）\n\n");
    sb.append("### 替换已有 run 的文本\n");
    sb.append("toolGroup=\"body\", kind=\"replace_run_text\"\n");
    sb.append("payload: {\"paragraph_index\":0,\"run_index\":0,\"text\":\"新文本\"}\n\n");
    sb.append("### 修改 run 样式（加粗/字号/颜色等）\n");
    sb.append("toolGroup=\"body\", kind=\"update_run_style\"\n");
    sb.append(
        "payload: {\"paragraph_index\":0,\"run_index\":0,\"bold\":true,\"font_size\":16,\"color\":\"FF0000\"}\n\n");
    sb.append("### 修改段落对齐\n");
    sb.append("toolGroup=\"body\", kind=\"update_paragraph_alignment\"\n");
    sb.append("payload: {\"paragraph_index\":0,\"alignment\":\"CENTER\"}\n\n");
    sb.append("### 修改表格单元格文本\n");
    sb.append("toolGroup=\"table\", kind=\"replace_table_cell_run_text\"\n");
    sb.append(
        "payload: {\"table_index\":0,\"row_index\":0,\"cell_index\":0,\"paragraph_index\":0,\"run_index\":0,\"text\":\"内容\"}\n\n");
    sb.append("### 合并单元格（纵向合并同一列的多行 / 横向合并同一行的多列）\n");
    sb.append("toolGroup=\"table\", kind=\"merge_table_cells\"\n");
    sb.append("纵向合并（如同列的第1~3行合并）:\n");
    sb.append(
        "payload: {\"table_index\":0,\"direction\":\"VERTICAL\",\"cell_index\":2,"
            + "\"from_row_index\":1,\"to_row_index\":2}\n");
    sb.append("横向合并（如同行的第0~2列合并）:\n");
    sb.append(
        "payload: {\"table_index\":0,\"direction\":\"HORIZONTAL\",\"row_index\":0,"
            + "\"from_cell_index\":0,\"to_cell_index\":2}\n\n");
    sb.append("### 设置表格边框（无边框 / 改边框样式）\n");
    sb.append("toolGroup=\"table\", kind=\"set_table_borders\"\n");
    sb.append(
        "payload: {\"table_index\":0,\"border_style\":\"NONE\"}（border_style 当前仅支持 NONE=无边框）\n");
    sb.append("用于「去掉表格边框」「表格设为无边框」等需求\n\n");
    sb.append("## 输出格式（严格 JSON）\n");
    sb.append(
        "{\"operations\":[{\"toolGroup\":\"body\",\"kind\":\"insert_heading\","
            + "\"targetRef\":\"heading:1\","
            + "\"payload\":{\"text\":\"标题\",\"heading_level\":\"H1\"},"
            + "\"intent\":\"简述\",\"reason\":\"理由\",\"riskNote\":\"\"}]}\n");
    sb.append("\n规则:\n");
    sb.append("- 只输出 JSON，不要 markdown 代码块标记，不要解释文本\n");
    sb.append("- 无操作时输出 {\"operations\":[]}\n");
    sb.append(
        "- 复合需求（如「加标题并居中且大字号」）尽量用一条 insert_heading 搞定，"
            + "alignment/font_size/heading_level 一次设齐\n");
    sb.append("- 不要编造不存在的 kind 或字段名\n");
    return sb.toString();
  }

  /**
   * 把快照的段落预览和表格预览按 body 顺序（bodyIndex）交错合并，渲染成 LLM 可读的 body 顺序视图。
   *
   * <p>段落和表格各自携带 bodyIndex（见 {@link
   * com.non.docx.toolkit.orchestration.snapshot.ParagraphPreview#bodyIndex()} / {@link
   * com.non.docx.toolkit.orchestration.snapshot.TablePreview#bodyIndex()}），按 bodyIndex 升序合并后逐行输出，让
   * LLM 看到真实的 body 结构——表格在哪里、段落在哪里。
   */
  private static void appendBodyOrderView(StringBuilder sb, DocumentSnapshot snapshot) {
    // 合并段落和表格到一个按 bodyIndex 排序的列表
    List<BodyItem> items = new ArrayList<>();
    for (var p : snapshot.paragraphs()) {
      items.add(new BodyItem(p.bodyIndex(), true, p));
    }
    for (var t : snapshot.tables()) {
      items.add(new BodyItem(t.bodyIndex(), false, t));
    }
    items.sort(java.util.Comparator.comparingInt(BodyItem::bodyIndex));
    for (BodyItem item : items) {
      sb.append("  [body:").append(item.bodyIndex).append("] ");
      if (item.isParagraph) {
        var p = (com.non.docx.toolkit.orchestration.snapshot.ParagraphPreview) item.ref;
        sb.append("段落 (para:").append(p.index()).append("): ").append(p.text());
        if (p.headingLevel() != null) {
          sb.append(" [H").append(p.headingLevel()).append("]");
        }
      } else {
        var t = (com.non.docx.toolkit.orchestration.snapshot.TablePreview) item.ref;
        sb.append("表格 (table:")
            .append(t.index())
            .append("): ")
            .append(t.rowCount())
            .append("行×")
            .append(t.columnCount())
            .append("列");
        if (!t.cellSamples().isEmpty() && !t.cellSamples().get(0).isEmpty()) {
          sb.append(" 首行: ").append(String.join(" | ", t.cellSamples().get(0)));
        }
      }
      sb.append('\n');
    }
  }

  /** body 顺序视图的合并中间项。 */
  private static final class BodyItem {
    final int bodyIndex;
    final boolean isParagraph;
    final Object ref;

    BodyItem(int bodyIndex, boolean isParagraph, Object ref) {
      this.bodyIndex = bodyIndex;
      this.isParagraph = isParagraph;
      this.ref = ref;
    }

    int bodyIndex() {
      return bodyIndex;
    }
  }

  /** 同步调用 LLM，返回原始文本输出。 */
  private String callLlm(String prompt) {
    try {
      Message message = Message.user(prompt);
      ChatResult result = llm.chat(List.of(message));
      return result.content().trim();
    } catch (RuntimeException e) {
      // LLM 调用失败——返回空 plan，不阻断流程
      log.warn("LLM 调用失败,返回空 plan: {}", rootMessage(e));
      return "{\"operations\":[]}";
    }
  }

  /** 解析 LLM 输出的 JSON 为强类型 Operation 列表；非法项跳过。 */
  private List<Operation> parseOperations(String llmOutput) {
    List<Operation> ops = new ArrayList<>();
    try {
      // 兜底：去掉可能的 markdown 代码块标记
      String cleaned =
          llmOutput.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "").trim();
      JsonNode root = json.readTree(cleaned);
      JsonNode arr = root.path("operations");
      if (!arr.isArray()) return ops;
      for (JsonNode item : arr) {
        try {
          Operation op = parseOne(item);
          if (op != null) ops.add(op);
        } catch (RuntimeException ex) {
          // 单条解析失败跳过，继续处理其余
          log.warn("跳过无法解析的 operation: {}", item, ex);
        }
      }
    } catch (Exception ex) {
      // 整体 JSON 解析失败——返回空列表
      log.warn(
          "LLM 输出 JSON 解析失败,返回空列表。原始输出前 200 字: {}",
          llmOutput.length() > 200 ? llmOutput.substring(0, 200) + "..." : llmOutput,
          ex);
    }
    return ops;
  }

  private Operation parseOne(JsonNode item) {
    String toolGroup = text(item, "toolGroup");
    String kind = text(item, "kind");
    String targetRef = text(item, "targetRef");
    if (toolGroup.isEmpty() || kind.isEmpty() || targetRef.isEmpty()) return null;

    Map<String, Object> payload = new LinkedHashMap<>();
    JsonNode payloadNode = item.path("payload");
    if (payloadNode.isObject()) {
      payloadNode.fields().forEachRemaining(e -> payload.put(e.getKey(), jsonValue(e.getValue())));
    }
    String intentText = text(item, "intent");
    String reason = text(item, "reason");
    String riskNote = text(item, "riskNote");

    return Operation.of(
        "llm-op-" + opIdSeq.incrementAndGet(),
        toolGroup.toLowerCase(Locale.ROOT),
        kind,
        targetRef,
        payload,
        new ConflictKey(toolGroup.toLowerCase(Locale.ROOT), kind, targetRef),
        intentText,
        reason,
        riskNote);
  }

  private static String text(JsonNode node, String field) {
    JsonNode v = node.path(field);
    return v.isTextual() ? v.asText() : (v.isMissingNode() ? "" : v.toString());
  }

  /** 取异常链最底层的 message（顶层常是包装异常，真正原因藏在 cause 里）。 */
  private static String rootMessage(Throwable e) {
    Throwable cur = e;
    while (cur.getCause() != null && cur.getCause() != cur) {
      cur = cur.getCause();
    }
    return cur.getMessage();
  }

  private Object jsonValue(JsonNode node) {
    if (node.isInt()) return node.asInt();
    if (node.isLong()) return node.asLong();
    if (node.isDouble()) return node.asDouble();
    if (node.isBoolean()) return node.asBoolean();
    return node.asText();
  }
}
