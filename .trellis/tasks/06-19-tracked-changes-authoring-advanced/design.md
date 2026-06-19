# Design — tracked changes 高级类型创作(move / property / cell / 带格式插入)

> 配套 `prd.md`。本文记录四类高级修订类型创作的技术设计:API 形态、OOXML 结构、CT 达成性、各类的语义陷阱。
>
> 四个 API 形态决策已在 brainstorm 收敛(见 prd.md Open Questions)。本文是它们的**技术展开**。

## 1. 总体设计原则(沿用父任务 R3 + 前序 authoring)

- **显式 tracked 方法**:每个能力一个 `*Tracked`/`*Inserted`/`*Deleted`/`commit*` 方法,不引入全局录制。
- **POI-free 公共表面**:创作方法住在领域类型(`Paragraph`/`Run`/`Cell`)上;CT/XmlBeans 脏活下沉到 `internal/poi/TrackedChangeNodes`。
- **自动元数据**:author 由调用方传;date 用 `Calendar.getInstance()`;`w:id` 用既有 `TrackedChangeNodes.nextRevisionId(doc)` 自动分配(与 ins/del 一致)。
- **与开关正交**:不依赖 `<w:trackChanges/>`,显式写出修订节点。
- **闭环验证**:创作出的修订必须能被既有 read 读回、被对应 accept/reject 正确处理。

## 2. 四类 CT 达成性(已 javap 实测,全部可做)

| 类型 | CT 类 / 访问器 | 达成性 | 创作路径 |
|---|---|---|---|
| 带格式插入 | `CTRunTrackChange`(`addInsertion` 已建好 `<w:ins>` 骨架) | ✅ | 现有 `addInsertion` 返回 `Run`,链式设样式 |
| 属性(rPrChange) | `CTRPrChange` + `CTRPr.addNewRPrChange()` | ✅ | 新建 rPrChange,旧 rPr 树挂 `rPrChange/rPr`,新值在 run 当前 rPr |
| 单元格(cellIns/cellDel) | `CTTrackChange` + `CTTcPr.addNewCellIns()/addNewCellDel()` | ✅ | 建节点设 id/author/date |
| 移动(move) | `CTMoveBookmark` + `CTMarkupRange` + `CTP.addNewMoveFromRangeStart/End()` + `addNewMoveToRangeStart/End()` + `addNewMoveFrom()`/`addNewMoveTo()` | ✅ | 源端 4 件 + 目标端 4 件配对 |

**排除**:cellMerge 创作不做(`CTCellMergeTrackChange` 不在精简 jar,见 N16)。pPrChange/sectPrChange/tblPrChange/trPrChange 创作不做(CT 类型全缺)。

## 3. 四类设计

### 3.1 带格式的插入修订(成本最低,基本零改动)

**决策**:不重载,复用现有 `Paragraph.addInsertion(author, text)`。

- `addInsertion` 已返回新插入的 `Run`;`Run` 已有 `bold()`/`italic()`/`underline()`/`font()`/`fontSize()`/`color()` 链式方法。
- 调用方:`p.addInsertion("甲","强调").bold().color("FF0000")`。
- `<w:ins>` 是包装元素,内 run 的 `<w:rPr>` 独立——链式设样式只改 run 的 rPr,不影响 ins 标记。OOXML 无「样式后置」语义问题。
- **本子任务工作量**:补示例 + round-trip 验证(确认带样式 ins 能被 read 读回、accept 正确保留样式)。无新代码。

### 3.2 属性修订 rPrChange(语义最微妙)

**决策**:两步式 `run.commitStyleAsTracked(author)`。

**OOXML 结构**:
```xml
<w:r>
  <w:rPr>                          <!-- 新值(当前属性) -->
    <w:b/><w:i/>
    <w:rPrChange w:id="1" w:author="甲" w:date="...">
      <w:rPr><w:vanish/></w:rPr>   <!-- 旧值树(pristine,accept 时用它覆盖新值) -->
    </w:rPrChange>
  </w:rPr>
  <w:t>文本</w:t>
</w:r>
```

**关键语义陷阱——「旧值树」是什么**:
- rPrChange 内的 `<w:rPr>` 是**修改前**的属性快照(pristine),不是「新值」。
- 读侧 `PropertyChangeDetails` 的 `newSummary` = 当前 rPr,`oldSummary` = rPrChange 内的 rPr。
- `rejectProperty` 用 rPrChange 内的旧 rPr **整树替换**当前 rPr(回到修改前);`acceptProperty` 删 rPrChange 标记(新值生效)。

**两步式如何工作**:
1. 调用方先链式改样式:`run.bold().italic()`(改的是当前 rPr)。
2. 再调 `run.commitStyleAsTracked(author)`。
3. **实现难点**:此时 run 的当前 rPr **已经是新值**了,「旧值」从哪来?
   - 方案 A(推荐):`commitStyleAsTracked` 不自己推算旧值——它把「调用前那一刻的 rPr」当作旧值。但调用方已改完,旧值丢失。
   - **正确方案**:不能在「改完」后才有旧值。需在 `Run` 引入「待提交的 tracked 样式变更」概念——但这违背「显式无状态」原则。
   - **务实方案(定)**:`commitStyleAsTracked` 要求调用方在改样式**之前**先调 `run.beginTrackedStyle()`(或类似),此时快照旧 rPr;改完再 `commit`。但这又变三步。
   - **最终务实决策**:本子任务采用**显式两步但内部一快照**——`commitStyleAsTracked` 把当前 rPr 当作**新值**,旧值由调用方传入「修改前的 RunStyle 快照」。即签名 `run.commitStyleAsTracked(author, RunStyle previousStyle)`。调用方先 `RunStyle before = run.style()`、再改样式、再 `commit(author, before)`。这避免 Run 引入可变状态,最贴「显式无状态」。

> ⚠️ 这是本子任务最需要在实现前用探针验证的点(rPrChange 的旧 rPr 树怎么挂、accept/reject 后是否正确还原)。见 implement.md Step 1。

### 3.3 单元格修订 cellIns/cellDel(结构最简)

**决策**:`cell.markInserted(author)` / `cell.markDeleted(author)`(挂在 `api/table/Cell`)。

**OOXML 结构**:
```xml
<w:tc>
  <w:tcPr>
    <w:cellIns w:id="1" w:author="甲" w:date="..."/>   <!-- 裸属性,id/author/date -->
  </w:tcPr>
  <w:p>...</w:p>
</w:tc>
```

- 节点类型是 `CTTrackChange`(与 cell 的 read/accept-reject 同委托),`CTTcPr.addNewCellIns()`/`addNewCellDel()` 直接建。
- 设 id/author/date 三件即可,无文本、无 run、无属性树——四类里结构最简。
- 创作后:read 读为 `CELL_INS`/`CELL_DEL`,accept/reject 作用于整个 `<w:tc>`(N16 已验证)。
- **cellMerge 不支持**:`Cell` 不提供 markMerged,诚实排除。

### 3.4 移动修订 move(结构最复杂)

**决策**:`targetParagraph.moveRunsFrom(author, sourceParagraph, List<Run> runs)`。

**OOXML 结构(完整四件配对)**:
```xml
<!-- 源段(sourceParagraph) -->
<w:moveFromRangeStart w:id="10" w:name="move1"/>     <!-- 范围起始,带唯一 name -->
<w:moveFrom w:id="1" w:author="甲" w:date="...">     <!-- 源端:被移走的文本用 delText -->
  <w:r><w:delText>被移走的文字</w:delText></w:r>
</w:moveFrom>
<w:moveFromRangeEnd w:id="11"/>

<!-- 目标段(targetParagraph = 接受方) -->
<w:moveToRangeStart w:id="20" w:name="move1"/>       <!-- name 与源端配对 -->
<w:moveTo w:id="2" w:author="甲" w:date="...">       <!-- 目标端:文本用 t -->
  <w:r><w:t>被移走的文字</w:t></w:r>
</w:moveTo>
<w:moveToRangeEnd w:id="21"/>
```

**关键设计点**:
- **rangeStart/End 的 `w:name` 必须配对**:源端 `moveFromRangeStart.name` 与目标端 `moveToRangeStart.name` 相同(如 `move1`),Word/读侧据此判定配对。实现需生成唯一 name。
- **源端文本用 `delText`、目标端用 `t`**(同 del/ins 规则,N12)。
- **moveFrom/moveTo 各自带 id/author/date**(走 `CTRunTrackChange`,同 ins/del 委托类型)。
- **rangeStart/End 的 id 与 moveFrom/moveTo 的 id 独立**:用 `nextRevisionId` 分配多个递增 id,不能复用。

**语义边界**:
- `runs` 必须属于 `sourceParagraph`(实现校验,否则抛 IllegalArgumentException)。
- move 后源段那些 run 进入 `moveFrom` 语义路径(类似 deletion),不再是稳定 live wrapper——接受方是 targetParagraph,返回新插入的 run 列表(类似 addInsertion 返回 Run)。
- **配对完整性**:必须一次性写出源+目标全部节点,不能只写一半(读侧对孤立 moveFrom 的 accept 会抛「配对端」异常,见 advanced-types 测试)。

> ⚠️ move 是本子任务风险最高、最需探针验证的:rangeStart/End 的 name 配对、id 分配、moveFrom 内 run 的 t→delText 转换、配对后能否被既有 accept 联动正确处理。见 implement.md Step 2。

## 4. 公共 API 形态(收敛后)

| 能力 | 方法 | 住在 |
|---|---|---|
| 带格式插入 | `Paragraph.addInsertion(author, text)`(复用)→ 链式 set 样式 | `Paragraph`(已存在) |
| 属性修订 | `Run.commitStyleAsTracked(author, RunStyle previousStyle)`(新) | `Run` |
| 单元格插入 | `Cell.markInserted(author)`(新) | `api/table/Cell` |
| 单元格删除 | `Cell.markDeleted(author)`(新) | `api/table/Cell` |
| 移动 | `Paragraph.moveRunsFrom(author, sourceParagraph, List<Run> runs)`(新) | `Paragraph` |

所有新方法:
- 返回值遵循前序 authoring 惯例(insertion 类返回新 `Run`,容器类返回 `Paragraph`/`Cell`)。
- author 必传,自动 date + `w:id`。
- 异常:author 空 / run 不属源段 / cellMerge 不支持,抛 `IllegalArgumentException` 或返回明确错误。

## 5. 内部实现落点(TrackedChangeNodes 扩展)

新增内部 helper(均 static,CT 脏活):
- `commitRPrChange(CTR run, RunStyle previousStyle, author, date, id)` —— 建 rPrChange、挂旧 rPr 树。
- `markCellIns(CTTcPr tcPr, author, date, id)` / `markCellDel(...)` —— 建 cellIns/cellDel。
- `moveRuns(CTP target, CTP source, List<CTR> runs, author, date, baseId)` —— 建 moveFrom 四件 + moveTo 四件(源端改 delText、目标端建 run)。

## 6. 与消费侧的集成验证(闭环)

每个创作方法必须有 round-trip 测试:
1. 创作(doc in-memory)。
2. save → reopen。
3. `list()` 读回:确认 type/details/location 正确(尤其 move 的配对、rPrChange 的新旧摘要、cellIns/cellDel 的 kind)。
4. 对应 accept/reject:确认文档结构结果正确(rPrChange accept 删标记、reject 用旧树覆盖;move accept 联动移除源保留目标;cell accept/reject 作用于 tc)。

这是「创作侧补齐后,tracked changes 形成完整闭环」的最终验证。

## 7. 待研究/实现期定的细节

- rPrChange 的「旧 rPr 树」:是浅拷贝当前 rPr 还是要剔除 rPrChange 自身(防递归)?——探针 Step 1 验证。
- move rangeStart/End 的 name 唯一性:若文档已有 `move1`,实现需查重或用更强前缀。——探针 Step 2 验证。
- `Run.commitStyleAsTracked` 的 `previousStyle` 参数是否友好:是否提供便捷的「先快照」模式?——实现期定,默认两步式。
