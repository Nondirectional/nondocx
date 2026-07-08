# 批次2·表格行属性与列宽（headerRow/cantSplit 设+读 + 列宽读写）

> 父任务：`07-08-table-toolkit-style-gap`
> 前置：子任务 1（`07-08-ttk-style-basics`）已完成单元格视觉样式。

## Goal

补齐表格的**行级属性**（`headerRow`/`cantSplit`）与**列宽**能力。重点是让 `QualityCheck` 已经在检查的 `headerRow`/`cantSplit` 变成 Agent **可设置**的，形成"能检查也能改"的闭环；并暴露 core 已有的列宽路径。

> **边界说明**：行级属性（表头行重复、禁止跨页拆分）同属 `<w:trPr>`、同被 `QualityCheck` 检查，本子任务把它们的**设置与读取**一并收口。表头行设置原本计划放在子任务 1，复审后移到本子任务——让"行属性"作为一个完整单元，且与子任务1的"单元格视觉样式"职责分明。

## 缺口对照（本子任务范围）

| 能力 | core 方法 | QualityCheck 现状 | toolkit 入口 |
|---|---|---|---|
| 表头行**设置** | `Row.headerRow(boolean)` | 已**检查** | ❌ 无（本子任务补） |
| 表头行**读取** | `Row.headerRow()` | 已用 | ❌ 无独立读工具 |
| 禁止跨页拆分**设置** | `Row.cantSplit(boolean)` | 已**检查** | ❌ 无（本子任务补） |
| 禁止跨页拆分**读取** | `Row.cantSplit()` | 已用 | ❌ 无独立读工具 |
| 列宽百分比（主推） | `Table.columnPercents(int[])` | — | ❌ 无 |
| 列宽 twips（绝对） | `Table.columnWidths(int[])` | — | ❌ 无 |
| 列宽读取 | `Table.columnWidths()` | — | ❌ 无 |

## Requirements

### R1. 表头行标记（批量写）

- [ ] 新增 `update_table_header_row`：批量标记/取消表头行。
- [ ] 入参 `edits` 对象数组，每条含 `table_index`、`row_index` 与 `header_row`（bool：true 标记表头行跨页重复、false 取消）。
- [ ] 底层调 `row.headerRow(boolean)`。

### R2. 禁止跨页拆分（批量写）

- [ ] 新增 `update_table_row_cant_split`：批量标记/取消行的禁止跨页拆分。
- [ ] 入参 `edits` 对象数组，每条含 `table_index`、`row_index`（0 起 int）与 `cant_split`（bool：true 禁止拆分、false 允许）。
- [ ] 底层调 `row.cantSplit(boolean)`。

### R3. 表格行属性读取（headerRow + cantSplit）

- [ ] 新增 `read_table_row`：批量读取若干行的属性摘要，至少含 `header_row`（bool）与 `cant_split`（bool）。
- [ ] 入参 `rows` 对象数组，每条含 `table_index`、`row_index`。
- [ ] 让 Agent 在 `QualityCheck` 报"未设 headerRow/cantSplit"后能先读确认、再用本子任务的写工具修正，形成"检查→读确认→改→复检"闭环。

### R4. 列宽设置（百分比，主推路径）

- [ ] 新增 `set_table_column_percents`：按百分比设置表格各列宽度。
- [ ] 入参 `table_index`（int）与 `percents`（int 数组，每列 0-100 的整数百分比；数组长度即列数）。
- [ ] 底层调 `table.columnPercents(int[])`——这是 core 的**主推路径**，跨 Word/WPS 行为一致（PCT 编码）。
- [ ] 在 description 里说明：百分比是跨引擎安全选择；若需绝对宽度用 `set_table_column_widths`。

### R5. 列宽设置（twips 绝对）

- [ ] 新增 `set_table_column_widths`：按 twips 绝对宽度设置表格各列宽度。
- [ ] 入参 `table_index`（int）与 `widths`（int 数组，每列 twips，1 twip = 1/20 点）。
- [ ] 底层调 `table.columnWidths(int[])`。
- [ ] 在 description 里标注 WPS 兼容性风险（纯 DXA 在 WPS 某些版本触发 tblGrid 错位 bug），建议优先用百分比。

### R6. 列宽读取

- [ ] 新增 `read_table_column_widths`：读取指定表格各列宽度（twips）列表。
- [ ] 底层调 `table.columnWidths()`——读时 PCT 类型按 A4 可用宽度（9026 twips）近似换算回 twips，DXA 原样返回。

### 设计决策·列宽为何是单表操作而非批量

- [ ] 列宽作用于整张表的 `<w:tblGrid>`（一张表只有一份 tblGrid），不是逐单元格/逐行属性，故**不采用批量对象数组**，而是 `table_index` + 一个宽度数组。这与 `set_table_borders`（也是单表作用域）一致。

## 一致性约束（继承父任务）

- [ ] 行属性写工具（R1/R2）用批量对象数组 + collect-errors。
- [ ] 列宽工具（R4/R5/R6）是单表作用域，用 `table_index` + 数组参数，参照 `set_table_borders`。
- [ ] 命名：行属性用 `update_table_row_*` / `update_table_header_row`；列宽用 `set_table_column_*` / `read_table_column_*`。
- [ ] 只动 toolkit 层，不改 core。
- [ ] 改完需 `save_docx` 落盘。

## Acceptance Criteria

- [ ] `headerRow` 与 `cantSplit` 均可被 Agent 设置与读取。
- [ ] 列宽百分比与 twips 两条路径都可设置，且可读取当前列宽。
- [ ] `QualityCheck` 报"未设 headerRow/cantSplit"后，Agent 能用本子任务的工具修正并复检通过。
- [ ] 百分比列宽产出的 docx 在 Word 与 WPS 中列宽一致。
- [ ] 每个新工具有配套单元测试（参照现有 `QualityCheckToolsTest`：建表 → 调工具 → save → reopen 断言行属性/列宽）。
- [ ] 现有工具无回归。

## Out of Scope

- 单元格级视觉样式（底纹/垂直对齐/run 字符样式/段落对齐）→ **子任务 1**。
- 增删行/单元格 → **子任务 3**。
