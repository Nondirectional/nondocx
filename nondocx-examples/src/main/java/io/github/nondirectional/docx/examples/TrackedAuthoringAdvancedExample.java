package io.github.nondirectional.docx.examples;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.style.RunStyle;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import io.github.nondirectional.docx.core.api.text.Run;
import java.nio.file.Path;
import java.util.List;

/**
 * 演示四类<b>高级修订类型创作</b>(tracked changes authoring)。
 *
 * <p>前序 {@code TrackedChangesExample} 演示了文本类创作({@code addInsertion}/{@code addDeletion}/{@code
 * replaceTracked})与只读消费。本示例补齐四类高级修订的创作:
 *
 * <ol>
 *   <li><b>带格式插入</b>:复用 {@code addInsertion} 返回的 {@code Run},链式设样式。
 *   <li><b>属性修订(rPrChange)</b>:两步式——先快照改前样式、再链式改样式、再 {@code commitStyleAsTracked}。
 *   <li><b>单元格修订(cellIns/cellDel)</b>:{@code Cell.markInserted}/{@code markDeleted}。
 *   <li><b>移动修订(move)</b>:{@code Paragraph.moveRunsFrom}(把源段 run 移到本段)。
 * </ol>
 *
 * <p><b>OOXML 教学:rPrChange 的 CTRPrOriginal 防递归。</b> rPrChange 内嵌的 {@code <w:rPr>} 是「旧值树」,其类型是
 * {@code CTRPrOriginal}(不是 {@code CTRPr})——schema 层面不含 {@code rPrChange} 子元素,架构层禁止递归嵌套,无需手动剔除。详见
 * {@code poi-bridge.md} N17。
 *
 * <p><b>OOXML 教学:move 靠 rangeStart 的 w:name 配对。</b> move 的 moveFrom/moveTo 本身不带 name;配对靠源端 {@code
 * moveFromRangeStart.w:name} 与目标端 {@code moveToRangeStart.w:name} 相同。实现用 {@code _move_<baseId>}
 * 防冲突。
 *
 * @see TrackedChangesExample 文本类创作与只读消费的基础示例
 */
public final class TrackedAuthoringAdvancedExample {

  public static void main(String[] args) throws Exception {
    Path output = ExamplePaths.outputDir().resolve("tracked-authoring-advanced-example.docx");
    output.toFile().getParentFile().mkdirs();

    // ===== 1. 带格式插入:addInsertion 返回 Run,链式设样式 =====
    System.out.println("=== 1. 带格式插入(addInsertion + 链式 set 样式)===");
    try (Document doc = Docx.create()) {
      doc.raw().getSettings().setTrackRevisions(true);
      Paragraph p = doc.addParagraph("段落开头");
      // addInsertion 返回新插入的 Run,直接链式设样式
      Run styled = p.addInsertion("审阅者甲", "强调文字");
      styled.bold().color("FF0000").fontSize(14);
      System.out.println(
          "  插入带样式 run:text=\""
              + styled.text()
              + "\", bold="
              + styled.isBold()
              + ", color="
              + styled.color()
              + ", size="
              + styled.fontSize());
      doc.save(output);
    }

    // ===== 2. 属性修订:两步式 commitStyleAsTracked =====
    System.out.println();
    System.out.println("=== 2. 属性修订(两步式:快照→改样式→commit)===");
    Path rprFile = ExamplePaths.outputDir().resolve("tracked-authoring-rpr.docx");
    try (Document doc = Docx.create()) {
      Run r = doc.addParagraph().addRun("这段文字");
      RunStyle before = r.style(); // 1. 先快照改前样式(无样式)
      System.out.println("  改前快照: " + before);
      r.bold().italic(); // 2. 链式改样式(当前 rPr 即新值)
      r.commitStyleAsTracked("审阅者甲", before); // 3. 把「改前/改后」写成 rPrChange
      System.out.println("  已记为 rPrChange(新=bold+italic,旧=空)。reject 会回到旧值。");
      doc.save(rprFile);
    }

    // ===== 3. 单元格修订:Cell.markInserted / markDeleted =====
    System.out.println();
    System.out.println("=== 3. 单元格修订(Cell.markInserted / markDeleted)===");
    Path cellFile = ExamplePaths.outputDir().resolve("tracked-authoring-cell.docx");
    try (Document doc = Docx.create()) {
      var row = doc.addTable().addRow();
      var c0 = row.addCell();
      c0.addParagraph().addRun("新插入的单元格");
      c0.markInserted("审阅者甲"); // 这个单元格标记为插入
      var c1 = row.addCell();
      c1.addParagraph().addRun("将被删除");
      c1.markDeleted("审阅者乙"); // 这个单元格标记为删除
      System.out.println("  两个单元格:一个 markInserted(甲)、一个 markDeleted(乙)。");
      doc.save(cellFile);
    }

    // ===== 4. 移动修订:Paragraph.moveRunsFrom =====
    System.out.println();
    System.out.println("=== 4. 移动修订(Paragraph.moveRunsFrom:源段 run 移到目标段)===");
    Path moveFile = ExamplePaths.outputDir().resolve("tracked-authoring-move.docx");
    try (Document doc = Docx.create()) {
      Paragraph source = doc.addParagraph("源段:");
      Run moving = source.addRun("【这段会被移走】");
      source.addRun("源段剩余。");
      Paragraph target = doc.addParagraph("目标段:");
      // 把 source 的 moving run 移到 target;返回目标段新插入的 run 列表
      List<Run> moved = target.moveRunsFrom("审阅者甲", source, List.of(moving));
      System.out.println("  已移动 " + moved.size() + " 个 run 到目标段(配对 moveFrom + moveTo)。");
      doc.save(moveFile);
    }

    // ===== 验证:读回各文件确认创作出的修订 =====
    System.out.println();
    System.out.println("=== 验证:读回创作出的修订(round-trip)===");
    verify(output, "带格式插入");
    verify(rprFile, "rPrChange");
    verify(cellFile, "单元格");
    verify(moveFile, "move");
  }

  /** 打开文件,列出修订(验证创作出的修订经 round-trip 仍可读)。 */
  private static void verify(Path file, String label) throws Exception {
    try (Document doc = Docx.open(file)) {
      var list = doc.trackedChanges().list();
      System.out.println("[" + label + "] " + list.size() + " 条修订:");
      for (var c : list) {
        System.out.println("  type=" + c.type() + ", author=" + c.author() + ", id=" + c.id());
      }
    }
  }

  private TrackedAuthoringAdvancedExample() {}
}
