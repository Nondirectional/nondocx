package com.non.docx.toolkit.orchestration.snapshot;

import com.non.docx.core.api.BodyElement;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.header.Footer;
import com.non.docx.core.api.header.Header;
import com.non.docx.core.api.style.HeadingLevel;
import com.non.docx.core.api.table.Cell;
import com.non.docx.core.api.table.Row;
import com.non.docx.core.api.table.Table;
import com.non.docx.core.api.text.Paragraph;
import com.non.docx.core.api.toc.TableOfContents;
import com.non.docx.core.api.track.TrackedChangeFamily;
import com.non.docx.core.api.track.TrackedChanges;
import com.non.docx.toolkit.orchestration.DocumentSnapshot;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从活 {@link Document} 构建 {@link DocumentSnapshot}：把「这一轮分析时刻文档的结构摘要」冻结成不可变快照。
 *
 * <p><b>职责边界。</b> 只做<b>读取与摘要</b>——遍历活文档的结构，提取 paragraph/cell 级短预览，组装成 强类型快照；不修改文档，不做冲突检测，不做
 * review。它是 RouterAgent ANALYZE 阶段的工具。
 *
 * <p><b>预览截断规则（第一版）。</b>
 *
 * <ul>
 *   <li>段落文本预览 ≤ 80 字符。
 *   <li>表格只采样首行 + 首列单元格，每格 ≤ 40 字符；非全量。
 *   <li>修订只给数量级与按家族分组计数；不给单条明细。
 *   <li>质量摘要暂时为空（基线快照不跑质量检查；质量由 QualityAgent 在 PLAN 阶段做）。
 * </ul>
 */
public final class SnapshotBuilder {

  /** 段落预览文本最大长度。 */
  private static final int PARA_PREVIEW_MAX = 80;

  /** 单元格样本文本最大长度。 */
  private static final int CELL_PREVIEW_MAX = 40;

  /** 表格采样的最大行数（首行优先）。 */
  private static final int TABLE_SAMPLE_ROWS = 1;

  /**
   * 构建文档快照。
   *
   * @param doc 活文档
   * @param conversationId 会话标识
   * @param sourcePath 源文档路径
   * @param sessionGeneration 当前会话代次
   */
  public DocumentSnapshot build(
      Document doc, String conversationId, Path sourcePath, long sessionGeneration) {
    SnapshotOverview overview = buildOverview(doc);
    BodyPreviews body = buildBodyPreviews(doc);
    RevisionSummary revision = buildRevision(doc);
    QualitySummary quality = new QualitySummary(0, 0, List.of());

    Instant sourceLastModified = readLastModified(sourcePath);
    return new DocumentSnapshot(
        conversationId,
        sourcePath.toString(),
        Instant.now(),
        sourceLastModified,
        sessionGeneration,
        overview,
        body.paragraphs,
        body.tables,
        revision,
        quality);
  }

  private SnapshotOverview buildOverview(Document doc) {
    int paragraphCount = doc.paragraphs().size();
    int tableCount = doc.tables().size();
    int imageCount = countImages(doc);
    int trackedChangeCount = countTrackedChanges(doc);
    boolean hasHeader = false;
    boolean hasFooter = false;
    try {
      Header h = doc.header();
      hasHeader = h != null && !h.paragraphs().isEmpty();
    } catch (RuntimeException ignored) {
      hasHeader = false;
    }
    try {
      Footer f = doc.footer();
      hasFooter = f != null && !f.paragraphs().isEmpty();
    } catch (RuntimeException ignored) {
      hasFooter = false;
    }
    boolean hasToc = hasToc(doc);
    return new SnapshotOverview(
        paragraphCount, tableCount, imageCount, trackedChangeCount, hasHeader, hasFooter, hasToc);
  }

  /**
   * 按真实 body 顺序遍历 {@link Document#bodyElements()}，一次构建段落预览和表格预览， 分别打上投影索引（index）和 body 顺序索引
   * (bodyIndex)。
   *
   * <p><b>为什么一次遍历而非分别遍历 {@code paragraphs()} 和 {@code tables()}。</b> 段落和表格在 {@code <w:body>}
   * 里交错排列，各自占一个 slot。分别遍历会丢失交错顺序信息——拿不到表格在 body 里的绝对位置， 导致 LLM 无法正确表达「在表格前/后插入段落」 （见任务
   * 07-10-body-insert-position-table-boundary 的根因分析）。一次遍历让每个段落和表格都带上 bodyIndex， LLM 就能精确表达插入落点。
   */
  private BodyPreviews buildBodyPreviews(Document doc) {
    List<ParagraphPreview> paragraphs = new ArrayList<>();
    List<TablePreview> tables = new ArrayList<>();
    int paraIdx = 0;
    int tableIdx = 0;
    int bodyIdx = 0;
    for (BodyElement be : doc.bodyElements()) {
      if (be instanceof Paragraph) {
        Paragraph p = (Paragraph) be;
        String text = truncate(p.text(), PARA_PREVIEW_MAX);
        HeadingLevel heading = p.heading();
        String headingLevel = heading == null ? null : String.valueOf(heading.ordinal() + 1);
        boolean listItem = p.listLevel() != null;
        paragraphs.add(new ParagraphPreview(paraIdx, bodyIdx, text, headingLevel, listItem));
        paraIdx++;
      } else if (be instanceof Table) {
        Table t = (Table) be;
        List<Row> rows = t.rows();
        int rowCount = rows.size();
        int colCount = rowCount == 0 ? 0 : rows.get(0).cells().size();
        List<List<String>> samples = sampleCells(rows);
        tables.add(new TablePreview(tableIdx, bodyIdx, rowCount, colCount, samples));
        tableIdx++;
      }
      bodyIdx++;
    }
    return new BodyPreviews(paragraphs, tables);
  }

  /** 一次遍历产出的段落预览 + 表格预览对。 */
  private static final class BodyPreviews {
    final List<ParagraphPreview> paragraphs;
    final List<TablePreview> tables;

    BodyPreviews(List<ParagraphPreview> paragraphs, List<TablePreview> tables) {
      this.paragraphs = paragraphs;
      this.tables = tables;
    }
  }

  private List<List<String>> sampleCells(List<Row> rows) {
    List<List<String>> samples = new ArrayList<>();
    int rowLimit = Math.min(TABLE_SAMPLE_ROWS, rows.size());
    for (int r = 0; r < rowLimit; r++) {
      List<Cell> cells = rows.get(r).cells();
      List<String> rowSample = new ArrayList<>();
      for (Cell c : cells) {
        rowSample.add(truncate(cellText(c), CELL_PREVIEW_MAX));
      }
      samples.add(rowSample);
    }
    return samples;
  }

  private RevisionSummary buildRevision(Document doc) {
    try {
      TrackedChanges tc = doc.trackedChanges();
      boolean enabled = tc.enabled();
      var changes = tc.list();
      int total = changes.size();
      Map<String, Integer> byType = new HashMap<>();
      for (var ch : changes) {
        TrackedChangeFamily family = ch.family();
        String key = family == null ? "unknown" : family.name().toLowerCase();
        byType.merge(key, 1, Integer::sum);
      }
      return new RevisionSummary(enabled, total, byType);
    } catch (RuntimeException ignored) {
      return new RevisionSummary(false, 0, Map.of());
    }
  }

  // ==================== 辅助 ====================

  private static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max);
  }

  private static String cellText(Cell c) {
    try {
      return c.text();
    } catch (RuntimeException ignored) {
      return "";
    }
  }

  private static int countImages(Document doc) {
    // 第一版不深挖 image 计数；overview 给 0，由 ReadCoordinator 按需补读
    return 0;
  }

  private static int countTrackedChanges(Document doc) {
    try {
      return doc.trackedChanges().list().size();
    } catch (RuntimeException ignored) {
      return 0;
    }
  }

  private static boolean hasToc(Document doc) {
    try {
      TableOfContents toc = doc.toc();
      return toc != null;
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private static Instant readLastModified(Path path) {
    try {
      FileTime t = java.nio.file.Files.getLastModifiedTime(path);
      return t.toInstant();
    } catch (RuntimeException | java.io.IOException ignored) {
      return null;
    }
  }
}
