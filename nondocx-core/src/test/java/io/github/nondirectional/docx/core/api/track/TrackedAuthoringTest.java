package io.github.nondirectional.docx.core.api.track;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import io.github.nondirectional.docx.core.api.text.Run;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * tracked changes 创作侧(authoring)显式 API 的验收测试。
 *
 * <p>验证 {@code Paragraph.addInsertion} / {@code Paragraph.addDeletion} / {@code Run.replaceTracked}
 * 写出的文本类修订:能被 {@code TrackedChanges.list()} 读回、携带正确元数据(author/date/w:id)、与开关正交、round-trip 存活,且能被
 * accept/reject 命中。
 *
 * <p>这些测试覆盖 authoring 子任务的 AC1–AC6。
 */
class TrackedAuthoringTest {

  /** addInsertion 写出的 ins 能被 list() 读回,type/family/author/details 正确。 */
  @Test
  void addInsertionIsReadableByList(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("author-ins.docx");
    try (Document doc = Docx.open(newDoc())) {
      doc.paragraph(0).addInsertion("non", "新增内容");
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      List<TrackedChange> list = doc.trackedChanges().list();
      assertThat(list).hasSize(1);
      TrackedChange c = list.get(0);
      assertThat(c.type()).isEqualTo(TrackedChangeType.INS);
      assertThat(c.family()).isEqualTo(TrackedChangeFamily.TEXT);
      assertThat(c.author()).isEqualTo("non");
      assertThat(((TextChangeDetails) c.details()).text()).isEqualTo("新增内容");
    }
  }

  /** addInsertion 返回的新 run 可继续链式修改,且修改反映到读回的 details。 */
  @Test
  void addInsertionReturnsMutableRun(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("author-ins-run.docx");
    try (Document doc = Docx.open(newDoc())) {
      Run inserted = doc.paragraph(0).addInsertion("non", "可继续改");
      inserted.bold();
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      assertThat(((TextChangeDetails) doc.trackedChanges().list().get(0).details()).text())
          .isEqualTo("可继续改");
    }
  }

  /** addDeletion 把既有 run 标记为删除,能被 list() 读回为 del。 */
  @Test
  void addDeletionMarksExistingRun(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("author-del.docx");
    try (Document doc = Docx.open(newDocWithRun("要删的文本"))) {
      doc.paragraph(0).addDeletion("non", doc.paragraph(0).run(0));
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      List<TrackedChange> list = doc.trackedChanges().list();
      assertThat(list).hasSize(1);
      TrackedChange c = list.get(0);
      assertThat(c.type()).isEqualTo(TrackedChangeType.DEL);
      assertThat(((TextChangeDetails) c.details()).text()).isEqualTo("要删的文本");
    }
  }

  /** addDeletion 对不属于本段落的 run 抛异常(防误用)。 */
  @Test
  void addDeletionRejectsForeignRun(@TempDir Path tmp) throws Exception {
    try (Document doc = Docx.open(newDoc())) {
      doc.addParagraph().addRun("第二段");
      Paragraph p0 = doc.paragraph(0);
      Run foreign = doc.paragraph(1).run(0);
      assertThatThrownBy(() -> p0.addDeletion("non", foreign))
          .isInstanceOf(java.util.NoSuchElementException.class);
    }
  }

  /** replaceTracked 生成 del + ins 两条修订,新 run 复制源 run 文本样式。 */
  @Test
  void replaceTrackedProducesDelAndIns(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("author-replace.docx");
    try (Document doc = Docx.open(newDocWithRun("旧文本"))) {
      Run replacement = doc.paragraph(0).run(0).bold().replaceTracked("non", "新文本");
      assertThat(replacement.text()).isEqualTo("新文本");
      assertThat(replacement.isBold()).isTrue(); // 样式已复制
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      List<TrackedChange> list = doc.trackedChanges().list();
      assertThat(list).hasSize(2);
      // del(旧文本) + ins(新文本)
      assertThat(list)
          .extracting(TrackedChange::type)
          .containsExactlyInAnyOrder(TrackedChangeType.DEL, TrackedChangeType.INS);
    }
  }

  /** 修订元数据:date 自动写入(非 null),w:id 自动分配且两条不冲突。 */
  @Test
  void writesDateAndDistinctRevisionIds(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("author-meta.docx");
    try (Document doc = Docx.open(newDoc())) {
      Paragraph p = doc.paragraph(0);
      p.addInsertion("non", "第一条");
      p.addInsertion("non", "第二条");
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      List<TrackedChange> list = doc.trackedChanges().list();
      assertThat(list).hasSize(2);
      for (TrackedChange c : list) {
        assertThat(c.date()).isNotNull(); // date 自动写入
      }
      // 两条 w:id 不同(底层原始 id,通过 raw() 取)
      long id0 = list.get(0).raw().getId().longValue();
      long id1 = list.get(1).raw().getId().longValue();
      assertThat(id0).isNotEqualTo(id1);
    }
  }

  /** 与开关正交:开关未开启时,显式 authoring 仍写出修订。 */
  @Test
  void authoringIsOrthogonalToTrackSwitch(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("author-orthogonal.docx");
    try (Document doc = Docx.open(newDoc())) {
      assertThat(doc.trackedChanges().enabled()).isFalse(); // 开关未开启
      doc.paragraph(0).addInsertion("non", "仍写出修订");
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      // 开关仍关,但修订确已写入
      assertThat(doc.trackedChanges().enabled()).isFalse();
      assertThat(doc.trackedChanges().list()).hasSize(1);
    }
  }

  /** 普通 addRun 不带修订(authoring 不污染普通写路径)。 */
  @Test
  void plainAddRunProducesNoRevision(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("author-plain.docx");
    try (Document doc = Docx.open(newDoc())) {
      doc.paragraph(0).addRun("普通文本");
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      assertThat(doc.trackedChanges().list()).isEmpty();
    }
  }

  /** authoring 写出的修订能被 accept-text 命中(跨子任务集成)。 */
  @Test
  void authoredInsertionAcceptableById(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("author-accept.docx");
    try (Document doc = Docx.open(newDoc())) {
      doc.paragraph(0).addInsertion("non", "可接受");
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      String id = doc.trackedChanges().list().get(0).id();
      doc.trackedChanges().accept(id);
      assertThat(doc.trackedChanges().list()).isEmpty();
    }
  }

  /** author 非法(null / 空白)抛 IllegalArgumentException。 */
  @Test
  void rejectsBlankAndNullAuthor(@TempDir Path tmp) throws Exception {
    try (Document doc = Docx.open(newDoc())) {
      Paragraph p = doc.paragraph(0);
      assertThatThrownBy(() -> p.addInsertion(null, "x"))
          .isInstanceOf(java.lang.IllegalArgumentException.class);
      assertThatThrownBy(() -> p.addInsertion("  ", "x"))
          .isInstanceOf(java.lang.IllegalArgumentException.class);
    }
  }

  // ---------- fixture ----------

  /** 一个空段落的新文档。 */
  private static Path newDoc() throws Exception {
    Path tmp = java.nio.file.Files.createTempFile("authoring-", ".docx");
    try (org.apache.poi.xwpf.usermodel.XWPFDocument poi =
        new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
      poi.createParagraph(); // 一个空段落
      try (var out = java.nio.file.Files.newOutputStream(tmp)) {
        poi.write(out);
      }
    }
    return tmp;
  }

  /** 一个含单个带文本 run 的段落的文档。 */
  private static Path newDocWithRun(String text) throws Exception {
    Path tmp = java.nio.file.Files.createTempFile("authoring-run-", ".docx");
    try (org.apache.poi.xwpf.usermodel.XWPFDocument poi =
        new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
      poi.createParagraph().createRun().setText(text);
      try (var out = java.nio.file.Files.newOutputStream(tmp)) {
        poi.write(out);
      }
    }
    return tmp;
  }
}
