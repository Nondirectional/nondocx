# 页眉页脚内表格与图片便捷方法

## Goal

为 nondocx 的 `Header` / `Footer` 补齐**表格与图片**的便捷编辑能力，使用户能在页眉里放 logo 图片、用表格做多列页眉布局，而无需掉进 `raw()` 手写 `XWPFHeader.createTable()`。

本子任务与变体扩展**正交** —— 表格 / 图片在任何变体（默认 / 首页 / 偶数页）的页眉页脚里都应可用。

## User Value

完成后，用户能：

- 在页眉里插入表格：`header.addTable()` → 返回 `Table`，可继续 `addRow()` / `addCell()`
- 枚举页眉里的表格：`header.tables()`
- 在页眉段落里插入图片：`header.addParagraph().addImage(bytes, type, w, h)`（**预期零额外代码**，复用 `Paragraph.addImage`）

## Confirmed Facts

- `XWPFHeader` / `XWPFFooter` 实现了 `IBody`（与 `XWPFDocument` 同接口），理论上 `createTable()` / `createParagraph()` / `getTables()` / `getBodyElements()` 路径可直接复用。
- 现有 `Document.addTable()` 已封装「剥掉 POI 预填行」的逻辑（poi-bridge N2），`Header.addTable()` 预期走完全相同的模式。
- 现有 `Document.tables()` 是活跃视图（`AbstractList` wrap-on-get），`Header.tables()` 预期同型。
- **图片路径需实测**：`Paragraph.addImage` 内部走 `XWPFRun.addPicture`，该方法通过 `IRunBody` → `getPart()` 解析图片 part 关系。`XWPFHeader` 作为 `IRunBody` 的有效性、part 解析是否真能在 header 上下文工作 —— 是本子任务最大的不确定点（见 Open Questions）。
- 父任务已确定：页眉页脚内的表格 / 图片 API 与 `Document` 上的同名 API 行为对称。
- 父任务已确定：图片路径若不能直接复用，按 N2 风格收进 `internal/poi/HeaderPictures.java`（但预期不需要）。

## Requirements

### R1. 表格便捷方法

- [ ] `Header.addTable()`：委托 `delegate.createTable()`，剥掉 POI 预填行（与 `Document.addTable` 完全一致），返回 `Table`。
- [ ] `Header.tables()`：活跃视图（`AbstractList` wrap-on-get），与 `Document.tables()` 同型。
- [ ] `Footer.addTable()` / `Footer.tables()`：对称。

### R2. 图片路径（实测后二选一）

- [ ] **路径 A（预期）**：`header.addParagraph().addImage(...)` 直接复用现有 `Paragraph.addImage`，无需新代码。实测 round-trip 通过即满足。
- [ ] **路径 B（fallback）**：若路径 A 在 header 上下文 part 解析失败，新建 `internal/poi/HeaderPictures.java` 收口，提供 `Header.addImage(byte[], ImageType, int, int)` 直接入口（不经过 `Paragraph`）。
- [ ] 无论哪条路径，图片经 save → reopen 后字节、尺寸、格式 round-trip 精确（与 `Paragraph.addImage` 的契约一致，poi-bridge N3）。

### R3. 一致性与兼容性约束

- [ ] 表格 / 图片在**任意变体**（默认 / 首页 / 偶数页）的页眉页脚里都可用（与 variants 子任务的正交性）。
- [ ] 表格 round-trip：save → reopen 后 `header.tables()` 读回结构等价的内容。
- [ ] 公共 API 保持 POI-free；`XWPFHeader.createTable` 的预填剥离逻辑与 `Document.addTable` 共享或镜像。
- [ ] 异常包装：POI 失败包成 `DocxIOException`。
- [ ] 现有 `Header` / `Footer` 的 `paragraphs()` / `addParagraph()` / `text()` 行为无回归。

### R4. 教学式 Javadoc

- [ ] `Header.addTable()` Javadoc 讲清：OOXML 的页眉/页脚与正文一样是 `<w:body>` 等价的块容器 → POI 的 `XWPFHeader` 实现 `IBody` 故 `createTable` 可用 → nondocx 为什么镜像 `Document.addTable` 的剥预填逻辑。
- [ ] `Header.tables()` Javadoc 说明活跃视图语义（参照 `Document.tables()`）。

## Acceptance Criteria

- [ ] AC1 `header.addTable().addRow().addCell().addParagraph().addRun("cell text")` round-trip 后内容存活。
- [ ] AC2 `header.tables()` 在无表格时返回空列表，有表格时返回活跃视图。
- [ ] AC3 `footer.addTable()` 对称工作。
- [ ] AC4 图片路径（A 或 B）实测 round-trip 通过：字节、尺寸（像素）、格式精确存活。
- [ ] AC5 表格与段落在页眉里共存（`header.addParagraph()` + `header.addTable()` 交错），round-trip 存活。
- [ ] AC6 表格在首页 / 偶数页变体页眉里同样可用（若 variants 子任务已完成，加交叉测试；否则标注为后续集成验证）。
- [ ] AC7 现有 `HeaderFooterTest` 无回归。

## Out of Scope

- **页眉内的非表格 / 非图片富内容**（文本框、shape、水印）—— 走 `raw()`。
- **图片的读侧增强**（如 `Header.images()` 枚举）—— 现有 `Paragraph.inlineElements()` 已把图片当 `Image` 暴露，无需额外入口。
- **表格在页眉里的特殊布局语义**（如「页眉表格自动随页面宽度」）—— 表格行为与正文表格一致，不特殊处理。
- **图片在页眉里的 part 关系底层重构** —— 若路径 A 可行则不动；路径 B 只做最小收口。

## Open Questions

- **图片 part 解析是否在 header 上下文工作？** 这是本子任务的核心实测点。建议实现时先写一个一次性探针（参照 tracked-changes 的 research/ 模式），用真实 docx 验证 `header.createParagraph().createRun().addPicture(...)` 的 part 关系是否正确建立、save→reopen 后图片是否存活。结论写入 `research/header-image-probe.md`，确认后删除探针代码。
- 若路径 A 可行，是否需要在 spec 里记录「`XWPFHeader` 实现 `IBody` 故 `Paragraph.addImage` 天然可复用」这一发现？（由 docs-spec 子任务决定）

## Notes

- 实测探针结果决定路径 A / B，直接影响 `design.md` 的表格 —— 这是为什么本子任务的 design 阶段必须在实现前完成探针。
- 与 variants 子任务的正交性意味着两者可并行实现，但 content 的交叉测试（AC6）若要覆盖变体场景，需 variants 先落地或在 docs-spec 集成验收时补。
