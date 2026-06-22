# Renderer Compatibility (WPS / Word)

> 如何让 nondocx 产出的 docx 在 **WPS** 与 **Microsoft Word** 双引擎下都正确渲染。
> 这些规则来自对 docx skill（`zcode-plugins-official/document-skills`）兼容性知识的系统化沉淀。

---

## Overview

nondocx 是 Java 库，最终产出是 `.docx` 文件，要被 WPS / Microsoft Word 打开。这两个引擎对
OOXML 的渲染**不完全一致**：同一份合法 XML，在 Word 里正常、在 WPS 里可能错位、变黑、消失。

本 spec 系统化记录这些「合法但跨引擎出错」的陷阱，并标注每条规则的**作用域**：

- `core-write-default`：nondocx 在写路径 API 的**默认值**层面主动规避（不暴露危险入口）。
- `explicit-cleanup`：提供**显式工具方法**让用户/Agent 清理，不在 save 自动做（守住 save 纯序列化）。
- `user-guidance`：无法在 core 默认值规避（会破坏合理用例），只在本 spec 告知用户。

**规则锚点是稳定的**（如 `#shading-solid`），`QualityCheckTools`（toolkit）的报告会引用它们，
实现 spec ↔ 工具交叉引用。**改锚点名要同步改 toolkit。**

---

## core-write-default 类规则

### <a id="shading-solid"></a>`shading-solid` — ShadingType.SOLID 在 WPS 显示为纯黑块

**症状**：在 Word 里设了单元格/段落底纹（`STShd.SOLID`），Word 显示为指定颜色的实心填充；
切到 WPS 打开，同一格变成**纯黑块**，文字被盖住。

**根因**：`STShd.SOLID`（`w:val="solid"`）在 OOXML 语义里是「100% 图案填充」——前景色完全
覆盖背景。Word 把它解释为「用 `w:color` 填充」，WPS 把它解释为「前景色（默认黑）100% 覆盖」，
于是变黑。这是两个渲染引擎对同一属性的不同解释，OOXML 本身允许两种读法。

**nondocx 规避**：公开 `Shading` 值对象的 `ShadingPattern` 枚举**剔除 `SOLID`**——即使用户
想设，公开 API 也没有入口。`cell.shading("F1F5F9")` / `cell.shading(new Shading("F1F5F9"))`
**永远产出 `pattern=CLEAR`**（`w:val="clear"`，纯背景色填充），这是双引擎一致的安全形态。
用户若确实要 SOLID 语义，走 `raw()` 直接操纵 `CTShd`，Javadoc 标注风险。

**skill 出处**：docx skill `references/common-rules.md §WPS Compatibility` +
`references/faq.md`（ShadingType 误用条目）。

---

### <a id="table-width-dxa"></a>`table-width-dxa` — 表格列宽纯 DXA 在 WPS 触发 tblGrid 错位

**症状**：表格列宽用纯 `dxa`（twips 绝对值）设置，Word 显示正常；WPS 打开后列宽错位、
表格整体宽度异常，甚至表格被压缩成一条线。

**根因**：WPS 对 `<w:tblGrid>` + `<w:gridCol w:w="N"/>`（DXA）的处理在某些版本存在 bug，
当 `tblW` 也是 DXA 且与 gridCol 总和不一致时，WPS 重新计算列宽出错。百分比（PCT）路径
走的是相对计算，两个引擎行为一致。

**nondocx 规避**：`Table.columnPercents(int[] pct)` 是**主路径**（默认安全、WPS 友好），
`Table.columnWidths(int[] dxa)` 是显式 DXA 覆盖（用户明确要绝对宽度时用，Javadoc 标注
WPS 风险）。两者后调覆盖前者（活对象 mutator 语义）。

**skill 出处**：docx skill `references/common-rules.md §WPS Compatibility`（表格宽度条目）。

---

## explicit-cleanup 类规则

### <a id="empty-pgnumtype"></a>`empty-pgnumtype` — 空 `<w:pgNumType/>` 混淆 WPS

**症状**：docx 的 `<w:sectPr>` 里出现一个**没有属性**的 `<w:pgNumType/>`（既无 `w:start`
也无 `w:fmt`），Word 打开正常，WPS 打开后页码显示异常（如从 0 开始、格式错乱）。

**根因**：`<w:pgNumType>` 的语义是「覆盖本节的起始页码 / 编号格式」。一个空元素等于
「我要覆盖，但没说覆盖成什么」——Word 容忍这种空声明（按默认处理），WPS 严格解读为
「覆盖为空值」，导致页码引擎混乱。POI 5.2.5 的 `CTSectPr.addNewPgNumType()` 不设属性时
确实会写出这种裸元素（已实测验证）。

**nondocx 规避**：提供 `Section.cleanEmptyPageNumbering()` 显式清理——扫 `CTSectPr`，
若 `<w:pgNumType>` 存在但 `w:start` / `w:fmt` 均未设，则 `unsetPgNumType()`。**不在
`Document.save` 自动调用**（守住 save 纯序列化原则，与 nondocx「活对象 + 显式操作」哲学
一致）。用户/Agent 检出此问题后显式调，或由 `QualityCheckTools`（toolkit）检出并建议调用。

**skill 出处**：docx skill `references/faq.md`（pgNumType 空元素条目）。

---

## user-guidance 类规则

> 以下规则**无法**在 core 默认值层面规避（强行改默认会破坏合理用例），只在 spec 告知。
> 标注「API 默认 TOP」的表示 core 提供了安全默认入口，但不强制。

### <a id="exact-row-valign"></a>`exact-row-valign` — exact 行高 + verticalAlign center 在 WPS 错位

**症状**：单元格设了 exact（固定）行高 + `verticalAlign=center`，Word 垂直居中正常；
WPS 里文字贴顶或溢出。

**根因**：exact 行高下，Word 在固定高度内做垂直居中，WPS 对 exact 行高的垂直对齐处理
不同（部分版本忽略 vAlign）。

**nondocx 规避**：`Cell.verticalAlign(VerticalAlign)` 默认 `TOP`（POI 自身默认也是 top，
本任务只是暴露 API + Javadoc 标注此 spec 知识）。**不**主动把所有 cell 设成 TOP——
用户要 center 就给 center，但 Javadoc 告知 exact 行高风险。

**skill 出处**：docx skill `references/common-rules.md`（exact 行高条目）。

---

### <a id="tab-alignment"></a>`tab-alignment` — Tab stops 对齐在 WPS 宽度不一致

**症状**：用 tab stops 做对齐（如目录的「标题........页码」），Word 对齐整齐，WPS 里
tab 后的内容位置偏移。

**根因**：Word 和 WPS 对 tab stop 宽度的计算（基于字体度量）有微小差异，多 tab 累积后
偏移明显。

**nondocx 规避**：`user-guidance`——无法在 core 默认值规避（tab 是合理的排版需求）。
建议跨引擎场景用表格代替 tab 对齐。本 spec 告知，不改 core。

**skill 出处**：docx skill `references/common-rules.md`（tab stops 条目）。

---

### <a id="char-decor-line"></a>`char-decor-line` — 字符装饰线（`───`）跨引擎宽度不一

**症状**：用连续的全角破折号 `─────` 做装饰线，Word 显示为连贯横线，WPS 里每两个
字符间有断点或宽度不一。

**根因**：全角破折号的字形拼接处理在不同引擎/字体下不一致。

**nondocx 规避**：`user-guidance`——建议用段落边框（`pBdr`）或表格边框代替字符装饰线。
本 spec 告知。

**skill 出处**：docx skill `references/common-rules.md`（字符装饰线条目）。

---

### <a id="character-spacing"></a>`character-spacing` — characterSpacing 大值跨引擎不一致

**症状**：段落设了较大的 `characterSpacing`（字间距），Word 显示一致，WPS 里间距
计算不同。

**根因**：字间距的度量与字体度量的交互，两引擎实现有差异。

**nondocx 规避**：`user-guidance`——避免设过大的 characterSpacing 值。本 spec 告知。

**skill 出处**：docx skill `references/common-rules.md`（characterSpacing 条目）。

---

### <a id="title-page-suppress"></a>`title-page-suppress` — titlePage 头尾抑制在 WPS 不可靠

**症状**：节设了 `titlePage=true`（首页不同页眉页脚），Word 正确抑制首页头尾，
WPS 里抑制行为不可靠（首页仍显示头尾，或正文首页被误抑制）。

**根因**：`titlePg` 的首页识别逻辑两引擎不同。

**nondocx 规避**：`user-guidance`——跨引擎场景慎用 titlePage，或改用分节 + 手动设置
不同头尾。本 spec 告知。

**skill 出处**：docx skill `references/common-rules.md`（titlePage 条目）。

---

### <a id="even-odd-headers"></a>`even-odd-headers` — evenAndOddHeaders 开关（已验证无坑）

**状态**：已验证无跨引擎坑（nondocx `06-23-hf-variants-variants` 子任务探针 + round-trip 验证）。

**说明**：`word/settings.xml` 的 `<w:evenAndOddHeaders/>`（奇偶页不同页眉页脚）在 Word 与 WPS 下均按
预期工作 —— 开关存在时两引擎都正确区分奇偶页渲染各自的页眉页脚变体。与 `title-page-suppress`（`titlePg`）
不同，这个开关没有观察到 WPS 的抑制不可靠问题。

**nondocx 位置**：`Section.ensureHeader(EVEN)` / `ensureFooter(EVEN)` 在创建偶数页变体 part 时自动补齐
这个开关（POI 的 `createHeader(EVEN)` 不自动写，见 `poi-bridge.md N19`）。幂等（已存在则不重复写）。

**记录目的**：明确「已验证无坑」，避免未来维护者重复验证；若后续在特定 WPS 版本发现问题，在此新增
症状/根因/规避段并升级为 `user-guidance`。

---

### <a id="cover-wrapper-border"></a>`cover-wrapper-border` — cover wrapper 默认边框导致 MS Office 空白页

**症状**：用无边框表格做封面布局（cover wrapper），表格的默认边框在某些 MS Office
版本里触发渲染，封面后多出一张空白页。

**根因**：表格 `tblBorders` 的默认值处理差异；cover wrapper 常省略显式 `tblBorders`。

**nondocx 规避**：`user-guidance`——nondocx 当前无封面建模（cover 属 `raw()` 边界）。
若用户用 `raw()` 做封面，本 spec 提醒显式设 `tblBorders=nil`。

**skill 出处**：docx skill `references/faq.md`（cover wrapper 边框条目）。

---

## 锚点速查表（供 QualityCheckTools 引用）

| 锚点 | 规则 | 作用域 |
|---|---|---|
| `#shading-solid` | SOLID → WPS 黑块 | core-write-default |
| `#table-width-dxa` | DXA → WPS tblGrid bug | core-write-default |
| `#empty-pgnumtype` | 空 pgNumType 混淆 WPS | explicit-cleanup |
| `#exact-row-valign` | exact 行高 + vAlign center | user-guidance |
| `#tab-alignment` | tab stops 跨引擎宽度 | user-guidance |
| `#char-decor-line` | 字符装饰线跨引擎 | user-guidance |
| `#character-spacing` | characterSpacing 大值 | user-guidance |
| `#title-page-suppress` | titlePage 头尾抑制 | user-guidance |
| `#even-odd-headers` | evenAndOddHeaders 开关（已验证无坑） | 已验证 |
| `#cover-wrapper-border` | cover wrapper 默认边框 | user-guidance |

---

## 如何新增规则

发现新的兼容性陷阱时：

1. 在本文件新增「## 锚点规则名」小节，四段式（症状/根因/nondocx 规避/skill 出处）。
2. 确定作用域（`core-write-default` / `explicit-cleanup` / `user-guidance`）。
3. 更新上方「锚点速查表」。
4. 若作用域是前两者，同步改 core 代码（加默认值规避或显式清理方法）。
5. 若 toolkit 的 `QualityCheckTools` 能检查此规则，加检查项并引用锚点。

**改锚点名是破坏性变更**——toolkit 报告会引用它，外部可能也引用。改前全局搜索锚点名。
