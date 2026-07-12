package com.non.docx.toolkit.orchestration.commit;

import com.non.docx.toolkit.result.ToolResultParser;

/**
 * executor 层统一工具结果检查（双模式兼容层）。
 *
 * <p>迁移混合期：优先解析结构化 JSON envelope（由 {@link ToolResultParser} 产出）； 未迁移的工具仍返回纯中文前缀，回退旧 {@code
 * startsWith("错误")} / {@code contains("错误:")} 路径。全量迁移完成后，回退路径将被移除（见 P0-02 切片 8）。
 *
 * <p>所有 executor 的 {@code checkResult} 必须走此工具类，禁止各自复制 {@code startsWith} 逻辑，也不得新增 {@code
 * contains("错误")} 判断。
 */
public final class ToolResultChecks {

  /** 旧失败前缀。 */
  private static final String LEGACY_ERROR_PREFIX = "错误";

  private ToolResultChecks() {}

  /**
   * 检查工具返回串是否表示失败。
   *
   * <p>双模式：
   *
   * <ol>
   *   <li>优先用 {@link ToolResultParser#parse(String)} 解析结构化 envelope；解析到则按 {@code success} 字段判定。
   *   <li>未解析到（旧格式纯中文），回退 {@code startsWith("错误")} 和 {@code contains("错误:")}/{@code
   *       contains("错误：")} 兼容检查。
   * </ol>
   *
   * @param result 工具返回串
   * @param domain 域名（如 {@code "body"}、{@code "table"}），用于失败消息
   * @param kind operation kind，用于失败消息
   * @return 原始返回串（成功路径）
   * @throws OperationExecutionException 若表示失败
   */
  public static String checkResult(String result, String domain, String kind)
      throws OperationExecutionException {
    if (result == null) {
      throw new OperationExecutionException(domain + "/" + kind + " 返回 null");
    }
    // 新路径：结构化 envelope
    ToolResultParser.Snapshot snapshot = ToolResultParser.parse(result);
    if (snapshot != null) {
      if (snapshot.isFailure()) {
        throw new OperationExecutionException(
            snapshot.message().isEmpty() ? result : snapshot.message());
      }
      return result;
    }
    // 旧路径：纯中文前缀（混合期回退，切片 8 移除）
    if (result.startsWith(LEGACY_ERROR_PREFIX)) {
      throw new OperationExecutionException(result);
    }
    if (result.contains("错误:") || result.contains("错误：")) {
      throw new OperationExecutionException(domain + "/" + kind + " 执行失败: " + result);
    }
    return result;
  }

  /**
   * 判断工具返回串是否表示失败（不抛异常）。
   *
   * <p>用于非 commit 路径（如 {@code DocxOrchestrator.open/reopen}）。
   *
   * @param result 工具返回串
   * @return true 表示失败；null 返回 true
   */
  public static boolean isFailure(String result) {
    if (result == null) {
      return true;
    }
    ToolResultParser.Snapshot snapshot = ToolResultParser.parse(result);
    if (snapshot != null) {
      return snapshot.isFailure();
    }
    // 旧路径回退
    return result.startsWith(LEGACY_ERROR_PREFIX)
        || result.contains("错误:")
        || result.contains("错误：");
  }

  /**
   * 从工具返回串中提取 data 字段的文本值。
   *
   * <p>用于成功时获取结构化负载（如 {@code open_docx} 返回的 docId）。 若返回的是结构化 envelope，解析其中的 {@code data}
   * 字段；若是旧格式裸字符串（如裸 {@code "doc-1"}），直接返回原串。
   *
   * @param result 工具返回串
   * @return data 文本；无结构化 envelope 时返回原串
   */
  public static String extractData(String result) {
    if (result == null) {
      return null;
    }
    ToolResultParser.Snapshot snapshot = ToolResultParser.parse(result);
    if (snapshot != null && snapshot.dataText() != null) {
      return snapshot.dataText();
    }
    // 旧格式：返回串本身就是 data（如裸 docId）
    return result;
  }
}
