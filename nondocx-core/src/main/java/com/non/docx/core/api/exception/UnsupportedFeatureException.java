package com.non.docx.core.api.exception;

/**
 * Raised when a docx feature is requested that lies outside nondocx's deep-wrap scope.
 *
 * <p>nondocx deliberately wraps the common ~90% of docx usage and leaves advanced features
 * (tracked changes, fields, OLE objects, OMML math, watermarks, text boxes, shapes, etc.) to the
 * {@code raw()} escape hatch. When such a feature is encountered on a wrapped path that cannot
 * honor it, this exception is thrown and its message directs the caller to {@code raw()}.
 *
 * <p>Example message:
 * {@code "Tracked changes are not wrapped by nondocx; use raw() to access the underlying POI object"}.
 */
public class UnsupportedFeatureException extends DocxException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs a new exception with the specified detail message.
     *
     * @param message the detail message (English), typically naming the unsupported feature and
     *                pointing to {@code raw()}
     */
    public UnsupportedFeatureException(String message) {
        super(message);
    }
}
