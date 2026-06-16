package com.non.docx.core.internal.poi;

import com.non.docx.core.api.style.Alignment;
import com.non.docx.core.api.style.HeadingLevel;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;

/**
 * Internal API — subject to change without notice.
 *
 * <p>Enum mapping bridge between nondocx's POI-free value objects and Apache POI enums. All
 * {@code org.apache.poi.*} import needed for mapping is concentrated here so that the public value
 * objects in {@code com.non.docx.core.api.style} stay POI-free at the source level, not just at the
 * signature level.
 */
public final class Mappers {

    private Mappers() {
    }

    /**
     * Maps a nondocx {@link Alignment} to Apache POI's {@link ParagraphAlignment}.
     *
     * @param alignment the nondocx alignment (not {@code null})
     * @return the corresponding POI alignment
     */
    public static ParagraphAlignment toPoi(Alignment alignment) {
        if (alignment == null) {
            throw new IllegalArgumentException("alignment must not be null");
        }
        switch (alignment) {
            case LEFT:
                return ParagraphAlignment.LEFT;
            case CENTER:
                return ParagraphAlignment.CENTER;
            case RIGHT:
                return ParagraphAlignment.RIGHT;
            case JUSTIFY:
                return ParagraphAlignment.BOTH;
            default:
                throw new IllegalArgumentException("Unsupported alignment: " + alignment);
        }
    }

    /**
     * Maps an Apache POI {@link ParagraphAlignment} back to a nondocx {@link Alignment}.
     *
     * <p>Only the four alignments nondocx models are represented exactly; rarer POI alignments
     * (for example {@code DISTRIBUTE} or kashida variants) collapse to {@link Alignment#LEFT} on
     * read so that real-world documents never fail to load.
     *
     * @param alignment the POI alignment, or {@code null} if unset
     * @return the corresponding nondocx alignment, or {@code null} if the input was {@code null}
     */
    public static Alignment fromPoi(ParagraphAlignment alignment) {
        if (alignment == null) {
            return null;
        }
        switch (alignment) {
            case CENTER:
                return Alignment.CENTER;
            case RIGHT:
            case END:
                return Alignment.RIGHT;
            case BOTH:
                return Alignment.JUSTIFY;
            case LEFT:
            case START:
            default:
                return Alignment.LEFT;
        }
    }

    /**
     * Maps a nondocx {@link HeadingLevel} to the OOXML built-in heading style id used by Word / POI
     * ({@code "Heading1"} … {@code "Heading6"}).
     *
     * @param level the heading level (not {@code null})
     * @return the corresponding OOXML style id (e.g. {@code "Heading2"})
     */
    public static String toStyleId(HeadingLevel level) {
        if (level == null) {
            throw new IllegalArgumentException("level must not be null");
        }
        return "Heading" + (level.ordinal() + 1);
    }

    /**
     * Maps an OOXML paragraph style id back to a nondocx {@link HeadingLevel}.
     *
     * <p>Only the six built-in heading style ids ({@code "Heading1"} … {@code "Heading6"}) are
     * recognized; every other style (including {@code null} and non-heading styles) maps to
     * {@code null}, meaning "this paragraph is not a heading".
     *
     * @param style the OOXML style id, or {@code null} if unset
     * @return the matching heading level, or {@code null} if the paragraph is not a heading
     */
    public static HeadingLevel headingFromStyle(String style) {
        if (style == null) {
            return null;
        }
        switch (style) {
            case "Heading1":
                return HeadingLevel.H1;
            case "Heading2":
                return HeadingLevel.H2;
            case "Heading3":
                return HeadingLevel.H3;
            case "Heading4":
                return HeadingLevel.H4;
            case "Heading5":
                return HeadingLevel.H5;
            case "Heading6":
                return HeadingLevel.H6;
            default:
                return null;
        }
    }
}
