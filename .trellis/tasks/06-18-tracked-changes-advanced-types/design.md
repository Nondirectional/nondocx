# Design — tracked changes 高级修订类型

> 配套 `prd.md`。本文记录高级修订类型的统一模型与操作语义设计：move、属性类、单元格结构类，以及它们如何接到现有统一门面和 `TrackedChange` 模型上。

## 1. 设计目标

本子任务是 tracked changes 项目中技术风险最高的一段，目标不是“赶快把所有高级类型都塞进去”，而是先把它们的**建模方式、读写边界与研究顺序**定清，再实现。

本子任务需要回答：

1. move 怎么进统一模型
2. 属性类修订怎么进统一模型
3. `cellIns` / `cellDel` 怎么进统一模型
4. 若 read 子任务未完全覆盖高级类型，本子任务是否顺带补齐枚举能力

## 2. 三层映射

### 2.1 OOXML 层

高级修订类型主要包括：

- move：`moveFrom` / `moveTo`
- 属性类：`rPrChange` / `pPrChange` / `sectPrChange` / `tblPrChange` / `trPrChange` / `tcPrChange`
- 单元格结构类：`cellIns` / `cellDel`

这些类型与 `ins` / `del` 不同：

- move 需要考虑“配对”
- 属性类需要考虑“变更目标是什么属性树”
- `cellIns` / `cellDel` 需要考虑“结构节点是否保留 / 恢复”

### 2.2 POI 层

POI 没有对应的 accept / reject 高层 API；实现需要：

- 直接面向 `CT*` 节点读取高级修订信息
- 在写路径中对配对关系、属性树与结构节点进行 CT 级操作

### 2.3 nondocx 层

nondocx 在此子任务中的责任是：

- 继续沿用统一 `TrackedChange` 顶层模型
- 让高级类型进入既有门面，而不是另起第二套 API
- 将复杂差异沉入 `details()`，避免反向污染 `location`

## 3. 统一模型接入策略

### 3.1 move（已定推荐）

当前推荐：

- **每个具体 OOXML 节点对应一个 `TrackedChange`**
- 也就是说：`MOVE_FROM` 与 `MOVE_TO` 分别是两个 `TrackedChange.type`
- 两者同属 `MOVE` family
- `MoveChangeDetails` 负责表达配对关系（如 counterpart stable id / 是否已解析到配对端）

这样做的好处：

1. 保留 OOXML 细粒度事实
2. 不需要为了“逻辑合并成一个 move”而扭曲现有统一模型
3. 若用户对其中任一端按 id 操作，实现仍可在内部联动处理配对关系

### 3.2 属性类修订

当前推荐：

- 每个具体属性变更节点仍对应一个 `TrackedChange`
- `location` 只表达结构位置
- `PropertyChangeDetails` 表达：
  - 目标属性树类型（如 `RPR` / `PPR` / `SECT_PR`）
  - 必要的 before/after 或变更摘要信息

这样可以保持：

- `location` 回答“挂在哪”
- `details()` 回答“改了什么”

### 3.3 `cellIns` / `cellDel`

当前推荐：

- 仍以一个具体结构修订节点对应一个 `TrackedChange`
- `location` path 指向对应表格结构位置
- `CellChangeDetails` 表达其结构语义（插入 / 删除、作用对象摘要等）

## 4. 与 read 子任务的边界（已定推荐）

当前推荐：

- 若 `06-18-tracked-changes-read` 第一版尚未完整枚举所有高级类型，本子任务**同时补齐其枚举能力与写语义**。

原因：

1. 高级类型本身就是在这里被真正研究与建模
2. 不应要求 read 子任务在没有这些研究结论时，先把高级类型完整列出来
3. 这样边界更自然：
   - read 打基础模型与入口
   - advanced-types 补齐高级类型的完整进入方式与操作语义

## 5. accept / reject 语义方向

### 5.1 move

当前推荐语义：

- 单条命中 `MOVE_FROM` 或 `MOVE_TO` 任一端时，内部都按配对 move 操作
- 对调用者仍表现为“按这个 id 操作成功”
- 若配对端缺失或损坏，按实现阶段约定抛异常，而不是静默降级

### 5.2 属性类修订

当前推荐方向：

- accept：让变更生效，并移除修订包装
- reject：撤销变更，并移除修订包装

但具体是“整棵属性树替换”还是“字段级 merge”，必须在 fixture / research 之后再落实现细节。

### 5.3 `cellIns` / `cellDel`

当前推荐方向：

- accept / reject 语义围绕结构节点是否保留、移除、恢复展开
- 不能简单套用文本类 `ins` / `del` 的语义描述

## 6. 研究优先策略

在开始真正实现前，至少先补三类研究 / fixture：

1. move 配对与缺损场景
2. 属性类修订的真实 XML 样本
3. `cellIns` / `cellDel` 的结构前后状态

若研究后发现某一类需要显著独立的实现与测试矩阵，再回 planning 拆分子任务。

## 7. 边界保护

- 不新增第二套高级修订 API
- 不把属性目标塞回 `location`
- 不把 read 子任务的基础模型推翻重来
- 不让高级类型需求反向污染文本类 accept/reject 已定语义

## 8. 风险观察点

- move 的“配对”若建模不稳，会直接影响单条 id 操作语义
- 属性类如果 before/after 语义不清，`details()` 容易变成空壳
- `cellIns` / `cellDel` 若误按文本类思路处理，最容易写出结构错误文档

## 9. 当前仍待收敛的问题

当前子任务级设计问题已按推荐方案收敛完成；后续若 research 结果显示某一类高级修订必须独立成任务，再回 planning 拆分。