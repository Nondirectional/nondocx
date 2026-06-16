package com.non.docx.core;

import com.non.docx.core.api.Document;
import com.non.docx.core.api.exception.DocxFormatException;
import com.non.docx.core.api.exception.DocxIOException;
import com.non.docx.core.internal.util.Objects;
import com.non.docx.core.internal.util.Streams;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stateless static factory facade for creating and opening docx documents.
 *
 * <p>This is the single entry point for obtaining a {@link Document}. It holds no state of its own;
 * every call produces an independent, live document.
 *
 * <p><b>Stream ownership.</b> {@code open(InputStream)} fully buffers the caller's stream before
 * constructing a document and does <em>not</em> close it — the caller retains ownership. The
 * resulting {@code Document} (and its {@code save(OutputStream)} method) likewise never close a
 * caller-provided stream. {@code Document.close()} is what releases the underlying POI resources.
 *
 * <p><b>Error mapping.</b> File-level IO failures (missing or unreadable file, read errors on a
 * caller's stream) surface as {@link DocxIOException}. A source that exists and was read but is not
 * a valid docx surfaces as {@link DocxFormatException}, carrying the source path when available.
 */
public final class Docx {

    private Docx() {
    }

    /**
     * Opens a docx document from a file.
     *
     * @param file the file to open (not {@code null})
     * @return a live document over the file's contents
     * @throws DocxIOException        if the file cannot be read
     * @throws DocxFormatException    if the file is not a valid docx
     * @throws IllegalArgumentException if {@code file} is {@code null}
     */
    public static Document open(File file) {
        Objects.requireNonNull(file, "file");
        return open(file.toPath());
    }

    /**
     * Opens a docx document from a path.
     *
     * @param path the path to open (not {@code null})
     * @return a live document over the file's contents
     * @throws DocxIOException        if the file cannot be read (missing, unreadable, etc.)
     * @throws DocxFormatException    if the file is not a valid docx
     * @throws IllegalArgumentException if {@code path} is {@code null}
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
     * Opens a docx document from a stream. The stream is fully buffered and is <em>not</em> closed;
     * the caller retains ownership.
     *
     * @param in the stream to read from (not {@code null}, not closed by this method)
     * @return a live document over the stream's contents
     * @throws DocxIOException        if the stream cannot be read
     * @throws DocxFormatException    if the contents are not a valid docx
     * @throws IllegalArgumentException if {@code in} is {@code null}
     */
    public static Document open(InputStream in) {
        Objects.requireNonNull(in, "in");
        byte[] bytes;
        try {
            bytes = Streams.readAllBytes(in);
        } catch (IOException e) {
            throw new DocxIOException("Failed to read from input stream", e);
        }
        return openDocument(bytes, null);
    }

    /**
     * Creates a new, empty docx document.
     *
     * @return a live document over a fresh, empty {@code XWPFDocument}
     */
    public static Document create() {
        return new Document(new XWPFDocument());
    }

    /**
     * Builds a document from already-buffered bytes. Because the source is now in memory, no IO can
     * fail here, so <em>any</em> exception raised while constructing the {@code XWPFDocument} — be
     * it an {@code IOException} (for example a malformed zip), a {@code POIXMLException}, or a
     * {@code NotOfficeXmlFileException} — means the bytes are not a valid docx and is reported as a
     * format error.
     */
    private static Document openDocument(byte[] bytes, String pathStr) {
        try {
            return new Document(new XWPFDocument(new ByteArrayInputStream(bytes)));
        } catch (IOException | RuntimeException e) {
            throw new DocxFormatException("Not a valid docx file", pathStr, e);
        }
    }
}
