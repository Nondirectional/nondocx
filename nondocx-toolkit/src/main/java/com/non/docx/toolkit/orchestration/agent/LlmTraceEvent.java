package com.non.docx.toolkit.orchestration.agent;

import java.util.Objects;

/**
 * LLM 推理过程的 trace 事件：让调用方（如 demo 的 AgentBridge）能实时观测专家内部 LLM 调用的 prompt、 response 增量、thinking
 * 增量与完成状态。
 *
 * <p><b>为什么是单值对象 + Kind 枚举，而非 4 方法接口。</b> {@code ExpertAgent.plan} 的第 4 参数是 {@code
 * Consumer<LlmTraceEvent>}，RouterAgent/expert 只需调 {@code traceCb.accept(event)}，不关心 4 个方法分派。 null
 * 时跳过。
 *
 * <p><b>事件时序。</b> 一次 LLM 调用按顺序产生：
 *
 * <ol>
 *   <li>{@link #ofPrompt} —— prompt 构造完成后、调 LLM 前，一次性。
 *   <li>{@link #ofContentDelta} / {@link #ofThinkingDelta} —— 模型流式返回时，每 chunk 一次（0~N 次）。
 *   <li>{@link #ofComplete} —— 调用结束（成功或失败），一次性。
 * </ol>
 *
 * <p><b>agentName 字段强制携带。</b> 为未来多专家场景（BodyAgent/TableAgent 各自产 trace）预留前端分组依据。 当前单专家场景，值由实现类的
 * {@code name()} 填入。
 *
 * <p><b>usage 类型用 Object。</b> nonchain 的 {@code TokenUsage} 是框架类型，toolkit 接口层不应硬绑。 由 demo
 * 侧消费时自行解释（如调用 {@code toString()} 或反射）。toolkit 只做搬运。
 *
 * @since P0 trace visibility
 */
public final class LlmTraceEvent {

  /** 事件种类。 */
  public enum Kind {
    PROMPT,
    CONTENT_DELTA,
    THINKING_DELTA,
    COMPLETE
  }

  private final Kind kind;
  private final String agentName;
  private final String prompt; // PROMPT 时非空
  private final String delta; // CONTENT_DELTA / THINKING_DELTA 时非空
  private final boolean success; // COMPLETE 时有意义
  private final String error; // COMPLETE 失败时非空
  private final Object usage; // COMPLETE 成功时可空（框架 TokenUsage，由消费侧解释）

  private LlmTraceEvent(
      Kind kind,
      String agentName,
      String prompt,
      String delta,
      boolean success,
      String error,
      Object usage) {
    this.kind = kind;
    this.agentName = Objects.requireNonNull(agentName, "agentName 不能为空");
    this.prompt = prompt;
    this.delta = delta;
    this.success = success;
    this.error = error;
    this.usage = usage;
  }

  /** prompt 构造完成事件（一次性）。 */
  public static LlmTraceEvent ofPrompt(String agentName, String prompt) {
    return new LlmTraceEvent(Kind.PROMPT, agentName, prompt, null, true, null, null);
  }

  /** response 内容增量事件（每 chunk 一次）。 */
  public static LlmTraceEvent ofContentDelta(String agentName, String delta) {
    return new LlmTraceEvent(Kind.CONTENT_DELTA, agentName, null, delta, true, null, null);
  }

  /** thinking 内容增量事件（每 chunk 一次）。 */
  public static LlmTraceEvent ofThinkingDelta(String agentName, String delta) {
    return new LlmTraceEvent(Kind.THINKING_DELTA, agentName, null, delta, true, null, null);
  }

  /** LLM 调用完成事件（成功）。usage 可空，由框架 TokenUsage 解释。 */
  public static LlmTraceEvent ofComplete(String agentName, Object usage) {
    return new LlmTraceEvent(Kind.COMPLETE, agentName, null, null, true, null, usage);
  }

  /** LLM 调用完成事件（失败）。 */
  public static LlmTraceEvent ofFailure(String agentName, String error) {
    return new LlmTraceEvent(Kind.COMPLETE, agentName, null, null, false, error, null);
  }

  /** 事件种类。 */
  public Kind kind() {
    return kind;
  }

  /** 产出此事件的专家名（如 {@code "LlmDocxExpert"}）。 */
  public String agentName() {
    return agentName;
  }

  /** PROMPT 事件携带的完整 prompt 字符串（其余事件为 null）。 */
  public String prompt() {
    return prompt;
  }

  /** CONTENT_DELTA / THINKING_DELTA 事件携带的增量字符串（其余事件为 null）。 */
  public String delta() {
    return delta;
  }

  /** COMPLETE 事件的成功标志（其余事件恒为 true）。 */
  public boolean success() {
    return success;
  }

  /** COMPLETE 失败事件的错误信息（成功时为 null）。 */
  public String error() {
    return error;
  }

  /** COMPLETE 成功事件的 token 用量对象（框架 TokenUsage，可空；由消费侧解释）。 */
  public Object usage() {
    return usage;
  }

  @Override
  public String toString() {
    switch (kind) {
      case PROMPT:
        return "LlmTraceEvent["
            + kind
            + " "
            + agentName
            + " prompt.len="
            + (prompt == null ? 0 : prompt.length())
            + "]";
      case CONTENT_DELTA:
      case THINKING_DELTA:
        return "LlmTraceEvent["
            + kind
            + " "
            + agentName
            + " delta.len="
            + (delta == null ? 0 : delta.length())
            + "]";
      case COMPLETE:
      default:
        return "LlmTraceEvent["
            + kind
            + " "
            + agentName
            + " success="
            + success
            + (error != null ? " error=" + error : "")
            + (usage != null ? " usage=" + usage : "")
            + "]";
    }
  }
}
