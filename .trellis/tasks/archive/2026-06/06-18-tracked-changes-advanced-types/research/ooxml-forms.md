# Research — 高级修订类型的真实 OOXML 形态

> 本文件是 advanced-types 子任务「先研究,再实现」(implement.md §2 Step 1)的产出。
> 用一次性探针 `AdvancedTypesProbeTest` 捕获真实 XML(已删除),据本结论定实现范围。

## 1. 三类高级修订的真实 OOXML 结构

### 1.1 move(moveFrom / moveTo)

探针 XML:
```xml
<w:p>
  <w:moveFrom w:id="1" w:author="non"><w:r><w:t>源</w:t></w:r></w:moveFrom>
  <w:moveTo   w:id="2" w:author="non"><w:r><w:t>目标</w:t></w:r></w:moveTo>
</w:p>
```

**结论**:
- `moveFrom` / `moveTo` 在结构上与 `ins` / `del` **完全同型**——都是 `CTRunTrackChange`(`CTRunTrackChangeImpl`),直接挂在段落/table cell 下,内含 `<w:r><w:t>`。
- **read 侧已完整覆盖**:`collect()` 的 `TEXT_LOCAL_NAMES` 表已含 `moveFrom`/`moveTo`,`walkParagraph` 已经能枚举它们。即 read 侧**已能**读出 move 修订。
- **配对关系是隐式的**:OOXML 不在 `moveFrom`/`moveTo` 上写配对指针;配对靠 author + 文本内容 + 文档位置启发式还原。无显式 `counterpart` 字段。

### 1.2 属性类修订(rPrChange 等)

探针 XML(rPrChange 为例):
```xml
<w:r>
  <w:rPr>
    <w:b/>
    <w:rPrChange w:id="1" w:author="non"><w:rPr/></w:rPrChange>
  </w:rPr>
  <w:t>被改样式的文本</w:t>
</w:r>
```

**结论**:
- `rPrChange` **不在 run 包装层**,而是**嵌在 `<w:rPr>` 内部**(run properties 里)。CT 类型是 `CTRPrChangeImpl`,**不是** `CTRunTrackChange`。
- 结构:**外层 `<w:rPr>` 表达"当前(新)样式"**(本例 `<w:b/>` 即现在粗体);**`<w:rPrChange>` 内的 `<w:rPr>` 表达"改之前的旧样式"**(本例空 rPr,即原来非粗体)。
- **read 侧目前完全不覆盖**:`walkParagraph` 只在段落直接子层查 `TEXT_LOCAL_NAMES`,不下钻 `<w:r>` → `<w:rPr>`。属性类修订对当前 `collect()` **不可见**。
- accept/reject 语义是**整棵属性子树的替换**(accept:保留外层新 rPr、删 rPrChange;reject:用 rPrChange 内旧 rPr 覆盖外层),不是字段级 merge。

### 1.3 单元格结构类(cellIns / cellDel)

探针 XML(cellIns 为例):
```xml
<w:tbl><w:tr><w:tc>
  <w:tcPr>
    <w:cellIns w:id="1" w:author="non"/>
  </w:tcPr>
</w:tc></w:tr></w:tbl>
```

**结论**:
- `cellIns` / `cellDel` **嵌在 `<w:tcPr>`(单元格属性)内**,不在 cell 包装层。CT 类型是 `CTTrackChangeImpl`(裸 id/author,**无 run、无文本**)。
- 它表达"这个单元格是被插入/删除的"(表格结构修订),不是文本内容。
- **read 侧目前完全不覆盖**:`walkCell` 只下钻到 cell 内的 `<w:p>`,不读 `<w:tcPr>`。

## 2. read 侧覆盖现状(关键)

| 类型 | read 侧是否已枚举 | 原因 |
|---|---|---|
| moveFrom / moveTo | ✅ 已覆盖 | 与 ins/del 同型,walkParagraph 直接命中 |
| rPrChange / pPrChange / 等属性类 | ❌ 未覆盖 | 嵌在 rPr/pPr 内,walker 不下钻 |
| cellIns / cellDel | ❌ 未覆盖 | 嵌在 tcPr 内,walkCell 不读 tcPr |

**即:design §4「同时补齐枚举能力」对本子任务成立——只有 move 已被 read 读到,property 与 cell 两类的 read 侧需要在本子任务补齐。**

## 3. 拆分判定(implement.md §2 Step 5)

三类高级修订的**实现复杂度差异巨大**,且结构上**互相正交**:

| 类别 | read 工作量 | accept/reject 工作量 | 结构风险 |
|---|---|---|---|
| **move** | 零(read 已覆盖) | 低(与 ins/del 同型 mechanics) | 低(配对是唯一新点) |
| **property** | 中(需下钻 rPr/pPr,6+ 种属性树) | 中(整树替换,含旧 rPr 解析) | 中(details 容易空壳) |
| **cell** | 中(需下钻 tcPr) | 中(结构语义,非文本) | 高(误当文本类会写出结构错文档) |

### 判定:**本子任务做 move + property,cell 回 planning 拆成独立子任务。**

(用户决策:范围 = move + property;move 做配对联动。)

理由:
1. **move 与已落地的文本类完全同型**——`acceptText`/`rejectText` 的 mechanics(ins 类 unwrap / del 类 remove)对 moveTo/moveFrom 直接适用。只需把门面的 `family == TEXT` gate 放宽到 `family == TEXT || family == MOVE`。实现收敛快、风险低。
2. **property 是独立但可控的工程**:需扩展 read walker(下钻 rPr/pPr)、设计 details(属性树类型 + 旧/新)、定 accept/reject 整树替换。与 move 同做合理。
3. **cell 结构风险最高**(误当文本类会写出结构错文档),单独拆出最稳。符合 implement.md §6「按 move -> property -> cell 顺序收缩」。

### 本子任务(advanced-types)实际范围

- **move read**:已覆盖(确认),无需改。
- **move write**:门面 gate 放宽到含 MOVE;底层 mechanics 已就绪。
- **move 配对联动**:**做**。配对策略见下「配对方案」。
- **property read**:扩展 walker 下钻 `<w:rPr>`(run 属性)与 `<w:pPr>`(段落属性),枚举 `rPrChange`/`pPrChange`。
- **property write**:accept(删 rPrChange、留新属性树)/ reject(用旧 rPr 覆盖外层、删 rPrChange)。
- **cell**:**不做**,回 planning 拆新子任务 `06-18-tracked-changes-cell-types`。

### 配对方案(moveFrom / moveTo)

OOXML **无显式配对指针**(探针 + CT 类型检查确认:`CTRunTrackChange` 无 counterpart/pair 字段)。Word 实际的配对依据:

- **author + 文本内容相同**:被移动的文本在 moveFrom(用 delText 语义,moveFrom 同 del)与 moveTo(用 t,同 ins)里文本一致;同一作者。
- **date 不作硬约束**:不同实现写入 date 的精度不同(Word 批量同毫秒,但手工/其它工具可能跨秒),故 date 不参与硬过滤,只靠 author + text 定配对。

**配对算法**(实现):
1. 单条命中 moveFrom 或 moveTo 时,在 `collect` 出的 move 修订里查配对端:同 author + 同 text 的另一 type。
2. 取文档顺序第一个匹配的 (author, text) 另一端。
3. **accept move**:两端都按 accept 处理(moveFrom=删除生效=移除,moveTo=插入生效=保留文本)。
4. **reject move**:两端都按 reject 处理(moveFrom=删除撤销=恢复文本,moveTo=插入撤销=移除)。
5. **配对端缺失/损坏**:抛 `NoSuchElementException`(design §5.1:不静默降级),消息指明「找不到配对端」。

**已知边界**:(author, text) 二元组非唯一(同一作者移动了相同文本多次)会配对歧义——按文档顺序取第一个。这是启发式方案的固有局限,写明为已知边界。

## 4. cell 拆出的新子任务(待回 planning 创建)

- `06-18-tracked-changes-cell-types`:单元格结构类(cellIns/cellDel)。read 下钻 tcPr;details 表达结构语义;accept/reject 围绕结构节点保留/删除。本子任务不做。

## 5. 探针删除

`AdvancedTypesProbeTest` 是研究一次性产物,结论已落入本文件。按"探针先于生产代码、确认后删除"的项目惯例,实现阶段开始前删除。
