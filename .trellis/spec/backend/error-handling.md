# Error Handling

> How nondocx surfaces errors: a self-contained, all-unchecked `DocxException` hierarchy.

---

## Overview

nondocx is a **library**, not an application. Its error contract serves two goals:

1. **Zero POI leakage**: users must never need to `import org.apache.poi.*` to catch an error.
2. **All unchecked**: every exception extends `RuntimeException`. Modern Java libraries do not
   force `throws` on callers — it is hostile to lambdas, streams, and builder chains.

This is the nondocx variant of "error handling"; it does NOT have a backend service layer.

---

## Exception Hierarchy

```
RuntimeException
└── com.non.docx.core.api.exception.DocxException        ← root
    ├── DocxIOException          wraps IOException / POI OpenXML4J / POIXML exceptions
    │     └── getCause() preserves the original exception
    ├── DocxFormatException      docx corrupt / illegal format; carries file path
    ├── DocxOperationException   logical error; carries context (e.g. paragraph index)
    │     └── subclasses: NoSuchElementException, IllegalArgumentException
    └── UnsupportedFeatureException  feature outside the wrap scope → tell user to use raw()
```

Location: `com.non.docx.core.api.exception.*`. All public, all `extends RuntimeException`.

---

## Rules

### Rule 1 — Wrap every POI exception on the public API surface

Any time code in `api/` or `builder/` would let an `org.apache.poi.*` exception escape, it MUST
catch and wrap it into the matching `Docx*Exception`. The only exception is the `raw()` escape
hatch path (see `poi-bridge.md` Rule 3).

```java
// GOOD — in Document.save()
try {
    delegate.write(out);
} catch (IOException e) {
    throw new DocxIOException("Failed to save document", e);
}

// BAD — leaks POI type into user's catch clause
public void save(OutputStream out) {
    delegate.write(out);   // throws POIXMLException leaks
}
```

### Rule 2 — Exceptions carry document context

Every `Docx*Exception` should help the user locate the problem:

- `DocxFormatException` carries the **file path** (`Path` / `String`).
- `DocxOperationException` carries the **element index / name** it was operating on
  (paragraph index, table index, etc.).
- 消息均为**中文**，可操作，并说明失败的操作。

### Rule 3 — 消息为中文、纯文本、不含堆栈跟踪信息

```
"Failed to open document: /path/to/file.docx"
"Paragraph index 5 out of bounds (document has 3 paragraphs)"
"Inline image format PNG not supported by this writer"
```

Do NOT encode POI internal class names in user-facing messages unless unavoidable. If a POI
cause is informative, it stays in `getCause()`, not the message.

### Rule 4 — Never swallow, never return null on error

- Do not catch-and-return-null. Throw the appropriate `Docx*Exception`.
- Validation failures (`null` arg, out-of-bounds index) throw `DocxOperationException`
  subclasses, not bare `NullPointerException`.

### Rule 5 — Unsupported features are explicit, not silent

When a docx feature is outside the deep-wrap scope (fields, OLE, math, etc.), the wrapper method
either:
- provides `raw()` (always available), OR
- throws `UnsupportedFeatureException` with a message pointing to `raw()`.

Never return a degraded/empty result silently for an unsupported feature. (Partial wraps count as
supported for the part that is wrapped — e.g. tracked-changes *read* is wrapped via
`Document.trackedChanges()`, while accept/reject, authoring, and advanced revision types still fall
back to `raw()` until their sub-tasks land.)

---

## What goes where

| Situation | Throw |
|---|---|
| `Docx.open()` / `save()` IO failure | `DocxIOException` |
| File is not a valid docx / corrupt OOXML | `DocxFormatException` (with path) |
| Index out of bounds (`paragraph(99)`) | `NoSuchElementException` (under `DocxOperationException`) |
| Null / illegal argument to a method | `IllegalArgumentException` (under `DocxOperationException`) |
| Feature outside wrap scope | `UnsupportedFeatureException` |
| Raw POI failure via `raw()` path | **do not wrap** — propagate as-is (POI's territory) |

---

**Language**: 所有异常消息、Javadoc 及本规范均使用**中文**编写。
