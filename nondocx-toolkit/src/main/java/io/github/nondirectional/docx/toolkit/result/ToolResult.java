package io.github.nondirectional.docx.toolkit.result;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 统一结构化工具结果。
 *
 * <p>不可变值对象。同时承载机器可读字段（{@link #success}、{@link #code}、{@link #data}）和 中文人类可读消息（{@link #message}）。通过
 * {@link ToolResultRenderer#render(ToolResult)} 产出双段 String（中文 + JSON fence），供 nonchain
 * {@code @ToolDef} 方法返回。
 *
 * <p>成功时 {@code success=true}、{@code code=OK}；失败时 {@code success=false}、 {@code code}
 * 为具体错误码。批量部分失败用 {@code code=PARTIAL_FAILURE}，单项成败见 {@link BatchItemResult}。
 *
 * @param <T> data 负载类型，应为轻量值对象，不含 POI 类型
 */
public final class ToolResult<T> {

  private final boolean success;
  private final ToolResultCode code;
  private final String message;
  private final T data;
  private final List<ToolWarning> warnings;
  private final List<String> changedRefs;
  private final Integer matchedCount;
  private final Integer changedCount;
  private final Integer skippedCount;
  private final String suggestion;

  private ToolResult(
      boolean success,
      ToolResultCode code,
      String message,
      T data,
      List<ToolWarning> warnings,
      List<String> changedRefs,
      Integer matchedCount,
      Integer changedCount,
      Integer skippedCount,
      String suggestion) {
    this.success = success;
    this.code = code;
    this.message = message == null ? "" : message;
    this.data = data;
    this.warnings = warnings == null ? Collections.emptyList() : List.copyOf(warnings);
    this.changedRefs = changedRefs == null ? Collections.emptyList() : List.copyOf(changedRefs);
    this.matchedCount = matchedCount;
    this.changedCount = changedCount;
    this.skippedCount = skippedCount;
    this.suggestion = suggestion == null ? "" : suggestion;
  }

  // ===== 成功工厂 =====

  /**
   * 成功结果。
   *
   * @param data 机器可读负载
   * @param message 中文消息
   */
  public static <T> ToolResult<T> ok(T data, String message) {
    return new ToolResult<>(
        true, ToolResultCode.OK, message, data, null, null, null, null, null, null);
  }

  /**
   * 成功结果，带变更的 ref 列表。
   *
   * @param data 机器可读负载
   * @param message 中文消息
   * @param changedRefs 写操作实际影响的 canonical ref
   */
  public static <T> ToolResult<T> ok(T data, String message, List<String> changedRefs) {
    return new ToolResult<>(
        true, ToolResultCode.OK, message, data, null, changedRefs, null, null, null, null);
  }

  /**
   * 成功结果，带匹配数和变更 ref。
   *
   * @param data 机器可读负载
   * @param message 中文消息
   * @param matchedCount 多目标匹配数
   * @param changedRefs 写操作实际影响的 canonical ref
   */
  public static <T> ToolResult<T> ok(
      T data, String message, Integer matchedCount, List<String> changedRefs) {
    return new ToolResult<>(
        true, ToolResultCode.OK, message, data, null, changedRefs, matchedCount, null, null, null);
  }

  /**
   * 成功结果，带匹配数、改动数和变更 ref。
   *
   * @param data 机器可读负载
   * @param message 中文消息
   * @param matchedCount 多目标匹配数
   * @param changedCount 实际改动数（≤ matchedCount）
   * @param changedRefs 写操作实际影响的 canonical ref
   */
  public static <T> ToolResult<T> ok(
      T data,
      String message,
      Integer matchedCount,
      Integer changedCount,
      List<String> changedRefs) {
    return new ToolResult<>(
        true,
        ToolResultCode.OK,
        message,
        data,
        null,
        changedRefs,
        matchedCount,
        changedCount,
        null,
        null);
  }

  /** 无 data 的成功结果。 */
  public static ToolResult<Void> ok(String message) {
    return new ToolResult<>(
        true, ToolResultCode.OK, message, null, null, null, null, null, null, null);
  }

  // ===== 失败工厂 =====

  /**
   * 失败结果。
   *
   * @param code 错误码（不可为 OK）
   * @param message 中文消息
   */
  public static ToolResult<Void> fail(ToolResultCode code, String message) {
    validateFailCode(code);
    return new ToolResult<>(false, code, message, null, null, null, null, null, null, null);
  }

  /**
   * 失败结果，带可重试建议。
   *
   * @param code 错误码
   * @param message 中文消息
   * @param suggestion 可重试建议
   */
  public static ToolResult<Void> fail(ToolResultCode code, String message, String suggestion) {
    validateFailCode(code);
    return new ToolResult<>(false, code, message, null, null, null, null, null, null, suggestion);
  }

  // ===== 部分失败工厂 =====

  /**
   * 部分失败结果（success=false, code=PARTIAL_FAILURE），带警告列表。
   *
   * @param data 机器可读负载（可为 {@code List<BatchItemResult>}）
   * @param message 中文消息
   * @param warnings 警告列表
   */
  public static <T> ToolResult<T> partial(T data, String message, List<ToolWarning> warnings) {
    return new ToolResult<>(
        false,
        ToolResultCode.PARTIAL_FAILURE,
        message,
        data,
        warnings,
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * 部分失败结果，自定义错误码。
   *
   * @param code 错误码（不可为 OK）
   * @param data 机器可读负载
   * @param message 中文消息
   * @param warnings 警告列表
   */
  public static <T> ToolResult<T> partial(
      ToolResultCode code, T data, String message, List<ToolWarning> warnings) {
    validateFailCode(code);
    return new ToolResult<>(false, code, message, data, warnings, null, null, null, null, null);
  }

  /**
   * 部分失败结果，带计数（用于批量 stopOnError 场景）。
   *
   * @param code 错误码（不可为 OK）
   * @param data 机器可读负载
   * @param message 中文消息
   * @param warnings 警告列表
   * @param matchedCount 匹配的目标数
   * @param changedCount 实际改动数
   * @param skippedCount 未执行数（stop 模式）
   */
  public static <T> ToolResult<T> partial(
      ToolResultCode code,
      T data,
      String message,
      List<ToolWarning> warnings,
      Integer matchedCount,
      Integer changedCount,
      Integer skippedCount) {
    validateFailCode(code);
    return new ToolResult<>(
        false, code, message, data, warnings, null, matchedCount, changedCount, skippedCount, null);
  }

  // ===== 带 suggestion 的成功 =====

  /**
   * 成功结果，带建议（如兼容性提示）。
   *
   * @param data 机器可读负载
   * @param message 中文消息
   * @param suggestion 建议
   */
  public static <T> ToolResult<T> okWith(T data, String message, String suggestion) {
    return new ToolResult<>(
        true, ToolResultCode.OK, message, data, null, null, null, null, null, suggestion);
  }

  // ===== 访问器 =====

  /** 是否成功。 */
  public boolean success() {
    return success;
  }

  /** 结果码。 */
  public ToolResultCode code() {
    return code;
  }

  /** 中文消息。 */
  public String message() {
    return message;
  }

  /** 机器可读负载，无则为 null。 */
  public T data() {
    return data;
  }

  /** 警告列表，无则为空列表。 */
  public List<ToolWarning> warnings() {
    return warnings;
  }

  /** 写操作变更的 canonical ref 列表，无则为空列表。 */
  public List<String> changedRefs() {
    return changedRefs;
  }

  /** 多目标匹配数，无则为 null。 */
  public Integer matchedCount() {
    return matchedCount;
  }

  /** 实际改动数（≤ matchedCount），无则为 null。 */
  public Integer changedCount() {
    return changedCount;
  }

  /** 未执行数（stopOnError 模式下因前面失败而未执行的条目数），无则为 null。 */
  public Integer skippedCount() {
    return skippedCount;
  }

  /** 可重试建议，无则为空字符串。 */
  public String suggestion() {
    return suggestion;
  }

  /**
   * 附加变更 ref，返回新实例（不可变）。
   *
   * @param ref canonical ref
   */
  public ToolResult<T> withChangedRef(String ref) {
    List<String> refs = new ArrayList<>(changedRefs);
    refs.add(ref);
    return new ToolResult<>(
        success,
        code,
        message,
        data,
        warnings,
        refs,
        matchedCount,
        changedCount,
        skippedCount,
        suggestion);
  }

  /**
   * 附加警告，返回新实例（不可变）。
   *
   * @param warning 警告
   */
  public ToolResult<T> withWarning(ToolWarning warning) {
    List<ToolWarning> list = new ArrayList<>(warnings);
    list.add(warning);
    return new ToolResult<>(
        success,
        code,
        message,
        data,
        list,
        changedRefs,
        matchedCount,
        changedCount,
        skippedCount,
        suggestion);
  }

  /**
   * 映射 data 类型，保留其余字段。
   *
   * @param newData 新 data
   * @param <R> 新 data 类型
   */
  public <R> ToolResult<R> mapData(R newData) {
    return new ToolResult<>(
        success,
        code,
        message,
        newData,
        warnings,
        changedRefs,
        matchedCount,
        changedCount,
        skippedCount,
        suggestion);
  }

  private static void validateFailCode(ToolResultCode code) {
    if (code == null) {
      throw new IllegalArgumentException("code 不能为空");
    }
    if (code == ToolResultCode.OK) {
      throw new IllegalArgumentException("失败结果不能用 OK 码");
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ToolResult)) return false;
    ToolResult<?> that = (ToolResult<?>) o;
    return success == that.success
        && code == that.code
        && message.equals(that.message)
        && Objects.equals(data, that.data)
        && warnings.equals(that.warnings)
        && changedRefs.equals(that.changedRefs)
        && Objects.equals(matchedCount, that.matchedCount)
        && Objects.equals(changedCount, that.changedCount)
        && Objects.equals(skippedCount, that.skippedCount)
        && suggestion.equals(that.suggestion);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        success,
        code,
        message,
        data,
        warnings,
        changedRefs,
        matchedCount,
        changedCount,
        skippedCount,
        suggestion);
  }

  @Override
  public String toString() {
    return ToolResultRenderer.render(this);
  }
}
