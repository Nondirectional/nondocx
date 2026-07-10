package com.non.docx.toolkit.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.non.docx.toolkit.orchestration.review.ReviewReason;
import com.non.docx.toolkit.orchestration.review.ReviewResult;
import com.non.docx.toolkit.orchestration.review.ReviewStatus;
import org.junit.jupiter.api.Test;

/** review 协议层单测：覆盖 (status, reason, ruleCode) 一致性、跨组错配校验与四态判定。 */
class ReviewResultTest {

  @Test
  void approvedHasFixedRuleCodeAndNoReason() {
    ReviewResult r = ReviewResult.approved();
    assertThat(r.status()).isEqualTo(ReviewStatus.APPROVED);
    assertThat(r.reason()).isNull();
    assertThat(r.ruleCode()).isEqualTo("APPROVED:OK");
    assertThat(r.blocksBatch()).isFalse();
    assertThat(r.submitted()).isTrue();
  }

  @Test
  void ofDerivesStatusAndCodeFromReason() {
    ReviewResult r = ReviewResult.of(ReviewReason.QUALITY_GATE_FAILED, "空白页超阈值");
    assertThat(r.status()).isEqualTo(ReviewStatus.BLOCKED);
    assertThat(r.reason()).isEqualTo(ReviewReason.QUALITY_GATE_FAILED);
    assertThat(r.ruleCode()).isEqualTo("BLOCKED:QUALITY_GATE_FAILED");
    assertThat(r.explanation()).isEqualTo("空白页超阈值");
    assertThat(r.blocksBatch()).isTrue();
    assertThat(r.submitted()).isFalse();
  }

  @Test
  void warnedIsSubmittedButExposed() {
    ReviewResult r = ReviewResult.of(ReviewReason.QUALITY_RISK, "行距偏紧");
    assertThat(r.status()).isEqualTo(ReviewStatus.WARNED);
    assertThat(r.submitted()).isTrue();
    assertThat(r.blocksBatch()).isFalse();
  }

  @Test
  void skippedKeepsRecord() {
    ReviewResult r = ReviewResult.of(ReviewReason.DUPLICATE_MERGED, "吸收到 op-2");
    assertThat(r.status()).isEqualTo(ReviewStatus.SKIPPED);
    assertThat(r.submitted()).isFalse();
    assertThat(r.blocksBatch()).isFalse();
  }

  @Test
  void everyReasonDerivesConsistentStatusAndCode() {
    // 遍历所有 reason：status() 与 code() 前缀必须一致
    for (ReviewReason reason : ReviewReason.values()) {
      ReviewResult rr = ReviewResult.of(reason, "");
      assertThat(rr.status()).isEqualTo(reason.status());
      assertThat(rr.ruleCode()).startsWith(rr.status().name() + ":");
      assertThat(rr.ruleCode()).endsWith(":" + reason.name().toUpperCase(java.util.Locale.ROOT));
    }
  }
}
