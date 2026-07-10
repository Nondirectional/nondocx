package com.non.docx.toolkit.orchestration.specialist;

import com.non.docx.toolkit.orchestration.Operation;
import com.non.docx.toolkit.orchestration.commit.OperationExecutor;
import com.non.docx.toolkit.orchestration.session.OrchestratorSession;

/**
 * 页眉目录域的 {@link OperationExecutor}：第一版偏只读，commit 阶段无需写。
 *
 * <p>{@link HeaderTocAgent} 在 plan 阶段已读取页眉页脚/目录并把报告存入 operation payload； 本执行器的 {@code execute} 是
 * no-op，不对文档做任何写操作。
 */
public final class HeaderTocExecutor implements OperationExecutor {

  @Override
  public boolean canHandle(Operation operation) {
    return "header-toc".equals(operation.toolGroup());
  }

  @Override
  public String execute(OrchestratorSession session, Operation operation) {
    return "页眉目录只读分析已完成（见 plan 阶段报告）";
  }
}
