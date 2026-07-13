# Orchestration Layer

> How the `nondocx-toolkit` orchestration layer maps LLM-produced operations to POI writes.

---

## Overview

The orchestration layer lives in `nondocx-toolkit/.../orchestration/`. It sits between the LLM
(produces JSON operation plans) and the toolkit/domain layer (`BodyTools`, `TableTools`, core
`Document`). Its job is to translate each `Operation` into a concrete write call, with merge,
review, and commit safety.

```
LLM → ExpertPlan(JSON) → MergedPlan → CommitCoordinator → OperationExecutor → toolkit/core
       (plan only)         (dedup)       (serial commit)    (per-domain switch)    (actual write)
```

---

## Scenario: 协商与显式授权实施

### 1. Scope / Trigger

Demo 出现多轮沟通需求时，普通聊天不得复用旧 `ANALYZE → PLAN → COMMIT` 自动路径。协商只读；写入只发生在单独的授权端点。

### 2. Signatures

- `POST /api/chat`：`{"message":"..."}`，只进入 `PrimaryConversationAgent`。
- `POST /api/execute`：`{"token":"..."}`，仅接受服务器签发的一次性授权 token。
- `POST /api/cancel`：请求当前实施取消。
- `GET /api/trace`：返回 JSONL trace 回放。
- `DocxOrchestrator.commitPlan(conversationId, mergedPlan)`：实施协调器唯一的显式提交入口。

### 3. Contracts

- 主 Agent registry 只能 scan `ViewTools`；写工具和全量 `CapabilityTools` 都不进入 LLM schema。每个协商 prompt 提供当前 `doc_id`。主 Agent 不得判断写 operation 是否存在或可行。
- 主 Agent 的回复为 `{"reply":"...","requestAuthorization":boolean}`。只有 `true` 才下发 token；文本中的“确认”不执行写入。
- 授权后由主 Agent 输出 `DispatchPlan` JSON；仅允许 `body/table/header-toc/revision/quality`，专家输出必须和其分配组相同。
- Trace 同时 SSE 和 JSONL 追加；落盘前删除 `apiKey`、`authorization`、`Authorization`。

### 4. Validation & Error Matrix

| 条件 | 行为 |
|---|---|
| token 缺失、失效、重复使用或正在执行 | 拒绝 `/api/execute`，零写入 |
| 文档 generation 与授权时不同 | `blocked`，回到协商 |
| 无专家分派、跨组输出或空操作 | `blocked`，不提交 |
| 提交失败或取消 | reopen 未保存磁盘文件，发 `rolled_back` |
| 保存后质量检查失败 | 保留已保存编辑，发 `quality` 报告，不自动修复 |

### 5. Good/Base/Bad Cases

- Good：用户多轮询问文档，主 Agent 调 `view_text`；用户点“开始实施”后才派发 `body` 专家。
- Base：主 Agent 无需工具即可回答，仍只产生协商文本。
- Bad：`/api/chat` 收到“确认执行”即调用 `commitPlan`；这是越过授权边界。

### 6. Tests Required

- 未获得 token 的聊天后，文件字节与会话 generation 不变。
- 失效 token、文档切换、取消、提交失败均不保存部分文档。
- 主 Agent 工具 schema 不包含 `ADD/UPDATE/REMOVE` 工具。
- JSONL 可回放且不含密钥；SSE 包含主 Agent、工具、专家、提交和质量事件。

### 7. Wrong vs Correct

#### Wrong

```java
// 普通聊天直接跑完整旧管线
orchestrator.run(conversationId, message);
```

#### Correct

```java
// chat 仅协商；token 只由 UI 授权端点消费
ChatResult reply = primaryAgent.run(message, trace);
CommitResult result = orchestrator.commitPlan(conversationId, mergedPlan);
```

---

## Scenario: Adding a new Operation kind to an Executor

### 1. Scope / Trigger

When you need to support a new operation kind (e.g., `set_table_borders`, `merge_table_cells`)
in the orchestration path. This is the single most common orchestration-layer change.

### 2. The Four-Point Coverage Rule (CRITICAL)

Every operation kind must be covered in **four** places. Missing any one causes a failure:

| # | Location | What | Failure if missing |
|---|----------|------|--------------------|
| 1 | `*Executor.execute()` switch | `case "kind":` → calls toolkit method | `OperationExecutionException("X 域不支持的 operation kind: ...")` — commit FAILED |
| 2 | `LlmDocxExpert.buildPrompt()` | operation documentation with payload field names | LLM hallucinates a non-existent kind (e.g. `update_table_style`) or wrong field names |
| 3 | `OperationDescriptor.describe()` | human-readable description | frontend shows raw kind name instead of Chinese description |
| 4 | Static `Operation` constructor | `public static Operation xxx(...)` convenience builder | programmatic plan construction (tests, BodyAgent) can't create this operation |

**The toolkit→orchestration coverage gap**: `TableTools` has 25 `@ToolDef` methods (as of the
table-style-gap task group), but `TableExecutor` only connects a subset (5 kinds: cell run text /
cell shading / cell run style / merge / borders). This is a **systematic blind spot** — each missing
kind is discovered only when a user request hits it. When adding an operation, check whether the
underlying toolkit method already exists; if so, it's a pure wiring task (4 points above). The
remaining 20 un-wired kinds span three groups: **read/build** (`create_table`, `read_table_cell`,
`read_table_cell_run` — may not need operation wiring at all, being read-only or whole-table), **new
table style/structure writes** (vertical align, paragraph alignment, header row, cant-split, column
percents/widths/read, row/cell add-remove, paragraph heading/indent/spacing/list/shading), and
`read_table_row`. These are tracked as a follow-up wiring batch.

### 3. Signature Pattern

```java
// In TableExecutor.execute() switch:
case "set_table_borders": {
    int tableIdx = intPayload(payload, "table_index");
    String borderStyle = strPayload(payload, "border_style");
    String result = table.setTableBorders(docId, tableIdx, borderStyle);
    return checkResult(result, operation);
}
```

Two toolkit calling patterns exist:
- **`List<Map>` batch pattern**: `replaceTableCellRunText(docId, List.of(edit))` — most table ops.
- **Direct parameter pattern**: `setTableBorders(docId, tableIdx, borderStyle)` — simpler ops.

### 4. Payload Field Tolerance

LLMs produce field names probabilistically. Executors must normalize common deviations before
calling toolkit. This is the established pattern (see `BodyExecutor.parseHeadingLevel`,
`parseAlignment`, `normalizeBodyIndex`, and `TableExecutor.normalizeMergePayload`).

| Operation | LLM deviation | Normalized to |
|-----------|--------------|---------------|
| `insert_heading` | `style=Heading1`, `heading=1` | `heading_level=H1` |
| `insert_heading` | `position=开头` | `position=start` |
| `insert_paragraph` | `paragraph_index` (no `body_index`) | when no tables: `body_index`; when tables: **reject** |
| `merge_table_cells` | `start_row_index`/`end_row_index` | `from_row_index`/`to_row_index` |
| `merge_table_cells` | missing `direction` | infer from fields (row range → VERTICAL, cell range → HORIZONTAL) |

### 5. Error Detection: ToolResultChecks (双模式)

P0-02 后，toolkit 的 `@ToolDef` 方法返回**双段格式** String（中文消息 + JSON envelope）。
executor 的 `checkResult` 统一委托 `ToolResultChecks.checkResult(result, domain, kind)`，
**双模式**：优先解析 JSON envelope 的 `success`/`code` 字段；解析失败回退旧中文前缀（safety-net）。

```java
// BodyExecutor / TableExecutor 的 checkResult
private static String checkResult(String result, Operation operation)
    throws OperationExecutionException {
  return ToolResultChecks.checkResult(result, "body", operation.kind());
}
```

`ToolResultChecks` 内部：
1. `ToolResultParser.parse(result)` 提取 JSON envelope 的 `success`/`code`/`message`。
2. 解析成功 → 按 `success` 判定；`success=false` 抛 `OperationExecutionException`。
3. 解析失败（无 envelope）→ 回退旧 `startsWith("错误")`/`contains("错误:")`（兼容期 safety-net）。

**禁止**在 executor 或工具内部新增 `startsWith("错误")` / `contains("错误")` 判断。
所有状态判定必须走 `ToolResultChecks` 或 `ToolResultParser`。

### 6. Tests Required

- Snapshot correctness: `SnapshotBodyOrderTest` — verifies bodyIndex/index dual-index when tables present
- Executor dispatch: `DocxToolkitBatchTest` — verifies each toolkit method works
- E2E round-trip: `DocxOrchestratorTest` — open → plan → commit → DONE

### 7. Wrong vs Correct

#### Wrong — only added the executor case
```java
case "set_table_borders": { ... }  // ← done, ship it
// prompt not updated → LLM produces "update_table_style" (hallucinated kind)
// OperationDescriptor not updated → frontend shows raw "set_table_borders"
```

#### Correct — all four points
```java
// 1. Executor case
case "set_table_borders": { ... }
// 2. Prompt doc
sb.append("### 设置表格边框\ntoolGroup=\"table\", kind=\"set_table_borders\"\n...");
// 3. Descriptor
case "set_table_borders": return describeSetTableBorders(p);
// 4. Static builder
public static Operation setTableBorders(...) { ... }
```

---

## Convention: Snapshot Dual-Index

**What**: `ParagraphPreview` and `TablePreview` carry two index fields.

| Field | Semantics | Used by |
|-------|-----------|---------|
| `index` | Projection index — counts only same-type elements (skips the other) | `replace_run_text`, `update_run_style` (`paragraph_index`); `replace_table_cell_run_text` (`table_index`) |
| `bodyIndex` | Body-order index — absolute position in the `<w:body>` interleaved sequence (paragraphs + tables) | `insert_paragraph`, `insert_heading` (`body_index`) |

**Why**: Without `bodyIndex`, the LLM only sees a flat paragraph list `[0] 段落A [1] 段落B` and
cannot know where tables sit in the body order. When a user says "insert at the beginning" and the
document starts with a table, the LLM produces `body_index=0` thinking it targets the first
paragraph, but it actually targets the table — the paragraph lands on the wrong side.

**When tables are absent**: `index == bodyIndex` (they converge).

**Example**: body order `[表格, 段落A, 段落B]`:
- 段落A: `index=0` (first paragraph), `bodyIndex=1` (after the table)
- 表格: `index=0` (first table), `bodyIndex=0` (body start)

**Construction**: `SnapshotBuilder.buildBodyPreviews()` does a single pass over
`doc.bodyElements()`, maintaining `paraIdx`, `tableIdx`, and `bodyIdx` counters.

**Related**: `BodyExecutor.normalizeBodyIndex()` — when the document contains tables and the LLM
only provides `paragraph_index` (not `body_index`), it **throws** rather than silently mapping
(silent mapping causes the paragraph to land on the wrong side of the table).

---

## Scenario: Stable Semantic References

### 1. Scope / Trigger

当 toolkit、snapshot、plan、review 或 commit 需要在多次读写之间持续指向同一文档元素时，
必须使用 `com.non.docx.toolkit.ref` 的强类型引用。位置索引只用于展示和旧 payload 兼容，
不能承担元素身份；在目标前插入新元素后，索引会漂移。

引用协议属于 toolkit/编排适配层，不进入 core 领域 API。core 继续暴露 POI 活对象 wrapper；
toolkit 负责文档 key、generation、opaque session id、canonical codec 和错误码。

### 2. Signatures

```java
public interface ElementRef {
  DocumentRef documentRef();
  ElementKind kind();
  RefStability stability();
  String elementId();
  String canonical();
}

public final class DocumentRef {
  public DocumentRef(String documentKey, long sessionGeneration);
}

public final class ElementResolver {
  public ParagraphRef reference(Paragraph paragraph);
  public RunRef reference(Run run);
  public TableRef reference(Table table);
  public CellRef reference(Cell cell);
  public HeaderFooterRef reference(Header header);
  public HeaderFooterRef reference(Footer footer);
  public RevisionRef reference(TrackedChange change);

  public Paragraph resolve(ParagraphRef ref);
  public Run resolve(RunRef ref);
  public Table resolve(TableRef ref);
  public Cell resolve(CellRef ref);
  public Header resolveHeader(HeaderFooterRef ref);
  public Footer resolveFooter(HeaderFooterRef ref);
  public TrackedChange resolve(RevisionRef ref);
}
```

具体值对象：`ParagraphRef`、`RunRef`、`TableRef`、`CellRef`、`HeaderFooterRef`、
`RevisionRef`。规范化字符串统一由 `ElementRefs.parse(String)` 解析；工具不得自行 `split`。

### 3. Contracts

- `SESSION` ref 绑定 `documentKey + sessionGeneration + opaque id`，只保证当前代次可解析。
- `PERSISTENT` 第一版只用于已有 `w14:paraId` 的正文段落；读取/快照/签发不得自动补 paraId。
- resolver 每次解析必须重新扫描当前活文档树，确认 delegate identity 仍存在；不得直接返回
  registry 里保存的旧 wrapper。
- `ParagraphPreview` / `TablePreview`（snapshot version 2）同时返回 `ref`、投影 `index`、
  `bodyIndex`；三者来自同一 resolver/document generation。
- `ConflictKey.targetRef` 必须保存 `ElementRef`。旧字符串构造器只允许在兼容边界转换为
  `OperationTargetRef`，内部状态不得保存自由字符串。
- 工具 payload 优先字段为 `ref`。旧 `paragraph_index`、`run_index`、表格五级坐标等继续兼容。
- ref 与旧索引同时出现时，先解析 ref，再验证索引是否指向同一 delegate；不一致时拒绝执行。
- 写工具结果必须包含实际 canonical ref，调用方后续继续复用 ref，不复用旧索引。
- `Operation.targetRef` 可暂时保留 canonical/兼容传输字符串，但冲突比较、去重和同目标判断必须
  使用 `ConflictKey.targetRef()` 的强类型值语义。

### 4. Validation & Error Matrix

| 条件 | 稳定错误码 | 行为 |
|---|---|---|
| canonical 格式非法 | `invalid_ref` | 不执行 |
| ref 的元素类型与工具不符 | `ref_type_mismatch` | 不执行 |
| `documentKey` 与当前文档不同 | `document_mismatch` | 不执行 |
| SESSION ref 的 generation 与当前代次不同 | `generation_mismatch` | 不执行 |
| 当前 resolver 不认识 opaque id | `stale_ref` | 不执行 |
| registry 认识目标，但活文档树已找不到 | `element_removed` | 不执行 |
| ref 与同时提供的索引/坐标不一致 | `stale_ref` | 拒绝写入，原目标不变 |
| PERSISTENT paraId 重复，无法唯一定位 | `stale_ref` | 不猜第一个 |

字符串工具边界统一渲染为 `错误[code]：message`；内部按 `RefResolutionCode` 分支，
不得靠中文 message 判断错误类型。

### 5. Good / Base / Bad Cases

- **Good**：先读取 run 得到 `RunRef`，在前方插入新段落，再用旧 ref 修改文本；仍命中原 run。
- **Base**：旧调用方只传完整索引/坐标；工具先定位活对象、签发 ref、执行并在结果返回 ref。
- **Bad**：同时传 `ref=左单元格 run` 和 `cell_index=1`（右单元格）；返回 `stale_ref`，不写任一单元格。
- **Lifecycle**：SESSION ref 在 reopen 后返回 `generation_mismatch`；已有 paraId 的
  PERSISTENT `ParagraphRef` 在同一逻辑文档 reopen 后仍命中。
- **Deletion**：段落、run、表格、单元格、页眉页脚 part 或修订被删除后返回
  `element_removed`，即使旧 wrapper 仍可访问。

### 6. Tests Required

- `ElementRefsTest`：值对象内容相等、canonical round-trip、非法格式。
- `ElementResolverTest`：
  - 前方插入后 SESSION ref 仍命中原对象；
  - 段落/run/表格/单元格删除后为 `element_removed`；
  - SESSION 跨 generation 为 `generation_mismatch`；
  - 已有 paraId 的 PERSISTENT 段落 save/reopen 后命中；
  - 无 paraId 段落签发 ref 前后 XML 完全相同。
- `SnapshotBodyOrderTest`：段落/表格 ref 与 `index/bodyIndex` 同时正确，snapshot version 为 2。
- `DocxToolkitBatchTest`：Body/Table ref 读写成功；ref 与索引不一致返回 `stale_ref` 且不写入。
- `DocxToolkitTrackedChangesTest`：list 返回 `RevisionRef`；按 ref accept/reject；处理后旧 ref 为
  `element_removed`。
- `ConflictKeyTest`：规范化相同的强类型 ref 可 `sameTarget`，不同元素不能去重。

### 7. Wrong vs Correct

#### Wrong — 把位置路径当身份

```java
String target = "paragraph:3";
ConflictKey key = new ConflictKey("body", "replace_run_text", target);
// paragraph 0 前插后，paragraph:3 已指向别的元素。
```

#### Correct — 快照签发 ref，提交前重新解析

```java
ParagraphRef ref = resolver.reference(paragraph);
ConflictKey key = new ConflictKey("body", "replace_run_text", ref);

Map<String, Object> edit = new LinkedHashMap<>();
edit.put("ref", ref.canonical());
edit.put("text", "新文本");
bodyTools.replaceRunText(docId, List.of(edit));
```

`resolver.resolve(ref)` 会校验 document/generation/type，并重扫活文档树；位置变化不影响身份，
删除则稳定失败。

---

## Gotcha: insertParagraph bodyIndex Semantics

> **Warning**: `Document.insertParagraph(bodyIndex)` inserts **before** the element at `bodyIndex`.
> When `bodyIndex` points to a table, the new paragraph lands **before** the table.
> When `bodyIndex == bodySize`, it appends to the end (after all elements including tables).
>
> This is POI's `cursor.beginElement("p")` semantics — the core layer is correct.
> The bug was always in the snapshot not giving the LLM enough information to produce the right `bodyIndex`.

---

## Common Mistake: Silent Failure on checkResult (Historical)

**Symptom**: Orchestrator reports `state=DONE, executed=1`, but OnlyOffice reload shows no change.

**Historical Cause**: Toolkit batch method single-item failure `[0] 错误:缺少必填字段 X` does not start
with `错误`, so `startsWith("错误")` missed it. The executor treated failure as success.

**Resolution (P0-02)**: `checkResult` 现统一委托 `ToolResultChecks`，解析 JSON envelope 的
`success`/`code` 字段判定成败，不再嗅探中文前缀。批量部分失败用 `PARTIAL_FAILURE` code。
旧 `startsWith`/`contains` 路径仅作为 safety-net 保留（envelope 不存在时回退）。

---

## Scenario: Structured Tool Results (ToolResult envelope)

### 1. Scope / Trigger

当 toolkit 的 `@ToolDef` 方法需要向 LLM 和 executor 同时提供结果时，必须使用 `ToolResult`
双段格式。P0-02 后，所有 55 个 `@ToolDef` 方法已迁移。

**框架硬约束**：nonchain `chain-0.10.0.jar` 的 `ToolRegistry.doExecute()` 对返回值只做
`Object.toString()`（bytecode offset 156），**不会** JSON 序列化 POJO。因此 `@ToolDef` 方法
**必须**返回 `String`，在方法内部构建 `ToolResult` 后经 `ToolResultRenderer.render()` 序列化。

### 2. Signatures

```java
// @ToolDef 方法——String 边界
@ToolDef(name = "open_docx")
public String openDocx(@ToolParam(name = "path") String path) {
    try {
        ...
        ToolResult<String> result = ToolResult.ok(docId, "已打开文档 " + path);
        return ToolResultRenderer.render(result);  // 双段 String
    } catch (RuntimeException e) {
        ToolResult<Void> result = ToolResult.fail(ToolResultCode.DOCUMENT_CORRUPT, msg);
        return ToolResultRenderer.render(result);
    }
}

// 内部 private helper——可直接返回 ToolResult，不走 String 边界
private ToolResult<Void> locateCellResult(...) {
    if (cell == null) {
        return ToolResult.fail(ToolResultCode.INDEX_OUT_OF_RANGE, msg);
    }
    return ToolResult.ok(null, "定位成功");
}
// 调用方检查 result.success()，不嗅探字符串
```

### 3. Contracts

**双段格式**：

```
<中文人类可读消息>
```json
{"success":true,"code":"ok","data":{...},"matchedCount":1,"changedRefs":[...]}
```
```

失败时消息尾部追加 `[code]`，并输出 `suggestion`：

```
run 索引 5 越界（共 2）[index_out_of_range]
```json
{"success":false,"code":"index_out_of_range","message":"...","suggestion":"使用 0..1"}
```
```

**ToolResult<T> 字段**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `success` | boolean | 成功/失败 |
| `code` | ToolResultCode | `OK` 或错误码 |
| `message` | String | 中文人类可读消息（第一段） |
| `data` | T | 机器可读负载（docId、ref、统计等），null 时不输出 |
| `warnings` | List\<ToolWarning\> | 非致命提示 |
| `changedRefs` | List\<String\> | 写操作影响的 canonical ref |
| `matchedCount` | Integer | 多目标匹配数 |
| `suggestion` | String | 可重试建议 |

**ToolResultCode 目录**（`com.non.docx.toolkit.result.ToolResultCode`）：
`OK`、`INVALID_ARGUMENT`、`INDEX_OUT_OF_RANGE`、`STALE_REF`、`ELEMENT_REMOVED`、
`GENERATION_MISMATCH`、`DOCUMENT_MISMATCH`、`REF_TYPE_MISMATCH`、`INVALID_REF`、
`UNSUPPORTED_FEATURE`、`NO_CHANGES_APPLIED`、`PARTIAL_FAILURE`、`DOCUMENT_CLOSED`、
`DOCUMENT_CORRUPT`、`COMPATIBILITY_RISK`。

ref 域错误码经 `RefResolutionCode.toToolResultCode()` 映射，共享同一 value 字符串。

### 4. Validation & Error Matrix

| 条件 | code | 行为 |
|---|---|---|
| 成功 | `OK` | 返回 data + message |
| docId 不存在 | `DOCUMENT_CLOSED` | 不执行 |
| 索引越界 | `INDEX_OUT_OF_RANGE` | 不执行，附 suggestion |
| 参数错误/缺失 | `INVALID_ARGUMENT` | 不执行 |
| ref 解析失败 | 映射自 `RefResolutionCode` | 不执行 |
| 不支持的能力 | `UNSUPPORTED_FEATURE` | 不执行 |
| 批量部分失败 | `PARTIAL_FAILURE` | data 含单项明细 |
| 文档损坏 | `DOCUMENT_CORRUPT` | open 失败 |
| 兼容性风险 | `COMPATIBILITY_RISK` | 附 warning |

### 5. Good / Base / Bad Cases

- **Good**：`@ToolDef` 方法构建 `ToolResult` → `ToolResultRenderer.render` → 返回双段 String。
  executor 经 `ToolResultChecks.checkResult` 解析 envelope 判定 `success`。
- **Base**：内部 helper 直接返回 `ToolResult`，调用方检查 `result.success()`，
  只有 `@ToolDef` public 方法走 String 边界。
- **Bad**：在 executor 或工具内部 `result.startsWith("错误")` / `contains("错误")` 嗅探状态。
  **禁止**——所有状态判定走 `ToolResultChecks` 或 `ToolResult.success()`。
- **批量**：全成功 → `OK` + `matchedCount`；有失败 → `PARTIAL_FAILURE` + 逐条明细。
  旧中文 `成功 N 条,失败 M 条` 汇总行保留在 message 段供 LLM 阅读。

### 6. Tests Required

- `ToolResultTest`：值对象不可变、内容相等、工厂方法。
- `ToolResultCodeTest`：枚举目录、`fromValue` round-trip、`RefResolutionCode` 映射一致。
- `ToolResultRendererTest`：双段格式、JSON fence、序列化兜底、中文 message 保留。
- `ToolResultParserTest`：双模式解析、旧格式回退 null、round-trip。
- 现有 toolkit 测试：`DocxToolkitBatchTest`、`DocxToolkitTrackedChangesTest` 用
  `ToolTestSupport.parse(result).code()` 断言结构化结果，不再 `contains("错误")`。

### 7. Wrong vs Correct

#### Wrong — 嗅探中文字符串判定状态

```java
String result = body.replaceRunText(docId, List.of(edit));
if (result.startsWith("错误") || result.contains("错误:")) {
    throw new OperationExecutionException(result);
}
// 单条失败 [0] 错误:... 不以「错误」开头 → 漏判 → 报告成功但文档没改
```

#### Correct — 解析结构化 envelope

```java
String result = body.replaceRunText(docId, List.of(edit));
return ToolResultChecks.checkResult(result, "body", operation.kind());
// 内部：ToolResultParser.parse(result) → snapshot.success() 判定
// 批量部分失败：code=PARTIAL_FAILURE → 抛 OperationExecutionException
```

---

## Scenario: Router Plan / Review Protocol

### 1. Scope / Trigger

当新增专家、合并 operation、接入 review 或提交 coordinator 时，必须经
`DocumentSnapshot -> ExpertPlan -> MergedPlan -> CommitCoordinator` 强类型边界；不得让
LLM JSON 直接驱动写操作。

### 2. Signatures

```java
new DocumentSnapshot(conversationId, sourcePath, createdAt, sourceLastModified,
    sessionGeneration, overview, paragraphs, tables, revisionSummary, qualitySummary);
new ExpertPlan(agentName, planId, conversationId, snapshotVersion, sessionGeneration, operations);
new MergedPlan(conversationId, mergedPlanId, sourcePlanIds, operations);
Operation.of(id, toolGroup, kind, targetRef, payload, conflictKey, intent, reason, riskNote);
```

### 3. Contracts

- `ExpertPlan` / `MergedPlan.schemaVersion` 为 1；快照版本独立演进，当前 `DocumentSnapshot.SNAPSHOT_VERSION=2`。
- `conversationId + snapshotVersion + sessionGeneration` 绑定计划基线；generation 不一致的快照不得继续产生或提交 plan。
- `ConflictKey` 内部保存 `ElementRef`，只以 `(toolGroup, targetRef)` 判断候选同目标；`kind` 与 payload 由第二层规则判定能否合并。
- `Operation` 保留稳定 `operationId`、review、`ruleCode` 与可选 `mergedIntoOperationId`；去重吸收项必须为 `SKIPPED/DUPLICATE_MERGED`。
- 状态只允许 `ANALYZE -> PLAN -> REVIEW? -> COMMIT -> DONE`，或从 REVIEW/COMMIT 到 `FAILED`。`FAILED` 后必须 close + reopen，不能在同一 session 直接重试。

### 4. Validation & Error Matrix

| 条件 | 行为 |
|---|---|
| 任一 operation 为 `BLOCKED` | 整批不进入 CommitCoordinator，Router 进入 `FAILED` |
| `WARNED` | 允许提交，结果必须保留 warning/review 信息 |
| `SKIPPED` | 不执行，保留原因与来源链 |
| 任一 executor 失败 | 非事务：已完成写入不回滚；会话失败并要求 reopen |
| 非法状态跃迁 | `IllegalStateException` |

### 5. Good / Base / Bad Cases

- Good：专家只输出本 `toolGroup` 的 `ExpertPlan`，Router 合并、排序、review 后再串行提交。
- Base：无需 review 的低风险计划可从 `PLAN` 直接到 `COMMIT`。
- Bad：把自由字符串 target 当身份，或在出现 `BLOCKED` 后仍调用 executor。

### 6. Tests Required

- `MergedPlanTest`：版本、generation 有效性、`BLOCKED/WARNED/SKIPPED` 汇总。
- `OperationTest` / `ReviewResultTest`：不可变 review 更新、`ruleCode`、去重来源。
- `ConflictKeyTest`：强类型 ref 值相等与同目标判定。
- `RouterStateTest`：所有合法和非法跃迁。
- `DocxOrchestratorTest`：成功提交与失败后不可原会话重试。

### 7. Wrong vs Correct

```java
// Wrong: LLM operation 绕过 review，直接写文档
executor.execute(session, llmOperation);

// Correct: review 闸门先阻断，只有可提交视图进入 coordinator
if (mergedPlan.hasBlocked()) return RouterState.FAILED;
commitCoordinator.commit(session, mergedPlan);
```

## Scenario: LLM Trace Visibility (P0-trace)

> 让调用方（如 demo 的 AgentBridge）实时观测专家内部 LLM 调用的 prompt、response 增量、thinking
> 增量与完成状态，用于前端可视化。与阶段级 `PhaseCallback` 并行，职责分开。

### 1. Scope / Trigger

当需要把专家内部 LLM 调用过程（prompt/response/thinking）暴露给前端或外部观测者时，经
`ExpertAgent.plan` 的第 4 参数 `Consumer<LlmTraceEvent> traceCallback` 发送事件。

### 2. Signatures

```java
// toolkit 值对象（agent 包）
public final class LlmTraceEvent {
  public enum Kind { PROMPT, CONTENT_DELTA, THINKING_DELTA, COMPLETE }
  // 工厂: ofPrompt / ofContentDelta / ofThinkingDelta / ofComplete / ofFailure
  // accessor: kind() / agentName() / prompt() / delta() / success() / error() / usage()
}

// ExpertAgent.plan 签名（改原签名，非 default 重载）
ExpertPlan plan(
    OrchestratorSession session,
    DocumentSnapshot snapshot,
    String intent,
    Consumer<LlmTraceEvent> traceCallback);  // 可空
```

### 3. Contracts

- **4 种事件 + agentName**：PROMPT（一次性，prompt 构造后）、CONTENT_DELTA（`ChatChunk.deltaContent()` 逐块）、
  THINKING_DELTA（`ChatChunk.deltaThinking()` 逐块）、COMPLETE（成功 `ofComplete` 或失败 `ofFailure`）。
- **agentName 强制携带**：为多专家场景预留前端分组依据。不调 LLM 的启发式专家（BodyAgent/TableAgent/
  HeaderTocAgent/QualityAgent/RevisionAgent）忽略 traceCallback，但签名必须加（契约统一）。
- **usage 类型用 Object**：toolkit 不硬绑 nonchain `TokenUsage`，由 demo 侧消费时解释（如 `toString()`）。
- **双 callback 并行**：`PhaseCallback`（阶段级，ANALYZE/PLAN/COMMIT 完成）与 `traceCallback`（token 级）
  职责分开。`PhaseCallback` 不含 trace 事件。
- **传递路径**：`DocxOrchestrator.run(convId, intent, phaseCb, traceCb)` → `RouterAgent.run(session, intent, phaseCb, traceCb)`
  → `expert.plan(session, snapshot, intent, currentTraceCb)`。RouterAgent 用栈式 `currentTraceCb` 字段
  （仿 `currentCallback`），try/finally 保存/恢复。
- **null 安全**：`traceCallback == null` 时实现必须跳过 trace 发送（`if (cb != null)` 守卫）。

### 4. 时序（一轮对话）

```
step(analyze)   ← PhaseCallback 阶段级
trace(prompt)   ← traceCb token 级（PLAN 进行中）
trace(thinking_delta) × N
trace(content_delta) × N
trace(complete)
step(plan)      ← PhaseCallback 阶段级（PLAN 完成后，与 trace 共存）
step(commit)
```

trace 帧与 plan step 帧**共存**：trace 是 LLM 思考过程，step 是编排层翻译产物（operation 人话清单），
语义分层不冲突。

### 5. Demo 实现要点

- `LlmDocxExpert.callLlm` 用 `llm.streamChat(messages, chunk -> {...})`（非阻塞 `chat`），在 consumer
  里按 chunk 类型发 content/thinking delta。**实际返回值用 `result.content()`**（完整文本），delta 仅用于推 trace。
- 失败时发 `ofFailure` 再返回空 plan（保持既有 catch 行为，不阻断流程）。
- `AgentBridge.buildTraceFrame` 把 `LlmTraceEvent` 转成 SSE `type:trace` 帧（event=prompt/content_delta/
  thinking_delta/complete）。

### 6. Wrong vs Correct

```java
// Wrong —— 只改 demo 不动 toolkit 接口，trace 靠 demo 内部 ThreadLocal/单例传递
// （RouterAgent 调 expert.plan 时无法穿透 traceCallback）
class LlmDocxExpert {
  void plan(...) {
    TraceBus.current().emit(...);  // 隐式全局状态，多专家/并发不安全
  }
}

// Correct —— toolkit 接口显式传 Consumer<LlmTraceEvent>，RouterAgent 透传
class RouterAgent {
  expert.plan(session, snapshot, intent, currentTraceCb);  // 显式参数
}
```

### 7. 兼容性

- `ExpertAgent.plan` 签名变更 → 所有实现类必改。当前 6 个实现类（1 个调 LLM：`LlmDocxExpert`；
  5 个启发式：BodyAgent/TableAgent/HeaderTocAgent/QualityAgent/RevisionAgent）+ 2 个测试 mock。
- `RouterAgent.run` / `DocxOrchestrator.run` 保留旧重载（传 null traceCb），既有调用方不改也能编译。

---

## Convention: Demo Observability for FAILED State

**What**: `AgentBridge` must log failure details when `state=FAILED`, not just `state=FAILED`.

**Why**: Without details, you can't tell whether the failure was a missing executor case, wrong
payload field names, or a POI exception. A single `log.info("state={}", result.state())` line
hides the root cause entirely.

**Pattern**:
```java
if (result.state() == RouterState.FAILED) {
    log.warn("编排失败摘要: {}", result.summaryText());
    for (Operation op : result.mergedPlan().operations()) {
        log.warn("  操作: id={}, kind={}, payload={}, status={}", ...);
    }
}
```

`RouterResult.summaryText()` includes the failed operation ID and the failure message from
`CommitResult`.

---

## Convention: `clear` 字段清除枚举属性（不用伪枚举值）

**What**: toolkit 里需要「清除」一个枚举属性（标题级别、列表成员等）时，**不**给枚举加一个不存在的值（如 `NONE` / `CLEAR`），而是用一个独立的布尔字段 `clear=true`。`clear=true` 时工具调 core 的 `clearHeading()` / `clearList()`，并忽略同条 edit 里的枚举字段。

**Why**: 给枚举塞伪值（`HeadingLevel.NONE`、`ListKind.NONE`）会污染 core 的值对象——core 的 `HeadingLevel` 是 OOXML `Heading1..6` 的忠实映射，没有「无标题」这个级别（无标题就是 `heading()==null`）。伪枚举值会让 core 的 `equals` / round-trip 断言出问题，也违反「public 值对象 POI-free 且忠实映射」的约定。独立 `clear` 字段把「清除」语义留在 toolkit 层，core 不感知。

**Where**: `TableTools.updateTableCellParagraphHeading`（`clear=true` → `clearHeading()`）、`TableTools.updateTableCellParagraphList`（`clear=true` → `clearList()`，后者走 `unsetNumPr` 见 poi-bridge.md N4）。未来 BodyTools 补对称的标题/列表工具时沿用此约定。

**Example**（toolkit payload）:
```json
// 设标题
{"table_index":0,"row_index":0,"cell_index":0,"paragraph_index":0,"heading":"H2"}
// 清除标题(不传 heading)
{"table_index":0,"row_index":0,"cell_index":0,"paragraph_index":0,"clear":true}
// 设列表
{"table_index":0,"row_index":0,"cell_index":0,"paragraph_index":0,"list_kind":"BULLET","level":0}
// 清除列表
{"table_index":0,"row_index":0,"cell_index":0,"paragraph_index":0,"clear":true}
```

**Wrong vs Correct**:
```java
// Wrong —— 给 core 枚举塞伪值
public enum HeadingLevel { H1, H2, H3, H4, H5, H6, NONE }  // NONE 不是 OOXML 标题级别
// Correct —— core 枚举忠实映射,toolkit 用 clear 字段
// core: enum HeadingLevel { H1..H6 }  (heading()==null 即「非标题」)
// toolkit: clear=true → p.clearHeading()
```

**Related**: poi-bridge.md N4（`clearList` 用 `unsetNumPr` 而非 `setNumID(null)`，避免空 `numId` 触发 XmlBeans 异常）。

---

## Gotcha: `add_table_row` / `add_table_cell` 返回空结构，无法直接被 run 文本工具填充

> **Warning**: toolkit 的 `add_table_row` / `add_table_cell` 调 core 的 `Table.addRow()` / `Row.addCell()`，后者按 poi-bridge.md N2 **剥离了 POI 预填的默认段落/单元格**。因此返回的是**真空结构**：新行有 0 个单元格，新单元格有 0 个段落。
>
> 这意味着「先 `add_table_cell` 再 `replace_table_cell_run_text`」会失败——新单元格没有 paragraph 0，run 文本工具报「单元格内段落索引 0 越界（共 0）」。

**根因（跨层）**: core 的 N2 剥离是有意的（保证 `addX = exactly one X` 语义、round-trip 干净），但它在 toolkit 层产生了下游约束：**空单元格必须先有段落才能写 run**。toolkit 当前没有「给空单元格加段落」的工具（core 的 `Cell.addParagraph()` 未暴露），所以 `add_table_cell` 的产物目前只能被结构读取工具（`read_table_cell`）确认，不能直接填内容。

**对编排层的影响**: 如果要让 LLM 编排「追加一行并填数据」，当前要么用 `create_table`（整体建表，单元格自带段落），要么需要补一个 `add_table_cell_paragraph` 工具（暴露 `Cell.addParagraph()`）。在 orchestration 层接 `add_table_row` / `add_table_cell` 的 operation 时，要意识到它们产出的空结构需要后续的「加段落」步骤才能填文本。

**当前测试如何处理**: `TableStyleToolsTest` 的结构编辑用例只断言「行数/单元格数变化 + 返回新索引 + 删除后索引前移」，不强行给空单元格写 run（那会踩此坑）。

**Related**: poi-bridge.md N2（core 层剥离预填的语义）。

---

## Scenario: Machine-Readable Capability Contract (P0-03)

> 每个 `@ToolDef` 工具必须声明能力元数据，构建期反射生成机器可读能力清单，CI 强制声明与实现一致。
> Agent 通过 `describe_capabilities` 或 `capabilities.json` 得知全部参数名/类型/枚举值，不再猜测。

### 1. Scope / Trigger

新增或修改任何 `@ToolDef` 工具方法时，必须同步标注 `@ToolCapability` + `@ParamCapability`。
未标注的 `@ToolDef` 方法会让 `CapabilityCollector` 在收集期抛 `CapabilityDeclarationException`，
导致构建期 `capabilities.json` 生成失败、CI 契约测试失败。

### 2. 单一真实来源（硬契约）

- **真实来源 = Java 代码 + 项目伴生注解**，不是手写 JSON schema。
- `capabilities.json` / `capabilities.digest` 是构建期派生产物（`process-classes` 阶段由
  `CapabilityManifestGenerator` 反射生成），**不入版本库**（.gitignore 忽略），可随时重新生成。
- **禁止**手编 `capabilities.json` 或维护第二套与注解并行同步的能力说明。

### 3. 注解契约

每个 `@ToolDef` 方法必须配 `@ToolCapability`（method 级），每个 `@ToolParam` 配 `@ParamCapability`（param 级）：

```java
@ToolDef(name = "update_paragraph_alignment", description = "...")
@ToolCapability(operation = CapabilityOperation.UPDATE, element = "paragraph")
public String updateParagraphAlignment(
    @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
        String docId,
    @ToolParam(name = "edits", description = "对象数组...") @NestedParamCapability(
            path = "edits.alignment", type = ParamType.ENUM,
            enumValues = {"LEFT", "CENTER", "RIGHT", "JUSTIFY"})
        List<Map<String, Object>> edits) {
```

- `@ToolCapability`：`operation`（READ/ADD/UPDATE/REMOVE/QUERY/SESSION/QUALITY）、`element`（小写字符串，如
  `"paragraph"`/`"table"`/`"run"`；会话元工具留空）、`level`（STABLE/WORD_ONLY/EXPERIMENTAL）、
  `needsRecalc`（是否需 Word/WPS 重新计算）。
- `@ParamCapability`：`type`（STRING/INTEGER/ENUM/BOOLEAN/REF/...）、`enumValues`（仅 ENUM 或数组类型可配）、
  `unit`（如 `"twip"`/`"pt"`/`"percent"`）。
- `@NestedParamCapability`（可重复）：声明 `List<Map>` 嵌套参数的子字段（如 `edits.alignment`）。
  `path` 首段必须与同位 `@ToolParam(name=X)` 的 X 一致。

### 4. 校验规则（收集期抛异常 = 构建失败）

`CapabilityCollector` 收集时强制：
- `@ToolDef` 方法无 `@ToolCapability` → 失败。
- `@ParamCapability.enumValues` 非空但 `type` 不在 `{ENUM, STRING_ARRAY, INTEGER_ARRAY, OBJECT_ARRAY}` → 失败。
- `@ParamCapability` 标在非 `@ToolParam` 参数上 → 失败。
- `@NestedParamCapability.path` 首段与 `@ToolParam.name` 不一致 → 失败。

### 5. CI 契约测试（`CapabilityContractTest`）

- `每个_tooldef都有_toolcapability`：计数断言 ≥54。
- `声明的工具都有测试调用或显式豁免`：按 camelCase 方法名扫描测试源码；无调用且不在
  `TEST_COVERAGE_ALLOWLIST` 的工具失败。新增工具必须配套测试。
- `枚举参数都声明了enumValues`：每个 type=ENUM 的参数/nested 字段必须有 enumValues。
- `digest在能力未变时稳定`：digest 排除 generatedAt，能力不变则值不变（Agent 据此判断缓存复用）。

### 6. Wrong vs Correct

```java
// Wrong —— 新增 @ToolDef 但忘记标注能力，CI 会失败
@ToolDef(name = "new_tool", description = "...")
public String newTool(@ToolParam(name = "doc_id", description = "x") String docId) { ... }

// Wrong —— 手编 capabilities.json 试图绕过注解
// （capabilities.json 是构建产物，下次构建会被反射结果覆盖）

// Correct —— 同步标注 method + param + nested
@ToolDef(name = "new_tool", description = "...")
@ToolCapability(operation = CapabilityOperation.READ, element = "paragraph")
public String newTool(
    @ToolParam(name = "doc_id", description = "x") @ParamCapability(type = ParamType.STRING)
        String docId) { ... }
```

### 7. 兼容与迁移

- `@ToolCapability` / `@ParamCapability` / `@NestedParamCapability` 是纯新增注解，零运行时开销
  （反射只在收集时发生，结果惰性缓存）。
- nonchain `@ToolDef` / `@ToolParam` 语义不变；项目注解只补 nonchain 缺失的维度
  （operation/element/level/type/enumValues/unit），不重复 name/description/required。
- 新增工具时，`describe_capabilities` 自动纳入完整 manifest（含自身），无循环。

**Related**: 本文档 P0-01（Stable Semantic References）、P0-02（Structured Tool Results）；
`capabilities.json` 字段形状见 `CapabilityJsonIo`。

---

## Scenario: Unified Semantic View (P0-04)

> `DocumentViewService` 把 `DocumentSnapshot` 提炼为 6 种只读视图，为 Agent 提供低成本、可控上下文。
> Agent 不必全文 dump 即可定位目标、理解结构、评估质量、按需展开细节。

### 1. Scope / Trigger

当 Agent 需要理解文档结构、定位目标元素、评估质量或按需展开 run 明细时，使用 `view_*` 工具族。
不要用 `get_document_overview`（已迁移为 `view_stats` 的薄适配层）+ 多次 `read_*` 拼凑上下文。

### 2. 架构

```
DocumentViewService (只读服务, 复用 SnapshotBuilder + ElementResolver)
  ├─ outline()   ─→ SnapshotBuilder.build() → 投影标题树/section/表格/TOC/页眉页脚/修订概览
  ├─ text()      ─→ SnapshotBuilder.build() → 投影按文档顺序的文本 + ref
  ├─ annotated() ─→ 快照 ref + ElementResolver 逐段补读 run 直接格式
  ├─ stats()     ─→ SnapshotBuilder.build() → 投影统计 + 补 imageCount/字体/字号聚合
  ├─ issues()    ─→ QualityCheckTools.runAllChecks(doc) → 包装 CheckResult → IssueEntry
  └─ element()   ─→ ElementRef parse → ElementResolver.resolve(ref) → 投影单元素详情
```

`ViewTools`（第 9 个工具类）是薄适配层：只做 docId 解析 + 参数校验 + `ToolResult<T>` envelope 包装。

### 3. Contracts

- **复用 SnapshotBuilder**：`outline`/`text`/`stats` 调 `build()` 一次投影，**不建第二套 body 遍历**。
- **annotated 复用快照 ref**：经 `resolver.resolve(pp.ref())` 补读 run，保证与快照 ref 一致。
- **零 POI 泄露**：全部 DTO 是 `final class` + `private final` 字段，Jackson 经 `FIELD` visibility 序列化。
- **上下文控制**：`ViewQuery`（maxItems=200 / textTruncate=120 / expandRuns=false）；超过截断并标记
  `ViewMeta.truncated=true` + `totalCount`。element 视图单元素全量，不走 maxItems。
- **视图一致性**：同一文档所有视图引用同一 `sessionGeneration`；outline ref 可被 element 解析。
- **issues 浅层**：复用 `QualityCheckTools.runAllChecks()` 返回 `List<CheckResult>`，包装成 `IssueEntry`。
  不建 `DocumentIssue` 模型和 issue code 目录（留 P1-01）。
- **annotated 浅层**：只给 run 直接格式（bold/italic/font/size/color），不解析样式链来源（留 P1-02）。

### 4. 6 个 view_* 工具

| 工具名 | operation | element | 参数 | data DTO |
|---|---|---|---|---|
| `view_outline` | READ | document | doc_id, max_items?, text_truncate? | OutlineView |
| `view_text` | READ | document | doc_id, max_items?, text_truncate? | TextView |
| `view_annotated` | READ | paragraph | doc_id, max_items?, expand_runs? | AnnotatedView |
| `view_stats` | READ | document | doc_id | StatsView |
| `view_issues` | QUALITY | document | doc_id, severity? | IssuesView |
| `view_element` | READ | element | doc_id, ref | ElementView |

### 5. get_document_overview 迁移

`SessionTools.getDocumentOverview` 已迁移为委托 `DocumentViewService.stats()`：
- 工具名 `get_document_overview` 不变（向后兼容）。
- data 从 `Map<String,Integer>` 升级为 `StatsView`（超集：旧 4 个 int 仍在，新增 imageCount/fonts/fontSizes 等）。
- message 文案从 `正文段落数`/`正文表格数` 改为 `段落数`/`表格数`（与 view_stats 一致）。
- `SessionTools` 经 `bindViewService()` 延迟绑定 `DocumentViewService`（解决 QualityCheckTools 循环依赖）。
  未绑定时走旧逻辑（独立使用 SessionTools 兼容）。

### 6. DTO 序列化（Jackson FIELD visibility）

`ToolResultRenderer` 的 ObjectMapper 配了 `PropertyAccessor.FIELD = ANY` +
`PropertyAccessor.GETTER = NONE`。这意味着：
- DTO 的 `private final` 字段直接被 Jackson 反射序列化，**不需要** `getXxx()` getter。
- accessor 方法名用无前缀风格（`paragraphCount()` 而非 `getParagraphCount()`），与现有 `ParagraphPreview` 一致。
- `Map<String,Object>` 的 value **不能为 null**（`Map.copyOf` 不允许）；用 `putIfNotNull` 过滤。

### 7. ToolkitToolContext 可见性

P0-04 起 `ToolkitToolContext` 提升为 `public abstract`，`document(docId)` 和 `generations` 字段提升为
`protected`，4 参构造提升为 `public`。因为 `ViewTools` 是第一个不在 `toolkit` 包的工具类（在 `view` 子包）。
现有包内子类不受影响（protected ⊇ 包级）。

### 8. QualityCheckTools.CheckResult 可见性

`CheckResult` 从包级提升为 `public static final class`，新增 4 个 public accessor（`name()`/`passed()`/
`message()`/`severity()`）。新增 `public List<CheckResult> runAllChecks(Document)` 供 issues 视图复用。
`check_quality` 工具行为不变（内部改为调 `runChecks` 私有 helper）。

### 9. Wrong vs Correct

```java
// Wrong —— 在 ViewTools 或 DocumentViewService 内重新遍历 doc.bodyElements() 做统计
public StatsView stats(Document doc, ...) {
  int paraCount = 0;
  for (BodyElement be : doc.bodyElements()) {  // 第二套遍历！
    if (be instanceof Paragraph) paraCount++;
  }
}

// Correct —— 复用 SnapshotBuilder.build() 投影
public StatsView stats(Document doc, ...) {
  DocumentSnapshot snapshot = buildSnapshot(doc, docId, generation);
  return new StatsView(meta, snapshot.overview().paragraphCount(), ...);
}
```

```java
// Wrong —— ElementView properties 含 null value（Map.copyOf 会 NPE）
properties.put("font", r.font());  // font() 可能返回 null

// Correct —— 过滤 null
putIfNotNull(properties, "font", r.font());
```

**Related**: 本文档 P0-01（ref）、P0-02（ToolResult envelope）、P0-03（能力契约）；
视图 DTO 见 `com.non.docx.toolkit.view.dto`。
