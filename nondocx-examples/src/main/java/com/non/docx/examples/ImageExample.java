package com.non.docx.examples;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.image.ImageType;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * 演示在文档中嵌入内联图片。
 *
 * <p>OOXML 中，图片以 {@code <w:drawing>} 嵌入在 Run 内部，引用 {@code word/media/} 目录下的文件。
 * nondocx 的 {@code Paragraph.addImage(bytes, type, width, height)} 隐藏了这些复杂细节。</p>
 *
 * <p>本例在运行时生成纯色 PNG 图片，无需依赖外部图片文件。</p>
 */
public final class ImageExample {

  public static void main(String[] args) throws Exception {
    var output = ExamplePaths.outputDir().resolve("image-example.docx");
    output.toFile().getParentFile().mkdirs();

    System.out.println("创建图片演示文档...");
    try (Document doc = Docx.create()) {

      doc.addParagraph("图片嵌入演示").heading(com.non.docx.core.api.style.HeadingLevel.H1);

      doc.addParagraph("下面是一个红色方块（60×60 像素）：");
      // addImage() 是段落级方法，直接在 Paragraph 上调用
      doc.addParagraph().addImage(solidPng(60, 60, 0xFF0000), ImageType.PNG, 60, 60);

      doc.addParagraph("下面是一个蓝色方块（40×40 像素）：");
      doc.addParagraph().addImage(solidPng(40, 40, 0x0000FF), ImageType.PNG, 40, 40);

      doc.addParagraph("图片可以和其他文本混排在同一段落中：");
      var p = doc.addParagraph();
      p.addRun("前置文字 ");
      p.addImage(solidPng(20, 20, 0x00AA00), ImageType.PNG, 20, 20);
      p.addRun(" 后置文字");

      doc.save(output);
      System.out.println("已保存: " + output.toAbsolutePath());
    }
  }

  /** 生成纯色 PNG 字节。相同参数生成相同字节。 */
  static byte[] solidPng(int width, int height, int rgb) {
    var img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    try {
      g.setPaint(new Color(rgb, false));
      g.fillRect(0, 0, width, height);
    } finally {
      g.dispose();
    }
    var out = new ByteArrayOutputStream();
    try {
      ImageIO.write(img, "png", out);
    } catch (IOException e) {
      throw new RuntimeException("内存 ImageIO 写入失败", e);
    }
    return out.toByteArray();
  }

  private ImageExample() {}
}
