package com.non.docx.toolkit.ref;

/** 文档元素的强类型引用。 */
public interface ElementRef {

  /** 所属逻辑文档与签发代次。 */
  DocumentRef documentRef();

  /** 元素类型。 */
  ElementKind kind();

  /** 引用稳定性。 */
  RefStability stability();

  /** 元素身份。SESSION ref 为 opaque id，PERSISTENT ref 为持久标识。 */
  String elementId();

  /** 规范化、可传输字符串。 */
  String canonical();
}
