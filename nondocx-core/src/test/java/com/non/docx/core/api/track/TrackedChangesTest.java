package com.non.docx.core.api.track;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import java.nio.file.Path;
import java.util.Calendar;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRunTrackChange;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTbl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText;

/**
 * 修订(tracked changes)只读消费侧的验收测试。
 *
 * <p>用 XmlBeans 手搓带修订标记的 docx(确定性、不依赖 Word),验证 nondocx 的读取:开关状态、文档顺序枚举、位置 path、 稳定 id、命中/未命中查找。
 *
 * <p>这些测试覆盖 read 子任务的 AC1–AC5。
 */
class TrackedChangesTest {

  @Test
  void enabledReturnsFalseWhenTrackChangesAbsent(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("no-track.docx");
    try (XWPFDocument poi = new XWPFDocument()) {
      poi.createParagraph().createRun().setText("普通文档,未开启修订");
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }
    try (Document doc = Docx.open(file)) {
      assertThat(doc.trackedChanges().enabled()).isFalse();
    }
  }

  @Test
  void enabledReturnsTrueWhenTrackChangesSet(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("track-on.docx");
    try (XWPFDocument poi = new XWPFDocument()) {
      poi.getSettings().setTrackRevisions(true);
      poi.createParagraph().createRun().setText("开启了修订记录");
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }
    try (Document doc = Docx.open(file)) {
      assertThat(doc.trackedChanges().enabled()).isTrue();
    }
  }

  @Test
  void listIsEmptyWhenNoRevisions(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("no-revisions.docx");
    try (XWPFDocument poi = new XWPFDocument()) {
      poi.createParagraph().createRun().setText("没有修订标记");
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }
    try (Document doc = Docx.open(file)) {
      assertThat(doc.trackedChanges().list()).isEmpty();
    }
  }

  /**
   * 正文段落内的文本类修订(ins / del):验证枚举、文档顺序、type、author、details 文本、location path。
   *
   * <p>OOXML 结构:
   *
   * <pre>{@code
   * <w:body>
   *   <w:p>                                          ← body[0] paragraph[0]
   *     <w:ins w:id="1" w:author="non" ...>
   *       <w:r><w:t>新增</w:t></w:r>
   *     </w:ins>
   *     <w:del w:id="2" w:author="non" ...>
   *       <w:r><w:delText>删除</w:delText></w:r>
   *     </w:del>
   *   </w:p>
   * </w:body>
   * }</pre>
   */
  @Test
  void listsInsAndDelInParagraphInDocumentOrder(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("body-revisions.docx");
    try (XWPFDocument poi = new XWPFDocument()) {
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP p =
          poi.getDocument().getBody().addNewP();
      addIns(p, "1", "non", "新增");
      addDel(p, "2", "non", "删除");
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }

    try (Document doc = Docx.open(file)) {
      List<TrackedChange> list = doc.trackedChanges().list();
      assertThat(list).hasSize(2);

      TrackedChange ins = list.get(0);
      assertThat(ins.type()).isEqualTo(TrackedChangeType.INS);
      assertThat(ins.family()).isEqualTo(TrackedChangeFamily.TEXT);
      assertThat(ins.author()).isEqualTo("non");
      assertThat(((TextChangeDetails) ins.details()).text()).isEqualTo("新增");
      // location path: body[0] > paragraph[0]
      assertThat(ins.location().segments())
          .containsExactly(
              new TrackedChangeSegment(TrackedChangeSegmentKind.BODY, 0),
              new TrackedChangeSegment(TrackedChangeSegmentKind.PARAGRAPH, 0));

      TrackedChange del = list.get(1);
      assertThat(del.type()).isEqualTo(TrackedChangeType.DEL);
      assertThat(((TextChangeDetails) del.details()).text()).isEqualTo("删除");
    }
  }

  /**
   * 表格单元格内的修订:验证 location path 能正确穿透 table → row → cell → paragraph 层级。
   *
   * <p>OOXML 结构:
   *
   * <pre>{@code
   * <w:body>
   *   <w:tbl>                                       ← body[0] table[0]
   *     <w:tr>                                      ← row[0]
   *       <w:tc>                                    ← cell[0]
   *         <w:p>                                   ← paragraph[0]
   *           <w:ins ...><w:r><w:t>单元格插入</w:t></w:r></w:ins>
   * </pre>
   */
  @Test
  void locatesRevisionInsideTableCell(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("table-revision.docx");
    try (XWPFDocument poi = new XWPFDocument()) {
      CTBody body = poi.getDocument().getBody();
      CTTbl tbl = body.addNewTbl();
      CTRow tr = tbl.addNewTr();
      CTTc tc = tr.addNewTc();
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP p = tc.addNewP();
      addIns(p, "10", "author2", "单元格插入");
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }

    try (Document doc = Docx.open(file)) {
      List<TrackedChange> list = doc.trackedChanges().list();
      assertThat(list).hasSize(1);
      TrackedChange change = list.get(0);
      assertThat(((TextChangeDetails) change.details()).text()).isEqualTo("单元格插入");
      // location path: body[0] > table[0] > row[0] > cell[0] > paragraph[0]
      assertThat(change.location().segments())
          .containsExactly(
              new TrackedChangeSegment(TrackedChangeSegmentKind.BODY, 0),
              new TrackedChangeSegment(TrackedChangeSegmentKind.TABLE, 0),
              new TrackedChangeSegment(TrackedChangeSegmentKind.ROW, 0),
              new TrackedChangeSegment(TrackedChangeSegmentKind.CELL, 0),
              new TrackedChangeSegment(TrackedChangeSegmentKind.PARAGRAPH, 0));
    }
  }

  /** 多个修订跨段落:验证文档顺序与各段索引正确。 */
  @Test
  void preservesDocumentOrderAcrossParagraphs(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("multi-paragraph.docx");
    try (XWPFDocument poi = new XWPFDocument()) {
      CTBody body = poi.getDocument().getBody();
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP p0 = body.addNewP();
      addIns(p0, "1", "non", "第一段插入");
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP p1 = body.addNewP();
      addDel(p1, "2", "non", "第二段删除");
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }

    try (Document doc = Docx.open(file)) {
      List<TrackedChange> list = doc.trackedChanges().list();
      assertThat(list).hasSize(2);
      // 第一条在第 0 段,第二条在第 1 段。
      assertThat(lastSegment(list.get(0).location()))
          .isEqualTo(new TrackedChangeSegment(TrackedChangeSegmentKind.PARAGRAPH, 0));
      assertThat(lastSegment(list.get(1).location()))
          .isEqualTo(new TrackedChangeSegment(TrackedChangeSegmentKind.PARAGRAPH, 1));
    }
  }

  /** 稳定 id 在同一会话内、多次 list() 调用间保持相等(进程内稳定)。 */
  @Test
  void idIsStableWithinSameSession(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("id-stable.docx");
    try (XWPFDocument poi = new XWPFDocument()) {
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP p =
          poi.getDocument().getBody().addNewP();
      addIns(p, "42", "non", "稳定");
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }

    try (Document doc = Docx.open(file)) {
      String idFirst = doc.trackedChanges().list().get(0).id();
      String idSecond = doc.trackedChanges().list().get(0).id();
      assertThat(idSecond).isEqualTo(idFirst);
      // id 内部含 type 与 w:id(调试可追踪),但格式非公共契约——这里只断言「含 w:id」的弱性质。
      assertThat(idFirst).contains("ins").contains("42");
    }
  }

  /** get(id) 命中返回对应修订。 */
  @Test
  void getByIdHits(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("get-hit.docx");
    try (XWPFDocument poi = new XWPFDocument()) {
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP p =
          poi.getDocument().getBody().addNewP();
      addIns(p, "7", "non", "命中");
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }

    try (Document doc = Docx.open(file)) {
      String id = doc.trackedChanges().list().get(0).id();
      TrackedChange found = doc.trackedChanges().get(id);
      assertThat(found.id()).isEqualTo(id);
      assertThat(((TextChangeDetails) found.details()).text()).isEqualTo("命中");
    }
  }

  /** get(id) 未命中抛 NoSuchElementException(命中式访问,不返回 null)。 */
  @Test
  void getByIdMissesThrowsNoSuchElement(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("get-miss.docx");
    try (XWPFDocument poi = new XWPFDocument()) {
      poi.createParagraph().createRun().setText("无修订");
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }

    try (Document doc = Docx.open(file)) {
      assertThatThrownBy(() -> doc.trackedChanges().get("不存在"))
          .isInstanceOf(java.util.NoSuchElementException.class)
          .hasMessageContaining("不存在");
    }
  }

  /** date() 透传 OOXML w:date;date 未置时为 null。 */
  @Test
  void readsDateWhenPresent(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("with-date.docx");
    Calendar when = new Calendar.Builder().setDate(2026, 5, 18).setTimeOfDay(10, 0, 0).build();
    try (XWPFDocument poi = new XWPFDocument()) {
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP p =
          poi.getDocument().getBody().addNewP();
      addIns(p, "1", "non", "x", when);
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        poi.write(out);
      }
    }
    try (Document doc = Docx.open(file)) {
      Calendar date = doc.trackedChanges().list().get(0).date();
      assertThat(date).isNotNull();
      assertThat(date.get(Calendar.YEAR)).isEqualTo(2026);
    }
  }

  // ---------- 手搓修订标记的辅助 ----------

  /** 在段落里加一个 {@code <w:ins>},内含一个带文本的 run。date 默认不设。 */
  private static void addIns(
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP p,
      String id,
      String author,
      String text) {
    addIns(p, id, author, text, null);
  }

  private static void addIns(
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP p,
      String id,
      String author,
      String text,
      Calendar date) {
    CTRunTrackChange ins = p.addNewIns();
    ins.setId(new java.math.BigInteger(id));
    ins.setAuthor(author);
    if (date != null) {
      ins.setDate(date);
    }
    CTR r = ins.addNewR();
    CTText t = r.addNewT();
    t.setStringValue(text);
  }

  /** 在段落里加一个 {@code <w:del>},内含一个带 delText 的 run(注意删除用 delText 而非 t)。 */
  private static void addDel(
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP p,
      String id,
      String author,
      String text) {
    CTRunTrackChange del = p.addNewDel();
    del.setId(new java.math.BigInteger(id));
    del.setAuthor(author);
    CTR r = del.addNewR();
    r.addNewDelText().setStringValue(text);
  }

  private static TrackedChangeSegment lastSegment(TrackedChangeLocation location) {
    List<TrackedChangeSegment> segs = location.segments();
    return segs.get(segs.size() - 1);
  }
}
