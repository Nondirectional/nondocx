package com.non.docx.toolkit.view;

import com.non.docx.core.api.BodyElement;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.table.Table;
import com.non.docx.core.api.text.Paragraph;
import com.non.docx.core.api.text.Run;
import com.non.docx.toolkit.QualityCheckTools;
import com.non.docx.toolkit.QualityCheckTools.CheckResult;
import com.non.docx.toolkit.orchestration.DocumentSnapshot;
import com.non.docx.toolkit.orchestration.snapshot.ParagraphPreview;
import com.non.docx.toolkit.orchestration.snapshot.RevisionSummary;
import com.non.docx.toolkit.orchestration.snapshot.SnapshotBuilder;
import com.non.docx.toolkit.orchestration.snapshot.SnapshotOverview;
import com.non.docx.toolkit.orchestration.snapshot.TablePreview;
import com.non.docx.toolkit.ref.DocumentRef;
import com.non.docx.toolkit.ref.ElementKind;
import com.non.docx.toolkit.ref.ElementRef;
import com.non.docx.toolkit.ref.ElementRefs;
import com.non.docx.toolkit.ref.ElementResolver;
import com.non.docx.toolkit.ref.ParagraphRef;
import com.non.docx.toolkit.ref.ReferenceContext;
import com.non.docx.toolkit.ref.RunRef;
import com.non.docx.toolkit.ref.TableRef;
import com.non.docx.toolkit.view.dto.AnnotatedParagraph;
import com.non.docx.toolkit.view.dto.AnnotatedRun;
import com.non.docx.toolkit.view.dto.AnnotatedView;
import com.non.docx.toolkit.view.dto.ElementView;
import com.non.docx.toolkit.view.dto.FontStat;
import com.non.docx.toolkit.view.dto.IssueEntry;
import com.non.docx.toolkit.view.dto.IssuesView;
import com.non.docx.toolkit.view.dto.OutlineEntry;
import com.non.docx.toolkit.view.dto.OutlineView;
import com.non.docx.toolkit.view.dto.RevisionOverview;
import com.non.docx.toolkit.view.dto.StatsView;
import com.non.docx.toolkit.view.dto.TextEntry;
import com.non.docx.toolkit.view.dto.TextView;
import com.non.docx.toolkit.view.dto.ViewMeta;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档语义视图只读服务：把 {@link DocumentSnapshot} 提炼为 6 种可复用视图，为 Agent 提供低成本、可控上下文。
 *
 * <p><b>职责边界。</b> 只读——不修改文档，不做冲突检测，不做 review。复用 {@link SnapshotBuilder} 单遍历 产出快照，再投影到不同视图 DTO；不建第二套
 * body 遍历。
 *
 * <p><b>6 种视图：</b>
 *
 * <ul>
 *   <li>{@link #outline}——标题/段落/表格 + section/TOC/页眉页脚/修订概览。
 *   <li>{@link #text}——按文档顺序输出文本 + 元素引用。
 *   <li>{@link #annotated}——文本 + run 直接格式（bold/italic/font/size/color）+ ref。
 *   <li>{@link #stats}——段落/表格/图片/字体/字号/样式/修订统计。
 *   <li>{@link #issues}——复用 {@link QualityCheckTools} 10 项检查，包装成视图 DTO。
 *   <li>{@link #element}——按 ref 获取单个元素的结构化详情。
 * </ul>
 *
 * <p><b>上下文控制。</b> 默认 {@code maxItems=200} + {@code textTruncate=120} + {@code expandRuns=false}，
 * 超过截断并在 {@link ViewMeta} 标记 {@code truncated=true}。element 视图单元素全量，不走 maxItems。
 *
 * <p><b>一致性。</b> 同一文档所有视图引用同一 {@code sessionGeneration}；outline/annotated 中的 ref 来自同一 {@link
 * ElementResolver}，保证可被 {@link #element} 解析。
 */
public final class DocumentViewService {

  private final ReferenceContext referenceContext;
  private final QualityCheckTools qualityCheckTools;

  public DocumentViewService(
      ReferenceContext referenceContext, QualityCheckTools qualityCheckTools) {
    this.referenceContext = referenceContext;
    this.qualityCheckTools = qualityCheckTools;
  }

  // ==================== outline ====================

  /**
   * 大纲视图：按 body 顺序的标题/段落/表格 + section/TOC/页眉页脚/修订概览。
   *
   * <p>复用 {@link SnapshotBuilder#build} 一次，从快照投影 {@link OutlineEntry}。段落和表格按 bodyIndex 合并为单一列表。
   */
  public OutlineView outline(Document doc, String docId, long generation, ViewQuery query) {
    DocumentSnapshot snapshot = buildSnapshot(doc, docId, generation);
    List<BodyItem> body = mergeBodyOrder(snapshot, query);
    List<OutlineEntry> entries = new ArrayList<>();
    for (BodyItem item : body) {
      if (item.paragraph != null) {
        ParagraphPreview pp = item.paragraph;
        entries.add(
            new OutlineEntry(
                "paragraph",
                pp.ref().canonical(),
                pp.bodyIndex(),
                pp.headingLevel(),
                truncate(pp.text(), query.textTruncate()),
                pp.isListItem(),
                0,
                0));
      } else {
        TablePreview tp = item.table;
        entries.add(
            new OutlineEntry(
                "table",
                tp.ref().canonical(),
                tp.bodyIndex(),
                null,
                tablePreviewText(tp),
                false,
                tp.rowCount(),
                tp.columnCount()));
      }
    }

    int totalCount = snapshot.paragraphs().size() + snapshot.tables().size();
    boolean truncated = entries.size() < totalCount;
    SnapshotOverview ov = snapshot.overview();
    RevisionSummary rev = snapshot.revisionSummary();
    ViewMeta meta =
        new ViewMeta(
            DocumentSnapshot.SNAPSHOT_VERSION,
            generation,
            snapshot.createdAt().toString(),
            truncated,
            totalCount);
    return new OutlineView(
        meta,
        entries,
        ov.hasToc(),
        ov.hasHeader(),
        ov.hasFooter(),
        doc.sections().size(),
        new RevisionOverview(rev.trackingEnabled(), rev.totalCount(), rev.countByType()));
  }

  // ==================== text ====================

  /** 文本视图：按 body 顺序输出文本条目，每项带 canonical ref。 */
  public TextView text(Document doc, String docId, long generation, ViewQuery query) {
    DocumentSnapshot snapshot = buildSnapshot(doc, docId, generation);
    List<BodyItem> body = mergeBodyOrder(snapshot, query);
    List<TextEntry> entries = new ArrayList<>();
    for (BodyItem item : body) {
      if (item.paragraph != null) {
        ParagraphPreview pp = item.paragraph;
        entries.add(
            new TextEntry(
                "paragraph",
                pp.ref().canonical(),
                pp.bodyIndex(),
                truncate(pp.text(), query.textTruncate())));
      } else {
        TablePreview tp = item.table;
        entries.add(
            new TextEntry("table", tp.ref().canonical(), tp.bodyIndex(), tablePreviewText(tp)));
      }
    }

    int totalCount = snapshot.paragraphs().size() + snapshot.tables().size();
    boolean truncated = entries.size() < totalCount;
    ViewMeta meta =
        new ViewMeta(
            DocumentSnapshot.SNAPSHOT_VERSION,
            generation,
            snapshot.createdAt().toString(),
            truncated,
            totalCount);
    return new TextView(meta, entries);
  }

  // ==================== annotated ====================

  /**
   * annotated 视图：段落级文本 + 按需展开的 run 直接格式。
   *
   * <p>复用快照的 {@link ParagraphRef}（保证一致性），经 {@link ElementResolver} 逐段解析活文档取 run。 {@code
   * expandRuns=false} 时 runs 为空列表，Agent 定位到目标后再展开。
   */
  public AnnotatedView annotated(Document doc, String docId, long generation, ViewQuery query) {
    DocumentSnapshot snapshot = buildSnapshot(doc, docId, generation);
    ElementResolver resolver = referenceContext.resolver(new DocumentRef(docId, generation), doc);
    List<AnnotatedParagraph> paragraphs = new ArrayList<>();

    for (ParagraphPreview pp : snapshot.paragraphs()) {
      List<AnnotatedRun> runs = List.of();
      if (query.expandRuns()) {
        Paragraph p = resolver.resolve(pp.ref());
        List<AnnotatedRun> runList = new ArrayList<>();
        for (Run r : p.runs()) {
          RunRef runRef = resolver.reference(r);
          runList.add(
              new AnnotatedRun(
                  runRef.canonical(),
                  truncate(r.text(), query.textTruncate()),
                  r.isBold(),
                  r.isItalic(),
                  r.font(),
                  r.fontSize(),
                  r.color()));
        }
        runs = runList;
      }
      paragraphs.add(
          new AnnotatedParagraph(
              pp.ref().canonical(),
              pp.index(),
              pp.bodyIndex(),
              truncate(pp.text(), query.textTruncate()),
              runs));
      if (paragraphs.size() >= query.maxItems()) {
        break;
      }
    }

    int totalCount = snapshot.paragraphs().size();
    boolean truncated = paragraphs.size() < totalCount;
    ViewMeta meta =
        new ViewMeta(
            DocumentSnapshot.SNAPSHOT_VERSION,
            generation,
            snapshot.createdAt().toString(),
            truncated,
            totalCount);
    return new AnnotatedView(meta, paragraphs);
  }

  // ==================== stats ====================

  /** 统计视图：段落/表格/图片/section/修订数量 + 字体/字号聚合。 */
  public StatsView stats(Document doc, String docId, long generation) {
    DocumentSnapshot snapshot = buildSnapshot(doc, docId, generation);
    SnapshotOverview ov = snapshot.overview();

    // 字体/字号聚合：遍历段落 run
    Map<String, Integer> fontCounts = new HashMap<>();
    Map<Integer, Integer> fontSizeCounts = new HashMap<>();
    for (BodyElement be : doc.bodyElements()) {
      if (be instanceof Paragraph) {
        for (Run r : ((Paragraph) be).runs()) {
          String font = r.font();
          if (font != null && !font.isEmpty()) {
            fontCounts.merge(font, 1, Integer::sum);
          }
          Integer size = r.fontSize();
          if (size != null) {
            fontSizeCounts.merge(size, 1, Integer::sum);
          }
        }
      }
    }

    List<FontStat> fonts = new ArrayList<>();
    fontCounts.entrySet().stream()
        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
        .forEach(e -> fonts.add(new FontStat(e.getKey(), e.getValue())));

    List<Integer> fontSizes = new ArrayList<>(fontSizeCounts.keySet());
    fontSizes.sort(Integer::compare);

    ViewMeta meta =
        new ViewMeta(
            DocumentSnapshot.SNAPSHOT_VERSION,
            generation,
            snapshot.createdAt().toString(),
            false,
            1);
    return new StatsView(
        meta,
        ov.paragraphCount(),
        ov.tableCount(),
        countImages(doc),
        doc.sections().size(),
        doc.bodyElements().size(),
        ov.trackedChangeCount(),
        ov.hasToc(),
        ov.hasHeader(),
        ov.hasFooter(),
        fonts,
        fontSizes);
  }

  // ==================== issues ====================

  /**
   * 问题视图：复用 {@link QualityCheckTools} 10 项检查，包装成视图 DTO。
   *
   * <p>只含未通过项（passed=false）。{@code severityFilter} 为 null 时返回全部未通过项； 否则只返回指定 severity（{@code
   * "error"}/{@code "warning"}）的项。
   */
  public IssuesView issues(Document doc, String docId, long generation, String severityFilter) {
    List<CheckResult> results = qualityCheckTools.runAllChecks(doc);
    int passed = 0;
    int warnings = 0;
    int errors = 0;
    List<IssueEntry> issues = new ArrayList<>();
    for (CheckResult cr : results) {
      if (cr.passed()) {
        passed++;
        continue;
      }
      if ("error".equals(cr.severity())) {
        errors++;
      } else {
        warnings++;
      }
      if (severityFilter == null || severityFilter.equals(cr.severity())) {
        issues.add(new IssueEntry(cr.name(), cr.passed(), cr.severity(), cr.message()));
      }
    }

    ViewMeta meta =
        new ViewMeta(
            DocumentSnapshot.SNAPSHOT_VERSION,
            generation,
            Instant.now().toString(),
            false,
            results.size());
    return new IssuesView(meta, passed, warnings, errors, issues);
  }

  // ==================== element ====================

  /**
   * 单元素详情视图：按 canonical ref 获取一个元素的结构化详情。
   *
   * <p>第一版支持 paragraph/table/run；其余类型返回 kind + ref + 空 properties。ref 解析失败时抛 {@link
   * com.non.docx.toolkit.ref.RefResolutionException}，由调用方（ViewTools）捕获转为 ToolResult。
   */
  public ElementView element(Document doc, String docId, long generation, String refCanonical) {
    ElementRef ref = ElementRefs.parse(refCanonical);
    ElementResolver resolver = referenceContext.resolver(new DocumentRef(docId, generation), doc);

    Map<String, Object> properties = new LinkedHashMap<>();
    switch (ref.kind()) {
      case PARAGRAPH:
        {
          Paragraph p = resolver.resolve((ParagraphRef) ref);
          properties.put("text", p.text());
          Integer hl = p.heading() == null ? null : p.heading().ordinal() + 1;
          if (hl != null) {
            properties.put("headingLevel", hl);
          }
          properties.put("listItem", p.listLevel() != null);
          break;
        }
      case TABLE:
        {
          Table t = resolver.resolve((TableRef) ref);
          properties.put("rowCount", t.rows().size());
          properties.put("columnCount", t.rows().isEmpty() ? 0 : t.rows().get(0).cells().size());
          break;
        }
      case RUN:
        {
          Run r = resolver.resolve((RunRef) ref);
          properties.put("text", r.text());
          properties.put("bold", r.isBold());
          properties.put("italic", r.isItalic());
          putIfNotNull(properties, "font", r.font());
          putIfNotNull(properties, "fontSize", r.fontSize());
          putIfNotNull(properties, "color", r.color());
          break;
        }
      default:
        // 其余类型（CELL/HEADER_FOOTER/REVISION/OPERATION_TARGET）第一版返回空 properties
        break;
    }

    ViewMeta meta =
        new ViewMeta(
            DocumentSnapshot.SNAPSHOT_VERSION, generation, Instant.now().toString(), false, 1);
    return new ElementView(meta, kindName(ref.kind()), refCanonical, properties);
  }

  private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
    if (value != null) {
      map.put(key, value);
    }
  }

  // ==================== 内部辅助 ====================

  /**
   * 按快照的 bodyIndex 归并段落和表格为单一有序列表，截断到 {@code query.maxItems()}。
   *
   * <p>outline 和 text 视图共享此归并逻辑，避免复制粘贴。段落和表格各自已按 bodyIndex 升序， 用标准有序归并即可。每项 {@link BodyItem}
   * 只填一个（paragraph 或 table），另一个为 null。
   */
  private static List<BodyItem> mergeBodyOrder(DocumentSnapshot snapshot, ViewQuery query) {
    List<ParagraphPreview> paragraphs = snapshot.paragraphs();
    List<TablePreview> tables = snapshot.tables();
    List<BodyItem> body = new ArrayList<>();
    int pi = 0;
    int ti = 0;
    while (pi < paragraphs.size() || ti < tables.size()) {
      ParagraphPreview pp = pi < paragraphs.size() ? paragraphs.get(pi) : null;
      TablePreview tp = ti < tables.size() ? tables.get(ti) : null;
      boolean pickPara = tp == null || (pp != null && pp.bodyIndex() <= tp.bodyIndex());
      if (pickPara) {
        body.add(new BodyItem(pp, null));
        pi++;
      } else {
        body.add(new BodyItem(null, tp));
        ti++;
      }
      if (body.size() >= query.maxItems()) {
        break;
      }
    }
    return body;
  }

  /** 归并后的 body 项：paragraph 或 table 二选一。 */
  private static final class BodyItem {
    final ParagraphPreview paragraph;
    final TablePreview table;

    BodyItem(ParagraphPreview paragraph, TablePreview table) {
      this.paragraph = paragraph;
      this.table = table;
    }
  }

  private DocumentSnapshot buildSnapshot(Document doc, String docId, long generation) {
    SnapshotBuilder builder = new SnapshotBuilder(referenceContext);
    // view 场景不关心 sourcePath；传一个占位路径（SnapshotBuilder 只用于读 lastModified，失败返回 null）
    return builder.build(doc, docId, java.nio.file.Paths.get(docId), generation);
  }

  /** 统计文档中的内联图片数。走 run.raw() 逃生舱；异常退回 0。 */
  private static int countImages(Document doc) {
    int count = 0;
    for (BodyElement be : doc.bodyElements()) {
      if (be instanceof Paragraph) {
        for (Run r : ((Paragraph) be).runs()) {
          try {
            count += r.raw().getEmbeddedPictures().size();
          } catch (RuntimeException ignored) {
            // POI inline image 遍历不稳时退回 0，不阻塞视图
          }
        }
      }
    }
    return count;
  }

  private static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max);
  }

  private static String tablePreviewText(TablePreview tp) {
    List<List<String>> samples = tp.cellSamples();
    if (samples.isEmpty() || samples.get(0).isEmpty()) {
      return "表格 " + tp.rowCount() + "×" + tp.columnCount();
    }
    return samples.get(0).get(0);
  }

  private static String kindName(ElementKind kind) {
    switch (kind) {
      case PARAGRAPH:
        return "paragraph";
      case RUN:
        return "run";
      case TABLE:
        return "table";
      case CELL:
        return "cell";
      case HEADER_FOOTER:
        return "header_footer";
      case REVISION:
        return "revision";
      case OPERATION_TARGET:
        return "operation_target";
      default:
        return kind.name().toLowerCase();
    }
  }
}
