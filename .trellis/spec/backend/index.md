# Backend Development Guidelines

> Coding conventions for nondocx — a Java/Maven library wrapping Apache POI's `XWPF*` API.

---

## Overview

"Backend" here means **library code**. nondocx has no service layer, no database, no HTTP API,
no frontend — it is a docx read/write library (`com.non:nondocx-core`). These guidelines encode
how the library is structured, how it bridges POI, how it reports errors, and what it considers
quality.

> Note: the default Trellis bootstrap ships `database-guidelines.md` and a `frontend/` directory.
> This project deleted both:
> - **No database** — it's a library.
> - **No frontend** — not a web project (nondocx-demo 是 demo 应用，不单列 frontend spec）。

---

## Guidelines Index

| Guide | Description | Status |
|-------|-------------|--------|
| [Directory Structure](./directory-structure.md) | Maven modules + `com.non.docx.core` package layout | Done |
| [POI Bridge](./poi-bridge.md) | How `api/` types wrap `XWPF*` (holding wrapper, `raw()`, exception wrapping) | Done |
| [Error Handling](./error-handling.md) | All-unchecked `DocxException` hierarchy, POI wrapping rules | Done |
| [Logging](./logging.md) | 库不日志走异常；demo 用 SLF4J；POI 5.x log4j-api 桥接陷阱 | Done |
| [Quality Guidelines](./quality-guidelines.md) | No Lombok, content-equal semantics, testing, forbidden patterns | Done |
| [Renderer Compatibility](./renderer-compatibility.md) | WPS/Word 双引擎兼容性陷阱清单（shading/列宽/pgNumType/...），每条带稳定锚点 | Done |
| [单 Agent 编辑](./agent-single.md) | 单 Agent 持有读/写/质检工具但不持有保存；保存由 `AgentEvent.Complete` 时应用层强制；dirty 检测、记忆污染治理、取消真相、SSE 契约 | Done |

Also relevant: [../guides/](../guides/) — general thinking guides (code reuse, cross-layer).

---

## How these guidelines were filled

1. **Source of truth**: task `06-16-nondocx-core-mvp` artifacts (`prd.md` / `design.md` /
   `implement.md`). These specs encoded the **design intent** agreed during planning; the MVP has
   since been **implemented and verified** (111 tests, round-trip green) in that task.
2. **Recalibration (done)**: the MVP is implemented. The per-topic "Examples" sections still
   point at the design doc; the load-bearing real-code knowledge — non-obvious Apache POI behaviors
   the wrappers adapt to — is captured in
   [poi-bridge.md → Implementation Notes — POI behavior gotchas](./poi-bridge.md). Read that before
   touching the bridge. The rules themselves stayed stable.
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
|- **全 unchecked `DocxException`** — POI 异常在公开表面包装；中文
  messages with context. → [Error Handling](./error-handling.md)
- **No Lombok, fluent `this`-returning mutators** — `run.text("x").bold()`, not `setBold`.
  → [Quality §1, Code Style](./quality-guidelines.md)
|- **全中文对外** — README/Javadoc/注释/异常消息均为中文；Trellis
  task docs in Chinese. → [Quality §5](./quality-guidelines.md)
- **WPS/Word 兼容性默认** — shading 强制 CLEAR（不暴露 SOLID）、列宽默认百分比、
  空 pgNumType 走显式清理。新增涉及渲染输出的 API 前查
  [Renderer Compatibility](./renderer-compatibility.md)。

---

## Scope Boundaries (what these specs intentionally do NOT cover)

Deferred / out-of-MVP — do not spec prematurely:
- **TOC** — **已落地为只读支持**（`api/toc/TableOfContents` + `TocEntry` + `Document.toc()`，见
  [poi-bridge.md N11](./poi-bridge.md)）。创建/刷新目录（需 Word 分页引擎）仍为 raw-only。
- **WPS/Word 兼容性** — **已系统化**（见
  [Renderer Compatibility](./renderer-compatibility.md)）。shading/列宽走 core 默认规避，
  空 pgNumType 走显式清理，其余 tab/装饰线/characterSpacing/titlePage 为 user-guidance。
  新增渲染相关 API 前必查此 spec。
- Document metadata, footnotes/endnotes (PRD Out of Scope)
- **Tracked changes** — **已落地**（`api/track/*`，见 [poi-bridge.md N12–N17](./poi-bridge.md)）。
  覆盖文本/移动/属性(rPrChange)/单元格(cellIns/cellDel/cellMerge 只读)的读 + accept/reject + 创作；
  pPrChange 等更高层属性类（CT 类型缺失）仍 raw-only。
- **Comments** — **已落地**（`api/comment/*`，见 [poi-bridge.md N18–N24](./poi-bridge.md)）。
  覆盖只读枚举/查询、整段范围批注创作、回复线程（commentsExtended 四 part 自维护）、
  现代兼容基础设施（people.xml/paraId/RSID）；resolve/done 状态、删除、跨段范围留 future。
- fields, OLE, OMML math, watermarks, text boxes, shapes (raw-only via `raw()`)
- JPMS `module-info.java` (pre-1.0)
- **Logging in library core** — 库本身仍不引入日志（诊断走异常）；demo 层日志规范见
  [logging.md](./logging.md)。若 core 未来需要内部诊断日志，届时新增 spec。
- Checkstyle / SpotBugs / Error Prone (after library stabilizes)

When any of these lands, add a spec entry and link it here.

---

**Language**: 所有文档均使用**中文**编写。
