package com.non.docx.core;

import com.non.docx.core.api.Document;
import com.non.docx.core.api.exception.DocxFormatException;
import com.non.docx.core.api.exception.DocxIOException;
import com.non.docx.core.internal.util.Objects;
import com.non.docx.core.internal.util.Streams;
import java.io.ByteArrayInputStream;
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
}
