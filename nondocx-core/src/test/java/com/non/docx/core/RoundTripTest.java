package com.non.docx.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.non.docx.core.api.Document;
import com.non.docx.core.api.image.Image;
import com.non.docx.core.api.image.ImageType;
import com.non.docx.core.api.section.Orientation;
import com.non.docx.core.api.section.PaperSize;
import com.non.docx.core.api.section.Section;
import com.non.docx.core.api.style.HeadingLevel;
import com.non.docx.core.api.style.ListKind;
import com.non.docx.core.api.text.Hyperlink;
import com.non.docx.core.api.text.Run;
import com.non.docx.core.builder.DocumentBuilder;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The MVP round-trip acceptance suite (design §7). A document is built with nondocx, written to a
 * real {@code .docx} file via {@code save}, reopened via {@link Docx#open}, and the reopened
 * document is asserted to be <em>content-equal</em> to the original. Because {@link
 * Document#equals} compares the ordered body element sequence and the ordered section sequence —
 * and each element compares only content derived from its POI delegate (never the delegate
 * reference) — a passing assertion proves that nondocx's deep-wrap covers every modeled feature
 * end-to-end across POI's actual write / read path.
 *
 * <p>{@link #fullDocumentRoundTripsEqual()} is the core acceptance test: one document exercising
 * every tier at once. The remaining tests each isolate a single feature so that a regression points
 * straight at the offending area.
 *
 * <p>Test picture bytes are generated in-process with {@code javax.imageio} (a solid-color PNG), so
 * no binary fixture is checked in. The bytes are deterministic for given size and color.
 */
class RoundTripTest {

  @Test
  void fullDocumentRoundTripsEqual(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("full-roundtrip.docx");
    Document original = buildFullDocument();

    original.save(file);
    try (Document readBack = Docx.open(file)) {
      // CORE ACCEPTANCE: deep content equality across save → open.
      assertThat(readBack).isEqualTo(original);

      // A few explicit feature assertions make the test's intent legible and give a clearer
      // first signal than the whole-document diff if a future regression sneaks in.
      assertThat(readBack.bodyElements()).hasSameSizeAs(original.bodyElements());

      // tier 1: heading + styled run survive
      assertThat(readBack.paragraph(0).heading()).isEqualTo(HeadingLevel.H1);

      // tier 1/2: inline ordering (styled run → hyperlink → tail run) survives
      assertThat(readBack.paragraph(1).inlineElements()).hasSize(3);
      assertThat(readBack.paragraph(1).inlineElement(0)).isInstanceOf(Run.class);
      assertThat(readBack.paragraph(1).inlineElement(1)).isInstanceOf(Hyperlink.class);
      assertThat(readBack.paragraph(1).inlineElement(2)).isInstanceOf(Run.class);

      // tier 2: list membership + nesting survives. Note paragraph(int) indexes the
      // filtered paragraph view — the body table between the styled paragraph and the list
      // paragraphs is skipped — so the numbered/bullet/image paragraphs sit at indices 2/3/4.
      assertThat(readBack.paragraph(2).listKind()).isEqualTo(ListKind.NUMBERED);
      assertThat(readBack.paragraph(2).listLevel()).isEqualTo(0);
      assertThat(readBack.paragraph(3).listKind()).isEqualTo(ListKind.BULLET);
      assertThat(readBack.paragraph(3).listLevel()).isEqualTo(1);

      // tier 2: inline image survives (bytes, dimensions, type)
      Image image = (Image) readBack.paragraph(4).inlineElement(0);
      assertThat(image.bytes()).isEqualTo(((Image) original.paragraph(4).inlineElement(0)).bytes());

      // tier 3: page properties + section-scoped header / footer survive
      assertThat(readBack.section(0).orientation()).isEqualTo(Orientation.LANDSCAPE);
      assertThat(readBack.section(0).paperSize()).isEqualTo(PaperSize.A4);
      assertThat(readBack.section(0).header().text()).contains("Running header");
      assertThat(readBack.section(0).footer().text()).contains("Running footer");
    }
  }

  @Test
  void headingAndStyledRunsRoundTrip(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("heading-styled.docx");

    Document original =
        DocumentBuilder.start()
            .heading(HeadingLevel.H2, "Section title")
            .paragraph(
                p ->
                    p.addRun("Important")
                        .bold()
                        .italic()
                        .underline()
                        .fontSize(18)
                        .font("Arial")
                        .color("0066CC"))
            .build();

    original.save(file);
    try (Document readBack = Docx.open(file)) {
      assertThat(readBack).isEqualTo(original);

      assertThat(readBack.paragraph(0).heading()).isEqualTo(HeadingLevel.H2);
      Run styled = readBack.paragraph(1).run(0);
      assertThat(styled.text()).isEqualTo("Important");
      assertThat(styled.isBold()).isTrue();
      assertThat(styled.isItalic()).isTrue();
      assertThat(styled.isUnderline()).isTrue();
      assertThat(styled.fontSize()).isEqualTo(18);
      assertThat(styled.font()).isEqualTo("Arial");
      assertThat(styled.color()).isEqualTo("0066CC");
    }
  }

  @Test
  void hyperlinkRoundTrip(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("hyperlink.docx");

    Document original = Docx.create();
    original.addParagraph().addHyperlink("Example", "https://example.com");

    original.save(file);
    try (Document readBack = Docx.open(file)) {
      assertThat(readBack).isEqualTo(original);

      assertThat(readBack.paragraph(0).inlineElements()).hasSize(1);
      assertThat(readBack.paragraph(0).inlineElement(0)).isInstanceOf(Hyperlink.class);
      Hyperlink link = (Hyperlink) readBack.paragraph(0).inlineElement(0);
      assertThat(link.text()).isEqualTo("Example");
      assertThat(link.url()).isEqualTo("https://example.com");
    }
  }

  @Test
  void imageRoundTrip(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("image.docx");
    byte[] png = solidPng(10, 8, 0x9933CC);

    Document original = Docx.create();
    original.addParagraph().addImage(png, ImageType.PNG, 10, 8);

    original.save(file);
    try (Document readBack = Docx.open(file)) {
      assertThat(readBack).isEqualTo(original);

      assertThat(readBack.paragraph(0).inlineElements()).hasSize(1);
      assertThat(readBack.paragraph(0).inlineElement(0)).isInstanceOf(Image.class);
      Image image = (Image) readBack.paragraph(0).inlineElement(0);
      assertThat(image.type()).isEqualTo(ImageType.PNG);
      assertThat(image.width()).isEqualTo(10);
      assertThat(image.height()).isEqualTo(8);
      assertThat(image.bytes()).containsExactly(png);
    }
  }

  @Test
  void listRoundTrip(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("list.docx");

    Document original =
        DocumentBuilder.start()
            .paragraph(p -> p.list(ListKind.NUMBERED, 0).addRun("first"))
            .paragraph(p -> p.list(ListKind.NUMBERED, 0).addRun("second"))
            .paragraph(p -> p.list(ListKind.BULLET, 1).addRun("nested under second"))
            .build();

    original.save(file);
    try (Document readBack = Docx.open(file)) {
      assertThat(readBack).isEqualTo(original);

      assertThat(readBack.paragraph(0).listKind()).isEqualTo(ListKind.NUMBERED);
      assertThat(readBack.paragraph(0).listLevel()).isEqualTo(0);
      assertThat(readBack.paragraph(2).listKind()).isEqualTo(ListKind.BULLET);
      assertThat(readBack.paragraph(2).listLevel()).isEqualTo(1);
    }
  }

  @Test
  void tableRoundTrip(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("table.docx");

    Document original =
        DocumentBuilder.start()
            .table(
                t ->
                    t.row(r -> r.cell("A1").cell("B1").cell("C1"))
                        .row(r -> r.cell("A2").cell("B2").cell("C2")))
            .build();

    original.save(file);
    try (Document readBack = Docx.open(file)) {
      assertThat(readBack).isEqualTo(original);

      assertThat(readBack.tables()).hasSize(1);
      assertThat(readBack.tables().get(0).rows()).hasSize(2);
      assertThat(readBack.tables().get(0).row(0).cells()).hasSize(3);
      assertThat(readBack.tables().get(0).row(0).cell(1).text()).isEqualTo("B1");
      assertThat(readBack.tables().get(0).row(1).cell(2).text()).isEqualTo("C2");
    }
  }

  @Test
  void sectionPropertiesRoundTrip(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("section.docx");

    Document original = Docx.create();
    Section section = original.section(0);
    section
        .paperSize(PaperSize.A4)
        .orientation(Orientation.LANDSCAPE)
        .margins(1440, 1080, 1440, 1080);

    original.save(file);
    try (Document readBack = Docx.open(file)) {
      assertThat(readBack).isEqualTo(original);

      Section read = readBack.section(0);
      assertThat(read.paperSize()).isEqualTo(PaperSize.A4);
      assertThat(read.orientation()).isEqualTo(Orientation.LANDSCAPE);
      assertThat(read.marginTop()).isEqualTo(1440);
      assertThat(read.marginRight()).isEqualTo(1080);
      assertThat(read.marginBottom()).isEqualTo(1440);
      assertThat(read.marginLeft()).isEqualTo(1080);
    }
  }

  @Test
  void headerFooterRoundTrip(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("header-footer.docx");

    Document original = Docx.create();
    original.section(0).header().addParagraph().addRun("Running header");
    original.section(0).footer().addParagraph().addRun("Running footer");

    original.save(file);
    try (Document readBack = Docx.open(file)) {
      assertThat(readBack).isEqualTo(original);

      assertThat(readBack.section(0).header().text()).contains("Running header");
      assertThat(readBack.section(0).footer().text()).contains("Running footer");
    }
  }

  /**
   * Builds a single document exercising every MVP tier at once: a heading, a paragraph whose inline
   * content mixes a fully-styled run, a hyperlink and a plain run (to exercise inline ordering and
   * run style), a 2×2 table, a numbered list item plus a nested bullet item, an inline image, and a
   * section carrying page properties plus a header and a footer.
   *
   * <p>The document is built through the same write path on both sides of the round-trip: the
   * original is constructed in-memory and then {@code save}d; the reopened document has been
   * through POI's serialization. Content equality holds without any field exclusion, which is the
   * round-trip fidelity acceptance bar.
   */
  private static Document buildFullDocument() {
    byte[] png = solidPng(16, 12, 0x336699);

    Document document =
        DocumentBuilder.start()
            // tier 1: heading
            .heading(HeadingLevel.H1, "Round-trip Title")
            // tier 1/2: inline ordering — styled run, hyperlink, plain tail run
            .paragraph(
                p -> {
                  Run styled = p.addRun("Bold italic underlined");
                  styled.bold().italic().underline().fontSize(14).font("Arial").color("FF0000");
                  p.addHyperlink("Example", "https://example.com");
                  p.addRun(" plain tail");
                })
            // tier 1: table
            .table(t -> t.row(r -> r.cell("A1").cell("B1")).row(r -> r.cell("A2").cell("B2")))
            // tier 2: numbered list (level 0) + nested bullet (level 1)
            .paragraph(p -> p.list(ListKind.NUMBERED, 0).addRun("First numbered item"))
            .paragraph(p -> p.list(ListKind.BULLET, 1).addRun("Nested bullet item"))
            // tier 2: inline image
            .paragraph(p -> p.addImage(png, ImageType.PNG, 16, 12))
            .build();

    // tier 3: page properties + section-scoped header / footer on the single section
    Section section = document.section(0);
    section
        .paperSize(PaperSize.A4)
        .orientation(Orientation.LANDSCAPE)
        .margins(1440, 1080, 1440, 1080);
    section.header().addParagraph().addRun("Running header");
    section.footer().addParagraph().addRun("Running footer");

    return document;
  }

  /**
   * Produces a deterministic solid-color PNG of the given size. Two calls with identical parameters
   * yield identical bytes. {@code javax.imageio} writing to an in-memory stream cannot plausibly
   * throw {@link IOException}; any failure is rethrown as an {@link AssertionError} so callers need
   * not declare checked exceptions.
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
