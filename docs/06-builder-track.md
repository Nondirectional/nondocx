# 06 · 构建器轨道

nondocx 有两条「轨道」编辑同一份 docx：

1. **活对象轨道** —— `Docx.open` / `Docx.create` 拿到 `Document`，直接 `.paragraph(0).run(0).bold()`
2. **构建器轨道** —— `DocumentBuilder.start()` 起一条声明式链，用配置器 lambda 组装

**它们不是两套并行的 API**，而是同一个底层活对象模型的两种编排方式。理解这一点的关键：

> 构建器不复制 run/段落/表格的任何行为，它只**组合活对象的构建块**（`addParagraph`/`addRun`/`addTable`...）。
> 因此所有样式、所有往返语义、所有 `equals` 规则都只活在一个位置 —— 活对象层。

---

## 1. 两条轨道，一个对象

### 活对象轨道（命令式）

```java
try (Document doc = Docx.create()) {
    doc.addParagraph("标题").heading(HeadingLevel.H1);
    Paragraph p = doc.addParagraph();
    p.addRun("普通文本 ");
    p.addRun("粗体").bold();

    doc.addTable()
       .addRow().cell("A1").cell("B1");
    // ...
    doc.save(Path.of("out.docx"));
}
```

适合：**改现有文档**（按索引取段/run/cell 做局部修改）、不确定结构需要边读边写。

### 构建器轨道（声明式）

```java
Document doc = DocumentBuilder.start()
    .heading(HeadingLevel.H1, "标题")
    .paragraph(p -> p
        .addRun("普通文本 ")
        .addRun("粗体").bold())
    .table(t -> t
        .row(r -> r.cell("A1").cell("B1")))
    .build();

doc.save(Path.of("out.docx"));
```

适合：**从零组装结构化文档**（报告、合同、模板生成），结构与代码结构一一对应，可读性高。

### 关键事实

- `DocumentBuilder.start()` 内部就是 `new DocumentBuilder(Docx.create())` —— 起点是同一个 `Docx.create()`。
- `build()` 返回的 `Document` 与 `Docx.open`/`Docx.create` 返回的是**同一种**活对象，可继续改、可关闭。
- 配置器 lambda（`paragraph(Consumer<Paragraph>)`、`table(Consumer<Table>)`）接收的就是活对象 `Paragraph`/`Table`，**全部 run/cell/样式 API 都能用**。

> 这就是「构建器不是并行 API」的来源 —— 它没有自己的词汇，调的是你熟悉的那套方法。

---

## 2. `DocumentBuilder` API

| 方法 | 说明 |
|---|---|
| `DocumentBuilder.start()` | 在一个空文档上起一条链 |
| `.heading(level, text)` 🔄 | 追加标题段（`= addParagraph().heading(level).addRun(text)`） |
| `.paragraph(text)` 🔄 | 追加带文本的普通段 |
| `.paragraph(Consumer<Paragraph> config)` 🔄 | 追加段，用 lambda 配置（含 addRun、样式、列表…） |
| `.table(Consumer<Table> config)` 🔄 | 追加表，用 lambda 配置（含 row/cell） |
| `.build()` | **结束链**，返回底层 `Document`（调用方负责 `close()`） |

> 没有 `addImage`/`addHyperlink` 等专用便捷方法 —— 这些都在 `Paragraph` 的 lambda 配置里调，因为它们本来就是 `Paragraph` 的能力。构建器只暴露「结构组装」级的方法。

---

## 3. 子构建器：`ParagraphBuilder` / `TableBuilder`

除了在 `DocumentBuilder` 链上用 `Consumer` lambda，nondocx 还提供了两个独立的子构建器，用来组装**单个**段落或表格：

```java
// ParagraphBuilder：装一个已有段落
Paragraph p = doc.addParagraph();
ParagraphBuilder.on(p)
    .heading(HeadingLevel.H2)
    .run(r -> r.text("hello").bold().color("0000FF"))
    .paragraph();   // 返回配好的 Paragraph

// TableBuilder：装一个已有表格
Table t = doc.addTable();
TableBuilder.on(t)
    .row(r -> r.cell("A").cell("B"))
    .row(r -> r.cell("C").cell("D"))
    .table();       // 返回配好的 Table
```

| `ParagraphBuilder` | 说明 |
|---|---|
| `ParagraphBuilder.on(paragraph)` | 绑定一个已有段（通常是 `addParagraph()` 的结果） |
| `.heading(level)` / `.clearHeading()` / `.alignment(...)` / `.indent(...)` / `.lineSpacing(...)` / `.list(kind, lvl)` / `.clearList()` 🔄 | 段落级样式（转发到 `Paragraph`） |
| `.text(text)` | 追加 run 带文本 → 返回 `Run`（可继续链式样式） |
| `.run()` / `.run(Consumer<Run>)` | 追加空 run / 用 lambda 配置 |
| `.paragraph()` | 返回配好的 `Paragraph` |

| `TableBuilder` | 说明 |
|---|---|
| `TableBuilder.on(table)` | 绑定一个已有表 |
| `.row()` 🔄 | 追加空行 → 返回 `Row`（可继续 `.cell(...)`） |
| `.row(Consumer<Row> config)` 🔄 | 追加行，用 lambda 配置 cell |
| `.table()` | 返回配好的 `Table` |

> 子构建器适合在你想「对一个新建段落/表格做一组配置」时用。链式风格更紧凑，但**底层调的还是活对象方法**。

---

## 4. 一个完整示例

```java
import com.non.docx.core.api.Document;
import com.non.docx.core.api.style.HeadingLevel;
import com.non.docx.core.api.style.Alignment;
import com.non.docx.core.builder.DocumentBuilder;
import java.nio.file.Path;

Document doc = DocumentBuilder.start()
    .heading(HeadingLevel.H1, "季度报告")
    .paragraph(p -> p
        .addRun("摘要：").bold()
        .addRun(" 本季度收入同比增长 20%。"))
    .paragraph(p -> p
        .alignment(Alignment.CENTER)
        .addRun("（详见表格）").italic().color("888888"))
    .table(t -> t
        .row(r -> r.cell("指标").cell("本季").cell("同比"))
        .row(r -> r.cell("收入").cell("$1.2M").cell("+20%"))
        .row(r -> r.cell("用户").cell("12,345").cell("+15%")))
    .build();

doc.save(Path.of("report.docx"));
doc.close();   // build() 返回的 Document 调用方负责关闭
```

> 结构与代码一一对应：标题 → 摘要段 → 居中说明 → 表格。这种「我看到了文档长什么样」的可读性是构建器轨道的核心价值。

---

## 5. 何时用哪条轨道

| 场景 | 推荐 | 原因 |
|---|---|---|
| 改现有文档（按索引改某个 run） | **活对象** | 索引式访问、读改交替自然 |
| 从零生成结构化文档 | **构建器** | 声明式、结构可读 |
| 边读边写、需要条件判断 | **活对象** | 命令式控制流更顺手 |
| 测试 fixture 生成 | **构建器** | 一行链式表达出一份文档，紧凑 |
| 把一份文档的某段「重新配样式」 | **`ParagraphBuilder.on(...)`** | 子构建器正合适 |
| Agent 通过工具改文档 | **活对象**（被 toolkit 包装，见 [07](./07-toolkit.md)） | 工具调用本质是命令式 |

**两轨可衔接**：`build()` 返回活对象，你可以接着用活对象 API 改；反之亦然 —— 你可以先 `Docx.create()` 建文档，再用 `ParagraphBuilder.on(...)` 给某些段落做配置化组装。

---

## 6. 构建器轨道的相等性保证

构建器轨道没有独立的相等性规则 —— 它返回的 `Document` 与 `Docx.create()` 返回的是同一种活对象，[04 · 往返保真](./04-round-trip-and-equality.md) 的所有规则原样适用。

这意味着：

```java
Document built = DocumentBuilder.start()
    .heading(HeadingLevel.H1, "Title")
    .paragraph("body")
    .build();
built.save(file);

try (Document reopened = Docx.open(file)) {
    // 比较的是 bodyElements() + sections()，与构建方式无关
    assertThat(reopened.bodyElements()).isEqualTo(built.bodyElements());
}
```

构建器只是「组装方式」，不改变内容语义。

---

## 7. 一句话总结

> `DocumentBuilder` 是活对象模型的**轻量编排器**：每个方法都委托给 `Document`/`Paragraph`/`Table` 的构建块，
> 配置器 lambda 接收的就是活对象、可用全部样式 API。`build()` 返回的仍是同一个可变活对象文档。

---

## 下一步

- 想接 Agent 用「工具调用」的方式改文档 → [07 · nondocx-toolkit](./07-toolkit.md)
- 想了解构建出来的文档怎么校验 → [04 · 往返保真与内容相等性](./04-round-trip-and-equality.md)
- 想看可运行示例 → [`ComplexDocument.java`](../nondocx-examples/src/main/java/com/non/docx/examples/ComplexDocument.java)
