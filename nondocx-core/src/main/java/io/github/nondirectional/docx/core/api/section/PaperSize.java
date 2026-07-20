package io.github.nondirectional.docx.core.api.section;

/**
 * 常见纸张尺寸，每种尺寸携带其<em>纵向</em>尺寸（以缇为单位，1/20 点，每英寸 1440 缇）。
 *
 * <p>尺寸以纵向方向存储（宽度 &lt; 高度）。当节使用横向方向时，存储的 {@code <w:pgSz>} 宽度/高度会由 {@link
 * Section#orientation(Orientation)} 交换；{@link #fromDimensions(int, int)}
 * 会归一化回纵向方向，以便无论方向如何都能恢复逻辑纸张尺寸。
 *
 * <p>这是一个无 POI 依赖的值对象：它不携带任何 {@code org.apache.poi.*} 依赖。
 */
public enum PaperSize {
  /** ISO A4（210 × 297 毫米）。 */
  A4(11906, 16838),
  /** US Letter（8.5 × 11 英寸）。 */
  LETTER(12240, 15840),
  /** US Legal（8.5 × 14 英寸）。 */
  LEGAL(12240, 20160),
  /** ISO A5（148 × 210 毫米）。 */
  A5(8391, 11906),
  /** JIS B5（182 × 257 毫米）。 */
  B5(10319, 14570),
  /** ISO A3（297 × 420 毫米）。 */
  A3(16838, 23811);

  private final int widthTwips;
  private final int heightTwips;

  PaperSize(int widthTwips, int heightTwips) {
    this.widthTwips = widthTwips;
    this.heightTwips = heightTwips;
  }

  /**
   * 返回纵向宽度（缇）。
   *
   * @return 纵向宽度（缇）
   */
  public int widthTwips() {
    return widthTwips;
  }

  /**
   * 返回纵向高度（缇）。
   *
   * @return 纵向高度（缇）
   */
  public int heightTwips() {
    return heightTwips;
  }

  /**
   * 从原始 {@code <w:pgSz>} 尺寸解析已知的纸张尺寸，如果尺寸与任何已知尺寸不匹配则返回 {@code null}。
   *
   * <p>比较与方向无关：输入在匹配前会归一化为纵向（较小的尺寸在前），因此无论节是纵向 还是横向，都能解析出相同的逻辑纸张尺寸。匹配是精确的；自定义或不常见的尺寸返回 {@code
   * null}。
   *
   * @param widthTwips 存储的页面宽度（缇）
   * @param heightTwips 存储的页面高度（缇）
   * @return 匹配的纸张尺寸，如果无匹配则为 {@code null}
   */
  public static PaperSize fromDimensions(int widthTwips, int heightTwips) {
    int portraitWidth = Math.min(widthTwips, heightTwips);
    int portraitHeight = Math.max(widthTwips, heightTwips);
    for (PaperSize size : values()) {
      if (size.widthTwips == portraitWidth && size.heightTwips == portraitHeight) {
        return size;
      }
    }
    return null;
  }
}
