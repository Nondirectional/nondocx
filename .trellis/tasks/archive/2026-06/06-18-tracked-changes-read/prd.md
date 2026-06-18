# tracked changes 只读消费侧

## Goal

为 tracked changes 建立一个**最小但稳定**的消费侧底座，使用户能够：

- 读取文档是否开启 tracked changes
- 枚举文档中的修订
- 通过稳定标识获取单条修订

本子任务优先解决“**枚举正确、顺序正确、定位正确**”三个基础问题，不在第一版里追求便利性筛选接口。

## User Value

完成后，用户无需先掉进 `raw()` / CT 层，就能以领域 API：

- 确认文档修订开关状态
- 看到文档里的修订列表
- 为后续单条 accept / reject 提供可引用的修订 id

## Confirmed Facts

- 父任务已确定：tracked changes 消费侧入口保持统一，走文档级入口。
- 父任务已确定：`TrackedChange` 采用**统一外壳 + `details()`**。
- 父任务已确定：`type` 采用**细粒度 OOXML kind**，同时提供粗粒度 `family`。
- 当前子任务第一版范围采用**最小集合**，不预先加入 `listByType` / `listByFamily` / `listByAuthor` 等便利筛选。
- 文档中修订的枚举顺序默认按**文档出现顺序**暴露。
- 当前子任务只负责 tracked changes 开关**读取**；`settings.xml` 开关写入不属于本子任务。
- 父任务已确定：`get(id)` 面向外部使用 nondocx 自己定义的稳定 id，而不是把 OOXML 原始 `w:id` 直接锁成公共契约。
- 父任务已确定：`TrackedChange.location` 第一版采用**结构化位置值对象**，不使用简单字符串摘要作为公共契约。
- 当前子任务已决定：`TrackedChange.location` 第一版从一开始就纳入**细粒度定位**，而不是只做 body / paragraph / table-cell 的粗粒度结构。
- 当前子任务已决定：`TrackedChange.location` 采用**path / segment 层级结构**，而不是固定字段平铺结构。
- 当前子任务已决定：对于属性类修订，`location` 只表达结构位置；属性目标（如 `rPr` / `pPr` / `sectPr`）留给 `PropertyChangeDetails` 表达。
- 当前子任务已决定：消费侧采用**文档级统一门面对象**，由 `Document.trackedChanges()` 返回并承载 `enabled()` / `list()` / `get(id)` 等读取能力。
- 当前子任务已决定：统一门面对象及其读取方法采用**最简命名风格**——`Document.trackedChanges()` / `enabled()` / `list()` / `get(id)`。
- 当前子任务已决定：稳定 id 采用**混合版生成策略**——对外是 nondocx 稳定 id，内部可组合原始节点信息（如 kind / 位置 / `w:id`）参与生成，但公共契约不直接等同于原始 `w:id`。
- 当前子任务已决定：`TrackedChangeLocation` 第一版的 segment 最小集合先固定为 `body / paragraph / table / row / cell / run` 六类结构段。
- 当前子任务已决定：segment 公共模型采用**通用 `kind + index` 结构**，而不是 `BodySegment` / `RunSegment` 等多个专用值对象类型。
- 当前子任务已决定：稳定 id 作为**不透明标识**对外暴露；只保证稳定引用能力，不承诺字符串格式可读、可解析或可长期依赖。

## Requirements

### R1. 最小公共读取能力

- [ ] 提供文档级统一入口访问 tracked changes 能力。
- [ ] 用户可以读取文档是否开启 tracked changes。
- [ ] 用户可以枚举文档中的全部修订。
- [ ] 用户可以通过统一入口枚举文档中的修订；第一版至少稳定覆盖文本类修订，高级类型由 `advanced-types` 子任务补齐或增强。

### R2. 第一版接口范围保持最小

- [ ] 第一版只承诺最小集合：开关读取 / `list()` / `get(id)`；不包含开关写入。
- [ ] 第一版**不承诺** `listByType` / `listByFamily` / `listByAuthor` 等便利筛选。
- [ ] 若后续需要便利筛选，应在不破坏最小集合契约的前提下增量添加。

### R3. 一致性与兼容性约束

- [ ] 枚举顺序按文档出现顺序返回，不按作者、类型或 id 重排。
- [ ] 旧 API（如 `paragraph.text()` / `runs()`）的默认语义保持不变，不通过改写旧 getter 暴露修订。
- [ ] 公共 API 保持 POI-free；CT / XmlBeans 细节下沉到 `internal/poi/`。
- [ ] `TrackedChange` 顶层字段与 `details()` 的职责分工遵守父任务设计，不把高级类型强压成文本语义。

### R4. 与后续子任务的衔接

- [ ] `get(id)` 返回的稳定标识可被后续 accept / reject 子任务复用。
- [ ] 当前子任务产出的 `TrackedChange` 列表模型应能承接后续文本类与高级类型扩展。
- [ ] `TrackedChange.location` 以结构化值对象暴露，可被测试与后续高级类型扩展稳定复用。

## Acceptance Criteria

- [ ] AC1 用户能通过统一文档级入口读取 tracked changes 开关状态。
- [ ] AC2 用户能通过统一入口获取按文档顺序排列的修订列表；第一版至少稳定覆盖文本类修订。
- [ ] AC3 用户能通过 id 获取单条修订；当 id 不存在时，抛 `NoSuchElementException`。
- [ ] AC4 第一版 API 不包含便利筛选方法（如 `listByType` / `listByFamily` / `listByAuthor`）。
- [ ] AC5 现有非修订读取 API 行为无回归。

## Out of Scope

- `accept` / `reject`
- tracked changes 创作侧 API
- move / `*PrChange` / `cellIns` / `cellDel` 的完整高级语义处理
- 第一版便利筛选能力（`listByType` / `listByFamily` / `listByAuthor`）
- tracked changes 开关写入 / 修改

## Open Questions

当前子任务级开放问题已按推荐方案收敛完成。
## Notes

- tracked changes 开关写入不在本子任务范围内；如后续提供写入能力，应在 design 中明确：这只是 `settings.xml` 的显式开关写入，不等于自动追踪。
- `get(id)` 查不到时抛 `NoSuchElementException`；这类按稳定标识精确定位的读取，被视为命中式访问而非可选 singleton 读取。
- `location` 采用 path / segment 结构；虽然会提供 `toString()` / 摘要显示，但字符串显示不应被视为稳定公共契约。
- 对于属性类修订，`location` 只负责“挂在哪个结构节点上”，具体属性目标由 `details()` 表达。
- 第一版 segment 最小集合固定为 `body / paragraph / table / row / cell / run`。
- 若高级类型尚未在本子任务中完整建模，由 `06-18-tracked-changes-advanced-types` 在同一统一入口上补齐，不另起第二套读取 API。
- 如后续发现 `TrackedChange.location` 语义还需更细设计，可再补 `design.md`。