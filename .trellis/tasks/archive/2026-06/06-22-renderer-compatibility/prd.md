# WPS/Word 兼容性 spec + core 规避（子任务 1）

> 父任务：`06-22-docx-skill-adoption`

## Goal

把 docx skill 散落在 `common-rules.md §WPS Compatibility` + `faq.md` 里的**零散兼容性陷阱**，沉淀为 nondocx 的**系统化 spec**，并在 `nondocx-core` 写路径上把其中「安全默认值明确」的一部分**主动规避**——让库默认产出双引擎兼容的文档，而不是把兼容性知识全压在用户脑子里。

> 对比 nondocx 现状：只有 `Section.ensureHeader/Footer` 做了「A4 + 1 英寸」一个兜底（poi-bridge.md N8），`docs/09-faq` 提了一句 WPS，**没有系统化的兼容性 spec**。

## 借鉴来源（skill 出处）

| 规则 | skill 出处 | nondocx 现状 |
|---|---|---|
| `ShadingType.SOLID` 在 WPS 显示黑块 | `common-rules.md` + `faq.md` | 未规避 |
| exact 行高 + `verticalAlign: center` 在 WPS 错位 | `common-rules.md` | 未规避 |
| tab stops 对齐在 WPS 宽度不一致 | `common-rules.md` | 未规避 |
| 字符装饰线（`───`）跨引擎宽度不一 | `common-rules.md` | 未规避 |
| 表格宽度 DXA → WPS tblGrid bug，应用 PERCENTAGE | `common-rules.md` | 未规避 |
| `characterSpacing` 大值跨引擎不一致 | `common-rules.md` | 未规避 |
| `titlePage: true` 头尾抑制 WPS 不可靠 | `common-rules.md` | 未规避 |
| cover wrapper 默认边框 → MS Office 空白页 | `faq.md` | 未规避（cover 属 raw） |
| `pgNumType` 空元素混淆 WPS | `faq.md` | 未规避 |

## Requirements

### R1. spec 文档（知识沉淀）

- [ ] 新增 `.trellis/spec/backend/renderer-compatibility.md`，按「症状 → 根因 → nondocx 规避策略 → skill 出处」四段式编写每条规则。
- [ ] 规则必须有**稳定锚点**（如 `#shading-solid`、`#exact-row-verticalalign`），供 `QualityCheckTools` 报告引用。
- [ ] 在 `spec/backend/index.md` 的 Guidelines Index 表里登记本文件，并在 Scope Boundaries 标注「兼容性已系统化」。
- [ ] 每条规则标注**作用域**：`core-write-default`（写路径默认值规避）/ `user-guidance`（只能写进文档提醒用户）/ `read-cleanup`（读路径清理垃圾元素）。

### R2. core 写路径主动规避（连带新建缺失 API）

> **决策（2026-06-22 brainstorm）**：core 目前**没有** shading / 列宽 / verticalAlign 的公开 API（已 grep 确认 Cell/Table 仅有 paragraphs/text/markInserted/markDeleted/raw）。本任务**连带新建这些 API 并内建兼容性默认值**，而不是等后续任务补 API。遵循父任务 R2「只补不覆盖」：

- [ ] **ShadingType（新建 API）**：新增 `Cell.shading(...)` / `Paragraph.shading(...)`（或统一底纹 API），**默认强制 `CLEAR`**（POI 的 `STShd.CLEAR`），不暴露 `SOLID` 入口；若用户确实要 SOLID 语义，提供显式 opt-in 方法并在 Javadoc 标注 WPS 显示黑块风险。
- [ ] **表格列宽（新建 API）**：新增表格列宽 API，**默认走百分比语义**（POI 层面用 `STTblWidth.PCT`），避免纯 DXA 触发 WPS tblGrid bug。用户显式传 DXA 时尊重。
- [ ] **exact 行高 + verticalAlign（新建 API）**：新增 `Cell.verticalAlign(...)`，**默认 TOP**（不默认 center），并在 Javadoc 标注「exact 行高下 WPS 忽略 verticalAlign」的行为。
- [ ] **已存在 API 的增强**：`Section.ensureHeader/Footer` 现有 A4 兜底保留；评估顺带清理 `<w:pgNumType/>` 空元素（见 Q3）。
- [ ] 其余规则（tab 对齐、字符装饰线、characterSpacing、titlePage）属 `user-guidance`，只进 spec，不改 core 默认（改了会破坏合理用例）。

**新建 API 的设计约束**（继承 nondocx 既有契约）：

- 新 API 必须 **POI-free** 签名（POI 类型只出现在 `raw()` 返回）。
- 新 API 必须是**链式 mutator 返回 this**（`cell.shading("F1F5F9")` 风格），不用 `setXxx`。
- 新增的内部 POI 脏活（如 `CTShd` / `CTTblW` 操作）收进 `internal/poi/`（可能新增 `ShadingNodes` / `TableWidthNodes` 或并入既有 helper）。
- 新 API 必须有 round-trip + POI cross-reference 测试（参见 quality-guidelines.md）。

### R3. 读路径清理（可选，视 design 收敛）

- [ ] 评估是否在 `Document.open` / save 路径清理 `<w:pgNumType/>` 空元素等「已知会混淆 WPS 的垃圾 XML」。倾向保守：**读不改、save 时可选清理**。Q3 在 design 收敛。

## Acceptance Criteria

- [ ] AC1 `spec/backend/renderer-compatibility.md` 落地，≥ 9 条规则（对应上表），每条四段式 + 稳定锚点 + 作用域标注。
- [ ] AC2 `spec/backend/index.md` 已登记本文件，Pre-Development Checklist 增加兼容性检查项。
- [ ] AC3 R2 三条**新建 API**（`shading` / 列宽 / `verticalAlign`）全部实现且 POI-free、链式 mutator，各有 round-trip + cross-reference 测试。
- [ ] AC4 R2 三条 API 的**兼容性默认值**单测：调默认路径 → 断言产出 CLEAR/PCT/TOP；显式覆盖路径 → 断言用户值被尊重。
- [ ] AC5 `Section.ensureHeader/Footer` 的兼容性增强（pgNumType 清理，若 Q3 决定做）有单测。
- [ ] AC6 现有 core 测试全绿（新 API 是「新增」，不能破坏既有 round-trip / cross-reference 测试）。
- [ ] AC7 规则锚点清单交付给子任务 2 `QualityCheckTools` 引用。

## 范围边界（不做）

- ❌ 不实现「场景化预设」（report/official 字体 profile）——那是 P1 的独立任务。
- ❌ 不做封面配方规避——cover 属 `raw()` 边界。
- ❌ 不重写 `Section.ensureHeader` 已有的 A4 兜底——只增不减。

## Notes

- 本子任务**先于** quality-check-tools 交付（沉淀知识 → 工具消费知识），但可并行起步。

## Open Questions（design.md 收敛）

- [x] **Q2（已决议 2026-06-22）**：core 没有 shading/width/vAlign API，本任务**连带新建 API 并内建兼容性默认**（决策 B）。设计原则：安全默认 + 显式覆盖。
- [x] **Q3（已决议 2026-06-22）**：实测确认 POI 5.2.5 在 `addNewPgNumType()` 不设属性时会发射裸 `<w:pgNumType/>`（正是 faq.md 指出混淆 WPS 的形状）。但 nondocx 默认路径不主动调用它，风险仅在「显式设了页码又没填字段」时出现。决议 **A：显式工具方法**——新增 `Section.cleanEmptyPageNumbering()`（或 `Document.normalizeForWps()`）显式清理；**不**在 save 路径自动清理（守住 save 纯序列化原则，与 nondocx「显式操作」哲学一致）。
- [x] **Q4（已决议 2026-06-22）**：底纹 API 形状 = **B 单参 + 完整对象重载**。提供 `Cell.shading(String hex)`（便捷重载，内部 `pattern=CLEAR`）和 `Cell.shading(Shading spec)`（完整重载）。引入新的公开值对象 `com.non.docx.core.api.style.Shading`（不可变，封装 fill/pattern/color，对应 `CTShd`）。同理 `Paragraph.shading(...)`。默认 pattern 强制 `CLEAR`，不暴露 `SOLID` 入口。
- [x] **Q5（已决议 2026-06-22）**：表格列宽 API 形状 = **A 百分比为主 + DXA 覆盖**。提供 `Table.columnPercents(int[] pct)`（主路径，默认安全/WPS 友好）和 `Table.columnWidths(int[] dxa)`（显式 DXA 覆盖）。两者作用于 `tblW` / `tblGrid`，后调的覆盖前者。
- [x] **Q6（已验证 2026-06-22，非阻塞，但有重要细节）**：实测 POI 5.2.5 lite jar（nondocx 实际依赖）：
  - **元素类型可达**：`CTShd` ✓、`CTTblWidth` ✓、`STTblWidth`（PCT/DXA/AUTO/NIL）✓、`STShd`（CLEAR/SOLID/...）✓、`STVerticalJc` ✓
  - **`CTTc`（单元格元素）rich（281 方法）**：有 `getTcPr()` / `addNewTcPr()` / `isSetTcPr()`
  - **`CTTcPr`（单元格属性）STRIPPED（仅 5 方法，全关于 tcPrChange）**：**无** typed `addNewShd()` / `getShd()`。同理 `CTPPr`（段落属性）预计也被剥离（待实现时复测）。
  - **结论**：shading API **不能**走 typed accessor，需用 **XmlCursor** 把 `<w:shd>` 作为子元素插入 `CTTcPr` / `CTPPr`。这**正是 nondocx 既有模式**（见 `TrackedChangeNodes.java:380-381` 的 `tc.isSetTcPr() ? tc.getTcPr() : tc.addNewTcPr()` + 后续 XmlCursor 操作）。
  - **verticalAlign**：POI 高层 `XWPFTableCell.setVerticalAlignment(XWPFVertAlign)` 已存在，直接用，无需 CT 操作。
  - **表格列宽**：POI 高层 `XWPFTable.setWidth(int)` / `getWidthType()` 存在但只设整表宽，列宽（`tblGrid`/`gridCol`）需 XmlCursor 或 CT 路径——实现时定。
