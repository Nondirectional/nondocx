# PRD：rPrChange——以修订方式变更既有文字格式（bold/italic 起步）

## 背景

「给既有文字加粗并留下修订痕迹」的严格形态是 rPrChange 修订节点（属性变更修订），不是 ins/del 文本修订。core 已有 `Run.commitStyleAsTracked(author, previousStyle)`（Run.java:322）与读侧 `TrackedChangeType.RPR_CHANGE` / `PropertyChangeDetails`；full 面已有一站式工具 `mark_style_change_tracked`（按 run_index 定位、无 run 拆分）。缺口：

1. compiler 面（PatchEngine，12 种 op）没有任何属性类修订 op；现有能力只有 `tracked.replace`（del+ins，非 rPrChange）与 `set_style`（整段、非修订）。
2. 修订观测面呈现层不完整：view REVISION 详情对 PropertyChangeDetails 不输出属性摘要；compiler 面 `read_document`（MarkdownView）完全不呈现修订——「写读同批」Convention（spec agent-single.md:329，§6.6/§6.13 三次复审误报教训）直接适用。

前驱任务 10-30-tracked-inline-format 的 Out of Scope 明确把本任务留作后续（其 prd.md:34-37）。

## 目标

1. **G1（写侧）**：compiler 面新增「以修订方式变更既有文字格式」的 op（bold/italic 起步），生成真正的 rPrChange 修订。
2. **G2（读侧观测）**：修订观测面同步表达格式变更类型——PropertyChangeDetails 在观测呈现面可见、可核对（含 compiler 面 read_document 的修订证据行），写读同批落地。

## 需求

- **R1 新 op**：`edit_document` ops 数组新增 `tracked.set_style`（独立 op）：anchor + find 文本定位 + bold/italic 显式布尔 + author 必填；目标词与邻文同 run 时必须能只对该词生效（run 拆分）；参与段落锚点同批独占（claimAnchor）。
- **R2 观测面**：
  - R2a compiler 面 `read_document` 输出修订元数据行（文档级注释行，覆盖全部修订类型，PROPERTY 类带新旧属性摘要）。
  - R2b full 面 view 工具 REVISION 详情对 PropertyChangeDetails 输出属性摘要（kind + 新/旧 summary），使格式变更类型可核对。
- **R3 eval 对照**：baseline 新增 `tracked-bold-phrase`（以修订方式加粗既有词，对照 `bold-existing-phrase` 非修订版），check 必须断言「RPR_CHANGE 存在且无 DEL 修订」堵住 `tracked.replace` 身份替换 workaround（§6.14 已实证可冒充）；计数锁 25→26。
- **R4 文档/spec 同步**：docs/07（op 集 v4→v5、op 枚举/JSON 示例/描述段、read_document 修订行说明）、docs/11 新增 §6.16、spec agent-single（compiler surface Convention + 评测基线条数 + runId 链式追加）。docs/03 不动（core 零新增）。
- **R5 质量门禁**：spotless:apply 后全量 `mvn test` 全绿；eval 前 `mvn install` 并复跑 baseline 全量（26 条），提交附 runId；中文提交、scope 前缀。

## 验收标准

- AC1：`tracked.set_style {anchor, find, bold:true, author}` 对「目标词与邻文同 run」的段落执行后：save+reopen 文档存在 1 条 RPR_CHANGE（author 正确、newSummary 含 b、oldSummary 不含 b），目标词加粗，邻文不加粗（严格子集），段落 text() 不变。
- AC2：同批多个改文本 op 与 `tracked.set_style` 指向同锚点时，preflight 整体拒绝并提示拆批（与 tracked.* 一致）；bold/italic 均缺省时 preflight 拒绝（无格式变更）；find 未命中时 preflight 拒绝并教学正确写法。
- AC3：read_document 对含 rPrChange 的文档输出修订元数据行（type=RPR_CHANGE、author、新旧属性摘要可见）；无修订文档不引入噪声（按 design 定形）。
- AC4：view REVISION 详情对 RPR_CHANGE 输出 property 摘要（RUN_PROPERTIES 新[...] 旧[...]），既有 TextChangeDetails 呈现不回归。
- AC5：`tracked-bold-phrase` 入组：`EvalTasksTest` 计数锁 26；capabilityGap 标记遵循 §6.12 复跑归因（初判 flag=true，seed 与 bold-existing-phrase 同构：目标词与邻文同 run，full 面物理不可达——无 run 拆分；复跑证据落地后确认）。
- AC6：accept 后样式保留、修订消失；reject 后还原旧样式（断言一律 save+reopen 口径，core 先例 TrackedAuthoringAdvancedTest:66-95）。
- AC7：全量 `mvn test` 全绿（spotless:apply 之后）；baseline 复跑 26 条，runId 记录于 spec 与提交。

## 非目标（Out of Scope）

- 取消加粗/取消斜体的观测方向性摘要（PropertyChangeDetails.summarize 只看本地名，[b]→[b] 不可区分方向）：不改 core 的 summarize 契约，记为已知边界；op 参数本身支持 false（w:val="off"），方向证据依赖 run 实际属性呈现。
- pPrChange / sectPrChange 读侧（POI 精简 schema 缺失，v1 已知边界，维持）。
- underline/font/size/color 四属性的 op 暴露（core 已支持六属性，op 起步只暴露 bold/italic；扩列是纯增量）。
- full 面 `mark_style_change_tracked` 的取消方向缺口（`if (bold)` 只认 true）——full 面工具改造另行任务。
- 新 Example 类（eval 为硬性配套，docs+eval 已覆盖表达，先例 5fd458c 亦无新 Example）。

## 自主决策记录（无人值守拍板，详见 design.md）

| # | 问题 | 决策 |
|---|---|---|
| D1 | op 形态 | 独立 op `tracked.set_style`（用户推荐独立 op；命名贴 tracked.* 修订族命名空间，与非修订 set_style 对称） |
| D2 | 格式范围 | bold/italic 起步（用户推荐）；Boolean 三态：显式传 false = 取消（写 w:val="off"），缺省 = 不动 |
| D3 | 参数形态 | 显式布尔字段（非行内标记），贴近 mark_style_change_tracked/set_style 惯例，且避免滑向 tracked.replace workaround |
| D4 | claimAnchor | 参与段落锚点独占（拆 run 重建 run 结构，保守与 tracked.* 同列） |
| D5 | read_document 修订行 | 文档级注释行、覆盖全部修订类型（写读同批 + tracked.* 家族整体受益） |
| D6 | core 改动 | 零新增零改动（基建完备；summarize 方向性缺口以已知边界记档） |
| D7 | capabilityGap 初判 | flag=true（seed 同构 bold-existing-phrase，full 面物理不可达），复跑归因后确认 |
| D8 | 分支 | 沿用 feat/paragraph-spacing-hf-refs 直接提交（近期多任务先例均在此分支滚动） |
