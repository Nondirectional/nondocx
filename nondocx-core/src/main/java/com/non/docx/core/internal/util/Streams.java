package com.non.docx.core.internal.util;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * Internal API — subject to change without notice.
 *
 * <p>Stream draining and resource-cleanup helpers used at the IO boundary ({@code Docx}). These
 * helpers centralize nondocx's stream-ownership rules: nondocx never closes a stream it did not
 * open, and best-effort cleanup must never mask a primary failure.
 */
public final class Streams {

    private Streams() {
    }

    /**
     * Fully drains the given stream into a newly allocated byte array. The stream is <em>not</em>
     * closed; the caller retains ownership and is responsible for closing it.
     *
     * <p>This is used so that {@code Docx.open(InputStream)} can buffer the caller's content fully
     * before constructing a document, without taking ownership of the caller's stream.
     *
     * @param in the stream to drain (not closed by this method)
     * @return the stream's remaining contents as a byte array
     * @throws IOException          if reading from the stream fails
     */
    public static byte[] readAllBytes(InputStream in) throws IOException {
        return in.readAllBytes();
    }

    /**
     * Closes the given {@link Closeable}, swallowing any {@link IOException}. Intended for cleanup
     * paths where a close failure must not mask a primary failure. Does nothing if {@code null}.
     *
     * @param closeable the resource to close, or {@code null}
     */
    public static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // best-effort cleanup; intentionally ignored
        }
    }
}
