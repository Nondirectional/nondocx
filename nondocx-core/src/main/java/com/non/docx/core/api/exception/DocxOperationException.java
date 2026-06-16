package com.non.docx.core.api.exception;

/**
 * Raised when a logical document operation fails at runtime (as opposed to an IO or format
 * problem).
 *
 * <p>Carries an optional context string (e.g. a paragraph index or body position) via {@link
 * #getContext()} to aid diagnosis.
 *
 * <p><b>Guidance for callers and implementors:</b> argument-validation failures and out-of-bounds
 * index access reuse the standard JDK exceptions — {@link IllegalArgumentException} and {@link
 * IndexOutOfBoundsException} respectively — rather than this type. Reserve {@code
 * DocxOperationException} for genuine domain operation failures (for example: performing an
 * operation on an element whose state cannot satisfy it).
 *
 * <p>Example message: {@code "Cannot remove the only paragraph of a table cell (context:
 * cell[0])"}.
 */
public class DocxOperationException extends DocxException {

  private static final long serialVersionUID = 1L;

  private final String context;

  /**
   * Constructs a new exception with the specified detail message.
   *
   * @param message the detail message (English) describing the failed operation
   */
  public DocxOperationException(String message) {
    super(message);
    this.context = null;
  }

  /**
   * Constructs a new exception with the specified detail message and operation context.
   *
   * @param message the detail message (English) describing the failed operation
   * @param context a short context descriptor (e.g. {@code "paragraph index 5"}), or {@code null}
   */
  public DocxOperationException(String message, String context) {
    super(appendContext(message, context));
    this.context = context;
  }

  /**
   * Returns the operation context (e.g. element index / position), or {@code null} if none.
   *
   * @return the operation context, or {@code null}
   */
  public String getContext() {
    return context;
  }

  private static String appendContext(String message, String context) {
    if (context == null) {
      return message;
    }
    return message + " (context: " + context + ")";
  }
}
