# 页码与通用简单域 API

## Goal

为 nondocx 补齐**简单域（simple field）**的写入能力，使用户能在正文段落、页眉、页脚里插入页码（`PAGE`）、总页数（`NUMPAGES`）等常见域，而无需掉进 `raw()` / CT 层手写 `<w:fldChar>` 三段式。

本子任务**只覆盖写侧**（创建域），不覆盖读侧（解析已有域）。

## User Value

完成后，用户能：

- 在页脚插入「第 X 页」页码域：`footer.addParagraph().addPageNumberField()`
- 在页脚插入「共 Y 页」总页数域：`footer.addParagraph().addPageCountField()`
- 插入任意 OOXML 简单域：`run.addSimpleField("DATE \\@ yyyy")`
- 域经 save → reopen 后指令文本存活（Word/WPS 打开后由其分页/计算引擎渲染实际值）

## Confirmed Facts

- **OOXML 域结构**：一个简单域由三段组成 —— `<w:fldChar w:fldCharType="begin"/>` + `<w:instrText>指令</w:instrText>` + `<w:fldChar w:fldCharType="end"/>`，分别住在相邻的 `<w:r>` 内。
- **`fldSimple`** 是另一种单元素形态（`<w:fldSimple w:instr="...">`），Word/WPS 都支持，但 POI 对它的 API 覆盖更弱；本子任务采用**三段式 `<w:r>` 形态**（与 Word 默认产出一致，round-trip 更稳）。
- **POI 无高级 API**：POI 没有 `XWPFField` / `addSimpleField` 这类方法，必须直接操纵 `CTR`（`addNewFldChar` + `addNewInstrText`）。与 N9/N10 的 hyperlink / tracked-changes 路径同型。
- **域的实际值由渲染引擎计算**：页码、日期等域的可见结果是 Word/WPS 打开时分页/计算引擎填的缓存，POI **不**计算。本子任务只写指令结构，不写缓存值（缓存值属「复杂域」范畴，见 Out of Scope）。
- 父任务已确定：域 API 写侧统一在 `Run` / `Paragraph` 上（与现有 `addRun` / `addHyperlink` / `addImage` 同层）。
- 父任务已确定：读侧（解析已有域）不在本次范围。

## Requirements

### R1. 通用简单域入口

- [ ] 提供 `Paragraph.addSimpleField(String instruction)`：在段落末尾追加一个**标准三-run 简单域**（begin fldChar run / instrText run / end fldChar run），承载给定指令。
- [ ] 返回承载 `instrText` 的中间 `Run`（用户可对其链式设样式 —— 域的可见结果样式由 instrText run 的样式决定）。
- [ ] 入口放在 `Paragraph` 上（与 `addHyperlink` / `addImage` 同模式：创建新 inline 内容并返回它），**不**放在 `Run` 上。
- [ ] 指令文本原样写入 `<w:instrText>`，不做语法校验（OOXML 域指令是自由文本，POI/nondocx 不负责校验语义）。
- [ ] 指令为 null 或空白时抛 `IllegalArgumentException`（遵循 nondocx 错误模型，IAE 而非 NPE）。

### R2. 页码 / 总页数便捷方法

- [ ] 提供 `Paragraph.addPageNumberField()`：等价于 `addSimpleField("PAGE")`，返回 `Run`。
- [ ] 提供 `Paragraph.addPageCountField()`：等价于 `addSimpleField("NUMPAGES")`，返回 `Run`。
- [ ] 命名沿用 nondocx「属性名即方法名」风格（不叫 `addPageNumberSimpleField`）。

### R3. 一致性与兼容性约束

- [ ] 域经 save → Docx.open 后，`instrText` 文本存活（round-trip 契约）。
- [ ] 公共 API 保持 POI-free；CT 操纵下沉到 `internal/poi/`（建议新建 `FieldNodes` 或并入既有工具类，由 design 决定）。
- [ ] 异常包装：POI / XmlBeans 失败包成 `DocxIOException`（遵循 poi-bridge Rule 4）。
- [ ] 现有 `Run` / `Paragraph` 的 `addRun` / `addHyperlink` / `addImage` / `inlineElements()` 行为无回归。
- [ ] 域产出的 run 应能被 `Paragraph.inlineElements()` 正常枚举（不破坏既有 inline 视图）。

### R4. 教学式 Javadoc

- [ ] `Paragraph.addSimpleField` Javadoc 讲清三层：OOXML 域的三段结构 → POI 无高层 API 需操纵 CTR → nondocx 为什么放在 `Paragraph` 而非 `Run` 上（与 Word 标准 3-run 产出一致，且 `Paragraph` 是「创建新 inline 内容」的入口，同 `addHyperlink`/`addImage`）。
- [ ] 便捷方法 Javadoc 指向 `addSimpleField` 并说明等价关系。

## Acceptance Criteria

- [ ] AC1 `paragraph.addSimpleField("PAGE")` 写出的域，save → reopen 后 `instrText` 读回为 `PAGE`。
- [ ] AC2 `paragraph.addPageNumberField()` 与 `addSimpleField("PAGE")` 产出的结构等价。
- [ ] AC3 `paragraph.addPageCountField()` 与 `addSimpleField("NUMPAGES")` 产出的结构等价。
- [ ] AC4 通用入口接受任意指令（如 `DATE \\@ yyyy`、`NUMPAGES`、`SECTION`），round-trip 存活。
- [ ] AC5 指令为 null 或空白时抛 `IllegalArgumentException`。
- [ ] AC6 现有 `Run` / `Paragraph` 测试无回归（`RunTest` / `ParagraphTest` / `RoundTripTest` 继续绿）。
- [ ] AC7 域产出的 3 个 run 出现在 `paragraph.inlineElements()` 中（不破坏 inline 视图契约）。

## Out of Scope

- **域的读侧解析**（抽取已有 field code、识别域类型）—— 走 `raw()`。
- **复杂域**（带 `<w:fldChar separate>` 缓存可见结果的完整域）—— 本次只做三段式简单域。
- **`fldSimple` 单元素形态** —— 采用三段式 `<w:r>` 形态，`fldSimple` 留给后续。
- **域的实际值计算** —— POI 不计算页码/日期，本子任务也不做。
- **嵌套域** —— 不支持。

## Open Questions

当前子任务级开放问题已按推荐方案收敛完成。若 design 阶段发现 `instrText` 与既有 `<w:t>` 在 `inlineElements()` 枚举里有冲突（例如 POI 把 `instrText` 当普通 run 暴露），回到 planning 修订 R3。

## Notes

- 域指令的合法值参考 OOXML 规范的 field codes 列表（`PAGE` / `NUMPAGES` / `DATE` / `TIME` / `SECTION` / `FILENAME` 等）；nondocx 不做白名单校验，交给用户。
- 写侧完成后，**docs-spec 子任务** 会决定是否在 spec 里记录「域的实际值由渲染引擎计算，nondocx 不保证」这一边界。
