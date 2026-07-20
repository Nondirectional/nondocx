package io.github.nondirectional.docx.toolkit.ref;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/** 元素引用规范化 codec。 */
public final class ElementRefs {

  private ElementRefs() {}

  /** 把强类型引用格式化为稳定字符串。 */
  public static String format(ElementRef ref) {
    String id =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(ref.elementId().getBytes(StandardCharsets.UTF_8));
    return ref.documentRef().canonical()
        + "/"
        + ref.kind().name().toLowerCase(Locale.ROOT)
        + ":"
        + ref.stability().name().toLowerCase(Locale.ROOT)
        + ":"
        + id;
  }

  /** 解析规范化字符串。 */
  public static ElementRef parse(String canonical) {
    if (canonical == null) {
      throw RefResolutionException.invalidRef("元素引用不能为空");
    }
    int slash = canonical.lastIndexOf('/');
    if (slash <= 0 || slash == canonical.length() - 1) {
      throw RefResolutionException.invalidRef("元素引用格式非法");
    }
    DocumentRef documentRef = DocumentRef.parse(canonical.substring(0, slash));
    String[] parts = canonical.substring(slash + 1).split(":", 3);
    if (parts.length != 3) {
      throw RefResolutionException.invalidRef("元素引用缺少类型、稳定性或身份");
    }
    try {
      ElementKind kind = ElementKind.valueOf(parts[0].toUpperCase(Locale.ROOT));
      RefStability stability = RefStability.valueOf(parts[1].toUpperCase(Locale.ROOT));
      String id = new String(Base64.getUrlDecoder().decode(parts[2]), StandardCharsets.UTF_8);
      switch (kind) {
        case PARAGRAPH:
          return new ParagraphRef(documentRef, stability, id);
        case RUN:
          return new RunRef(documentRef, stability, id);
        case TABLE:
          return new TableRef(documentRef, stability, id);
        case CELL:
          return new CellRef(documentRef, stability, id);
        case HEADER_FOOTER:
          return new HeaderFooterRef(documentRef, stability, id);
        case REVISION:
          return new RevisionRef(documentRef, stability, id);
        case OPERATION_TARGET:
          return new OperationTargetRef(documentRef, stability, id);
        default:
          throw RefResolutionException.invalidRef("未知元素引用类型");
      }
    } catch (RefResolutionException e) {
      throw e;
    } catch (IllegalArgumentException e) {
      throw RefResolutionException.invalidRef("元素引用内容非法");
    }
  }
}
