package com.non.docx.core.internal.util;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * 内部 API — 如有变更，恕不另行通知。
 *
 * <p>用于 IO 边界（{@code Docx}）的流排空和资源清理辅助。这些辅助函数集中了 nondocx 的 流所有权规则：nondocx
 * 从不关闭它未打开的流，并且尽力而为的清理绝不能掩盖主要失败。
 */
public final class Streams {

  private Streams() {}

  /**
   * 将给定流完全排空到新分配的字节数组中。流<em>不会</em>被关闭；调用者保留所有权并负责关闭它。
   *
   * <p>用于 {@code Docx.open(InputStream)} 在构造文档前完全缓冲调用者的内容， 而不获取调用者流的所有权。
   *
   * @param in 要排空的流（不由此方法关闭）
   * @return 流的剩余内容作为字节数组
   * @throws IOException 如果从流中读取失败
   */
  public static byte[] readAllBytes(InputStream in) throws IOException {
    return in.readAllBytes();
  }

  /**
   * 关闭给定的 {@link Closeable}，吞掉任何 {@link IOException}。用于清理路径， 其中关闭失败绝不能掩盖主要失败。如果为 {@code
   * null}，则不执行任何操作。
   *
   * @param closeable 要关闭的资源，或 {@code null}
   */
  public static void closeQuietly(Closeable closeable) {
    if (closeable == null) {
      return;
    }
    try {
      closeable.close();
    } catch (IOException ignored) {
      // 尽力而为的清理；有意忽略
    }
  }
}
