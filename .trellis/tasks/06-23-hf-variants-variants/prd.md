# 首页 / 偶数页页眉页脚变体 API

## Goal

为 nondocx 补齐**首页 / 偶数页页眉页脚变体**的编辑能力，使用户能：

- 为章节创建「首页不同」的页眉页脚（典型场景：封面页无页眉、或首页页眉单独设计）
- 为章节创建「奇偶页不同」的页眉页脚（典型场景：偶数页放书名、奇数页放章节名）
- 与现有默认（奇数页）变体共存

本子任务是父任务的**核心**。

## User Value

完成后，用户能通过枚举参数形态：

```java
// 首页页眉
Header first = doc.ensureHeader(HeaderFooterVariant.FIRST);
first.addParagraph().addRun("封面页眉");

// 偶数页页脚
Footer even = doc.ensureFooter(HeaderFooterVariant.EVEN);
even.addParagraph().addRun("even page footer");

// 默认（奇数页）—— 无参版本保持向后兼容
Header def = doc.ensureHeader();
```

变体经 save → reopen 后正确存活，且首页 / 偶数页开关（`titlePg` / `evenAndOddHeaders`）被正确写入。

## Confirmed Facts

- **POI 变体常量齐全**：`XWPFHeaderFooterPolicy.DEFAULT` / `FIRST` / `EVEN`，对称提供 `createHeader(variant)` / `createFooter(variant)` / `getDefaultHeader` / `getFirstHeader` / `getEvenHeader` 等。
- **首页开关**：OOXML 里首页不同由 `<w:sectPr>` 子元素 `<w:titlePg/>` 控制（存在即启用）。**POI 的 `createHeader(FIRST)` 不会自动写 `titlePg`**，必须 nondocx 显式补，否则 part 创建了但首页不生效（待 spec N19 落档）。
- **偶数页开关**：OOXML 里奇偶页不同由 `word/settings.xml` 的 `<w:evenAndOddHeaders/>` 控制（文档级开关，不是 per-section）。**POI 的 `createHeader(EVEN)` 不会自动写这个开关**，必须 nondocx 显式补。
- **settings.xml 访问路径**：`document.getSettings()` → `XWPFSettings`，已有先例（tracked changes 的 `isTrackRevisions` 走同路径，poi-bridge N12）。`<w:evenAndOddHeaders/>` 需通过 `CTSettings` 直接操纵（POI 无便捷 setter）。
- **WPS 兼容性警告**：spec `#title-page-suppress` 已记录 `titlePg` 首页抑制在 WPS 不可靠。首页变体功能照常提供，但 Javadoc 须标注该警告。
- 父任务已确定：变体 API 形态用**枚举参数**（`HeaderFooterVariant { DEFAULT, FIRST, EVEN }`），无参版本 = DEFAULT（向后兼容）。
- 父任务已确定：变体读写延续**读写分离**契约（`header(variant)` 只读返 null，`ensureHeader(variant)` 创建）。
- 现有 `Section.header()` / `ensureHeader()` / `footer()` / `ensureFooter()` 与 `Document` 的便捷重载已存在，本子任务在其上加变体重载。

## Requirements

### R1. 枚举与映射

- [ ] 新增 `api/header/HeaderFooterVariant` 枚举（POI-free，参照 `Alignment` 风格）：`DEFAULT` / `FIRST` / `EVEN`。
- [ ] `internal/poi/Mappers` 加 `toPoi(HeaderFooterVariant)` → `XWPFHeaderFooterPolicy.DEFAULT` / `FIRST` / `EVEN`。

### R2. Section 变体重载

- [ ] `Section.header(HeaderFooterVariant)`：只读，不存在返 null，永不动文档。
- [ ] `Section.ensureHeader(HeaderFooterVariant)`：不存在才创建；`FIRST` 时同时写 `<w:titlePg/>`，`EVEN` 时同时在 settings.xml 写 `<w:evenAndOddHeaders/>`。
- [ ] `Section.footer(HeaderFooterVariant)` / `ensureFooter(HeaderFooterVariant)`：对称。
- [ ] 无参版本 `header()` / `ensureHeader()` / `footer()` / `ensureFooter()` 保持为 DEFAULT，委托给带参版本（消除重复）。
- [ ] `ensureHeader(FIRST)` / `ensureFooter(FIRST)` 创建时复用现有 `ensureCompatiblePageSetupForHeaderFooterCreation()`（与默认变体一致的兼容性页面设置补齐）。

### R3. Document 变体便捷重载

- [ ] `Document.header(HeaderFooterVariant)` / `ensureHeader(HeaderFooterVariant)` / `footer(HeaderFooterVariant)` / `ensureFooter(HeaderFooterVariant)`：委托 `section(0)`。

### R4. 内容相等性扩展

- [ ] `Section.equals` / `hashCode` 纳入 FIRST / EVEN 变体的段落内容（与 DEFAULT 对称，null 归一化为空列表）。
- [ ] 扩展不破坏现有 `RoundTripTest`（默认变体场景的相等性不变）。

### R5. 一致性与兼容性约束

- [ ] 变体经 save → Docx.open 后正确存活（part 引用 + 开关都 round-trip）。
- [ ] 首页变体创建后，`<w:titlePg/>` 出现在 sectPr；偶数页变体创建后，`<w:evenAndOddHeaders/>` 出现在 settings.xml。
- [ ] 公共 API 保持 POI-free；`XWPFHeaderFooterPolicy` / `CTSettings` / `CTSectPr` 操纵下沉到 `Section` 的私有方法或 `internal/poi`。
- [ ] 异常包装：part 创建失败包成 `DocxIOException`（与现有 `ensureHeader` 一致）。
- [ ] 现有无参 API 行为无回归（`header()` / `ensureHeader()` 仍 = DEFAULT）。

### R6. 教学式 Javadoc

- [ ] `ensureHeader(FIRST)` Javadoc 讲清：OOXML 的 `<w:titlePg/>` 是 sectPr 子元素 → POI 不自动写需补 → nondocx 为什么把它绑进 ensure 而非独立方法。
- [ ] `ensureHeader(EVEN)` Javadoc 讲清：OOXML 的 `<w:evenAndOddHeaders/>` 是文档级 settings 开关（与 per-section 的 titlePg 不同层级）→ POI 无便捷 setter 需操纵 CTSettings → nondocx 的处理。
- [ ] `HeaderFooterVariant` 枚举 Javadoc 说明三个值对应的 OOXML 语义与 WPS 兼容性提示（FIRST 引用 `#title-page-suppress`）。

## Acceptance Criteria

- [ ] AC1 `doc.ensureHeader(HeaderFooterVariant.FIRST)` 创建首页页眉，save → reopen 后 `doc.header(FIRST)` 读回非 null，内容 round-trip。
- [ ] AC2 `doc.ensureFooter(HeaderFooterVariant.EVEN)` 创建偶数页页脚，save → reopen 后 `doc.footer(EVEN)` 读回非 null，内容 round-trip。
- [ ] AC3 首页变体创建后，sectPr 里有 `<w:titlePg/>`（结构断言或 POI cross-reference）。
- [ ] AC4 偶数页变体创建后，settings.xml 里有 `<w:evenAndOddHeaders/>`（结构断言）。
- [ ] AC5 三变体（DEFAULT + FIRST + EVEN）共存于同一章节，round-trip 后各自内容独立可读。
- [ ] AC6 `ensureHeader(FIRST)` 重复调用返回同一页眉（create-once 语义）。
- [ ] AC7 无参 `header()` / `ensureHeader()` 仍 = `header(DEFAULT)` / `ensureHeader(DEFAULT)`（向后兼容）。
- [ ] AC8 `Section.equals` / `hashCode` 扩展后，`RoundTripTest` 与现有 `HeaderFooterTest` 继续绿。
- [ ] AC9 WPS 兼容性警告出现在 `HeaderFooterVariant.FIRST` 的 Javadoc 里（引用 `#title-page-suppress`）。

## Out of Scope

- **「链接到上一节」的 header/footer 继承** —— 涉及跨节引用，超出本次范围。
- **首页 / 偶数页变体的修订层面 accept/reject** —— 属 tracked changes 范畴。
- **`evenAndOddHeaders` 的 WPS 渲染验证** —— 实现后由 docs-spec 子任务决定是否新增 renderer-compatibility 锚点。
- **页眉页脚内的富内容**（表格 / 图片）—— 由 content 子任务负责。
- **页码域** —— 由 field 子任务负责。

## Open Questions

- `ensureHeader(EVEN)` 写 settings.xml 的 `<w:evenAndOddHeaders/>` 时，是否需要考虑「文档已有 settings 但无该元素」与「文档完全没有 settings.xml」两种情况？POI 的 `getSettings()` 是否保证返回非 null？（design 阶段验证，预期 POI 会懒创建）

## Notes

- 变体的 POI 坑（`createHeader(FIRST/EVEN)` 不自动写开关）将由 **docs-spec 子任务** 落档为 poi-bridge N19。
- WPS 对 `evenAndOddHeaders` 的兼容性观察也由 docs-spec 子任务记录。
