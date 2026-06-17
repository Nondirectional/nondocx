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

This hatch is how nondocx covers out-of-scope features (tracked changes, fields, OLE, math,
watermarks, shapes — see PRD "Out of Scope") without pretending to support them. It is also the
de-facto answer to "POI can do X but we don't wrap it yet".

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

### N5 — Header/footer are create-on-access; resolve read-only for equality
`Section.header()`/`footer()` create+attach a default header/footer part if none exists
(create-once, via `XWPFHeaderFooterPolicy(document, sectPr)`). Because that mutates the document,
`Section.equals`/`hashCode` resolve header/footer content **read-only** via
`getDefaultHeader()`/`getDefaultFooter()` (null→empty list, no creation, `catch POIXMLException`).
Never call the create-on-access `header()`/`footer()` from inside `equals`.

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

**Rule**: on the **first** `Section.header()` / `Section.footer()` creation path, if the section still lacks page settings, nondocx materializes a compatibility default: `PaperSize.A4` + 1-inch margins (`1440` twips on all four sides). This fill is **missing-only** — never overwrite user-specified `pgSz` / `pgMar`, and do not mutate a section merely because an existing header/footer is being read. Examples may still set page size/margins explicitly for teaching clarity.

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
