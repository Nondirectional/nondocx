# specialists 设计

## 目标

完成剩余高风险/高价值领域专家：修订、页眉页脚目录、质量。

## 边界

负责：

- `RevisionAgent`
- `HeaderTocAgent`
- `QualityAgent`

不负责：

- demo 最终接线

## 核心设计

### 1. `RevisionAgent`

覆盖：

- tracked changes 查询
- tracked changes 创作
- accept/reject 相关计划生成

重点：

- review 中修订类操作更容易触发 `REVIEW`
- `stable id` 和 operation 来源链必须对齐

### 2. `HeaderTocAgent`

覆盖：

- 页眉页脚读取解释
- 目录读取解释

第一版偏只读，不追求复杂写入

### 3. `QualityAgent`

覆盖：

- `check_quality`
- 质量结果直接映射到统一 review

规则：

- `QUALITY_GATE_FAILED` -> `BLOCKED`
- `QUALITY_RISK` -> `WARNED`

## 风险

- 修订场景天然复杂，容易和正文/表格交叉
- 质量规则如果解释不清，容易被误用成“万能否决器”

## 交付物

- 三个专家 prompt
- 修订 operation 映射
- 质量结果到 review 的直连适配
