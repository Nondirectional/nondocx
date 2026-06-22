# Design — 页码与通用简单域 API

> 子任务 `06-23-hf-variants-field` 的技术设计。
> 需求与验收见 `prd.md`；执行计划见 `implement.md`。

## 设计目标

为 `Paragraph` 补齐**简单域写入**能力，使页码、总页数等常见域可在正文 / 页眉 / 页脚里创建。不引入新的公共域类型 —— 域作为「3 个承载 fldChar/instrText 的普通 run 序列」存在，沿用现有 `Run` 抽象。

## OOXML / POI / nondocx 三层

### OOXML：域的三段 run 结构

一个简单域在 OOXML 里由**三个相邻 run** 组成：

```xml
<w:r><w:fldChar w:fldCharType="begin"/></w:r>     <!-- run 1：域开始 -->
<w:r><w:instrText> PAGE </w:instrText></w:r>       <!-- run 2：域指令 -->
<w:r><w:fldChar w:fldCharType="end"/></w:r>        <!-- run 3：域结束 -->
```

**关键点**：
- 域指令（`PAGE` / `NUMPAGES` / `DATE` 等）住在 `<w:instrText>`，**不**是普通可见文本 `<w:t>`。
- 域的**可见结果**（如"第 3 页"里的"3"）由 Word/WPS 打开时的分页 / 计算引擎渲染，POI 不计算 —— 本子任务只写指令结构，不写可见结果。
- **简单域 vs 完整域**：完整域在 begin 和 end 之间还有 `<w:fldChar separate>` + 缓存可见结果文本；简单域省略 separate，打开时由渲染引擎即时计算。本子任务只做**简单域**（我们无法计算可见结果，Word/WPS 打开简单域会自动填充）。

### POI：无高层 API，直接操纵 CTR

POI 没有 `XWPFField` / `addSimpleField` 这类方法。**实测确认**（非推断）：

- `CTR.addNewFldChar()` → 返回 `CTFldChar`，`CTFldChar.setFldCharType(STFldCharType.BEGIN/END)` —— 可用（lite schema 已含 `CTFldChar` / `STFldCharType`）
- `CTR.addNewInstrText().setStringValue(String)` —— 可用
- 现有先例：`TocFields.java` 读域、`TableOfContentsTest.java:200-211` 写域，都是这个手法

**与 `addPicture` / tracked-changes 路径同型**：POI 不暴露的结构，nondocx 直接操纵 CT 节点，对外只暴露干净 API。

### nondocx：入口放在 `Paragraph`，产出标准 3-run

**关键设计决策**（从初稿"Run.addSimpleField"修订而来 —— 见下方"决策推演"）：

- 入口方法 `addSimpleField(instruction)` 放在 **`Paragraph`** 上，与 `addHyperlink` / `addImage` 同模式（都是"创建新 inline 内容并返回它"）
- 产出 **3 个独立 run**（begin / instrText / end 各一个），与 Word 标准产出完全一致
- 返回承载 `instrText` 的**中间 run**（用户可对其链式设样式 —— 域的可见结果样式由 instrText run 决定）

## 决策推演：为什么入口在 `Paragraph` 而非 `Run`

初稿曾设计 `Run.addSimpleField(instruction)` —— "把当前 run 变成域"。推敲后否决，理由：

1. **与 Word 标准产出冲突**：Word 导出的简单域是 3 个相邻 run，不是"1 个 run 同时承载 fldChar+instrText"。强行塞进单 run 在 OOXML 里合法但偏离规范，Word/WPS 打开后常把它规范化回 3 run，引入不稳定。
2. **与 `Run` 返回 `this` 的链式惯例冲突**：`Run` 的所有 mutator（`text`/`bold`/`color`...）都返回 `this`；若 `addSimpleField` 要产出 3 run，它要么违反这个惯例（返回新建的 run），要么越权创建兄弟 run（那是 `Paragraph` 的职责）。
3. **与 `addHyperlink` / `addImage` 不对称**：那两个方法都在 `Paragraph` 上，创建新 inline 内容并返回它。域是同类东西（新的 inline 内容），理应同模式。

**最终决策**：入口在 `Paragraph.addSimpleField`，产出标准 3-run，返回中间的 instrText run。

## 公共 API 形态

### `Paragraph.addSimpleField(String instruction)` → `Run`

```java
/**
 * 在此段落末尾追加一个简单域（simple field），并返回承载域指令的 run。
 *
 * <p><b>OOXML</b>：一个简单域由三个相邻 run 组成 ——
 * {@code <w:fldChar begin>} / {@code <w:instrText>指令</w:instrText>} /
 * {@code <w:fldChar end>}。本方法在段落末尾追加这三个 run，承载给定指令。
 *
 * <p><b>POI</b>：没有 {@code XWPFField} 高层 API，本方法直接操纵
 * {@code CTR}（{@code addNewFldChar} + {@code addNewInstrText}）。
 *
 * <p><b>域的实际可见值</b>（如 {@code PAGE} 域显示的页码数字）由 Word/WPS
 * 打开时的渲染引擎计算，POI 与 nondocx 都不计算 —— 故本方法只写指令结构。
 * 简单域不带 {@code separate} 缓存段，打开时由渲染引擎即时填充。
 *
 * <p><b>返回的 run</b> 承载 {@code <w:instrText>}，可对其链式设样式
 * （域可见结果的样式由此 run 决定）。
 *
 * @param instruction 域指令（如 {@code "PAGE"}、{@code "NUMPAGES"}、
 *     {@code "DATE \\@ yyyy"}；不能为 {@code null} 或空白）
 * @return 承载域指令的 run
 * @throws IllegalArgumentException 如果 {@code instruction} 为 {@code null} 或空白
 */
public Run addSimpleField(String instruction) { ... }
```

### `Paragraph.addPageNumberField()` / `addPageCountField()` → `Run`

便捷方法，等价于 `addSimpleField("PAGE")` / `addSimpleField("NUMPAGES")`。Javadoc 指向 `addSimpleField` 并说明等价关系。

### 实现骨架

```java
public Run addSimpleField(String instruction) {
  Objects.requireNonNull(instruction, "instruction");
  if (instruction.isBlank()) {
    throw new IllegalArgumentException("instruction 不能为空白");
  }
  // run 1: begin
  XWPFRun beginRun = delegate.createRun();
  CTFldChar begin = beginRun.getCTR().addNewFldChar();
  begin.setFldCharType(STFldCharType.BEGIN);
  // run 2: instrText（返回这个）
  XWPFRun instrRun = delegate.createRun();
  instrRun.getCTR().addNewInstrText().setStringValue(instruction);
  // run 3: end
  XWPFRun endRun = delegate.createRun();
  CTFldChar end = endRun.getCTR().addNewFldChar();
  end.setFldCharType(STFldCharType.END);
  return new Run(instrRun);
}
```

约 12 行，无需下沉到 `internal/poi`。

## CT 操纵下沉位置

**决策：直接在 `Paragraph.addSimpleField` 内联操纵 CTR，不新建 `internal/poi/FieldNodes`**。

理由：
- 域的 CT 操纵只有 3 行（`addNewFldChar` × 2 + `addNewInstrText`），下沉到独立工具类是过度设计
- 与 `Run.text()` 直接操纵 CTR 清空 `<w:t>`（N9 手法）同型 —— 那里也是内联
- 若未来读侧域解析需要复用，再提取不迟（YAGNI）

**与 poi-bridge Rule 5 的关系**：`Paragraph` 内联操纵 CTR 不违反"枚举映射在 internal"规则（这里没有枚举映射，只有 CT 节点创建）。`STFldCharType` 是 POI 的 XmlBeans 枚举，在 `Paragraph` 内部使用不算"POI 类型泄漏到公开签名"（方法签名是 `Run addSimpleField(String)`，不暴露 `STFldCharType`）。

## 域在 `inlineElements()` 里的暴露（诚实记录）

按 `Paragraph.wrap()` 的现有逻辑（`Paragraph.java:729-740`）：一个 run 若非 hyperlink、无 embedded picture，会被包成 `Run`。域的 3 个 run 符合这个条件 → 会以**3 个空文本 `Run`** 出现在 `paragraph.inlineElements()` 里。

**这是 MVP 的诚实取舍**：
- 3 个域 run 的 `text()` 都返回空串（因为它们没有 `<w:t>`）
- 用户通过 `inlineElements()` 看到的是"3 个空 run"，而非"1 个 Field 对象"
- 若需要识别域，目前只能走 `raw().getCTR().getFldCharArray()` / `getInstrTextArray()`

**为什么不在本次引入 `Field` 公共类型**：
- 读侧（解析已有域）已在父任务 Out of Scope，引入 `Field` 类型会让读 / 写两侧的抽象不对称（写了能创建，但读不出）
- 域的完整建模（含 separate 缓存值、嵌套域、PAGEREF 子域等）是独立的大子任务（参考 TOC 的 N11 规模）
- 本次专注**写入入口**，域的读侧建模留给后续 —— 这是诚实的"写侧已覆盖，读侧走 raw()"边界

> **spec 落档**：此边界由 docs-spec 子任务记录为 poi-bridge 一条 Note（建议编号 N21：域写侧的三段 run 模式 + 在 inlineElements 里以空文本 Run 暴露的取舍）。

## 测试策略

### Round-trip（核心验收）

```java
@Test
void simpleFieldRoundTrips(@TempDir Path tmp) throws Exception {
  Path file = tmp.resolve("field.docx");
  Document original = Docx.create();
  original.addParagraph().addPageNumberField();
  original.save(file);

  try (Document opened = Docx.open(file)) {
    // 通过 raw 读 instrText（公开 API 不暴露域读侧）
    String instr = readInstrText(opened.paragraph(0));
    assertThat(instr).isEqualTo("PAGE");
  }
}
```

**注意**：round-trip 断言走 `raw()` 读 instrText，因为本次不引入读侧公开 API。

### 便捷方法等价性

```java
@Test
void pageNumberFieldEqualsAddSimpleFieldPage() {
  Document a = Docx.create();
  Document b = Docx.create();
  a.addParagraph().addPageNumberField();
  b.addParagraph().addSimpleField("PAGE");
  assertThat(readInstrText(a)).isEqualTo(readInstrText(b));
}
```

### 边界

```java
@Test
void rejectsBlankInstruction() {
  Paragraph p = Docx.create().addParagraph();
  assertThatThrownBy(() -> p.addSimpleField("  "))
      .isInstanceOf(IllegalArgumentException.class);
}

@Test
void rejectsNullInstruction() {
  Paragraph p = Docx.create().addParagraph();
  assertThatThrownBy(() -> p.addSimpleField(null))
      .isInstanceOf(IllegalArgumentException.class);
}
```

### 返回的 run 可链式设样式

```java
@Test
void returnedRunAcceptsStyle() {
  Run run = Docx.create().addParagraph().addPageNumberField().bold();
  assertThat(run.isBold()).isTrue();
}
```

### 回归（不破坏现有契约）

- `RunTest` / `ParagraphTest` / `RoundTripTest` / `InlineElementOrderTest` 全量绿
- 域的 3 个 run 出现在 `inlineElements()` 里但不破坏顺序契约

### 通用域指令覆盖

测试 `addSimpleField("NUMPAGES")`、`addSimpleField("DATE \\@ yyyy")` 等，确保指令原样写入、round-trip 存活。

## 风险与边界

| 风险 | 缓解 |
|---|---|
| 3-run 形态在 WPS round-trip 后被改动 | 采用 Word 标准产出形态，WPS 理应兼容；实测 round-trip 验证 |
| `instrText` 指令带前导/尾随空格（Word 惯例 ` PAGE `） | 原样写入，不做 trim —— OOXML 域指令对空格宽容 |
| 域 run 的 `text()` 为空，在 `Run.equals` 里两个域 run 会相等 | 这是正确的（空文本域 run 内容确实相等），不算 bug |
| 用户对返回的 instrText run 调 `text()` 期望返回指令 | Javadoc 标注：返回的 run 是 instrText run，`text()` 返回空串（因为指令不是 `<w:t>`） |

## 与 Rule 1 的关系

`Paragraph.addSimpleField` 操作的是 POI 的 `XWPFParagraph` 委托（标准写穿透，`createRun` + CTR 操纵），不引入新模型、不偏离 Rule 1。域 run 就是普通 `Run`，只是内容不是 `<w:t>` —— 这与"图片 run 内嵌 picture"（N6）是同型取舍。

## 不在本设计内

- 域的读侧（`Paragraph.fields()` / `Run.isField()` / `Run.fieldInstruction()`）—— 走 `raw()`，留给后续
- `Field` 公共类型 —— 读侧建模时再引入
- 复杂域（separate 缓存值）—— 本次只做简单域
- `fldSimple` 单元素形态 —— 采用三段 `<w:r>` 形态
- 嵌套域 —— 不支持

## 待 research 确认点

无。所有技术前提已通过现有代码（`TocFields` / `TableOfContentsTest`）实测验证。
