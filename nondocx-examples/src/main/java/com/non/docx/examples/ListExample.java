package com.non.docx.examples;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.style.ListKind;
import java.nio.file.Path;

/**
 * 演示项目符号列表和编号列表，包括嵌套层级。
 *
 * <p>OOXML 中列表编号通过 {@code <w:numPr>}（编号属性）实现，引用独立的编号定义部分
 * ({@code word/numbering.xml})。nondocx 的 {@code Paragraph.list(ListKind, int)} 自动管理
 * 编号定义。</p>
 */
public final class ListExample {

  public static void main(String[] args) throws Exception {
    Path output = ExamplePaths.outputDir().resolve("list-example.docx");
    output.toFile().getParentFile().mkdirs();

    System.out.println("创建列表演示文档...");
    try (Document doc = Docx.create()) {

      // ---- 项目符号列表（无序） ----
      doc.addParagraph("购物清单").heading(com.non.docx.core.api.style.HeadingLevel.H2);
      doc.addParagraph("苹果").list(ListKind.BULLET, 0);
      doc.addParagraph("香蕉").list(ListKind.BULLET, 0);
      doc.addParagraph("牛奶").list(ListKind.BULLET, 0);

      // ---- 编号列表（有序） ----
      doc.addParagraph("").addRun("操作步骤").bold();
      doc.addParagraph("打开冰箱门").list(ListKind.NUMBERED, 0);
      doc.addParagraph("把大象放进去").list(ListKind.NUMBERED, 0);
      doc.addParagraph("关上冰箱门").list(ListKind.NUMBERED, 0);

      // ---- 嵌套列表（三级） ----
      doc.addParagraph("").addRun("项目计划（嵌套层级）").bold();
      doc.addParagraph("阶段一：需求分析").list(ListKind.NUMBERED, 0);
      doc.addParagraph("收集用户故事").list(ListKind.BULLET, 1);
      doc.addParagraph("编写 PRD").list(ListKind.BULLET, 1);
      doc.addParagraph("评审").list(ListKind.BULLET, 2);
      doc.addParagraph("阶段二：开发").list(ListKind.NUMBERED, 0);
      doc.addParagraph("前端开发").list(ListKind.BULLET, 1);
      doc.addParagraph("后端开发").list(ListKind.BULLET, 1);
      doc.addParagraph("集成测试").list(ListKind.BULLET, 2);

      doc.save(output);
      System.out.println("已保存: " + output.toAbsolutePath());
    }
  }

  private ListExample() {}
}
