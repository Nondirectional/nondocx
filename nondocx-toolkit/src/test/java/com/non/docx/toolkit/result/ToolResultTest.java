package com.non.docx.toolkit.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link ToolResult} 值对象语义测试。 */
class ToolResultTest {

  @Test
  void okFactoriesSetCodeToOk() {
    ToolResult<String> r = ToolResult.ok("data", "msg");
    assertThat(r.success()).isTrue();
    assertThat(r.code()).isEqualTo(ToolResultCode.OK);
    assertThat(r.data()).isEqualTo("data");
  }

  @Test
  void failFactoryRejectsOkCode() {
    assertThatThrownBy(() -> ToolResult.fail(ToolResultCode.OK, "msg"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void failFactoryRejectsNullCode() {
    assertThatThrownBy(() -> ToolResult.fail(null, "msg"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void changedRefsImmutableAndCopied() {
    List<String> refs = new java.util.ArrayList<>();
    refs.add("ref-1");
    ToolResult<Void> r = ToolResult.ok(null, "msg", refs);

    refs.add("ref-2"); // 修改原 list 不影响结果
    assertThat(r.changedRefs()).containsExactly("ref-1");
  }

  @Test
  void warningsImmutableAndCopied() {
    List<ToolWarning> warnings = new java.util.ArrayList<>();
    warnings.add(ToolWarning.of("w1", "警告1"));
    ToolResult<Void> r = ToolResult.partial(null, "msg", warnings);

    warnings.add(ToolWarning.of("w2", "警告2"));
    assertThat(r.warnings()).hasSize(1);
  }

  @Test
  void withChangedRefReturnsNewInstance() {
    ToolResult<Void> r = ToolResult.ok(null, "msg");
    ToolResult<Void> r2 = r.withChangedRef("ref-1");

    assertThat(r.changedRefs()).isEmpty();
    assertThat(r2.changedRefs()).containsExactly("ref-1");
    assertThat(r).isNotSameAs(r2);
  }

  @Test
  void withWarningReturnsNewInstance() {
    ToolResult<Void> r = ToolResult.ok(null, "msg");
    ToolResult<Void> r2 = r.withWarning(ToolWarning.of("w", "警告"));

    assertThat(r.warnings()).isEmpty();
    assertThat(r2.warnings()).hasSize(1);
  }

  @Test
  void mapDataChangesTypePreservingOtherFields() {
    ToolResult<String> r = ToolResult.ok("data", "msg", List.of("ref"));
    ToolResult<Integer> r2 = r.mapData(42);

    assertThat(r2.data()).isEqualTo(42);
    assertThat(r2.message()).isEqualTo("msg");
    assertThat(r2.changedRefs()).containsExactly("ref");
  }

  @Test
  void equalsByContent() {
    ToolResult<String> a = ToolResult.ok("data", "msg", List.of("ref"));
    ToolResult<String> b = ToolResult.ok("data", "msg", List.of("ref"));
    ToolResult<String> c = ToolResult.ok("other", "msg");

    assertThat(a).isEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
    assertThat(a).isNotEqualTo(c);
  }

  @Test
  void partialFailureHasWarningsAndPartialCode() {
    ToolResult<Void> r = ToolResult.partial(null, "部分失败", List.of(ToolWarning.of("w", "警告")));

    assertThat(r.success()).isFalse();
    assertThat(r.code()).isEqualTo(ToolResultCode.PARTIAL_FAILURE);
    assertThat(r.warnings()).hasSize(1);
  }

  @Test
  void nullMessageNormalizedToEmpty() {
    ToolResult<Void> r = ToolResult.ok(null);
    assertThat(r.message()).isEmpty();
  }

  @Test
  void nullSuggestionNormalizedToEmpty() {
    ToolResult<Void> r = ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "msg", null);
    assertThat(r.suggestion()).isEmpty();
  }
}
