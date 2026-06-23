# core 页眉页脚编辑 API 补齐（父任务）

## Goal

将 nondocx 当前对页眉页脚的 **「MVP 只暴露默认（奇数页）」** 状态，规划并拆解为一组可独立验收的子任务，最终交付一套覆盖变体（首页 / 偶数页）+ 富内容（图片 / 表格 / 页码域）的页眉页脚编辑能力。

这个父任务本身**不直接承载所有实现细节**；它负责：

- 保存源需求与总体目标
- 维护子任务拆分与边界
- 约束跨子任务的 API 一致性
- 在所有子任务完成后做集成验收

## User Value

完成后，nondocx 用户应能在页眉页脚里完成常见排版需求，而无需掉进 `raw()` / CT 层：

- **变体**：首页不同的页眉页脚、奇偶页不同的页眉页脚（典型场景：封面无页眉、偶数页放书名、奇数页放章节名）
- **页码域**：在页脚插入「第 X 页 / 共 Y 页」这类页码 / 总页数域
- **富内容**：在页眉里放 logo 图片、用表格做多列页眉布局
- **兼容性**：现有默认（奇数页）页眉页脚 API 行为保持稳定，不因引入变体而回归

## Confirmed Facts

- 当前 `Header` / `Footer`（`api/header/`）已提供 `paragraphs()` / `addParagraph()` / `text()` / `raw()`，以及基于 `XWPFHeaderFooterPolicy.DEFAULT` 的默认变体读写分离（`header()` 只读返 null，`ensureHeader()` 创建）。
- POI 的 `XWPFHeaderFooterPolicy` 已提供 `FIRST` / `EVEN` 常量与对称的 `createHeader/Footer(variant)` / `getFirstHeader` / `getEvenHeader` API，变体扩展的 POI 侧能力齐全。
- 偶数页变体需要 `word/settings.xml` 里的 `<w:evenAndOddHeaders/>` 开关；首页变体需要 `<w:sectPr>` 里的 `<w:titlePg/>` 标志。**这两个开关 POI 不会在 `createHeader(FIRST/EVEN)` 时自动写**，必须由 nondocx 显式补齐（待 spec N19 落档）。
- `XWPFHeader` / `XWPFFooter` 实现了 `IBody`（与 `XWPFDocument` 同接口），理论上 `createTable()` / `createParagraph()` 路径可直接复用；图片走 `XWPFRun.addPicture` 的 part 解析链理论上也兼容（待 content 子任务实测确认）。
- 现有 `Paragraph.addImage` 已封装像素→EMU 转换 + `DocxIOException` 包装（poi-bridge N3），页眉内图片预期零额外代码即可复用。
- WPS 兼容性 spec `#title-page-suppress` 已记录 `titlePg` 首页抑制在 WPS 不可靠；首页变体功能照常提供，但 Javadoc 须标注该警告。
- 读写分离契约（poi-bridge N5）是页眉页脚的硬约束：变体扩展必须延续「只读返 null、ensure 才创建」的模式，不能回退到「访问即创建」。

## Task Map（父 / 子任务拆分）

当前父任务拆分为以下子任务：

1. `06-23-hf-variants-field`
   - **页码与通用简单域 API**：`Run.addSimpleField(instruction)` 通用入口 + `Paragraph.addPageNumberField()` / `addPageCountField()` 便捷方法。域是 OOXML 通用机制，与变体正交，可独立 round-trip 测试。
2. `06-23-hf-variants-variants`
   - **首页 / 偶数页变体 API**：`HeaderFooterVariant` 枚举 + `Section` / `Document` 的变体重载 + 首页 / 偶数页开关写入。本任务的核心。
3. `06-23-hf-variants-content`
   - **页眉页脚内表格与图片便捷方法**：`Header` / `Footer` 加 `addTable()` / `tables()`；验证图片复用 `Paragraph.addImage` 路径。与变体正交。
4. `06-23-hf-variants-docs-spec`
   - **spec 更新与集成验收**：poi-bridge N19（变体 POI 坑）、renderer-compatibility 补充；父任务集成验收与回归确认。

子任务依赖关系（写入子任务 `prd.md` 而非依赖树位置）：

- `field` / `variants` / `content` 三者**互相正交**，可任意顺序实现。
- `docs-spec` **依赖** 前三者完成（需要它们的实现结论来落 spec）。
- 推荐顺序：`field`（最独立）→ `variants`（核心）→ `content`（收尾）→ `docs-spec`。

## Requirements

### R1. 总体能力目标

- [ ] 最终交付覆盖**变体 + 富内容 + 页码域**三条能力线。
- [ ] 变体线支持：首页不同、偶数页不同，且与默认（奇数页）变体共存。
- [ ] 富内容线支持：页眉页脚内可放表格、图片。
- [ ] 页码域线支持：通用简单域 + 页码 / 总页数便捷方法。

### R2. 子任务边界必须清晰

- [ ] 每个子任务都必须有**独立、可验证**的交付物与验收标准。
- [ ] 父任务负责跨子任务的 API 一致性与最终集成，不把多个子任务的具体实现细节重新堆回父任务里。
- [ ] 若后续发现某一块还能进一步独立验证，可以继续增补子任务，而不是把复杂度重新塞回单任务。

### R3. 跨子任务的一致性约束

- [ ] 变体 API 形态统一为**枚举参数**（`HeaderFooterVariant`），不出现「专用方法名」与「枚举参数」两套并存。
- [ ] 变体读写延续**读写分离**契约（`header(variant)` 只读返 null，`ensureHeader(variant)` 创建）；无参版本保持为 DEFAULT，向后兼容。
- [ ] 域 API 的**写侧**统一在 `Run` / `Paragraph` 上（与现有 `addRun` / `addHyperlink` 同层）；**读侧**（解析已有域）不在本次范围，走 `raw()`。
- [ ] 页眉页脚内的表格 / 图片 API 与 `Document` 上的同名 API 行为对称（剥掉 POI 预填、`DocxIOException` 包装、链式返回）。
- [ ] 公共 API 继续保持 POI-free；CT / XmlBeans 脏活集中在 `internal/poi/`。
- [ ] 对外异常继续遵守 `error-handling.md`；不把 POI / XmlBeans 细节泄漏到公共表面。
- [ ] 教学式 Javadoc：每个新公开方法用「OOXML 是什么 → POI 如何表达 → nondocx 为什么这样封装」三层递进（见 `.trellis/spec/guides/teaching-approach.md`）。

### R4. 分阶段交付顺序

- [ ] 默认顺序为：field → variants → content → docs-spec。
- [ ] docs-spec 必须最后做（需要前三者的实现结论）。
- [ ] 如果实现中发现高风险子题（例如 evenAndOddHeaders 在 WPS 有额外坑、图片在页眉内 part 解析失败）需要再拆，优先继续拆分。

### R5. 父任务必须保留的未决问题

- [ ] 图片在页眉 / 页脚上下文里，POI 的 `XWPFRun.addPicture` 的 part 关系解析是否真能直接复用？若不能，是否需要 `internal/poi/HeaderPictures` 收口？（由 content 子任务实测回答）
- [ ] `<w:evenAndOddHeaders/>` 是否在 WPS 也有跨引擎渲染差异？（由 docs-spec 子任务在实现后验证，必要时新增 renderer-compatibility 锚点）

## Acceptance Criteria

- [x] AC1 当前父任务已拆分为子任务，且每个子任务职责边界清晰、不重叠。
- [x] AC2 父任务 `prd.md` 只保留需求、约束、验收与开放问题；技术设计迁移到 `design.md`。
- [x] AC3 所有子任务完成后，最终集成结果满足：
  - 用户能通过 `ensureHeader(FIRST)` / `ensureHeader(EVEN)` 创建首页 / 偶数页变体，且 round-trip 存活 ✅（`HeaderFooterTest` 22 用例 + `HeaderFooterIntegrationTest`）
  - 用户能在页眉页脚里插入页码域（`addPageNumberField`）、总页数域、任意简单域 ✅（`SimpleFieldTest` 8 用例）
  - 用户能在页眉页脚里插入表格与图片 ✅（`HeaderContentTest` 7 用例）
  - 现有默认页眉页脚 API 行为无回归（`RoundTripTest` + `HeaderFooterTest` 继续绿）✅
  - 相关 spec / 文档与能力范围保持一致（poi-bridge N19/N20/N21 + renderer-compatibility even-odd-headers）✅

## Out of Scope（父任务层）

- **域的读侧解析**（抽取已有 field code）—— 不在本次范围，走 `raw()`。
- **首页 / 偶数页变体的修订层面 accept/reject** —— 属 tracked changes 范畴，不混做。
- **页眉页脚的水印、shape、文本框等非段落 / 表格内容** —— 走 `raw()`。
- **多章节文档里「链接到上一节」的 header/footer 继承语义** —— 涉及 `HeaderFooterPolicy` 的跨节引用，超出本次范围。
- **域的复杂形态**（嵌套域、SDT 形态域、带 separate 缓存结果的完整域）—— 本次只做「简单域」（begin + instrText + end，无 separate 缓存）。
- **在父任务中直接堆实现细节** —— 具体技术路线应下沉到子任务与 `design.md`。

## Open Questions

当前父任务级开放问题见 R5；若后续在子任务 research / 实测阶段发现某一类复杂度显著超出预期，再回到 planning 继续拆分。

## Notes

- 跨子任务的技术契约、API 一致性策略、风险收敛见 `design.md`（brainstorm 阶段产出）。
- 执行顺序与开始实现前的准备项见 `implement.md`（brainstorm 阶段产出）。
- 具体子任务的功能定义与验收标准在各自目录下继续细化。
