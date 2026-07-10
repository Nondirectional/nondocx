package com.non.docx.toolkit.orchestration.commit;

/**
 * 单条 operation 执行失败异常：被 {@link OperationExecutor#execute} 抛出， 由 {@code CommitCoordinator} 捕获后转为
 * {@link CommitResult#failure}。
 *
 * <p>携带面向人的失败消息；根因消息由调用方从 {@link #getCause()} 提取。
 */
public final class OperationExecutionException extends RuntimeException {

  public OperationExecutionException(String message) {
    super(message);
  }

  public OperationExecutionException(String message, Throwable cause) {
    super(message, cause);
  }
}
