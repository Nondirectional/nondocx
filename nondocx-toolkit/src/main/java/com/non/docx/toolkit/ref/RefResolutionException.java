package com.non.docx.toolkit.ref;

/** 元素引用无法解析。错误类型由稳定 {@link RefResolutionCode} 表达。 */
public final class RefResolutionException extends IllegalArgumentException {

  private final RefResolutionCode code;

  public RefResolutionException(RefResolutionCode code, String message) {
    super(message);
    this.code = code;
  }

  public RefResolutionCode code() {
    return code;
  }

  /** 当前字符串工具边界使用的兼容渲染。 */
  public String render() {
    return "错误[" + code.value() + "]：" + getMessage();
  }

  static RefResolutionException invalidRef(String message) {
    return new RefResolutionException(RefResolutionCode.INVALID_REF, message);
  }
}
