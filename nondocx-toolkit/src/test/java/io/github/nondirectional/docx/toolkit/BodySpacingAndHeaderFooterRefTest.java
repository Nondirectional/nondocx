package io.github.nondirectional.docx.toolkit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import io.github.nondirectional.docx.toolkit.ref.ElementResolver;
import io.github.nondirectional.docx.toolkit.ref.HeaderFooterRef;
import io.github.nondirectional.docx.toolkit.ref.ParagraphRef;
import io.github.nondirectional.docx.toolkit.ref.RunRef;
import io.github.nondirectional.docx.toolkit.result.ToolResultParser;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 正文行距工具 + 页眉/页脚 ref 读取链路的回归测试。
 *
 * <p>覆盖：批量行距 save→reopen、非法行距、页脚 paragraph/run ref 的 view/read 解析、 HeaderFooterRef 读取、以及写工具对页脚 ref
 * 的明确拒绝。
 */
class BodySpacingAndHeaderFooterRefTest {

  @TempDir Path temp;

  @Test
  void batchLineSpacingWritesAndSurvivesReopen() {
    Path source = temp.resolve("spacing.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("第一段。");
      doc.addParagraph("第二段。");
      doc.save(source);
    }

    Path output = temp.resolve("spacing-out.docx");
    DocxToolkit toolkit = new DocxToolkit();
    String docId = ToolTestSupport.extractDocId(toolkit.session.openDocx(source.toString()));
    try {
      String raw =
          toolkit.body.updateParagraphSpacing(
              docId,
              List.of(
                  Map.of("paragraph_index", 0, "line_spacing", 1.5),
                  Map.of("paragraph_index", 1, "line_spacing", 2.0)),
              null,
              null);
      ToolResultParser.Snapshot result = ToolTestSupport.parse(raw);
      assertThat(result).isNotNull();
      assertThat(result.success()).isTrue();
      assertThat(result.message()).contains("成功 2 条");

      Document doc = toolkit.session.getDocument(docId);
      assertThat(doc.paragraph(0).lineSpacing()).isEqualTo(1.5);
      assertThat(doc.paragraph(1).lineSpacing()).isEqualTo(2.0);

      Paragraph first = doc.paragraph(0);
      ParagraphRef firstRef = toolkit.session.getElementResolver(docId).reference(first);
      ToolResultParser.Snapshot view =
          ToolTestSupport.parse(toolkit.view.viewElement(docId, firstRef.canonical()));
      assertThat(view).isNotNull();
      assertThat(view.success()).isTrue();
      assertThat(view.dataText()).contains("\"lineSpacing\":1.5");

      String saved = toolkit.session.saveDocx(docId, output.toString());
      assertThat(ToolTestSupport.isFailure(saved)).isFalse();
    } finally {
      toolkit.session.closeDocx(docId);
    }

    try (Document reopened = Docx.open(output)) {
      assertThat(reopened.paragraph(0).lineSpacing()).isEqualTo(1.5);
      assertThat(reopened.paragraph(1).lineSpacing()).isEqualTo(2.0);
    }
  }

  @Test
  void invalidLineSpacingFailsWithoutWriting() {
    Path source = temp.resolve("invalid-spacing.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("正文。");
      doc.save(source);
    }

    DocxToolkit toolkit = new DocxToolkit();
    String docId = ToolTestSupport.extractDocId(toolkit.session.openDocx(source.toString()));
    try {
      String raw =
          toolkit.body.updateParagraphSpacing(
              docId, List.of(Map.of("paragraph_index", 0, "line_spacing", 0)), null, null);
      assertThat(ToolTestSupport.isFailure(raw)).isTrue();
      assertThat(raw).contains("line_spacing");
      assertThat(toolkit.session.getDocument(docId).paragraph(0).lineSpacing()).isEqualTo(-1.0);
    } finally {
      toolkit.session.closeDocx(docId);
    }
  }

  @Test
  void footerParagraphAndRunRefsAreReadableButNotWritable() {
    Path source = temp.resolve("footer.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("正文");
      doc.ensureFooter().addParagraph().addRun("页脚文本");
      doc.save(source);
    }

    DocxToolkit toolkit = new DocxToolkit();
    String docId = ToolTestSupport.extractDocId(toolkit.session.openDocx(source.toString()));
    try {
      Document doc = toolkit.session.getDocument(docId);
      Paragraph footerParagraph = doc.footer().paragraph(0);
      ElementResolver resolver = toolkit.session.getElementResolver(docId);
      ParagraphRef paragraphRef = resolver.reference(footerParagraph);
      RunRef runRef = resolver.reference(footerParagraph.run(0));
      HeaderFooterRef footerRef = resolver.reference(doc.footer());

      ToolResultParser.Snapshot paragraphView =
          ToolTestSupport.parse(toolkit.view.viewElement(docId, paragraphRef.canonical()));
      assertThat(paragraphView).isNotNull();
      assertThat(paragraphView.success()).isTrue();
      assertThat(paragraphView.dataText()).contains("页脚文本").contains("页脚");

      @SuppressWarnings({"unchecked", "rawtypes"})
      List<Integer> paragraphRefList = (List) List.of(paragraphRef.canonical());
      ToolResultParser.Snapshot paragraphRead =
          ToolTestSupport.parse(toolkit.body.readParagraph(docId, paragraphRefList));
      assertThat(paragraphRead).isNotNull();
      assertThat(paragraphRead.success()).isTrue();
      assertThat(paragraphRead.message()).contains("页脚文本").contains("位置: 页脚");

      ToolResultParser.Snapshot runRead =
          ToolTestSupport.parse(
              toolkit.body.readRun(docId, List.of(Map.of("ref", runRef.canonical()))));
      assertThat(runRead).isNotNull();
      assertThat(runRead.success()).isTrue();
      assertThat(runRead.message()).contains("页脚文本").contains("位置: 页脚");

      ToolResultParser.Snapshot runView =
          ToolTestSupport.parse(toolkit.view.viewElement(docId, runRef.canonical()));
      assertThat(runView).isNotNull();
      assertThat(runView.success()).isTrue();
      assertThat(runView.dataText()).contains("页脚文本").contains("页脚");

      ToolResultParser.Snapshot hfRead =
          ToolTestSupport.parse(toolkit.headerFooterToc.readHeaderFooter(docId, "FOOTER", 0, 0));
      assertThat(hfRead).isNotNull();
      assertThat(hfRead.success()).isTrue();
      assertThat(hfRead.message()).contains("paragraph ref:").contains("run 0 ref");

      ToolResultParser.Snapshot hfRefRead =
          ToolTestSupport.parse(
              toolkit.headerFooterToc.readHeaderFooterRef(docId, footerRef.canonical()));
      assertThat(hfRefRead).isNotNull();
      assertThat(hfRefRead.success()).isTrue();
      assertThat(hfRefRead.message()).contains("run 0 ref=");

      ToolResultParser.Snapshot partView =
          ToolTestSupport.parse(toolkit.view.viewElement(docId, footerRef.canonical()));
      assertThat(partView).isNotNull();
      assertThat(partView.success()).isTrue();
      assertThat(partView.dataText()).contains("\"part\":\"footer\"").contains("页脚文本");

      String write =
          toolkit.body.replaceRunText(
              docId, List.of(Map.of("ref", runRef.canonical(), "text", "改掉")), null, null);
      assertThat(ToolTestSupport.isFailure(write)).isTrue();
      assertThat(write).contains("仅支持正文");
      assertThat(footerParagraph.text()).isEqualTo("页脚文本");

      String spacingWrite =
          toolkit.body.updateParagraphSpacing(
              docId,
              List.of(Map.of("ref", paragraphRef.canonical(), "line_spacing", 1.5)),
              null,
              null);
      assertThat(ToolTestSupport.isFailure(spacingWrite)).isTrue();
      assertThat(spacingWrite).contains("仅支持正文");
      assertThat(footerParagraph.text()).isEqualTo("页脚文本");
    } finally {
      toolkit.session.closeDocx(docId);
    }
  }
}
