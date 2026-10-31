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

  // ---------- 质量 post-condition：写结果自带质检反馈（P2 收尾） ----------

  @Test
  void createDocumentAttachesQualityIssuesForPlainProfile(@TempDir Path temp) throws Exception {
    DocxToolkit tk = new DocxToolkit();
    String docId = ToolTestSupport.extractDocId(tk.session.openDocx(seed(temp)));
    // plain + CJK 正文：无首行缩进 → cjk-indent。表分页控制编译器全 profile 默认写入，
    // table-pagination 不会触发（docs/11 §6.4 修正过的事实）
    String raw =
        tk.compiler.createDocument(
            docId, "# 报告\n\n这是中文正文段落。\n\n| 模块 | 状态 |\n|---|---|\n| core | 完成 |", "plain");
    JsonNode data = MAPPER.readTree(ToolTestSupport.parse(raw).dataText());
    JsonNode issues = data.get("qualityIssues");
    assertThat(issues).isNotNull();
    java.util.List<String> names = new java.util.ArrayList<>();
    for (JsonNode issue : issues) {
      names.add(issue.get("name").asText());
    }
    assertThat(names).contains("cjk-indent");
    // toc 建议项不进 post-condition（内容完整性建议，非写质量信号）
    assertThat(names).doesNotContain("toc");
    tk.session.closeDocx(docId);
  }

  @Test
  void editDocumentQualityPostConditionAndDryRunSkipsIt(@TempDir Path temp) throws Exception {
    DocxToolkit tk = new DocxToolkit();
    String docId = ToolTestSupport.extractDocId(tk.session.openDocx(seed(temp)));

    // dry-run：未写入，无 post-condition
    java.util.Map<String, Object> insert =
        new java.util.LinkedHashMap<>(
            java.util.Map.of("op", "insert", "position", "end", "block", "追加段落"));
    JsonNode dry =
        MAPPER.readTree(
            ToolTestSupport.parse(
                    tk.compiler.editDocument(docId, List.of(insert), true, null, null))
                .dataText());
    assertThat(dry.get("dryRun").asBoolean()).isTrue();
    assertThat(dry.has("qualityIssues")).isFalse();

    // 真写入：seed 的 CJK 段无首行缩进 → cjk-indent 提示；消息含质量检查摘要
    String raw = tk.compiler.editDocument(docId, List.of(insert), null, null, null);
    ToolResultParser.Snapshot parsed = ToolTestSupport.parse(raw);
    assertThat(parsed.message()).contains("质量检查");
    JsonNode issues = MAPPER.readTree(parsed.dataText()).get("qualityIssues");
    assertThat(issues).isNotNull();
    boolean hasCjkIndent = false;
    for (JsonNode issue : issues) {
      if ("cjk-indent".equals(issue.get("name").asText())) {
        hasCjkIndent = true;
      }
    }
    assertThat(hasCjkIndent).as("seed 未排版段落应触发 cjk-indent 提示").isTrue();
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

  @Test
  void editDocumentSupportsTrackedAndFooterOps(@TempDir Path temp) throws Exception {
    DocxToolkit tk = new DocxToolkit();
    String docId = ToolTestSupport.extractDocId(tk.session.openDocx(seed(temp)));

    // 修订替换 + 页脚页码一次表达；read_document 能读回页脚核对
    java.util.Map<String, Object> tracked =
        new java.util.LinkedHashMap<>(
            java.util.Map.of(
                "op", "tracked.replace",
                "anchor", "p0",
                "find", "旧内容",
                "text", "新内容",
                "author", "审查员"));
    java.util.Map<String, Object> footer =
        new java.util.LinkedHashMap<>(java.util.Map.of("op", "footer.set_page_number"));
    String raw = tk.compiler.editDocument(docId, List.of(tracked, footer), null, null, null);
    ToolResultParser.Snapshot parsed = ToolTestSupport.parse(raw);
    assertThat(parsed).isNotNull();
    assertThat(parsed.success()).isTrue();

    Document after = tk.session.getDocument(docId);
    assertThat(after.trackedChanges().list()).hasSize(2);
    assertThat(after.trackedChanges().list().get(0).author()).isEqualTo("审查员");
    assertThat(after.footer().text()).contains("第").contains("1");
    assertThat(after.paragraph(0).text()).isEmpty(); // POI 盲区：del 已从 text() 移除、ins 不可见

    // read_document 的 Markdown 视图带页脚元数据行
    String readBack = tk.compiler.readDocument(docId, null);
    assertThat(ToolTestSupport.parse(readBack).dataText()).contains("<!-- 页脚: 第 1 页 -->");
    tk.session.closeDocx(docId);
  }

  @Test
  void editDocumentSupportsHeaderOpWithReadBackMetadataLine(@TempDir Path temp) throws Exception {
    DocxToolkit tk = new DocxToolkit();
    String docId = ToolTestSupport.extractDocId(tk.session.openDocx(seed(temp)));

    // seed 无页眉：读视图无页眉元数据行
    String before = tk.compiler.readDocument(docId, null);
    assertThat(ToolTestSupport.parse(before).dataText()).doesNotContain("<!-- 页眉:");

    // 页眉写文本一次表达；text 支持行内标记（**机密** → 加粗 run），正文不受影响
    java.util.Map<String, Object> header =
        new java.util.LinkedHashMap<>(
            java.util.Map.of("op", "header.set_text", "text", "**机密** 内部文档"));
    String raw = tk.compiler.editDocument(docId, List.of(header), null, null, null);
    ToolResultParser.Snapshot parsed = ToolTestSupport.parse(raw);
    assertThat(parsed).isNotNull();
    assertThat(parsed.success()).isTrue();

    Document after = tk.session.getDocument(docId);
    assertThat(after.header()).isNotNull();
    assertThat(after.header().paragraph(0).run(0).isBold()).isTrue();
    assertThat(after.paragraph(0).text()).isEqualTo("旧内容");

    // read_document 的 Markdown 视图带页眉元数据行（写读同批落地）
    String readBack = tk.compiler.readDocument(docId, null);
    assertThat(ToolTestSupport.parse(readBack).dataText()).contains("<!-- 页眉: 机密 内部文档 -->");
    tk.session.closeDocx(docId);
  }

  @Test
  void editDocumentSupportsTrackedSetStyleWithReadBackRevisionLine(@TempDir Path temp)
      throws Exception {
    DocxToolkit tk = new DocxToolkit();
    String docId = ToolTestSupport.extractDocId(tk.session.openDocx(seed(temp)));

    // seed 无修订：读视图无修订元数据行（零噪声）
    String before = tk.compiler.readDocument(docId, null);
    assertThat(ToolTestSupport.parse(before).dataText()).doesNotContain("<!-- 修订");

    // 以修订方式加粗既有词（目标词与邻文同 run，op 内部拆 run）；不改文本
    java.util.Map<String, Object> style =
        new java.util.LinkedHashMap<>(
            java.util.Map.of(
                "op", "tracked.set_style",
                "anchor", "p0",
                "find", "内容",
                "bold", true,
                "author", "审查员"));
    String raw = tk.compiler.editDocument(docId, List.of(style), null, null, null);
    ToolResultParser.Snapshot parsed = ToolTestSupport.parse(raw);
    assertThat(parsed).isNotNull();
    assertThat(parsed.success()).isTrue();

    Document after = tk.session.getDocument(docId);
    assertThat(after.trackedChanges().list()).hasSize(1);
    assertThat(after.trackedChanges().list().get(0).type())
        .isEqualTo(io.github.nondirectional.docx.core.api.track.TrackedChangeType.RPR_CHANGE);
    assertThat(after.paragraph(0).text()).isEqualTo("旧内容");

    // read_document 的 Markdown 视图带修订元数据行（写读同批：属性摘要可见）
    String readBack = tk.compiler.readDocument(docId, null);
    assertThat(ToolTestSupport.parse(readBack).dataText())
        .contains("<!-- 修订 1 条 -->")
        .contains("RPR_CHANGE author=审查员 属性=RUN_PROPERTIES 新[b] 旧[]");
    tk.session.closeDocx(docId);
  }

  @Test
  void fillTemplateFillsPlaceholdersInSessionDocument(@TempDir Path temp) throws Exception {
    DocxToolkit tk = new DocxToolkit();
    Path template = temp.resolve("template.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("甲方：{{party_a}}");
      doc.addParagraph("{{#urgent}}");
      doc.addParagraph("加急：{{deadline}}");
      doc.addParagraph("{{/urgent}}");
      doc.save(template);
    }
    String docId = ToolTestSupport.extractDocId(tk.session.openDocx(template.toString()));

    java.util.Map<String, Object> data =
        new java.util.LinkedHashMap<>(
            java.util.Map.of("party_a", "甲公司", "urgent", true, "deadline", "周五"));
    String raw = tk.compiler.fillTemplate(docId, data, null);
    ToolResultParser.Snapshot parsed = ToolTestSupport.parse(raw);
    assertThat(parsed).isNotNull();
    assertThat(parsed.success()).isTrue();
    JsonNode result = MAPPER.readTree(parsed.dataText());
    assertThat(result.get("placeholdersFilled").asInt()).isEqualTo(2); // party_a + deadline
    Document after = tk.session.getDocument(docId);
    assertThat(after.paragraph(0).text()).isEqualTo("甲方：甲公司");
    assertThat(after.paragraph(1).text()).isEqualTo("加急：周五"); // 标记行已删、内容保留

    // 空 data 拒绝；不存在的 docId 拒绝
    assertThat(ToolTestSupport.isFailure(tk.compiler.fillTemplate(docId, java.util.Map.of(), null)))
        .isTrue();
    assertThat(ToolTestSupport.isFailure(tk.compiler.fillTemplate("doc-99", data, null))).isTrue();
    tk.session.closeDocx(docId);
  }

  @Test
  void renderPreviewRejectsBadFormatWithoutTouchingDocument(@TempDir Path temp) throws Exception {
    DocxToolkit tk = new DocxToolkit();
    String docId = ToolTestSupport.extractDocId(tk.session.openDocx(seed(temp)));
    Document before = tk.session.getDocument(docId);

    String raw = tk.compiler.renderPreview(docId, "gif");
    assertThat(ToolTestSupport.isFailure(raw)).isTrue();
    assertThat(ToolTestSupport.parse(raw).message()).contains("渲染失败");
    // 只读：文档句柄与内容不变
    assertThat(tk.session.getDocument(docId)).isSameAs(before);
    assertThat(tk.session.getDocument(docId).paragraph(0).text()).isEqualTo("旧内容");
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
