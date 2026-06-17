package com.non.docx.examples;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.image.ImageType;
import com.non.docx.core.api.section.Orientation;
import com.non.docx.core.api.section.PaperSize;
import com.non.docx.core.api.style.Alignment;
import com.non.docx.core.api.style.HeadingLevel;
import com.non.docx.core.api.style.ListKind;
import java.nio.file.Path;

/**
 * 综合示例：生成一份完整的「项目报告」文档，演示 nondocx 大部分 API 的组合使用。
 *
 * <p>包含：页面设置、页眉页脚、标题、段落格式化、表格、列表、超链接、图片。
 */
public final class ComplexDocument {

  public static void main(String[] args) throws Exception {
    Path output = ExamplePaths.outputDir().resolve("complex-report.docx");
    output.toFile().getParentFile().mkdirs();

    System.out.println("创建综合示例文档...");
    try (Document doc = Docx.create()) {

      // ========== 页面设置 ==========
      // section(0) 获取第一个章节，链式调用设置纸张/方向/边距
      doc.section(0)
          .paperSize(PaperSize.A4)
          .orientation(Orientation.PORTRAIT)
          .margins(1440, 1440, 1440, 1440); // 上下左右各 1 英寸

      // ========== 页眉 ==========
      // ensureHeader() 显式创建并返回 Header；addParagraph() 返回 Paragraph
      // alignment() 是段落级方法
      var headerPara = doc.ensureHeader().addParagraph();
      headerPara.addRun("项目进度报告").bold().fontSize(9);
      headerPara.alignment(Alignment.RIGHT);

      // ========== 页脚 ==========
      var footerPara = doc.ensureFooter().addParagraph();
      footerPara.addRun("机密文件 · 请勿外传").fontSize(8).color("999999");
      footerPara.alignment(Alignment.CENTER);

      // ========== 封面 ==========
      doc.addParagraph("\n\n"); // 空行留白
      doc.addParagraph("项目进度报告").heading(HeadingLevel.H1).alignment(Alignment.CENTER);
      doc.addParagraph("版本 1.0 · 2025 年 4 月").alignment(Alignment.CENTER);
      doc.addParagraph("编制：技术部").alignment(Alignment.CENTER);

      // ========== 目录概要 ==========
      doc.addParagraph("\n");
      doc.addParagraph("一、项目概况").heading(HeadingLevel.H2);

      doc.addParagraph(
          "本项目旨在开发一套基于 Apache POI 的 Fluent 风格 docx 读写库——nondocx。" + "经过两个月的迭代开发，核心功能已完成。");

      doc.addParagraph("主要技术指标如下：");

      // ---- 表格 ----
      // addTable().row(Consumer<Row>) 链式填充
      doc.addTable()
          .row(r -> r.cell("指标").cell("目标值").cell("当前值").cell("完成度"))
          .row(r -> r.cell("段落读写").cell("支持").cell("已完成").cell("100%"))
          .row(r -> r.cell("表格支持").cell("支持").cell("已完成").cell("100%"))
          .row(r -> r.cell("图片嵌入").cell("支持").cell("已完成").cell("100%"))
          .row(r -> r.cell("页眉页脚").cell("支持").cell("已完成").cell("100%"))
          .row(r -> r.cell("列表编号").cell("支持").cell("已完成").cell("100%"));

      // ========== 详细内容 ==========
      doc.addParagraph("二、功能详述").heading(HeadingLevel.H2);

      // ---- 列表 ----
      var para = doc.addParagraph();
      para.addRun("已实现的功能清单：").bold();
      doc.addParagraph("段落与 Run 格式化").list(ListKind.BULLET, 0);
      doc.addParagraph("多级列表").list(ListKind.BULLET, 0);
      doc.addParagraph("表格与复杂单元格").list(ListKind.BULLET, 0);
      doc.addParagraph("超链接").list(ListKind.BULLET, 0);
      doc.addParagraph("图片嵌入").list(ListKind.BULLET, 0);

      // ---- 嵌套列表 ----
      para = doc.addParagraph();
      para.addRun("待办事项（按优先级）：").bold();
      doc.addParagraph("性能优化").list(ListKind.NUMBERED, 0);
      doc.addParagraph("大数据量文档分段写入").list(ListKind.BULLET, 1);
      doc.addParagraph("缓存重复样式").list(ListKind.BULLET, 1);
      doc.addParagraph("文档兼容性").list(ListKind.NUMBERED, 0);
      doc.addParagraph("Word 2010 验证").list(ListKind.BULLET, 1);
      doc.addParagraph("WPS 验证").list(ListKind.BULLET, 1);

      // ---- 带格式的段落 ----
      doc.addParagraph("三、技术验证").heading(HeadingLevel.H2);
      doc.addParagraph("通过以下方式验证文档质量：");

      para = doc.addParagraph();
      para.addRun("1. 单元测试：").bold();
      para.addRun("已编写 111 个测试用例，覆盖全部核心功能。");

      para = doc.addParagraph();
      para.addRun("2. 往返测试：").bold();
      para.addRun("每个功能都验证了「保存→重新打开→内容一致」。");

      para = doc.addParagraph();
      para.addRun("3. 人工审查：").bold();
      para.addRun("在 Word 和 WPS 中打开验证效果。");

      // ---- 超链接 ----
      doc.addParagraph("四、参考资源").heading(HeadingLevel.H2);
      doc.addParagraph("相关文档与链接：");

      para = doc.addParagraph();
      para.addRun("项目仓库：");
      para.addHyperlink("nondocx GitHub", "https://github.com/nondocx/nondocx");

      para = doc.addParagraph();
      para.addRun("底层框架：");
      para.addHyperlink("Apache POI", "https://poi.apache.org/");

      // ---- 图片 ----
      doc.addParagraph("五、示例图片").heading(HeadingLevel.H2);
      doc.addParagraph("进度指示图（50×50 绿色方块）：");
      doc.addParagraph().addImage(ImageExample.solidPng(50, 50, 0x00AA00), ImageType.PNG, 50, 50);

      // ========== 页脚签名 ==========
      doc.addParagraph("\n\n");
      doc.addParagraph("报告结束").alignment(Alignment.CENTER);

      doc.save(output);
      System.out.println("已保存: " + output.toAbsolutePath());
    }
  }

  private ComplexDocument() {}
}
