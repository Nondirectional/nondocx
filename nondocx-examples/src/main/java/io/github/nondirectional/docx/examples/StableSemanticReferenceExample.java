package io.github.nondirectional.docx.examples;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import io.github.nondirectional.docx.core.internal.poi.AuthoringInfra;
import io.github.nondirectional.docx.toolkit.ref.DocumentRef;
import io.github.nondirectional.docx.toolkit.ref.ElementResolver;
import io.github.nondirectional.docx.toolkit.ref.ParagraphRef;
import io.github.nondirectional.docx.toolkit.ref.RefResolutionCode;
import io.github.nondirectional.docx.toolkit.ref.RefResolutionException;
import java.nio.file.Path;

/**
 * 演示 P0-01 稳定语义寻址：位置变化不漂移、删除后稳定失效、SESSION/PERSISTENT 生命周期不同。
 *
 * <p>示例文档包含两个目标段落：
 *
 * <ul>
 *   <li>普通段落没有 {@code w14:paraId}，签发 SESSION ref。
 *   <li>持久段落预置 {@code w14:paraId}，签发 PERSISTENT ref。
 * </ul>
 *
 * <p>{@link AuthoringInfra#setParaId} 仅用于准备一份“原本就带 paraId”的输入文档；引用签发和读取路径本身不会补写 paraId。
 */
public final class StableSemanticReferenceExample {

  private static final String DOCUMENT_KEY = "stable-semantic-reference-example";
  private static final String PERSISTENT_PARA_ID = "00A1B2C3";

  public static void main(String[] args) throws Exception {
    Path output =
        ExamplePaths.outputDir().resolve("stable-semantic-reference-example.docx").toAbsolutePath();
    createInput(output);

    ParagraphRef sessionRef;
    ParagraphRef persistentRef;
    try (Document firstGeneration = Docx.open(output)) {
      ElementResolver resolver =
          new ElementResolver(new DocumentRef(DOCUMENT_KEY, 1), firstGeneration);

      Paragraph sessionTarget = firstGeneration.paragraphs().get(0);
      Paragraph persistentTarget = firstGeneration.paragraphs().get(1);
      sessionRef = resolver.reference(sessionTarget);
      persistentRef = resolver.reference(persistentTarget);

      System.out.println("SESSION ref:    " + sessionRef.canonical());
      System.out.println("PERSISTENT ref: " + persistentRef.canonical());

      firstGeneration.insertParagraph(0).addRun("后来插入到前方的段落");
      requireText("前方插入后旧 ref 仍命中原段落", resolver.resolve(sessionRef), "SESSION 目标段落");

      firstGeneration.removeParagraph(1);
      expectCode(
          "删除后旧 ref 返回 element_removed",
          RefResolutionCode.ELEMENT_REMOVED,
          () -> resolver.resolve(sessionRef));

      firstGeneration.save(output);
    }

    try (Document secondGeneration = Docx.open(output)) {
      ElementResolver resolver =
          new ElementResolver(new DocumentRef(DOCUMENT_KEY, 2), secondGeneration);

      expectCode(
          "SESSION ref 跨 generation 返回 generation_mismatch",
          RefResolutionCode.GENERATION_MISMATCH,
          () -> resolver.resolve(sessionRef));
      requireText(
          "PERSISTENT ref save/reopen 后仍命中", resolver.resolve(persistentRef), "PERSISTENT 目标段落");
    }

    System.out.println("PASS: 全部稳定语义寻址场景通过");
    System.out.println("输出文档: " + output);
  }

  private static void createInput(Path output) throws Exception {
    try (Document doc = Docx.create()) {
      doc.addParagraph("SESSION 目标段落");
      Paragraph persistent = doc.addParagraph("PERSISTENT 目标段落");
      AuthoringInfra.setParaId(persistent.raw(), PERSISTENT_PARA_ID);
      doc.save(output);
    }
  }

  private static void requireText(String scenario, Paragraph paragraph, String expected) {
    if (!expected.equals(paragraph.text())) {
      throw new IllegalStateException(
          scenario + " 失败：期望文本「" + expected + "」，实际「" + paragraph.text() + "」");
    }
    System.out.println("PASS: " + scenario + " -> " + paragraph.text());
  }

  private static void expectCode(
      String scenario, RefResolutionCode expected, ThrowingAction action) {
    try {
      action.run();
      throw new IllegalStateException(scenario + " 失败：未抛出引用解析错误");
    } catch (RefResolutionException e) {
      if (e.code() != expected) {
        throw new IllegalStateException(
            scenario + " 失败：期望 " + expected.value() + "，实际 " + e.code().value(), e);
      }
      System.out.println("PASS: " + scenario + " -> " + e.render());
    }
  }

  @FunctionalInterface
  private interface ThrowingAction {
    void run();
  }

  private StableSemanticReferenceExample() {}
}
