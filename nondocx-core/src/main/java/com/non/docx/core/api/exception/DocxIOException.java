package com.non.docx.core.api.exception;

import java.io.IOException;

/**
 * 当无法从字节源读取文档或向字节源写入文档时抛出。
 *
 * <p>它包装底层的 {@link IOException} 和 Apache POI IO 相关失败（例如 {@code OpenXML4JException} 或 {@code
 * POIXMLException}），因此调用者无需导入 {@code org.apache.poi.*} 即可处理 IO 错误。原始异常会保留为 {@link #getCause()} 原因。
 *
 * <p>示例消息：{@code "保存文档失败"}。
 */
public class DocxIOException extends DocxException {

  private static final long serialVersionUID = 1L;

  /**
   * 使用指定的详细信息和根本原因构造一个新异常。
   *
   * @param message 描述失败 IO 操作的详细信息
   * @param cause 底层的 IO / Apache POI 异常，或 {@code null}
   */
  public DocxIOException(String message, Throwable cause) {
    super(message, cause);
  }
}
