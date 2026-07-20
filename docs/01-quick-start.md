# 01 · 快速开始

5 分钟打开、改、存一份 `.docx`。读完你会用 nondocx 完成最常见的三件事：**改现有文档**、**从零建文档**、**用逃生舱碰 POI**。

> 还没装？往下看 Maven 坐标。想先理解 nondocx 为什么是「活对象」？先去 [02 · 架构与核心契约](./02-architecture.md) 再回来。

---

## 1. 装上它

**要求**：Java 11+，兼容 Maven 的构建。

```xml
<dependency>
  <groupId>io.github.nondirectional</groupId>
  <artifactId>nondocx-core</artifactId>
  <version>0.0.1-SNAPSHOT</version>
</dependency>
```

Apache POI 被传递引入（`compile` 作用域），消费方无需额外配置。

> 状态：正在开发中（MVP），API 在 `1.0.0` 之前可能变更。

---

## 2. 三件事

### 2.1 打开、修改并保存

入口只有一个工厂类 [`Docx`](../nondocx-core/src/main/java/io/github/nondirectional/docx/core/Docx.java)。`Docx.open(...)` 返回一个 `Document` —— 它是 `AutoCloseable`，用 try-with-resources 包住。

```java
import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import java.nio.file.Path;

try (Document doc = Docx.open(Path.of("input.docx"))) {
    // 改第 0 段第 0 个 run 的文本，顺手加粗
    doc.paragraph(0).run(0).text("Hello, nondocx!").bold();

    // 末尾追加一段带样式的文字
    doc.addParagraph().addRun("New paragraph").italic().color("FF0000");

    doc.save(Path.of("output.docx"));
}
```

**这里发生了什么（三层递进）**：

| 层 | 对应物 |
|---|---|
| OOXML | 你改的 run，就是 `word/document.xml` 里的一个 `<w:r>` 元素；`.text("...")` 改它内部的 `<w:t>`，`.bold()` 在它的 `<w:rPr>` 里塞一个 `<w:b/>` |
| POI | `Run` 持有 `XWPFRun` 委托，`.text()`/`.bold()` 直接转发给委托 |
| nondocx | `Run` 返回 `this`，所以能 `.text("x").bold()` 链式；且 POI 异常被包装成 `Docx*Exception`，公开表面零 POI 泄露 |

> 改完**立即生效** —— `Document`/`Paragraph`/`Run` 都是活对象，写穿透到底层 POI，没有缓存快照。这是 nondocx 的核心契约，详见 [02 · 架构](./02-architecture.md)。

### 2.2 从零构建

两种姿势：

**(a) 活对象 + 链式**（改现有文档时最自然）：

```java
try (Document doc = Docx.create()) {
    doc.addParagraph("标题演示").heading(HeadingLevel.H1);
    doc.addParagraph("居中段落").alignment(Alignment.CENTER);

    Paragraph p = doc.addParagraph();
    p.addRun("普通文本 ");
    p.addRun("粗体").bold();
    p.addRun(" 斜体").italic();

    doc.save(Path.of("formatting.docx"));
}
```

> 取自 [`FormattingDemo.java`](../nondocx-examples/src/main/java/io/github/nondirectional/docx/examples/FormattingDemo.java)，可直接运行。

**(b) 声明式 builder**（从零组装一份结构化文档，配置器 lambda 风格）：

```java
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.style.HeadingLevel;
import io.github.nondirectional.docx.core.builder.DocumentBuilder;

Document doc = DocumentBuilder.start()
    .heading(HeadingLevel.H1, "Quarterly Report")
    .paragraph(p -> p.addRun("Summary").bold().fontSize(14))
    .table(t -> t
        .row(r -> r.cell("Metric").cell("Value"))
        .row(r -> r.cell("Revenue").cell("$1.2M")))
    .build();

doc.save(Path.of("report.docx"));
```

`build()` 返回的还是一个**活** `Document` —— 与 `Docx.open` / `Docx.create` 同一种对象，可继续改、可关闭。两条轨道的区别和取舍见 [06 · 构建器轨道](./06-builder-track.md)。

### 2.3 用逃生舱碰 POI（处理 nondocx 没封装的特性）

docx 的特性面极大（域、OLE、公式、形状、水印 ……）。nondocx 的 MVP 只深包了核心结构（段落/run/表格/图片/超链接/列表/分节/页眉页脚/修订/TOC），其余特性通过 `raw()` 下探到底层 POI：

```java
import org.apache.poi.xwpf.usermodel.XWPFDocument;

try (Document doc = Docx.open(Path.of("in.docx"))) {
    XWPFDocument raw = doc.raw(); // 底层 POI 对象，谨慎改
    // ... 用任意 POI 能力 ...
}
```

**`raw()` 的领地规则**（重要）：

- 同一个包装器生命周期内，`raw()` 返回**同一个** POI 实例（不拷贝、不重建）。
- 通过 `raw()` 路径抛出的 POI 异常**原样传播**，不会被包装成 `Docx*Exception`。你选了 `raw()`，就是选了 POI 的行为。
- `raw()` 是公开表面**唯一**会出现 `org.apache.poi.*` 类型的地方。

详见 [08 · 异常与 raw 领地](./08-exceptions-and-raw.md)。

---

## 3. 一个稍完整的例子：页眉页脚

```java
import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;

try (Document doc = Docx.create()) {
    // 首次创建默认页眉/页脚时，若当前节还没有显式页面设置，
    // nondocx 会补一个兼容性最小值：A4 + 四边 1 英寸边距（WPS 友好）。
    doc.ensureHeader().addParagraph().addRun("nondocx 示例文档");
    doc.ensureFooter().addParagraph().addRun("第 1 页");

    doc.save(Path.of("header-footer.docx"));
}
```

> 取自 [`HeaderFooterExample.java`](../nondocx-examples/src/main/java/io/github/nondirectional/docx/examples/HeaderFooterExample.java)。

两个细节：

- **读 / 写分离**：`header()` / `footer()` 是**只读**的，不存在时返回 `null`、绝不创建；`ensureHeader()` / `ensureFooter()` 才是**创建**路径。这样只读遍历（搜索、比较）不会污染文档。
- **WPS 兼容兜底**：WPS 对「有页眉页脚引用但缺页面几何设置」的 `sectPr` 比较敏感；nondocx 在**首次创建**页眉页脚且该节缺页面设置时补 A4+1inch。补缺不覆盖 —— 你显式设过 `paperSize(...)`/`margins(...)` 就不动。详见 [09 · FAQ](./09-faq-and-boundaries.md)。

---

## 4. 下一步

- 想知道**为什么 `run.bold()` 改完不用 `save` 就生效**、**为什么 save→reopen 还能比较相等** → [02 · 架构与核心契约](./02-architecture.md)
- 想查「某类型有哪些方法」→ [03 · API 速查](./03-api-reference.md)
- 要做修订（tracked changes）→ [05 · tracked changes 教程](./05-tracked-changes/README.md)
- 要接 LLM Agent → [07 · nondocx-toolkit](./07-toolkit.md)
