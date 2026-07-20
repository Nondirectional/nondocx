package io.github.nondirectional.docx.toolkit.ref;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/** 逻辑文档引用：稳定文档 key + 当前会话代次。 */
public final class DocumentRef {

  private static final String PREFIX = "doc:";

  private final String documentKey;
  private final long sessionGeneration;

  public DocumentRef(String documentKey, long sessionGeneration) {
    if (documentKey == null || documentKey.isBlank()) {
      throw new IllegalArgumentException("documentKey 不能为空");
    }
    if (sessionGeneration < 1) {
      throw new IllegalArgumentException("sessionGeneration 必须大于 0");
    }
    this.documentKey = documentKey;
    this.sessionGeneration = sessionGeneration;
  }

  /** 逻辑文档标识。编排层通常使用 conversationId，直接 toolkit 使用 docId。 */
  public String documentKey() {
    return documentKey;
  }

  /** 活文档会话代次。 */
  public long sessionGeneration() {
    return sessionGeneration;
  }

  /** 规范化、可传输字符串。 */
  public String canonical() {
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(documentKey.getBytes(StandardCharsets.UTF_8));
    return PREFIX + encoded + "@g" + sessionGeneration;
  }

  /** 解析 {@link #canonical()} 产出的字符串。 */
  public static DocumentRef parse(String canonical) {
    if (canonical == null || !canonical.startsWith(PREFIX)) {
      throw RefResolutionException.invalidRef("文档引用格式非法");
    }
    int generationMarker = canonical.lastIndexOf("@g");
    if (generationMarker <= PREFIX.length()) {
      throw RefResolutionException.invalidRef("文档引用缺少会话代次");
    }
    try {
      String encoded = canonical.substring(PREFIX.length(), generationMarker);
      String key = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
      long generation = Long.parseLong(canonical.substring(generationMarker + 2));
      return new DocumentRef(key, generation);
    } catch (IllegalArgumentException e) {
      throw RefResolutionException.invalidRef("文档引用内容非法");
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof DocumentRef)) return false;
    DocumentRef that = (DocumentRef) o;
    return sessionGeneration == that.sessionGeneration && documentKey.equals(that.documentKey);
  }

  @Override
  public int hashCode() {
    return Objects.hash(documentKey, sessionGeneration);
  }

  @Override
  public String toString() {
    return canonical();
  }
}
