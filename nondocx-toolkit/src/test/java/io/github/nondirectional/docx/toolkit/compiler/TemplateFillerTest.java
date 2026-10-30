package io.github.nondirectional.docx.toolkit.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.table.Table;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link TemplateFiller} 契约测试：mustache 子集的值替换 / 条件块 / 表格模板行 + 宽容降级。
 *
 * <p>重点覆盖两个真实模板痛点：占位符被 Word 拆进多个 run（拼写检查/rsid 边界）；模板行复制必须保留 单元格与行属性（走 core {@code Table.copyRowTo}
 * XML 深拷贝）。
 */
class TemplateFillerTest {

  // ==================== 值替换 ====================

  @Test
  void fillsPlaceholdersIncludingSplitRuns() {
    Document doc = Docx.create();
    try {
      // 占位符拆进两个 run——Word 模板的常态
      Paragraph split = doc.addParagraph();
      split.addRun("总额：{{");
      split.addRun("amount}} 元");
      doc.addParagraph("客户：{{client}}");

      TemplateFiller.FillResult result =
          TemplateFiller.fill(doc, Map.of("amount", 1000, "client", "非方向科技"));

      assertThat(result.placeholdersFilled()).isEqualTo(2);
      assertThat(doc.paragraph(0).text()).isEqualTo("总额：1000 元");
      assertThat(doc.paragraph(1).text()).isEqualTo("客户：非方向科技");
      // 首 run 承载全文：格式保留（仍是 2 个 run，第二个已清空）
      assertThat(doc.paragraph(0).runs()).hasSize(2);
      assertThat(doc.paragraph(0).runs().get(1).text()).isEmpty();
      assertThat(result.warnings()).isEmpty();
    } finally {
      doc.close();
    }
  }

  @Test
  void missingKeyWarnsAndKeepsPlaceholder() {
    Document doc = Docx.create();
    try {
      doc.addParagraph("甲方：{{party_a}}，乙方：{{party_b}}");
      TemplateFiller.FillResult result = TemplateFiller.fill(doc, Map.of("party_a", "甲公司"));

      assertThat(result.placeholdersFilled()).isEqualTo(1);
      assertThat(doc.paragraph(0).text()).isEqualTo("甲方：甲公司，乙方：{{party_b}}");
      assertThat(result.warnings()).anyMatch(w -> w.contains("party_b"));
    } finally {
      doc.close();
    }
  }

  // ==================== 条件块 ====================

  @Test
  void conditionalBlockKeptWhenTruthyDroppedWhenFalsy() {
    Document doc = Docx.create();
    try {
      doc.addParagraph("常规段 {{title}}");
      doc.addParagraph("{{#urgent}}");
      doc.addParagraph("加急处理：{{deadline}}");
      doc.addParagraph("{{/urgent}}");
      doc.addParagraph("{{#draft}}");
      doc.addParagraph("草稿段落不应出现");
      doc.addParagraph("{{/draft}}");

      TemplateFiller.FillResult result =
          TemplateFiller.fill(doc, Map.of("title", "项目周报", "urgent", true, "deadline", "本周五"));

      assertThat(doc.paragraph(0).text()).isEqualTo("常规段 项目周报");
      // truthy 块：内容保留并填充，标记行删除
      assertThat(doc.paragraph(1).text()).isEqualTo("加急处理：本周五");
      // falsy 块（draft 缺失）：整块删除
      assertThat(doc.paragraphs()).hasSize(2);
      assertThat(result.paragraphsDropped()).isEqualTo(5); // urgent 2 标记行 + draft 标记/内容/标记 3 段
    } finally {
      doc.close();
    }
  }

  @Test
  void emptyListAndBlankStringAreFalsy() {
    Document doc = Docx.create();
    try {
      doc.addParagraph("{{#items}}");
      doc.addParagraph("不应出现（空列表）");
      doc.addParagraph("{{/items}}");
      doc.addParagraph("{{#note}}");
      doc.addParagraph("不应出现（空白串）");
      doc.addParagraph("{{/note}}");

      TemplateFiller.fill(doc, Map.of("items", List.of(), "note", "  "));

      assertThat(doc.paragraphs()).isEmpty();
    } finally {
      doc.close();
    }
  }

  @Test
  void unterminatedBlockWarnsAndKeepsMarkerAsText() {
    Document doc = Docx.create();
    try {
      doc.addParagraph("{{#lost}}");
      doc.addParagraph("内容 {{x}}");
      TemplateFiller.FillResult result = TemplateFiller.fill(doc, Map.of("x", 1));

      // 无结束标记：标记行保留，内容按普通段落填充
      assertThat(doc.paragraphs()).hasSize(2);
      assertThat(doc.paragraph(1).text()).isEqualTo("内容 1");
      assertThat(result.warnings()).anyMatch(w -> w.contains("lost"));
    } finally {
      doc.close();
    }
  }

  // ==================== 表格模板行 ====================

  @Test
  void tableTemplateRowExpandsPerElementAndStripsMarkers(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("expanded.docx");
    Document doc = Docx.create();
    try {
      Table table = doc.addTable();
      table.row(r -> r.cell("模块").cell("数量"));
      // 模板行：区段标记嵌在单元格文本里
      table.row(r -> r.cell("{{#items}}{{name}}").cell("{{qty}}"));
      table.row(r -> r.cell("合计").cell("{{total}}"));

      TemplateFiller.FillResult result =
          TemplateFiller.fill(
              doc,
              Map.of(
                  "items",
                  List.of(Map.of("name", "解析器", "qty", 2), Map.of("name", "编译器", "qty", 3)),
                  "total",
                  5));

      // 表头 + 2 条复制行 + 合计行
      assertThat(table.rows()).hasSize(4);
      assertThat(result.rowsExpanded()).isEqualTo(2);
      assertThat(result.placeholdersFilled()).isEqualTo(5); // 2×(name+qty) + total
      doc.save(file);
    } finally {
      doc.close();
    }
    // 落盘断言（防 POI 行缓存/脱链 wrapper 一族陷阱：内存对、XML 错）
    try (Document reopened = Docx.open(file)) {
      Table table = reopened.tables().get(0);
      assertThat(table.rows()).hasSize(4);
      assertThat(table.row(0).cell(0).text()).isEqualTo("模块");
      assertThat(table.row(1).cell(0).text()).isEqualTo("解析器");
      assertThat(table.row(1).cell(1).text()).isEqualTo("2");
      assertThat(table.row(2).cell(0).text()).isEqualTo("编译器");
      assertThat(table.row(2).cell(1).text()).isEqualTo("3");
      // 复制行插在模板行位置（表头后、合计前）
      assertThat(table.row(3).cell(0).text()).isEqualTo("合计");
      assertThat(table.row(3).cell(1).text()).isEqualTo("5");
    }
  }

  @Test
  void tableTemplateRowKeepsCellShadingOnCopies(@TempDir Path tmp) throws Exception {
    Document doc = Docx.create();
    try {
      Table table = doc.addTable();
      table.row(r -> r.cell("名称"));
      io.github.nondirectional.docx.core.api.table.Row templateRow = table.addRow();
      templateRow.cell("{{#items}}{{name}}");
      // 给模板行单元格上底纹——复制行必须保留（XML 深拷贝）
      templateRow.cell(0).shading("F2F2F2");
      doc.save(tmp.resolve("tpl.docx"));
    } finally {
      doc.close();
    }
    try (Document reopened = Docx.open(tmp.resolve("tpl.docx"))) {
      TemplateFiller.fill(
          reopened, Map.of("items", List.of(Map.of("name", "甲"), Map.of("name", "乙"))));
      Table table = reopened.tables().get(0);
      assertThat(table.rows()).hasSize(3);
      assertThat(table.row(1).cell(0).shading().fill()).isEqualTo("F2F2F2");
      assertThat(table.row(2).cell(0).shading().fill()).isEqualTo("F2F2F2");
    }
  }

  @Test
  void emptyListDropsTemplateRowAndNonListKeepsItWithWarning() {
    Document doc = Docx.create();
    try {
      Table t1 = doc.addTable();
      t1.row(r -> r.cell("{{#empty}}{{name}}"));
      Table t2 = doc.addTable();
      t2.row(r -> r.cell("{{#broken}}{{name}}"));

      TemplateFiller.FillResult result =
          TemplateFiller.fill(doc, Map.of("empty", List.of(), "broken", "不是列表"));

      assertThat(t1.rows()).isEmpty();
      assertThat(t2.rows()).hasSize(1);
      assertThat(t2.row(0).cell(0).text()).contains("{{#broken}}");
      assertThat(result.warnings()).anyMatch(w -> w.contains("broken"));
    } finally {
      doc.close();
    }
  }

  @Test
  void scalarElementBindsToDotPlaceholder() {
    Document doc = Docx.create();
    try {
      Table table = doc.addTable();
      table.row(r -> r.cell("{{#tags}}{{.}}"));
      TemplateFiller.fill(doc, Map.of("tags", List.of("alpha", "beta")));

      assertThat(table.rows()).hasSize(2);
      assertThat(table.row(0).cell(0).text()).isEqualTo("alpha");
      assertThat(table.row(1).cell(0).text()).isEqualTo("beta");
    } finally {
      doc.close();
    }
  }
}
