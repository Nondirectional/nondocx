package com.non.docx.core.internal.poi;

import org.apache.poi.common.usermodel.PictureType;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.openxmlformats.schemas.drawingml.x2006.main.CTPositiveSize2D;
import org.openxmlformats.schemas.drawingml.x2006.main.CTShapeProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTransform2D;
import org.openxmlformats.schemas.drawingml.x2006.picture.CTPicture;

/**
 * Internal API — subject to change without notice.
 *
 * <p>Reads geometry and payload off an Apache POI {@link XWPFPicture} so that the public
 * {@code Image} type can stay free of POI / XmlBeans imports at the source level.
 *
 * <p>Dimensions are read from the picture's drawing extent, which Apache POI stores in EMU, and
 * converted to pixels using {@link Units#EMU_PER_PIXEL} (9525 EMU per pixel at 96&nbsp;DPI). That is
 * the exact inverse of what {@code XWPFRun.addPicture} writes (it multiplies pixel inputs by
 * {@code EMU_PER_PIXEL}), so the pixel values survive a save → open round-trip without rounding.
 */
public final class Pictures {

    private Pictures() {
    }

    /**
     * Returns the picture's width in pixels (96&nbsp;DPI), or {@code 0} if no extent is stored.
     *
     * @param picture the POI picture (not {@code null})
     * @return the width in pixels, or {@code 0}
     */
    public static int widthPixels(XWPFPicture picture) {
        return emuToPixels(extent(picture, true));
    }

    /**
     * Returns the picture's height in pixels (96&nbsp;DPI), or {@code 0} if no extent is stored.
     *
     * @param picture the POI picture (not {@code null})
     * @return the height in pixels, or {@code 0}
     */
    public static int heightPixels(XWPFPicture picture) {
        return emuToPixels(extent(picture, false));
    }

    /**
     * Returns the picture's stored format as a POI {@link PictureType}, or {@code null} if the
     * picture carries no data part.
     *
     * @param picture the POI picture (not {@code null})
     * @return the POI picture type, or {@code null}
     */
    public static PictureType pictureTypeOf(XWPFPicture picture) {
        XWPFPictureData data = picture.getPictureData();
        return data == null ? null : data.getPictureTypeEnum();
    }

    /**
     * Returns the raw picture bytes, or an empty array if the picture carries no data part.
     *
     * @param picture the POI picture (not {@code null})
     * @return the picture bytes (never {@code null})
     */
    public static byte[] bytesOf(XWPFPicture picture) {
        XWPFPictureData data = picture.getPictureData();
        return data == null ? new byte[0] : data.getData();
    }

    /**
     * Converts a pixel dimension (96&nbsp;DPI) to EMU, the unit Apache POI's {@code addPicture} stores
     * on the picture extent. This is the exact inverse of {@link #widthPixels} /
     * {@link #heightPixels}: {@code emuFromPixels(p)} &times; read-back yields {@code p} unchanged.
     *
     * <p>Apache POI's {@code XWPFRun.addPicture(..., int width, int height)} treats the width / height
     * as EMU (it stores them verbatim on the {@code <wp:extent>}), so callers working in pixels must
     * convert first.
     *
     * @param pixels the pixel value (96&nbsp;DPI)
     * @return the equivalent EMU value
     */
    public static int emuFromPixels(int pixels) {
        return pixels * Units.EMU_PER_PIXEL;
    }

    /**
     * Reads the picture's extent ({@code cx} for width, {@code cy} for height) in EMU. Returns
     * {@code 0} when the drawing extent is absent rather than throwing, so {@code equals} on the
     * public wrapper never throws on a malformed picture.
     */
    private static long extent(XWPFPicture picture, boolean width) {
        try {
            CTPicture ct = picture.getCTPicture();
            if (ct == null) {
                return 0L;
            }
            CTShapeProperties spPr = ct.getSpPr();
            if (spPr == null) {
                return 0L;
            }
            CTTransform2D xfrm = spPr.getXfrm();
            if (xfrm == null) {
                return 0L;
            }
            CTPositiveSize2D ext = xfrm.getExt();
            if (ext == null) {
                return 0L;
            }
            return width ? ext.getCx() : ext.getCy();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static int emuToPixels(long emu) {
        if (emu <= 0L) {
            return 0;
        }
        return (int) (emu / Units.EMU_PER_PIXEL);
    }
}
