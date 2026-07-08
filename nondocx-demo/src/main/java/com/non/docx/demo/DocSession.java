package com.non.docx.demo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * demo 的单文档会话状态:跟踪当前磁盘 docx + OnlyOffice 版本号 key + 文档显示名。
 *
 * <p><b>它管什么 / 不管什么。</b> 本类串起三个独立的「文档状态」概念:
 *
 * <ul>
 *   <li><b>磁盘文件</b>{@code current.docx} —— OnlyOffice 拉取的物理文件,也是 Agent {@code open_docx} 的路径。
 *       本类持有它的 {@link Path}。
 *   <li><b>OnlyOffice 缓存 key</b> —— OO 按 key 缓存转换结果;换 key = 强制 OO 重新拉文件。 本类用 {@link AtomicInteger}
 *       自增管理。
 *   <li><b>文档显示名</b> —— 样例叫「nondocx 样例.docx」,上传时记录用户文件名。前端展示用。
 * </ul>
 *
 * <p>本类<b>不</b>持有 POI 活文档({@code Document})—— 那是 {@code DocxToolkit.sessions} map 的事,Agent 在对话里 自己
 * {@code open_docx}。这里只管「磁盘上现在摆着哪个文件、OO 该用哪个 key」。
 *
 * <p><b>刷新机制核心:换 key。</b> OnlyOffice 不能直接 reload 同一实例。每次文档改动(Agent {@code save_docx} 或用户 上传),后端调
 * {@link #bumpKey()} 让 key 自增,前端收到新 key 后 {@code destroyEditor()} + {@code new
 * DocsAPI.DocEditor(新key)},OO 因 key 变化而重新拉文件、重新转换。详见 design.md §3。
 *
 * <p><b>原子写。</b> {@link #replaceWith(byte[])} 用「临时文件 + {@code Files.move(ATOMIC_MOVE)}」替换磁盘文件,避免
 * OnlyOffice 在文件写到一半时拉取到损坏 docx。{@code save_docx}(POI 写)本身不经过本类,但同样建议在 toolkit 侧或 路由侧做原子替换 —— demo
 * 期 POI 直接覆盖写已可接受,上传路径必须原子。
 *
 * <p><b>线程模型。</b> key 用 {@link AtomicInteger} 保证自增原子;{@link #replaceWith(byte[])} 的文件替换本身原子。 但「换文件
 * + bump key」这一对操作<b>不是</b>原子的——由 {@code AgentBridge} / 路由层的串行化 (demo 单对话队列)保证不会并发触发。
 */
final class DocSession {

  /** 当前磁盘 docx 路径(给 OnlyOffice 拉 + 给 Agent open)。 */
  private final Path currentFile;

  /** OnlyOffice 缓存版本号,从 1 开始单调递增。每次文档改动 bump 一次。 */
  private final AtomicInteger keyVersion = new AtomicInteger(1);

  /** 当前文档显示名(样例或上传文件名)。 */
  private String filename;

  /**
   * @param currentFile 工作目录下的 current.docx 路径(由 SampleDocSeeder 落地)
   */
  DocSession(Path currentFile, String filename) {
    this.currentFile = currentFile;
    this.filename = filename;
  }

  /** 当前磁盘 docx 的 OnlyOffice key,形如 {@code "demo-v3"}。 */
  String currentKey() {
    return "demo-v" + keyVersion.get();
  }

  /** 版本号自增,返回新 key。每次文档改动(保存/上传/重置)调用。 */
  String bumpKey() {
    return "demo-v" + keyVersion.incrementAndGet();
  }

  /** 当前磁盘文件路径(给 /api/doc/file 读取、给 Agent open_docx)。 */
  Path currentFile() {
    return currentFile;
  }

  /** 当前文档显示名。 */
  String filename() {
    return filename;
  }

  /** 读取当前 docx 的字节(给 /api/doc/file 返回给 OnlyOffice)。 */
  byte[] readBytes() throws IOException {
    return Files.readAllBytes(currentFile);
  }

  /**
   * 用给定字节原子替换磁盘文档,并记录新文件名。<b>不</b> bump key(由调用方决定何时 bump,通常紧跟)。
   *
   * <p>原子替换:先写 {@code current.docx.part},再 {@code Files.move(ATOMIC_MOVE)} 覆盖。 OnlyOffice 在拉取时要么拿到
   * 旧完整版,要么拿到新完整版,永远不会拿到写一半的损坏文件。
   *
   * @param bytes 新文档字节(用户上传的 docx)
   * @param filename 新文档显示名
   */
  void replaceWith(byte[] bytes, String filename) throws IOException {
    Path tmp = currentFile.resolveSibling("current.docx.part");
    Files.write(tmp, bytes);
    Files.move(
        tmp, currentFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    this.filename = filename;
  }
}
