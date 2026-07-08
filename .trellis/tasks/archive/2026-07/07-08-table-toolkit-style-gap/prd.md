# 表格工具组样式编辑能力补齐（父任务）

## Goal

`nondocx-toolkit` 的 `TableTools` 当前只暴露了表格的**结构**操作（创建、合并、边框、文本读写），**缺少样式编辑能力**。而 core 层的 `Cell` / `Row` / `Table` / `Paragraph` / `Run` 早已提供了完整的底层样式方法——缺口纯粹在 toolkit 层未暴露。

本父任务负责把这个缺口拆解为三批可独立验收的子任务，按使用频率从高到低推进，最终让 Agent 能像编辑正文样式一样编辑表格样式。

## 缺口根源（三层视角）

| 层 | 状态 |
|---|---|
| **OOXML** | ✅ 结构齐全：`<w:tcPr>` 下有 `<w:shd>`/`<w:vAlign>`；`<w:trPr>` 下有 `<w:tblHeader>`/`<w:cantSplit>`；单元格内 `<w:p>/<w:r>` 样式与正文同构 |
| **POI / core** | ✅ 方法齐全：`Cell.shading()/verticalAlign()`、`Row.headerRow()/cantSplit()`、`Table.columnPercents()/columnWidths()`、单元格内 `Paragraph`/`Run` 是**与正文同一个类** |
| **toolkit** | ❌ 只有文本替换 `replace_table_cell_run_text`，没有样式写入入口；`QualityCheck` 甚至已经在**检查** `headerRow`/`cantSplit` 但 Agent **无法设置**它们 |

**结论**：补齐这些能力不需要碰 POI 或 OOXML 底层，纯粹是 toolkit 工具方法的包装工作（活对象直写）。

## User Value

完成后，Agent 能通过工具调用完成常见的表格外观编辑，而无需降级到 `raw()` 或 CT 层：

- 给表头/状态行**填色**、设文字**垂直居中**
- 给单元格内文字设**粗体/颜色/字号**（与正文 `update_run_style` 对称）
- 设单元格内段落的**对齐**
- 标记**表头行**（跨页重复）、标记**禁止跨页拆分**
- 设置表格**列宽**（百分比主推，跨 Word/WPS 安全）
- 增删**行/单元格**、设单元格内段落的标题/缩进/行距等

## 子任务拆分

### 子任务 1：单元格视觉样式（最高频）`07-08-ttk-style-basics`

> 单元格底纹、垂直对齐、单元格内 run 字符样式、单元格内段落对齐、读侧补强。

这是 Agent 改表格外观最常要的能力，且全部是 core 已就绪的"活对象直写"。

### 子任务 2：表格行属性与列宽 `07-08-ttk-layout-width`

> `headerRow`/`cantSplit` 的**设置与读取**（与 `QualityCheck` 形成"能检查也能改"的闭环）、列宽百分比/twips 读写。

让长表格在跨页时表现可控，让列宽可调。行级属性（同属 `<w:trPr>`）作为一个完整单元收口于此。

### 子任务 3：表格结构编辑与段落长尾能力 `07-08-ttk-structure-tail`

> 增删行/单元格、单元格内段落级标题/缩进/行距/列表/底纹等。

表格里较少见但补上后能力完整。

## 设计原则（跨子任务一致性约束）

### R1. 与正文工具组对称

- [ ] 单元格内 run/段落样式工具应与 `BodyTools` 的 `update_run_style` / `update_paragraph_alignment` 在**参数结构、批量语义、collect-errors 失败处理**上保持一致，只是寻址链多了 `table_index/row_index/cell_index` 三层坐标。
- [ ] 命名遵循 toolkit 既有约定：动作动词在前、对象在后（如 `update_table_cell_shading`、`update_table_cell_run_style`）。

### R2. 批量语义沿用既有模式

- [ ] 写类工具用**对象数组**入参（`edits` / `cells` / `rows`），长度 1 即单次操作，多个即一次改多处——把"N 个单元格"从 N 轮 LLM 往返压成 1 轮。
- [ ] 失败语义：**collect-errors**——逐条尝试，越界/缺字段的条目记中文错误串不中断整批，末尾汇总成功/失败条数。这是 toolkit 既有约定（见 `replace_table_cell_run_text` / `merge_table_cells`）。

### R3. 读侧配套

- [ ] 每个写类能力尽量配套一个读侧（或在已有读工具里补字段），让 Agent 能"先读再改"。
- [ ] `read_table_cell` 的摘要应补上**样式信息**（当前只返回文本+run 数+段落数）。

### R4. core 不改动

- [ ] 本任务组**只动 toolkit 层**，不改 core。core 的方法已经齐全且有 WPS/Word 兼容性收口（如 `Cell.shading` 强制 `w:val="clear"` 避免 WPS 黑块）。

### R5. 不破坏现有工具

- [ ] 现有 `create_table` / `merge_table_cells` / `set_table_borders` / `read_table_cell` / `replace_table_cell_run_text` 行为无回归。

## 验收（父任务集成）

- [ ] 三个子任务全部完成并归档。
- [ ] Agent 能端到端完成一个"美化表格"场景：建表 → 填数据 → 表头填色+加粗 → 数据行按状态填色 → 设列宽 → 标记表头行 → 保存。
