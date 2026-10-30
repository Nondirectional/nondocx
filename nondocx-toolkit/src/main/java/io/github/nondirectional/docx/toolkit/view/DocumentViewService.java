package io.github.nondirectional.docx.toolkit.view;

import io.github.nondirectional.docx.core.api.BodyElement;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.header.Footer;
import io.github.nondirectional.docx.core.api.header.Header;
import io.github.nondirectional.docx.core.api.table.Table;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import io.github.nondirectional.docx.core.api.text.Run;
import io.github.nondirectional.docx.toolkit.QualityCheckTools;
import io.github.nondirectional.docx.toolkit.QualityCheckTools.CheckResult;
import io.github.nondirectional.docx.toolkit.ref.CellRef;
import io.github.nondirectional.docx.toolkit.ref.DocumentRef;
import io.github.nondirectional.docx.toolkit.ref.ElementKind;
import io.github.nondirectional.docx.toolkit.ref.ElementRef;
import io.github.nondirectional.docx.toolkit.ref.ElementRefs;
import io.github.nondirectional.docx.toolkit.ref.ElementResolver;
import io.github.nondirectional.docx.toolkit.ref.HeaderFooterRef;
import io.github.nondirectional.docx.toolkit.ref.ParagraphRef;
import io.github.nondirectional.docx.toolkit.ref.RefResolutionException;
import io.github.nondirectional.docx.toolkit.ref.ReferenceContext;
import io.github.nondirectional.docx.toolkit.ref.RevisionRef;
import io.github.nondirectional.docx.toolkit.ref.RunRef;
import io.github.nondirectional.docx.toolkit.ref.TableRef;
import io.github.nondirectional.docx.toolkit.view.dto.AnnotatedParagraph;
import io.github.nondirectional.docx.toolkit.view.dto.AnnotatedRun;
import io.github.nondirectional.docx.toolkit.view.dto.AnnotatedView;
import io.github.nondirectional.docx.toolkit.view.dto.CommentEntry;
import io.github.nondirectional.docx.toolkit.view.dto.CommentOverview;
import io.github.nondirectional.docx.toolkit.view.dto.ElementView;
import io.github.nondirectional.docx.toolkit.view.dto.FontStat;
import io.github.nondirectional.docx.toolkit.view.dto.IssueEntry;
import io.github.nondirectional.docx.toolkit.view.dto.IssuesView;
import io.github.nondirectional.docx.toolkit.view.dto.OutlineEntry;
import io.github.nondirectional.docx.toolkit.view.dto.OutlineView;
import io.github.nondirectional.docx.toolkit.view.dto.RevisionEntry;
import io.github.nondirectional.docx.toolkit.view.dto.RevisionOverview;
import io.github.nondirectional.docx.toolkit.view.dto.StatsView;
import io.github.nondirectional.docx.toolkit.view.dto.TextEntry;
import io.github.nondirectional.docx.toolkit.view.dto.TextView;
import io.github.nondirectional.docx.toolkit.view.dto.ViewMeta;
import io.github.nondirectional.docx.toolkit.view.snapshot.DocumentSnapshot;
import io.github.nondirectional.docx.toolkit.view.snapshot.ParagraphPreview;
import io.github.nondirectional.docx.toolkit.view.snapshot.RevisionSummary;
import io.github.nondirectional.docx.toolkit.view.snapshot.SnapshotBuilder;
import io.github.nondirectional.docx.toolkit.view.snapshot.SnapshotOverview;
import io.github.nondirectional.docx.toolkit.view.snapshot.TablePreview;
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

  /** view_element 表格详情的 cellTexts 矩阵最大行数（超出标「…」）。 */
  private static final int TABLE_ELEMENT_ROWS_MAX = 6;

  /** cellTexts 矩阵里单个单元格文本的截断长度。 */
  private static final int TABLE_ELEMENT_CELL_MAX = 24;

  /** stats 页脚文本预览的截断长度。 */
  private static final int FOOTER_PREVIEW_MAX = 120;

  /** outline 批注概览 recent 的最大条数。 */
  private static final int RECENT_COMMENTS_MAX = 5;

  /** outline 批注概览 snippet 的截断长度（与修订 recent 片段同档）。 */
  private static final int COMMENT_SNIPPET_MAX = 40;

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
    List<RevisionEntry> recent =
        rev.recent().stream()
            .map(
                e ->
                    new RevisionEntry(
                        e.ref(), e.type(), e.author(), e.snippet(), e.bold(), e.italic()))
            .collect(java.util.stream.Collectors.toList());
    ElementResolver commentResolver =
        referenceContext.resolver(new DocumentRef(docId, generation), doc);
    return new OutlineView(
        meta,
        entries,
        ov.hasToc(),
        ov.hasHeader(),
        ov.hasFooter(),
        doc.sections().size(),
        new RevisionOverview(
            rev.trackingEnabled(),
            rev.totalCount(),
            rev.countByType(),
            rev.countByAuthor(),
            recent),
        buildCommentOverview(doc, commentResolver));
  }

  /**
   * 构建批注概览：总数/按作者计数用文档级 {@code doc.comments().list()}（含锚在单元格内段落的批注）， recent 明细带锚定段落 ref（顶层段落一遍
   * {@code Paragraph.comments()} 建 id→ref 映射）。
   *
   * <p>与修订概览同型：批注创作（{@code comment.add}）落地后复审要能从只读视图核对「作者/内容/挂哪段」， 否则会像修订作者盲区一样把已正确的文档误报「未达成」。
   */
  private static CommentOverview buildCommentOverview(Document doc, ElementResolver resolver) {
    List<io.github.nondirectional.docx.core.api.comment.Comment> all;
    try {
      all = doc.comments().list();
    } catch (RuntimeException e) {
      all = List.of();
    }
    Map<String, Integer> countByAuthor = new LinkedHashMap<>();
    for (io.github.nondirectional.docx.core.api.comment.Comment c : all) {
      countByAuthor.merge(c.author(), 1, Integer::sum);
    }
    Map<String, String> anchorRefById = new HashMap<>();
    for (BodyElement be : doc.bodyElements()) {
      if (!(be instanceof Paragraph)) {
        continue;
      }
      Paragraph p = (Paragraph) be;
      try {
        List<io.github.nondirectional.docx.core.api.comment.Comment> anchored = p.comments();
        if (!anchored.isEmpty()) {
          String ref = resolver.reference(p).canonical();
          for (io.github.nondirectional.docx.core.api.comment.Comment c : anchored) {
            anchorRefById.putIfAbsent(c.id(), ref);
          }
        }
      } catch (RuntimeException ignored) {
        // 单段解析失败跳过，不让批注概览拖垮整个 outline
      }
    }
    List<CommentEntry> recent = new ArrayList<>();
    for (io.github.nondirectional.docx.core.api.comment.Comment c : all) {
      if (recent.size() >= RECENT_COMMENTS_MAX) {
        break;
      }
      recent.add(
          new CommentEntry(
              anchorRefById.get(c.id()),
              c.author(),
              truncate(c.text() == null ? "" : c.text(), COMMENT_SNIPPET_MAX)));
    }
    return new CommentOverview(all.size(), countByAuthor, recent);
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
                  r.fontEastAsia(),
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
        commentCount(doc),
        ov.hasToc(),
        ov.hasHeader(),
        ov.hasFooter(),
        footerTextPreview(doc),
        fonts,
        fontSizes);
  }

  /** 页脚文本预览：无页脚/空页脚为 null；截断到 {@value #FOOTER_PREVIEW_MAX} 字符。 */
  private static String footerTextPreview(Document doc) {
    try {
      Footer footer = doc.footer();
      if (footer == null) {
        return null;
      }
      String text = footer.text();
      if (text == null || text.isBlank()) {
        return null;
      }
      return text.length() <= FOOTER_PREVIEW_MAX
          ? text.trim()
          : text.substring(0, FOOTER_PREVIEW_MAX);
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  /** 批注总数（文档级，恒出现——0=无）；core 读取异常时退回 0，不阻塞 stats。 */
  private static int commentCount(Document doc) {
    try {
      return doc.comments().list().size();
    } catch (RuntimeException ignored) {
      return 0;
    }
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
   * <p>支持 paragraph/table/run/header_footer；段落与 run 详情带 location，页眉/页脚 part 返回文本与段落数。其余类型返回 kind +
   * ref + 空 properties。ref 解析失败时抛 {@link
   * io.github.nondirectional.docx.toolkit.ref.RefResolutionException}，由调用方（ViewTools）捕获转为
   * ToolResult。
   */
  public ElementView element(Document doc, String docId, long generation, String refCanonical) {
    ElementRef ref = ElementRefs.parse(refCanonical);
    ElementResolver resolver = referenceContext.resolver(new DocumentRef(docId, generation), doc);

    Map<String, Object> properties = new LinkedHashMap<>();
    switch (ref.kind()) {
      case PARAGRAPH:
        {
          Paragraph p = resolver.resolveAny((ParagraphRef) ref);
          properties.put("text", p.text());
          properties.put("location", resolver.describe(p));
          // 对齐恒出现（未设置=left）：只读复审靠元素详情核对「居中标题」类要求，
          // 属性缺失会被误读成「未设置」。命名与 {align=…} 写侧记号一致（left/center/right/both），
          // 让读者能把 alignment:"center" 与 {align=center} 对上。
          properties.put("alignment", alignmentName(p.alignment()));
          double lineSpacing = p.lineSpacing();
          if (lineSpacing > 0) {
            properties.put("lineSpacing", lineSpacing);
          }
          Integer hl = p.heading() == null ? null : p.heading().ordinal() + 1;
          if (hl != null) {
            properties.put("headingLevel", hl);
          }
          properties.put("listItem", p.listLevel() != null);
          // 批注恒出现（空数组=无）：复审核对「这段挂了哪些批注」的直接证据；
          // 属性缺失会被读成「未知/未设置」，与 alignment 恒出现同理
          List<Map<String, Object>> comments = new ArrayList<>();
          for (io.github.nondirectional.docx.core.api.comment.Comment c : p.comments()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", c.id());
            entry.put("author", c.author());
            entry.put("text", c.text());
            comments.add(entry);
          }
          properties.put("comments", comments);
          break;
        }
      case TABLE:
        {
          Table t = resolver.resolve((TableRef) ref);
          properties.put("rowCount", t.rows().size());
          properties.put("columnCount", t.rows().isEmpty() ? 0 : t.rows().get(0).cells().size());
          // 单元格文本矩阵预览（≤6 行，每格截断）——只读复审核对「某格改成了什么」的依据；
          // 只给行列数时复审无法确认单元格内容（table-cell-edit 曾因此误报）
          List<List<String>> cellTexts = new ArrayList<>();
          int rowsShown = Math.min(t.rows().size(), TABLE_ELEMENT_ROWS_MAX);
          for (int r = 0; r < rowsShown; r++) {
            List<String> rowTexts = new ArrayList<>();
            for (io.github.nondirectional.docx.core.api.table.Cell c : t.rows().get(r).cells()) {
              rowTexts.add(truncate(c.text(), TABLE_ELEMENT_CELL_MAX));
            }
            cellTexts.add(rowTexts);
          }
          if (t.rows().size() > rowsShown) {
            cellTexts.add(List.of("…（共 " + t.rows().size() + " 行）"));
          }
          properties.put("cellTexts", cellTexts);
          break;
        }
      case CELL:
        {
          io.github.nondirectional.docx.core.api.table.Cell c = resolver.resolve((CellRef) ref);
          properties.put("text", c.text());
          break;
        }
      case REVISION:
        {
          io.github.nondirectional.docx.core.api.track.TrackedChange change =
              resolver.resolve((RevisionRef) ref);
          properties.put("type", change.type() == null ? null : change.type().name().toLowerCase());
          properties.put("author", change.author());
          properties.put("date", change.date() == null ? null : change.date().toString());
          var details = change.details();
          if (details instanceof io.github.nondirectional.docx.core.api.track.TextChangeDetails) {
            properties.put(
                "text",
                ((io.github.nondirectional.docx.core.api.track.TextChangeDetails) details).text());
            // 带格式修订的证据：修订内 run 不被 paragraph.runs() 暴露（N14），
            // run 级视图够不着——复审核对「以修订加粗/倾斜」只能看这里（恒出现）
            boolean bold = false;
            boolean italic = false;
            try {
              for (var ctr : change.raw().getRList()) {
                if (ctr.getRPr() != null) {
                  bold |= ctr.getRPr().sizeOfBArray() > 0;
                  italic |= ctr.getRPr().sizeOfIArray() > 0;
                }
              }
            } catch (RuntimeException ignored) {
              // 读不到 run 的修订按无格式上报
            }
            properties.put("bold", bold);
            properties.put("italic", italic);
          }
          break;
        }
      case RUN:
        {
          Run r = resolver.resolve((RunRef) ref);
          properties.put("text", r.text());
          properties.put("location", resolver.describe(r));
          properties.put("bold", r.isBold());
          properties.put("italic", r.isItalic());
          putIfNotNull(properties, "font", r.font());
          // 中文槽与西文槽分离展示：编译器写的是「西文 TNR/Arial + 中文宋体/黑体」双槽，
          // 只暴露 ascii 槽会让读者把已正确设置的中文字体误判成「未设置」
          putIfNotNull(properties, "fontEastAsia", r.fontEastAsia());
          putIfNotNull(properties, "fontSize", r.fontSize());
          putIfNotNull(properties, "color", r.color());
          break;
        }
      case HEADER_FOOTER:
        {
          HeaderFooterRef hf = (HeaderFooterRef) ref;
          try {
            Header header = resolver.resolveHeader(hf);
            properties.put("part", "header");
            properties.put("text", header.text());
            properties.put("paragraphCount", header.paragraphs().size());
          } catch (RefResolutionException ignored) {
            Footer footer = resolver.resolveFooter(hf);
            properties.put("part", "footer");
            properties.put("text", footer.text());
            properties.put("paragraphCount", footer.paragraphs().size());
          }
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

  /**
   * 对齐的稳定小写名，与 Markdown 写侧的 {@code {align=…}} 记号一致（left/center/right/both）。
   *
   * <p>view_element 的段落详情用它暴露 alignment——读者（含只读复审 SubAgent）需要能把读到的值与 写侧记号直接对上，不复用 enum
   * 名（JUSTIFY）以免两套叫法。
   */
  private static String alignmentName(
      io.github.nondirectional.docx.core.api.style.Alignment alignment) {
    switch (alignment) {
      case CENTER:
        return "center";
      case RIGHT:
        return "right";
      case JUSTIFY:
        return "both";
      case LEFT:
      default:
        return "left";
    }
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
