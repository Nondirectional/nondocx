# Design: P0-05 写操作安全协议

## 架构总览

P0-05 不是新建一个服务，而是给现有 34 个写 @ToolDef 接入统一的写安全协议。
改动分三层：**结果模型增强**（ToolResult + Renderer）、**批量骨架增强**（三个 helper +
on_error 开关）、**写工具改造**（逐工具接入空更新/generation/全局防护）。

```
┌─ 结果模型层 (result/) ────────────────────────────────────────┐
│  ToolResult<T>  +changedCount 字段 +onError 模式标记           │
│  ToolResultRenderer  输出 changedCount / skippedCount          │
│  ToolkitToolContext  +noChangesAppliedResult() 工厂            │
│  ToolkitToolContext  +generationMismatchResult() 工厂          │
│  ToolkitToolContext  +checkExpectedGeneration(docId, expected) │
└───────────────────────────────────────────────────────────────┘
         │ 复用
         ▼
┌─ 批量骨架层 (TableTools private helpers) ─────────────────────┐
│  applyCellEditResults     +on_error 参数 +changedCount 统计    │
│  applyRowEditResults      +on_error 参数 +changedCount 统计    │
│  applyCellParagraphEditResults +on_error 参数 +changedCount   │
│  → stop 模式: 遇首条失败即停, 返回 skippedCount                │
└───────────────────────────────────────────────────────────────┘
         │ 调用
         ▼
┌─ 写工具层 (BodyTools / TableTools / TrackedChange*Tools) ─────┐
│  R1 空更新: update_run_style / update_table_cell_run_style    │
│            / update_hyperlink → NO_CHANGES_APPLIED             │
│  R3 影响范围: 所有批量工具修正 matchedCount 语义 + 补 changedCount│
│  R4 停止策略: 批量工具 +on_error(默认 continue)                │
│  R5 乐观锁: 所有写工具 +expected_generation 可选参数            │
│  R6 全局防护: apply_text_revisions(scope=ALL) +confirm_all     │
│  R7 删除提示: remove_table_row/cell +后继索引前移 warning       │
└───────────────────────────────────────────────────────────────┘
```

## 设计决策详述

### D1 generation 策略（仅 close/reopen bump）

**保持不变**：`generations` Map 只在 `open_docx`(put 1L) / `close_docx`(remove) 时动。
写操作**不 bump** generation。

**新增**：写工具接受可选 `expected_generation`（Integer，可 null）。
- null：跳过校验（向后兼容，现有调用不受影响）。
- 非 null：与 `generations.getOrDefault(docId, 1L)` 比对，不符返回 `GENERATION_MISMATCH`。
- 校验在工具入口、取到活文档之后、执行写逻辑之前。

**防护边界**：Agent 拿到 snapshot（含 sessionGeneration）→ 中途文档被 close/reopen
（generation bump）→ Agent 用旧 generation 调写工具 → 被拦截。同 docId 内不 bump，
所以同会话内 ref 复用不受影响。

### D2 批量停止策略（on_error 参数）

**toolkit 批量工具**：增加可选 `on_error` 参数（String，枚举 `continue`/`stop`，默认 `continue`）。
- `continue`（现状）：逐条 try/catch 不中断，有失败返回 `PARTIAL_FAILURE`。
- `stop`（新增）：遇第一条失败即停，未执行条目计入 `skippedCount`，返回 `PARTIAL_FAILURE`。

**实现位置**：三个批量骨架 helper（`applyCellEditResults`/`applyRowEditResults`/
`applyCellParagraphEditResults`）增加 `boolean stopOnError` 参数。循环内失败时若
`stopOnError`，break 并记录 `skipped = total - ok - fail`。

**编排层 CommitCoordinator**：保持 fail-fast 不变（异构操作序列本应严格）。

**参数传递**：调用骨架的 @ToolDef 方法从入参解析 `on_error`，传给 helper。非骨架工具
（如 `update_run_style` 自带循环）同样在循环内检查 `stopOnError`。

### D3 全局修改防护（只防护 apply_text_revisions scope=ALL）

**不建通用 guard 类**。只给 `apply_text_revisions` 增加 `confirm_all` 参数（Boolean，默认 false）。
- `scope=ALL` 且 `confirm_all` 非 true：返回 `ToolResult.fail(INVALID_ARGUMENT, ...)` +
  suggestion "scope=ALL 会一次性处理全部文本/移动类修订，需显式传 confirm_all=true 确认"。
- `scope=ALL` 且 `confirm_all=true`：正常执行。
- `scope=AUTHOR`：不受影响（已限定范围）。

### D4 零匹配语义（NO_CHANGES_APPLIED）

**不新增错误码**。复用已定义的 `NO_CHANGES_APPLIED`。
- 现有坐标工具的"无匹配"本质是越界（索引超出 size），保持 `INDEX_OUT_OF_RANGE`。
- 未来 selector 类工具命中 0 个时用 `NO_CHANGES_APPLIED`。
- ref 解析路径已区分：ref 指向已删除元素 → `ELEMENT_REMOVED`。

### D5 删除索引安全（补后继提示）

**remove_table_row / remove_table_cell 保持单条**。删除成功后，在返回的 `ToolResult` 中
增加 `ToolWarning`：`code="index_shifted"`，`message="删除后该表后续行/格索引前移，如有批量删除请从大到小执行"`。
不自动倒序。

### D6 写前后摘要（不做）

复用 P0-04 `view_stats`/`view_outline` 作为写后读回。CommitResult 不变。

## 数据模型变更

### ToolResult 新增字段

```java
public final class ToolResult<T> {
  // 现有字段不变
  private final Integer matchedCount;   // 语义修正：文档中匹配的目标数
  // 新增
  private final Integer changedCount;   // 实际改动数（≤ matchedCount）
  private final Integer skippedCount;   // stop 模式下未执行数（null 表示非 stop 模式或全执行）
}
```

**matchedCount 语义修正**：
- 旧：`ok + fail`（尝试数）→ **错误**
- 新：文档中匹配到的目标数。对于坐标工具，匹配成功=1，越界=0（但越界直接返回 `INDEX_OUT_OF_RANGE`，
  不到汇总阶段）。对于批量工具，matchedCount = 成功定位的条目数（不含参数错误条目）。

**changedCount**：实际写入成功的条目数（= 旧的 ok）。

**skippedCount**：`on_error=stop` 模式下，因前面失败而未执行的条目数。`continue` 模式恒为 null。

### 工厂方法调整

新增带 `changedCount` 的成功工厂：
```java
public static <T> ToolResult<T> ok(T data, String message,
    Integer matchedCount, Integer changedCount, List<String> changedRefs)
```

`partial` 工厂增加 `skippedCount` 重载：
```java
public static <T> ToolResult<T> partial(ToolResultCode code, T data, String message,
    List<ToolWarning> warnings, Integer matchedCount, Integer changedCount, Integer skippedCount)
```

### Renderer 输出

`buildEnvelope` 增加 `changedCount`（非 null 时）和 `skippedCount`（非 null 时）字段。

### ToolkitToolContext 新增工厂

```java
// 空更新结果
static ToolResult<Void> noChangesAppliedResult(String message) {
  return ToolResult.fail(ToolResultCode.NO_CHANGES_APPLIED, message, "至少提供一个可写字段");
}

// generation 不匹配结果
static ToolResult<Void> generationMismatchResult(long expected, long actual) {
  return ToolResult.fail(ToolResultCode.GENERATION_MISMATCH,
      "expected_generation=" + expected + " 与当前 generation=" + actual + " 不符",
      "重新读取文档获取最新 generation");
}

// generation 校验（null 跳过，非 null 校验）
boolean checkExpectedGeneration(String docId, Integer expected) {
  if (expected == null) return true;
  long current = generations.getOrDefault(docId, 1L);
  return expected.longValue() == current;
}
```

## 批量骨架改造

### applyCellEditResults / applyRowEditResults / applyCellParagraphEditResults

新增参数 `boolean stopOnError`。循环内失败时：
```java
if (r.success()) {
  ok++;
} else {
  fail++;
  if (stopOnError) {
    int skipped = list.size() - i - 1;
    // 汇总并返回，含 skippedCount
    break;
  }
}
```

汇总时：
- `matchedCount` = ok + fail（已定位的条目，不含参数错误条目 → 需细分）
- 实际上参数错误条目也应算"尝试"。修正：matchedCount = ok + fail（所有进入循环的条目），
  changedCount = ok，skippedCount = stop 模式下未执行数。

**调用方传递 on_error**：@ToolDef 方法解析 `on_error` 参数（String，默认 "continue"），
转 `boolean stopOnError = "stop".equalsIgnoreCase(onError)` 传给 helper。

## 写工具改造清单

### BodyTools（5 个写工具）

| 工具 | R1 空更新 | R3 计数 | R4 on_error | R5 expected_gen |
|------|:---:|:---:|:---:|:---:|
| `update_paragraph_alignment` | — | ✓ | ✓ | ✓ |
| `replace_run_text` | — | ✓ | ✓ | ✓ |
| `update_run_style` | ✓ (L666-670) | ✓ | ✓ | ✓ |
| `insert_paragraph` | — | ✓ | ✓ | ✓ |
| `update_hyperlink` | ✓ (L862-867) | — (单条) | — | ✓ |

### TableTools（20 个写工具）

批量工具（经骨架）改造 `on_error` + 计数：
- `merge_table_cells`、`replace_table_cell_run_text`、`update_table_cell_shading`/
  `vertical_align`/`run_style`/`paragraph_alignment`/`paragraph_heading`/
  `paragraph_indent`/`paragraph_spacing`/`paragraph_list`/`paragraph_shading`、
  `update_table_header_row`/`row_cant_split`。

空更新：
- `update_table_cell_run_style`（L1273-1280）→ `NO_CHANGES_APPLIED`。

单条工具只加 `expected_generation`：
- `create_table`、`set_table_borders`、`add_table_row`、`remove_table_row`(+R7)、
  `add_table_cell`、`remove_table_cell`(+R7)、`set_table_column_percents`/`widths`。

### TrackedChangeAuthoringTools（6 个写工具）

批量工具：`insert_tracked_run`、`delete_run_tracked`、`replace_run_tracked`、
`mark_tracked_cells` → on_error + 计数。
单条：`mark_style_change_tracked`、`move_run_tracked` → expected_generation。

### TrackedChangeQueryTools（3 个写工具）

- `apply_text_revisions`：+ `confirm_all`（R6）+ `expected_generation`（R5）。
- `apply_tracked_changes`：+ `expected_generation`。
- `set_tracked_changes_enabled`：+ `expected_generation`。

## expected_generation 参数标注

所有写工具的 `@ToolParam` + `@ParamCapability`：
```java
@ToolParam(name = "expected_generation", description = "可选。调用方持有的 session generation，"
    + "与当前不符则拒绝写入（防止旧快照修改新状态）。不传则跳过校验。", required = false)
@ParamCapability(type = ParamType.INTEGER)
Integer expectedGeneration
```

nonchain 框架把 JSON 数字还原为 Integer/Long/Double，`Integer` 参数直接接收。

## 兼容与迁移

- **所有新增参数都是可选**（`required = false` 或默认值），现有调用不受影响。
- `matchedCount` 语义从"尝试数"修正为"匹配数"——对 Agent 是语义改善（更准确），
  但如果有 Agent 依赖旧语义需注意。changedCount = 旧的 matchedCount 中的 ok 部分。
- `on_error` 默认 `continue`，与现有行为一致。
- `confirm_all` 默认 false，**会破坏现有 `scope=ALL` 调用**——这是有意为之的安全加固。
  Agent 需加 `confirm_all=true`。能力契约 manifest 会反映新参数。
- ToolResult 新增 `changedCount`/`skippedCount` 字段，JSON envelope 是超集，不破坏现有解析。

## 风险与回滚

- **最大风险**：34 个写工具逐一改造，遗漏某个工具的 `expected_generation` 或 `on_error`。
  缓解：implement.md 按工具类分切片，每片独立编译验证；能力契约测试会捕获漏标注。
- **回滚点**：每个切片独立 commit，可逐切片 revert。结果模型变更（ToolResult 加字段）是
  超集变更，不影响现有消费者。
- `confirm_all` 破坏性变更：如果有 demo/测试依赖 `scope=ALL` 无 confirm，需同步更新。
