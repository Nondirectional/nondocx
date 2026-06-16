package com.non.docx.core.api.section;

/**
 * Common paper sizes, each carrying its <em>portrait</em> dimensions in twips (1/20 of a point,
 * 1440 per inch).
 *
 * <p>Dimensions are stored in portrait orientation (width &lt; height). When a section uses
 * landscape orientation the stored {@code <w:pgSz>} width/height are swapped by
 * {@link Section#orientation(Orientation)}; {@link #fromDimensions(int, int)} normalizes back to
 * portrait so the logical paper size can be recovered regardless of orientation.
 *
 * <p>This is a POI-free value object: it carries no {@code org.apache.poi.*} dependency.
 */
public enum PaperSize {
    /** ISO A4 (210 × 297 mm). */
    A4(11906, 16838),
    /** US Letter (8.5 × 11 in). */
    LETTER(12240, 15840),
    /** US Legal (8.5 × 14 in). */
    LEGAL(12240, 20160),
    /** ISO A5 (148 × 210 mm). */
    A5(8391, 11906),
    /** JIS B5 (182 × 257 mm). */
    B5(10319, 14570),
    /** ISO A3 (297 × 420 mm). */
    A3(16838, 23811);

    private final int widthTwips;
    private final int heightTwips;

    PaperSize(int widthTwips, int heightTwips) {
        this.widthTwips = widthTwips;
        this.heightTwips = heightTwips;
    }

    /**
     * Returns the portrait width in twips.
     *
     * @return the portrait width (twips)
     */
    public int widthTwips() {
        return widthTwips;
    }

    /**
     * Returns the portrait height in twips.
     *
     * @return the portrait height (twips)
     */
    public int heightTwips() {
        return heightTwips;
    }

    /**
     * Resolves a known paper size from raw {@code <w:pgSz>} dimensions, or {@code null} if the
     * dimensions do not match any known size.
     *
     * <p>The comparison is orientation-agnostic: the inputs are normalized to portrait
     * (smaller dimension first) before matching, so the same logical paper size is resolved
     * whether the section is portrait or landscape. Matching is exact; custom or uncommon
     * dimensions return {@code null}.
     *
     * @param widthTwips  the stored page width in twips
     * @param heightTwips the stored page height in twips
     * @return the matching paper size, or {@code null} if none match
     */
    public static PaperSize fromDimensions(int widthTwips, int heightTwips) {
        int portraitWidth = Math.min(widthTwips, heightTwips);
        int portraitHeight = Math.max(widthTwips, heightTwips);
        for (PaperSize size : values()) {
            if (size.widthTwips == portraitWidth && size.heightTwips == portraitHeight) {
                return size;
            }
        }
        return null;
    }
}
