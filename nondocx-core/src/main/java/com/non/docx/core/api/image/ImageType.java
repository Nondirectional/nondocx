package com.non.docx.core.api.image;

/**
 * The image format of an inline picture.
 *
 * <p>This is a POI-free value object: it carries no {@code org.apache.poi.*} dependency. The mapping
 * to Apache POI's {@code PictureType} lives in the internal POI bridge
 * ({@code com.non.docx.core.internal.poi.Mappers}), so the public enum stays POI-free at the source
 * level, not just at the signature level.
 *
 * <p>The MVP models the four most common inline image formats. Rarer formats (BMP, EMF, WMF, …)
 * are not modeled; an embedded picture of such a format is still readable via
 * {@link Image#raw()} and reports {@code null} from {@link Image#type()}.
 */
public enum ImageType {
    /** PNG (Portable Network Graphics). */
    PNG,
    /** JPEG (Joint Photographic Experts Group), including CMYK variants. */
    JPEG,
    /** GIF (Graphics Interchange Format). */
    GIF,
    /** TIFF (Tagged Image File Format). */
    TIFF
}
