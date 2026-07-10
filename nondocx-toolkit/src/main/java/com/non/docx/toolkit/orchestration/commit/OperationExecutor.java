package com.non.docx.toolkit.orchestration.commit;

import com.non.docx.toolkit.orchestration.Operation;
import com.non.docx.toolkit.orchestration.session.OrchestratorSession;

/**
 * 单条 {@link Operation} 的执行器：把强类型 operation 落到 toolkit 工具调用上。
 *
 * <p><b>为什么用接口而非 switch。</b> 不同工具组（body/table/revision/quality）的 operation kind 到 toolkit
 * 调用的映射差异很大，且会随专家落地逐步扩充。用接口 + 注册表（见 {@link
 * com.non.docx.toolkit.orchestration.commit.OperationExecutors}）让每个工具组自带执行器，避免 CommitCoordinator
 * 膨胀成全知全觉的上帝类。
 *
 * <p><b>契约。</b>
 *
 * <ul>
 *   <li>{@link #canHandle(Operation)}——本执行器是否能处理该 operation（按 toolGroup/kind 匹配）。
 *   <li>{@link #execute(OrchestratorSession, Operation)}——执行单条 operation。
 *       <ul>
 *         <li>成功返回正常结果（含面向人的简述）。
 *         <li>失败抛 {@link OperationExecutionException}，由 CommitCoordinator 捕获转为失败结果。
 *       </ul>
 * </ul>
 *
 * <p>执行器<b>只负责写</b>，不做冲突检测、不做 review、不决定排序——那些是 RouterAgent 与 CommitCoordinator 的职责。
 */
public interface OperationExecutor {

  /** 本执行器是否能处理该 operation。 */
  boolean canHandle(Operation operation);

  /**
   * 执行单条 operation。
   *
   * @param session 当前会话（取活文档用）
   * @param operation 待执行 operation
   * @return 面向人的执行简述（成功信息）
   * @throws OperationExecutionException 执行失败时抛出，由 CommitCoordinator 转为失败结果
   */
  String execute(OrchestratorSession session, Operation operation)
      throws OperationExecutionException;
}
