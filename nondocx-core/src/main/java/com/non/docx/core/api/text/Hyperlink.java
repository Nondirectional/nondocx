package com.non.docx.core.api.text;

import com.non.docx.core.api.InlineElement;
import com.non.docx.core.internal.util.Objects;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHyperlink;
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun;

/**
 * A hyperlink — an inline fragment whose visible text links to a target (typically an external
 * URL).
 *
 * <p>Holds an Apache POI {@code XWPFHyperlinkRun} delegate (which itself extends {@code XWPFRun})
 * and exposes its display text and target URL. Reads go straight through to the delegate; there is
 * no cached snapshot.
 *
 * <p><b>URL resolution.</b> OOXML stores a hyperlink as a run carrying a relationship id (rId); the
 * actual target lives in the document's relationship part. {@link #url()} follows that rId through
 * the owning document to the external target. The document is reached via the run's own {@code
 * getDocument()} reference, so no extra parameter is needed; if the relationship cannot be resolved
 * (for example the run is detached from its document, or the hyperlink targets an internal anchor
 * rather than a URL), {@code url()} returns {@code null}.
 *
 * <p>Content equality compares the visible text and the resolved URL, never the delegate reference.
 */
public final class Hyperlink implements InlineElement {

  private final XWPFHyperlinkRun delegate;

  /**
   * Wraps the given POI hyperlink run.
   *
   * <p>This constructor is the internal seam by which {@link Paragraph} produces live hyperlink
   * wrappers, so it accepts a POI type by design. Users normally obtain hyperlinks via {@code
   * Paragraph.addHyperlink(...)} rather than constructing them.
   *
   * @param delegate the backing POI hyperlink run (not {@code null})
   * @throws IllegalArgumentException if {@code delegate} is {@code null}
   */
  public Hyperlink(XWPFHyperlinkRun delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /**
   * Returns the hyperlink's visible (display) text.
   *
   * @return the visible text (possibly empty, never {@code null})
   */
  public String text() {
    return delegate.text();
  }

  /**
   * Resolves and returns the hyperlink's target URL, following the run's relationship id to the
   * document's relationship part.
   *
   * @return the target URL, or {@code null} if this hyperlink targets an internal anchor or the
   *     relationship cannot be resolved
   */
  public String url() {
    XWPFDocument document = delegate.getDocument();
    if (document == null) {
      return null;
    }
    XWPFHyperlink link = delegate.getHyperlink(document);
    return link == null ? null : link.getURL();
  }

  /**
   * Returns the underlying POI hyperlink run.
   *
   * <p>Modifications to the returned object affect the document immediately. Use with caution.
   *
   * @return the backing {@code XWPFHyperlinkRun} instance (same instance for the wrapper's
   *     lifetime)
   */
  public XWPFHyperlinkRun raw() {
    return delegate;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Hyperlink)) {
      return false;
    }
    Hyperlink that = (Hyperlink) o;
    return java.util.Objects.equals(this.text(), that.text())
        && java.util.Objects.equals(this.url(), that.url());
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(text(), url());
  }

  @Override
  public String toString() {
    return "Hyperlink{text=" + text() + ", url=" + url() + '}';
  }
}
