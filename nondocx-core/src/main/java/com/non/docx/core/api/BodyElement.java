package com.non.docx.core.api;

/**
 * A top-level block that appears in a document body, in document order.
 *
 * <p>The document body is an ordered sequence of body elements — primarily paragraphs and tables.
 * {@code Document.bodyElements()} returns them in their true Word-body order, and this interface is
 * the shared type of that sequence. It is the structural source of truth used for round-trip
 * equality comparisons.
 *
 * <p>Implementations include {@code com.non.docx.core.api.text.Paragraph} and {@code
 * com.non.docx.core.api.table.Table}, added in later phases. This is currently a marker interface;
 * common members may be promoted onto it as the domain model grows.
 */
public interface BodyElement {}
