package io.github.nondirectional.docx.core.internal.poi;

import io.github.nondirectional.docx.core.api.toc.TocEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.xmlbeans.XmlCursor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFldChar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTHyperlink;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtBlock;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtContentBlock;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType;

/**
 * 内部 API——恕不另行通知即可更改。
 *
 * <p>定位并解析 Word 目录(TOC)。这是 nondocx 里<b>唯一</b>接触 TOC 的 OOXML 结构的地方,因此公有类型 {@link TocEntry} / {@code
 * TableOfContents} 在源代码层面保持无 {@code org.apache.poi.*}。
 *
 * <p><b>为什么需要单独的解析器(poi-bridge.md N11)。</b> Apache POI 没有任何 {@code XWPFToc} 高级 API。TOC 在 OOXML
 * 里有两种形态,本解析器两种都支持:
 *
 * <ul>
 *   <li><b>域(field)形态</b>(较早的 Word):TOC 是正文里的一个<b>大域</b>,由 {@code fldChar} 的 begin/separate/end
 *       界定,begin 与 end 之间的段落是条目,用 {@code pStyle=TOC1..TOC9} 标层级。
 *   <li><b>SDT 形态</b>(较新的 Word):整个 TOC 被包进一个 {@code <w:sdt>} 内容控件,其 {@code <w:sdtContent>} 内
 *       每个条目是一个段落(同样 {@code pStyle=TOC1..TOC9}),且<b>每个条目段落自身又嵌套一个 {@code PAGEREF} 子域</b>
 *       来引用页码。条目段落的可见内容(标题 + 页码)藏在其 {@code CTP} 级 {@code <w:hyperlink w:anchor=...>} 内。
 * </ul>
 *
 * <p><b>两种形态的共同点</b>:条目段落都带 {@code TOC1..TOC9} 样式(或 {@code <w:outlineLvl>})。本解析器据此识别条目,
 * 而非依赖某个具体的域边界——这让同一套逻辑同时覆盖两种形态。
 *
 * <p><b>POI 的两个坑(都得绕过)。</b>
 *
 * <ol>
 *   <li>{@code XWPFDocument.getParagraphs()} <b>不返回 SDT 内的段落</b>。SDT 形态的 TOC 因此对它完全不可见。 本类用 {@link
 *       #collectParagraphs(XWPFDocument)} 穿透:从 {@code CTBody} 出发,对 {@code <w:sdt>} 下钻到 {@code
 *       <w:sdtContent>} 取其内 {@code <w:p>},把 body 直接段落与 SDT 内段落按文档顺序汇成一个统一序列。
 *   <li>条目可见内容在 {@code CTP} 级 {@code <w:hyperlink>} 内,{@code XWPFParagraph.getRuns()} 不暴露。 本类直接走
 *       {@code CTP.getHyperlinkArray()} 与其内 {@code CTR}。
 * </ol>
 *
 * <p><b>条目识别策略。</b> 在统一段落序列里,先靠「段内含 {@code instrText} 以 {@code TOC } 开头的 begin 域」定位首个 TOC 起始段落
 * (两种形态都有这一标记),再用域深度计数器配对到对应 end,界定大致区间;区间内<b>凡有 TOC 样式/大纲级别且有可见文本的段落</b>都解析为条目。 这样既兼容老式大域(条目夹在
 * begin/end 之间),也兼容 SDT 形态(每条目自带 PAGEREF 子域、begin 与条目同段)。
 *
 * <p><b>多 TOC / 嵌套域。</b> v1 只取首个 TOC 区间。条目内的 {@code PAGEREF} 子域不会被误判为 TOC(指令文本匹配只在 「当前不在 TOC
 * 区间内」时触发)。
 *
 * <p><b>防御性。</b> 单个条目解析失败时跳过该条目(返回 {@code null})而不抛异常,保证一份文档的 TOC 即便有畸形条目也能尽量给出其余条目。 整个 walk 永不在本类抛
 * POI 异常——上层 {@code TableOfContents} 负责按需包装。
 */
public final class TocFields {

  /** 已解析的 TOC: dirty 标志 + 有序条目。上层 {@code TableOfContents} 据此构建。 */
  public static final class Toc {
    private final boolean dirty;
    private final List<TocEntry> entries;

    Toc(boolean dirty, List<TocEntry> entries) {
      this.dirty = dirty;
      this.entries = entries;
    }

    /** TOC 域 begin 字符上的 {@code w:dirty} 标志:为真表示目录可能已过期(源文档改动后未刷新)。 */
    public boolean dirty() {
      return dirty;
    }

    /** 有序条目列表(文档顺序)。 */
    public List<TocEntry> entries() {
      return entries;
    }
  }

  private TocFields() {}

  /**
   * 在文档里查找首个 TOC 并解析。无 TOC 时返回 {@link Optional#empty()}。
   *
   * @param document POI 文档(不能为 {@code null})
   * @return 首个 TOC 的解析结果,或空
   */
  public static Optional<Toc> findToc(XWPFDocument document) {
    // 穿透 SDT 后的统一段落序列(body 直接段落 + 各 <w:sdt> 内段落,按文档顺序)。
    List<XWPFParagraph> paragraphs = collectParagraphs(document);

    int[] span = locateTocSpan(paragraphs);
    if (span == null) {
      return Optional.empty();
    }
    int beginIndex = span[0];
    int endIndex = span[1];

    boolean dirty = readDirty(paragraphs.get(beginIndex));
    List<TocEntry> entries = parseEntries(paragraphs, beginIndex, endIndex);
    return Optional.of(new Toc(dirty, entries));
  }

  /**
   * 收集文档正文的统一段落序列:<b>穿透 {@code <w:sdt>}</b>。
   *
   * <p>POI 的 {@code getParagraphs()} 只返回 {@code CTBody} 直接子级的 {@code <w:p>},<b>不返回</b> SDT
   * 内容控件内的段落。 而 SDT 形态的 TOC 恰恰把所有条目放在一个 {@code <w:sdt>/<w:sdtContent>} 里——于是它们对 {@code
   * getParagraphs()} 完全不可见。 本方法用 {@link XmlCursor} 按文档顺序遍历 {@code CTBody} 的直接子,遇到 {@code <w:p>}
   * 直接收,遇到 {@code <w:sdt>} 则下钻到其 {@code <w:sdtContent>} 收其中的 {@code <w:p>},汇成一个统一序列。每段用 {@code new
   * XWPFParagraph(CTP, doc)} 重包,使后续解析与「普通段落」走同一条 {@code XWPFParagraph} 代码路径。
   *
   * <p><b>仅穿透一层 SDT</b>。TOC 不会嵌套 SDT-in-SDT;若出现,内层由外层 SDT 的 {@code sdtContent.pArray} 自然收入
   * (XmlBeans 的 {@code getPArray()} 不会下钻嵌套 SDT,属已知限制,可走 {@code raw()} 处理)。
   */
  static List<XWPFParagraph> collectParagraphs(XWPFDocument document) {
    List<XWPFParagraph> out = new ArrayList<>();
    CTBody body = document.getDocument().getBody();
    XmlCursor cur = body.newCursor();
    try {
      if (!cur.toFirstChild()) {
        return out;
      }
      do {
        String local = cur.getName().getLocalPart();
        if ("p".equals(local) && cur.getObject() instanceof CTP) {
          out.add(new XWPFParagraph((CTP) cur.getObject(), document));
        } else if ("sdt".equals(local) && cur.getObject() instanceof CTSdtBlock) {
          CTSdtBlock sdt = (CTSdtBlock) cur.getObject();
          CTSdtContentBlock content = sdt.getSdtContent();
          if (content != null) {
            for (CTP ctp : content.getPArray()) {
              out.add(new XWPFParagraph(ctp, document));
            }
          }
        }
      } while (cur.toNextSibling());
    } finally {
      cur.dispose();
    }
    return out;
  }

  /**
   * 扫描段落序列,定位首个 TOC 的段落区间。
   *
   * <p>当某段含 {@code fldChar begin} 且其 {@code instrText} 以 {@code TOC } 开头时,记为 {@code beginIndex};
   * 随后用「域深度」计数器(已为 1)向前,每遇 begin +1、每遇 end -1,深度回到 0 的那段为 {@code endIndex}(含)。 这同时覆盖两种形态:老式大域的
   * begin/end 跨多段;SDT 形态的 begin 与首个条目同段、end 在 SDT 末段。
   *
   * @return {@code [beginIndex, endIndex]}(均含),无 TOC 时 {@code null}
   */
  private static int[] locateTocSpan(List<XWPFParagraph> paragraphs) {
    for (int i = 0; i < paragraphs.size(); i++) {
      if (!isTocBegin(paragraphs.get(i))) {
        continue;
      }
      int depth = 1;
      for (int j = i + 1; j < paragraphs.size(); j++) {
        depth += fieldDepthDelta(paragraphs.get(j));
        if (depth <= 0) {
          return new int[] {i, j};
        }
      }
      // begin 没有匹配的 end(畸形文档):以 begin 单点为区间,条目解析对含 TOC 样式的段落仍生效。
      return new int[] {i, i};
    }
    return null;
  }

  /**
   * 判断某段是否承载「TOC 域」的 begin:段内有 {@code fldChar begin},且其 instrText 以 {@code TOC } 开头。begin 与
   * instrText 可能在同段的不同 run 里(典型)。条目内的 {@code PAGEREF} 子域不会被误判——它们的 instrText 以 {@code PAGEREF} 开头。
   */
  private static boolean isTocBegin(XWPFParagraph paragraph) {
    boolean hasBegin = false;
    StringBuilder instr = new StringBuilder();
    for (CTR ctr : paragraph.getCTP().getRList()) {
      for (int k = 0; k < ctr.sizeOfFldCharArray(); k++) {
        CTFldChar fc = ctr.getFldCharArray(k);
        if (fc.getFldCharType() == STFldCharType.BEGIN) {
          hasBegin = true;
        }
      }
      for (int k = 0; k < ctr.sizeOfInstrTextArray(); k++) {
        instr.append(ctr.getInstrTextArray(k).getStringValue());
      }
    }
    return hasBegin && instr.toString().trim().toUpperCase().startsWith("TOC ");
  }

  /** 一段对「域深度」的净贡献:段内 begin 个数 − end 个数(含 CTP 直属 run 与 hyperlink 内 run)。 */
  private static int fieldDepthDelta(XWPFParagraph paragraph) {
    int delta = 0;
    List<CTR> runs = allRuns(paragraph);
    for (CTR ctr : runs) {
      for (int k = 0; k < ctr.sizeOfFldCharArray(); k++) {
        CTFldChar fc = ctr.getFldCharArray(k);
        STFldCharType.Enum t = fc.getFldCharType();
        if (t == STFldCharType.BEGIN) {
          delta++;
        } else if (t == STFldCharType.END) {
          delta--;
        }
      }
    }
    return delta;
  }

  /**
   * 读 begin 段的 {@code w:dirty} 标志(首个 TOC begin fldChar 上置位即为 dirty)。
   *
   * <p>{@code w:dirty} 的 OOXML 类型是 {@code STOnOff};不同 POI schema(精简版 vs full)对其 getter 返回类型不一致
   * (Object vs boolean)。这里走 {@code xgetDirty()} 拿原始 {@code XmlObject} 再取字符串值,跨 schema 版本稳定: 值为
   * {@code "true"}/{@code "1"}/{@code "on"} 视为置位。
   */
  private static boolean readDirty(XWPFParagraph beginParagraph) {
    List<CTR> runs = allRuns(beginParagraph);
    for (CTR ctr : runs) {
      for (int k = 0; k < ctr.sizeOfFldCharArray(); k++) {
        CTFldChar fc = ctr.getFldCharArray(k);
        if (fc.getFldCharType() == STFldCharType.BEGIN && fc.isSetDirty()) {
          String v = fc.xgetDirty().getStringValue();
          return "true".equalsIgnoreCase(v) || "1".equals(v) || "on".equalsIgnoreCase(v);
        }
      }
    }
    return false;
  }

  /**
   * 解析 TOC 区间 [beginIndex, endIndex] 内的条目。
   *
   * <p><b>关键</b>:条目识别靠「段落有 TOC 样式/大纲级别」({@link #levelOf}),不靠 separate/end 边界。这样兼容两种形态: 老式大域里条目夹在
   * begin 与 end 之间;SDT 形态里首个条目与 begin 同段(它本身也是 TOC 样式段)。只要段落有层级且有可见文本, 就解析为条目——{@link #parseEntry}
   * 会自行剥离其中的域字符(PAGEREF 子域的可见结果 = 页码文本)。
   *
   * <p>{@code endIndex} 段若本身有 TOC 样式(罕见)也会被纳入;通常 end 段无 TOC 样式,{@link #parseEntry} 返回 null 自然跳过。
   */
  private static List<TocEntry> parseEntries(
      List<XWPFParagraph> paragraphs, int beginIndex, int endIndex) {
    List<TocEntry> entries = new ArrayList<>();
    for (int i = beginIndex; i <= endIndex; i++) {
      TocEntry entry = parseEntry(paragraphs.get(i));
      if (entry != null) {
        entries.add(entry);
      }
    }
    return entries;
  }

  /**
   * 把一个段落解析为 {@link TocEntry}。
   *
   * <p><b>层级</b>:取 {@code pStyle} 为 {@code TOC1..TOC9} 的尾数字;否则取 {@code <w:outlineLvl>}+1;都无则跳过 (返回
   * {@code null},视为非条目段落,例如「目录」标题行或 end 段)。
   *
   * <p><b>文本与页码</b>:条目可见内容优先在 CTP 级 {@code <w:hyperlink>} 内(两种形态都如此),退而取段落直属 run。 按文档顺序收集 {@code
   * <w:t>} 文本与制表符信息——最后一个<b>非空</b>的 {@code <w:t>} 视为页码 (SDT 形态里就是嵌套 PAGEREF
   * 子域的可见结果),其前的拼接(去掉尾随空白)为标题。anchor 取包裹超链接的 {@code w:anchor}。
   *
   * @return 条目值;若段落不像条目(无层级、无可见文本)则 {@code null}
   */
  private static TocEntry parseEntry(XWPFParagraph paragraph) {
    Integer level = levelOf(paragraph);
    if (level == null) {
      return null;
    }
    CTP ctp = paragraph.getCTP();

    List<TextPiece> pieces = new ArrayList<>();
    String anchor = null;
    for (int h = 0; h < ctp.sizeOfHyperlinkArray(); h++) {
      CTHyperlink hl = ctp.getHyperlinkArray(h);
      if (anchor == null && hl.isSetAnchor()) {
        anchor = hl.getAnchor();
      }
      collectPieces(hl.getRList(), pieces);
    }
    if (pieces.isEmpty()) {
      collectPieces(ctp.getRList(), pieces);
    }
    if (pieces.isEmpty()) {
      return null;
    }

    // 页码 = 最后一个非空 T;标题 = 其前所有片段拼接(去尾随空白)。
    int pageIdx = -1;
    for (int i = pieces.size() - 1; i >= 0; i--) {
      if (!pieces.get(i).text.isEmpty()) {
        pageIdx = i;
        break;
      }
    }
    String pageNumber = "";
    StringBuilder titleBuilder = new StringBuilder();
    int titleLimit = pageIdx;
    for (int i = 0; i < pieces.size(); i++) {
      if (i == pageIdx) {
        pageNumber = pieces.get(i).text;
      } else if (titleLimit < 0 || i < titleLimit) {
        titleBuilder.append(pieces.get(i).text);
      }
    }
    String title = titleBuilder.toString().replaceAll("\\s+$", "");
    if (pageNumber.isEmpty() && title.isEmpty()) {
      return null;
    }
    return new TocEntry(title, level, pageNumber, anchor);
  }

  /** 从一组 run 里收集 {@code <w:t>} 文本片段(制表符记为空串占位,保留数量语义)。 */
  private static void collectPieces(List<CTR> runs, List<TextPiece> pieces) {
    for (CTR ctr : runs) {
      boolean hasTab = ctr.sizeOfTabArray() > 0;
      for (int k = 0; k < ctr.sizeOfTArray(); k++) {
        CTText t = ctr.getTArray(k);
        String s = t.getStringValue() == null ? "" : t.getStringValue();
        pieces.add(new TextPiece(s));
      }
      if (hasTab) {
        pieces.add(new TextPiece(""));
      }
    }
  }

  /** 取段落层级: {@code TOC1..TOC9} 样式 → 尾数字;否则 {@code <w:outlineLvl>}+1;都无返回 {@code null}。 */
  private static Integer levelOf(XWPFParagraph paragraph) {
    String style = paragraph.getStyle();
    if (style != null) {
      String upper = style.toUpperCase();
      if (upper.startsWith("TOC") && upper.length() == 4) {
        char d = upper.charAt(3);
        if (d >= '1' && d <= '9') {
          return d - '0';
        }
      }
    }
    if (paragraph.getCTP().isSetPPr() && paragraph.getCTP().getPPr().isSetOutlineLvl()) {
      int val = paragraph.getCTP().getPPr().getOutlineLvl().getVal().intValue();
      if (val >= 0 && val <= 8) {
        return val + 1;
      }
    }
    return null;
  }

  /**
   * 段落内的所有 run: CTP 直属 run + 各 {@code <w:hyperlink>} 内的 run。条目内容与域字符都可能藏在 hyperlink 里, 所以读 fldChar
   * / dirty 等结构信息时也要遍历 hyperlink 内的 run,否则 SDT 形态会漏读。
   */
  private static List<CTR> allRuns(XWPFParagraph paragraph) {
    CTP ctp = paragraph.getCTP();
    List<CTR> runs = new ArrayList<>(ctp.getRList());
    for (int h = 0; h < ctp.sizeOfHyperlinkArray(); h++) {
      runs.addAll(ctp.getHyperlinkArray(h).getRList());
    }
    return runs;
  }

  /** 一个 {@code <w:t>} 文本片段的轻量载体(制表符用空串占位)。 */
  private static final class TextPiece {
    final String text;

    TextPiece(String text) {
      this.text = text;
    }
  }
}
