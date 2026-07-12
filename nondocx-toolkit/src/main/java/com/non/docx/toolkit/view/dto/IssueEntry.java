package com.non.docx.toolkit.view.dto;

import java.util.Objects;

/**
 * 问题视图单项：复用 {@code QualityCheckTools.CheckResult} 的检查结果。
 *
 * <p><b>浅层先行（P0-04 D2）</b>：不建 DocumentIssue 模型和 issue code 目录，留 P1-01。 {@code checkName} 复用 {@code
 * QualityCheckTools.ALL_CHECKS} 的名称。
 *
 * @param checkName 检查项名称（如 {@code "blank-pages"/"line-spacing"/...}）
 * @param passed 是否通过（issues 列表只含 {@code passed=false} 的项）
 * @param severity 严重级别：{@code "error"} 或 {@code "warning"}
 * @param message 中文检查消息
 */
public final class IssueEntry {

  private final String checkName;
  private final boolean passed;
  private final String severity;
  private final String message;

  public IssueEntry(String checkName, boolean passed, String severity, String message) {
    this.checkName = Objects.requireNonNull(checkName, "checkName 不能为空");
    this.passed = passed;
    this.severity = Objects.requireNonNull(severity, "severity 不能为空");
    this.message = Objects.requireNonNull(message, "message 不能为空");
  }

  public String checkName() {
    return checkName;
  }

  public boolean passed() {
    return passed;
  }

  public String severity() {
    return severity;
  }

  public String message() {
    return message;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof IssueEntry)) return false;
    IssueEntry that = (IssueEntry) o;
    return passed == that.passed
        && checkName.equals(that.checkName)
        && severity.equals(that.severity)
        && message.equals(that.message);
  }

  @Override
  public int hashCode() {
    return Objects.hash(checkName, passed, severity, message);
  }
}
