package io.github.nondirectional.docx.toolkit;

import com.non.chain.tool.ToolDef;
import com.non.chain.tool.ToolParam;
import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.toolkit.capability.CapabilityOperation;
import io.github.nondirectional.docx.toolkit.capability.ParamCapability;
import io.github.nondirectional.docx.toolkit.capability.ParamType;
import io.github.nondirectional.docx.toolkit.capability.ToolCapability;
import io.github.nondirectional.docx.toolkit.ref.ElementResolver;
import io.github.nondirectional.docx.toolkit.ref.ReferenceContext;
import io.github.nondirectional.docx.toolkit.result.ToolResult;
import io.github.nondirectional.docx.toolkit.result.ToolResultCode;
import io.github.nondirectional.docx.toolkit.result.ToolResultRenderer;
import io.github.nondirectional.docx.toolkit.view.DocumentViewService;
import io.github.nondirectional.docx.toolkit.view.dto.StatsView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 文档会话工具组（原 A 组）：打开 / 保存 / 关闭文档，以及文档结构概览。
 *
 * <p><b>会话模型的「源头」。</b> 本类是 toolkit 里<b>唯一</b>自己创建 {@code sessions}/{@code seq} 的工具类 （走 {@link
 * ToolkitToolContext#ToolkitToolContext()} 无参构造）。 {@link DocxToolkit} 门面在构造时先把本类实例化， 再经 {@link
 * #sharedSessions()}/{@link #sharedSeq()} 把这<b>同一份</b>会话状态 注入给其它四个工具类，从而全工具集共享。
 *
 * <p><b>会话语义（docId）。</b> 一次 {@code open_docx} 返回字符串句柄 {@code "doc-<n>"}，后续所有工具只传 {@code docId} +
 * 索引，不传文件路径。这对应 nondocx 的活对象语义：一次打开、多次读写、 {@code save_docx} 才落盘。
 *
 * <p><b>返回值约定。</b> 统一返回 {@code String}（nonchain 的 {@code ToolRegistry} 走 {@code
 * result.toString()}）。 失败返回 <em>中文错误描述串</em>而非抛异常——Agent 能把错误读回并自行修正，更贴合 Agent 循环语义。
 */
public final class SessionTools extends ToolkitToolContext {

  /** 创建一份<b>独立</b>会话（拥有自己的 sessions/seq）。门面会复用本实例的会话状态注入给其它工具。 */
  public SessionTools() {
    super();
  }

  /** 暴露共享 sessions 供门面注入给其它工具类（包级可见，门面在同包）。 */
  Map<String, Document> sharedSessions() {
    return sessions;
  }

  /** 暴露共享 seq 供门面注入给其它工具类（包级可见）。 */
  AtomicInteger sharedSeq() {
    return seq;
  }

  /** 暴露共享引用上下文供门面注入给其它工具类。 */
  ReferenceContext sharedReferences() {
    return references;
  }

  /** 暴露共享 generation 状态供门面注入给其它工具类。 */
  Map<String, Long> sharedGenerations() {
    return generations;
  }

  /**
   * 语义视图服务引用。由 {@link DocxToolkit} 在构造末尾绑定，供 {@code get_document_overview} 委托。
   *
   * <p>延迟绑定是因为 {@link DocumentViewService} 依赖 {@link QualityCheckTools}，而后者在门面构造里晚于 {@code
   * SessionTools} 创建。未绑定时（独立使用 SessionTools）走旧概览逻辑，保持兼容。
   */
  private DocumentViewService viewService;

  /** 门面在构造末尾绑定视图服务，让 {@code get_document_overview} 委托 {@code view_stats}。 */
  void bindViewService(DocumentViewService viewService) {
    this.viewService = viewService;
  }

  /**
   * 按 docId 取活文档（公共 API）。
   *
   * <p>供应用层的受限保存、质量检查和语义视图查询当前会话的活文档。不暴露整个 sessions Map，只给按 id 查询。
   *
   * @param docId 文档句柄
   * @return 活文档；句柄不存在返回 null
   */
  public Document getDocument(String docId) {
    return sessions.get(docId);
  }

  /** 返回直接 toolkit 会话使用的元素 resolver；句柄不存在返回 {@code null}。 */
  public ElementResolver getElementResolver(String docId) {
    return elementResolver(docId);
  }

  /**
   * 返回编排层逻辑文档使用的元素 resolver。
   *
   * @param docId 底层文档句柄
   * @param documentKey 稳定逻辑文档 key，通常为 conversationId
   * @param generation 当前编排会话代次
   * @return resolver；句柄不存在返回 {@code null}
   */
  public ElementResolver getElementResolver(String docId, String documentKey, long generation) {
    return elementResolver(docId, documentKey, generation);
  }

  // ==================== 工具方法 ====================

  /**
   * 打开一个 .docx 文件，返回文档句柄 docId。
   *
   * @param path 文档路径（绝对路径或相对工作目录的路径）
   * @return 形如 {@code "doc-1"} 的句柄；打开失败返回中文错误串
   */
  @ToolDef(name = "open_docx", description = "打开一个 .docx 文件，返回文档句柄 docId，后续工具用它定位文档")
  @ToolCapability(operation = CapabilityOperation.SESSION)
  public String openDocx(
      @ToolParam(name = "path", description = "文档路径（绝对路径）") @ParamCapability(type = ParamType.PATH)
          String path) {
    try {
      Document doc = Docx.open(Path.of(path));
      String docId = "doc-" + seq.incrementAndGet();
      sessions.put(docId, doc);
      generations.put(docId, 1L);
      ToolResult<String> result = ToolResult.ok(docId, "已打开文档 " + path + "，句柄 " + docId);
      return ToolResultRenderer.render(result);
    } catch (RuntimeException e) {
      String msg = "无法打开文档 " + path + "（" + rootMessage(e) + "）";
      ToolResult<Void> result = ToolResult.fail(ToolResultCode.DOCUMENT_CORRUPT, msg);
      return ToolResultRenderer.render(result);
    }
  }

  /**
   * 把指定文档保存到输出路径（落盘）。
   *
   * @param docId 文档句柄
   * @param outputPath 输出文件路径
   * @return 保存结果（含输出路径）；失败返回中文错误串
   */
  @ToolDef(name = "save_docx", description = "把指定 docId 的文档保存到 output_path（覆盖写），返回保存结果")
  @ToolCapability(operation = CapabilityOperation.SESSION)
  public String saveDocx(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "output_path", description = "输出文件路径（绝对路径）")
          @ParamCapability(type = ParamType.PATH)
          String outputPath) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      ToolResult<Void> result =
          ToolResult.fail(
              ToolResultCode.DOCUMENT_CLOSED, "文档句柄 " + docId + " 不存在（未 open_docx 或已 close_docx）");
      return ToolResultRenderer.render(result);
    }
    try {
      Path out = Path.of(outputPath);
      Path parent = out.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      doc.save(out);
      ToolResult<String> result =
          ToolResult.ok(out.toAbsolutePath().toString(), "已保存到 " + out.toAbsolutePath());
      return ToolResultRenderer.render(result);
    } catch (Exception e) {
      String msg = "无法保存到 " + outputPath + "（" + rootMessage(e) + "）";
      ToolResult<Void> result = ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, msg);
      return ToolResultRenderer.render(result);
    }
  }

  /**
   * 关闭并移除文档会话（幂等）。
   *
   * @param docId 文档句柄
   * @return 关闭结果；句柄不存在视为已关闭
   */
  @ToolDef(name = "close_docx", description = "关闭并释放指定 docId 的文档会话（幂等：未打开也返回成功）")
  @ToolCapability(operation = CapabilityOperation.SESSION)
  public String closeDocx(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId) {
    Document doc = sessions.remove(docId);
    generations.remove(docId);
    references.invalidate(docId);
    if (doc == null) {
      ToolResult<Void> result = ToolResult.ok("文档 " + docId + " 未打开（视为已关闭）");
      return ToolResultRenderer.render(result);
    }
    try {
      doc.close();
    } catch (RuntimeException e) {
      String msg = "文档 " + docId + " 已从会话移除，但关闭时出错：" + rootMessage(e);
      ToolResult<Void> result = ToolResult.fail(ToolResultCode.DOCUMENT_CLOSED, msg);
      return ToolResultRenderer.render(result);
    }
    ToolResult<Void> result = ToolResult.ok("已关闭 " + docId);
    return ToolResultRenderer.render(result);
  }

  /**
   * 用磁盘上的干净版本替换会话内指定 docId 的活文档，<b>保持 docId 不变</b>。
   *
   * <p>供应用层在需要丢弃内存改动（SubAgent 取消/失败、异常）时调用。与 {@code closeDocx + openDocx} 的关键区别：docId 保持稳定， 不会因
   * {@code seq} 递增而漂移。这避免了依赖该 docId 的上层（主 Agent 对话记忆、已派发给 SubAgent 的 task 文本） 因 docId 变化而失效。
   *
   * <p>语义上等价于「丢弃内存里被改过的 Document，重新从磁盘加载」，但对外契约（docId）不变。 generation 递增以使旧引用缓存（{@link
   * ReferenceContext}）失效——reopen 后上一轮的元素索引引用不应再命中。
   *
   * @param docId 要重开的文档句柄（保持不变）
   * @param path 磁盘上的 docx 路径（重新加载来源）
   * @throws DocxIOException 磁盘文件无法打开
   */
  public void reopen(String docId, String path) {
    Document old = sessions.get(docId);
    Document fresh = Docx.open(Path.of(path));
    sessions.put(docId, fresh);
    generations.merge(docId, 1L, Long::sum);
    references.invalidate(docId);
    if (old != null) {
      try {
        old.close();
      } catch (RuntimeException ignored) {
        // 旧文档关闭失败不影响 reopen 语义：fresh 已 put 进 sessions
      }
    }
  }

  /**
   * 返回文档结构概览。
   *
   * <p>P0-04 起委托 {@link DocumentViewService#stats}，data 升级为完整 {@link StatsView}（旧 4 个 int 仍包含在内）。
   * 未绑定 viewService 时走旧逻辑（独立使用 SessionTools 的兼容路径）。
   */
  @ToolDef(
      name = "get_document_overview",
      description =
          "返回文档结构概览：正文段落数、正文表格数、body 元素数、section 数、图片数、修订数、字体/字号分布。"
              + "了解文档规模/判断后续索引范围时优先用它，不要分别调用多个 count 工具。")
  @ToolCapability(operation = CapabilityOperation.READ, element = "document")
  public String getDocumentOverview(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      ToolResult<Void> result =
          ToolResult.fail(
              ToolResultCode.DOCUMENT_CLOSED, "文档句柄 " + docId + " 不存在（未 open_docx 或已 close_docx）");
      return ToolResultRenderer.render(result);
    }
    if (viewService != null) {
      long generation = generations.getOrDefault(docId, 1L);
      StatsView stats = viewService.stats(doc, docId, generation);
      String message =
          "文档概览\n"
              + "段落数: "
              + stats.paragraphCount()
              + "\n表格数: "
              + stats.tableCount()
              + "\nbody 元素数: "
              + stats.bodyElementCount()
              + "\nsection 数: "
              + stats.sectionCount();
      ToolResult<StatsView> result = ToolResult.ok(stats, message);
      return ToolResultRenderer.render(result);
    }
    // 兼容路径：未绑定 viewService（独立使用 SessionTools）
    Map<String, Integer> data = new LinkedHashMap<>();
    data.put("正文段落数", doc.paragraphs().size());
    data.put("正文表格数", doc.tables().size());
    data.put("body 元素数", doc.bodyElements().size());
    data.put("section 数", doc.sections().size());
    String message =
        "文档概览\n"
            + "正文段落数: "
            + doc.paragraphs().size()
            + "\n正文表格数: "
            + doc.tables().size()
            + "\nbody 元素数: "
            + doc.bodyElements().size()
            + "\nsection 数: "
            + doc.sections().size();
    ToolResult<Map<String, Integer>> result = ToolResult.ok(data, message);
    return ToolResultRenderer.render(result);
  }
}
