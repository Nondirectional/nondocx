package com.non.docx.examples;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.track.ChangeDetails;
import com.non.docx.core.api.track.TextChangeDetails;
import com.non.docx.core.api.track.TrackedChange;
import java.nio.file.Path;
import java.util.List;

/**
 * 演示“旧版 docx + 新版 docx → 生成带修订结果”的 compare API。
 *
 * <p><b>第一层：OOXML 是什么。</b> Word 的“修订”不是普通高亮，而是把差异真正写进 {@code word/document.xml}：
 *
 * <pre>{@code
 * <w:p>
 *   <w:r><w:t>风险等级：</w:t></w:r>
 *   <w:del w:author="审阅者甲"><w:r><w:delText>中</w:delText></w:r></w:del>
 *   <w:ins w:author="审阅者甲"><w:r><w:t>低</w:t></w:r></w:ins>
 * </w:p>
 * }</pre>
 *
 * <p>也就是说，compare 的结果不是“把最终文本直接改掉”，而是把新增写成 {@code <w:ins>}，把删除写成 {@code <w:del>}。
 *
 * <p><b>第二层：POI 如何表达。</b> Apache POI 负责把 docx 打开成 {@code XWPFDocument} / {@code XWPFParagraph} /
 * {@code XWPFRun} 这样的对象，但它没有“比较两份 docx 并产出修订”的高层 API。
 *
 * <p><b>第三层：nondocx 为什么这样设计。</b> nondocx 把 compare 入口放在 {@link Docx}：{@link Docx#compare(Path,
 * Path)} / {@link Docx#compare(Path, Path,
 * String)}。当前版本仍以<b>旧文档</b>为基线，只比较<b>正文纯文本段落</b>，把差异重放成标准修订； 对可归约成单一样式的纯文本段落，会保留 run
 * 级六样式；表格、超链接、多样式混排等复杂结构仍保留旧文档原样。
 */
public final class DocxCompareExample {

  public static void main(String[] args) throws Exception {
    Path outputDir = ExamplePaths.outputDir();
    Path oldFile = outputDir.resolve("docx-compare-example-old.docx");
    Path newFile = outputDir.resolve("docx-compare-example-new.docx");
    Path explicitResult = outputDir.resolve("docx-compare-example-explicit-author.docx");
    Path defaultResult = outputDir.resolve("docx-compare-example-default-author.docx");

    createOldVersion(oldFile);
    createNewVersion(newFile);

    System.out.println("=== 1. 生成两份待比较文档 ===");
    System.out.println("旧版文档: " + oldFile.toAbsolutePath());
    System.out.println("新版文档: " + newFile.toAbsolutePath());

    // 显式 author：结果里的 <w:ins>/<w:del> 会带这个作者。
    try (Document compared = Docx.compare(oldFile, newFile, "审阅者甲")) {
      compared.save(explicitResult);
    }
    System.out.println();
    System.out.println("=== 2. 显式 author compare ===");
    inspectComparedFile(explicitResult, "显式作者结果");

    // 默认 author：当调用方不传 author 时，nondocx 会写入 DEFAULT_COMPARE_AUTHOR。
    try (Document compared = Docx.compare(oldFile, newFile)) {
      compared.save(defaultResult);
    }
    System.out.println();
    System.out.println("=== 3. 默认 author compare ===");
    inspectDefaultAuthorResult(defaultResult);
  }

  private static void createOldVersion(Path file) throws Exception {
    try (Document doc = Docx.create()) {
      doc.addParagraph("版本对比演示");
      doc.addParagraph().addRun("本周完成接口联调。").bold();

      // compare MVP 不比较表格内容；结果文档会保留旧表格原样。
      doc.addTable().row(r -> r.cell("旧表格数据"));

      doc.addParagraph("风险等级：中。");
      doc.save(file);
    }
  }

  private static void createNewVersion(Path file) throws Exception {
    try (Document doc = Docx.create()) {
      doc.addParagraph("版本对比演示");
      doc.addParagraph().addRun("本周完成接口联调和回归测试。").italic().color("FF0000");
      doc.addParagraph().addRun("新增安排：下周开始灰度发布。").italic().color("0066CC");
      doc.addParagraph("风险等级：低。");
      doc.save(file);
    }
  }

  private static void inspectComparedFile(Path file, String label) throws Exception {
    try (Document doc = Docx.open(file)) {
      List<TrackedChange> changes = doc.trackedChanges().list();
      System.out.println("[" + label + "] 文件: " + file.toAbsolutePath());
      System.out.println("[" + label + "] tracked changes 数量: " + changes.size());
      for (int i = 0; i < changes.size(); i++) {
        System.out.println("  #" + (i + 1) + " " + describe(changes.get(i)));
      }
      System.out.println(
          "[" + label + "] 第二段正文样式（旧基线未改部分仍是粗体）: bold=" + doc.paragraph(1).run(0).isBold());
      System.out.println(
          "[" + label + "] 表格首单元格仍是旧值: " + doc.tables().get(0).row(0).cell(0).text());
    }
  }

  private static void inspectDefaultAuthorResult(Path file) throws Exception {
    try (Document doc = Docx.open(file)) {
      List<TrackedChange> changes = doc.trackedChanges().list();
      System.out.println("[默认作者结果] 文件: " + file.toAbsolutePath());
      System.out.println(
          "[默认作者结果] DEFAULT_COMPARE_AUTHOR = \"" + Docx.DEFAULT_COMPARE_AUTHOR + "\"");
      for (int i = 0; i < changes.size(); i++) {
        System.out.println("  #" + (i + 1) + " author=" + changes.get(i).author());
      }
    }
  }

  private static String describe(TrackedChange change) {
    StringBuilder sb = new StringBuilder();
    sb.append("type=").append(change.type());
    sb.append(", family=").append(change.family());
    sb.append(", author=\"").append(change.author()).append('"');
    ChangeDetails details = change.details();
    if (details instanceof TextChangeDetails) {
      sb.append(", text=\"").append(((TextChangeDetails) details).text()).append('"');
    }
    return sb.toString();
  }

  private DocxCompareExample() {}
}
