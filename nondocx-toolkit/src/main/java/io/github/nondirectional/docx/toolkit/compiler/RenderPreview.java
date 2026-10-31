package io.github.nondirectional.docx.toolkit.compiler;

import io.github.nondirectional.docx.core.api.Document;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 渲染预览：把文档经 LibreOffice headless 转成 PDF/PNG（docs/11 §3.4 {@code render_preview} 的执行器）。
 *
 * <p><b>为什么需要它。</b> 结构/文本检查（validate）看不见分页、溢出、表格断行等<b>视觉</b>缺陷——渲染 一次拿 PDF/PNG 才能做视觉
 * QA。工具面只读不写正文：产物落在临时目录，不触碰会话文档。
 *
 * <p><b>soffice 约束（踩坑记档）。</b>
 *
 * <ul>
 *   <li>同一用户 profile 的 soffice 一次只能跑一个实例（profile 锁），并发调用会静默失败——每次调用 用独立 {@code
 *       -env:UserInstallation} profile 规避；
 *   <li>{@code --convert-to png} 只输出<b>首页</b>（soffice 行为，多页 PNG 需 PDF 再拆，v1 不做）；
 *   <li>soffice 路径经环境变量 {@code NONDOCX_SOFFICE} 注入（默认 {@code soffice}，依赖 PATH）， 无 LibreOffice
 *       的环境返回明确错误而不是挂死。
 * </ul>
 */
public final class RenderPreview {

  /** 渲染产物。 */
  public static final class RenderResult {

    private final Path file;
    private final String format;
    private final long bytes;

    RenderResult(Path file, String format, long bytes) {
      this.file = file;
      this.format = format;
      this.bytes = bytes;
    }

    /** 产物文件路径（PDF/PNG）。 */
    public Path file() {
      return file;
    }

    /** 产物格式（{@code pdf}/{@code png}）。 */
    public String format() {
      return format;
    }

    /** 产物大小（字节）。 */
    public long bytes() {
      return bytes;
    }
  }

  /** soffice 启动/转换失败。消息含原因与（可得的）stderr 尾部。 */
  public static final class RenderException extends RuntimeException {

    RenderException(String message) {
      super(message);
    }

    RenderException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private RenderPreview() {}

  /**
   * 把文档渲染为 PDF 或 PNG（首页）。
   *
   * @param doc 活文档（只读使用：存临时 docx 后转格式，不修改文档）
   * @param format {@code pdf} 或 {@code png}
   * @param workDir 产物输出目录（临时 docx 用完即删，产物保留）
   * @param sofficePath soffice 可执行文件路径
   * @param timeoutSeconds soffice 超时秒数（超时销毁进程并报错）
   * @return 渲染产物
   * @throws RenderException 格式非法/启动失败/转换失败/超时
   */
  public static RenderResult render(
      Document doc, String format, Path workDir, String sofficePath, long timeoutSeconds) {
    String normalized = format == null ? "" : format.trim().toLowerCase();
    if (!"pdf".equals(normalized) && !"png".equals(normalized)) {
      throw new RenderException("不支持的渲染格式: " + format + "（可用: pdf/png）");
    }
    try {
      Files.createDirectories(workDir);
    } catch (IOException e) {
      throw new RenderException("创建输出目录失败: " + workDir, e);
    }
    String base = "nondocx-preview-" + System.nanoTime();
    Path docx = workDir.resolve(base + ".docx");
    Path profile = workDir.resolve(base + "-profile");
    try {
      doc.save(docx);
    } catch (RuntimeException e) {
      throw new RenderException("写出临时 docx 失败", e);
    }
    List<String> command = new ArrayList<>();
    command.add(sofficePath);
    command.add("--headless");
    command.add("--norestore");
    command.add("-env:UserInstallation=file://" + profile.toAbsolutePath());
    command.add("--convert-to");
    command.add(normalized);
    command.add("--outdir");
    command.add(workDir.toAbsolutePath().toString());
    command.add(docx.toAbsolutePath().toString());

    Process process = null;
    String output;
    try {
      ProcessBuilder builder = new ProcessBuilder(command);
      builder.redirectErrorStream(true);
      process = builder.start();
      // 读空输出流避免子进程阻塞（soffice 打印转换日志）
      output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        throw new RenderException("soffice 转换超时（>" + timeoutSeconds + "s）");
      }
    } catch (IOException e) {
      throw new RenderException(
          "启动 soffice 失败: " + sofficePath + "（检查 NONDOCX_SOFFICE / LibreOffice 安装）", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RenderException("等待 soffice 被中断", e);
    } finally {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
      }
      try {
        Files.deleteIfExists(docx);
      } catch (IOException ignored) {
        // 临时文件删除失败不影响结果
      }
    }
    Path artifact = workDir.resolve(base + "." + normalized);
    if (!Files.exists(artifact)) {
      throw new RenderException(
          "soffice 未产出 "
              + normalized
              + "（exit="
              + process.exitValue()
              + "）输出: "
              + tail(output, 400));
    }
    long size;
    try {
      size = Files.size(artifact);
    } catch (IOException e) {
      throw new RenderException("读取产物大小失败: " + artifact, e);
    }
    return new RenderResult(artifact, normalized, size);
  }

  private static String tail(String text, int max) {
    if (text == null || text.isEmpty()) {
      return "（无输出）";
    }
    String trimmed = text.strip();
    return trimmed.length() <= max ? trimmed : trimmed.substring(trimmed.length() - max);
  }
}
