package com.non.docx.toolkit.orchestration;

import java.util.EnumSet;
import java.util.Set;

/**
 * RouterAgent 的显式状态机：流程至少包含 {@code ANALYZE -> PLAN -> REVIEW -> COMMIT -> DONE/FAILED}。
 *
 * <p><b>OOXML 三层递进（状态机）：</b>
 *
 * <ul>
 *   <li><b>OOXML</b>：.docx 没有内建「编辑流程」概念——它只是一个静态文档结构。
 *   <li><b>POI</b>：{@code XWPFDocument} 只提供读/写/保存的原子动作，没有「先分析、再计划、再审查、再提交」 的阶段编排。
 *   <li><b>nondocx</b>：在 toolkit 编排层引入显式状态机，把「安全编辑会话」拆成可测试、可 trace、可 UI 展示
 *       的阶段，让每一轮用户请求的推进过程对调用方可见，而不是藏在一次黑盒 Agent 调用里。
 * </ul>
 *
 * <p><b>状态语义：</b>
 *
 * <ul>
 *   <li>{@link #ANALYZE}——基于 DocumentSnapshot 与用户意图做粗分流，决定唤起哪些专家。
 *   <li>{@link #PLAN}——接收各专家 ExpertPlan，合并为 MergedPlan。
 *   <li>{@link #REVIEW}——条件触发（非每次强制）。至少在跨专家合并、冲突候选、质量告警、修订操作、 失败后重试等高风险场景进入；对 MergedPlan 中每条
 *       operation 产出强类型 review 结果。
 *   <li>{@link #COMMIT}——无 BLOCKED 时，把 MergedPlan 交给 CommitCoordinator 串行提交。
 *   <li>{@link #DONE}——本轮成功收尾。
 *   <li>{@link #FAILED}——提交失败或出现 BLOCKED；必须 close + reopen 后才能进入新一轮。
 * </ul>
 */
public enum RouterState {
  ANALYZE,
  PLAN,
  REVIEW,
  COMMIT,
  DONE,
  FAILED;

  /**
   * 该状态的合法后继集合。
   *
   * <p>第一版采用线性推进 + 两个出口（DONE / FAILED）：
   *
   * <pre>
   *   ANALYZE -> PLAN -> REVIEW? -> COMMIT -> DONE
   *                                   ↘ FAILED
   * </pre>
   *
   * REVIEW 是条件触发——PLAN 在判定无需 review 时可直接进入 COMMIT；因此 PLAN 的后继同时含 REVIEW 与 COMMIT。
   */
  public Set<RouterState> successors() {
    switch (this) {
      case ANALYZE:
        return EnumSet.of(PLAN);
      case PLAN:
        // REVIEW 条件触发：可直接进 COMMIT，也可先进 REVIEW。
        return EnumSet.of(REVIEW, COMMIT);
      case REVIEW:
        return EnumSet.of(COMMIT, FAILED);
      case COMMIT:
        return EnumSet.of(DONE, FAILED);
      case DONE:
      case FAILED:
        // 终态：DONE 后可由下一轮用户请求重新进入 ANALYZE；FAILED 必须先 close+reopen 再重开 ANALYZE。
        // 这里返回空集表示「当前轮次内无更多自动后继」。
        return EnumSet.noneOf(RouterState.class);
      default:
        throw new IllegalStateException("未知状态: " + this);
    }
  }

  /** 从 {@code from} 推进到 {@code to} 是否合法。 */
  public static boolean canTransition(RouterState from, RouterState to) {
    return from.successors().contains(to);
  }

  /**
   * 校验并执行状态推进；非法推进抛 {@link IllegalStateException}。
   *
   * @return 推进后的新状态
   */
  public static RouterState transition(RouterState from, RouterState to) {
    if (!canTransition(from, to)) {
      throw new IllegalStateException("非法状态推进：" + from + " -> " + to);
    }
    return to;
  }

  /** 是否为终态（DONE 或 FAILED）。 */
  public boolean isTerminal() {
    return this == DONE || this == FAILED;
  }
}
