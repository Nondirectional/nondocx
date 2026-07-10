package com.non.docx.toolkit.orchestration.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.toolkit.orchestration.DocumentSnapshot;
import com.non.docx.toolkit.ref.ElementKind;
import com.non.docx.toolkit.ref.RefStability;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证 {@link SnapshotBuilder} 的 body 顺序索引正确性：段落和表格按真实 body 顺序交错编号时， 每个元素的 bodyIndex 反映其在 {@code
 * <w:body>} 交错序列中的绝对位置，index（投影索引）反映其 在各自类型列表中的位置。
 *
 * <p><b>这是任务 07-10-body-insert-position-table-boundary 的核心验证</b>： 确保 LLM 从快照里能读到准确的 body
 * 顺序信息，从而正确表达「在表格前/后插入段落」。
 */
class SnapshotBodyOrderTest {

  private final SnapshotBuilder builder = new SnapshotBuilder();

  @Test
  void tableAtStart() throws Exception {
    // body 顺序: [表格, 段落A, 段落B]
    Path file = tmp.resolve("table-start.docx");
    try (Document doc = Docx.create()) {
      var row = doc.addTable().addRow();
      row.addCell().addParagraph().addRun("表头");
      doc.addParagraph("段落A");
      doc.addParagraph("段落B");
      doc.save(file);
    }
    DocumentSnapshot snap = build(file);

    // 表格: index=0（第一个表格），bodyIndex=0（body 最前面）
    assertThat(snap.tables()).hasSize(1);
    assertThat(snap.tables().get(0).index()).isEqualTo(0);
    assertThat(snap.tables().get(0).bodyIndex()).isEqualTo(0);
    assertThat(snap.tables().get(0).ref().kind()).isEqualTo(ElementKind.TABLE);
    assertThat(snap.tables().get(0).ref().stability()).isEqualTo(RefStability.SESSION);
    assertThat(snap.tables().get(0).ref().documentRef().documentKey()).isEqualTo("test-conv");

    // 段落A: index=0（第一个段落），bodyIndex=1（在表格之后）
    assertThat(snap.paragraphs()).hasSize(2);
    assertThat(snap.paragraphs().get(0).index()).isEqualTo(0);
    assertThat(snap.paragraphs().get(0).bodyIndex()).isEqualTo(1);
    assertThat(snap.paragraphs().get(0).text()).isEqualTo("段落A");
    assertThat(snap.paragraphs().get(0).ref().kind()).isEqualTo(ElementKind.PARAGRAPH);
    assertThat(snap.paragraphs().get(0).ref().documentRef().sessionGeneration()).isEqualTo(1L);

    // 段落B: index=1（第二个段落），bodyIndex=2
    assertThat(snap.paragraphs().get(1).index()).isEqualTo(1);
    assertThat(snap.paragraphs().get(1).bodyIndex()).isEqualTo(2);
  }

  @Test
  void tableAtEnd() throws Exception {
    // body 顺序: [段落A, 段落B, 表格]
    Path file = tmp.resolve("table-end.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("段落A");
      doc.addParagraph("段落B");
      var row = doc.addTable().addRow();
      row.addCell().addParagraph().addRun("表尾");
      doc.save(file);
    }
    DocumentSnapshot snap = build(file);

    // 段落A: index=0, bodyIndex=0
    assertThat(snap.paragraphs().get(0).index()).isEqualTo(0);
    assertThat(snap.paragraphs().get(0).bodyIndex()).isEqualTo(0);

    // 段落B: index=1, bodyIndex=1
    assertThat(snap.paragraphs().get(1).index()).isEqualTo(1);
    assertThat(snap.paragraphs().get(1).bodyIndex()).isEqualTo(1);

    // 表格: index=0（第一个表格），bodyIndex=2（body 最后面）
    assertThat(snap.tables()).hasSize(1);
    assertThat(snap.tables().get(0).index()).isEqualTo(0);
    assertThat(snap.tables().get(0).bodyIndex()).isEqualTo(2);
  }

  @Test
  void tableInMiddle() throws Exception {
    // body 顺序: [段落A, 表格, 段落B]
    Path file = tmp.resolve("table-middle.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("段落A");
      var row = doc.addTable().addRow();
      row.addCell().addParagraph().addRun("中间表");
      doc.addParagraph("段落B");
      doc.save(file);
    }
    DocumentSnapshot snap = build(file);

    // 段落A: index=0, bodyIndex=0
    assertThat(snap.paragraphs().get(0).index()).isEqualTo(0);
    assertThat(snap.paragraphs().get(0).bodyIndex()).isEqualTo(0);

    // 表格: index=0, bodyIndex=1（夹在两个段落之间）
    assertThat(snap.tables().get(0).index()).isEqualTo(0);
    assertThat(snap.tables().get(0).bodyIndex()).isEqualTo(1);

    // 段落B: index=1, bodyIndex=2
    assertThat(snap.paragraphs().get(1).index()).isEqualTo(1);
    assertThat(snap.paragraphs().get(1).bodyIndex()).isEqualTo(2);
  }

  @Test
  void noTableBodyIndexEqualsIndex() throws Exception {
    // 无表格时 bodyIndex == index（两者等价）
    Path file = tmp.resolve("no-table.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("第一段");
      doc.addParagraph("第二段");
      doc.save(file);
    }
    DocumentSnapshot snap = build(file);

    assertThat(snap.tables()).isEmpty();
    assertThat(snap.paragraphs()).hasSize(2);
    for (int i = 0; i < snap.paragraphs().size(); i++) {
      assertThat(snap.paragraphs().get(i).index()).isEqualTo(i);
      assertThat(snap.paragraphs().get(i).bodyIndex()).isEqualTo(i);
    }
  }

  @Test
  void multipleTablesInterleaved() throws Exception {
    // body 顺序: [表格0, 段落A, 表格1, 段落B]
    Path file = tmp.resolve("multi-table.docx");
    try (Document doc = Docx.create()) {
      doc.addTable().addRow().addCell().addParagraph().addRun("表0");
      doc.addParagraph("段落A");
      doc.addTable().addRow().addCell().addParagraph().addRun("表1");
      doc.addParagraph("段落B");
      doc.save(file);
    }
    DocumentSnapshot snap = build(file);

    // 表格0: index=0, bodyIndex=0
    assertThat(snap.tables().get(0).index()).isEqualTo(0);
    assertThat(snap.tables().get(0).bodyIndex()).isEqualTo(0);

    // 段落A: index=0, bodyIndex=1
    assertThat(snap.paragraphs().get(0).index()).isEqualTo(0);
    assertThat(snap.paragraphs().get(0).bodyIndex()).isEqualTo(1);

    // 表格1: index=1, bodyIndex=2
    assertThat(snap.tables().get(1).index()).isEqualTo(1);
    assertThat(snap.tables().get(1).bodyIndex()).isEqualTo(2);

    // 段落B: index=1, bodyIndex=3
    assertThat(snap.paragraphs().get(1).index()).isEqualTo(1);
    assertThat(snap.paragraphs().get(1).bodyIndex()).isEqualTo(3);
  }

  private DocumentSnapshot build(Path file) {
    try (Document doc = Docx.open(file)) {
      return builder.build(doc, "test-conv", file, 1L);
    }
  }

  @TempDir Path tmp;
}
