# Backend Development Guidelines

> Coding conventions for nondocx — a Java/Maven library wrapping Apache POI's `XWPF*` API.

---

## Overview

"Backend" here means **library code**. nondocx has no service layer, no database, no HTTP API,
no frontend — it is a docx read/write library (`com.non:nondocx-core`). These guidelines encode
how the library is structured, how it bridges POI, how it reports errors, and what it considers
quality.

> Note: the default Trellis bootstrap ships `database-guidelines.md` / `logging-guidelines.md`
> and a `frontend/` directory. This project deleted all three:
> - **No database** — it's a library.
> - **No logging framework in MVP** — diagnostic info travels via exceptions
>   (see `design.md` §9). When a logging layer is introduced later, add a spec then.
> - **No frontend** — not a web project.

---

## Guidelines Index

| Guide | Description | Status |
|-------|-------------|--------|
| [Directory Structure](./directory-structure.md) | Maven modules + `com.non.docx.core` package layout | Done |
| [POI Bridge](./poi-bridge.md) | How `api/` types wrap `XWPF*` (holding wrapper, `raw()`, exception wrapping) | Done |
| [Error Handling](./error-handling.md) | All-unchecked `DocxException` hierarchy, POI wrapping rules | Done |
| [Quality Guidelines](./quality-guidelines.md) | No Lombok, content-equal semantics, testing, forbidden patterns | Done |

Also relevant: [../guides/](../guides/) — general thinking guides (code reuse, cross-layer).

---

## How these guidelines were filled

1. **Source of truth**: task `06-16-nondocx-core-mvp` artifacts (`prd.md` / `design.md` /
   `implement.md`), since the library has no committed code yet. These specs encode the
   **design intent** agreed during planning.
2. **Recalibration**: once the first classes land in `06-16-nondocx-core-mvp`, re-read 2-3 real
   examples per rule and update the "Examples" sections with real file links. The rules
   themselves should stay stable.
3. **Document reality, not ideals**: if a rule below ever disagrees with shipped code, the
   **code wins** — fix the spec, not silently the code.

---

## At-a-Glance Rules

These are the load-bearing conventions; each links to its full treatment.

- **Holding wrapper, no cache** — each `api/` type holds a `final XWPF* delegate`; reads/writes
  are live. → [POI Bridge §1](./poi-bridge.md)
- **`raw()` escape hatch on every core type** — same delegate, warning Javadoc, POI exceptions
  propagate unwrapped on this path. → [POI Bridge §3](./poi-bridge.md)
- **Zero POI leakage on public API** — no `org.apache.poi.*` in signatures except `raw()` return.
  → [Quality §3](./quality-guidelines.md)
- **Content equality** — `equals`/`hashCode` compare content, never the delegate reference.
  → [Quality §2](./quality-guidelines.md)
- **All-unchecked `DocxException`** — POI exceptions wrapped on the public surface; English
  messages with context. → [Error Handling](./error-handling.md)
- **No Lombok, fluent `this`-returning mutators** — `run.text("x").bold()`, not `setBold`.
  → [Quality §1, Code Style](./quality-guidelines.md)
- **English outward-facing** — README/Javadoc/comments/exception messages in English; Trellis
  task docs in Chinese. → [Quality §5](./quality-guidelines.md)

---

## Scope Boundaries (what these specs intentionally do NOT cover)

Deferred / out-of-MVP — do not spec prematurely:
- TOC, document metadata, footnotes/endnotes (PRD Out of Scope)
- Tracked changes, fields, OLE, OMML math, watermarks, text boxes, shapes (raw-only via `raw()`)
- JPMS `module-info.java` (pre-1.0)
- Logging framework (introduce spec when adopted)
- Checkstyle / SpotBugs / Error Prone (after library stabilizes)

When any of these lands, add a spec entry and link it here.

---

**Language**: All documentation is written in **English**.
