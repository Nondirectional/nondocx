package io.github.nondirectional.docx.toolkit.result;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nondirectional.docx.toolkit.ref.RefResolutionCode;
import org.junit.jupiter.api.Test;

/** {@link ToolResultCode} 与 {@link RefResolutionCode} 映射测试。 */
class ToolResultCodeTest {

  @Test
  void okIsSuccess() {
    assertThat(ToolResultCode.OK.isSuccess()).isTrue();
  }

  @Test
  void nonOkCodesAreNotSuccess() {
    for (ToolResultCode code : ToolResultCode.values()) {
      if (code != ToolResultCode.OK) {
        assertThat(code.isSuccess()).isFalse();
      }
    }
  }

  @Test
  void fromValueRoundTrips() {
    for (ToolResultCode code : ToolResultCode.values()) {
      assertThat(ToolResultCode.fromValue(code.value())).isEqualTo(code);
    }
  }

  @Test
  void fromValueReturnsNullForUnknown() {
    assertThat(ToolResultCode.fromValue("nonexistent_code")).isNull();
    assertThat(ToolResultCode.fromValue(null)).isNull();
  }

  @Test
  void refResolutionCodeMapsToToolResultCode() {
    assertThat(RefResolutionCode.STALE_REF.toToolResultCode()).isEqualTo(ToolResultCode.STALE_REF);
    assertThat(RefResolutionCode.ELEMENT_REMOVED.toToolResultCode())
        .isEqualTo(ToolResultCode.ELEMENT_REMOVED);
    assertThat(RefResolutionCode.GENERATION_MISMATCH.toToolResultCode())
        .isEqualTo(ToolResultCode.GENERATION_MISMATCH);
    assertThat(RefResolutionCode.DOCUMENT_MISMATCH.toToolResultCode())
        .isEqualTo(ToolResultCode.DOCUMENT_MISMATCH);
    assertThat(RefResolutionCode.REF_TYPE_MISMATCH.toToolResultCode())
        .isEqualTo(ToolResultCode.REF_TYPE_MISMATCH);
    assertThat(RefResolutionCode.INVALID_REF.toToolResultCode())
        .isEqualTo(ToolResultCode.INVALID_REF);
  }

  @Test
  void refAndToolResultCodesShareValueStrings() {
    for (RefResolutionCode ref : RefResolutionCode.values()) {
      ToolResultCode mapped = ref.toToolResultCode();
      assertThat(mapped.value()).isEqualTo(ref.value());
    }
  }

  @Test
  void retryableFlagSet() {
    assertThat(ToolResultCode.INVALID_ARGUMENT.retryable()).isTrue();
    assertThat(ToolResultCode.INDEX_OUT_OF_RANGE.retryable()).isTrue();
    assertThat(ToolResultCode.ELEMENT_REMOVED.retryable()).isFalse();
    assertThat(ToolResultCode.UNSUPPORTED_FEATURE.retryable()).isFalse();
  }
}
