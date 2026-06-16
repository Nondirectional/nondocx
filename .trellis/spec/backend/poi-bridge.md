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

**Language**: This spec and all outward-facing code artifacts are written in **English**.
