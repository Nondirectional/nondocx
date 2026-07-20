package io.github.nondirectional.docx.core.api.track;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.style.RunStyle;
import io.github.nondirectional.docx.core.api.table.Cell;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import io.github.nondirectional.docx.core.api.text.Run;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 高级类型创作(authoring)的闭环验收测试:创作 → save/reopen → read → accept/reject。
 *
 * <p>覆盖四类(implement.md Step 4-7):
 *
 * <ul>
 *   <li>带格式插入:addInsertion + 链式 set 样式 → read 回 INS + accept 后保留样式。
 *   <li>rPrChange:commitStyleAsTracked → read 回 RPR_CHANGE(new/old 摘要对);accept/reject 双向。
 *   <li>cellIns/cellDel:Cell.markInserted/markDeleted → read 回 CELL_INS/CELL_DEL;accept/reject 作用于
 *       tc。
 *   <li>move:Paragraph.moveRunsFrom → read 回 MOVE_FROM + MOVE_TO 配对;accept 联动。
 * </ul>
 *
 * <p>每类都过 round-trip(save→reopen),验证创作出的修订能被既有 read/accept-reject 正确处理(闭环)。
 */
class TrackedAuthoringAdvancedTest {

  // ---------- 带格式插入 ----------

  @Test
  void styledInsertionRoundTripsAndKeepsStyle(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("styled.docx");
    try (Document doc = Docx.create()) {
      Paragraph p = doc.addParagraph();
      // addInsertion 返回 Run,链式设样式
      p.addInsertion("甲", "强调").bold().color("FF0000");
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      List<TrackedChange> list = doc.trackedChanges().list();
      assertThat(list).hasSize(1);
      assertThat(list.get(0).type()).isEqualTo(TrackedChangeType.INS);
      assertThat(list.get(0).author()).isEqualTo("甲");
      // accept 后插入生效;再 save→reopen 验证样式(POI 的 XWPFRun 在 accept 解包后会断连)
      doc.trackedChanges().accept(list.get(0).id());
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      assertThat(doc.trackedChanges().list()).isEmpty();
      Run r = doc.paragraphs().get(0).runs().get(0);
      assertThat(r.text()).isEqualTo("强调");
      assertThat(r.isBold()).isTrue();
      assertThat(r.color()).isEqualTo("FF0000");
    }
  }

  // ---------- rPrChange ----------

  @Test
  void rprChangeAuthoringRoundTrip(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("rpr.docx");
    try (Document doc = Docx.create()) {
      Paragraph p = doc.addParagraph();
      Run r = p.addRun("文本");
      RunStyle before = r.style(); // 改前快照(无样式)
      r.bold().italic(); // 改样式(新值)
      r.commitStyleAsTracked("甲", before); // 记为 rPrChange
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      List<TrackedChange> list = doc.trackedChanges().list();
      assertThat(list).hasSize(1);
      TrackedChange c = list.get(0);
      assertThat(c.type()).isEqualTo(TrackedChangeType.RPR_CHANGE);
      PropertyChangeDetails d = (PropertyChangeDetails) c.details();
      // 新值含 b+i;旧值空
      assertThat(d.newSummary()).contains("b").contains("i");
      assertThat(d.oldSummary()).doesNotContain("b");

      // reject:回到旧值(样式消失);save→reopen 验证(POI XWPFRun 在 reject 整树替换后会断连)
      doc.trackedChanges().rejectProperty(c.id());
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      assertThat(doc.trackedChanges().list()).isEmpty();
      Run r = doc.paragraphs().get(0).runs().get(0);
      assertThat(r.isBold()).isFalse();
      assertThat(r.isItalic()).isFalse();
    }
  }

  @Test
  void rprChangeAcceptKeepsNewStyle(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("rpr-accept.docx");
    try (Document doc = Docx.create()) {
      Run r = doc.addParagraph().addRun("文本");
      RunStyle before = r.style();
      r.bold();
      r.commitStyleAsTracked("甲", before);
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      String id = doc.trackedChanges().list().get(0).id();
      doc.trackedChanges().acceptProperty(id);
      // accept:新值生效,bold 保留
      assertThat(doc.paragraphs().get(0).runs().get(0).isBold()).isTrue();
      assertThat(doc.trackedChanges().list()).isEmpty();
    }
  }

  @Test
  void rprChangeRejectsNullPreviousStyle() {
    try (Document doc = Docx.create()) {
      Run r = doc.addParagraph().addRun("x");
      assertThatThrownBy(() -> r.commitStyleAsTracked("甲", null))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  // ---------- 单元格 ----------

  @Test
  void cellInsertionAuthoringRoundTrip(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("cellins.docx");
    try (Document doc = Docx.create()) {
      Cell c = doc.addTable().addRow().addCell();
      c.addParagraph().addRun("内容");
      c.markInserted("甲");
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      List<TrackedChange> list = doc.trackedChanges().list();
      assertThat(list).hasSize(1);
      assertThat(list.get(0).type()).isEqualTo(TrackedChangeType.CELL_INS);
      assertThat(((CellChangeDetails) list.get(0).details()).kind())
          .isEqualTo(CellChangeKind.CELL_INSERTION);
      // accept cellIns:保留 tc、删标记
      doc.trackedChanges().acceptCell(list.get(0).id());
      assertThat(doc.trackedChanges().list()).isEmpty();
    }
  }

  @Test
  void cellDeletionAuthoringAcceptRemovesTc(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("celldel.docx");
    try (Document doc = Docx.create()) {
      var row = doc.addTable().addRow();
      Cell keep = row.addCell();
      keep.addParagraph().addRun("保留");
      Cell del = row.addCell();
      del.addParagraph().addRun("将删");
      del.markDeleted("甲");
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      List<TrackedChange> list = doc.trackedChanges().list();
      assertThat(list).hasSize(1);
      assertThat(list.get(0).type()).isEqualTo(TrackedChangeType.CELL_DEL);
      // accept cellDel:移除整个 tc,行里剩 1 个
      doc.trackedChanges().acceptCell(list.get(0).id());
      assertThat(doc.trackedChanges().list()).isEmpty();
      var row = doc.raw().getDocument().getBody().getTblArray(0).getTrArray(0);
      assertThat(row.sizeOfTcArray()).isEqualTo(1);
    }
  }

  // ---------- 移动 ----------

  @Test
  void moveAuthoringProducesPairedRevisions(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("move.docx");
    try (Document doc = Docx.create()) {
      Paragraph source = doc.addParagraph();
      source.addRun("前缀");
      Run moving = source.addRun("被移走的文字");
      Paragraph target = doc.addParagraph();
      target.moveRunsFrom("甲", source, List.of(moving));
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      List<TrackedChange> list = doc.trackedChanges().list();
      // 读回配对的 MOVE_FROM + MOVE_TO
      assertThat(list).hasSize(2);
      assertThat(list)
          .extracting(TrackedChange::type)
          .containsExactlyInAnyOrder(TrackedChangeType.MOVE_FROM, TrackedChangeType.MOVE_TO);
      list.forEach(c -> assertThat(c.author()).isEqualTo("甲"));
    }
  }

  @Test
  void moveAuthoringAcceptMovesText(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("move-accept.docx");
    try (Document doc = Docx.create()) {
      Paragraph source = doc.addParagraph();
      Run moving = source.addRun("被移走的文字");
      Paragraph target = doc.addParagraph();
      target.moveRunsFrom("甲", source, List.of(moving));
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      // accept moveFrom(配对联动):源端文本移除、目标端保留
      var moveFrom =
          doc.trackedChanges().list().stream()
              .filter(c -> c.type() == TrackedChangeType.MOVE_FROM)
              .findFirst()
              .orElseThrow();
      doc.trackedChanges().accept(moveFrom.id());
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      assertThat(doc.trackedChanges().list()).isEmpty();
      // 目标段(索引1)应有「被移走的文字」
      // save→reopen 验证:accept 重构树后 POI 的内存 XWPFParagraph 会断连
      String targetText = doc.paragraphs().get(1).text();
      assertThat(targetText).contains("被移走的文字");
    }
  }

  @Test
  void moveRejectsRunNotFromSource(@TempDir Path tmp) throws Exception {
    try (Document doc = Docx.create()) {
      Paragraph p1 = doc.addParagraph();
      Run r = p1.addRun("x");
      Paragraph p2 = doc.addParagraph();
      Paragraph p3 = doc.addParagraph();
      // r 属于 p1,但声称从 p2 移动 → 应抛
      assertThatThrownBy(() -> p3.moveRunsFrom("甲", p2, List.of(r)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("不属于源段落");
    }
  }

  @Test
  void moveRejectsEmptyRuns() {
    try (Document doc = Docx.create()) {
      Paragraph p1 = doc.addParagraph();
      Paragraph p2 = doc.addParagraph();
      assertThatThrownBy(() -> p2.moveRunsFrom("甲", p1, List.of()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
