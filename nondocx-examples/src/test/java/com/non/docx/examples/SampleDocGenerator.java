package com.non.docx.examples;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.style.HeadingLevel;
import com.non.docx.core.api.table.Table;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 样例输入文档生成器（fixture 性质）。
 *
 * <p>本类不是正式 example，而是为 {@code DocxAgentExample} 生成固定的样例输入文档 {@code
 * src/main/resources/document/sample-agent-input.docx}。产物已入库， 通常无需重跑；仅当需要调整样例结构时运行一次：
 *
 * <pre>{@code
 * mvn -q -pl nondocx-examples exec:java \
 *   -Dexec.mainClass=com.non.docx.examples.SampleDocGenerator \
 *   -Dexec.classpathScope=test
 * }</pre>
 *
 * <p>样例结构（design.md §5，让 Agent 示例的读取/编辑索引无歧义）：
 *
 * <ul>
 *   <li>正文 4 段：标题段、含多 run 的段落、含 1 个超链接的段落、普通段落。
 *   <li>1 个表格：表头行 + 2 数据行，其中 (1,1) 单元格含 2 个 run。
 * </ul>
 */
public final class SampleDocGenerator {

  /** 产物入库路径（相对仓库根；{@code mvn exec:java} 默认从仓库根运行， 因此路径需带模块前缀）。 */
  private static final Path OUTPUT =
      Path.of("nondocx-examples/src/main/resources/document/sample-agent-input.docx");

  public static void main(String[] args) throws Exception {
    Files.createDirectories(OUTPUT.getParent());

    // OOXML：word/document.xml 的正文是一串 <w:p> 和 <w:tbl>。
    // POI：XWPFDocument 映射为 getParagraphs()/getTables() 两个视图。
    // nondocx：Docx.create() + addParagraph/addTable builder 风格产出固定结构。
    try (Document doc = Docx.create()) {

      // ---- 正文段落 ----

      // 段落 0：标题（单 run）
      doc.addParagraph("项目周报 · 第 12 周").heading(HeadingLevel.H1);

      // 段落 1：含两个 run 的普通段落（演示 replace_run_text）
      var p1 = doc.addParagraph();
      p1.addRun("本周完成了 nondocx 的表格与超链接封装，");
      p1.addRun("整体进度符合预期。");

      // 段落 2：含 1 个超链接的段落（演示超链接读写）
      var p2 = doc.addParagraph();
      p2.addRun("相关设计文档见 ");
      p2.addHyperlink("nondocx 设计说明", "https://github.com/Nondirectional/nondocx");
      p2.addRun("。");

      // 段落 3：普通单 run 段落
      doc.addParagraph("下周计划：补齐 Agent 示例并完成端到端验证。");

      // ---- 表格 ----
      // OOXML：<w:tbl><w:tr><w:tc><w:p><w:r>... 嵌套；单元格内仍是段落+run 模型。
      // nondocx：doc.addTable().row(r -> r.cell(...)) 链式填充。
      Table table = doc.addTable();
      // 表头行
      table.row(r -> r.cell("模块").cell("状态").cell("负责人"));
      // 数据行 0：每格单 run
      table.row(r -> r.cell("nondocx-core").cell("已完成").cell("non"));
      // 数据行 1：(1,1) 单元格内放 2 个 run，演示 cell 内 run 寻址。
      // Row.cell(String) 是返回 Row 的链式便捷方法；要拿到 Cell 对象填多 run，
      // 用 cell(Consumer<Cell>) 配置器形式。新 Cell 经 addCell() 后内部无段落，
      // 需先 addParagraph() 建一个段落，再在里面加 run。
      table.row(
          r -> {
            r.cell("nondocx-examples");
            r.cell(
                c -> {
                  var para = c.addParagraph();
                  para.addRun("进行中");
                  para.addRun("（含 Agent 示例）");
                });
            r.cell("non");
          });

      doc.save(OUTPUT);
      System.out.println("样例文档已生成: " + OUTPUT.toAbsolutePath());
    }
  }

  private SampleDocGenerator() {}
}
