package com.non.docx.toolkit.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.non.docx.toolkit.ref.DocumentRef;
import com.non.docx.toolkit.ref.ParagraphRef;
import org.junit.jupiter.api.Test;

/** {@link ConflictKey} 单测：粗粒度同目标判定与值相等。 */
class ConflictKeyTest {

  @Test
  void sameTargetIgnoresKind() {
    // 同段落、不同 kind（替换文本 vs 改样式）仍算候选冲突
    ConflictKey a = new ConflictKey("body", "replace_text", "p:3");
    ConflictKey b = new ConflictKey("body", "set_style", "p:3");
    assertThat(a.sameTarget(b)).isTrue();
  }

  @Test
  void differentTargetNotCandidate() {
    ConflictKey a = new ConflictKey("body", "replace_text", "p:3");
    ConflictKey b = new ConflictKey("body", "replace_text", "p:4");
    assertThat(a.sameTarget(b)).isFalse();
  }

  @Test
  void differentGroupNotCandidate() {
    ConflictKey a = new ConflictKey("body", "replace_text", "p:3");
    ConflictKey b = new ConflictKey("table", "set_cell_text", "p:3");
    assertThat(a.sameTarget(b)).isFalse();
  }

  @Test
  void valueEqualityIncludesKind() {
    // equals 包含 kind；sameTarget 不包含。两个语义分离。
    ConflictKey a = new ConflictKey("body", "replace_text", "p:3");
    ConflictKey b = new ConflictKey("body", "set_style", "p:3");
    assertThat(a).isNotEqualTo(b);
    ConflictKey c = new ConflictKey("body", "replace_text", "p:3");
    assertThat(a).isEqualTo(c);
    assertThat(a.hashCode()).isEqualTo(c.hashCode());
  }

  @Test
  void sameTargetUsesStrongTypedReferenceValue() {
    DocumentRef documentRef = new DocumentRef("conversation-1", 3);
    ParagraphRef first = ParagraphRef.session(documentRef, "p-7");
    ParagraphRef sameValue = ParagraphRef.session(new DocumentRef("conversation-1", 3), "p-7");
    ParagraphRef other = ParagraphRef.session(documentRef, "p-8");

    ConflictKey replace = new ConflictKey("body", "replace_text", first);
    ConflictKey style = new ConflictKey("body", "set_style", sameValue);
    ConflictKey otherParagraph = new ConflictKey("body", "replace_text", other);

    assertThat(replace.targetRef()).isEqualTo(first);
    assertThat(replace.sameTarget(style)).isTrue();
    assertThat(replace.sameTarget(otherParagraph)).isFalse();
  }
}
