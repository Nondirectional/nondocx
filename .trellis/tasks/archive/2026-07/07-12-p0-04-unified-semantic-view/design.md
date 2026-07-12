# Design: P0-04 统一语义视图

## 架构总览

```
DocumentViewService (只读服务)
  │  注入: ReferenceContext + QualityCheckTools 引用
  │  复用: SnapshotBuilder (单遍历, ref-aware)
  │
  ├─ outline()   ─→ SnapshotBuilder.build() → 投影标题树/section/表格/图片/TOC/页眉页脚/修订概览
  ├─ text()      ─→ SnapshotBuilder.build() → 投影按文档顺序的文本 + ref
  ├─ annotated() ─→ 快照 ref + ElementResolver 逐段补读 run 直接格式
  ├─ stats()     ─→ SnapshotBuilder.build() → 投影统计 + 补 imageCount/字体/字号聚合
  ├─ issues()    ─→ QualityCheckTools.runAllChecks(doc) → 包装 CheckResult → IssueEntry
  └─ element()   ─→ ElementRef parse → ElementResolver.resolve(ref) → 投影单元素详情
        │
        ▼
ViewTools (第 9 个工具类, 6 个 view_* @ToolDef)
  │  全部返回 ToolResult<ViewDto> → ToolResultRenderer.render() → 双段 String
  │  全部标注 @ToolCapability + @ParamCapability (P0-03 能力契约)
        │
        ▼
DocxToolkit.scanAll(ToolRegistry)  ── .scan(view) ── 加入工具清单
CapabilityTools 构造               ── 接收 view 实例 ── 纳入 manifest 反射
```

单一职责：`DocumentViewService` 只读、不改文档、不做冲突检测；`ViewTools` 是薄适配层，
只做 docId 解析 + 参数校验 + envelope 包装。

## 包结构

新增包 `com.non.docx.toolkit.view`（与 `ref/`、`result/`、`capability/`、`orchestration/` 平级）：

```
nondocx-toolkit/src/main/java/com/non/docx/toolkit/view/
├── DocumentViewService.java       // 只读服务核心
├── ViewQuery.java                 // 查询参数 (maxItems/textTruncate/expandRuns)
├── ViewTools.java                 // 第 9 个工具类, 6 个 view_* @ToolDef
└── dto/
    ├── ViewMeta.java              // snapshotVersion/sessionGeneration/createdAt/truncated
    ├── OutlineView.java           // 标题树 + section + 表格/图片/TOC/页眉页脚/修订概览
    ├── OutlineEntry.java          // 单项: kind/headingLevel/ref/text/bodyIndex/children
    ├── TextView.java              // 按文档顺序的文本条目列表
    ├── TextEntry.java             // bodyIndex/ref/kind/text
    ├── AnnotatedView.java         // 段落级 annotated 列表
    ├── AnnotatedParagraph.java    // ref/index/bodyIndex/text/runs[]
    ├── AnnotatedRun.java          // text/bold/italic/font/size/color/ref
    ├── StatsView.java             // 段落/表格/图片/section/修订/字体/字号统计
    ├── FontStat.java              // fontName/count
    ├── IssuesView.java            // 问题列表 + 汇总
    ├── IssueEntry.java            // checkName/passed/severity/message
    └── ElementView.java           // 单元素详情: kind/ref/properties

nondocx-toolkit/src/test/java/com/non/docx/toolkit/view/
├── DocumentViewServiceTest.java   // 6 视图正确性 + 截断 + 一致性
└── ViewToolsTest.java             // 工具端到端 + envelope + ref 一致性
```

## DTO 设计

### 公共原则

- 全部 `public final class`，字段 `private final`，构造 `List.copyOf`，`equals/hashCode` 用 `Objects.hash`。
- 零 POI 类型（满足 `ToolResultRenderer` Jackson `NON_NULL` 序列化约束）。
- ref 字段存 **canonical String**（`ref.canonical()`），不存 `ElementRef` 对象（DTO 要可序列化）。
- 仿现有 `ParagraphPreview` 风格。

### ViewMeta（所有视图内嵌）

```java
public final class ViewMeta {
  int snapshotVersion;       // = DocumentSnapshot.SNAPSHOT_VERSION (2)
  long sessionGeneration;    // 当前会话代次
  String createdAt;          // ISO-8601 快照时刻
  boolean truncated;         // 是否因 maxItems 截断
  int totalCount;            // 未截断时的总条数（截断时 > 返回条数）
}
```

### ViewQuery（查询参数）

```java
public final class ViewQuery {
  int maxItems = 200;        // 最大返回条数；超过截断并标记 truncated
  int textTruncate = 120;    // outline/text 文本截断长度
  boolean expandRuns = false; // annotated 是否展开 run（默认 false：只给段落级文本）
  // element 视图不走 maxItems（单元素全量）
}
```

不可变值对象 + `withMaxItems(int)` / `withTextTruncate(int)` / `withExpandRuns(boolean)` wither。

### OutlineView / OutlineEntry

```java
public final class OutlineView {
  ViewMeta meta;
  List<OutlineEntry> entries;   // body 顺序
  boolean hasToc;
  boolean hasHeader;
  boolean hasFooter;
  int sectionCount;
  RevisionOverview revision;    // enabled/totalCount/countByType
}

public final class OutlineEntry {
  String kind;            // "paragraph" | "table"
  String ref;             // canonical ref
  int bodyIndex;
  String headingLevel;    // "1".."6" 或 null（非标题）
  String text;            // 截断文本
  boolean listItem;
  // table 专属（kind=table 时）
  int rowCount;
  int columnCount;
}
```

outline 不做嵌套标题树（第一版按 body 顺序扁平列出标题 + 表格）。
嵌套树留后续，避免过度设计。

### TextView / TextEntry

```java
public final class TextView {
  ViewMeta meta;
  List<TextEntry> entries;   // body 顺序
}

public final class TextEntry {
  String kind;      // "paragraph" | "table"
  String ref;       // canonical
  int bodyIndex;
  String text;      // 段落文本或表格采样文本（截断）
}
```

### AnnotatedView / AnnotatedParagraph / AnnotatedRun

```java
public final class AnnotatedView {
  ViewMeta meta;
  List<AnnotatedParagraph> paragraphs;
}

public final class AnnotatedParagraph {
  String ref;          // canonical ParagraphRef
  int index;
  int bodyIndex;
  String text;         // 段落全文（截断）
  List<AnnotatedRun> runs;  // expandRuns=true 时填充；false 时空列表
}

public final class AnnotatedRun {
  String ref;          // canonical RunRef
  String text;         // run 文本（截断）
  boolean bold;
  boolean italic;
  String font;         // run.font()
  Integer fontSize;    // run.fontSize()（可能 null）
  String color;        // run.color()（可能 null）
}
```

### StatsView / FontStat

```java
public final class StatsView {
  ViewMeta meta;
  int paragraphCount;
  int tableCount;
  int imageCount;
  int sectionCount;
  int bodyElementCount;
  int trackedChangeCount;
  boolean hasToc;
  boolean hasHeader;
  boolean hasFooter;
  List<FontStat> fonts;      // 按使用次数降序
  List<Integer> fontSizes;   // 出现过的字号（pt）
}

public final class FontStat {
  String fontName;
  int count;   // 使用该字体的 run 数
}
```

StatsView 是 `get_document_overview` 旧 4 int 的超集：
旧 `正文段落数`→`paragraphCount`、`正文表格数`→`tableCount`、`body 元素数`→`bodyElementCount`、
`section 数`→`sectionCount`。

### IssuesView / IssueEntry

```java
public final class IssuesView {
  ViewMeta meta;
  int passedCount;
  int warningCount;
  int errorCount;
  List<IssueEntry> issues;   // 只含未通过项（passed=false）
}

public final class IssueEntry {
  String checkName;    // "blank-pages"/"line-spacing"/...
  boolean passed;      // 固定 false（issues 列表只含未通过项）
  String severity;     // "error" | "warning"
  String message;      // 中文消息
}
```

IssuesView 不建 issue code 目录（留 P1-01）；`checkName` 复用 `QualityCheckTools.ALL_CHECKS` 的名称。

### ElementView

```java
public final class ElementView {
  ViewMeta meta;
  String kind;            // "paragraph"/"run"/"table"/"cell"/...
  String ref;             // canonical
  Map<String, Object> properties;  // 元素特定属性（段落: text/headingLevel/listItem; 表格: rowCount/colCount; run: text/bold/...）
}
```

`properties` 用 `Map<String,Object>` 因为不同元素类型属性集不同。
第一版支持 paragraph / table / run；其余类型返回 kind + ref + 空 properties。

## DocumentViewService 设计

### 构造与依赖

```java
public final class DocumentViewService {
  private final ReferenceContext referenceContext;
  private final QualityCheckTools qualityCheckTools;  // 供 issues() 复用

  public DocumentViewService(ReferenceContext referenceContext, QualityCheckTools qualityCheckTools) { ... }

  // 6 个视图方法，均接收 Document + docId + sessionGeneration + ViewQuery
  public OutlineView outline(Document doc, String docId, long generation, ViewQuery query) { ... }
  public TextView text(Document doc, String docId, long generation, ViewQuery query) { ... }
  public AnnotatedView annotated(Document doc, String docId, long generation, ViewQuery query) { ... }
  public StatsView stats(Document doc, String docId, long generation) { ... }
  public IssuesView issues(Document doc, String docId, long generation, String severityFilter) { ... }
  public ElementView element(Document doc, String docId, long generation, String refCanonical) { ... }
}
```

### 复用 SnapshotBuilder（outline/text/stats）

```java
private DocumentSnapshot buildSnapshot(Document doc, String docId, long generation) {
  SnapshotBuilder builder = new SnapshotBuilder(referenceContext);
  return builder.build(doc, docId, Path.of(snapshot.sourcePath()), generation);
  // 或直接传 null path（view 场景不关心 sourcePath）
}
```

`outline()`：调 `buildSnapshot()`，从 `snapshot.paragraphs()` / `snapshot.tables()` 投影 `OutlineEntry`。
按 bodyIndex 排序合并段落和表格。截断 text 到 `query.textTruncate()`。超过 `query.maxItems()` 截断。

`text()`：同上投影 `TextEntry`。

`stats()`：从 `snapshot.overview()` 取 paragraphCount/tableCount/trackedChangeCount/hasHeader/hasFooter/hasToc。
补 imageCount（独立计数，见下）、sectionCount（`doc.sections().size()`）、bodyElementCount（`doc.bodyElements().size()`）、
字体/字号聚合（遍历段落 run）。

### annotated 补读 run

```java
public AnnotatedView annotated(Document doc, String docId, long generation, ViewQuery query) {
  DocumentSnapshot snapshot = buildSnapshot(doc, docId, generation);
  ElementResolver resolver = referenceContext.resolver(
      new DocumentRef(docId, generation), doc);
  List<AnnotatedParagraph> paragraphs = new ArrayList<>();
  for (ParagraphPreview pp : snapshot.paragraphs()) {
    Paragraph p = resolver.resolve(pp.ref());  // 复用快照 ref
    List<AnnotatedRun> runs = List.of();
    if (query.expandRuns()) {
      runs = p.runs().stream()
          .map(r -> new AnnotatedRun(
              resolver.reference(r).canonical(), truncate(r.text()),
              r.bold(), r.italic(), r.font(), r.fontSize(), r.color()))
          .collect(toList());
    }
    paragraphs.add(new AnnotatedParagraph(pp.ref().canonical(), pp.index(), pp.bodyIndex(),
        truncate(pp.text(), query.textTruncate()), runs));
    if (paragraphs.size() >= query.maxItems()) break;  // 截断
  }
  ...
}
```

**关键**：复用快照的 `ParagraphRef`（`pp.ref()`）保证一致性；不重新签发 ref。
`resolver.resolve(pp.ref())` 会校验 generation 并重扫活文档树。

### issues 复用 QualityCheckTools

`QualityCheckTools` 需新增一个**包级可见**方法暴露 `List<CheckResult>`：

```java
// QualityCheckTools.java 新增
List<CheckResult> runAllChecks(Document doc) {
  List<CheckResult> results = new ArrayList<>();
  for (String name : ALL_CHECKS) {
    try {
      results.add(runCheck(name, doc));
    } catch (Exception e) {
      results.add(new CheckResult(name, false, "检查异常: " + rootMessage(e), "error"));
    }
  }
  return results;
}
```

`CheckResult` 已是包级可见（`static final class`，无 `private`/`public` 修饰符），
`view` 包与 `toolkit` 包不同 → 需将 `CheckResult` 提升为 `public`，或把 `runAllChecks` 返回
`List<IssueEntry>` 直接在 `QualityCheckTools` 内转换。

**决策**：`CheckResult` 提升为 `public static final class`（最小改动，让 view 包可消费）。
`runAllChecks` 提升为 `public`。这不改变 `check_quality` 工具行为。

```java
public IssuesView issues(Document doc, String docId, long generation, String severityFilter) {
  List<CheckResult> results = qualityCheckTools.runAllChecks(doc);
  List<IssueEntry> issues = results.stream()
      .filter(r -> !r.passed)
      .filter(r -> severityFilter == null || r.severity.equals(severityFilter))
      .map(r -> new IssueEntry(r.name, r.passed, r.severity, r.message))
      .collect(toList());
  ...
}
```

### element 视图

```java
public ElementView element(Document doc, String docId, long generation, String refCanonical) {
  ElementRef ref = ElementRefRefs.parse(refCanonical);  // 复用 P0-01 ElementRefRefs.parse
  ElementResolver resolver = referenceContext.resolver(new DocumentRef(docId, generation), doc);
  // 按 ref.kind() 分发
  switch (ref.kind()) {
    case PARAGRAPH:
      Paragraph p = resolver.resolve((ParagraphRef) ref);
      props = Map.of("text", p.text(), "headingLevel", ..., "listItem", ...);
      break;
    case TABLE:
      Table t = resolver.resolve((TableRef) ref);
      props = Map.of("rowCount", t.rows().size(), "columnCount", ...);
      break;
    case RUN:
      Run r = resolver.resolve((RunRef) ref);
      props = Map.of("text", r.text(), "bold", r.bold(), "italic", r.italic(), ...);
      break;
    default:
      props = Map.of();  // 其余类型第一版返回空 properties
  }
  return new ElementView(meta, ref.kind().name().toLowerCase(), refCanonical, props);
}
```

ref 解析失败时抛 `RefResolutionException`，由 `ViewTools` 捕获转为 `ToolResult.fail(STALE_REF/...)`。

### imageCount 真实计数

`SnapshotBuilder.countImages()` 当前返回 0。在 `DocumentViewService.stats()` 内独立计数：

```java
private int countImages(Document doc) {
  int count = 0;
  for (BodyElement be : doc.bodyElements()) {
    if (be instanceof Paragraph) {
      for (Run r : ((Paragraph) be).runs()) {
        try {
          count += r.raw().getEmbeddedPictures().size();
        } catch (RuntimeException ignored) { }
      }
    }
  }
  return count;
}
```

走 `r.raw().getEmbeddedPictures()`（POI XWPFRun 逃生舱）。若不稳，try-catch 退回 0 + warning。
不改 `SnapshotBuilder`（保持 snapshot 第一版行为不变）；imageCount 只在 view_stats 内补。

## ViewTools 工具签名

```java
public final class ViewTools extends ToolkitToolContext {

  private final DocumentViewService viewService;

  ViewTools(Map sessions, AtomicInteger seq, ReferenceContext refs,
            Map generations, QualityCheckTools qualityCheckTools) {
    super(sessions, seq, refs, generations);
    this.viewService = new DocumentViewService(refs, qualityCheckTools);
  }

  @ToolDef(name = "view_outline", description = "...")
  @ToolCapability(operation = READ, element = "document")
  public String viewOutline(
      @ToolParam(name = "doc_id", ...) @ParamCapability(type = STRING) String docId,
      @ToolParam(name = "max_items", required = false) @ParamCapability(type = INTEGER) Integer maxItems,
      @ToolParam(name = "text_truncate", required = false) @ParamCapability(type = INTEGER) Integer textTruncate) {
    // 1. document(docId) 取活文档；null → ToolResult.fail(DOCUMENT_CLOSED)
    // 2. generation = generations.getOrDefault(docId, 1L)
    // 3. ViewQuery query = ViewQuery.defaults().withMaxItems(...).withTextTruncate(...)
    // 4. OutlineView view = viewService.outline(doc, docId, generation, query)
    // 5. return ToolResultRenderer.render(ToolResult.ok(view, "大纲视图..."))
  }

  // view_text / view_annotated / view_stats / view_issues / view_element 同构
}
```

### 6 工具参数表

| 工具名 | operation | element | 参数 | data DTO |
|---|---|---|---|---|
| `view_outline` | READ | document | doc_id, max_items?, text_truncate? | OutlineView |
| `view_text` | READ | document | doc_id, max_items?, text_truncate? | TextView |
| `view_annotated` | READ | paragraph | doc_id, max_items?, expand_runs? | AnnotatedView |
| `view_stats` | READ | document | doc_id | StatsView |
| `view_issues` | QUALITY | document | doc_id, severity? | IssuesView |
| `view_element` | READ | element | doc_id, ref | ElementView |

`severity?` 参数 `@ParamCapability(type=ENUM, enumValues={"error","warning"})`。

## get_document_overview 迁移

```java
// SessionTools.getDocumentOverview 改为：
public String getDocumentOverview(@ToolParam(name="doc_id")... String docId) {
  Document doc = document(docId);
  if (doc == null) return renderDocNotFound(docId);
  long generation = generations.getOrDefault(docId, 1L);
  // 委托 view 服务（SessionTools 需持有 DocumentViewService 引用）
  StatsView stats = viewService.stats(doc, docId, generation);
  String message = "文档概览\n段落数: " + stats.paragraphCount() + ...;
  return ToolResultRenderer.render(ToolResult.ok(stats, message));
}
```

**向后兼容**：工具名 `get_document_overview` 不变；data 从 `Map<String,Integer>` 升级为 `StatsView`
（超集，旧 4 个 int 仍在）。`@ToolCapability`/`@ParamCapability` 标注保持。

**注入**：`SessionTools` 需持有 `DocumentViewService`。但 `DocumentViewService` 依赖 `QualityCheckTools`，
而 `QualityCheckTools` 在 `DocxToolkit` 构造里晚于 `SessionTools` 创建 → 循环依赖。

**解决**：`DocxToolkit` 构造顺序调整——先建 `SessionTools` + `QualityCheckTools`，再建
`DocumentViewService`，最后把 `DocumentViewService` 注入回 `SessionTools`（setter 或延迟字段）。
或：`SessionTools.getDocumentOverview` 直接持有 `DocxToolkit` 级别的 `viewService` 引用，
在 `DocxToolkit` 构造末尾 `session.bindViewService(viewService)`。

**决策**：`DocxToolkit` 构造末尾 `session.bindViewService(this.viewService)`。
`SessionTools` 增加 `private DocumentViewService viewService` + 包级 `bindViewService()`。
`getDocumentOverview` 在 `viewService` 非 null 时委托，null 时走旧逻辑（测试/独立使用兼容）。

## 集成点

### DocxToolkit

```java
public final class DocxToolkit {
  // 现有 8 字段...
  public final ViewTools view;  // 新增

  public DocxToolkit() {
    // 现有构造...
    this.qualityCheck = new QualityCheckTools(...);
    // 新增：view 工具（注入 qualityCheck 引用）
    this.view = new ViewTools(
        session.sharedSessions(), session.sharedSeq(),
        session.sharedReferences(), session.sharedGenerations(),
        qualityCheck);
    // 新增：让 session 的 get_document_overview 委托 view 服务
    session.bindViewService(new DocumentViewService(session.sharedReferences(), qualityCheck));
    this.capability = new CapabilityTools(
        session, body, table, headerFooterToc,
        trackedChangeQuery, trackedChangeAuthoring, qualityCheck,
        view);  // 新增 view 参数（7→8 个文档工具）
  }

  public ToolRegistry scanAll(ToolRegistry registry) {
    return registry
        .scan(session)
        .scan(body)
        ...
        .scan(qualityCheck)
        .scan(view)        // 新增
        .scan(capability);
  }
}
```

### CapabilityTools 构造

从 `(session, body, table, headerFooterToc, trackedChangeQuery, trackedChangeAuthoring, qualityCheck)`
改为增加 `view` 参数。`CapabilityTools` 内部把这 8 个工具实例传给 `CapabilityCollector.collect(...)`，
manifest 自动纳入 6 个新 `view_*` 工具。

### CapabilityTools 测试覆盖

新增 6 个 `view_*` 工具需在 `CapabilityContractTest` 的测试覆盖检查中通过。
`TEST_COVERAGE_ALLOWLIST` 不应豁免它们——必须在切片 4 配套测试。

## 数据流与复用

- **复用 P0-01**：`ElementResolver`/`ReferenceContext`/`ElementRef.parse()`；ref canonical 一致。
- **复用 P0-02**：`ToolResult<T>` + `ToolResultRenderer` + `ToolResultCode`；双段 String 输出。
- **复用 P0-03**：`@ToolCapability`/`@ParamCapability` 标注；`CapabilityCollector` 自动纳入。
- **复用 SnapshotBuilder**：`outline`/`text`/`stats` 调 `build()` 一次，不建第二套遍历。
- **复用 QualityCheckTools**：`issues` 委托 `runAllChecks()`，不重写检查逻辑。

## 兼容性与迁移

- `ViewTools` 纯新增工具类，不破坏现有 8 个工具。
- `get_document_overview` 工具名不变，data 升级为超集。
- `QualityCheckTools.CheckResult` 从包级提升为 `public`，`runAllChecks` 从 private 提升为 `public`。
  `check_quality` 工具行为不变。
- `DocxToolkit` 构造增加 `ViewTools` + `session.bindViewService()`。
- `CapabilityTools` 构造参数从 7 增至 8 个文档工具。

## 权衡

- **为什么 outline 第一版扁平不嵌套**：嵌套标题树需要维护父子关系和层级栈，增加复杂度。
  Agent 用 bodyIndex 扁平序列 + headingLevel 已可定位；嵌套树留后续按需迭代。
- **为什么 annotated 默认 expandRuns=false**：大型文档逐段解析 run 成本高。默认只给段落级文本，
  Agent 定位到目标段落后再 `expand_runs=true` 或用 `view_element` 取 run 明细。
- **为什么 element 用 Map<String,Object> 而非多态 DTO**：不同元素类型属性集差异大，
  多态 DTO 会让 JSON schema 复杂。Map + kind 字段让 Agent 按类型解释，且 Jackson 直接序列化。
  第一版只支持 paragraph/table/run，其余返回空 properties（不阻塞，可扩展）。
- **为什么 imageCount 走 raw() 而非补 core API**：core 的 `Image` 访问路径需设计；
  P0-04 聚焦视图服务，不在 core 开新 API。`r.raw().getEmbeddedPictures()` 是 POI 逃生舱，
  try-catch 兜底。正式 Image API 留后续图片相关任务（P1-05/P2-03）。
- **为什么不直接在 SnapshotBuilder 补 imageCount**：snapshot 第一版行为已稳定（imageCount=0 有意），
  改它会影响 `DocumentSnapshot.equals` 和所有 snapshot 测试。view_stats 独立计数，隔离变更。
