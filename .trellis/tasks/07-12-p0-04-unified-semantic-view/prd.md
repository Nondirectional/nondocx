# P0-04 统一语义视图

## Goal

把现有 `DocumentSnapshot` 提炼为可复用的只读服务 `DocumentViewService`，为 Agent 提供低成本、
可控上下文的 6 种语义视图。让 Agent 不必全文 dump 即可定位目标、理解结构、评估质量、按需展开细节。

不复刻 OfficeCLI 的字符串 `view` 层；复用 nondocx 已有的 `SnapshotBuilder` 单遍历 +
`ElementResolver` + `ToolResult` envelope + 能力契约基础设施。

## 已确认事实

### nondocx 现状（探索确认）

- **`SnapshotBuilder`**（`orchestration/snapshot/`）：单遍历 `doc.bodyElements()`，一次产出
  `ParagraphPreview`（ref + index + bodyIndex + 截断文本 ≤80 + headingLevel + listItem）
  和 `TablePreview`（ref + index + bodyIndex + rowCount/colCount + 首行采样）。只读、ref-aware、
  不可变输出。是天然的复用点。
- **`DocumentSnapshot`**：`SNAPSHOT_VERSION=2`，含 `overview`/`paragraphs`/`tables`/
  `revisionSummary`/`qualitySummary`，带 `sessionGeneration` 和 `isValidFor(generation)`。
- **`SnapshotOverview`**：7 字段（paragraphCount/tableCount/imageCount/trackedChangeCount/
  hasHeader/hasFooter/hasToc），但 `imageCount` 硬编码 `0`（第一版未实现图片计数）。
- **`QualityCheckTools`**：10 项内置检查，内部 `CheckResult`（name/passed/message/severity）是
  **包级可见** `static final class`；`runCheck(name, doc)` 是 **private**。`checkQuality` 返回
  `Map<String,Integer>`（passed/errors/warnings/total），不返回结构化逐条明细。
- **`SessionTools.getDocumentOverview`**：返回中文 key 的 `Map<String,Integer>`（正文段落数/正文表格数/
  body 元素数/section 数），与 `SnapshotOverview` 内容重叠但更弱。
- **P0-01**：`ElementRef`/`ElementResolver`/`ReferenceContext`/`ParagraphRef` 等全套 ref 已落地。
- **P0-02**：`ToolResult<T>` + `ToolResultRenderer` + `ToolResultCode` 已落地；`@ToolDef` 方法
  必须返回 `String`（经 renderer 序列化）。
- **P0-03**：`@ToolCapability`/`@ParamCapability`/`@NestedParamCapability` + `CapabilityCollector` +
  构建期 `capabilities.json` + CI 契约测试已落地。新增工具必须同步标注，否则构建失败。
- **`DocxToolkit`**：聚合门面，8 个工具类（session/body/table/headerFooterToc/trackedChangeQuery/
  trackedChangeAuthoring/qualityCheck/capability）。新增工具类需加字段 + 构造注入 + `scanAll` +
  `CapabilityTools` 构造参数。
- **`ToolkitToolContext`**：抽象基类，4 项共享状态（sessions/seq/references/generations），
  包级 `document(docId)` + `elementResolver(docId)` 访问器。

### OfficeCLI 参考

- `view text` / `view annotated` / `view outline` / `view stats` / `view issues`
- `src/officecli/Handlers/Word/WordHandler.View.cs`
- 重点提取：视图分类、上下文控制、ref 关联；不复制 C# 实现。

### nondocx 与 OfficeCLI 的差异

OfficeCLI 的视图是命令行字符串渲染。nondocx 的视图是 **强类型 DTO**（经 `ToolResult<T>` envelope
序列化为 JSON），Agent 可直接消费结构化字段，不需解析字符串。

## 已定决策（范围边界）

- **D1 annotated 浅层先行**：annotated 视图只给 run 直接格式（bold/italic/font/size/color）+ ref，
  不解析样式链来源（direct/style/docDefaults/theme）。effective-format-source 留 P1-02。
- **D2 issues 复用 CheckResult**：issues 视图复用 `QualityCheckTools` 现有 10 项检查，把
  `CheckResult` 包装成视图 DTO（`IssueEntry`）。不建 `DocumentIssue` 模型、不建 issue code 目录。
  留 P1-01。
- **D3 迁移范围**：新增 6 个 `view_*` 工具 + 把 `get_document_overview` 改为委托 view 服务的
  薄适配层。`read_paragraph`/`read_run` 保持不动（已 ref-aware 且工作正常）。
- **D4 复用 SnapshotBuilder**：`outline`/`text`/`stats` 视图调用 `SnapshotBuilder.build()` 一次，
  从 `DocumentSnapshot` 投影到 DTO，不建第二套 body 遍历。
- **D5 annotated 补读 run**：快照第一版不含 run 级明细。annotated 视图复用快照的 ref（保证一致性），
  经 `ElementResolver` 逐段解析活 `Document` 取 run 直接格式。
- **D6 上下文控制**：默认 `maxItems=200` + `textTruncate=120` + `expandRuns=false`，
  超过截断并标记 `truncated=true`。element 视图单元素全量，不走 maxItems。

## 需求

### R1 DocumentViewService 只读服务

- 新增 `DocumentViewService`，注入 `ReferenceContext` + `QualityCheckTools` 引用。
- 6 个视图方法：`outline()` / `text()` / `annotated()` / `stats()` / `issues()` / `element()`。
- `outline`/`text`/`stats` 复用 `SnapshotBuilder.build()`，不重新遍历 `doc.bodyElements()`。
- `annotated` 复用快照 ref + `ElementResolver` 补读 run。
- `issues` 委托 `QualityCheckTools` 跑检查 + 包装 `CheckResult` → `IssueEntry`。
- `element` 走 `ElementResolver.resolve(ref)` 取活对象投影到 `ElementView`。

### R2 强类型 DTO

- 全部 `final class` + `List.copyOf` + `equals/hashCode`（仿 `ParagraphPreview` 风格）。
- 零 POI 类型泄露（满足 `ToolResultRenderer` Jackson 序列化约束）。
- 每个视图 DTO 内嵌 `ViewMeta`（snapshotVersion + sessionGeneration + createdAt + truncated）。

### R3 上下文控制

- `ViewQuery`：`maxItems`（默认 200）、`textTruncate`（默认 120）、`expandRuns`（默认 false）。
- 超过 `maxItems` 截断并标记 `truncated=true`。
- `textTruncate` 控制 outline/text 视图的文本截断长度。
- `element` 视图单元素全量，不走 maxItems。

### R4 视图一致性

- 同一 snapshot 内所有视图引用同一 `sessionGeneration` 和 ref 集。
- outline 中每项都带可用于后续修改的 canonical ref。
- stats 与真实文档结构一致。

### R5 ViewTools 工具类

- 新增 `ViewTools`（第 9 个工具类），6 个 `view_*` 工具：
  `view_outline` / `view_text` / `view_annotated` / `view_stats` / `view_issues` / `view_element`。
- 全部走 `ToolResult<ViewDto>` envelope + `@ToolCapability`/`@ParamCapability` 标注。
- `DocxToolkit` 增 `view` 字段 + 构造注入 + `scanAll` 增加 `.scan(view)`。
- `CapabilityTools` 构造参数增加 `view` 实例（文档工具 7→8 个），纳入 manifest 反射。

### R6 get_document_overview 迁移

- `SessionTools.getDocumentOverview` 改为委托 `DocumentViewService.stats()`。
- 保持工具名 `get_document_overview` 不变（向后兼容）。
- data 升级为完整 `StatsView`（旧 4 个 int 仍包含在内，字段超集）。

### R7 imageCount 真实计数

- `SnapshotOverview.imageCount` 当前硬编码 0。`view_stats` 需真实图片数。
- 补图片计数实现（遍历 `doc.bodyElements()` 段落 run 的 inline images）。
- 若 POI inline image 遍历不稳，退回 0 + warning，不阻塞视图。

## 验收标准

- [ ] 大型文档无需全文 dump 即可定位目标（outline + maxItems 截断生效）。
- [ ] outline 中每项都带可用于后续修改的 canonical ref。
- [ ] stats 与真实文档结构一致（段落/表格/图片/section/修订数）。
- [ ] 同一 snapshot 内所有视图引用一致（同一 sessionGeneration + ref 集）。
- [ ] 6 个 `view_*` 工具均返回结构化 `ToolResult` envelope（双段 String）。
- [ ] `get_document_overview` 迁移后向后兼容（工具名不变，data 为 StatsView 超集）。
- [ ] 新增工具全部标注 `@ToolCapability`/`@ParamCapability`，能力契约测试不破。
- [ ] `mvn -q verify` 通过，无 Spotless 或现有回归失败。

## 范围外

- 不解析样式继承链来源（direct/style/docDefaults/theme）——留 P1-02。
- 不建 `DocumentIssue` 模型和 issue code 目录——留 P1-01。
- 不迁移 `read_paragraph`/`read_run`/`read_table_cell` 等现有 read 工具——保持不动。
- 不实现视觉渲染/HTML 预览——留 P1-04。
- 不引入跨进程视图服务或网络接口——视图是进程内只读服务。
- 不为 Excel/PPT 预留通用 DocumentNode 抽象（违反 todolist "明确不照搬"）。
- annotated 不解析 effective format（只给 direct format 原始值）。

## 兼容与迁移

- `ViewTools` 是新增工具类，不破坏现有 8 个工具类的清单。
- `get_document_overview` 工具名不变，data 升级为超集（旧 4 个 int 仍在 StatsView 内）。
- `DocxToolkit` 构造增加 `ViewTools` 字段，`scanAll` 增加 `.scan(view)`。
- `CapabilityTools` 构造参数从 7 个文档工具增至 8 个，manifest 自动纳入新工具。
- `QualityCheckTools` 新增一个包级可见方法暴露 `List<CheckResult>`（供 issues 视图复用），
  不改变 `check_quality` 工具行为。
