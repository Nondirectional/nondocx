package io.github.nondirectional.docx.core.internal.poi;

import io.github.nondirectional.docx.core.api.style.Shading;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STShd;

/**
 * 内部 API — 如有变更，恕不另行通知。
 *
 * <p>底纹（{@code <w:shd>}）节点的读写桥接，统一单元格（{@code <w:tcPr>/<w:shd>}）与 段落（{@code <w:pPr>/<w:shd>}）两条路径。
 *
 * <p><b>POI 5.2.5 schema 实情</b>：{@code CTTcPr} 与 {@code CTPPr} 在精简 jar 的叶子接口上看似被剥离了底纹访问器， 但它们分别继承自
 * {@code CTTcPrBase} / {@code CTPPrBase}，后者提供完整的 typed 底纹访问器 （{@code isSetShd()} / {@code getShd()}
 * / {@code addNewShd()} / {@code unsetShd()}）。因此本类<b>无需 XmlCursor</b>， 全程走 typed accessor。
 *
 * <p><b>WPS/Word 兼容性</b>：写路径只产出 {@link STShd#CLEAR} 或 {@link STShd#NIL}，<b>永不产出 {@code SOLID}</b>
 * （WPS 渲染为黑块，见 {@code renderer-compatibility.md#shading-solid}）。{@link Shading} 的 {@code
 * ShadingPattern} 枚举本身已排除 SOLID，本类只是不绕过它。
 */
public final class ShadingNodes {

  private ShadingNodes() {}

  /**
   * 把给定的底纹写入单元格属性。若已有 {@code <w:shd>} 则覆盖其字段，否则新建。
   *
   * @param tc 单元格的底层 {@code CTTc}（不能为 {@code null}）
   * @param shading 底纹值对象（不能为 {@code null}）
   */
  public static void applyToCell(CTTc tc, Shading shading) {
    java.util.Objects.requireNonNull(tc, "tc");
    java.util.Objects.requireNonNull(shading, "shading");
    CTTcPr tcPr = tc.isSetTcPr() ? tc.getTcPr() : tc.addNewTcPr();
    CTShd shd = tcPr.isSetShd() ? tcPr.getShd() : tcPr.addNewShd();
    writeShd(shd, shading);
  }

  /**
   * 把给定的底纹写入段落属性。若已有 {@code <w:shd>} 则覆盖其字段，否则新建。
   *
   * @param p 段落的底层 {@code CTP}（不能为 {@code null}）
   * @param shading 底纹值对象（不能为 {@code null}）
   */
  public static void applyToParagraph(CTP p, Shading shading) {
    java.util.Objects.requireNonNull(p, "p");
    java.util.Objects.requireNonNull(shading, "shading");
    CTPPr pPr = p.isSetPPr() ? p.getPPr() : p.addNewPPr();
    CTShd shd = pPr.isSetShd() ? pPr.getShd() : pPr.addNewShd();
    writeShd(shd, shading);
  }

  /**
   * 读取单元格的底纹。
   *
   * @param tc 单元格的底层 {@code CTTc}（不能为 {@code null}）
   * @return 底纹值对象，若未设底纹则为 {@code null}
   */
  public static Shading readFromCell(CTTc tc) {
    java.util.Objects.requireNonNull(tc, "tc");
    if (!tc.isSetTcPr()) {
      return null;
    }
    CTTcPr tcPr = tc.getTcPr();
    if (!tcPr.isSetShd()) {
      return null;
    }
    return readShd(tcPr.getShd());
  }

  /**
   * 读取段落的底纹。
   *
   * @param p 段落的底层 {@code CTP}（不能为 {@code null}）
   * @return 底纹值对象，若未设底纹则为 {@code null}
   */
  public static Shading readFromParagraph(CTP p) {
    java.util.Objects.requireNonNull(p, "p");
    if (!p.isSetPPr()) {
      return null;
    }
    CTPPr pPr = p.getPPr();
    if (!pPr.isSetShd()) {
      return null;
    }
    return readShd(pPr.getShd());
  }

  /**
   * 移除单元格的底纹。若未设底纹则无操作。
   *
   * @param tc 单元格的底层 {@code CTTc}（不能为 {@code null}）
   */
  public static void removeFromCell(CTTc tc) {
    java.util.Objects.requireNonNull(tc, "tc");
    if (tc.isSetTcPr() && tc.getTcPr().isSetShd()) {
      tc.getTcPr().unsetShd();
    }
  }

  /**
   * 移除段落的底纹。若未设底纹则无操作。
   *
   * @param p 段落的底层 {@code CTP}（不能为 {@code null}）
   */
  public static void removeFromParagraph(CTP p) {
    java.util.Objects.requireNonNull(p, "p");
    if (p.isSetPPr() && p.getPPr().isSetShd()) {
      p.getPPr().unsetShd();
    }
  }

  /** 把 {@link Shading} 的三个字段写入 {@code CTShd}。 */
  private static void writeShd(CTShd shd, Shading shading) {
    shd.setVal(Mappers.toPoi(shading.pattern()));
    shd.setFill(shading.fill());
    if (shading.color() != null) {
      shd.setColor(shading.color());
    } else if (shd.isSetColor()) {
      shd.unsetColor();
    }
  }

  /** 从 {@code CTShd} 读出 {@link Shading}，三个字段归并到 nondocx 的安全枚举。 */
  private static Shading readShd(CTShd shd) {
    // 注意:XmlBeans 的 getFill()/getColor() 返回 byte[](hex 被存为字节数组),
    // 故用 xget*().getStringValue() 拿原始十六进制字符串。
    String fill = shd.isSetFill() ? shd.xgetFill().getStringValue() : null;
    io.github.nondirectional.docx.core.api.style.ShadingPattern pattern =
        Mappers.fromPoi(shd.getVal());
    String color = shd.isSetColor() ? shd.xgetColor().getStringValue() : null;
    if (fill == null && pattern == null && color == null) {
      return null;
    }
    // pattern 归并后可能为 null（未设），按 CLEAR 兜底——读侧不应返回 null pattern
    io.github.nondirectional.docx.core.api.style.ShadingPattern safePattern =
        pattern != null
            ? pattern
            : io.github.nondirectional.docx.core.api.style.ShadingPattern.CLEAR;
    String safeFill = fill != null ? fill : "auto";
    return Shading.of(safeFill, safePattern, color);
  }
}
