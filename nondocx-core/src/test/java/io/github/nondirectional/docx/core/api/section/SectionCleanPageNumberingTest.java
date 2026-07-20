package io.github.nondirectional.docx.core.api.section;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;

/**
 * 验证 {@link Section#cleanEmptyPageNumbering()} 的 WPS 兼容性清理:裸 {@code <w:pgNumType/>}
 * 被清理、有属性的被保留、未设的无操作、往返后保持清理状态。
 *
 * <p>这是 WPS/Word 兼容性 spec({@code renderer-compatibility.md#empty-pgnumtype})的 load-bearing 测试。
 */
class SectionCleanPageNumberingTest {

  /** 取 body 上的 {@code CTSectPr}(用于注入原始 XML 验证清理行为);不存在则新建。 */
  private static CTSectPr bodySectPr(Document doc) {
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody body =
        doc.raw().getDocument().getBody();
    CTSectPr sectPr = body.getSectPr();
    return sectPr != null ? sectPr : body.addNewSectPr();
  }

  @Test
  void cleanEmptyPgNumTypeReturnsTrueAndRemovesIt() {
    Document doc = Docx.create();
    CTSectPr sectPr = bodySectPr(doc);
    sectPr.addNewPgNumType(); // 裸元素:无 w:start、无 w:fmt(POI 5.2.5 实测会写出)

    assertThat(sectPr.isSetPgNumType()).isTrue();
    boolean cleaned = doc.section(0).cleanEmptyPageNumbering();
    assertThat(cleaned).isTrue();
    assertThat(sectPr.isSetPgNumType()).isFalse();
  }

  @Test
  void keepPgNumTypeWithStartAttribute() {
    Document doc = Docx.create();
    CTSectPr sectPr = bodySectPr(doc);
    sectPr.addNewPgNumType().setStart(java.math.BigInteger.valueOf(3));

    boolean cleaned = doc.section(0).cleanEmptyPageNumbering();
    assertThat(cleaned).isFalse(); // 有 w:start → 保留
    assertThat(sectPr.isSetPgNumType()).isTrue();
    assertThat(sectPr.getPgNumType().getStart().intValue()).isEqualTo(3);
  }

  @Test
  void keepPgNumTypeWithFmtAttribute() {
    Document doc = Docx.create();
    CTSectPr sectPr = bodySectPr(doc);
    sectPr.addNewPgNumType().setFmt(STNumberFormat.LOWER_ROMAN);

    boolean cleaned = doc.section(0).cleanEmptyPageNumbering();
    assertThat(cleaned).isFalse(); // 有 w:fmt → 保留
    assertThat(sectPr.isSetPgNumType()).isTrue();
    assertThat(sectPr.getPgNumType().getFmt()).isEqualTo(STNumberFormat.LOWER_ROMAN);
  }

  @Test
  void noOpWhenPgNumTypeUnset() {
    Document doc = Docx.create();
    CTSectPr sectPr = bodySectPr(doc);
    assertThat(sectPr.isSetPgNumType()).isFalse();

    boolean cleaned = doc.section(0).cleanEmptyPageNumbering();
    assertThat(cleaned).isFalse();
    assertThat(sectPr.isSetPgNumType()).isFalse();
  }

  @Test
  void cleanedStateSurvivesRoundTrip(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("pgnum.docx");
    Document original = Docx.create();
    CTSectPr sectPr = bodySectPr(original);
    sectPr.addNewPgNumType(); // 裸元素
    assertThat(sectPr.isSetPgNumType()).isTrue();
    original.section(0).cleanEmptyPageNumbering();

    original.save(file);
    try (Document opened = Docx.open(file)) {
      // 清理后的状态往返后保持:不应出现空 pgNumType
      CTSectPr openedSectPr = opened.raw().getDocument().getBody().getSectPr();
      if (openedSectPr.isSetPgNumType()) {
        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageNumber pn =
            openedSectPr.getPgNumType();
        assertThat(pn.isSetStart() || pn.isSetFmt()).as("往返后不应残留空 pgNumType").isTrue();
      }
    }
  }
}
