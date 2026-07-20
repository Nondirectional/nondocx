package io.github.nondirectional.docx.core.internal.poi;

import java.math.BigInteger;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTbl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblBorders;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;

/** 内部 API — 表格级边框与单元格合并的 OOXML 桥接。 */
public final class TableNodes {

  private TableNodes() {}

  /**
   * 显式把表格六类边框写为 {@code nil}。
   *
   * <p>OOXML: {@code <w:tblPr><w:tblBorders><w:top w:val="nil"/>...}。这里同时写
   * top/left/bottom/right/start/end/insideH/insideV，兼容新旧方向属性。
   */
  public static void applyNoBorders(CTTbl tbl) {
    java.util.Objects.requireNonNull(tbl, "tbl");
    CTTblPr pr = tbl.getTblPr();
    if (pr == null) {
      pr = tbl.addNewTblPr();
    }
    CTTblBorders borders = pr.isSetTblBorders() ? pr.getTblBorders() : pr.addNewTblBorders();
    setNil(borders.isSetTop() ? borders.getTop() : borders.addNewTop());
    setNil(borders.isSetLeft() ? borders.getLeft() : borders.addNewLeft());
    setNil(borders.isSetBottom() ? borders.getBottom() : borders.addNewBottom());
    setNil(borders.isSetRight() ? borders.getRight() : borders.addNewRight());
    setNil(borders.isSetStart() ? borders.getStart() : borders.addNewStart());
    setNil(borders.isSetEnd() ? borders.getEnd() : borders.addNewEnd());
    setNil(borders.isSetInsideH() ? borders.getInsideH() : borders.addNewInsideH());
    setNil(borders.isSetInsideV() ? borders.getInsideV() : borders.addNewInsideV());
  }

  private static void setNil(CTBorder border) {
    border.setVal(STBorder.NIL);
  }

  /** 设置水平合并起始单元格的 {@code gridSpan}。 */
  public static void applyGridSpan(CTTc tc, int span) {
    java.util.Objects.requireNonNull(tc, "tc");
    if (span < 2) {
      throw new IllegalArgumentException("span 必须 >= 2");
    }
    CTTcPr pr = tc.isSetTcPr() ? tc.getTcPr() : tc.addNewTcPr();
    (pr.isSetGridSpan() ? pr.getGridSpan() : pr.addNewGridSpan()).setVal(BigInteger.valueOf(span));
  }

  /** 设置纵向合并标记。 */
  public static void applyVMerge(CTTc tc, boolean restart) {
    java.util.Objects.requireNonNull(tc, "tc");
    CTTcPr pr = tc.isSetTcPr() ? tc.getTcPr() : tc.addNewTcPr();
    (pr.isSetVMerge() ? pr.getVMerge() : pr.addNewVMerge())
        .setVal(restart ? STMerge.RESTART : STMerge.CONTINUE);
  }
}
