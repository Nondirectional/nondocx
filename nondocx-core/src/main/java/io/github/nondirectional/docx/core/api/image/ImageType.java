package io.github.nondirectional.docx.core.api.image;

/**
 * 内联图片的图像格式。
 *
 * <p>这是一个无 POI 依赖的值对象：它不携带任何 {@code org.apache.poi.*} 依赖。与 Apache POI 的 {@code PictureType} 的映射位于内部
 * POI 桥接层（{@code io.github.nondirectional.docx.core.internal.poi.Mappers}），因此公共枚举在源代码级别保持无 POI 依赖，
 * 而不仅仅是在签名级别。
 *
 * <p>MVP 建模了四种最常见的内联图片格式。较罕见的格式（BMP、EMF、WMF 等）未被建模； 此类格式的嵌入图片仍然可以通过 {@link Image#raw()} 读取， 并从
 * {@link Image#type()} 返回 {@code null}。
 */
public enum ImageType {
  /** PNG（便携式网络图形）。 */
  PNG,
  /** JPEG（联合图像专家组），包括 CMYK 变体。 */
  JPEG,
  /** GIF（图形交换格式）。 */
  GIF,
  /** TIFF（标签图像文件格式）。 */
  TIFF
}
