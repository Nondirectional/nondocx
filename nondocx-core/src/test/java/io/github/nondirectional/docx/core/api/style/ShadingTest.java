package io.github.nondirectional.docx.core.api.style;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 验证 {@link Shading} 值对象的内容相等性、工厂方法和 SOLID 排除契约。
 *
 * <p>这是 WPS/Word 兼容性 spec（{@code renderer-compatibility.md#shading-solid}）的 load-bearing 测试之一:确认
 * nondocx 公开 API 不暴露 {@code SOLID} 入口。
 */
class ShadingTest {

  @Test
  void singleArgFactoryDefaultsToClearPatternAndNullColor() {
    Shading s = Shading.of("F1F5F9");
    assertThat(s.fill()).isEqualTo("F1F5F9");
    assertThat(s.pattern()).isEqualTo(ShadingPattern.CLEAR);
    assertThat(s.color()).isNull();
  }

  @Test
  void twoArgFactoryDefaultsColorToNull() {
    Shading s = Shading.of("F1F5F9", ShadingPattern.NIL);
    assertThat(s.fill()).isEqualTo("F1F5F9");
    assertThat(s.pattern()).isEqualTo(ShadingPattern.NIL);
    assertThat(s.color()).isNull();
  }

  @Test
  void threeArgFactoryPreservesAllFields() {
    Shading s = Shading.of("F1F5F9", ShadingPattern.CLEAR, "000000");
    assertThat(s.fill()).isEqualTo("F1F5F9");
    assertThat(s.pattern()).isEqualTo(ShadingPattern.CLEAR);
    assertThat(s.color()).isEqualTo("000000");
  }

  @Test
  void rejectsNullFill() {
    assertThatThrownBy(() -> Shading.of(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Shading.of(null, ShadingPattern.CLEAR, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNullPattern() {
    assertThatThrownBy(() -> Shading.of("F1F5F9", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void equalsComparesFillPatternColor() {
    Shading a = Shading.of("F1F5F9", ShadingPattern.CLEAR, "000000");
    Shading b = Shading.of("F1F5F9", ShadingPattern.CLEAR, "000000");
    Shading diffFill = Shading.of("EEEEEE", ShadingPattern.CLEAR, "000000");
    Shading diffPattern = Shading.of("F1F5F9", ShadingPattern.NIL, "000000");
    Shading diffColor = Shading.of("F1F5F9", ShadingPattern.CLEAR, "111111");
    Shading nullColor = Shading.of("F1F5F9", ShadingPattern.CLEAR, null);

    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    assertThat(a).isNotEqualTo(diffFill);
    assertThat(a).isNotEqualTo(diffPattern);
    assertThat(a).isNotEqualTo(diffColor);
    assertThat(a).isNotEqualTo(nullColor);
    assertThat(a).isNotEqualTo("not a shading");
    assertThat(a).isEqualTo(a);
  }

  @Test
  void shadingPatternEnumExcludesSolid() {
    // WPS 渲染 SOLID 为黑块 — nondocx 公开 API 必须不暴露 SOLID
    assertThat(ShadingPattern.values()).containsOnly(ShadingPattern.CLEAR, ShadingPattern.NIL);
  }

  @Test
  void toStringMentionsAllThreeFields() {
    Shading s = Shading.of("F1F5F9", ShadingPattern.CLEAR, "000000");
    String str = s.toString();
    assertThat(str).contains("F1F5F9").contains("CLEAR").contains("000000");
  }
}
