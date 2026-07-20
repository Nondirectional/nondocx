# 02 · 架构与核心契约

这一篇讲 nondocx 的设计骨架。读完你会明白：

- 为什么 `run.bold()` 改完**不用 `save`** 就在内存里生效
- 为什么 `save()` 再 `Docx.open()` 回来，新文档与原文档**比较仍相等**
- 为什么 `raw()` 这一个地方规则特殊到值得单独讲

> 这些不是 API 细节，是**契约**。后续每篇文档（尤其是 [05 tracked changes](./05-tracked-changes/README.md)）都会引用这里的术语。

---

## 1. 三层架构

nondocx 是一座架在 Apache POI 之上的桥。POI 自己又是架在 OOXML 之上的桥。所以一共三层：

```
┌─────────────────────────────────────────────────────────────┐
│  你的代码                                                    │
│    doc.paragraph(0).run(0).text("Hi").bold()                │
└──────────────────────────┬──────────────────────────────────┘
                           │  零 POI 泄露（raw() 除外）
┌──────────────────────────▼──────────────────────────────────┐
│  nondocx        io.github.nondirectional.docx.core.api.*                      │
│    Document / Paragraph / Run / Table / Section / ...        │
│    ─ 活对象，读穿透 / 写穿透                                  │
│    ─ 异常包装成 Docx*Exception                               │
│    ─ POI 类型只在 raw() 返回值里出现                          │
└──────────────────────────┬──────────────────────────────────┘
                           │  转发 / 包装
┌──────────────────────────▼──────────────────────────────────┐
│  Apache POI     org.apache.poi.xwpf.*                        │
│    XWPFDocument / XWPFParagraph / XWPFRun / XWPFTable / ...  │
└──────────────────────────┬──────────────────────────────────┘
                           │  读写
┌──────────────────────────▼──────────────────────────────────┐
│  OOXML         .docx（一个 zip，内含 word/document.xml 等）  │
│    <w:document><w:body>                                      │
│      <w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Hi</w:t></w:r>      │
└─────────────────────────────────────────────────────────────┘
```

| 层 | 它是什么 | nondocx 对它的态度 |
|---|---|---|
| **OOXML** | `.docx` 文件背后的 XML 语义（`<w:p>` 段落、`<w:r>` run、`<w:rPr>` run 样式、`<w:ins>` 修订标记 ……） | 一切特性的**真实语义来源**。nondocx 的封装是否正确，最终看 OOXML 怎么规定 |
| **POI** | Java 读 docx 的事实标准库。把 XML 解析成 `XWPF*` 对象树 | 封装对象，但绕开它的坑（POI 有很多 API 行为与 OOXML 直觉不符，见 `.trellis/spec/backend/poi-bridge.md` N1–N17） |
| **nondocx** | 领域友好的活对象模型 + 流畅 API + 干净异常 | **本库的全部价值**就体现在这一层 |

### 一个例子，三层走一遍：`run.bold()`

```java
doc.paragraph(0).run(0).text("Hi").bold();
```

**OOXML 层**：一个 run 是 `<w:r>` 元素。它的样式在子元素 `<w:rPr>`（run properties）里。粗体对应 `<w:rPr>` 下一个空的 `<w:b/>` 元素。

```xml
<w:r>
  <w:rPr><w:b/></w:rPr>      ← bold() 写的就是这个
  <w:t>Hi</w:t>               ← text("Hi") 写的是这个
</w:r>
```

**POI 层**：`XWPFRun` 是对 `<w:r>` 的 Java 包装。`xwpfRun.setBold(true)` 会在底层 `CTR`（POI 对 `<w:r>` 的 CT 类型）的 `RPr` 上加 `<w:b/>`。

**nondocx 层**：`Run` 持有一个 `final XWPFRun delegate`，`.bold()` 就是：

```java
// nondocx-core/.../api/text/Run.java
public Run bold(boolean bold) {
    delegate.setBold(bold);   // 直接转发给 POI
    return this;              // 返回 this → 支持链式
}
```

三层看完，几个 nondocx 的设计决定自然浮现：

1. **`.bold()` 返回 `Run`（this）** → 链式 `run.text("x").bold().italic()`。
2. **转发是同步写穿透的** → 不需要 `flush` / `save`，内存里立刻生效。
3. **`Run` 不缓存任何样式字段** → 下一次 `run.isBold()` 直接读 POI，保证永远是最新值。

这三个决定就是下一节的「活对象契约」。

---

## 2. 活对象契约（Holding Wrapper）

nondocx 的每一个领域类型（`Document`/`Paragraph`/`Run`/`Table`/`Row`/`Cell`/`Section`/`Image`/`Header`/`Footer`）都是一个 **活对象（live object）**：

### 契约 1：持有一个 `final` 委托，无缓存

```java
public final class Paragraph {
    private final XWPFParagraph delegate;   // 构造时设定，永不换

    public String text()        { return delegate.getText(); }      // 读穿透
    public Paragraph alignment(Alignment a) {
        delegate.setAlignment(Mappers.toPoi(a));                    // 写穿透
        return this;
    }
}
```

**没有「字段级缓存」**。`Run` 不会把 `text` 或 `bold` 存进一个字段"方便下次读"。
每次读都走委托。理由：一旦你缓存，就有"另一个 wrapper 或 `raw()` 改了同一个委托、缓存却没刷新"的同步 bug。

### 契约 2：包装即便宜，集合是「活视图」

构造一个 `new Paragraph(xwpfParagraph)` 只是存个引用，零计算。
这让 `paragraphs()` / `tables()` 这种返回集合的方法可以做成**活视图**：

```java
// 概念示意（实际代码用 AbstractList）
public List<Paragraph> paragraphs() {
    return new AbstractList<>() {
        private final List<XWPFParagraph> backing = delegate.getParagraphs();
        @Override public Paragraph get(int i) { return new Paragraph(backing.get(i)); }
        @Override public int size()           { return backing.size(); }
    };
}
```

含义：

- `doc.paragraphs().get(0)` 每次都 `new` 一个新 `Paragraph`。两个 `Paragraph` 实例可能包**同一个** `XWPFParagraph` —— 它们 `equals` 相等（见 [04 往返保真](./04-round-trip-and-equality.md)），但 `!=`。
- **不要**给 wrapper 赋予身份语义。别用 `IdentityHashMap<Paragraph, ...>`。

### 契约 3：读穿透 / 写穿透 ⇒ 改完立即生效

```java
Paragraph p = doc.paragraph(0);
p.addRun("追加一行");
System.out.println(p.runs().size());  // 立刻就能看到新 run
```

因为读写都直接落到底层 POI，没有「提交」步骤。`save()` 只是把内存里的 POI 树序列化到磁盘。

---

## 3. `raw()` 逃生舱 —— 唯一的 POI 出口

docx 的特性面是**天文数字**：域、OLE、OMML 数学、水印、文本框、形状、注释、图表、SmartArt ……
MVP 不可能都包。nondocx 的处理是：**给每个核心类型一个 `raw()` 逃生舱，其余特性诚实地留给 `raw()`**。

### 硬规则

每个核心类型都必须有：

```java
/**
 * Returns the underlying POI object.
 * <p>Modifications to the returned object affect the document immediately. Use with caution.
 */
public XWPF<Thing> raw() {
    return delegate;
}
```

- **同一实例**：包装器生命周期内，`raw()` 永远返回同一个委托引用（不拷贝、不重建）。
- **唯一出口**：`raw()` 的返回类型是公开 API 中**唯一**允许出现 `org.apache.poi.*` 的地方。其它任何公开方法的签名都不得泄露 POI 类型。
- **不包装异常**：通过 `raw()` 返回的对象调用 POI 方法，抛的 POI 异常**原样传播**，不会被包成 `Docx*Exception`。
  你选了 `raw()`，就是选了 POI 的领地，POI 的行为对你生效。
- **永不返回 null**：构造好的包装器，`raw()` 一定返回非 null 的委托。

### 为什么这个不对称是有意的

| 调用点 | POI 异常处理 |
|---|---|
| 普通 nondocx 公开方法（`paragraph()`/`addRun()`/…） | **包装**成 `Docx*Exception` |
| `raw()` 返回值上调用 POI 方法 | **不包装**，原样传播 |

在 wrapper 内部，nondocx 拥有抽象，负责给你干净的异常；
一旦你走 `raw()`，就是主动跳进 POI 的领地，POI 的异常（包括它那些与 OOXML 直觉不符的行为）对你原样生效。

### 不支持 ≠ 静默失败

碰到 nondocx 没封装的特性，**绝**不会：

- 返回空列表装作没东西（静默降级）
- 半包一个带 TODO 的东西

只有两条诚实的路：

1. 走 `raw()` 自己用 POI 处理；
2. 库内某些操作（如 cellMerge 修订的 accept/reject）会**显式抛 `UnsupportedFeatureException`**，消息里指明走 `raw()`。

「不支持」是一个合法、明确的答案。

---

## 4. 异常边界速览

```
RuntimeException
└── DocxException           ← nondocx 公开 API 的单一根类型（unchecked）
    ├── DocxIOException     ← 文件/流读写失败
    ├── DocxFormatException ← 内容不是有效 docx
    ├── DocxOperationException ← 语义层错误（索引越界、操作前置条件不满足等）
    └── UnsupportedFeatureException ← 显式声明某特性未封装，指向 raw()
```

- **全 unchecked**：不用 `catch` 或 `throws`，但你想统一处理时只需 `catch (DocxException e)`。
- **POI 异常在公开表面永不裸露**：除 `raw()` 路径外，POI 的 `IOException`/`POIXMLException` 等都会被包装进上面某一种。
- **消息全中文**（与代码注释一致）。

详见 [08 · 异常与 raw 领地](./08-exceptions-and-raw.md)。

---

## 5. 模块结构

```
nondocx/
├── nondocx-core/          ← 本篇讲的就是它
│   └── io.github.nondirectional.docx.core/
│       ├── Docx.java            ← 静态工厂（open/create）
│       ├── api/                 ← 公开领域类型（POI-free）
│       │   ├── Document, Paragraph, Run, Table, Row, Cell
│       │   ├── section/ (Section, PaperSize, Orientation)
│       │   ├── header/ (Header, Footer)
│       │   ├── image/ (Image, ImageType)
│       │   ├── style/  (Alignment, HeadingLevel, ListKind, RunStyle)
│       │   ├── text/   (Paragraph, Run, Hyperlink)
│       │   ├── table/  (Table, Row, Cell)
│       │   ├── toc/    (TableOfContents, TocEntry)
│       │   ├── track/  (TrackedChanges, TrackedChange, ...)
│       │   └── exception/ (DocxException 家族)
│       ├── builder/             ← 声明式构建轨道
│       │   └── DocumentBuilder, ParagraphBuilder, TableBuilder
│       └── internal/            ← 实现细节（POI 脏活收容所），用户勿用
│           ├── poi/  (Mappers, Pictures, Numbering, TocFields, TrackedChangeNodes)
│           └── util/ (Objects, Streams)
├── nondocx-toolkit/       ← 给 LLM Agent 的工具集（见 07）
└── nondocx-examples/      ← 可运行示例
```

**三个约定**：

1. **`api/` 在源码级 POI-free**：不只是签名不泄露 POI，连 `import` 都没有。POI 的枚举映射（`Alignment ↔ ParagraphAlignment`）住在 `internal/poi/Mappers`，不污染公开类型。
2. **`internal/` 是脏活收容所**：所有「绕过 POI 坑」的代码都在这里（`TocFields`、`TrackedChangeNodes`、`Numbering`、`Pictures`）。用户**不应**直接用这个包的类 —— 它们随时可能变。
3. **`api/` 的构造函数接受 POI 类型是「内部接缝」**：跨包构造需要，但只供库内使用。用户通过 `Docx.open` / `Docx.create` / 父对象的方法拿实例。

---

## 6. 一句话总结

> nondocx 的每个领域类型都是 **持有一个 POI 委托、读写穿透、不缓存、公开表面 POI-free** 的活对象，
> 配一个 `raw()` 逃生舱让你下探到 POI，配一套中文 `DocxException` 处理所有失败。

这三件事 —— 活对象、`raw()`、干净异常 —— 是 nondocx 全部设计决策的出发点。
读后面任何一篇（tracked changes、toolkit、往返保真 ……）遇到"为什么"时，答案大概率回到这三点之一。

---

## 下一步

- [04 · 往返保真与内容相等性](./04-round-trip-and-equality.md) —— `equals/hashCode` 为什么比的是「解析值」而不是 XML，save→reopen 为什么仍相等
- [03 · API 速查](./03-api-reference.md) —— 按类型查方法
- [08 · 异常与 raw 领地](./08-exceptions-and-raw.md) —— 异常层级详解
