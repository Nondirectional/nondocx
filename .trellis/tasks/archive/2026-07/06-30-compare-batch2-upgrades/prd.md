# compare batch 2 upgrades

## Goal

在 compare 第一版已可用的基础上，规划第二批升级范围，收敛出一组边界清晰、可分批验证的 compare 增强能力。

## User Value

- 让 compare 从“正文纯文本段落 MVP”走向更接近真实审阅场景的能力集。
- 在不破坏现有 tracked changes 模型的前提下，逐步提升 compare 的覆盖面与可读性。
- 避免一次性扩太多结构，导致结果不稳定或边界重新变模糊。

## Confirmed Facts

- 当前 compare 公开入口是 `Docx.compare(Path oldPath, Path newPath)` 和 `Docx.compare(Path oldPath, Path newPath, String author)`。
- 当前默认作者常量是 `Docx.DEFAULT_COMPARE_AUTHOR = "nondocx compare"`。
- 当前 compare 结果以旧文档为基线，通过克隆旧文档后在结果文档上重放修订生成。
- 当前 compare 内核位于 `nondocx-core/src/main/java/com/non/docx/core/internal/compare/DocumentCompareSupport.java`。
- 当前段落对齐策略是：以 `Paragraph.text()` 做顺序保持的 LCS 锚点对齐。
- 当前段内 diff 策略是：按 Unicode code point 做字符级 LCS diff，并重放为 `EQUAL / DELETE / INSERT` 三类片段。
- 当前“差异段落改写”策略是：清空目标段落内联内容，再按 diff 结果重建纯文本 run / insertion / deletion。
- 当前 `Paragraph.addInsertion(author, text)` 产生的新插入 run 默认不复制外部样式。
- 当前 `Run.replaceTracked(author, newText)` 会把原 run 的六种内联样式复制到新插入 run。
- 当前项目已经具备 run 属性修订能力：可通过 `Run.style()` + `Run.commitStyleAsTracked(author, before)` 写出 `rPrChange`，并由 `acceptProperty/rejectProperty` 处理。
- 当前 `Paragraph.text()` 是段落纯文本视图，不保留 run 边界与 run 样式信息；现有 compare 正是基于这层纯文本做段内 diff。
- 当前仓库没有现成的“字符区间 ↔ run 样式片段”映射框架；样式复制能力主要体现在 `Run.style()` 快照和个别写路径（如 `replaceTracked`）中。
- 当前 compare 只安全支持正文中的纯文本 run 段落。
- 当前如果段落含 `Hyperlink`，或含非 `Run` 内联元素，或 run 内含 field 相关结构，则该段发生差异时会跳过改写，结果保留旧段落原样。
- 当前表格、页眉页脚、批注、图片、分节、样式变更、move 识别都不参与 compare。
- 当前已有回归测试覆盖：无差异、默认/显式作者、文本替换、跨表格插入段落、旧表格保留、超链接段落跳过、空白作者报错。
- 第一版设计文档已经明确其定位是“边界诚实的 compare MVP”，而不是 Word Compare 的全量兼容实现。

## Requirements

### R1. 第二批升级必须先收敛主目标

- [x] 第二批升级主轴优先选择“正文能力扩展 + 结果保真度提升”，暂不以表格 / 页眉页脚等大结构 compare 为主目标。
- [x] 在该主轴下，第一优先先做“现有纯文本段落的样式保真”，再考虑更多正文结构可 compare。
- [x] “样式保真”第一阶段仅保留原有可见格式，暂不把样式差异本身输出为 tracked property changes（`rPrChange`）。
- [x] 第二批暂不引入 `CompareOptions` 等新的公开配置对象，继续保持当前最小 compare API 表面。
- [x] 第二批规划先收口为“简单纯文本段落的样式保真增强”这一条主交付，不并行追加超链接段落等正文结构扩展里程碑。
- [x] 新增能力围绕单一主轴展开，避免同时引入多类高耦合升级导致 scope 失控。

### R2. 升级后的边界仍需保持诚实

- [x] 插入类修订的可见样式优先采用新版对应文本原本的 run 样式，而不是旧文档上下文样式。
- [x] 第二批样式保真先只承诺“两侧都能归约成单一 run 样式的纯文本段落”。
- [x] “单一样式纯文本段落”不要求物理上只有一个 run；允许多个连续 run，只要它们的 `Run.style()` 完全相同，仍视为可支持场景。
- [x] 第二批“样式保真”范围先只覆盖 run 级可见字符样式（`Run.style()` 六样式 / OOXML `w:rPr`），暂不把段落级格式（`w:pPr`）纳入 compare。
- [x] 当两侧段落文本完全相同、只有 run 级样式不同，而本批又不输出 `rPrChange` 时，继续视为“无差异”。
- [x] 对于“文本替换”场景，删除片段继续显示旧文档原 run 的样式，插入片段显示新文档原 run 的样式。
- [x] 当差异段落被重放成 `EQUAL / DEL / INS` 片段时，未改动的 `EQUAL` 文本继续沿用旧文档样式。
- [x] 第二批样式保真同时覆盖“整段新增 / 整段删除”场景：当整段可归约成单一样式纯文本段落时，整段 insertion 保留新版 run 样式，整段 deletion 保留旧版 run 样式。
- [x] 第二批样式保真初期不承诺覆盖“多 run 混合样式段落”；先覆盖单样式 / 易映射场景，复杂混排继续诚实降级。
- [x] 当文本差异落入“复杂混排、当前无法可靠保样式”的场景时，继续整段跳过 compare，不输出“文本对了但样式被简化”的半正确结果。
- [x] 对于第二批仍无法可靠处理而被跳过的复杂段落，默认 compare 继续保持静默跳过，不新增运行时报告或异常信号；边界通过文档、Javadoc、示例明确暴露。
- [x] 上述“支持单一样式纯文本段落、复杂段落跳过”的默认语义，需显式写入对外文档、Javadoc 与示例。
- [x] 对新增支持的结构，需明确 compare 语义、结果基线和可测试行为。（“单一样式纯文本段落”语义已在 design.md §4 / Javadoc / example 中明确）
- [x] 对仍不支持的结构，需继续保持显式边界，不能让用户误以为已参与 compare。（复杂混排 / 超链接 / field 等跳过语义已在 Javadoc 与 example 显式写出）

### R3. 必须复用现有 tracked changes 语义

- [x] 第二批升级仍优先产出标准 tracked changes，而不是引入私有 diff 表达。
- [x] 如新增结构暂时无法映射为现有 tracked changes 模型，需要在规划阶段提前暴露这个约束。（本批未引入无法映射的结构，仍复用 `<w:ins>` / `<w:del>`）

### R4. 规划产物需要支持分批实施

- [x] 本批 planning 收口为单一主交付，不额外拆 parent/child；如后续扩到超链接/field 等结构，再作为后续独立里程碑。
- [x] 每个候选增强项都要能写出单独 acceptance criteria，而不是笼统写“compare 更强了”。（本批收口为单一主交付，acceptance criteria 见 design.md §8 与 implement.md §4 测试矩阵）

## Candidate Directions

- 扩展结构覆盖：表格内容 compare、图片/绘图、页眉页脚、批注、分节。
- 扩展正文能力：支持超链接段落、field 段落、更多复杂 inline 结构。
- 提升保真度：保留 run 样式、减少“清空后重建”带来的格式丢失。
- 提升 diff 语义：词级 diff、移动识别、相似段落重配对、替换可读性优化。
- 提升 API / 策略：`CompareOptions`、不支持结构处理策略、报告输出。

## Likely Out Of Scope

- 一次性做到与 Microsoft Word Compare 行为完全一致。
- 在未先稳定语义模型前，同时推进多种复杂结构 compare。
- 为了覆盖边缘结构而推翻当前 tracked changes 基线与公开 API。

## Open Questions

当前已无阻塞性产品开放问题；下一步进入 `design.md` / `implement.md` 评审。

## Acceptance Criteria

- [x] AC1 `prd.md` 明确第二批升级的主目标、边界和不做项。
- [x] AC2 `design.md` 与 `implement.md` 围绕“简单纯文本段落的样式保真增强”这一单一主轴展开。
- [x] AC3 最终规划产出一组可测试、可分批交付的 compare 增强项，且本批只落一个主交付。
