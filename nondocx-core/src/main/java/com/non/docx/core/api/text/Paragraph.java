package com.non.docx.core.api.text;

import com.non.docx.core.api.BodyElement;
import com.non.docx.core.api.InlineElement;
import com.non.docx.core.api.exception.DocxIOException;
import com.non.docx.core.api.exception.DocxOperationException;
import com.non.docx.core.api.image.Image;
import com.non.docx.core.api.image.ImageType;
import com.non.docx.core.api.style.Alignment;
import com.non.docx.core.api.style.HeadingLevel;
import com.non.docx.core.api.style.ListKind;
import com.non.docx.core.api.style.Shading;
import com.non.docx.core.internal.poi.Mappers;
import com.non.docx.core.internal.poi.Numbering;
import com.non.docx.core.internal.poi.Pictures;
import com.non.docx.core.internal.poi.ShadingNodes;
import com.non.docx.core.internal.util.Objects;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.xwpf.usermodel.IRunElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFldChar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType;

/**
 * A paragraph — a body-level block of inline content.
 *
 * <p>Holds an Apache POI {@code XWPFParagraph} delegate and exposes a live view over it. Reads go
 * straight through to the delegate; there is no cached snapshot. Paragraph-level style mutators
 * (heading, alignment, indentation, line spacing) return {@code this} for chaining.
 *
 * <p>The <em>structural source of truth</em> for a paragraph's content is {@link
 * #inlineElements()}: the ordered sequence of runs, hyperlinks and inline images in reading order.
 * A run that carries an embedded picture is surfaced in that view as an {@link Image} (not a {@link
 * Run}), so images take part in the ordering and in content equality. {@link #runs()} is a
 * type-filtered view that keeps only the plain runs; round-trip equality is based on the full
 * {@code inlineElements()} order, so a run followed by a hyperlink followed by a run stays in that
 * order.
 *
 * <p>Content equality ({@code equals} / {@code hashCode}) compares the ordered inline elements, the
 * paragraph-level style (heading, alignment, indentation, line spacing) and list membership (kind
 * and nesting level), never the delegate reference. This is what makes round-trip assertions work.
 *
 * <p><b>List membership:</b> {@link #list(ListKind, int)} marks this paragraph as a member of a
 * bulleted or numbered list at a 0-based nesting level (0..8); {@link #clearList()} removes that
 * membership. {@link #listKind()} and {@link #listLevel()} read it back and report {@code null} for
 * a paragraph that is not part of any list.
 */
public final class Paragraph implements BodyElement {

  private final XWPFParagraph delegate;

  /**
   * Wraps the given POI paragraph.
   *
   * <p>This constructor is the internal seam by which {@code Document} produces live paragraph
   * wrappers, so it accepts a POI type by design. Users normally obtain paragraphs via {@code
   * Document.paragraph(...)} / {@code Document.addParagraph(...)} rather than constructing them
   * directly.
   *
   * @param delegate the backing POI paragraph (not {@code null})
   * @throws IllegalArgumentException if {@code delegate} is {@code null}
   */
  public Paragraph(XWPFParagraph delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /**
   * Returns the paragraph's concatenated plain-text content.
   *
   * @return the text of this paragraph (possibly empty, never {@code null})
   */
  public String text() {
    return delegate.getText();
  }

  // ---------- inline ordered view (structural source of truth) ----------

  /**
   * Returns a live view of this paragraph's inline content in true reading order.
   *
   * <p>The returned list contains the inline constructs nondocx models — runs, hyperlinks and
   * inline images — preserving their order. A run that carries an embedded picture is surfaced here
   * as an {@link Image} (a hyperlink run is always surfaced as a {@link Hyperlink}). Other inline
   * constructs (for example structured document tags) are excluded; they remain reachable via
   * {@code raw().getIRuns()}. The view is re-read from the delegate on every access, so mutations
   * are reflected live.
   *
   * @return a live, unmodifiable list of inline elements in reading order
   */
  public List<InlineElement> inlineElements() {
    return new AbstractList<InlineElement>() {
      @Override
      public InlineElement get(int index) {
        return wrap(modeledIruns().get(index));
      }

      @Override
      public int size() {
        return modeledIruns().size();
      }
    };
  }

  /**
   * Returns the inline element at the given reading-order index.
   *
   * @param index reading-order index (0-based, into {@link #inlineElements()})
   * @return the inline element at that position
   * @throws IndexOutOfBoundsException if {@code index} is out of range
   */
  public InlineElement inlineElement(int index) {
    return inlineElements().get(index);
  }

  /**
   * Returns a live, type-filtered view of this paragraph's plain runs (in reading order).
   * Hyperlinks are excluded — they are their own inline element type.
   *
   * @return a live, unmodifiable list of runs
   */
  public List<Run> runs() {
    final List<InlineElement> all = inlineElements();
    return new AbstractList<Run>() {
      @Override
      public Run get(int index) {
        int seen = 0;
        for (InlineElement element : all) {
          if (element instanceof Run) {
            if (seen == index) {
              return (Run) element;
            }
            seen++;
          }
        }
        throw new IndexOutOfBoundsException(
            "run index " + index + " out of bounds (paragraph has " + size() + " runs)");
      }

      @Override
      public int size() {
        int count = 0;
        for (InlineElement element : all) {
          if (element instanceof Run) {
            count++;
          }
        }
        return count;
      }
    };
  }

  /**
   * Returns the run at the given filtered run index.
   *
   * @param index run index (0-based, into {@link #runs()})
   * @return the run at that position
   * @throws IndexOutOfBoundsException if {@code index} is out of range
   */
  public Run run(int index) {
    return runs().get(index);
  }

  /**
   * Appends a new, empty run to this paragraph and returns a live wrapper for it.
   *
   * @return the newly appended run
   */
  public Run addRun() {
    return new Run(delegate.createRun());
  }

  /**
   * Appends a new run carrying the given text and returns a live wrapper for it.
   *
   * @param text the run's text (not {@code null})
   * @return the newly appended run
   * @throws IllegalArgumentException if {@code text} is {@code null}
   */
  public Run addRun(String text) {
    Objects.requireNonNull(text, "text");
    XWPFRun run = delegate.createRun();
    run.setText(text);
    return new Run(run);
  }

  /**
   * 在段落末尾追加一条 tracked insertion(插入)修订,并返回新插入 run 的活跃包装。
   *
   * <p>与普通 {@link #addRun(String)} 不同:本方法写出的是被追踪的插入——底层生成 {@code <w:ins>} 修订节点(带 author / 自动 date /
   * 自动分配的 {@code w:id}),其内的 run 承载新文本。该修订随后可被 {@code doc.trackedChanges().list()} 读回,也能被
   * accept/reject 子任务按稳定 id 命中。
   *
   * <p>与 {@code <w:trackChanges/>} 开关<b>正交</b>:无论文档是否开启修订记录,本方法都显式写出修订节点(不依赖开关)。新 run 按普通新 run
   * 起步,不复制外部 run 样式。
   *
   * <p><b>OOXML / POI / nondocx 三层。</b> {@code <w:ins>} 是包住 {@code <w:r>} 的包装元素;POI 没有创建 tracked
   * insertion 的高层 API,nondocx 把节点创建下沉到 {@code internal/poi/TrackedChangeNodes};POI 的 {@code
   * XWPFParagraph.getRuns()} <b>不</b>暴露 {@code ins} 内的 run,故这里从底层 {@code CTR} 重新构造 {@code XWPFRun}
   * 以保证返回的 {@link Run} 可继续链式操作。
   *
   * @param author 修订作者(不能为 {@code null} 或空白)
   * @param text 插入的文本(不能为 {@code null})
   * @return 新插入 run 的活跃包装
   * @throws IllegalArgumentException 如果 {@code author} 为 {@code null} 或空白,或 {@code text} 为 {@code
   *     null}
   * @see com.non.docx.core.api.track.TrackedChanges
   */
  public Run addInsertion(String author, String text) {
    requireAuthor(author);
    Objects.requireNonNull(text, "text");
    XWPFDocument doc = delegate.getDocument();
    java.util.Calendar now = java.util.Calendar.getInstance();
    java.math.BigInteger revisionId =
        com.non.docx.core.internal.poi.TrackedChangeNodes.nextRevisionId(doc);
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRunTrackChange ins =
        com.non.docx.core.internal.poi.TrackedChangeNodes.addInsertion(
            delegate, text, author, now, revisionId);
    // ins 内的 run 不被 XWPFParagraph.getRuns() 暴露,从 CTR 重新构造 XWPFRun。
    XWPFRun inserted = new XWPFRun(ins.getRList().get(0), delegate);
    return new Run(inserted);
  }

  /**
   * 将本段落中一个已有的 run 显式标记为 tracked deletion(删除),并返回 {@code this}。
   *
   * <p>目标 run 从「普通正文 run」迁入 {@code <w:del>} 修订节点(其 {@code <w:t>} 转为 {@code
   * <w:delText>}),成为被追踪的删除内容。该修订随后可被 {@code doc.trackedChanges().list()} 读回。
   *
   * <p>本方法<b>不返回</b>原 {@link Run}:迁入 deletion 语义路径后,原 run 已不再是稳定的「普通正文 live
   * wrapper」,继续暴露它会制造误导。要继续在段落上操作,用返回的 {@code this} 链式调用。
   *
   * @param author 修订作者(不能为 {@code null} 或空白)
   * @param target 要标记删除的目标 run(不能为 {@code null},且必须属于本段落)
   * @return 本段落(便于链式)
   * @throws IllegalArgumentException 如果 {@code author} 为 {@code null} 或空白,或 {@code target} 为 {@code
   *     null}
   * @throws java.util.NoSuchElementException 如果 {@code target} 不属于本段落
   * @see com.non.docx.core.api.track.TrackedChanges
   */
  public Paragraph addDeletion(String author, Run target) {
    requireAuthor(author);
    Objects.requireNonNull(target, "target");
    // 校验 target 属于本段落(避免把别的段落的 run 当成本段落的来标记)。
    if (!containsRun(target)) {
      throw new java.util.NoSuchElementException("目标 run 不属于本段落,无法标记为 tracked deletion");
    }
    XWPFDocument doc = delegate.getDocument();
    java.util.Calendar now = java.util.Calendar.getInstance();
    java.math.BigInteger revisionId =
        com.non.docx.core.internal.poi.TrackedChangeNodes.nextRevisionId(doc);
    com.non.docx.core.internal.poi.TrackedChangeNodes.addDeletion(
        delegate, target.raw().getCTR(), author, now, revisionId);
    return this;
  }

  /**
   * 把一组 run 从<b>源段落</b>移动到<b>本段落</b>(作为 tracked move 修订),并返回新插入到本段落的 run 列表。
   *
   * <p>语义:这是「把源段的这些 run 剪切、粘贴到本段落」的修订版本——产出配对的 moveFrom(源端,文本转 delText)与 moveTo(本段/目标端,文本为 t),两者靠
   * rangeStart 的 {@code w:name} 配对。该修订随后可被 {@code list()} 读回为 {@code MOVE_FROM} + {@code MOVE_TO}
   * 两条,被 {@code accept}/{@code reject} 配对联动处理。
   *
   * <p>接受方是<b>本段落</b>(目标段),与 {@link #addInsertion(String, String)} 住在同一类型——「往本段落追加内容」的语义一致。
   *
   * <p><b>OOXML / POI / nondocx 三层。</b> move 不是单个 OOXML 元素,而是源端 moveFromRangeStart/End +
   * moveFrom、目标端 moveToRangeStart/End + moveTo 的四件配对;POI 无高层 API,节点创建下沉到 {@code
   * internal/poi/TrackedChangeNodes.moveRuns}(探针验证 name 配对与 delText/t
   * 规则,research/authoring-forms.md §2)。与 {@code <w:trackChanges/>} 开关正交。
   *
   * @param author 修订作者(不能为 {@code null} 或空白)
   * @param sourceParagraph 源段落(不能为 {@code null})
   * @param runs 要移动的源 runs(不能为 {@code null} 或空;每个都必须属于 {@code sourceParagraph})
   * @return 新插入到本段落(runTo 内)的 run 列表,按原文本顺序;可继续链式操作
   * @throws IllegalArgumentException 如果 {@code author} 为空白,或 {@code runs} 为空,或有 run 不属于 {@code
   *     sourceParagraph}
   * @see com.non.docx.core.api.track.TrackedChanges
   */
  public List<Run> moveRunsFrom(String author, Paragraph sourceParagraph, List<Run> runs) {
    requireAuthor(author);
    Objects.requireNonNull(sourceParagraph, "sourceParagraph");
    Objects.requireNonNull(runs, "runs");
    if (runs.isEmpty()) {
      throw new IllegalArgumentException("runs 不能为空");
    }
    // 校验所有 run 都属于 sourceParagraph
    for (Run r : runs) {
      if (!sourceParagraph.containsRun(r)) {
        throw new IllegalArgumentException("存在不属于源段落的 run,无法移动");
      }
    }
    XWPFDocument doc = delegate.getDocument();
    java.util.Calendar now = java.util.Calendar.getInstance();
    // 收集源 runs 的底层 CTR
    List<org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR> srcCtrs =
        new java.util.ArrayList<>();
    for (Run r : runs) {
      srcCtrs.add(r.raw().getCTR());
    }
    List<org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR> movedCtrs =
        com.non.docx.core.internal.poi.TrackedChangeNodes.moveRuns(
            doc, sourceParagraph.raw().getCTP(), delegate.getCTP(), srcCtrs, author, now);
    // 把新建的 moveTo 内 run 包成活跃 Run
    List<Run> moved = new java.util.ArrayList<>();
    for (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR ctr : movedCtrs) {
      moved.add(new Run(new XWPFRun(ctr, delegate)));
    }
    return moved;
  }

  /** 校验 author 参数:非 {@code null} 且非空白,否则抛 {@link IllegalArgumentException}。 */
  private static void requireAuthor(String author) {
    Objects.requireNonNull(author, "author");
    if (author.isBlank()) {
      throw new IllegalArgumentException("author 不能为空白");
    }
  }

  /** 判断给定 run 是否属于本段落(按底层 CTR 引用相等)。 */
  private boolean containsRun(Run run) {
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR target = run.raw().getCTR();
    for (XWPFRun r : delegate.getRuns()) {
      if (r.getCTR() == target) {
        return true;
      }
    }
    return false;
  }

  /**
   * Appends a new hyperlink carrying the given display text and target URL, and returns a live
   * wrapper for it.
   *
   * @param text the hyperlink's visible text (not {@code null})
   * @param url the hyperlink's target URL (not {@code null})
   * @return the newly appended hyperlink
   * @throws IllegalArgumentException if {@code text} or {@code url} is {@code null}
   * @throws DocxIOException if the hyperlink run cannot be created (for example a POI relationship
   *     failure)
   */
  public Hyperlink addHyperlink(String text, String url) {
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(url, "url");
    try {
      XWPFHyperlinkRun hyperlink = delegate.createHyperlinkRun(url);
      hyperlink.setText(text);
      return new Hyperlink(hyperlink);
    } catch (POIXMLException e) {
      throw new DocxIOException("无法创建超链接", e);
    }
  }

  /**
   * Appends a new inline image to this paragraph and returns a live wrapper for it.
   *
   * <p>OOXML embeds an inline picture <em>inside</em> a run (as a drawing). This method creates a
   * fresh run holding only that picture (no text), so it appears as an {@link Image} in {@link
   * #inlineElements()}. The {@code width} and {@code height} are in <em>pixels</em> at 96&nbsp;DPI
   * and are converted to EMU internally (Apache POI's {@code addPicture} stores them as EMU); they
   * survive a save → open round-trip exactly. POI / IO failures while embedding the picture are
   * wrapped into a {@link DocxIOException} on this public surface.
   *
   * @param bytes the raw picture bytes (not {@code null})
   * @param type the image format (not {@code null})
   * @param width the picture width in pixels (96&nbsp;DPI)
   * @param height the picture height in pixels (96&nbsp;DPI)
   * @return the newly appended image
   * @throws IllegalArgumentException if {@code bytes} or {@code type} is {@code null}
   * @throws DocxIOException if the picture cannot be embedded
   */
  public Image addImage(byte[] bytes, ImageType type, int width, int height) {
    Objects.requireNonNull(bytes, "bytes");
    Objects.requireNonNull(type, "type");
    XWPFRun run = delegate.createRun();
    try {
      // Apache POI's addPicture stores width/height verbatim as EMU on the <wp:extent>, so
      // convert pixel inputs (the unit this API exposes) to EMU first for an exact round-trip.
      XWPFPicture picture =
          run.addPicture(
              new ByteArrayInputStream(bytes),
              Mappers.toPoi(type),
              "image",
              Pictures.emuFromPixels(width),
              Pictures.emuFromPixels(height));
      return new Image(picture);
    } catch (IOException | InvalidFormatException | POIXMLException e) {
      throw new DocxIOException("无法添加内联图像", e);
    }
  }

  /**
   * 在此段落末尾追加一个简单域（simple field），并返回承载域指令的 run。
   *
   * <p><b>OOXML。</b> 一个简单域由三个相邻 run 组成：
   *
   * <pre>{@code
   * <w:r><w:fldChar w:fldCharType="begin"/></w:r>   ← 域开始
   * <w:r><w:instrText> PAGE </w:instrText></w:r>     ← 域指令（返回这个 run）
   * <w:r><w:fldChar w:fldCharType="end"/></w:r>      ← 域结束
   * }</pre>
   *
   * 域指令住在 {@code <w:instrText>}，不是普通可见文本 {@code <w:t>}。
   *
   * <p><b>POI。</b> 没有 {@code XWPFField} / {@code addSimpleField} 这类高层 API，本方法直接操纵 {@code
   * CTR}（{@code addNewFldChar} + {@code addNewInstrText}），与 {@code addPicture} / tracked-changes
   * 的下沉路径同型。
   *
   * <p><b>nondocx 为什么放在 {@code Paragraph} 上。</b> Word 标准产出的简单域就是 3 个相邻 run（不是「单 run 三子元素」）；创建新
   * inline 内容的入口是 {@code Paragraph}（同 {@link #addHyperlink} / {@link #addImage}）， 故本方法与之同模式。入口若放在
   * {@code Run} 上，要么违反 {@code Run} mutator 返回 {@code this} 的链式惯例， 要么越权创建兄弟 run。
   *
   * <p><b>域的实际可见值</b>（如 {@code PAGE} 域显示的页码数字）由 Word/WPS 打开时的渲染引擎计算，POI 与 nondocx 都不计算 ——
   * 本方法只写指令结构。简单域不带 {@code separate} 缓存段，打开时由渲染引擎即时填充。
   *
   * <p><b>返回的 run</b> 承载 {@code <w:instrText>}，可对其链式设样式（域可见结果的样式由此 run 决定）。 注意该 run 的 {@link
   * Run#text()} 返回空串 —— 指令不是 {@code <w:t>}。
   *
   * <p><b>读侧。</b> 识别 / 解析已有域不在本方法范围，走 {@code raw()}。
   *
   * @param instruction 域指令（如 {@code "PAGE"}、{@code "NUMPAGES"}、{@code "DATE \\@ yyyy"}； 原样写入 {@code
   *     <w:instrText>}，不做语法校验；不能为 {@code null} 或空白）
   * @return 承载域指令的 run（可继续链式设样式）
   * @throws IllegalArgumentException 如果 {@code instruction} 为 {@code null} 或空白
   */
  public Run addSimpleField(String instruction) {
    Objects.requireNonNull(instruction, "instruction");
    if (instruction.isBlank()) {
      throw new IllegalArgumentException("instruction 不能为空白");
    }
    // run 1：begin
    XWPFRun beginRun = delegate.createRun();
    CTFldChar begin = beginRun.getCTR().addNewFldChar();
    begin.setFldCharType(STFldCharType.BEGIN);
    // run 2：instrText（返回这个）
    XWPFRun instrRun = delegate.createRun();
    instrRun.getCTR().addNewInstrText().setStringValue(instruction);
    // run 3：end
    XWPFRun endRun = delegate.createRun();
    CTFldChar end = endRun.getCTR().addNewFldChar();
    end.setFldCharType(STFldCharType.END);
    return new Run(instrRun);
  }

  /**
   * 在此段落末尾追加一个页码域（{@code PAGE}），并返回承载域指令的 run。
   *
   * <p>等价于 {@link #addSimpleField(String) addSimpleField("PAGE")}。页码域在 Word/WPS 打开时由
   * 渲染引擎填充当前页码。常用于页脚。
   *
   * @return 承载域指令的 run（可继续链式设样式）
   */
  public Run addPageNumberField() {
    return addSimpleField("PAGE");
  }

  /**
   * 在此段落末尾追加一个总页数域（{@code NUMPAGES}），并返回承载域指令的 run。
   *
   * <p>等价于 {@link #addSimpleField(String) addSimpleField("NUMPAGES")}。总页数域在 Word/WPS 打开时
   * 由渲染引擎填充文档总页数。常用于页脚「第 X 页 / 共 Y 页」的「共 Y 页」部分。
   *
   * @return 承载域指令的 run（可继续链式设样式）
   */
  public Run addPageCountField() {
    return addSimpleField("NUMPAGES");
  }

  /**
   * Removes the inline element at the given reading-order index.
   *
   * @param index reading-order index (0-based, into {@link #inlineElements()})
   * @throws IndexOutOfBoundsException if {@code index} is out of range
   */
  public void removeInlineElement(int index) {
    List<IRunElement> modeled = modeledIruns();
    if (index < 0 || index >= modeled.size()) {
      throw new IndexOutOfBoundsException(
          "inline element index "
              + index
              + " out of bounds (paragraph has "
              + modeled.size()
              + " inline elements)");
    }
    IRunElement target = modeled.get(index);
    // POI's removeRun(int) indexes into getRuns(), which holds run + hyperlink-run instances.
    // Locate the target by identity, then drop it at that position.
    List<XWPFRun> poiRuns = delegate.getRuns();
    int pos = -1;
    for (int i = 0; i < poiRuns.size(); i++) {
      if (poiRuns.get(i) == target) {
        pos = i;
        break;
      }
    }
    if (pos < 0) {
      throw new DocxOperationException(
          "Inline element at index " + index + " could not be located for removal", "paragraph");
    }
    delegate.removeRun(pos);
  }

  // ---------- paragraph-level style ----------

  /**
   * Applies a heading level to this paragraph and returns {@code this}. This sets the paragraph's
   * style to the matching built-in heading style ({@code Heading1} … {@code Heading6}).
   *
   * @param level the heading level (not {@code null})
   * @return this paragraph
   * @throws IllegalArgumentException if {@code level} is {@code null}
   */
  public Paragraph heading(HeadingLevel level) {
    Objects.requireNonNull(level, "level");
    delegate.setStyle(Mappers.toStyleId(level));
    return this;
  }

  /**
   * Clears any heading style from this paragraph (restoring it to body/non-heading text) and
   * returns {@code this}.
   *
   * @return this paragraph
   */
  public Paragraph clearHeading() {
    delegate.setStyle(null);
    return this;
  }

  /**
   * Returns this paragraph's heading level, or {@code null} if it is not a heading (including when
   * it carries a non-heading style).
   *
   * @return the heading level, or {@code null} if this is not a heading paragraph
   */
  public HeadingLevel heading() {
    return Mappers.headingFromStyle(delegate.getStyle());
  }

  /**
   * Sets the horizontal alignment and returns {@code this}.
   *
   * @param alignment the alignment (not {@code null})
   * @return this paragraph
   * @throws IllegalArgumentException if {@code alignment} is {@code null}
   */
  public Paragraph alignment(Alignment alignment) {
    Objects.requireNonNull(alignment, "alignment");
    delegate.setAlignment(Mappers.toPoi(alignment));
    return this;
  }

  /**
   * Returns the horizontal alignment. A paragraph with no explicit alignment is reported as {@link
   * Alignment#LEFT} (Word's default).
   *
   * @return the alignment (never {@code null})
   */
  public Alignment alignment() {
    return Mappers.fromPoi(delegate.getAlignment());
  }

  /**
   * 给此段落设置<b>纯色背景填充</b>底纹,并返回 {@code this} 以支持链式调用。
   *
   * <p><b>OOXML</b>:在段落属性 {@code <w:pPr>} 内写 {@code <w:shd w:val="clear" w:fill="...">}。
   *
   * <p><b>WPS/Word 兼容性</b>:本方法<b>强制 {@code w:val="clear"}</b>(纯背景色填充,跨引擎安全), 不暴露 {@code SOLID}(WPS
   * 渲染为黑块,见 {@code renderer-compatibility.md#shading-solid})。若需要其它图案, 使用 {@link
   * #shading(Shading)};若确实需要 SOLID 语义,走 {@link #raw()} 直接操纵 {@code CTShd}。
   *
   * <p>等价于 {@code shading(Shading.of(fill))}。覆盖此段落上已有的底纹。
   *
   * @param fill 背景色(十六进制 RGB 字符串,如 {@code "F1F5F9"},不带 {@code #};不能为 {@code null})
   * @return 此段落(链式)
   * @throws IllegalArgumentException 如果 {@code fill} 为 {@code null}
   */
  public Paragraph shading(String fill) {
    return shading(Shading.of(fill));
  }

  /**
   * 给此段落设置指定的底纹,并返回 {@code this} 以支持链式调用。
   *
   * <p>覆盖此段落上已有的底纹。{@link Shading} 的 {@code ShadingPattern} 枚举已排除 {@code SOLID}, 故本方法永远不产出 WPS
   * 黑块风险。
   *
   * @param shading 底纹值对象(不能为 {@code null})
   * @return 此段落(链式)
   * @throws IllegalArgumentException 如果 {@code shading} 为 {@code null}
   */
  public Paragraph shading(Shading shading) {
    Objects.requireNonNull(shading, "shading");
    ShadingNodes.applyToParagraph(delegate.getCTP(), shading);
    return this;
  }

  /**
   * 返回此段落的底纹。
   *
   * <p>每次访问都从委托重新读取。读取时 OOXML 中未在 nondocx 建模的图案(各种条纹/百分比/SOLID)归并为 {@code NIL};若需保留原始图案细节,走 {@link
   * #raw()} 直接读 {@code CTShd}。
   *
   * @return 底纹值对象;若未设底纹则返回 {@code null}
   */
  public Shading shading() {
    return ShadingNodes.readFromParagraph(delegate.getCTP());
  }

  /**
   * 移除此段落的底纹,并返回 {@code this} 以支持链式调用。
   *
   * <p>若未设底纹则无操作。
   *
   * @return 此段落(链式)
   */
  public Paragraph removeShading() {
    ShadingNodes.removeFromParagraph(delegate.getCTP());
    return this;
  }

  /**
   * Sets the left and first-line indentation (in twips, 1/20 of a point) and returns {@code this}.
   *
   * @param leftTwips the left indentation in twips
   * @param firstLineTwips the first-line indentation in twips (may be negative for a hanging
   *     indent)
   * @return this paragraph
   */
  public Paragraph indent(int leftTwips, int firstLineTwips) {
    delegate.setIndentationLeft(leftTwips);
    delegate.setIndentationFirstLine(firstLineTwips);
    return this;
  }

  /**
   * Returns the left indentation in twips, as stored on this paragraph.
   *
   * @return the left indentation in twips
   */
  public int indentationLeft() {
    return delegate.getIndentationLeft();
  }

  /**
   * Returns the first-line indentation in twips, as stored on this paragraph.
   *
   * @return the first-line indentation in twips
   */
  public int indentationFirstLine() {
    return delegate.getIndentationFirstLine();
  }

  /**
   * Sets the line spacing as a multiple of single-line height and returns {@code this}. For
   * example, {@code 1.0} is single spacing, {@code 1.5} is 1.5 lines, and {@code 2.0} is double.
   *
   * @param multiple the line spacing as a multiple of single-line height
   * @return this paragraph
   */
  public Paragraph lineSpacing(double multiple) {
    delegate.setSpacingBetween(multiple);
    return this;
  }

  /**
   * Returns the line spacing as a multiple of single-line height, or {@code -1.0} if line spacing
   * is not explicitly set on this paragraph.
   *
   * @return the line spacing multiple, or {@code -1.0} if unset
   */
  public double lineSpacing() {
    return delegate.getSpacingBetween();
  }

  // ---------- list membership ----------

  /**
   * Marks this paragraph as a member of a list of the given kind at the given 0-based nesting
   * level, and returns {@code this}. The level must be in the range {@code 0..8}. All paragraphs in
   * the same list kind share one numbering definition on the document; they differ only by nesting
   * level.
   *
   * @param kind the list kind (not {@code null})
   * @param level the 0-based nesting level ({@code 0..8})
   * @return this paragraph
   * @throws IllegalArgumentException if {@code kind} is {@code null} or {@code level} is out of
   *     range
   * @throws DocxOperationException if this paragraph is not attached to a document
   */
  public Paragraph list(ListKind kind, int level) {
    Objects.requireNonNull(kind, "kind");
    Numbering.apply(delegate, kind, level);
    return this;
  }

  /**
   * Removes list membership from this paragraph and returns {@code this}. After this call {@link
   * #listKind()} and {@link #listLevel()} report {@code null}.
   *
   * @return this paragraph
   */
  public Paragraph clearList() {
    Numbering.clear(delegate);
    return this;
  }

  /**
   * Returns the list kind this paragraph belongs to, or {@code null} if it is not part of any list.
   *
   * @return the list kind, or {@code null} if this paragraph is not a list member
   */
  public ListKind listKind() {
    return Numbering.kindOf(delegate);
  }

  /**
   * Returns the 0-based nesting level of this paragraph within its list, or {@code null} if it is
   * not part of any list. A list paragraph with no explicit level is reported as {@code 0}.
   *
   * @return the nesting level, or {@code null} if this paragraph is not a list member
   */
  public Integer listLevel() {
    return Numbering.levelOf(delegate);
  }

  // ---------- escape hatch ----------

  /**
   * Returns the underlying POI paragraph.
   *
   * <p>Modifications to the returned object affect the document immediately. Use with caution.
   *
   * @return the backing {@code XWPFParagraph} instance (same instance for the wrapper's lifetime)
   */
  public XWPFParagraph raw() {
    return delegate;
  }

  // ---------- content equality ----------

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Paragraph)) {
      return false;
    }
    Paragraph that = (Paragraph) o;
    return java.util.Objects.equals(this.inlineElements(), that.inlineElements())
        && java.util.Objects.equals(this.heading(), that.heading())
        && this.alignment() == that.alignment()
        && this.indentationLeft() == that.indentationLeft()
        && this.indentationFirstLine() == that.indentationFirstLine()
        && Double.doubleToLongBits(this.lineSpacing())
            == Double.doubleToLongBits(that.lineSpacing())
        && java.util.Objects.equals(this.listKind(), that.listKind())
        && java.util.Objects.equals(this.listLevel(), that.listLevel())
        && java.util.Objects.equals(this.shading(), that.shading());
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(
        inlineElements(),
        heading(),
        alignment(),
        indentationLeft(),
        indentationFirstLine(),
        lineSpacing(),
        listKind(),
        listLevel(),
        shading());
  }

  // ---------- internals ----------

  /**
   * Returns the modeled inline elements (runs and hyperlink runs) in reading order, excluding
   * inline constructs nondocx does not model (for example structured document tags). Re-derived on
   * each call so the resulting view stays live.
   */
  private List<IRunElement> modeledIruns() {
    List<IRunElement> modeled = new ArrayList<>();
    for (IRunElement element : delegate.getIRuns()) {
      // XWPFHyperlinkRun extends XWPFRun, so this also catches hyperlink runs and field runs.
      if (element instanceof XWPFRun) {
        modeled.add(element);
      }
    }
    return modeled;
  }

  /**
   * Wraps a POI inline element as a nondocx inline element. {@code XWPFHyperlinkRun} is checked
   * before {@code XWPFRun} because the former subclasses the latter. A plain run that carries an
   * embedded picture is surfaced as an {@link Image}; otherwise it is surfaced as a {@link Run}.
   */
  private static InlineElement wrap(IRunElement element) {
    if (element instanceof XWPFHyperlinkRun) {
      return new Hyperlink((XWPFHyperlinkRun) element);
    }
    if (element instanceof XWPFRun) {
      XWPFRun run = (XWPFRun) element;
      if (!run.getEmbeddedPictures().isEmpty()) {
        return new Image(run.getEmbeddedPictures().get(0));
      }
    }
    return new Run((XWPFRun) element);
  }
}
