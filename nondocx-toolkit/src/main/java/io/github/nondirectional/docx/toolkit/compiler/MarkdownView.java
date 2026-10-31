package io.github.nondirectional.docx.toolkit.compiler;

import io.github.nondirectional.docx.core.api.BodyElement;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.style.Alignment;
import io.github.nondirectional.docx.core.api.table.Table;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import io.github.nondirectional.docx.core.api.text.Run;

/**
 * 文档 → Markdown 视图：把正文元素按顺序投影成带短 ID 的 Markdown 文本。
 *
 * <p>它是「文档 IR + 编译器」路线的读侧：{@code read_document} 工具用它把整份文档压缩成 一段 LLM 友好的文本——标题层级、粗斜体、下划线（{@code
 * <u>…</u>}）、列表、表格都保留，每个段落/表格带 {@code <!-- pN -->}/{@code <!-- tN -->} 短 ID 锚点。短 ID 按当前文档顺序编号 （p 序号
 * = 正文段落序，t 序号 = 表格序），<b>文档结构变化后需重新读取</b>——spike 阶段 没有跨版本锚点承诺。
 *
 * <p>与 {@code DocumentViewService} 的分工：视图服务输出结构化 DTO（stats/outline/…）， 本类输出<b>可 round-trip 的
 * Markdown</b>——模型读到什么，就能照着写什么给 {@code create_document}。两者共享「只读、不改文档」的边界。
 *
 * <p>已知取舍（v1）：超链接/图片折叠为其文本；表格单元格输出纯文本；正文之外的容器只有页眉/页脚以元数据注释行进视图 （{@code <!-- 页眉: … -->}/{@code <!--
 * 页脚: … -->}，页眉行在页脚行之前——与页面物理顺序一致；写入后供模型核对），表格内段落不在本视图内。批注以整行元数据注释紧跟被评段落（{@code <!-- 批注 pN [作者]: 内容
 * -->}，v3——comment.add 落地后模型可直接读到核对）。修订以文档尾整行元数据注释呈现（{@code <!-- 修订#N: TYPE author=… …
 * -->}，v5——tracked 修订族与 tracked.set_style 写入后的读侧证据，无修订零输出）。
 */
public final class MarkdownView {

  /** 默认输出上限（字符）；超过按行边界截断。 */
  public static final int DEFAULT_MAX_CHARS = 6000;

  /** 修订元数据片段截断上限（字符），超长以「…」结尾。 */
  private static final int REVISION_SNIPPET_MAX = 20;

  private MarkdownView() {}

  /** 渲染结果：Markdown 文本 + 是否截断 + 段落/表格总数。 */
  public static final class Rendered {

    private final String markdown;
    private final boolean truncated;
    private final int paragraphs;
    private final int tables;

    public Rendered(String markdown, boolean truncated, int paragraphs, int tables) {
      this.markdown = markdown;
      this.truncated = truncated;
      this.paragraphs = paragraphs;
      this.tables = tables;
    }

    public String markdown() {
      return markdown;
    }

    public boolean truncated() {
      return truncated;
    }

    public int paragraphs() {
      return paragraphs;
    }

    public int tables() {
      return tables;
    }
  }

  /**
   * 投影整份文档正文。
   *
   * @param doc 活文档（只读遍历，不修改）
   * @param maxChars 输出上限；{@code <=0} 用 {@link #DEFAULT_MAX_CHARS}
   */
  public static Rendered render(Document doc, int maxChars) {
    int limit = maxChars <= 0 ? DEFAULT_MAX_CHARS : maxChars;
    StringBuilder sb = new StringBuilder();
    int paragraphSeq = 0;
    int tableSeq = 0;
    for (BodyElement element : doc.bodyElements()) {
      if (element instanceof Paragraph) {
        renderParagraph(sb, (Paragraph) element, paragraphSeq++);
      } else if (element instanceof Table) {
        renderTable(sb, (Table) element, tableSeq++);
      }
      if (sb.length() > limit) {
        return truncate(sb, limit, paragraphSeq, tableSeq);
      }
    }
    // 页眉元数据行：在页脚行之前输出（与页面物理顺序一致，页眉在上）——header.set_text 写入后
    // 模型能读到页眉文本核对。读侧用 header() 只读入口（null=不存在，绝不因读视图创建页眉 part）；
    // 注释形态，round-trip 时被解析器当元数据剥掉，不产生脏块。格式与页脚行同族。
    io.github.nondirectional.docx.core.api.header.Header header = doc.header();
    if (header != null) {
      String headerText = header.text();
      if (headerText != null && !headerText.isBlank()) {
        sb.append("<!-- 页眉: ").append(headerText.trim().replace("\n", " / ")).append(" -->\n");
      }
    }
    // 页脚元数据（v2）：正文外容器里只有页脚进视图——页码写入后模型能读到「第 1 页」核对；
    // 注释形态，round-trip 时被解析器当元数据剥掉，不产生脏块。
    io.github.nondirectional.docx.core.api.header.Footer footer = doc.footer();
    if (footer != null) {
      String footerText = footer.text();
      if (footerText != null && !footerText.isBlank()) {
        sb.append("<!-- 页脚: ").append(footerText.trim().replace("\n", " / ")).append(" -->\n");
      }
    }
    // 修订元数据行（v5）：文档级修订概览，每条一行整行注释——tracked 修订族与 tracked.set_style 写入后
    // 模型/复审核对「改了什么、作者是谁、格式变更方向」的读侧证据（写读同批，§6.6/§6.13 教训：
    // 只有写侧没有读侧观测，复审会把已正确的文档误报「未达成」）。无修订零输出（零噪声）。
    appendRevisionLines(sb, doc);
    return new Rendered(sb.toString(), false, paragraphSeq, tableSeq);
  }

  /**
   * 追加文档级修订元数据行：条数行 + 每条一行（{@code <!-- 修订#N: TYPE author=… … -->}）。 TEXT/移动类给文本片段「…」，PROPERTY
   * 类给新旧属性摘要（{@code 属性=RUN_PROPERTIES 新[b] 旧[]}），CELL 类只给 type+author。 无修订零输出；整行注释在 round-trip
   * 时被解析器跳过，不产生脏块。{@code trackedChanges()} 走 CT 树遍历，live 语义可读（rPrChange 不依赖 reopen）。
   */
  private static void appendRevisionLines(StringBuilder sb, Document doc) {
    java.util.List<io.github.nondirectional.docx.core.api.track.TrackedChange> changes;
    try {
      changes = doc.trackedChanges().list();
    } catch (RuntimeException e) {
      return; // 修订枚举失败只丢观测行，不拖垮整个读视图
    }
    if (changes.isEmpty()) {
      return;
    }
    sb.append("<!-- 修订 ").append(changes.size()).append(" 条 -->\n");
    for (int i = 0; i < changes.size(); i++) {
      io.github.nondirectional.docx.core.api.track.TrackedChange change = changes.get(i);
      sb.append("<!-- 修订#")
          .append(i)
          .append(": ")
          .append(change.type())
          .append(" author=")
          .append(change.author());
      io.github.nondirectional.docx.core.api.track.ChangeDetails details = change.details();
      if (details instanceof io.github.nondirectional.docx.core.api.track.TextChangeDetails) {
        sb.append(" 「")
            .append(
                shorten(
                    escapeText(
                        ((io.github.nondirectional.docx.core.api.track.TextChangeDetails) details)
                            .text())))
            .append("」");
      } else if (details
          instanceof io.github.nondirectional.docx.core.api.track.PropertyChangeDetails) {
        io.github.nondirectional.docx.core.api.track.PropertyChangeDetails property =
            (io.github.nondirectional.docx.core.api.track.PropertyChangeDetails) details;
        sb.append(" 属性=")
            .append(property.kind())
            .append(" 新")
            .append(property.newSummary())
            .append(" 旧")
            .append(property.oldSummary());
      }
      sb.append(" -->\n");
    }
  }

  /** 截断到上限（超长以「…」结尾），供修订片段展示。 */
  private static String shorten(String text) {
    return text.length() <= REVISION_SNIPPET_MAX
        ? text
        : text.substring(0, REVISION_SNIPPET_MAX - 1) + "…";
  }

  private static void renderParagraph(StringBuilder sb, Paragraph paragraph, int seq) {
    String anchor = "<!-- p" + seq + " -->";
    Alignment alignment = paragraph.alignment();
    String attr =
        alignment != null && alignment != Alignment.LEFT
            ? " {align=" + attrName(alignment) + "}"
            : "";
    String marker = listMarker(paragraph);
    if (paragraph.heading() != null) {
      String hashes = "#".repeat(Math.max(1, Math.min(paragraph.heading().ordinal() + 1, 6)));
      sb.append(hashes)
          .append(' ')
          .append(escapeText(paragraph.text()))
          .append(attr)
          .append(' ')
          .append(anchor)
          .append('\n');
      return;
    }
    String body = renderRuns(paragraph);
    if (marker.isEmpty()) {
      sb.append(body.isEmpty() ? "" : body).append(attr).append(' ').append(anchor).append('\n');
    } else {
      sb.append(marker).append(body).append(' ').append(anchor).append('\n');
    }
    // 批注元数据行（整行注释）：模型核对「这段挂了什么批注」的读侧依据；
    // 整行注释被解析器跳过，round-trip 不产生脏块。格式与页脚元数据行同族。
    for (io.github.nondirectional.docx.core.api.comment.Comment c : paragraph.comments()) {
      sb.append("<!-- 批注 p")
          .append(seq)
          .append(" [")
          .append(c.author())
          .append("]: ")
          .append(escapeText(c.text()))
          .append(" -->\n");
    }
  }

  /**
   * 段落 run → 行内标记文本。
   *
   * <p>下划线在最内层包 {@code <u>…</u>}，粗/斜再包外层（{@code **<u>x</u>**}、{@code *<u>x</u>*}）——与 {@link
   * MarkdownParser} 的递归解析互逆，保证「写 → 读 → 再写」的粗体/斜体/下划线组合可还原。文本先 {@link #escapeText} 再拼标签，标签不会被二次转义。
   */
  private static String renderRuns(Paragraph paragraph) {
    StringBuilder sb = new StringBuilder();
    for (io.github.nondirectional.docx.core.api.InlineElement inline : paragraph.inlineElements()) {
      if (inline instanceof Run) {
        Run run = (Run) inline;
        String text = escapeText(run.text());
        if (!text.isEmpty()) {
          String inner = run.isUnderline() ? "<u>" + text + "</u>" : text;
          if (run.isBold()) {
            sb.append("**").append(inner).append("**");
          } else if (run.isItalic()) {
            sb.append('*').append(inner).append('*');
          } else {
            sb.append(inner);
          }
        }
      }
      // 超链接/图片：无独立 Markdown 表示（v1 取舍），文本经 paragraph.text() 之外不保留。
    }
    if (sb.length() == 0) {
      // 无可渲染 run（空段落或仅超链接/图片）：回退整段纯文本，保证内容不丢。
      return escapeText(paragraph.text());
    }
    return sb.toString();
  }

  private static String listMarker(Paragraph paragraph) {
    if (paragraph.listKind() == null) {
      return "";
    }
    String indent = "  ".repeat(paragraph.listLevel() == null ? 0 : paragraph.listLevel());
    return indent
        + (paragraph.listKind() == io.github.nondirectional.docx.core.api.style.ListKind.NUMBERED
            ? "1. "
            : "- ");
  }

  private static void renderTable(StringBuilder sb, Table table, int seq) {
    int columnCount = table.rows().isEmpty() ? 0 : table.rows().get(0).cells().size();
    if (columnCount == 0) {
      sb.append("<!-- t").append(seq).append(" 空表格 -->\n");
      return;
    }
    for (int r = 0; r < table.rows().size(); r++) {
      var row = table.rows().get(r);
      sb.append('|');
      for (int c = 0; c < columnCount; c++) {
        String text = c < row.cells().size() ? row.cells().get(c).text() : "";
        sb.append(' ').append(escapeCell(text)).append(" |");
      }
      sb.append('\n');
      if (r == 0) {
        sb.append('|');
        for (int c = 0; c < columnCount; c++) {
          sb.append("---|");
        }
        sb.append('\n');
      }
    }
    // 锚点放表格后独立注释行：不放表头行内（会污染最后一列）、不放分隔行（会破坏表格识别）。
    sb.append("<!-- t").append(seq).append(" -->\n");
  }

  private static Rendered truncate(StringBuilder sb, int limit, int paragraphs, int tables) {
    String text = sb.toString();
    int cut = text.lastIndexOf('\n', limit);
    if (cut <= 0) {
      cut = limit;
    }
    return new Rendered(text.substring(0, cut) + "\n<!-- 已截断 -->\n", true, paragraphs, tables);
  }

  private static String escapeText(String text) {
    return text == null ? "" : text.replace("\n", " ");
  }

  private static String escapeCell(String text) {
    return escapeText(text).replace("|", "\\|");
  }

  private static String attrName(Alignment alignment) {
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
}
