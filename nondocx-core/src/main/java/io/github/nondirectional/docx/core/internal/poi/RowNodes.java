package io.github.nondirectional.docx.core.internal.poi;

import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTrPr;

/**
 * 内部 API — 如有变更，恕不另行通知。
 *
 * <p>表格行属性（{@code <w:trPr>} 内的 {@code <w:tblHeader>} 与 {@code <w:cantSplit>}）的读写桥接。
 * 这两个属性控制表格的<b>分页行为</b>：
 *
 * <ul>
 *   <li>{@code <w:tblHeader>}：标记此行为「表头行」，跨页时在各页顶部重复显示。
 *   <li>{@code <w:cantSplit>}：禁止此行跨页拆分（行内容保持在同一页）。
 * </ul>
 *
 * <p><b>POI 5.2.5 schema 实情</b>：{@code CTTrPr}（行属性）继承自 {@code CTTrPrBase}，后者提供完整的 typed {@code
 * tblHeader} / {@code cantSplit} 访问器（{@code addNewTblHeader()} / {@code sizeOfTblHeaderArray()} /
 * {@code removeTblHeader(int)} 等）。{@code CTOnOff} 的 {@code setVal(Object)} 接受 {@code Boolean}。 全程
 * typed accessor，无需 XmlCursor。
 */
public final class RowNodes {

  private RowNodes() {}

  /**
   * 设置此行的「表头行」标记。{@code on=true} 时新建/覆盖 {@code <w:tblHeader>}；{@code on=false} 时移除。
   *
   * @param row 行的底层 {@code CTRow}（不能为 {@code null}）
   * @param on 是否标记为表头行
   */
  public static void applyHeaderRow(CTRow row, boolean on) {
    java.util.Objects.requireNonNull(row, "row");
    CTTrPr trPr = row.isSetTrPr() ? row.getTrPr() : row.addNewTrPr();
    if (on) {
      // 若已存在则不重复添加
      if (trPr.sizeOfTblHeaderArray() == 0) {
        trPr.addNewTblHeader().setVal(Boolean.TRUE);
      }
    } else {
      while (trPr.sizeOfTblHeaderArray() > 0) {
        trPr.removeTblHeader(0);
      }
    }
  }

  /**
   * 读取此行是否标记为「表头行」。
   *
   * @param row 行的底层 {@code CTRow}（不能为 {@code null}）
   * @return 若存在 {@code <w:tblHeader>} 则返回 {@code true}；否则 {@code false}
   */
  public static boolean readHeaderRow(CTRow row) {
    java.util.Objects.requireNonNull(row, "row");
    return row.isSetTrPr() && row.getTrPr().sizeOfTblHeaderArray() > 0;
  }

  /**
   * 设置此行的「禁止跨页拆分」标记。{@code on=true} 时新建/覆盖 {@code <w:cantSplit>}；{@code on=false} 时移除。
   *
   * @param row 行的底层 {@code CTRow}（不能为 {@code null}）
   * @param on 是否禁止跨页拆分
   */
  public static void applyCantSplit(CTRow row, boolean on) {
    java.util.Objects.requireNonNull(row, "row");
    CTTrPr trPr = row.isSetTrPr() ? row.getTrPr() : row.addNewTrPr();
    if (on) {
      if (trPr.sizeOfCantSplitArray() == 0) {
        trPr.addNewCantSplit().setVal(Boolean.TRUE);
      }
    } else {
      while (trPr.sizeOfCantSplitArray() > 0) {
        trPr.removeCantSplit(0);
      }
    }
  }

  /**
   * 读取此行是否标记为「禁止跨页拆分」。
   *
   * @param row 行的底层 {@code CTRow}（不能为 {@code null}）
   * @return 若存在 {@code <w:cantSplit>} 则返回 {@code true}；否则 {@code false}
   */
  public static boolean readCantSplit(CTRow row) {
    java.util.Objects.requireNonNull(row, "row");
    return row.isSetTrPr() && row.getTrPr().sizeOfCantSplitArray() > 0;
  }
}
