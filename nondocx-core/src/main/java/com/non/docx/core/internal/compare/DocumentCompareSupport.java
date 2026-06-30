package com.non.docx.core.internal.compare;

import com.non.docx.core.api.Document;
import com.non.docx.core.api.InlineElement;
import com.non.docx.core.api.exception.DocxOperationException;
import com.non.docx.core.api.style.HeadingLevel;
import com.non.docx.core.api.style.ListKind;
import com.non.docx.core.api.style.Shading;
import com.non.docx.core.api.text.Hyperlink;
import com.non.docx.core.api.text.Paragraph;
import com.non.docx.core.api.text.Run;
import com.non.docx.core.internal.util.Objects;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

/**
 * Internal API — subject to change without notice.
 *
 * <p>docx compare MVP 的内部支撑：以旧文档为基线，只比较正文纯文本段落，并把差异重放成 tracked changes。复杂结构（表格、页眉页脚、图片、超链接、 field
 * 等）不在这里做深 compare。
 */
public final class DocumentCompareSupport {

  private DocumentCompareSupport() {}

  /** 在 {@code result} 上应用 old/new 的 compare 结果。 */
  public static void apply(Document oldDoc, Document newDoc, Document result, String author) {
    Objects.requireNonNull(oldDoc, "oldDoc");
    Objects.requireNonNull(newDoc, "newDoc");
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(author, "author");

    List<Paragraph> oldParagraphs = oldDoc.paragraphs();
    List<Paragraph> newParagraphs = newDoc.paragraphs();
    List<AnchorPair> anchors = paragraphAnchors(oldParagraphs, newParagraphs);

    for (int a = anchors.size() - 1; a >= 1; a--) {
      AnchorPair current = anchors.get(a);
      AnchorPair previous = anchors.get(a - 1);
      applySegment(
          oldParagraphs,
          newParagraphs,
          result,
          author,
          previous.oldIndex + 1,
          current.oldIndex - 1,
          previous.newIndex + 1,
          current.newIndex - 1,
          current.oldIndex);
    }
  }

  private static void applySegment(
      List<Paragraph> oldParagraphs,
      List<Paragraph> newParagraphs,
      Document result,
      String author,
      int oldStart,
      int oldEnd,
      int newStart,
      int newEnd,
      int nextOldAnchorIndex) {
    int oldCount = Math.max(0, oldEnd - oldStart + 1);
    int newCount = Math.max(0, newEnd - newStart + 1);
    int common = Math.min(oldCount, newCount);

    for (int i = 0; i < newCount - common; i++) {
      Paragraph source = newParagraphs.get(newStart + common + i);
      insertParagraphBeforeAnchor(result, nextOldAnchorIndex, source, author);
    }

    for (int oldIndex = oldEnd; oldIndex >= oldStart + common; oldIndex--) {
      markWholeParagraphDeleted(result.paragraph(oldIndex), author);
    }

    for (int i = common - 1; i >= 0; i--) {
      Paragraph oldParagraph = oldParagraphs.get(oldStart + i);
      Paragraph newParagraph = newParagraphs.get(newStart + i);
      applyParagraphModification(
          result.paragraph(oldStart + i), oldParagraph, newParagraph, author);
    }
  }

  private static void applyParagraphModification(
      Paragraph target, Paragraph oldParagraph, Paragraph newParagraph, String author) {
    String oldText = oldParagraph.text();
    String newText = newParagraph.text();
    if (java.util.Objects.equals(oldText, newText)) {
      return;
    }
    if (!supportsTrackedRewrite(oldParagraph) || !supportsTrackedRewrite(newParagraph)) {
      return;
    }
    List<TextSegment> segments = diffText(oldText, newText);
    clearInlineContent(target);
    for (TextSegment segment : segments) {
      if (segment.text.isEmpty()) {
        continue;
      }
      switch (segment.kind) {
        case EQUAL:
          target.addRun(segment.text);
          break;
        case DELETE:
          Run deleted = target.addRun(segment.text);
          target.addDeletion(author, deleted);
          break;
        case INSERT:
          target.addInsertion(author, segment.text);
          break;
        default:
          throw new DocxOperationException("未知文本 diff 片段类型", segment.kind.name());
      }
    }
  }

  private static void markWholeParagraphDeleted(Paragraph paragraph, String author) {
    if (!supportsTrackedRewrite(paragraph)) {
      return;
    }
    String oldText = paragraph.text();
    clearInlineContent(paragraph);
    if (oldText.isEmpty()) {
      return;
    }
    Run deleted = paragraph.addRun(oldText);
    paragraph.addDeletion(author, deleted);
  }

  private static void insertParagraphBeforeAnchor(
      Document result, int nextOldAnchorIndex, Paragraph source, String author) {
    Paragraph inserted = createParagraphBeforeAnchor(result, nextOldAnchorIndex);
    copyParagraphProperties(source, inserted);
    String text = source.text();
    if (!text.isEmpty()) {
      inserted.addInsertion(author, text);
    }
  }

  private static Paragraph createParagraphBeforeAnchor(Document result, int nextOldAnchorIndex) {
    if (nextOldAnchorIndex >= result.paragraphs().size()) {
      return result.addParagraph();
    }
    XWPFParagraph anchor = result.paragraph(nextOldAnchorIndex).raw();
    int bodyIndex = result.raw().getPosOfParagraph(anchor);
    if (bodyIndex < 0) {
      throw new DocxOperationException("无法定位插入锚点段落", "paragraph index " + nextOldAnchorIndex);
    }
    return result.insertParagraph(bodyIndex);
  }

  private static void copyParagraphProperties(Paragraph source, Paragraph target) {
    HeadingLevel heading = source.heading();
    if (heading != null) {
      target.heading(heading);
    }
    target.alignment(source.alignment());
    target.indent(source.indentationLeft(), source.indentationFirstLine());
    double lineSpacing = source.lineSpacing();
    if (lineSpacing >= 0) {
      target.lineSpacing(lineSpacing);
    }
    ListKind listKind = source.listKind();
    if (listKind != null) {
      Integer level = source.listLevel();
      target.list(listKind, level == null ? 0 : level);
    }
    Shading shading = source.shading();
    if (shading != null) {
      target.shading(shading);
    }
  }

  private static boolean supportsTrackedRewrite(Paragraph paragraph) {
    for (InlineElement element : paragraph.inlineElements()) {
      if (element instanceof Hyperlink) {
        return false;
      }
      if (!(element instanceof Run)) {
        return false;
      }
      Run run = (Run) element;
      if (run.raw().getCTR().sizeOfFldCharArray() > 0
          || run.raw().getCTR().sizeOfInstrTextArray() > 0) {
        return false;
      }
    }
    return true;
  }

  private static void clearInlineContent(Paragraph paragraph) {
    for (int i = paragraph.inlineElements().size() - 1; i >= 0; i--) {
      paragraph.removeInlineElement(i);
    }
  }

  private static List<AnchorPair> paragraphAnchors(
      List<Paragraph> oldParagraphs, List<Paragraph> newParagraphs) {
    String[] oldTexts = paragraphTexts(oldParagraphs);
    String[] newTexts = paragraphTexts(newParagraphs);
    int[][] lcs = lcs(oldTexts, newTexts);
    List<AnchorPair> anchors = new ArrayList<>();
    anchors.add(new AnchorPair(-1, -1));
    int i = 0;
    int j = 0;
    while (i < oldTexts.length && j < newTexts.length) {
      if (java.util.Objects.equals(oldTexts[i], newTexts[j])) {
        anchors.add(new AnchorPair(i, j));
        i++;
        j++;
      } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
        i++;
      } else {
        j++;
      }
    }
    anchors.add(new AnchorPair(oldTexts.length, newTexts.length));
    return anchors;
  }

  private static String[] paragraphTexts(List<Paragraph> paragraphs) {
    String[] texts = new String[paragraphs.size()];
    for (int i = 0; i < paragraphs.size(); i++) {
      texts[i] = paragraphs.get(i).text();
    }
    return texts;
  }

  private static int[][] lcs(String[] left, String[] right) {
    int[][] dp = new int[left.length + 1][right.length + 1];
    for (int i = left.length - 1; i >= 0; i--) {
      for (int j = right.length - 1; j >= 0; j--) {
        if (java.util.Objects.equals(left[i], right[j])) {
          dp[i][j] = dp[i + 1][j + 1] + 1;
        } else {
          dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
        }
      }
    }
    return dp;
  }

  private static List<TextSegment> diffText(String oldText, String newText) {
    int[] oldPoints = oldText.codePoints().toArray();
    int[] newPoints = newText.codePoints().toArray();
    int[][] dp = lcs(oldPoints, newPoints);
    List<TextSegment> raw = new ArrayList<>();
    int i = 0;
    int j = 0;
    while (i < oldPoints.length && j < newPoints.length) {
      if (oldPoints[i] == newPoints[j]) {
        raw.add(new TextSegment(TextSegmentKind.EQUAL, codePointString(oldPoints[i])));
        i++;
        j++;
      } else if (dp[i + 1][j] >= dp[i][j + 1]) {
        raw.add(new TextSegment(TextSegmentKind.DELETE, codePointString(oldPoints[i])));
        i++;
      } else {
        raw.add(new TextSegment(TextSegmentKind.INSERT, codePointString(newPoints[j])));
        j++;
      }
    }
    while (i < oldPoints.length) {
      raw.add(new TextSegment(TextSegmentKind.DELETE, codePointString(oldPoints[i++])));
    }
    while (j < newPoints.length) {
      raw.add(new TextSegment(TextSegmentKind.INSERT, codePointString(newPoints[j++])));
    }
    return mergeSegments(raw);
  }

  private static int[][] lcs(int[] left, int[] right) {
    int[][] dp = new int[left.length + 1][right.length + 1];
    for (int i = left.length - 1; i >= 0; i--) {
      for (int j = right.length - 1; j >= 0; j--) {
        if (left[i] == right[j]) {
          dp[i][j] = dp[i + 1][j + 1] + 1;
        } else {
          dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
        }
      }
    }
    return dp;
  }

  private static String codePointString(int codePoint) {
    return new String(Character.toChars(codePoint));
  }

  private static List<TextSegment> mergeSegments(List<TextSegment> raw) {
    if (raw.isEmpty()) {
      return Collections.emptyList();
    }
    List<TextSegment> merged = new ArrayList<>();
    TextSegment current = raw.get(0);
    StringBuilder text = new StringBuilder(current.text);
    for (int i = 1; i < raw.size(); i++) {
      TextSegment next = raw.get(i);
      if (next.kind == current.kind) {
        text.append(next.text);
      } else {
        merged.add(new TextSegment(current.kind, text.toString()));
        current = next;
        text = new StringBuilder(next.text);
      }
    }
    merged.add(new TextSegment(current.kind, text.toString()));
    return merged;
  }

  private static final class AnchorPair {
    private final int oldIndex;
    private final int newIndex;

    private AnchorPair(int oldIndex, int newIndex) {
      this.oldIndex = oldIndex;
      this.newIndex = newIndex;
    }
  }

  private enum TextSegmentKind {
    EQUAL,
    DELETE,
    INSERT
  }

  private static final class TextSegment {
    private final TextSegmentKind kind;
    private final String text;

    private TextSegment(TextSegmentKind kind, String text) {
      this.kind = kind;
      this.text = text;
    }
  }
}
