package com.non.docx.toolkit.orchestration.commit;

import com.non.docx.toolkit.orchestration.MergedPlan;
import com.non.docx.toolkit.orchestration.Operation;
import com.non.docx.toolkit.orchestration.review.ReviewStatus;
import com.non.docx.toolkit.orchestration.session.OrchestratorSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 唯一写入口：接收强类型 {@link MergedPlan}，按固定优先级排序后逐条提交。
 *
 * <p><b>OOXML 三层递进（提交）：</b>
 *
 * <ul>
 *   <li><b>OOXML</b>：.docx 的写入是直接改 XML 节点，没有「提交」「事务」概念。
 *   <li><b>POI</b>：{@code XWPFDocument} 的写是活对象就地修改，{@code save()} 才落盘；多次写之间无隔离。
 *   <li><b>nondocx</b>：在编排层把「所有写」收敛到 CommitCoordinator 单一入口——RouterAgent 与子代理 都不直接写活文档，避免多源并发改同一
 *       {@code docId} 导致的冲突。
 * </ul>
 *
 * <p><b>非事务语义（第一版决策）。</b>
 *
 * <ul>
 *   <li>按 {@link CommitPriority} 固定优先级顺序执行（结构 -> 文本/样式 -> 修订 -> 质量 -> 保存前检查）。
 *   <li>遇错即停，<b>不</b>自动回滚已完成修改。
 *   <li>失败时返回已执行步骤、失败点与 shouldReopen=true 建议；RouterAgent 必须 close + reopen 后再进入新一轮。
 *   <li>不在半修改内存态上继续补丁式修复。
 * </ul>
 *
 * <p><b>提交过滤。</b> 只提交 {@code APPROVED} 与 {@code WARNED} 的 operation；{@code SKIPPED} 与 {@code
 * BLOCKED} 不进入提交（BLOCKED 在进 commit 前就已被 RouterAgent 整批拦下）。
 */
public final class CommitCoordinator {

  private final OperationExecutors executors;
  private final CommitPriority priority;

  public CommitCoordinator(OperationExecutors executors) {
    this(executors, new CommitPriority());
  }

  public CommitCoordinator(OperationExecutors executors, CommitPriority priority) {
    this.executors = Objects.requireNonNull(executors);
    this.priority = Objects.requireNonNull(priority);
  }

  /**
   * 提交 MergedPlan。
   *
   * @param session 当前会话
   * @param plan 合并计划（只提交 APPROVED/WARNED 项，按优先级排序）
   * @return 提交结果
   */
  public CommitResult commit(OrchestratorSession session, MergedPlan plan) {
    Objects.requireNonNull(session, "session 不能为空");
    Objects.requireNonNull(plan, "plan 不能为空");

    // 1. 过滤出可提交项（APPROVED + WARNED），并按固定优先级排序
    List<Operation> toSubmit = new ArrayList<>();
    for (Operation op : plan.operations()) {
      ReviewStatus s = op.reviewStatus();
      if (s == ReviewStatus.APPROVED || s == ReviewStatus.WARNED) {
        toSubmit.add(op);
      }
    }
    List<Operation> ordered = priority.sortByPriority(toSubmit);

    // 2. 逐条执行，遇错即停
    List<String> executed = new ArrayList<>();
    for (Operation op : ordered) {
      OperationExecutor executor = executors.find(op);
      if (executor == null) {
        // 没有执行器能处理——视为失败（不应出现在正常流程；说明 plan 含未支持的 kind）
        return CommitResult.failure(
            executed, op.operationId(), "找不到能处理 " + op.toolGroup() + "/" + op.kind() + " 的执行器");
      }
      try {
        executor.execute(session, op);
        executed.add(op.operationId());
      } catch (OperationExecutionException e) {
        String msg = e.getMessage() == null ? "执行失败" : e.getMessage();
        if (e.getCause() != null) {
          msg = msg + "（根因：" + e.getCause().getMessage() + "）";
        }
        return CommitResult.failure(executed, op.operationId(), msg);
      } catch (RuntimeException e) {
        // 兜底：把非 OperationExecutionException 的运行时异常也转为失败结果
        return CommitResult.failure(
            executed,
            op.operationId(),
            "运行时异常：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
      }
    }
    return CommitResult.success(executed);
  }
}
