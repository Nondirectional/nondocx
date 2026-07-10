package com.non.docx.toolkit.ref;

import com.non.docx.core.api.Document;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** 按逻辑文档维护当前代次 resolver 的轻量上下文。 */
public final class ReferenceContext {

  private final Map<String, Entry> current = new HashMap<>();

  /** 获取或创建当前文档 resolver。同一 documentKey、generation 和活 Document 复用同一实例， 保证同一会话内重复签发得到相同 opaque id。 */
  public ElementResolver resolver(DocumentRef documentRef, Document document) {
    Objects.requireNonNull(documentRef, "documentRef 不能为空");
    Objects.requireNonNull(document, "document 不能为空");
    Entry existing = current.get(documentRef.documentKey());
    if (existing != null
        && existing.documentRef.equals(documentRef)
        && existing.documentIdentity == document.raw()) {
      return existing.resolver;
    }
    ElementResolver resolver = new ElementResolver(documentRef, document);
    current.put(documentRef.documentKey(), new Entry(documentRef, document.raw(), resolver));
    return resolver;
  }

  /** 移除逻辑文档的当前 resolver。 */
  public void invalidate(String documentKey) {
    current.remove(documentKey);
  }

  private static final class Entry {
    private final DocumentRef documentRef;
    private final Object documentIdentity;
    private final ElementResolver resolver;

    private Entry(DocumentRef documentRef, Object documentIdentity, ElementResolver resolver) {
      this.documentRef = documentRef;
      this.documentIdentity = documentIdentity;
      this.resolver = resolver;
    }
  }
}
