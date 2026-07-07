package com.non.docx.core;

import com.non.docx.core.api.Document;
import com.non.docx.core.api.exception.DocxFormatException;
import com.non.docx.core.api.exception.DocxIOException;
import com.non.docx.core.internal.compare.DocumentCompareSupport;
import com.non.docx.core.internal.util.Objects;
import com.non.docx.core.internal.util.Streams;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/**
 * 无状态静态工厂外观，用于创建和打开 docx 文档。
 *
 * <p>这是获取 {@link Document} 的唯一入口点。它本身不持有任何状态； 每次调用都会生成一个独立的、活跃的文档。
 *
 * <p><b>流的所有权。</b> {@code open(InputStream)} 会在构建文档前完全缓冲调用者的流，并且 <em>不</em> 关闭它 — 调用者保留所有权。生成的
 * {@code Document}（及其 {@code save(OutputStream)} 方法）同样 从不关闭调用者提供的流。{@code Document.close()} 才是释放底层
 * POI 资源的方法。
 *
 * <p><b>错误映射。</b> 文件级别的 IO 失败（文件缺失或无法读取、调用者流的读取错误） 以 {@link DocxIOException} 形式呈现。存在且已读取但不是有效 docx
 * 的源 以 {@link DocxFormatException} 形式呈现，并在可用时携带源路径。
 */
public final class Docx {

  /** compare 结果文档使用的默认修订作者。 */
  public static final String DEFAULT_COMPARE_AUTHOR = "nondocx compare";

  private Docx() {}

  /**
   * 从文件打开 docx 文档。
   *
   * @param file 要打开的文件（不能为 {@code null}）
   * @return 基于文件内容的活跃文档
   * @throws DocxIOException 如果文件无法读取
   * @throws DocxFormatException 如果文件不是有效的 docx
   * @throws IllegalArgumentException 如果 {@code file} 为 {@code null}
   */
  public static Document open(File file) {
    Objects.requireNonNull(file, "file");
    return open(file.toPath());
  }

  /**
   * 从路径打开 docx 文档。
   *
   * @param path 要打开的路径（不能为 {@code null}）
   * @return 基于文件内容的活跃文档
   * @throws DocxIOException 如果文件无法读取（缺失、不可读等）
   * @throws DocxFormatException 如果文件不是有效的 docx
   * @throws IllegalArgumentException 如果 {@code path} 为 {@code null}
   */
  public static Document open(Path path) {
    Objects.requireNonNull(path, "path");
    String pathStr = path.toString();
    byte[] bytes;
    try (InputStream in = Files.newInputStream(path)) {
      bytes = Streams.readAllBytes(in);
    } catch (IOException e) {
      throw new DocxIOException("Failed to open document: " + pathStr, e);
    }
    return openDocument(bytes, pathStr);
  }

  /**
   * 从流打开 docx 文档。该流会被完全缓冲，并且 <em>不</em> 关闭； 调用者保留所有权。
   *
   * @param in 要读取的流（不能为 {@code null}，不会被此方法关闭）
   * @return 基于流内容的活跃文档
   * @throws DocxIOException 如果流无法读取
   * @throws DocxFormatException 如果内容不是有效的 docx
   * @throws IllegalArgumentException 如果 {@code in} 为 {@code null}
   */
  public static Document open(InputStream in) {
    Objects.requireNonNull(in, "in");
    byte[] bytes;
    try {
      bytes = Streams.readAllBytes(in);
    } catch (IOException e) {
      throw new DocxIOException("无法从输入流读取", e);
    }
    return openDocument(bytes, null);
  }

  /**
   * 创建一个新的空 docx 文档。
   *
   * @return 基于全新空 {@code XWPFDocument} 的活跃文档
   */
  public static Document create() {
    return new Document(new XWPFDocument());
  }

  /**
   * 比较两份现有 docx，并返回一份新的带修订结果文档。
   *
   * <p><b>当前范围。</b> compare 仍只比较<b>正文纯文本段落</b>：结果以旧文档为基线，把新文档相对旧文档的正文文本差异写成 tracked
   * changes。表格、页眉页脚、批注、图片、分节，以及包含超链接 / 图片 / field / 多样式混排等复杂内联结构的差异段落，当前不参与 compare，结果中保留旧文档原样。
   *
   * <p><b>样式保真边界。</b> 对于两侧都能归约成<b>单一样式纯文本段落</b>的场景，compare 会保留 run 级可见字符样式：未改动文本与删除文本沿用旧文档样式，
   * 新插入文本采用新文档样式。这里的“样式”只指粗体 / 斜体 / 下划线 / 字体 / 字号 / 颜色六种 run 级属性；纯样式变化（文本完全相同，仅样式不同）当前仍视为无差异。
   *
   * <p>返回的是一个新的活跃 {@link Document}。调用方负责后续 {@code save(...)} 与 {@code close()}。该方法不会修改调用者传入路径上的源文件。
   *
   * @param oldPath 旧版文档路径（不能为 {@code null}）
   * @param newPath 新版文档路径（不能为 {@code null}）
   * @return 一份新的带修订结果文档
   * @throws DocxIOException 如果任一文件无法读取，或 compare 结果无法构造
   * @throws DocxFormatException 如果任一文件不是有效的 docx
   * @throws IllegalArgumentException 如果任一路径为 {@code null}
   */
  public static Document compare(Path oldPath, Path newPath) {
    return compare(oldPath, newPath, DEFAULT_COMPARE_AUTHOR);
  }

  /**
   * 比较两份现有 docx，并返回一份新的带修订结果文档，修订作者使用显式传入值。
   *
   * <p><b>OOXML → POI → nondocx。</b> 修订不是普通文本高亮，而是写入 {@code <w:ins>} / {@code <w:del>}
   * 等实际修订节点。Apache POI 没有“比较两个 docx 并生成修订”的高层 API，因此 nondocx 在内部完成段落对齐、段内文本 diff，并复用现有 tracked
   * authoring API 写出标准修订。
   *
   * <p><b>当前限制。</b> 当前只比较正文纯文本段落。对可归约成单一样式的纯文本段落，会保留 run 级六样式（粗体 / 斜体 / 下划线 / 字体 / 字号 /
   * 颜色）；但不比较纯样式变化，也不承诺保留原始 run 分段边界。若差异落在含超链接 / 图片 / field / 多样式混排等复杂段落，结果中保留旧段落原样。
   *
   * @param oldPath 旧版文档路径（不能为 {@code null}）
   * @param newPath 新版文档路径（不能为 {@code null}）
   * @param author 写入修订的作者（不能为 {@code null} 或空白）
   * @return 一份新的带修订结果文档
   * @throws DocxIOException 如果任一文件无法读取，或 compare 结果无法构造
   * @throws DocxFormatException 如果任一文件不是有效的 docx
   * @throws IllegalArgumentException 如果任一路径为 {@code null}，或 {@code author} 为空白
   */
  public static Document compare(Path oldPath, Path newPath, String author) {
    Objects.requireNonNull(oldPath, "oldPath");
    Objects.requireNonNull(newPath, "newPath");
    Objects.requireNonNull(author, "author");
    if (author.isBlank()) {
      throw new IllegalArgumentException("author 不能为空白");
    }
    try (Document oldDoc = open(oldPath);
        Document newDoc = open(newPath)) {
      Document working = cloneDocument(oldDoc);
      try {
        DocumentCompareSupport.apply(oldDoc, newDoc, working, author);
        Document stabilized = cloneDocument(working);
        return stabilized;
      } finally {
        working.close();
      }
    }
  }

  /**
   * Builds a document from already-buffered bytes. Because the source is now in memory, no IO can
   * fail here, so <em>any</em> exception raised while constructing the {@code XWPFDocument} — be it
   * an {@code IOException} (for example a malformed zip), a {@code POIXMLException}, or a {@code
   * NotOfficeXmlFileException} — means the bytes are not a valid docx and is reported as a format
   * error.
   */
  private static Document openDocument(byte[] bytes, String pathStr) {
    try {
      return new Document(new XWPFDocument(new ByteArrayInputStream(bytes)));
    } catch (IOException | RuntimeException e) {
      throw new DocxFormatException("Not a valid docx file", pathStr, e);
    }
  }

  /** 深拷贝一个文档，返回独立的活跃副本。 */
  private static Document cloneDocument(Document source) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    source.save(out);
    return open(new ByteArrayInputStream(out.toByteArray()));
  }
}
