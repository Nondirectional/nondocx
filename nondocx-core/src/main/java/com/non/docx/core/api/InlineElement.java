package com.non.docx.core.api;

/**
 * An inline fragment that appears within a paragraph, in reading order.
 *
 * <p>A paragraph's content is an ordered sequence of inline elements — primarily runs,
 * hyperlinks, and inline images. {@code Paragraph.inlineElements()} returns them in their true
 * reading order, and this interface is the shared type of that sequence. It is the structural
 * source of truth used for round-trip equality comparisons.
 *
 * <p>Implementations include {@code com.non.docx.core.api.text.Run},
 * {@code com.non.docx.core.api.text.Hyperlink}, and {@code com.non.docx.core.api.image.Image},
 * added in later phases. This is currently a marker interface; common members may be promoted
 * onto it as the domain model grows.
 */
public interface InlineElement {
}
