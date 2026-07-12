# Implement: P0-05 写操作安全协议

## 执行切片（每片独立可编译、可回滚）

### 切片 0：结果模型增强（ToolResult + Renderer + Context 工厂）

- [ ] `ToolResult` 新增 `changedCount`（Integer）+ `skippedCount`（Integer）字段
- [ ] 新增成功工厂 `ok(data, message, matchedCount, changedCount, changedRefs)` 重载
- [ ] 新增 partial 工厂重载 `(code, data, message, warnings, matchedCount, changedCount, skippedCount)`
- [ ] `withChangedRef` / `mapData` 等 wither 保留新字段
- [ ] `equals/hashCode` 纳入新字段
- [ ] `ToolResultRenderer.buildEnvelope` 输出 `changedCount`（非 null）+ `skippedCount`（非 null）
- [ ] `ToolkitToolContext` 新增 `noChangesAppliedResult(message)` 静态工厂
- [ ] `ToolkitToolContext` 新增 `generationMismatchResult(expected, actual)` 静态工厂
- [ ] `ToolkitToolContext` 新增 `checkExpectedGeneration(docId, expected)` 实例方法（null 跳过）
- [ ] `ToolkitToolContext` 新增 `renderNoChangesApplied(message)` / `renderGenerationMismatch(...)`
- **验证**：`mvn -q -pl nondocx-toolkit compile`

### 切片 1：批量骨架增强（on_error + changedCount）

- [ ] `applyCellEditResults` 增加 `boolean stopOnError` 参数
- [ ] `applyRowEditResults` 增加 `boolean stopOnError` 参数
- [ ] `applyCellParagraphEditResults` 增加 `boolean stopOnError` 参数
- [ ] 循环内失败时若 `stopOnError` → break，记录 `skipped = total - i - 1`
- [ ] 汇总：`matchedCount = ok + fail`，`changedCount = ok`，`skippedCount`（stop 模式或 null）
- [ ] 成功路径返回 `ok(items, msg, matchedCount, changedCount=ok, changedRefs)`
- [ ] 部分失败返回 `partial(code, items, msg, warnings, matchedCount, changedCount, skippedCount)`
- [ ] 调用方（各 @ToolDef 方法）解析 `on_error` 参数，传 `stopOnError` 给 helper
- **验证**：`mvn -q -pl nondocx-toolkit compile`

### 切片 2：BodyTools 写工具改造

- [ ] 所有 5 个写工具增加 `expected_generation`（Integer, required=false）参数 + `@ParamCapability`
- [ ] 入口加 `checkExpectedGeneration(docId, expectedGeneration)` 校验，不符返回 `renderGenerationMismatch`
- [ ] `update_run_style`：
  - `changed.isEmpty()` 从 `INVALID_ARGUMENT` + `fail++` 改为 `NO_CHANGES_APPLIED`（L666-670）
  - 批量循环加 `on_error` 解析 + stopOnError 逻辑
  - 汇总改用 `changedCount = ok`
- [ ] `update_hyperlink`：无 text 且无 url 时返回 `NO_CHANGES_APPLIED`（L862-867）
- [ ] `update_paragraph_alignment` / `replace_run_text` / `insert_paragraph`：加 `on_error` + 计数修正
- [ ] 批量工具加 `on_error`（String, required=false, enum=[continue,stop], 默认 continue）+ `@ParamCapability`
- **验证**：`mvn -q -pl nondocx-toolkit compile` + `mvn -q -pl nondocx-toolkit test -Dtest='Body*Test'`

### 切片 3：TableTools 写工具改造

- [ ] 所有 20 个写工具增加 `expected_generation` 参数 + `@ParamCapability`
- [ ] 入口加 generation 校验
- [ ] 批量工具（经骨架）加 `on_error` 参数 + 传 `stopOnError` 给 helper
- [ ] `update_table_cell_run_style`：`changed.isEmpty()` 改为 `NO_CHANGES_APPLIED`（L1273-1280）
- [ ] `remove_table_row`（L1912）：删除成功后加 `ToolWarning(code="index_shifted", message="...")`
- [ ] `remove_table_cell`（L2002）：同上
- [ ] 单条工具（create_table/set_table_borders/add_table_row/remove_table_row/add_table_cell/
  remove_table_cell/set_table_column_*）只加 generation 校验
- [ ] 批量工具计数修正：matchedCount/changedCount 经骨架统一
- **验证**：`mvn -q -pl nondocx-toolkit compile` + `mvn -q -pl nondocx-toolkit test -Dtest='Table*Test'`

### 切片 4：TrackedChange 工具改造

- [ ] `TrackedChangeAuthoringTools` 6 个写工具加 `expected_generation`
- [ ] 批量工具（insert_tracked_run/delete_run_tracked/replace_run_tracked/mark_tracked_cells）
  加 `on_error` + 计数
- [ ] 单条（mark_style_change_tracked/move_run_tracked）只加 generation
- [ ] `TrackedChangeQueryTools`：
  - `apply_text_revisions`：加 `confirm_all`（Boolean, required=false）参数 + `@ParamCapability`
  - `scope=ALL` 且 `confirm_all != true` → 返回 `INVALID_ARGUMENT` + suggestion
  - 加 `expected_generation`
  - `apply_tracked_changes` + `set_tracked_changes_enabled`：加 `expected_generation`
- **验证**：`mvn -q -pl nondocx-toolkit compile` + `mvn -q -pl nondocx-toolkit test -Dtest='Tracked*Test'`

### 切片 5：测试

- [ ] `WriteSafetyProtocolTest`（新建）：
  - 空更新：`update_run_style` 无样式字段 → `NO_CHANGES_APPLIED`（非 INVALID_ARGUMENT）
  - 空更新：`update_table_cell_run_style` 无样式字段 → `NO_CHANGES_APPLIED`
  - 空更新：`update_hyperlink` 无 text/url → `NO_CHANGES_APPLIED`
  - generation：`expected_generation` 不符 → `GENERATION_MISMATCH`
  - generation：`expected_generation` = null → 正常执行（向后兼容）
  - generation：`expected_generation` 匹配 → 正常执行
  - on_error=continue：多条中一条失败，其余继续 → `PARTIAL_FAILURE` + skippedCount=null
  - on_error=stop：第一条失败即停 → `PARTIAL_FAILURE` + skippedCount>0
  - on_error 默认 continue：不传 on_error 行为同 continue
  - 全局防护：`apply_text_revisions(scope=ALL, confirm_all 未传)` → `INVALID_ARGUMENT`
  - 全局防护：`apply_text_revisions(scope=ALL, confirm_all=true)` → 正常执行
  - 全局防护：`scope=AUTHOR` 不受 confirm_all 影响
  - 删除提示：`remove_table_row` 成功后 warning 含 `index_shifted`
  - 计数：批量成功 → matchedCount=changedCount=N；批量部分失败 → changedCount < matchedCount
- [ ] 回归：现有 BodyToolsTest/TableToolsTest/TrackedChange*Test 不破
- [ ] `CapabilityContractTest` 通过（新参数全部标注，digest 更新）
- **验证**：`mvn -q -pl nondocx-toolkit test`

### 切片 6：全量验证 + spec + todolist

- [ ] `mvn -q -pl nondocx-toolkit spotless:apply`
- [ ] `mvn -q verify` 全绿（含 spotless + 能力契约测试 + 现有回归）
- [ ] 确认 `CapabilityContractTest` 通过：所有写工具新参数有 `@ParamCapability`、enumValues 完整
- [ ] `.trellis/spec/backend/` 记录写安全协议硬契约（no_changes_applied/generation/on_error/confirm_all）
- [ ] `docs/10-officecli-docx-learning-todolist.md` 勾选 P0-05 实施清单与验收项

## 验证命令

```bash
# 单模块快速反馈
mvn -q -pl nondocx-toolkit compile
mvn -q -pl nondocx-toolkit test -Dtest='WriteSafetyProtocolTest'
mvn -q -pl nondocx-toolkit test -Dtest='Body*Test,Table*Test,Tracked*Test'

# 能力契约
mvn -q -pl nondocx-toolkit test -Dtest='CapabilityContractTest'

# 格式化
mvn -q -pl nondocx-toolkit spotless:apply

# 全量
mvn -q verify
```

## 风险与回滚

- **风险 R1：34 个写工具逐一改造遗漏**。缓解：按工具类分切片（2/3/4），每片独立编译 +
  能力契约测试捕获漏标注的 `@ParamCapability`。
- **风险 R2：matchedCount 语义变更破坏现有 Agent**。缓解：changedCount = 旧的 ok，
  Agent 可改用 changedCount。matchedCount 语义更准确（匹配数而非尝试数）。
- **风险 R3：confirm_all 破坏现有 scope=ALL 调用**。缓解：这是有意安全加固；demo/测试
  中 `scope=ALL` 调用需加 `confirm_all=true`。manifest 反映新参数，Agent 可发现。
- **风险 R4：on_error 参数增加所有批量工具的参数数量**。缓解：可选参数，默认 continue
  与现状一致。能力契约 manifest 自动纳入。
- **风险 R5：stopOnError 在活对象模型下部分写入不可回滚**。缓解：文档化非事务语义；
  stop 模式只是"停止后续"，不回滚已执行项（与编排层 CommitCoordinator 一致）。

**回滚点**：每切片独立 commit。
- 切片 0：ToolResult 加字段是超集，可独立 revert（revert 后编译恢复）。
- 切片 1：骨架 helper 加参数，调用方同步改，可一起 revert。
- 切片 2-4：按工具类独立 revert。
- 切片 5：纯新增测试。

## 审查门

- 切片 0 完成后：确认 ToolResult 新字段不破坏现有序列化（超集）。
- 切片 1 完成后：确认骨架 helper 的 stopOnError 逻辑正确（break 后 skippedCount 准确）。
- 切片 2-4 完成后：确认所有写工具的 `expected_generation` 参数标注完整
  （`CapabilityContractTest` 捕获漏标注）。
- 切片 5 完成后：确认空更新/generation/on_error/confirm_all 四个核心场景测试全绿。
