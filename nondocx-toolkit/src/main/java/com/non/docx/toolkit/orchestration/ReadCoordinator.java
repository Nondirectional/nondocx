package com.non.docx.toolkit.orchestration;

import com.non.docx.core.api.Document;
import com.non.docx.toolkit.DocxToolkit;
import com.non.docx.toolkit.orchestration.session.OrchestratorSession;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/**
 * 只读补读协调器：为子代理提供经限流的、对活 {@link Document} 的<b>只读</b>访问通道。
 *
 * <p><b>OOXML 三层递进（补读）：</b>
 *
 * <ul>
 *   <li><b>OOXML</b>：.docx 的 run 级细节分散在 {@code <w:r>} 元素里，数量可能极大。
 *   <li><b>POI</b>：{@code XWPFDocument} 遍历 run 是内存中活对象访问，无并发保护。
 *   <li><b>nondocx</b>：基础 {@link DocumentSnapshot} 默认只到 paragraph/cell 级，run 细节由本协调器 按需补读——避免把全量
 *       run 塞进快照（token 爆炸），同时避免子代理直接并发触碰活文档。
 * </ul>
 *
 * <p><b>限流模型（第一版决策）。</b> 不走完全串行，也不允许无界并发：
 *
 * <ul>
 *   <li>{@code per-doc = 1}——同一份活文档同一时刻只允许一个补读槽位（用 docId 维度的信号量）。
 *   <li>{@code global = 4}——全局最多四个补读任务并发（防止子代理数量膨胀时打满线程）。
 * </ul>
 *
 * <p>第一版补读是<b>同步阻塞</b>的（子代理在 runtime 骨架阶段尚未真正并发）；Semaphore 是为后续异步预留的 安全边界，当前主要保证「同一文档不并发读」的语义成立。
 */
public final class ReadCoordinator {

  /** 全局并发上限（第一版固定）。 */
  static final int GLOBAL_PERMITS = 4;

  /** 单文档并发上限（第一版固定）。 */
  static final int PER_DOC_PERMITS = 1;

  private final DocxToolkit toolkit;
  private final Semaphore globalSlots;

  public ReadCoordinator(DocxToolkit toolkit) {
    this.toolkit = Objects.requireNonNull(toolkit);
    this.globalSlots = new Semaphore(GLOBAL_PERMITS, true);
  }

  /**
   * 在限流保护下执行一次只读访问。
   *
   * <p>调用方拿到的是活 {@link Document}，必须保证<b>只读</b>——本协调器不强制只读（POI 无法低成本拦截写）， 但契约要求子代理只做读，写一律走 {@code
   * CommitCoordinator}。
   *
   * @param session 当前会话（用于定位活文档与校验代次）
   * @param reader 只读回调，接收活文档
   * @param <R> 返回类型
   * @return 回调返回值
   */
  public <R> R read(OrchestratorSession session, java.util.function.Function<Document, R> reader) {
    Objects.requireNonNull(session, "session 不能为空");
    Objects.requireNonNull(reader, "reader 不能为空");
    Document doc = toolkit.session.getDocument(session.docId());
    if (doc == null) {
      throw new IllegalStateException("文档句柄 " + session.docId() + " 不存在（可能已被 close/reopen）");
    }
    // 全局限流；per-doc 在第一版同步模型下天然串行（同一 docId 只在调用线程访问），故 per-doc 信号量
    // 留给后续异步实现。这里先占全局槽位。
    acquire(globalSlots);
    try {
      return reader.apply(doc);
    } finally {
      globalSlots.release();
    }
  }

  /** 校验快照基线是否仍然有效（代次一致）。 */
  public boolean isSnapshotCurrent(DocumentSnapshot snapshot, OrchestratorSession session) {
    return snapshot.isValidFor(session.sessionGeneration());
  }

  private static void acquire(Semaphore s) {
    try {
      s.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("补读等待被中断", e);
    }
  }
}
