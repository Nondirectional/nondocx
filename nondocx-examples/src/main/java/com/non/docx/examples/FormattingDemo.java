package com.non.docx.examples;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.style.Alignment;
import com.non.docx.core.api.style.HeadingLevel;
import com.non.docx.core.api.text.Paragraph;
import java.nio.file.Path;

/**
 * 演示段落标题、对齐方式，以及 Run 级别的粗体/斜体/下划线/字体/字号/颜色。
 *
 * <p>OOXML 中，每个 Run ({@code <w:r>}) 携带可选的格式化属性 ({@code <w:rPr>})：
 * {@code <w:b>}（粗体）、{@code <w:i>}（斜体）、{@code <w:u>}（下划线）、
 * {@code <w:rFonts>}（字体）、{@code <w:sz>}（字号）、{@code <w:color>}（颜色）。</p>
 */
public final class FormattingDemo {

  public static void main(String[] args) throws Exception {
    Path output = ExamplePaths.outputDir().resolve("formatting-demo.docx");
    output.toFile().getParentFile().mkdirs();

    System.out.println("创建格式化演示文档...");
    try (Document doc = Docx.create()) {

      // ---- 标题 ----
      // heading() 返回 Paragraph（this），所以可以链式调用
      doc.addParagraph("标题演示").heading(HeadingLevel.H1);
      doc.addParagraph("二级标题").heading(HeadingLevel.H2);

      // ---- 对齐方式 ----
      // alignment() 也返回 this（Paragraph）
      doc.addParagraph("左对齐（默认）");
      doc.addParagraph("居中对齐").alignment(Alignment.CENTER);
      doc.addParagraph("右对齐").alignment(Alignment.RIGHT);
      doc.addParagraph("两端对齐文本：这是一段较长的文字，用于演示两端对齐的效果。")
          .alignment(Alignment.JUSTIFY);

      // ---- Run 级格式化 ----
      // 段落内的每个 Run 是独立的格式化单元：
      // addRun("文字") 返回 Run，然后在 Run 上调用 .bold() / .italic() 等
      doc.addParagraph("").addRun("Run 格式化演示").bold(); // 只有 "..." 是粗体

      Paragraph p = doc.addParagraph();
      p.addRun("普通文本 ");
      p.addRun("粗体").bold();
      p.addRun(" ");
      p.addRun("斜体").italic();
      p.addRun(" ");
      p.addRun("下划线").underline();

      p = doc.addParagraph();
      p.addRun("红色大字：").color("FF0000").fontSize(18);
      // 注意：color() 和 fontSize() 返回 Run（this），所以可以链式调用
      // 但需要一个新的 addRun 才能开始一个新的 Run
      p.addRun(" 蓝色小字：").color("0000FF").fontSize(10);

      p = doc.addParagraph();
      p.addRun("等宽字体：").font("Courier New");
      p.addRun(" 无衬线字体：").font("Arial");

      doc.save(output);
      System.out.println("已保存: " + output.toAbsolutePath());
    }
  }

  private FormattingDemo() {}
}
