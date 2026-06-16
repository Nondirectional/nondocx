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
 * Internal API — subject to change without notice.
 *
 * <p>Bridge between nondocx's POI-free {@link ListKind} and Apache POI's OOXML numbering machinery.
 * This is the only place that builds {@code CTAbstractNum}/{@code CTNum} definitions and reads list
 * membership back off an {@code XWPFParagraph}, so the public value object {@code ListKind} and the
 * {@code Paragraph} wrapper stay free of {@code org.apache.poi.*} at the source level.
 *
 * <p>List membership is modeled as the pair (list kind, nesting level 0..8). Each kind maps to a
 * single {@code abstractNum} (with nine {@code CTLvl} entries) and a single {@code num} per
 * document; all paragraphs of the same kind share that num and differ only by their {@code ilvl}.
 * The {@code numId} for each (document, kind) is cached so repeated {@code list(...)} calls never
 * create duplicate definitions on the same document.
 *
 * <p><b>Cache scope and lifecycle:</b> the cache keys on the {@code XWPFDocument} instance itself
 * (identity semantics). It intentionally holds a strong reference to the document for the JVM
 * lifetime; this is acceptable for an MVP library, but means documents should not be expected to be
 * garbage-collected while the cache retains them. After a {@code save} → {@code open} round trip
 * the freshly opened document is a different {@code XWPFDocument} instance, so its list definitions
 * are re-created on demand the first time {@code list(...)} is applied again; this is harmless
 * because paragraphs keep resolving correctly via the numId stored in the file.
 */
public final class Numbering {

  /** Maximum nesting level supported by a single abstractNum (ilvl 0..8 → nine levels). */
  private static final int MAX_LEVEL = 8;

  /**
   * Bullet glyphs used per nesting level for {@link ListKind#BULLET} abstractNums. Repeats after
   * four levels to cover all nine ilvl entries.
   */
  private static final String[] BULLET_GLYPHS = {
    "\u2022", // • level 0
    "\u25CB", // ○ level 1
    "\u25AA", // ▪ level 2
    "\u00B7", // · level 3
    "\u2022", // • level 4
    "\u25CB", // ○ level 5
    "\u25AA", // ▪ level 6
    "\u00B7", // · level 7
    "\u2022" // • level 8
  };

  /**
   * Per-document cache of the {@code numId} provisioned for each {@link ListKind}. Keyed by {@code
   * XWPFDocument} identity so the same document does not get duplicate numbering definitions across
   * repeated {@code list(...)} calls.
   */
  private static final Map<XWPFDocument, Map<ListKind, BigInteger>> NUM_ID_CACHE =
      new IdentityHashMap<>();

  private Numbering() {}

  /**
   * Marks the given paragraph as a member of a list of the specified kind at the specified nesting
   * level. Ensures the owning document has a numbering part and provisions a shared abstractNum/num
   * for the kind (cached), then assigns the paragraph's {@code numId} and {@code ilvl}.
   *
   * @param paragraph the POI paragraph to update (not {@code null})
   * @param kind the list kind (not {@code null})
   * @param level the 0-based nesting level, in the range {@code 0..8}
   * @throws IllegalArgumentException if {@code kind} is {@code null} or {@code level} is out of
   *     range
   * @throws DocxOperationException if the paragraph is not attached to a document
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
   * Removes list membership from the given paragraph by unsetting its {@code <w:numPr/>} element
   * entirely. This is stronger than {@link XWPFParagraph#setNumID(BigInteger) setNumID(null)},
   * which leaves an empty {@code numId} that XmlBeans rejects as an invalid integer on the next
   * save/open round trip. After this call {@link XWPFParagraph#getNumID()} reports {@code null}, so
   * the paragraph reads as a non-list paragraph.
   *
   * @param paragraph the POI paragraph to clear (not {@code null})
   */
  public static void clear(XWPFParagraph paragraph) {
    CTPPr pPr = paragraph.getCTP().getPPr();
    if (pPr != null && pPr.isSetNumPr()) {
      pPr.unsetNumPr();
    }
  }

  /**
   * Reads the list kind of the given paragraph, or {@code null} if it is not a list member.
   *
   * <p>The kind is inferred from the paragraph's current-level number format as reported by POI: a
   * {@code "bullet"} format means {@link ListKind#BULLET}; any other format (decimal, letter,
   * roman, …) collapses to {@link ListKind#NUMBERED}. A paragraph with no {@code numId} is reported
   * as {@code null}.
   *
   * @param paragraph the POI paragraph to inspect (not {@code null})
   * @return the list kind, or {@code null} if the paragraph is not a list member
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
   * Reads the 0-based nesting level of the given paragraph, or {@code null} if it is not a list
   * member. A list paragraph with no explicit {@code ilvl} is reported as level {@code 0}.
   *
   * @param paragraph the POI paragraph to inspect (not {@code null})
   * @return the nesting level, or {@code null} if the paragraph is not a list member
   */
  public static Integer levelOf(XWPFParagraph paragraph) {
    if (paragraph.getNumID() == null) {
      return null;
    }
    BigInteger ilvl = paragraph.getNumIlvl();
    return ilvl == null ? 0 : ilvl.intValue();
  }

  // ---------- internals ----------

  /**
   * Returns the {@code numId} for the given (document, kind), provisioning a new abstractNum+num on
   * first access and serving it from the cache afterwards.
   */
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
   * Builds and registers an abstractNum carrying nine levels for the given kind, returning its
   * {@code abstractNumId}. The id is pre-computed to be unique among existing abstractNums so it
   * survives POI's copy-through add path without collision.
   */
  private static BigInteger createAbstractNum(XWPFNumbering numbering, ListKind kind) {
    CTAbstractNum ct = CTAbstractNum.Factory.newInstance();
    ct.setAbstractNumId(nextAbstractNumId(numbering));
    populateLevels(ct, kind);
    return numbering.addAbstractNum(new XWPFAbstractNum(ct));
  }

  /** Appends nine {@code CTLvl} entries (ilvl 0..8) to the abstractNum for the given kind. */
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
   * Computes the next free abstractNum id for the numbering part as {@code max(existing ids) + 1}
   * (mirroring POI's own {@code findNextAbstractNumberingId}), starting at {@code 1} when the
   * numbering has no abstractNums yet.
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
