package io.github.nondirectional.docx.toolkit.ref;

import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.header.Footer;
import io.github.nondirectional.docx.core.api.header.Header;
import io.github.nondirectional.docx.core.api.header.HeaderFooterVariant;
import io.github.nondirectional.docx.core.api.section.Section;
import io.github.nondirectional.docx.core.api.table.Cell;
import io.github.nondirectional.docx.core.api.table.Table;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import io.github.nondirectional.docx.core.api.text.Run;
import io.github.nondirectional.docx.core.api.track.TrackedChange;
import io.github.nondirectional.docx.core.internal.poi.ParagraphIds;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 当前活文档的元素引用解析器。
 *
 * <p>SESSION 引用绑定底层 delegate identity，但解析时仍重新扫描当前文档树确认目标存在。 PERSISTENT 段落引用按已有 {@code w14:paraId}
 * 重新定位。
 */
public final class ElementResolver {

  private final DocumentRef documentRef;
  private final Document document;
  private final AtomicLong sequence = new AtomicLong();
  private final IdentityHashMap<Object, String> idByIdentity = new IdentityHashMap<>();
  private final Map<String, RegisteredElement> registeredById = new HashMap<>();

  public ElementResolver(DocumentRef documentRef, Document document) {
    this.documentRef = Objects.requireNonNull(documentRef, "documentRef 不能为空");
    this.document = Objects.requireNonNull(document, "document 不能为空");
  }

  public DocumentRef documentRef() {
    return documentRef;
  }

  /** 为当前活段落签发引用。读取路径不会补写 paraId。 */
  public ParagraphRef reference(Paragraph paragraph) {
    Objects.requireNonNull(paragraph, "paragraph 不能为空");
    String paraId = ParagraphIds.read(paragraph.raw());
    if (paraId != null) {
      return ParagraphRef.persistent(documentRef, paraId);
    }
    return ParagraphRef.session(documentRef, register(ElementKind.PARAGRAPH, paragraph.raw(), "p"));
  }

  /** 为当前活表格签发 SESSION 引用。 */
  public TableRef reference(Table table) {
    Objects.requireNonNull(table, "table 不能为空");
    return TableRef.session(documentRef, register(ElementKind.TABLE, table.raw(), "t"));
  }

  /** 为当前活 run 签发 SESSION 引用。 */
  public RunRef reference(Run run) {
    Objects.requireNonNull(run, "run 不能为空");
    return new RunRef(documentRef, RefStability.SESSION, register(ElementKind.RUN, run.raw(), "r"));
  }

  /** 为当前活单元格签发 SESSION 引用。 */
  public CellRef reference(Cell cell) {
    Objects.requireNonNull(cell, "cell 不能为空");
    return new CellRef(
        documentRef, RefStability.SESSION, register(ElementKind.CELL, cell.raw(), "c"));
  }

  /** 为当前页眉签发 SESSION 引用。 */
  public HeaderFooterRef reference(Header header) {
    Objects.requireNonNull(header, "header 不能为空");
    return new HeaderFooterRef(
        documentRef, RefStability.SESSION, register(ElementKind.HEADER_FOOTER, header.raw(), "hf"));
  }

  /** 为当前页脚签发 SESSION 引用。 */
  public HeaderFooterRef reference(Footer footer) {
    Objects.requireNonNull(footer, "footer 不能为空");
    return new HeaderFooterRef(
        documentRef, RefStability.SESSION, register(ElementKind.HEADER_FOOTER, footer.raw(), "hf"));
  }

  /** 为当前修订签发 SESSION 引用。 */
  public RevisionRef reference(TrackedChange change) {
    Objects.requireNonNull(change, "change 不能为空");
    String id = "rev-" + change.id();
    registeredById.putIfAbsent(id, new RegisteredElement(ElementKind.REVISION, change.id()));
    return new RevisionRef(documentRef, RefStability.SESSION, id);
  }

  /** 解析段落引用。 */
  public Paragraph resolve(ParagraphRef ref) {
    validateDocument(ref);
    if (ref.stability() == RefStability.PERSISTENT) {
      return resolvePersistentParagraph(ref);
    }
    validateGeneration(ref);
    RegisteredElement registered = requireRegistered(ref);
    for (Paragraph paragraph : document.paragraphs()) {
      if (paragraph.raw() == registered.identity) {
        return paragraph;
      }
    }
    throw error(RefResolutionCode.ELEMENT_REMOVED, "段落引用 " + ref.canonical() + " 的目标已从文档中删除");
  }

  /** 解析表格引用。 */
  public Table resolve(TableRef ref) {
    validateDocument(ref);
    validateGeneration(ref);
    RegisteredElement registered = requireRegistered(ref);
    for (Table table : document.tables()) {
      if (table.raw() == registered.identity) {
        return table;
      }
    }
    throw error(RefResolutionCode.ELEMENT_REMOVED, "表格引用 " + ref.canonical() + " 的目标已从文档中删除");
  }

  /** 解析 run 引用。 */
  public Run resolve(RunRef ref) {
    validateDocument(ref);
    validateGeneration(ref);
    RegisteredElement registered = requireRegistered(ref);
    for (Paragraph paragraph : allParagraphs()) {
      for (Run run : paragraph.runs()) {
        if (run.raw() == registered.identity) {
          return run;
        }
      }
    }
    throw elementRemoved("run", ref);
  }

  /** 解析单元格引用。 */
  public Cell resolve(CellRef ref) {
    validateDocument(ref);
    validateGeneration(ref);
    RegisteredElement registered = requireRegistered(ref);
    for (Table table : document.tables()) {
      for (var row : table.rows()) {
        for (Cell cell : row.cells()) {
          if (cell.raw() == registered.identity) {
            return cell;
          }
        }
      }
    }
    throw elementRemoved("单元格", ref);
  }

  /** 解析页眉引用。 */
  public Header resolveHeader(HeaderFooterRef ref) {
    validateDocument(ref);
    validateGeneration(ref);
    RegisteredElement registered = requireRegistered(ref);
    for (Section section : document.sections()) {
      for (HeaderFooterVariant variant : HeaderFooterVariant.values()) {
        Header header = section.header(variant);
        if (header != null && header.raw() == registered.identity) {
          return header;
        }
      }
    }
    throw elementRemoved("页眉", ref);
  }

  /** 解析页脚引用。 */
  public Footer resolveFooter(HeaderFooterRef ref) {
    validateDocument(ref);
    validateGeneration(ref);
    RegisteredElement registered = requireRegistered(ref);
    for (Section section : document.sections()) {
      for (HeaderFooterVariant variant : HeaderFooterVariant.values()) {
        Footer footer = section.footer(variant);
        if (footer != null && footer.raw() == registered.identity) {
          return footer;
        }
      }
    }
    throw elementRemoved("页脚", ref);
  }

  /** 解析修订引用。 */
  public TrackedChange resolve(RevisionRef ref) {
    validateDocument(ref);
    validateGeneration(ref);
    RegisteredElement registered = requireRegistered(ref);
    String changeId = String.valueOf(registered.identity);
    try {
      return document.trackedChanges().get(changeId);
    } catch (NoSuchElementException e) {
      throw elementRemoved("修订", ref);
    }
  }

  private Paragraph resolvePersistentParagraph(ParagraphRef ref) {
    Paragraph match = null;
    for (Paragraph paragraph : document.paragraphs()) {
      if (!ref.elementId().equals(ParagraphIds.read(paragraph.raw()))) {
        continue;
      }
      if (match != null) {
        throw error(RefResolutionCode.STALE_REF, "文档中存在重复 paraId，无法唯一解析段落引用 " + ref.elementId());
      }
      match = paragraph;
    }
    if (match == null) {
      throw error(RefResolutionCode.ELEMENT_REMOVED, "未找到 paraId=" + ref.elementId() + " 的段落");
    }
    return match;
  }

  private String register(ElementKind kind, Object identity, String prefix) {
    String existing = idByIdentity.get(identity);
    if (existing != null) {
      return existing;
    }
    String id = prefix + "-" + sequence.incrementAndGet();
    idByIdentity.put(identity, id);
    registeredById.put(id, new RegisteredElement(kind, identity));
    return id;
  }

  private List<Paragraph> allParagraphs() {
    List<Paragraph> paragraphs = new ArrayList<>(document.paragraphs());
    for (Table table : document.tables()) {
      for (var row : table.rows()) {
        for (Cell cell : row.cells()) {
          paragraphs.addAll(cell.paragraphs());
        }
      }
    }
    for (Section section : document.sections()) {
      for (HeaderFooterVariant variant : HeaderFooterVariant.values()) {
        Header header = section.header(variant);
        if (header != null) {
          paragraphs.addAll(header.paragraphs());
        }
        Footer footer = section.footer(variant);
        if (footer != null) {
          paragraphs.addAll(footer.paragraphs());
        }
      }
    }
    return paragraphs;
  }

  private void validateDocument(ElementRef ref) {
    Objects.requireNonNull(ref, "ref 不能为空");
    if (!documentRef.documentKey().equals(ref.documentRef().documentKey())) {
      throw error(
          RefResolutionCode.DOCUMENT_MISMATCH,
          "引用属于文档 " + ref.documentRef().documentKey() + "，当前文档为 " + documentRef.documentKey());
    }
  }

  private void validateGeneration(ElementRef ref) {
    if (ref.documentRef().sessionGeneration() != documentRef.sessionGeneration()) {
      throw error(
          RefResolutionCode.GENERATION_MISMATCH,
          "引用来自代次 "
              + ref.documentRef().sessionGeneration()
              + "，当前代次为 "
              + documentRef.sessionGeneration());
    }
  }

  private RegisteredElement requireRegistered(ElementRef ref) {
    RegisteredElement registered = registeredById.get(ref.elementId());
    if (registered == null) {
      throw error(RefResolutionCode.STALE_REF, "当前会话不认识引用 " + ref.canonical());
    }
    if (registered.kind != ref.kind()) {
      throw error(
          RefResolutionCode.REF_TYPE_MISMATCH,
          "引用类型为 " + ref.kind() + "，注册目标类型为 " + registered.kind);
    }
    return registered;
  }

  private static RefResolutionException error(RefResolutionCode code, String message) {
    return new RefResolutionException(code, message);
  }

  private static RefResolutionException elementRemoved(String type, ElementRef ref) {
    return error(RefResolutionCode.ELEMENT_REMOVED, type + "引用 " + ref.canonical() + " 的目标已从文档中删除");
  }

  private static final class RegisteredElement {
    private final ElementKind kind;
    private final Object identity;

    private RegisteredElement(ElementKind kind, Object identity) {
      this.kind = kind;
      this.identity = identity;
    }
  }
}
