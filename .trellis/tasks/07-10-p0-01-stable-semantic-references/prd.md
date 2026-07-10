# P0-01 稳定语义寻址

## 目标

为 `nondocx-toolkit` 建立统一、强类型、可校验的文档元素引用协议，使分析、计划、
审查和提交阶段不再依赖会漂移的位置索引，并保留现有索引输入的兼容路径。

## 已确认事实

- core 领域对象是 POI 活对象的 holding wrapper，包装实例本身不能承担稳定身份。
- toolkit 当前只共享 `docId -> Document` 与自增序号；编排层另有
  `conversationId + sessionGeneration`。
- `DocumentSnapshot` 已同时暴露投影索引 `index` 和正文顺序索引 `bodyIndex`，
  但没有稳定元素引用。
- `ConflictKey.targetRef` 当前是自由字符串，缺少类型和规范化约束。
- 正文段落可能已有 `w14:paraId`；读取路径不得为了制造持久 ID 修改文档。
- tracked change 已有进程内稳定 id，但底层节点删除后必须能报告目标失效。

## 需求

### R1 引用模型

- 定义 `DocumentRef`、`ElementRef`、`ParagraphRef`、`RunRef`、`TableRef`、
  `CellRef`、`HeaderFooterRef`、`RevisionRef`。
- 定义稳定性枚举：
  - `SESSION`：只保证当前 `sessionGeneration` 内可解析。
  - `PERSISTENT`：允许在同一逻辑文档 save/reopen 后重新解析。
- 引用必须是不可变值对象，支持内容相等、规范化字符串和明确元素类型。
- 位置索引只作为展示元数据或兼容输入，不作为引用身份。

### R2 引用签发与解析

- 定义统一 `ElementResolver`，负责活对象到 ref、ref 到活对象的双向转换。
- 无持久标识的元素使用 opaque session id，并绑定 `DocumentRef` 与
  `sessionGeneration`。
- SESSION ref 在代次变化后返回 `generation_mismatch`。
- 已删除目标返回 `element_removed`；类型、文档或格式不匹配返回明确错误码。
- resolver 必须通过重新扫描当前活文档确认目标仍存在，不能仅返回已脱离文档树的旧 wrapper。

### R3 持久段落引用

- 段落已有 `w14:paraId` 时签发 `PERSISTENT` `ParagraphRef`。
- 段落没有 `paraId` 时签发 `SESSION` ref。
- 纯读取、快照与签发引用不得补写 `paraId`。
- reopen 后，PERSISTENT 段落 ref 按 `paraId` 在当前逻辑文档中重新定位。

### R4 快照与冲突协议

- `ParagraphPreview`、`TablePreview` 等可定位快照项增加元素 ref，同时保留旧索引。
- 同一快照中的 ref 必须来自同一 resolver/document generation。
- `ConflictKey.targetRef` 改为 `ElementRef`，不再使用自由字符串承担协议。
- plan、review、commit 沿用同一规范化引用。

### R5 工具兼容

- Body/Table/HeaderFooter/TrackedChange 工具逐步支持 `ref` 与旧索引两种输入。
- 同时提供 ref 和索引时必须验证二者指向同一元素；不一致时拒绝执行。
- 旧索引继续可用，但在 Javadoc、工具描述和文档中标明弃用路线。
- 写操作结果包含解析后的规范化 ref，避免调用方继续复用旧索引。

### R6 错误契约

- 本任务至少定义以下稳定错误码：
  - `stale_ref`
  - `element_removed`
  - `generation_mismatch`
  - `document_mismatch`
  - `ref_type_mismatch`
  - `invalid_ref`
- P0-02 落地前，现有字符串工具返回值可渲染上述 code；内部不得靠人类文本判断错误类型。

## 验收标准

- [x] 读取段落并取得 `ParagraphRef` 后，在其前方插入新段落，旧 ref 仍解析到原段落。
- [x] 段落和表格交错时，ref 解析不依赖 `paragraph_index/body_index` 猜测。
- [x] 删除目标后再次解析旧 ref，稳定得到 `element_removed`。
- [x] close/reopen 后解析 SESSION ref，稳定得到 `generation_mismatch`。
- [x] 已有 `w14:paraId` 的段落可在 save/reopen 后由同一 PERSISTENT ref 重新定位。
- [x] 快照段落与表格项同时包含 ref、投影索引和 body 索引。
- [x] `ConflictKey` 对规范化相同 ref 内容相等，不接受自由 target 字符串。
- [x] 新增单元测试覆盖 ref 值语义、resolver 生命周期、索引兼容和持久段落定位。
- [x] `mvn -q verify` 通过，无新增 Spotless 或现有回归失败。

## 范围外

- 不在本任务完成 P0-02 的通用 `ToolResult<T>` envelope。
- 不为缺少 `paraId` 的既有段落自动补写持久 ID。
- 不承诺所有元素都支持 PERSISTENT；第一版只要求已有 `paraId` 的正文段落。
- 不改变 core holding-wrapper、内容相等和 `raw()` 契约。
- 不引入跨进程或数据库式引用注册表。

## 兼容与迁移

- 旧索引工具调用保持可运行，内部尽早转为 ref 再执行。
- 公开索引参数先标记 deprecated 路线，不在本任务直接删除。
- 自由字符串 `ConflictKey` 构造器可保留短期兼容工厂，但内部模型和新代码只使用
  `ElementRef`。
