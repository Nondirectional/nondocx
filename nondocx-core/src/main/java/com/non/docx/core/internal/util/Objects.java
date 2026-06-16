package com.non.docx.core.internal.util;

/**
 * Internal API — subject to change without notice.
 *
 * <p>Argument-validation helper. Unlike {@link java.util.Objects#requireNonNull}, this throws
 * {@link IllegalArgumentException} rather than {@link NullPointerException}, matching nondocx's
 * error model: a {@code null} argument on the public surface is reported as an
 * {@code IllegalArgumentException}, never a {@code NullPointerException}.
 */
public final class Objects {

    private Objects() {
    }

    /**
     * Validates that the given reference is non-null.
     *
     * @param obj     the reference to check
     * @param context a short descriptor of the argument (e.g. {@code "file"} or {@code "delegate"}),
     *                used in the exception message; treated as {@code "argument"} when {@code null}
     * @param <T>     the reference type
     * @return the non-null reference
     * @throws IllegalArgumentException if {@code obj} is {@code null}
     */
    public static <T> T requireNonNull(T obj, String context) {
        if (obj == null) {
            throw new IllegalArgumentException(
                    (context == null ? "argument" : context) + " must not be null");
        }
        return obj;
    }
}
