package com.non.docx.core.api;

import com.non.docx.core.api.exception.DocxIOException;
import com.non.docx.core.api.section.Section;
import com.non.docx.core.api.table.Table;
import com.non.docx.core.api.text.Paragraph;
import com.non.docx.core.internal.util.Objects;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.xmlbeans.XmlCursor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;

/**
 * A live, mutable docx document wrapping an Apache POI {@code XWPFDocument}.
 *
 * <p>A {@code Document} holds a single {@code XWPFDocument} delegate and exposes a domain view over
 * it. All reads go straight through to the delegate (there is no cached snapshot), and every
 * mutation is write-through — the underlying POI document changes immediately.
 *
 * <p>The <em>structural source of truth</em> is the ordered body: {@link #bodyElements()} returns
 * paragraphs and tables in their true Word-body order. The {@link #paragraphs()} and {@link
 * #tables()} views are type-filtered projections of that order. Paragraph and table indices used by
 * {@link #paragraph(int)} / {@link #removeParagraph(int)} / {@link #tables()} refer to the filtered
 * views, while {@link #insertParagraph(int)} takes a body-order index to stay unambiguous when
 * paragraphs and tables are interleaved.
 *
 * <p><b>Sections ({@code sections()}, {@code section(int)}) are wired and cover page properties
 * (paper size, orientation, margins) plus section-scoped {@link
 * com.non.docx.core.api.header.Header} / {@link com.non.docx.core.api.header.Footer} accessors.
 * {@link #header()} and {@link #footer()} are conveniences that return the first section's default
 * header / footer.</b>
 *
 * <p>{@code Document} implements {@link AutoCloseable}; closing it releases the underlying POI
 * resources.
 *
 * <p><b>Content equality.</b> {@code equals} / {@code hashCode} compare content derived from the
 * delegate — the ordered {@link #bodyElements()} sequence and the ordered {@link #sections()}
 * sequence — and never the delegate reference itself. Two documents are equal when their body
 * element sequence (paragraphs and tables in true Word-body order) and their section sequence (page
 * properties plus section-scoped default header / footer content) are element-by-element
 * content-equal. This is exactly what round-trip assertions need: a document saved then reopened is
 * necessarily backed by a different {@code XWPFDocument} instance yet compares equal to the
 * original. {@code Document} is a mutable live object; {@code equals} / {@code hashCode} serve
 * comparison and testing and are not suited as a long-lived {@code HashMap} key, since the
 * underlying content can change at any time.
 */
public final class Document implements AutoCloseable {

  private final XWPFDocument delegate;

  /**
   * 封装给定的 POI 文档。
   *
   * <p>此构造函数是 {@code Docx} 工厂生成文档实例的内部接缝， 因此它有意接受 POI 类型。用户通过 {@code Docx.open(...)} / {@code
   * Docx.create()} 获取文档， 而不是直接构造它们。
   *
   * @param delegate 底层的 POI 文档（不能为 {@code null}）
   * @throws IllegalArgumentException 如果 {@code delegate} 为 {@code null}
   */
  public Document(XWPFDocument delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  // ---------- ordered body view (structural source of truth) ----------

  /**
   * 返回文档正文的活跃视图，按真实 Word 正文顺序排列。
   *
   * <p>返回的列表仅包含 nondocx 建模的正文结构 — 段落和表格 — 保留它们的相对顺序。其他正文结构（例如结构化文档标签） 被排除在外；它们仍可通过 {@code
   * raw().getBodyElements()} 访问。每次访问时 都会从委托重新读取视图，因此变更会实时反映。
   *
   * @return 按文档顺序排列的活跃、不可修改的正文元素列表
   */
  public List<BodyElement> bodyElements() {
    return new AbstractList<BodyElement>() {
      @Override
      public BodyElement get(int index) {
        return wrap(modeledBody().get(index));
      }

      @Override
      public int size() {
        return modeledBody().size();
      }
    };
  }

  /**
   * 返回指定正文顺序索引处的正文元素。
   *
   * @param index 正文顺序索引（从 0 开始，指向 {@link #bodyElements()}）
   * @return 该位置的正文元素
   * @throws IndexOutOfBoundsException 如果 {@code index} 超出范围
   */
  public BodyElement bodyElement(int index) {
    return bodyElements().get(index);
  }

  // ---------- paragraph views ----------

  /**
   * 返回正文段落的活跃、类型筛选视图（按文档顺序）。
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
   * 返回指定筛选段落索引处的段落。
   *
   * @param index 段落索引（从 0 开始，指向 {@link #paragraphs()}）
   * @return 该位置的段落
   * @throws IndexOutOfBoundsException 如果 {@code index} 超出范围
   */
  public Paragraph paragraph(int index) {
    return paragraphs().get(index);
  }

  /**
   * 在正文末尾追加一个新的空段落，并返回其活跃包装器。
   *
   * @return 新追加的段落
   */
  public Paragraph addParagraph() {
    return new Paragraph(delegate.createParagraph());
  }

  /**
   * Appends a new paragraph containing the given text and returns a live wrapper for it. This is a
   * convenience for {@code addParagraph()} followed by adding a run with the text.
   *
   * @param text the paragraph text (not {@code null})
   * @return the newly appended paragraph
   * @throws IllegalArgumentException if {@code text} is {@code null}
   */
  public Paragraph addParagraph(String text) {
    Objects.requireNonNull(text, "text");
    XWPFParagraph paragraph = delegate.createParagraph();
    paragraph.createRun().setText(text);
    return new Paragraph(paragraph);
  }

  /**
   * Inserts a new, empty paragraph at the given body-order index and returns a live wrapper for it.
   * The new paragraph takes that index, shifting the element previously there (and all later
   * elements) one position toward the end.
   *
   * @param bodyIndex body-order index (0-based, into {@link #bodyElements()}); {@code size()}
   *     appends at the end
   * @return the newly inserted paragraph
   * @throws IndexOutOfBoundsException if {@code bodyIndex < 0} or {@code bodyIndex > size()}
   */
  public Paragraph insertParagraph(int bodyIndex) {
    List<IBodyElement> modeled = modeledBody();
    if (bodyIndex < 0 || bodyIndex > modeled.size()) {
      throw new IndexOutOfBoundsException(
          "bodyIndex "
              + bodyIndex
              + " out of bounds (body has "
              + modeled.size()
              + " modeled elements)");
    }
    if (bodyIndex == modeled.size()) {
      return new Paragraph(delegate.createParagraph());
    }
    IBodyElement target = modeled.get(bodyIndex);
    XmlCursor cursor;
    if (target instanceof XWPFParagraph) {
      cursor = ((XWPFParagraph) target).getCTP().newCursor();
    } else if (target instanceof XWPFTable) {
      cursor = ((XWPFTable) target).getCTTbl().newCursor();
    } else {
      throw new IllegalArgumentException("无法在类型为 " + target.getClass().getName() + " 的正文元素前插入");
    }
    try {
      return new Paragraph(delegate.insertNewParagraph(cursor));
    } finally {
      cursor.dispose();
    }
  }

  /**
   * Removes the paragraph at the given filtered paragraph index.
   *
   * @param paragraphIndex paragraph index (0-based, into {@link #paragraphs()})
   * @throws IndexOutOfBoundsException if {@code paragraphIndex} is out of range
   */
  public void removeParagraph(int paragraphIndex) {
    List<XWPFParagraph> paragraphs = delegate.getParagraphs();
    if (paragraphIndex < 0 || paragraphIndex >= paragraphs.size()) {
      throw new IndexOutOfBoundsException(
          "paragraphIndex "
              + paragraphIndex
              + " out of bounds (document has "
              + paragraphs.size()
              + " paragraphs)");
    }
    XWPFParagraph target = paragraphs.get(paragraphIndex);
    int bodyPos = delegate.getPosOfParagraph(target);
    delegate.removeBodyElement(bodyPos);
  }

  // ---------- table views ----------

  /**
   * Returns a live, type-filtered view of the body tables (in document order).
   *
   * @return a live, unmodifiable list of tables
   */
  public List<Table> tables() {
    return new AbstractList<Table>() {
      private final List<XWPFTable> backing = delegate.getTables();

      @Override
      public Table get(int index) {
        return new Table(backing.get(index));
      }

      @Override
      public int size() {
        return backing.size();
      }
    };
  }

  /**
   * Appends a new, empty table at the end of the body and returns a live wrapper for it.
   *
   * @return the newly appended table
   */
  public Table addTable() {
    XWPFTable created = delegate.createTable();
    // POI pre-populates a new table with a default row; clear it so addTable() yields a
    // genuinely empty table, matching nondocx's addX-exactly-one semantics (addParagraph /
    // addRun add exactly one element with no phantom defaults).
    while (created.getRows().size() > 0) {
      created.removeRow(0);
    }
    return new Table(created);
  }

  // ---------- section views ----------

  /**
   * Returns a live view of the document's sections, in document order.
   *
   * <p>Each mid-document section break is stored on the paragraph that ends the section (its {@code
   * <w:pPr>/<w:sectPr>}); the document body's final {@code <w:sectPr>} is the last section. This
   * view walks the body in order, yielding one {@link Section} per section break followed by the
   * final body section. A document therefore always has at least one section — the body section is
   * created on first access if absent.
   *
   * <p>The returned list re-reads the underlying {@code XWPFDocument} on every access, so mutations
   * (including those made via {@code raw()}) are reflected live.
   *
   * @return a live, unmodifiable list of sections in document order (never empty)
   */
  public List<Section> sections() {
    return new AbstractList<Section>() {
      @Override
      public Section get(int index) {
        List<CTSectPr> sectPrs = resolveSectionProperties();
        if (index < 0 || index >= sectPrs.size()) {
          throw new IndexOutOfBoundsException(
              "section index "
                  + index
                  + " out of bounds (document has "
                  + sectPrs.size()
                  + " sections)");
        }
        return new Section(delegate, sectPrs.get(index));
      }

      @Override
      public int size() {
        return resolveSectionProperties().size();
      }
    };
  }

  /**
   * 返回指定文档顺序索引处的章节。
   *
   * @param index 章节索引（从 0 开始，指向 {@link #sections()}）
   * @return 该位置的章节
   * @throws IndexOutOfBoundsException 如果 {@code index} 超出范围
   */
  public Section section(int index) {
    return sections().get(index);
  }

  // ---------- header / footer (convenience) ----------

  /**
   * 以只读方式返回文档第一个章节（章节 0）的默认（奇数页）页眉；不存在时返回 {@code null}，绝不创建。
   *
   * <p>这是 {@code section(0).header()} 的便捷方法，遵循读写分离：只读遍历用本方法，写入用 {@link #ensureHeader()}。详见 {@link
   * Section#header()}。
   *
   * @return 第一个章节的默认页眉，不存在则返回 {@code null}
   */
  public com.non.docx.core.api.header.Header header() {
    return section(0).header();
  }

  /**
   * 显式确保第一个章节存在默认页眉（不存在则创建），返回它。便捷委托 {@code section(0).ensureHeader()}。
   *
   * @return 第一个章节的默认页眉（从不返回 {@code null}）
   * @throws com.non.docx.core.api.exception.DocxIOException 如果页眉部分无法创建或附加
   */
  public com.non.docx.core.api.header.Header ensureHeader() {
    return section(0).ensureHeader();
  }

  /**
   * 以只读方式返回文档第一个章节（章节 0）的默认（奇数页）页脚；不存在时返回 {@code null}，绝不创建。
   *
   * <p>语义与 {@link #header()} 对称：只读遍历用本方法，写入用 {@link #ensureFooter()}。
   *
   * @return 第一个章节的默认页脚，不存在则返回 {@code null}
   */
  public com.non.docx.core.api.header.Footer footer() {
    return section(0).footer();
  }

  /**
   * 显式确保第一个章节存在默认页脚（不存在则创建），返回它。便捷委托 {@code section(0).ensureFooter()}。
   *
   * @return 第一个章节的默认页脚（从不返回 {@code null}）
   * @throws com.non.docx.core.api.exception.DocxIOException 如果页脚部分无法创建或附加
   */
  public com.non.docx.core.api.header.Footer ensureFooter() {
    return section(0).ensureFooter();
  }

  // ---------- 目录（TOC，只读） ----------

  /**
   * 以只读方式返回文档首个目录（Table of Contents）；不存在时返回 {@code null}，绝不创建。
   *
   * <p>目录在 OOXML 里不是一个独立元素，而是正文里的一个<b>域</b>（由 {@code fldChar} 界定）。POI 没有 {@code XWPFToc} 高级
   * API，nondocx 把「找域 → 解析条目」收进 {@code internal/poi/TocFields}，对外只暴露 {@link
   * com.non.docx.core.api.toc.TableOfContents} / {@link com.non.docx.core.api.toc.TocEntry} 两个干净类型。
   * 详见 {@link com.non.docx.core.api.toc.TableOfContents} 的三层说明与 poi-bridge.md N11。
   *
   * <p><b>只读。</b> 创建/刷新目录（需 Word 分页引擎计算页码）超出范围，属 {@code raw()} 范畴。本方法不动文档。
   *
   * <p><b>多 TOC 文档。</b> v1 只取首个 TOC 域；一份文档里有多个目录（罕见）时，后续的不可见，需走 {@code raw()}。
   *
   * <p><b>不参与 {@code Document.equals}。</b> {@code equals} 比较的是 {@link #bodyElements()}(段落 + 表格)与
   * {@link #sections()} 序列,TOC 不单独纳入。域形态的 TOC 嵌在正文段落里,其 run/超链接已隐式计入 body 相等性; SDT 形态的 TOC 是 {@code
   * <w:sdt>} 容器,目前不在 {@code bodyElements()} 的建模范围内(仅段落/表格),故 SDT 形态 的 TOC 内容<b>不参与</b> {@code
   * equals}——这是已知的不对称(读得到但比较不到),如需对 SDT-TOC 做往返断言,直接比较 {@code toc().entries()}。
   *
   * @return 首个目录，不存在则返回 {@code null}
   */
  public com.non.docx.core.api.toc.TableOfContents toc() {
    return com.non.docx.core.internal.poi.TocFields.findToc(delegate).isPresent()
        ? new com.non.docx.core.api.toc.TableOfContents(delegate)
        : null;
  }

  // ---------- 修订（tracked changes，只读） ----------

  /**
   * 返回文档的修订(tracked changes)能力门面 —— 一个<b>只读</b>视图。
   *
   * <p>这是 nondocx 对 tracked changes 的统一入口(无论后续 accept/reject、authoring 等子任务如何演进,读取入口都集中在此)。
   * 门面提供三件只读事:是否开启修订记录、按文档顺序枚举修订、按稳定 id 获取单条修订。
   *
   * <p><b>OOXML / POI / nondocx 三层。</b>
   *
   * <ul>
   *   <li><b>OOXML</b>:开关在 {@code word/settings.xml} 的 {@code <w:trackChanges/>};修订标记散落在 {@code
   *       word/document.xml} 正文各处,如 {@code <w:ins>} / {@code <w:del>}。
   *   <li><b>POI</b>:没有 {@code XWPFTrackedChanges} 高级 API。开关需读 {@code CTSettings};修订节点需用 {@code
   *       XmlCursor} 按文档顺序遍历 body 树。
   *   <li><b>nondocx</b>:把这两件脏活收进 {@code internal/poi/TrackedChangeNodes},对外只暴露 {@link
   *       com.non.docx.core.api.track.TrackedChanges} / {@link
   *       com.non.docx.core.api.track.TrackedChange} 等干净类型。
   * </ul>
   *
   * <p><b>与 {@code toc()} 的区别。</b> {@code toc()} 在文档无 TOC 时返回 {@code null};而 tracked changes 的「开关」与
   * 「列表」总是有意义的(即便关闭、即便列表为空),故本方法<b>总是返回非 null</b>门面对象,不返回 {@code null}。
   *
   * <p><b>只读。</b> 开关写入、accept/reject 均不属于当前 read 子任务。
   *
   * @return 修订能力门面(从不为 {@code null})
   */
  public com.non.docx.core.api.track.TrackedChanges trackedChanges() {
    return new com.non.docx.core.api.track.TrackedChanges(delegate);
  }

  // ---------- save ----------

  /**
   * 将此文档写入指定文件。文件流由此方法打开和关闭。
   *
   * @param file 目标文件（不能为 {@code null}）
   * @throws DocxIOException 如果文件无法写入
   * @throws IllegalArgumentException 如果 {@code file} 为 {@code null}
   */
  public void save(File file) {
    Objects.requireNonNull(file, "file");
    save(file.toPath());
  }

  /**
   * 将此文档写入指定路径。文件流由此方法打开和关闭。
   *
   * @param path 目标路径（不能为 {@code null}）
   * @throws DocxIOException 如果文件无法写入
   * @throws IllegalArgumentException 如果 {@code path} 为 {@code null}
   */
  public void save(Path path) {
    Objects.requireNonNull(path, "path");
    try (OutputStream out = Files.newOutputStream(path)) {
      writeTo(out, path.toString());
    } catch (IOException e) {
      throw new DocxIOException("无法将文档保存到 " + path, e);
    }
  }

  /**
   * 将此文档写入指定流。该流 <em>不</em> 被关闭；调用者保留所有权。
   *
   * @param out 目标流（不能为 {@code null}，不会被此方法关闭）
   * @throws DocxIOException 如果文档无法序列化
   * @throws IllegalArgumentException 如果 {@code out} 为 {@code null}
   */
  public void save(OutputStream out) {
    Objects.requireNonNull(out, "out");
    writeTo(out, null);
  }

  private void writeTo(OutputStream out, String context) {
    try {
      delegate.write(out);
    } catch (IOException | POIXMLException e) {
      String message = "无法保存文档" + (context == null ? "" : " 到 " + context);
      throw new DocxIOException(message, e);
    }
  }

  // ---------- lifecycle ----------

  /**
   * 释放底层 POI 资源。
   *
   * @throws DocxIOException 如果关闭底层文档失败
   */
  @Override
  public void close() {
    try {
      delegate.close();
    } catch (IOException e) {
      throw new DocxIOException("无法关闭文档", e);
    }
  }

  // ---------- escape hatch ----------

  /**
   * Returns the underlying POI document.
   *
   * <p>Modifications to the returned object affect the document immediately. Use with caution.
   *
   * @return the backing {@code XWPFDocument} instance (same instance for the wrapper's lifetime)
   */
  public XWPFDocument raw() {
    return delegate;
  }

  // ---------- content equality ----------

  /**
   * Compares this document to another for content equality.
   *
   * <p>Two documents are equal when their ordered body element sequences (paragraphs and tables in
   * true Word-body order) are element-by-element content-equal <em>and</em> their ordered section
   * sequences (page properties plus section-scoped default header / footer content) are
   * element-by-element content-equal. The comparison is driven entirely by the content views {@link
   * #bodyElements()} and {@link #sections()} and never touches the backing {@code XWPFDocument}
   * reference, so a document saved then reopened — necessarily backed by a different {@code
   * XWPFDocument} instance — compares equal to the original. This is the round-trip fidelity
   * contract.
   *
   * <p>{@code Document} is a mutable live object; this method and {@link #hashCode()} serve
   * comparison and testing and are not suited as a long-lived {@code HashMap} key, since the
   * underlying content can change at any time.
   *
   * @param o the object to compare against
   * @return {@code true} if {@code o} is a {@code Document} with equal body and section content
   */
  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Document)) {
      return false;
    }
    Document that = (Document) o;
    return java.util.Objects.equals(this.bodyElements(), that.bodyElements())
        && java.util.Objects.equals(this.sections(), that.sections());
  }

  /**
   * Returns a content-based hash code for this document, consistent with {@link #equals(Object)}.
   *
   * @return the hash code over the body element and section sequences
   */
  @Override
  public int hashCode() {
    return java.util.Objects.hash(bodyElements(), sections());
  }

  @Override
  public String toString() {
    return "Document{bodyElements=" + bodyElements().size() + '}';
  }

  // ---------- internals ----------

  /**
   * Returns the modeled body elements (paragraphs and tables) in true Word-body order, excluding
   * body constructs nondocx does not yet model (for example structured document tags). Re-derived
   * on each call so the resulting view stays live.
   */
  private List<IBodyElement> modeledBody() {
    List<IBodyElement> modeled = new ArrayList<>();
    for (IBodyElement element : delegate.getBodyElements()) {
      if (element instanceof XWPFParagraph || element instanceof XWPFTable) {
        modeled.add(element);
      }
    }
    return modeled;
  }

  /**
   * Collects the document's section-property elements in true document order: first every
   * paragraph-level {@code <w:sectPr>} (each marks where a mid-document section ends), then the
   * body's final {@code <w:sectPr>} as the last section. The body section is created on demand if
   * absent, so the returned list is never empty and the document always exposes at least one
   * section. Re-derived on every call so the {@code sections()} view stays live.
   */
  private List<CTSectPr> resolveSectionProperties() {
    List<CTSectPr> sectPrs = new ArrayList<>();
    for (XWPFParagraph paragraph : delegate.getParagraphs()) {
      CTPPr pPr = paragraph.getCTP().getPPr();
      if (pPr != null && pPr.isSetSectPr()) {
        sectPrs.add(pPr.getSectPr());
      }
    }
    CTBody body = delegate.getDocument().getBody();
    sectPrs.add(body.isSetSectPr() ? body.getSectPr() : body.addNewSectPr());
    return sectPrs;
  }

  private static BodyElement wrap(IBodyElement element) {
    if (element instanceof XWPFParagraph) {
      return new Paragraph((XWPFParagraph) element);
    }
    if (element instanceof XWPFTable) {
      return new Table((XWPFTable) element);
    }
    throw new IllegalArgumentException("不支持的正文元素类型：" + element.getClass().getName());
  }
}
