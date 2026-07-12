# P0-02 结构化工具结果

## Goal

消除 `nondocx-toolkit` 工具返回值中靠中文字符串前缀编码状态（`startsWith("错误")`）
的脆弱协议，建立统一结构化结果模型，同时保留中文文本渲染供 LLM 阅读。

## 已确认事实

- 全部 55 个 `@ToolDef` 方法返回 `String`，状态靠前缀编码：
  - 成功：`已...`、`表格(...)`、裸 `docId`。
  - 失败：`错误`、`错误:`、`错误：`。
- 字符串解析散布生产代码：4 个 executor（`BodyExecutor:316-328`、
  `RevisionExecutor:69-72`、`TableExecutor:213`、Quality/HeaderToc executor）、
  `DocxOrchestrator:127,143`、`TableTools` 内部 7 处
  （`:346,588,648,824,928,1039,1614,1890`）。
- 测试依赖字符串契约：`DocxToolkitBatchTest:237,474,772`、
  `DocxToolkitTrackedChangesTest:87,233` 断言 `contains("错误")`。
- **框架硬约束（决定性）**：nonchain `chain-0.10.0.jar` 中 `ToolRegistry.doExecute()`
  对 `@ToolDef` 返回值只做 `Object.toString()`（bytecode offset 156），无 Jackson 序列化、
  无 result adapter。`ToolHandler.execute` 返回 `String`，`Message.toolResult(String,String)`
  内容参数为 `String`，`AfterToolCall` 拦截器也只能看到已 `toString()` 后的 String。
  → `@ToolDef` 方法**不能**返回 `ToolResult<T>` POJO 让框架序列化，必须由 toolkit
  自行在 String 边界序列化。
- 现有结构化结果模板：`CommitResult`（编排层）—— 不可变值对象，静态 `success/failure`
  工厂，status flag + payload + message。
- P0-01 已落地的 ref 错误码（`stale_ref`/`element_removed`/`generation_mismatch`/
  `document_mismatch`/`ref_type_mismatch`/`invalid_ref`）目前渲染为 `错误[code]：msg`。

## 需求

### R1 结构化结果模型

- 定义统一 `ToolResult<T>`（不可变值对象），至少包含：
  - `boolean success`
  - `String code`（成功为 `ok`，失败为稳定错误码）
  - `T data`（机器可读负载：docId、ref、索引、统计等）
  - `List<ToolWarning> warnings`（非致命提示）
  - `List<ElementRef> changedRefs`（写操作实际影响范围）
  - `Integer matchedCount`（多目标匹配数）
  - `String suggestion`（可重试建议）
- 定义 `ToolWarning`（code + message + 可选 ref）。
- 定义 `BatchItemResult`（index + ToolResult），供批量操作区分单项成败。

### R2 稳定错误码目录

- 至少包含 todolist 第 152-162 行的码：
  `invalid_argument`、`index_out_of_range`、`stale_ref`、`element_removed`、
  `unsupported_feature`、`no_changes_applied`、`partial_failure`、`document_closed`、
  `document_corrupt`、`compatibility_risk`。
- 与 P0-01 的 ref 错误码合并为统一目录，不维护两套。
- 错误码为枚举或常量类，禁止散落字符串字面量。

### R3 双输出：结构化 + 中文文本

- `ToolResult` 必须能同时产出：
  - 结构化对象（内部消费、executor、测试）。
  - 中文文本（LLM 阅读，复用现有文案基线，不降级可读性）。
- 通过 `ToolResultRenderer`（或 `toText()`）产出中文，文本与结构化结果**来自同一对象**。

### R4 String 边界序列化（受框架约束）

- `@ToolDef` 方法仍返回 `String`（nonchain 框架硬约束）。
- 方法内部构建 `ToolResult`，在返回前序列化为 String。
- 序列化格式由 R5 的待定决策决定（纯文本 / JSON / 双段）。

### R5 消费方迁移

- 4 个 executor 的 `startsWith("错误")`/`contains("错误")` 改为消费结构化 `success`/`code`。
- `DocxOrchestrator` open/reopen 的 docId/error 判定改走结构化。
- `TableTools` 内部 7 处对兄弟工具输出的字符串嗅探改为结构化判定。
- 测试断言改为检查 `code`/`success`，不再 `contains("错误")`。
- **禁止**新增任何 `contains("错误")`/`startsWith("错误")` 形式的状态判断。

## 验收标准

- [x] `ToolResult<T>`、`ToolWarning`、`BatchItemResult` 值对象就位，不可变、内容相等。
- [x] 统一错误码目录（枚举/常量），覆盖 R2 全部码 + P0-01 ref 码。
- [x] 文本输出与结构化结果来自同一 `ToolResult` 对象。
- [x] 单条成功、单条失败、批量部分失败均有稳定结构化形状。
- [x] 4 个 executor、`DocxOrchestrator`、`TableTools` 内部不再靠中文前缀判断状态。
- [x] 全仓 `grep` 无新增 `contains("错误")`/`startsWith("错误")`（仅 `ToolResultChecks` 兼容层保留 safety-net）。
- [x] 测试断言改用 `code`/`success`（`ToolTestSupport.parse`），不再 `contains("错误")`。
- [x] `mvn -q verify` 通过，无 Spotless 或现有回归失败。
- [x] demo（AgentBridge）端到端仍可读回中文结果。

## 范围外

- 不改变 nonchain 框架的 String 边界（框架改动超出本仓范围）。
- 不在本任务实现 P0-03 的能力契约 schema 生成。
- 不改变 `CommitResult`（编排层已结构化，本任务只改其下游 executor 的判定方式）。
- 不删除旧中文文案（文本渲染保留，只是不再作为状态判定依据）。

## 兼容与迁移

- `@ToolDef` 方法签名返回类型保持 `String`（框架约束 + 不破坏 nonchain tool schema）。
- 序列化格式须保证 LLM 可读性不降级（当前基线是纯中文文本）。
- executor/测试迁移分批进行，每个工具类独立可编译。

## 已定决策

- **D1 输出格式 = 双段**：中文人类可读文本 + JSON 尾段（` ```json {...}``` `）。
  LLM 既能读中文又能解析 `code`/`success`。executor 解析 JSON 段取 code，不再嗅探中文前缀。
- **D2 包位置 = 新建 `result/` 包，泛型 `ToolResult<T>`**：
  `com.non.docx.toolkit.result`，与 `ref/` 平级。含 `ToolResult<T>`、`ToolWarning`、
  `BatchItemResult`、`ToolResultCode` 枚举、`ToolResultRenderer`。`RefResolutionCode`
  映射到 `ToolResultCode`（stale_ref 等复用同一码），ref 枚举保留（P0-01 存量代码依赖）。
- **D3 迁移节奏 = 分批 + checkResult 单点兼容**：先建 result 包（纯新增），再改
  `checkResult` 为双模式（优先解析 JSON 段，回退旧中文前缀），然后逐工具类迁移，
  最后移除旧路径 + 测试迁移。每切片独立可编译可回滚。
