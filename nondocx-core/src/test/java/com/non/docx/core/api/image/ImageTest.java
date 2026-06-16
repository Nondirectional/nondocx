package com.non.docx.core.api.image;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.InlineElement;
import com.non.docx.core.api.text.Paragraph;
import com.non.docx.core.api.text.Run;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that inline images round-trip through save → open, take part in a paragraph's ordered
 * inline view, and contribute to content equality (design §3.1, §4.3, §7).
 *
 * <p>Test picture bytes are generated in-process with {@code javax.imageio} (a solid-color PNG),
 * so no binary fixture is checked in. Two {@code solidPng} calls with the same size and color
 * produce identical bytes; differing colors or sizes produce differing bytes.
 */
class ImageTest {

    @Test
    void addImageRoundTrips(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("image.docx");
        byte[] png = solidPng(8, 12, 0xFF0000);

        Document original = Docx.create();
        original.addParagraph().addImage(png, ImageType.PNG, 8, 12);
        original.save(file);

        try (Document opened = Docx.open(file)) {
            List<InlineElement> inline = opened.paragraph(0).inlineElements();
            assertThat(inline).hasSize(1);
            assertThat(inline.get(0)).isInstanceOf(Image.class);

            Image image = (Image) inline.get(0);
            assertThat(image.type()).isEqualTo(ImageType.PNG);
            assertThat(image.width()).isEqualTo(8);
            assertThat(image.height()).isEqualTo(12);
            assertThat(image.bytes()).containsExactly(png);
        }
    }

    @Test
    void imageParticipatesInInlineOrder() {
        byte[] png = solidPng(4, 4, 0x00FF00);

        Paragraph paragraph = Docx.create().addParagraph();
        paragraph.addRun("text");
        paragraph.addImage(png, ImageType.PNG, 4, 4);
        paragraph.addRun("tail");

        List<InlineElement> inline = paragraph.inlineElements();
        assertThat(inline).hasSize(3);
        assertThat(inline.get(0)).isInstanceOf(Run.class);
        assertThat(inline.get(1)).isInstanceOf(Image.class);
        assertThat(inline.get(2)).isInstanceOf(Run.class);

        assertThat(((Run) inline.get(0)).text()).isEqualTo("text");
        assertThat(((Run) inline.get(2)).text()).isEqualTo("tail");

        // runs() is the Run-only filtered view: images are excluded
        assertThat(paragraph.runs()).hasSize(2);
    }

    @Test
    void imageParticipatesInInlineOrderAcrossRoundTrip(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("order-image.docx");
        byte[] png = solidPng(4, 4, 0x0000FF);

        Document original = Docx.create();
        Paragraph paragraph = original.addParagraph();
        paragraph.addRun("r1");
        paragraph.addImage(png, ImageType.PNG, 4, 4);
        paragraph.addRun("r2");
        original.save(file);

        try (Document opened = Docx.open(file)) {
            List<InlineElement> inline = opened.paragraph(0).inlineElements();
            assertThat(inline).hasSize(3);
            assertThat(inline.get(0)).isInstanceOf(Run.class);
            assertThat(inline.get(1)).isInstanceOf(Image.class);
            assertThat(((Image) inline.get(1)).bytes()).containsExactly(png);
            assertThat(inline.get(2)).isInstanceOf(Run.class);
        }
    }

    @Test
    void identicalImagesAreEqualDirectlyAndViaParagraph() {
        byte[] png = solidPng(6, 6, 0x102030);
        byte[] samePng = png; // identical reference → identical bytes

        Image a = Docx.create().addParagraph().addImage(png, ImageType.PNG, 6, 6);
        Image b = Docx.create().addParagraph().addImage(samePng, ImageType.PNG, 6, 6);

        // direct Image content equality (type + dimensions + bytes)
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());

        // and it flows into paragraph equality, since images sit in the inline order
        Paragraph pa = Docx.create().addParagraph();
        pa.addImage(png, ImageType.PNG, 6, 6);
        Paragraph pb = Docx.create().addParagraph();
        pb.addImage(samePng, ImageType.PNG, 6, 6);
        assertThat(pa).isEqualTo(pb);
    }

    @Test
    void differingImageBytesAreNotEqual() {
        byte[] red = solidPng(6, 6, 0xFF0000);
        byte[] blue = solidPng(6, 6, 0x0000FF);
        assertThat(red).isNotEqualTo(blue); // sanity: the fixtures really do differ

        Image a = Docx.create().addParagraph().addImage(red, ImageType.PNG, 6, 6);
        Image b = Docx.create().addParagraph().addImage(blue, ImageType.PNG, 6, 6);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void differingImageDimensionsAreNotEqual() {
        byte[] png = solidPng(6, 6, 0x405060);

        Image a = Docx.create().addParagraph().addImage(png, ImageType.PNG, 6, 6);
        Image b = Docx.create().addParagraph().addImage(png, ImageType.PNG, 12, 6);

        assertThat(a.width()).isEqualTo(6);
        assertThat(b.width()).isEqualTo(12);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void rawReturnsTheBackingPoiPicture() {
        byte[] png = solidPng(3, 3, 0x111111);
        Image image = Docx.create().addParagraph().addImage(png, ImageType.PNG, 3, 3);
        // raw() returns the same non-null POI picture instance for the wrapper's lifetime
        assertThat(image.raw()).isSameAs(image.raw());
        assertThat(image.raw()).isNotNull();
    }

    /**
     * Produces a deterministic solid-color PNG of the given size. Two calls with identical
     * parameters yield identical bytes. {@code javax.imageio} writing to an in-memory stream
     * cannot plausibly throw {@link IOException}; any failure is rethrown as an {@link AssertionError}
     * so callers need not declare checked exceptions.
     */
    private static byte[] solidPng(int width, int height, int rgb) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setPaint(new Color(rgb, false));
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", out);
        } catch (IOException e) {
            throw new AssertionError("In-memory ImageIO write failed", e);
        }
        return out.toByteArray();
    }
}
