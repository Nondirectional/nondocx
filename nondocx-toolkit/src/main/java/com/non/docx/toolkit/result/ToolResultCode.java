package com.non.docx.toolkit.result;

/**
 * 统一工具结果码目录。
 *
 * <p>成功为 {@link #OK}，其余均为失败码。每个码携带稳定 {@link #value()}（对外契约字符串）、 中文 {@link #label()} 和 {@link
 * #retryable()} 标记（可重试 vs 不可重试）。
 *
 * <p>ref 域错误码（{@code stale_ref} 等）由 {@link
 * com.non.docx.toolkit.ref.RefResolutionCode#toToolResultCode()} 映射进来， 不维护两套语义。
 */
public enum ToolResultCode {
  OK("ok", "成功", false),
  INVALID_ARGUMENT("invalid_argument", "参数错误", true),
  INDEX_OUT_OF_RANGE("index_out_of_range", "索引越界", true),
  STALE_REF("stale_ref", "引用已失效", true),
  ELEMENT_REMOVED("element_removed", "目标元素已删除", false),
  GENERATION_MISMATCH("generation_mismatch", "文档代次不匹配", false),
  DOCUMENT_MISMATCH("document_mismatch", "文档不匹配", false),
  REF_TYPE_MISMATCH("ref_type_mismatch", "引用类型不匹配", false),
  INVALID_REF("invalid_ref", "引用格式非法", false),
  UNSUPPORTED_FEATURE("unsupported_feature", "不支持的能力", false),
  NO_CHANGES_APPLIED("no_changes_applied", "无变更可应用", false),
  PARTIAL_FAILURE("partial_failure", "部分失败", true),
  DOCUMENT_CLOSED("document_closed", "文档已关闭", false),
  DOCUMENT_CORRUPT("document_corrupt", "文档损坏", false),
  COMPATIBILITY_RISK("compatibility_risk", "兼容性风险", false);

  private final String value;
  private final String label;
  private final boolean retryable;

  ToolResultCode(String value, String label, boolean retryable) {
    this.value = value;
    this.label = label;
    this.retryable = retryable;
  }

  /** 对外契约字符串，稳定不变。 */
  public String value() {
    return value;
  }

  /** 中文标签，用于人类可读渲染。 */
  public String label() {
    return label;
  }

  /** 是否可重试。 */
  public boolean retryable() {
    return retryable;
  }

  /** 是否成功。 */
  public boolean isSuccess() {
    return this == OK;
  }

  /** 按 value 反查；找不到返回 null。 */
  public static ToolResultCode fromValue(String value) {
    if (value == null) {
      return null;
    }
    for (ToolResultCode code : values()) {
      if (code.value.equals(value)) {
        return code;
      }
    }
    return null;
  }
}
