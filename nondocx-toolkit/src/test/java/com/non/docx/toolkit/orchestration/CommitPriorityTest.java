package com.non.docx.toolkit.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.non.docx.toolkit.orchestration.commit.CommitPriority;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link CommitPriority} 单测：固定优先级排序与档位映射。 */
class CommitPriorityTest {

  private static Operation op(String id, String group, String kind, String target) {
    return Operation.of(
        id, group, kind, target, Map.of(), new ConflictKey(group, kind, target), "", "", "");
  }

  @Test
  void sortsByFixedPriority() {
    // 故意乱序：文本、修订、结构、质量、保存前检查
    Operation text = op("1", "body", "replace_text", "p:1");
    Operation revision = op("2", "revision", "accept", "rev:1");
    Operation structure = op("3", "body", "insert_paragraph", "p:0");
    Operation quality = op("4", "quality", "check_layout", "doc");
    Operation preSave = op("5", "body", "pre_save_check", "doc");

    List<Operation> sorted =
        new CommitPriority().sortByPriority(List.of(text, revision, structure, quality, preSave));

    // 期望：structure(0) -> text(1) -> revision(2) -> quality(3) -> preSave(4)
    assertThat(sorted).extracting(Operation::operationId).containsExactly("3", "1", "2", "4", "5");
  }

  @Test
  void stableOrderWithinSameTier() {
    // 同为 TEXT_STYLE，保持原相对顺序
    Operation a = op("a", "body", "replace_text", "p:1");
    Operation b = op("b", "body", "set_style", "p:2");
    List<Operation> sorted = new CommitPriority().sortByPriority(List.of(a, b));
    assertThat(sorted).extracting(Operation::operationId).containsExactly("a", "b");
  }

  @Test
  void tierMapping() {
    CommitPriority p = new CommitPriority();
    assertThat(p.tierOf(op("1", "body", "insert_paragraph", "p:0")))
        .isEqualTo(CommitPriority.Tier.STRUCTURE);
    assertThat(p.tierOf(op("1", "body", "delete_paragraph", "p:0")))
        .isEqualTo(CommitPriority.Tier.STRUCTURE);
    assertThat(p.tierOf(op("1", "body", "replace_text", "p:1")))
        .isEqualTo(CommitPriority.Tier.TEXT_STYLE);
    assertThat(p.tierOf(op("1", "revision", "accept", "rev:1")))
        .isEqualTo(CommitPriority.Tier.REVISION);
    assertThat(p.tierOf(op("1", "quality", "check_layout", "doc")))
        .isEqualTo(CommitPriority.Tier.QUALITY_RECHECK);
    assertThat(p.tierOf(op("1", "body", "pre_save_check", "doc")))
        .isEqualTo(CommitPriority.Tier.PRE_SAVE_CHECK);
  }
}
