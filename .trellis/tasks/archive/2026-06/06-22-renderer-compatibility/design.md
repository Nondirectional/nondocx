# Design — WPS/Word 兼容性 spec + core 规避

> 子任务 `06-22-renderer-compatibility` 的技术设计。PRD 见 `prd.md`。

---

## 1. 设计目标与边界

把 docx skill 散在 `common-rules.md §WPS Compatibility` + `faq.md` 的兼容性陷阱，沉淀为 nondocx 的**系统化 spec**，并在 `nondocx-core` **连带新建三条缺失 API**（shading / 列宽 / verticalAlign）并内建兼容性默认值（PRD 决策 B）。

**边界**：

- 只做「安全默认明确」的 core 规避：shading 强制 CLEAR、列宽默认 PERCENTAGE、vAlign 默认 TOP。
- tab 对齐 / 字符装饰线 / characterSpacing / titlePage 等只进 spec 当 user-guidance，不改 core 默认。
- 不在 save 路径自动清理；pgNumType 等脏元素走**显式工具方法**（PRD Q3 决议 A）。
- 不做封面（raw 边界）、不做场景化预设（P1 独立任务）。

---

## 2. POI 5.2.5 schema 实情（关键约束）

> 这一节是 PRD Q6 的设计含义，决定每条 API 的实现路径。
> **2026-06-22 实现期修正**：最初以为 `CTTcPr` / `CTPPr` 被 stripped（grep 叶子接口只见 5 方法），
> 实测发现 XmlBeans 接口有**继承链**，typed accessor 在父接口上。

实际继承链：

```
CTShd-bearing props:
  CTPPrBase  ← addNewShd()/getShd()/isSetShd()/unsetShd()  [typed ✓]
    ↑
  CTPPr  ← 段落属性（继承上述 shd 访问器）

  CTTcPrBase  ← addNewShd()/getShd() + addNewVAlign()/getVAlign() + addNewTcW()/getTcW()  [typed ✓]
    ↑
  CTTcPrInner  ← cellMerge + cellIns/cellDel
    ↑
  CTTcPr  ← tcPrChange（叶子，继承上述全部）

Table-level:
  CTTblPrBase  ← addNewTblW()/getTblW() + addNewShd()/getShd()  [typed ✓]
  CTTblGrid    ← STRIPPED（仅 tblGridChange）→ gridCol 列宽需 XmlCursor 或 POI 高层
```

**每条 API 的实现路径**：

| API | 路径 | 证据 |
|---|---|---|
| Cell.shading | **typed** `CTTc.getTcPr().addNewShd()` | CTTcPrBase 继承 |
| Paragraph.shading | **typed** `CTP.getPPr().addNewShd()` | CTPPrBase 继承 |
| Cell.verticalAlign | **POI 高层** `XWPFTableCell.setVerticalAlignment()` 或 typed `addNewVAlign()` | 二选一，倾向 POI 高层 |
| Table 整表宽 | **POI 高层** `XWPFTable.setWidth(int)` + `setWidthType()` | 已验证 |
| Table 列宽（gridCol） | **XmlCursor 或 POI 高层** | CTTblGrid stripped；实现期验证 POI `getColWidths` 等 |

**关键修正**：XmlCursor **非必需**用于 shading/vAlign（design 初版判断错误）。只有列宽的 gridCol 路径可能需要。这大幅简化实现——无需新 `internal/poi/ShadingNodes` 走 XmlCursor，直接用 typed accessor。

---

## 3. 架构：新代码去哪里

```
nondocx-core/src/main/java/com/non/docx/core/
├── api/
│   ├── style/
│   │   ├── Shading.java                 [新] 不可变值对象（fill/pattern/color）
│   │   ├── ShadingPattern.java          [新] 枚举（剔除 SOLID）
│   │   └── VerticalAlign.java           [新] 枚举（TOP/CENTER/BOTTOM，默认 TOP）
│   ├── table/
│   │   ├── Cell.java                    [改] +shading()/+verticalAlign()/+removeShading()
│   │   └── Table.java                   [改] +columnPercents()/+columnWidths()
│   └── text/
│       └── Paragraph.java               [改] +shading()/+removeShading()（对称）
├── internal/poi/
│   └── ShadingNodes.java                [新·可选] typed accessor 的薄封装（统一 cell/paragraph 路径）
│       （若 typed 路径在 Cell/Paragraph 直接写很简洁，则不抽此类；实现期判断）
└── api/section/
    └── Section.java                     [改] +cleanEmptyPageNumbering()
```

**分层契约**（继承 poi-bridge.md）：

- `api/style/Shading`、`api/style/VerticalAlign`：公开、POI-free、不可变值对象/枚举。
- `api/table/Cell`、`api/text/Paragraph`、`api/section/Section`：公开、POI-free，链式 mutator 返回 `this`。
- `internal/poi/ShadingNodes`、`TableWidthNodes`：internal API，所有 XmlCursor/CT 脏活收容所。首行 `Internal API — subject to change without notice.`

---

## 4. 公开 API 契约

### 4.1 Shading 值对象（Q4 决议 B）

```java
package com.non.docx.core.api.style;

/** 不可变底纹值对象。对应 OOXML <w:shd>。 */
public final class Shading {
  public Shading(String fill)                          // 便捷：pattern=CLEAR
  public Shading(String fill, ShadingPattern pattern)  // 完整（不含 SOLID）
  public Shading(String fill, ShadingPattern pattern, String color)
  // getters: fill() / pattern() / color()
  // equals/hashCode 比较 fill/pattern/color
}
```

- **不暴露 SOLID**：`ShadingPattern` 枚举**剔除 `SOLID`**（OOXML 有，但 WPS 渲染为黑块，参见 spec §shading-solid）。即使用户走 `raw()` 设 SOLID，`Shading` 公开 API 不提供入口。
- `fill` 是 hex 字符串（如 `"F1F5F9"`），不带 `#`（OOXML `w:fill` 惯例）。

### 4.2 Cell / Paragraph shading（对称）

```java
public final class Cell {
  public Cell shading(String fill)         // 便捷重载，内部 new Shading(fill)
  public Cell shading(Shading shading)     // 完整重载
  public Shading shading()                 // 读；无底纹返回 null
  public Cell removeShading()              // 删
}

public final class Paragraph {
  // 同上四个方法，对称
}
```

**默认值语义**：`shading(fill)` / `shading(new Shading(fill))` **永远产出 `pattern=CLEAR`**（除非用户显式传非 CLEAR 的 ShadingPattern）。这即「兼容性默认」。

### 4.3 Cell verticalAlign（POI 高层直用）

```java
public final class Cell {
  public Cell verticalAlign(VerticalAlign align)   // 默认 TOP
  public VerticalAlign verticalAlign()             // 读
}
```

- POI 的 `XWPFVertAlign`（TOP/CENTER/BOTH/BOTTOM）映射到 nondocx `VerticalAlign`（TOP/CENTER/BOTTOM）。
- **不设默认值**（POI 自身不设时 OOXML 行为是 top）——本任务只暴露 API + Javadoc 标注「exact 行高下 WPS 忽略 vAlign」的 spec 知识。不主动改默认。

### 4.4 Table 列宽（Q5 决议 A）

```java
public final class Table {
  public Table columnPercents(int[] pct)    // 主路径：百分比，WPS 友好
  public Table columnWidths(int[] dxa)      // 显式 DXA 覆盖
  public List<Integer> columnWidths()       // 读（统一返回 twips，百分比换算后）
}
```

- `columnPercents` 写 `tblGrid` + 每个 `gridCol` 用 PCT，`tblW` type=PCT。
- `columnWidths` 写 DXA。两者后调覆盖前者（活的 mutator 语义）。
- **默认行为**：`Table` 新建时**不主动设列宽**（保持 POI 默认 AUTO）——本任务只在用户显式调列宽 API 时提供安全默认（百分比路径）。

### 4.5 Section.cleanEmptyPageNumbering（Q3 决议 A）

```java
public final class Section {
  /** 清理空的 <w:pgNumType/>（WPS 兼容性，见 spec §empty-pgnumtype）。返回是否清理了。 */
  public boolean cleanEmptyPageNumbering()
}
```

- 扫 `CTSectPr`，若 `<w:pgNumType>` 存在但 `w:start` / `w:fmt` 均未设 → `unsetPgNumType()`。
- **不在 save 自动调用**——用户/Agent 显式调，或未来 `QualityCheckTools` 检出后建议调用。

---

## 5. spec 文档结构（`.trellis/spec/backend/renderer-compatibility.md`）

每条规则四段式 + 稳定锚点 + 作用域：

```markdown
## <锚点> 规则名

**症状**：用户可见的现象（如「WPS 单元格显示纯黑」）。
**根因**：OOXML/渲染引擎层面的原因（如「STShd.SOLID 在 WPS 被当全填充」）。
**nondocx 规避**：core 默认 / user-guidance / 读路径清理（标注作用域）。
**skill 出处**：docx skill `common-rules.md` / `faq.md` 对应段落。
```

规则清单（≥9 条，锚点稳定供子任务 2 引用）：

| 锚点 | 规则 | 作用域 |
|---|---|---|
| `shading-solid` | SOLID → WPS 黑块 | core-write-default |
| `table-width-dxa` | DXA → WPS tblGrid bug | core-write-default |
| `exact-row-valign` | exact 行高 + vAlign center | user-guidance（API 默认 TOP） |
| `empty-pgnumtype` | 空 `<w:pgNumType/>` 混淆 WPS | 显式清理（Section 方法） |
| `tab-alignment` | tab stops 跨引擎宽度不一 | user-guidance |
| `char-decor-line` | 字符装饰线（`───`）跨引擎 | user-guidance |
| `character-spacing` | characterSpacing 大值 | user-guidance |
| `title-page-suppress` | titlePage 头尾抑制 | user-guidance |
| `cover-wrapper-border` | cover wrapper 默认边框 → MS Office 空白页 | user-guidance（cover 属 raw） |

---

## 6. 数据流与兼容性

### 写路径（shading 为例，typed 路径）

```
cell.shading("F1F5F9")
  → new Shading("F1F5F9")                      // pattern=CLEAR 强制
  → CTTc tc = cell.getCTTc()
  → CTTcPr tcPr = tc.isSetTcPr() ? tc.getTcPr() : tc.addNewTcPr()
  → CTShd shd = tcPr.isSetShd() ? tcPr.getShd() : tcPr.addNewShd()
  → shd.setVal(STShd.CLEAR); shd.setFill("F1F5F9");  // 按 Shading 对象填
  → return this
```

### 读路径

```
cell.shading()
  → CTTc tc = cell.getCTTc()
  → if (!tc.isSetTcPr() || !tc.getTcPr().isSetShd()) return null
  → CTShd shd = tc.getTcPr().getShd()
  → new Shading(shd.getFill(), mapPattern(shd.getVal()), shd.getColor())
```

### round-trip 影响

- 新 API 产出标准 OOXML，POI 自身 round-trip 无碍。
- `equals/hashCode`：`Shading` 是值对象参与 equals；`Cell.equals` 需**扩展**包含 shading（破坏性变更，需更新既有 round-trip 测试基线——见 implement.md 风险点）。

---

## 7. 关键权衡

| 决策 | 选择 | 代价 | 为什么值 |
|---|---|---|---|
| shading/vAlign 走 typed vs XmlCursor | **typed**（实现期修正） | 无 | CTTcPrBase/CTPPrBase 继承链提供完整 typed accessor，XmlCursor 是多余复杂度 |
| Shading 剔除 SOLID | 剔除 | 用户要 SOLID 需走 raw | SOLID 在 WPS 是 bug 源；公开 API 不应鼓励 bug |
| 列宽默认 AUTO 不主动设百分比 | 不主动 | 用户不调就没安全默认 | 主动改 POI 默认可能破坏既有 round-trip；「显式调列宽 API 才有默认」符合最小惊讶 |
| pgNumType 走显式方法 vs save 自动 | 显式 | 用户得记得调 | 守住 save 纯序列化；与「活对象 + 显式操作」哲学一致 |
| Cell.equals 扩展含 shading | 扩展 | 既有 round-trip 测试基线变 | 不扩展则 shading 不参与相等性，违反 content-equal 契约 |

---

## 8. 回滚点

每条 API 独立可回滚（git 层面）：

1. spec 文档（`renderer-compatibility.md`）——纯文档，零风险。
2. `Shading` + `ShadingNodes` + Cell/Paragraph shading——可单独 revert。
3. `VerticalAlign` + Cell.verticalAlign——可单独 revert。
4. Table 列宽——可单独 revert。
5. `Section.cleanEmptyPageNumbering`——可单独 revert。

任一 API 出问题，revert 对应 commit 不影响其它。

---

## 9. 未决（实现期收敛）— 全部已解决

- [x] **`CTPPr` shd 访问器**：实现期实测发现 XmlBeans 接口有继承链——`CTPPr extends CTPPrBase`，后者提供完整 typed `addNewShd()`/`getShd()`/`isSetShd()`/`unsetShd()`。段落 shading 走 typed accessor，**无需 XmlCursor**。
- [x] **`CTTcPr` shd/vAlign 访问器**：同样有继承链——`CTTcPr → CTTcPrInner → CTTcPrBase`，`CTTcPrBase` 提供 typed `shd` + `vAlign` + `tcW` 访问器。单元格 shading/vAlign 走 typed accessor。
- [x] **`CTTblGrid` gridCol 访问器**：`CTTblGrid extends CTTblGridBase`，后者提供 `addNewGridCol()`/`getGridColArray(int)`/`sizeOfGridColArray()`。列宽走 typed accessor。类型名是 `CTTblGridCol`（不是 `CTGridCol`），其 `getW()`/`setW(Object)` 可达。
- [x] **`CTTbl` 无 `isSetTblPr()` / `isSetTblGrid()`**：实现期发现叶子接口无 `isSet*` 方法，改用 `getTblPr() == null` 判空。
- [x] **`CTShd.getFill()`/`getColor()` 返回 `byte[]`**：实现期发现 hex color 被 XmlBeans 存为字节数组，`toString()` 返回 `[B@...`。**读字符串必须用 `xgetFill().getStringValue()` / `xgetColor().getStringValue()`**。这是一个非显而易见的 gotcha，已写入 `ShadingNodes` 注释。
- [x] **`Cell.equals` 扩展 shading/vAlign 后既有测试**：全量 verify 288 测试全绿，无回归（既有 fixture 无 shading/vAlign，`null==null` 不影响）。

**设计修正总结**：最初基于「CTTcPr/CTPPr 被 stripped」的判断错误，实际是 XmlBeans 继承链让 typed accessor 在父接口。**三条 API 全程走 typed accessor，XmlCursor 完全不需要**——比原设计更简单、更稳健。
