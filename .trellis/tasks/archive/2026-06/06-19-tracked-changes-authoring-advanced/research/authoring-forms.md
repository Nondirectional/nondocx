# Research — 高级类型创作的真实 OOXML 结构

> 本子任务「先研究,再实现」(implement.md Step 1-3)的产出。
> 用一次性探针 `AdvancedAuthoringProbeTest` + `AdvancedAuthoringRoundTripProbeTest` 捕获真实结构并验证闭环(探针已删除)。

## 1. rPrChange 创作(已探针验证)

### 1.1 真实 OOXML 结构

构造「run 当前 bold(新值)、原本 vanish(旧值)」后 dump:

```xml
<w:rPr>                                    <!-- 新值(当前 rPr) -->
  <w:b/>
  <w:rChange w:id="1" w:author="甲" w:date="...">
    <w:rPr><w:vanish/></w:rPr>             <!-- 旧值树(pristine) -->
  </w:rChange>
</w:rPr>
```

### 1.2 关键发现:内 rPr 类型是 CTRPrOriginal,架构层防递归

`rPrChange.getRPr()` 返回 **`CTRPrOriginal`**(实现类 `CTRPrOriginalImpl`),**不是** `CTRPr`。

- `CTRPrOriginal` 这个「原始 rPr」类型**天然不含 `rPrChange` 子元素**(schema 层面就没有这个访问器)。
- **结论**:旧值树不可能递归嵌套 rPrChange,**无需手动剔除防递归**。这是 design §7 提的最大不确定性的明确答案——架构已经防住。
- 实现上:`change.addNewRPr()` 返回 `CTRPrOriginal`,往里加旧属性子元素(b/vanish/i 等)即可。

### 1.3 round-trip 闭环验证

手搓 rPrChange → save → reopen → `list()`:
- 读回 1 条 `RPR_CHANGE`,author=甲,details=`RUN_PROPERTIES: new=[b], old=[vanish]`。
- `rejectProperty(id)`:剩余 0 条(旧值树覆盖新值,标记消失)。✓

**结论**:rPrChange 创作结构正确,能被既有 read/accept-reject 完整处理。

## 2. move 创作(已探针验证)

### 2.1 真实 OOXML 结构(四件配对)

源段:
```xml
<w:moveFromRangeStart w:id="1" w:name="_move_1"/>
<w:moveFrom w:id="2" w:author="甲" w:date="...">
  <w:r><w:delText>被移走的文字</w:delText></w:r>   <!-- 源端用 delText -->
</w:moveFrom>
<w:moveFromRangeEnd w:id="3"/>
```

目标段:
```xml
<w:moveToRangeStart w:id="4" w:name="_move_1"/>   <!-- name 与源端配对 -->
<w:moveTo w:id="5" w:author="甲" w:date="...">
  <w:r><w:t>被移走的文字</w:t></w:r>               <!-- 目标端用 t -->
</w:moveTo>
<w:moveToRangeEnd w:id="6"/>
```

### 2.2 关键发现:name 配对是 rangeStart 的事

- `w:name` 只在 `moveFromRangeStart`/`moveToRangeStart` 上,**两端必须相同**(`_move_1`)。
- `moveFrom`/`moveTo` 本身**不带 name**——配对靠 rangeStart 的 name,不是靠 moveFrom/moveTo 的 id。
- 实现需生成唯一 name;若担心与已有 name 冲突,用强前缀 `_move_<baseId>`(baseId 来自 nextRevisionId,文档内唯一)。

### 2.3 id 分配(6 个独立 id)

一次 move 需要 6 个独立 `w:id`:rangeStart(源) + moveFrom + rangeEnd(源) + rangeStart(目标) + moveTo + rangeEnd(目标)。全部走 `nextRevisionId` 递增,不能复用。

### 2.4 round-trip 闭环验证

手搓 move → save → reopen → `list()`:
- 读回 2 条:`MOVE_FROM`(author=甲)+ `MOVE_TO`(author=甲),配对正确。✓

**结论**:move 创作结构正确,能被既有 read 配对读回(accept 联动由既有 accept-text 逻辑处理,已验证)。

## 3. cell 创作(N16 已验证,无需新探针)

cellIns/cellDel 的创作是 N16 读侧的反向:`CTTcPr.addNewCellIns()`/`addNewCellDel()` 建出 `<w:tcPr><w:cellIns .../></w:tcPr>`,结构 N16 已 dump 确认。创作后必然能被既有 read/accept-reject 处理(同一结构)。

## 4. 带格式插入(零改动,沿用现有)

`addInsertion` 返回 Run,链式 set 样式只改 run 的 rPr,不影响 ins 包装。无需探针,实现期补 round-trip 测试即可。

## 5. 探针删除

`AdvancedAuthoringProbeTest` + `AdvancedAuthoringRoundTripProbeTest` 是研究一次性产物,结论已落入本文件。按惯例删除,进入生产实现。
