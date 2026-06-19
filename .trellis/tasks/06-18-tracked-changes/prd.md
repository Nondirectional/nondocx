# core 修订(tracked changes)封装（父任务）

## Goal

将 nondocx 当前对「修订（tracked changes）」的 **raw()-only / unsupported** 状态，规划并拆解为一组可独立验收的子任务，最终交付一套面向用户的一等公民封装。

这个父任务本身**不直接承载所有实现细节**；它负责：

- 保存源需求与总体目标
- 维护子任务拆分与边界
- 约束跨子任务的一致性
- 在所有子任务完成后做集成验收

## User Value

完成后，nondocx 用户应能以领域 API 而非裸 POI / CT 操作修订：

- **消费侧**：读取修订开关、枚举修订、按粒度 accept / reject
- **创作侧**：显式创建被追踪的插入 / 删除 / 替换
- **兼容性**：现有非修订读写 API 行为保持稳定，不因引入 tracked changes 而回归

## Confirmed Facts

- 当前 nondocx 对修订没有正式封装；相关能力主要停留在 `raw()` 逃生口与 unsupported 文案层面。
- 现有规范已把 tracked changes 视为 out-of-scope；本任务完成后需同步更新相关 spec。
- TOC 只读支持提供了一个现成模式：**公共 API 提供 POI-free 领域模型，CT 脏活集中到 `internal/poi/`**。
- 页眉页脚的读写分离提供了另一个边界模板：**只读枚举绝不偷偷改文档，破坏性写操作集中到明确入口**。
- POI 没有现成的 tracked changes accept / reject 高层 API；这部分需要在 nondocx 自己的实现边界内处理。

## Task Map（父 / 子任务拆分）

当前父任务拆分为以下子任务：

1. `06-18-tracked-changes-read`
   - 只读消费侧：`settings.xml` 开关读取 + 修订枚举基础能力
2. `06-18-tracked-changes-accept-text`
   - 文本类修订的 accept / reject：先聚焦 `w:ins` / `w:del` 与粒度（all / byAuthor / 单条）
3. `06-18-tracked-changes-authoring`
   - 创作侧显式 tracked API：插入 / 删除 / 替换（**仅文本类**）
4. `06-18-tracked-changes-advanced-types`
   - 高级修订类型：move、属性变更、`cellIns` / `cellDel`（**仅读 + accept/reject**）
5. `06-18-tracked-changes-cell-types`
   - 单元格结构类 cellIns/cellDel 读 + accept/reject、cellMerge 只读（advanced-types 的拆分）
6. `06-19-tracked-changes-authoring-advanced`
   - **高级类型的创作侧**：带格式插入、rPrChange、cellIns/cellDel、move（补齐 authoring 从「文本类」到「四类」的扩展）
7. `06-18-tracked-changes-docs-spec`
   - 文档、异常示例、spec 更新、最终收尾

## Requirements

### R1. 总体能力目标

- [ ] 最终交付覆盖**消费侧 + 创作侧**两条能力线。
- [ ] 消费侧最终应支持：开关读取、修订枚举、accept / reject。
- [ ] 创作侧最终应支持：显式创建被追踪的插入 / 删除 / 替换。

### R2. 子任务边界必须清晰

- [ ] 每个子任务都必须有**独立、可验证**的交付物与验收标准。
- [ ] 父任务负责跨子任务的 API 一致性与最终集成，不把多个子任务的具体实现细节重新堆回父任务里。
- [ ] 若后续发现某一块还能进一步独立验证，可以继续增补子任务，而不是把复杂度重新塞回单任务。

### R3. 跨子任务的一致性约束

- [ ] 用户可见的消费侧入口保持统一；不要让多个子任务各自发明不同入口。
- [ ] 创作侧继续坚持“**显式 tracked 方法**”路线；不引入“全局录制 / 自动追踪现有写操作”的魔法机制。
- [ ] 现有不带追踪的 API 行为保持不变；tracked changes 的引入不得改变现有默认写语义。
- [ ] 现有只读 API（如 `paragraph.text()` / `runs()`）行为保持不变；修订内容通过专门能力读取，而不是偷偷改变旧 API 含义。
- [ ] 公共 API 继续保持 POI-free；CT / XmlBeans 脏活集中在 `internal/poi/`。
- [ ] 对外异常继续遵守 `error-handling.md`；不要把 POI / XmlBeans 细节直接泄漏到公共表面。

### R4. 分阶段交付顺序

- [ ] 子任务的默认顺序为：只读消费侧 → 文本类 accept/reject → 创作侧 → 高级类型 → 文档/spec 收尾。
- [ ] 如果实现中发现高风险子题（例如 move / `*PrChange` / `cellIns`）需要再拆，优先继续拆分，而不是在单个子任务里硬塞。

### R5. 父任务必须保留的未决问题

- [ ] 在进入高级修订类型实现前，先明确 `TrackedChange` 如何表达**非文本类修订**（属性变更、移动、单元格级修订）。
- [ ] 在进入高级修订类型实现前，先明确 move / `*PrChange` / `cellIns` / `cellDel` 的支持语义与测试策略。

## Acceptance Criteria

- [ ] AC1 当前父任务已拆分为子任务，且每个子任务职责边界清晰、不重叠。
- [ ] AC2 父任务 `prd.md` 只保留需求、约束、验收与开放问题；实现细节迁移到 `design.md`。
- [ ] AC3 父任务 `design.md` 明确跨子任务契约、集成边界与高风险点，不与 `prd.md` 冲突。
- [ ] AC4 父任务 `implement.md` 明确执行顺序、前置研究点、验证命令与收尾门槛。
- [ ] AC5 所有子任务完成后，最终集成结果满足：
  - 用户能通过统一消费侧入口读取修订开关
  - 用户能枚举修订并按约定粒度执行 accept / reject
  - 用户能通过显式 tracked API 创建被追踪的写操作
  - 现有非修订 API 行为无回归
  - 相关 spec / 异常示例 / 文档与能力范围保持一致

## Out of Scope（父任务层）

- **批注（comments）** —— 独立 OOXML 机制，不与 tracked changes 混做。
- **修订标记的显示 / 隐藏** —— 偏渲染与视图层，不在当前封装范围。
- **“自动追踪所有现有写操作”** —— 当前路线明确排除。
- **在父任务中直接堆实现细节** —— 具体技术路线应下沉到子任务与 `design.md`。

## Open Questions

当前父任务级开放问题已收敛完成；若后续在 `06-18-tracked-changes-advanced-types` 的 research / fixture 阶段发现某一类高级修订复杂度显著超出预期，再回到 planning 继续拆分。

## Notes

- 技术边界、父子任务契约、风险收敛策略见 `design.md`。
- 执行顺序与开始实现前的准备项见 `implement.md`。
- 具体子任务的功能定义与验收标准应在各自目录下继续细化。