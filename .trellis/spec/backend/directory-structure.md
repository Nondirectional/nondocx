# Directory Structure

> How the nondocx Java library code is organized.

---

## Overview

nondocx is a **Maven multi-module Java library** (`com.non:nondocx-*`) wrapping Apache POI's
`XWPF*` API into a fluent, domain-friendly docx read/write layer. There is one real module today:

- `nondocx-core` — the docx domain model + POI bridge.

The layout below is the source of truth for where new code goes. It mirrors `design.md` §2/§3.

---

## Repository Layout

```
nondocx/
├── pom.xml                          # parent POM (packaging=pom): dependencyManagement + pluginManagement
├── nondocx-core/
│   ├── pom.xml                      # inherits parent; declares poi/poi-ooxml + junit/assertj
│   └── src/{main,test}/java/com/non/docx/core/
├── LICENSE                          # Apache 2.0
├── README.md                        # 中文
└── .github/workflows/ci.yml         # JDK matrix [11,17,21]
```

---

## Java Package Layout (base: `com.non.docx.core`)

```
com.non.docx.core
├── Docx.java                    # static factory facade (open/create); stateless
├── api/                         # PUBLIC domain model (deep-wrap zone)
│   ├── Document.java            (holds XWPFDocument)
│   ├── text/                    Paragraph / Run / Hyperlink
│   ├── table/                   Table / Row / Cell
│   ├── section/                 Section (page properties)
│   ├── image/                   Image (inline pictures)
│   ├── header/                  Header / Footer
│   ├── style/                   Alignment / HeadingLevel / RunStyle (value objects)
│   └── exception/               DocxException hierarchy (user-facing)
├── builder/                     # construction track: DocumentBuilder / TableBuilder / ...
└── internal/                    # IMPLEMENTATION DETAIL — naming-convention isolation
    ├── poi/                     XWPF* ↔ core type bridge, enum mapping
    ├── style/                   WdAlign / STXXX ↔ style value objects
    └── util/                    XmlBeans / reflection / resource cleanup helpers
```

### Where new code goes

| You are adding… | Put it in |
|---|---|
| A new public docx domain type (wraps an `XWPF*`) | `com.non.docx.core.api.<concept>/` |
| A new immutable style value object (alignment, heading, etc.) | `com.non.docx.core.api.style/` |
| A new public exception | `com.non.docx.core.api.exception/` |
| A construction helper from scratch | `com.non.docx.core.builder/` |
| POI enum mapping, XmlBeans wrangling, IO helpers | `com.non.docx.core.internal.*` (prefer sub-package by concern) |
| A future module (template engine, converter) | new Maven module `nondocx-<name>` with base package `com.non.docx.<name>` |

---

## Naming Conventions

- **Packages**: lowercase, single segment per word. Concept grouping via sub-package
  (`api.text`, `api.table`), NOT via `CamelCase`.
- **Classes wrapping an `XWPF*` type**: domain noun, no suffix — `Paragraph` not `ParagraphWrapper`.
  The class **holds** a `final XWPFParagraph delegate` (see `poi-bridge.md`).
- **Value objects** (immutable style/config): plain noun, no `Vo`/`Dto` suffix
  (e.g. `RunStyle`, not `RunStyleVo`).
- **Internal classes**: package-private where possible; only `public` when crossed by `api/`.
  Every internal class Javadoc first line: `Internal API — subject to change without notice.`
- **Facade / entry point**: `Docx` (static factory, stateless, not a god-object).

---

## Module Boundaries

- `api/` and `builder/` are **public**. Method signatures there MUST NOT mention any
  `org.apache.poi.*` type except inside `raw()` return types.
- `internal/` is **implementation detail**. It may import POI freely. It is isolated by
  naming convention + Javadoc + minimal visibility only (JPMS `module-info.java` is deferred
  to pre-1.0; do not add one yet).
- Each module's base package embeds the module name (`core`) to leave room for future modules
  without forced relocations.

---

## Examples

> Note: code is being implemented in task `06-16-nondocx-core-mvp`. Reference the design
> doc (`.trellis/tasks/06-16-nondocx-core-mvp/design.md`) §3 for the canonical layout while
> the first classes land. Once classes exist, replace this section with real file links:
>
> - `nondocx-core/src/main/java/com/non/docx/core/api/text/Paragraph.java` — domain type holding `XWPFParagraph`
> - `nondocx-core/src/main/java/com/non/docx/core/internal/poi/Mappers.java` — enum mapping (internal)

---

**Language**: 所有文档均使用**中文**编写。
