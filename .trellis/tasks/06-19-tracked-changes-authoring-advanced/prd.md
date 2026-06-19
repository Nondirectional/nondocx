# PRD — tracked changes 高级类型创作(move / property / cell / 带格式插入)

> 父任务:`06-18-tracked-changes`(planning)。
>
> **背景**:前序子任务 `06-18-tracked-changes-authoring` 已交付**文本类**显式创作(`Paragraph.addInsertion` / `Paragraph.addDeletion` / `Run.replaceTracked`,commit `447369c`)。本子任务补齐**高级修订类型**的创作能力,把 tracked changes 的创作线从「文本类」扩展到「带格式 + 属性 + 单元格 + 移动」。
>
> 消费侧(read + accept/reject)四类全已就绪(read/accept-text/advanced-types/cell 子任务),本子任务只做**创作侧**。

## Goal

交付四类高级修订类型的显式创作 API,沿用父任务 R3「显式 tracked 方法、不引入全局录制魔法」的约束:

1. **带格式的插入修订** —— 插入带内联样式(bold/italic/underline/font/size/color)的 tracked run。
2. **属性类修订(rPrChange)** —— 把 run 的属性变更记为修订(保留旧值树)。
3. **单元格类修订(cellIns/cellDel)** —— 把单元格标记为插入/删除。
4. **移动类修订(move)** —— 把一组 run 从源段落移动到目标段落,产出配对的 moveFrom/moveTo。

## User Value

完成后,nondocx 的修订创作不再局限于「插入/删除普通文本」,能覆盖 Word 里常见的四类结构化修订场景。配合已就绪的 read/accept/reject,形成完整的「创作 → 消费」闭环。

## Confirmed Facts(已 javap 实测 POI 5.2.5 精简 jar)

| 类型 | CT 类 / 访问器 | 在 lite 5.2.5 jar 内 |
|---|---|---|
| 带格式插入 | 复用现成 `CTRunTrackChange`(`addInsertion` 已建好 ins 骨架) | ✅ |
| 属性(rPrChange) | `CTRPrChange` + `CTRPr.addNewRPrChange()` | ✅ |
| 单元格(cellIns/cellDel) | `CTTrackChange` + `CTTcPr.addNewCellCellIns()/addNewCellDel()` | ✅(cellMerge ❌ 缺失,见 N16) |
| 移动(move) | `CTMoveBookmark` + `CTMarkupRange` + `CTP.addNewMoveFromRangeStart/End()` + `addNewMoveToRangeStart/End()` + `addNewMoveFrom()`/`addNewMoveTo()` | ✅(全套件可达) |

- 前序 `addInsertion` 的实现已演示了「建修订节点 + 自动 date/w:id + 复制样式」的完整模式(`Run.replaceTracked` 在 `Run.java:251-269` 演示了插入后复制六样式)。
- `TrackedChangeNodes` 已有 `nextRevisionId(doc)` 与 `addInsertion`/`addDeletion` 内部 helper,可复用。

## Requirements

### R1. 四类创作能力

- [ ] **R1.1 带格式插入**:能插入带 bold/italic/underline/font/size/color 的 tracked run,且样式落在新 run 上。
- [ ] **R1.2 属性修订**:能把 run 的属性变更记为 rPrChange(旧值树正确保留),可被 read 读回、被 acceptProperty/rejectProperty 处理。
- [ ] **R1.3 单元格修订**:能把一个单元格标记为 cellIns/cellDel(设 id/author/date),可被 read 读回、被 acceptCell/rejectCell 处理。
- [ ] **R1.4 移动修订**:能把一组 run 从源移动到目标段落,产出配对的 moveFrom(+rangeStart/End)/moveTo(+rangeStart/End),可被 read 读回、被 accept/reject 配对联动处理。

### R2. 与现有约束的一致性

- [ ] 沿用「显式 tracked 方法」路线,不引入全局录制。
- [ ] 创作 API 保持 POI-free 公共表面;CT/XmlBeans 脏活在 `internal/poi/`。
- [ ] 创作出的修订必须能被**既有 read + accept/reject 正确读回处理**(跨子任务集成验证)。
- [ ] 现有普通写 API 行为不变。
- [ ] 对外异常遵守 `error-handling.md`。

## Acceptance Criteria

- [ ] AC1 四类创作 API 可用,每类有验收示例 + 单元测试。
- [ ] AC2 创作出的修订经 save→reopen round-trip 后,能被 `list()` 读回正确 type/details/location。
- [ ] AC3 创作出的修订能被对应的 accept/reject(按 family)正确处理,文档结构结果正确。
- [ ] AC4 cellMerge **不**创作(CT 类型缺失,诚实排除)。
- [ ] AC5 新 API 加入 DocxAgentTools(可选,若 H 组工具需要扩展)。

## Out of Scope

- **cellMerge 创作** —— CT 类型缺失(CTCellMergeTrackChange 不在精简 jar,见 N16)。
- **全局修订录制** —— 显式 tracked 方法路线明确排除(父任务 R3)。
- **pPrChange / sectPrChange / tblPrChange / trPrChange 创作** —— CT 类型全缺(同 N16),与本子任务正交。
- **修订标记的显示/隐藏** —— 渲染层,不在范围。

## Open Questions(已 brainstorm 收敛)

四个 API 形态决策均已收敛(详见 design.md):

- **带格式插入**:不重载。现有 `Paragraph.addInsertion(author, text)` 返回 `Run`,调用方链式 `newRun.bold().italic()...` 设样式即可——已隐式支持,本子任务只需补示例 + round-trip 验证。
- **属性修订(rPrChange)**:两步式。调用方先链式改 run 样式(`run.bold().italic()`),再调 `run.commitStyleAsTracked(author)` 把「改前/改后」两份 rPr 写成 rPrChange。实现需在改样式前捕获旧 rPr 快照。
- **单元格修订**:挂在 `Cell` 上。`cell.markInserted(author)` / `cell.markDeleted(author)`(cellMerge 不支持,CT 类型缺失)。
- **移动(move)**:`targetParagraph.moveRunsFrom(author, sourceParagraph, List<Run> runs)`——接受方是目标段(与 `addInsertion` 同类型),语义「把源段的这些 run 移到当前段」。
