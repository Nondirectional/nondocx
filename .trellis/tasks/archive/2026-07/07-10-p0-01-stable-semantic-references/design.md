# P0-01 稳定语义寻址设计

## 1. 边界

引用协议属于 `nondocx-toolkit` 的 Agent/编排适配层，不进入 core 领域 API。
core 保持活对象、强类型领域方法和 POI 隔离；toolkit 负责会话身份、引用签发、
索引兼容和错误码渲染。

需要读取 `w14:paraId` 时，把 OOXML 访问收敛到 core `internal/poi` 的只读 helper，
toolkit 不散落 XmlCursor/POI 解析逻辑。

## 2. 类型模型

新增包 `com.non.docx.toolkit.ref`：

- `RefStability`: `SESSION` / `PERSISTENT`
- `ElementKind`: `PARAGRAPH` / `RUN` / `TABLE` / `CELL` /
  `HEADER_FOOTER` / `REVISION`
- `DocumentRef`
  - `documentKey`: 逻辑文档标识，编排层使用 `conversationId`，直接 toolkit 使用 `docId`
  - `sessionGeneration`: 当前活文档代次
- `ElementRef`
  - `DocumentRef documentRef()`
  - `ElementKind kind()`
  - `RefStability stability()`
  - `String canonical()`
- 各具体 ref 使用显式字段，不使用可变 Map：
  - SESSION ref: 文档作用域 opaque id
  - PERSISTENT `ParagraphRef`: `paraId`
  - `RunRef` / `CellRef` / `HeaderFooterRef` / `RevisionRef` 第一版同样使用文档作用域
    opaque id；resolver registry 保存真实父子与 delegate identity，不把位置路径编码成身份

Java 11 不使用 sealed interface。`ElementRef` 为公开接口，各实现为 `final`，
手写 `equals/hashCode/toString`。

## 3. 规范化格式

规范化字符串只用于日志、LLM payload 和兼容传输，不作为内部自由字符串协议。

示例：

```text
doc:<escaped-document-key>@g2/paragraph:persistent:00A1B2C3
doc:<escaped-document-key>@g2/paragraph:session:p-7
doc:<escaped-document-key>@g2/run:session:r-3
doc:<escaped-document-key>@g2/table:session:t-2
doc:<escaped-document-key>@g2/cell:session:c-8
```

解析集中在 `ElementRefs.parse`。格式非法返回 `invalid_ref`，不得由各工具自行 split。

## 4. Resolver

`ElementResolver` 绑定一个当前 `DocumentRef` 和活 `Document`，维护会话内 registry：

- opaque id -> `RegisteredElement`
- delegate identity -> opaque id，使用 `IdentityHashMap`
- 每类元素有统一扫描器，从当前文档树重建“当前仍存在的 delegate identity”集合

签发 SESSION ref 时记录底层 delegate identity，不记录位置索引。解析时：

1. 校验逻辑文档 key。
2. SESSION ref 校验 generation；PERSISTENT ref 允许 generation 变化。
3. 校验 ref kind 与请求类型。
4. SESSION ref 重新扫描当前文档，按 delegate identity 查找。
5. PERSISTENT 段落 ref 扫描正文段落并比较已有 `paraId`。
6. registry 存在但当前树找不到时返回 `element_removed`。
7. registry 不认识 opaque id 时返回 `stale_ref`。

插入导致位置变化时 delegate identity 不变，因此旧 ref 仍命中。删除后 wrapper 可能仍可读，
但重新扫描找不到，必须返回 `element_removed`。

## 5. 生命周期

新增共享 `ReferenceContext`，与 `sessions/seq` 一起由 `SessionTools` 创建并由
`DocxToolkit` 注入所有工具：

- `docId -> generation`
- `docId -> ElementResolver`
- open 创建 generation 1 resolver
- close 先使 resolver 失效，再移除文档
- orchestration reopen 用稳定 `conversationId` 创建新 `DocumentRef`，generation 递增

编排层 `OrchestratorSession` 继续拥有代次真相；构建快照时显式把
`DocumentRef(conversationId, generation)` 传给 resolver，避免从全局猜测。

## 6. 快照

`SnapshotBuilder` 单次 body 遍历时同时签发 ref：

- `ParagraphPreview`: `ParagraphRef ref`, `index`, `bodyIndex`
- `TablePreview`: `TableRef ref`, `index`, `bodyIndex`

旧索引继续用于展示和兼容输入。后续 P0-04 的 `element` 视图可直接复用同一 resolver。

快照 schema 发生兼容新增，`SNAPSHOT_VERSION` 从 1 升到 2。

## 7. ConflictKey

`ConflictKey.targetRef` 类型改为 `ElementRef`：

- `sameTarget` 比较规范化 ref 的值语义。
- `toString` 使用 `targetRef.canonical()`。
- `Operation` 静态工厂和 Agent 规划代码必须从快照 ref 构建 key。
- 短期兼容字符串只在 payload 解析边界转换，不进入 `ConflictKey` 状态。

## 8. 工具兼容策略

现有工具继续保留索引参数，增加 ref 输入时遵循统一顺序：

1. 有 ref：解析 ref。
2. 同时有旧索引：解析索引并验证与 ref 同一 delegate。
3. 只有旧索引：先按索引定位，再签发 ref。
4. 执行写操作。
5. 返回规范化 ref。

批量 Map payload 优先新增 `"ref"` 字段。直接参数工具新增重载或内部 DTO，避免一次性破坏
`@ToolDef` 现有签名。

## 9. 错误模型

新增 `RefResolutionCode` 和 `RefResolutionException`。内部按 code 分支，工具边界统一渲染：

```text
错误[generation_mismatch]：引用来自代次 1，当前代次为 2
```

本任务不引入通用 `ToolResult<T>`，为 P0-02 保留迁移空间。

## 10. 实施切片

1. 引用值对象、parser、错误码与 resolver 基础。
2. 段落/表格签发解析与快照接线。
3. `ConflictKey` 强类型迁移。
4. Body/Table 旧索引适配与写结果 ref。
5. HeaderFooter/Revision 接线。
6. reopen、删除、插入漂移、paraId 持久定位测试。

## 11. 风险与回滚

- 风险：直接 wrapper 引用在删除后仍可访问。防护：每次解析重扫当前树。
- 风险：`paraId` 可能重复或格式异常。防护：重复时报 `stale_ref`，不猜第一个。
- 风险：一次性修改全部工具签名破坏 nonchain schema。防护：先增加 payload `ref`
  和内部适配，不删除旧字段。
- 风险：`ConflictKey` 构造调用点多。防护：编译驱动迁移并保留单一兼容 parser。
- 回滚：每个切片独立可编译；若工具接线过大，可保留模型、快照、冲突键，后续继续迁移。
