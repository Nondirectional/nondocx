# Implement: P0-04 统一语义视图

## 执行切片（每片独立可编译、可回滚）

### 切片 0：DTO + ViewQuery + ViewMeta（纯新增，零依赖）

- [ ] 新建 `view/dto/` 包
- [ ] `ViewMeta`：snapshotVersion/sessionGeneration/createdAt/truncated/totalCount
- [ ] `ViewQuery`：maxItems(200)/textTruncate(120)/expandRuns(false) + wither
- [ ] `OutlineView` + `OutlineEntry`：标题树扁平 + section/TOC/页眉页脚/修订概览
- [ ] `TextView` + `TextEntry`：body 顺序文本 + ref
- [ ] `AnnotatedView` + `AnnotatedParagraph` + `AnnotatedRun`：段落 + run 直接格式
- [ ] `StatsView` + `FontStat`：统计 + 字体/字号聚合
- [ ] `IssuesView` + `IssueEntry`：问题列表 + 汇总
- [ ] `ElementView`：单元素 kind/ref/properties
- [ ] 全部 `final class` + `List.copyOf` + `equals/hashCode`（仿 `ParagraphPreview`）
- **验证**：`mvn -q -pl nondocx-toolkit compile`

### 切片 1：DocumentViewService 核心

- [ ] 新建 `view/DocumentViewService.java`，注入 `ReferenceContext` + `QualityCheckTools`
- [ ] `buildSnapshot()`：私有 helper，调 `SnapshotBuilder.build()` 复用单遍历
- [ ] `outline()`：从 snapshot 投影 `OutlineEntry`（段落+表格按 bodyIndex 合并），截断 + maxItems
- [ ] `text()`：从 snapshot 投影 `TextEntry`，截断 + maxItems
- [ ] `stats()`：从 `snapshot.overview()` 投影 + 补 imageCount（`r.raw().getEmbeddedPictures()`）
  + sectionCount + bodyElementCount + 字体/字号聚合（遍历段落 run）
- [ ] `annotated()`：复用快照 ref + `resolver.resolve(pp.ref())` 补读 run（`expandRuns` 控制）
- [ ] `issues()`：委托 `qualityCheckTools.runAllChecks(doc)` → 包装 `IssueEntry` + severity 过滤
- [ ] `element()`：`ElementRefs.parse(refCanonical)` → `resolver.resolve()` → 按 kind 投影 `ElementView`
- [ ] 私有 `truncate(text, max)` + `countImages(doc)` helper
- **前置改动**：`QualityCheckTools.CheckResult` 提升为 `public static final class`；
  新增 `public List<CheckResult> runAllChecks(Document doc)`（提取现有 `checkQuality` 内循环）
- **验证**：`mvn -q -pl nondocx-toolkit compile`

### 切片 2：ViewTools 工具类 + 集成

- [ ] 新建 `view/ViewTools.java`，`extends ToolkitToolContext`
- [ ] 构造：`(sessions, seq, references, generations, QualityCheckTools)` → 内部建 `DocumentViewService`
- [ ] 6 个 `@ToolDef` 方法：`view_outline`/`view_text`/`view_annotated`/`view_stats`/`view_issues`/`view_element`
- [ ] 每个方法：`document(docId)` 取活文档 → null 返回 `DOCUMENT_CLOSED`；
  `generation = generations.getOrDefault(docId, 1L)`；
  构建 `ViewQuery`（从可选参数）；调 `viewService.xxx()`；`ToolResultRenderer.render(ToolResult.ok(view, msg))`
- [ ] `view_element` 的 ref 解析失败 → try-catch `RefResolutionException` → `ToolResult.fail(STALE_REF/INVALID_REF)`
- [ ] 全部标注 `@ToolCapability`（READ/QUALITY + element）+ `@ParamCapability`（STRING/INTEGER/ENUM/REF）
- [ ] `DocxToolkit`：增 `public final ViewTools view` 字段 + 构造 + `scanAll` 增 `.scan(view)`
- [ ] `CapabilityTools` 构造参数增 `view`（7→8 个文档工具）
- **验证**：`mvn -q -pl nondocx-toolkit compile` + 能力契约测试不破

### 切片 3：迁移 get_document_overview

- [ ] `SessionTools` 增 `private DocumentViewService viewService` + 包级 `bindViewService()`
- [ ] `getDocumentOverview` 改为：`viewService != null` 时委托 `stats()`，返回 `ToolResult<StatsView>`；
  null 时走旧逻辑（测试/独立使用兼容）
- [ ] 保持工具名 `get_document_overview` 不变；`@ToolCapability`/`@ParamCapability` 保持
- [ ] `DocxToolkit` 构造末尾增 `session.bindViewService(new DocumentViewService(references, qualityCheck))`
- [ ] 确认旧 4 个 int 仍在 StatsView 内（paragraphCount/tableCount/bodyElementCount/sectionCount）
- **验证**：`mvn -q -pl nondocx-toolkit test`

### 切片 4：测试

- [ ] `DocumentViewServiceTest`：
  - outline：段落+表格交错时 bodyIndex 正确；headingLevel 标记；maxItems 截断 + truncated=true
  - text：body 顺序文本 + ref；截断生效
  - stats：段落/表格/section/修订数与真实文档一致；imageCount（含图片文档）；字体/字号聚合
  - annotated：expandRuns=false 时 runs 空；expandRuns=true 时 run 直接格式（bold/font/size/color）正确
  - issues：含未通过项 + severity 过滤；passed 项不出现
  - element：按 paragraph/table/run ref 取详情；非法 ref 返回错误
  - 一致性：同一文档所有视图 sessionGeneration 一致
- [ ] `ViewToolsTest`：
  - 6 工具端到端调用 + envelope 格式（双段 String + JSON fence）
  - docId 不存在 → `DOCUMENT_CLOSED`
  - view_element 非法 ref → `INVALID_REF`/`STALE_REF`
  - ref 一致性：outline 返回的 ref 可被 view_element 解析
- [ ] 复用 `ToolTestSupport`（`parse(result).code()` 断言）+ 现有测试文档 fixture
- [ ] `get_document_overview` 迁移后回归测试（data 为 StatsView 超集）
- **验证**：`mvn -q -pl nondocx-toolkit test -Dtest='View*Test,DocumentViewServiceTest,*Overview*'`

### 切片 5：全量验证 + spec + todolist

- [ ] `mvn -q -pl nondocx-toolkit spotless:apply`
- [ ] `mvn -q verify` 全绿（含 spotless + 能力契约测试 + 现有回归）
- [ ] 确认 `CapabilityContractTest` 通过：6 个新 view_* 工具有测试覆盖、enumValues 完整、digest 更新
- [ ] `.trellis/spec/backend/orchestration-layer.md` 增加 P0-04 章节（视图服务硬契约）
- [ ] `docs/10-officecli-docx-learning-todolist.md` 勾选 P0-04 实施清单与验收项

## 验证命令

```bash
# 单模块快速反馈
mvn -q -pl nondocx-toolkit compile
mvn -q -pl nondocx-toolkit test -Dtest='View*Test,DocumentViewServiceTest'

# 能力契约
mvn -q -pl nondocx-toolkit test -Dtest='CapabilityContractTest'

# 格式化
mvn -q -pl nondocx-toolkit spotless:apply

# 全量
mvn -q verify
```

## 风险与回滚

- **风险 R1：annotated 补读 run 性能**。大型文档逐段 `resolver.resolve()` + `p.runs()` 可能慢。
  缓解：`maxItems` 默认 200 截断；`expandRuns` 默认 false（只给段落级文本）。
  Agent 定位到目标后再 `expand_runs=true` 或 `view_element`。
- **风险 R2：get_document_overview 迁移破坏调用方**。缓解：保持工具名 + data 为 StatsView 超集
  （旧 4 个 int 仍在）。executor 不直接调 overview。`viewService==null` 时走旧逻辑（兼容独立 SessionTools）。
- **风险 R3：imageCount 走 raw() 可能踩 POI inline image 遍历坑**。缓解：`try-catch` 退回 0 + warning，
  不阻塞视图。不改 `SnapshotBuilder`（保持 snapshot 第一版行为）。
- **风险 R4：CheckResult 提升为 public 影响可见性**。缓解：`CheckResult` 本就是值对象，提升 public
  无副作用；`runAllChecks` 是 `checkQuality` 内循环的提取，行为一致。
- **风险 R5：CapabilityTools 构造参数变化破坏调用方**。缓解：`CapabilityTools` 只在 `DocxToolkit`
  内构造，无外部调用方。测试如直接 `new CapabilityTools(...)` 需同步更新参数。

**回滚点**：每切片独立 commit。
- 切片 0-1 纯新增，可随时回退（删 `view/` 包）。
- 切片 2 集成：`DocxToolkit`/`CapabilityTools` 改动可 git revert。
- 切片 3 迁移：`SessionTools.bindViewService` 可移除，`getDocumentOverview` 回退旧逻辑。
- 切片 4 测试：纯新增测试，可独立删除。

## 审查门

- 切片 1 完成后：人工检查 `DocumentViewService` 是否真正复用 `SnapshotBuilder`（无第二套 body 遍历）。
- 切片 2 完成后：确认 6 个 `view_*` 工具全部标注 `@ToolCapability`/`@ParamCapability`，
  `CapabilityContractTest` 通过。
- 切片 3 完成后：确认 `get_document_overview` 向后兼容（旧测试不破）。
- 切片 4 完成后：确认 ref 一致性测试（outline ref → view_element 解析）通过。
