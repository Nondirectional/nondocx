package com.non.docx.toolkit;

import com.non.docx.toolkit.orchestration.commit.ToolResultChecks;
import com.non.docx.toolkit.result.ToolResultParser;

/**
 * 测试支持工具：从结构化工具返回串中提取字段。
 *
 * <p>P0-02 之前，工具返回裸字符串（如 {@code "doc-1"}），测试直接使用。P0-02 之后，工具返回 双段格式（中文 + JSON
 * envelope），测试需用本类提取结构化字段。
 */
public final class ToolTestSupport {

  private ToolTestSupport() {}

  /**
   * 从 openDocx 返回串中提取 docId。
   *
   * <p>兼容旧格式（裸 {@code "doc-1"}）和新格式（JSON envelope 的 data 字段）。
   *
   * @param raw openDocx 的返回串
   * @return docId（如 {@code "doc-1"}）
   */
  public static String extractDocId(String raw) {
    return ToolResultChecks.extractData(raw);
  }

  /**
   * 断言工具返回串表示失败。
   *
   * <p>兼容旧格式（中文前缀）和新格式（JSON envelope 的 success=false）。
   *
   * @param raw 工具返回串
   * @return true 表示失败
   */
  public static boolean isFailure(String raw) {
    return ToolResultChecks.isFailure(raw);
  }

  /**
   * 解析工具返回串的结构化快照。
   *
   * @param raw 工具返回串
   * @return 结构化快照；无 envelope 返回 null
   */
  public static ToolResultParser.Snapshot parse(String raw) {
    return ToolResultParser.parse(raw);
  }
}
