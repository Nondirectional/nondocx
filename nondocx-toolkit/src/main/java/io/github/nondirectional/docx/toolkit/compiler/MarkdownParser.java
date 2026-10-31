package io.github.nondirectional.docx.toolkit.compiler;

import io.github.nondirectional.docx.core.api.style.Alignment;
import io.github.nondirectional.docx.core.api.style.ListKind;
import io.github.nondirectional.docx.toolkit.compiler.MarkdownDoc.Block;
import io.github.nondirectional.docx.toolkit.compiler.MarkdownDoc.Heading;
import io.github.nondirectional.docx.toolkit.compiler.MarkdownDoc.ListItem;
import io.github.nondirectional.docx.toolkit.compiler.MarkdownDoc.Para;
import io.github.nondirectional.docx.toolkit.compiler.MarkdownDoc.Span;
import io.github.nondirectional.docx.toolkit.compiler.MarkdownDoc.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 子集解析器：Markdown 文本 → {@link MarkdownDoc} IR。
 *
 * <p><b>支持范围（v1）：</b>标题（{@code #}~{@code ######}）、段落、行内 {@code **粗体**}/{@code *斜体*}/{@code
 * `代码`}/{@code <u>下划线</u>}、无序/有序列表（缩进 2 空格一级）、 管线表格（{@code |---|} 分隔行，{@code \|} 转义）、行尾 {@code
 * {align=center|left|right|both}} 属性、围栏代码块（降级为等宽段落）。
 *
 * <p><b>解析姿态：宽容降级，不拒绝。</b> LLM 产出的 Markdown 一定带瑕疵，解析器的工作是 兜住可识别的部分、把识别不了的按纯文本处理并记
 * warning（如「表格缺少分隔行，已按普通 段落处理」），而不是让一次创作整体失败。行内标记未闭合时原样保留字符。
 *
 * <p><b>段落切分约定。</b> 每个<b>非空行</b>是一个段落块（不做 Markdown 软换行合并）。 LLM 写段落时几乎总在段间留空行，按行切分最可预测，也让 {@code
 * read_document} 的 短 ID 与行一一对应。
 *
 * <p>本类无状态、无会话依赖，可安全复用。
 */
public final class MarkdownParser {

  private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
  private static final Pattern LIST_BULLET = Pattern.compile("^(\\s*)[-*]\\s+(.*)$");
  private static final Pattern LIST_NUMBER = Pattern.compile("^(\\s*)\\d+[.、)]\\s+(.*)$");
  private static final Pattern BLOCKQUOTE = Pattern.compile("^>\\s?(.*)$");
  private static final Pattern ATTR =
      Pattern.compile("\\{\\s*align\\s*=\\s*(center|left|right|both)\\s*\\}\\s*$");
  private static final Pattern TRAILING_COMMENT = Pattern.compile("\\s*<!--.*?-->\\s*$");
  private static final Pattern INLINE =
      Pattern.compile("(\\*\\*.+?\\*\\*|\\*[^*\\n]+?\\*|`[^`\\n]+?`|<u>(.+?)</u>)");

  private MarkdownParser() {}

  /** 解析 Markdown 文本。入参 {@code null} 或全空白视为空文档（无块、无警告）。 */
  public static MarkdownDoc parse(String markdown) {
    List<Block> blocks = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    if (markdown == null || markdown.isBlank()) {
      return new MarkdownDoc(blocks, warnings);
    }
    String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    int i = 0;
    boolean inFence = false;
    StringBuilder fenceText = new StringBuilder();
    while (i < lines.length) {
      String line = lines[i].trim();
      // 围栏代码块：``` / ~~~ 之间整块收集，降级为等宽段落。
      if (line.startsWith("```") || line.startsWith("~~~")) {
        if (inFence) {
          if (fenceText.length() > 0) {
            blocks.add(new Para(List.of(new Span(fenceText.toString(), false, false, true)), null));
          }
          fenceText.setLength(0);
          inFence = false;
        } else {
          inFence = true;
        }
        i++;
        continue;
      }
      if (inFence) {
        if (fenceText.length() > 0) {
          fenceText.append('\n');
        }
        fenceText.append(lines[i]);
        i++;
        continue;
      }
      // HTML 注释是元数据不是内容：行尾锚点（<!-- p0 -->）剥掉；整行注释跳过。
      // 这让 read_document 输出的 Markdown 可原样再作为 create_document 输入（round-trip）。
      String uncommented = TRAILING_COMMENT.matcher(line).replaceFirst("");
      if (uncommented.isEmpty()) {
        i++;
        continue;
      }
      line = uncommented;
      if (line.isEmpty()) {
        i++;
        continue;
      }
      Matcher blockquote = BLOCKQUOTE.matcher(line);
      if (blockquote.matches()) {
        // 引用块不单独渲染：剥掉前缀按普通段落处理（v1 取舍，内容无损）。
        line = blockquote.group(1).trim();
        if (line.isEmpty()) {
          i++;
          continue;
        }
      }
      Matcher heading = HEADING.matcher(line);
      if (heading.matches()) {
        blocks.add(parseHeading(heading.group(1).length(), heading.group(2)));
        i++;
        continue;
      }
      if (line.startsWith("|")) {
        // 收集连续表格行，交独立的表格解析（可能降级为段落）。行尾注释一并剥掉。
        int j = i;
        List<String> tableLines = new ArrayList<>();
        while (j < lines.length && lines[j].trim().startsWith("|")) {
          String tableLine = TRAILING_COMMENT.matcher(lines[j].trim()).replaceFirst("").trim();
          if (!tableLine.isEmpty()) {
            tableLines.add(tableLine);
          }
          j++;
        }
        parseTable(tableLines, blocks, warnings);
        i = j;
        continue;
      }
      Matcher bullet = LIST_BULLET.matcher(lines[i]);
      if (bullet.matches()) {
        blocks.add(parseListItem(ListKind.BULLET, bullet.group(1).length(), bullet.group(2)));
        i++;
        continue;
      }
      Matcher number = LIST_NUMBER.matcher(lines[i]);
      if (number.matches()) {
        blocks.add(parseListItem(ListKind.NUMBERED, number.group(1).length(), number.group(2)));
        i++;
        continue;
      }
      blocks.add(parsePara(line));
      i++;
    }
    if (inFence && fenceText.length() > 0) {
      // 未闭合的围栏：内容不丢，同样降级为代码段落。
      blocks.add(new Para(List.of(new Span(fenceText.toString(), false, false, true)), null));
      warnings.add("围栏代码块未闭合，已按代码段落处理");
    }
    return new MarkdownDoc(blocks, warnings);
  }

  // ==================== 块级解析 ====================

  private static Heading parseHeading(int level, String rest) {
    Alignment alignment = null;
    Matcher attr = ATTR.matcher(rest);
    if (attr.find()) {
      alignment = alignmentOf(attr.group(1));
      rest = rest.substring(0, attr.start()).trim();
    }
    // 标题内不保留行内标记：解析后取纯文本拼接（# **粗体标题** → 「粗体标题」）。
    return new Heading(level, stripInlineMarkers(rest), alignment);
  }

  private static Para parsePara(String line) {
    Alignment alignment = null;
    Matcher attr = ATTR.matcher(line);
    if (attr.find()) {
      alignment = alignmentOf(attr.group(1));
      line = line.substring(0, attr.start()).trim();
    }
    return new Para(parseInline(line), alignment);
  }

  private static ListItem parseListItem(ListKind kind, int indentSpaces, String rest) {
    int level = Math.min(8, indentSpaces / 2);
    return new ListItem(kind, level, parseInline(rest));
  }

  /**
   * 解析一段连续的 {@code |…|} 行。
   *
   * <p>第 2 行是分隔行（{@code |---|---|}，允许 {@code :}）认定为标准表格；<b>缺少分隔行</b> 时按行数分流：≥2 行仍按表格解析（首行视为表头，记
   * warning——LLM 常漏写分隔行，倒进 段落会产生带管道符的脏文本）；单行才降级为段落。行列数与表头不一致时补空/截断并记 warning。
   */
  private static void parseTable(
      List<String> tableLines, List<Block> blocks, List<String> warnings) {
    boolean hasSeparator = tableLines.size() >= 2 && isSeparatorRow(tableLines.get(1));
    if (!hasSeparator && tableLines.size() < 2) {
      warnings.add("表格缺少 |---| 分隔行，已按普通段落处理（首个表格行: " + truncate(tableLines.get(0)) + "）");
      for (String line : tableLines) {
        blocks.add(new Para(List.of(Span.text(line)), null));
      }
      return;
    }
    if (!hasSeparator) {
      warnings.add("表格缺少 |---| 分隔行，首行已按表头处理");
    }
    int firstDataRow = hasSeparator ? 2 : 1;
    // 单元格按纯文本处理（v1 不承载富文本）：行内标记剥掉，避免「**加粗**」星号污染文本。
    List<String> header = stripCellMarkers(splitCells(tableLines.get(0)));
    List<List<String>> rows = new ArrayList<>();
    for (int k = firstDataRow; k < tableLines.size(); k++) {
      List<String> cells = stripCellMarkers(splitCells(tableLines.get(k)));
      if (cells.size() != header.size()) {
        warnings.add(
            "表格第 "
                + (k - firstDataRow + 1)
                + " 行有 "
                + cells.size()
                + " 列，与表头 "
                + header.size()
                + " 列不一致，已补空/截断");
      }
      while (cells.size() < header.size()) {
        cells.add("");
      }
      rows.add(new ArrayList<>(cells.subList(0, Math.min(cells.size(), header.size()))));
    }
    blocks.add(new Table(header, rows));
  }

  /** 逐格剥行内标记（粗体/斜体/代码/下划线），保持单元格纯文本。 */
  private static List<String> stripCellMarkers(List<String> cells) {
    List<String> stripped = new ArrayList<>();
    for (String cell : cells) {
      stripped.add(stripInlineMarkers(cell));
    }
    return stripped;
  }

  /** 分隔行判定：整行只含 {@code | - : 空白} 且至少一个 {@code -}。 */
  private static boolean isSeparatorRow(String line) {
    boolean sawDash = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '-') {
        sawDash = true;
      } else if (c != '|' && c != ':' && c != ' ' && c != '\t') {
        return false;
      }
    }
    return sawDash;
  }

  /** 按 未转义的 {@code |} 切分单元格，剥掉首尾空管道与空白；支持 {@code \|} 转义。 */
  static List<String> splitCells(String line) {
    List<String> cells = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '\\' && i + 1 < line.length() && line.charAt(i + 1) == '|') {
        current.append('|');
        i++;
      } else if (c == '|') {
        cells.add(current.toString().trim());
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    cells.add(current.toString().trim());
    // 行首/行尾管道会产生两个空串端点，剥掉；行中空单元格保留。
    if (!cells.isEmpty() && cells.get(0).isEmpty()) {
      cells.remove(0);
    }
    if (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) {
      cells.remove(cells.size() - 1);
    }
    return cells;
  }

  // ==================== 行内解析 ====================

  /**
   * 行内解析：切出 code/粗体/斜体/下划线片段，其余按纯文本 span。未闭合标记原样保留。
   *
   * <p>{@code <u>…</u>} 的内容<b>递归</b>走同一解析器再逐 span 打上下划线——内层的粗/斜/代码照常生效， 下划线取并集。粗/斜 token 的内层含
   * {@code <u>} 时同样递归（读侧 {@code **<u>x</u>**} 才能还原为粗体+下划线）， 内层不含 {@code <u>} 时仍按 v1
   * 的字面整体语义处理，不改变既有输入的 span 结构。
   */
  static List<Span> parseInline(String text) {
    List<Span> spans = new ArrayList<>();
    Matcher matcher = INLINE.matcher(text);
    int last = 0;
    while (matcher.find()) {
      if (matcher.start() > last) {
        spans.add(Span.text(text.substring(last, matcher.start())));
      }
      String token = matcher.group(1);
      if (token.startsWith("**")) {
        expandEmphasis(spans, token.substring(2, token.length() - 2), true, false);
      } else if (token.startsWith("`")) {
        spans.add(new Span(token.substring(1, token.length() - 1), false, false, true));
      } else if (token.startsWith("<u>")) {
        for (Span inner : parseInline(token.substring(3, token.length() - 4))) {
          spans.add(inner.withUnderline());
        }
      } else {
        expandEmphasis(spans, token.substring(1, token.length() - 1), false, true);
      }
      last = matcher.end();
    }
    if (last < text.length()) {
      spans.add(Span.text(text.substring(last)));
    }
    if (spans.isEmpty()) {
      spans.add(Span.text(""));
    }
    return spans;
  }

  /**
   * 粗体/斜体 token 的内层展开。
   *
   * <p>内层<b>不含</b> {@code <u>} 时整体成一个 span——与 v1 完全一致的「内层其它标记保持字面」语义； 含 {@code <u>} 时递归解析（保证读侧
   * {@code **<u>x</u>**}/{@code *<u>x</u>*} 能还原出粗/斜+下划线）， 再对内层每个 span 取并集。
   */
  private static void expandEmphasis(List<Span> spans, String inner, boolean bold, boolean italic) {
    List<Span> innerSpans = inner.contains("<u>") ? parseInline(inner) : List.of(Span.text(inner));
    for (Span span : innerSpans) {
      spans.add(
          new Span(
              span.text(),
              bold || span.bold(),
              italic || span.italic(),
              span.code(),
              span.underline()));
    }
  }

  /** 把行内标记剥成纯文本（标题用：# **粗体标题** → 粗体标题）。 */
  static String stripInlineMarkers(String text) {
    StringBuilder sb = new StringBuilder();
    for (Span span : parseInline(text)) {
      sb.append(span.text());
    }
    return sb.toString();
  }

  /** {@code {align=…}} 属性值 → 对齐枚举。 */
  static Alignment alignmentOf(String value) {
    switch (value) {
      case "center":
        return Alignment.CENTER;
      case "right":
        return Alignment.RIGHT;
      case "both":
        return Alignment.JUSTIFY;
      case "left":
      default:
        return Alignment.LEFT;
    }
  }

  private static String truncate(String value) {
    return value.length() <= 40 ? value : value.substring(0, 40) + "…";
  }
}
