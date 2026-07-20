package io.github.nondirectional.docx.core.api.table;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.style.Shading;
import io.github.nondirectional.docx.core.api.style.ShadingPattern;
import io.github.nondirectional.docx.core.api.style.VerticalAlign;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTbl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STShd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STVerticalJc;

/**
 * 验证 {@link Cell} 的底纹（shading）与垂直对齐（verticalAlign）API:往返保真、live 视图、内容相等性、 以及 WPS/Word 兼容性默认（{@code
 * w:val="clear"} 强制、SOLID 不暴露）。
 *
 * <p>这是 WPS/Word 兼容性 spec（{@code renderer-compatibility.md#shading-solid} / {@code
 * #exact-row-valign}）的 load-bearing 测试。
 */
class CellShadingVerticalAlignTest {

  /** 取文档第一个表格第一行第一个单元格的底层 {@code CTTc}(用于 XML 级交叉验证)。 */
  private static CTTc firstTc(Document doc) {
    CTTbl tbl = doc.raw().getDocument().getBody().getTblArray(0);
    CTRow tr = tbl.getTrArray(0);
    return tr.getTcArray(0);
  }

  // ---------- shading: WPS-default 强制 CLEAR ----------

  @Test
  void singleArgShadingForcesClearPatternXmlLevel(@TempDir Path tmp) throws Exception {
    // WPS 兼容性默认:cell.shading("F1F5F9") 必须产出 w:val="clear",永不产出 solid
    Document original = Docx.create();
    Cell cell = original.addTable().addRow().addCell().text("x");
    cell.shading("F1F5F9");

    // XML 级断言:val="clear",fill="F1F5F9"
    // 注意:getFill() 返回 byte[](XmlBeans 把 hex 存为字节数组),用 xgetFill().getStringValue() 拿原始字符串
    CTTc tc = firstTc(original);
    assertThat(tc.isSetTcPr()).isTrue();
    assertThat(tc.getTcPr().isSetShd()).isTrue();
    assertThat(tc.getTcPr().getShd().getVal()).isEqualTo(STShd.CLEAR);
    assertThat(tc.getTcPr().getShd().xgetFill().getStringValue()).isEqualTo("F1F5F9");
  }

  @Test
  void shadingOverwritesExistingShading() {
    Document doc = Docx.create();
    Cell cell = doc.addTable().addRow().addCell().text("x");
    cell.shading("F1F5F9");
    cell.shading("EEEEEE");

    Shading read = cell.shading();
    assertThat(read.fill()).isEqualTo("EEEEEE");
    assertThat(read.pattern()).isEqualTo(ShadingPattern.CLEAR);
  }

  @Test
  void shadingWithExplicitNilPattern() {
    Document doc = Docx.create();
    Cell cell = doc.addTable().addRow().addCell().text("x");
    cell.shading(Shading.of("F1F5F9", ShadingPattern.NIL));

    CTTc tc = firstTc(doc);
    assertThat(tc.getTcPr().getShd().getVal()).isEqualTo(STShd.NIL);
  }

  @Test
  void shadingRejectsNull() {
    Document doc = Docx.create();
    Cell cell = doc.addTable().addRow().addCell();
    assertThatThrownBy(() -> cell.shading((String) null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> cell.shading((Shading) null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---------- shading: 往返保真 ----------

  @Test
  void shadingRoundTrips(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("shading.docx");
    Document original = Docx.create();
    Cell cell = original.addTable().addRow().addCell().text("x");
    cell.shading("F1F5F9");

    original.save(file);
    try (Document opened = Docx.open(file)) {
      Cell openedCell = opened.tables().get(0).row(0).cell(0);
      Shading read = openedCell.shading();
      assertThat(read).isNotNull();
      assertThat(read.fill()).isEqualTo("F1F5F9");
      assertThat(read.pattern()).isEqualTo(ShadingPattern.CLEAR);
      // 内容相等性:含 shading 的 cell 相等
      assertThat(openedCell).isEqualTo(cell);
    }
  }

  @Test
  void removeShadingClearsIt(@TempDir Path tmp) throws Exception {
    Document doc = Docx.create();
    Cell cell = doc.addTable().addRow().addCell().text("x");
    cell.shading("F1F5F9");
    assertThat(cell.shading()).isNotNull();

    cell.removeShading();
    assertThat(cell.shading()).isNull();

    // XML 级:shd 已被 unset
    CTTc tc = firstTc(doc);
    assertThat(tc.getTcPr().isSetShd()).isFalse();
  }

  @Test
  void removeShadingIsNoOpWhenUnset() {
    Document doc = Docx.create();
    Cell cell = doc.addTable().addRow().addCell();
    assertThat(cell.shading()).isNull();
    cell.removeShading(); // 不抛异常
    assertThat(cell.shading()).isNull();
  }

  // ---------- shading: 读侧归并 SOLID 为安全值 ----------

  @Test
  void readingSolidPatternMergesToSafeValue() {
    // 即使用户走 raw() 写了 SOLID,nondocx 读侧也不暴露它(归并 NIL)
    Document doc = Docx.create();
    Cell cell = doc.addTable().addRow().addCell().text("x");
    CTTc tc = firstTc(doc);
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd shd =
        tc.isSetTcPr() ? tc.getTcPr().getShd() : tc.addNewTcPr().addNewShd();
    shd.setVal(STShd.SOLID);
    shd.setFill("FF0000");

    Shading read = cell.shading();
    // SOLID 归并为 NIL(跨引擎安全),fill 保留
    assertThat(read.pattern()).isEqualTo(ShadingPattern.NIL);
    assertThat(read.fill()).isEqualTo("FF0000");
  }

  // ---------- shading: 内容相等性 ----------

  @Test
  void equalsIncludesShading() {
    Document docA = Docx.create();
    Document docB = Docx.create();
    Cell a = docA.addTable().addRow().addCell().text("x");
    Cell b = docB.addTable().addRow().addCell().text("x");
    assertThat(a).isEqualTo(b); // 都无 shading

    a.shading("F1F5F9");
    assertThat(a).isNotEqualTo(b); // 加了 shading 后不等

    b.shading("F1F5F9");
    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b); // 同 shading 再相等
  }

  // ---------- verticalAlign ----------

  @Test
  void verticalAlignSetsXmlVal() {
    Document doc = Docx.create();
    Cell cell = doc.addTable().addRow().addCell().text("x");
    cell.verticalAlign(VerticalAlign.CENTER);

    CTTc tc = firstTc(doc);
    assertThat(tc.getTcPr().isSetVAlign()).isTrue();
    assertThat(tc.getTcPr().getVAlign().getVal()).isEqualTo(STVerticalJc.CENTER);
  }

  @Test
  void verticalAlignReadsBack() {
    Document doc = Docx.create();
    Cell cell = doc.addTable().addRow().addCell().text("x");
    assertThat(cell.verticalAlign()).isNull(); // 未设

    cell.verticalAlign(VerticalAlign.BOTTOM);
    assertThat(cell.verticalAlign()).isEqualTo(VerticalAlign.BOTTOM);

    cell.verticalAlign(VerticalAlign.TOP);
    assertThat(cell.verticalAlign()).isEqualTo(VerticalAlign.TOP);
  }

  @Test
  void verticalAlignRejectsNull() {
    Document doc = Docx.create();
    Cell cell = doc.addTable().addRow().addCell();
    assertThatThrownBy(() -> cell.verticalAlign(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void verticalAlignRoundTrips(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("valign.docx");
    Document original = Docx.create();
    original.addTable().addRow().addCell().text("x").verticalAlign(VerticalAlign.CENTER);

    original.save(file);
    try (Document opened = Docx.open(file)) {
      Cell openedCell = opened.tables().get(0).row(0).cell(0);
      assertThat(openedCell.verticalAlign()).isEqualTo(VerticalAlign.CENTER);
    }
  }

  @Test
  void equalsIncludesVerticalAlign() {
    Document docA = Docx.create();
    Document docB = Docx.create();
    Cell a = docA.addTable().addRow().addCell().text("x");
    Cell b = docB.addTable().addRow().addCell().text("x");
    assertThat(a).isEqualTo(b);

    a.verticalAlign(VerticalAlign.CENTER);
    assertThat(a).isNotEqualTo(b);

    b.verticalAlign(VerticalAlign.CENTER);
    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
  }
}
