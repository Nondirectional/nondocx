package io.github.nondirectional.docx.toolkit.ref;

import io.github.nondirectional.docx.toolkit.result.ToolResultCode;

/** 稳定引用解析错误码。 */
public enum RefResolutionCode {
  STALE_REF("stale_ref"),
  ELEMENT_REMOVED("element_removed"),
  GENERATION_MISMATCH("generation_mismatch"),
  DOCUMENT_MISMATCH("document_mismatch"),
  REF_TYPE_MISMATCH("ref_type_mismatch"),
  INVALID_REF("invalid_ref");

  private final String value;

  RefResolutionCode(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  /**
   * 映射到统一 {@link ToolResultCode}。
   *
   * <p>ref 域错误码与 {@code ToolResultCode} 共享同一 value 字符串，不维护两套语义。
   */
  public ToolResultCode toToolResultCode() {
    switch (this) {
      case STALE_REF:
        return ToolResultCode.STALE_REF;
      case ELEMENT_REMOVED:
        return ToolResultCode.ELEMENT_REMOVED;
      case GENERATION_MISMATCH:
        return ToolResultCode.GENERATION_MISMATCH;
      case DOCUMENT_MISMATCH:
        return ToolResultCode.DOCUMENT_MISMATCH;
      case REF_TYPE_MISMATCH:
        return ToolResultCode.REF_TYPE_MISMATCH;
      case INVALID_REF:
        return ToolResultCode.INVALID_REF;
      default:
        throw new IllegalStateException("未覆盖的 RefResolutionCode: " + this);
    }
  }
}
