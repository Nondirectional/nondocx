package io.github.nondirectional.docx.core.api.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.style.Shading;
import io.github.nondirectional.docx.core.api.style.ShadingPattern;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STShd;

/**
 * 验证 {@link Paragraph} 的底纹（shading）API:与 {@code Cell.shading(...)} 对称。 往返保真、WPS-default 强制
 * CLEAR、SOLID 读侧归并、内容相等性含 shading。
 */
class ParagraphShadingTest {

  /** 取文档第一个段落（body 第一段）的底层 {@code CTP}。 */
  private static CTP firstParagraphCt(Document doc) {
    return doc.raw().getDocument().getBody().getPArray(0);
  }

  @Test
  void singleArgShadingForcesClearPatternXmlLevel() {
    Document doc = Docx.create();
    Paragraph p = doc.addParagraph();
    p.addRun("x");
    p.shading("F1F5F9");

    CTP ctP = firstParagraphCt(doc);
    assertThat(ctP.isSetPPr()).isTrue();
    assertThat(ctP.getPPr().isSetShd()).isTrue();
    assertThat(ctP.getPPr().getShd().getVal()).isEqualTo(STShd.CLEAR);
    // 注意:getFill() 返回 byte[];用 xgetFill().getStringValue() 拿原始十六进制字符串
    assertThat(ctP.getPPr().getShd().xgetFill().getStringValue()).isEqualTo("F1F5F9");
  }

  @Test
  void shadingOverwritesExisting() {
    Document doc = Docx.create();
    Paragraph p = doc.addParagraph();
    p.addRun("x");
    p.shading("F1F5F9");
    p.shading("EEEEEE");
    assertThat(p.shading().fill()).isEqualTo("EEEEEE");
  }

  @Test
  void shadingWithExplicitNilPattern() {
    Document doc = Docx.create();
    Paragraph p = doc.addParagraph();
    p.addRun("x");
    p.shading(Shading.of("F1F5F9", ShadingPattern.NIL));
    CTP ctP = firstParagraphCt(doc);
    assertThat(ctP.getPPr().getShd().getVal()).isEqualTo(STShd.NIL);
  }

  @Test
  void shadingRejectsNull() {
    Document doc = Docx.create();
    Paragraph p = doc.addParagraph();
    assertThatThrownBy(() -> p.shading((String) null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> p.shading((Shading) null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shadingRoundTrips(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("pshading.docx");
    Document original = Docx.create();
    Paragraph p = original.addParagraph();
    p.addRun("x");
    p.shading("F1F5F9");

    original.save(file);
    try (Document opened = Docx.open(file)) {
      Paragraph openedP = opened.paragraphs().get(0);
      Shading read = openedP.shading();
      assertThat(read).isNotNull();
      assertThat(read.fill()).isEqualTo("F1F5F9");
      assertThat(read.pattern()).isEqualTo(ShadingPattern.CLEAR);
      assertThat(openedP).isEqualTo(p);
    }
  }

  @Test
  void removeShadingClearsIt() {
    Document doc = Docx.create();
    Paragraph p = doc.addParagraph();
    p.addRun("x");
    p.shading("F1F5F9");
    assertThat(p.shading()).isNotNull();

    p.removeShading();
    assertThat(p.shading()).isNull();
    CTP ctP = firstParagraphCt(doc);
    assertThat(ctP.getPPr().isSetShd()).isFalse();
  }

  @Test
  void readingSolidPatternMergesToSafeValue() {
    Document doc = Docx.create();
    Paragraph p = doc.addParagraph();
    p.addRun("x");
    CTP ctP = firstParagraphCt(doc);
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd shd =
        ctP.isSetPPr() ? ctP.getPPr().getShd() : ctP.addNewPPr().addNewShd();
    shd.setVal(STShd.SOLID);
    shd.setFill("FF0000");

    Shading read = p.shading();
    assertThat(read.pattern()).isEqualTo(ShadingPattern.NIL);
    assertThat(read.fill()).isEqualTo("FF0000");
  }

  @Test
  void equalsIncludesShading() {
    Document docA = Docx.create();
    Document docB = Docx.create();
    Paragraph a = docA.addParagraph();
    a.addRun("x");
    Paragraph b = docB.addParagraph();
    b.addRun("x");
    assertThat(a).isEqualTo(b);

    a.shading("F1F5F9");
    assertThat(a).isNotEqualTo(b);

    b.shading("F1F5F9");
    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
  }
}
