package com.non.docx.core.api.exception;

/**
 * Root of the nondocx unchecked exception hierarchy.
 *
 * <p>All exceptions raised by the nondocx public API (excluding the {@code raw()} escape-hatch
 * path, where Apache POI exceptions propagate unwrapped) extend this type. It is an unchecked
 * {@link RuntimeException}, so callers are never forced to declare or catch it.
 *
 * <p>This is the single type a caller needs to handle nondocx failures broadly:
 *
 * <pre>{@code
 * try {
 *     Document doc = Docx.open(file);
 * } catch (DocxException e) {
 *     // covers IO, format, and operation failures
 * }
 * }</pre>
 */
public class DocxException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Constructs a new exception with the specified detail message.
   *
   * @param message the detail message (English), or {@code null} if none
   */
  public DocxException(String message) {
    super(message);
  }

  /**
   * Constructs a new exception with the specified detail message and cause.
   *
   * @param message the detail message (English), or {@code null} if none
   * @param cause the underlying cause (typically an IO or Apache POI exception), or {@code null}
   */
  public DocxException(String message, Throwable cause) {
    super(message, cause);
  }
}
