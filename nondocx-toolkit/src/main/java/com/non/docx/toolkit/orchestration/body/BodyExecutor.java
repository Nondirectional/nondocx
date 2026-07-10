package com.non.docx.toolkit.orchestration.body;

import com.non.docx.core.api.Document;
import com.non.docx.core.api.style.Alignment;
import com.non.docx.core.api.style.HeadingLevel;
import com.non.docx.core.api.text.Paragraph;
import com.non.docx.core.api.text.Run;
import com.non.docx.toolkit.BodyTools;
import com.non.docx.toolkit.DocxToolkit;
import com.non.docx.toolkit.orchestration.ConflictKey;
import com.non.docx.toolkit.orchestration.Operation;
import com.non.docx.toolkit.orchestration.commit.OperationExecutionException;
import com.non.docx.toolkit.orchestration.commit.OperationExecutor;
import com.non.docx.toolkit.orchestration.session.OrchestratorSession;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 正文工具组的 {@link OperationExecutor}：把 body 域的 {@link Operation} 落到 {@link BodyTools} 或直接操作 core 活文档。
 *
 * <p><b>OOXML 三层递进（正文 operation 映射）：</b>
 *
 * <ul>
 *   <li><b>OOXML</b>：正文段落是 {@code <w:p>}，run 是 {@code <w:r>}，文本是 {@code <w:t>}；改文本就是改 {@code <w:t>}
 *       的内容，改样式就是改 {@code <w:rPr>} 的属性。
 *   <li><b>POI</b>：{@code XWPFParagraph.runs().get(i).setText()} 改文本，{@code setBold()/setColor()}
 *       改样式； {@code BodyTools} 把这些封装成批量 edit。
 *   <li><b>nondocx</b>：本执行器把单条 operation 的 payload 落到实际写入。细粒度操作（替换文本、改样式、 改对齐）走 {@code BodyTools}
 *       批量方法；组合语义操作（插入标题）直接操作 core 活文档，一次完成 「插入段落 + 设标题级别 + 对齐 + run 样式」。
 * </ul>
 *
 * <p><b>支持的 operation kind：</b>
 *
 * <ul>
 *   <li>{@code replace_run_text}——替换 run 文本。payload: {@code paragraph_index}, {@code run_index},
 *       {@code text}。
 *   <li>{@code update_run_style}——改 run 样式。payload: {@code paragraph_index}, {@code run_index}，可选
 *       {@code bold/italic/underline/font/font_size/color}。
 *   <li>{@code update_paragraph_alignment}——改段落对齐。payload: {@code paragraph_index}, {@code
 *       alignment}。
 *   <li>{@code insert_paragraph}——插入纯文本段落。payload: {@code body_index}, {@code text}。
 *   <li>{@code insert_heading}——插入标题段落（组合操作）。payload: {@code text}, {@code heading_level} (H1~H6),
 *       可选 {@code alignment}(CENTER/LEFT/RIGHT), 可选 {@code font_size}, 可选 {@code position}
 *       (start/end/after:N)。直接操作 core 活文档，一次完成插入 + 标题样式 + 对齐 + 字号。
 * </ul>
 *
 * <p><b>payload 字段容错。</b> LLM 常产出 {@code paragraph_index} 而非 {@code body_index}（两者在无表格时等价）， 或产出
 * {@code style=Heading1} 而非 {@code heading_level=H1}。本执行器对这些常见偏差做归一化，提升 LLM 产出命中率，减少因字段名不匹配导致的静默失败。
 *
 * <p>调用结果若含 BodyTools 的失败标志（「错误」开头或含「错误:」）视为失败，抛 {@link OperationExecutionException}。
 */
public final class BodyExecutor implements OperationExecutor {

  private final BodyTools body;
  private final DocxToolkit toolkit;

  /** 旧构造（仅 BodyTools）：不直接操作 core，无法处理 insert_heading。 */
  public BodyExecutor(BodyTools body) {
    this(body, null);
  }

  /**
   * 全参构造：既能走 BodyTools 批量方法，也能直接操作 core 活文档（insert_heading 需要）。
   *
   * @param body 正文工具组
   * @param toolkit 工具聚合器（用于取活 Document；insert_heading 需要）
   */
  public BodyExecutor(BodyTools body, DocxToolkit toolkit) {
    this.body = body;
    this.toolkit = toolkit;
  }

  @Override
  public boolean canHandle(Operation operation) {
    return "body".equals(operation.toolGroup());
  }

  @Override
  public String execute(OrchestratorSession session, Operation operation)
      throws OperationExecutionException {
    String docId = session.docId();
    Map<String, Object> payload = new LinkedHashMap<>(operation.payload());
    String kind = operation.kind();
    try {
      switch (kind) {
        case "replace_run_text":
          {
            Map<String, Object> edit = pickedEdit(payload, "paragraph_index", "run_index", "text");
            return checkResult(body.replaceRunText(docId, List.of(edit)), operation);
          }
        case "update_run_style":
          {
            Map<String, Object> edit =
                pickedEdit(
                    payload,
                    "paragraph_index",
                    "run_index",
                    "bold",
                    "italic",
                    "underline",
                    "font",
                    "font_size",
                    "color");
            return checkResult(body.updateRunStyle(docId, List.of(edit)), operation);
          }
        case "update_paragraph_alignment":
          {
            Map<String, Object> edit = pickedEdit(payload, "paragraph_index", "alignment");
            return checkResult(body.updateParagraphAlignment(docId, List.of(edit)), operation);
          }
        case "insert_paragraph":
          {
            // 容错：LLM 常用 paragraph_index，无表格时与 body_index 等价；
            // 有表格时两者不可互换，必须用 body_index（否则段落会落在错误的表格侧）。
            normalizeBodyIndex(payload, session, operation);
            Map<String, Object> edit = pickedEdit(payload, "body_index", "text");
            return checkResult(body.insertParagraph(docId, List.of(edit)), operation);
          }
        case "insert_heading":
          return executeInsertHeading(session, payload, operation);
        default:
          throw new OperationExecutionException("body 域不支持的 operation kind: " + kind);
      }
    } catch (OperationExecutionException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new OperationExecutionException("body/" + kind + " 执行异常", e);
    }
  }

  // ==================== insert_heading：直接操作 core 活文档 ====================

  /**
   * 插入标题段落：一次性完成「插入段落 + 设标题级别 + 对齐 + run 字号」。
   *
   * <p>这是组合语义操作，BodyTools 没有对应的单工具；这里直接操作 core 的 {@link Document} / {@link Paragraph} / {@link
   * Run}，一次完成多步写入。需要 toolkit 注入（否则抛异常提示）。
   */
  private String executeInsertHeading(
      OrchestratorSession session, Map<String, Object> payload, Operation operation)
      throws OperationExecutionException {
    if (toolkit == null) {
      throw new OperationExecutionException(
          "insert_heading 需要 DocxToolkit 注入（用 new BodyExecutor(body, toolkit) 构造）");
    }
    Document doc = toolkit.session.getDocument(session.docId());
    if (doc == null) {
      throw new OperationExecutionException("文档句柄 " + session.docId() + " 不存在");
    }

    String text = strPayload(payload, "text");
    HeadingLevel level = parseHeadingLevel(payload);
    Alignment alignment = parseAlignment(payload);
    int fontSize = payload.containsKey("font_size") ? intPayload(payload, "font_size") : -1;
    String position = strPayloadOptional(payload, "position", "start");

    // 计算 body 插入位置
    int bodyIndex = resolveBodyIndex(doc, position, payload);

    Paragraph p = doc.insertParagraph(bodyIndex);
    if (level != null) {
      p.heading(level);
    }
    if (alignment != null) {
      p.alignment(alignment);
    }
    Run run = p.addRun(text);
    if (fontSize > 0) {
      run.fontSize(fontSize);
    }
    return "body "
        + bodyIndex
        + " 插入标题「"
        + text
        + "」("
        + (level == null ? "无级别" : level.name())
        + (alignment == null ? "" : "/" + alignment.name())
        + (fontSize > 0 ? "/" + fontSize + "pt" : "")
        + ") ✓";
  }

  /** 解析 position（start/end/after:N）为 body_index。 */
  private static int resolveBodyIndex(Document doc, String position, Map<String, Object> payload) {
    int bodySize = doc.bodyElements().size();
    if (payload.containsKey("body_index")) {
      return intPayload(payload, "body_index");
    }
    if ("end".equalsIgnoreCase(position)) {
      return bodySize;
    }
    if (position != null && position.toLowerCase(Locale.ROOT).startsWith("after:")) {
      try {
        int after = Integer.parseInt(position.substring(6).trim());
        return Math.min(after + 1, bodySize);
      } catch (NumberFormatException ignored) {
        // fall through
      }
    }
    // 默认 start
    return 0;
  }

  /** 把 HeadingLevel 从 payload 解析出来，兼容 heading_level / style 两种字段名。 */
  private static HeadingLevel parseHeadingLevel(Map<String, Object> payload) {
    String raw = null;
    if (payload.containsKey("heading_level")) {
      raw = String.valueOf(payload.get("heading_level"));
    } else if (payload.containsKey("style")) {
      raw = String.valueOf(payload.get("style"));
    } else if (payload.containsKey("heading")) {
      raw = String.valueOf(payload.get("heading"));
    }
    if (raw == null || raw.isBlank()) return null;
    String upper = raw.trim().toUpperCase(Locale.ROOT);
    // 兼容 "Heading1" / "H1" / "1" 三种格式
    String digits = upper.replaceAll("[^0-9]", "");
    if (digits.isEmpty()) return null;
    try {
      int n = Integer.parseInt(digits);
      if (n >= 1 && n <= 6) return HeadingLevel.values()[n - 1];
    } catch (NumberFormatException ignored) {
      // fall through
    }
    return null;
  }

  /** 解析 alignment，兼容 CENTER/center/Center。 */
  private static Alignment parseAlignment(Map<String, Object> payload) {
    String raw = payload.containsKey("alignment") ? String.valueOf(payload.get("alignment")) : null;
    if (raw == null || raw.isBlank()) return null;
    try {
      return Alignment.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  // ==================== payload 归一化与提取 ====================

  /**
   * 把 paragraph_index 归一化为 body_index（仅当 payload 缺 body_index 时）。
   *
   * <p>LLM 常产出 {@code paragraph_index} 而非 {@code body_index}。在无表格的文档里两者等价 （段落索引 = body 顺序索引），此时退回
   * paragraph_index 是安全的。但<b>有表格时两者不可互换</b>—— 段落索引跳过表格，把它当 body 索引会导致段落落在表格的错误一侧 （见任务
   * 07-10-body-insert-position-table-boundary）。 因此有表格时拒绝静默翻译， 抛异常让 LLM 改用 body_index。
   *
   * @param payload 操作 payload（可能被原地修改：补 body_index）
   * @param session 编排会话（用于取活 Document 检查是否含表格）
   * @param operation 原操作（用于异常消息）
   */
  private void normalizeBodyIndex(
      Map<String, Object> payload, OrchestratorSession session, Operation operation)
      throws OperationExecutionException {
    if (payload.containsKey("body_index")) {
      return; // 已有 body_index，无需归一化
    }
    if (!payload.containsKey("paragraph_index")) {
      return; // 两者都没有，交给 BodyTools 报「缺少必填字段 body_index」
    }
    // 有 paragraph_index 无 body_index：检查文档是否含表格
    if (toolkit != null) {
      Document doc = toolkit.session.getDocument(session.docId());
      if (doc != null && !doc.tables().isEmpty()) {
        throw new OperationExecutionException(
            "文档含表格，insert_paragraph 必须用 body_index（body 顺序索引，含表格），"
                + "不能用 paragraph_index（段落索引，跳过表格）。"
                + "请从快照的 body 顺序预览中读取正确的 body_index。");
      }
    }
    // 无表格（或无法确认）：退回 paragraph_index，与历史行为兼容
    payload.put("body_index", payload.get("paragraph_index"));
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

  private static String strPayload(Map<String, Object> payload, String key)
      throws OperationExecutionException {
    Object v = payload.get(key);
    if (v == null) {
      throw new OperationExecutionException("缺少必填字段 " + key);
    }
    return String.valueOf(v);
  }

  private static String strPayloadOptional(Map<String, Object> payload, String key, String def) {
    Object v = payload.get(key);
    return v == null ? def : String.valueOf(v);
  }

  private static int intPayload(Map<String, Object> payload, String key) {
    Object v = payload.get(key);
    if (v instanceof Number) return ((Number) v).intValue();
    try {
      return Integer.parseInt(String.valueOf(v).trim());
    } catch (NumberFormatException e) {
      throw new OperationExecutionException("字段 " + key + " 不是合法整数:\"" + v + "\"");
    }
  }

  /**
   * 检查 BodyTools 返回串是否表示执行失败。
   *
   * <p>BodyTools 批量方法（单元素调用也走批量路径）有两种失败格式：整体失败（以「错误」开头）和单条失败 （含「错误:」或「错误：」子串，如 {@code "[0]
   * 错误:缺少必填字段 body_index"}）。两种都要检测，否则 会把单条失败误判为成功（commit 报告 executed=1 但文档实际没改）。
   */
  private static String checkResult(String result, Operation operation)
      throws OperationExecutionException {
    if (result == null) {
      throw new OperationExecutionException("body/" + operation.kind() + " 返回 null");
    }
    if (result.startsWith("错误")) {
      throw new OperationExecutionException(result);
    }
    if (result.contains("错误:") || result.contains("错误：")) {
      throw new OperationExecutionException("body/" + operation.kind() + " 执行失败: " + result);
    }
    return result;
  }

  // ==================== Operation 构造便捷方法（供 BodyAgent 使用） ====================

  /** 构造一条 replace_run_text operation。 */
  public static Operation replaceRunText(
      String opId, int paragraphIndex, int runIndex, String text, String intent) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("paragraph_index", paragraphIndex);
    payload.put("run_index", runIndex);
    payload.put("text", text);
    String target = "p:" + paragraphIndex + "/r:" + runIndex;
    return Operation.of(
        opId,
        "body",
        "replace_run_text",
        target,
        payload,
        new ConflictKey("body", "replace_run_text", target),
        intent,
        "替换 run 文本",
        "");
  }

  /** 构造一条 update_run_style operation。 */
  public static Operation updateRunStyle(
      String opId,
      int paragraphIndex,
      int runIndex,
      Map<String, Object> styleFields,
      String intent) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("paragraph_index", paragraphIndex);
    payload.put("run_index", runIndex);
    payload.putAll(styleFields);
    String target = "p:" + paragraphIndex + "/r:" + runIndex;
    return Operation.of(
        opId,
        "body",
        "update_run_style",
        target,
        payload,
        new ConflictKey("body", "update_run_style", target),
        intent,
        "改 run 样式",
        "");
  }

  /** 构造一条 update_paragraph_alignment operation。 */
  public static Operation updateParagraphAlignment(
      String opId, int paragraphIndex, String alignment, String intent) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("paragraph_index", paragraphIndex);
    payload.put("alignment", alignment);
    String target = "p:" + paragraphIndex;
    return Operation.of(
        opId,
        "body",
        "update_paragraph_alignment",
        target,
        payload,
        new ConflictKey("body", "update_paragraph_alignment", target),
        intent,
        "改段落对齐",
        "");
  }

  /** 构造一条 insert_paragraph operation。 */
  public static Operation insertParagraph(String opId, int bodyIndex, String text, String intent) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("body_index", bodyIndex);
    payload.put("text", text);
    String target = "body:" + bodyIndex;
    return Operation.of(
        opId,
        "body",
        "insert_paragraph",
        target,
        payload,
        new ConflictKey("body", "insert_paragraph", target),
        intent,
        "插入段落",
        "");
  }
}
