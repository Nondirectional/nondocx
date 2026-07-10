package com.non.docx.toolkit.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.non.docx.toolkit.orchestration.review.ReviewReason;
import com.non.docx.toolkit.orchestration.review.ReviewResult;
import com.non.docx.toolkit.orchestration.review.ReviewStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link Operation} 单测：不可变更新、去重吸收标记、精简视图与值相等。 */
class OperationTest {

  private static Operation op(String id) {
    return Operation.of(
        id,
        "body",
        "replace_text",
        "p:1",
        Map.of("text", "hi"),
        new ConflictKey("body", "replace_text", "p:1"),
        "把第1段改成hi",
        "用户要求",
        "");
  }

  @Test
  void defaultsToApproved() {
    Operation o = op("op-1");
    assertThat(o.reviewStatus()).isEqualTo(ReviewStatus.APPROVED);
    assertThat(o.mergedIntoOperationId()).isEmpty();
  }

  @Test
  void withReviewReturnsNewInstance() {
    Operation o = op("op-1");
    Operation warned = o.withReview(ReviewResult.of(ReviewReason.QUALITY_RISK, "行距紧"));
    // 原实例不变（不可变更新）
    assertThat(o.reviewStatus()).isEqualTo(ReviewStatus.APPROVED);
    assertThat(warned.reviewStatus()).isEqualTo(ReviewStatus.WARNED);
    assertThat(warned.review().explanation()).isEqualTo("行距紧");
    // 其余字段保持
    assertThat(warned.operationId()).isEqualTo("op-1");
    assertThat(warned.shortLabel()).isEqualTo(o.shortLabel());
  }

  @Test
  void withMergedIntoMarksSkippedDuplicate() {
    Operation o = op("op-1");
    Operation merged = o.withMergedInto("op-2");
    assertThat(merged.reviewStatus()).isEqualTo(ReviewStatus.SKIPPED);
    assertThat(merged.review().reason()).isEqualTo(ReviewReason.DUPLICATE_MERGED);
    assertThat(merged.mergedIntoOperationId()).hasValue("op-2");
  }

  @Test
  void payloadIsImmutableCopy() {
    Map<String, Object> mutable = new LinkedHashMap<>();
    mutable.put("text", "hi");
    Operation o =
        Operation.of(
            "op-1",
            "body",
            "replace_text",
            "p:1",
            mutable,
            new ConflictKey("body", "replace_text", "p:1"),
            "",
            "",
            "");
    mutable.put("text", "CHANGED"); // 修改原 Map 不应影响 operation
    assertThat(o.payload().get("text")).isEqualTo("hi");
  }

  @Test
  void shortViewContainsCoreFields() {
    Operation o = op("op-1").withReview(ReviewResult.of(ReviewReason.QUALITY_RISK, "行距紧"));
    Map<String, Object> view = o.shortView();
    assertThat(view.get("operationId")).isEqualTo("op-1");
    assertThat(view.get("status")).isEqualTo("WARNED");
    assertThat(view.get("shortLabel")).isEqualTo("body/replace_text@p:1");
    assertThat(view.get("reason")).isEqualTo("行距紧");
  }

  @Test
  void valueEquality() {
    Operation a = op("op-1");
    Operation b = op("op-1");
    assertThat(a).isEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
    // review 不同则不等
    assertThat(a.withReview(ReviewResult.approved("ok"))).isNotEqualTo(b);
  }
}
