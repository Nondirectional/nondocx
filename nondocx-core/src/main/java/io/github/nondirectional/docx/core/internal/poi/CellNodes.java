package io.github.nondirectional.docx.core.internal.poi;

import io.github.nondirectional.docx.core.api.style.VerticalAlign;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTVerticalJc;

/**
 * 内部 API — 如有变更，恕不另行通知。
 *
 * <p>单元格垂直对齐({@code <w:tcPr>/<w:vAlign>})的读写桥接。
 *
 * <p><b>POI 5.2.5 schema 实情</b>:{@code CTTcPr} 继承自 {@code CTTcPrBase},后者提供完整的 typed {@code vAlign}
 * 访问器({@code isSetVAlign()} / {@code getVAlign()} / {@code addNewVAlign()} / {@code
 * unsetVAlign()})。 故本类全程走 typed accessor,无需 XmlCursor。
 */
public final class CellNodes {

  private CellNodes() {}

  /**
   * 设置单元格的垂直对齐。若已有 {@code <w:vAlign>} 则覆盖其 val,否则新建。
   *
   * @param tc 单元格底层 {@code CTTc}(不能为 {@code null})
   * @param align 垂直对齐(不能为 {@code null})
   */
  public static void applyVerticalAlign(CTTc tc, VerticalAlign align) {
    java.util.Objects.requireNonNull(tc, "tc");
    java.util.Objects.requireNonNull(align, "align");
    CTTcPr tcPr = tc.isSetTcPr() ? tc.getTcPr() : tc.addNewTcPr();
    CTVerticalJc vAlign = tcPr.isSetVAlign() ? tcPr.getVAlign() : tcPr.addNewVAlign();
    vAlign.setVal(Mappers.toPoi(align));
  }

  /**
   * 读取单元格的垂直对齐。
   *
   * @param tc 单元格底层 {@code CTTc}(不能为 {@code null})
   * @return 垂直对齐;未设则返回 {@code null}(OOXML 实际默认是 {@link VerticalAlign#TOP})
   */
  public static VerticalAlign readVerticalAlign(CTTc tc) {
    java.util.Objects.requireNonNull(tc, "tc");
    if (!tc.isSetTcPr() || !tc.getTcPr().isSetVAlign()) {
      return null;
    }
    return Mappers.fromPoi(tc.getTcPr().getVAlign().getVal());
  }
}
