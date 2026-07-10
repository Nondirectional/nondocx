package com.non.docx.toolkit.orchestration.session;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * DocxOrchestrator 的会话状态：绑定单活跃文档 + 会话标识 + 代次。
 *
 * <p><b>OOXML 三层递进（会话）：</b>
 *
 * <ul>
 *   <li><b>OOXML</b>：.docx 是静态文件，没有「会话」概念。
 *   <li><b>POI</b>：{@code XWPFDocument} 是打开后的活对象句柄，{@code close()} 释放；但 POI 不提供
 *       「会话标识」「代次」「conversation memory」这层语义。
 *   <li><b>nondocx</b>：在编排层引入 {@code OrchestratorSession}，把「一份活跃文档 + 一个会话标识 + 一个
 *       代次」绑成一个显式状态对象——对外暴露 {@code conversationId}/{@code sessionId}，对内持有底层 {@code docId}，并维护
 *       {@code sessionGeneration} 用于识别 close/reopen 后的旧快照与旧 plan。
 * </ul>
 *
 * <p><b>单会话单文档（第一版约束）。</b> 一个会话只服务一份活跃文档；切换到另一份文档必须开启新会话， 不复用旧 memory。close + reopen 同一份文档会递增
 * {@code sessionGeneration}，使基于旧代次的快照与 plan 失效。
 *
 * <p><b>多轮记忆。</b> {@code memory} 是本轮及历史轮次的对话记录（user 文本 + 每轮摘要），供多轮 conversation memory
 * 使用。第一版不跨会话持久化。
 */
public final class OrchestratorSession {

  private final String conversationId;
  private final String sessionId;
  private final String docId;
  private final Path sourcePath;
  private long sessionGeneration;
  private final List<Turn> memory = new ArrayList<>();

  /**
   * @param conversationId 对外会话标识（续聊同一文档时复用）
   * @param sessionId 会话内运行标识
   * @param docId 底层文档句柄（不对外暴露）
   * @param sourcePath 源文档路径
   */
  public OrchestratorSession(
      String conversationId, String sessionId, String docId, Path sourcePath) {
    this.conversationId = Objects.requireNonNull(conversationId, "conversationId 不能为空");
    this.sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
    this.docId = Objects.requireNonNull(docId, "docId 不能为空");
    this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath 不能为空");
    this.sessionGeneration = 1L;
  }

  /** 对外会话标识。 */
  public String conversationId() {
    return conversationId;
  }

  /** 会话内运行标识。 */
  public String sessionId() {
    return sessionId;
  }

  /** 底层文档句柄（仅 orchestrator/coordinator 内部使用，不对外）。 */
  public String docId() {
    return docId;
  }

  /** 源文档路径。 */
  public Path sourcePath() {
    return sourcePath;
  }

  /** 当前会话代次（close/reopen/reset 递增）。 */
  public long sessionGeneration() {
    return sessionGeneration;
  }

  /** 递增代次（close + reopen 后调用），使旧快照与旧 plan 失效。 */
  public long bumpGeneration() {
    return ++sessionGeneration;
  }

  /** 多轮记忆：追加一轮。 */
  public void appendTurn(Turn turn) {
    memory.add(Objects.requireNonNull(turn));
  }

  /** 多轮记忆快照（不可变拷贝）。 */
  public List<Turn> memory() {
    return List.copyOf(memory);
  }

  /** 单轮对话记录：用户输入 + 本轮摘要。 */
  public static final class Turn {
    private final String userInput;
    private final String summary;

    public Turn(String userInput, String summary) {
      this.userInput = Objects.requireNonNull(userInput, "userInput 不能为空");
      this.summary = summary == null ? "" : summary;
    }

    public String userInput() {
      return userInput;
    }

    public String summary() {
      return summary;
    }
  }
}
