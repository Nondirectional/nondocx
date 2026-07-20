package io.github.nondirectional.docx.core.internal.poi;

import java.util.ArrayList;
import java.util.List;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTbl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblGrid;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblGridCol;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblWidth;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblWidth;

/**
 * 内部 API — 如有变更，恕不另行通知。
 *
 * <p>表格列宽（{@code <w:tblGrid>/<w:gridCol>} + {@code <w:tblW>}）的读写桥接。
 *
 * <p><b>POI 5.2.5 schema 实情</b>:{@code CTTblGrid} 叶子接口被剥离,但继承自 {@code CTTblGridBase}, 后者提供完整的 typed
 * {@code gridCol} 访问器({@code addNewGridCol()} / {@code getGridColArray(int)} / {@code
 * sizeOfGridColArray()})。{@code CTTblWidth} 提供 typed {@code w}/{@code type} 访问器。故本类全程走 typed
 * accessor,无需 XmlCursor。
 *
 * <p><b>WPS/Word 兼容性</b>:百分比(PCT)路径在两个引擎行为一致;纯 DXA 在 WPS 触发 tblGrid bug (见 {@code
 * renderer-compatibility.md#table-width-dxa})。本类同时支持两条路径——调用方({@code Table})决定主推哪个。
 *
 * <p><b>PCT 单位换算</b>:OOXML 的 PCT 宽度值以「五十分之一百分比」编码({@code w:w="5000"} = 100%)。 本类的 {@code percents}
 * 参数以 0-100 的整数百分比传入,内部乘以 50 转换。
 */
public final class TableWidthNodes {

  private TableWidthNodes() {}

  /**
   * 按百分比设置表格列宽(主推路径,WPS 友好)。
   *
   * <p>同时写 {@code <w:tblGrid>} 内每个 {@code <w:gridCol w:w="...">}(PCT 单位)和 {@code <w:tblW
   * w:type="pct" w:w="...">}(列百分比之和)。PCT 值为五十分之一百分比({@code pct[i] * 50})。
   *
   * @param tbl 表格底层 {@code CTTbl}(不能为 {@code null})
   * @param percents 各列百分比(0-100 的整数;数组长度即列数;不能为 {@code null} 或空)
   */
  public static void applyColumnPercents(CTTbl tbl, int[] percents) {
    java.util.Objects.requireNonNull(tbl, "tbl");
    requireNonEmpty(percents);
    CTTblGrid grid = tbl.getTblGrid();
    if (grid == null) {
      grid = tbl.addNewTblGrid();
    }
    ensureGridColCount(grid, percents.length);
    int sumFiftieths = 0;
    for (int i = 0; i < percents.length; i++) {
      int fiftieths = percents[i] * 50;
      CTTblGridCol col = grid.getGridColArray(i);
      col.setW(java.math.BigInteger.valueOf(fiftieths));
      sumFiftieths += fiftieths;
    }
    setTableWidth(tbl, STTblWidth.PCT, java.math.BigInteger.valueOf(sumFiftieths));
  }

  /**
   * 按 twips(DXA)设置表格列宽(显式覆盖路径)。
   *
   * <p>同时写 {@code <w:tblGrid>} 内每个 {@code <w:gridCol w:w="...">}(DXA)和 {@code <w:tblW w:type="dxa"
   * w:w="...">}(列宽之和,单位 twips)。
   *
   * @param tbl 表格底层 {@code CTTbl}(不能为 {@code null})
   * @param dxa 各列宽度(twips;数组长度即列数;不能为 {@code null} 或空)
   */
  public static void applyColumnWidths(CTTbl tbl, int[] dxa) {
    java.util.Objects.requireNonNull(tbl, "tbl");
    requireNonEmpty(dxa);
    CTTblGrid grid = tbl.getTblGrid();
    if (grid == null) {
      grid = tbl.addNewTblGrid();
    }
    ensureGridColCount(grid, dxa.length);
    int sumDxa = 0;
    for (int i = 0; i < dxa.length; i++) {
      CTTblGridCol col = grid.getGridColArray(i);
      col.setW(java.math.BigInteger.valueOf(dxa[i]));
      sumDxa += dxa[i];
    }
    setTableWidth(tbl, STTblWidth.DXA, java.math.BigInteger.valueOf(sumDxa));
  }

  /**
   * 读取各列宽度(twips)。
   *
   * <p>返回 {@code <w:gridCol>} 的 {@code w:w} 列表。PCT 值按 1/50 换算回 twips 近似(乘以默认表格总宽 9026 twips / 100,即
   * A4 可用宽度的常见值);DXA 值原样返回。若 {@code tblGrid} 未设则返回空列表。
   *
   * @param tbl 表格底层 {@code CTTbl}(不能为 {@code null})
   * @return 各列 twips 宽度列表
   */
  public static List<Integer> readColumnWidths(CTTbl tbl) {
    java.util.Objects.requireNonNull(tbl, "tbl");
    List<Integer> widths = new ArrayList<>();
    CTTblGrid grid = tbl.getTblGrid();
    if (grid == null) {
      return widths;
    }
    int n = grid.sizeOfGridColArray();
    STTblWidth.Enum tableType = getTableWidthType(tbl);
    for (int i = 0; i < n; i++) {
      CTTblGridCol col = grid.getGridColArray(i);
      if (!col.isSetW()) {
        widths.add(0);
        continue;
      }
      long w = asLong(col.getW());
      if (tableType == STTblWidth.PCT) {
        // PCT: w 是五十分之一百分比,换算到 twips(按 A4 可用宽度 9026 twips 近似)
        widths.add((int) (w * 9026L / 5000L));
      } else {
        widths.add((int) w);
      }
    }
    return widths;
  }

  /** 调整 {@code tblGrid} 的 {@code gridCol} 数量到 {@code target},不足则补,多余则删。 */
  private static void ensureGridColCount(CTTblGrid grid, int target) {
    int current = grid.sizeOfGridColArray();
    while (current < target) {
      grid.addNewGridCol();
      current++;
    }
    while (current > target) {
      grid.removeGridCol(current - 1);
      current--;
    }
  }

  /** 设置 {@code <w:tblW>} 的 type 与 w。 */
  private static void setTableWidth(CTTbl tbl, STTblWidth.Enum type, java.math.BigInteger w) {
    CTTblPr tblPr = tbl.getTblPr();
    if (tblPr == null) {
      tblPr = tbl.addNewTblPr();
    }
    CTTblWidth tblW = tblPr.isSetTblW() ? tblPr.getTblW() : tblPr.addNewTblW();
    tblW.setType(type);
    tblW.setW(w);
  }

  /** 读 {@code <w:tblW>} 的 type,未设则返回 {@code AUTO}。 */
  private static STTblWidth.Enum getTableWidthType(CTTbl tbl) {
    CTTblPr tblPr = tbl.getTblPr();
    if (tblPr == null || !tblPr.isSetTblW()) {
      return STTblWidth.AUTO;
    }
    STTblWidth.Enum type = tblPr.getTblW().getType();
    return type != null ? type : STTblWidth.AUTO;
  }

  /** 把 {@code getW()} 返回的 {@code Object}(XmlBeans 数值)安全转为 {@code long}。 */
  private static long asLong(Object w) {
    if (w == null) {
      return 0L;
    }
    if (w instanceof java.lang.Number) {
      return ((java.lang.Number) w).longValue();
    }
    return Long.parseLong(w.toString());
  }

  private static void requireNonEmpty(int[] arr) {
    if (arr == null || arr.length == 0) {
      throw new IllegalArgumentException("列宽数组不能为 null 或空");
    }
  }
}
