package com.non.docx.core.api.image;

import com.non.docx.core.api.InlineElement;
import com.non.docx.core.internal.poi.Mappers;
import com.non.docx.core.internal.poi.Pictures;
import com.non.docx.core.internal.util.Objects;
import org.apache.poi.xwpf.usermodel.XWPFPicture;

import java.util.Arrays;

/**
 * An inline image — a picture embedded inside a paragraph's run.
 *
 * <p>Holds an Apache POI {@code XWPFPicture} delegate and exposes a live view over it. Reads go
 * straight through to the delegate (and the underlying picture part); there is no cached snapshot.
 *
 * <p><b>Dimensions.</b> {@link #width()} and {@link #height()} return the picture's stored size in
 * <em>pixels</em> at 96&nbsp;DPI — the same unit Apache POI's {@code addPicture} takes on input.
 * They are read back from the picture's drawing extent (stored in EMU) and converted to pixels using
 * POI's {@code EMU_PER_PIXEL} constant (9525), which is the exact inverse of what {@code addPicture}
 * stores, so the pixel values survive a save → open round-trip without rounding loss.
 *
 * <p><b>Inline placement.</b> In OOXML an inline picture lives <em>inside</em> a run (as a drawing),
 * not as a sibling of runs. nondocx models it as its own {@link InlineElement} in the paragraph's
 * ordered inline view: when a run carries an embedded picture, that run is surfaced as an
 * {@code Image} (not a {@code com.non.docx.core.api.text.Run}) in
 * {@code Paragraph.inlineElements()}. A run that carries <em>both</em> text and a picture is an edge
 * case the MVP does not fully model — the text portion is not surfaced separately in that case and
 * remains reachable only via {@code raw()}. Most documents, and {@code Paragraph.addImage(...)}
 * itself, produce pure-image runs, so this is rarely hit in practice.
 *
 * <p>Content equality ({@code equals} / {@code hashCode}) compares the image type, the pixel
 * dimensions and the raw picture bytes (compared byte-for-byte for fidelity), never the delegate
 * reference. This is what makes round-trip image assertions fidelity-checking. The byte array can be
 * large; this is acceptable for test fixtures but means {@code Image} is not well suited as a
 * long-lived {@code HashMap} key, since the underlying content can change at any time.
 */
public final class Image implements InlineElement {

    private final XWPFPicture delegate;

    /**
     * Wraps the given POI inline picture.
     *
     * <p>This constructor is the internal seam by which {@link com.non.docx.core.api.text.Paragraph}
     * produces live image wrappers, so it accepts a POI type by design. Users normally obtain images
     * via {@code Paragraph.addImage(...)} rather than constructing them directly.
     *
     * @param delegate the backing POI picture (not {@code null})
     * @throws IllegalArgumentException if {@code delegate} is {@code null}
     */
    public Image(XWPFPicture delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * Returns the image format.
     *
     * <p>The format is resolved from the embedded picture part. Picture formats nondocx does not
     * model (for example BMP, EMF or WMF) are reported as {@code null}; the raw picture part remains
     * reachable via {@link #raw()} in that case.
     *
     * @return the image type, or {@code null} if the format is not one nondocx models
     */
    public ImageType type() {
        return Mappers.fromPoi(Pictures.pictureTypeOf(delegate));
    }

    /**
     * Returns the picture's width in pixels at 96&nbsp;DPI.
     *
     * @return the width in pixels, or {@code 0} if no extent is stored
     */
    public int width() {
        return Pictures.widthPixels(delegate);
    }

    /**
     * Returns the picture's height in pixels at 96&nbsp;DPI.
     *
     * @return the height in pixels, or {@code 0} if no extent is stored
     */
    public int height() {
        return Pictures.heightPixels(delegate);
    }

    /**
     * Returns the raw picture bytes.
     *
     * @return the picture bytes (possibly empty, never {@code null})
     */
    public byte[] bytes() {
        return Pictures.bytesOf(delegate);
    }

    /**
     * Returns the underlying POI picture.
     * <p>
     * Modifications to the returned object affect the document immediately. Use with caution.
     *
     * @return the backing {@code XWPFPicture} instance (same instance for the wrapper's lifetime)
     */
    public XWPFPicture raw() {
        return delegate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Image)) {
            return false;
        }
        Image that = (Image) o;
        return this.width() == that.width()
                && this.height() == that.height()
                && java.util.Objects.equals(this.type(), that.type())
                && Arrays.equals(this.bytes(), that.bytes());
    }

    @Override
    public int hashCode() {
        int result = java.util.Objects.hash(width(), height(), type());
        result = 31 * result + Arrays.hashCode(bytes());
        return result;
    }

    @Override
    public String toString() {
        return "Image{type=" + type() + ", width=" + width() + ", height=" + height()
                + ", bytes=" + bytes().length + '}';
    }
}
