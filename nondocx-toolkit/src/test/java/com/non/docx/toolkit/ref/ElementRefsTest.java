package com.non.docx.toolkit.ref;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ElementRefsTest {

  @Test
  void canonicalRoundTripsAllReferenceMetadata() {
    ParagraphRef ref = ParagraphRef.persistent(new DocumentRef("会话/alpha", 2L), "00A1B2C3");

    ElementRef parsed = ElementRefs.parse(ref.canonical());

    assertThat(parsed).isEqualTo(ref);
    assertThat(parsed.kind()).isEqualTo(ElementKind.PARAGRAPH);
    assertThat(parsed.stability()).isEqualTo(RefStability.PERSISTENT);
  }

  @Test
  void invalidCanonicalReturnsStableCode() {
    assertThatThrownBy(() -> ElementRefs.parse("not-a-ref"))
        .isInstanceOfSatisfying(
            RefResolutionException.class,
            e -> assertThat(e.code()).isEqualTo(RefResolutionCode.INVALID_REF));
  }
}
