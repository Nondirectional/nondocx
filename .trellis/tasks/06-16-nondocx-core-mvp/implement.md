# nondocx-core MVP — 执行清单 (implement.md)

> 基于 `prd.md`（需求）与 `design.md`（技术设计）。按顺序执行，每个 Phase 末尾有验证命令与 review gate。

## 执行约定

- **语言**：所有对外产物（README / Javadoc / 代码注释 / 异常消息 / commit 也可英文）全英文；本清单中文
- **不使用 Lombok**：核心类型手写代理 + 手写内容相等 equals/hashCode
- **commit 粒度**：每个 Phase 一次提交（或 Phase 内逻辑分组），commit message 用 `feat:` / `chore:` / `docs:` 前缀
- **验证基线**：每阶段至少 `mvn -q -pl nondocx-core compile test-compile` 通过；功能阶段需对应测试通过

---

## Phase 0 · 项目脚手架

- [ ] 创建根目录 `.gitignore`（Maven / Java / IDE）
- [ ] `git init` + 初始提交（仅 `.gitignore`）
- [ ] 根 `pom.xml`（父 POM）：`groupId=com.non`、`artifactId=nondocx-parent`、`packaging=pom`、`<modules><module>nondocx-core</module></modules>`
- [ ] 父 POM `<properties>`：`maven.compiler.release=11`、`sourceEncoding=UTF-8`、`poi.version=5.2.5`、各插件版本
- [ ] 父 POM `<dependencyManagement>`：poi / poi-ooxml / junit-jupiter / assertj-core + POI 传递依赖（commons-io / xmlbeans / commons-compress / log4j-api）
- [ ] 父 POM `<build><pluginManagement>`：maven-compiler-plugin / surefire / source / javadoc / spotless 固定版本
- [ ] 父 POM 元数据：`<scm>` (github.com/Nondirectional/nondocx) / `<url>` / `<licenses>` (Apache 2.0) / `<developers>`
- [ ] `nondocx-core/pom.xml`：继承父 POM，声明 poi+poi-ooxml (compile) / junit-jupiter (test) / assertj-core (test)
- [ ] 创建 Java 基包目录 `nondocx-core/src/main/java/com/non/docx/core/`
- [ ] 根目录 `LICENSE`（Apache 2.0 全文）
- [ ] 根目录 `README.md`（英文，简介 + 快速开始占位 + 依赖坐标）

**验证**：`mvn -q validate`（根目录），`mvn -q -pl nondocx-core compile`（空 main 也能过）

**Review Gate 0**：确认脚手架结构与坐标无误。

---

## Phase 1 · 异常体系 + style 值对象（无外部依赖，最先落地）

- [ ] `api/exception/DocxException.java`（根，继承 RuntimeException，带 message/cause 构造）
- [ ] `api/exception/DocxIOException.java`（包装 IOException / POI IO 异常）
- [ ] `api/exception/DocxFormatException.java`（带文件路径字段）
- [ ] `api/exception/DocxOperationException.java`（带上下文字段）+ 子类 `NoSuchElementException` / `IllegalArgumentException`（复用 JDK 或自建，自建避免歧义则放 exception 包）
- [ ] `api/exception/UnsupportedFeatureException.java`
- [ ] 所有异常 Javadoc 首行英文说明，消息模板英文
- [ ] `api/style/Alignment.java`（枚举：LEFT/CENTER/RIGHT/JUSTIFY，映射 POI ParagraphAlignment）
- [ ] `api/style/HeadingLevel.java`（H1–H6 枚举）
- [ ] `api/style/RunStyle.java`（内联样式值对象：bold/italic/underline/font/size/color，实现内容相等 equals/hashCode）

**验证**：`mvn -q -pl nondocx-core test-compile`；为 RunStyle 写 `RunStyleTest`（equals/hashCode）。

**Rollback Point**：本 Phase 纯新增无依赖类，失败可直接删除重写。

---

## Phase 2 · core 基础设施 + Document/Docx 入口（第 1 档骨架）

- [ ] `internal/util/`：`Streams.java`（InputStream→byte[] / close quiet）、`Objects.java`（requireNonNull 带上下文消息）—— Javadoc 标 `Internal API`
- [ ] `internal/poi/`：`Mappers.java` 占位（ParagraphAlignment ↔ Alignment 等映射，随各类型填充）
- [ ] `api/Document.java`：持有 `final XWPFDocument delegate`，实现 paragraphs()/paragraph(i)/addParagraph()/addParagraph(text)/insertParagraph(i)/removeParagraph(i)/tables()/addTable()/save(File/Path/OutputStream)/raw()
- [ ] `Docx.java`：静态工厂 open(File/Path/InputStream) + create()，open 失败抛 DocxIOException/DocxFormatException
- [ ] save() 内部 try/catch POI IO 异常包装为 DocxIOException

**验证**：写 `DocumentOpenSaveTest`：`Docx.create()` → `save(tmp)` → `Docx.open(tmp)` 不抛异常，round-trip 基本通路。

**Review Gate 2**：确认 Document/Docx 入口签名与 design.md §4.1/§4.2 一致。

---

## Phase 3 · text 包（Paragraph / Run / Hyperlink）— 第 1+2 档

- [ ] `api/text/Run.java`：持有 `XWPFRun`，链式 mutator（text/bold/italic/underline/fontSize/font/color 返回 this）+ getter + 内容相等 equals/hashCode + raw()
- [ ] `api/text/Paragraph.java`：持有 `XWPFParagraph`，runs()/run(i)/addRun()/addRun(text)/removeRun(i) + 段落样式（style/alignment/indent/heading via HeadingLevel）链式 + raw() + 内容相等
- [ ] `api/text/Hyperlink.java`：持有 `XWPFHyperlinkRun`，url/text + raw()
- [ ] Document.paragraphs()/tables() 返回的 List 是包装视图（每次 get 现场包装 `XWPFParagraph`→`Paragraph`）

**验证**：
- `RunTest`：mutator 链生效，round-trip 读写样式（bold/size/font/color）
- `ParagraphTest`：增删 run、对齐、标题样式 round-trip
- POI 交叉参照：用 `XWPFDocument` 原生读，断言文本一致

---

## Phase 4 · table 包 — 第 1 档

- [ ] `api/table/Table.java`：持有 `XWPFTable`，rows()/row(i)/addRow()/removeRow(i) + raw() + 内容相等
- [ ] `api/table/Row.java`：持有 `XWPFTableRow`，cells()/cell(i)/addCell()/removeCell(i) + raw()
- [ ] `api/table/Cell.java`：持有 `XWPFTableCell`，paragraphs()/addParagraph()/text()/setText() + raw()
- [ ] Document.tables()/addTable() 接通

**验证**：`TableTest` round-trip（含多行多列、单元格文本），POI 交叉参照。

---

## Phase 5 · section + image + header/footer — 第 2+3 档

- [ ] `api/section/Section.java`：页面属性（纸张大小 PaperSize 枚举 A4/Letter、页边距、横竖向 Orientation 枚举）+ raw()（映射 CT_SectPr）
- [ ] `api/image/Image.java`：插入（addPicture bytes/type/width/height）+ 读取元信息 + raw()
- [ ] `api/header/Header.java` + `Footer.java`：持有 `XWPFHeader`/`XWPFFooter`，复用 Paragraph 模型 + raw()
- [ ] Document.sections()/section(i)/header()/footer() 接通

**验证**：`SectionTest`（页面属性 round-trip）、`ImageTest`（插入图片 round-trip，断言图片存在）、`HeaderFooterTest`。

---

## Phase 6 · builder 包（构造轨）

- [ ] `builder/ParagraphBuilder.java`：lambda 风格 `.text().bold()`
- [ ] `builder/TableBuilder.java`：`.row(r -> r.cell().cell())`
- [ ] `builder/DocumentBuilder.java`：`.start().heading().paragraph(fn).table(fn).build()` → 产出 Document
- [ ] 确保 Builder 产出的 Document 与可变 Live Object 体系一致（底层即 XWPFDocument）

**验证**：`DocumentBuilderTest`：用 Builder 构造与手写 addParagraph 等价文档，equals 相等。

---

## Phase 7 · 测试基础设施 + 往返保真总测

- [ ] `src/test/resources/ooxml/`：手写 OOXML 模板（plain.xml / styled.xml / table.xml / list.xml / section-header.xml）
- [ ] `internal/TestDocxPackager`（test 作用域，打包 OOXML 模板 → .docx）
- [ ] `src/test/resources/fixtures/`：放 1-2 个真实 Word 固件（标注来源）
- [ ] `RoundTripTest`：构造完整文档（标题+段落+表格+图片+列表+分节+页眉页脚）→ save → open → `assertThat(readBack).isEqualTo(original)` 深粒度相等
- [ ] `PoiCrossReferenceTest`：POI 原生读取同一文件，断言文本一致

**验证**：`mvn -q -pl nondocx-core test` 全绿。这是验收标准核心。

**Review Gate 7**：往返保真通过 = MVP 核心验收达成。暂停确认。

---

## Phase 8 · 代码质量 + CI

- [ ] 配置 `spotless-maven-plugin`（palantir-java-format 或 google-java-format，固定版本）绑 verify
- [ ] 根 `.github/workflows/ci.yml`：matrix `jdk: [11, 17, 21]` × `mvn verify`
- [ ] 运行 `mvn spotless:apply` 统一格式，再 `mvn -q verify` 确认全绿
- [ ] README 补全：特性列表 + 完整快速开始示例（open/modify/save + builder）+ 依赖坐标 + License

**验证**：本地切 JDK 11 跑 `mvn -q verify`（或本地仅 17，靠 CI 矩阵覆盖 11/21）。

**Review Gate 8**：CI 矩阵设计确认，准备合并 main。

---

## 完成定义 (Definition of Done)

对照 `prd.md` 验收标准逐条勾选：
- [ ] 往返保真深粒度 equals 通过（RoundTripTest 绿）
- [ ] 第 1/2 档 + 第 3 档（页面属性+页眉页脚）读写闭环
- [ ] 所有核心类型提供 raw()
- [ ] 异常为自建 DocxException 体系，零 POI 异常泄漏
- [ ] `mvn verify` 在 JDK 11/17/21 矩阵全绿
- [ ] `spotless:check` 通过
- [ ] 父 POM + nondocx-core + LICENSE + README 齐全

全部勾选后，进入 Phase 3（Trellis）：spec 更新 → commit → `/trellis:finish-work`。

---

## 风险与回退点

| 风险 | 触发 | 回退 |
|------|------|------|
| POI 某特性 API 不足 | Phase 3-5 发现 XWPF* 缺能力 | 降级为 `raw()` + `UnsupportedFeatureException` 提示，记入 design.md 权衡 |
| 往返保真失败（POI 写入自动补全/规范化字段） | Phase 7 equals 不等 | 排查差异字段；若属 POI 规范化副作用，在 equals 中排除该字段并记录原因 |
| 图片/页眉 round-trip 不稳 | Phase 5/7 | 这些字段在 equals 中标为 best-effort，README 注明已知限制 |
| `com.non` 无法发 Maven Central | 未来发布 | 不影响 MVP；发布单独任务改 groupId |

## 不在本任务实现

模板引擎 / 转换器（未来模块）、Maven Central 发布、Checkstyle/SpotBugs、TOC/元数据/脚注、JPMS —— 见 `prd.md` Out of Scope。
