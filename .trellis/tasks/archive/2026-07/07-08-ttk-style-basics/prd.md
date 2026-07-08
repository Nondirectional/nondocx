# 批次1·单元格视觉样式（底纹/垂直对齐 + 单元格内 run/段落样式）

> 父任务：`07-08-table-toolkit-style-gap`

## Goal

补齐 `TableTools` 最高频的**单元格视觉样式**编辑能力。这些能力 core 层全部已就绪（`Cell.shading()/verticalAlign()`、单元格内 `Paragraph`/`Run` 与正文同一个类），本子任务只做 toolkit 层的工具方法包装。

> **边界说明**：本子任务专注**单元格级**视觉样式。行级属性（`headerRow`/`cantSplit`）与列宽属于"表格结构/分页控制"，在子任务 2（`07-08-ttk-layout-width`）统一处理——它们同属 `<w:trPr>`/`<w:tblGrid>`，且与 `QualityCheck` 形成"能检查也能改"的闭环，放在一起更内聚。

## 缺口对照（本子任务范围）

| 能力 | core 方法 | BodyTools 对应 | TableTools 现状 |
|---|---|---|---|
| 单元格背景底纹 | `Cell.shading(String)` / `removeShading()` | —（正文无） | ❌ 无 |
| 单元格垂直对齐 | `Cell.verticalAlign(VerticalAlign)` | —（正文无） | ❌ 无 |
| 单元格内 run 字符样式 | `Run.bold/italic/underline/font/fontSize/color` | `update_run_style` | ❌ 无 |
| 单元格内段落对齐 | `Paragraph.alignment(Alignment)` | `update_paragraph_alignment` | ❌ 无 |

## Requirements

### R1. 单元格底纹（批量写）

- [ ] 新增 `update_table_cell_shading`：批量给单元格设置纯色背景底纹。
- [ ] 入参 `edits` 是对象数组，每条含 `table_index`、`row_index`、`cell_index`（均 0 起 int）与 `fill`（十六进制 RGB 字符串，如 `F1F5F9`，不带 `#`）。
- [ ] 底层调 `cell.shading(fill)`——core 已强制 `w:val="clear"`，跨 Word/WPS 安全，不会出 WPS 黑块。
- [ ] collect-errors：越界/缺字段记中文错误不中断，末尾汇总成功/失败条数。

### R2. 单元格垂直对齐（批量写）

- [ ] 新增 `update_table_cell_vertical_align`：批量设置单元格内容垂直对齐。
- [ ] 入参 `edits` 对象数组，每条含 `table_index`、`row_index`、`cell_index` 与 `vertical_align`（`TOP`/`CENTER`/`BOTTOM`，大小写不敏感）。
- [ ] 底层调 `cell.verticalAlign(VerticalAlign)`——枚举值即 core 的 `VerticalAlign.TOP/CENTER/BOTTOM`。
- [ ] 固定（exact）行高时 CENTER/BOTTOM 在 WPS 可能不生效——在工具 description 里提示，不在 toolkit 层兜底。

### R3. 单元格内 run 字符样式（批量写）

- [ ] 新增 `update_table_cell_run_style`：批量改单元格内 run 的内联字符样式。
- [ ] 入参 `edits` 对象数组，每条含 `table_index`、`row_index`、`cell_index`、`paragraph_index`、`run_index`（均 0 起 int），以及可选样式字段 `bold`/`italic`/`underline`/`font`/`font_size`/`color`。
- [ ] **与 `BodyTools.update_run_style` 参数结构、布尔字段语义（显式 false 清除、未传不改）、collect-errors 完全一致**，只是寻址链深三层。
- [ ] 底层调 `run.bold()/italic()/...`。

### R4. 单元格内段落对齐（批量写）

- [ ] 新增 `update_table_cell_paragraph_alignment`：批量改单元格内段落的水平对齐。
- [ ] 入参 `edits` 对象数组，每条含 `table_index`、`row_index`、`cell_index`、`paragraph_index` 与 `alignment`（`LEFT`/`CENTER`/`RIGHT`/`JUSTIFY`）。
- [ ] **与 `BodyTools.update_paragraph_alignment` 参数结构与失败语义一致**。

### R5. 读侧补强

- [ ] `read_table_cell` 摘要补上单元格底纹与垂直对齐信息（当前只有文本+段落数+run 数）。
- [ ] `read_table_cell_run` 补上 run 样式摘要（当前只返回文本），与 `BodyTools.read_run` 对称。

## 一致性约束（继承父任务）

- [ ] 命名遵循 `update_table_cell_*` 前缀，与 `update_run_style` / `update_paragraph_alignment` 风格统一。
- [ ] 批量对象数组 + collect-errors。
- [ ] 只动 toolkit 层，不改 core。
- [ ] 改完需 `save_docx` 落盘（在 description 里标注）。

## Acceptance Criteria

- [ ] 四个新工具（`update_table_cell_shading` / `update_table_cell_vertical_align` / `update_table_cell_run_style` / `update_table_cell_paragraph_alignment`）可被 Agent 调用。
- [ ] `read_table_cell` 摘要包含底纹/垂直对齐；`read_table_cell_run` 包含样式摘要。
- [ ] 批量场景：一次调用改多个单元格/多个 run，部分失败不中断。
- [ ] 每个新工具有配套单元测试（参照现有 `DocxToolkitBatchTest` / `QualityCheckToolsTest` 的写法：建表 → 调工具 → save → reopen 断言）。
- [ ] 现有工具无回归（`create_table` / `merge_table_cells` / `set_table_borders` / `replace_table_cell_run_text`）。
- [ ] 产出的 docx 在 Word 与 WPS 中正确显示（底纹不为黑块）。

## Out of Scope

- 行级属性 `headerRow`（表头行标记）/ `cantSplit`（禁止跨页拆分）的设置与读取 → **子任务 2**。
- 列宽读写 → **子任务 2**。
- 增删行/单元格、单元格内段落级标题/缩进/行距/列表 → **子任务 3**。
- 单元格底纹的高级图案（条纹/百分比）——core 已排除 SOLID，toolkit 同样只暴露纯色填充。
