package com.non.docx.toolkit.orchestration.commit;

import com.non.docx.toolkit.orchestration.Operation;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link OperationExecutor} 注册表：按 toolGroup/kind 路由到具体执行器。
 *
 * <p>每个工具组子任务（body-table / specialists）落地时把自己的执行器注册进来， {@code CommitCoordinator} 不需要知道具体映射规则。
 */
public final class OperationExecutors {

  private final List<OperationExecutor> executors = new ArrayList<>();

  /** 注册一个执行器（可链式）。 */
  public OperationExecutors register(OperationExecutor executor) {
    executors.add(executor);
    return this;
  }

  /** 查找能处理该 operation 的执行器；找不到返回 null（调用方决定是否报错）。 */
  public OperationExecutor find(Operation operation) {
    for (OperationExecutor e : executors) {
      if (e.canHandle(operation)) {
        return e;
      }
    }
    return null;
  }

  /** 已注册执行器数量。 */
  public int size() {
    return executors.size();
  }
}
