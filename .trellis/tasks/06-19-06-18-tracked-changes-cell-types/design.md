# Design — tracked changes 单元格结构类(cellIns/cellDel/cellMerge)读写

> 配套 `prd.md`。本文记录 cell 子任务的真实 OOXML 形态、CT 类型可达性、统一模型接入与 accept/reject 语义设计。
>
> **与 PRD 前提的关键修正**:planning 阶段对 POI 精简 schema 的预估(「CT 类型在精简 classpath 待引入」)经 `javap` 直接验证后**被推翻**——见 §1.1。这把本子任务从「需先引入 CT 类型」降级为「复用现成 typed 访问器」,工程量与 advanced-types 的 property 类同构。

## 1. 真实 OOXML 形态与 POI 可达性(已验证)

### 1.1 `javap` 实测结论(POI 5.2.5 精简 jar 内)

| 类型 | 在 lite 5.2.5 jar 内 | 说明 |
|---|---|---|
| `CTTcPr.getCellIns()` / `getCellDel()` | ✅ 已存在 | 返回类型是 **现成的 `CTTrackChange`**(id 来自 `CTMarkup`、author/date 来自 `CTTrackChange`)。定义在 `CTTcPrInner`(被 `CTTcPr` 继承)。 |
| `CTTcPr.getCellMerge()` | ⚠️ 访问器声明在,但类型缺失 | 返回 `CTCellMergeTrackChange`,**该类不在 lite jar 内**(`getCellMerge()` 调用会 `NoClassDefFoundError`)。这是 POI lite 的「dangling reference」生成模式:只有 POI 自身调用到的类才会被保留进 lite jar。 |
| `CTTcPr.getTcPrChange()` | ⚠️ 同上 dangling | 返回 `CTTcPrChange`,**该类不在 lite jar 内**。 |
| `CTPPrChange` / `CTSectPrChange` / `CTTblPrChange` / `CTTrPrChange` | ❌ 不在 jar 内 | `CTP.getPPr()` 存在,但 `<w:pPr>` 内的 `<w:pPrChange>` 元素无 CT 类型反序列化目标。pPrChange 等更高层属性类**全部受阻**。 |
| `CTCellMergeTrackChange` | ❌ 不在 jar 内 | 见上。 |

**结论**:
- **cellIns / cellDel**:无需新 CT 类型、无需 XmlCursor 本地名探测。read 直接 `tc.getTcPr().getCellIns()`;accept/reject 复用 property 类已建好的「持 `CTTrackChange` 委托」机制。
- **cellMerge**:不能走 typed 访问器;只能走 XmlCursor 本地名探测读取,accept/reject 暂不支持(见 §5.3)。
- **pPrChange / sectPrChange / tblPrChange / trPrChange**:CT 类型全部缺失,移出本子任务(见 §8)。

### 1.2 cellIns / cellDel 的 OOXML 结构

```xml
<w:tbl><w:tr><w:tc>
  <w:tcPr>
    <w:cellIns w:id="1" w:author="non" w:date="..."/>     ← 裸属性,无 run、无文本
  </w:tcPr>
  <w:p>...单元格内段落...</w:p>
</w:tc></w:tr></w:tbl>
```

- `cellIns` / `cellDel` 嵌在 `<w:tcPr>`(单元格属性)内,**不在** cell 包装层、也不在单元格内段落里。
- CT 类型 `CTTrackChange`(裸 id/author/date,无 run、无文本)——表达「**这个单元格(`tc`)本身是被插入/删除的**」,是表格**结构修订**,不是文本内容修订。
- 关键差异(与文本类 ins/del、属性类 rPrChange 对比):

| family | 修订标记的含义 | accept/reject 作用对象 |
|---|---|---|
| TEXT(ins/del) | run 级文本增删 | run(unwrap 或 remove) |
| PROPERTY(rPrChange) | 属性子树变更 | 属性元素(整树替换 / 删标记) |
| **CELL(cellIns/cellDel)** | **整个 `tc` 单元格的存亡** | **整个 `<w:tc>` 元素** |

## 2. 三层映射

### 2.1 OOXML 层

本子任务范围内的单元格结构修订:

- `cellIns` —— 标记一个单元格被插入(单元格本身是新增内容)。
- `cellDel` —— 标记一个单元格被删除(单元格本身是待删内容)。
- `cellMerge` —— 标记两个单元格的合并/拆分(本子任务**仅读**,不写)。

### 2.2 POI 层

- `cellIns`/`cellDel` 节点类型是 `CTTrackChange`(`org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTrackChange`),经 `CTTcPr.getCellIns()`/`getCellDel()` 取得。
- `CTTrackChange` 继承自 `CTMarkup`(给 `w:id`),自身提供 `author`/`date`——与 property 类(走 `CTRPrChange` → `CTTrackChange`)的**委托类型完全相同**。这意味着 cell 类可复用 `TrackedChange` 已有的「`CTTrackChange` 委托」构造函数与 `propertyNode()` 包内接缝。
- accept/reject 需要操作 `<w:tc>`(节点祖父),POI 无高层 API,走 `XmlCursor.toParent()` 二层提升到 `tc` 再 `removeXml()`。

### 2.3 nondocx 层

- cell 类进入既有统一门面(`TrackedChanges`),**不新增第二套 API**。
- `cellIns`/`cellDel` 用 cell 专用 details(`CellChangeDetails`)表达结构语义;`cellMerge` 只读,details 标记为未确认合并。
- accept/reject 经门面专用方法(`acceptCell`/`rejectCell`,方案 C 同 property)走,不通用 `accept(id)`。

## 3. 统一模型接入策略

### 3.1 cellIns / cellDel(推荐:每个节点一个 TrackedChange)

- 每个 `<w:cellIns>` / `<w:cellDel>` 节点对应一个 `TrackedChange`。
- `TrackedChangeType.CELL_INS` / `CELL_DEL`(枚举已预留),`family = CELL`(已预留)。
- `location` path 指向单元格结构位置:`body > table[i] > row[j] > cell[k]`(**不含** `paragraph` 段——cell 修订挂在 `tcPr`,不在单元格内段落里)。
- 委托走 property 类的第二个 `TrackedChange` 构造函数(持 `CTTrackChange`),`raw()` 对 cell 类**抛 `UnsupportedFeatureException`**(同 property 的方案 C,见 N15)。

### 3.2 cellMerge(只读接入)

- `cellMerge` 节点也产出一条 `TrackedChange`,`type` 需新增 `CELL_MERGE`,`family = CELL`。
- 因 `CTCellMergeTrackChange` 不在 classpath,**委托无法是具体 CT 类型**——这里引入第三种委托形态:**XmlCursor 节点句柄**(见 §4.3)。或者更简:cellMerge 读出后只产出 details,**不持有可写委托**;accept/reject 调用直接抛 `UnsupportedFeatureException`。
- `details()` 为 `CellChangeDetails`(`kind = UNCONFIRMED_MERGE`),标记「合并未确认」。

## 4. 委托形态决策

### 4.1 cellIns / cellDel:复用 property 类委托(推荐)

`TrackedChange` 已有持 `CTTrackChange propertyDelegate` 的构造函数;cellIns/cellDel 的节点类型正好是 `CTTrackChange`,**直接复用,零改动**:
- 构造:`new TrackedChange(id, type, location, details, (CTTrackChange) cellInsNode)`
- 取节点:`propertyNode()`(已存在,包内接缝)
- `raw()` 自动抛 `UnsupportedFeatureException`(runDelegate == null)

### 4.2 location path 形态

cell 修订 location **不含 `paragraph` segment**:`[BODY, TABLE, ROW, CELL]`。这与 `walkCell` 当前在 cell 内下钻 `paragraph` 产出的段落级修订 path(`[BODY, TABLE, ROW, CELL, PARAGRAPH]`)区分开——cell 结构修订挂在 `tcPr`,比单元格内段落高一层。

### 4.3 cellMerge 的委托问题(需实现期决定)

两条路:
- **路 A(推荐):cellMerge 读出后是「纯值」TrackedChange,不持可写委托。** accept/reject 直接抛 `UnsupportedFeatureException`,消息指向 raw()。简单、诚实,但 cellMerge 的 `TrackedChange` 不能像其它类那样持节点句柄。
- 路 B:新增第三个委托槽(XmlCursor 句柄)。引入复杂度,且 cellMerge 本就只读,不值得。

推荐路 A。cellMerge 的 `TrackedChange` 经一个**接受 `null` 委托**的构造变体产出(或复用 property 构造但传一个 sentinel)。实现期若发现 `TrackedChange` 的非空校验挡路,再定具体形态——这是实现细节,不影响对外契约。

## 5. accept / reject 语义

### 5.1 cellIns

- **accept**:单元格插入生效 → **保留整个 `<w:tc>`**(连同其内段落),仅删 `tcPr` 内的 `cellIns` 标记。
- **reject**:单元格插入撤销 → **移除整个 `<w:tc>`**(含其内全部段落/run)。

### 5.2 cellDel

- **accept**:单元格删除生效 → **移除整个 `<w:tc>`**。
- **reject**:单元格删除撤销 → **保留整个 `<w:tc>`**,仅删 `cellDel` 标记。

### 5.3 cellMerge

- accept/reject **暂不支持**,抛 `UnsupportedFeatureException`。理由:`CTCellMergeTrackChange` 不在 classpath,且合并/拆分涉及两个相邻单元格的 `vMerge` 属性恢复,结构风险与工程量都远高于 cellIns/cellDel。诚实保留为边界。

### 5.4 实现机制(XmlCursor)

accept/reject 对 cell 类都要找到 `cellIns`/`cellDel` 节点的**祖父** `<w:tc>`:

1. 从 `CTTrackChange` 节点开 cursor。
2. `toParent()` → 到 `tcPr`(`<w:tcPr>`)。
3. `toParent()` → 到 `tc`(`<w:tc>`)。
4. accept-keep / reject-remove(对 cellIns)/ accept-remove / reject-keep(对 cellDel)分支:
   - 保留 `tc`:回到 cellIns/cellDel 节点,`removeXml()`(只删标记)。
   - 移除 `tc`:在 `tc` 的 cursor 上 `removeXml()`(整个单元格子树消失,标记随之而去)。

这是本子任务**唯一**的新结构手术逻辑;其余 read/id/details 都复用 property 类的现成路径。

## 6. read walker 扩展

当前 `walkCell` 只下钻 cell 内的 `<w:p>`(经 `walkParagraph`),**不读** `<w:tcPr>`。扩展点:

- 在 `walkCell` 进入 cell 后,先读 `tcPr` 的 `cellIns`/`cellDel`(`tc.isSetTcPr() && tc.getTcPr().isSetCellIns()` 等),命中即产出 `TrackedChange`(location path **不**加 `paragraph` segment,停在 `CELL`)。
- `cellMerge` 因 typed 访问器会 NoClassDefFoundError,走 XmlCursor 在 `tcPr` 子里找本地名 `cellMerge`,读出 author/id/date 产出只读 `TrackedChange`。

## 7. 边界保护

- 不新增第二套 cell 修订 API(进既有 `TrackedChanges` 门面)。
- 不把 cell 修订的「作用对象是哪个 tc」塞回 `location`(location 只表达结构位置,语义交给 `details`)。
- cellMerge 不静默降级:accept/reject 明确抛 `UnsupportedFeatureException`,不假装成功。
- 不让 cell 类需求反向污染文本类 / property 类已定语义。

## 8. 范围边界(pPrChange 等移出)

经 §1.1 验证,以下类型 CT 在 lite 5.2.5 jar 内**全部缺失**,本子任务**不做**:

- `pPrChange`(`CTPPrChange` 缺失)
- `sectPrChange`(`CTSectPrChange` 缺失)
- `tblPrChange`(`CTTblPrChange` 缺失)
- `trPrChange`(`CTTrPrChange` 缺失)

这些要落地需重新生成 poi-ooxml-lite(引入 full schema 的相应 CT 类)或全程 XmlCursor 裸操作。成本与本子任务正交,**回 planning 单独评估**是否拆新子任务。父任务 `prd.md` / `implement.md` 的 pPrChange 条目应据此更新(诚实标记为「CT 类型缺失,单独评估」)。

## 9. 与 spec 的衔接点(N16 候选)

本子任务落地后,应在 `poi-bridge.md` 新增一条 N(建议 **N16**),记录:

- lite schema 的「dangling reference」生成模式:CT 接口声明了返回类型,但该类型 class 不在 jar 内(`getCellMerge`→`CTCellMergeTrackChange`、`getTcPrChange`→`CTTcPrChange` 都是此例)。调这类访问器会 NoClassDefFoundError——**判断某 CT 类型是否可达,必须 `unzip -l` / `javap` 实测,不能只看接口声明**。
- cellIns/cellDel 的 typed 可达性(`CTTcPr.getCellIns/getCellDel` → `CTTrackChange`),与 property 类同委托。
- cell accept/reject 作用于整个 `<w:tc>`(祖父)而非标记本身的结构语义。

## 10. 当前仍待收敛的问题

子任务级设计问题已按推荐方案收敛完成。实现期唯一待定的是 §4.3 cellMerge 的「无委托 TrackedChange」具体构造形态(不影响对外契约,实现期再定)。
