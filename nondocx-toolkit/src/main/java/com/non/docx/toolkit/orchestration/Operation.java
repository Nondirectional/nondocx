package com.non.docx.toolkit.orchestration;

import com.non.docx.toolkit.orchestration.review.ReviewResult;
import com.non.docx.toolkit.orchestration.review.ReviewStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 单条原子编辑操作：子代理产出的最小计划单元，也是 MergedPlan 与 CommitCoordinator 的执行单元。
 *
 * <p><b>OOXML 三层递进（operation）：</b>
 *
 * <ul>
 *   <li><b>OOXML</b>：一次 .docx 编辑在 XML 上往往改动多个分散节点（例如改一段文本的 run、改单元格的 段落、改样式属性），没有「一个操作」这种原生概念。
 *   <li><b>POI</b>：提供的是底层 API（{@code run.setText()}、{@code cell.removeParagraph()}）， 每个 API
 *       调用是原子的，但「把这段话替换成 X 并加粗」对 POI 来说是多次 API 调用。
 *   <li><b>nondocx</b>：在编排层定义 {@code Operation}，把「一次语义编辑」抽象成带稳定 id、工具组归属、 目标定位、payload、冲突 key、意图说明与
 *       review 结果的强类型单元——子代理只产出本工具组的原子 operation，跨组组合/排序/冲突解决交给 RouterAgent。
 * </ul>
 *
 * <p><b>字段说明（与父任务 PRD 对齐）：</b>
 *
 * <ul>
 *   <li>{@code operationId}——稳定 id，关联来源 ExpertPlan、MergedPlan、review 结果与 commit 失败定位。
 *   <li>{@code toolGroup}——归属工具组（body/table/revision/...），用于权限与排序。
 *   <li>{@code kind}——operation 类型（如 {@code replace_text}/{@code set_cell_text}）。
 *   <li>{@code targetRef}——目标定位串（与 {@link #conflictKey()} 的 targetRef 同源）。
 *   <li>{@code payload}——自由参数 Map（字段级意图，由具体工具组定义），第二层冲突检测按它判断可否合并。
 *   <li>{@code conflictKey}——粗粒度冲突 key，用于第一层候选圈定。
 *   <li>{@code intent}/{@code reason}/{@code riskNote}——explanation 三件套，供 review、trace、失败复盘、 UI/CLI
 *       展示。
 *   <li>{@code review}——review 结果（初始可为 APPROVED，由 REVIEW 阶段覆盖）。
 *   <li>{@code mergedIntoOperationId}——仅跳过场景：被去重吸收时指向最终保留项的 operationId。
 * </ul>
 *
 * <p><b>不可变性。</b> 唯一可变的是 {@code review} 与 {@code mergedIntoOperationId}——它们由 REVIEW/MERGE
 * 阶段写入；其余字段构造后不可变，保证来源链稳定。可变字段通过 {@link #withReview(ReviewResult)} / {@link
 * #withMergedInto(String)} 返回<b>新实例</b>，保持 operation 在传递中的不可变语义。
 */
public final class Operation {

  private final String operationId;
  private final String toolGroup;
  private final String kind;
  private final String targetRef;
  private final Map<String, Object> payload;
  private final ConflictKey conflictKey;
  private final String intent;
  private final String reason;
  private final String riskNote;
  private final ReviewResult review;
  private final String mergedIntoOperationId;

  private Operation(
      String operationId,
      String toolGroup,
      String kind,
      String targetRef,
      Map<String, Object> payload,
      ConflictKey conflictKey,
      String intent,
      String reason,
      String riskNote,
      ReviewResult review,
      String mergedIntoOperationId) {
    this.operationId = Objects.requireNonNull(operationId, "operationId 不能为空");
    this.toolGroup = Objects.requireNonNull(toolGroup, "toolGroup 不能为空");
    this.kind = Objects.requireNonNull(kind, "kind 不能为空");
    this.targetRef = Objects.requireNonNull(targetRef, "targetRef 不能为空");
    this.payload = payload == null ? Map.of() : Map.copyOf(payload);
    this.conflictKey = Objects.requireNonNull(conflictKey, "conflictKey 不能为空");
    this.intent = intent == null ? "" : intent;
    this.reason = reason == null ? "" : reason;
    this.riskNote = riskNote == null ? "" : riskNote;
    this.review = Objects.requireNonNull(review, "review 不能为空");
    this.mergedIntoOperationId = mergedIntoOperationId;
  }

  /**
   * 构造一条新 operation，默认 review=APPROVED、无 mergedInto。
   *
   * <p>payload 会被防御性拷贝并包装为不可变 Map。
   */
  public static Operation of(
      String operationId,
      String toolGroup,
      String kind,
      String targetRef,
      Map<String, Object> payload,
      ConflictKey conflictKey,
      String intent,
      String reason,
      String riskNote) {
    return new Operation(
        operationId,
        toolGroup,
        kind,
        targetRef,
        payload,
        conflictKey,
        intent,
        reason,
        riskNote,
        ReviewResult.approved(),
        null);
  }

  /** 返回一条 {@link #withReview} / {@link #withMergedInto} 用的全参 builder（内部用）。 */
  Operation copy(ReviewResult newReview, String newMergedInto) {
    return new Operation(
        operationId,
        toolGroup,
        kind,
        targetRef,
        payload,
        conflictKey,
        intent,
        reason,
        riskNote,
        newReview,
        newMergedInto);
  }

  /** 稳定 id。 */
  public String operationId() {
    return operationId;
  }

  /** 归属工具组。 */
  public String toolGroup() {
    return toolGroup;
  }

  /** operation 类型。 */
  public String kind() {
    return kind;
  }

  /** 目标定位串。 */
  public String targetRef() {
    return targetRef;
  }

  /** 字段级 payload（不可变）。 */
  public Map<String, Object> payload() {
    return payload;
  }

  /** 粗粒度冲突 key。 */
  public ConflictKey conflictKey() {
    return conflictKey;
  }

  /** 用户意图映射（面向人）。 */
  public String intent() {
    return intent;
  }

  /** 操作理由（面向人）。 */
  public String reason() {
    return reason;
  }

  /** 风险说明（面向人）。 */
  public String riskNote() {
    return riskNote;
  }

  /** review 结果。 */
  public ReviewResult review() {
    return review;
  }

  /** 返回带新 review 结果的拷贝（不可变更新）。 */
  public Operation withReview(ReviewResult newReview) {
    return copy(newReview, mergedIntoOperationId);
  }

  /** 跳过（去重吸收）时，指向最终保留项的 operationId；非跳过场景为 {@link Optional#empty()}。 */
  public Optional<String> mergedIntoOperationId() {
    return Optional.ofNullable(mergedIntoOperationId);
  }

  /** 返回标记为被合并吸收的拷贝（不可变更新），review 自动设为 SKIPPED(DUPLICATE_MERGED)。 */
  public Operation withMergedInto(String targetOperationId) {
    Objects.requireNonNull(targetOperationId, "targetOperationId 不能为空");
    return copy(
        com.non.docx.toolkit.orchestration.review.ReviewResult.of(
            com.non.docx.toolkit.orchestration.review.ReviewReason.DUPLICATE_MERGED,
            "去重吸收到 " + targetOperationId),
        targetOperationId);
  }

  /** review 状态便捷取值。 */
  public ReviewStatus reviewStatus() {
    return review.status();
  }

  /**
   * 精简状态视图（供高层摘要的精简操作清单使用）。
   *
   * @return {@code (operationId, status, shortLabel)} 的有序 Map
   */
  public Map<String, Object> shortView() {
    Map<String, Object> view = new LinkedHashMap<>(4);
    view.put("operationId", operationId);
    view.put("status", review.status().name());
    view.put("shortLabel", shortLabel());
    String reasonText = review.explanation();
    if (!reasonText.isEmpty()) {
      view.put("reason", reasonText);
    }
    return view;
  }

  /** 一行简短标签：{@code "body/replace_text@p:3"}。 */
  public String shortLabel() {
    return toolGroup + "/" + kind + "@" + targetRef;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Operation)) return false;
    Operation that = (Operation) o;
    return operationId.equals(that.operationId)
        && toolGroup.equals(that.toolGroup)
        && kind.equals(that.kind)
        && targetRef.equals(that.targetRef)
        && payload.equals(that.payload)
        && conflictKey.equals(that.conflictKey)
        && intent.equals(that.intent)
        && reason.equals(that.reason)
        && riskNote.equals(that.riskNote)
        && review.equals(that.review)
        && Objects.equals(mergedIntoOperationId, that.mergedIntoOperationId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        operationId,
        toolGroup,
        kind,
        targetRef,
        payload,
        conflictKey,
        intent,
        reason,
        riskNote,
        review,
        mergedIntoOperationId);
  }

  @Override
  public String toString() {
    return "Operation{" + shortLabel() + " [" + review.status() + "]}";
  }
}
