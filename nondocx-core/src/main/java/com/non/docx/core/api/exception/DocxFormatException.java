package com.non.docx.core.api.exception;

/**
 * 当文件或流不是有效的、格式正确的 docx (OOXML) 文档时抛出。
 *
 * <p>有问题的源路径（如果可用）会作为上下文携带，并通过 {@link #getPath()} 暴露，以便调用者可以定位哪个文件损坏或格式错误。
 *
 * <p>示例消息：{@code "不是有效的 docx 文件：/path/to/broken.docx"}。
 */
public class DocxFormatException extends DocxException {

  private static final long serialVersionUID = 1L;

  private final String path;

  /**
   * 使用指定的详细信息和源路径构造一个新异常。
   *
   * @param message 描述格式问题的详细信息
   * @param path 解析失败的源路径，如果未知（例如流）则为 {@code null}
   */
  public DocxFormatException(String message, String path) {
    super(appendPath(message, path));
    this.path = path;
  }

  /**
   * 使用指定的详细信息、源路径和原因构造一个新异常。
   *
   * @param message 描述格式问题的详细信息
   * @param path 解析失败的源路径，如果未知则为 {@code null}
   * @param cause 底层的解析 / Apache POI 异常，或 {@code null}
   */
  public DocxFormatException(String message, String path, Throwable cause) {
    super(appendPath(message, path), cause);
    this.path = path;
  }

  /**
   * 返回格式错误文档的源路径，如果源是流或路径未知则为 {@code null}
   *
   * @return 源路径，或 {@code null}
   */
  public String getPath() {
    return path;
  }

  private static String appendPath(String message, String path) {
    if (path == null) {
      return message;
    }
    return message + ": " + path;
  }
}
