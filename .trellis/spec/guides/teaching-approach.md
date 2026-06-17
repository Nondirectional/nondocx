# 教学式开发指南 (Teaching-Oriented Development)

> **目的**：约定 AI 助手在与开发者 non 协作实现时，如何系统性讲解 POI 和 OOXML
> 知识，确保开发者从每个功能的实现中学习到底层原理。

---

## 适用场景

此指南适用于所有**涉及 docx 格式处理**的实现工作——无论是新增功能、修复 bug、
还是重构。凡涉及以下任一领域，都必须按本指南执行教学：

- **OOXML (Office Open XML)** — `.docx` 文件的底层 XML 格式
- **Apache POI** — Java 生态操作 docx 的事实标准库
- **nondocx 封装层** — 在 POI 之上构建的领域抽象

## 核心原则

### 1. 不用子代理

```
✅ 正确：AI 在主对话中直接编辑文件、解释概念、回答问题
❌ 错误：把实现任务派发给子代理，只给开发者看结果
```

实现工作的**每一步**都在开发者眼前进行。这样开发者可以随时打断问「这行代码
是什么意思」「为什么这里要这样处理」。

### 2. 三层递进教学法

每引入一个新的 docx 概念或功能，按以下顺序讲解：

```
第一层 · OOXML 概念
    什么是这个功能在 .docx 文件里的 XML 表达？
    打开 ZIP 看 document.xml 是什么样的？

第二层 · POI 映射
    Apache POI 用什么 XWPF* 类型承载这个 XML？
    它的 API 是什么样的？有什么坑？

第三层 · nondocx 封装
    为什么这样封装？为什么不是另一种方式？
    和 POI 原生的差异是什么？为什么做这个取舍？
```

### 3. 具体 vs 抽象

- 讲 OOXML 时，**打开文件看实际 XML**（用 `unzip -p` 或 POI 原生读取）
- 讲 POI 时，**写一小段原生 POI 代码做对比**
- 讲封裝时，**对比「如果不这样封装会怎样」**

---

## 教学清单（AI 在实现前自检）

在开始写任何代码前，检查是否满足以下条件：

### 概念准备

- [ ] 本次涉及哪个 docx 概念？（段落 / run / 表格 / 图片 / 分节 / 列表…）
- [ ] 这个概念的 OOXML XML 结构我已经准备好解释了？
- [ ] POI 的对应 XWPF* API 我已经确认了？
- [ ] POI 的这个 API 有没有隐藏行为（gotcha）需要提前说明？
- [ ] nondocx 已有的封装风格是什么？我这个实现是否一致？

### 教学过程

- [ ] 先解释 OOXML（XML 结构），再讲 POI，最后才写 nondocx 代码
- [ ] 每段关键代码都配有注释说明「为什么」
- [ ] 给了开发者提问的机会（不要一口气写完所有代码）
- [ ] 涉及 POI 不合理/不直观的行为时，特别标注

---

## 常见 OOXML / POI 知识点（按优先级排序）

以下列表是预计需要教学的核心概念，每完成一个就在前面打 `[x]`：

### 基础（必须先掌握）

- [ ] **ZIP 包结构** — `.docx` 就是一个 ZIP，核心是 `word/document.xml`
- [ ] **`w:p` (Paragraph)** — 段落的 XML 元素；`XWPFParagraph`
- [ ] **`w:r` (Run)** — 文本片段的 XML 元素；`XWPFRun`
- [ ] **`w:rPr` (Run Properties)** — 粗体/斜体/字体/字号/颜色的 XML 位置
- [ ] **`w:pPr` (Paragraph Properties)** — 对齐/缩进/行距/列表属性的 XML 位置
- [ ] **Streaming vs DOM 模型** — POI 用 DOM 操作 XML，修改立即写回

### 常见富内容

- [ ] **`w:tbl` (Table)** — 表格的 XML 结构；`XWPFTable` / `XWPFTableRow` / `XWPFTableCell`
- [ ] **`w:hyperlink` (Hyperlink)** — 超链接的 XML（`w:hyperlink` 包着 `w:r`）
- [ ] **`w:drawing` (Image)** — 图片作为 `w:drawing` 嵌在 run 里；EMU 单位
- [ ] **`w:numPr` (List/Numbering)** — 列表编号的 XML；`abstractNum` / `num`

### 页面级

- [ ] **`w:sectPr` (Section)** — 分节属性（纸张/边距/横竖）
- [ ] **`w:headerReference` / `w:footerReference`** — 页眉页脚的 XML 引用机制
- [ ] **Header/Footer 独立 Part** — header.xml / footer.xml 是单独的 ZIP 条目

---

## 教学节奏建议

| 开发者状态 | AI 节奏 |
|-----------|---------|
| 第一次接触某个概念 | 慢：先看 XML → 再看 POI → 再写封装，每步确认 |
| 已经见过类似概念 | 中：快速类比，重点讲不同之处 |
| 已经熟悉的概念 | 快：直接从问题入手，按需解释 |

> **原则**：宁可啰嗦也不要跳过。「OOXML 为什么这样设计」这个层面的理解，
> 一旦跳过去就很难补回来。

---

## 相关文档

- [OOXML 规范 ECMA-376](https://www.ecma-international.org/publications-and-standards/standards/ecma-376/)
- [Apache POI 官方文档](https://poi.apache.org/components/document/)
- [POI Bridge 规范](../backend/poi-bridge.md) — nondocx 如何封装 POI
- [Quality Guidelines](../backend/quality-guidelines.md) — 代码质量约定
