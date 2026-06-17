package com.non.docx.examples;

import java.nio.file.Path;

/**
 * 示例模块的共享工具方法。
 *
 * <p>所有示例的输出文件统一写入此目录。该目录已在 {@code .gitignore} 中忽略。
 */
public final class ExamplePaths {

  /** 所有示例输出文件写入此目录（相对于工作目录）。 */
  private static final Path OUTPUT_DIR = Path.of("target", "examples-output");

  /** 返回示例输出目录的绝对路径，并在必要时创建该目录。 */
  public static Path outputDir() {
    OUTPUT_DIR.toFile().mkdirs();
    return OUTPUT_DIR;
  }

  private ExamplePaths() {}
}
