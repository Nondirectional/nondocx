package io.github.nondirectional.docx.core.api.style;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Verifies content equality, null-safety, immutability, and hashCode consistency for {@link
 * RunStyle}.
 */
class RunStyleTest {

  @Test
  void equalWhenAllSixAttributesMatch() {
    RunStyle a = RunStyle.of(true, false, true, "Arial", 12, "FF0000");
    RunStyle b = RunStyle.of(true, false, true, "Arial", 12, "FF0000");

    assertThat(a).isEqualTo(b);
    assertThat(b).isEqualTo(a);
  }

  @Test
  void notEqualWhenAnyAttributeDiffers() {
    RunStyle base = RunStyle.of(true, false, false, "Arial", 12, "FF0000");

    assertThat(base.bold(false)).isNotEqualTo(base);
    assertThat(base.italic(true)).isNotEqualTo(base);
    assertThat(base.underline(true)).isNotEqualTo(base);
    assertThat(base.font("Calibri")).isNotEqualTo(base);
    assertThat(base.size(14)).isNotEqualTo(base);
    assertThat(base.color("00FF00")).isNotEqualTo(base);
  }

  @Test
  void nullFieldsAreSafeAndContentEqual() {
    RunStyle a = RunStyle.empty();
    RunStyle b = RunStyle.of(false, false, false, null, null, null);

    assertThat(a).isEqualTo(b);

    assertThat(a.font()).isNull();
    assertThat(a.size()).isNull();
    assertThat(a.color()).isNull();
  }

  @Test
  void notEqualToNullAndNotEqualToUnrelatedType() {
    RunStyle style = RunStyle.of(true, true, true, "Arial", 12, "000000");

    assertThat(style).isNotEqualTo(null);
    assertThat(style).isNotEqualTo("not a RunStyle");
  }

  @Test
  void hashCodeIsConsistentWithEquals() {
    RunStyle a = RunStyle.of(true, false, true, "Arial", 12, "FF0000");
    RunStyle b = RunStyle.of(true, false, true, "Arial", 12, "FF0000");

    assertThat(a).isEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
    assertThat(a.hashCode()).isEqualTo(a.hashCode());
  }

  @Test
  void emptyIsASingletonBaseline() {
    assertThat(RunStyle.empty()).isSameAs(RunStyle.empty());

    RunStyle empty = RunStyle.empty();
    assertThat(empty.isBold()).isFalse();
    assertThat(empty.isItalic()).isFalse();
    assertThat(empty.isUnderline()).isFalse();
    assertThat(empty.font()).isNull();
    assertThat(empty.size()).isNull();
    assertThat(empty.color()).isNull();
  }

  @Test
  void withersReturnNewInstanceAndLeaveOriginalUnchanged() {
    RunStyle base = RunStyle.of(true, false, false, "Arial", 12, "FF0000");

    RunStyle modified = base.bold(false).font("Calibri");

    assertThat(modified.isBold()).isFalse();
    assertThat(modified.font()).isEqualTo("Calibri");
    // unchanged siblings are preserved on the modified copy
    assertThat(modified.isItalic()).isFalse();
    assertThat(modified.size()).isEqualTo(12);
    assertThat(modified.color()).isEqualTo("FF0000");

    // the original instance is immutable and unchanged
    assertThat(base.isBold()).isTrue();
    assertThat(base.font()).isEqualTo("Arial");
  }

  @Test
  void toStringMentionsAllAttributes() {
    RunStyle style = RunStyle.of(true, false, true, "Arial", 12, "FF0000");
    String s = style.toString();

    assertThat(s)
        .contains("bold=true")
        .contains("italic=false")
        .contains("underline=true")
        .contains("Arial")
        .contains("12")
        .contains("FF0000");
  }
}
