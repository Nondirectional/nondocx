package com.non.docx.core.internal.poi;

import com.non.docx.core.api.header.HeaderFooterVariant;
import com.non.docx.core.api.image.ImageType;
import com.non.docx.core.api.section.Orientation;
import com.non.docx.core.api.style.Alignment;
import com.non.docx.core.api.style.HeadingLevel;
import com.non.docx.core.api.style.ShadingPattern;
import com.non.docx.core.api.style.VerticalAlign;
import org.apache.poi.common.usermodel.PictureType;
import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STHdrFtr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STPageOrientation;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STShd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STVerticalJc;

/**
 * 内部 API——恕不另行通知即可更改。
 *
 * <p>nondocx 无 POI 值对象与 Apache POI 枚举之间的枚举映射桥接。映射所需的所有 {@code org.apache.poi.*} 导入都集中在此处，以便 {@code
 * com.non.docx.core.api.style} / {@code com.non.docx.core.api.image} 中的公有值对象在源代码层面（而不仅仅是签名层面） 保持无
 * POI 依赖。
 */
public final class Mappers {

  private Mappers() {}

  /**
   * 将 nondocx {@link Alignment} 映射到 Apache POI 的 {@link ParagraphAlignment}。
   *
   * @param alignment nondocx 对齐方式（不能为 {@code null}）
   * @return 对应的 POI 对齐方式
   */
  public static ParagraphAlignment toPoi(Alignment alignment) {
    if (alignment == null) {
      throw new IllegalArgumentException("alignment must not be null");
    }
    switch (alignment) {
      case LEFT:
        return ParagraphAlignment.LEFT;
      case CENTER:
        return ParagraphAlignment.CENTER;
      case RIGHT:
        return ParagraphAlignment.RIGHT;
      case JUSTIFY:
        return ParagraphAlignment.BOTH;
      default:
        throw new IllegalArgumentException("Unsupported alignment: " + alignment);
    }
  }

  /**
   * 将 Apache POI {@link ParagraphAlignment} 映射回 nondocx {@link Alignment}。
   *
   * <p>只有 nondocx 建模的四种对齐方式会被精确映射；较罕见的 POI 对齐方式 （例如 {@code DISTRIBUTE} 或 kashida 变体）在读取时归并为 {@link
   * Alignment#LEFT}， 以确保实际文档永远不会加载失败。
   *
   * @param alignment POI 对齐方式，如果未设置则为 {@code null}
   * @return 对应的 nondocx 对齐方式，如果输入为 {@code null} 则返回 {@code null}
   */
  public static Alignment fromPoi(ParagraphAlignment alignment) {
    if (alignment == null) {
      return null;
    }
    switch (alignment) {
      case CENTER:
        return Alignment.CENTER;
      case RIGHT:
      case END:
        return Alignment.RIGHT;
      case BOTH:
        return Alignment.JUSTIFY;
      case LEFT:
      case START:
      default:
        return Alignment.LEFT;
    }
  }

  /**
   * 将 nondocx {@link HeadingLevel} 映射到 Word / POI 使用的 OOXML 内置标题样式 ID （{@code "Heading1"} … {@code
   * "Heading6"}）。
   *
   * @param level 标题级别（不能为 {@code null}）
   * @return 对应的 OOXML 样式 ID（例如 {@code "Heading2"}）
   */
  public static String toStyleId(HeadingLevel level) {
    if (level == null) {
      throw new IllegalArgumentException("level must not be null");
    }
    return "Heading" + (level.ordinal() + 1);
  }

  /**
   * 将 OOXML 段落样式 ID 映射回 nondocx {@link HeadingLevel}。
   *
   * <p>只有六个内置标题样式 ID（{@code "Heading1"} … {@code "Heading6"}）会被识别； 所有其他样式（包括 {@code null}
   * 和非标题样式）映射为 {@code null}， 表示该段落不是标题。
   *
   * @param style OOXML 样式 ID，如果未设置则为 {@code null}
   * @return 匹配的标题级别，如果该段落不是标题则返回 {@code null}
   */
  public static HeadingLevel headingFromStyle(String style) {
    if (style == null) {
      return null;
    }
    switch (style) {
      case "Heading1":
        return HeadingLevel.H1;
      case "Heading2":
        return HeadingLevel.H2;
      case "Heading3":
        return HeadingLevel.H3;
      case "Heading4":
        return HeadingLevel.H4;
      case "Heading5":
        return HeadingLevel.H5;
      case "Heading6":
        return HeadingLevel.H6;
      default:
        return null;
    }
  }

  /**
   * 将 nondocx {@link Orientation} 映射到 OOXML 的 {@link STPageOrientation}。
   *
   * @param orientation nondocx 方向（不能为 {@code null}）
   * @return 对应的 OOXML 页面方向枚举
   */
  public static STPageOrientation.Enum toPoi(Orientation orientation) {
    if (orientation == null) {
      throw new IllegalArgumentException("orientation must not be null");
    }
    switch (orientation) {
      case PORTRAIT:
        return STPageOrientation.PORTRAIT;
      case LANDSCAPE:
        return STPageOrientation.LANDSCAPE;
      default:
        throw new IllegalArgumentException("Unsupported orientation: " + orientation);
    }
  }

  /**
   * 将 OOXML {@link STPageOrientation} 映射回 nondocx {@link Orientation}。
   *
   * <p>{@code null} 输入（意味着节未设置方向）映射为 {@code null}； 调用方在 {@code Section} 级别应用 Word 的默认值 {@link
   * Orientation#PORTRAIT}。
   *
   * @param orientation OOXML 方向，如果未设置则为 {@code null}
   * @return 对应的 nondocx 方向，如果输入为 {@code null} 则返回 {@code null}
   */
  public static Orientation fromPoi(STPageOrientation.Enum orientation) {
    if (orientation == null) {
      return null;
    }
    if (orientation == STPageOrientation.LANDSCAPE) {
      return Orientation.LANDSCAPE;
    }
    return Orientation.PORTRAIT;
  }

  /**
   * 将 nondocx {@link ImageType} 映射到 Apache POI 的 {@link PictureType}。
   *
   * @param type nondocx 图片类型（不能为 {@code null}）
   * @return 对应的 POI 图片类型
   */
  public static PictureType toPoi(ImageType type) {
    if (type == null) {
      throw new IllegalArgumentException("type must not be null");
    }
    switch (type) {
      case PNG:
        return PictureType.PNG;
      case JPEG:
        return PictureType.JPEG;
      case GIF:
        return PictureType.GIF;
      case TIFF:
        return PictureType.TIFF;
      default:
        throw new IllegalArgumentException("Unsupported image type: " + type);
    }
  }

  /**
   * 将 Apache POI {@link PictureType} 映射回 nondocx {@link ImageType}。
   *
   * <p>只有 nondocx 建模的四种图片格式会被表示。CMYK JPEG 归并为 {@link ImageType#JPEG}； 所有其他 POI
   * 图片类型（BMP、EMF、WMF……）映射为 {@code null}， 表示该图片类型在 nondocx 中不可表示。
   *
   * @param type POI 图片类型，如果不可用则为 {@code null}
   * @return 对应的 nondocx 图片类型，如果无法映射则返回 {@code null}
   */
  public static ImageType fromPoi(PictureType type) {
    if (type == null) {
      return null;
    }
    switch (type) {
      case JPEG:
      case CMYKJPEG:
        return ImageType.JPEG;
      case GIF:
        return ImageType.GIF;
      case TIFF:
        return ImageType.TIFF;
      case PNG:
        return ImageType.PNG;
      default:
        return null;
    }
  }

  /**
   * 将 nondocx {@link ShadingPattern} 映射到 OOXML {@link STShd.Enum}。
   *
   * <p>nondocx 的 {@link ShadingPattern} 故意只暴露跨引擎安全的图案（{@link ShadingPattern#CLEAR}、 {@link
   * ShadingPattern#NIL}），<b>不暴露 {@code SOLID}</b>（WPS 渲染为黑块，见 {@code
   * renderer-compatibility.md#shading-solid}）。本方法也只产出这两种安全值。
   *
   * @param pattern nondocx 底纹图案（不能为 {@code null}）
   * @return 对应的 OOXML 底纹图案枚举
   * @throws IllegalArgumentException 如果 {@code pattern} 为 {@code null}
   */
  public static STShd.Enum toPoi(ShadingPattern pattern) {
    if (pattern == null) {
      throw new IllegalArgumentException("pattern must not be null");
    }
    switch (pattern) {
      case CLEAR:
        return STShd.CLEAR;
      case NIL:
        return STShd.NIL;
      default:
        throw new IllegalArgumentException("Unsupported shading pattern: " + pattern);
    }
  }

  /**
   * 将 OOXML {@link STShd.Enum} 映射回 nondocx {@link ShadingPattern}。
   *
   * <p>{@code null} 输入（意味着未设底纹）映射为 {@code null}。{@code SOLID} 及所有未在 nondocx 建模的图案 （各种条纹/百分比图案）归并为
   * {@link ShadingPattern#NIL}，以确保实际文档永不加载失败——若需要保留原始图案 细节，请走 {@code raw()} 直接读 {@code CTShd}。
   *
   * @param pattern OOXML 底纹图案，如果未设置则为 {@code null}
   * @return 对应的 nondocx 底纹图案，如果输入为 {@code null} 则返回 {@code null}
   */
  public static ShadingPattern fromPoi(STShd.Enum pattern) {
    if (pattern == null) {
      return null;
    }
    if (pattern == STShd.CLEAR) {
      return ShadingPattern.CLEAR;
    }
    if (pattern == STShd.NIL) {
      return ShadingPattern.NIL;
    }
    // SOLID 及所有未建模图案归并 NIL（跨引擎安全默认），raw() 可读原始值
    return ShadingPattern.NIL;
  }

  /**
   * 将 nondocx {@link VerticalAlign} 映射到 OOXML {@link STVerticalJc.Enum}。
   *
   * @param align nondocx 垂直对齐（不能为 {@code null}）
   * @return 对应的 OOXML 垂直对齐枚举
   * @throws IllegalArgumentException 如果 {@code align} 为 {@code null}
   */
  public static STVerticalJc.Enum toPoi(VerticalAlign align) {
    if (align == null) {
      throw new IllegalArgumentException("align must not be null");
    }
    switch (align) {
      case TOP:
        return STVerticalJc.TOP;
      case CENTER:
        return STVerticalJc.CENTER;
      case BOTTOM:
        return STVerticalJc.BOTTOM;
      default:
        throw new IllegalArgumentException("Unsupported vertical align: " + align);
    }
  }

  /**
   * 将 OOXML {@link STVerticalJc.Enum} 映射回 nondocx {@link VerticalAlign}。
   *
   * <p>{@code null} 输入（意味着未设垂直对齐）映射为 {@code null}；调用方在 {@code Cell} 级别应用 OOXML 的 默认行为（{@link
   * VerticalAlign#TOP}）。
   *
   * @param align OOXML 垂直对齐，如果未设置则为 {@code null}
   * @return 对应的 nondocx 垂直对齐，如果输入为 {@code null} 则返回 {@code null}
   */
  public static VerticalAlign fromPoi(STVerticalJc.Enum align) {
    if (align == null) {
      return null;
    }
    if (align == STVerticalJc.CENTER) {
      return VerticalAlign.CENTER;
    }
    if (align == STVerticalJc.BOTTOM) {
      return VerticalAlign.BOTTOM;
    }
    return VerticalAlign.TOP;
  }

  /**
   * 将 nondocx {@link HeaderFooterVariant} 映射到 POI 的 {@link STHdrFtr.Enum}（即 {@code
   * XWPFHeaderFooterPolicy.DEFAULT/FIRST/EVEN}）。
   *
   * @param variant nondocx 变体（不能为 {@code null}）
   * @return 对应的 POI {@code STHdrFtr} 常量
   * @throws IllegalArgumentException 如果 {@code variant} 为 {@code null}
   */
  public static STHdrFtr.Enum toPoi(HeaderFooterVariant variant) {
    if (variant == null) {
      throw new IllegalArgumentException("variant must not be null");
    }
    switch (variant) {
      case DEFAULT:
        return XWPFHeaderFooterPolicy.DEFAULT;
      case FIRST:
        return XWPFHeaderFooterPolicy.FIRST;
      case EVEN:
        return XWPFHeaderFooterPolicy.EVEN;
      default:
        throw new IllegalArgumentException("未知的变体: " + variant);
    }
  }
}
