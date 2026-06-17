package com.non.docx.core.internal.poi;

import com.non.docx.core.api.exception.DocxOperationException;
import com.non.docx.core.api.style.ListKind;
import com.non.docx.core.internal.util.Objects;
import java.math.BigInteger;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STJc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;

/**
 * 内部 API——恕不另行通知即可更改。
 *
 * <p>nondocx 无 POI 的 {@link ListKind} 与 Apache POI 的 OOXML 编号机制之间的桥接。 这是唯一构建 {@code
 * CTAbstractNum}/{@code CTNum} 定义并从 {@code XWPFParagraph} 读取列表成员资格的地方，因此公有值对象 {@code ListKind} 和
 * {@code Paragraph} 包装器 在源代码层面保持无 {@code org.apache.poi.*}。
 *
 * <p>列表成员资格建模为（列表类型、嵌套级别 0..8）对。每种类型映射到单个 {@code abstractNum}（包含九个 {@code CTLvl} 条目）和每个文档的单个 {@code
 * num}； 相同类型的所有段落共享该 num，仅通过其 {@code ilvl} 区分。每个（文档、类型）的 {@code numId} 被缓存，因此重复的 {@code list(...)}
 * 调用在同一文档上永远不会创建重复定义。
 *
 * <p><b>缓存作用域和生命周期：</b>缓存以 {@code XWPFDocument} 实例本身为键（同一性语义）。 它有意在 JVM 生命周期内持有对文档的强引用；这对 MVP
 * 库是可接受的，但不应期望文档 在缓存保留它们时被垃圾回收。经过 {@code save} → {@code open} 往返后，新打开的文档 是一个不同的 {@code
 * XWPFDocument} 实例，因此其列表定义在下次应用 {@code list(...)} 时按需重新创建；这是无害的，因为段落通过文件中存储的 numId 继续正确解析。
 */
public final class Numbering {

  /** 单个 abstractNum 支持的最大嵌套级别（ilvl 0..8 → 九个级别）。 */
  private static final int MAX_LEVEL = 8;

  /** 用于 {@link ListKind#BULLET} abstractNums 每个嵌套级别的项目符号字形。 四个级别后重复以覆盖所有九个 ilvl 条目。 */
  private static final String[] BULLET_GLYPHS = {
    "•", // • 级别 0
    "○", // ○ 级别 1
    "▪", // ▪ 级别 2
    "·", // · 级别 3
    "•", // • 级别 4
    "○", // ○ 级别 5
    "▪", // ▪ 级别 6
    "·", // · 级别 7
    "•" // • 级别 8
  };

  /**
   * 每个文档缓存的为每个 {@link ListKind} 分配的 {@code numId}。 以 {@code XWPFDocument} 同一性为键，因此同一文档不会在重复的 {@code
   * list(...)} 调用上获得重复的编号定义。
   */
  private static final Map<XWPFDocument, Map<ListKind, BigInteger>> NUM_ID_CACHE =
      new IdentityHashMap<>();

  private Numbering() {}

  /**
   * 将给定段落标记为指定类型和嵌套级别的列表成员。确保所属文档有一个编号部件， 并为该类型提供一个共享的 abstractNum/num（已缓存），然后分配段落的 {@code numId} 和
   * {@code ilvl}。
   *
   * @param paragraph 要修改的 POI 段落（不能为 {@code null}）
   * @param kind 列表类型（不能为 {@code null}）
   * @param level 从 0 开始的嵌套级别，范围 {@code 0..8}
   * @throws IllegalArgumentException 如果 {@code kind} 为 {@code null} 或 {@code level} 超出范围
   * @throws DocxOperationException 如果段落未附加到文档
   */
  public static void apply(XWPFParagraph paragraph, ListKind kind, int level) {
    Objects.requireNonNull(kind, "kind");
    if (level < 0 || level > MAX_LEVEL) {
      throw new IllegalArgumentException(
          "list level must be between 0 and " + MAX_LEVEL + " inclusive, was " + level);
    }
    XWPFDocument document = paragraph.getDocument();
    if (document == null) {
      throw new DocxOperationException(
          "Cannot apply list: paragraph is not attached to a document", "list");
    }
    XWPFNumbering numbering = document.getNumbering();
    if (numbering == null) {
      numbering = document.createNumbering();
    }
    BigInteger numId = numIdFor(document, numbering, kind);
    paragraph.setNumID(numId);
    paragraph.setNumILvl(BigInteger.valueOf(level));
  }

  /**
   * 通过完全取消设置 {@code <w:numPr/>} 元素来移除给定段落的列表成员资格。 这比 {@link XWPFParagraph#setNumID(BigInteger)
   * setNumID(null)} 更强， 后者会留下一个空的 {@code numId}，XmlBeans 在下一次保存/打开往返时将其拒绝为无效整数。 调用后 {@link
   * XWPFParagraph#getNumID()} 报告 {@code null}， 因此段落读取为非列表段落。
   *
   * @param paragraph 要清除的 POI 段落（不能为 {@code null}）
   */
  public static void clear(XWPFParagraph paragraph) {
    CTPPr pPr = paragraph.getCTP().getPPr();
    if (pPr != null && pPr.isSetNumPr()) {
      pPr.unsetNumPr();
    }
  }

  /**
   * 读取给定段落的列表类型，如果该段落不是列表成员则返回 {@code null}。
   *
   * <p>类型从 POI 报告的段落当前级别的编号格式推断：{@code "bullet"} 格式表示 {@link
   * ListKind#BULLET}；任何其他格式（十进制、字母、罗马数字……）归并为 {@link ListKind#NUMBERED}。没有 {@code numId} 的段落被报告为
   * {@code null}。
   *
   * @param paragraph 要检查的 POI 段落（不能为 {@code null}）
   * @return 列表类型，如果段落不是列表成员则返回 {@code null}
   */
  public static ListKind kindOf(XWPFParagraph paragraph) {
    if (paragraph.getNumID() == null) {
      return null;
    }
    String format = paragraph.getNumFmt();
    if ("bullet".equals(format)) {
      return ListKind.BULLET;
    }
    return ListKind.NUMBERED;
  }

  /**
   * 读取给定段落的从 0 开始的嵌套级别，如果该段落不是列表成员则返回 {@code null}。 没有显式 {@code ilvl} 的列表段落被报告为级别 {@code 0}。
   *
   * @param paragraph 要检查的 POI 段落（不能为 {@code null}）
   * @return 嵌套级别，如果段落不是列表成员则返回 {@code null}
   */
  public static Integer levelOf(XWPFParagraph paragraph) {
    if (paragraph.getNumID() == null) {
      return null;
    }
    BigInteger ilvl = paragraph.getNumIlvl();
    return ilvl == null ? 0 : ilvl.intValue();
  }

  // ---------- 内部方法 ----------

  /** 返回给定（文档、类型）的 {@code numId}，在首次访问时创建新的 abstractNum+num， 之后从缓存中提供。 */
  private static BigInteger numIdFor(
      XWPFDocument document, XWPFNumbering numbering, ListKind kind) {
    Map<ListKind, BigInteger> perDocument = NUM_ID_CACHE.get(document);
    if (perDocument != null) {
      BigInteger cached = perDocument.get(kind);
      if (cached != null) {
        return cached;
      }
    }
    BigInteger abstractNumId = createAbstractNum(numbering, kind);
    BigInteger numId = numbering.addNum(abstractNumId);
    if (perDocument == null) {
      perDocument = new EnumMap<>(ListKind.class);
      NUM_ID_CACHE.put(document, perDocument);
    }
    perDocument.put(kind, numId);
    return numId;
  }

  /**
   * 构建并注册一个包含给定类型九个级别的 abstractNum，返回其 {@code abstractNumId}。 ID 被预先计算为在现有 abstractNum 中唯一，因此它能在
   * POI 的复制添加路径中无冲突地存活。
   */
  private static BigInteger createAbstractNum(XWPFNumbering numbering, ListKind kind) {
    CTAbstractNum ct = CTAbstractNum.Factory.newInstance();
    ct.setAbstractNumId(nextAbstractNumId(numbering));
    populateLevels(ct, kind);
    return numbering.addAbstractNum(new XWPFAbstractNum(ct));
  }

  /** 为给定类型的 abstractNum 追加九个 {@code CTLvl} 条目（ilvl 0..8）。 */
  private static void populateLevels(CTAbstractNum ct, ListKind kind) {
    for (int level = 0; level <= MAX_LEVEL; level++) {
      CTLvl lvl = ct.addNewLvl();
      lvl.setIlvl(BigInteger.valueOf(level));
      lvl.addNewStart().setVal(BigInteger.ONE);
      if (kind == ListKind.BULLET) {
        lvl.addNewNumFmt().setVal(STNumberFormat.BULLET);
        lvl.addNewLvlText().setVal(BULLET_GLYPHS[level]);
      } else {
        lvl.addNewNumFmt().setVal(STNumberFormat.DECIMAL);
        lvl.addNewLvlText().setVal("%" + (level + 1) + ".");
      }
      lvl.addNewLvlJc().setVal(STJc.LEFT);
    }
  }

  /**
   * 计算编号部件中下一个空闲的 abstractNum ID，为 {@code max(现有 ID) + 1} （镜像 POI 自身的 {@code
   * findNextAbstractNumberingId}）， 当编号没有 abstractNum 时从 {@code 1} 开始。
   */
  private static BigInteger nextAbstractNumId(XWPFNumbering numbering) {
    long max = 0;
    for (XWPFAbstractNum existing : numbering.getAbstractNums()) {
      BigInteger id = existing.getAbstractNum().getAbstractNumId();
      if (id != null) {
        max = Math.max(max, id.longValue());
      }
    }
    return BigInteger.valueOf(max + 1);
  }
}
