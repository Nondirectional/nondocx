package com.non.docx.core.api.header;

import static org.assertj.core.api.Assertions.assertThat;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.image.Image;
import com.non.docx.core.api.image.ImageType;
import com.non.docx.core.api.text.Paragraph;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 父任务 {@code 06-23-header-footer-variants} 的集成验收：三条能力线（变体 + 富内容 + 页码域）协同工作。
 *
 * <p>不重复单子任务的边界用例，只验证「组合场景」—— 把变体、表格、图片、页码域放在一起，确认 round-trip 全部存活、无相互干扰。对应父任务 {@code prd.md} 的
 * AC3。
 */
class HeaderFooterIntegrationTest {

  @Test
  void firstVariantHeaderWithTableImageAndPageFieldRoundTrips(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("integration.docx");
    byte[] logo = solidPng(8, 4, 0x336699);

    Document original = Docx.create();
    // 首页变体页眉：放 logo 图片 + 页码域 + 一个表格
    Header firstHeader = original.ensureHeader(HeaderFooterVariant.FIRST);
    firstHeader.addParagraph().addImage(logo, ImageType.PNG, 8, 4);
    firstHeader.addParagraph().addPageNumberField();
    firstHeader.addTable().addRow().addCell().addParagraph().addRun("首页表格");
    // 默认页脚也放个总页数域
    original.ensureFooter().addParagraph().addPageCountField();
    original.save(file);

    try (Document opened = Docx.open(file)) {
      Header first = opened.header(HeaderFooterVariant.FIRST);

      // 图片存活
      Paragraph imgPara = first.paragraph(0);
      assertThat(imgPara.inlineElements()).hasSize(1);
      assertThat(imgPara.inlineElement(0)).isInstanceOf(Image.class);
      assertThat(((Image) imgPara.inlineElement(0)).bytes()).isEqualTo(logo);

      // 页码域存活（instrText 读回 PAGE，走 raw 因为读侧不在范围）
      assertThat(readInstrText(first.paragraph(1))).isEqualTo("PAGE");

      // 表格存活
      assertThat(first.tables()).hasSize(1);
      assertThat(first.tables().get(0).rows().get(0).cells().get(0).text()).contains("首页表格");

      // 默认页脚的总页数域存活
      assertThat(readInstrText(opened.footer().paragraph(0))).isEqualTo("NUMPAGES");

      // 首页开关也写对了
      assertThat(opened.section(0).raw().isSetTitlePg()).isTrue();
    }
  }

  @Test
  void threeVariantsWithDifferentContentRoundTrips(@TempDir Path tmp) throws Exception {
    // 三变体各有不同的富内容，round-trip 后互不干扰。
    Path file = tmp.resolve("three-rich.docx");
    byte[] redDot = solidPng(2, 2, 0xFF0000);
    byte[] blueDot = solidPng(2, 2, 0x0000FF);

    Document original = Docx.create();
    original.ensureHeader().addParagraph().addRun("默认纯文本");
    original
        .ensureHeader(HeaderFooterVariant.FIRST)
        .addParagraph()
        .addImage(redDot, ImageType.PNG, 2, 2);
    original
        .ensureHeader(HeaderFooterVariant.EVEN)
        .addTable()
        .addRow()
        .addCell()
        .addParagraph()
        .addRun("偶数页表格");
    original
        .ensureFooter(HeaderFooterVariant.EVEN)
        .addParagraph()
        .addImage(blueDot, ImageType.PNG, 2, 2);
    original.save(file);

    try (Document opened = Docx.open(file)) {
      // 默认：纯文本
      assertThat(opened.header().paragraph(0).text()).contains("默认纯文本");
      assertThat(opened.header().tables()).isEmpty();

      // 首页：图片
      Header first = opened.header(HeaderFooterVariant.FIRST);
      assertThat(first.paragraph(0).inlineElement(0)).isInstanceOf(Image.class);
      assertThat(first.tables()).isEmpty();

      // 偶数页：表格
      Header even = opened.header(HeaderFooterVariant.EVEN);
      assertThat(even.tables()).hasSize(1);
      assertThat(even.tables().get(0).rows().get(0).cells().get(0).text()).contains("偶数页表格");

      // 偶数页脚：图片
      Footer evenFooter = opened.footer(HeaderFooterVariant.EVEN);
      assertThat(evenFooter.paragraph(0).inlineElement(0)).isInstanceOf(Image.class);

      // 两个开关都写了
      assertThat(opened.section(0).raw().isSetTitlePg()).isTrue();
      assertThat(opened.raw().getSettings().getCTSettings().isSetEvenAndOddHeaders()).isTrue();
    }
  }

  /** 读段落的 instrText（域指令）。读侧不在公开 API 范围，走 raw CTR。 */
  private static String readInstrText(Paragraph p) {
    for (XWPFRun run : p.raw().getRuns()) {
      if (run.getCTR().sizeOfInstrTextArray() > 0) {
        return run.getCTR().getInstrTextArray(0).getStringValue();
      }
    }
    return null;
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
