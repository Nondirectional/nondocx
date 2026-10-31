package io.github.nondirectional.docx.toolkit.compiler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 样式 profile：一份「结构无关」的排版策略，供 {@link DocumentCompiler} 编译时套用。
 *
 * <p><b>为什么是 profile 而不是让模型逐 run 设字体字号。</b> 中文文档的字体有 ascii/eastAsia 两个槽、正文小四对应
 * 12pt、首行缩进「两字符」要按字号换算成缇—— 这些 Word/CJK 坑是<b>确定性的</b>，应该由编译器统一兜底，而不是让 LLM 在十几个 工具调用里逐个设置（见 {@code
 * docs/11-agent-authoring-architecture.md} §3.2）。 模型只表达「这是一份中文报告」，排版细节由 profile 决定。
 *
 * <p><b>内置 profile（v1）：</b>
 *
 * <ul>
 *   <li>{@code report-cn}——中文报告：黑体标题（H1 16pt 居中 / H2 14pt / H3+ 12pt）、 宋体+Times New Roman
 *       正文小四（12pt）、1.5 倍行距、首行缩进 2 字符、 表头加粗灰底（F2F2F2）、等宽百分比列宽（WPS 安全）；
 *   <li>{@code plain}——只做结构不设任何排版属性，等价于「裸 Markdown → docx」。
 * </ul>
 *
 * <p>{@code null} 字段表示「不设置」（沿用文档/样式默认值）。profile 不可变； 自定义 profile 留给后续任务（先验证内置两个的假设）。
 */
public final class StyleProfile {

  private static final Map<String, StyleProfile> BUILTINS = new LinkedHashMap<>();

  static {
    register(
        new StyleProfile(
            "report-cn",
            "中文报告：黑体标题、宋体/Times New Roman 正文小四、1.5 倍行距、首行缩进 2 字符、表头加粗灰底",
            /* bodyEastAsia= */ "宋体",
            /* bodyLatin= */ "Times New Roman",
            /* bodySizePt= */ 12,
            /* bodyLineSpacing= */ 1.5,
            /* bodyFirstLineIndentChars= */ 2,
            /* headingEastAsia= */ "黑体",
            /* headingLatin= */ "Arial",
            /* h1SizePt= */ 16,
            /* h2SizePt= */ 14,
            /* h3SizePt= */ 12,
            /* h1Center= */ true,
            /* tableHeaderBold= */ true,
            /* tableHeaderShading= */ "F2F2F2",
            /* tableEqualColumns= */ true,
            /* codeFont= */ "Consolas"));
    register(
        new StyleProfile(
            "plain",
            "纯结构：不设置任何字体/字号/行距/缩进/表头样式，只把 Markdown 结构编译成 docx",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            false,
            null,
            false,
            null));
  }

  private final String name;
  private final String description;
  private final String bodyEastAsia;
  private final String bodyLatin;
  private final Integer bodySizePt;
  private final Double bodyLineSpacing;
  private final Integer bodyFirstLineIndentChars;
  private final String headingEastAsia;
  private final String headingLatin;
  private final Integer h1SizePt;
  private final Integer h2SizePt;
  private final Integer h3SizePt;
  private final boolean h1Center;
  private final boolean tableHeaderBold;
  private final String tableHeaderShading;
  private final boolean tableEqualColumns;
  private final String codeFont;

  private StyleProfile(
      String name,
      String description,
      String bodyEastAsia,
      String bodyLatin,
      Integer bodySizePt,
      Double bodyLineSpacing,
      Integer bodyFirstLineIndentChars,
      String headingEastAsia,
      String headingLatin,
      Integer h1SizePt,
      Integer h2SizePt,
      Integer h3SizePt,
      boolean h1Center,
      boolean tableHeaderBold,
      String tableHeaderShading,
      boolean tableEqualColumns,
      String codeFont) {
    this.name = name;
    this.description = description;
    this.bodyEastAsia = bodyEastAsia;
    this.bodyLatin = bodyLatin;
    this.bodySizePt = bodySizePt;
    this.bodyLineSpacing = bodyLineSpacing;
    this.bodyFirstLineIndentChars = bodyFirstLineIndentChars;
    this.headingEastAsia = headingEastAsia;
    this.headingLatin = headingLatin;
    this.h1SizePt = h1SizePt;
    this.h2SizePt = h2SizePt;
    this.h3SizePt = h3SizePt;
    this.h1Center = h1Center;
    this.tableHeaderBold = tableHeaderBold;
    this.tableHeaderShading = tableHeaderShading;
    this.tableEqualColumns = tableEqualColumns;
    this.codeFont = codeFont;
  }

  private static void register(StyleProfile profile) {
    BUILTINS.put(profile.name(), profile);
  }

  /** 按名取内置 profile；不存在返回 {@code null}（调用方负责报 INVALID_ARGUMENT）。 */
  public static StyleProfile builtin(String name) {
    return BUILTINS.get(name);
  }

  /** 内置 profile 名单（保持注册顺序，供工具描述/枚举使用）。 */
  public static Map<String, StyleProfile> builtins() {
    return new LinkedHashMap<>(BUILTINS);
  }

  /** profile 名（如 {@code report-cn}）。 */
  public String name() {
    return name;
  }

  /** 一句话描述，供 Agent 选择 profile。 */
  public String description() {
    return description;
  }

  /** 正文 eastAsia 字体（中文字形槽）；{@code null} 表示不设置。 */
  public String bodyEastAsia() {
    return bodyEastAsia;
  }

  /** 正文 ascii 字体（西文字形槽）；{@code null} 表示不设置。 */
  public String bodyLatin() {
    return bodyLatin;
  }

  /** 正文字号（pt）；{@code null} 表示不设置。 */
  public Integer bodySizePt() {
    return bodySizePt;
  }

  /** 正文行距倍数；{@code null} 表示不设置。 */
  public Double bodyLineSpacing() {
    return bodyLineSpacing;
  }

  /** 正文首行缩进字符数（按字号换算缇）；{@code null} 表示不设置。 */
  public Integer bodyFirstLineIndentChars() {
    return bodyFirstLineIndentChars;
  }

  /** 标题 eastAsia 字体；{@code null} 表示不设置。 */
  public String headingEastAsia() {
    return headingEastAsia;
  }

  /** 标题 ascii 字体；{@code null} 表示不设置。 */
  public String headingLatin() {
    return headingLatin;
  }

  /** H1 字号（pt）。 */
  public Integer h1SizePt() {
    return h1SizePt;
  }

  /** H2 字号（pt）。 */
  public Integer h2SizePt() {
    return h2SizePt;
  }

  /** H3 及以下字号（pt）。 */
  public Integer h3SizePt() {
    return h3SizePt;
  }

  /** H1 是否默认居中（显式 {@code {align=…}} 属性优先）。 */
  public boolean h1Center() {
    return h1Center;
  }

  /** 表头行是否加粗。 */
  public boolean tableHeaderBold() {
    return tableHeaderBold;
  }

  /** 表头行底纹（6 位 HEX）；{@code null} 表示不设置。 */
  public String tableHeaderShading() {
    return tableHeaderShading;
  }

  /** 表格是否用等宽百分比列宽（WPS 安全路径）。 */
  public boolean tableEqualColumns() {
    return tableEqualColumns;
  }

  /** 行内代码字体；{@code null} 表示不设置。 */
  public String codeFont() {
    return codeFont;
  }

  /** 首行缩进换算成缇：字符数 × 字号(pt) × 20。无字号或无缩进配置返回 0。 */
  int firstLineIndentTwips() {
    if (bodyFirstLineIndentChars == null || bodySizePt == null) {
      return 0;
    }
    return bodyFirstLineIndentChars * bodySizePt * 20;
  }

  /** 按标题级别取字号：H1/H2/H3+ 分别取 h1/h2/h3，未配置返回 {@code null}。 */
  Integer headingSizePt(int level) {
    if (level <= 1) {
      return h1SizePt;
    }
    if (level == 2) {
      return h2SizePt;
    }
    return h3SizePt;
  }
}
