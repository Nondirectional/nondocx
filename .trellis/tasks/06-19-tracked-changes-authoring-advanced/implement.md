# Implement Plan — tracked changes 高级类型创作

> 配套 `prd.md` / `design.md`。本文件记录四类高级修订创作的有序执行计划、研究探针、验证命令与回退点。

## 0. Start 前门槛

- [x] `prd.md` 已写
- [x] `design.md` 已写
- [x] `implement.md` 已写(本文件)
- [ ] `implement.jsonl` / `check.jsonl` 已补真实条目
- [ ] 前序 authoring(文本类)/read/accept/advanced-types/cell 子任务契约已稳定(本子任务复用其 `nextRevisionId`、`TrackedChange` 模型、read walker、accept/reject)
- [ ] 用户已评审三件套并同意 start

## 1. research-first 探针(先验证,再写码)

四类里 rPrChange 与 move 的语义最微妙,实现前必须用探针确认真实 OOXML 结构与 accept/reject 后果。沿用 advanced-types/cell 的「探针先于生产代码、确认后删除」惯例。

### Step 1 — rPrChange 探针:旧 rPr 树怎么挂

- [ ] 写一次性探针 `AdvancedAuthoringProbeTest`:
  - [ ] 构造一个 run,设 bold。
  - [ ] 手工(在探针里用 XmlCursor / CTRPr.addNewRPrChange())建 rPrChange,旧 rPr 树放 `<w:vanish/>`(模拟「原本隐藏,现在加粗」)。
  - [ ] dump 原始 XML,确认 `<w:rPrChange><w:rPr><w:vanish/></w:rPr></w:rPrChange>` 形态与 design §3.2 一致。
  - [ ] save → reopen,确认 `list()` 读回 `RPR_CHANGE` + `PropertyChangeDetails`(newSummary=bold、oldSummary=vanish)。
  - [ ] 调 `rejectProperty`:确认 run 的 rPr 被旧树覆盖(回到 vanish、bold 消失)。
  - [ ] 调 `acceptProperty`:确认 rPrChange 标记消失、bold 保留。
- [ ] **关键验证**:旧 rPr 树是否要剔除 rPrChange 自身(防递归嵌套)?探针 dump 确认结构不嵌套。
- [ ] 结论落入 `research/authoring-forms.md`,删除探针。

### Step 2 — move 探针:rangeStart/End name 配对与四件结构

- [ ] 在同一探针里构造 move:
  - [ ] 源段建 `moveFromRangeStart(name=move1)` + `moveFrom`(内 run 用 delText) + `moveFromRangeEnd`。
  - [ ] 目标段建 `moveToRangeStart(name=move1)` + `moveTo`(内 run 用 t) + `moveToRangeEnd`。
  - [ ] dump 两段 XML,确认四件结构与 name 配对(design §3.4)。
  - [ ] save → reopen,确认 `list()` 读回 `MOVE_FROM` + `MOVE_TO` 两条。
  - [ ] 调 `accept`(通用 accept 走 move 配对):确认源端 moveFrom 文本移除、目标端 moveTo 文本保留(accept 后文字出现在目标段)。
  - [ ] 调 `reject`:确认源端恢复、目标端消失。
- [ ] **关键验证**:name 重复(文档已有 move1)时 Word/读侧行为;实现需查重或强前缀。
- [ ] 结论落入 research,删除探针。

### Step 3 — cell 探针(轻量,cell 结构已在 N16 验证)

- [ ] 复用 N16 的 cellIns/cellDel 形态结论(`<w:tcPr><w:cellIns .../></w:tcPr>`)。本步只验证**创作路径**:`CTTcPr.addNewCellIns()` 建出的节点能否被 read 读回 + accept/reject 正确(基本是 N16 的反向)。
- [ ] 可与 Step 1/2 合并进同一探针。

## 2. 实现(按风险从低到高)

### Step 4 — 带格式插入(基本零改动,先做)

- [ ] 验证现有 `Paragraph.addInsertion(author, text)` 返回的 Run 链式设样式后,带样式 ins 能 round-trip。
- [ ] 补 `TrackedAuthoringAdvancedTest` 用例:`addInsertion(...).bold().color("FF0000")` → read 回 `INS` + 文本 → accept 后 run 保留样式。
- [ ] 无新代码(除非探针发现 ins 内 run 的样式设置有坑)。

### Step 5 — 单元格修订(结构最简)

- [ ] `Cell.markInserted(author)` / `Cell.markDeleted(author)`:调 `TrackedChangeNodes.markCellIns(tcPr, ...)` / `markCellDel(...)`。
- [ ] `TrackedChangeNodes.markCellIns/markCellDel`:建 `CTTrackChange`、设 id/author/date。
- [ ] round-trip 测试:read 回 `CELL_INS`/`CELL_DEL` + `CellChangeDetails`;accept/reject 作用于 tc(复用 N16 验证的手术)。
- [ ] cellMerge 不提供方法(Cell 上无 markMerged)。

### Step 6 — 属性修订 rPrChange(语义最微妙)

- [ ] `Run.commitStyleAsTracked(author, RunStyle previousStyle)`:
  - 快照当前 rPr(新值)。
  - 调 `TrackedChangeNodes.commitRPrChange(CTR, previousStyle, author, date, id)`。
- [ ] `TrackedChangeNodes.commitRPrChange`:建 `CTRPrChange`、把 previousStyle 渲染成旧 rPr 树挂进 `rPrChange/rPr`(剔除 rPrChange 自身防递归)、设 id/author/date。
- [ ] round-trip 测试:read 回 `RPR_CHANGE` + `PropertyChangeDetails`(new/old 摘要对);`acceptProperty` 删标记保留新、`rejectProperty` 用旧树覆盖。
- [ ] 调用示例(写进示例 + 测试):`RunStyle before = run.style(); run.bold(); run.commitStyleAsTracked("甲", before);`

### Step 7 — 移动修订 move(最复杂)

- [ ] `Paragraph.moveRunsFrom(author, sourceParagraph, List<Run> runs)`:
  - 校验 runs 都属 sourceParagraph。
  - 生成唯一 name(查重或强前缀如 `_move_<id>`)。
  - 调 `TrackedChangeNodes.moveRuns(targetCTP, sourceCTP, runCTRs, author, date, baseId, name)`。
  - 返回新插入的 run 列表(目标端 moveTo 内的 runs)。
- [ ] `TrackedChangeNodes.moveRuns`:
  - 源段:建 moveFromRangeStart(name)、把 runs 改 delText、迁入 moveFrom(CTRunTrackChange)、建 moveFromRangeEnd。
  - 目标段:建 moveToRangeStart(name)、建 moveTo(CTRunTrackChange)+ 复制 runs 文本(用 t)、建 moveToRangeEnd。
  - id 分配:rangeStart/End + moveFrom/moveTo 各用 nextRevisionId 递增。
- [ ] round-trip 测试:read 回 `MOVE_FROM` + `MOVE_TO` 配对;`accept` 联动(源删目标留);`reject` 联动(源留目标删)。

## 3. 闭环集成验证(每类都过)

每类创作方法必须完整过四步:
- [ ] 创作(in-memory)
- [ ] save → reopen
- [ ] `list()` 读回 type/details/location 正确
- [ ] 对应 accept/reject 处理后文档结构正确

## 4. 文档与 spec 同步

- [ ] `poi-bridge.md`:新增 N17,记录四类创作的 CT 达成性 + rPrChange 旧值树语义 + move 四件配对。
- [ ] 更新 N15/N16 的「创作侧」边界:rPrChange/cellIns/cellDel/move 创作已支持;cellMerge/pPrChange 等仍不创作。
- [ ] `error-handling.md`:新方法的异常(author 空 / run 不属源段 / cellMerge)消息模板。
- [ ] README + 验收示例:`TrackedAuthoringAdvancedExample`(四类各一演示)。
- [ ] DocxAgentTools:四类创作加为工具(I 组,可选,若 H 组需扩展)。

## 5. 验证命令

```bash
python3 ./.trellis/scripts/task.py validate 06-19-tracked-changes-authoring-advanced

# 窄范围(创作 + 读 + accept/reject 联动)
mvn -pl nondocx-core -Dtest='*Authoring*,*TrackedChange*,*Cell*,*Advanced*' test

# 全量回归
mvn -pl nondocx-core test
```

## 6. fixture / 覆盖面清单

- [ ] 带格式 ins:read 回文本 + accept 后保留样式。
- [ ] rPrChange:read 回新/旧摘要;accept/reject 双向。
- [ ] cellIns/cellDel:read 回 kind;accept/reject 作用于 tc。
- [ ] move:read 回配对两条;accept/reject 联动(源/目标端正确)。
- [ ] move 边界:run 不属源段抛 IllegalArgumentException。
- [ ] 创作后 round-trip(location 经 save→reopen 仍正确)。
- [ ] 创作出的修订与既有修订(read/accept-text/cell)共存无干扰。

## 7. 风险观察点

- [ ] rPrChange 旧 rPr 树若嵌套 rPrChange 自身,accept/reject 会递归错乱——探针 Step 1 必验。
- [ ] move rangeStart/End name 重复会导致配对错乱——探针 Step 2 必验,实现需查重。
- [ ] moveFrom 内 run 的 t→delText 转换若漏(只改 moveFrom 不改文本),读侧读不出 delText,语义错。
- [ ] 创作 API 若泄漏 CT 类型到公共表面,违反 Rule 1——review 时查公共方法签名。
- [ ] commitStyleAsTracked 的 previousStyle 参数若调用方传错(传了改后的快照),旧值树=新值,rPrChange 形同虚设——Javadoc 必须强调「传改前快照」。

## 8. Rollback / 回退策略

- 若 move 的 name 配对在真实文档(非手搓 fixture)上不稳定,降级:move 只做「源端 moveFrom + 目标端 moveTo」(不带 rangeStart/End),诚实标记为「单 run move,范围配对有限」。但需确认读侧仍能配对。
- 若 rPrChange 旧值树管理复杂度失控,降级:本子任务只做带格式插入 + cell + move,rPrChange 创作移出(读侧仍支持 rPrChange,只是不能创作)。
- 若四类一次性风险过高,按 design 风险序分多个 PR:带格式插入+cell(低风险)先合,rPrChange+move(高风险)后合。

## 9. Ready-to-start 判定

- [ ] prd/design/implement 三件套齐全
- [ ] implement.jsonl / check.jsonl 已补
- [ ] 前序子任务契约稳定
- [ ] 至少基础 fixture 清单已准备(§6)
- [ ] 用户已评审并同意 start
