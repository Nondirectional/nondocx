package com.non.docx.core.api.exception;

/**
 * 当请求的 docx 功能超出了 nondocx 深度封装范围时抛出。
 *
 * <p>nondocx 特意封装了常见的约 90% 的 docx 用法，将尚在封装进程之外的高级功能（域、 OLE 对象、OMML 数学公式、水印、文本框、形状等）留给 {@code raw()}
 * 逃生口。当在无法 处理的封装路径上遇到此类功能时，将抛出此异常，其消息会引导调用者使用 {@code raw()}。
 *
 * <p>示例消息：{@code "文本框/形状未被 nondocx 封装；请使用 raw() 访问底层 POI 对象"}。
 */
public class UnsupportedFeatureException extends DocxException {

  private static final long serialVersionUID = 1L;

  /**
   * 使用指定的详细信息构造一个新异常。
   *
   * @param message 详细信息（中文），通常指明不支持的功能并指向 {@code raw()}
   */
  public UnsupportedFeatureException(String message) {
    super(message);
  }
}
