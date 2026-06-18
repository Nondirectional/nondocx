# tracked changes 创作侧显式 API

## Goal

为 tracked changes 建立**显式的文本类创作 API**，使用户能够主动写出被追踪的插入、删除与替换，而不是依赖“打开开关后自动追踪一切写操作”的魔法机制。

本子任务聚焦**文本类 authoring 路径**，不负责高级修订类型（move / 属性类 / `cellIns` / `cellDel`）的创建。

## User Value

完成后，用户可以在 nondocx 中直接：

- 创建带 tracked changes 元数据的插入内容
- 将现有文本 run 显式标记为删除
- 以“删除旧文本 + 插入新文本”的方式完成 tracked replacement

## Confirmed Facts

- 父任务已确定：创作侧方法住在 `Paragraph` / `Run` 等内容所属类型上，而不是住在 `TrackedChanges` 门面里。
- 父任务已确定：tracked changes 开关与显式 tracked 写 API **正交**；是否开启 `<w:trackChanges/>` 不自动改变普通写 API 语义。
- 本子任务只负责文本类 authoring，不负责 move、属性类、`cellIns` / `cellDel` 的创建。
- read 子任务提供统一只读模型；本子任务写出的文本类修订最终应能被 `TrackedChanges.list()` 读回。

## Requirements

### R1. 显式文本类创作入口

- [ ] 提供显式的文本类 tracked authoring 方法，不引入“全局录制 / 自动追踪”机制。
- [ ] 支持插入、删除、替换三类最小创作能力。
- [ ] 普通非 tracked 写 API（如 `addRun()` / `text()` 等）行为保持不变。

### R2. 方法落点与风格

- [ ] tracked authoring 方法住在内容所属类型上，而不是 `TrackedChanges` 门面上。
- [ ] 命名与返回值风格应与现有 nondocx fluent / domain API 保持一致。
- [ ] 新方法应尽量避免把“已失效的 live wrapper”暴露给调用方。

### R3. 修订元数据生成

- [ ] `author` 为显式必填参数。
- [ ] `date` 由 nondocx 自动生成。
- [ ] 写入时需要生成 OOXML 原始修订元数据（包括底层 `w:id`）。
- [ ] 这些写入出的修订在后续 read 子任务中可被重新枚举为 `TrackedChange`。

### R4. 兼容性与边界

- [ ] tracked authoring 与 `<w:trackChanges/>` 开关正交；开关不自动影响普通写 API。
- [ ] 公共 API 仍保持 POI-free；底层节点创建与重挂下沉到 `internal/poi/`。
- [ ] 本子任务不承担高级类型创作。
- [ ] 本子任务不承担 accept / reject。

### R5. 与后续子任务的衔接

- [ ] 本子任务写出的文本类修订应能被 read 子任务的统一模型读回。
- [ ] 本子任务生成的底层修订元数据不应与 read 子任务的“稳定 id”公共契约混淆。
- [ ] 文本类 authoring 的写入策略应能为后续高级类型创作保留扩展空间。

## Acceptance Criteria

- [ ] AC1 用户能够显式创建 tracked insertion。
- [ ] AC2 用户能够显式创建 tracked deletion。
- [ ] AC3 用户能够显式创建 tracked replacement（删除旧文本 + 插入新文本）。
- [ ] AC4 写出的 author / date / 底层 `w:id` 等修订元数据符合设计约定。
- [ ] AC5 这些写出的文本类修订可以被统一 `TrackedChanges.list()` 读回。
- [ ] AC6 现有普通写 API 行为无回归。

## Out of Scope

- move / `moveFrom` / `moveTo` 的创建
- 属性类修订的创建
- `cellIns` / `cellDel` 的创建
- tracked changes 开关写入 / 修改
- accept / reject
- “自动追踪所有既有写操作”

## Open Questions

当前子任务级开放问题已按推荐方案收敛完成：

- `replaceTracked(author, newText)` 返回新插入的 replacement `Run`
- “将现有 run 标记为删除”的入口不返回原 `Run`，避免暴露误导性的旧句柄
## Notes

- 本子任务建议补 `design.md` 与 `implement.md` 后再进入 `task.py start`。
- 该子任务的核心难点不在“多写几个方法”，而在于把 OOXML 修订元数据与 live wrapper 语义处理干净。