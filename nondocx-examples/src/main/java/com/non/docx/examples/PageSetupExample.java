package com.non.docx.examples;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.section.Orientation;
import com.non.docx.core.api.section.PaperSize;
import java.nio.file.Path;

/**
 * 演示页面设置：纸张大小、方向、边距。
 *
 * <p>OOXML 中页面属性存储在章节属性 {@code <w:sectPr>} 下：
 *
 * <ul>
 *   <li>{@code <w:pgSz>} — 纸张大小（width/height）和方向（orient 标志）
 *   <li>{@code <w:pgMar>} — 四边边距（top/right/bottom/left，单位：缇，1 缇 = 1/20 磅）
 * </ul>
 *
 * nondocx 的 {@code Section} 封装了这些操作，自动处理方向与尺寸的交换逻辑。
 */
public final class PageSetupExample {

  public static void main(String[] args) throws Exception {
    Path output = ExamplePaths.outputDir().resolve("page-setup-example.docx");
    output.toFile().getParentFile().mkdirs();

    System.out.println("创建页面设置演示文档...");
    try (Document doc = Docx.create()) {

      // 获取第一个（也是默认的）章节
      // 一个文档总是有至少一个章节
      doc.section(0)
          // 设置纸张为 A4（11906 × 16838 缇）
          .paperSize(PaperSize.A4)
          // 设置为横向
          .orientation(Orientation.LANDSCAPE)
          // 设置边距：上下 1 英寸（1440 缇），左右 1.25 英寸（1800 缇）
          .margins(1440, 1800, 1440, 1800);

      doc.addParagraph("文档采用 A4 横向布局。");
      doc.addParagraph("四周边距已自定义。");

      System.out.println("  纸张大小: " + doc.section(0).paperSize());
      System.out.println("  方向:     " + doc.section(0).orientation());
      System.out.println(
          "  边距:     top="
              + doc.section(0).marginTop()
              + " right="
              + doc.section(0).marginRight()
              + " bottom="
              + doc.section(0).marginBottom()
              + " left="
              + doc.section(0).marginLeft());

      doc.save(output);
      System.out.println("已保存: " + output.toAbsolutePath());
    }
  }

  private PageSetupExample() {}
}
