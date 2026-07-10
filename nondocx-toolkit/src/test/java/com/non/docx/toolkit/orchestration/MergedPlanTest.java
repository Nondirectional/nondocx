package com.non.docx.toolkit.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.non.docx.toolkit.orchestration.review.ReviewReason;
import com.non.docx.toolkit.orchestration.review.ReviewResult;
import com.non.docx.toolkit.orchestration.review.ReviewStatus;
import com.non.docx.toolkit.orchestration.snapshot.ParagraphPreview;
import com.non.docx.toolkit.orchestration.snapshot.QualitySummary;
import com.non.docx.toolkit.orchestration.snapshot.RevisionSummary;
import com.non.docx.toolkit.orchestration.snapshot.SnapshotOverview;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link MergedPlan} / {@link DocumentSnapshot} 核心语义单测：BLOCKED 闸门、版本与会话代次一致性。 */
class MergedPlanTest {

  private static Operation op(String id, ReviewStatus status, ReviewReason reason) {
    ReviewResult review =
        status == ReviewStatus.APPROVED ? ReviewResult.approved() : ReviewResult.of(reason, "");
    return Operation.of(
            id,
            "body",
            "replace_text",
            "p:" + id,
            Map.of(),
            new ConflictKey("body", "replace_text", "p:" + id),
            "",
            "",
            "")
        .withReview(review);
  }

  @Test
  void hasBlockedDetectsAnyBlocked() {
    MergedPlan withBlocked =
        new MergedPlan(
            "c1",
            "m1",
            List.of(),
            List.of(
                op("1", ReviewStatus.APPROVED, null),
                op("2", ReviewStatus.BLOCKED, ReviewReason.QUALITY_GATE_FAILED)));
    assertThat(withBlocked.hasBlocked()).isTrue();

    MergedPlan clean =
        new MergedPlan("c1", "m1", List.of(), List.of(op("1", ReviewStatus.APPROVED, null)));
    assertThat(clean.hasBlocked()).isFalse();
  }

  @Test
  void hasWarnedAndSkippedFlags() {
    MergedPlan m =
        new MergedPlan(
            "c1",
            "m1",
            List.of(),
            List.of(
                op("1", ReviewStatus.WARNED, ReviewReason.QUALITY_RISK),
                op("2", ReviewStatus.SKIPPED, ReviewReason.DUPLICATE_MERGED)));
    assertThat(m.hasWarned()).isTrue();
    assertThat(m.hasSkipped()).isTrue();
    assertThat(m.hasBlocked()).isFalse();
  }

  @Test
  void schemaVersionsAreFixedAtOne() {
    assertThat(MergedPlan.SCHEMA_VERSION).isEqualTo(1);
    assertThat(ExpertPlan.SCHEMA_VERSION).isEqualTo(1);
    assertThat(DocumentSnapshot.SNAPSHOT_VERSION).isEqualTo(1);
  }

  @Test
  void snapshotValidityByGeneration() {
    DocumentSnapshot snap =
        new DocumentSnapshot(
            "c1",
            "/tmp/a.docx",
            Instant.now(),
            Instant.now(),
            3L,
            new SnapshotOverview(2, 0, 0, 0, false, false, false),
            List.of(new ParagraphPreview(0, 0, "hi", null, false)),
            List.of(),
            new RevisionSummary(false, 0, Map.of()),
            new QualitySummary(0, 0, List.of()));
    assertThat(snap.isValidFor(3L)).isTrue();
    // 发生了 close/reopen，代次递增到 4，旧快照失效
    assertThat(snap.isValidFor(4L)).isFalse();
    assertThat(snap.sessionGeneration()).isEqualTo(3L);
  }
}
