# Quality Guidelines

> Code standards for nondocx. What "good" looks like, and the patterns this project explicitly avoids.

---

## Overview

nondocx is a hand-written POI wrapper library. There is no ORM, no service layer, no DI framework.
Quality here means: **correct POI bridging, stable public API, content-equal value semantics,
and code that future maintainers can read.** This file is the contract that `trellis-check`
sub-agents verify against.

---

## Non-Negotiable Rules

### 1. No Lombok

- Do not add `lombok` to any `pom.xml`.
- Core types are hand-written: hand-written delegation + hand-written content-equal
  `equals`/`hashCode`.
- internal DTOs may use IDE-generated accessors, but no annotation processor.

**Why**: Lombok + POI `XWPF*` (mutable delegates) gives misleading `@Data` equals that compare
delegate references and break round-trip testing. Hand-written keeps semantics explicit.

### 2. Content equality for core types

`Paragraph`, `Run`, `Table`, `RunStyle`, and any other type participating in round-trip
assertions MUST implement **content equality**:

- `equals` / `hashCode` compare **only content fields** (text, style, structure).
- **Never** include the `delegate` (`XWPF*`) reference in `equals`/`hashCode`.

```java
// RunStyle — immutable value object
public final class RunStyle {
    private final boolean bold; private final boolean italic; ...
    @Override public boolean equals(Object o) { /* compare bold/italic/font/size/color only */ }
    @Override public int hashCode() { return Objects.hash(bold, italic, ...); }
}

// Run — mutable live object, but equals is content-only
public final class Run {
    private final XWPFRun delegate;   // NOT in equals/hashCode
    @Override public boolean equals(Object o) { /* compare text + style derived from delegate */ }
}
```

**Why**: round-trip tests assert `assertThat(readBack).isEqualTo(original)` across two `Document`
instances that hold **different** delegate instances. Including delegate ref makes this always
false. See `design.md` §7 and §11 tradeoff.

### 3. Zero POI type leakage on public API

Public method signatures in `api/` and `builder/` MUST NOT contain any `org.apache.poi.*` type,
**except** the return type of `raw()`. See `poi-bridge.md`.

### 4. `raw()` is always available on core types

Every public domain type (`Document`, `Paragraph`, `Run`, `Table`, `Row`, `Cell`, `Section`,
`Image`, `Header`, `Footer`) MUST expose `public XWPF* raw()`. This is the escape hatch for
features outside the deep-wrap scope.

### 5. English everywhere outward-facing

README, Javadoc, code comments on public API, and exception messages are **English**.
Trellis task docs (PRD/design/implement) remain Chinese.

---

## Testing Requirements

### Coverage model: round-trip + POI cross-reference

This is a POI wrapper. "Self-testing self" is a blind spot. Two strategies are mandatory:

1. **Round-trip (deep equals)** — construct a document, `save`, `Docx.open`, assert
   `readBack.equals(original)` via content equality. This is the **core acceptance test**
   (`RoundTripTest`).
2. **POI cross-reference** — open the same file with raw `XWPFDocument`, assert our domain
   extraction matches POI's native extraction (`PoiCrossReferenceTest`).

### Test fixtures

- **Primary**: hand-written OOXML templates under `src/test/resources/ooxml/`, packed into
  `.docx` at test time by `internal/TestDocxPackager`. Transparent, reviewable, tiny, lets us
  craft edge cases precisely.
- **Secondary**: 1-2 real Word/WPS-generated `.docx` under `src/test/resources/fixtures/` for
  real-world compatibility smoke tests. Always annotate the source in a comment.

### Test stack

- **JUnit 5** (`org.junit.jupiter:junit-jupiter`) + **AssertJ** (`assertj-core`).
- Tests may use POI directly (`XWPFDocument`) for cross-reference — that is the point.
- No Mockito / Powermock in MVP.

### Naming

- Test classes: `<TypeUnderTest>Test` (`RunTest`, `ParagraphTest`, `RoundTripTest`).
- Test methods: describe behavior, e.g. `roundTripsBoldItalicAndFontSize()`.

---

## Code Style

### Format

- **Spotless** is the formatter (see `index.md`). Run `mvn spotless:apply` before commit.
- `spotless:check` is bound to `verify` and must stay green in CI.

### Mutator style: returning `this` (fluent), not `setXxx`

`Paragraph`, `Run`, and other mutable live objects use **chainable mutators** that return `this`:

```java
run.text("hi").bold().fontSize(12);
```

- Mutator method name is the property (e.g. `bold()`, `fontSize(int)`), not `setBold`.
- Getters keep the property name: `run.isBold()`, `run.text()`.
- This is the "mutable live object" track; the separate `builder/` track is for from-scratch
  construction. Do not blur the two (see `design.md` §4.3/§4.4).

### Javadoc

- Public API: full Javadoc, English, with `@throws` referencing the `Docx*Exception` type
  (not POI types).
- `raw()` Javadoc MUST include the warning: *"Modifications to the returned object affect the
  document immediately. Use with caution."*
- internal classes: first line `Internal API — subject to change without notice.`

### Java version

- Source/target via `<maven.compiler.release>11</maven.compiler.release>` (uses `--release`,
  not source/target pair — prevents accidental linkage of newer JDK APIs).
- Product is bytecode 55, compatible with 11/17/21. CI matrix verifies all three.

---

## Forbidden Patterns

| Pattern | Why forbidden |
|---|---|
| `lombok` dependency | See Rule 1 — breaks content-equal semantics |
| `@Data` / `@EqualsAndHashCode` on delegate-holding types | Compares delegate ref, breaks round-trip |
| `org.apache.poi.*` in public signatures (except `raw()` return) | Leaks POI to users |
| Catch + return null | Swallows errors; throw `Docx*Exception` instead |
| `setXxx` on live objects | Use fluent mutator returning `this` |
| JPMS `module-info.java` | Deferred to pre-1.0; POI JPMS friction |
| Logging framework in MVP | Carries info via exceptions; PRD defers logging |
| Mockito / mocking POI in tests | Round-trip + cross-reference instead |

---

## Code Review Checklist

Before requesting review / before `trellis-check`:

- [ ] Public signatures leak no POI types (except `raw()` returns)
- [ ] Core types implement content-equal `equals`/`hashCode` (no delegate ref)
- [ ] `raw()` present on every public domain type, with the warning Javadoc
- [ ] POI exceptions wrapped into `Docx*Exception` on public surface
- [ ] Exception messages English + carry context (path / index)
- [ ] No Lombok anywhere
- [ ] Fluent mutators return `this`; no `setXxx` on live objects
- [ ] Round-trip + POI cross-reference tests added for new domain behavior
- [ ] `mvn -q verify` green locally (compile + test + spotless)

---

**Language**: This spec and all outward-facing code artifacts are written in **English**.
