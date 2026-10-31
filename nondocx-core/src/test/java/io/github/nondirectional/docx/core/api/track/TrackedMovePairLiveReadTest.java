package io.github.nondirectional.docx.core.api.track;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRunTrackChange;

/**
 * move 成对修订在 <b>live 会话</b>里的读稳定性锁定测试(POI 段落包装缓存断连修复)。
 *
 * <p><b>为什么需要这个测试。</b> move 创作({@code moveRunsFrom} 的 {@code moveXml})与 accept/reject 的 XML 手术会让
 * POI {@code XWPFParagraph.runs}/{@code iruns} 缓存(open 时固化)里的旧 {@code XWPFRun} 句柄断连,此后 live
 * 会话对受影响段落的任何读都抛 {@code XmlValueDisconnectedException};落盘走 XML 树序列化不经缓存,save+reopen 后一切正常——纯 live
 * 会话缺陷,既有测试全部走 reopen 口径故从未踩到。
 *
 * <p><b>现状为抛 XmlValueDisconnectedException,本测试正面锁定 live 会话崩溃修复</b>:live 读断言全部放在 save/reopen 之前。核心修复
 * = 手术点尾部经同包桥接 {@code XWPFParagraphCacheBridge} 按需重建段落缓存。
 *
 * <p>锁定的语义保证(见任务 design §3.3):
 *
 * <ul>
 *   <li>move 创作后源段 live 读值与 reopen 口径一致(源文本已转 delText,{@code getText()} 跳过)。
 *   <li>「XML 手术新建的 ins/moveTo 在 live text() 不可见」的 §6.13 既有语义保持(目标段仍只读原值)。
 *   <li>按 id accept 第二端(move 配对已整对完成)仍抛 {@code NoSuchElementException}——行为锁定,不改。
 *   <li>open 时已存在的修订(ins/del/moveFrom/moveTo 包装内 run 已入 POI 缓存)accept/reject 后 live 读不抛且值与 reopen
 *       一致(poi-bridge.md「accept 后必须 save+reopen」边界的正面锁定)。
 * </ul>
 */
class TrackedMovePairLiveReadTest {

  /**
   * live 会话完整时序:move 创作 → live 读 → accept(move_to) → live 读全部段 → 第二端 accept 报找不到 → save+reopen 终态。
   *
   * <p>修复前:move 创作后读源段即抛 XmlValueDisconnectedException(accept 之前);accept 后读全部段同样抛。
   */
  @Test
  void movePairSurvivesLiveSessionReads(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("move-live.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addRun("被移走的文字");
      doc.addParagraph("目标段");
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      // ---- live move 创作(模拟 toolkit 的 move_run_tracked)----
      doc.paragraph(1).moveRunsFrom("non", doc.paragraph(0), List.of(doc.paragraph(0).run(0)));

      // 修复前:下一行读源段即抛 XmlValueDisconnectedException(moveXml 断连了 open 时固化的句柄)。
      // 源段文本已转 delText 迁入 moveFrom,重建后的缓存读值与 reopen 口径一致(空串)。
      assertThat(doc.paragraph(0).text()).isEmpty();
      // runs() 同样经 POI 缓存,重建后可读:moveFrom 内 run(仅 delText)入缓存。
      assertThat(doc.paragraph(0).runs()).hasSize(1);
      // 目标段:moveTo 内 run 是 XML 手术新建、不入 live 缓存——live 仍只读原值(§6.13 既有语义,不回退)。
      assertThat(doc.paragraph(1).text()).isEqualTo("目标段");

      List<TrackedChange> pair = doc.trackedChanges().list();
      assertThat(pair)
          .extracting(TrackedChange::type)
          .containsExactly(TrackedChangeType.MOVE_FROM, TrackedChangeType.MOVE_TO);
      String fromId =
          pair.stream()
              .filter(c -> c.type() == TrackedChangeType.MOVE_FROM)
              .findFirst()
              .orElseThrow()
              .id();
      String toId =
          pair.stream()
              .filter(c -> c.type() == TrackedChangeType.MOVE_TO)
              .findFirst()
              .orElseThrow()
              .id();

      // ---- live accept 命中 to 端(联动两端)----
      doc.trackedChanges().accept(toId);

      // 修复前:accept 后读全部段抛 XmlValueDisconnectedException。修复后:不抛;源段空、目标段原值
      // (moveTo unwrap 迁出的 run 不入 live 缓存,§6.13 已知形态保持,终态由 reopen 断言)。
      assertThat(doc.paragraph(0).text()).isEmpty();
      assertThat(doc.paragraph(1).text()).isEqualTo("目标段");
      assertThat(doc.trackedChanges().list()).isEmpty();

      // ---- 第二端再 accept:整对已完成,报找不到(语义锁定,修复不改异常行为)----
      assertThatThrownBy(() -> doc.trackedChanges().accept(fromId))
          .isInstanceOf(java.util.NoSuchElementException.class)
          .hasMessageContaining("找不到 id 为 " + fromId + " 的修订");

      // ---- save+reopen 终态:文本移走、零残留修订(修复前后一致的落盘真相)----
      Path out = tmp.resolve("move-live-out.docx");
      doc.save(out);
      try (Document reopened = Docx.open(out)) {
        assertThat(reopened.paragraph(0).text()).isEmpty();
        assertThat(reopened.paragraph(1).text()).isEqualTo("目标段被移走的文字");
        assertThat(reopened.trackedChanges().list()).isEmpty();
      }
    }
  }

  /**
   * open 时已存在的文本类修订(ins/del/moveFrom/moveTo 包装内 run 已入 POI 段落缓存)accept 后,live 读不抛且值正确。
   *
   * <p>修复前:removeNode/unwrap 断连 open 时入缓存的句柄,accept 后任何 live 读必抛
   * XmlValueDisconnectedException(poi-bridge.md「accept 后必须 save+reopen」记录的家族边界)。
   */
  @Test
  void acceptOnOpenTimeRevisionsKeepsLiveReadable(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("open-time-revisions.docx");
    // POI 裸构造落盘(TrackedAdvancedTypesTest 手法):同一段落里普通文本 + ins + del + move 对,
    // 保证 Docx.open 时这些包装内的 run 都被 buildRunsInOrderFromXml 固化进段落缓存。
    try (org.apache.poi.xwpf.usermodel.XWPFDocument poi =
        new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
      CTP p = poi.getDocument().getBody().addNewP();
      p.addNewR().addNewT().setStringValue("前");
      ins(p, "插");
      del(p, "删");
      movePair(p, "移");
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }
    try (Document doc = Docx.open(file)) {
      // open 时 ins/moveTo 内 run(t)入缓存可见,del/moveFrom 内 run(delText)被 getText 跳过
      assertThat(doc.paragraph(0).text()).isEqualTo("前插移");
      // ins + del + move 对(两端算 1 条)= 3 条
      assertThat(doc.trackedChanges().acceptAll()).isEqualTo(3);
      // 修复前:下一行抛 XmlValueDisconnectedException。修复后:live 读值与 reopen 口径一致
      // (ins/moveTo 生效保留,del/moveFrom 删除生效消失)。
      assertThat(doc.paragraph(0).text()).isEqualTo("前插移");
      assertThat(doc.trackedChanges().list()).isEmpty();
      assertThat(doc.paragraph(0).runs()).hasSize(3);

      Path out = tmp.resolve("open-time-revisions-accept.docx");
      doc.save(out);
      try (Document reopened = Docx.open(out)) {
        assertThat(reopened.paragraph(0).text()).isEqualTo("前插移");
        assertThat(reopened.trackedChanges().list()).isEmpty();
      }
    }
  }

  /**
   * 同族对称面:open 时已存在的修订 <b>reject</b> 后 live 读不抛且值正确(reject del/moveFrom 走 delText→t 恢复 +
   * unwrap,同样会断连 open 时入缓存的句柄)。
   */
  @Test
  void rejectOnOpenTimeRevisionsKeepsLiveReadable(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("open-time-revisions-reject.docx");
    try (org.apache.poi.xwpf.usermodel.XWPFDocument poi =
        new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
      CTP p = poi.getDocument().getBody().addNewP();
      p.addNewR().addNewT().setStringValue("前");
      ins(p, "插");
      del(p, "删");
      movePair(p, "移");
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }
    try (Document doc = Docx.open(file)) {
      assertThat(doc.trackedChanges().rejectAll()).isEqualTo(3);
      // 修复前:下一行抛 XmlValueDisconnectedException。修复后:ins/moveTo 被撤销消失,
      // del/moveFrom 的 delText 恢复为 t 回到正文——live 读值与 reopen 口径一致。
      assertThat(doc.paragraph(0).text()).isEqualTo("前删移");
      assertThat(doc.trackedChanges().list()).isEmpty();

      Path out = tmp.resolve("open-time-revisions-reject-out.docx");
      doc.save(out);
      try (Document reopened = Docx.open(out)) {
        assertThat(reopened.paragraph(0).text()).isEqualTo("前删移");
        assertThat(reopened.trackedChanges().list()).isEmpty();
      }
    }
  }

  /**
   * 同族缺口正面锁定:整 run tracked 删除({@code Paragraph.addDeletion} / {@code Run.replaceTracked} 复合路径) 的
   * {@code moveXml} 同样迁走段落<b>直接子</b> run,open 时已固化的 {@code XWPFRun} 句柄断连——修复前此后任何 live 读必抛
   * XmlValueDisconnectedException(check 阶段探针实证,与 move 创作同型;既有测试只读新 run 不读段落 live text,故从未暴露)。
   */
  @Test
  void addDeletionAndReplaceTrackedKeepLiveReadable(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("del-live.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addRun("旧文本甲").bold();
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      // 整 run 删除:run(0) 是 open 时固化的直接子 run,迁入 <w:del> 即断连缓存句柄。
      doc.paragraph(0).addDeletion("non", doc.paragraph(0).run(0));
      // 修复前:下一行抛 XmlValueDisconnectedException。修复后:delText 被 getText() 跳过,live 读值与 reopen 一致。
      assertThat(doc.paragraph(0).text()).isEmpty();
      assertThat(doc.trackedChanges().list()).hasSize(1);

      // Run.replaceTracked 复合路径(内部先走同一个 addDeletion):再开一段验证替换后 live 读不抛。
      doc.addParagraph().addRun("旧文本乙");
      io.github.nondirectional.docx.core.api.text.Run replacement =
          doc.paragraph(1).run(0).replaceTracked("non", "新文本");
      assertThat(replacement.text()).isEqualTo("新文本");
      // 修复前:replaceTracked 内部的 addDeletion 断连段 1 缓存,下一行必抛。
      // 修复后:del 生效(旧文本消失);ins 是 XML 手术新建、不入 live 缓存,§6.13 语义保持(text 不含新文本)。
      assertThat(doc.paragraph(1).text()).isEmpty();

      Path out = tmp.resolve("del-live-out.docx");
      doc.save(out);
      try (Document reopened = Docx.open(out)) {
        // reopen 口径:段 0 del 文本不可见;段 1 del 消失、ins 可见。
        assertThat(reopened.paragraph(0).text()).isEmpty();
        assertThat(reopened.paragraph(1).text()).isEqualTo("新文本");
        assertThat(reopened.trackedChanges().list()).hasSize(3);
      }
    }
  }

  // ---------- fixture(POI 裸构造,同 TrackedAdvancedTypesTest 手法)----------

  /** 落盘一条 ins(id/author 固定,内含一个 t 文本 run)。 */
  private static void ins(CTP p, String text) {
    CTRunTrackChange ins = p.addNewIns();
    ins.setId(java.math.BigInteger.ONE);
    ins.setAuthor("non");
    ins.setDate(java.util.Calendar.getInstance());
    ins.addNewR().addNewT().setStringValue(text);
  }

  /** 落盘一条 del(删除/移动源用 delText,不是 t)。 */
  private static void del(CTP p, String text) {
    CTRunTrackChange del = p.addNewDel();
    del.setId(java.math.BigInteger.valueOf(2));
    del.setAuthor("non");
    del.setDate(java.util.Calendar.getInstance());
    del.addNewR().addNewDelText().setStringValue(text);
  }

  /** 落盘一对 moveFrom/moveTo(author+text 相同,满足配对启发式;源端 delText、目标端 t)。 */
  private static void movePair(CTP p, String text) {
    CTRunTrackChange from = p.addNewMoveFrom();
    from.setId(java.math.BigInteger.valueOf(3));
    from.setAuthor("non");
    from.setDate(java.util.Calendar.getInstance());
    from.addNewR().addNewDelText().setStringValue(text);
    CTRunTrackChange to = p.addNewMoveTo();
    to.setId(java.math.BigInteger.valueOf(4));
    to.setAuthor("non");
    to.setDate(java.util.Calendar.getInstance());
    to.addNewR().addNewT().setStringValue(text);
  }
}
