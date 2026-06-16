package com.non.docx.core.api.exception;

import java.io.IOException;

/**
 * Raised when a docx document cannot be read from or written to a byte source.
 *
 * <p>This wraps underlying {@link IOException}s and Apache POI IO-related failures
 * (for example {@code OpenXML4JException} or {@code POIXMLException}) so callers never need to
 * import {@code org.apache.poi.*} to handle IO errors. The original exception is preserved as the
 * {@link #getCause() cause}.
 *
 * <p>Example message: {@code "Failed to save document"}.
 */
public class DocxIOException extends DocxException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs a new exception with the specified detail message and underlying cause.
     *
     * @param message the detail message (English) describing the failed IO operation
     * @param cause   the underlying IO / Apache POI exception, or {@code null}
     */
    public DocxIOException(String message, Throwable cause) {
        super(message, cause);
    }
}
