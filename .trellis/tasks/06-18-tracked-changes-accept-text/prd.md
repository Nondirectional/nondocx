# tracked changes accept/reject 文本类

## Goal

在统一 `TrackedChanges` 门面上，先完成**文本类修订**的 accept / reject 能力，使用户能够对 `ins` / `del` 这两类最核心、最常见的 tracked changes 进行程序化应用。

本子任务聚焦“**文本类修订的破坏性写路径**”，不负责高级修订类型（move / 属性变更 / `cellIns` / `cellDel`）的完整语义。

## User Value

完成后，用户可以直接在 nondocx API 中：

- 一次性 accept / reject 全部文本类修订
- 按作者 accept / reject 文本类修订
- 按稳定 id accept / reject 单条文本类修订

而不必自己下沉到 CT / XmlBeans 层做 XML 手术。

## Confirmed Facts

- 父任务已确定：修订能力通过 `Document.trackedChanges()` 的统一门面暴露。
- `06-18-tracked-changes-read` 已负责统一只读模型、稳定 id、`type/family/details/location` 的基础契约。
- 本子任务只负责**文本类修订**，即以 `ins` / `del` 为核心的 accept / reject。
- 高级类型（move / 属性类 / `cellIns` / `cellDel`）留给 `06-18-tracked-changes-advanced-types`。
- accept / reject 是**破坏性写操作**；与 read 子任务的只读边界不同。

## Requirements

### R1. 文本类 accept / reject 入口

- [ ] 在统一门面上提供文本类修订的 accept / reject 能力。
- [ ] 支持 `acceptAll()` / `rejectAll()`。
- [ ] 支持 `acceptByAuthor(String)` / `rejectByAuthor(String)`。
- [ ] 支持 `accept(String id)` / `reject(String id)`，其中 `id` 为 nondocx 稳定 id。

### R2. 范围只覆盖文本类修订

- [ ] 第一版只承诺 `ins` / `del` 文本类修订。
- [ ] 文本类修订既包括正文段落内，也包括表格单元格中承载文本的 `ins` / `del`。
- [ ] 不承诺 move、属性变更、`cellIns` / `cellDel` 的 accept / reject 语义。

### R3. 粒度与筛选语义

- [ ] all 粒度作用于文档中全部**文本类**修订。
- [ ] byAuthor 粒度只作用于作者精确匹配的**文本类**修订。
- [ ] byAuthor 匹配采用大小写敏感的精确字符串匹配。
- [ ] 单条粒度按稳定 id 精确命中一条**文本类**修订。
- [ ] 当 `id` 不存在时，失败语义需在设计中明确。

### R4. 兼容性与边界

- [ ] 现有只读 API（`paragraph.text()` / `runs()` 等）默认语义不变。
- [ ] 公共 API 保持 POI-free；底层 CT 手术集中到 `internal/poi/`。
- [ ] 该子任务不引入新的写侧“自动追踪”机制。
- [ ] 该子任务不改变 tracked changes 开关本身的读取/写入语义。

### R5. 与后续子任务的衔接

- [ ] 本子任务产出的单条操作语义应能与 read 子任务的稳定 id 对齐。
- [ ] 高级修订类型后续接入时，不应推翻统一门面与稳定 id 契约。
- [ ] 文本类 accept / reject 的异常风格与行为边界，应能被 advanced-types 子任务延续。

## Acceptance Criteria

- [ ] AC1 `acceptAll()` / `rejectAll()` 能正确应用文档中的全部文本类修订。
- [ ] AC2 `acceptByAuthor(String)` / `rejectByAuthor(String)` 只作用于作者精确匹配的文本类修订。
- [ ] AC3 `accept(String id)` / `reject(String id)` 能按稳定 id 精确命中单条文本类修订。
- [ ] AC4 当 `id` 不存在时，失败行为与设计约定一致。
- [ ] AC5 文本类修订应用后，文档不再保留对应 `ins` / `del` 标记，内容结果符合 accept / reject 预期。
- [ ] AC6 现有非修订读 API 行为无回归。

## Out of Scope

- move / `moveFrom` / `moveTo`
- 属性类修订（`rPrChange` / `pPrChange` / `sectPrChange` / `tblPrChange` / `trPrChange` / `tcPrChange`）
- `cellIns` / `cellDel`
- tracked changes 创作侧 API
- tracked changes 开关写入 / 修改

## Open Questions

当前子任务级开放问题已按推荐方案收敛完成。
## Notes

- 本子任务建议补 `design.md` 与 `implement.md` 后再进入 `task.py start`。
- 若实现中发现文本类 accept / reject 也无法与高级类型边界保持干净，应回到 planning 重新评估子任务拆分。