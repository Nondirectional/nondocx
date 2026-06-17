# nondocx

面向 Java 的流畅、领域友好的 **docx 读写库**，基于
[Apache POI](https://poi.apache.org/) 构建。

nondocx 将 POI 冗长的 `XWPF*` API 封装为直观的领域模型
（`Document`、`Paragraph`、`Run`、`Table`、`Section`…），让你用
几行代码就能读取、构建和修改 `.docx` 文件 —— 同时保留 `raw()` 逃生舱
在高级场景下直接操作底层 POI 对象。

> **状态：** 正在开发中（MVP）。API 尚不稳定，`1.0.0` 之前可能变更。

## 特性

- **流畅、可链式调用的活对象** — 原地修改文档：`run.text("Hi").bold()`
- **完整的读 *和* 写往返保真** — 文档、段落、run、表格、图片、
  超链接、列表、分节、页眉和页脚，均通过深度内容相等性测试验证
- **可变活对象 + 构建器轨道** — 既可编辑现有文档，也可用 `DocumentBuilder`
  从零构建
- **自包含的全 unchecked `DocxException` 层级** — `catch` 子句中永远不会
  泄露 `org.apache.poi.*` 异常
- **每个核心类型都提供 `raw()` 逃生舱** — 可下探到底层 `XWPF*` 对象，
  处理深度封装范围外的特性（修订、字段、OLE、公式…）
- **公开 API 零 POI 泄露** — POI 类型 *仅* 出现在 `raw()` 返回类型中
- **目标 JDK 11+**，已在 CI 上通过 11 / 17 / 21 验证
- **Apache License 2.0**
- **全中文编写** — 代码注释、Javadoc、异常消息均为中文

## 快速开始

### 打开、修改并保存

```java
import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import java.nio.file.Path;

// 打开现有文档并实时编辑。
try (Document doc = Docx.open(Path.of("input.docx"))) {
    // 编辑现有内容：流式修改器直接写入 POI。
    doc.paragraph(0).run(0).text("Hello, nondocx!").bold();

    // 追加带样式的新段落。
    doc.addParagraph().addRun("New paragraph").italic().color("FF0000");

    doc.save(Path.of("output.docx"));
}
```

### 从零构建

```java
import com.non.docx.core.api.Document;
import com.non.docx.core.api.style.HeadingLevel;
import com.non.docx.core.builder.DocumentBuilder;
import java.nio.file.Path;

// 声明式组装文档。配置器 lambda 接收活对象
// Paragraph / Table，因此可内联使用完整的 run / cell / 样式 API。
Document doc = DocumentBuilder.start()
    .heading(HeadingLevel.H1, "Quarterly Report")
    .paragraph(p -> p.addRun("Summary").bold().fontSize(14))
    .table(t -> t
        .row(r -> r.cell("Metric").cell("Value"))
        .row(r -> r.cell("Revenue").cell("$1.2M")))
    .build();

doc.save(Path.of("report.docx"));
```

`build()` 返回的 `Document` 与 `Docx.open` / `Docx.create` 获取的是同一种
活的、可变的文档 —— 可继续修改，或用完关闭。

### 使用逃生舱

对于深度封装范围之外的 docx 特性，使用 `raw()`：

```java
import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

try (Document doc = Docx.open(Path.of("in.docx"))) {
    XWPFDocument raw = doc.raw(); // 底层 POI 对象，谨慎修改
    // ... 使用 nondocx 未封装的任意 POI 能力 ...
}
```

> 通过 `raw()` 路径抛出的 POI 异常原样传播 —— `raw()` 是 POI 的领地。

## Maven 坐标

```xml
<dependency>
    <groupId>com.non</groupId>
    <artifactId>nondocx-core</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

Apache POI 被传递引入（`compile` 作用域）；消费方无需额外配置。

## 要求

- **Java 11 或以上**
- 兼容 Maven 的构建（项目以 Maven 构件形式发布）

## 许可

依据 [Apache License, Version 2.0](./LICENSE) 授权。
