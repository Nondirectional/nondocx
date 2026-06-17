# 实现计划 — examples 中新增 nonchain Agent docx 示例

> 配套：`prd.md`（需求验收）、`design.md`（技术设计）。本文件是**有序执行清单 + 验证 + 回滚点**。
>
> 实现顺序遵循依赖：**先 core（被依赖方）→ 再 examples（依赖方）**。每步完成即验证，不攒到最后。

---

## 前置说明

- 实现遵守 `.trellis/spec/backend/{index,poi-bridge,quality-guidelines,error-handling}.md` 与 `.trellis/spec/guides/teaching-approach.md`。
- 每引入一个 docx 概念（超链接关系、表格嵌套段落等），在代码注释/对话中按 **OOXML → POI → nondocx** 三层讲。
- 中文交付：代码注释、Javadoc、异常消息、工具描述均为中文。
- 不用子代理：全部在主对话实现。

---

## Phase 0 — 实现前准备

- [ ] **0.1** 跑一遍现状基线：`cd <repo> && mvn -q -pl nondocx-core,nondocx-examples -am verify`，确认当前是绿的（本任务一切问题的对照基线）。
- [ ] **0.2** 确认 `DASHSCOPE_API_KEY` 在当前 shell 可用（`echo $DASHSCOPE_API_KEY` 非空）。示例需真实 LLM 才能跑通；没有 key 时仍可编译 + 跑 core 测试，但 `main` 不能端到端验证。

---

## Phase 1 — nondocx-core：Hyperlink 最小可写封装

> 目标：补 `Hyperlink.text(String)` / `Hyperlink.url(String)`，让 example 不必碰 `raw()`。这是被动需求，优先做且独立验证。

- [ ] **1.1** 先讲清 OOXML / POI 三层（教学指南）：
  - OOXML：`w:hyperlink` 内的 `w:r` 承载文本；URL 在关系部分，`r:id` 引用。
  - POI：`XWPFHyperlinkRun` 继承 `XWPFRun`，文本走 `setText`；URL 需操作 `PackagePart` 关系。
  - nondocx：把脏活封进 setter，对外暴露中文 fluent API。
- [ ] **1.2** 实现 `Hyperlink.text(String text)`：
  - 委托 `raw().setText(text)`（`XWPFHyperlinkRun` 继承自 `XWPFRun`，路径干净）。
  - 全中文 Javadoc，`@throws IllegalArgumentException`（null 入参）。
  - 返回 `this`。
- [ ] **1.3** 实现 `Hyperlink.url(String url)`：
  - **风险步骤**（design §4.3）。先写最小实现：取 `rId` → `getDocument().getPackagePart().removeRelationship(rId)` → `addExternalRelationship(url, XWPFRelation.HYPERLINK)` → 必要时更新 `getCTHyperlink().setRid(newRid)`。
  - POI/XmlBeans 异常包装为 `DocxOperationException`（中文消息 + 上下文）。
  - 返回 `this`。
- [ ] **1.4** 写 round-trip 测试 `HyperlinkTest`（nondocx-core `src/test/java/.../api/text/`）：
  - `text` setter：构造文档 → `addHyperlink` → `text("新文本")` → `save` → `open` → 断言 `text()` 读回新值。
  - `url` setter：同上，断言 `url()` 读回新 URL（**这是 POI 脏活的兜底测试，必须过**）。
  - 覆盖 null 入参抛 `IllegalArgumentException`。
- [ ] **1.5** 验证 core：`mvn -q -pl nondocx-core verify`。
  - **回滚点 R1**：若 §1.3 POI 改 URL 在 5.2.5 上无法干净实现（关系重分配失败），按 design §4.3 回退——core 暂只交付 `text(String)`，`url(String)` 降级，并更新 design/prd 记录该限制。先别硬刚 POI。
- [ ] **1.6** 若 §1.3 发现非显然 POI 行为，用 `trellis-update-spec` 把它沉淀成 `poi-bridge.md` 的一条 gotcha（如 N9）。

---

## Phase 2 — nondocx-examples：依赖与骨架

- [ ] **2.1** 在**父** `pom.xml` 加 `<chain.version>0.8.4</chain.version>` 与 `dependencyManagement` 条目（与 `poi.version` 风格一致）。
- [ ] **2.2** 在 `nondocx-examples/pom.xml` 加 `<dependency> com.non:chain </dependency>`（版本走父管理）。
- [ ] **2.3** 编译验证：`mvn -q -pl nondocx-examples -am compile`。
- [ ] **2.4** 依赖冲突检查：`mvn -q -pl nondocx-examples dependency:tree`，目测 POI 只出现一个版本（5.2.5）。若 `chain` 传递引入了不同 POI 版本，记录并排到 design 风险。

---

## Phase 3 — 样例输入文档

- [ ] **3.1** 写一次性生成脚本（`src/test/java/.../examples/SampleDocGenerator.java` 或临时 main），按 design §5 结构生成：
  - 3~4 段正文（含多 run 段、含 1 个超链接的段）。
  - 1 个表格（表头 + 2~3 数据行，至少 1 cell 含 2 run）。
- [ ] **3.2** 跑生成脚本，产物落到 `nondocx-examples/src/main/resources/document/sample-agent-input.docx`。
- [ ] **3.3** 用 nondocx 打开产物自检（临时断言或打印），确认结构符合预期（段落数、表格数、超链接数）。
- [ ] **3.4** 决定生成脚本去留：保留为 fixture 生成器（推荐，放 `src/test`）或删除。记录在 design §5。

---

## Phase 4 — Agent 工具类 `DocxAgentTools`

- [ ] **4.1** 建 `src/main/java/com/non/docx/examples/agent/DocxAgentTools.java`，内含 `Map<String, Document> sessions` + 自增 docId。
- [ ] **4.2** 实现文档会话工具：`open_docx` / `save_docx` / `close_docx`（design §3.1-A）。
  - `open_docx`：`Docx.open(Path.of(path))` → 存入 sessions → 返回 `"doc-<n>"`；打开失败返回中文错误串。
  - `close_docx`：`close()` + `remove`；幂等。
- [ ] **4.3** 实现正文工具（design §3.1-B）：`get_paragraph_count` / `read_paragraph` / `read_run` / `replace_run_text` / `append_paragraph`。
  - 统一 `doc(docId)` 私有方法取文档（找不到返回中文错误串）。
  - `read_paragraph` 返回结构摘要（文本 + run 数 + 是否含超链接）。
- [ ] **4.4** 实现表格工具（design §3.1-C）：`get_table_count` / `read_table_cell` / `read_table_cell_run` / `replace_table_cell_run_text`。
  - 寻址链 `doc.table(i).row(j).cell(k).paragraph(p).run(r)`。
- [ ] **4.5** 实现超链接工具（design §3.1-D）：`read_hyperlink` / `update_hyperlink_text` / `update_hyperlink_url`。
  - 超链接从 `paragraph.inlineElements()` 过滤 `Hyperlink` 实例得到（与 nondocx 模型一致，而非 `runs()`）。
  - `update_*` 调用 Phase 1 新增的 core setter。
- [ ] **4.6** 全部工具方法：`@ToolDef` + `@ToolParam`，中文 description。
- [ ] **4.7** 编译验证：`mvn -q -pl nondocx-examples compile`。

---

## Phase 5 — Agent 示例 `DocxAgentExample`

- [ ] **5.1** 建 `src/main/java/.../agent/DocxAgentExample.java`，按 design §6 组装 `Agent`。
- [ ] **5.2** 写 `copyResourceToWorking(...)`：把 classpath 的 `document/sample-agent-input.docx` 复制到 `target/examples-output/`，返回路径。
- [ ] **5.3** 写中文 `SYSTEM_PROMPT`：约束 Agent 只用这些 docx 工具、路径由用户消息给定、遇错误串可重试。
- [ ] **5.4** 复刻 `AgentLoopExample` 的日志 `ChainCallback`（打印 LLM/Tool 事件）。
- [ ] **5.5** 两段流程（design §6）：第一段读取汇报，第二段编辑保存到 `agent-edited.docx`。prompt 里**预置**路径与索引（稳定性取舍 §6.1）。
- [ ] **5.6** 编译验证：`mvn -q -pl nondocx-examples compile`。

---

## Phase 6 — 端到端验证（需 DASHSCOPE_API_KEY）

- [ ] **6.1** 端到端运行：`mvn -q -pl nondocx-examples exec:java -Dexec.mainClass=com.non.docx.examples.agent.DocxAgentExample`（或 IDE 跑 main）。
  - 观察日志：Agent 是否按预期调用 `open_docx → read_* → close_docx`，再 `open_docx → replace_* / update_* → save_docx → close_docx`。
- [ ] **6.2** 客观断言：`target/examples-output/agent-edited.docx` 已生成；用 nondocx 打开它，人工/断言核验第二段要求的改动是否落到文档里（run 文本、单元格、超链接 text+url）。
- [ ] **6.3** 稳定性备注：LLM 输出不可逐字复现，验收以"流程跑通 + 输出文件客观改动"为准（design §6.1）。

---

## Phase 7 — 收尾

- [ ] **7.1** 全量验证：`mvn -q verify`（全模块 compile + test + spotless）。
- [ ] **7.2** spotless：`mvn -q spotless:apply` 后再 `spotless:check` 必须绿。
- [ ] **7.3** 勾选 prd.md 的 Acceptance Criteria，逐条标注证据（文件/命令）。
- [ ] **7.4** 若 Phase 1 触发了新 POI gotcha，确认已进 `poi-bridge.md`（trellis-update-spec）。
- [ ] **7.5** 提交（Phase 3.4）：按仓库风格分逻辑 commit（core Hyperlink 封装 / examples Agent 示例 / 样例文档 + 依赖）。

---

## 风险与回滚点速查

| 点 | 风险 | 回滚/回退 |
|----|------|-----------|
| R1（§1.3/1.5） | POI 改超链接 URL 在 5.2.5 不可行 | core 只留 `text(String)`，`url(String)` 降级；更新 prd/design |
| R2（§2.4） | `chain` 传递引入冲突 POI 版本 | exclusion 或固定版本；记录到 design |
| R3（§6.1） | LLM 不稳定，Agent 越界/选错工具 | prompt 预置索引 + 工具返回中文错误串（已内置）；必要时收窄工具集 |

## 关键验证命令

```bash
# 基线
mvn -q -pl nondocx-core,nondocx-examples -am verify

# core 单测（含新 Hyperlink 测试）
mvn -q -pl nondocx-core verify

# examples 编译
mvn -q -pl nondocx-examples -am compile

# 依赖树（查 POI 版本冲突）
mvn -q -pl nondocx-examples dependency:tree

# 端到端（需 DASHSCOPE_API_KEY）
mvn -q -pl nondocx-examples exec:java -Dexec.mainClass=com.non.docx.examples.agent.DocxAgentExample

# 全量 + 格式
mvn -q verify
mvn -q spotless:apply && mvn -q spotless:check
```

---

**Language**: 本计划及所有交付代码均使用**中文**。
