# docx compare to tracked changes

## Goal

为用户提供“旧版 `docx` + 新版 `docx` → 生成一份带修订标记的新 `docx`”的能力，使差异能在 Word 中以标准修订（tracked changes）方式展示，而不是仅做纯文本 diff 输出。

## User Value

- 用户可以把程序生成的差异结果直接交给人类审阅。
- 结果文档在 Word 中显示为标准修订，而不是库私有格式。
- 生成结果可继续使用现有 `TrackedChanges` 能力查询、accept、reject。

## Confirmed Facts

- 项目已经具备 tracked changes 的读取、查询、accept/reject、authoring 能力。
- 现有 authoring API 已支持文本插入/删除/替换、move、属性修订、单元格结构修订。
- 现有 tracked authoring API 对修订作者有硬约束：`author` 必传，且不能为空白。
- 当前仓库中还没有“比较两个 docx 并自动生成修订结果”的现成功能，也没有 compare/diff 引擎。
- tracked changes 在 OOXML 中不是“高亮结果”，而是实际写入 `word/document.xml` 的 `<w:ins>` / `<w:del>` / `<w:moveFrom>` / `<w:moveTo>` / `rPrChange` 等修订节点。
- Apache POI 没有可直接复用的高层 “compare two docx and emit tracked changes” API，因此该能力需要项目自行定义 compare 语义与映射策略。

## Requirements

### R1. 输入与输出

- [ ] 提供一个明确入口，接收旧版文档与新版文档。
- [ ] 生成一份新的 `.docx` 文件作为 compare 结果。
- [ ] 输出文档中的差异必须以标准 tracked changes 形式呈现。

### R2. 与现有 tracked changes 能力对齐

- [ ] compare 生成的修订必须能被 `Document.trackedChanges().list()` 读回。
- [ ] compare 生成的修订后续应能被现有 accept/reject 能力处理，至少文本类修订需成立。

### R3. 语义基线

- [ ] 结果文档应有清晰的基线语义：通常以“旧文档为底稿”，把“新文档相对旧文档的变化”写成修订。
- [ ] 新增内容写为 insertion；旧内容移除写为 deletion；若采用 replacement 语义，必须明确其底层等价于 del + ins。
- [x] 第一版以**旧文档**为结果文档的基线。
- [x] 正文段落按**文档顺序一一对齐**；一侧多出的段落按整段新增/整段删除处理。
- [x] 已对齐段落内部再做文本 diff，并映射为文本类修订。
- [x] compare API 提供默认 `author`，但也允许调用方显式传入覆盖。
- [ ] 默认 `author` 的具体字面值需要在 design 中定稿，并在 Javadoc / 文档中说明。

### R4. 第一版范围必须明确

- [x] 第一版仅覆盖**正文段落中的文本差异**。
- [x] 第一版目标是把正文文本差异写成**文本类修订**（`<w:ins>` / `<w:del>` / replacement = del + ins）。
- [x] 第一版采用“段落匹配 + 段内文本 diff”的组合。
- [x] 段内文本 diff 采用 **Unicode code point 级字符 diff**。
- [ ] 对未覆盖结构，行为必须诚实：报错、跳过、或降级，都要明确约定。

### R4.1 第一版显式不覆盖

- [x] 表格内容 compare
- [x] 样式变更 compare（粗体/颜色/字体等）
- [x] 页眉页脚 compare
- [x] 批注 compare
- [x] 图片/绘图 compare
- [x] 分节/页面设置 compare
- [x] move 识别（`moveFrom` / `moveTo`）

### R4.2 第一版对非支持结构的处理

- [x] 第一版只对**正文段落文本**做 compare。
- [x] 文档中的表格、页眉页脚、图片、批注、分节等非支持结构**不参与 compare**。
- [x] 非支持结构在结果文档中**保留旧文档原样**，而不是导致整文档拒绝处理。
- [x] 对于包含复杂内联结构（如超链接、图片、field 等）的正文段落，第一版不承诺安全改写；若该类段落发生差异，结果中保留旧文档原段落原样。
- [ ] README / Javadoc / 测试需明确这一边界，避免用户误以为这些结构已参与差异计算。

### R5. 可验证性

- [ ] 至少需要可重复的测试样例，验证 compare 后生成的文档能被 reopen，并正确读出预期修订。
- [ ] 验证不应只看字符串 diff；应验证 tracked changes 模型或 OOXML 结果。

## Likely Out Of Scope For MVP

- 与 Microsoft Word “比较文档”功能完全一致的全量行为兼容。
- 一次性覆盖正文、表格、样式、移动、页眉页脚、脚注、批注、分节等全部结构。
- 复杂布局/编号/域代码/图片位置变更的高保真 compare。
- 差异段落内超链接 / 图片 / field 等复杂内联结构的保真改写。

## Open Questions

当前 planning 阶段无阻塞性开放问题；默认作者字面值推荐为 `nondocx compare`，在实现时按 design 固化。

## Design Notes

- compare 引擎第一版应落在 `nondocx-core`，因为 `nondocx-toolkit` 的职责是包装 `core` 能力供 Agent 使用，而不是在工具层重新实现 docx 语义。
- 后续若提供 Agent 工具，只应在 `toolkit` 增加一个粗粒度 compare 工具，底层调用 `core` compare API。

## Acceptance Criteria

- [ ] AC1 用户可以提供旧版与新版 `docx`，得到一份新的结果 `docx`。
- [ ] AC2 结果文档在 Word 中以标准修订方式展示差异，而不是普通改写后的最终态。
- [ ] AC3 至少第一版承诺支持的结构范围内，compare 产生的修订可被 `trackedChanges().list()` 正确读回。
- [ ] AC4 至少第一版承诺支持的文本类修订，可被现有 accept/reject 流程处理。
- [ ] AC5 超出第一版范围的结构有明确、可测试的边界行为，不做隐式错误结果。
