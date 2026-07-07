# Implement Plan — compare batch 2 样式保真增强

> 本文件记录第二批 compare 升级的建议实施顺序、验证命令、风险回退点与 start 前门槛。目标不是扩 compare 结构覆盖面，而是在现有 compare 主路径上，把“简单纯文本段落”的 run 级视觉样式保真稳定落地。

## 1. Start 前门槛

- [x] `prd.md` / `design.md` / `implement.md` 已评审通过
- [x] 已确认第二批只做“简单纯文本段落的样式保真增强”
- [x] 已确认不新增 `CompareOptions` 或其它 compare 公开配置对象
- [x] 已确认纯样式变化仍视为“无差异”
- [x] 已确认复杂混排 / 复杂 inline 段落继续静默跳过
- [x] 已确认视觉样式保真优先于 run 物理分段保真

## 2. 建议实现顺序

### Step 1 — 先锁住对外契约

- [x] 更新 `Docx.compare(...)` 的 Javadoc
- [x] 明确写清：
  - [x] 仍以旧文档为基线
  - [x] 第二批只对简单纯文本段落保 run 级样式
  - [x] 复杂段落仍静默跳过
  - [x] 纯样式变化仍不生成差异
- [x] 更新 example / 文档的 compare 边界说明

目标：先把这批最容易被误解的默认语义固定下来，避免代码写完才补契约。

### Step 2 — 抽出“单一样式纯文本段落”判定

- [x] 在 `internal/compare/` 下新增或扩展内部辅助类型
- [x] 实现“段落是否可归约成单一样式纯文本段落”的判定逻辑
- [x] 判定规则至少包括：
  - [x] `inlineElements()` 全是 `Run`
  - [x] 不含 `Hyperlink`
  - [x] 不含 `Image`
  - [x] run 不含 field 结构
  - [x] 全部 run 的 `Run.style()` 完全一致
- [x] 若通过，返回统一 `RunStyle`
- [x] 若失败，明确标记为“不支持样式保真”

建议测试：

- [ ] 单 run 纯文本段落 → 支持
- [ ] 多 run 且样式完全一致 → 支持
- [ ] 多 run 且样式不同 → 不支持
- [ ] 超链接段落 → 不支持
- [ ] 图片段落 → 不支持
- [ ] field 段落 → 不支持

### Step 3 — 提供统一样式应用小工具

- [x] 在 compare 内部补一个“把 `RunStyle` 应用到某个 `Run`”的小工具
- [x] 只覆盖六样式：
  - [x] `bold`
  - [x] `italic`
  - [x] `underline`
  - [x] `font`
  - [x] `fontSize`
  - [x] `color`
- [x] 明确空值 / false 的处理语义

目标：避免在 compare 重放代码里到处散落 `if (style.xxx())` 逻辑。

### Step 4 — 改造支持段落的差异重放路径

- [x] 保持现有段落对齐与文本 diff 不变
- [x] 仅改造“支持段落”的段内重放逻辑：
  - [x] `EQUAL(text)` → `addRun(text)` 后应用 `oldStyle`
  - [x] `DELETE(text)` → `addRun(text)` 后应用 `oldStyle`，再 `addDeletion(author, run)`
  - [x] `INSERT(text)` → `addInsertion(author, text)`，对返回 run 应用 `newStyle`
- [x] 确认删除看旧、插入看新、未改看旧

建议测试：

- [ ] 段内插入保留新版样式
- [ ] 段内删除保留旧版样式
- [ ] 段内替换产出 `DEL + INS`，且两侧样式来源正确

### Step 5 — 改造整段新增 / 删除路径

- [x] 整段新增时：
  - [x] 若新段支持单一样式保真，则插入段使用新版统一样式
  - [x] 若不支持，则沿用现有边界（插入裸文本或继续保持旧逻辑；具体以 design 统一）
- [x] 整段删除时：
  - [x] 若旧段支持单一样式保真，则删除段使用旧版统一样式
  - [x] 若不支持，则沿用现有边界

建议测试：

- [ ] 文首整段新增保留新版样式
- [ ] 中间整段新增保留新版样式
- [ ] 文末整段新增保留新版样式
- [ ] 整段删除保留旧版样式

### Step 6 — 保持不支持段落的诚实边界

- [x] 对复杂混排段落：
  - [x] 文本无差异 → 原样保留
  - [x] 文本有差异 → 继续整段跳过 compare
- [x] 不引入“文本正确但样式降级”的后备路径
- [x] 不引入 runtime report / warning / exception

建议测试：

- [ ] 多样式 run 段落发生差异 → 无修订，保留旧原样
- [ ] 多样式 run 段落无差异 → 原样保留

### Step 7 — 锁住“纯样式变化无差异”

- [x] 为“文本完全相同、样式不同”的场景补回归测试
- [x] 明确断言：
  - [x] `trackedChanges().list()` 为空
  - [x] 结果文档仍等于旧基线语义

### Step 8 — 文档与示例收尾

- [x] 更新 compare 示例，展示：
  - [x] 简单纯文本段落样式保真
  - [x] 复杂段落继续跳过
- [x] 更新 API 文档 / FAQ 边界说明
- [ ] 如实现中沉淀出可复用 compare 边界认知，回写 spec（待后续 spec 收口）

## 3. 建议文件落点

推荐最小改动面：

- [x] `nondocx-core/src/main/java/com/non/docx/core/internal/compare/DocumentCompareSupport.java`
- [x] `nondocx-core/src/main/java/com/non/docx/core/Docx.java`（Javadoc / 对外说明）
- [x] `nondocx-core/src/test/java/com/non/docx/core/DocxCompareTest.java`
- [x] 必要时新增 compare 样式专项测试类（并入 `DocxCompareTest`，未单独建类）
- [x] `nondocx-examples/src/main/java/com/non/docx/examples/DocxCompareExample.java`
- [ ] 相关 docs / FAQ / API reference（待文档收口阶段处理）

## 4. 建议测试矩阵

至少准备以下测试用例：

- [x] `compare_preservesNewStyleForInsertedTextInUniformParagraph`（实际命名 `comparePreservesNewStyleForInsertedTextInUniformParagraph`）
- [x] `compare_preservesOldStyleForDeletedTextInUniformParagraph`
- [x] `compare_replacementUsesOldStyleForDelAndNewStyleForIns`
- [x] `compare_equalSegmentsKeepOldStyle`（由 `comparePreservesOldStyleForDeletedTextInUniformParagraph` 的 EQUAL 段断言覆盖）
- [x] `compare_insertedParagraphUsesNewUniformStyle`
- [x] `compare_deletedParagraphUsesOldUniformStyle`
- [x] `compare_uniformParagraphMayContainMultipleEquivalentRuns`
- [x] `compare_mixedStyleParagraphStillSkipsRewrite`
- [x] `compare_styleOnlyChangeStillProducesNoDiff`
- [x] `compare_resultMayCoalesceRunBoundariesButKeepsVisualStyle`

验证层次：

- [x] `trackedChanges().list()` 类型 / 数量 / 作者断言
- [x] reopen 后 `Run.style()` 断言
- [ ] 必要时 POI / OOXML 交叉验证 `w:rPr`（当前 reopen 读回 `Run.style()` 已等价覆盖）

## 5. 建议验证命令

```bash
python3 ./.trellis/scripts/task.py validate 06-30-compare-batch2-upgrades
mvn -pl nondocx-core -Dtest='*Compare*Test,*Run*Test,*Paragraph*Test,*Tracked*Test' test
```

若文档示例也更新，可补：

```bash
mvn -pl nondocx-examples test
```

## 6. 风险观察点

- [ ] 不要把“同样式多 run”误判成复杂段落，导致支持范围过窄
- [ ] 不要把“多样式混排”误判成简单段落，导致输出半正确结果
- [ ] 不要在 `addDeletion(...)` / `addInsertion(...)` 之后丢掉样式应用时机
- [ ] 不要把“视觉样式保真”偷偷扩成“run 分段保真”
- [ ] 不要让文档只写“支持样式保真”，却不写“复杂段落仍跳过”

## 7. Rollback / 回退策略

- 若“多 run 同样式也支持”的判定实现不稳，可临时回退为“仅单 run 支持”，但要在文档中显式写明，这是降级，不是默认设计。
- 若 deletion / insertion 的样式落盘不稳定，优先保住“删除看旧 / 插入看新”的语义，再排查底层 `w:rPr` 写入，不要退回无样式实现。
- 若新增测试暴露 compare 现有对齐层问题，不要在本批顺手扩为新的段落对齐工程；把问题单独记成后续任务。

## 8. Ready-to-start 判定

- [x] “简单纯文本段落”的定义已评审通过
- [x] “删除看旧 / 插入看新 / 未改看旧”的语义已评审通过
- [x] “复杂段落静默跳过”已评审通过
- [x] 文档 / Javadoc 必须同步更新的要求已接受
- [x] `design.md` / `implement.md` 与 `prd.md` 一致
- [x] 用户明确同意进入 `task.py start` / Phase 2

## 9. 验证结果（实现完成后记录）

- 核心 compare 测试：`DocxCompareTest` 17 个测试全部通过（含 9 个新增样式保真用例）。
- 回归套件 `*Compare*Test,*Run*Test,*Paragraph*Test,*Tracked*Test`：128 个测试全部通过，0 失败。
- `nondocx-core` 完整测试套件：306 个测试全部通过。
- example 模块 `DocxCompareExample` main 运行正常：EQUAL 段保留旧版粗体样式，INS 段采用新版样式，表格保持旧值。
- Spotless 格式检查通过（`nondocx-core` / `nondocx-examples` 均已 `spotless:apply`）。
