# Research: toolkit 行内标记解析机制与相关测试

- Query: text 行内标记（**bold** 等）在哪些类解析、如何应用到 XWPFRun；footer.set_page_number 测试写法与位置
- Scope: internal
- Date: 2026-10-31

## Findings

### 1. 行内标记解析：MarkdownParser.parseInline

**位置**：`nondocx-toolkit/src/main/java/io/github/nondirectional/docx/toolkit/compiler/MarkdownParser.java:269-294`（package-private static）

```java
/** 行内解析：切出 code/粗体/斜体片段，其余按纯文本 span。未闭合标记原样保留。 */
static List<Span> parseInline(String text) { ... }
```

- 用正则 `INLINE`（同文件上方定义）匹配 `**bold**` / `*italic*` / `` `code` `` 三类 token；group(1) 是标记内内容。
- 输出 `MarkdownDoc.Span` 列表：`Span(text)` 纯文本 / `new Span(text, bold, italic, code)`（MarkdownParser.java:275-284）。
- 未闭合标记原样保留为纯文本 span（契约）。空入参产生一个空纯文本 span（:290-292）。
- 配套：`stripInlineMarkers(text)`（MarkdownParser.java:297-303）剥标记取纯文本（标题用）。

**Span 消费方（toolkit 内全链路）**：

| 消费点 | 位置 | 用法 |
|---|---|---|
| MarkdownParser.parsePara/parseList | MarkdownParser.java:165/:170 | Markdown 块解析进 Para/ListItem |
| PatchEngine planReplaceText | PatchEngine.java:312-313 | `text` 参数 → spans；`formatted = anyMatch(bold/italic/code)` 决定替换策略 |
| PatchEngine planTrackedInsert / planTrackedReplace | PatchEngine.java:589 / :690 / :729 | tracked op 的 text 同样过 parseInline |
| DocumentCompiler | （块编译路径） | create_document 的 Markdown → 段落 |

**应用到 run 的统一函数：PatchEngine.applySpanFormat**（PatchEngine.java:407-418）：

```java
private static void applySpanFormat(Run run, MarkdownDoc.Span span, StyleProfile profile) {
  if (span.bold()) run.bold();          // core Run.bold() -> XWPFRun.setBold
  if (span.italic()) run.italic();
  if (span.code()) DocumentCompiler.applyRunFonts(run, profile.codeFont(), profile.codeFont());
}
```

- 配对辅助 `spanTexts(spans)`（PatchEngine.java:425-429）：spans → 有序文本列表，与返回的 run 列表索引一一对应（多 run API 场景）。

**「页眉写 op 的 text 支持行内标记」可直接复用的模式**（最贴近先例是 tracked.insert，PatchEngine.java:700-705）：

```java
List<MarkdownDoc.Span> spans = MarkdownParser.parseInline(text);
// 执行期：
Paragraph paragraph = doc.ensureHeader().addParagraph();
for (MarkdownDoc.Span span : spans) {
  applySpanFormat(paragraph.addRun(span.text()), span, profile);
}
```

参数校验用 `str(op, "text", index)`（PatchEngine.java:954-960，缺失/空 → PatchException「op[i] 缺少必填字段 text」）。

### 2. footer.set_page_number 既有测试

**引擎级（PatchEngineTest）**：`nondocx-toolkit/src/test/java/io/github/nondirectional/docx/toolkit/compiler/PatchEngineTest.java`

- :799 分节注释 `// ==================== footer.set_page_number op（v2） ====================`
- `footerPageNumberAppendsCachedFieldReadableByPoi()`（:801-830）：
  - 前置：`doc.addParagraph("正文")` + `doc.ensureFooter().addParagraph().addRun("页脚")`，记 `paragraphsBefore`；
  - `PatchEngine.apply(doc, ops(op("op", "footer.set_page_number")), REPORT_CN, false)`；
  - 断言：`result.changed()` containsExactly `"footer"`；正文段数不变、text 不变；`doc.footer().text()` 含「第」「1」且不含 `{page}`；遍历 `doc.footer().paragraph(1).raw().getRuns()` 检查 CTR instrText 含 "PAGE"（域指令真写入）。
- `footerPageNumberCustomFormatAndValidation()`（:832-877）：
  - 自定义 format「第 {page} 页 / 共 {pages} 页」→ footer.text 含「第 1 页 / 共 1 页」；
  - 无占位符（「第几页」）→ PatchException 含 `{page}`；未知占位符 `{count}` → PatchException 含 `{count}`；被拒后页脚段数仍 1（零写入）；
  - **同批测试**：footer op + replace_text(p0) 同批成功，正文改写、页脚段 1→2（「不占锚点」的行为断言，:863-873）。
- 测试基建：`ops(...)`/`op(k,v,...)` 辅助构造 LinkedHashMap；`REPORT_CN` 是 StyleProfile 常量；Document 用 try-finally close。

**工具级（CompilerToolsTest）**：`nondocx-toolkit/src/test/java/io/github/nondirectional/docx/toolkit/CompilerToolsTest.java`

- `editDocumentSupportsTrackedAndFooterOps(@TempDir Path temp)`（:232-263）：
  - `tk.session.openDocx(seed(temp))` 拿 docId（ToolTestSupport.extractDocId）；
  - 构造 `Map.of("op","tracked.replace",...)` + `Map.of("op","footer.set_page_number")` 两个 LinkedHashMap；
  - `tk.compiler.editDocument(docId, List.of(tracked, footer), null, null, null)` → `ToolTestSupport.parse(raw)` 断言 success；
  - 拿底层 `tk.session.getDocument(docId)` 断言 trackedChanges 数量/author、`after.footer().text()` 含页码；
  - **读回**：`tk.compiler.readDocument(docId, null)` 的 dataText 含 `<!-- 页脚: 第 1 页 -->`。

**视图级（CompilerPipelineTest）**：`nondocx-toolkit/src/test/java/io/github/nondirectional/docx/toolkit/compiler/CompilerPipelineTest.java`

- `markdownViewShowsFooterAsMetadataAndKeepsRoundTrip()`（:221-234）：页脚不存在/空 → 无页脚行；有内容 → `<!-- 页脚: 第 1 页 -->` 且 round-trip 安全。
- spec 引用：`.trellis/spec/backend/agent-single.md:303-305`（页脚元数据行条目点名此测试）。

**新页眉 op 测试放置建议（按先例映射）**：

| 层 | 文件 | 仿照测试 |
|---|---|---|
| 引擎级 | PatchEngineTest.java（footer 分节后加 `header op` 分节） | footerPageNumber* 两个测试：写入断言（header.text() 含标记剥壳后文本、run 级 bold 断言可仿 instrText 遍历写法）、校验拒绝、与正文 op 同批 |
| 工具级 | CompilerToolsTest.java | editDocumentSupportsTrackedAndFooterOps：edit + readDocument 读回 `<!-- 页眉: … -->` |
| 视图级 | CompilerPipelineTest.java | markdownViewShowsFooterAsMetadataAndKeepsRoundTrip：页眉元数据行 + round-trip |
| core 面 | 无需新增（复用 ensureHeader，core 已有 Header/RoundTrip 覆盖） | — |

### 3. eval 任务先例（EvalTasks.java）

`nondocx-examples/src/main/java/io/github/nondirectional/docx/examples/eval/EvalTasks.java`

- `footer-page-number` 任务定义（:361-384）：四参 EvalTask(name, 描述, 用户指令「在页脚插入页码，格式为「第 X 页」。」, seeder（addParagraph("正文") + ensureFooter().addParagraph().addRun("页脚")）, checks（页脚存在 / 正文不被误改 / 页脚含页码——`footer.text()` 含「第」且含数字）, 第 6 参 true）。
- baseline 组：`group(name)`（:54-57），默认/baseline 返回固定顺序的编辑任务组（注释 :44「对既有文档做编辑的 22 条任务」）。
- check 风格：`EvalTask.NamedCheck.of(name, doc -> EvalAssert.xxx(...))`；页眉任务的 seeder 可能需要预置 `doc.ensureHeader().addParagraph().addRun("旧页眉")` 之类（对齐 footer seeder）。

## Caveats / Not Found

- `INLINE` 正则本体未抄录（MarkdownParser.java 上部），语义以 parseInline javadoc 与用例为准：`**…**` 粗、`*…*` 斜、`` `…` `` 代码，未闭合原样保留。
- EvalTask 第 6 参 `true` 的字段名未确认（疑为 tracked/编辑类标记），落地 eval 任务时读 EvalTask 构造器确认。
