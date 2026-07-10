package com.non.docx.core.internal.poi;

import javax.xml.namespace.QName;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.xmlbeans.XmlCursor;

/**
 * Internal API — subject to change without notice.
 *
 * <p>只读访问段落的 {@code w14:paraId}。本类不创建、不修复也不覆盖 paraId，供上层稳定引用基础设施 在不修改文档的前提下判断段落是否具备持久标识。
 */
public final class ParagraphIds {

  private static final QName PARA_ID =
      QName.valueOf("{http://schemas.microsoft.com/office/word/2010/wordml}paraId");

  private ParagraphIds() {}

  /**
   * 读取段落的 {@code w14:paraId}。
   *
   * @param paragraph POI 段落
   * @return paraId；属性不存在或无法读取时返回 {@code null}
   */
  public static String read(XWPFParagraph paragraph) {
    if (paragraph == null) {
      return null;
    }
    try {
      XmlCursor cursor = paragraph.getCTP().newCursor();
      try {
        String value = cursor.getAttributeText(PARA_ID);
        return value == null || value.isBlank() ? null : value;
      } finally {
        cursor.dispose();
      }
    } catch (RuntimeException e) {
      return null;
    }
  }
}
