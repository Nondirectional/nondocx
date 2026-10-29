package io.github.nondirectional.docx.toolkit.compiler;

import io.github.nondirectional.docx.core.api.style.Alignment;
import io.github.nondirectional.docx.core.api.style.ListKind;
import java.util.List;
import java.util.Objects;

/**
 * Markdown 子集解析后的文档 IR（中间表示）。
 *
 * <p>它是「意图 → 文档模型 → OOXML」编译管线的第一站：{@link MarkdownParser} 把 Markdown 文本解析成块（block）列表，{@link
 * DocumentCompiler} 再把块列表 + 样式 profile 编译成 nondocx-core 的活文档。IR 刻意保持最小——只描述结构，不描述字体字号： 排版策略全部由
 * {@link StyleProfile} 承担，这是「模型写结构、编译器管排版」分工的落点。
 *
 * <p><b>支持的块类型（v1）：</b>
 *
 * <ul>
 *   <li>{@link Heading}——{@code #}~{@code ######} 标题，可带 {@code {align=…}} 属性；
 *   <li>{@link Para}——段落，由行内 span 组成（粗体/斜体/代码）；
 *   <li>{@link Table}——Markdown 管线表格，首行为表头；
 *   <li>{@link ListItem}——无序（{@code -}/{@code *})或有序（{@code 1.}）列表项，按缩进分级。
 * </ul>
 *
 * <p>不认识的语法<b>宽容降级</b>为段落并记入 {@code warnings}，由调用方决定是否回显给 Agent——编译器兜底而不是拒绝，是本包对 LLM 输入的基本姿态。
 */
public final class MarkdownDoc {

  private final List<Block> blocks;
  private final List<String> warnings;

  public MarkdownDoc(List<Block> blocks, List<String> warnings) {
    this.blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
    this.warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
  }

  /** 解析出的块列表（保持文档顺序）。 */
  public List<Block> blocks() {
    return blocks;
  }

  /** 宽容降级产生的警告（如「表格缺少分隔行，已按段落处理」）。 */
  public List<String> warnings() {
    return warnings;
  }

  /** IR 块的公共接口：块只可能是 {@link Heading}/{@link Para}/{@link Table}/{@link ListItem}。 */
  public interface Block {}

  /** 标题块。{@code level} 1~6；{@code alignment} 为 {@code null} 表示未指定（交给 profile）。 */
  public static final class Heading implements Block {

    private final int level;
    private final String text;
    private final Alignment alignment;

    public Heading(int level, String text, Alignment alignment) {
      this.level = level;
      this.text = Objects.requireNonNull(text, "text");
      this.alignment = alignment;
    }

    public int level() {
      return level;
    }

    public String text() {
      return text;
    }

    public Alignment alignment() {
      return alignment;
    }
  }

  /** 段落块。spans 至少一条；{@code alignment} 为 {@code null} 表示未指定。 */
  public static final class Para implements Block {

    private final List<Span> spans;
    private final Alignment alignment;

    public Para(List<Span> spans, Alignment alignment) {
      this.spans = List.copyOf(Objects.requireNonNull(spans, "spans"));
      this.alignment = alignment;
    }

    public List<Span> spans() {
      return spans;
    }

    public Alignment alignment() {
      return alignment;
    }
  }

  /** 表格块。header 与每行等长（解析期已校）；单元格按纯文本处理。 */
  public static final class Table implements Block {

    private final List<String> header;
    private final List<List<String>> rows;

    public Table(List<String> header, List<List<String>> rows) {
      this.header = List.copyOf(Objects.requireNonNull(header, "header"));
      this.rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
    }

    public List<String> header() {
      return header;
    }

    public List<List<String>> rows() {
      return rows;
    }
  }

  /** 列表项块。{@code level} 从 0 开始（缩进每 2 空格一级）。 */
  public static final class ListItem implements Block {

    private final ListKind kind;
    private final int level;
    private final List<Span> spans;

    public ListItem(ListKind kind, int level, List<Span> spans) {
      this.kind = Objects.requireNonNull(kind, "kind");
      this.level = level;
      this.spans = List.copyOf(Objects.requireNonNull(spans, "spans"));
    }

    public ListKind kind() {
      return kind;
    }

    public int level() {
      return level;
    }

    public List<Span> spans() {
      return spans;
    }
  }

  /** 行内 span：纯文本 + 三个直接格式标记。不支持嵌套强调。 */
  public static final class Span {

    private final String text;
    private final boolean bold;
    private final boolean italic;
    private final boolean code;

    public Span(String text, boolean bold, boolean italic, boolean code) {
      this.text = Objects.requireNonNull(text, "text");
      this.bold = bold;
      this.italic = italic;
      this.code = code;
    }

    public static Span text(String text) {
      return new Span(text, false, false, false);
    }

    public String text() {
      return text;
    }

    public boolean bold() {
      return bold;
    }

    public boolean italic() {
      return italic;
    }

    public boolean code() {
      return code;
    }
  }
}
