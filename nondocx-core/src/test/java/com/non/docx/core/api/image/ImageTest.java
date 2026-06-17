package com.non.docx.core.api.image;

import static org.assertj.core.api.Assertions.assertThat;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.InlineElement;
import com.non.docx.core.api.text.Paragraph;
import com.non.docx.core.api.text.Run;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证内联图片在保存→打开往返中存活，参与段落的 有序内联视图，并对内容相等性有贡献（设计文档 §3.1、§4.3、§7）。
 *
 * <p>测试图片字节在进程中通过 {@code javax.imageio} 生成（纯色 PNG）， 因此无需签入二进制夹具。两个具有相同尺寸和颜色的 {@code solidPng} 调用
 * 产生相同的字节；不同颜色或尺寸产生不同的字节。
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
    // runs() 是仅 run 的过滤视图：图片被排除在外

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
    byte[] samePng = png; // 相同引用→相同字节

    Image a = Docx.create().addParagraph().addImage(png, ImageType.PNG, 6, 6);
    Image b = Docx.create().addParagraph().addImage(samePng, ImageType.PNG, 6, 6);

    // 直接的 Image 内容相等性（类型 + 尺寸 + 字节）
    assertThat(a).isEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());

    // 并且它流入段落相等性，因为图片位于内联顺序中
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
    assertThat(red).isNotEqualTo(blue); // 合理性检查：夹具确实不同

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
    // raw() 在包装器生命周期内返回相同的非空 POI 图片实例
    assertThat(image.raw()).isSameAs(image.raw());
    assertThat(image.raw()).isNotNull();
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
