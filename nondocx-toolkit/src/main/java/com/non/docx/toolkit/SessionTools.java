package com.non.docx.toolkit;

import com.non.chain.tool.ToolDef;
import com.non.chain.tool.ToolParam;
import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import java.nio.file.Files;
import java.nio.file.Path;
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

  // ==================== 工具方法 ====================

  /**
   * 打开一个 .docx 文件，返回文档句柄 docId。
   *
   * @param path 文档路径（绝对路径或相对工作目录的路径）
   * @return 形如 {@code "doc-1"} 的句柄；打开失败返回中文错误串
   */
  @ToolDef(name = "open_docx", description = "打开一个 .docx 文件，返回文档句柄 docId，后续工具用它定位文档")
  public String openDocx(@ToolParam(name = "path", description = "文档路径（绝对路径）") String path) {
    try {
      Document doc = Docx.open(Path.of(path));
      String docId = "doc-" + seq.incrementAndGet();
      sessions.put(docId, doc);
      return docId;
    } catch (RuntimeException e) {
      return "错误：无法打开文档 " + path + "（" + rootMessage(e) + "）";
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
  public String saveDocx(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "output_path", description = "输出文件路径（绝对路径）") String outputPath) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    try {
      Path out = Path.of(outputPath);
      Path parent = out.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      doc.save(out);
      return "已保存到 " + out.toAbsolutePath();
    } catch (Exception e) {
      return "错误：无法保存到 " + outputPath + "（" + rootMessage(e) + "）";
    }
  }

  /**
   * 关闭并移除文档会话（幂等）。
   *
   * @param docId 文档句柄
   * @return 关闭结果；句柄不存在视为已关闭
   */
  @ToolDef(name = "close_docx", description = "关闭并释放指定 docId 的文档会话（幂等：未打开也返回成功）")
  public String closeDocx(@ToolParam(name = "doc_id", description = "文档句柄") String docId) {
    Document doc = sessions.remove(docId);
    if (doc == null) {
      return "文档 " + docId + " 未打开（视为已关闭）";
    }
    try {
      doc.close();
    } catch (RuntimeException e) {
      return "文档 " + docId + " 已从会话移除，但关闭时出错：" + rootMessage(e);
    }
    return "已关闭 " + docId;
  }

  /** 返回文档结构概览。 */
  @ToolDef(
      name = "get_document_overview",
      description =
          "返回文档结构概览：正文段落数、正文表格数、body 元素数、section 数。" + "了解文档规模/判断后续索引范围时优先用它，不要分别调用多个 count 工具。")
  public String getDocumentOverview(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId) {
    Document doc = sessions.get(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    return "文档概览\n"
        + "正文段落数: "
        + doc.paragraphs().size()
        + "\n正文表格数: "
        + doc.tables().size()
        + "\nbody 元素数: "
        + doc.bodyElements().size()
        + "\nsection 数: "
        + doc.sections().size();
  }
}
