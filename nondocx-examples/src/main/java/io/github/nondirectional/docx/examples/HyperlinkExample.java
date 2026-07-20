package io.github.nondirectional.docx.examples;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import java.nio.file.Path;

/**
 * 演示超链接。
 *
 * <p>OOXML 中，超链接用 {@code <w:hyperlink>} 包裹 {@code <w:r>} 实现， 并通过 {@code <w:hyperlink r:id="...">}
 * 引用文档关系中的目标 URL。 nondocx 的 {@code Paragraph.addHyperlink(text, url)} 隐藏了这些细节。
 */
public final class HyperlinkExample {

  public static void main(String[] args) throws Exception {
    Path output = ExamplePaths.outputDir().resolve("hyperlink-example.docx");
    output.toFile().getParentFile().mkdirs();

    System.out.println("创建超链接演示文档...");
    try (Document doc = Docx.create()) {

      doc.addParagraph("超链接演示")
          .heading(io.github.nondirectional.docx.core.api.style.HeadingLevel.H1);

      doc.addParagraph("下面是一个超链接：");

      // addHyperlink 是段落级方法，返回 Hyperlink
      // 然后在同一段落再添加普通文本 Run
      var p = doc.addParagraph();
      p.addRun("请访问 ");
      p.addHyperlink("nondocx GitHub 仓库", "https://github.com/nondocx/nondocx");
      p.addRun(" 了解更多信息。");

      doc.addParagraph("");
      doc.addParagraph("多个链接：");

      p = doc.addParagraph();
      p.addHyperlink("Apache POI 官网", "https://poi.apache.org/");
      p.addRun(" | ");
      p.addHyperlink(
          "OOXML 规范 ECMA-376",
          "https://www.ecma-international.org/publications-and-standards/standards/ecma-376/");

      doc.save(output);
      System.out.println("已保存: " + output.toAbsolutePath());
    }
  }

  private HyperlinkExample() {}
}
