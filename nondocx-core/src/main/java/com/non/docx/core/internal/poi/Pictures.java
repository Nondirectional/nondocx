package com.non.docx.core.internal.poi;

import org.apache.poi.common.usermodel.PictureType;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.openxmlformats.schemas.drawingml.x2006.main.CTPositiveSize2D;
import org.openxmlformats.schemas.drawingml.x2006.main.CTShapeProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTransform2D;
import org.openxmlformats.schemas.drawingml.x2006.picture.CTPicture;

/**
 * 内部 API——恕不另行通知即可更改。
 *
 * <p>从 Apache POI {@link XWPFPicture} 中读取几何数据和负载，以便公有 {@code Image} 类型 在源代码层面保持无 POI / XmlBeans 导入。
 *
 * <p>尺寸从图片的绘图范围读取，Apache POI 以 EMU 存储，并使用 {@link Units#EMU_PER_PIXEL} 转换为像素（96&nbsp;DPI 下每像素 9525
 * EMU）。这正是 {@code XWPFRun.addPicture} 写入的 逆运算（它将像素输入乘以 {@code EMU_PER_PIXEL}），因此像素值在保存→打开往返中
 * 无舍入错误地存活。
 */
public final class Pictures {

  private Pictures() {}

  /**
   * 返回图片的宽度（像素，96&nbsp;DPI），如果没有存储范围则返回 {@code 0}。
   *
   * @param picture POI 图片（不能为 {@code null}）
   * @return 宽度（像素），或 {@code 0}
   */
  public static int widthPixels(XWPFPicture picture) {
    return emuToPixels(extent(picture, true));
  }

  /**
   * 返回图片的高度（像素，96&nbsp;DPI），如果没有存储范围则返回 {@code 0}。
   *
   * @param picture POI 图片（不能为 {@code null}）
   * @return 高度（像素），或 {@code 0}
   */
  public static int heightPixels(XWPFPicture picture) {
    return emuToPixels(extent(picture, false));
  }

  /**
   * 返回图片的存储格式作为 POI {@link PictureType}，如果图片没有数据部分则返回 {@code null}。
   *
   * @param picture POI 图片（不能为 {@code null}）
   * @return POI 图片类型，或 {@code null}
   */
  public static PictureType pictureTypeOf(XWPFPicture picture) {
    XWPFPictureData data = picture.getPictureData();
    return data == null ? null : data.getPictureTypeEnum();
  }

  /**
   * 返回原始图片字节，如果图片没有数据部分则返回空数组。
   *
   * @param picture POI 图片（不能为 {@code null}）
   * @return 图片字节（从不 {@code null}）
   */
  public static byte[] bytesOf(XWPFPicture picture) {
    XWPFPictureData data = picture.getPictureData();
    return data == null ? new byte[0] : data.getData();
  }

  /**
   * 将像素尺寸（96&nbsp;DPI）转换为 EMU——Apache POI 的 {@code addPicture} 在图片范围上 存储的单位。这是 {@link #widthPixels}
   * / {@link #heightPixels} 的精确逆运算： {@code emuFromPixels(p)} × 读回得到未改变的 {@code p}。
   *
   * <p>Apache POI 的 {@code XWPFRun.addPicture(..., int width, int height)} 将宽度/高度 视为
   * EMU（它直接将它们原样存储在 {@code <wp:extent>} 上），因此以像素工作的调用方 必须首先转换。
   *
   * @param pixels 像素值（96&nbsp;DPI）
   * @return 等效的 EMU 值
   */
  public static int emuFromPixels(int pixels) {
    return pixels * Units.EMU_PER_PIXEL;
  }

  /**
   * 读取图片的范围（{@code cx} 为宽度，{@code cy} 为高度），单位为 EMU。当绘图范围不存在时 返回 {@code 0} 而不是抛出异常，这样公有包装器的 {@code
   * equals} 在格式错误的图片上 永远不会抛出。
   */
  private static long extent(XWPFPicture picture, boolean width) {
    try {
      CTPicture ct = picture.getCTPicture();
      if (ct == null) {
        return 0L;
      }
      CTShapeProperties spPr = ct.getSpPr();
      if (spPr == null) {
        return 0L;
      }
      CTTransform2D xfrm = spPr.getXfrm();
      if (xfrm == null) {
        return 0L;
      }
      CTPositiveSize2D ext = xfrm.getExt();
      if (ext == null) {
        return 0L;
      }
      return width ? ext.getCx() : ext.getCy();
    } catch (RuntimeException e) {
      return 0L;
    }
  }

  private static int emuToPixels(long emu) {
    if (emu <= 0L) {
      return 0;
    }
    return (int) (emu / Units.EMU_PER_PIXEL);
  }
}
