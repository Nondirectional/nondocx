# POI Bridge

> The single most important convention in nondocx: how `api/` types wrap `org.apache.poi.xwpf.*`.

---

## Overview

nondocx is, at its core, a bridge over Apache POI's `XWPF*` API. Get the bridge wrong and you get
either a leaky abstraction (users forced into POI types) or a buggy cache (stale reads). This
spec is the **binding contract** for every wrapper type. It is referenced from
`directory-structure.md`, `error-handling.md`, and `quality-guidelines.md`, and is what
`trellis-check` audits most aggressively.

---

## Rule 1 — Holding wrapper, not cache

Every public domain type **holds a single `final XWPF* delegate`** and delegates every operation
to it directly. There is **no field-level cache** of read values.

```java
public final class Paragraph {
    private final XWPFParagraph delegate;   // final, set in constructor

    public String text() {
        return delegate.getText();          // read-through, every call
    }
    public Paragraph alignment(Alignment a) {
        delegate.setAlignment(Mappers.toPoi(a));   // write-through, every call
        return this;
    }
}
```

- Reads and writes are **live**: the moment you mutate the wrapper, the underlying `XWPFDocument`
  changes, and vice versa.
- Do NOT store a snapshot of text/style in a field "for convenience". A field becomes stale the
  instant another wrapper (or `raw()`) touches the same delegate.

**Why this over cache+sync or value-copy**: see `design.md` §11 (cache → consistency bugs;
value-copy → breaks live-object semantics). The holding-wrapper is what makes round-trip
mutability predictable.

---

## Rule 2 — Wrapper construction is cheap and idempotent

Wrapping an `XWPF*` into the domain type must be **allocation-cheap and stateless** — it just
stores the reference. This lets collections return **live views** that wrap-on-`get`:

```java
public List<Paragraph> paragraphs() {
    return new AbstractList<>() {
        private final List<XWPFParagraph> backing = delegate.getParagraphs();
        @Override public Paragraph get(int i) { return new Paragraph(backing.get(i)); }
        @Override public int size()           { return backing.size(); }
    };
}
```

Consequences:
- Two `Paragraph` objects wrapping the same `XWPFParagraph` are **content-equal** (Rule 4) but
  not reference-equal. That is expected and correct.
- Do not attach identity to wrapper instances. Do not put wrappers in `IdentityHashMap`.

---

## Rule 3 — The `raw()` escape hatch is a hard contract

Every public domain type (`Document`, `Paragraph`, `Run`, `Table`, `Row`, `Cell`, `Section`,
`Image`, `Header`, `Footer`) MUST expose:

```java
/**
 * Returns the underlying POI object.
 * <p>
 * Modifications to the returned object affect the document immediately. Use with caution.
 *
 * @return the backing {@code XWPF*} instance (same instance for the lifetime of this wrapper)
 */
public XWPF<Thing> raw() {
    return delegate;
}
```

Hard rules for `raw()`:
- Returns the **same** delegate instance for the wrapper's lifetime (no copy, no rebuild).
- It is the **only** place a POI type may appear in a public signature.
- POI exceptions thrown on the `raw()` path (i.e. by code the user writes against the returned
  object) are **NOT wrapped**. They propagate as-is. That is POI's territory, and the user opted
  in by calling `raw()`. (Contrast with Rule 4: normal public methods DO wrap.)
- `raw()` never returns null for a constructed wrapper — the delegate is final and non-null.

This hatch is how nondocx covers out-of-scope features (fields, OLE, math, watermarks, shapes —
see PRD "Out of Scope") without pretending to support them. It is also the de-facto answer to "POI
can do X but we don't wrap it yet". Tracked changes is a partial example: the read side,
text-class accept/reject/authoring, move accept/reject (paired), run-property (rPrChange)
read/accept/reject, and cell-structure (cellIns/cellDel read+accept/reject, cellMerge read-only)
are wrapped (`Document.trackedChanges()` / `Paragraph` / `Run`, see N12 / N13 / N14 / N15 / N16),
while pPrChange/sectPrChange/tblPrChange/trPrChange (CT types absent from the lite schema) and
accept/reject/authoring of cellMerge still fall back to `raw()` until later sub-tasks land.

---

## Rule 4 — Public surface wraps POI exceptions; `raw()` path does not

| Call site | POI exception handling |
|---|---|
| Normal public method (`api/` + `builder/`) | **Catch and wrap** into `Docx*Exception` |
| `raw()` return value used by caller | **Do not wrap** — propagate POI exception as-is |

See `error-handling.md` for the wrap mapping (IO → `DocxIOException`, format → `DocxFormatException`,
etc.). The asymmetry is intentional: inside the wrapper, we own the abstraction; on the `raw()`
path, the user opted into POI and gets POI's behavior verbatim.

---

## Rule 5 — Enum mapping lives in `internal`, not on value objects

Mapping between POI enums (`ParagraphAlignment`, `STXXX`, …) and our value objects
(`Alignment`, `HeadingLevel`, …) lives in `internal/poi/` or `internal/style/` — never as
static methods on the public value object. The public `Alignment` enum must not import POI.

```java
// internal/poi/Mappers.java
static ParagraphAlignment toPoi(Alignment a) { ... }
static Alignment fromPoi(ParagraphAlignment p) { ... }

// api/style/Alignment.java — POI-free
public enum Alignment { LEFT, CENTER, RIGHT, JUSTIFY }
```

This keeps `api/` genuinely POI-free at the source level, not just at the signature level.

---

## Rule 6 — Content equality excludes the delegate

`equals`/`hashCode` on wrapper types compare **content derived from the delegate**, never the
delegate reference. See `quality-guidelines.md` Rule 2. Two `Paragraph` wrappers over the same
`XWPFParagraph` are equal; two wrappers over structurally identical-but-distinct `XWPFParagraph`s
are also equal (this is what makes round-trip assertions work).

---

## Rule 7 — No reflective magic; delegate access is the only "trick"

- Do not use reflection to reach into `XWPF*` internals. If POI's public API can't express it,
  either (a) add it to `internal/poi` as a documented workaround, or (b) leave it to `raw()`.
- Do not subclass `XWPF*` to inject behavior. Wrap; do not extend.
- The CT XmlBeans types (`CTP`, `CTRow`, …) are reachable via `raw().getCT[…]()` when truly
  needed; that access stays on the `raw()` path and is not re-exposed on the wrapper.

---


## Implementation Notes — POI behavior gotchas

> These are non-obvious Apache POI behaviors the nondocx wrappers had to adapt to during MVP
> implementation (task `06-16-nondocx-core-mvp`). Future maintainers and agents MUST read these
> before touching the bridge — they are not obvious from POI's own Javadoc.

### N1 — Wrapper constructors accept a POI type (internal seam)
Each wrapper's constructor takes its backing `XWPF*` (or `CTSectPr`) by design — e.g.
`Document(XWPFDocument)`, `Paragraph(XWPFParagraph)`, `Section(XWPFDocument, CTSectPr)`. This is the
ONLY public signature (besides `raw()`) that mentions a POI/XmlBeans type. It is required because
`Docx` (base package), `Document` (api), `Paragraph` (api.text), `Table` (api.table) live in
different packages, so cross-package construction needs a public ctor. Each ctor Javadoc documents
this as the "internal seam". This is consistent with Rule 2's `new Paragraph(backing.get(i))`
example and is an accepted exception to the "zero POI in signatures" rule.

### N2 — POI pre-populates created tables/rows/cells (strip them)
`XWPFDocument.createTable()`, `XWPFTable.createRow()` and `XWPFTableRow.createCell()` inject
default children (a default row, mirror-grid cells, an empty paragraph). To keep nondocx's
`addX = exactly one X` semantics, the creation paths (`Document.addTable`, `Table.addRow`,
`Row.addCell`) **strip the pre-populated children** via POI's own `removeRow`/`removeCell`/
`removeParagraph` before returning. Do NOT skip the stripping or content equality and round-trip
assertions break.

**toolkit 层下游影响**: 此剥离在 toolkit 层产生连锁约束——`add_table_row` / `add_table_cell`
返回的是**真空结构**（新行 0 单元格、新单元格 0 段落），无法直接被 `replace_table_cell_run_text`
等 run 文本工具填充（会报段落索引越界）。详见 `orchestration-layer.md` 的「add_table_row /
add_table_cell 返回空结构」Gotcha。

### N3 — `XWPFRun.addPicture` width/height are EMU, not pixels
POI 5.2.5 stores the `width`/`height` args of `addPicture(...)` **verbatim as EMU** on
`<wp:extent>` (no pixel→EMU conversion). So `Paragraph.addImage(bytes, type, wPx, hPx)` converts
pixels→EMU via `internal/poi/Pictures.emuFromPixels` (× `Units.EMU_PER_PIXEL` = 9525) before
calling POI, and `Image.width()/height()` convert EMU→pixels back. This is why image round-trip is
exact to the pixel.

### N4 — Clear list membership with `unsetNumPr`, never `setNumID(null)`
`XWPFParagraph.setNumID(null)` leaves an **empty** `<w:numId val=""/>`, which XmlBeans rejects as
an invalid integer on the next save/open (`XmlValueOutOfRange`). `internal/poi/Numbering.clear()`
uses `paragraph.getCTP().getPPr().unsetNumPr()` to remove the whole `<w:numPr/>` element instead.

### N5 — Header/footer are read/write split: `header()`/`footer()` read-only, `ensureHeader()`/`ensureFooter()` create
POI itself splits "read" (`getDefaultHeader()`/`getDefaultFooter()`, return null when absent) from
"write" (`createHeader()`/`createFooter()`, build+attach a new part). nondocx mirrors that split:

- `Section.header()`/`footer()` are **read-only** — they return `null` when no part exists and never
  mutate the document. `Section.equals`/`hashCode` use them directly (null→empty list).
- `Section.ensureHeader()`/`ensureFooter()` are the **create** path — they create+attach a default
  part if absent (create-once) and are what write-side callers use to get an appendable header/footer.

This replaced an earlier "create-on-access" design where `header()`/`footer()` themselves created the
part on first call. That merged read and write into one getter: convenient for writing, but it meant
read-only traversals (search, iteration, `equals`) silently created empty header/footer parts and
polluted the document — `Section.equals` had to avoid `header()` via a private read-only resolver.
The split makes the read path safe by construction, so that workaround is gone.

### N6 — Inline images live INSIDE a run; a picture-bearing run is surfaced as `Image`
OOXML embeds an image as a drawing inside a run, not as a sibling of runs. nondocx models it as an
`InlineElement`: in `Paragraph.inlineElements()`, a run that carries an embedded picture is
wrapped as an `Image` (not a `Run`); `XWPFHyperlinkRun` is checked first (it subclasses `XWPFRun`).
`Paragraph.addImage` creates a pure-image run. A run carrying BOTH text and an image is an MVP
edge case (text not separately surfaced; reachable via `raw()`).

### N7 — Content equality compares PARSED values, not raw XML (why round-trip is clean)
All `equals`/`hashCode` compare values parsed through the public getters (e.g. `listKind()`/
`listLevel()`, not the raw `numId`; `isBold()`/`font()`/`color()`, not the raw `rPr` XML). So
write-side normalization POI may inject (empty `rPr`/`pPr`, re-allocated numbering ids, default
attributes) is **invisible to equality**, and `RoundTripTest` passes without any field exclusion.
Keep it this way: never compare raw XmlBeans fragments in `equals`.

### N8 — Section-scoped header/footer creation materializes missing page setup for compatibility
`<w:headerReference>` / `<w:footerReference>` and page properties (`<w:pgSz>`, `<w:pgMar>`) all live under the same `<w:sectPr>`. POI can legally create header/footer parts and references without emitting explicit page settings, and Word/POI round-trip that structure fine. But WPS is less forgiving when a section has header/footer references yet no explicit page geometry.

**Rule**: on the **first** `Section.ensureHeader()` / `Section.ensureFooter()` creation path (i.e. when a part actually has to be created), if the section still lacks page settings, nondocx materializes a compatibility default: `PaperSize.A4` + 1-inch margins (`1440` twips on all four sides). This fill is **missing-only** — never overwrite user-specified `pgSz` / `pgMar`. Because the read-only `header()`/`footer()` never create parts (see N5), simply reading an existing or absent header/footer can never trigger this fill. Examples may still set page size/margins explicitly for teaching clarity.

### N9 — `XWPFRun.setText(String)` APPENDS a `<w:t>` when the run already has text

OOXML allows multiple `<w:t>` children inside one `<w:r>`. POI's `XWPFRun.setText(String text)` delegates to `setText(text, sizeOfTArray())`. When `pos == size` (i.e. the run already carries one or more `<w:t>`), the overload **appends** a new `<w:t>` carrying `text` rather than replacing the existing one. Net effect: calling `setText` on a run that already has text silently **concatenates** (old + new), which violates every caller's "replace" intuition.

**Rule**: any nondocx setter that replaces run-class text (`Run.text(String)`, `Hyperlink.text(String)`, and any future analog) MUST clear every existing `<w:t>` on the underlying `CTR` first, then call `setText`. Pattern (copy-paste between the two call sites):

```java
CTR ctr = delegate.getCTR();
int tCount = ctr.sizeOfTArray();
for (int i = tCount - 1; i >= 0; i--) {
  ctr.removeT(i);
}
delegate.setText(text); // now sizeOfTArray()==0 → creates a single fresh <w:t>
```

Iterate **backwards** (`tCount-1 → 0`) so indices stay valid as you remove. Both call sites carry a Javadoc note + `@see poi-bridge.md N9`, and each is guarded by a round-trip regression test (`RunTest#textReplacementSurvivesRoundTrip`, `HyperlinkTest` setter round-trips). This is a load-bearing gotcha — an Agent-driven "replace run text" tool surfaced it immediately when the output doc came back as "旧文本新文本".

### N10 — Hyperlink URL rewrite: rebuild the relationship, don't trust the open-time cache; use `getRelation()` not `toString()`

POI has **no** API to mutate a hyperlink's target URL. The only path is to rebuild the OPC relationship the hyperlink's `r:id` points at:

1. Read the old rId via `XWPFHyperlinkRun.getHyperlinkId()`.
2. `document.getPackagePart().removeRelationship(oldRid)`.
3. `part.addExternalRelationship(url, XWPFRelation.HYPERLINK.getRelation())` — let OpenXML4J **auto-allocate** the new rId; do **not** assume the old rId is reusable.
4. `delegate.setHyperlinkId(newRel.getId())`.

**Two POI traps here**:

- **Relationship type URI**: the second arg to `addExternalRelationship` must be `XWPFRelation.HYPERLINK.getRelation()`, **not** `.toString()`. `toString()` returns a debug string, which produces an illegal relationship type and makes the URL unreadable after reopen (`Hyperlink.url()` returns `null`). `getRelation()` returns the correct namespace URI.
- **Open-time cache**: `XWPFDocument.hyperlinks` is a `List` populated once on open; `getHyperlinkByID` reads from this cache, so after a relationship rebuild the **same instance** may still report the old URL via `url()`. The contract that holds is `save → reopen → url()` reads the new value (the on-disk relationship is correct). For `Docx.create()` documents the cache is never pre-populated, so in-memory reads are unaffected.

**Rule**: nondocx's `Hyperlink.url(String)` implements the rebuild above, wraps OpenXML4J/XmlBeans failures as `DocxIOException` (cause preserved), and its Javadoc spells out the cache caveat. Round-trip tests assert only after `save → reopen`. Any future code that mutates OPC relationships for hyperlinks (or any rId-bearing run) must follow the same pattern and must not rely on the in-memory cache reflecting the change immediately.

### N11 — TOC 有两种 OOXML 形态(域 / SDT),POI 无高级 API;TocEntry 不可变值,诚实偏离 Rule 1

Word 目录(Table of Contents)在 OOXML 里**不是一个独立元素**,而是藏在正文段落里的内容。它有**两种形态**,较新 Word 默认用第二种,nondocx 两种都解析:

**形态一 —— 大域(field),较早的 Word**:正文里一个跨多段的域,由 `fldChar`(begin/separate/end)与以 `TOC ` 开头的 `instrText` 界定;begin 与 end 之间的段落是条目。

```
<w:r><w:fldChar w:fldCharType="begin" w:dirty="true"/></w:r>
<w:r><w:instrText> TOC ... </w:instrText></w:r>     ← 指令文本以 "TOC " 开头
<w:r><w:fldChar w:fldCharType="separate"/></w:r>
…缓存可见文本:每个条目是一个 <w:p>,pStyle=TOC1..TOC9,
  内容常包在 <w:hyperlink w:anchor="_Toc..."> 内:标题 + <w:tab/> + 页码…
<w:r><w:fldChar w:fldCharType="end"/></w:r>
```

**形态二 —— SDT 内容控件,较新的 Word**:整个 TOC 被包进一个 `<w:sdt>/<w:sdtContent>`,其内每个条目是一个段落(`pStyle=TOC1..TOC9`,内容在 CTP 级 `<w:hyperlink w:anchor=...>` 内),且**首个条目段落本身承载 TOC 域的 begin**,收尾段承载 end;条目的页码是一个**嵌套的 `PAGEREF` 子域**的可见结果(不是普通文本)。

**POI 的两个坑(都得绕过)**:① POI 没有任何 `XWPFToc` 高级 API,域字符当普通 `XWPFRun` 吐出,条目可见内容在 CTP 级 `<w:hyperlink>` 内、`XWPFParagraph.getRuns()` 不暴露。② `XWPFDocument.getParagraphs()` **不返回 SDT 内的段落**——形态二对它完全不可见。

**Rule(解析策略)**: nondocx 把脏活收进 `internal/poi/TocFields`(`findToc` + `collectParagraphs` + `parseEntries`),对外只暴露 `api/toc/TableOfContents` 与 `api/toc/TocEntry` 两个 POI-free 类型。三步:
1. **穿透 SDT 收集段落**:从 `CTBody` 出发,用 `XmlCursor` 按文档顺序遍历直接子,遇到 `<w:p>` 直接收、遇到 `<w:sdt>` 下钻到 `<w:sdtContent>` 收其内 `<w:p>`,汇成统一序列(每段 `new XWPFParagraph(CTP, doc)` 重包,使两种形态走同一条解析路径)。仅穿透一层(嵌套 SDT-in-SDT 罕见,属已知限制)。
2. **定位 TOC 区间**:在统一序列里,靠「段内含 `instrText` 以 `TOC ` 开头的 begin 域」定位首段(`PAGEREF` 子域不误判——指令以 `PAGEREF` 开头),再用域深度计数器配对到 end,界定区间。
3. **识别条目**:区间内<b>凡有 `TOC1..TOC9` 样式(退而 `<w:outlineLvl>`+1)且有可见文本的段落</b>都解析为条目——不依赖 separate/end 边界,故同时兼容形态一(条目夹在 begin/end 间)与形态二(begin 与首条目同段、每条目自带 PAGEREF 子域)。层级取 `TOC1..TOC9` 样式尾数字;页码 = 条目最后一个非空 `<w:t>`(形态二即 PAGEREF 可见结果);锚点 = 包裹超链接的 `w:anchor`。单条目解析失败时跳过而非抛异常(防御式)。`dirty` 走 `xgetDirty()` 取字符串值(跨精简/full schema 稳定)。

**对 Rule 1 的诚实偏离(已记录)**: Rule 1 要求每个 `api/` 类型持有单个 `final XWPF*` 委托、读写穿透、`TocEntry` 也应是 holding-wrapper。但 TOC **没有专属 POI 委托类型**(域横跨多段落),条目也没有干净的 per-entry POI 句柄,且条目本质是 Word 渲染分页后的**缓存快照**(改它等于篡改缓存,下次刷新被覆盖)。因此:
- `TableOfContents` 持有 `XWPFDocument` 作为委托(像 `Section` 持 doc+sectPr 那样),`raw()` 返回该文档;`entries()` 每次调用**当场重算**、不缓存,守住「无字段快照」精神。
- `TocEntry` 是**不可变解析值**(标题/层级/页码/锚点四字段,构造时一次解析),不是 holding-wrapper。

这是对抽象诚实的选择:把没有干净 POI 句柄的东西硬包成活对象会得到一个会撒谎的抽象。该偏差已在 `TableOfContents` / `TocEntry` 的 Javadoc 与本条注明。

**范围(只读)**: 创建/刷新目录需 Word 的分页引擎计算页码,POI 无此能力,属 `raw()` / `UnsupportedFeatureException` 范畴。v1 只取首个 TOC。回归:`TableOfContentsTest`(含形态一 `parsesTocEntries`、形态二 `parsesTocInsideSdtBlock`、`tocSurvivesRoundTrip`);真实文档验证:1072.docx(SDT 形态,20 条目全解析)。

---

### N12 — Tracked changes:开关元素名与 POI 方法名不一致;四类文本/移动修订同型,POI 无高级 API

Word 修订(tracked changes)在 OOXML 里**没有单一根元素**,而是散落在 `word/document.xml` 正文各处的标记元素,加上 `word/settings.xml` 里的一个开关。nondocx 的只读消费侧(`Document.trackedChanges()` → `TrackedChanges`)把脏活收进 `internal/poi/TrackedChangeNodes`,对外只暴露 `api/track/*` 的 POI-free 类型。

**OOXML 结构**:
- **开关**:`word/settings.xml` 的 `<w:trackChanges/>`。该元素**存在即开启**,没有值属性;缺失即未开启。
- **修订标记**(散落在 `word/document.xml` 的 body 树各处):文本类用 `<w:ins>`(插入)/ `<w:del>`(删除);移动类用 `<w:moveFrom>` / `<w:moveTo>`。移动还配一个 `<w:moveTo>` / `<w:moveFrom>` 之间的 `author` 配对关系,但 v1 只按节点本身枚举、不重建配对。

**POI 的两个坑(都得绕过)**:
1. **开关元素名与 POI 方法名不一致**:`<w:trackChanges/>` 这个开关,POI **不**叫 `isTrackChanges()`,而是暴露为 `XWPFSettings.isTrackRevisions()`(元素名 `trackChanges` ↔ 方法名 `trackRevisions`)。读开关要走 `doc.getSettings()` → `isTrackRevisions()`,凭方法名猜元素名会踩空。
2. **四类文本/移动修订同型,且 POI 无遍历 API**:POI **没有** `XWPFTrackedChanges` 这类高级 API,也没有枚举修订的现成方法。在精简 schema 下,`ins`/`del`/`moveFrom`/`moveTo` 统一由 `CTRunTrackChange` 承载——它继承 `CTTrackChange`(给 `author`/`date`)与 `CTMarkup`(给 `w:id`)。一个特别点:**删除/移动源用 `<w:delText>` 而非普通 `<w:t>`**(被删的文本才用 `delText`),取文本时不能一律读 `<w:t>`。

**Rule(解析策略)**: nondocx 把脏活收进 `internal/poi/TrackedChangeNodes`,对外只暴露 `api/track/*`(门面 `TrackedChanges`、holding-wrapper `TrackedChange`、Type/Family/Location/Segment/Details 等)。两件脏活:
1. **开关**:`isEnabled` 读 `XWPFSettings.isTrackRevisions()`(即上面的方法名不一致坑)。
2. **枚举**:`collect` 用 `XmlCursor` 从 `CTBody` 出发,按 **body → table → row → cell → paragraph** 深度优先、文档顺序遍历,命中 `ins`/`del`/`moveFrom`/`moveTo` 即产出一条 `TrackedChange`(持对应 `CTRunTrackChange`);删除/移动源类的文本走 `<w:delText>`。高级类型(`*PrChange` / `cellIns` 等)目前**跳过**,留给 `advanced-types` 子任务。

**与 Rule 1 的关系(无偏离)**: 与 TOC(N11)不同,tracked changes 的文本类修订**有干净的 per-revision CT 节点**(`CTRunTrackChange`),因此 `TrackedChange` 走标准 holding-wrapper(持单个 `final CTRunTrackChange` 委托),不偏离 Rule 1。稳定 id 仅承诺**进程内**稳定(同一委托、同一文档顺序、同一 id 生成规则),**不**承诺 `save()` 后重新 `Docx.open()` 仍稳定——因为修订没有跨会话的天然稳定句柄。

**范围(只读)**: 本子任务只覆盖**只读消费**——`enabled()`(开关状态)、`list()`(按文档顺序枚举)、`get(id)`(按稳定 id 命中,miss 抛 `NoSuchElementException`)。accept/reject 已由 `accept-text` 子任务补齐(见 N13);**仍不**含:开关写入、显式创作(authoring)、高级修订类型。回归:`TrackedChangesTest`;全量 tests green、spotless clean;手工往返验证带 `<w:ins>` 的 docx save→reopen 修订正确重现。

---

### N13 — Tracked changes accept/reject:POI 无高层 API,靠 XmlCursor 手术;del 的 reject 要 delText→t 类型转换

只读消费侧(N12)读出 `ins`/`del` 后,**应用(accept)**与**撤销(reject)**是破坏性写:要改 `document.xml` 的修订标记树。nondocx 把这两件脏活也收进 `internal/poi/TrackedChangeNodes`(`acceptText` / `rejectText`),对外暴露 `TrackedChanges` 门面上的 6 个写方法。

**OOXML 语义(accept/reject 各 4 种)**:
- **accept `ins`**:插入生效——拆 `<w:ins>` 包装,内部 `<w:r>/<w:t>` 提升为正文。
- **reject `ins`**:插入被撤销——整个 `<w:ins>` 子树删除。
- **accept `del`**:删除生效——整个 `<w:del>` 子树删除。
- **reject `del`**:删除被撤销——拆包装,且内部 `<w:delText>` **转回**普通 `<w:t>`,原文回到正文。

**POI 的坑(都得绕过)**:
1. **没有 accept/reject 高层 API**:POI 不提供"应用某条修订"。一切靠 `XmlCursor` 手术——两把刀:`cursor.moveXml(anchor)`(把 cursor 指向的节点移到 `anchor` **之前**,并让 cursor 自动指向下一个兄弟,故 `toNextSibling()` 不重复)、`cursor.removeXml()`(删当前节点)。
2. **`del` 的 reject 需要元素类型转换,不是单纯 move**:`delText` 与 `t` 是**不同 OOXML 元素**(本地名 `delText` vs `t`),`moveXml` 只搬不改名。所以 reject `del` 必须先读出 `delText` 文本 → `removeDelText` → `addNewT` 重写,再搬 run、删包装。
3. **破坏性写会让此前 collect 到的节点句柄失效**:accept/reject 改写文档树后,同一批 `collect` 返回的其它 `CTRunTrackChange` 的 cursor 可能失效(曾触发 `XmlValueDisconnectedException`)。因此门面的 `all`/`byAuthor` 粒度用**「重算 → 应用第一条匹配 → 重算」**循环,而不是一次性收集后批量改。

**Rule(写策略)**: 门面 `TrackedChanges` 提供 `acceptAll/rejectAll/acceptByAuthor/rejectByAuthor/accept(id)/reject(id)`。**scope 守 `family == TEXT`**:只对 `ins`/`del` 生效;底层 `acceptText`/`rejectText` 虽同型可处理 `moveTo`/`moveFrom`,但门面 gate 住——`accept(id)` 命中 `MOVE`/属性类/cell 类时抛 `UnsupportedFeatureException`(阶段性边界,留 `advanced-types`)。异常契约:null/空白 → `IllegalArgumentException`(用 nondocx 的 `Objects.requireNonNull`,抛 IAE 而非 NPE);id miss → `NoSuchElementException`。

**与 Rule 1 的关系(无偏离)**: accept/reject 操作 `TrackedChange.raw()` 拿到的 `CTRunTrackChange` 委托,仍是标准 holding-wrapper 写穿透;不引入新的写侧模型或 id 体系——accept/reject 复用 read 子任务的进程内稳定 id。

**范围(文本类写)**: 本子任务覆盖 `ins`/`del` 的 accept/reject(all/byAuthor/单条 id 三粒度)。**不**含:`move`/属性类/`cellIns`/`cellDel` 的写语义、开关写入、显式创作(authoring)。显式创作已由 `authoring` 子任务补齐(见 N14);`move`/属性类/cell 类的写语义仍留给 `advanced-types`。回归:`TrackedChangesTest` 新增 11 用例(accept/reject × ins/del × all/byAuthor/id + 表格内 + 边界);全量 tests green、spotless clean。

---

### N14 — Tracked changes authoring:POI 不暴露 ins/del 内 run,需从 CTR 重构;迁 run 入 del 靠 toEndToken+moveXml

创作侧(authoring)主动写出文本类 tracked 修订:`Paragraph.addInsertion` / `Paragraph.addDeletion` / `Run.replaceTracked`。节点创建下沉到 `internal/poi/TrackedChangeNodes`(`addInsertion` / `addDeletion` / `nextRevisionId`),对外只暴露 `Paragraph`/`Run` 上的 POI-free 方法。

**OOXML 语义**:
- **insertion**:在段落末尾新建 `<w:ins>`,内含一个带 `<w:t>` 的 `<w:r>`。
- **deletion**:在段落新建 `<w:del>`,把目标 run 的 `<w:t>` 转 `<w:delText>`,再把该 `<w:r>` 迁入 `<w:del>` 内部。
- **replacement**:不是独立元素,是 del + ins 的组合;新 ins run 复制源 run 的六个内联样式属性。

**POI 的坑(都得绕过)**:
1. **`XWPFParagraph.getRuns()` 不暴露 `ins`/`del` 内的 run**:POI 只把段落直接子级的 `<w:r>` 包成 `XWPFRun`;位于 `<w:ins>`/`<w:del>` 内部的 run 对它不可见。因此 `addInsertion` 返回的 run 不能从 `getRuns()` 取——必须从创建的 `CTRunTrackChange.getRList()` 拿到 `CTR`,再用 `new XWPFRun(ctr, paragraph)` 重构(该构造函数接受 `IRunBody`,即段落),否则返回的 `Run` 无法链式操作。
2. **迁既有 run 入 `<w:del>` 不是 addNewR**:目标 run 已存在于段落直接子位置,不能 `addNewR`(会新建)。正确做法:新建空 `<w:del>` → 把目标 `CTR` 的 `t` 转 `delText` → 用 `XmlCursor` 把 `CTR` `moveXml` 进 `<w:del>` 内部。迁入的关键是先把 del cursor `toEndToken()`(指向其内部末尾),再 `moveXml`(节点移到该位置),否则会落到 del 之外。
3. **`w:id` 自动分配,但与 nondocx 稳定 id 是两套概念**:authoring 用 `nextRevisionId` 扫描文档已有修订 `w:id` 取 `max+1`。这是**底层 OOXML 修订 id**,写入文档;它与 read 子任务对外暴露的 nondocx 稳定 id(`type:location:w:id` 混合串)**不是**同一概念——前者是元数据,后者是进程内引用标识。两者切勿混淆。

**Rule(写策略)**: 三类入口方法住在内容所属类型(`Paragraph`/`Run`)上,**不**住 `TrackedChanges` 门面(与 accept/reject 不同——创作属于「在某处写内容」,门面是「对文档修订状态负责」)。`addDeletion` 不返回原 `Run`(迁入 deletion 语义路径后原 run 已非稳定普通 live wrapper,继续暴露会误导),返回 `this` 段落;`replaceTracked` 返回新插入 run。authoring 与 `<w:trackChanges/>` 开关**正交**(不依赖开关也能写)。

**与 Rule 1 的关系(无偏离)**: 创作操作的是 POI 的 `XWPFParagraph`/`XWPFRun` 委托,标准写穿透;不引入新模型。写出的修订经 `collect` 自然读回,`addInsertion`/`addDeletion` 写出的 `w:id` 会进入稳定 id 的混合串。

**范围(文本类创作)**: 本子任务覆盖 `ins`/`del` 的显式创作(insertion/deletion/replacement)。**不**含:`move`/属性类/`cellIns`/`cellDel` 的创作、开关写入、"自动追踪所有既有写操作"。move 的 accept/reject(配对联动)与属性类(rPrChange)的读写已由 `advanced-types` 子任务补齐(见 N15);其余高级类型的创作仍走 `raw()`。回归:`TrackedAuthoringTest` 10 用例(读回 / 元数据 / 正交 / 普通 API 无污染 / 与 accept 集成 / 边界);全量 tests green、spotless clean。

---

### N15 — Tracked changes 高级类型:move 配对靠 author+text 启发式;property 走 CTTrackChange 双委托 + 专用写

advanced-types 子任务补齐两类高级修订:move(moveFrom/moveTo 的 accept/reject 配对联动)与 property(rPrChange 的读 + accept/reject)。cell(cellIns/cellDel)因结构风险最高、需独立工程,回 planning 拆为新子任务。研究依据见 `tasks/.../advanced-types/research/ooxml-forms.md`(一次性探针捕获真实 OOXML 形态,确认后删除)。

**move — 同型 + 配对联动**:
- **结构同文本类**:探针确认 `moveFrom`/`moveTo` 与 `ins`/`del` 完全同型(都 `CTRunTrackChangeImpl`);accept/reject 底层 mechanics 复用 `acceptText`/`rejectText`(moveTo 同 ins、moveFrom 同 del)。**门面 gate 从 `family==TEXT` 放宽到含 `MOVE`**(改 2 处)。
- **配对无显式指针**:`CTRunTrackChange` 无 counterpart 字段。配对靠 **author + text** 启发式(同一作者、相同文本的另一端);**date 不作硬约束**(Word 批量同毫秒、但其它工具跨秒)。单条命中任一端时查配对端、两端同时操作;配对端缺失抛 `NoSuchElementException`(不静默降级)。已知边界:同作者同文本多次移动会歧义,取文档顺序第一个。
- **moveFrom 用 delText、moveTo 用 t**(与 del/ins 一致)——测试 fixture 必须据此构造,否则 moveText 不匹配。

**property — CT 类型不同,走双委托 + 专用写(方案 C)**:
- **CT 类型分裂**:探针 + lite schema 检查确认:`rPrChange`/`pPrChange` 的类型(`CTRPrChange`/`CTPPrChange`)与 `CTRunTrackChange` **不同**,共同父是 `CTTrackChange`(都继承它,提供 author/date/id)。**没有 `CTPrChange` 这个共同中间类**(lite gen 未保留)。
- **`TrackedChange` 改双委托**:持 `CTRunTrackChange runDelegate`(文本/移动类)与 `CTTrackChange propertyDelegate`(属性类)二选一。`raw()` 仅对文本/移动类返回 runDelegate,**对属性类抛 `UnsupportedFeatureException`**;新增包内 `propertyNode()` 取属性节点。属性构造函数与 `propertyNode()` 经 `public`/包内接缝暴露给 `internal/poi`。
- **读**(read walker 扩展):`walkParagraph` 下钻段落直属 run 的 `<w:rPr>`,枚举 `rPrChange` 为 `RPR_CHANGE` + `PropertyChangeDetails`(kind=RUN_PROPERTIES、newSummary/oldSummary=新旧 rPr 直接子本地名摘要)。**pPrChange 暂不覆盖**——其 CT 类型 `CTPPrChange` 在 POI 精简 schema 下尚未被引用、不在 classpath;留作已知边界。
- **写**(门面专用方法,方案 C):`acceptProperty(id)`/`rejectProperty(id)`(不通用 `accept(id)`)。**accept**:删 `*PrChange` 标记(保留外层新 rPr)。**reject**:清空外层 rPr 现有直接子(除 rPrChange)→ 把旧 rPr(`CTRPrOriginal`,与 `CTRPr` 不同类型,故走 XmlCursor 通用搬运)的直接子搬入外层 → 删 `*PrChange`(整树替换)。

**与 Rule 1 的关系(无偏离)**: property 的读出节点仍是 holding-wrapper(委托 `CTTrackChange`);accept/reject 经 `propertyNode()` 写穿透。`raw()` 对属性类抛异常是**显式契约**(方案 C),不是静默降级——调用方被引导到专用写方法。

**范围**: 覆盖 move(accept/reject 配对联动)与 property(rPrChange 读 + accept/reject)。cell(cellIns/cellDel/cellMerge)已由后续子任务补齐(见 N16)。**仍不**含:pPrChange/`sectPrChange`/`tblPrChange`/`trPrChange`(其 CT 类型在 POI 精简 schema 下全部缺失,见 N16 的 dangling reference 说明)、move/property 的显式创作。回归:`TrackedAdvancedTypesTest` 8 用例(move 配对读回/accept/孤立异常 + property 读回/accept/reject/类型边界/raw 抛)。

### N16 — Tracked changes 单元格结构类:精简 schema 的 dangling reference;cellIns/cellDel 走 typed 访问器,cellMerge 双重阻塞走纯 XmlCursor

cell 子任务补齐表格单元格结构修订:`cellIns`/`cellDel`(完整 read + accept/reject)与 `cellMerge`(只读)。研究依据见 `tasks/.../cell-types/research/cell-forms.md`(一次性探针确认真实 XML 与 accept/reject 手术结果,确认后删除)。

**精简 schema 的 dangling reference 模式(本子任务最重要的知识点)**:
- POI 精简 jar(poi-ooxml-lite)只保留 POI 自身运行时调用到的 CT 类。一个 CT 接口可以**声明**返回某类型,但该类型的 class 文件与 XmlBeans 的 `.xsb` schema 资源**都不在** jar 内——叫 dangling reference。
- 实测(lite 5.2.5,`javap` + 编译期 + 运行期三层验证):
  - `CTTcPr.getCellIns()`/`getCellDel()` → `CTTrackChange`:**可达**(typed 访问器可用)。
  - `CTTcPr.getCellMerge()` → `CTCellMergeTrackChange`:**编译期不可达**(`tcPr.getCellMerge()` 这行 javac 直接拒绝,报「无法访问 CTCellMergeTrackChange」)。
  - `CTTcPr.getTcPrChange()` → `CTTcPrChange`:同 cellMerge,dangling。
  - `CTPPrChange`/`CTSectPrChange`/`CTTblPrChange`/`CTTrPrChange`:**全缺**,pPrChange 等更高层属性类全部受阻。
- **判断某 CT 类型是否可达,必须 `unzip -l`/`javap` 实测,不能只看接口声明**。dangling 的类型连「只读」都做不到——`cur.getObject()` 一旦要把它当类型化对象,就查 `.xsb` schema 资源,查不到运行期抛 `SchemaTypeLoaderException`。

**cellIns / cellDel — 复用 property 类委托**:
- 节点类型是 `CTTrackChange`(与 property 类 `CTRPrChange` 的共同父,都继承它给 author/date,id 来自 `CTMarkup`),经 `CTTcPr.getCellIns()`/`getCellDel()` 取得。**直接复用 `TrackedChange` 已有的「持 `CTTrackChange` 委托」构造函数与 `propertyNode()` 包内接缝,零新 CT 类型**。
- `raw()` 对 cell 类抛 `UnsupportedFeatureException`(同 property 的方案 C);accept/reject 经门面专用方法 `acceptCell`/`rejectCell` 走。
- **read**:`walkCell` 进入 cell 后先下钻 `tcPr`,枚举 `cellIns`/`cellDel` 为 `CELL_INS`/`CELL_DEL` + `CellChangeDetails`(kind=CELL_INSERTION/CELL_DELETION)。**location path 不含 `paragraph` segment**:cell 修订挂在 `tcPr`,比单元格内段落高一层,path 停在 `[BODY, TABLE, ROW, CELL]`(与单元格内文本类修订的 `[..., CELL, PARAGRAPH]` 区分)。

**cell accept/reject — 作用于整个 `<w:tc>` 祖父节点(与文本类/属性类本质不同)**:
- `cellIns`/`cellDel` 标记的是「**单元格本身**的存亡」(表格结构修订),不是单元格内的文本或属性。故 accept/reject 操作**整个 `<w:tc>` 元素**,不是标记本身——误当文本类处理会写出「本应删除却仍存在」的单元格(advanced-types research 点名的最高风险点)。
- 语义:accept cellIns=保留 tc/删标记;reject cellIns=移除整个 tc;cellDel 对称(accept 移除 tc、reject 保留 tc/删标记)。
- 实现:从 `cellIns`/`cellDel` 节点开 cursor,`toParent()`×2 到祖父 `tc`(探针确认本地名为 `tc`),再按语义 `removeXml()` 整个 tc 或仅删标记。防御:祖父本地名不是 `tc` 时抛 `DocxOperationException`,不静默删错层级。
- **`acceptAll`/`rejectAll`/`acceptByAuthor`/`rejectByAuthor` 的 family gate(`TEXT || MOVE`)不放宽到 CELL**——cell 结构修订不应被批量 accept/reject 误伤(结构删除批量执行风险高),这是有意的范围控制。

**cellMerge — 双重阻塞,纯 XmlCursor 只读**:
- `CTCellMergeTrackChange` 既无 Java 类(编译期不可达)也无 `.xsb` schema 资源(运行期不可反序列化)。**不能**走 typed 访问器,**也不能**对节点调 `cur.getObject()`(触发 schema 查找即 `SchemaTypeLoaderException`)。
- read:用 XmlCursor 在 `tcPr` 子里按本地名 `cellMerge` 命中,**直接读 `w:id`/`w:author` 属性文本**(`getAttributeText`,不触发类型化),产出一条经「无委托构造」的纯值 `TrackedChange`(`CellChangeDetails` kind=UNCONFIRMED_MERGE)。`TrackedChange` 为此新增第三构造函数:接收已解析的 author/date 字符串、不持委托;其 `raw()`/`acceptCell`/`rejectCell` 都抛 `UnsupportedFeatureException`。
- accept/reject **不支持**,门面对 cellMerge 命中抛 `UnsupportedFeatureException`(合并/拆分涉及相邻单元格 vMerge 恢复,结构风险高;且根本无 CT 类型可操作)。

**与 Rule 1 的关系(无偏离)**: cellIns/cellDel 的读出节点仍是 holding-wrapper(委托 `CTTrackChange`);accept/reject 经 `propertyNode()` 写穿透。cellMerge 是诚实的「只读、不持委托」——`raw()` 与 accept/reject 都明确抛异常,不静默降级。

**范围**: 覆盖 cellIns/cellDel(读 + accept/reject)与 cellMerge(只读)。**不**含:pPrChange/sectPrChange/tblPrChange/trPrChange(CT 类型全缺)、cellMerge 的 accept/reject、cell 类的显式创作。回归:`TrackedCellTypesTest` 12 用例(cellIns/cellDel/cellMerge 读回 + accept/reject × cellIns/cellDel + cellMerge accept/reject 抛异常 + 类型边界 + acceptAll 不误伤 + raw 抛 + 与单元格内文本类共存);全量 179 tests green、spotless clean。

### N17 — Tracked changes 高级类型**创作侧**:rPrChange 的 CTRPrOriginal 架构防递归;move 靠 rangeStart 的 w:name 配对;nextRevisionId 必须用 wId() 不能用 raw()

advanced-types/cell 补齐了高级修订类型的**读 + accept/reject** 后,本条补齐**创作侧**:四类(带格式插入、rPrChange、cellIns/cellDel、move)的显式创作 API。研究依据见 `tasks/.../authoring-advanced/research/authoring-forms.md`(一次性探针确认结构与闭环,删除探针)。

**带格式插入(零改动)**:`Paragraph.addInsertion(author, text)` 已返回 `Run`,链式 `newRun.bold().color(...)` 设样式即可。`<w:ins>` 是包装元素,内 run 的 `<w:rPr>` 独立——样式后置无 OOXML 语义问题。

**rPrChange 创作 —— CTRPrOriginal 架构层防递归(重要发现)**:`Run.commitStyleAsTracked(author, RunStyle previousStyle)` 两步式(先链式改样式、再传「改前快照」提交)。结构:
```xml
<w:rPr>                                    <!-- 新值(当前 rPr) -->
  <w:b/>
  <w:rPrChange w:id="1" w:author="甲">
    <w:rPr><w:vanish/></w:rPr>             <!-- 旧值树(previousStyle 渲染) -->
  </w:rPrChange>
</w:rPr>
```
- `CTRPr.addNewRPrChange()` 建容器;`change.addNewRPr()` 返回 **`CTRPrOriginal`**(不是 `CTRPr`)。
- **`CTRPrOriginal` 的 schema 天然不含 `rPrChange` 子元素**——旧值树不可能递归嵌套 rPrChange,**无需手动剔除防递归**。这是 design 期最大的不确定性的明确答案:架构已经防住。
- 旧值树由 `RunStyle`(六样式:b/i/u/rFonts/sz/color)渲染进 `CTRPrOriginal`。注意 size 是磅值,OOXML `sz` 是半磅(`value * 2`);underline 用 `STUnderline.SINGLE`。

**cellIns/cellDel 创作(最简)**:`Cell.markInserted(author)` / `Cell.markDeleted(author)`。结构是 N16 读侧的反向:`CTTcPr.addNewCellIns()`/`addNewCellDel()` 建裸属性节点(设 id/author/date),随后必然能被既有 read/accept-reject 处理(同结构)。cellMerge 不提供创作方法(CT 类型缺失,诚实排除)。

**move 创作 —— 靠 rangeStart 的 w:name 配对,不是靠 moveFrom/moveTo 的 id**:`Paragraph.moveRunsFrom(author, sourceParagraph, runs)`(接受方是目标段,与 `addInsertion` 同类型)。结构(四件配对):
```xml
<!-- 源段 -->
<w:moveFromRangeStart w:id="10" w:name="_move_5"/>
<w:moveFrom w:id="2" w:author="甲"><w:r><w:delText>...</w:delText></w:r></w:moveFrom>
<w:moveFromRangeEnd w:id="3"/>
<!-- 目标段 -->
<w:moveToRangeStart w:id="20" w:name="_move_5"/>      <!-- name 与源端相同 -->
<w:moveTo w:id="5" w:author="甲"><w:r><w:t>...</w:t></w:r></w:moveTo>
<w:moveToRangeEnd w:id="21"/>
```
- **`w:name` 只在 rangeStart 上,两端必须相同**——配对靠 name,不靠 moveFrom/moveTo 的 id。实现用 `_move_<baseId>`(baseId 来自 nextRevisionId,文档内唯一)防冲突。
- 源端文本用 `delText`、目标端用 `t`(同 del/ins 规则,N12)。
- 一次 move 需 6 个独立 `w:id`(rangeStart/End ×2 + moveFrom/moveTo)。
- **moveXml 后源 CTR 句柄 XmlValueDisconnected**——目标端 run 的文本必须在 moveXml **之前**预捕获,不能移动后再读源 run 的 delText。

**nextRevisionId 必须用 wId(),不能用 raw()(重要修复)**:创作需分配 `w:id` 时扫已有最大 id。原实现 `c.raw().getId()` 对属性/单元格类会抛 `UnsupportedFeatureException`(raw 仅文本/移动类)。新增 `TrackedChange.wId()`(public)在两个委托槽(`runDelegate`/`propertyDelegate`)取非空那个读 `CTMarkup.getId()`——所有 family 都继承 `CTMarkup`,共享 `getId()`。`nextRevisionId` 改用 `c.wId()`。**这是 N15 落地时被掩盖的 bug,高级类型创作首次暴露**。

**accept/reject 后的 POI 缓存失效(测试/使用须知)**:accept/reject 重构树后,POI 的内存 `XWPFParagraph`/`XWPFRun` 包装器会 `XmlValueDisconnected`。验证 accept 后的结构,必须 save→reopen 重新读,不能信任 accept 前的内存 wrapper。

**与 Rule 1 的关系(无偏离)**:四类创作方法的公共表面 POI-free(住在 `Paragraph`/`Run`/`Cell`);CT 脏活在 `internal/poi/TrackedChangeNodes`。author 必传,date/w:id 自动分配。与 `<w:trackChanges/>` 开关正交。

**范围**: 覆盖四类创作(带格式插入/rPrChange/cellIns/cellDel/move)。**不**含:cellMerge 创作、pPrChange 等创作(CT 类型缺)、全局修订录制(显式 tracked 路线排除)。回归:`TrackedAuthoringAdvancedTest` 10 用例(带格式插入 round-trip + rPrChange 创作双向 + cellIns/cellDel 创作 accept/reject + move 配对读回 + move accept 联动 + 边界);全量 189 tests green、spotless clean。

### N18 — Comments 只读:部件顺序≠正文顺序;XmlCursor 递归遍历兄弟必须 push/pop

comments-read 子任务交付批注只读消费(`Comments.list()`/`get(id)` + `Comment` 五字段)。POI 5.2.5 对 comments 有完整高级 API(`XWPFDocument.getDocComments()` → `XWPFComments`、`XWPFComment` 实现 `IBody`、`CTComment extends CTTrackChange` 同构),故 `Comment` 走标准 holding-wrapper 持 `XWPFComment`(不偏离 Rule 1)。两个非显然发现:

**1. `getComments()` 返回 comments.xml 部件顺序(创建顺序),≠ 正文顺序。** 批注的正文存 `word/comments.xml`,锚点(`commentRangeStart`/`commentReference`/`commentRangeEnd`,共享同一 `w:id`)散在 `word/document.xml`。POI 的 `getComments()` 按 comments.xml 里的元素顺序返回,与正文里 `commentRangeStart` 的出现顺序无关——探针验证:三批注按 id 2,0,1 创建、body 按 0,1,2 锚定时,`getComments()` 返回 `[2,0,1]`。因此 `Comments.list()` 的「文档顺序」契约必须由 `CommentNodes.collect` 自己实现:先建 `Map<id, XWPFComment>`,再用 XmlCursor 扫 `CTBody` 按 `commentRangeStart` 出现顺序取,孤儿批注(comments.xml 有、document.xml 无锚点)降级按部件顺序追加末尾不丢弃。**不能直接委托 `getComments()`**。

**2. XmlCursor 递归遍历兄弟必须 push/pop(只读场景的关键坑,与 N13 的写场景不同)。** `commentRangeStart` 是叶子 markup 元素,可能出现在任意层级(段落内、表格单元格内段落里)。深度优先遍历整棵 body 子树时,`toFirstChild()` 进入子层、递归返回后 cursor 停在子树深处,外层 `toNextSibling()` 从错误位置继续,**漏掉同层后续兄弟**——探针实测:不加 push/pop 时三批注只能命中第一个(id=0),id=1/2 全丢。正确写法是递归下钻前 `cur.push()`、返回后 `cur.pop()` 恢复到当前节点,保证 `toNextSibling()` 始终在正确兄弟层:
```java
private static void collectRangeStartIds(XmlCursor cur, ...) {
  if (!cur.toFirstChild()) return;
  do {
    if ("commentRangeStart".equals(localNameOf(cur))) { ... 产出 ... }
    cur.push();                      // 下钻前保存
    collectRangeStartIds(cur, ...);  // 递归(toFirstChild 会移动 cursor)
    cur.pop();                       // 恢复,让 toNextSibling 在正确兄弟层
  } while (cur.toNextSibling());
}
```
**与 `TrackedChangeNodes` 的 walk 对比**:后者命中修订节点(`ins`/`del` 等包装容器)后**不下钻**(内容由专门方法处理),故不踩此坑;comments 的 `commentRangeStart` 是叶子,遍历必须继续走过兄弟,递归不可避免,push/pop 是必需的。**凡是要用 XmlCursor 递归遍历并继续处理兄弟的场景,都要 push/pop。**

**3. `readWAttribute` 的 null vs "" 口径**:`TrackedChangeNodes.readWAttribute`(1158 行)缺失返回 `""`,因修订属性基本不缺;`CommentNodes.readWAttribute` 缺失返回 `null`,因批注锚点的 `w:id` 缺失意味「无锚点」需与「空串」区分。两者各自正确,但 **w 命名空间 URI 的字面量 `"{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"` 现已在两处内联重复**(已知 code smell,后续可提取共享常量;当前两处都正确且有测试覆盖,不在本子任务 scope 内动 TrackedChangeNodes)。

**边界行为(探针确认)**:`getDocComments()` 无批注时返 `null`(需 null-guard 返空列表);`getCommentByID(miss)` 返 `null`(包装成 `NoSuchElementException`);`XWPFComment.getDate()` 可空;`XWPFComment.getText()` 多段拼接正确(委托,不稳则回退自拼)。`Comment.id()` 直接透传 `w:id`(OOXML 语义里本就跨会话稳定,不像 tracked-changes 要造混合 id)。

**范围**: 只读 `list()`/`get(id)` + 五字段。**不**含:创作(已由 authoring 子任务交付,见 N22)、回复/线程、resolve 状态(`commentsExtended.xml`,子任务 3)、锚点位置解析。回归:`CommentsTest` 17 用例(无批注空列表 + 单条五字段 + date/initials 缺失 + **body 顺序≠部件顺序定向测试** + 孤儿降级 + 多段拼接 + get 命中/miss/null 参数 + 活视图 + **表格内批注** + 内容相等 + raw 同一性);全量 209 tests green。

### N19 — Header/footer 变体:POI 的 createHeader(FIRST/EVEN) 不自动写开关;POI 无统一 getter 只有三个分别方法

首页/偶数页页眉页脚变体扩展(`06-23-hf-variants-variants` 子任务)。POI 的 `XWPFHeaderFooterPolicy` 提供了 `DEFAULT`/`FIRST`/`EVEN` 三个 `STHdrFtr.Enum` 常量与对称的 `createHeader(variant)` / `getFirstPageHeader` / `getEvenPageHeader` 等 API,变体扩展的 POI 侧能力齐全,但有两个坑:

**坑 1 — createHeader(FIRST/EVEN) 不自动写生效开关**:
- **FIRST 变体**需要 per-section 的 `<w:sectPr>/<w:titlePg/>` 才会实际渲染首页不同。POI 的 `createHeader(FIRST)` **只建 part + reference,不写 `titlePg`**。
- **EVEN 变体**需要文档级的 `word/settings.xml/<w:evenAndOddHeaders/>` 才会实际渲染奇偶页不同。POI 的 `createHeader(EVEN)` **同样不写这个开关**。
- 不补开关的后果:part 创建了、reference 写了,但 Word/WPS 打开后**不显示**首页/偶数页变体(引擎按默认奇数页渲染),属于「合法 OOXML 但功能无效」陷阱。

**坑 2 — POI 没有统一的 getHeader(STHdrFtr) 方法**:
- 读取变体只有三个分别方法:`getDefaultHeader()` / `getFirstPageHeader()` / `getEvenPageHeader()`(注意是 `FirstPage`/`EvenPage`,不是 `First`/`Even`)。
- 没有 `getHeader(STHdrFtr.Enum)` 这种按常量分派的统一入口,与 `createHeader(STHdrFtr.Enum)` 不对称。
- nondocx 在 `Section` 内用私有 `readHeader`/`readFooter` switch 分派方法补这个缺口。

**Rule(写策略)**: `Section.ensureHeader(variant)` / `ensureFooter(variant)` 在创建 part 前调用私有 `ensureVariantFlags(variant)`:
- `FIRST` → 若 `!delegate.isSetTitlePg()` 则 `delegate.addNewTitlePg()`(per-section)。
- `EVEN` → 若 `!settings.isSetEvenAndOddHeaders()` 则 `document.getSettings().getCTSettings().addNewEvenAndOddHeaders()`(文档级)。
- `DEFAULT` → 无开关。
幂等(`isSet` 守卫),只读路径 `header(variant)` **不**补开关(读写分离,N5)。

**不用 POI 的 `XWPFSettings.setEvenAndOddHeadings(boolean)`**:虽然存在,但方法名拼写是 `Headings`(带 s)且语义模糊(这个标志实际控制的是页眉页脚的奇偶页区分,不只是 headings);直接操纵 `CTSettings.addNewEvenAndOddHeaders()` 与 OOXML 元素名精确对应,避免依赖 POI 便捷方法名的歧义。与 `trackChanges` 开关的处理方式一致(那里 `setTrackRevisions` 名字对得上才用便捷方法)。

**与 Rule 1 的关系(无偏离)**: `Section` 持 `final XWPFDocument document` + `final CTSectPr delegate`(现有结构),CT 操纵(`addNewTitlePg`/`addNewEvenAndOddHeaders`)内联在 `Section` 私有方法 —— 与现有 `ensureCompatiblePageSetupForHeaderFooterCreation` 内联 `paperSize`/`margins` 同模式,不下沉 `internal/poi`。`STHdrFtr.Enum` 映射在 `Mappers.toPoi(HeaderFooterVariant)`(Rule 5)。

**范围**: 覆盖 DEFAULT/FIRST/EVEN 三变体的读 + 写(create-once)。**不**含:「链接到上一节」的跨节继承、变体的修订层面 accept/reject。回归:`HeaderFooterTest` 22 用例(含 9 个变体用例)+ `HeaderFooterIntegrationTest` 2 用例(三能力线协同);全量 289 tests green、spotless clean。

**WPS 兼容性**: `titlePg` 的首页抑制在 WPS 不可靠(见 `renderer-compatibility.md#title-page-suppress`),`HeaderFooterVariant.FIRST` 的 Javadoc 已引用该锚点。`evenAndOddHeaders` 在实现中未发现新跨引擎坑(探针 + round-trip 验证通过)。

### N20 — XWPFHeader/XWPFFooter 实现 IBody,Header/Footer 的表格与图片复用 Document 的下沉路径

页眉页脚内表格与图片便捷方法(`06-23-hf-variants-content` 子任务)。探针实测确认两个复用条件:

**1. `XWPFHeaderFooter` 实现 `IBody`**(与 `XWPFDocument` 同接口),故 `createTable` / `createParagraph` / `getTables` / `getBodyElements` 路径可直接复用。图片走 `XWPFRun.addPicture`,该方法通过 `IRunBody.getPart()` 解析图片 part 关系 —— `XWPFHeader` 作为 `IRunBody` 有效,part 关系在 header 上下文里正确建立,save→reopen 后图片字节 round-trip 精确。**故 `Header.addParagraph().addImage(...)` 天然可用,无需 `internal/poi/HeaderPictures`**(探针 `HeaderContentProbeTest` 验证后已删除)。

**2. `XWPFHeaderFooter.createTable(int rows, int cols)` 签名与 `XWPFDocument.createTable()` 不同**(重要):前者必须传行列数且预填 `rows×cols` 个单元格;后者无参、预填 1 行。`Header.addTable()` 用 `createTable(1, 1)` 创建后剥掉那一行(与 `Document.addTable` 的「剥掉 POI 预填」语义一致,N2 模式),得到真空表。

**诚实边界**: `Header.equals`/`Footer.equals` **不**纳入表格(只比段落)。页眉里同时有段落和表格是罕见场景;若需对含表格的页眉做 round-trip 断言,用户单独比较 `header.tables()`。与 TOC 的 SDT 形态不参与 equals(N11)同型取舍。

**与 Rule 1 的关系(无偏离)**: `Header`/`Footer` 持 `final XWPFHeader`/`XWPFFooter` delegate(现有结构),`addTable`/`tables` 标准写穿透 + 活跃视图(与 `Document.addTable`/`tables` 同型)。回归:`HeaderContentTest` 7 用例。

### N21 — 简单域(simple field)写侧:POI 无高层 API,三段 run 结构,读侧走 raw

页码与通用简单域 API(`06-23-hf-variants-field` 子任务)。POI 没有 `XWPFField`/`addSimpleField` 这类方法,域的三段结构需直接操纵 `CTR`(`addNewFldChar` + `addNewInstrText`)—— 与 `addPicture`/tracked-changes 的下沉路径同型。现有先例:`TocFields.java` 读域、`TableOfContentsTest.java:200-211` 写域,都是这个手法。`CTFldChar`/`STFldCharType`/`CTText`(instrText)在 lite schema 均可达。

**OOXML 域结构**: 一个简单域由三个相邻 run 组成 —— `<w:fldChar begin>` / `<w:instrText>指令</w:instrText>` / `<w:fldChar end>`。域指令住在 `<w:instrText>`(**不是**普通可见文本 `<w:t>`)。域的**可见结果**(如 PAGE 域显示的页码数字)由 Word/WPS 打开时的渲染引擎计算,POI 与 nondocx 都不计算 —— 故只写指令结构。简单域不带 `separate` 缓存段,打开时由渲染引擎即时填充(完整域带 separate + 缓存可见结果,本次不支持)。

**设计决策(入口在 Paragraph 而非 Run)**: `Paragraph.addSimpleField(instruction)` 产出标准 3-run(begin/instrText/end 各一个 run),返回承载 instrText 的中间 run(用户可对其链式设样式 —— 域可见结果的样式由此 run 决定)。入口不放 `Run` 的理由:Word 标准产出的简单域就是 3 个相邻 run(不是「单 run 三子元素」);创建新 inline 内容的入口是 `Paragraph`(同 `addHyperlink`/`addImage`);若放在 `Run` 上,要么违反 `Run` mutator 返回 `this` 的链式惯例,要么越权创建兄弟 run。便捷方法 `addPageNumberField()`/`addPageCountField()` 等价于 `addSimpleField("PAGE")`/`addSimpleField("NUMPAGES")`。

**诚实边界(读侧走 raw)**: 域的 3 个 run 在 `Paragraph.inlineElements()` 里以**3 个空文本 Run** 暴露(因为指令是 `<w:instrText>` 不是 `<w:t>`,`text()` 返空串)。识别/解析已有域(读侧)**不在**本次范围,走 `raw().getCTR().getInstrTextArray()`。不引入 `Field` 公共类型 —— 读侧建模(含 separate 缓存值、嵌套域、PAGEREF 子域)是独立大子任务(参考 TOC 的 N11 规模),本次专注写入入口。与 TOC 的「写不了、只读」边界对称,这里是「写得出来、读走 raw」。

**与 Rule 1 的关系(无偏离)**: `Paragraph.addSimpleField` 操作 `XWPFParagraph` 委托(标准写穿透,`createRun` + CTR 操纵),CT 操纵内联在 `Paragraph`(与 `Run.text()` 内联清空 `<w:t>` 的 N9 手法同型,不下沉 `internal/poi`)。回归:`SimpleFieldTest` 8 用例。

### N22 — Comments 创作:POI 的 addNew/insertNew 不按 schema 顺序,锚点必须 XmlCursor 定位;XWPFComment.getId() 返回 String

comments-authoring 子任务交付单条**整段**范围批注的显式创作(`Paragraph.addComment(author, text)` → `Comment`)。与 tracked-changes authoring(N14)的本质差异:tracked 的 `addInsertion` 是「新建 `<w:ins>` 容器包新 run」,新节点天然在段末、顺序正确;comments 的 `addComment` 是「往**已有内容的**段落里插锚点」,锚点必须落在已有 run 的**外侧**(start 在前、end+reference 在后)。POI 不自动排序,故必须 XmlCursor 手动定位——两个非显然发现:

**1. POI 的 `addNewCommentRangeStart`/`insertNewCommentRangeStart(int)` 都不按 OOXML schema 顺序,锚点落到段末(探针三方案对比验证)。** 给一个已有 2 个 run 的段落调 `addNewCommentRangeStart()`,得到的 `commentRangeStart` 落在所有 run **之后**、紧贴 `commentRangeEnd`,范围实际为空(包住 0 个 run):
```
addNew 前:  [r, r]
addNew 后:  [r, r, commentRangeStart, commentRangeEnd, r(引用)]   ← start 在段末,范围为空
```
`insertNewCommentRangeStart(int)` 也一样——其索引是 XmlBeans 内部 **per-type 数组索引**,不是 `CTP` 全局子位置索引;该数组此前为空时新元素仍被追加到 `CTP` 子序列末尾。**POI/XmlBeans 不做 schema-order 排序。** 正确做法是 `addNew` 后用 XmlCursor 把 `commentRangeStart` move 到 `CTP` 第一个子之前(探针方案 C):
```java
XmlCursor pCur = ctp.newCursor();
XmlCursor startCur = start.newCursor();
try {
  if (pCur.toFirstChild()) {      // 空段时返 false,跳过 move(start 已在首位)
    startCur.moveXml(pCur);       // 把 start 移到 pCur(原第一个子)之前
  }
} finally { pCur.dispose(); startCur.dispose(); }
```
`commentRangeEnd` + 引用 run 留在段末不动——`addNew` 的自然位置(段末)就是它们的正确语义位置。**只有 `commentRangeStart` 错位需 move。** 空段(`toFirstChild` 返 false)无需 move。凡是要往「已有内容的容器」里按 schema 顺序插元素的 POI/XmlBeans 场景,都要假设 `addNew`/`insertNew` 不排序,自己用 XmlCursor 定位。

**2. `XWPFComment.getId()` 返回 `String`,与 `createComment(BigInteger)` 入参类型不对称。** POI 5.2.5 的 `XWPFComments.createComment(BigInteger id)` 入参是 `BigInteger`,但读侧 `XWPFComment.getId()` 返回 `String`(而非 `BigInteger`)。分配批注 `w:id` 的 `CommentNodes.nextCommentId` 要把 `getId()` 的 String 解析回 long 取 max(非数字 id try/catch 跳过),再 `BigInteger.valueOf` 返回。与 tracked-changes 的 `nextRevisionId`(扫 `CTMarkup.getId()` 返 `BigInteger`)不同——**批注 id 与修订 id 是两套独立 OOXML id 计数器**,`nextCommentId` 不能复用 `nextRevisionId`。read 子任务 N18 末尾 `Comment.id()` 透传字符串口径与此一致。

**3. initials 设空串(不派生)。** `XWPFComment.setInitials` 接受任意字符串,POI/Word 不约束 initials 与 author 的关系。创作时设空串——派生规则(取首字母等)是产品偏好,无 OOXML 约束,空 initials 不影响 Word 显示(read 子任务 N18 已验证 initials 可缺失)。若人工验收发现 Word 显示需要 initials 再回退补派生。`rStyle=CommentReference` 字符样式同理**不建**——Word 批注气泡显示由批注窗格逻辑处理,不依赖该样式。

**与 Rule 1 的关系(无偏离)**: `Paragraph.addComment` 操作 `XWPFParagraph` 委托(标准写穿透),XmlCursor 定位脏活下沉到 `internal/poi/CommentNodes.addWholeParagraphComment`(与 N14 `addInsertion` 下沉到 `TrackedChangeNodes` 同型)。返回新建的 `Comment`(holding-wrapper 持新建 `XWPFComment`,N18 已建该形态),调用方可立即读 id/author/text。回归:`CommentsAuthoringTest` 7 用例(创作读回 + round-trip 结构 + 参数校验 + id 自增 + 空段边界 + 不污染 runs 视图);全量 313 tests green。

**范围**: 单条**整段**范围批注创作。**不**含:run 级批注(`Run.addComment`,留 v2)、回复/线程(`commentsExtended`,已由 reply-threads 子任务交付,见 N23)、people.xml/RSID(子任务 4)、删除批注、跨段范围。toolkit/example 扩展留 `comments-docs-spec` 子任务(对称 tracked-changes-authoring)。

### N23 — Comments 回复+线程:POI 零支持的三个 part 用 OPC 自维护;MemoryPackagePart.getOutputStream() 累加语义要 clear()

comments-reply-threads 子任务交付批注回复(`Comments.reply(parentId, author, text)` → `Comment`)与线程建模(`Comment.parentId()`/`paraId()`)。POI 5.2.5 对 `commentsExtended.xml`/`commentsIds.xml`/`commentsExtensible.xml` 三个 part **无 Java 类、无 API**(父任务 prd 已确认),nondocx 首次建立「自维护 OOXML part」模式。三个非显然发现:

**1. OPC createPart 自动注册 Content_Types,addRelationship 手动加关系(探针验证)。** POI 的 OPC 层完整支持自维护 part:
```java
PackagePartName name = PackagingURIHelper.createPartName("/word/commentsExtended.xml");
PackagePart part = pkg.createPart(name, contentType);   // [Content_Types].xml 的 Override 自动注册!
try (OutputStream os = part.getOutputStream()) { os.write(xml.getBytes(UTF_8)); }
document.getPackagePart().addRelationship(name, TargetMode.INTERNAL, relType);  // relationship 手动加
```
`createPart` **自动**在 `[Content_Types].xml` 注册 Override——这是简化关键,不用手写 Content_Types。relationship 要手动 `addRelationship`(document.xml part → 新 part)。**幂等坑**:重复 `createPart(同名)` 抛 `PartAlreadyExistsException`,必须先 `getPart(name)` 检查(存在读-改-写、不存在 create)。

**2. MemoryPackagePart.getOutputStream() 累加语义——多次写入要先 clear()(实现期踩到的关键坑)。** 对同一 part 多次 `getOutputStream()` 写入,内容**累加**而非覆盖——实测三次 writeDom 后 part 里有**三段独立 XML 文档**拼接(`<?xml?><commentsEx>..</commentsEx><?xml?><commentsEx>..</commentsEx>...`),是非法 XML,readDom 解析失败(`[Fatal Error] 不允许有匹配 "[xX][mM][lL]" 的处理指令目标`)。根因:`MemoryPackagePart.getOutputStreamImpl()` 关闭时把本次写入字节**追加**到 part 现有 buffer,而非替换。修复:writeDom 前 `((MemoryPackagePart) part).clear()`:
```java
if (part instanceof MemoryPackagePart) {
  ((MemoryPackagePart) part).clear();
}
try (OutputStream os = part.getOutputStream()) { ... 写完整 DOM ... }
```
**通用教训**:凡是对 POI OPC part 多次写入(读-改-写循环),都要先 clear 再写。`MemoryPackagePart.clear()` 是 public(基类 `PackagePart` 无,需 instanceof——POI 运行时实现都是 MemoryPackagePart)。

**3. 线程关系的读侧解析是两步 join(paraId 是中间 key)。** 线程关系唯一线索是 `commentsExtended.xml` 的 `w15:paraIdParent`,但它指向父批注的 **paraId** 而非 comment id。要得到「回复批注的 parentId(父 comment id)」需两步 join:
- comments.xml:批注内首段的 `w14:paraId` → 批注 `w:id`(paraId→commentId)
- commentsExtended:`paraId` → `paraIdParent`(本 paraId → 父 paraId)
- 再 join:父 paraId → 父 comment id

`CommentNodes.ThreadResolver` 内部类在 `collect` 入口建三张映射(paraId→parentParaId、paraId→commentId、commentId→paraId),产出时 join 注入 `Comment(c, paraId, parentId)`。**防御式**:无 commentsExtended/paraId 缺失/join 失败时,parentId 为 null(根批注语义),不抛——畸形/旧文档不破坏读侧。

**paraId/durableId 生成**:8 位大写 hex 随机,范围 `[1, 0x7FFFFFFE]`(OOXML 约束必须 < 0x7FFFFFFF,对照 docx skill `_generate_hex_id`)。authoring 产出的批注无 paraId(`XWPFComment.createParagraph` 不写),reply 时若父批注无 paraId 要补一个(否则 paraIdParent 链断)。dateUtc 用 ISO-8601 UTC(`2026-07-07T12:34:56Z`),注入 commentsExtensible 的 `w16du:dateUtc`。

**回复的正文锚点位置**(对照 docx skill `reply_to_comment`):在父批注 `commentRangeStart` 后插新 `commentRangeStart`;在父批注引用 run(含 `commentReference`)后插新 `commentRangeEnd` + 引用 run。用 XmlCursor 定位父锚点(N22 的定位脏活同型,但基准是「父批注锚点」而非「段首」)。回复范围紧贴父范围、几乎重合。

**Comment holding-wrapper 扩展**:POI 委托(`XWPFComment`)不提供 paraId/parentId,故新增 `Comment(delegate, paraId, parentId)` 构造,既有单参构造保留(read 兼容,paraId/parentId=null)。**paraId/parentId 不纳入 equals/hashCode**——保 read 子任务的 round-trip 五字段相等性契约。`parentId()` 返回 `Optional<String>`(根批注 empty),`paraId()` 返回可空 String(与 `date()` 同型)。

**与 Rule 1 的关系(无偏离)**: 四 part 自维护脏活全收进 `internal/poi/CommentExtendedParts`(新建)+ `CommentNodes.replyToComment`,对外 `Comments.reply`/`Comment.parentId`/`paraId` POI-free。回归:`CommentsReplyThreadsTest` 8 用例(reply 读回 + round-trip 线程 + 四 part 幂等 + 多级链 + 参数校验 + 兼容性 + 结构断言);全量 321 tests green。

**范围**: 回复 + 线程(commentsExtended 四 part 全做)。**不**含:people.xml/RSID(子任务 4)、resolve/done 状态 API、删除回复、跨段批注回复。toolkit/example 扩展留 `comments-docs-spec` 子任务。

### N24 — Comments 基础设施:people.xml 复用 N23 OPC 模式;RSID settings.xml 走 XmlCursor(CTDocRsids dangling);paraId 收敛;beginElement cursor 语义

comments-infrastructure 子任务补齐批注的**现代 Word 兼容元数据**:people.xml(author 注册,@mention 提示)、w14:paraId(收敛 reply-threads 散落实现 + 补到 addComment)、RSID(Document 级单例,Word 合并修订对齐)。三项都是「锦上添花」——缺了批注仍能用(N18/N22/N23 已保证基本可用),但 Word 审阅面板体验打折。三个非显然点:

**1. people.xml 完全复用 N23 的 OPC part 自维护模式(零新机制)。** people.xml 是 `w15` 命名空间的 part(`<w15:people><w15:person w15:author=..><w15:presenceInfo .../></w15:person></w15:people>`),POI 无 Java 类。处理方式与 commentsExtended(N23)**完全同型**:`createPart` 自动注册 [Content_Types].xml Override(people+xml)+ `addRelationship` 手动加关系 + DOM 读-改-写。实现把 N23 的 `CommentExtendedParts` 的 OPC/DOM 工具(`ensurePart`/`readOrCreateDom`/`writeDom`/`readDom`/`AttrBuilder`)从 `private` 提升 **package-private**,供同包的 `AuthoringInfra`(新建,三项基础设施统一入口)复用——避免重复造 OPC 轮子。**幂等**:author 精确字符串匹配去重(不 normalize,prd Q3),`getElementsByTagNameNS` 扫现有 person。**presenceInfo 用占位 `providerId="None"`**(docx skill 同款,真实身份服务集成是 Out of Scope)。content type/relationship type:`application/vnd.openxmlformats-officedocument.wordprocessingml.people+xml` / `.../relationships/people`。

**2. RSID settings.xml 走 XmlCursor——CTDocRsids 是 N16 同型的 dangling reference。** RSID 要写两处:① settings.xml 的 `<w:rsids>` 段(`<w:rsidRoot w:val=../>` + `<w:rsid w:val=../>`);② 节点级(`<w:p w:rsidR=.. w:rsidRDefault=../>`、`<w:r w:rsidR=../>`)。POI 的 `CTSettings.getRsids()`/`addNewRsids()` 声明返回 `CTDocRsids`,但 lite jar 缺该 class 文件(与 N16 的 `CTCellMergeTrackChange` 同型),typed 访问器运行期抛 `ClassNotFoundException`。故 `AuthoringInfra` 用 XmlCursor 操作 `CTSettings` 原始 XML。
- **Document 级单例(design §5)**:RSID 持久化在 settings.xml 的 `<w:rsidRoot>`——`documentRsid(doc)` 首次调用生成并注册,后续读回。故 `save→reopen` 后仍是同一个 RSID,同一文档多次创作的节点标同一个 RSID(Word「同一编辑会话」语义);不同文档概率上不同。这避免了 `Document` API 层持 RSID 字段(RSID 状态留在 settings.xml,真正的「文档级」)。
- **`<w:rsids>` 的 schema 位置**:rsids 应在 compat 之后,但 Word 宽容,追加到 settings 末尾也接受。本实现不严格排 schema 顺序。

**3. XmlCursor 的 `beginElement` 停在 END,不是 START(实现期踩的关键坑)。** 往 settings.xml 建嵌套结构(`<w:rsids>` 内含 `<w:rsidRoot>` + `<w:rsid>`)时,`beginElement(QName)` 的语义是「在当前位置之前插入新元素,cursor 移到**新元素的 END**」(实测确认,非直觉的 START)。因此建嵌套子的正确导航是:cursor 在容器 END → `beginElement(child)` 插子(cursor 在 child END)→ 设属性 → **`toNextToken`** 从 child END 移到容器 END → `beginElement(nextChild)` 插第二个子。**误用 `toParent`/`toEndToken` 会插错层级**(`toParent` 从元素 END 回到自身 START 而非父元素,导致子嵌进子)。`insertElement`(在当前之前插、cursor 不动)与 `beginElement`(插并移到 END)语义不同,选用要看是否需要继续操作新节点。凡是要用 XmlCursor 建嵌套结构的场景,都要记住 `beginElement` 后 cursor 在 END。

**paraId 收敛(无新机制)。** reply-threads 子任务已把 `setParagraphParaId`(CommentNodes 私有)用在 reply 路径;本子任务把它提升到 `AuthoringInfra.setParaId`(public),addComment 路径也调用,CommentNodes 删掉私有副本。paraId 不查重(prd Q2:8 位 hex 空间大,冲突可忽略)。paraId/RSID 是**属性**非子元素,故既有 `CommentsAuthoringTest` 的子元素顺序断言(`commentRangeStart, r, commentRangeEnd, r`)不受影响——实现期验证证实。

**与 Rule 1 的关系(无偏离)**: 三项基础设施脏活全收进 `internal/poi/AuthoringInfra`(新建)+ `CommentNodes.stampAuthoringInfrastructure`(私有 helper),公共 API 无感——用户调 `addComment`/`reply` 不变。`AuthoringInfra` 的方法 public 但在 `internal/poi`(内部包,POI-free 表面在 `api/`)。**与 tracked-changes 的隔离(AC6)**:`TrackedChangeNodes` **不**接入 `AuthoringInfra`——基础设施仅作用于 comments 创作路径(父任务 Q4,避免改动已稳定的 track 包)。

**范围**: 覆盖 people.xml(注册 + 幂等)、paraId(收敛 + 补 addComment)、RSID(Document 级单例 + settings.xml + 节点级)。**不**含:w16du:dateUtc(reply-threads 已做)、presenceInfo 真实 providerId/userId、回溯补到 tracked-changes 创作路径(AC6)。回归:`CommentsInfrastructureTest` 11 用例(people.xml 存在/幂等/round-trip + paraId addComment/reply + RSID 节点级/settings.xml rsids/文档级单例/round-trip 持久化/不同文档不同 + tracked-changes 隔离 AC6);全量 332 tests green、spotless clean。

---

## Out-of-Scope feature policy

When you encounter a docx feature that is NOT in the deep-wrap scope:

1. **Do not** silently degrade (e.g. return empty list, drop the element).
2. **Do not** half-wrap it with a TODO.
3. Either expose `raw()` (always allowed) and document the feature as raw-only in Javadoc, or
   throw `UnsupportedFeatureException` with a message directing the user to `raw()`.

This keeps the abstraction honest. "Not supported" is a valid, explicit answer.

---

## Review checklist (POI-bridge specific)

`trellis-check` should verify, per wrapper type:

- [ ] Holds a single `final XWPF* delegate`, set only in the constructor
- [ ] No cached read fields (text/style snapshots) — all reads go through delegate
- [ ] Collection-returning methods are live views (wrap-on-`get`), not snapshots
- [ ] `raw()` exists, returns the same delegate, has the warning Javadoc
- [ ] Public signatures leak no POI type except `raw()` return
- [ ] POI exceptions wrapped on normal methods; not wrapped on the `raw()` path
- [ ] Enum mapping in `internal/*`, public value objects are POI-free at source
- [ ] `equals`/`hashCode` exclude the delegate reference
- [ ] No reflection into POI internals, no subclassing of `XWPF*`
- [ ] Out-of-scope features are raw-only or throw `UnsupportedFeatureException` — never silent

---

**Language**: 本规范及所有对外代码工件均使用**中文**编写。
