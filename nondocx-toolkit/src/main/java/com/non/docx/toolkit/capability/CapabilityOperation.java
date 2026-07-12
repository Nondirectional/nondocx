package com.non.docx.toolkit.capability;

/**
 * 工具操作类别。覆盖 nondocx-toolkit 现有 56 个工具的全部动词前缀。
 *
 * <p>每个值携带稳定 {@link #value()}（对外契约字符串）和中文 {@link #label()}。
 */
public enum CapabilityOperation {
  READ("read", "读取"),
  ADD("add", "新增"),
  UPDATE("update", "修改"),
  REMOVE("remove", "删除"),
  QUERY("query", "查询"),
  SESSION("session", "会话"),
  QUALITY("quality", "质量检查");

  private final String value;
  private final String label;

  CapabilityOperation(String value, String label) {
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
  public static CapabilityOperation fromValue(String value) {
    if (value == null) {
      return null;
    }
    for (CapabilityOperation op : values()) {
      if (op.value.equals(value)) {
        return op;
      }
    }
    return null;
  }
}
