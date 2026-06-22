# toolkit 质量自检工具（子任务 2）

> 父任务：`06-22-docx-skill-adoption`

## Goal

在 `nondocx-toolkit` 新增 `QualityCheckTools`：对**已保存的 .docx 文件**跑可执行版式/兼容性自检，以字符串报告返回给 Agent。让 LLM 写完文档后能自查「版式有没有问题」，而不必肉眼排查或反复打开 Word/WPS 验证。

> 对比 nondocx 现状：只有开发期的 `RoundTripTest` / `PoiCrossReferenceTest`，**交付给用户后没有任何运行时质量检查**。这正是 Agent × docx 场景最大的缺口之一。

## 借鉴来源（skill 出处）

直接借鉴 docx skill 的 `scripts/postcheck.py`，它实现了 15 项业务规则自检：

| # | postcheck.py 检查项 | nondocx 适用性 | 规则锚点来源 |
|---|---|---|---|
| 1 | 空白页检测（尾随/中间多余空页、双分页、连续空段） | ✅ 全适用 | — |
| 2 | 行距一致性（正文段落行距是否统一） | ✅ 全适用 | — |
| 3 | 表格边距（单元格是否设了 padding） | ✅ 全适用 | — |
| 4 | 表格分页控制（表头 tblHeader、数据行 cantSplit） | ✅ 全适用 | — |
| 5 | 图片溢出（宽度超页面可用区） | ✅ 全适用 | — |
| 6 | 字体回退（用了目标系统可能缺失的字体） | ✅ 全适用 | — |
| 7 | CJK 首行缩进（中文正文是否有首行缩进，排除表格/列表/居中） | ✅ 全适用 | — |
| 8 | 标题层级连续性（是否跳级 H1→H3） | ✅ 全适用 | — |
| 9 | 编号连续性（有序列表是否有跳号） | ✅ 全适用 | — |
| 10 | 封面与正文分节 | ⚠️ cover 属 raw，降级或跳过 | — |
| 11 | ShadingType 是否误用 SOLID | ✅ 全适用 | `renderer-compatibility.md#shading-solid` |
| 12 | TOC 质量（域是否存在、标题是否用标准 Heading 样式） | ✅ 全适用 | — |
| 13 | 图片宽高比（是否被拉伸/压缩） | ✅ 全适用 | — |
| 14 | 文档清洁度（占位符/Markdown 语法/草稿措辞残留） | ✅ 全适用 | — |
| 15 | 报告内容质量（摘要是否存在、标题是否具体、结论是否含糊） | ⚠️ 语义性，可选/降级 | — |

> 锚点列：兼容性类检查项应引用子任务 1 `renderer-compatibility.md` 的规则锚点，实现 spec ↔ 工具交叉引用（父任务 AC2）。

## Requirements

### R1. 工具形态

- [ ] 新增 `nondocx-toolkit/src/main/java/com/non/docx/toolkit/QualityCheckTools.java`，继承 `ToolkitToolContext`（与其它六类共享会话状态）。
- [ ] 在 `DocxToolkit` 门面注入第七个字段 `qualityCheck`，与现有六个工具类共享同一份 `sessions`/`seq`。
- [ ] 暴露 `@ToolDef` 工具方法 `check_quality`，签名遵循 toolkit 现有约定（`doc_id` 句柄 + 字符串报告返回）。

### R2. 检查执行模型（Q1 决议 2026-06-22：内存为主）

- [x] **Q1 决议**：**A. 内存为主**——主体走内存 `Document` 活对象 API（复用 nondocx 投资，含子任务 1 刚加的 `cell.shading()`）。会话不跟踪文件路径，磁盘方案需 save 临时文件，成本高且不复用 API。
- [ ] **输入**：`doc_id`（必填）+ 可选 `checks` 数组（指定跑哪些检查，空则跑全量）。
- [ ] **检查对象**：内存中的 `Document` 活对象（经 `document(docId)` 取回），遍历 `doc.paragraphs()` / `doc.tables()` / `doc.sections()` 等。
- [ ] **输出**：结构化字符串报告，每条含 `✅/⚠️/❌` 图标 + 检查名 + 严重级别 + 具体问题描述 + （若适用）规则锚点引用。格式对齐 toolkit 现有报告（`StringBuilder` + 中文 + 换行分隔）。
- [ ] **不抛异常**：检查发现的问题全部以报告条目返回；只有「doc_id 不存在」这类硬错误才返回 `docNotFound(docId)` 错误串。

### R3. 检查项实现（Q2 决议 2026-06-22：9 项内存可跑 + 新建 API 补表格分页）

> Q2 决议 **B**：纳入 9 项内存可跑的检查 + 顺带给 core 新建表格分页控制 API（`Row.headerRow()` / `Row.cantSplit()`）来支持 #4。

**纳入 MVP 的检查项（10 项）**：

| # | 检查 | 走的 API | 严重级别 |
|---|---|---|---|
| 1 | 空白页（连续空段、双分页） | `doc.paragraphs()` 遍历 | ⚠️ |
| 2 | 行距一致性 | `paragraph.lineSpacing()` | ⚠️ |
| 4 | 表格分页控制（表头 `tblHeader` + 数据行 `cantSplit`） | **新建 `Row.headerRow()`/`Row.cantSplit()` API** | ⚠️ |
| 5 | 图片溢出 | `image.width()` + `section.paperSize()/margins()` | ❌ |
| 6 | 字体回退（罕见字体） | `run.style().font()` | ⚠️ |
| 7 | CJK 首行缩进 | `paragraph.indentationFirstLine()` | ⚠️ |
| 8 | 标题层级连续性 | `paragraph.heading()` | ⚠️ |
| 11 | ShadingType 误用（SOLID） | `cell.shading()` + raw 兜底 | ❌ |
| 12 | TOC 质量（域存在 + 标题用 Heading） | `doc.toc()` + `paragraph.heading()` | ⚠️ |
| 14 | 文档清洁度（占位符/Markdown 残留） | 遍历文本正则匹配 | ⚠️ |

**排除的检查项（本任务不做）**：

- #3 表格边距（cell padding）：nondocx 无 cell margin API，本任务不新建（避免 scope 膨胀），标为「待新 API」。
- #13 图片宽高比：`Image.width()/height()` 是存储尺寸，无原图尺寸 API，需读图片二进制（磁盘方案）。本任务排除。
- #9 编号连续性：编号是 POI 计算的，nondocx 无编号展开 API。排除。
- #10 封面分节 / #15 报告内容质量：语义性/cover 属 raw，排除。

### R4. 新建 core API（表格分页控制）

- [ ] 新增 `Row.headerRow(boolean)` / `Row.headerRow()` / `Row.cantSplit(boolean)` / `Row.cantSplit()`，对应 OOXML `<w:trPr>/<w:tblHeader>` 与 `<w:cantSplit>`。
- [ ] POI 访问路径（已实测 typed 可达）：`CTRow.getTrPr()/addNewTrPr()` → `CTTrPr` 继承 `CTTrPrBase` 的 `addNewTblHeader()/isSetCantSplit()/unsetCantSplit()` 等。
- [ ] 契约对齐 shading API：POI-free 签名、链式 mutator、internal/poi 桥接收容 CT 脏活。
- [ ] `Row.equals` 扩展含 headerRow/cantSplit（content-equal 契约）。

### R5. 不引入新依赖

- [ ] 全部走 nondocx core API + JDK，**不**引入新第三方依赖，**不**解包读 XML。

## Acceptance Criteria

- [ ] AC1 `QualityCheckTools` 落地，`check_quality` 工具方法可被 Agent 调用，返回结构化报告字符串。
- [ ] AC2 `DocxToolkit` 门面注入第七个工具类，`scanAll` 注册成功，现有六个工具类无回归。
- [ ] AC3 R3 表格列出的 **10 项检查**全部实现，每项有单元测试：构造违规文档 → 断言报告含对应 ❌/⚠️ 条目；构造合规文档 → 断言全 ✅。
- [ ] AC4 R4 新建的 `Row.headerRow()`/`Row.cantSplit()` API 落地，有 round-trip + cross-reference 测试。
- [ ] AC5 兼容性检查项的报告条目引用 `renderer-compatibility.md` 锚点（如 `见 #shading-solid`、`见 #empty-pgnumtype`）。
- [ ] AC6 `docs/07-toolkit.md` 补充 `QualityCheckTools` 章节，工具清单表加一行。
- [ ] AC7 现有 core + toolkit 测试全绿。

## 范围边界（不做）

- ❌ 不做「自动修复」——本工具只**报告**问题，修复留给 Agent / 用户调用其它工具。（postcheck.py 有 `--fix`，但 nondocx 的修复路径应由显式工具方法承担，不混进检查器。）
- ❌ 不做封面质量检查——cover 属 raw 边界。
- ❌ 不做语义级内容质量判断（如「结论是否含糊」）——这超出静态检查能力，留给 LLM。

## Notes

- 本子任务**消费**子任务 1 沉淀的兼容性规则；若子任务 1 未交付，可先用 postcheck.py 直译规则，后续回填锚点引用。
- design.md 需收敛父任务 Q1（检查磁盘文件 vs 内存对象）。
- 命名对齐 toolkit 现有约定：工具方法 `check_quality`，类 `QualityCheckTools`（复数，与 `BodyTools`/`TableTools` 一致）。

## 子任务 1 交付物:spec 锚点清单(2026-06-22 交付)

> 子任务 1 (`06-22-renderer-compatibility`) 已落地,以下锚点清单供本任务的 `QualityCheckTools` 在兼容性检查项中引用(满足父任务 AC2 / 本任务 AC4)。
> 锚点定义文件:`.trellis/spec/backend/renderer-compatibility.md`(改锚名是破坏性变更,引用前先查该文件)。

### 可被 QualityCheckTools 检查的锚点(检查项来源)

| 锚点 | 检查思路(供实现参考) | nondocx 已有的对应 API |
|---|---|---|
| `#shading-solid` | 遍历所有 cell/paragraph 的 `<w:shd>`,若 `w:val="solid"` → ❌ 报告 WPS 黑块风险 | `Cell.shading()` / `Paragraph.shading()` 读侧已把 SOLID 归并 NIL(可借此检测:读到 NIL 但 raw 是 SOLID) |
| `#table-width-dxa` | 遍历所有表格,若 `tblW type=dxa` → ⚠️ 提示 WPS tblGrid 错位风险,建议改用百分比 | `Table.columnWidths()` / `Table.raw().getCTTbl().getTblPr().getTblW()` |
| `#empty-pgnumtype` | 遍历所有 `sectPr`,若 `<w:pgNumType>` 存在但 `w:start`/`w:fmt` 均未设 → ❌ 报告 WPS 页码混乱 | `Section.cleanEmptyPageNumbering()` 已可清理(检查器检出后建议调用) |

### 仅作 user-guidance 的锚点(不在 QualityCheckTools 检查范围)

这些规则无法静态检查(涉及渲染语义或合理用例),只在 spec 告知用户:

- `#exact-row-valign`、`#tab-alignment`、`#char-decor-line`、`#character-spacing`、`#title-page-suppress`、`#cover-wrapper-border`

### 已验证的 POI schema 事实(本任务实现期可复用)

子任务 1 已实测确认(避免本任务重复踩坑):

- POI 5.2.5 lite jar:`CTShd`/`STShd`/`CTTblWidth`/`STTblWidth`/`STVerticalJc` 全部 typed 可达。
- XmlBeans 继承链:`CTTcPr → CTTcPrInner → CTTcPrBase`(shd/vAlign/tcW 在 base 层);`CTPPr → CTPPrBase`(shd 在 base 层)。
- `getFill()`/`getColor()` 返回 `byte[]`(hex 存为字节数组);读字符串要用 `xgetFill().getStringValue()`。
- `CTTbl` 无 `isSetTblPr()` / `isSetTblGrid()`,要用 `getTblPr() == null` 判空。
- `CTPageNumber` 有 `isSetStart()` / `isSetFmt()` 可判空 pgNumType。
