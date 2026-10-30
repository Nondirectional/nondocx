# Research: core 页眉页脚 API（ensureHeader/ensureFooter、N5 读写分离、Paragraph 行内写入）

- Query: ensureHeader()/ensureFooter() 位置与 API 形态；N5 读写分离；Header/Footer 的 Paragraph API；行内标记如何写入
- Scope: internal
- Date: 2026-10-31

## Findings

### 1. ensureHeader / ensureFooter API 位置

**Document 便捷层**（nondocx-core/src/main/java/io/github/nondirectional/docx/core/api/Document.java）：

| 方法 | 行号 | 语义 |
|---|---|---|
| `Document.header()` | Document.java:329-331 | 只读返回 section(0) 默认页眉，不存在返 null，绝不创建 |
| `Document.ensureHeader()` | Document.java:339-341 | 委托 `section(0).ensureHeader()`，不存在则创建，从不返 null，DocxIOException on 失败 |
| `Document.header(variant)` / `ensureHeader(variant)` | Document.java:353-371 | 变体版（DEFAULT/FIRST/EVEN） |
| `Document.footer()` / `ensureFooter()` / 变体 | Document.java:380-419 | 与 header 完全对称 |

**Section 实现层**（nondocx-core/src/main/java/io/github/nondirectional/docx/core/api/section/Section.java）：

| 方法 | 行号 | 要点 |
|---|---|---|
| `Section.ensureHeader()` | Section.java:296-298 | `= ensureHeader(HeaderFooterVariant.DEFAULT)` |
| `Section.ensureHeader(variant)` | Section.java:327-342 | ① `header(variant)` 已存在则原样返回（create-once 幂等）② `ensureCompatiblePageSetupForHeaderFooterCreation()`（N8：补 A4+1 英寸边距，missing-only）③ `ensureVariantFlags(variant)`（FIRST 补 titlePg、EVEN 补 evenAndOddHeaders、DEFAULT 无开关）④ `XWPFHeaderFooterPolicy.createHeader(...)` 创建 part ⑤ 包成 `Header` |
| `Section.footer()` / `footer(variant)` | Section.java:351-374 | 只读，POIXMLException 时返 null |
| `Section.ensureFooter(variant)` | Section.java:401-414 | 与 ensureHeader 完全一致 |

**页眉写 op 直接可用的最小调用**：`doc.ensureHeader().addParagraph()`（与 footer op 的 `doc.ensureFooter().addParagraph()` 完全对称，PatchEngine.java:836）。core **零新增**。

### 2. N5 读写分离（spec + 归档）

**spec 定义**：`.trellis/spec/backend/poi-bridge.md` 「N5 — Header/footer are read/write split」（poi-bridge.md:200-213）：

- POI 自身就分读（`getDefaultHeader()/getDefaultFooter()`，不存在返 null）与写（`createHeader()/createFooter()`）。
- nondocx 镜像该分离：`Section.header()/footer()` 只读（null=不存在，绝不改文档，`Section.equals` 直接用）；`Section.ensureHeader()/ensureFooter()` 是创建路径（create-once，写侧调用者用它拿可 append 的容器）。
- 历史：取代早期「访问即创建」（create-on-access）设计——旧设计让只读遍历（搜索/迭代/equals）偷偷创建空 part 污染文档，`Section.equals` 曾要靠私有只读 resolver 绕开；分离后 workaround 移除。

**相关归档任务**：
- `.trellis/tasks/archive/2026-06/06-18-header-footer-split-agent-tools/prd.md` — core 页眉页脚读写分离原始任务（把 header()/footer() 从「访问即创建」改为纯只读）；spec poi-bridge.md N5 重写、N8 更新。
- `.trellis/tasks/archive/2026-06/06-23-header-footer-variants/prd.md` — 变体扩展，硬约束「header(variant) 只读返 null、ensureHeader(variant) 创建」。
- `.trellis/tasks/archive/2026-06/06-23-hf-variants-variants/design.md:225` — 读侧即使文档没 titlePg 也只读返 null，不补开关（补开关是写行为）。
- 其它引用：docs/09-faq-and-boundaries.md:157、poi-bridge.md:229-232（N8）。

**对页眉写 op 的含义**：op 执行体用 `ensureHeader()`（写路径）合法；`MarkdownView` 读侧用 `header()`（只读，null=不存在，绝不因读视图创建页眉 part）——写读两侧入口必须分开选。

### 3. Header / Footer 容器 API（api/header/ 包）

`Header.java`（nondocx-core/.../api/header/Header.java）与 `Footer.java` 同构（Footer 对 XWPFFooter）：

| 方法 | 行号 | 说明 |
|---|---|---|
| `paragraphs()` | Header.java:51-63 | 活跃不可修改 List&lt;Paragraph&gt;，每次访问重读委托 |
| `paragraph(int index)` | Header.java:72-74 | 按索引取段 |
| `addParagraph()` | Header.java:81-83 | **追加一个新空段落**，返回活跃 Paragraph 包装（`new Paragraph(delegate.createParagraph())`）。注意：是追加，不是替换 |
| `addTable()` / `tables()` | Header.java:92-126 | 页眉也可含表格（createTable(1,1) 后剥预填行） |
| `text()` | Header.java:133-135 | 全部段落拼接纯文本（读侧核对用） |
| `raw()` | Header.java:144-146 | 逃逸口，返回 XWPFHeader |
| equals/hashCode | Header.java:150-165 | 比较有序段落序列（round-trip 基础） |

**没有「替换/清空段落」的高层 API**——只有 `paragraphs()` 读 + `addParagraph()` 追加。若页眉已有内容想覆盖，要么经 `paragraph(i)` 拿到活 Paragraph 后用 Paragraph 的 run 级操作清写，要么 raw()。footer op 先例就是纯追加语义。

### 4. Paragraph / Run 写入 API（行内标记落地的支撑）

`nondocx-core/.../api/text/Paragraph.java`：

| 方法 | 行号 | 说明 |
|---|---|---|
| `addRun(String text)` | Paragraph.java:230 | 追加文本 run（footer op 的字面片段用它） |
| `addRun()` | Paragraph.java:219 | 追加空 run |
| `copyRun(int index)` | Paragraph.java:197 | 深拷贝 run（rPr 继承） |
| `addInsertion(author, List<String> texts)` | Paragraph.java:277 | tracked 多 run ins |
| `replaceTrackedRange(...)` | （§6.5 新增） | 范围级最小 diff 修订替换 |
| `addSimpleField(instruction)` / `addSimpleField(instruction, cachedResult)` | Paragraph.java:915 / :959 | 域写入；双参版五 run（begin/instr/separate/result/end），返回 result run 可链式设样式 |
| `removeInlineElement(int)` | Paragraph.java:1019 | 删 run（整段重建用） |
| `heading(level)` / `alignment(alignment)` | Paragraph.java:1057 / :1091 | 段落属性 |

`nondocx-core/.../api/text/Run.java`：

| 方法 | 行号 |
|---|---|
| `bold()` / `bold(boolean)` | Run.java:88 / :82 |
| `italic()` / `italic(boolean)` | Run.java:109 / :103 |
| 字体：`DocumentCompiler.applyRunFonts(run, ascii, eastAsia)`（toolkit 侧工具方法，PatchEngine.java:416 用于 code span） | — |

**行内标记写入 header 段落的路径**：`ensureHeader().addParagraph()` 拿到 Paragraph → 逐 span `paragraph.addRun(span.text())` → `applySpanFormat(run, span, profile)`（PatchEngine.java:408-418）。Header.addParagraph 返回的就是普通 `Paragraph`，与正文段完全同一 API——无任何 header 特殊性。

## Caveats / Not Found

- Header/Footer 无「替换第 N 段文本」「清空」便捷 API；若 op 语义要求「设置页眉」（替换而非追加），需明确策略（追加先例 vs 经 paragraphs() 改写）。PRD 未定，属设计决策。
- `Footer.java` 未逐行读，但类注释与用法（doc.footer().text() 等）显示与 Header 同构。
