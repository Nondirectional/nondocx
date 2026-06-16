# nondocx-core MVP — 技术设计 (design.md)

> 配合 `prd.md` 阅读。本文聚焦技术架构、契约、数据流与权衡，不包含执行清单（见 `implement.md`）。

## 1. 架构总览

```
┌─────────────────────────────────────────────────────────┐
│  用户代码                                                  │
└────────────────────────┬────────────────────────────────┘
                         │ 公开 API (api / builder 包)
┌────────────────────────▼────────────────────────────────┐
│  nondocx-core                                            │
│  ┌──────────────────────┐   ┌──────────────────────────┐│
│  │  api  (领域模型)      │   │  builder (构造轨)         ││
│  │  Document/Paragraph..│   │  DocumentBuilder/...     ││
│  └──────────┬───────────┘   └──────────────────────────┘│
│             │ 持有 XWPF* delegate                          │
│  ┌──────────▼───────────────────────────────────────────┐│
│  │  internal (POI 桥接 / 样式映射 / util)  ← 命名约定隔离  ││
│  └──────────┬───────────────────────────────────────────┘│
│             │ raw() 逃生舱: 用户可直达                     │
└─────────────┼───────────────────────────────────────────┘
              ▼
   Apache POI 5.2.5 (XWPFDocument / XWPFParagraph / ...)
```

三大核心决策（详见 `prd.md` 推导）：

1. **混合封装 (Hybrid)** — 核心 90% 路径深封装，边角能力走 `raw()` 逃生舱
2. **持有式 wrapper (A)** — 每个核心类型持有对应 `XWPF*` delegate，方法直接代理，无缓存层
3. **双轨 API (C)** — 读改用可变 Live Object；从零构造用 Builder/工厂链

## 2. Maven 模块结构

```
nondocx/
├── pom.xml                          # 父 POM: packaging=pom
├── nondocx-core/
│   ├── pom.xml                      # 继承父 POM
│   └── src/{main,test}/java/...
├── LICENSE                          # Apache 2.0
├── README.md                        # 英文
└── .github/workflows/ci.yml         # JDK 矩阵 [11,17,21]
```

### 2.1 父 POM 职责

- `groupId=com.non`，`artifactId=nondocx-parent`，`packaging=pom`
- `<properties>`：
  - `<maven.compiler.release>11</maven.compiler.release>`
  - `<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>`
  - `<poi.version>5.2.5</poi.version>` 及各插件版本
- `<dependencyManagement>`：集中管理 POI / JUnit5 / AssertJ / Spotless 及 POI 传递依赖（`commons-io`、`xmlbeans`、`commons-compress`、`log4j-api`），子模块只引 groupId:artifactId 不写版本
- `<build><pluginManagement>`：固定 `maven-compiler-plugin`、`maven-surefire-plugin`、`maven-source-plugin`、`maven-javadoc-plugin`、`spotless-maven-plugin` 版本
- `<scm>` / `<url>` / `<licenses>` / `<developers>` 元数据（指向 GitHub `Nondirectional/nondocx`）

### 2.2 nondocx-core POM 职责

- `artifactId=nondocx-core`，继承 `nondocx-parent`
- `dependencies`：
  - `org.apache.poi:poi` + `org.apache.poi:poi-ooxml`（scope=compile）
  - `org.junit.jupiter:junit-jupiter`（test）
  - `org.assertj:assertj-core`（test）

## 3. Java 包布局

基包 `com.non.docx.core`（含模块名 `core`，为未来多模块隔离预留）。

```
com.non.docx.core
├── Docx.java                    ← 静态工厂门面 (open/create，不持有状态)
├── api/                         ← 公开领域模型 (核心深封区)
│   ├── BodyElement.java         (正文块顺序抽象)
│   ├── InlineElement.java       (段内顺序抽象)
│   ├── Document.java            (持有 XWPFDocument, implements AutoCloseable)
│   ├── text/
│   │   ├── Paragraph.java       (持有 XWPFParagraph, implements BodyElement)
│   │   ├── Run.java             (持有 XWPFRun, implements InlineElement)
│   │   └── Hyperlink.java       (持有 XWPFHyperlinkRun, implements InlineElement)
│   ├── table/
│   │   ├── Table.java           (持有 XWPFTable, implements BodyElement)
│   │   ├── Row.java             (持有 XWPFTableRow)
│   │   └── Cell.java            (持有 XWPFTableCell)
│   ├── section/
│   │   ├── Section.java         (页面属性 + section-scoped header/footer)
│   │   ├── PaperSize.java       (A4 / Letter / ...)
│   │   └── Orientation.java     (PORTRAIT / LANDSCAPE)
│   ├── image/
│   │   ├── Image.java           (内联图片, implements InlineElement)
│   │   └── ImageType.java       (PNG/JPEG/GIF/...)
│   ├── header/
│   │   ├── Header.java
│   │   └── Footer.java
│   ├── style/                   ← 样式值对象 (对齐/缩进/字体等)
│   │   ├── Alignment.java       (枚举)
│   │   ├── HeadingLevel.java    (H1–H6 枚举)
│   │   ├── ListKind.java        (BULLET / NUMBERED)
│   │   └── RunStyle.java
│   └── exception/               ← 公开异常 (用户需 catch)
│       ├── DocxException.java
│       ├── DocxIOException.java
│       ├── DocxFormatException.java
│       ├── DocxOperationException.java
│       └── UnsupportedFeatureException.java
├── builder/                     ← 双轨的构造轨
│   ├── DocumentBuilder.java
│   ├── TableBuilder.java
│   └── ParagraphBuilder.java
├── internal/                    ← 实现细节 (命名约定 + Javadoc + 最小可见性)
│   ├── poi/                     POI 类型 ↔ 核心类型桥接
│   ├── style/                   样式枚举映射 (WdAlign / STXXX ↔ style)
│   └── util/                    XmlBeans / 反射 / 资源清理辅助
└── (spi/ 预留为空 — 未来扩展点)
```

### 3.1 顺序模型：`BodyElement` / `InlineElement`

- `Document.bodyElements()` 是正文结构真相源，按 Word body 顺序返回 `Paragraph` / `Table`
- `Paragraph` / `Table` 实现 `BodyElement`
- `Paragraph.inlineElements()` 是段内结构真相源，按顺序返回 `Run` / `Hyperlink` / `Image`
- `Run` / `Hyperlink` / `Image` 实现 `InlineElement`
- `paragraphs()` / `tables()` / `runs()` 保留为类型过滤视图，便于常见场景；round-trip equality 以顺序视图为准

## 4. API 设计

### 4.1 入口门面 `Docx`

静态工厂，不持有文档状态。IO 全覆盖重载：

```java
public final class Docx {
    // 读
    public static Document open(File file);
    public static Document open(Path path);
    public static Document open(InputStream in);

    // 从零创建空文档
    public static Document create();
}
```

流所有权契约：

- `open(InputStream)` 不关闭调用方传入的流；内部先缓冲/复制内容，再创建 `Document`
- `save(OutputStream)` 不关闭调用方传入的流
- `Document.close()` 负责释放底层 `XWPFDocument` / `OPCPackage` 资源

### 4.2 领域对象 `Document`（可变 Live Object）

持有 `XWPFDocument delegate`，承载内容操作、顺序视图与保存：

```java
public final class Document implements AutoCloseable {
    private final XWPFDocument delegate;

    // 正文顺序视图（结构真相源）
    public List<BodyElement> bodyElements();
    public BodyElement bodyElement(int i);

    // 类型过滤视图 + 增删改
    public List<Paragraph> paragraphs();
    public Paragraph paragraph(int i);
    public Paragraph addParagraph();
    public Paragraph addParagraph(String text);   // 便捷重载
    public Paragraph insertParagraph(int bodyIndex);
    public void removeParagraph(int paragraphIndex);

    // 表格
    public List<Table> tables();
    public Table addTable();

    // 分节 / 页面属性 / 分节级页眉页脚
    public List<Section> sections();
    public Section section(int i);

    // 便捷访问：等价于 section(0).header()/footer()
    public Header header();
    public Footer footer();

    // 保存 (放 Document，因持有底层 XWPFDocument)
    public void save(File file);
    public void save(Path path);
    public void save(OutputStream out);

    // 生命周期
    @Override
    public void close();

    // 逃生舱
    public XWPFDocument raw();
}
```

约定：

- `bodyElements()` 保留段落 / 表格的真实顺序，是结构比较与 round-trip equality 的真相源
- `paragraphs()` / `tables()` 是类型过滤视图；`paragraph(int)` 与 `removeParagraph(int)` 作用于过滤后的段落索引
- `insertParagraph(int bodyIndex)` 作用于正文 body 顺序索引，避免表格与段落混排时语义歧义
- 存在多个 section 时，优先使用 `Section.header()` / `Section.footer()`；`Document.header()` / `footer()` 仅是首个 section 的便捷代理

### 4.3 `Paragraph` / `Run` / `Hyperlink` / `Image`

`Paragraph` / `Run` 等采用 **返回 `this` 的链式 mutator**（而非 `setXxx`）。段内内容以 `InlineElement` 顺序视图为准：

```java
public final class Paragraph implements BodyElement {
    public List<InlineElement> inlineElements();
    public InlineElement inlineElement(int i);

    // 便捷过滤视图
    public List<Run> runs();
    public Run run(int i);

    // 段内新增元素
    public Run addRun();
    public Run addRun(String text);
    public Hyperlink addHyperlink(String text, String url);
    public Image addImage(byte[] bytes, ImageType type, int width, int height);
    public void removeInlineElement(int i);

    // 段落级样式
    public Paragraph heading(HeadingLevel level);
    public Paragraph alignment(Alignment alignment);
    public Paragraph indent(int leftTwips, int firstLineTwips);
    public Paragraph lineSpacing(double multiple);
    public Paragraph list(ListKind kind, int level);   // 0-based nesting
    public Paragraph clearList();

    public XWPFParagraph raw();
}

public final class Run implements InlineElement {
    public Run text(String t);        // 返回 this
    public Run bold();
    public Run italic();
    public Run underline();
    public Run fontSize(int pt);
    public Run font(String name);
    public Run color(String hex);     // e.g. "FF0000"
    public String text();
    public boolean isBold();
    // ... getter + raw()
}
```

`Hyperlink` 与 `Image` 也实现 `InlineElement`，分别暴露 URL/text 与图片元信息读写；`runs()` 仅作为文本 run 的过滤视图，顺序比较以 `inlineElements()` 为准。

### 4.4 `Section` / `Header` / `Footer`

`Section` 表示一个 Word 分节，既承载页面属性，也承载 **section-scoped** 的页眉页脚：

```java
public final class Section {
    public Section paperSize(PaperSize size);
    public Section orientation(Orientation orientation);
    public Section margins(int topTwips, int rightTwips, int bottomTwips, int leftTwips);

    public Header header();
    public Footer footer();

    public CTSectPr raw();
}
```

约定：

- `Document.sections()` 返回按文档顺序排列的 section live view
- 多 section 场景下，页眉/页脚的内容相等比较以 section 维度进行，而非全局单例
- `Header` / `Footer` MVP 暴露 paragraph-oriented API；更复杂的表格/绘图场景走 `raw()` 逃生舱

### 4.5 构造轨 Builder

从零快速构造，与可变对象解耦：

```java
Document doc = DocumentBuilder.start()
    .heading(HeadingLevel.H1, "标题")
    .paragraph(p -> p.text("正文").bold())
    .table(t -> t.row(r -> r.cell("A1").cell("B1")))
    .build();
```

### 4.6 逃生舱 `raw()`

所有核心类型提供 `raw()`，返回持有的 `XWPF*` delegate（**同一实例**，修改立即反映）。契约：

- 公开方法签名**不出现** `XWPF*` 类型（零泄漏给常规用户）
- 仅 `raw()` 返回值允许出现 POI 类型
- `raw()` 路径上 POI 抛出的异常**原样透传**（不包装），因那是 POI 地盘
- Javadoc 警告：「Modifications to the returned object affect the document immediately. Use with caution.」

## 5. 异常模型（全 unchecked + 全自建）

```
RuntimeException
└── com.non.docx.core.api.exception.DocxException        ← 根异常
    ├── DocxIOException          (包装 IOException / POI OpenXML4J / POIXML)
    │     └── cause 保留原始异常，getCause() 可取
    ├── DocxFormatException      (docx 损坏 / 格式非法，带文件路径)
    ├── DocxOperationException   (领域操作失败，带上下文如段落索引 / body 位置)
    │     └── 参数与索引错误优先复用 JDK `IllegalArgumentException` / `IndexOutOfBoundsException`
    └── UnsupportedFeatureException  (功能不在封装范围 → 提示用 raw())
```

规则：
- 全部继承 `RuntimeException`，用户不强制 catch
- 除 `raw()` 逃生舱外，POI 异常包装为自建类型，用户无需 import `org.apache.poi.*` 异常
- 参数错误与索引错误优先复用 JDK 标准异常；文档 IO / 格式 / 领域操作失败走 `DocxException` 体系
- 异常消息**英文**
- 异常携带文档上下文（文件路径 / 元素索引）便于定位

## 6. internal 包隔离（手段 1）

- 仅靠 `com.non.docx.core.internal.*` 包名 + Javadoc 标注隔离
- `internal` 下所有类 Javadoc 首行：`Internal API — subject to change without notice.`
- 能用 `package-private` 就不加 `public`（最小可见性）
- JPMS `module-info.java` 推迟到 v1.0 前（当前不引入，避免 POI JPMS 集成摩擦）

## 7. 往返保真测试策略

**核心验收手段：深粒度领域对象 `equals`**。

- 核心类型（`Paragraph` / `Run` / `Table` / `RunStyle` 等）实现**内容相等** `equals/hashCode`：仅比较内容字段（文本、样式、结构），**不含** `delegate` 引用
- 内容相等覆盖 `bodyElements()` / `inlineElements()` 的顺序、列表属性、section 页面属性，以及 section-scoped header/footer 内容
- 这些对象同时是 **mutable live view**；`equals/hashCode` 主要服务于比较与测试，不建议作为长期 `HashMap` / `HashSet` key 使用
- 往返测试：

```java
Document original = ...; // 构造复杂文档
File f = tempFile();
original.save(f);
Document readBack = Docx.open(f);
assertThat(readBack).isEqualTo(original);   // 深粒度内容相等
```

**测试数据来源**：
- 主力：`src/test/resources/ooxml/` 放手写 OOXML 源码模板（document.xml 等），测试时用 `internal/TestDocxPackager` 打包成 .docx（透明、可 review、体积小、可精确构造边角场景）
- 少量：`src/test/resources/fixtures/` 放真实 Word/WPS 生成固件（烟雾测试真实兼容性）
- POI 交叉参照：测试作用域直接用 `XWPFDocument` 原生读取，断言我们读出的文本 == POI 原生读出（补「自己测自己」盲点）

## 8. Java 兼容性

- 编译：`<maven.compiler.release>11</maven.compiler.release>`（用 `--release` 而非 source/target，杜绝链接高版本 API 导致运行期 `NoSuchMethodError`）
- 产物：字节码版本 55 (Java 11)，天然兼容 11/17/21+
- 验证：CI 矩阵 `[11, 17, 21]` × `mvn verify`，兼容性持续验证
- 构建 JDK：本地/CI 用 JDK 17 LTS 编译（`--release 11` 保证产物兼容 11）

## 9. 横切约定

| 维度 | 约定 |
|------|------|
| 语言 | 对外文档（README/Javadoc/注释/异常消息）**全英文**；Trellis 任务文档（本文）中文 |
| Lombok | 不使用 |
| 样板 | 核心类型手写代理 + 手写内容相等 equals/hashCode；internal DTO 用 IDE 生成 |
| 格式化 | Spotless，`spotless:check` 进 CI |
| 日志 | MVP 不引日志框架（POI 5.x 依赖 log4j-api 仅为其内部）；库内部暂用异常承载信息 |

## 10. 关键数据流示例

**读取并修改**：
```java
Document doc = Docx.open(Paths.get("in.docx"));   // Docx 门面 → Document
doc.bodyElements().get(0);                          // 正文顺序视图（结构真相源）
doc.paragraph(0).run(0).text();                    // Document → Paragraph → Run (过滤视图代理 delegate)
doc.addParagraph("新增段落").runs().get(0).bold(); // 可变 Live Object，改底层 XWPFDocument
doc.save(Paths.get("out.docx"));                   // Document 持有 XWPFDocument，直接写
```

**逃生舱**（高级特性，封装未覆盖）：
```java
Document doc = Docx.open(f);
XWPFDocument raw = doc.raw();   // 直达 POI，处理域代码/修订等第 4 档特性
// 此处 POI 异常原样透传
```

## 11. 权衡记录 (Tradeoffs)

| 决策 | 选择 | 放弃的替代 | 理由 |
|------|------|-----------|------|
| 封装深度 | 混合 | 纯深封 | 纯深封对修订/域代码等边角功能工作量过载且易漏 |
| POI 集成 | 持有式 wrapper | 缓存+sync / 值对象拷贝 | 缓存引入一致性问题；值对象违背可变 Live Object 语义 |
| API 可变性 | 可变 Live Object 为主 | 纯不可变 | docx 本质有状态，不可变让「改一个字」变繁琐且保真难 |
| 异常 | 全 unchecked | checked | 现代 Java 库共识，Lambda/Stream 友好 |
| internal 隔离 | 命名约定 | JPMS | JPMS 在 classpath 模式消费者不生效，POI 集成有摩擦 |
| equals | 内容相等排除 delegate | @Data 含 delegate | 含 delegate 会导致往返测试（不同实例）必然失败 |
