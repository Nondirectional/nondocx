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
 * {@link Document#equals} 比较有序的正文元素序列和有序的节序列——
 * 且每个元素只比较从其 POI 委托派生的内容（从不比较委托引用）——
 * 通过的断言证明 nondocx 的深度包装覆盖了每个建模功能，跨越 POI 的实际写入/读取路径端到端。
 *
 * <p>{@link #fullDocumentRoundTripsEqual()} 是核心验收测试：一个文档一次性练习所有层级。
 * 其余测试各自隔离一个功能，以便回归能直指问题区域。
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
      // 核心验收：保存→打开的深层内容相等性。
      assertThat(readBack).isEqualTo(original);

      // 几个显式功能断言使测试意图更清晰，并在未来回归出现时提供比整个文档差异
      // 更明确的初始信号。
      assertThat(readBack.bodyElements()).hasSameSizeAs(original.bodyElements());

      // 层级 1：标题 + 样式化 run 存活
      assertThat(readBack.paragraph(0).heading()).isEqualTo(HeadingLevel.H1);

      // 层级 1/2：内联顺序（样式化 run → 超链接 → 尾部 run）存活
      assertThat(readBack.paragraph(1).inlineElements()).hasSize(3);
      assertThat(readBack.paragraph(1).inlineElement(0)).isInstanceOf(Run.class);
      assertThat(readBack.paragraph(1).inlineElement(1)).isInstanceOf(Hyperlink.class);
      assertThat(readBack.paragraph(1).inlineElement(2)).isInstanceOf(Run.class);

      // 层级 2：列表成员资格 + 嵌套存活。注意 paragraph(int) 索引的是
      // 过滤后的段落视图——样式段落和列表段落之间的正文表格被跳过——
      // 因此编号/项目符号/图片段落位于索引 2/3/4 处。
      assertThat(readBack.paragraph(2).listKind()).isEqualTo(ListKind.NUMBERED);
      assertThat(readBack.paragraph(2).listLevel()).isEqualTo(0);
      assertThat(readBack.paragraph(3).listKind()).isEqualTo(ListKind.BULLET);
      assertThat(readBack.paragraph(3).listLevel()).isEqualTo(1);

      // 层级 2：内联图片存活（字节、尺寸、类型）
      Image image = (Image) readBack.paragraph(4).inlineElement(0);
      assertThat(image.bytes()).isEqualTo(((Image) original.paragraph(4).inlineElement(0)).bytes());

      // 层级 3：页面属性 + 节作用域的页眉/页脚存活
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
   * 构建一个同时练习所有 MVP 层级的单个文档：一个标题、一个内联内容混合了完全样式化 run、 超链接和纯文本 run（以练习内联顺序和 run 样式）的段落、一个 2×2
   * 表格、一个编号列表项 加一个嵌套的项目符号项、一个内联图片，以及一个携带页面属性加页眉和页脚的节。
   *
   * <p>文档在往返的两侧通过相同的写入路径构建：原始文档在内存中构造然后 {@code save}d； 重新打开的文档经过了 POI 的序列化。内容相等性不需要任何字段排除即可成立，
   * 这就是往返保真度的验收标准。
   */
  private static Document buildFullDocument() {
    byte[] png = solidPng(16, 12, 0x336699);

    Document document =
        DocumentBuilder.start()
            // 层级 1：标题
            .heading(HeadingLevel.H1, "Round-trip Title")
            // 层级 1/2：内联顺序——样式化 run、超链接、纯文本尾部 run
            .paragraph(
                p -> {
                  Run styled = p.addRun("Bold italic underlined");
                  styled.bold().italic().underline().fontSize(14).font("Arial").color("FF0000");
                  p.addHyperlink("Example", "https://example.com");
                  p.addRun(" plain tail");
                })
            // 层级 1：表格
            .table(t -> t.row(r -> r.cell("A1").cell("B1")).row(r -> r.cell("A2").cell("B2")))
            // 层级 2：编号列表（级别 0）+ 嵌套项目符号（级别 1）
            .paragraph(p -> p.list(ListKind.NUMBERED, 0).addRun("First numbered item"))
            .paragraph(p -> p.list(ListKind.BULLET, 1).addRun("Nested bullet item"))
            // 层级 2：内联图片
            .paragraph(p -> p.addImage(png, ImageType.PNG, 16, 12))
            .build();

    // 层级 3：单节上的页面属性 + 节作用域的页眉/页脚
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
   * 生成给定尺寸的确定性纯色 PNG。两个具有相同参数的调用产生相同的字节。 {@code javax.imageio} 写入内存流不可能抛出 {@link IOException}；
   * 任何失败都会重新抛出为 {@link AssertionError}，因此调用者无需声明受检异常。
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
