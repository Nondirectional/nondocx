# 07 · nondocx-toolkit

`nondocx-toolkit` 把 nondocx 的 docx 读写能力**封装成 LLM Agent 可调用的工具集**。它不是给人类用的 API（人类直接用 `nondocx-core`），而是给 **Agent × docx** 场景：让 LLM 像操作「文档编辑器」那样读取、编辑、保存 `.docx`。

> 底层基于 nonchain 的 `ToolRegistry` + `@ToolDef`。工具方法调的是 `nondocx-core` 的活对象 API，不重新实现任何 docx 语义。

---

## 1. 它解决什么问题

直接给 Agent 用 `nondocx-core` 不顺手 —— LLM 工具调用需要：

1. **粗粒度、自描述**的操作（不是 `run(0).bold()` 这种细粒度链式）
2. **字符串句柄**定位文档（Agent 不持有 Java 对象引用，靠 `docId`）
3. **错误以字符串返回**（不是抛异常，让 LLM 看到错误消息自己重试）
4. **批量操作**（一次调用处理多个目标，减少 LLM 往返）

`nondocx-toolkit` 把 `Document`/`Paragraph`/`Run` 的活对象 API 包装成满足以上四点的工具方法。

---

## 2. 一个最小可用例子

```java
import com.non.chain.agent.Agent;
import com.non.chain.tool.ToolRegistry;
import com.non.docx.toolkit.DocxToolkit;

// 1) 装上工具集
DocxToolkit toolkit = new DocxToolkit();
ToolRegistry registry = toolkit.scanAll(new ToolRegistry());

// 2) 组装 Agent
Agent agent = Agent.builder(llm, registry)
    .systemPrompt("你是 docx 文档编辑助手……")
    .build();

// 3) 跑起来 —— Agent 会自己调 open_docx / read_paragraph / save_docx 等工具
agent.run("把 /tmp/a.docx 第一段第一个 run 的文本改成 'Hello'");
```

> 取自 [`DocxAgentExample.java`](../nondocx-examples/src/main/java/com/non/docx/examples/agent/DocxAgentExample.java)，可运行（需 `DASHSCOPE_API_KEY`）。

非 Agent 场景也可直接持有 toolkit 的字段逐个调工具方法（见 §6）。

---

## 3. 七组工具 + 一个门面

工具按功能域分成**七个类**，由 `DocxToolkit` 门面统一装配：

| 字段 | 类型 | 职责 |
|---|---|---|
| `session` | `SessionTools` | open/save/close + 文档结构概览（**会话状态的源头**） |
| `body` | `BodyTools` | 正文 run / 超链接 / 文本搜索 |
| `table` | `TableTools` | 单元格读 / 单元格内 run 读改 |
| `headerFooterToc` | `HeaderFooterTocTools` | 页眉页脚 + 目录（只读） |
| `trackedChangeQuery` | `TrackedChangeQueryTools` | 修订查询 / accept / reject |
| `trackedChangeAuthoring` | `TrackedChangeAuthoringTools` | 修订创作：insert/delete/replace/move/mark |
| `qualityCheck` | `QualityCheckTools` | 版式/兼容性自检（10 项检查，报告不修复） |

### 为什么需要门面（会话状态共享）

七个工具类必须**共享同一份** `sessions`/`seq`：

> Agent 在一轮对话里 `open_docx`（SessionTools）打开的文档，紧接着 `read_paragraph`（BodyTools）、`list_tracked_changes`（TrackedChangeQueryTools）都要能按 `docId` 取回。

门面负责把 `SessionTools` 自建的会话状态**注入**给其它六个类：

```java
// DocxToolkit 构造函数内部
this.session = new SessionTools();                          // 自建 sessions/seq
this.body = new BodyTools(session.sharedSessions(), session.sharedSeq());   // 注入
this.table = new TableTools(session.sharedSessions(), session.sharedSeq());
// ... 其余五类同样注入
```

`scanAll(registry)` 把七个类逐一 scan 进同一个 registry，让 Agent 一次会话能调任意一组工具。

> **线程模型**：为单 Agent 实例设计，内部状态未做并发保护，**不要跨 Agent 共享**同一个 `DocxToolkit`。

---

## 4. 工具清单（按组）

### SessionTools（会话）

| 工具 | 作用 |
|---|---|
| `open_docx` | 打开文件，返回 `docId` 句柄 |
| `save_docx` | 按 `docId` 保存到 `output_path`（覆盖写） |
| `close_docx` | 关闭并释放会话（幂等） |
| `get_document_overview` | 文档结构概览：正文段落数、正文表格数、body 元素数、section 数 |

### BodyTools（正文）

| 工具 | 作用 | 批量 |
|---|---|---|
| `read_paragraph` | 读段落（含 run 列表） | ✅ `paragraph_indexes` 数组 |
| `update_paragraph_alignment` | 改段落对齐（LEFT/CENTER/RIGHT/JUSTIFY） | ✅ `edits` 数组 |
| `read_run` | 读单个 run | ✅ `runs` 数组 |
| `replace_run_text` | 改 run 文本 | ✅ `edits` 数组 |
| `update_run_style` | 改 run 样式（bold/italic/underline/font/font_size/color） | ✅ `edits` 数组 |
| `insert_paragraph` | 按 body 顺序插入段（开头/中间/末尾） | ✅ `paragraphs` 对象数组 |
| `read_hyperlink` | 读超链接 | 单条 |
| `update_hyperlink` | 改超链接 text/url（都可选） | 单条 |
| `search_text` | 跨容器文本搜索（页眉页脚也命中） | 返回多结果 |

### TableTools（表格）

| 工具 | 作用 | 批量 |
|---|---|---|
| `create_table` | 末尾创建表格并按二维数组填充单元格 | `rows` 二维数组 |
| `set_table_borders` | 设置表格边框（当前支持 NONE 无边框） | 单条 |
| `merge_table_cells` | 合并单元格（HORIZONTAL / VERTICAL） | ✅ `merges` 对象数组 |
| `read_table_cell` | 读单元格 | ✅ `cells` 数组 |
| `read_table_cell_run` | 读单元格内 run | 单条 |
| `replace_table_cell_run_text` | 改单元格 run 文本 | ✅ `edits` 数组 |

### HeaderFooterTocTools（页眉页脚 + 目录，只读）

| 工具 | 作用 |
|---|---|
| `read_header_footer` | 读页眉/页脚段落（`part=HEADER/FOOTER`） |
| `read_toc` | 读首个目录的条目 |

### TrackedChangeQueryTools（修订查询 + 处理）

| 工具 | 作用 | 批量 |
|---|---|---|
| `get_tracked_changes_enabled` | 查修订开关 | — |
| `set_tracked_changes_enabled` | 改修订开关 | — |
| `list_tracked_changes` | 枚举修订 | — |
| `get_tracked_change` | 按 id 取单条 | — |
| `apply_tracked_changes` | 按 `action=ACCEPT/REJECT`、`target=TEXT_OR_MOVE/PROPERTY/CELL` 处理指定 ids | ✅ `ids` |
| `apply_text_revisions` | 按 `action=ACCEPT/REJECT`、`scope=ALL/AUTHOR` 批量处理文本/移动类修订 | — |

> 修订的 family gate / 异常契约 与 core 一致，详见 [05/03 accept-reject](./05-tracked-changes/03-accept-reject.md)。

### TrackedChangeAuthoringTools（修订创作）

| 工具 | 作用 | 批量 |
|---|---|---|
| `insert_tracked_run` | tracked 插入 | ✅ `edits`（共享 author） |
| `delete_run_tracked` | tracked 删除 | ✅ `edits` |
| `replace_run_tracked` | tracked 替换 | ✅ `edits` |
| `mark_style_change_tracked` | rPrChange 创作（bold/italic/color） | 单条 |
| `mark_tracked_cells` | 按 `change_type=INSERTED/DELETED` 标记 cell 存亡 | ✅ `cells` |
| `move_run_tracked` | 移动 run | 单条 |

### QualityCheckTools（文档质量自检）

对内存中的文档跑版式/兼容性自检，返回 ❌/⚠️/✅ 报告。让 Agent 写完文档后能自查「版式有没有问题」，而不必肉眼排查或反复打开 Word/WPS 验证。

| 工具 | 作用 |
|---|---|
| `check_quality` | 跑版式/兼容性自检，返回结构化报告（可选 `checks` 数组过滤） |

**10 项内置检查**（借鉴 docx skill 的 `postcheck.py`，但走 nondocx 内存活对象 API 而非解包读 XML）：

| 检查项 | 报警条件 | 级别 |
|---|---|---|
| `blank-pages` | 连续 ≥3 个空段（可能产生空白页） | ⚠️ |
| `line-spacing` | 正文存在 ≥2 种行距 | ⚠️ |
| `table-pagination` | 多行表格首行未设 `headerRow` 或数据行未设 `cantSplit` | ⚠️ |
| `image-overflow` | 图片宽度超过页面可用区 | ❌ |
| `font-fallback` | 使用了白名单外的罕见字体（目标系统可能缺失） | ⚠️ |
| `cjk-indent` | CJK 正文段落无首行缩进 | ⚠️ |
| `heading-levels` | 标题层级跳级（如 H1→H3） | ⚠️ |
| `shading-solid` | 误用 SOLID 底纹（WPS 渲染为黑块，见 [renderer-compatibility.md](../.trellis/spec/backend/renderer-compatibility.md#shading-solid) `#shading-solid`） | ❌ |
| `toc` | 有标题无 TOC 域，或有 TOC 域但无 Heading 标题 | ⚠️ |
| `cleanliness` | 占位符（TODO/待填写）/ Markdown 残留（`**bold**`/`[link](url)`） | ⚠️ |

**只报告不修复**——检查发现问题后，Agent 调用其它工具方法修复（如 `table.row(0).headerRow(true)` 修表格分页、`section.cleanEmptyPageNumbering()` 清空页码元素）。

**调用示例**：

```json
check_quality({
  "doc_id": "d1",
  "checks": ["shading-solid", "table-pagination"]  // 可选；空则全量
})
```

返回示例：

```
📋 文档质量自检报告: d1
  ❌ [shading-solid] 误用 SOLID 底纹（WPS 显示黑块）：单元格（表格1,行2,列1）。见 renderer-compatibility.md#shading-solid
  ⚠️ [table-pagination] 表格 1 首行未设 headerRow（跨页时表头不重复）
  ───
  通过 0/2 | ❌ 1 errors | ⚠️ 1 warnings
```

---

## 5. 单次 vs 批量调用（v2 升级）

核心工具支持**一次操作多个目标**。单次调用时传长度 1 的数组即可，调用方不用区分两套 API。

**批量示例**（一次改三个 run）：

```json
replace_run_text({
  "doc_id": "d1",
  "edits": [
    {"paragraph_index": 0, "run_index": 0, "text": "新文本1"},
    {"paragraph_index": 0, "run_index": 2, "text": "新文本2"},
    {"paragraph_index": 1, "run_index": 0, "text": "新文本3"}
  ]
})
```

- **部分失败不中断**：批量操作中某条失败不影响其它条，返回每条的成功/失败明细
- **同段删/换多个 run 不索引漂移**：`delete_run_tracked`/`replace_run_tracked` 自动按从后往前顺序处理、自动去重
- **减少 LLM 往返**：要同时改/读多处时优先用批量

> 这套批量能力是 toolkit 相对于直接用 core 活对象 API 的主要价值之一 —— LLM 一轮思考能处理多个目标，不必为每个 run 单独发起一次工具调用。

---

## 6. 两套工作方式：直接编辑 vs 修订模式

`DocxAgentExample` 的 SYSTEM_PROMPT 把 Agent 工作方式显式分成两套：

| | 直接编辑 | 修订模式（tracked changes） |
|---|---|---|
| 工具 | `replace_run_text` / `replace_table_cell_run_text` / `update_*` | `insert_tracked_run` / `replace_run_tracked` / `delete_run_tracked` / `mark_*` |
| 效果 | 改动立即生效、不留痕 | 改动以修订标记写入，可审阅后定稿 |
| 适用 | 用户要「直接改好」 | 用户要审阅改动、可选接受/拒绝 |

Agent 按用户意图选择。配合 `search_text` 先定位再改，能显著降低 LLM 越界率（避免逐段 `read_paragraph` 盲读）。

---

## 7. 非 Agent 用法（直接持有字段）

测试或非 Agent 场景可直接调工具方法：

```java
DocxToolkit tk = new DocxToolkit();
String docId = tk.session.openDocx("/path/to/a.docx");
System.out.println(tk.body.readParagraph(docId, List.of(0)));  // 读第 0 段
System.out.println(tk.trackedChangeQuery.listTrackedChanges(docId));  // 枚举修订
tk.session.closeDocx(docId);
```

工具方法的 Java 签名就是带 `@ToolDef` 注解的 public 方法，参数对应工具 schema 的字段。

---

## 8. Maven 坐标

```xml
<dependency>
  <groupId>com.non</groupId>
  <artifactId>nondocx-toolkit</artifactId>
  <version>0.0.1-SNAPSHOT</version>
</dependency>
```

传递引入 `nondocx-core`。Agent 框架（nonchain）需消费方自行引入。

---

## 9. 多子代理编排层（orchestration）

七组工具 + 门面是「单 Agent 直连全部工具」的用法。当编辑任务变复杂（多轮、多领域、需计划/审查/提交层）时，toolkit 还提供一套更可控的编排层：**RouterAgent + 多专业子代理 + 唯一提交通道**。

### 9.1 为什么需要编排层

| 单 Agent 直连 | 多子代理编排 |
|---|---|
| 一个 Agent 持有全部工具，prompt 负担重 | 按工具组拆专家，每个专家只懂本组 |
| LLM 直接调写工具，无统一提交边界 | 写入经 `CommitCoordinator` 串行提交 |
| 多轮编辑缺计划/审查层 | 有 `ExpertPlan` → `MergedPlan` → review → commit 显式阶段 |
| 无冲突检测 | 分层冲突检测（粗粒度 `ConflictKey` + 字段级判断） |

### 9.2 架构总览

```
调用方
  ↓
DocxOrchestrator（对外 facade）
  ↓
RouterAgent（调度核心，状态机 ANALYZE→PLAN→REVIEW→COMMIT→DONE/FAILED）
  ├─ SnapshotBuilder（构建 DocumentSnapshot 共享事实层）
  ├─ ReadCoordinator（限流只读补读：per-doc=1, global=4）
  ├─ Expert SubAgents（BodyAgent / TableAgent / RevisionAgent / HeaderTocAgent / QualityAgent）
  ├─ Review Engine（条件触发，APPROVED/WARNED/BLOCKED/SKIPPED）
  └─ CommitCoordinator（唯一写入口，非事务、遇错即停）
        ↓
     DocxToolkit → nondocx-core → Apache POI
```

### 9.3 两层 API

**高层** `run(...)` / `chat(...)`——默认只返回 `RunSummary`（摘要 + 精简操作清单 + 统计）：

```java
DocxOrchestrator orch = DocxOrchestrator.create();
orch.experts().register(new BodyAgent());
orch.executors().register(new BodyExecutor(orch.toolkit().body));
String conv = orch.open(Path.of("a.docx"));
RunSummary summary = orch.run(conv, "把『你好』改成『Hello』");
// summary.executedCount() / warnedCount() / skippedCount() / blockedCount()
```

**低层** `analyze(...)` / `plan(...)`——返回完整阶段产物（snapshot / mergedPlan / review / commitResult），用于调试与精细控制：

```java
DocumentSnapshot snap = orch.analyze(conv);
RouterResult result = orch.plan(conv, "...");
// result.snapshot() / result.mergedPlan() / result.commitResult()
```

### 9.4 会话模型

- **单会话单文档**：一个 `conversationId` 只服务一份活跃文档。
- **切文档新会话**：切换到另一份文档必须新开会话，不复用旧 memory。
- **reopen 递增代次**：close + reopen 同一文档，`sessionGeneration++`，使旧快照与旧 plan 失效。
- **docId 不外露**：对外只暴露 `conversationId`；底层 `docId` 只在 orchestrator/coordinator 内部流转。

### 9.5 提交与失败语义

- **非事务**：`CommitCoordinator` 按固定优先级（结构→文本/样式→修订→质量→保存前检查）顺序执行，遇错即停，**不自动回滚**。
- **BLOCKED 整批停**：`MergedPlan` 中存在任一 `BLOCKED` operation，整批不进 commit。
- **WARNED 可提交**：允许提交但 warning 显式暴露在结果/日志/trace。
- **SKIPPED 保留记录**：被去重/超范围跳过的 operation 保留原因与来源链（`mergedIntoOperationId`）。
- **失败后 reopen**：提交失败不在半修改内存态上补救，必须 close + reopen 重新打开文档基线。

---

## 10. 可运行示例

| 示例 | 演示 |
|---|---|
| [`DocxOrchestratorExample.java`](../nondocx-examples/src/main/java/com/non/docx/examples/agent/DocxOrchestratorExample.java) | 编排层高层 run / 低层 plan / debug 三条路径（启发式专家，无需 API key） |
| [`DocxAgentExample.java`](../nondocx-examples/src/main/java/com/non/docx/examples/agent/DocxAgentExample.java) | LLM 直连工具的两段流程：读结构 → 执行编辑（直接编辑 + 修订模式） |
| [`InteractiveDocxAgentExample.java`](../nondocx-examples/src/main/java/com/non/docx/examples/agent/InteractiveDocxAgentExample.java) | LLM 交互式对话，不限迭代次数 |

`DocxAgentExample` / `InteractiveDocxAgentExample` 需 `DASHSCOPE_API_KEY` 环境变量；`DocxOrchestratorExample` 用启发式专家，无需 key 即可运行。

---

## 下一步

- 想知道 Agent 调工具时 docx 失败怎么呈现给 LLM → [08 · 异常与 raw 领地](./08-exceptions-and-raw.md)
- 想了解工具背后的修订语义 → [05 · tracked changes 教程](./05-tracked-changes/README.md)
- 查具体方法 → [03 · API 速查](./03-api-reference.md)
