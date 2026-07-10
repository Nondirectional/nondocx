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

### 5. Error Detection: checkResult

Toolkit batch methods return a `String` summary. A single-item failure format `[0] 错误:...` does
**not** start with `错误`, so `startsWith("错误")` alone misses it. Both checks are required:

```java
private static String checkResult(String result, Operation operation)
    throws OperationExecutionException {
  if (result == null) throw new OperationExecutionException("返回 null");
  if (result.startsWith("错误")) throw new OperationExecutionException(result);
  if (result.contains("错误:") || result.contains("错误："))  // single-item failure
    throw new OperationExecutionException("执行失败: " + result);
  return result;
}
```

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

## Gotcha: insertParagraph bodyIndex Semantics

> **Warning**: `Document.insertParagraph(bodyIndex)` inserts **before** the element at `bodyIndex`.
> When `bodyIndex` points to a table, the new paragraph lands **before** the table.
> When `bodyIndex == bodySize`, it appends to the end (after all elements including tables).
>
> This is POI's `cursor.beginElement("p")` semantics — the core layer is correct.
> The bug was always in the snapshot not giving the LLM enough information to produce the right `bodyIndex`.

---

## Common Mistake: Silent Failure on checkResult

**Symptom**: Orchestrator reports `state=DONE, executed=1`, but OnlyOffice reload shows no change.

**Cause**: Toolkit batch method single-item failure `[0] 错误:缺少必填字段 X` does not start with
`错误`, so `startsWith("错误")` misses it. The executor treats failure as success.

**Fix**: `checkResult` must check both `startsWith("错误")` AND `contains("错误:")` / `contains("错误：")`.

**Prevention**: Always run the full `checkResult` pattern (see §5 above), not just a prefix check.

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
