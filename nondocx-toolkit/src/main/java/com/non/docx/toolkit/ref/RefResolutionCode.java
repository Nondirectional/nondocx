package com.non.docx.toolkit.ref;

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
}
