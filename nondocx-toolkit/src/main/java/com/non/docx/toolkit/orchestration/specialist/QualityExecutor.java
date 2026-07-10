package com.non.docx.toolkit.orchestration.specialist;

import com.non.docx.toolkit.orchestration.Operation;
import com.non.docx.toolkit.orchestration.commit.OperationExecutor;
import com.non.docx.toolkit.orchestration.session.OrchestratorSession;

/**
 * 质量域的 {@link OperationExecutor}：质量检查是<b>只读分析</b>，commit 阶段无需再写。
 *
 * <p>{@link QualityAgent} 在 plan 阶段已跑完 {@code checkQuality} 并把报告存入 operation payload； 本执行器的 {@code
 * execute} 是 no-op——确认报告存在即可，不对文档做任何写操作。
 *
 * <p>这样质量 operation 能正常走完 commit 流程（计入 executed），而不会因「找不到执行器」失败。
 */
public final class QualityExecutor implements OperationExecutor {

  @Override
  public boolean canHandle(Operation operation) {
    return "quality".equals(operation.toolGroup());
  }

  @Override
  public String execute(OrchestratorSession session, Operation operation) {
    // 质量检查报告已在 plan 阶段写入 payload；这里只确认存在，不重复执行、不写文档。
    Object report = operation.payload().get("report");
    if (report == null) {
      return "质量检查（无报告）";
    }
    return "质量检查已完成（见 plan 阶段报告）";
  }
}
