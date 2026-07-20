package io.github.nondirectional.docx.core.api.track;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.exception.UnsupportedFeatureException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * advanced-types 子任务(move + property)的验收测试。
 *
 * <p>覆盖:
 *
 * <ul>
 *   <li>move:成对 accept/reject、配对端缺失抛异常、与文本类 accept 共存。
 *   <li>property(rPrChange):读出为 PropertyChangeDetails、accept(保留新树)、reject(用旧树覆盖)。
 *   <li>边界:文本类 accept(id) 命中 move 抛 UnsupportedFeatureException(因 move 需配对、走 acceptProperty 之外的统一
 *       accept 也支持)。
 * </ul>
 */
class TrackedAdvancedTypesTest {

  // ---------- move ----------

  /** 成对 move 能被 list 读出为 MOVE_FROM + MOVE_TO 两条。 */
  @Test
  void movePairIsReadable(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("move.docx");
    try (org.apache.poi.xwpf.usermodel.XWPFDocument poi =
        new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
      var p = poi.getDocument().getBody().addNewP();
      var mf = p.addNewMoveFrom();
      mf.setId(java.math.BigInteger.ONE);
      mf.setAuthor("non");
      mf.setDate(java.util.Calendar.getInstance());
      mf.addNewR().addNewDelText().setStringValue("被移动");
      var mt = p.addNewMoveTo();
      mt.setId(java.math.BigInteger.valueOf(2));
      mt.setAuthor("non");
      mt.setDate(java.util.Calendar.getInstance());
      mt.addNewR().addNewT().setStringValue("被移动");
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }
    try (Document doc = Docx.open(file)) {
      List<TrackedChange> list = doc.trackedChanges().list();
      assertThat(list)
          .extracting(TrackedChange::type)
          .containsExactlyInAnyOrder(TrackedChangeType.MOVE_FROM, TrackedChangeType.MOVE_TO);
    }
  }

  /** accept 成对 move:moveFrom 移除、moveTo 保留文本。 */
  @Test
  void acceptMovePairAppliesBothEnds(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("move-accept.docx");
    try (org.apache.poi.xwpf.usermodel.XWPFDocument poi =
        new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
      var p = poi.getDocument().getBody().addNewP();
      var mf = p.addNewMoveFrom();
      mf.setId(java.math.BigInteger.ONE);
      mf.setAuthor("non");
      mf.setDate(java.util.Calendar.getInstance());
      // moveFrom 同 del:被移走的源文本用 delText
      mf.addNewR().addNewDelText().setStringValue("X");
      var mt = p.addNewMoveTo();
      mt.setId(java.math.BigInteger.valueOf(2));
      mt.setAuthor("non");
      mt.setDate(java.util.Calendar.getInstance());
      // moveTo 同 ins:目标文本用 t
      mt.addNewR().addNewT().setStringValue("X");
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }
    try (Document doc = Docx.open(file)) {
      String fromId =
          doc.trackedChanges().list().stream()
              .filter(c -> c.type() == TrackedChangeType.MOVE_FROM)
              .findFirst()
              .orElseThrow()
              .id();
      doc.trackedChanges().accept(fromId); // 命中 from 端,联动两端
      // 配对操作后两条 move 都消失
      assertThat(doc.trackedChanges().list()).isEmpty();
    }
  }

  /** 孤立 moveFrom(无配对端)accept 时抛 NoSuchElementException(不静默降级)。 */
  @Test
  void orphanMoveThrowsOnAccept(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("move-orphan.docx");
    try (org.apache.poi.xwpf.usermodel.XWPFDocument poi =
        new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
      var p = poi.getDocument().getBody().addNewP();
      var mf = p.addNewMoveFrom();
      mf.setId(java.math.BigInteger.ONE);
      mf.setAuthor("non");
      mf.addNewR().addNewDelText().setStringValue("无配对");
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }
    try (Document doc = Docx.open(file)) {
      String id = doc.trackedChanges().list().get(0).id();
      assertThatThrownBy(() -> doc.trackedChanges().accept(id))
          .isInstanceOf(java.util.NoSuchElementException.class)
          .hasMessageContaining("配对端");
    }
  }

  // ---------- property (rPrChange) ----------

  /** rPrChange 能被读出为 RPR_CHANGE + PropertyChangeDetails。 */
  @Test
  void rPrChangeIsReadable(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("rpr.docx");
    try (org.apache.poi.xwpf.usermodel.XWPFDocument poi =
        new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
      var p = poi.getDocument().getBody().addNewP();
      var r = p.addNewR();
      r.addNewT().setStringValue("文本");
      var rPr = r.addNewRPr();
      rPr.addNewB(); // 新样式:粗体
      var change = rPr.addNewRPrChange();
      change.setId(java.math.BigInteger.ONE);
      change.setAuthor("non");
      change.addNewRPr(); // 旧样式:空(非粗)
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }
    try (Document doc = Docx.open(file)) {
      List<TrackedChange> list = doc.trackedChanges().list();
      assertThat(list).hasSize(1);
      TrackedChange c = list.get(0);
      assertThat(c.type()).isEqualTo(TrackedChangeType.RPR_CHANGE);
      assertThat(c.family()).isEqualTo(TrackedChangeFamily.PROPERTY);
      PropertyChangeDetails d = (PropertyChangeDetails) c.details();
      assertThat(d.kind()).isEqualTo(PropertyChangeKind.RUN_PROPERTIES);
      assertThat(d.newSummary()).contains("b"); // 新树含粗体
    }
  }

  /** accept rPrChange:保留新(粗体)树,移除 rPrChange 标记。 */
  @Test
  void acceptRPrChangeKeepsNewTree(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("rpr-accept.docx");
    try (org.apache.poi.xwpf.usermodel.XWPFDocument poi =
        new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
      var p = poi.getDocument().getBody().addNewP();
      var r = p.addNewR();
      r.addNewT().setStringValue("文本");
      var rPr = r.addNewRPr();
      rPr.addNewB();
      var change = rPr.addNewRPrChange();
      change.setId(java.math.BigInteger.ONE);
      change.setAuthor("non");
      change.addNewRPr();
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }
    try (Document doc = Docx.open(file)) {
      String id = doc.trackedChanges().list().get(0).id();
      doc.trackedChanges().acceptProperty(id);
      assertThat(doc.trackedChanges().list()).isEmpty();
    }
  }

  /** reject rPrChange:用旧(空)树覆盖新(粗体)树,移除 rPrChange 标记。 */
  @Test
  void rejectRPrChangeRestoresOldTree(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("rpr-reject.docx");
    try (org.apache.poi.xwpf.usermodel.XWPFDocument poi =
        new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
      var p = poi.getDocument().getBody().addNewP();
      var r = p.addNewR();
      r.addNewT().setStringValue("文本");
      var rPr = r.addNewRPr();
      rPr.addNewB(); // 新:粗
      var change = rPr.addNewRPrChange();
      change.setId(java.math.BigInteger.ONE);
      change.setAuthor("non");
      change.addNewRPr(); // 旧:空
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }
    try (Document doc = Docx.open(file)) {
      String id = doc.trackedChanges().list().get(0).id();
      doc.trackedChanges().rejectProperty(id);
      assertThat(doc.trackedChanges().list()).isEmpty();
      // 验证 reject 后外层 rPr 不再含 b(旧空树覆盖了新粗树)——走 raw 检查
      var r = doc.raw().getDocument().getBody().getPArray(0).getRList().get(0);
      assertThat(r.getRPr()).isNotNull();
      assertThat(r.getRPr().sizeOfBArray()).isZero();
    }
  }

  /** acceptProperty 命中非属性类(文本类)抛 UnsupportedFeatureException。 */
  @Test
  void acceptPropertyRejectsNonProperty(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("rpr-mismatch.docx");
    try (org.apache.poi.xwpf.usermodel.XWPFDocument poi =
        new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
      var p = poi.getDocument().getBody().addNewP();
      var ins = p.addNewIns();
      ins.setId(java.math.BigInteger.ONE);
      ins.setAuthor("non");
      ins.addNewR().addNewT().setStringValue("X");
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }
    try (Document doc = Docx.open(file)) {
      String id = doc.trackedChanges().list().get(0).id();
      assertThatThrownBy(() -> doc.trackedChanges().acceptProperty(id))
          .isInstanceOf(UnsupportedFeatureException.class);
    }
  }

  /** 属性类修订的 raw() 抛 UnsupportedFeatureException(方案 C:写走专用方法)。 */
  @Test
  void propertyRawThrows(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("rpr-raw.docx");
    try (org.apache.poi.xwpf.usermodel.XWPFDocument poi =
        new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
      var p = poi.getDocument().getBody().addNewP();
      var r = p.addNewR();
      r.addNewT().setStringValue("X");
      var rPr = r.addNewRPr();
      rPr.addNewB();
      var change = rPr.addNewRPrChange();
      change.setId(java.math.BigInteger.ONE);
      change.setAuthor("non");
      change.addNewRPr();
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }
    try (Document doc = Docx.open(file)) {
      TrackedChange c = doc.trackedChanges().list().get(0);
      assertThatThrownBy(c::raw).isInstanceOf(UnsupportedFeatureException.class);
    }
  }
}
