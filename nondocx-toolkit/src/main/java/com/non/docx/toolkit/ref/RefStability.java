package com.non.docx.toolkit.ref;

/** 元素引用稳定性。 */
public enum RefStability {
  /** 只在当前文档会话代次内稳定。 */
  SESSION,

  /** 可在同一逻辑文档 save/reopen 后重新解析。 */
  PERSISTENT
}
