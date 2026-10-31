# Research: footer.set_page_number 完整实现链（页眉写 op 的对齐模板）

- Query: footer.set_page_number 的注册/解析/执行链、参数 schema、工具描述与提示词、错误处理与返回值、需同步修改文件清单
- Scope: internal
- Date: 2026-10-31

## Findings

### 1. PatchEngine 中的实现链（nondocx-toolkit/src/main/java/io/github/nondirectional/docx/toolkit/compiler/PatchEngine.java）

| 环节 | 位置 | 说明 |
|---|---|---|
| 类 javadoc op 集清单 | PatchEngine.java:46-47 | `{@code footer.set_page_number}`——页脚追加页码段落，`format` 支持 `{page}`/`{pages}` 占位符（PAGE/NUMPAGES 域，带缓存结果 "1"） |
| op 注册（dispatch） | PatchEngine.java:198-199 | `case "footer.set_page_number": return planFooterPageNumber(index, op);`。注意：**不调 `claimAnchor`、不传 anchor/snapshot**（区别于 replace_text/tracked.*） |
| 未知 op 报错文案 | PatchEngine.java:203-210 | 可用 op 列表字符串里含 `footer.set_page_number/`——新增 header op 需同步此清单 |
| plan 函数 | PatchEngine.java:803-868 | `planFooterPageNumber(int index, Map<String,Object> op)`（签名只收 index+op，无 anchor/snapshot——这就是「不占锚点」的结构性体现） |
| 执行体 | PatchEngine.java:828-867 | 匿名 `PlannedOp("footer.set_page_number \"" + format + "\"")` 子类 |

### 2. planFooterPageNumber 的参数解析与校验（preflight，PatchEngine.java:804-827）

- **参数 schema**：单参数 `format`（可选，字符串）。缺省/空白 → 默认 `"第 {page} 页"`（PatchEngine.java:805-808）。**没有 anchor**。
- 校验（两段式，先具体后一般）：
  1. 扫全部 `{...}` token：非 `{page}`/`{pages}` 或未闭合 `{` → `PatchException`，消息含具体 token 名（PatchEngine.java:810-823）；
  2. 要求至少含一个占位符，否则 `PatchException`，消息给示例「第 {page} 页 / 共 {pages} 页」（PatchEngine.java:824-827）。
- 校验在 preflight 期（`planOp` 阶段）完成 → 抛异常即零写入。

### 3. 执行体逻辑（PatchEngine.java:828-867）

```java
Paragraph paragraph = doc.ensureFooter().addParagraph();
// 光标扫描 format：{pages} 优先于 {page}（前者是后者的前缀扩展，PatchEngine.java:844）
//   字面片段 → paragraph.addRun(literal)（appendLiteral，空串跳过，PatchEngine.java:870-875）
//   占位符  → paragraph.addSimpleField(isPages ? "NUMPAGES" : "PAGE", "1")  // 五 run 域，缓存值 "1"
changed.add("footer");   // 触碰标记是字符串字面量 "footer"，不是段落短 ID
```

- `addSimpleField(instruction, cachedResult)` 是 core 侧 API（P2 续新增，见 §6.5 要点与 Paragraph.java:959），写 begin/instr/separate/result/end 五 run，缓存结果让 POI 系读者能读到 "1"。
- **追加语义**：每次执行 `ensureFooter().addParagraph()` 往页脚追加一段（不替换已有段）。测试里两次调用页脚段落 1→2（PatchEngineTest.java:862/873）。

### 4. 返回值形态

- `PatchResult`（PatchEngine.java:69-114）：`dryRun` / `applied`（op 数）/ `changed`（触碰锚点列表，footer op 报 `"footer"`）/ `warnings` / `planned`（dry-run 计划文本，footer op 的描述是 `footer.set_page_number "<format>"`，PatchEngine.java:828）。
- 工具层（CompilerTools.editDocument，CompilerTools.java:341-365）：成功 → JSON data `{docId, generation, applied, changed, warnings}` + message「已应用 N 个 op，触碰 M 个锚点…」+ `qualityPostCondition`（qualityIssues）；`dry_run=true` → `{dryRun:true, planned, warnings}`。
- 错误处理（CompilerTools.java:366-374）：
  - `PatchException` → `renderInvalidArgument(e.getMessage() + "（已拒绝，文档未被修改）")`（preflight 失败 = 零写入）；
  - 其它 `RuntimeException` → `ToolResult.fail(INVALID_ARGUMENT, "patch 执行异常（文档可能被部分修改，建议 read_document 核对）: ...")`。
- 前置门槛：`document(docId)` 不存在 → renderDocNotFound；`expectedGeneration` 不匹配 → renderGenerationMismatch（CompilerTools.java:320-328）。

### 5. CompilerTools 工具描述与提示词写法

**edit_document 工具描述**（CompilerTools.java:253-271，`@ToolDef`）中 footer op 一句：

> `footer.set_page_number（页脚页码，format 含 {page}/{pages} 占位符，默认「第 {page} 页」，不占 anchor）、`

（CompilerTools.java:266；句末顿号接下一个 op 描述；末尾总结句「先校验全部 op 再写入：任一 op 非法则整体拒绝、零改动」「每个段落锚点一批里至多一个改文本 op」）。

**ops 参数描述的示例 JSON**（CompilerTools.java:282-287）末尾含 `{"op":"footer.set_page_number"}`（无参数形态）。

**NestedParamCapability 清单**（CompilerTools.java:288-303）：`ops.format → ParamType.STRING`（footer 专用参数在此声明，CompilerTools.java:303）。若 header op 复用 `text` 参数则 `ops.text` 已存在；新增别的参数名需加 `@NestedParamCapability`。

**system prompt 两处**（新增页眉 op 需同步）：

| 位置 | 行号 | 原文 |
|---|---|---|
| nondocx-demo AgentBridge.java `systemPromptFor("compiler")` | AgentBridge.java:296 | `用户要求在页脚插页码时用 footer.set_page_number op（format 如「第 {page} 页」）；` |
| nondocx-examples EvalRunner.java `COMPILER_SYSTEM_PROMPT` | EvalRunner.java:85 | 同上（demo 版改编自 eval，需保持同口径） |

注：AgentBridge.java:264 注释明确「compiler 版改编自 eval 的 COMPILER_SYSTEM_PROMPT（同口径才能复现 A/B 数据）」——两处提示词要同步改。

### 6. 「不占段落锚点」的实现方式（页眉 op 应对齐的点）

1. **dispatch 不认领锚点**：planOp 的 switch 里 footer 分支（PatchEngine.java:198-199）不调 `claimAnchor`（PatchEngine.java:214-232 只约束 tracked.*/replace_text），也不读 `anchor` 字段、不进 `textClaims`。
2. **plan 函数不接触 Snapshot**：签名 `planFooterPageNumber(int index, Map<String,Object> op)`——没有 anchor/snapshot 参数，结构性排除锚点依赖（对比 `planCommentAdd` 仍需 snapshot.requireParagraph）。
3. **写入目标在正文之外**：`doc.ensureFooter().addParagraph()` 操作页脚 part，不动 `doc.bodyElements()`，Snapshot 的 pN/tN 编号不受影响 → 同批可与正文 op（含改文本 op）共存。测试证据：PatchEngineTest.java:863-873（footer op + replace_text p0 同批成功）。
4. **changed 报 `"footer"` 字面量**而非短 ID（PatchEngine.java:865）。
5. 文档化说法：docs/11 §6.5「页脚 op 不占段落锚点，可与正文 op 同批」；工具描述里「不占 anchor」。

页眉写 op 对齐方式：dispatch case 不调 claimAnchor、plan 函数签名 `(int index, Map op)`、执行体 `doc.ensureHeader().addParagraph()`、`changed.add("header")`。

### 7. 需同步修改的文件清单（以 footer 先例推导）

| 文件 | 修改点 | footer 先例位置 |
|---|---|---|
| `nondocx-toolkit/.../compiler/PatchEngine.java` | ① 类 javadoc op 集清单 ② planOp switch case ③ 未知 op 报错文案 ④ plan 函数（含参数校验）⑤ op 集计数注释「op 集（v2，11 种）」若有 | :46, :198, :209, :803-868, :29 |
| `nondocx-toolkit/.../CompilerTools.java` | ① edit_document 描述加 op 一句 ② ops 参数示例 JSON ③ NestedParamCapability（若新参数名） | :266, :287, :303 |
| `nondocx-toolkit/.../compiler/MarkdownView.java` | read_document 读侧元数据行（本任务含「页眉元数据行」）：render() 尾部仿页脚 `<!-- 页脚: … -->` 加 `<!-- 页眉: … -->`；javadoc「已知取舍」需改（现写明「页眉…不在本视图内」，:20-22） | :84-92（页脚先例） |
| `nondocx-toolkit/.../compiler/PatchEngineTest.java` | 新 op 的引擎级测试（校验拒绝 + 写入 + 不动正文 + 同批） | :799-877 |
| `nondocx-toolkit/.../CompilerToolsTest.java` | 工具级测试（edit_document 带 header op + read_document 读回元数据行） | :233-263 |
| `nondocx-toolkit/.../compiler/CompilerPipelineTest.java` | MarkdownView 元数据行 + round-trip 测试（若改 MarkdownView 必加，注释形态被解析器剥掉） | :221-234 |
| `nondocx-demo/.../AgentBridge.java` | compiler 面 system prompt 加一句 | :296 |
| `nondocx-examples/.../eval/EvalRunner.java` | COMPILER_SYSTEM_PROMPT 同步加一句（与 demo 同口径） | :85 |
| `nondocx-examples/.../eval/EvalTasks.java` | eval 新增页眉写文本任务（baseline 组） | :361-384（footer-page-number 先例） |
| `.trellis/spec/backend/agent-single.md` | spec 中 op 能力描述（若能力清单计数变化） | :297-305 |
| `docs/07-toolkit.md` / `docs/11-agent-authoring-architecture.md` | 工具文档/架构文档 op 集（视惯例） | docs/07 含 set_page_number |

注：core 侧无需新增（ensureHeader 已存在，Paragraph API 齐备）——正是任务背景「core 复用 ensureHeader 最小化新增」的含义。

## Caveats / Not Found

- op 集大小注释（「op 集（v2，11 种）」PatchEngine.java:29）当前是 v3（11 种含 comment.add）；新增后是 12 种，计数文案要改。
- 未发现单独的「能力清单 66」常量文件；能力计数疑由注解扫描生成（CompilerTools 的 @NestedParamCapability/@ToolCapability），加 op/参数后计数自动变。
