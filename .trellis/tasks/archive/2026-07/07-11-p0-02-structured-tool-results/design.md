# P0-02 结构化工具结果设计

## 1. 边界

结构化结果模型属于 `nondocx-toolkit` 的 Agent/编排适配层，不进入 core 领域 API。

**框架硬约束（决定性）**：nonchain `chain-0.10.0.jar` 的 `ToolRegistry.doExecute()` 对
`@ToolDef` 返回值只做 `Object.toString()`（bytecode offset 156），无 Jackson 序列化、
无 result adapter。`Message.toolResult(String,String)`、`ToolHandler.execute` 返回类型、
`AfterToolCall` 拦截器的 context/result 全是 `String`。

→ 结论：`@ToolDef` 方法**必须**返回 `String`。`ToolResult<T>` POJO 在 toolkit 内部构建，
序列化为 String 后返回。序列化由 toolkit 负责，不由框架负责。

## 2. 类型模型

新增包 `com.non.docx.toolkit.result`：

### ToolResultCode（枚举）

统一错误码目录。成功为 `OK`。包含 todolist R2 全部码 + P0-01 ref 码映射：

```
OK
invalid_argument
index_out_of_range
stale_ref              // 映射自 RefResolutionCode.STALE_REF
element_removed        // 映射自 RefResolutionCode.ELEMENT_REMOVED
generation_mismatch    // 映射自 RefResolutionCode.GENERATION_MISMATCH
document_mismatch      // 映射自 RefResolutionCode.DOCUMENT_MISMATCH
ref_type_mismatch      // 映射自 RefResolutionCode.REF_TYPE_MISMATCH
invalid_ref            // 映射自 RefResolutionCode.INVALID_REF
unsupported_feature
no_changes_applied
partial_failure
document_closed
document_corrupt
compatibility_risk
```

每个 code 携带稳定中文 message 模板和 retryable 标记（可重试 vs 不可重试）。
`RefResolutionCode` 保留（P0-01 存量代码依赖），提供 `toToolResultCode()` 映射方法。

### ToolResult<T>（不可变值对象）

```java
public final class ToolResult<T> {
  private final boolean success;
  private final ToolResultCode code;
  private final String message;       // 中文人类可读消息
  private final T data;               // 机器可读负载（可为 null）
  private final List<ToolWarning> warnings;
  private final List<String> changedRefs;  // canonical ref 字符串
  private final Integer matchedCount;

  // 静态工厂
  public static <T> ToolResult<T> ok(T data, String message);
  public static <T> ToolResult<T> ok(T data, String message, List<String> changedRefs);
  public static ToolResult<Void> fail(ToolResultCode code, String message);
  public static ToolResult<Void> fail(ToolResultCode code, String message, String suggestion);
  public static <T> ToolResult<T> partial(T data, String message, List<ToolWarning> warnings);

  // 带 suggestion 的工厂（建议单独存储，不混进 message）
  public static <T> ToolResult<T> okWith(T data, String message, String suggestion);
}
```

- 不可变：`List.copyOf` 复制集合。
- 手写 `equals/hashCode/toString`，遵循 `CommitResult` 风格。
- `toString()` 产出双段格式（见 §4）。

### ToolWarning

```java
public final class ToolWarning {
  private final ToolResultCode code;   // 或 String warningCode
  private final String message;
  private final String ref;            // 可选，关联元素

  public static ToolWarning of(ToolResultCode code, String message);
  public static ToolWarning of(ToolResultCode code, String message, String ref);
}
```

### BatchItemResult<T>

```java
public final class BatchItemResult<T> {
  private final int index;
  private final ToolResult<T> result;

  public static <T> BatchItemResult<T> of(int index, ToolResult<T> result);
}
```

批量操作的 `data` 可为 `List<BatchItemResult<?>>`，单项成败通过 `result.code` 区分。

## 3. 序列化：ToolResultRenderer

负责 `ToolResult<T>` → 双段 String：

```
<中文人类可读消息>
```json
{"success":true,"code":"ok","data":{...},"matchedCount":1,"changedRefs":[...]}
```
```

失败时：

```
<中文消息>[<code>]
```json
{"success":false,"code":"index_out_of_range","message":"run 索引 5 越界（共 2）","suggestion":"使用 0..1"}
```
```

### 实现要点

- `data` 序列化用 Jackson `ObjectMapper`（项目已依赖，见 `pom.xml`）。
- `data` 为 null 时不输出 `data` 字段。
- JSON 块用 ` ```json ` fence 包裹，便于 LLM 和 executor 识别。
- `warnings` 非空时并入 JSON。
- Renderer 是无状态工具类，`static String render(ToolResult<?> result)`。

### 双模式解析：ToolResultParser

```java
public final class ToolResultParser {
  // 从 @ToolDef 返回的 String 中提取结构化 code/success
  // 优先解析 ```json``` fence 内容；解析失败回退 null（交由旧 checkResult 兼容）
  public static ToolResultCode parseCode(String toolOutput);
  public static boolean parseSuccess(String toolOutput);
  public static ToolResultSnapshot parse(String toolOutput);  // 含 code/success/message/data
}
```

executor 消费：

```java
// 新路径：解析 JSON 段
ToolResultSnapshot snap = ToolResultParser.parse(result);
if (snap != null) {
  if (!snap.success()) throw new OperationExecutionException(snap.message());
  return result;
}
// 旧路径（混合期回退）：未迁移工具仍返回纯中文
if (result.startsWith("错误") || result.contains("错误:") || result.contains("错误：")) {
  throw new OperationExecutionException(result);
}
```

迁移完成后移除旧路径。

## 4. 双段格式契约

### 成功示例

```
已修改：段落 0 的 run 0 文本
```json
{"success":true,"code":"ok","matchedCount":1,"changedRefs":["doc:x@g1/run:session:r-0"]}
```
```

### 失败示例

```
run 索引 5 越界（共 2）[index_out_of_range]
```json
{"success":false,"code":"index_out_of_range","message":"run 索引 5 越界（共 2）","suggestion":"使用 0..1"}
```
```

### 批量部分失败示例

```
批量完成：2 成功，1 失败
```json
{"success":false,"code":"partial_failure","matchedCount":3,"items":[{"index":0,"success":true},{"index":1,"success":false,"code":"index_out_of_range","message":"..."}]}
```
```

## 5. 消费方迁移

### executor（4 个）

- `BodyExecutor.checkResult`：改为先 `ToolResultParser.parse`，回退旧前缀检查。
- `RevisionExecutor.execute`：同上。
- `TableExecutor`、Quality/HeaderToc executor：同上。
- 迁移完成后移除旧前缀检查分支。

### DocxOrchestrator

- `open`（:127）、`reopen`（:143）：`openDocx` 成功返回 `docId`（data），失败返回 code。
  改为解析 `ToolResultParser.parseSuccess`。

### TableTools 内部（7 处）

- `:346,588,824,928,1039,1614,1890`：内部调兄弟工具方法后 `startsWith("错误")`。
  改为兄弟方法返回 `ToolResult`（内部方法可不返回 String，直接返回 `ToolResult`；
  只有 `@ToolDef` 注解方法才需 String 序列化边界）。

**关键洞察**：`TableTools` 内部非 `@ToolDef` 的 private helper 可以直接返回
`ToolResult`，不必序列化。只有标注 `@ToolDef` 的 public 方法才走 String 边界。
这大幅减少内部嗅探点。

### 测试

- `DocxToolkitBatchTest:237,474,772`、`DocxToolkitTrackedChangesTest:87,233`：
  断言从 `contains("错误")` 改为解析 `code` 或检查 `success`。
  混合期可用 `ToolResultParser.parseCode(result)` 取 code 断言。

## 6. data 类型约定

各工具的 `data` 用轻量值对象，不用 POI 类型：

| 工具类 | data 类型 | 内容 |
|---|---|---|
| SessionTools.openDocx | `OpenResult` | `docId`, `snapshotVersion` |
| SessionTools.saveDocx | `SaveResult` | `path` |
| BodyTools.* | `MutationResult` | `changedRefs`, `matchedCount` |
| TableTools.* | `TableMutationResult` / `List<BatchItemResult>` | ref + 索引 |
| QualityCheckTools | `QualityData` | 复用现有 `QualitySummary` |

`data` 为 `null` 时 JSON 不输出该字段。

## 7. 风险与回滚

- **风险**：JSON fence 被误判为普通文本。防护：固定 ` ```json ` 开头 + ` ``` ` 结尾，
  renderer/parse 配对使用。
- **风险**：Jackson 序列化 POI 类型泄露。防护：data 只用轻量值对象，Renderer 对未知类型
  走 `toString()` 兜底而非 Jackson。
- **风险**：混合期 executor 双路径掩盖 bug。防护：迁移完成的工具类在 checkResult
  记录"已迁移"标记（或用断言），CI grep 确保无残留旧前缀检查。
- **风险**：内部 helper 改返回 `ToolResult` 破坏调用链。防护：内部重构与 `@ToolDef`
  边界迁移分切片，每切片可编译。
- **回滚**：result/ 包是纯新增，切片 1 可独立回滚。后续切片每个工具类独立可回滚。
  checkResult 兼容层保证混合期不破坏现有行为。

## 8. 与 P0-03 的关系

P0-03（能力契约）将消费 `ToolResultCode` 目录和 `data` 结构，生成 capability schema。
本任务的 `ToolResultCode` 枚举是 P0-03 schema 的稳定来源之一，但不在本任务生成 schema。
