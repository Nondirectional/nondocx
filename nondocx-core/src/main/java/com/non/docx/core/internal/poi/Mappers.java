package com.non.docx.core.internal.poi;

import com.non.docx.core.api.style.Alignment;
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
}
