# P0-05 写操作安全协议

## Goal

统一所有 Agent 写操作（mutation）的前置检查、影响范围回报和 no-op 语义，防止空操作
返回成功、无匹配目标静默成功、旧快照驱动修改新文档状态、以及未限定的全局修改。

P0-02 已落地 `ToolResult<T>` + `ToolResultCode` 骨架，但写安全协议的四个核心缺口
目前几乎全是空白：`NO_CHANGES_APPLIED` 零调用、`sessionGeneration` 写路径不校验
也不 bump、没有全局修改防护、批量语义 toolkit 层（collect-errors）与编排层
（fail-fast）不一致。本任务接通这些缺口，让写操作具备可审计、可回滚判断、可防护
的安全基线。

## 已确认事实（代码库探索）

### 写工具清单（34 个写 @ToolDef）

- **BodyTools**（5）：`update_paragraph_alignment`、`replace_run_text`、`update_run_style`、
  `insert_paragraph`、`update_hyperlink`。无 remove/move 工具（core 有 `Document.removeParagraph`
  但 toolkit 未暴露）。
- **TableTools**（20）：`create_table`、`set_table_borders`、`merge_table_cells`、
  `replace_table_cell_run_text`、`update_table_cell_shading`/`vertical_align`/`run_style`/
  `paragraph_alignment`/`paragraph_heading`/`paragraph_indent`/`paragraph_spacing`/
  `paragraph_list`/`paragraph_shading`、`update_table_header_row`/`row_cant_split`、
  `set_table_column_percents`/`widths`、`add_table_row`、`remove_table_row`、
  `add_table_cell`、`remove_table_cell`。
- **TrackedChangeAuthoringTools**（6）：`insert_tracked_run`、`delete_run_tracked`、
  `replace_run_tracked`、`mark_style_change_tracked`、`mark_tracked_cells`、`move_run_tracked`。
- **TrackedChangeQueryTools**（3）：`set_tracked_changes_enabled`、`apply_tracked_changes`、
  `apply_text_revisions`（含 `scope=ALL` 全量批量，目前唯一无 review 闸门的全量写）。

### P0-02 基础设施（已就位，待接通）

- `ToolResult<T>`（`result/ToolResult.java`）：`success/code/message/data/warnings/
  changedRefs/matchedCount/suggestion`。工厂 `ok()/fail()/partial()`。
- `ToolResultCode`（`result/ToolResultCode.java:12-27`）：15 个码全部已定义，含
  `NO_CHANGES_APPLIED`(L23)、`INDEX_OUT_OF_RANGE`(L15)、`STALE_REF`(L16)、
  `ELEMENT_REMOVED`(L17)、`GENERATION_MISMATCH`(L18)、`PARTIAL_FAILURE`(L24)。
- `BatchItemResult`（`result/BatchItemResult.java`）：批量单项 `index + ToolResult`。
- `ToolkitToolContext`（`ToolkitToolContext.java:142-179`）：共享工厂
  `docNotFoundResult`/`indexErrorResult`/`invalidArgumentResult`，**缺 `noChangesAppliedResult`**。

### 四个核心缺口

1. **`NO_CHANGES_APPLIED` 零调用**：`update_run_style`/`update_table_cell_run_style`
   检查到 `changed.isEmpty()` 时走 `INVALID_ARGUMENT` + `PARTIAL_FAILURE` inline 拼串
   （`BodyTools.java:666-670`、`TableTools.java:1273-1280`），不用已定义的 `NO_CHANGES_APPLIED`。
   `update_hyperlink` 单条用 `INVALID_ARGUMENT`（`BodyTools.java:862-867`）。
2. **generation 写路径不校验不 bump**：`generations` Map 只在 `open_docx`(put 1L) /
   `close_docx`(remove) 时动（`SessionTools.java:124,185`）。没有任何写操作接受
   `expected_generation`、没有写操作写后 bump。`ElementResolver.validateGeneration`
   （`ElementResolver.java:264-272`）只在 close/reopen 后才生效（同 docId 内 generation 恒为 1）。
   编排层 `OrchestratorSession.bumpGeneration()`（L78）只在 `reopen()` 调用。
3. **无全局修改防护**：无 `MutationSelectorGuard` 类似物。现有写工具都要求显式坐标，
   所以"删全部段落"无法表达；但 `apply_text_revisions(scope=ALL)` 是无闸门全量写
   （`TrackedChangeQueryTools.java:272`）。编排层 `shouldReview`（`RouterAgent.java:182-193`）
   只在编排路径触发 review，toolkit 直连不经过。
4. **批量语义不一致 + changedCount 缺失**：ToolResult 只有 `matchedCount`（实为
   "尝试条目数"=ok+fail），**无独立 `changedCount`**。toolkit 层全部 collect-errors
   （continueOnError），编排层 `CommitCoordinator`（L79-95）是 fail-fast（stopOnError）。
   两层没有统一开关。

### 批量骨架（可复用）

- `TableTools` 三个 private helper：`applyCellEditResults`(L364)、`applyRowEditResults`(L442)、
  `applyCellParagraphEditResults`(L529)。
- 批量工具统一公式 `matchedCount = ok + fail`，有失败返回 `partial(items, msg, warnings)`。

### OfficeCLI 参考

- `MutationSelectorGuard.cs`（58 行）：agent-facing 层对 `set`/`remove` 裸选择器硬拒绝，
  抛 `bare_selector_rejected` + suggestion。nondocx 是显式坐标模型，无裸选择器概念，
  但"mutation 必须命名 WHERE"的思想可对应到"全局修改必须显式 opt-in"。
- `CommandBuilder.Set.cs:158-181`：`set` 无 `--prop` → `missing_property` code + suggestion
  （对应 nondocx `NO_CHANGES_APPLIED`）。
- `CommandBuilder.Set.cs:238-268,307-315`：match-count 回报 + `zero_matches` warning
  （nondocx 当前无匹配走 `INDEX_OUT_OF_RANGE`，语义其实不同）。
- `OutputFormatter.cs:470-528`：异常消息 → 错误码模式映射。

## 已定决策

- **D1 generation 策略**：保持只在 close/reopen 时 bump。`expected_generation` 参数仅
  防护"旧快照驱动修改 reopen 后的新文档状态"，不破坏现有 ref 复用。
- **D2 批量停止策略**：toolkit 批量工具增加可选 `on_error`（continue/stop，默认 continue）。
  编排层 CommitCoordinator 保持 fail-fast。
- **D3 全局修改防护**：只给 `apply_text_revisions(scope=ALL)` 加 opt-in 防护，不建通用 guard。
- **D4 零匹配语义**：零匹配 = `NO_CHANGES_APPLIED`（复用已定义码，不新增），越界 = `INDEX_OUT_OF_RANGE`。
- **D5 删除索引安全**：remove_table_row/cell 保持单条不动，在 changedRefs 返回中补后继索引变动提示。
- **D6 写前后摘要**：不建 diff 摘要系统，复用 P0-04 view_stats/outline 作为写后读回手段。R8 移除。

## 需求

### R1 空更新检测（no_changes_applied）

- 写操作未提供任何可写字段时返回 `NO_CHANGES_APPLIED`，不返回成功。
- 覆盖 `update_run_style`、`update_table_cell_run_style`、`update_hyperlink` 等
  "可选字段全缺"场景。
- 当前 `update_run_style` 在 `changed.isEmpty()` 时走 `INVALID_ARGUMENT` + `fail++`
  （`BodyTools.java:666-670`），需改为 `NO_CHANGES_APPLIED`。

### R2 无匹配目标语义（zero_matches vs index_out_of_range）

- **D4**：零匹配 = `NO_CHANGES_APPLIED`（不新增码），越界 = `INDEX_OUT_OF_RANGE`。
- ref 解析路径已区分：ref 指向已删除元素 → `ELEMENT_REMOVED`。
- 主要影响未来 selector 类工具；现有坐标工具的"无匹配"本质是越界，保持 `INDEX_OUT_OF_RANGE`。

### R3 批量影响范围回报（matchedCount/changedCount）

- 多目标修改必须回报 `matchedCount`（文档中匹配的目标数）和 `changedCount`（实际改动数）。
- 当前 `matchedCount = ok + fail`（实为"尝试数"），需修正语义并补 `changedCount`。
- 批量操作能明确区分成功数、失败数、未执行数（stop 模式下有未执行项）。

### R4 批量停止策略（continueOnError/stopOnError）

- **D2**：toolkit 批量工具增加可选 `on_error` 参数（`continue`/`stop`，默认 `continue`）。
  编排层 CommitCoordinator 保持 fail-fast。
- `stop` 模式：遇第一条失败即停，返回已执行项 + 未执行项计数。

### R5 expectedGeneration 乐观锁

- 写操作支持可选 `expected_generation` 参数。
- 与当前 session generation 不符时返回 `GENERATION_MISMATCH`。
- **D1**：不每次写后 bump generation。

### R6 全局修改防护

- **D3**：只给 `apply_text_revisions(scope=ALL)` 加 opt-in 防护（如 `confirm_all=true`
  或返回 warning + 需二次确认），不建通用 guard 类。

### R7 删除/移动索引安全

- **D5**：remove_table_row/cell 保持单条不动，在返回的 changedRefs 或 warning 中补
  "后继索引前移"提示。不自动倒序。

## 验收标准

- [ ] 空更新不再返回成功（返回 `NO_CHANGES_APPLIED`）。
- [ ] 越界（`INDEX_OUT_OF_RANGE`）和零匹配（`NO_CHANGES_APPLIED`）具有不同错误码。
- [ ] 批量操作回报 `matchedCount`（匹配数）+ `changedCount`（改动数），能区分成功/失败/未执行。
- [ ] 批量 `on_error=stop` 模式遇第一条失败即停，返回未执行项计数。
- [ ] 旧 generation 驱动写操作时被阻止（`expected_generation` 不符 → `GENERATION_MISMATCH`）。
- [ ] `apply_text_revisions(scope=ALL)` 不再无防护执行，需显式 opt-in。
- [ ] `remove_table_row`/`remove_table_cell` 返回含后继索引前移提示。
- [ ] `mvn -q verify` 通过，无 Spotless 或现有回归失败。
- [ ] 改造工具全部标注 `@ToolCapability`/`@ParamCapability`，能力契约测试不破。

## 范围外

- 不引入事务/回滚（保持当前非事务语义，只做防护和回报）。
- 不实现 dry-run / preflight（留 P1-08 批处理与提交语义）。
- 不新增 remove/move 段落工具（core 已有 `Document.removeParagraph`，toolkit 暴露留独立任务）。
- 不重写编排层 CommitCoordinator 状态机（只接通 generation 校验入口）。
- 不建写后 diff 摘要系统（复用 P0-04 view 读回）。
- 不建通用 MutationSelectorGuard 类（只防护 `apply_text_revisions(scope=ALL)`）。
- 不每次写后 bump generation（保持 close/reopen bump）。
