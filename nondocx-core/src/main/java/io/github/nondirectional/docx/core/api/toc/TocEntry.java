package io.github.nondirectional.docx.core.api.toc;

import io.github.nondirectional.docx.core.internal.util.Objects;

/**
 * 目录(TOC)里的一个条目 —— 一个不可变的<b>解析值</b>,不是持有 POI 委托的活对象包装。
 *
 * <p><b>为什么不是 holding-wrapper(poi-bridge.md Rule 1)。</b> Rule 1 要求每个 {@code api/} 类型持有单个 {@code
 * final XWPF*} 委托、读写穿透。但 TOC 条目没有单一 POI 委托对象:一个条目是「TOC 域缓存里的一段可见文本」, 散落在条目段落的 {@code <w:hyperlink>}
 * 内多个 {@code <w:r>}/{@code <w:t>} 子节点上,且本质是 Word 渲染分页后的 <em>缓存快照</em>。把它当活对象包装既无对应 POI
 * 句柄、也无干净的「写入回写」语义(改条目文本等于篡改 Word 的缓存, 下次刷新会被覆盖)。因此本类诚实建模为不可变值:标题、层级、页码、锚点四个字段,构造时一次性解析,之后只读。 这是对
 * Rule 1 的<b>明确且已记录的偏差</b>,详见 {@link TableOfContents} 与 poi-bridge.md N11。
 *
 * <p><b>字段语义。</b>
 *
 * <ul>
 *   <li>{@code title} —— 条目可见标题(已剥离尾部的制表符与页码)。从不为 {@code null},可能为空。
 *   <li>{@code level} —— 从 1 开始的层级(1..9),来自条目段落的 {@code TOC1..TOC9} 样式, 退而求其次取 {@code
 *       <w:outlineLvl>}+1。无法判定时为 {@code 1}。
 *   <li>{@code pageNumber} —— 页码字符串。之所以是 {@code String} 而非 {@code int}:Word 目录页码可能是罗马数字 ({@code
 *       iv})、字母({@code A})或带前导符。无页码时为空串 {@code ""}。从不为 {@code null}。
 *   <li>{@code anchor} —— 条目超链接的内部书签锚点(形如 {@code "_Toc123456"}),用于跳转到对应标题。 条目无超链接时为 {@code null}。
 * </ul>
 *
 * <p>{@code equals}/{@code hashCode} 比较上述四个字段。
 *
 * <p><b>OOXML 对应。</b> 一个典型条目段落在 OOXML 里形如:
 *
 * <pre>{@code
 * <w:p>
 *   <w:pPr><w:pStyle w:val="TOC1"/></w:pPr>
 *   <w:hyperlink w:anchor="_Toc100001">
 *     <w:r><w:t>第一章 引言</w:t></w:r>
 *     <w:r><w:tab/></w:r>
 *     <w:r><w:t>3</w:t></w:r>
 *   </w:hyperlink>
 * </w:p>
 * }</pre>
 *
 * POI 不把这种 CTP 级 {@code <w:hyperlink>} 内的 run 通过 {@code XWPFParagraph.getRuns()} 暴露, 所以 nondocx
 * 的解析器直接走 {@code CTP} 的 {@code hyperlinkArray} 与其内 {@code CTR} 子节点(见 {@code TocFields})。
 */
public final class TocEntry {

  private final String title;
  private final int level;
  private final String pageNumber;
  private final String anchor;

  /**
   * 构造一个目录条目值。各字段归一化: {@code title} 与 {@code pageNumber} 不允许 {@code null} (无值时用空串), {@code anchor}
   * 允许 {@code null} 表示无内部超链接。
   *
   * @param title 条目标题(不能为 {@code null};无值传 {@code ""})
   * @param level 从 1 开始的层级(1..9)
   * @param pageNumber 页码字符串(不能为 {@code null};无值传 {@code ""})
   * @param anchor 内部书签锚点,无超链接时为 {@code null}
   * @throws IllegalArgumentException 如果 {@code title} 或 {@code pageNumber} 为 {@code null}, 或 {@code
   *     level} 不在 1..9
   */
  public TocEntry(String title, int level, String pageNumber, String anchor) {
    this.title = Objects.requireNonNull(title, "title");
    this.pageNumber = Objects.requireNonNull(pageNumber, "pageNumber");
    if (level < 1 || level > 9) {
      throw new IllegalArgumentException("level must be between 1 and 9 inclusive, was " + level);
    }
    this.level = level;
    this.anchor = anchor;
  }

  /**
   * 返回条目的可见标题(已剥离尾部制表符与页码)。
   *
   * @return 标题(从不为 {@code null},可能为空)
   */
  public String title() {
    return title;
  }

  /**
   * 返回从 1 开始的层级(1..9)。
   *
   * @return 层级
   */
  public int level() {
    return level;
  }

  /**
   * 返回页码字符串。可能是阿拉伯数字、罗马数字、字母等,故为 {@code String};无页码时为空串。
   *
   * @return 页码(从不为 {@code null})
   */
  public String pageNumber() {
    return pageNumber;
  }

  /**
   * 返回条目超链接的内部书签锚点(形如 {@code "_Toc123456"}),用于跳转到对应标题。
   *
   * @return 锚点;条目无超链接时为 {@code null}
   */
  public String anchor() {
    return anchor;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TocEntry)) {
      return false;
    }
    TocEntry that = (TocEntry) o;
    return level == that.level
        && java.util.Objects.equals(title, that.title)
        && java.util.Objects.equals(pageNumber, that.pageNumber)
        && java.util.Objects.equals(anchor, that.anchor);
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(title, level, pageNumber, anchor);
  }

  @Override
  public String toString() {
    return "TocEntry{level="
        + level
        + ", title=\""
        + title
        + "\", page="
        + pageNumber
        + (anchor == null ? "" : ", anchor=" + anchor)
        + '}';
  }
}
