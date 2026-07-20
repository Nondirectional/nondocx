package io.github.nondirectional.docx.core.api.section;

import io.github.nondirectional.docx.core.api.exception.DocxIOException;
import io.github.nondirectional.docx.core.api.header.Footer;
import io.github.nondirectional.docx.core.api.header.Header;
import io.github.nondirectional.docx.core.api.header.HeaderFooterVariant;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import io.github.nondirectional.docx.core.internal.poi.Mappers;
import io.github.nondirectional.docx.core.internal.util.Objects;
import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSettings;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STPageOrientation;

/**
 * 文档章节 — 为文档正文的一段范围承载页面属性（纸张大小、方向、页边距）。
 *
 * <p>持有 OOXML {@code CTSectPr} 委托，并在其上暴露活跃视图。每次读取 直接穿透到委托（没有缓存快照）；每次修改写入并 返回 {@code this}
 * 以支持链式调用。纸张大小、方向和页边距修改器保持 底层的 {@code <w:pgSz>} / {@code <w:pgMar>} 元素自洽 — 例如切换到 {@link
 * Orientation#LANDSCAPE} 会交换存储的宽度/高度，使较大的维度成为 宽度，与 Word 自身的存储约定一致。
 *
 * <p><b>纸张大小和方向的交互方式如下。</b> {@link #paperSize(PaperSize)} 存储 大小的纵向尺寸。{@link
 * #orientation(Orientation)} 在目标方向与当前外观 不同时交换这些尺寸，因此以任一顺序调用两者都会使章节处于一致状态。 {@link #paperSize()} 使用
 * {@link PaperSize#fromDimensions(int, int)} 从存储的维度 解析回逻辑纸张大小，该方法是方向无关的。
 *
 * <p><b>默认值。</b> 没有存储尺寸的 {@code <w:pgSz>} 在方向交换时被视为 A4 纵向；未设置的 {@code <w:pgMar>} 属性读回 {@code
 * 0}；未设置的 {@code orient} 属性读回 {@link Orientation#PORTRAIT}（Word
 * 的默认值）。当首次创建默认页眉/页脚、且当前章节仍缺少页面设置时，nondocx 会补齐一个兼容性最小值：A4 + 四边 1 英寸边距。
 *
 * <p><b>页眉/页脚。</b> {@link #header()} 和 {@link #footer()} 暴露章节级别的默认（奇数页）页眉和页脚。每个都通过绑定到此章节 {@code
 * CTSectPr} 的章节级别 {@code XWPFHeaderFooterPolicy}
 * 解析：如果已经附加了默认页眉/页脚则返回它，否则在首次访问时创建一个空的并附加。创建时若当前章节还没有显式页面设置， nondocx 会先补齐兼容性最小页面设置（A4 + 四边 1
 * 英寸边距），以降低 WPS 等消费者对“裸 {@code <w:sectPr>}”的显示敏感性。首页和偶数页变体不在 MVP 范围内，仍可通过 {@code raw()} 访问。所属的
 * {@code XWPFDocument} 仅用于构建该策略；它是一个内部辅助对象，从不公开暴露，也从不参与内容相等性。
 */
public final class Section {

  private final XWPFDocument document;
  private final CTSectPr delegate;

  /**
   * 为首次创建页眉/页脚时补齐的默认页边距：1 英寸 = 1440 缇。
   *
   * <p>仅当该节尚未显式写入 {@code <w:pgMar>} 时使用，用于生成对 WPS 等消费者更稳定的最小页面设置。
   */
  private static final int DEFAULT_COMPAT_MARGIN_TWIPS = 1440;

  /**
   * 封装给定的 OOXML 章节属性。
   *
   * <p>此构造函数是 {@code Document} 生成活跃章节包装器的内部接缝， 因此它有意接受 POI / XmlBeans 类型（与其他包装器接受其 底层 {@code XWPF*}
   * 类型的方式相同）。用户通过 {@code Document.sections()} / {@code Document.section(int)} 获取章节，而不是直接构造它们。
   *
   * <p>{@code document} 参数是所属的 POI 文档；它仅被持有以使此章节 可以为 {@link #header()} / {@link #footer()} 构建章节级别的
   * {@code XWPFHeaderFooterPolicy}。 它从不公开暴露，也从不参与内容相等性。
   *
   * @param document 所属的 POI 文档（不能为 {@code null}）
   * @param delegate 底层的 {@code CTSectPr}（不能为 {@code null}）
   * @throws IllegalArgumentException 如果任一参数为 {@code null}
   */
  public Section(XWPFDocument document, CTSectPr delegate) {
    this.document = Objects.requireNonNull(document, "document");
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  // ---------- 纸张大小 ----------

  /**
   * 设置纸张大小并返回 {@code this}。将大小的纵向尺寸存储到 底层的 {@code <w:pgSz>}；{@code orient} 标志保持不变。
   *
   * @param size 纸张大小（不能为 {@code null}）
   * @return 此章节
   * @throws IllegalArgumentException 如果 {@code size} 为 {@code null}
   */
  public Section paperSize(PaperSize size) {
    Objects.requireNonNull(size, "size");
    CTPageSz pgSz = ensurePgSz();
    pgSz.setW(BigInteger.valueOf(size.widthTwips()));
    pgSz.setH(BigInteger.valueOf(size.heightTwips()));
    return this;
  }

  /**
   * 从存储的 {@code <w:pgSz>} 维度解析逻辑纸张大小，如果尺寸不匹配已知的 {@link PaperSize}，则返回 {@code null}。
   *
   * <p>匹配是方向无关的（参见 {@link PaperSize#fromDimensions(int, int)}），因此无论章节是纵向还是横向， 都会解析出相同的纸张大小。根本没有
   * {@code <w:pgSz>} 的章节 解析为 {@code null}。
   *
   * @return 纸张大小，如果未设置或无法识别则返回 {@code null}
   */
  public PaperSize paperSize() {
    if (!delegate.isSetPgSz()) {
      return null;
    }
    CTPageSz pgSz = delegate.getPgSz();
    return PaperSize.fromDimensions((int) twipsOf(pgSz.getW()), (int) twipsOf(pgSz.getH()));
  }

  // ---------- 方向 ----------

  /**
   * 设置页面方向并返回 {@code this}。确保 {@code <w:pgSz>} 存在，在目标方向 需要时交换存储的宽度/高度（横向将较大的维度作为
   * 宽度；纵向将较小的维度作为宽度），并写入 {@code orient} 标志。
   *
   * <p>如果 {@code <w:pgSz>} 没有存储的尺寸，则在交换前假定 A4 纵向尺寸作为 基准，因此方向始终是明确定义的。
   *
   * @param orientation 方向（不能为 {@code null}）
   * @return 此章节
   * @throws IllegalArgumentException 如果 {@code orientation} 为 {@code null}
   */
  public Section orientation(Orientation orientation) {
    Objects.requireNonNull(orientation, "orientation");
    CTPageSz pgSz = ensurePgSz();
    long w = dimOrDefault(pgSz.getW(), PaperSize.A4.widthTwips());
    long h = dimOrDefault(pgSz.getH(), PaperSize.A4.heightTwips());
    if (orientation == Orientation.LANDSCAPE && w <= h) {
      long swap = w;
      w = h;
      h = swap;
    } else if (orientation == Orientation.PORTRAIT && w > h) {
      long swap = w;
      w = h;
      h = swap;
    }
    pgSz.setW(BigInteger.valueOf(w));
    pgSz.setH(BigInteger.valueOf(h));
    pgSz.setOrient(Mappers.toPoi(orientation));
    return this;
  }

  /**
   * 返回页面方向。没有 {@code <w:pgSz>} 的章节，或其 {@code orient} 标志 未设置，报告为 {@link Orientation#PORTRAIT}（Word
   * 的默认值）。
   *
   * @return 方向（从不返回 {@code null}）
   */
  public Orientation orientation() {
    if (!delegate.isSetPgSz()) {
      return Orientation.PORTRAIT;
    }
    STPageOrientation.Enum orient = delegate.getPgSz().getOrient();
    Orientation mapped = Mappers.fromPoi(orient);
    return mapped == null ? Orientation.PORTRAIT : mapped;
  }

  // ---------- 边距 ----------

  /**
   * 设置四个页面边距（以缇为单位）并返回 {@code this}。
   *
   * @param topTwips 上边距（缇）
   * @param rightTwips 右边距（缇）
   * @param bottomTwips 下边距（缇）
   * @param leftTwips 左边距（缇）
   * @return 此章节
   */
  public Section margins(int topTwips, int rightTwips, int bottomTwips, int leftTwips) {
    CTPageMar pgMar = delegate.isSetPgMar() ? delegate.getPgMar() : delegate.addNewPgMar();
    pgMar.setTop(BigInteger.valueOf(topTwips));
    pgMar.setRight(BigInteger.valueOf(rightTwips));
    pgMar.setBottom(BigInteger.valueOf(bottomTwips));
    pgMar.setLeft(BigInteger.valueOf(leftTwips));
    return this;
  }

  /**
   * 返回上边距（以缇为单位），如果未显式设置则返回 {@code 0}。
   *
   * @return 上边距（缇），如果未设置则返回 {@code 0}
   */
  public int marginTop() {
    return marginOf(CTPageMar::getTop);
  }

  /**
   * 返回右边距（以缇为单位），如果未显式设置则返回 {@code 0}。
   *
   * @return 右边距（缇），如果未设置则返回 {@code 0}
   */
  public int marginRight() {
    return marginOf(CTPageMar::getRight);
  }

  /**
   * 返回下边距（以缇为单位），如果未显式设置则返回 {@code 0}。
   *
   * @return 下边距（缇），如果未设置则返回 {@code 0}
   */
  public int marginBottom() {
    return marginOf(CTPageMar::getBottom);
  }

  /**
   * 返回左边距（以缇为单位），如果未显式设置则返回 {@code 0}。
   *
   * @return 左边距（缇），如果未设置则返回 {@code 0}
   */
  public int marginLeft() {
    return marginOf(CTPageMar::getLeft);
  }

  /**
   * 清理此章节中<b>没有属性</b>的 {@code <w:pgNumType/>} 元素,并返回是否清理了。
   *
   * <p><b>OOXML</b>:{@code <w:pgNumType>} 的语义是「覆盖本节的起始页码 / 编号格式」。一个既无 {@code w:start} 也无 {@code
   * w:fmt} 的<b>裸元素</b>等于「我要覆盖,但没说覆盖成什么」——Microsoft Word 容忍这种空声明, 但 <b>WPS</b>
   * 严格解读为「覆盖为空值」,导致页码引擎混乱(从 0 开始、格式错乱)。 POI 5.2.5 的 {@code CTSectPr.addNewPgNumType()}
   * 不设属性时确实会写出这种裸元素 (已实测验证)。
   *
   * <p><b>nondocx 不会在 {@code Document.save} 自动调用本方法</b>——守住 save 的纯序列化语义, 与 nondocx「活对象 +
   * 显式操作」哲学一致。用户/Agent 在需要时显式调用, 或由 toolkit 的 {@code QualityCheckTools} 检出后建议调用。
   *
   * <p>详见 {@code renderer-compatibility.md#empty-pgnumtype}。
   *
   * @return 若清理了空 {@code <w:pgNumType/>} 则返回 {@code true};未设或有属性(保留)则返回 {@code false}
   */
  public boolean cleanEmptyPageNumbering() {
    if (!delegate.isSetPgNumType()) {
      return false;
    }
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageNumber pn =
        delegate.getPgNumType();
    if (pn.isSetStart() || pn.isSetFmt()) {
      return false; // 有属性,保留
    }
    delegate.unsetPgNumType();
    return true;
  }

  // ---------- 页眉/页脚 ----------

  /**
   * 以只读方式返回章节级别的默认（奇数页）页眉；不存在时返回 {@code null}，绝不创建。
   *
   * <p>等价于 {@code header(HeaderFooterVariant.DEFAULT)}。读写分离契约与 {@link #header(HeaderFooterVariant)}
   * 相同，详见那里。
   *
   * @return 此章节的默认页眉，不存在则返回 {@code null}
   */
  public Header header() {
    return header(HeaderFooterVariant.DEFAULT);
  }

  /**
   * 以只读方式返回章节级别的指定变体页眉；不存在时返回 {@code null}，绝不创建。
   *
   * <p><b>读写分离。</b> POI 本身就把”读”（{@code getDefaultHeader/getFirstPageHeader/getEvenPageHeader}，不存在返回
   * null）与”写”（{@code createHeader}，新建并附加 part）分成了两组方法。nondocx 遵循该分离：
   *
   * <ul>
   *   <li>{@code header(variant)} —— 纯只读，不存在返回 {@code null}，永不动文档。
   *   <li>{@link #ensureHeader(HeaderFooterVariant)} —— 显式创建（不存在才建），用于写入场景。
   * </ul>
   *
   * <p><b>变体与开关。</b> {@link HeaderFooterVariant#FIRST} 需要 {@code <w:titlePg/>}、 {@link
   * HeaderFooterVariant#EVEN} 需要 {@code <w:evenAndOddHeaders/>} 才会实际渲染。本方法（只读）<b>不</b>补开关 ——
   * 开关是文档修改行为，属于 {@code ensure} 写路径；只读遍历永不改文档。
   *
   * <p>页眉通过绑定到此章节 {@code CTSectPr} 的章节级别 {@code XWPFHeaderFooterPolicy} 解析，因此返回的页眉属于 <em>此</em>
   * 章节：在多章节文档中，每个 {@link Section} 携带自己的各变体页眉。
   *
   * @param variant 页眉变体（不能为 {@code null}）
   * @return 此章节的指定变体页眉，不存在则返回 {@code null}
   * @throws IllegalArgumentException 如果 {@code variant} 为 {@code null}
   */
  public Header header(HeaderFooterVariant variant) {
    Objects.requireNonNull(variant, "variant");
    try {
      XWPFHeaderFooterPolicy policy = new XWPFHeaderFooterPolicy(document, delegate);
      XWPFHeader existing = readHeader(policy, variant);
      return existing == null ? null : new Header(existing);
    } catch (POIXMLException e) {
      // 解析失败（罕见，如 part 损坏）按”不存在”处理，保证只读语义永不抛异常、永不动文档。
      return null;
    }
  }

  /**
   * 显式确保此章节存在一个默认页眉：不存在则创建并附加一个空的，已存在则原样返回。
   *
   * <p>等价于 {@code ensureHeader(HeaderFooterVariant.DEFAULT)}。详见 {@link
   * #ensureHeader(HeaderFooterVariant)}。
   *
   * @return 此章节的默认页眉（从不返回 {@code null}）
   * @throws DocxIOException 如果页眉部分无法创建或附加
   */
  public Header ensureHeader() {
    return ensureHeader(HeaderFooterVariant.DEFAULT);
  }

  /**
   * 显式确保此章节存在指定变体页眉：不存在则创建并附加一个空的，已存在则原样返回。
   *
   * <p>用于<b>写入</b>场景——需要拿到一个可 {@code addParagraph} 的页眉时调用本方法。只读遍历/搜索请用 {@link
   * #header(HeaderFooterVariant)}（不会凭空创建）。
   *
   * <p><b>开关补齐（重要）。</b> POI 的 {@code createHeader(variant)} <b>不</b>自动写以下开关，故本方法在创建 FIRST/EVEN
   * 变体时显式补齐（已存在则不重复写，幂等）：
   *
   * <ul>
   *   <li>{@link HeaderFooterVariant#FIRST} —— 在此章节的 {@code <w:sectPr>} 写 {@code <w:titlePg/>}
   *       （首页不同标志，per-section）。
   *   <li>{@link HeaderFooterVariant#EVEN} —— 在 {@code word/settings.xml} 写 {@code
   *       <w:evenAndOddHeaders/>} （奇偶页不同标志，文档级）。
   *   <li>{@link HeaderFooterVariant#DEFAULT} —— 无开关。
   * </ul>
   *
   * <p>不补开关的后果：part 创建了但渲染时被引擎忽略（Word/WPS 不显示首页/偶数页变体），属于「合法但无效」陷阱。
   *
   * <p>创建时若该节尚未显式写入 {@code <w:pgSz>} / {@code <w:pgMar>}，nondocx 也会补齐一个兼容性最小页面设置 （A4 + 四边 1
   * 英寸边距），以降低 WPS 等消费者对”裸 {@code <w:sectPr>}”的显示敏感性。
   *
   * @param variant 页眉变体（不能为 {@code null}）
   * @return 此章节的指定变体页眉（从不返回 {@code null}）
   * @throws IllegalArgumentException 如果 {@code variant} 为 {@code null}
   * @throws DocxIOException 如果页眉部分无法创建或附加
   */
  public Header ensureHeader(HeaderFooterVariant variant) {
    Objects.requireNonNull(variant, "variant");
    Header existing = header(variant);
    if (existing != null) {
      return existing;
    }
    try {
      ensureCompatiblePageSetupForHeaderFooterCreation();
      ensureVariantFlags(variant);
      XWPFHeaderFooterPolicy policy = new XWPFHeaderFooterPolicy(document, delegate);
      XWPFHeader created = policy.createHeader(Mappers.toPoi(variant));
      return new Header(created);
    } catch (POIXMLException e) {
      throw new DocxIOException("无法创建章节" + variant + "页眉", e);
    }
  }

  /**
   * 以只读方式返回章节级别的默认（奇数页）页脚；不存在时返回 {@code null}，绝不创建。
   *
   * <p>等价于 {@code footer(HeaderFooterVariant.DEFAULT)}。语义与 {@link #header()} 对称：纯只读、永不动文档。
   *
   * @return 此章节的默认页脚，不存在则返回 {@code null}
   */
  public Footer footer() {
    return footer(HeaderFooterVariant.DEFAULT);
  }

  /**
   * 以只读方式返回章节级别的指定变体页脚；不存在时返回 {@code null}，绝不创建。
   *
   * <p>语义与 {@link #header(HeaderFooterVariant)} 对称：纯只读、永不动文档。写入场景用 {@link
   * #ensureFooter(HeaderFooterVariant)}。
   *
   * @param variant 页脚变体（不能为 {@code null}）
   * @return 此章节的指定变体页脚，不存在则返回 {@code null}
   * @throws IllegalArgumentException 如果 {@code variant} 为 {@code null}
   */
  public Footer footer(HeaderFooterVariant variant) {
    Objects.requireNonNull(variant, "variant");
    try {
      XWPFHeaderFooterPolicy policy = new XWPFHeaderFooterPolicy(document, delegate);
      XWPFFooter existing = readFooter(policy, variant);
      return existing == null ? null : new Footer(existing);
    } catch (POIXMLException e) {
      return null;
    }
  }

  /**
   * 显式确保此章节存在一个默认页脚：不存在则创建并附加一个空的，已存在则原样返回。
   *
   * <p>等价于 {@code ensureFooter(HeaderFooterVariant.DEFAULT)}。详见 {@link
   * #ensureFooter(HeaderFooterVariant)}。
   *
   * @return 此章节的默认页脚（从不返回 {@code null}）
   * @throws DocxIOException 如果页脚部分无法创建或附加
   */
  public Footer ensureFooter() {
    return ensureFooter(HeaderFooterVariant.DEFAULT);
  }

  /**
   * 显式确保此章节存在指定变体页脚：不存在则创建并附加一个空的，已存在则原样返回。
   *
   * <p>用于<b>写入</b>场景。只读遍历/搜索请用 {@code footer(variant)}。开关补齐与兼容性页面设置补齐 与 {@link
   * #ensureHeader(HeaderFooterVariant)} 完全一致（FIRST 补 {@code titlePg}、EVEN 补 {@code
   * evenAndOddHeaders}）。
   *
   * @param variant 页脚变体（不能为 {@code null}）
   * @return 此章节的指定变体页脚（从不返回 {@code null}）
   * @throws IllegalArgumentException 如果 {@code variant} 为 {@code null}
   * @throws DocxIOException 如果页脚部分无法创建或附加
   */
  public Footer ensureFooter(HeaderFooterVariant variant) {
    Objects.requireNonNull(variant, "variant");
    Footer existing = footer(variant);
    if (existing != null) {
      return existing;
    }
    try {
      ensureCompatiblePageSetupForHeaderFooterCreation();
      ensureVariantFlags(variant);
      XWPFHeaderFooterPolicy policy = new XWPFHeaderFooterPolicy(document, delegate);
      XWPFFooter created = policy.createFooter(Mappers.toPoi(variant));
      return new Footer(created);
    } catch (POIXMLException e) {
      throw new DocxIOException("无法创建章节" + variant + "页脚", e);
    }
  }

  // ---------- 逃生出口 ----------

  /**
   * 返回底层的 OOXML 章节属性。
   *
   * <p>对返回对象的修改会立即影响文档。请谨慎使用。
   *
   * @return 底层的 {@code CTSectPr} 实例（包装器生命周期内同一实例）
   */
  public CTSectPr raw() {
    return delegate;
  }

  // ---------- 内容相等 ----------

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Section)) {
      return false;
    }
    Section that = (Section) o;
    return java.util.Objects.equals(this.paperSize(), that.paperSize())
        && this.orientation() == that.orientation()
        && this.marginTop() == that.marginTop()
        && this.marginRight() == that.marginRight()
        && this.marginBottom() == that.marginBottom()
        && this.marginLeft() == that.marginLeft()
        && java.util.Objects.equals(this.defaultHeaderParagraphs(), that.defaultHeaderParagraphs())
        && java.util.Objects.equals(this.defaultFooterParagraphs(), that.defaultFooterParagraphs())
        && java.util.Objects.equals(this.firstHeaderParagraphs(), that.firstHeaderParagraphs())
        && java.util.Objects.equals(this.firstFooterParagraphs(), that.firstFooterParagraphs())
        && java.util.Objects.equals(this.evenHeaderParagraphs(), that.evenHeaderParagraphs())
        && java.util.Objects.equals(this.evenFooterParagraphs(), that.evenFooterParagraphs());
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(
        paperSize(),
        orientation(),
        marginTop(),
        marginRight(),
        marginBottom(),
        marginLeft(),
        defaultHeaderParagraphs(),
        defaultFooterParagraphs(),
        firstHeaderParagraphs(),
        firstFooterParagraphs(),
        evenHeaderParagraphs(),
        evenFooterParagraphs());
  }

  @Override
  public String toString() {
    return "Section{paperSize="
        + paperSize()
        + ", orientation="
        + orientation()
        + ", margins=["
        + marginTop()
        + ","
        + marginRight()
        + ","
        + marginBottom()
        + ","
        + marginLeft()
        + "]}";
  }

  // ---------- 内部方法 ----------

  /** 返回 {@code <w:pgSz>} 元素，如果不存在则创建它。 */
  private CTPageSz ensurePgSz() {
    return delegate.isSetPgSz() ? delegate.getPgSz() : delegate.addNewPgSz();
  }

  /** 读取单个边距，当 {@code <w:pgMar>} 或特定属性未设置时返回 {@code 0}。 */
  private int marginOf(java.util.function.Function<CTPageMar, Object> getter) {
    if (!delegate.isSetPgMar()) {
      return 0;
    }
    Object value = getter.apply(delegate.getPgMar());
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    return 0;
  }

  /** 将原始 XmlBeans 维度（{@code BigInteger}-as-{@code Object}）强制转换为 long， 未设置时返回 {@code 0}。 */
  private static long twipsOf(Object value) {
    return value instanceof Number ? ((Number) value).longValue() : 0L;
  }

  /** 将原始 XmlBeans 维度强制转换为 long，未设置时返回 {@code defaultTwips}。 */
  private static long dimOrDefault(Object value, int defaultTwips) {
    return value instanceof Number ? ((Number) value).longValue() : defaultTwips;
  }

  /**
   * 在首次创建默认页眉/页脚前补齐最小页面设置。
   *
   * <p>OOXML 中，{@code <w:headerReference>} / {@code <w:footerReference>} 与 {@code <w:pgSz>} /
   * {@code <w:pgMar>} 同属一个 {@code <w:sectPr>}。POI 允许只写 header/footer 引用而不显式写页面设置， 但 WPS
   * 对这种“裸节属性”更敏感。这里仅在调用方首次创建 section-scoped 页眉/页脚、且页面设置缺失时， materialize 一个兼容性默认值；如果用户已显式设置，则绝不覆盖。
   */
  private void ensureCompatiblePageSetupForHeaderFooterCreation() {
    if (!delegate.isSetPgSz()) {
      paperSize(PaperSize.A4);
    }
    if (!delegate.isSetPgMar()) {
      margins(
          DEFAULT_COMPAT_MARGIN_TWIPS,
          DEFAULT_COMPAT_MARGIN_TWIPS,
          DEFAULT_COMPAT_MARGIN_TWIPS,
          DEFAULT_COMPAT_MARGIN_TWIPS);
    }
  }

  /**
   * 按 {@code variant} 分派到 POI 的对应只读 getter（POI 没有 {@code getHeader(STHdrFtr)} 统一方法，只有 三个分别方法）。
   *
   * <p>三个 getter 都遵循「不存在返 null」语义，与读写分离契约一致。
   */
  private static XWPFHeader readHeader(XWPFHeaderFooterPolicy policy, HeaderFooterVariant variant) {
    switch (variant) {
      case DEFAULT:
        return policy.getDefaultHeader();
      case FIRST:
        return policy.getFirstPageHeader();
      case EVEN:
        return policy.getEvenPageHeader();
      default:
        throw new IllegalArgumentException("未知的变体: " + variant);
    }
  }

  /** 同 {@link #readHeader}，针对页脚。 */
  private static XWPFFooter readFooter(XWPFHeaderFooterPolicy policy, HeaderFooterVariant variant) {
    switch (variant) {
      case DEFAULT:
        return policy.getDefaultFooter();
      case FIRST:
        return policy.getFirstPageFooter();
      case EVEN:
        return policy.getEvenPageFooter();
      default:
        throw new IllegalArgumentException("未知的变体: " + variant);
    }
  }

  /**
   * 补齐 FIRST/EVEN 变体生效所需的 OOXML 开关（POI 的 {@code createHeader(variant)} 不自动写）。
   *
   * <p><b>FIRST</b> → 此章节 {@code <w:sectPr>} 的 {@code <w:titlePg/>}（首页不同，per-section）。 <br>
   * <b>EVEN</b> → {@code word/settings.xml} 的 {@code <w:evenAndOddHeaders/>}（奇偶页不同，文档级）。 <br>
   * <b>DEFAULT</b> → 无开关。
   *
   * <p>幂等：已存在则不重复写。{@code document.getSettings()} 保证返回非 null（POI 会懒创建 settings part）。
   */
  private void ensureVariantFlags(HeaderFooterVariant variant) {
    if (variant == HeaderFooterVariant.FIRST && !delegate.isSetTitlePg()) {
      delegate.addNewTitlePg();
    }
    if (variant == HeaderFooterVariant.EVEN) {
      CTSettings settings = document.getSettings().getCTSettings();
      if (!settings.isSetEvenAndOddHeaders()) {
        settings.addNewEvenAndOddHeaders();
      }
    }
  }

  // ---------- 页眉/页脚（只读，用于相等性） ----------

  /**
   * 以只读方式解析此章节的默认页眉段落，供 {@code equals} / {@code hashCode} 使用。
   *
   * <p>读写分离后 {@link #header()} 本身就是只读的（null=不存在），这里只是把 null 归一化为空列表， 让相等性比较的写法不必处理 null 样板。
   */
  private List<Paragraph> defaultHeaderParagraphs() {
    Header header = header();
    return header == null ? Collections.emptyList() : header.paragraphs();
  }

  /** 同 {@link #defaultHeaderParagraphs()}，针对页脚。 */
  private List<Paragraph> defaultFooterParagraphs() {
    Footer footer = footer();
    return footer == null ? Collections.emptyList() : footer.paragraphs();
  }

  /** 首页页眉段落（只读，null 归一化为空列表），供 {@code equals} / {@code hashCode}。 */
  private List<Paragraph> firstHeaderParagraphs() {
    Header header = header(HeaderFooterVariant.FIRST);
    return header == null ? Collections.emptyList() : header.paragraphs();
  }

  /** 首页页脚段落（只读，null 归一化为空列表），供 {@code equals} / {@code hashCode}。 */
  private List<Paragraph> firstFooterParagraphs() {
    Footer footer = footer(HeaderFooterVariant.FIRST);
    return footer == null ? Collections.emptyList() : footer.paragraphs();
  }

  /** 偶数页页眉段落（只读，null 归一化为空列表），供 {@code equals} / {@code hashCode}。 */
  private List<Paragraph> evenHeaderParagraphs() {
    Header header = header(HeaderFooterVariant.EVEN);
    return header == null ? Collections.emptyList() : header.paragraphs();
  }

  /** 偶数页页脚段落（只读，null 归一化为空列表），供 {@code equals} / {@code hashCode}。 */
  private List<Paragraph> evenFooterParagraphs() {
    Footer footer = footer(HeaderFooterVariant.EVEN);
    return footer == null ? Collections.emptyList() : footer.paragraphs();
  }
}
