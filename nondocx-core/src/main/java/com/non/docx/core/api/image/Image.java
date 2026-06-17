package com.non.docx.core.api.image;

import com.non.docx.core.api.InlineElement;
import com.non.docx.core.internal.poi.Mappers;
import com.non.docx.core.internal.poi.Pictures;
import com.non.docx.core.internal.util.Objects;
import java.util.Arrays;
import org.apache.poi.xwpf.usermodel.XWPFPicture;

/**
 * 内联图像 — 嵌入在段落运行内的图片。
 *
 * <p>持有 Apache POI {@code XWPFPicture} 委托，并在其上暴露活跃视图。读取 直接穿透到委托（以及底层的图片部分）；没有缓存快照。
 *
 * <p><b>尺寸。</b> {@link #width()} 和 {@link #height()} 返回图片的存储大小，以 96&nbsp;DPI 的 <em>像素</em> 为单位 — 与
 * Apache POI 的 {@code addPicture} 输入的 单位相同。它们从图片的绘图范围（以 EMU 存储）读回，并使用 POI 的 {@code EMU_PER_PIXEL}
 * 常量（9525）转换为像素，该常量正好是 {@code addPicture} 存储值的倒数，因此像素值在保存 → 打开往返中 不会丢失精度。
 *
 * <p><b>内联放置。</b> 在 OOXML 中，内联图片位于运行 <em>内部</em>（作为 绘图），而不是作为运行的兄弟元素。nondocx 将其建模为段落有序内联视图中的独立
 * {@link InlineElement}： 当运行携带嵌入图片时，该运行在 {@code Paragraph.inlineElements()} 中以 {@code Image}（而非
 * {@code com.non.docx.core.api.text.Run}）的 形式呈现。同时携带 <em>文本和</em> 图片的运行是 MVP 未完全建模的 边界情况 —
 * 在这种情况下文本部分不会单独呈现， 只能通过 {@code raw()} 访问。大多数文档以及 {@code Paragraph.addImage(...)}
 * 本身产生纯图像运行，因此实践中很少遇到这种情况。
 *
 * <p>内容相等性（{@code equals} / {@code hashCode}）比较图像类型、像素 尺寸和原始图片字节（逐字节比较以确保保真度），从不比较委托
 * 引用。这就是往返图像断言能检查保真度的原因。字节数组可能 很大；这对于测试夹具是可接受的，但意味着 {@code Image} 不适合作为 长期存在的 {@code HashMap}
 * 键，因为底层内容随时可能改变。
 */
public final class Image implements InlineElement {

  private final XWPFPicture delegate;

  /**
   * 封装给定的 POI 内联图片。
   *
   * <p>此构造函数是 {@link com.non.docx.core.api.text.Paragraph} 生成活跃图像包装器的内部接缝， 因此它有意接受 POI 类型。用户通常通过
   * {@code Paragraph.addImage(...)} 获取图像， 而不是直接构造它们。
   *
   * @param delegate 底层的 POI 图片（不能为 {@code null}）
   * @throws IllegalArgumentException 如果 {@code delegate} 为 {@code null}
   */
  public Image(XWPFPicture delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /**
   * 返回图像格式。
   *
   * <p>格式从嵌入的图片部分解析。nondocx 未建模的图片格式 （例如 BMP、EMF 或 WMF）报告为 {@code null}；在这种情况下，原始图片部分仍 可通过 {@link
   * #raw()} 访问。
   *
   * @return 图像类型，如果格式不是 nondocx 建模的类型则返回 {@code null}
   */
  public ImageType type() {
    return Mappers.fromPoi(Pictures.pictureTypeOf(delegate));
  }

  /**
   * 返回图片的宽度，以 96&nbsp;DPI 的像素为单位。
   *
   * @return 宽度（像素），如果没有存储范围则返回 {@code 0}
   */
  public int width() {
    return Pictures.widthPixels(delegate);
  }

  /**
   * 返回图片的高度，以 96&nbsp;DPI 的像素为单位。
   *
   * @return 高度（像素），如果没有存储范围则返回 {@code 0}
   */
  public int height() {
    return Pictures.heightPixels(delegate);
  }

  /**
   * 返回原始图片字节。
   *
   * @return 图片字节（可能为空，从不返回 {@code null}）
   */
  public byte[] bytes() {
    return Pictures.bytesOf(delegate);
  }

  /**
   * 返回底层的 POI 图片。
   *
   * <p>对返回对象的修改会立即影响文档。请谨慎使用。
   *
   * @return 底层的 {@code XWPFPicture} 实例（包装器生命周期内同一实例）
   */
  public XWPFPicture raw() {
    return delegate;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Image)) {
      return false;
    }
    Image that = (Image) o;
    return this.width() == that.width()
        && this.height() == that.height()
        && java.util.Objects.equals(this.type(), that.type())
        && Arrays.equals(this.bytes(), that.bytes());
  }

  @Override
  public int hashCode() {
    int result = java.util.Objects.hash(width(), height(), type());
    result = 31 * result + Arrays.hashCode(bytes());
    return result;
  }

  @Override
  public String toString() {
    return "Image{type="
        + type()
        + ", width="
        + width()
        + ", height="
        + height()
        + ", bytes="
        + bytes().length
        + '}';
  }
}
