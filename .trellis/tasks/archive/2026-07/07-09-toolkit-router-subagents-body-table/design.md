# body-table 设计

## 目标

把正文和表格工具组变成两个真正可用的领域专家。

## 边界

负责：

- `BodyAgent`
- `TableAgent`
- 正文/表格补读策略
- 正文/表格 operation 映射

不负责：

- 修订
- 质量专家
- demo 接线

## 核心设计

### 1. `BodyAgent`

工具边界：

- 段落读取
- run 读取
- 文本替换
- 样式更新
- 段落插入等正文操作

补读策略：

- 默认只看 paragraph 级摘要
- 涉及 run 细节时经 `ReadCoordinator` 补读

### 2. `TableAgent`

工具边界：

- 表格读取
- 单元格读取
- 单元格内文本/样式/结构操作

补读策略：

- 默认看 table/cell 级摘要
- 涉及 cell 内段落/run 时补读

### 3. 冲突与合并

正文和表格混合时：

- 保留各自 `ExpertPlan`
- Router 负责跨组排序与合并

典型冲突：

- 同一 paragraph / run 重复改文本
- 同一 cell 重复改内容
- 表结构变化后旧索引失效

## 风险

- run 级索引补读如果提示不清，子代理会误改
- 表格结构变更可能影响后续 cell 定位

## 交付物

- 两个专家 prompt
- 两组 operation 映射
- 两组测试样例
