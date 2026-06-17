package com.non.docx.examples;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import java.nio.file.Path;

/**
 * 演示表格的创建：行、单元格、表头、数据行。
 *
 * <p>OOXML 中表格的结构是 {@code <w:tbl>} → {@code <w:tr>}（行）→ {@code <w:tc>}（单元格）。 每个 {@code <w:tc>}
 * 内部又包含段落，所以单元格可以像文档正文一样有格式化文本。
 *
 * <p>nondocx 的 {@code Table.row(Consumer<Row>)} 便捷方法让你能用 lambda 快速填充行。 行内的 {@code row.cell("文本")}
 * 追加一个单文本单元格；{@code row.cell(c -> c.text("..."))} 或 {@code c.addParagraph().addRun(...)} 用于复杂内容。
 */
public final class TableExample {

  public static void main(String[] args) throws Exception {
    Path output = ExamplePaths.outputDir().resolve("table-example.docx");
    output.toFile().getParentFile().mkdirs();

    System.out.println("创建表格演示文档...");
    try (Document doc = Docx.create()) {

      doc.addParagraph("员工信息表").heading(com.non.docx.core.api.style.HeadingLevel.H1);

      // 用 addTable() 创建一个空表格，然后用 row(Consumer) 链式填充
      doc.addTable()
          // 表头行（第一行）
          .row(
              r -> {
                r.cell("姓名");
                r.cell("部门");
                r.cell("职位");
                r.cell("邮箱");
              })
          // 数据行
          .row(
              r -> {
                r.cell("张三");
                r.cell("技术部");
                r.cell("高级工程师");
                r.cell("zhangsan@example.com");
              })
          .row(
              r -> {
                r.cell("李四");
                r.cell("市场部");
                r.cell("市场总监");
                r.cell("lisi@example.com");
              })
          .row(
              r -> {
                r.cell("王五");
                r.cell("财务部");
                r.cell("财务经理");
                r.cell("wangwu@example.com");
              });

      // 多段落单元格演示
      doc.addParagraph("").addRun("多段落单元格演示").bold();
      doc.addTable()
          .row(r -> r.cell("单元格一\n第二行"))
          .row(
              r ->
                  r.cell(
                      c -> {
                        c.text("第一段内容");
                        c.addParagraph().addRun("第二段内容").italic().color("888888");
                        c.addParagraph().addRun("第三段内容").underline();
                      }));

      doc.save(output);
      System.out.println("已保存: " + output.toAbsolutePath());
    }
  }

  private TableExample() {}
}
