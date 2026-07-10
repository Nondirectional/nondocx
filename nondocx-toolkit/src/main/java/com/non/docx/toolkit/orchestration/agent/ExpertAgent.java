package com.non.docx.toolkit.orchestration.agent;

import com.non.docx.toolkit.orchestration.DocumentSnapshot;
import com.non.docx.toolkit.orchestration.ExpertPlan;
import com.non.docx.toolkit.orchestration.session.OrchestratorSession;

/**
 * 领域专家子代理：读取基础快照（必要时经 ReadCoordinator 补读），产出<b>本工具组</b>的 {@link ExpertPlan}。
 *
 * <p><b>OOXML 三层递进（专家子代理）：</b>
 *
 * <ul>
 *   <li><b>OOXML</b>：.docx 的不同领域（正文/表格/修订/页眉页脚/质量）在 XML 上是不同节点族， 使用教程与失败恢复规则各不相同。
 *   <li><b>POI</b>：{@code XWPF*} API 跨领域混在一起，单 Agent 持有全部工具时 prompt 负担过重。
 *   <li><b>nondocx</b>：按 toolkit 工具组拆出领域专家，每个专家只懂本组的工具教程、参数规则与失败恢复， 只产出本组的原子
 *       operation——跨组组合、排序、冲突解决交给 RouterAgent。
 * </ul>
 *
 * <p><b>第一版固定按工具组拆：</b>
 *
 * <ul>
 *   <li>{@code BodyAgent}（正文）、{@code TableAgent}（表格）、{@code RevisionAgent}（修订）、 {@code
 *       HeaderTocAgent}（页眉页脚目录）、{@code QualityAgent}（质量）。
 *   <li>{@code SessionTools} 不做独立专家——文档生命周期由 orchestration/coordinator 层直接持有。
 * </ul>
 *
 * <p><b>边界。</b> 专家<b>不直接写</b>活文档、<b>不决定保存时机</b>、<b>不产出跨组复合 operation</b>。
 * 第一版只允许异步执行读/分析/计划生成/质量检查，写一律经 CommitCoordinator。
 */
public interface ExpertAgent {

  /** 专家名（如 {@code "BodyAgent"}），用于 plan 的 agentName 字段与 trace。 */
  String name();

  /**
   * 本专家是否需要被本轮唤起（粗分流判定）。
   *
   * <p>RouterAgent 第一版采用分阶段派发：先基于用户意图与 {@link DocumentSnapshot} 粗分流，再唤起必要专家。
   * 此方法让每个专家自带「我是否相关」的判定，避免全专家广播。
   *
   * @param intent 用户意图文本
   * @param snapshot 文档快照（只读）
   */
  boolean relevantTo(String intent, DocumentSnapshot snapshot);

  /**
   * 产出本工具组的 {@link ExpertPlan}。
   *
   * <p>实现可读取快照，必要时经 {@code ReadCoordinator} 补读；面向 LLM 先输出 JSON plan， 由 RouterAgent 解析校验为强类型
   * ExpertPlan。
   *
   * @param session 当前会话（补读用）
   * @param snapshot 文档快照（只读基线）
   * @param intent 用户意图文本
   */
  ExpertPlan plan(OrchestratorSession session, DocumentSnapshot snapshot, String intent);
}
