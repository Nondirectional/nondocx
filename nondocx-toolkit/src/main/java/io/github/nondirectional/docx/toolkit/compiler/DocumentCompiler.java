package io.github.nondirectional.docx.toolkit.compiler;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.style.Alignment;
import io.github.nondirectional.docx.core.api.style.HeadingLevel;
import io.github.nondirectional.docx.core.api.table.Row;
import io.github.nondirectional.docx.core.api.table.Table;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import io.github.nondirectional.docx.core.api.text.Run;
import io.github.nondirectional.docx.toolkit.compiler.MarkdownDoc.Block;
import io.github.nondirectional.docx.toolkit.compiler.MarkdownDoc.Heading;
import io.github.nondirectional.docx.toolkit.compiler.MarkdownDoc.ListItem;
import io.github.nondirectional.docx.toolkit.compiler.MarkdownDoc.Para;
import io.github.nondirectional.docx.toolkit.compiler.MarkdownDoc.Span;
import java.util.List;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;

/**
 * 文档编译器：{@link MarkdownDoc} IR + {@link StyleProfile} → nondocx-core 活文档。
 *
 * <p><b>职责边界。</b> 只组合 core 已有的活对象 API（{@code addParagraph().heading(…)}、 {@code lineSpacing}、{@code
 * indent}、{@code list}、表格行/单元格），不新增排版行为； 排版决策全部来自 profile，IR 块自身的显式属性（如 {@code {align=center}}）优先于
 * profile。
 *
 * <p><b>唯一的 raw() 用点：eastAsia 字体槽。</b> core 公开 API 的 {@link Run#font(String)} 只写 ascii 槽（POI {@code
 * setFontFamily}）；中文文档里中文字形走 eastAsia 槽，只设 ascii 槽会让宋体/黑体在 Word/WPS 里不生效。在 core 提供公开双槽 API 之前，编译器经
 * {@link Run#raw()} 补写 {@code w:rFonts/@w:eastAsia}——这是 spec（poi-bridge §3）允许的 raw-only
 * 逃生口用法，集中在本类一个私有方法内。
 */
public final class DocumentCompiler {

  private DocumentCompiler() {}

  /** 编译产物：活文档 + 结构统计 + 解析期警告（透传给调用方回显）。 */
  public static final class CompiledDocument {

    private final Document document;
    private final int paragraphCount;
    private final int tableCount;
    private final List<String> warnings;

    public CompiledDocument(
        Document document, int paragraphCount, int tableCount, List<String> warnings) {
      this.document = document;
      this.paragraphCount = paragraphCount;
      this.tableCount = tableCount;
      this.warnings = List.copyOf(warnings);
    }

    public Document document() {
      return document;
    }

    public int paragraphCount() {
      return paragraphCount;
    }

    public int tableCount() {
      return tableCount;
    }

    public List<String> warnings() {
      return warnings;
    }
  }

  /**
   * 编译 IR + profile 为一份新文档。
   *
   * <p>编译失败（如空 IR）抛 {@link IllegalArgumentException}——由工具层转 INVALID_ARGUMENT；编译期不触碰任何会话状态。
   *
   * @param doc 解析好的 IR（至少一个块）
   * @param profile 样式策略（{@code null} 视为 {@code plain}）
   * @return 编译产物（调用方拥有返回的 Document，负责关闭）
   */
  public static CompiledDocument compile(MarkdownDoc doc, StyleProfile profile) {
    if (doc == null || doc.blocks().isEmpty()) {
      throw new IllegalArgumentException("markdown 内容为空，无法编译成文档");
    }
    StyleProfile p = profile == null ? StyleProfile.builtin("plain") : profile;
    Document document = Docx.create();
    int tables = 0;
    for (Block block : doc.blocks()) {
      if (block instanceof Heading) {
        compileHeading(document, (Heading) block, p);
      } else if (block instanceof Para) {
        compilePara(document, (Para) block, p);
      } else if (block instanceof MarkdownDoc.Table) {
        compileTableInto(document, (MarkdownDoc.Table) block, p);
        tables++;
      } else if (block instanceof ListItem) {
        compileListItem(document, (ListItem) block, p);
      }
    }
    return new CompiledDocument(document, document.paragraphs().size(), tables, doc.warnings());
  }

  // ==================== 块编译 ====================

  private static void compileHeading(Document document, Heading h, StyleProfile p) {
    HeadingLevel level = HeadingLevel.values()[Math.min(Math.max(h.level() - 1, 0), 5)];
    fillHeading(document.addParagraph().heading(level), h, p);
  }

  /** 把标题块内容填进<b>已存在</b>的段落（标题级别由调用方设置，见 {@link #headingLevelOf}）。 */
  static void fillHeading(Paragraph paragraph, Heading h, StyleProfile p) {
    // 显式 {align=…} 优先；否则 H1 默认按 profile（中文报告一级标题常居中）。
    if (h.alignment() != null) {
      paragraph.alignment(h.alignment());
    } else if (h.level() == 1 && p.h1Center()) {
      paragraph.alignment(Alignment.CENTER);
    }
    Run run = paragraph.addRun(h.text());
    applyRunFonts(run, p.headingLatin(), p.headingEastAsia());
    if (p.headingSizePt(h.level()) != null) {
      run.fontSize(p.headingSizePt(h.level()));
    }
  }

  /** 标题块的 IR 级别 → core HeadingLevel（供定位插入的调用方在段落上设置）。 */
  static HeadingLevel headingLevelOf(Heading h) {
    return HeadingLevel.values()[Math.min(Math.max(h.level() - 1, 0), 5)];
  }

  private static void compilePara(Document document, Para para, StyleProfile p) {
    fillPara(document.addParagraph(), para, p);
  }

  /** 把段落块内容填进已存在的段落（PatchEngine 定位插入复用）。 */
  static void fillPara(Paragraph paragraph, Para para, StyleProfile p) {
    if (para.alignment() != null) {
      paragraph.alignment(para.alignment());
    }
    boolean codeOnly = para.spans().size() == 1 && para.spans().get(0).code();
    if (p.bodyLineSpacing() != null) {
      paragraph.lineSpacing(p.bodyLineSpacing());
    }
    // 首行缩进只给正文散文：代码段与显式对齐的段落不缩进。
    if (!codeOnly && para.alignment() == null && p.firstLineIndentTwips() > 0) {
      paragraph.indent(0, p.firstLineIndentTwips());
    }
    for (Span span : para.spans()) {
      Run run = paragraph.addRun(span.text());
      if (span.bold()) {
        run.bold();
      }
      if (span.italic()) {
        run.italic();
      }
      if (span.underline()) {
        run.underline();
      }
      if (span.code()) {
        if (p.codeFont() != null) {
          applyRunFonts(run, p.codeFont(), p.codeFont());
        }
      } else {
        applyRunFonts(run, p.bodyLatin(), p.bodyEastAsia());
      }
      if (p.bodySizePt() != null) {
        run.fontSize(p.bodySizePt());
      }
    }
  }

  private static void compileListItem(Document document, ListItem item, StyleProfile p) {
    fillListItem(document.addParagraph().list(item.kind(), Math.min(item.level(), 8)), item, p);
  }

  /** 把列表项块内容填进已存在的段落（PatchEngine 定位插入复用；列表属性由调用方设置）。 */
  static void fillListItem(Paragraph paragraph, ListItem item, StyleProfile p) {
    for (Span span : item.spans()) {
      Run run = paragraph.addRun(span.text());
      if (span.bold()) {
        run.bold();
      }
      if (span.italic()) {
        run.italic();
      }
      if (span.underline()) {
        run.underline();
      }
      if (span.code() && p.codeFont() != null) {
        applyRunFonts(run, p.codeFont(), p.codeFont());
      } else {
        applyRunFonts(run, p.bodyLatin(), p.bodyEastAsia());
      }
      if (p.bodySizePt() != null) {
        run.fontSize(p.bodySizePt());
      }
    }
  }

  /** 在文档末尾编译一个表格块（定位插入不支持表格，见 PatchEngine preflight）。 */
  static void compileTableInto(Document document, MarkdownDoc.Table table, StyleProfile p) {
    Table target = document.addTable();
    // 表格分页健壮性默认值：首行跨页重复（headerRow），数据行不跨页拆分（cantSplit）。
    Row header = target.addRow();
    header.headerRow(true);
    for (int c = 0; c < table.header().size(); c++) {
      writeCell(header, c, table.header().get(c), true, p);
    }
    for (List<String> rowCells : table.rows()) {
      Row row = target.addRow();
      row.cantSplit(true);
      for (int c = 0; c < table.header().size(); c++) {
        String text = c < rowCells.size() ? rowCells.get(c) : "";
        writeCell(row, c, text, false, p);
      }
    }
    if (p.tableEqualColumns() && !table.header().isEmpty()) {
      int n = table.header().size();
      int base = 100 / n;
      int[] percents = new int[n];
      for (int c = 0; c < n; c++) {
        percents[c] = c == n - 1 ? 100 - base * (n - 1) : base;
      }
      target.columnPercents(percents);
    }
  }

  private static void writeCell(Row row, int index, String text, boolean header, StyleProfile p) {
    // addRow() 建出的行没有单元格，按 index 逐个补建（列数由表头决定，逐行等长）。
    io.github.nondirectional.docx.core.api.table.Cell cell;
    if (index < row.cells().size()) {
      cell = row.cells().get(index);
    } else {
      cell = row.addCell();
    }
    cell.text(text);
    Run run = cell.paragraph(0).run(0);
    if (header && p.tableHeaderBold()) {
      run.bold();
    }
    if (header && p.tableHeaderShading() != null) {
      cell.shading(p.tableHeaderShading());
    }
    applyRunFonts(run, p.bodyLatin(), p.bodyEastAsia());
    if (p.bodySizePt() != null) {
      run.fontSize(p.bodySizePt());
    }
  }

  // ==================== 字体双槽 ====================

  /**
   * 同时写 ascii 与 eastAsia 字体槽。
   *
   * <p>ascii 槽走公开 API {@link Run#font(String)}；eastAsia 槽 core 暂无公开入口，经 {@code raw()} 直写 {@code
   * w:rFonts/@w:eastAsia}（POI 泄漏仅存在于本私有方法内部， 不出现在公开签名）。任一槽为 {@code null} 时跳过该槽。
   */
  static void applyRunFonts(Run run, String latin, String eastAsia) {
    if (latin != null) {
      run.font(latin);
    }
    if (eastAsia != null) {
      CTR ctr = run.raw().getCTR();
      CTRPr rpr = ctr.isSetRPr() ? ctr.getRPr() : ctr.addNewRPr();
      // rPr 的 rFonts 在 schema 里可重复，取第一个，没有则新建。
      CTFonts fonts = rpr.sizeOfRFontsArray() > 0 ? rpr.getRFontsArray(0) : rpr.addNewRFonts();
      fonts.setEastAsia(eastAsia);
    }
  }
}
