# Design — 首页 / 偶数页页眉页脚变体 API

> 子任务 `06-23-hf-variants-variants` 的技术设计。
> 需求与验收见 `prd.md`；执行计划见 `implement.md`。

## 设计目标

在现有默认（奇数页）页眉页脚的读写分离基础上，补齐**首页（FIRST）**与**偶数页（EVEN）**两个变体。延续既有契约：

- 读侧 `header(variant)` 只读、不存在返 null、永不动文档
- 写侧 `ensureHeader(variant)` 不存在才创建、create-once
- 无参版本 `header()` / `ensureHeader()` 保持为 DEFAULT（向后兼容）

并在创建 FIRST/EVEN 变体时**显式补齐** OOXML 要求的两个开关（POI 不自动写）。

## 技术前提（全部实测验证，非推断）

### POI 变体常量与方法（`XWPFHeaderFooterPolicy`）

```java
// 常量（STHdrFtr.Enum 类型）
public static final STHdrFtr.Enum DEFAULT;
public static final STHdrFtr.Enum FIRST;
public static final STHdrFtr.Enum EVEN;

// 写：创建并附加指定变体的 part
XWPFHeader createHeader(STHdrFtr.Enum);
XWPFFooter createFooter(STHdrFtr.Enum);

// 读：不存在返 null（与 getDefaultHeader 同语义）
XWPFHeader getDefaultHeader();   XWPFFooter getDefaultFooter();
XWPFHeader getFirstPageHeader(); XWPFFooter getFirstPageFooter();  // 注意：FirstPage 不是 First
XWPFHeader getEvenPageHeader();  XWPFFooter getEvenPageFooter();   // 注意：EvenPage 不是 Even
```

### 两个开关（POI 不自动写，nondocx 必须补）

**首页开关 `titlePg`**（per-section，住在 `<w:sectPr>`）：
```java
// CTSectPr 上的 XmlBeans 标准 5 件套（已确认可达）
boolean isSetTitlePg();
CTOnOff getTitlePg();
void setTitlePg(CTOnOff);
CTOnOff addNewTitlePg();      // 用这个新建空元素
void unsetTitlePg();
```
OOXML：`<w:sectPr><w:titlePg/></w:sectPr>`，元素存在即「首页不同」。

**偶数页开关 `evenAndOddHeaders`**（文档级，住在 `word/settings.xml`）：
```java
// CTSettings 上的 XmlBeans 标准 5 件套（已确认可达）
boolean isSetEvenAndOddHeaders();
CTOnOff getEvenAndOddHeaders();
void setEvenAndOddHeaders(CTOnOff);
CTOnOff addNewEvenAndOddHeaders();   // 用这个新建空元素
void unsetEvenAndOddHeaders();
```
OOXML：`<w:settings><w:evenAndOddHeaders/></w:settings>`，元素存在即「奇偶页不同」。

> **不用 POI 的 `XWPFSettings.setEvenAndOddHeadings(boolean)`**：虽然存在，但方法名拼写是 `Headings`（带 s）且语义模糊；直接操纵 `CTSettings.addNewEvenAndOddHeaders()` 与 OOXML 元素名精确对应，避免依赖 POI 便捷方法名的歧义。与 `trackChanges` 开关的处理方式一致（`TrackedChangeNodes.setEnabled` 直接走 `setTrackRevisions`，因为它名字对得上；这里名字对不上，走 CT）。

### settings.xml 访问路径

`document.getSettings()` 已有先例（`TrackedChangeNodes.isEnabled/setEnabled`，第 90/120 行），无 null guard —— POI 保证返回非 null（会懒创建 settings part）。

## 公共 API 形态

### 新枚举 `HeaderFooterVariant`

```java
package com.non.docx.core.api.header;

/**
 * 页眉 / 页脚的变体类型。
 *
 * <p>OOXML 里一个章节可以有三套页眉页脚：默认（奇数页）、首页、偶数页。
 * 三者独立存在，由 {@code XWPFHeaderFooterPolicy} 按 {@code STHdrFtr} 区分。
 *
 * <p><b>开关依赖。</b>
 * <ul>
 *   <li>{@link #FIRST} 需要 {@code <w:sectPr>} 的 {@code <w:titlePg/>} 才生效（POI 不自动写，nondocx 补）。
 *   <li>{@link #EVEN} 需要 {@code settings.xml} 的 {@code <w:evenAndOddHeaders/>} 才生效（POI 不自动写，nondocx 补）。
 *   <li>{@link #DEFAULT} 无开关依赖。
 * </ul>
 *
 * <p><b>WPS 兼容性。</b> {@link #FIRST} 依赖的 {@code titlePg} 在 WPS 的首页抑制不可靠
 * （见 {@code renderer-compatibility.md#title-page-suppress}）；功能照常提供，但跨引擎场景需注意。
 */
public enum HeaderFooterVariant {
  /** 默认（奇数页）页眉 / 页脚。无开关依赖。 */
  DEFAULT,
  /** 首页页眉 / 页脚。需要 {@code <w:titlePg/>}，WPS 兼容性见 {@code renderer-compatibility.md#title-page-suppress}。 */
  FIRST,
  /** 偶数页页眉 / 页脚。需要 {@code settings.xml} 的 {@code <w:evenAndOddHeaders/>}。 */
  EVEN
}
```

### `internal/poi/Mappers` 新增映射

```java
public static STHdrFtr.Enum toPoi(HeaderFooterVariant variant) {
  switch (variant) {
    case DEFAULT: return XWPFHeaderFooterPolicy.DEFAULT;
    case FIRST:   return XWPFHeaderFooterPolicy.FIRST;
    case EVEN:    return XWPFHeaderFooterPolicy.EVEN;
    default: throw new IllegalStateException("未知的变体: " + variant);
  }
}
```

### `Section` 变体重载

**核心：抽出私有统一入口 `resolveHeader(variant, create)`，消除现有 `header()`/`ensureHeader()` 的重复**：

```java
// 公开只读
public Header header()                          { return header(HeaderFooterVariant.DEFAULT); }
public Header header(HeaderFooterVariant v)     { return resolveHeader(v, false); }

// 公开创建
public Header ensureHeader()                    { return ensureHeader(HeaderFooterVariant.DEFAULT); }
public Header ensureHeader(HeaderFooterVariant v){ return resolveHeader(v, true); }

// 统一私有入口
private Header resolveHeader(HeaderFooterVariant variant, boolean create) {
  Objects.requireNonNull(variant, "variant");
  try {
    XWPFHeaderFooterPolicy policy = new XWPFHeaderFooterPolicy(document, delegate);
    XWPFHeader existing = policy.getHeader(Mappers.toPoi(variant));
    // ↑ 注意：POI 的统一 getter 是 getHeader(STHdrFtr.Enum)，
    //   但实测它不存在！只有 getDefaultHeader/getFirstPageHeader/getEvenPageHeader 三个分别方法。
    //   故需要 switch 调对应方法。见下方"实现骨架"。
    ...
  }
}
```

**实测修正**：`XWPFHeaderFooterPolicy` 没有 `getHeader(STHdrFtr.Enum)` 统一方法，只有三个分别方法（`getDefaultHeader` / `getFirstPageHeader` / `getEvenPageHeader`）。故读侧需要 switch 分派。

### 实现骨架（Section）

```java
private Header resolveHeader(HeaderFooterVariant variant, boolean create) {
  Objects.requireNonNull(variant, "variant");
  try {
    XWPFHeaderFooterPolicy policy = new XWPFHeaderFooterPolicy(document, delegate);
    XWPFHeader existing = readHeader(policy, variant);
    if (existing != null) {
      return new Header(existing);
    }
    if (!create) {
      return null;
    }
    ensureCompatiblePageSetupForHeaderFooterCreation();   // 复用现有
    ensureVariantFlags(variant);                          // 新增：补 titlePg / evenAndOddHeaders
    XWPFHeader created = policy.createHeader(Mappers.toPoi(variant));
    return new Header(created);
  } catch (POIXMLException e) {
    if (create) {
      throw new DocxIOException("无法创建章节" + variant + "页眉", e);
    }
    return null;  // 只读路径：解析失败按"不存在"处理，永不抛
  }
}

// 读分派（POI 没有统一 getter）
private static XWPFHeader readHeader(XWPFHeaderFooterPolicy policy, HeaderFooterVariant variant) {
  switch (variant) {
    case DEFAULT: return policy.getDefaultHeader();
    case FIRST:   return policy.getFirstPageHeader();
    case EVEN:    return policy.getEvenPageHeader();
    default: throw new IllegalStateException("未知的变体: " + variant);
  }
}
// footer 对称：readFooter 用 getDefaultFooter/getFirstPageFooter/getEvenPageFooter

// 开关写入
private void ensureVariantFlags(HeaderFooterVariant variant) {
  if (variant == HeaderFooterVariant.FIRST && !delegate.isSetTitlePg()) {
    delegate.addNewTitlePg();
  }
  if (variant == HeaderFooterVariant.EVEN) {
    CTSettings settings = document.getSettings().getCTSettings();
    if (!settings.isSetEvenAndOddHeaders()) {
      settings.addNewEvenAndOddHeaders();
    }
  }
  // DEFAULT 不需要开关
}
```

### `Document` 便捷重载

```java
public Header header(HeaderFooterVariant v)      { return section(0).header(v); }
public Header ensureHeader(HeaderFooterVariant v){ return section(0).ensureHeader(v); }
public Footer footer(HeaderFooterVariant v)      { return section(0).footer(v); }
public Footer ensureFooter(HeaderFooterVariant v){ return section(0).ensureFooter(v); }
```

## `equals` / `hashCode` 扩展

现有 `Section.equals` 比较默认变体页眉页脚段落（`defaultHeaderParagraphs()` / `defaultFooterParagraphs()`）。扩展为三变体都参与：

```java
@Override public boolean equals(Object o) {
  ...
  return ... // 现有字段
      && java.util.Objects.equals(this.defaultHeaderParagraphs(), that.defaultHeaderParagraphs())
      && java.util.Objects.equals(this.defaultFooterParagraphs(), that.defaultFooterParagraphs())
      && java.util.Objects.equals(this.firstHeaderParagraphs(), that.firstHeaderParagraphs())
      && java.util.Objects.equals(this.firstFooterParagraphs(), that.firstFooterParagraphs())
      && java.util.Objects.equals(this.evenHeaderParagraphs(), that.evenHeaderParagraphs())
      && java.util.Objects.equals(this.evenFooterParagraphs(), that.evenFooterParagraphs());
}
```

新增 4 个私有只读解析方法（`firstHeaderParagraphs()` 等），与 `defaultHeaderParagraphs()` 同模式：null 归一化为空列表。`hashCode` 对称。

## 决策：开关写在 `ensure` 还是 `header`？

**开关只在 `ensure`（创建路径）写，`header`（只读）永不写。**

理由：延续读写分离（N5）。读侧 `header(FIRST)` 即使文档没 `titlePg`，也只读返 null（FIRST part 不存在），不去补开关 —— 因为补开关是「修改文档」行为，属于写。

**幂等**：`ensureVariantFlags` 用 `isSetXxx()` 守卫，重复 ensure 同一变体不会重复写开关。

## 与 Rule 1 的关系

- `Section` 持有 `final XWPFDocument document` + `final CTSectPr delegate`（现有结构），不新增字段
- `HeaderFooterVariant` 是 POI-free 值对象（enum）
- CT 操纵（`addNewTitlePg` / `addNewEvenAndOddHeaders`）内联在 `Section` 私有方法 —— 与现有 `ensureCompatiblePageSetupForHeaderFooterCreation` 内联 `paperSize`/`margins` 同模式，不下沉 `internal/poi`
- `STHdrFtr.Enum` 映射在 `Mappers`（Rule 5：枚举映射在 internal）

## 测试策略

### Round-trip（核心验收）

```java
@Test
void firstHeaderRoundTrips(@TempDir Path tmp) throws Exception {
  Path file = tmp.resolve("first.docx");
  Document original = Docx.create();
  original.ensureHeader(HeaderFooterVariant.FIRST).addParagraph().addRun("封面页眉");
  original.save(file);

  try (Document opened = Docx.open(file)) {
    Header first = opened.header(HeaderFooterVariant.FIRST);
    assertThat(first).isNotNull();
    assertThat(first.text()).contains("封面页眉");
  }
}

@Test
void evenFooterRoundTrips(@TempDir Path tmp) throws Exception {
  // 类似，even footer
}
```

### 开关断言（POI cross-reference）

```java
@Test
void firstHeaderWritesTitlePg() {
  Document doc = Docx.create();
  doc.ensureHeader(HeaderFooterVariant.FIRST);
  // 通过 raw 读 sectPr，断言 titlePg 存在
  assertThat(doc.section(0).raw().isSetTitlePg()).isTrue();
}

@Test
void evenHeaderWritesSettingsFlag() {
  Document doc = Docx.create();
  doc.ensureHeader(HeaderFooterVariant.EVEN);
  assertThat(doc.raw().getSettings().getCTSettings().isSetEvenAndOddHeaders()).isTrue();
}
```

### 三变体共存

```java
@Test
void threeVariantsCoexist(@TempDir Path tmp) throws Exception {
  Document original = Docx.create();
  original.ensureHeader().addParagraph().addRun("默认");
  original.ensureHeader(FIRST).addParagraph().addRun("首页");
  original.ensureHeader(EVEN).addParagraph().addRun("偶数");
  original.save(file);

  try (Document opened = Docx.open(file)) {
    assertThat(opened.header().text()).contains("默认");
    assertThat(opened.header(FIRST).text()).contains("首页");
    assertThat(opened.header(EVEN).text()).contains("偶数");
  }
}
```

### create-once / 向后兼容 / equals

- `ensureHeader(FIRST)` 重复调用返回同一页眉（内容追加而非新建 part）
- 无参 `header()` == `header(DEFAULT)`
- `Section.equals` 扩展后 round-trip 存活（默认变体场景行为不变）

## 风险与边界

| 风险 | 缓解 |
|---|---|
| `XWPFHeaderFooterPolicy` 构造对未设 sectPr 的章节抛异常 | 已有 `ensureCompatiblePageSetupForHeaderFooterCreation` 在 create 路径补页面设置 |
| 多章节文档里 FIRST/EVEN 的开关语义 | FIRST 的 `titlePg` 是 per-section（正确）；EVEN 的 `evenAndOddHeaders` 是文档级（写一次即可，多章节共享）—— 这是 OOXML 本身的语义，nondocx 遵循 |
| WPS 对 titlePg 不可靠 | Javadoc 标注 `#title-page-suppress` |
| `document.getSettings().getCTSettings()` 是否可达 | 实测确认（TrackedChangeNodes 已用 `getSettings()`，`getCTSettings()` 是 XmlBeans 标准方法） |

## 不在本设计内

- 「链接到上一节」的 header/footer 继承（跨节引用）
- FIRST/EVEN 变体的修订层面 accept/reject
- `evenAndOddHeaders` 的 WPS 渲染验证（留给 docs-spec）
- 页眉内的富内容（content 子任务）
- 页码域（field 子任务）

## 待 research 确认点

无。所有技术前提已实测验证。
