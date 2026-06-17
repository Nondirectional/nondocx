package com.non.docx.examples;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.section.PaperSize;
import com.non.docx.core.api.style.Alignment;
import java.nio.file.Path;

/**
 * 演示页眉和页脚。
 *
 * <p>OOXML 中，页眉/页脚是独立的 ZIP 部分 ({@code word/header*.xml}、{@code word/footer*.xml})， 通过章节属性 ({@code
 * <w:sectPr>}) 中的 {@code <w:headerReference>} / {@code <w:footerReference>} 引用。
 *
 * <p>nondocx 通过 {@code Document.ensureHeader()} 和 {@code Document.ensureFooter()} 提供便捷的<b>显式创建</b>访问，
 * 内部委托 POI 的 {@code XWPFHeaderFooterPolicy} 在不存在时创建页眉/页脚部分。 （读写分离后，{@code header()} / {@code footer()} 改为纯只读、
 * 不存在返回 null；需要拿到可写入的页眉页脚时用 {@code ensureXxx()}。） 为了让生成结果在 WPS 等对极简 {@code <w:sectPr>}
 * 更敏感的消费者里更稳定地显示，本示例还会显式写入页面大小与页边距。
 */
public final class HeaderFooterExample {

  public static void main(String[] args) throws Exception {
    Path output = ExamplePaths.outputDir().resolve("header-footer-example.docx");
    output.toFile().getParentFile().mkdirs();

    System.out.println("创建页眉页脚演示文档...");
    try (Document doc = Docx.create()) {

      // ---- 页面设置 ----
      // OOXML 里，页眉页脚引用和页面尺寸/边距都挂在同一个 <w:sectPr> 下。
      // 这里显式写入 A4 + 1 英寸边距，让生成结果在 WPS 中更稳定地显示页眉页脚。
      doc.section(0).paperSize(PaperSize.A4).margins(1440, 1440, 1440, 1440);

      // ---- 页眉 ----
      // ensureHeader() 显式创建并返回 Header（不存在才建）；addParagraph() 返回 Paragraph
      // alignment() 是段落级方法，在 Paragraph 上调用
      // 注：header() 是只读访问（不存在返回 null），写入场景用 ensureHeader()。
      var headerPara = doc.ensureHeader().addParagraph();
      headerPara.addRun("nondocx 示例文档").bold().fontSize(10);
      headerPara.alignment(Alignment.CENTER);

      // ---- 正文 ----
      doc.addParagraph("页眉页脚演示").heading(com.non.docx.core.api.style.HeadingLevel.H1);

      doc.addParagraph("本页顶部应显示页眉文字「nondocx 示例文档」。");
      doc.addParagraph("底部应显示版权信息（见页脚）。");

      // 写几个空行让页脚可见
      for (int i = 2; i <= 15; i++) {
        doc.addParagraph("第 " + i + " 行——页脚位于页面底部。");
      }

      // ---- 页脚 ----
      // ensureFooter() 显式创建并返回 Footer；addParagraph() 返回 Paragraph
      var footerPara = doc.ensureFooter().addParagraph();
      footerPara.addRun("版权所有 © 2025 nondocx").fontSize(9).color("666666");
      footerPara.alignment(Alignment.CENTER);

      doc.save(output);
      System.out.println("已保存: " + output.toAbsolutePath());
    }
  }

  private HeaderFooterExample() {}
}
