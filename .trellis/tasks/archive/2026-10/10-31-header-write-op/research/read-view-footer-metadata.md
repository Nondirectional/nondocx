# Research: read_document 页脚元数据行与页眉读侧现状

- Query: read_document 中「<!-- 页脚: … -->」生成代码位置与格式惯例；页眉目前读侧暴露（full 面 header 只读指什么）
- Scope: internal
- Date: 2026-10-31

## Findings

### 页脚元数据行生成位置

| File | 位置 | 说明 |
|---|---|---|
| `nondocx-toolkit/src/main/java/io/github/nondirectional/docx/toolkit/compiler/MarkdownView.java` | line 84-92 | 生成处（`render()` 尾部） |
| 同上 | line 20-22 | javadoc：「正文之外的容器只有页脚以元数据注释行进视图…**页眉/表格内段落不在本视图内**」 |
| 同上 | line 121-131 | 同族格式先例：批注元数据行 `<!-- 批注 pN [作者]: 内容 -->`（紧跟被评段落，每条一行） |

核心代码（MarkdownView.java:84-92）：

```java
// 页脚元数据（v2）：正文外容器里只有页脚进视图——页码写入后模型能读到「第 1 页」核对；
// 注释形态，round-trip 时被解析器当元数据剥掉，不产生脏块。
io.github.nondirectional.docx.core.api.header.Footer footer = doc.footer();
if (footer != null) {
  String footerText = footer.text();
  if (footerText != null && !footerText.isBlank()) {
    sb.append("<!-- 页脚: ").append(footerText.trim().replace("\n", " / ")).append(" -->\n");
  }
}
```

`read_document` 工具（CompilerTools）直接消费 `MarkdownView.render` 输出，工具描述文案见 `CompilerTools.java`（read_document 行描述在 `docs/07-toolkit.md:219`：「页脚以 `<!-- 页脚: … -->` 元数据行呈现，与写侧同构（round-trip）」）。

### 格式惯例（对齐页眉行时需遵守）

1. **空文档/无页脚**：`doc.footer()` 返回 null → **不输出该行**；footer 存在但 `text()` 为 blank → 同样不输出。spec 措辞：「存在且非空才输出」（`.trellis/spec/backend/agent-single.md` ~line 300「read_document 的页脚元数据行」条目）。
2. **多行文本**：页脚内部换行 `\n` 替换为 `" / "` 拼成单行，整体 trim。
3. **截断规则**：截断检查在正文元素循环内（MarkdownView.java:80-83），超限即 `return truncate(...)` 并追加 `<!-- 已截断 -->`（truncate 在 line 196-203）。**截断发生时提前 return，页脚行根本不会被输出**。页脚行本身无二次截断（footerText 全量放入）。输出上限默认 `DEFAULT_MAX_CHARS = 6000`（line 27），按行边界截断。
4. **round-trip 安全**：HTML 注释形态被 create_document 解析器当元数据剥掉，不产生脏块。锁定测试：`CompilerPipelineTest#markdownViewShowsFooterAsMetadataAndKeepsRoundTrip`（`nondocx-toolkit/src/test/java/io/github/nondirectional/docx/toolkit/compiler/CompilerPipelineTest.java:234` 附近断言 `contains("<!-- 页脚: 第 1 页 -->")`）。
5. **锚点语义**：元数据行不占 `pN`/`tN` 段落锚点（与 footer.set_page_number「不占 anchor」对称）。

### 页眉目前的读侧暴露（三层）

| 面 | 位置 | 现状 |
|---|---|---|
| compiler 面 `read_document` | MarkdownView | **完全无页眉**（javadoc line 21 明示「页眉…不在本视图内」） |
| full 面 header 只读 | `nondocx-toolkit/src/main/java/io/github/nondirectional/docx/toolkit/HeaderFooterTocTools.java` | `read_header_footer`（@ToolDef line 67-101，`part=HEADER/FOOTER`，按 section/paragraph 索引读）与 `read_header_footer_ref`（line 102-135，按 canonical `HeaderFooterRef` 读）；两者 `@ToolCapability(operation = CapabilityOperation.READ)`。类 javadoc line 44：「本工具直接调 `header()`/`footer()` 即可安全只读」 |
| full 面写边界（为什么叫「只读」） | spec `agent-single.md` line 211-250「Convention: ref 的容器边界（读/写分离）」 | `ElementResolver.resolve(ParagraphRef)` 只扫正文=写工具唯一边界；`resolveAny` 扫正文+表格+页眉+页脚=仅只读路径（`view_element`/`read_paragraph`/`read_run`）。写工具遇页眉/页脚 ref 返回明确「仅支持正文」错误（`ElementResolver.java`、`HeaderFooterRef.java` 在 `toolkit/ref/`）。`docs/07-toolkit.md:131-134` blockquote 同述：「写工具仍只接受正文 ref…页脚 PAGE 域写入仍不在范围内」 |
| view 层（两面共用的 review 面） | `toolkit/view/` | `view_outline` 有 `hasHeader`/`hasFooter` 布尔（OutlineView.java，存在性）；`view_stats` 只有 `footerText` 预览、**无 headerText**（DocumentViewService.java:372/378 `footerTextPreview`）；`view_element` 支持 header_footer part 详情（DocumentViewService.java:449：「页眉/页脚 part 返回文本与段落数」） |

### core 侧 Header/Footer API（PRD 说「复用 ensureHeader 最小化新增」）

`nondocx-core/src/main/java/io/github/nondirectional/docx/core/api/Document.java`：

- `header()`（~line 324）：只读便捷方法，null 表示未设置，不创建
- `ensureHeader()`（line 339-341）：`section(0).ensureHeader()`，不存在则创建（首次创建补 A4+1inch 兜底）
- `ensureHeader(variant)`（line 368-370）：指定变体
- `ensureFooter()`（line 390）与 `footer()` 对称
- `Header`/`Footer` API：`paragraphs()/paragraph(i)` 读、`addParagraph()` 写、`text()` 全文纯文本、`raw()` → `XWPFHeader/XWPFFooter`（docs/03 line 189-201）

footer.set_page_number 的实现先例（页眉 op 的直接模板）：`PatchEngine.planFooterPageNumber`（`toolkit/compiler/PatchEngine.java:803-860+`）——`doc.ensureFooter().addParagraph()` 后逐段拼 literal/域，`{page}`/`{pages}` 占位符走 `Paragraph.addSimpleField`（缓存结果域）。行内标记路径见 tracked op 的 `parseInline` 复用（§6.13）。

### 「页眉写无 op」的既有声明位置（本任务要更新的句子）

- `docs/11-agent-authoring-architecture.md` §6.9 末尾（~line 697-699）：「页眉写不进 baseline（compiler 面无页眉 op，属已知能力边界，与 §6.4 时页脚缺口同型——出现真实需求再补）」
- `.trellis/spec/backend/agent-single.md` 评测基线结果块（~line 200-203）：「已知边界：页眉写无 op（两面）」——注意是**两面**（full 面写工具 ref 只解析正文，compiler 面无 op）

## Caveats / Not Found

- MarkdownView 的页脚行没有任何「headerText」预留字段或 TODO；页眉行需全新加。
- `view_stats` 的 StatsView 无 headerText 字段，若复审核对需要（§6.6 恒出现原则）可能同样要补——本调研只记录现状。
