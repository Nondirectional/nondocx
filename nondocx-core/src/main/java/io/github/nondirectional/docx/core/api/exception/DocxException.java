package io.github.nondirectional.docx.core.api.exception;

/**
 * nondocx 非受检异常层次结构的根类。
 *
 * <p>nondocx 公开 API 抛出的所有异常（不包括通过 {@code raw()} 逃生口路径传播的 Apache POI 异常 路径，其中 Apache POI
 * 异常会未经封装地传播）都继承此类。这是一个非受检 {@link RuntimeException}，因此调用者无需强制声明或捕获它。
 *
 * <p>这是调用者广泛处理 nondocx 失败所需的单一类型：
 *
 * <pre>{@code
 * try {
 *     Document doc = Docx.open(file);
 * } catch (DocxException e) {
 *     // 涵盖 IO、格式和操作失败
 * }
 * }</pre>
 */
public class DocxException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * 使用指定的详细信息构造一个新异常。
   *
   * @param message 详细信息，如果无则为 {@code null}
   */
  public DocxException(String message) {
    super(message);
  }

  /**
   * 使用指定的详细信息和原因构造一个新异常。
   *
   * @param message 详细信息，如果无则为 {@code null}
   * @param cause 根本原因（通常是 IO 或 Apache POI 异常），如果无为 {@code null}
   */
  public DocxException(String message, Throwable cause) {
    super(message, cause);
  }
}
