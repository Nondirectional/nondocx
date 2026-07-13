package com.non.docx.demo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 把 classpath 里的内置样例 {@code .docx} 落到磁盘工作目录。
 *
 * <p><b>为什么需要这一步。</b> demo 的受限文档工具操作的是<b>磁盘文件</b>，而内置样例打包在 jar 里（classpath 资源），Agent 不能直接 {@code
 * open_docx("/classpath/...")}。 故启动时把样例 复制一份到工作目录 {@code target/demo-work/current.docx}，Agent
 * 再对这个磁盘文件 {@code open_docx}。
 *
 * <p><b>样例 vs 上传。</b> 样例是 demo 的「空白起步」文档；用户点「上传文档」会用自有 .docx 覆盖同一个 {@code current.docx}（见 {@code
 * DocSession}）。两者落到同一文件，只是来源不同。
 */
final class SampleDocSeeder {

  /** classpath 里的样例资源路径。 */
  static final String SAMPLE_RESOURCE = "/sample-input.docx";

  /** 样例文档的显示名（前端展示用）。 */
  static final String SAMPLE_FILENAME = "nondocx 样例.docx";

  private final Path workDir;

  /**
   * @param workDir 工作目录（运行时创建），样例复制到此目录下的 {@code current.docx}。
   */
  SampleDocSeeder(Path workDir) {
    this.workDir = workDir;
  }

  /**
   * 把 classpath 样例复制到 {@code <workDir>/current.docx}，返回该路径。 已存在则覆盖（每次启动都拿一份干净的样例）。
   *
   * @return 工作目录下的 current.docx 路径
   * @throws IOException 复制失败（资源缺失 / 磁盘错误）
   */
  Path seed() throws IOException {
    Files.createDirectories(workDir);
    Path target = workDir.resolve("current.docx");
    try (InputStream in = SampleDocSeeder.class.getResourceAsStream(SAMPLE_RESOURCE)) {
      if (in == null) {
        throw new IOException("找不到 classpath 资源: " + SAMPLE_RESOURCE);
      }
      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
    }
    return target;
  }

  /** 样例文档的原始字节（用于「重置为样例」——把 current.docx 覆盖回样例）。 */
  byte[] sampleBytes() throws IOException {
    try (InputStream in = SampleDocSeeder.class.getResourceAsStream(SAMPLE_RESOURCE)) {
      if (in == null) {
        throw new IOException("找不到 classpath 资源: " + SAMPLE_RESOURCE);
      }
      return in.readAllBytes();
    }
  }
}
