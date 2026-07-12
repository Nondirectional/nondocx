package com.non.docx.toolkit;

import com.non.docx.core.api.Document;
import com.non.docx.core.api.table.Cell;
import com.non.docx.core.api.text.Hyperlink;
import com.non.docx.core.api.text.Paragraph;
import com.non.docx.toolkit.ref.DocumentRef;
import com.non.docx.toolkit.ref.ElementResolver;
import com.non.docx.toolkit.ref.ReferenceContext;
import com.non.docx.toolkit.result.ToolResult;
import com.non.docx.toolkit.result.ToolResultCode;
import com.non.docx.toolkit.result.ToolResultRenderer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * nondocx-toolkit 所有工具类共享的状态与辅助方法的抽象基类。
 *
 * <p><b>它解决什么问题。</b> 把 docx 读写能力拆成多个 {@code @ToolDef} 工具类后，它们必须<b>共享同一份文档会话</b>： {@code
 * open_docx}（会话工具类）把活文档放进 sessions，{@code read_paragraph}（正文工具类）、 {@code
 * list_tracked_changes}（修订工具类）等都要从 sessions 里按 docId 取回同一份活文档。 若每个工具类各自维护一份 sessions，Agent
 * 在一轮对话里打开的文档在另一个工具类里就找不到了。故由本基类持有<b>唯一一份</b> sessions/seq， 经由 {@link DocxToolkit}
 * 门面注入到每个工具类实例，保证全工具集共享。
 *
 * <p><b>OOXML 三层递进（会话模型）：</b>
 *
 * <ul>
 *   <li><b>OOXML</b>：一份 .docx 是多个 XML part 的集合——{@code word/document.xml}（正文）、 {@code
 *       word/header1.xml}/{@code footer1.xml}（页眉页脚，各自独立 part）、 {@code word/settings.xml}（修订开关等）。
 *       它们打包在一个 ZIP（OO OPC）里，靠 relationships（{@code .rels}）互相引用。
 *   <li><b>POI</b>：{@code XWPFDocument} 是这个 ZIP 的活对象载体——它持有 {@code OPCPackage}（ZIP 包装）+ 各 part 的 CT
 *       schema bean 树（{@code CTBody}、{@code CTHdrFtr} 等），读写直接改这些树。 {@code close()} 释放底层 ZIP 句柄。
 *   <li><b>nondocx</b>：{@code Document} 包装 {@code XWPFDocument}，把 {@code word/document.xml} 的正文表为
 *       活对象链 {@code doc.paragraphs().get(i).runs().get(j)}；本 toolkit 不新增领域逻辑，只把这条链按 Agent
 *       友好的粒度逐段暴露。
 * </ul>
 *
 * <p><b>辅助方法分层。</b> 本基类提供两类辅助：
 *
 * <ul>
 *   <li><b>状态相关</b>（依赖 sessions 的实例方法）：{@link #document(String)}（按 docId 取活文档）、{@link
 *       #docNotFound(String)}（docId 不存在的中文错误串）。
 *   <li><b>无状态纯函数</b>（{@code protected static}）：入参归一化（{@link #coerceList}/{@link #getInt}/{@link
 *       #getStr}/{@link #boolVal}）、边界检查（{@link #outOfBounds}/{@link #indexError}）、异常根因（{@link
 *       #rootMessage}）、段落超链接计数（{@link #hyperlinkCount}）。
 * </ul>
 *
 * <p>定位类辅助（超链接、单元格）按使用范围下放到具体工具类，避免基类臃肿。
 *
 * <p><b>线程模型。</b> 与原单体一致——为单 Agent 实例设计，内部状态未做并发保护，不要跨 Agent 共享。
 */
public abstract class ToolkitToolContext {

  /** 打开的文档会话：docId → 活文档。{@code final} 引用保证子类共享同一份 Map。 */
  final Map<String, Document> sessions;

  /** docId 自增序号，产出 {@code "doc-1"}、{@code "doc-2"}、… */
  final AtomicInteger seq;

  /** 所有工具共享的元素引用上下文。 */
  final ReferenceContext references;

  /** docId 对应的 toolkit 会话代次。 */
  protected final Map<String, Long> generations;

  /**
   * 创建一份<b>独立</b>的会话状态（拥有自己的 sessions/seq）。
   *
   * <p>仅 {@link SessionTools} 用这个构造——它是会话的「源头」，自己建 sessions/seq，再经由 {@link DocxToolkit}
   * 把同一份注入给其它工具类。
   */
  ToolkitToolContext() {
    this.sessions = new HashMap<>();
    this.seq = new AtomicInteger();
    this.references = new ReferenceContext();
    this.generations = new HashMap<>();
  }

  /**
   * 复用<b>既有的</b>会话状态（与某个 SessionTools 共享同一份 sessions/seq）。
   *
   * <p>除 SessionTools 外的所有工具类用这个构造——它们不自己建会话，而是接收门面注入的同一份， 从而 {@code document(docId)} 能取到
   * SessionTools 那边 {@code open_docx} 放进去的活文档。
   */
  ToolkitToolContext(Map<String, Document> sharedSessions, AtomicInteger sharedSeq) {
    this(sharedSessions, sharedSeq, new ReferenceContext(), new HashMap<>());
  }

  /**
   * 复用既有文档会话、引用上下文与 generation 状态。
   *
   * <p>{@link DocxToolkit} 用此构造把 {@link SessionTools} 创建的四个共享对象注入全部工具组。
   */
  public ToolkitToolContext(
      Map<String, Document> sharedSessions,
      AtomicInteger sharedSeq,
      ReferenceContext sharedReferences,
      Map<String, Long> sharedGenerations) {
    this.sessions = sharedSessions;
    this.seq = sharedSeq;
    this.references = sharedReferences;
    this.generations = sharedGenerations;
  }

  // ==================== 状态相关：按 docId 取活文档 / 错误串 ====================

  /** 按 docId 取活文档；不存在返回 {@code null}（调用方自行决定返回哪个中文错误串）。 */
  protected Document document(String docId) {
    return sessions.get(docId);
  }

  /** 按 docId 获取当前 toolkit 会话的 resolver；文档不存在返回 {@code null}。 */
  ElementResolver elementResolver(String docId) {
    Document doc = document(docId);
    if (doc == null) {
      return null;
    }
    long generation = generations.getOrDefault(docId, 1L);
    return references.resolver(new DocumentRef(docId, generation), doc);
  }

  /**
   * 使用上层逻辑文档 key/generation 获取 resolver。
   *
   * <p>编排层使用 conversationId 作为逻辑 key，使 PERSISTENT ref 可跨 docId reopen 重新定位。
   */
  ElementResolver elementResolver(String docId, String documentKey, long generation) {
    Document doc = document(docId);
    if (doc == null) {
      return null;
    }
    return references.resolver(new DocumentRef(documentKey, generation), doc);
  }

  /** docId 不存在的统一中文错误串（沿用「不抛异常、返回错误串给 Agent」的约定）。 */
  static String docNotFound(String docId) {
    return "错误：文档句柄 " + docId + " 不存在（未 open_docx 或已 close_docx）";
  }

  // ==================== 结构化结果便捷工厂（P0-02） ====================
  //
  // 以下 render* 方法返回双段格式 String（中文消息 + JSON envelope），
  // 消除各工具类重复定义同一 docNotFound/indexError/invalidArgument 渲染逻辑。
  // 所有 @ToolDef 方法应优先使用这些共享工厂，而非各自内联 ToolResult.fail + render。

  /** 渲染 docId 不存在的结构化失败结果（双段格式）。 */
  static String renderDocNotFound(String docId) {
    return ToolResultRenderer.render(docNotFoundResult(docId));
  }

  /** docId 不存在的结构化失败结果（ToolResult 对象，供内部 helper 检查 success）。 */
  static ToolResult<Void> docNotFoundResult(String docId) {
    return ToolResult.fail(
        ToolResultCode.DOCUMENT_CLOSED, "文档句柄 " + docId + " 不存在（未 open_docx 或已 close_docx）");
  }

  /** 渲染索引越界的结构化失败结果（附 suggestion 使用 0..size-1）。 */
  static String renderIndexError(String what, int index, int size) {
    return ToolResultRenderer.render(indexErrorResult(what, index, size));
  }

  /** 索引越界的结构化失败结果（ToolResult 对象）。 */
  static ToolResult<Void> indexErrorResult(String what, int index, int size) {
    String message = what + " " + index + " 越界（共 " + size + "）";
    return ToolResult.fail(
        ToolResultCode.INDEX_OUT_OF_RANGE, message, "使用 0.." + Math.max(0, size - 1));
  }

  /** 渲染参数错误的结构化失败结果。 */
  static String renderInvalidArgument(String message) {
    return ToolResultRenderer.render(invalidArgumentResult(message));
  }

  /** 参数错误的结构化失败结果（ToolResult 对象）。 */
  static ToolResult<Void> invalidArgumentResult(String message) {
    return ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, message);
  }

  // ==================== 无状态纯函数（入参归一化 / 边界 / 异常） ====================
  //
  // 背景:nonchain 0.8.4 把 LLM 传来的 JSON 数组还原成 ArrayList<LinkedHashMap>,
  // 数字可能是 Integer/Long/Double(按大小选),不能用 (int)/(Integer) 强转(会 CCE)。
  // 另外 LLM 偶尔会把"单次调用"误传成标量(如 paragraph_indexes: 0 而非 [0]),
  // 故统一在入口归一化为 List 后再循环处理,提升健壮性。

  /**
   * 把入参归一化为 {@code List}。批量工具的统一入口预处理：
   *
   * <ul>
   *   <li>{@code null} → 空列表（等价于"无操作"）。
   *   <li>已是 {@code List} → 原样返回。
   *   <li>单个非 List 元素（LLM 误传标量）→ 包成单元素列表，让单次调用语义不被破坏。
   * </ul>
   */
  static List<Object> coerceList(Object raw) {
    if (raw == null) {
      return List.of();
    }
    if (raw instanceof List) {
      @SuppressWarnings("unchecked")
      List<Object> list = (List<Object>) raw;
      return list;
    }
    return List.of(raw);
  }

  /**
   * 从对象 Map 里取一个 int 字段。走 {@code ((Number) ...).intValue()}，兼容 Jackson 还原出的
   * Integer/Long/Double；null 或类型不符时抛 {@link IllegalArgumentException}，由调用方 catch 后
   * 转中文错误串（沿用本工具集"不抛异常给框架、返回错误串给 Agent"的约定）。
   */
  static int getInt(Map<String, Object> m, String key) {
    Object v = m.get(key);
    if (v instanceof Number) {
      return ((Number) v).intValue();
    }
    if (v instanceof String) {
      try {
        return Integer.parseInt(((String) v).trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("字段 " + key + " 不是合法整数:\"" + v + "\"");
      }
    }
    if (v == null) {
      throw new IllegalArgumentException("缺少必填字段 " + key);
    }
    throw new IllegalArgumentException("字段 " + key + " 不是整数:" + v);
  }

  /**
   * 从对象 Map 里取一个 String 字段。null 或非 String 时抛 {@link IllegalArgumentException} （理由同 {@link
   * #getInt}），由调用方 catch 转中文错误串。
   */
  static String getStr(Map<String, Object> m, String key) {
    Object v = m.get(key);
    if (v == null) {
      throw new IllegalArgumentException("缺少必填字段 " + key);
    }
    return v.toString();
  }

  /**
   * 从对象 Map 里取一个<b>可选</b>布尔字段。LLM 可能传 Boolean 或 String（"true"/"false"）； null 或缺省视为 false。 与 {@link
   * #getInt}/{@link #getStr} 不同，这里<b>不抛异常</b>（可选字段），非法值一律当 false。
   */
  static boolean boolVal(Object v) {
    if (v instanceof Boolean) {
      return (Boolean) v;
    }
    if (v instanceof String) {
      return Boolean.parseBoolean(((String) v).trim());
    }
    return false;
  }

  /** 索引是否越界（{@code index < 0 || index >= size}）。 */
  static boolean outOfBounds(int index, int size) {
    return index < 0 || index >= size;
  }

  /** 越界时的统一中文错误串（含总数，便于 Agent 据此修正索引）。 */
  static String indexError(String what, int index, int size) {
    return "错误：" + what + " " + index + " 越界（共 " + size + "）";
  }

  /** 取异常根因消息，避免把 POI/XmlBeans 长栈抛回 LLM。 */
  static String rootMessage(Throwable e) {
    Throwable cur = e;
    while (cur.getCause() != null && cur.getCause() != cur) {
      cur = cur.getCause();
    }
    return cur.getMessage();
  }

  /** 段落内超链接计数（从 inlineElements 过滤 Hyperlink，而非 runs()）。 */
  static long hyperlinkCount(Paragraph p) {
    return p.inlineElements().stream().filter(e -> e instanceof Hyperlink).count();
  }

  // ==================== 单元格定位（TableTools + TrackedChangesTools 共用） ====================

  /** 定位表格单元格；成功返回 "ok"，失败返回中文错误串（沿用工具返回值约定）。 */
  static String locateCell(Document doc, int tableIndex, int rowIndex, int cellIndex) {
    var tables = doc.tables();
    if (outOfBounds(tableIndex, tables.size())) {
      return indexError("表格索引", tableIndex, tables.size());
    }
    var rows = tables.get(tableIndex).rows();
    if (outOfBounds(rowIndex, rows.size())) {
      return indexError("行索引", rowIndex, rows.size());
    }
    var cells = rows.get(rowIndex).cells();
    if (outOfBounds(cellIndex, cells.size())) {
      return indexError("单元格索引", cellIndex, cells.size());
    }
    return "ok";
  }

  /** {@link #locateCell} 已校验过边界，这里按同样链路再取一次活 Cell。 */
  static Cell locateCellObj(Document doc, int tableIndex, int rowIndex, int cellIndex) {
    return doc.tables().get(tableIndex).rows().get(rowIndex).cells().get(cellIndex);
  }

  /** 解析表格单元格活对象；越界返回 null（调用方据此决定返回哪个错误串）。 */
  Cell resolveCell(String docId, int tableIndex, int rowIndex, int cellIndex) {
    Document doc = document(docId);
    if (doc == null) {
      return null;
    }
    var tables = doc.tables();
    if (outOfBounds(tableIndex, tables.size())) {
      return null;
    }
    var rows = tables.get(tableIndex).rows();
    if (outOfBounds(rowIndex, rows.size())) {
      return null;
    }
    var cells = rows.get(rowIndex).cells();
    if (outOfBounds(cellIndex, cells.size())) {
      return null;
    }
    return cells.get(cellIndex);
  }

  /** resolveCell 失败时的中文错误串（重新解析边界以给准确数字）。 */
  String cellResolveError(String docId, int tableIndex, int rowIndex, int cellIndex) {
    Document doc = document(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    var tables = doc.tables();
    if (outOfBounds(tableIndex, tables.size())) {
      return indexError("表格索引", tableIndex, tables.size());
    }
    var rows = tables.get(tableIndex).rows();
    if (outOfBounds(rowIndex, rows.size())) {
      return indexError("行索引", rowIndex, rows.size());
    }
    var cells = rows.get(rowIndex).cells();
    return indexError("单元格索引", cellIndex, cells.size());
  }
}
