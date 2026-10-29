package io.github.nondirectional.docx.toolkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.toolkit.result.ToolResultParser;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 编译器工具组契约测试：create/read/validate_document 的会话语义与错误路径。
 *
 * <p>锁定三条硬契约：
 *
 * <ol>
 *   <li><b>create_document 换血不换柄</b>——docId 不变、generation+1、旧文档内容被替换 （与 {@code SessionTools.reopen}
 *       同一条 {@code replaceSessionDocument} 路径）；
 *   <li><b>失败不触碰会话</b>——markdown 为空 / profile 未知时返回 INVALID_ARGUMENT， 会话里旧文档原样保留；
 *   <li><b>read_document 与写侧同构</b>——create 后读回的 Markdown 含短 ID 锚点， 可直接再作为 create_document 的输入。
 * </ol>
 */
class CompilerToolsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String MARKDOWN =
      "# 会议纪要 {align=center}\n"
          + "\n"
          + "本周完成了编译器 spike。\n"
          + "\n"
          + "| 议题 | 结论 |\n"
          + "|---|---|\n"
          + "| 工具面 | 5 个入口 |\n";

  @Test
  void createDocumentReplacesSessionDocumentAndKeepsDocId(@TempDir Path temp) throws Exception {
    DocxToolkit tk = new DocxToolkit();
    String docId = ToolTestSupport.extractDocId(tk.session.openDocx(seed(temp)));

    String raw = tk.compiler.createDocument(docId, MARKDOWN, "report-cn");
    ToolResultParser.Snapshot parsed = ToolTestSupport.parse(raw);
    assertThat(parsed).isNotNull();
    assertThat(parsed.success()).isTrue();
    JsonNode data = MAPPER.readTree(parsed.dataText());

    // docId 不变，旧内容已被编译产物替换
    Document after = tk.session.getDocument(docId);
    assertThat(after.paragraph(0).text()).isEqualTo("会议纪要");
    assertThat(after.paragraph(1).text()).isEqualTo("本周完成了编译器 spike。");
    assertThat(after.tables()).hasSize(1);

    // generation 递增：openDocx 时为 1，create 后为 2
    assertThat(data.get("generation").asLong()).isEqualTo(2L);
    assertThat(data.get("styleProfile").asText()).isEqualTo("report-cn");
    assertThat(data.get("paragraphCount").asInt()).isEqualTo(2);
    assertThat(data.get("tableCount").asInt()).isEqualTo(1);

    tk.session.closeDocx(docId);
  }

  @Test
  void failedCreateLeavesSessionUntouched(@TempDir Path temp) throws Exception {
    DocxToolkit tk = new DocxToolkit();
    String docId = ToolTestSupport.extractDocId(tk.session.openDocx(seed(temp)));

    // 空 markdown：失败且不换文档
    String empty = tk.compiler.createDocument(docId, "   \n", "report-cn");
    assertThat(ToolTestSupport.isFailure(empty)).isTrue();
    assertThat(tk.session.getDocument(docId).paragraph(0).text()).isEqualTo("旧内容");

    // 未知 profile：失败且不换文档，错误信息列出可选项
    String badProfile = tk.compiler.createDocument(docId, MARKDOWN, "fancy");
    assertThat(ToolTestSupport.isFailure(badProfile)).isTrue();
    assertThat(ToolTestSupport.parse(badProfile).message()).contains("report-cn");
    assertThat(tk.session.getDocument(docId).paragraph(0).text()).isEqualTo("旧内容");

    // docId 不存在
    assertThat(ToolTestSupport.isFailure(tk.compiler.createDocument("doc-99", MARKDOWN, null)))
        .isTrue();

    tk.session.closeDocx(docId);
  }

  @Test
  void readDocumentReturnsMarkdownWithShortIds(@TempDir Path temp) throws Exception {
    DocxToolkit tk = new DocxToolkit();
    String docId = ToolTestSupport.extractDocId(tk.session.openDocx(seed(temp)));
    tk.compiler.createDocument(docId, MARKDOWN, "report-cn");

    String raw = tk.compiler.readDocument(docId, null);
    ToolResultParser.Snapshot parsed = ToolTestSupport.parse(raw);
    assertThat(parsed).isNotNull();
    assertThat(parsed.success()).isTrue();
    JsonNode data = MAPPER.readTree(parsed.dataText());
    assertThat(data.get("markdown").asText())
        .contains("# 会议纪要 {align=center} <!-- p0 -->")
        .contains("<!-- t0 -->");
    assertThat(data.get("paragraphCount").asInt()).isEqualTo(2);
    assertThat(data.get("tableCount").asInt()).isEqualTo(1);
    assertThat(data.get("truncated").asBoolean()).isFalse();

    assertThat(ToolTestSupport.isFailure(tk.compiler.readDocument("doc-99", null))).isTrue();
    tk.session.closeDocx(docId);
  }

  @Test
  void validateDocumentReportsIssuesWithIds(@TempDir Path temp) throws Exception {
    DocxToolkit tk = new DocxToolkit();
    String docId = ToolTestSupport.extractDocId(tk.session.openDocx(seed(temp)));
    tk.compiler.createDocument(docId, MARKDOWN, "report-cn");

    String raw = tk.compiler.validateDocument(docId);
    ToolResultParser.Snapshot parsed = ToolTestSupport.parse(raw);
    assertThat(parsed).isNotNull();
    assertThat(parsed.success()).isTrue();
    JsonNode data = MAPPER.readTree(parsed.dataText());
    assertThat(data.get("totalChecks").asInt()).isPositive();
    // issues 恒为数组；编译产物不触发结构/版式类问题（toc「建议插目录」是允许的提醒项）
    assertThat(data.get("issues").isArray()).isTrue();
    for (JsonNode issue : data.get("issues")) {
      assertThat(issue.get("check").asText()).as("非预期问题: %s", issue).isEqualTo("toc");
    }

    assertThat(ToolTestSupport.isFailure(tk.compiler.validateDocument("doc-99"))).isTrue();
    tk.session.closeDocx(docId);
  }

  @Test
  void editDocumentAppliesAtomicPatchAndRejectsOnPreflightFailure(@TempDir Path temp)
      throws Exception {
    DocxToolkit tk = new DocxToolkit();
    String docId = ToolTestSupport.extractDocId(tk.session.openDocx(seed(temp)));

    // 合法 patch：插入 + 套样式，一次调用
    java.util.Map<String, Object> insert =
        new java.util.LinkedHashMap<>(
            java.util.Map.of("op", "insert", "position", "end", "block", "追加的一段。"));
    java.util.Map<String, Object> style =
        new java.util.LinkedHashMap<>(
            java.util.Map.of("op", "set_style", "anchor", "p0", "style", "body"));
    String raw = tk.compiler.editDocument(docId, List.of(insert, style), null, null, null);
    ToolResultParser.Snapshot parsed = ToolTestSupport.parse(raw);
    assertThat(parsed).isNotNull();
    assertThat(parsed.success()).isTrue();
    JsonNode data = MAPPER.readTree(parsed.dataText());
    assertThat(data.get("applied").asInt()).isEqualTo(2);
    Document after = tk.session.getDocument(docId);
    assertThat(after.paragraphs()).hasSize(2);
    assertThat(after.paragraph(0).lineSpacing()).isEqualTo(1.5);

    // preflight 失败：零写入 + 明确消息；generation 不匹配：拒绝
    java.util.Map<String, Object> badAnchor =
        new java.util.LinkedHashMap<>(java.util.Map.of("op", "delete", "anchor", "p99"));
    String failed = tk.compiler.editDocument(docId, List.of(badAnchor), null, null, null);
    assertThat(ToolTestSupport.isFailure(failed)).isTrue();
    assertThat(ToolTestSupport.parse(failed).message()).contains("未被修改");
    assertThat(tk.session.getDocument(docId).paragraphs()).hasSize(2);

    String stale = tk.compiler.editDocument(docId, List.of(insert), null, null, 99);
    assertThat(ToolTestSupport.isFailure(stale)).isTrue();
    assertThat(ToolTestSupport.parse(stale).message()).contains("generation");

    assertThat(
            ToolTestSupport.isFailure(
                tk.compiler.editDocument("doc-99", List.of(insert), null, null, null)))
        .isTrue();
    tk.session.closeDocx(docId);
  }

  private static String seed(Path temp) throws Exception {
    java.nio.file.Path file = temp.resolve("seed.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("旧内容");
      doc.save(file);
    }
    return file.toString();
  }
}
