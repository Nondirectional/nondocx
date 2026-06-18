package com.non.docx.core.api.text;

import com.non.docx.core.api.InlineElement;
import com.non.docx.core.api.style.RunStyle;
import com.non.docx.core.internal.util.Objects;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFRun;

/**
 * 运行 — 段落内的连续文本片段，携带内联字符格式化信息。
 *
 * <p>持有 Apache POI {@code XWPFRun} 委托，并在其上暴露活跃的、可链式调用的视图。每个 修改器直接写入委托并返回 {@code this} 以支持链式调用；每个
 * 获取器直接读取。没有缓存快照。
 *
 * <p>内联格式化使用六个属性建模 — 粗体、斜体、下划线、字体名称、字体 大小（以磅为单位）和颜色（十六进制）。{@link #style()} 返回所有六个属性的不可变 {@link
 * RunStyle} 快照，内容相等性（{@code equals} / {@code hashCode}）比较的正是这些属性 以及运行的文本。委托引用从不参与相等性比较，因此两个基于不同 POI
 * 实例但具有相同文本和格式的运行是相等的 — 这就是 往返断言能正常工作的原因。
 *
 * <p>这是一个 <em>可变的活动对象</em>。其 {@code equals} / {@code hashCode} 用于比较 和往返断言；它们不适合作为长期存在的 {@code
 * HashMap} 键，因为 底层内容随时可能改变。
 */
public final class Run implements InlineElement {

  private final XWPFRun delegate;

  /**
   * 封装给定的 POI 运行。
   *
   * <p>此构造函数是 {@link Paragraph} 生成活跃运行包装器的内部接缝， 因此它有意接受 POI 类型。用户通常通过 {@code Paragraph.addRun(...)}
   * / {@code Paragraph.run(...)} 获取运行，而不是直接构造它们。
   *
   * @param delegate 底层的 POI 运行（不能为 {@code null}）
   * @throws IllegalArgumentException 如果 {@code delegate} 为 {@code null}
   */
  public Run(XWPFRun delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /**
   * 设置此运行的文本并返回 {@code this} 以支持链式调用。
   *
   * <p><b>POI 行为注意：</b> {@code XWPFRun.setText(String)} 内部调用 {@code setText(text,
   * sizeOfTArray())}——当位置等于现有 {@code <w:t>} 数量时会 <em>追加</em> 一个新的 {@code <w:t>}，而非替换。因此对一个已有文本的运行调用
   * {@code setText} 会把新文本拼到旧文本后面。 本方法先清空运行的底层 {@code CTR} 上所有 {@code <w:t>}，再调用 {@code setText}（此时
   * sizeOfTArray()==0，会新建一个携带新文本的 {@code <w:t>}）， 确保「替换」语义，与用户对 setter 的直觉一致。详见 poi-bridge.md N9。
   *
   * <p>传入空字符串可生成没有可见文本的运行。
   *
   * @param text 新文本（不能为 {@code null}；使用 {@code ""} 清除）
   * @return 此运行
   * @throws IllegalArgumentException 如果 {@code text} 为 {@code null}
   * @see poi-bridge.md N9（XWPFRun.setText 的追加行为）
   */
  public Run text(String text) {
    Objects.requireNonNull(text, "text");
    // 与 Hyperlink.text(String) 同一手法：先清空 CTR 上所有 <w:t>，再 setText，
    // 绕过 POI「pos==size 时追加」的行为，确保替换语义。
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR ctr = delegate.getCTR();
    int tCount = ctr.sizeOfTArray();
    for (int i = tCount - 1; i >= 0; i--) {
      ctr.removeT(i);
    }
    delegate.setText(text);
    return this;
  }

  /**
   * 返回此运行的完整纯文本。
   *
   * @return 运行的文本（可能为空，从不返回 {@code null}）
   */
  public String text() {
    return delegate.text();
  }

  /**
   * 设置或清除粗体并返回 {@code this}。
   *
   * @param bold 文本是否为粗体
   * @return 此运行
   */
  public Run bold(boolean bold) {
    delegate.setBold(bold);
    return this;
  }

  /** {@code bold(true)} 的便捷方法。 */
  public Run bold() {
    return bold(true);
  }

  /** 返回文本是否为粗体。 */
  public boolean isBold() {
    return delegate.isBold();
  }

  /**
   * 设置或清除斜体并返回 {@code this}。
   *
   * @param italic 文本是否为斜体
   * @return 此运行
   */
  public Run italic(boolean italic) {
    delegate.setItalic(italic);
    return this;
  }

  /** {@code italic(true)} 的便捷方法。 */
  public Run italic() {
    return italic(true);
  }

  /** 返回文本是否为斜体。 */
  public boolean isItalic() {
    return delegate.isItalic();
  }

  /**
   * 设置或清除下划线并返回 {@code this}。
   *
   * <p>启用下划线应用单下划线（Word 最常见的变体）。禁用它 会移除任何下划线。
   *
   * @param underline 文本是否带下划线
   * @return 此运行
   */
  public Run underline(boolean underline) {
    delegate.setUnderline(underline ? UnderlinePatterns.SINGLE : UnderlinePatterns.NONE);
    return this;
  }

  /** {@code underline(true)} 的便捷方法。 */
  public Run underline() {
    return underline(true);
  }

  /** 返回文本是否带有下划线。 */
  public boolean isUnderline() {
    UnderlinePatterns pattern = delegate.getUnderline();
    return pattern != null && pattern != UnderlinePatterns.NONE;
  }

  /**
   * 设置字体大小（以磅为单位）并返回 {@code this}。
   *
   * @param points 字体大小（磅）
   * @return 此运行
   */
  public Run fontSize(int points) {
    delegate.setFontSize((double) points);
    return this;
  }

  /**
   * 返回字体大小（以磅为单位），如果未在此运行上显式设置则返回 {@code null}。
   *
   * @return 字体大小（磅），如果未设置则返回 {@code null}
   */
  public Integer fontSize() {
    Double size = delegate.getFontSizeAsDouble();
    return size == null ? null : size.intValue();
  }

  /**
   * 设置字体名称并返回 {@code this}。
   *
   * @param name 字体名称（不能为 {@code null}）
   * @return 此运行
   * @throws IllegalArgumentException 如果 {@code name} 为 {@code null}
   */
  public Run font(String name) {
    Objects.requireNonNull(name, "name");
    delegate.setFontFamily(name);
    return this;
  }

  /** 返回字体名称，如果未在此运行上显式设置则返回 {@code null}。 */
  public String font() {
    return delegate.getFontFamily();
  }

  /**
   * 设置文本颜色并返回 {@code this}。
   *
   * @param hex 颜色为 6 位十六进制 RGB 字符串（例如 {@code "FF0000"}），不能为 {@code null}
   * @return 此运行
   * @throws IllegalArgumentException 如果 {@code hex} 为 {@code null}
   */
  public Run color(String hex) {
    Objects.requireNonNull(hex, "hex");
    delegate.setColor(hex);
    return this;
  }

  /** 返回文本颜色为十六进制 RGB 字符串，如果未在此运行上显式设置则返回 {@code null}。 */
  public String color() {
    return delegate.getColor();
  }

  /**
   * 返回此运行的内联字符格式化的不可变快照（六个样式 属性）。每次调用时实时获取快照。
   *
   * @return 反映当前格式化的 {@link RunStyle}（从不返回 {@code null}）
   */
  public RunStyle style() {
    return RunStyle.of(isBold(), isItalic(), isUnderline(), font(), fontSize(), color());
  }

  /**
   * 以「删除旧文本 + 插入新文本」的方式完成一条 tracked replacement,并返回新插入 run 的活跃包装。
   *
   * <p>底层把本 run 迁入 {@code <w:del>}(删除旧文本),随后新建 {@code <w:ins>}(插入新文本),两者各自携带修订元数据(author / 自动 date
   * / 自动分配的 {@code w:id})。新插入 run 复制本 run 替换前的文本样式(六个内联样式属性),贴近「替换文本但保留格式」的直觉。
   *
   * <p>替换后本 {@code Run} 已迁入 deletion 语义路径,不再是稳定的普通 live wrapper;请改用返回的新 run 继续操作。与 {@code
   * <w:trackChanges/>} 开关<b>正交</b>。
   *
   * <p><b>OOXML / POI / nondocx 三层。</b> replacement 不是独立 OOXML 元素,而是 del + ins 的组合;POI 无高层
   * API,节点创建下沉到 {@code internal/poi/TrackedChangeNodes}。
   *
   * @param author 修订作者(不能为 {@code null} 或空白)
   * @param newText 替换后的新文本(不能为 {@code null})
   * @return 新插入 run 的活跃包装
   * @throws IllegalArgumentException 如果 {@code author} 为 {@code null} 或空白,或 {@code newText} 为
   *     {@code null}
   */
  public Run replaceTracked(String author, String newText) {
    Objects.requireNonNull(author, "author");
    if (author.isBlank()) {
      throw new IllegalArgumentException("author 不能为空白");
    }
    Objects.requireNonNull(newText, "newText");
    org.apache.poi.xwpf.usermodel.XWPFParagraph parent = delegate.getParagraph();
    if (parent == null) {
      // run 未挂在段落上(罕见,如裸 CTR 构造的 Run),无法做 replacement。
      throw new java.util.NoSuchElementException("本 run 未挂在段落上,无法做 tracked replacement");
    }
    // 替换前先捕获样式,用于复制到新插入 run(本 run 在 deletion 后样式不再可信)。
    RunStyle snapshot = style();
    org.apache.poi.xwpf.usermodel.XWPFDocument doc = parent.getDocument();
    java.util.Calendar now = java.util.Calendar.getInstance();
    java.math.BigInteger baseId =
        com.non.docx.core.internal.poi.TrackedChangeNodes.nextRevisionId(doc);
    // 先 deletion,再 insertion;各用一个递增 w:id。
    com.non.docx.core.internal.poi.TrackedChangeNodes.addDeletion(
        parent, delegate.getCTR(), author, now, baseId);
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRunTrackChange ins =
        com.non.docx.core.internal.poi.TrackedChangeNodes.addInsertion(
            parent, newText, author, now, baseId.add(java.math.BigInteger.ONE));
    XWPFRun inserted = new XWPFRun(ins.getRList().get(0), parent);
    Run newRun = new Run(inserted);
    // 复制替换前的文本样式到新插入 run。
    if (snapshot.isBold()) {
      newRun.bold();
    }
    if (snapshot.isItalic()) {
      newRun.italic();
    }
    if (snapshot.isUnderline()) {
      newRun.underline();
    }
    if (snapshot.font() != null) {
      newRun.font(snapshot.font());
    }
    if (snapshot.size() != null) {
      newRun.fontSize(snapshot.size());
    }
    if (snapshot.color() != null) {
      newRun.color(snapshot.color());
    }
    return newRun;
  }

  /**
   * 返回底层的 POI 运行。
   *
   * <p>对返回对象的修改会立即影响文档。请谨慎使用。
   *
   * @return 底层的 {@code XWPFRun} 实例（包装器生命周期内同一实例）
   */
  public XWPFRun raw() {
    return delegate;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Run)) {
      return false;
    }
    Run that = (Run) o;
    return java.util.Objects.equals(this.text(), that.text())
        && java.util.Objects.equals(this.style(), that.style());
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(text(), style());
  }

  @Override
  public String toString() {
    return "Run{text=" + text() + ", style=" + style() + '}';
  }
}
