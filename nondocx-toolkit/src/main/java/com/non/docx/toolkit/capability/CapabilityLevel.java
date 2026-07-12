package com.non.docx.toolkit.capability;

/**
 * 能力稳定性与兼容性等级。
 *
 * <p>声明某项能力在 Word/WPS 双引擎下的可靠程度，供 Agent 做兼容判断， 供 CI 契约测试决定校验强度。
 */
public enum CapabilityLevel {
  /** OOXML 标准，Word/WPS 均支持，round-trip 已验证。 */
  STABLE("stable", "稳定"),

  /** 仅 Word 正确呈现（如依赖 w14:paraId 等 Word 私有扩展）。 */
  WORD_ONLY("word_only", "仅 Word"),

  /** POI 支持薄或 round-trip 未充分验证，实验性。 */
  EXPERIMENTAL("experimental", "实验性");

  private final String value;
  private final String label;

  CapabilityLevel(String value, String label) {
    this.value = value;
    this.label = label;
  }

  /** 对外契约字符串，稳定不变。 */
  public String value() {
    return value;
  }

  /** 中文标签。 */
  public String label() {
    return label;
  }

  /** 按 value 反查；找不到返回 null。 */
  public static CapabilityLevel fromValue(String value) {
    if (value == null) {
      return null;
    }
    for (CapabilityLevel level : values()) {
      if (level.value.equals(value)) {
        return level;
      }
    }
    return null;
  }
}
