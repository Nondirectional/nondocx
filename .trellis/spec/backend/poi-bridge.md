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
text-class accept/reject/authoring, move accept/reject (paired), and run-property (rPrChange)
read/accept/reject are wrapped (`Document.trackedChanges()` / `Paragraph` / `Run`, see N12 / N13 /
N14 / N15), while cell revisions (cellIns/cellDel), pPrChange, and accept/reject/authoring of
those still fall back to `raw()` until later sub-tasks land.

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

**范围**: 覆盖 move(accept/reject 配对联动)与 property(rPrChange 读 + accept/reject)。**不**含:cell(cellIns/cellDel)、pPrChange、`sectPrChange`/`tblPrChange` 等更高层属性类、move/property 的显式创作。回归:`TrackedAdvancedTypesTest` 8 用例(move 配对读回/accept/孤立异常 + property 读回/accept/reject/类型边界/raw 抛);全量 167 tests green、spotless clean。

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
