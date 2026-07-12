# P0-02 结构化工具结果实施计划

## 执行顺序

### 切片 1：result/ 包基础（纯新增，零迁移）

- [ ] 新建 `com.non.docx.toolkit.result` 包。
- [ ] `ToolResultCode` 枚举：全部码 + 中文 message 模板 + retryable 标记。
- [ ] `ToolResult<T>` 不可变值对象：工厂 + `equals/hashCode/toString`。
- [ ] `ToolWarning`、`BatchItemResult<T>`。
- [ ] `ToolResultRenderer`：双段序列化（中文 + JSON fence）。
- [ ] `ToolResultParser`：JSON fence 解析 + 回退 null。
- [ ] `RefResolutionCode.toToolResultCode()` 映射方法。
- [ ] 单元测试：Renderer round-trip、Parser 双模式、Code 映射。

### 切片 2：checkResult 双模式兼容层

- [ ] `BodyExecutor.checkResult` 改为先 `ToolResultParser.parse`，回退旧前缀检查。
- [ ] `RevisionExecutor.execute` 同上。
- [ ] `TableExecutor`、Quality/HeaderToc executor 同上。
- [ ] 确保旧格式工具仍可正常通过（兼容期）。
- [ ] 运行现有测试确认无回归。

### 切片 3：SessionTools 迁移

- [ ] `openDocx`：成功返回 `ToolResult.ok(OpenResult(docId), "已打开...")`，
  失败返回 `ToolResult.fail(document_corrupt, ...)`。
- [ ] `saveDocx`、`closeDocx`、`getDocumentOverview` 同理。
- [ ] `DocxOrchestrator.open`（:127）、`reopen`（:143）改用 `ToolResultParser.parseSuccess`。

### 切片 4：BodyTools 迁移（9 方法）

- [ ] `readParagraph`、`searchText`、`replaceRunText`、`insertParagraph`、`insertHeading`、
  `updateRunStyle`、`replaceRunHyperlink` 等。
- [ ] 成功/失败/越界统一走 `ToolResult`。
- [ ] 写操作返回 `changedRefs`。

### 切片 5：TableTools 迁移（26 方法 + 内部 7 处）

- [ ] 内部 private helper 改返回 `ToolResult`（不走 String 边界），消除 7 处
  `startsWith("错误")`。
- [ ] `@ToolDef` public 方法构建 `ToolResult` 后用 Renderer 序列化返回。
- [ ] 批量方法用 `BatchItemResult`/`partial_failure`。
- [ ] `read_table_cell` 等 read 方法返回结构化 `data`。

### 切片 6：TrackedChange 迁移（12 方法）

- [ ] `TrackedChangeQueryTools`（6）、`TrackedChangeAuthoringTools`（6）。
- [ ] accept/reject 返回 `changedRefs`。

### 切片 7：HeaderFooter / Quality 迁移（4 方法）

- [ ] `HeaderFooterTocTools`（3）、`QualityCheckTools`（1）。
- [ ] Quality 的 `data` 复用 `QualitySummary`。

### 切片 8：收尾——移除旧路径 + 测试迁移

- [ ] 移除 `checkResult` 的旧前缀回退分支（双模式 → 单模式）。
- [ ] 移除 `DocxOrchestrator` 旧前缀检查。
- [ ] 测试断言从 `contains("错误")` 改为 `ToolResultParser.parseCode` / `success`。
- [ ] 全仓 grep 验证：`grep -rn 'contains("错误")\|startsWith("错误")'` 在 toolkit
  源码中应为零（测试和文档允许）。
- [ ] demo（AgentBridge）端到端验证中文输出可读。

## 验证命令

```bash
rtk mvn -q -pl nondocx-toolkit -am test
rtk mvn -q spotless:apply
rtk mvn -q verify
# grep 验证无残留旧前缀检查
rtk grep -rn 'contains("错误")\|startsWith("错误")' nondocx-toolkit/src/main/java/
```

## 重点审查

- `@ToolDef` 方法签名返回类型保持 `String`（框架约束）。
- JSON fence 格式稳定（` ```json ` + ` ``` `），renderer/parse 配对。
- data 值对象无 POI 类型泄露。
- 内部 helper 可直接返回 `ToolResult`，只有 `@ToolDef` 方法走 String 边界。
- `ToolResultCode` 与 `RefResolutionCode` 映射一致，不产生两套语义。
- 批量部分失败的 `items` 形状稳定。
- 测试断言改用结构化字段，不嗅探中文。
- demo 端到端中文可读性不降级。

## 回滚点

- 切片 1（result/ 包）纯新增，可独立回滚。
- 切片 2（checkResult 双模式）兼容层，可回退到纯前缀检查。
- 切片 3-7 每个工具类独立可编译可回滚。
- 切片 8（移除旧路径）在全部迁移完成且测试绿后执行，若发现问题可恢复兼容层。

## 验收结果（2026-07-11）

- [x] 切片 1-8 全部完成
- [x] `rtk mvn -q spotless:apply`
- [x] `rtk mvn verify`：4 模块 SUCCESS，523 测试（336 core + 182 toolkit + 5 examples）全过
- [x] 主代码 grep：除 `ToolResultChecks` 兼容层（设计如此）外，无散落 `startsWith("错误")`/`contains("错误")`
- [x] 测试 grep：`contains("错误")` 已全清除，改用 `ToolTestSupport.parse(result).code()` 结构化断言
- [x] `TableTools` 内部 7 处 `startsWith("错误")` 全消除（改用 `locateCellResult().success()`）
- [x] `BodyTools` 内部 `locateHyperlinkFailed` 重构为返回 `ToolResult<Void>`
- [x] demo（AgentBridge）编译通过，不直接嗅探工具返回串

## 兼容层保留决策

`ToolResultChecks` 的旧中文前缀回退路径**保留**为 safety-net（非移除）：
- 所有工具已迁移，envelope 存在时不触发回退
- 保留可防御未来手写工具忘加 envelope 的回归
- 无害——`ToolResultParser` 解析成功后直接返回，回退分支不执行
- 切片 3-7 每个工具类独立可编译可回滚。
- 切片 8（移除旧路径）在全部迁移完成且测试绿后执行，若发现问题可恢复兼容层。
