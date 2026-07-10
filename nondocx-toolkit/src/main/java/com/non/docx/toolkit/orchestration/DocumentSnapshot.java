package com.non.docx.toolkit.orchestration;

import com.non.docx.toolkit.orchestration.snapshot.ParagraphPreview;
import com.non.docx.toolkit.orchestration.snapshot.QualitySummary;
import com.non.docx.toolkit.orchestration.snapshot.RevisionSummary;
import com.non.docx.toolkit.orchestration.snapshot.SnapshotOverview;
import com.non.docx.toolkit.orchestration.snapshot.TablePreview;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 文档快照：RouterAgent、ReadCoordinator、子代理 prompt 渲染与校验的<b>共享事实层</b>。
 *
 * <p><b>OOXML 三层递进（快照）：</b>
 *
 * <ul>
 *   <li><b>OOXML</b>：.docx 是活 XML 文件，没有「快照」概念——任何时候读到的都是当前落盘或当前内存态。
 *   <li><b>POI</b>：{@code XWPFDocument} 是活对象，多次读取拿到的是同一份可变树；跨阶段/跨子代理传递
 *       「文档当前长什么样」如果靠反复遍历活对象，既慢又容易在并发下读到不一致中间态。
 *   <li><b>nondocx</b>：在编排层引入 {@code DocumentSnapshot}，把「这一轮分析时刻文档的结构摘要」 冻结成不可变强类型值对象，作为子代理 prompt
 *       的稳定事实源——子代理看到的快照在整轮内不变， 避免把 prompt 文本当事实源，也避免子代理直接反复触碰活 {@code Document}。
 * </ul>
 *
 * <p><b>分层粒度（第一版）。</b> 默认只到 paragraph / cell 级：
 *
 * <ul>
 *   <li>overview——段落/表格/图片/修订数量、页眉页脚/目录存在性。
 *   <li>paragraph previews——段落索引 + 短文本预览。
 *   <li>table previews——表格尺寸 + 关键单元格样本。
 *   <li>revision summary——修订开关 + 数量级摘要。
 *   <li>quality summary——基线质量风险条目。
 * </ul>
 *
 * 第一版<b>不</b>默认包含 run 级明细与 {@code docFingerprint}——run 细节由 {@code ReadCoordinator} 按需补读，快照一致性由
 * {@code conversationId} + 基线元数据 + {@code sessionGeneration} 共同保证。
 *
 * <p><b>版本与一致性字段。</b>
 *
 * <ul>
 *   <li>{@code snapshotVersion}——快照 schema 版本，第一版固定 {@code 1}，与 plan schema 版本独立演进。
 *   <li>{@code schemaVersion} 不在 snapshot 上（snapshot 用 {@code snapshotVersion}）；plan 用 {@code
 *       schemaVersion}。两者独立。
 *   <li>{@code sessionGeneration}——会话代次。每次 close/reopen/reset 后递增，用于识别旧快照并阻止其 继续驱动 plan/review。
 *   <li>基线元数据（{@code sourcePath}/{@code conversationId}/{@code createdAt}/{@code
 *       sourceLastModified}） 用于判断快照是否过期或跨会话失效。
 * </ul>
 */
public final class DocumentSnapshot {

  /** 快照 schema 版本，第一版固定为 1，与 plan schema 版本独立演进。 */
  public static final int SNAPSHOT_VERSION = 1;

  private final int snapshotVersion;
  private final String conversationId;
  private final String sourcePath;
  private final Instant createdAt;
  private final Instant sourceLastModified;
  private final long sessionGeneration;

  private final SnapshotOverview overview;
  private final List<ParagraphPreview> paragraphs;
  private final List<TablePreview> tables;
  private final RevisionSummary revisionSummary;
  private final QualitySummary qualitySummary;

  /**
   * 全参构造（一般由 SnapshotBuilder 调用）。
   *
   * @param sessionGeneration 会话代次：每次 close/reopen/reset 后递增；用于识别旧快照
   */
  public DocumentSnapshot(
      String conversationId,
      String sourcePath,
      Instant createdAt,
      Instant sourceLastModified,
      long sessionGeneration,
      SnapshotOverview overview,
      List<ParagraphPreview> paragraphs,
      List<TablePreview> tables,
      RevisionSummary revisionSummary,
      QualitySummary qualitySummary) {
    this.snapshotVersion = SNAPSHOT_VERSION;
    this.conversationId = Objects.requireNonNull(conversationId, "conversationId 不能为空");
    this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath 不能为空");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt 不能为空");
    this.sourceLastModified = sourceLastModified;
    this.sessionGeneration = sessionGeneration;
    this.overview = Objects.requireNonNull(overview, "overview 不能为空");
    this.paragraphs = List.copyOf(paragraphs);
    this.tables = List.copyOf(tables);
    this.revisionSummary = Objects.requireNonNull(revisionSummary, "revisionSummary 不能为空");
    this.qualitySummary = Objects.requireNonNull(qualitySummary, "qualitySummary 不能为空");
  }

  /** 快照 schema 版本（固定 1）。 */
  public int snapshotVersion() {
    return snapshotVersion;
  }

  /** 所属会话标识。 */
  public String conversationId() {
    return conversationId;
  }

  /** 源文档路径。 */
  public String sourcePath() {
    return sourcePath;
  }

  /** 快照生成时刻。 */
  public Instant createdAt() {
    return createdAt;
  }

  /** 源文档最后修改时刻（可能为 null，如新建未落盘文档）。 */
  public Instant sourceLastModified() {
    return sourceLastModified;
  }

  /** 会话代次（close/reopen/reset 递增）。 */
  public long sessionGeneration() {
    return sessionGeneration;
  }

  /** 文档结构概览。 */
  public SnapshotOverview overview() {
    return overview;
  }

  /** 段落预览列表。 */
  public List<ParagraphPreview> paragraphs() {
    return paragraphs;
  }

  /** 表格预览列表。 */
  public List<TablePreview> tables() {
    return tables;
  }

  /** 修订摘要。 */
  public RevisionSummary revisionSummary() {
    return revisionSummary;
  }

  /** 质量风险摘要。 */
  public QualitySummary qualitySummary() {
    return qualitySummary;
  }

  /**
   * 判断本快照相对「当前会话代次」是否仍然有效。
   *
   * @param currentGeneration 当前会话代次
   * @return 代次一致为有效；代次落后（发生了 close/reopen/reset）则失效，应阻止其继续驱动 plan/review
   */
  public boolean isValidFor(long currentGeneration) {
    return this.sessionGeneration == currentGeneration;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof DocumentSnapshot)) return false;
    DocumentSnapshot that = (DocumentSnapshot) o;
    return snapshotVersion == that.snapshotVersion
        && sessionGeneration == that.sessionGeneration
        && conversationId.equals(that.conversationId)
        && sourcePath.equals(that.sourcePath)
        && createdAt.equals(that.createdAt)
        && Objects.equals(sourceLastModified, that.sourceLastModified)
        && overview.equals(that.overview)
        && paragraphs.equals(that.paragraphs)
        && tables.equals(that.tables)
        && revisionSummary.equals(that.revisionSummary)
        && qualitySummary.equals(that.qualitySummary);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        snapshotVersion,
        conversationId,
        sourcePath,
        createdAt,
        sourceLastModified,
        sessionGeneration,
        overview,
        paragraphs,
        tables,
        revisionSummary,
        qualitySummary);
  }
}
