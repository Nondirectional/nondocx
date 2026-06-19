# tracked changes 高级修订类型

## Goal

补齐 tracked changes 中**高级修订类型**的统一模型与操作语义，使 nondocx 在文本类基础之外，继续覆盖：

- move（`moveFrom` / `moveTo`）
- 属性类修订（`rPrChange` / `pPrChange` / `sectPrChange` / `tblPrChange` / `trPrChange` / `tcPrChange`）
- 单元格结构类修订（`cellIns` / `cellDel`）

本子任务当前仍保持为**一个 research-first 的高级类型任务**；若实现中发现某一类复杂度显著独立，再回 planning 继续拆分。

## User Value

完成后，用户将不再只能处理最基础的文本类 tracked changes，而是能够在统一 API 下：

- 识别并操作 move 类修订
- 识别并操作属性类修订
- 识别并操作单元格结构类修订

## Confirmed Facts

- 父任务已确定：当前不先拆成 `move` / `property-changes` / `cell-changes` 三个独立子任务，而是先保持一个 advanced-types 任务。
- 父任务已确定：`TrackedChange` 使用统一外壳 + `details()`，`type` 用细粒度 kind，`family` 用粗粒度分组。
- `TrackedChange.location` 已定为 path / segment 结构，属性目标不放进 `location`，而放进 `PropertyChangeDetails`。
- 文本类 accept / reject 留在 `06-18-tracked-changes-accept-text`；本子任务负责高级类型的读/写语义补齐。

## Requirements

### R1. 高级类型覆盖范围

- [ ] 覆盖 move（`moveFrom` / `moveTo`）。
- [ ] 覆盖属性类修订（`rPrChange` / `pPrChange` / `sectPrChange` / `tblPrChange` / `trPrChange` / `tcPrChange`）。
- [ ] 覆盖 `cellIns` / `cellDel`。

### R2. 统一模型补齐

- [ ] 在现有统一 `TrackedChange` 模型上补齐这些类型对应的 `details()` 子类型或字段语义。
- [ ] 不为了高级类型而推翻 `type/family/location/details()` 的既有分工。
- [ ] 属性目标继续放在 `PropertyChangeDetails`，不把属性语义塞回 `location`。

### R3. 统一门面下的操作语义

- [ ] 高级类型接入后，统一门面的单条 / 全量 / 按作者操作语义继续成立。
- [ ] move 的 pair 关系需在设计中明确。
- [ ] 属性类与单元格类的 accept / reject 语义需在设计中明确。

### R4. 研究优先与可回退拆分

- [ ] 在开始实现前，先通过 fixture / 研究把 move、属性类、cell 类的 OOXML 形态看清。
- [ ] 若实现中发现某一类高级修订复杂度独立且显著高于其他类，应回 planning 继续拆分，而不是在当前任务里硬塞。

### R5. 兼容性与边界

- [ ] 不引入新的第二套修订入口；继续沿用统一门面与统一读模型。
- [ ] 不修改普通非修订读/写 API 的默认语义。
- [ ] 不把高级类型需求反向污染文本类 accept/reject 的既有边界。

## Acceptance Criteria

- [ ] AC1 move 类型在统一模型中具备明确的读取与操作语义。
- [ ] AC2 属性类修订在统一模型中具备明确的读取与操作语义。
- [ ] AC3 `cellIns` / `cellDel` 在统一模型中具备明确的读取与操作语义。
- [ ] AC4 高级类型接入后，不推翻既有 `TrackedChange` / `location` / `details()` 分工。
- [ ] AC5 若某类高级修订被证明需要独立拆分，planning 工件会被回写更新，而不是在实现中静默扩 scope。

## Out of Scope

- tracked changes 文档与 spec 收尾
- tracked changes 开关写入 / 修改
- “自动追踪所有既有写操作”
- 新增第二套独立于统一门面的高级修订 API

## Open Questions

当前子任务级开放问题已按推荐方案收敛完成：

- move 以“每个具体节点一个 `TrackedChange`”进入统一模型，再由 `MoveChangeDetails` 表达配对关系
- 若 read 子任务第一版尚未完整列出所有高级类型，本子任务同时补齐其枚举能力与写语义
## Notes

- 本子任务强烈建议补 `design.md` 与 `implement.md` 后再进入 `task.py start`。
- 这是整个 tracked changes 项目里技术风险最高的子任务之一，研究与 fixture 先行比“先写代码”更重要。