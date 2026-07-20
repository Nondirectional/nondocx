package io.github.nondirectional.docx.core.api.header;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.image.Image;
import io.github.nondirectional.docx.core.api.image.ImageType;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证 {@link Header} / {@link Footer} 内的表格（{@link Header#addTable()} / {@link Header#tables()}）
 * 与图片（经 {@link Header#addParagraph()} → {@link Paragraph#addImage} 复用）在 save → reopen 往返中存活。
 *
 * <p>探针已确认 {@code XWPFHeader} 实现 {@code IBody}，故 {@code Paragraph.addImage} 的 part 关系解析在页眉 上下文里工作正常
 * —— 本测试固化该结论。
 */
class HeaderContentTest {

  @Test
  void headerTableRoundTrips(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("header-table.docx");
    Document original = Docx.create();
    original.ensureHeader().addTable().addRow().addCell().addParagraph().addRun("cell text");
    original.save(file);

    try (Document opened = Docx.open(file)) {
      assertThat(opened.header().tables()).hasSize(1);
      assertThat(opened.header().tables().get(0).rows().get(0).cells().get(0).text())
          .contains("cell text");
    }
  }

  @Test
  void headerImageRoundTripsViaParagraph(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("header-image.docx");
    byte[] png = solidPng(4, 4, 0xFF0000);
    Document original = Docx.create();
    original.ensureHeader().addParagraph().addImage(png, ImageType.PNG, 4, 4);
    original.save(file);

    try (Document opened = Docx.open(file)) {
      Paragraph p = opened.header().paragraph(0);
      assertThat(p.inlineElements()).hasSize(1);
      assertThat(p.inlineElement(0)).isInstanceOf(Image.class);
      assertThat(((Image) p.inlineElement(0)).bytes()).isEqualTo(png);
    }
  }

  @Test
  void paragraphAndTableCoexistInHeader(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("header-mixed.docx");
    Document original = Docx.create();
    original.ensureHeader().addParagraph().addRun("段落文本");
    original.ensureHeader().addTable().addRow().addCell().addParagraph().addRun("表格内容");
    original.save(file);

    try (Document opened = Docx.open(file)) {
      assertThat(opened.header().paragraphs()).hasSize(1);
      assertThat(opened.header().paragraph(0).text()).contains("段落文本");
      assertThat(opened.header().tables()).hasSize(1);
      assertThat(opened.header().tables().get(0).rows().get(0).cells().get(0).text())
          .contains("表格内容");
    }
  }

  @Test
  void headerTablesIsEmptyByDefault() {
    Document doc = Docx.create();
    doc.ensureHeader();
    assertThat(doc.header().tables()).isEmpty();
  }

  @Test
  void headerAddTableYieldsEmptyTable() {
    // addTable 返回真空表（剥掉 POI 预填），符合 nondocx「addX = exactly one X」契约。
    Document doc = Docx.create();
    io.github.nondirectional.docx.core.api.table.Table table = doc.ensureHeader().addTable();
    assertThat(table.rows()).isEmpty();
  }

  @Test
  void footerTableRoundTrips(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("footer-table.docx");
    Document original = Docx.create();
    original.ensureFooter().addTable().addRow().addCell().addParagraph().addRun("footer cell");
    original.save(file);

    try (Document opened = Docx.open(file)) {
      assertThat(opened.footer().tables()).hasSize(1);
      assertThat(opened.footer().tables().get(0).rows().get(0).cells().get(0).text())
          .contains("footer cell");
    }
  }

  @Test
  void footerImageRoundTripsViaParagraph(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("footer-image.docx");
    byte[] png = solidPng(3, 3, 0x0000FF);
    Document original = Docx.create();
    original.ensureFooter().addParagraph().addImage(png, ImageType.PNG, 3, 3);
    original.save(file);

    try (Document opened = Docx.open(file)) {
      Paragraph p = opened.footer().paragraph(0);
      assertThat(p.inlineElements()).hasSize(1);
      assertThat(p.inlineElement(0)).isInstanceOf(Image.class);
      assertThat(((Image) p.inlineElement(0)).bytes()).isEqualTo(png);
    }
  }

  /** 生成纯色 PNG（复制自 ImageTest.solidPng）。 */
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
      javax.imageio.ImageIO.write(img, "png", out);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
    return out.toByteArray();
  }
}
