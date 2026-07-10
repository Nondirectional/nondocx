package com.non.docx.toolkit.orchestration;

import java.util.List;
import java.util.Objects;

/**
 * 专家子代理产出的计划：子代理只输出<b>本工具组</b>的原子 operation 列表。
 *
 * <p><b>OOXML 三层递进（expert plan）：</b>
 *
 * <ul>
 *   <li><b>OOXML</b>：没有「计划」概念——文档就是当前结构。
 *   <li><b>POI</b>：提供原子 API，但没有「这一组改动作为一个语义单元」的表达。
 *   <li><b>nondocx</b>：在编排层引入 {@code ExpertPlan}，把单个领域专家（如 BodyAgent）产出的 「本轮该改什么」收敛成带
 *       schemaVersion、会话绑定、快照代次绑定与 operation 列表的强类型单元。 子代理面向 LLM 先输出 JSON plan，RouterAgent
 *       再解析校验为强类型 {@code ExpertPlan}； CommitCoordinator 不直接消费 LLM JSON。
 * </ul>
 *
 * <p><b>边界。</b> 单个子代理<b>只</b>产出本工具组的原子 operation，不输出跨组组合 operation，也不决定 保存时机。跨组组合、排序、冲突解决与保存时机由
 * RouterAgent / coordinator 统一负责。
 *
 * <p><b>版本与会话字段。</b>
 *
 * <ul>
 *   <li>{@code schemaVersion}——plan schema 版本，第一版固定 {@code 1}，与 snapshot 版本独立演进。
 *   <li>{@code conversationId}——会话绑定。
 *   <li>{@code snapshotVersion}/{@code sessionGeneration}——绑定本计划所基于的快照基线，用于校验 计划是否仍基于当前文档代次。
 * </ul>
 */
public final class ExpertPlan {

  /** plan schema 版本，第一版固定为 1，与 snapshot schema 版本独立演进。 */
  public static final int SCHEMA_VERSION = 1;

  private final int schemaVersion;
  private final String agentName;
  private final String planId;
  private final String conversationId;
  private final int snapshotVersion;
  private final long sessionGeneration;
  private final List<Operation> operations;

  public ExpertPlan(
      String agentName,
      String planId,
      String conversationId,
      int snapshotVersion,
      long sessionGeneration,
      List<Operation> operations) {
    this.schemaVersion = SCHEMA_VERSION;
    this.agentName = Objects.requireNonNull(agentName, "agentName 不能为空");
    this.planId = Objects.requireNonNull(planId, "planId 不能为空");
    this.conversationId = Objects.requireNonNull(conversationId, "conversationId 不能为空");
    this.snapshotVersion = snapshotVersion;
    this.sessionGeneration = sessionGeneration;
    this.operations = List.copyOf(operations);
  }

  public int schemaVersion() {
    return schemaVersion;
  }

  public String agentName() {
    return agentName;
  }

  public String planId() {
    return planId;
  }

  public String conversationId() {
    return conversationId;
  }

  public int snapshotVersion() {
    return snapshotVersion;
  }

  public long sessionGeneration() {
    return sessionGeneration;
  }

  public List<Operation> operations() {
    return operations;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ExpertPlan)) return false;
    ExpertPlan that = (ExpertPlan) o;
    return schemaVersion == that.schemaVersion
        && snapshotVersion == that.snapshotVersion
        && sessionGeneration == that.sessionGeneration
        && agentName.equals(that.agentName)
        && planId.equals(that.planId)
        && conversationId.equals(that.conversationId)
        && operations.equals(that.operations);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        schemaVersion,
        agentName,
        planId,
        conversationId,
        snapshotVersion,
        sessionGeneration,
        operations);
  }
}
