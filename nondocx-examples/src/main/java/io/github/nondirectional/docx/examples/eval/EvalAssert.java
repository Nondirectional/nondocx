package io.github.nondirectional.docx.examples.eval;

import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import io.github.nondirectional.docx.core.api.text.Run;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 评测断言工具：失败时抛 {@link AssertionError}，消息包含「期望/实际」。
 *
 * <p>刻意不依赖 JUnit/AssertJ：评测运行器在 main 进程里执行，断言失败只应记为一条 check 失败，不能让整个 JVM 退出。这里只做最小校验，失败消息面向排查。
 */
public final class EvalAssert {

  private EvalAssert() {}

  /** 条件必须为 true。 */
  public static void isTrue(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }

  /** 两个值必须相等（允许 null）。 */
  public static void equal(Object expected, Object actual, String what) {
    if (!Objects.equals(expected, actual)) {
      throw new AssertionError(what + "：期望「" + expected + "」，实际「" + actual + "」");
    }
  }

  /** 文本必须包含指定片段。 */
  public static void contains(String haystack, String needle, String what) {
    String text = haystack == null ? "" : haystack;
    if (!text.contains(needle)) {
      throw new AssertionError(what + "：期望包含「" + needle + "」，实际「" + text + "」");
    }
  }

  /** 文本不得包含指定片段。 */
  public static void notContains(String haystack, String needle, String what) {
    String text = haystack == null ? "" : haystack;
    if (text.contains(needle)) {
      throw new AssertionError(what + "：期望不包含「" + needle + "」，实际「" + text + "」");
    }
  }

  /** double 近似相等。 */
  public static void closeTo(double expected, double actual, double tolerance, String what) {
    if (Math.abs(expected - actual) > tolerance) {
      throw new AssertionError(what + "：期望「" + expected + "±" + tolerance + "」，实际「" + actual + "」");
    }
  }

  /** 汇总正文段落文本，段落之间用换行分隔。 */
  public static String bodyText(Document doc) {
    StringBuilder sb = new StringBuilder();
    List<Paragraph> paragraphs = doc.paragraphs();
    for (int i = 0; i < paragraphs.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      sb.append(paragraphs.get(i).text());
    }
    return sb.toString();
  }

  /** 返回段落中文本非空的 run；空 run 不参与字体/字号断言。 */
  public static List<Run> textRuns(Paragraph paragraph) {
    List<Run> runs = new ArrayList<>();
    for (Run run : paragraph.runs()) {
      if (run.text() != null && !run.text().isBlank()) {
        runs.add(run);
      }
    }
    return runs;
  }

  /** 段落必须至少有一个非空 run。 */
  public static List<Run> requireTextRuns(Paragraph paragraph, String what) {
    List<Run> runs = textRuns(paragraph);
    if (runs.isEmpty()) {
      throw new AssertionError(what + "：段落没有非空 run，文本为「" + paragraph.text() + "」");
    }
    return runs;
  }

  /** 断言段落所有非空 run 的字体名。 */
  public static void assertFont(Paragraph paragraph, String expectedFont, String what) {
    for (Run run : requireTextRuns(paragraph, what)) {
      if (!Objects.equals(expectedFont, run.font())) {
        throw new AssertionError(
            what + "：run「" + run.text() + "」字体期望「" + expectedFont + "」，实际「" + run.font() + "」");
      }
    }
  }

  /** 断言段落所有非空 run 的字号（磅）。 */
  public static void assertFontSize(Paragraph paragraph, int expectedSize, String what) {
    for (Run run : requireTextRuns(paragraph, what)) {
      Integer actual = run.fontSize();
      if (actual == null || actual != expectedSize) {
        throw new AssertionError(
            what + "：run「" + run.text() + "」字号期望「" + expectedSize + "」，实际「" + actual + "」");
      }
    }
  }

  /** 断言段落所有非空 run 是否加粗。 */
  public static void assertBold(Paragraph paragraph, boolean expectedBold, String what) {
    for (Run run : requireTextRuns(paragraph, what)) {
      if (run.isBold() != expectedBold) {
        throw new AssertionError(
            what + "：run「" + run.text() + "」加粗期望「" + expectedBold + "」，实际「" + run.isBold() + "」");
      }
    }
  }
}
