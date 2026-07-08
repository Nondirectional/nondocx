# 批次3·表格结构编辑与段落长尾能力（增删行列 + 标题/缩进/行距/列表/段落底纹）

> 父任务：`07-08-table-toolkit-style-gap`
> 前置：子任务 1、2 完成（单元格视觉样式与行属性/列宽已就位）。

## Goal

补齐表格的**结构编辑**（增删行/单元格）与单元格内**段落级长尾样式**（标题/缩进/行距/列表/段落底纹）。这些在表格场景里较少见，但补上后 toolkit 对表格的能力覆盖才算完整，与正文工具组全面对称。

## 缺口对照（本子任务范围）

| 能力 | core 方法 | BodyTools 对应 | TableTools 现状 |
|---|---|---|---|
| 增行 | `Table.addRow()` / `Table.row(Consumer)` | `insert_paragraph`（正文） | ❌ 无（`create_table` 只能整体建） |
| 删行 | `Table.removeRow(int)` | — | ❌ 无 |
| 增单元格 | `Row.addCell()` / `Row.cell(Consumer)` | — | ❌ 无 |
| 删单元格 | `Row.removeCell(int)` | — | ❌ 无 |
| 段落标题级别 | `Paragraph.heading(HeadingLevel)` / `clearHeading()` | ❌ 正文也缺 | ❌ 无 |
| 段落缩进 | `Paragraph.indent(left, firstLine)` | ❌ 正文也缺 | ❌ 无 |
| 段落行距 | `Paragraph.lineSpacing(double)` | ❌ 正文也缺 | ❌ 无 |
| 列表成员 | `Paragraph.list(ListKind, int)` / `clearList()` | ❌ 正文也缺 | ❌ 无 |
| 段落底纹 | `Paragraph.shading(String)` / `removeShading()` | ❌ 正文也缺 | ❌ 无 |

## Requirements

### R1. 增删行（结构编辑）

- [ ] 新增 `add_table_row`：在指定表格末尾追加空行，返回新行索引。
- [ ] 新增 `remove_table_row`：删除指定表格的某行（入参 `table_index`、`row_index`）。
- [ ] 底层调 `table.addRow()` / `table.removeRow(int)`。
- [ ] **删行会改变后续行索引**——在 description 与返回里提示 Agent 注意索引漂移（与 `removeInlineElement` 同类提醒）。

### R2. 增删单元格（结构编辑）

- [ ] 新增 `add_table_cell`：在指定行末尾追加空单元格，返回新单元格索引。
- [ ] 新增 `remove_table_cell`：删除指定单元格（入参 `table_index`、`row_index`、`cell_index`）。
- [ ] 底层调 `row.addCell()` / `row.removeCell(int)`。
- [ ] 同样提示删单元格的索引漂移。

### R3. 单元格内段落长尾样式（批量写）

补齐单元格内段落级样式。每个工具用对象数组批量 + collect-errors。

- [ ] `update_table_cell_paragraph_heading`：设单元格内段落的标题级别。底层 `paragraph.heading(HeadingLevel)`。入参字段 `heading` 取值 `H1`/`H2`/`H3`/`H4`/`H5`/`H6`（即 core `HeadingLevel` 枚举的原样字符串，大小写不敏感）。**清除标题**不传特殊值，而是用独立字段 `clear`（bool=true 时调 `clearHeading()`，此时忽略 `heading`）。
- [ ] `update_table_cell_paragraph_indent`：设单元格内段落缩进（`left_twips`、`first_line_twips`）。底层 `paragraph.indent(int, int)`。
- [ ] `update_table_cell_paragraph_spacing`：设单元格内段落行距（`line_spacing` 倍数）。底层 `paragraph.lineSpacing(double)`。
- [ ] `update_table_cell_paragraph_list`：设单元格内段落的列表成员。底层 `paragraph.list(ListKind, int)`。入参字段 `list_kind` 取值 `BULLET`/`NUMBERED`（即 core `ListKind` 枚举原样字符串）+ `level`（0-8）。**清除列表**用独立字段 `clear`（bool=true 时调 `clearList()`，忽略 `list_kind`/`level`）。
- [ ] `update_table_cell_paragraph_shading`：设单元格内段落底纹（`fill`）。底层 `paragraph.shading(String)`。

## 一致性约束（继承父任务）

- [ ] 命名：结构编辑用 `add_table_*` / `remove_table_*`；段落样式用 `update_table_cell_paragraph_*`。
- [ ] 样式类用批量对象数组 + collect-errors（增删类因返回新索引/提示索引漂移，用单次调用更自然，由实现权衡）。
- [ ] 只动 toolkit 层，不改 core。
- [ ] 改完需 `save_docx` 落盘。

## Acceptance Criteria

- [ ] 增删行/单元格工具可被 Agent 调用，且返回新索引/提示索引漂移。
- [ ] 单元格内段落的标题/缩进/行距/列表/底纹五个样式工具可用。
- [ ] 标题级别接受 `H1`..`H6`、列表接受 `BULLET`/`NUMBERED`，且 `clear` 字段能正确清除（不依赖不存在的枚举值）。
- [ ] 批量改多个单元格的段落样式，部分失败不中断。
- [ ] 每个新工具有配套单元测试（建表 → 调工具 → save → reopen 断言）。
- [ ] 现有工具无回归。
- [ ] 产出的 docx 在 Word 与 WPS 中正确显示。

## Out of Scope

- 单元格级视觉样式（底纹/垂直对齐/run 字符样式/段落对齐）→ **子任务 1**。
- 行级属性（headerRow/cantSplit）与列宽 → **子任务 2**。
- **BodyTools（正文）的段落长尾能力**（正文段落的标题/缩进/行距/列表/底纹）：BodyTools 当前同样缺这些，但本任务组聚焦**表格**寻址链版本。BodyTools 的对称补齐是独立工作，不在本任务组承诺范围。
- 段落内图片/超链接/域在表格单元格内的创建——这些 `Paragraph` 已支持，toolkit 是否单独暴露按需再加，不在本子任务承诺范围。
