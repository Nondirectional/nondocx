package com.non.docx.core.api.exception;

/**
 * 当逻辑文档操作在运行时失败时抛出（相对于 IO 或格式问题）。
 *
 * <p>通过 {@link #getContext()} 携带可选的上下文字符串（例如段落索引或正文位置）以辅助诊断。
 *
 * <p><b>对调用者和实现者的指导：</b>参数验证失败和越界索引访问应复用标准 JDK 异常—— 即 {@link IllegalArgumentException} 和 {@link
 * IndexOutOfBoundsException}，而不是使用此类型。将 {@code DocxOperationException}
 * 保留给真正的领域操作失败（例如：对状态无法满足该操作的元素执行操作）。
 *
 * <p>示例消息：{@code "无法移除表格单元格中的唯一段落（上下文：cell[0]）"}。
 */
public class DocxOperationException extends DocxException {

  private static final long serialVersionUID = 1L;

  private final String context;

  /**
   * 使用指定的详细信息构造一个新异常。
   *
   * @param message 描述失败操作的详细信息
   */
  public DocxOperationException(String message) {
    super(message);
    this.context = null;
  }

  /**
   * 使用指定的详细信息和操作上下文构造一个新异常。
   *
   * @param message 描述失败操作的详细信息
   * @param context 简短的上下文描述符（例如 {@code "paragraph index 5"}），或 {@code null}
   */
  public DocxOperationException(String message, String context) {
    super(appendContext(message, context));
    this.context = context;
  }

  /**
   * 返回操作上下文（例如元素索引/位置），如果没有则为 {@code null}。
   *
   * @return 操作上下文，或 {@code null}
   */
  public String getContext() {
    return context;
  }

  private static String appendContext(String message, String context) {
    if (context == null) {
      return message;
    }
    return message + " (context: " + context + ")";
  }
}
