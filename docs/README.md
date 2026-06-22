# nondocx 文档

> 面向 Java 的流畅、领域友好的 **docx 读写库**，基于 [Apache POI](https://poi.apache.org/) 构建。

本目录是 nondocx 的**使用文档**。代码注释/Javadoc 讲的是「这个方法做什么」；
这里讲的是「**为什么这么设计、怎么组合起来用、边界在哪里**」。

---

## 文档地图

| # | 文档 | 一句话 |
|---|---|---|
| 01 | [快速开始](./01-quick-start.md) | 5 分钟打开、改、存一份 docx |
| 02 | [架构与核心契约](./02-architecture.md) | 三层架构、活对象语义、`raw()` 逃生舱 —— **理解 nondocx 的根基** |
| 03 | [API 速查](./03-api-reference.md) | 按类型分组的常用方法表 + 代码片段 |
| 04 | [往返保真与内容相等性](./04-round-trip-and-equality.md) | 为什么 save→reopen 仍相等，活对象能不能当 HashMap 键 |
| 05 | [修订（tracked changes）教程](./05-tracked-changes/README.md) | 从 OOXML 模型到读写、accept/reject、创作的完整教程 |
| 06 | [构建器轨道](./06-builder-track.md) | `DocumentBuilder` 声明式构建 vs 活对象编辑 |
| 07 | [nondocx-toolkit](./07-toolkit.md) | 把 docx 能力暴露给 LLM Agent 的六组工具 |
| 08 | [异常与 `raw()` 领地](./08-exceptions-and-raw.md) | `DocxException` 层级、POI 异常何处不包装 |
| 09 | [FAQ 与已知边界](./09-faq-and-boundaries.md) | TOC 只读、WPS 兼容、id 跨 save 不稳定 …… |

---

## 三条阅读路线

**📜 路线 A —— 我是新手，想会用**
`01-quick-start` → `02-architecture` → `03-api-reference` → 按需查 `09-faq`。

**🔧 路线 B —— 我要做修订（tracked changes）**
直接进 `05-tracked-changes/`，四篇按「概念 → 读 → accept/reject → 创作」顺序读。
建议先扫一眼 `02-architecture` 的三层递进范式，TC 教程全程沿用。

**🤖 路线 C —— 我要接 Agent**
`07-toolkit` 看工具集设计，`nondocx-examples/agent/` 是可运行示例，
TC 相关工具配合 `05-tracked-changes/` 一起看。

---

## 本文档与 `.trellis/spec/` 的分工

| | `docs/`（本目录） | `.trellis/spec/` |
|---|---|---|
| **视角** | 使用者：怎么用、为什么 | 贡献者：怎么写代码、什么不能碰 |
| **读者** | 用 nondocx 的人 / 未来的 AI | 改 nondocx 源码的人 / AI |
| **典型内容** | 教程、API 速查、概念解释 | POI bridge 硬契约、N1–N17 gotchas、目录结构规范 |
| **语言** | 中文 | 中文 |

两者互补不重复。比如 POI 的「`setNumID(null)` 会留下空 numId 导致 XmlValueOutOfRange」
这种**源码级 gotcha** 在 `spec/backend/poi-bridge.md N4`；本文档只在 `09-faq` 讲
「为什么你不能直接操作底层」的使用层面含义。

---

## 写作约定

- **三层递进**：讲到任何封装特性，都按 **OOXML 是什么 → POI 如何表达 → nondocx 为什么这样设计** 的顺序展开。
  这是项目的既定教学范式（见 `.trellis/spec/guides/teaching-approach.md`），对「未来回看」特别友好 —— 既懂 API 又懂底层契约。
- **代码示例优先取自 `nondocx-examples/`**，保证文档与真实可运行代码不脱节。
- **公开 API 零 POI 泄露**：示例里除 `raw()` 一处外，不会出现 `org.apache.poi.*` 类型。
