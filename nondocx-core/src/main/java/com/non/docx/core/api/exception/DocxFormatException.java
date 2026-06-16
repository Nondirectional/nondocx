package com.non.docx.core.api.exception;

/**
 * Raised when a file or stream is not a valid, well-formed docx (OOXML) document.
 *
 * <p>The offending source path, when available, is carried as context and exposed via {@link
 * #getPath()} so callers can pinpoint which file is corrupt or malformed.
 *
 * <p>Example message: {@code "Not a valid docx file: /path/to/broken.docx"}.
 */
public class DocxFormatException extends DocxException {

  private static final long serialVersionUID = 1L;

  private final String path;

  /**
   * Constructs a new exception with the specified detail message and source path.
   *
   * @param message the detail message (English) describing the format problem
   * @param path the source path that failed to parse, or {@code null} if unknown (e.g. a stream)
   */
  public DocxFormatException(String message, String path) {
    super(appendPath(message, path));
    this.path = path;
  }

  /**
   * Constructs a new exception with the specified detail message, source path, and cause.
   *
   * @param message the detail message (English) describing the format problem
   * @param path the source path that failed to parse, or {@code null} if unknown
   * @param cause the underlying parse / Apache POI exception, or {@code null}
   */
  public DocxFormatException(String message, String path, Throwable cause) {
    super(appendPath(message, path), cause);
    this.path = path;
  }

  /**
   * Returns the source path of the malformed document, or {@code null} when the source was a stream
   * or the path is otherwise unknown.
   *
   * @return the source path, or {@code null}
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
