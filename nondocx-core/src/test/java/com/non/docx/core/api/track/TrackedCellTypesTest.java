package com.non.docx.core.api.track;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.exception.UnsupportedFeatureException;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.xmlbeans.XmlCursor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTbl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTrackChange;

/**
 * cell 子任务(cellIns/cellDel/cellMerge)的验收测试。
 *
 * <p>覆盖:
 *
 * <ul>
 *   <li>read:cellIns/cellDel/cellMerge 能被 {@code list()} 读回,type/details/location 正确;location path
 *       不含 paragraph segment(挂在 tcPr,不在单元格内段落里)。
 *   <li>accept/reject:作用于整个 {@code <w:tc>}(存亡语义);cellIns accept 保留 tc、reject 移除 tc;cellDel 对称。
 *   <li>边界:cellMerge 的 accept/reject 抛 {@code UnsupportedFeatureException};acceptCell 命中非 cell
 *       类抛异常; acceptAll 不误伤 cell 类。
 *   <li>与单元格内文本类修订(同 tc 内 ins)共存:两条都读出、location 正确分层。
 * </ul>
 *
 * <p>fixture 用 XmlBeans 手搓(确定性、不依赖 Word)。cellMerge 因 typed 访问器编译期不可达,用 XmlCursor 直接插 {@code
 * <w:cellMerge>} 裸元素(见 {@link #addCellMergeViaCursor})。
 */
class TrackedCellTypesTest {

  // ---------- read ----------

  /** cellIns 能被读回为 CELL_INS + CellChangeDetails(CELL_INSERTION);location 停在 CELL,不含 paragraph。 */
  @Test
  void cellInsIsReadable(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("cellins.docx");
    try (XWPFDocument poi = new XWPFDocument()) {
      CTBody body = poi.getDocument().getBody();
      CTTbl tbl = body.addNewTbl();
      CTRow tr = tbl.addNewTr();
      CTTc tc = tr.addNewTc();
      tc.addNewP().addNewR().addNewT().setStringValue("单元格");
      addCellIns(tc, "1", "non");
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }
    try (Document doc = Docx.open(file)) {
      List<TrackedChange> list = doc.trackedChanges().list();
      assertThat(list).hasSize(1);
      TrackedChange c = list.get(0);
      assertThat(c.type()).isEqualTo(TrackedChangeType.CELL_INS);
      assertThat(c.family()).isEqualTo(TrackedChangeFamily.CELL);
      assertThat(c.author()).isEqualTo("non");
      assertThat(((CellChangeDetails) c.details()).kind()).isEqualTo(CellChangeKind.CELL_INSERTION);
      // location path: body > table > row > cell(不含 paragraph——cell 修订挂在 tcPr)
      assertThat(c.location().segments())
          .containsExactly(
              new TrackedChangeSegment(TrackedChangeSegmentKind.BODY, 0),
              new TrackedChangeSegment(TrackedChangeSegmentKind.TABLE, 0),
              new TrackedChangeSegment(TrackedChangeSegmentKind.ROW, 0),
              new TrackedChangeSegment(TrackedChangeSegmentKind.CELL, 0));
    }
  }

  /** cellDel 读回为 CELL_DEL + CELL_DELETION。 */
  @Test
  void cellDelIsReadable(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("celldel.docx");
    try (XWPFDocument poi = new XWPFDocument()) {
      CTTc tc = poi.getDocument().getBody().addNewTbl().addNewTr().addNewTc();
      tc.addNewP().addNewR().addNewT().setStringValue("将删");
      addCellDel(tc, "2", "non");
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }
    try (Document doc = Docx.open(file)) {
      TrackedChange c = doc.trackedChanges().list().get(0);
      assertThat(c.type()).isEqualTo(TrackedChangeType.CELL_DEL);
      assertThat(((CellChangeDetails) c.details()).kind()).isEqualTo(CellChangeKind.CELL_DELETION);
    }
  }

  /** cellMerge 读回为 CELL_MERGE + UNCONFIRMED_MERGE(XmlCursor 探测路径,CT 类型缺失)。 */
  @Test
  void cellMergeIsReadable(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("cellmerge.docx");
    try (XWPFDocument poi = new XWPFDocument()) {
      CTTc tc = poi.getDocument().getBody().addNewTbl().addNewTr().addNewTc();
      tc.addNewP().addNewR().addNewT().setStringValue("合并单元格");
      addCellMergeViaCursor(tc, "3", "non");
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }
    try (Document doc = Docx.open(file)) {
      TrackedChange c = doc.trackedChanges().list().get(0);
      assertThat(c.type()).isEqualTo(TrackedChangeType.CELL_MERGE);
      assertThat(c.family()).isEqualTo(TrackedChangeFamily.CELL);
      assertThat(((CellChangeDetails) c.details()).kind())
          .isEqualTo(CellChangeKind.UNCONFIRMED_MERGE);
    }
  }

  /** 单元格结构类修订与单元格内文本类修订(同 tc 内 ins)共存:两条都读出,location 正确分层。 */
  @Test
  void cellStructureAndTextRevisionsCoexist(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("cell-and-text.docx");
    try (XWPFDocument poi = new XWPFDocument()) {
      CTTc tc = poi.getDocument().getBody().addNewTbl().addNewTr().addNewTc();
      // 单元格内段落带一个文本类 ins(location 含 paragraph)
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP p = tc.addNewP();
      var ins = p.addNewIns();
      ins.setId(java.math.BigInteger.valueOf(10));
      ins.setAuthor("non");
      ins.addNewR().addNewT().setStringValue("单元格内插入");
      // 单元格本身带 cellIns(location 不含 paragraph)
      addCellIns(tc, "1", "non");
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }
    try (Document doc = Docx.open(file)) {
      List<TrackedChange> list = doc.trackedChanges().list();
      assertThat(list).hasSize(2);
      assertThat(list)
          .extracting(TrackedChange::type)
          .containsExactlyInAnyOrder(TrackedChangeType.INS, TrackedChangeType.CELL_INS);
      // 文本类 ins 的 location 含 paragraph;cellIns 的 location 停在 cell
      TrackedChange textIns =
          list.stream().filter(c -> c.type() == TrackedChangeType.INS).findFirst().orElseThrow();
      TrackedChange cellIns =
          list.stream()
              .filter(c -> c.type() == TrackedChangeType.CELL_INS)
              .findFirst()
              .orElseThrow();
      assertThat(lastSegment(textIns.location()).kind())
          .isEqualTo(TrackedChangeSegmentKind.PARAGRAPH);
      assertThat(lastSegment(cellIns.location()).kind()).isEqualTo(TrackedChangeSegmentKind.CELL);
    }
  }

  // ---------- accept / reject ----------

  /** accept cellIns:保留整个 tc,仅删标记。 */
  @Test
  void acceptCellInsKeepsTc(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("accept-cellins.docx");
    try (XWPFDocument poi = new XWPFDocument()) {
      CTRow tr = poi.getDocument().getBody().addNewTbl().addNewTr();
      CTTc tc = tr.addNewTc();
      tc.addNewP().addNewR().addNewT().setStringValue("保留");
      addCellIns(tc, "1", "non");
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }
    try (Document doc = Docx.open(file)) {
      String id = doc.trackedChanges().list().get(0).id();
      doc.trackedChanges().acceptCell(id);
      // cellIns 标记消失
      assertThat(doc.trackedChanges().list()).isEmpty();
      // tc 仍在(单元格保留),内文仍在
      CTRow tr = doc.raw().getDocument().getBody().getTblArray(0).getTrArray(0);
      assertThat(tr.sizeOfTcArray()).isEqualTo(1);
      assertThat(tr.getTcArray(0).isSetTcPr()).isTrue();
      assertThat(tr.getTcArray(0).getTcPr().isSetCellIns()).isFalse();
    }
  }

  /** reject cellIns:移除整个 tc(插入被撤销,单元格本不该存在)。 */
  @Test
  void rejectCellInsRemovesTc(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("reject-cellins.docx");
    try (XWPFDocument poi = new XWPFDocument()) {
      CTRow tr = poi.getDocument().getBody().addNewTbl().addNewTr();
      CTTc tc0 = tr.addNewTc();
      tc0.addNewP().addNewR().addNewT().setStringValue("保留的单元格");
      CTTc tc1 = tr.addNewTc();
      tc1.addNewP().addNewR().addNewT().setStringValue("被删的单元格");
      addCellIns(tc1, "1", "non");
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }
    try (Document doc = Docx.open(file)) {
      String id = doc.trackedChanges().list().get(0).id();
      doc.trackedChanges().rejectCell(id);
      assertThat(doc.trackedChanges().list()).isEmpty();
      // 整个 tc1 被移除:行里只剩 1 个 tc
      CTRow tr = doc.raw().getDocument().getBody().getTblArray(0).getTrArray(0);
      assertThat(tr.sizeOfTcArray()).isEqualTo(1);
      assertThat(tr.getTcArray(0).getPArray(0).getRList().get(0).getTArray(0).getStringValue())
          .isEqualTo("保留的单元格");
    }
  }

  /** accept cellDel:移除整个 tc(删除生效)。 */
  @Test
  void acceptCellDelRemovesTc(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("accept-celldel.docx");
    try (XWPFDocument poi = new XWPFDocument()) {
      CTRow tr = poi.getDocument().getBody().addNewTbl().addNewTr();
      CTTc tc0 = tr.addNewTc();
      tc0.addNewP().addNewR().addNewT().setStringValue("保留");
      CTTc tc1 = tr.addNewTc();
      tc1.addNewP().addNewR().addNewT().setStringValue("将删");
      addCellDel(tc1, "2", "non");
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }
    try (Document doc = Docx.open(file)) {
      String id = doc.trackedChanges().list().get(0).id();
      doc.trackedChanges().acceptCell(id);
      assertThat(doc.trackedChanges().list()).isEmpty();
      CTRow tr = doc.raw().getDocument().getBody().getTblArray(0).getTrArray(0);
      assertThat(tr.sizeOfTcArray()).isEqualTo(1);
    }
  }

  /** reject cellDel:保留 tc,仅删标记(删除被撤销,单元格恢复)。 */
  @Test
  void rejectCellDelKeepsTc(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("reject-celldel.docx");
    try (XWPFDocument poi = new XWPFDocument()) {
      CTTc tc = poi.getDocument().getBody().addNewTbl().addNewTr().addNewTc();
      tc.addNewP().addNewR().addNewT().setStringValue("恢复");
      addCellDel(tc, "2", "non");
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }
    try (Document doc = Docx.open(file)) {
      String id = doc.trackedChanges().list().get(0).id();
      doc.trackedChanges().rejectCell(id);
      assertThat(doc.trackedChanges().list()).isEmpty();
      CTRow tr = doc.raw().getDocument().getBody().getTblArray(0).getTrArray(0);
      assertThat(tr.sizeOfTcArray()).isEqualTo(1);
      assertThat(tr.getTcArray(0).getTcPr().isSetCellDel()).isFalse();
    }
  }

  // ---------- 边界 ----------

  /** cellMerge 的 accept/reject 抛 UnsupportedFeatureException(CT 类型缺失)。 */
  @Test
  void cellMergeAcceptRejectThrows(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("merge-nop.docx");
    try (XWPFDocument poi = new XWPFDocument()) {
      CTTc tc = poi.getDocument().getBody().addNewTbl().addNewTr().addNewTc();
      tc.addNewP().addNewR().addNewT().setStringValue("合并");
      addCellMergeViaCursor(tc, "3", "non");
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }
    try (Document doc = Docx.open(file)) {
      String id = doc.trackedChanges().list().get(0).id();
      assertThatThrownBy(() -> doc.trackedChanges().acceptCell(id))
          .isInstanceOf(UnsupportedFeatureException.class)
          .hasMessageContaining("cellMerge");
      assertThatThrownBy(() -> doc.trackedChanges().rejectCell(id))
          .isInstanceOf(UnsupportedFeatureException.class);
      // 失败后文档未变:cellMerge 仍在
      assertThat(doc.trackedChanges().list()).hasSize(1);
    }
  }

  /** acceptCell 命中非 cell 类(文本类)抛 UnsupportedFeatureException。 */
  @Test
  void acceptCellRejectsNonCell(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("mismatch.docx");
    try (XWPFDocument poi = new XWPFDocument()) {
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
      assertThatThrownBy(() -> doc.trackedChanges().acceptCell(id))
          .isInstanceOf(UnsupportedFeatureException.class);
    }
  }

  /** acceptAll 不误伤 cell 类:批量操作只作用于文本/移动类,cell 类保留。 */
  @Test
  void acceptAllDoesNotTouchCellRevisions(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("all-no-cell.docx");
    try (XWPFDocument poi = new XWPFDocument()) {
      // body 段落里一个文本类 ins
      var p = poi.getDocument().getBody().addNewP();
      var ins = p.addNewIns();
      ins.setId(java.math.BigInteger.ONE);
      ins.setAuthor("non");
      ins.addNewR().addNewT().setStringValue("文本插入");
      // 表格里一个 cellIns
      CTTc tc = poi.getDocument().getBody().addNewTbl().addNewTr().addNewTc();
      tc.addNewP();
      addCellIns(tc, "2", "non");
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }
    try (Document doc = Docx.open(file)) {
      int applied = doc.trackedChanges().acceptAll();
      // 只应用了文本类那 1 条
      assertThat(applied).isEqualTo(1);
      // cellIns 不受影响,仍在
      List<TrackedChange> rest = doc.trackedChanges().list();
      assertThat(rest).hasSize(1);
      assertThat(rest.get(0).type()).isEqualTo(TrackedChangeType.CELL_INS);
    }
  }

  /** cell 类修订的 raw() 抛 UnsupportedFeatureException(写走专用方法)。 */
  @Test
  void cellRawThrows(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("cell-raw.docx");
    try (XWPFDocument poi = new XWPFDocument()) {
      CTTc tc = poi.getDocument().getBody().addNewTbl().addNewTr().addNewTc();
      tc.addNewP();
      addCellIns(tc, "1", "non");
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }
    try (Document doc = Docx.open(file)) {
      TrackedChange c = doc.trackedChanges().list().get(0);
      assertThatThrownBy(c::raw).isInstanceOf(UnsupportedFeatureException.class);
    }
  }

  // ---------- 手搓 fixture 的辅助 ----------

  /** 在 tc 的 tcPr 里加一个 cellIns(typed 路径)。 */
  private static void addCellIns(CTTc tc, String id, String author) {
    CTTcPr tcPr = tc.isSetTcPr() ? tc.getTcPr() : tc.addNewTcPr();
    CTTrackChange cellIns = tcPr.addNewCellIns();
    cellIns.setId(new java.math.BigInteger(id));
    cellIns.setAuthor(author);
  }

  /** 在 tc 的 tcPr 里加一个 cellDel(typed 路径)。 */
  private static void addCellDel(CTTc tc, String id, String author) {
    CTTcPr tcPr = tc.isSetTcPr() ? tc.getTcPr() : tc.addNewTcPr();
    CTTrackChange cellDel = tcPr.addNewCellDel();
    cellDel.setId(new java.math.BigInteger(id));
    cellDel.setAuthor(author);
  }

  /**
   * 用 XmlCursor 在 tc 的 tcPr 里插一个裸 {@code <w:cellMerge>} 元素。
   *
   * <p>因 {@code CTCellMergeTrackChange} 编译期不可达,不能走 {@code addNewCellMerge()};这里用 XmlCursor {@code
   * beginElement} 直接造一个本地名为 {@code cellMerge} 的元素,再设 id/author 属性(走 CTTrackChange 的 XmlBeans 形态)。这是
   * cellMerge fixture 的唯一构造方式。
   */
  private static void addCellMergeViaCursor(CTTc tc, String id, String author) {
    CTTcPr tcPr = tc.isSetTcPr() ? tc.getTcPr() : tc.addNewTcPr();
    XmlCursor cur = tcPr.newCursor();
    try {
      cur.toEndToken(); // 指向 tcPr 内部末尾
      cur.beginElement(
          javax.xml.namespace.QName.valueOf(
              "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}cellMerge"));
      cur.toPrevSibling(); // 回到新建的 cellMerge 元素
      // cellMerge 是 CTTrackChange 形态,设 id/author 属性
      cur.insertAttributeWithValue(
          javax.xml.namespace.QName.valueOf(
              "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}id"),
          id);
      cur.insertAttributeWithValue(
          javax.xml.namespace.QName.valueOf(
              "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}author"),
          author);
    } finally {
      cur.dispose();
    }
  }

  private static TrackedChangeSegment lastSegment(TrackedChangeLocation location) {
    List<TrackedChangeSegment> segs = location.segments();
    return segs.get(segs.size() - 1);
  }
}
