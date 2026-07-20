package io.github.nondirectional.docx.core.api.table;

import io.github.nondirectional.docx.core.api.style.Shading;
import io.github.nondirectional.docx.core.api.style.VerticalAlign;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import io.github.nondirectional.docx.core.internal.poi.ShadingNodes;
import io.github.nondirectional.docx.core.internal.util.Objects;
import java.util.AbstractList;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;

/**
 * {@link Row} 中的单元格 — 段落的容器，表格的最小可寻址单元。
 *
 * <p>持有 Apache POI {@code XWPFTableCell} 委托，并在其上暴露活跃视图。读取 直接穿透到委托；没有缓存快照。每次修改都是直接写入。
 *
 * <p>单元格 <em>不是</em> 正文元素 — 它位于行内部、表格内部。其内容是 由 {@link #paragraphs()} 返回的有序段落序列。内容相等性（{@code equals}
 * / {@code hashCode}）比较该有序段落序列，从不比较委托 引用，因此两个基于不同 POI 实例但具有相同段落的单元格是相等的 — 这就是 往返断言能正常工作的原因。
 *
 * <p>{@link #text()} 返回单元格的拼接纯文本。{@link #text(String)} 将 给定文本写入单元格的第一个段落（如果单元格为空则创建段落，
 * 并清除该段落现有的运行）并返回 {@code this} 以支持链式调用；这镜像了 POI 的 {@code XWPFTableCell.setText}。第一个段落之后的段落保持不变。
 *
 * <p>这是一个 <em>可变的活动对象</em>。其 {@code equals} / {@code hashCode} 用于比较 和往返断言；它们不适合作为长期存在的 {@code
 * HashMap} 键，因为 底层内容随时可能改变。
 */
public final class Cell {

  private final XWPFTableCell delegate;

  /**
   * 封装给定的 POI 单元格。
   *
   * <p>此构造函数是 {@link Row} 生成活跃单元格包装器的内部接缝， 因此它有意接受 POI 类型。用户通常通过 {@code Row.cells()} / {@code
   * Row.addCell()} 获取单元格，而不是直接构造它们。
   *
   * @param delegate 底层的 POI 单元格（不能为 {@code null}）
   * @throws IllegalArgumentException 如果 {@code delegate} 为 {@code null}
   */
  public Cell(XWPFTableCell delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /**
   * 返回此单元格的段落的活跃视图，按阅读顺序排列。
   *
   * <p>每次访问时都会从委托重新读取视图，因此变更会实时反映。
   *
   * @return 活跃、不可修改的段落列表
   */
  public List<Paragraph> paragraphs() {
    return new AbstractList<Paragraph>() {
      private final List<XWPFParagraph> backing = delegate.getParagraphs();

      @Override
      public Paragraph get(int index) {
        return new Paragraph(backing.get(index));
      }

      @Override
      public int size() {
        return backing.size();
      }
    };
  }

  /**
   * 返回指定索引处的段落。
   *
   * @param index 段落索引（从 0 开始，指向 {@link #paragraphs()}）
   * @return 该位置的段落
   * @throws IndexOutOfBoundsException 如果 {@code index} 超出范围
   */
  public Paragraph paragraph(int index) {
    return paragraphs().get(index);
  }

  /**
   * 向此单元格追加一个新的空段落，并返回其活跃包装器。
   *
   * @return 新追加的段落
   */
  public Paragraph addParagraph() {
    return new Paragraph(delegate.addParagraph());
  }

  /**
   * 返回此单元格的拼接纯文本（所有段落按阅读顺序连接）。
   *
   * @return 单元格的文本（可能为空，从不返回 {@code null}）
   */
  public String text() {
    return delegate.getText();
  }

  /**
   * 将给定文本写入此单元格的第一个段落，并返回 {@code this} 以支持链式调用。
   *
   * <p>如果单元格为空，则创建一个段落；否则清除第一个段落现有的运行 并添加一个携带文本的单个运行。第一个段落之后的段落保持 不变（多段落内容请使用 {@link #paragraphs()}
   * / {@link #addParagraph()}）。 这镜像了 POI 的 {@code XWPFTableCell.setText}。
   *
   * @param text 要写入第一个段落的文本（不能为 {@code null}）
   * @return 此单元格
   * @throws IllegalArgumentException 如果 {@code text} 为 {@code null}
   */
  public Cell text(String text) {
    Objects.requireNonNull(text, "text");
    delegate.setText(text);
    return this;
  }

  /**
   * 把此单元格标记为<b>被插入</b>(tracked cellIns),即「这个单元格本身是被新增的」。
   *
   * <p><b>OOXML</b>:在单元格属性 {@code <w:tcPr>} 内写 {@code <w:cellIns w:id=.. w:author=..
   * w:date=../>}。它是裸属性(无 run、无文本),标记单元格的<b>存亡</b>(表格结构修订),与单元格内文本的 ins/del 无关。
   *
   * <p><b>POI</b>:{@code CTTcPr.addNewCellIns()} 返回 {@code CTTrackChange}(与单元格修订读侧 N16 同委托)。nondocx
   * 把节点创建下沉到 {@code internal/poi/TrackedChangeNodes}。
   *
   * <p><b>nondocx</b>:与 {@code Paragraph.addInsertion} 同属「显式 tracked 方法」——author 必传,date 与 {@code
   * w:id} 自动分配。创作出的修订随后可被 {@code doc.trackedChanges().list()} 读回为 {@code CELL_INS},也能被 {@code
   * acceptCell}/{@code rejectCell} 处理(作用于整个 {@code <w:tc>})。
   *
   * <p>与 {@code <w:trackChanges/>} 开关<b>正交</b>。
   *
   * @param author 修订作者(不能为 {@code null} 或空白)
   * @return 此单元格(链式)
   * @throws IllegalArgumentException 如果 {@code author} 为 {@code null} 或空白
   */
  public Cell markInserted(String author) {
    requireAuthor(author);
    io.github.nondirectional.docx.core.internal.poi.TrackedChangeNodes.markCellIns(
        delegate.getXWPFDocument(), delegate.getCTTc(), author, java.util.Calendar.getInstance());
    return this;
  }

  /**
   * 把此单元格标记为<b>被删除</b>(tracked cellDel)。
   *
   * <p>语义:标记此单元格本身是被删除的(存亡修订)。accept 时移除整个 {@code <w:tc>},reject 时保留。其余同 {@link
   * #markInserted(String)}。
   *
   * @param author 修订作者(不能为 {@code null} 或空白)
   * @return 此单元格(链式)
   * @throws IllegalArgumentException 如果 {@code author} 为 {@code null} 或空白
   */
  public Cell markDeleted(String author) {
    requireAuthor(author);
    io.github.nondirectional.docx.core.internal.poi.TrackedChangeNodes.markCellDel(
        delegate.getXWPFDocument(), delegate.getCTTc(), author, java.util.Calendar.getInstance());
    return this;
  }

  /** 校验 author 非空非空白(创作类方法共用)。 */
  private static void requireAuthor(String author) {
    Objects.requireNonNull(author, "author");
    if (author.isBlank()) {
      throw new IllegalArgumentException("author 不能为空白");
    }
  }

  /**
   * 给此单元格设置<b>纯色背景填充</b>底纹，并返回 {@code this} 以支持链式调用。
   *
   * <p><b>OOXML</b>:在单元格属性 {@code <w:tcPr>} 内写 {@code <w:shd w:val="clear" w:fill="...">}。
   *
   * <p><b>WPS/Word 兼容性</b>:本方法<b>强制 {@code w:val="clear"}</b>(纯背景色填充,跨引擎安全), 不暴露 {@code SOLID}(WPS
   * 渲染为黑块,见 {@code renderer-compatibility.md#shading-solid})。若需要其它图案, 使用 {@link
   * #shading(Shading)};若确实需要 SOLID 语义,走 {@link #raw()} 直接操纵 {@code CTShd}。
   *
   * <p>等价于 {@code shading(Shading.of(fill))}。覆盖此单元格上已有的底纹。
   *
   * @param fill 背景色(十六进制 RGB 字符串,如 {@code "F1F5F9"},不带 {@code #};不能为 {@code null})
   * @return 此单元格(链式)
   * @throws IllegalArgumentException 如果 {@code fill} 为 {@code null}
   */
  public Cell shading(String fill) {
    return shading(Shading.of(fill));
  }

  /**
   * 给此单元格设置指定的底纹,并返回 {@code this} 以支持链式调用。
   *
   * <p>覆盖此单元格上已有的底纹。{@link Shading} 的 {@code ShadingPattern} 枚举已排除 {@code SOLID}, 故本方法永远不产出 WPS
   * 黑块风险。
   *
   * @param shading 底纹值对象(不能为 {@code null})
   * @return 此单元格(链式)
   * @throws IllegalArgumentException 如果 {@code shading} 为 {@code null}
   */
  public Cell shading(Shading shading) {
    Objects.requireNonNull(shading, "shading");
    ShadingNodes.applyToCell(delegate.getCTTc(), shading);
    return this;
  }

  /**
   * 返回此单元格的底纹。
   *
   * <p>每次访问都从委托重新读取。读取时 OOXML 中未在 nondocx 建模的图案(各种条纹/百分比/SOLID)归并为 {@code NIL};若需保留原始图案细节,走 {@link
   * #raw()} 直接读 {@code CTShd}。
   *
   * @return 底纹值对象;若未设底纹则返回 {@code null}
   */
  public Shading shading() {
    return ShadingNodes.readFromCell(delegate.getCTTc());
  }

  /**
   * 移除此单元格的底纹,并返回 {@code this} 以支持链式调用。
   *
   * <p>若未设底纹则无操作。
   *
   * @return 此单元格(链式)
   */
  public Cell removeShading() {
    ShadingNodes.removeFromCell(delegate.getCTTc());
    return this;
  }

  /**
   * 设置此单元格内容的垂直对齐方式,并返回 {@code this} 以支持链式调用。
   *
   * <p><b>OOXML</b>:在 {@code <w:tcPr>} 内写 {@code <w:vAlign w:val="top|center|bottom">}。
   *
   * <p><b>WPS/Word 兼容性</b>:当单元格设了 <b>固定(exact)行高</b>时,{@code CENTER} 与 {@code BOTTOM} 在 WPS
   * 里可能不生效(见 {@code renderer-compatibility.md#exact-row-valign})。跨引擎要求严格的场景建议用 {@link
   * VerticalAlign#TOP}(也是 OOXML 默认)。
   *
   * @param align 垂直对齐(不能为 {@code null})
   * @return 此单元格(链式)
   * @throws IllegalArgumentException 如果 {@code align} 为 {@code null}
   */
  public Cell verticalAlign(VerticalAlign align) {
    Objects.requireNonNull(align, "align");
    io.github.nondirectional.docx.core.internal.poi.CellNodes.applyVerticalAlign(
        delegate.getCTTc(), align);
    return this;
  }

  /**
   * 返回此单元格内容的垂直对齐方式。
   *
   * <p>每次访问都从委托重新读取。未设垂直对齐时返回 {@code null}(OOXML 的实际默认行为是 {@link VerticalAlign#TOP})。
   *
   * @return 垂直对齐;若未设则返回 {@code null}
   */
  public VerticalAlign verticalAlign() {
    return io.github.nondirectional.docx.core.internal.poi.CellNodes.readVerticalAlign(
        delegate.getCTTc());
  }

  /**
   * 返回底层的 POI 单元格。
   *
   * <p>对返回对象的修改会立即影响文档。请谨慎使用。
   *
   * @return 底层的 {@code XWPFTableCell} 实例（包装器生命周期内同一实例）
   */
  public XWPFTableCell raw() {
    return delegate;
  }

  // ---------- 内容相等 ----------

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Cell)) {
      return false;
    }
    Cell that = (Cell) o;
    return java.util.Objects.equals(this.paragraphs(), that.paragraphs())
        && java.util.Objects.equals(this.shading(), that.shading())
        && java.util.Objects.equals(this.verticalAlign(), that.verticalAlign());
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(paragraphs(), shading(), verticalAlign());
  }
}
