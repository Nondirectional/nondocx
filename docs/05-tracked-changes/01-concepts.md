# 01 · OOXML 修订模型

修订（tracked changes）在 OOXML 里**没有一个根元素**。它由两样东西组成：

1. **一个开关** —— 在 `word/settings.xml`
2. **散落在 `word/document.xml` 正文各处的标记元素** —— 四大类，每类长得不一样

这一篇把四类的 XML 结构讲清楚，并指出 POI 在每一类上的坑。**这是 TC 教程最理论的一篇**，后面三篇都建立在这些概念上。

> 三层递进：先看 OOXML 长什么样 → 看 POI 怎么（不）表达 → 看 nondocx 怎么收容。

---

## 1. 开关：`<w:trackChanges/>`

开关住在**另一个文件** `word/settings.xml`：

```xml
<!-- word/settings.xml -->
<w:settings xmlns:w="...">
    <w:trackChanges/>     <!-- 存在即开启，没有值属性 -->
    ...
</w:settings>
```

**语义要点**：

- **存在即开启**。元素是空的，没有 `val="true"` 之类属性。缺失元素 = 关闭。
- **只控制「后续在 Word 里手动改动是否被追踪」**，与文档里**已有的修订标记**（`<w:ins>`/`<w:del>` 等）是否可见、可接受**完全无关**。
- **与 authoring 正交**：nondocx 的 `Paragraph.addInsertion` 等创作方法直接产出修订标记，**不依赖**开关状态。开关的价值在「接力编辑」—— Agent 把带修订的文档交还给人，人在 Word 里继续改动会被自动追踪。

### POI 的坑：方法名与元素名不一致

POI 不叫 `isTrackChanges()`，而是 `XWPFSettings.isTrackRevisions()`：

```java
// 元素名 trackChanges ↔ 方法名 trackRevisions（凭元素名猜方法名会踩空）
doc.raw().getSettings().isTrackRevisions();
```

nondocx 在 `TrackedChanges.enabled()` / `enable()` / `disable()` 里收了这个不一致，对外就是干净的 `enabled()`。

---

## 2. 四大类修订标记

标记散落在 `word/document.xml` 的 body 树各处（段落里、表格的 cell 里 ……）。nondocx 按结构把它们分成**四大类**（`TrackedChangeFamily`）：

| Family | 含义 | 包含的 OOXML 元素 | `TrackedChangeType` |
|---|---|---|---|
| **TEXT** | 文本级增删 | `<w:ins>` / `<w:del>` | `INS` / `DEL` |
| **MOVE** | 移动（成对） | `<w:moveFrom>` / `<w:moveTo>` | `MOVE_FROM` / `MOVE_TO` |
| **PROPERTY** | 属性变更 | `rPrChange`（运行属性）等 | `RPR_CHANGE` 等 |
| **CELL** | 单元格结构存亡 | `<w:cellIns>` / `<w:cellDel>` / `<w:cellMerge>` | `CELL_INS` / `CELL_DEL` / `CELL_MERGE` |

下面逐类展开。

---

## 3. TEXT 类：插入与删除（`<w:ins>` / `<w:del>`）

最常见、最直观的一类。

```xml
<w:p>
  <w:r><w:t>原有文本</w:t></w:r>

  <w:ins w:id="1" w:author="审阅者甲" w:date="2026-06-18T10:00:00Z">
    <w:r><w:t>新增的文本</w:t></w:r>          <!-- ins 用普通 t -->
  </w:ins>

  <w:del w:id="2" w:author="审阅者甲" w:date="...">
    <w:r><w:delText>被删除的文本</w:delText></w:r>   <!-- del 用 delText！不是 t -->
  </w:del>
</w:p>
```

**三个 OOXML 关键点**：

1. **标记是带属性的容器元素**：`w:id` / `w:author` / `w:date` 三个属性。
2. **被修订的内容是容器内的 `<w:r>`**（run）。
3. **删除用 `<w:delText>`，插入用 `<w:t>`** —— 这是 OOXML 的硬约定。`delText` 与 `t` 是**不同元素**（本地名 `delText` vs `t`），不是同一元素的变体。读删除文本时不能一律读 `<w:t>`，否则读到空。

### POI 的坑：没有 `XWPFIns`/`XWPFDel`

POI **没有** `XWPFIns`/`XWPFDel` 这种高级类型，也**没有**枚举修订的方法。在精简 schema 下，`<w:ins>`/`<w:del>`/`<w:moveFrom>`/`<w:moveTo>` 四种元素**统一由 `CTRunTrackChange` 承载**（它继承 `CTTrackChange` 给 author/date，继承 `CTMarkup` 给 id）。

nondocx 在 `internal/poi/TrackedChangeNodes.collect` 里用 `XmlCursor` 从 body 出发按文档顺序遍历，命中这四种本地名就产出一条 `TrackedChange`（持对应 `CTRunTrackChange`）；删除类文本走 `<w:delText>`。

---

## 4. MOVE 类：移动（成对，`<w:moveFrom>` / `<w:moveTo>`）

「把这段文字从 A 移到 B」在 OOXML 里是**两个配对标记**：A 处一个 `moveFrom`（像 del），B 处一个 `moveTo`（像 ins）。

```xml
<!-- 源段（文字被移走的位置） -->
<w:p>
  <w:moveFromRangeStart w:id="10" w:name="_move_5"/>
  <w:moveFrom w:id="2" w:author="甲" w:date="...">
    <w:r><w:delText>这段被移走</w:delText></w:r>   <!-- moveFrom 用 delText（同 del） -->
  </w:moveFrom>
  <w:moveFromRangeEnd w:id="3"/>
</w:p>

<!-- 目标段（文字移到的位置） -->
<w:p>
  <w:moveToRangeStart w:id="20" w:name="_move_5"/>      <!-- name 与源端相同！配对靠它 -->
  <w:moveTo w:id="5" w:author="甲" w:date="...">
    <w:r><w:t>这段被移走</w:t></w:r>                    <!-- moveTo 用 t（同 ins） -->
  </w:moveTo>
  <w:moveToRangeEnd w:id="21"/>
</w:p>
```

### 三个关键点

1. **结构同 TEXT 类**：`moveFrom` 同 `del`、`moveTo` 同 `ins`（本地名不同，但 `CTRunTrackChange` 同型）。`moveFrom` 用 `delText`、`moveTo` 用 `t`。
2. **配对无显式指针**：`moveFrom` 和 `moveTo` **没有**互相指向的字段。配对靠 rangeStart 上的 **`w:name`**（两端必须相同），nondocx 创作时用 `_move_<baseId>` 保证文档内唯一。
3. **一次移动要 6 个独立 `w:id`**：rangeStart/End ×2 + moveFrom/moveTo。

### nondocx 的配对启发式（accept/reject 时）

OOXML 没给配对指针，nondocx 在 `TrackedChanges.findMoveCounterpart` 里用 **author + text** 启发式查配对端：

- 同一作者、相同文本的另一端
- **date 不作硬约束**（Word 批量同毫秒，但其它工具可能跨秒）
- 单条命中任一端时查配对端、两端同时操作
- **配对端缺失抛 `NoSuchElementException`**（不静默降级 —— 文档可能损坏）

已知边界：同作者同文本多次移动会歧义，取文档顺序第一个。

---

## 5. PROPERTY 类：属性变更（`rPrChange` 等）

「把这个 run 从非粗体改成粗体」这类**属性级**改动，OOXML 用 `*PrChange` 标记。nondocx 目前**只支持 run 属性**（`rPrChange`）：

```xml
<w:r>
  <w:rPr>                                    <!-- 新值（当前属性） -->
    <w:b/>
    <w:rPrChange w:id="1" w:author="甲" w:date="...">
      <w:rPr><w:vanish/></w:rPr>             <!-- 旧值树（改前快照） -->
    </w:rPrChange>
  </w:rPr>
  <w:t>文本</w:t>
</w:r>
```

**两个关键点**：

1. **`rPrChange` 嵌在 `<w:rPr>` 内**，不像 ins/del 那样作为段落的直接子。它有两层 rPr：外层是新值（当前），内层（在 rPrChange 里）是旧值快照。
2. **旧值树的类型是 `CTRPrOriginal`，不是 `CTRPr`** —— 这个类型上的 schema 天然不含 `rPrChange` 子元素，因此**旧值树不可能递归嵌套 rPrChange**。这是 nondocx 创作 `commitStyleAsTracked` 时无需手动剔除防递归的架构保证（spec N17）。

### POI 的坑：CT 类型不同

`rPrChange` 的 CT 类型是 `CTRPrChange`，与文本类的 `CTRunTrackChange` **不同**，共同父是 `CTTrackChange`（都继承它拿 author/date/id）。**没有 `CTPrChange` 这个共同中间类**（精简 schema 未保留）。

这迫使 nondocx 的 `TrackedChange` 走**双委托**：

- 文本/移动类持 `CTRunTrackChange runDelegate`
- 属性类持 `CTTrackChange propertyDelegate`（属性子类型的共同父）
- `raw()` **对属性类抛 `UnsupportedFeatureException`**（方案 C），引导到专用写方法 `acceptProperty`/`rejectProperty`

### 明确不支持

`pPrChange`（段落属性）、`sectPrChange`（节属性）、`tblPrChange`/`trPrChange`（表格属性）的 **CT 类型在 POI 精简 jar 里全部缺失**（`.xsb` schema 资源不在），既不可读也不可写。属 `raw()` 范畴。

---

## 6. CELL 类：单元格结构存亡（`<w:cellIns>` / `<w:cellDel>` / `<w:cellMerge>`）

「这个单元格是新插入的」「这个单元格要被删除」「这两个单元格合并了」—— 表格**结构**级修订。

```xml
<w:tc>                                       <!-- 单元格 -->
  <w:tcPr>                                   <!-- 单元格属性 -->
    <w:cellIns w:id="7" w:author="甲" w:date="..."/>   <!-- 空元素，标记此 tc 是被插入的 -->
  </w:tcPr>
  <w:p>...</w:p>
</w:tc>
```

**三个关键点**：

1. **标记挂在 `<w:tcPr>`（单元格属性）里**，不是段落级，比单元格内的文本类修订高一层。
2. **`cellIns`/`cellDel` 标记的是「单元格本身的存亡」**（表格结构修订），不是单元格内的文本或属性。accept/reject 操作**整个 `<w:tc>` 祖父节点**，不是标记本身 —— 误当文本类处理会写出「本应删除却仍存在」的单元格。
3. **`cellMerge` 是双重阻塞**：CT 类型 `CTCellMergeTrackChange` 既无 Java 类（编译期不可达）也无 `.xsb` schema 资源（运行期不可反序列化）。nondocx 对它**只读、不持委托**（纯值对象），accept/reject 都明确抛 `UnsupportedFeatureException`。

### POI 精简 jar 的 dangling reference（最重要的知识点）

POI 精简 jar（poi-ooxml-lite）只保留 POI 自身运行时调用到的 CT 类。一个 CT 接口可以**声明**返回某类型，但该类型的 class 文件与 `.xsb` schema 资源**都不在** jar 内 —— 叫 dangling reference。

实测（POI 5.2.5 精简 schema）：

| 访问路径 | 类型 | 可达性 |
|---|---|---|
| `CTTcPr.getCellIns()` / `getCellDel()` | → `CTTrackChange` | ✅ 可达（typed 访问器可用） |
| `CTTcPr.getCellMerge()` | → `CTCellMergeTrackChange` | ❌ **编译期不可达** |
| `CTTcPr.getTcPrChange()` | → `CTTcPrChange` | ❌ 编译期不可达 |
| `CTPPrChange` / `CTSectPrChange` / `CTTblPrChange` / `CTTrPrChange` | — | ❌ **全缺** |

**判断某 CT 类型是否可达，必须 `unzip -l`/`javap` 实测**，不能只看接口声明。dangling 的类型连「只读」都做不到 —— `XmlCursor.getObject()` 一旦要把它当类型化对象，就查 `.xsb` schema 资源，查不到运行期抛 `SchemaTypeLoaderException`。

---

## 7. nondocx 怎么把脏活收起来

这张图总结 nondocx 在 `api/track/*` 暴露什么、把什么收进 `internal/`：

```
你的代码
  │  零 POI 泄露
  ▼
api/track/*  (POI-free)
  ├── TrackedChanges        门面（enabled/list/get/accept*/reject*）
  ├── TrackedChange         单条修订（holding-wrapper：持 CT 节点；或 cellMerge 的纯值）
  ├── TrackedChangeType     INS/DEL/MOVE_FROM/MOVE_TO/RPR_CHANGE/CELL_INS/...
  ├── TrackedChangeFamily   TEXT/MOVE/PROPERTY/CELL
  ├── TrackedChangeLocation 位置值对象（segments path）
  └── ChangeDetails 系列    TextChangeDetails / PropertyChangeDetails / CellChangeDetails
  │
  │  转发 / 包装
  ▼
internal/poi/TrackedChangeNodes  (脏活收容所)
  ├── isEnabled / setEnabled      开关（绕过方法名不一致）
  ├── collect                      XmlCursor 遍历 body 树，按文档顺序找修订节点
  ├── acceptText / rejectText      文本/移动类的 XmlCursor 手术（del 的 reject 要 delText→t 转换）
  ├── acceptProperty / rejectProperty   属性类整树替换
  ├── acceptCell / rejectCell      cell 类作用于整个 <w:tc> 祖父
  ├── addInsertion / addDeletion   创作
  └── nextRevisionId               扫已有 w:id 取 max+1
```

**所有 `org.apache.poi.*` 与 `org.openxmlformats.*` 类型只出现在 `internal/` 与 `TrackedChange` 的构造函数签名（内部接缝）里**。公开表面干净。

---

## 8. 一张速查表：四类对比

| 维度 | TEXT | MOVE | PROPERTY | CELL |
|---|---|---|---|---|
| OOXML 元素 | `ins`/`del` | `moveFrom`/`moveTo` | `rPrChange` 等 | `cellIns`/`cellDel`/`cellMerge` |
| 文本元素 | `t` / `delText` | `moveTo`=t / `moveFrom`=delText | —（属性无文本） | —（结构无文本） |
| CT 类型 | `CTRunTrackChange` | `CTRunTrackChange`（同型） | `CTRPrChange`（等） | `CTTrackChange` / cellMerge 缺失 |
| 读 | ✅ | ✅ | ✅（仅 rPrChange） | ✅（cellMerge 只读属性文本） |
| accept/reject | ✅ 通用 | ✅ 通用（配对联动） | ✅ 专用方法 | ✅ 专用方法（cellMerge ❌） |
| 创作 | ✅ | ✅ | ✅（仅 rPrChange） | ✅（cellMerge ❌） |
| 挂载位置 | 段落直接子 | 段落直接子 + rangeStart/End | `<w:rPr>` 内 | `<w:tcPr>` 内 |

---

## 下一步

概念清楚了，去看 nondocx 怎么**读**这些修订 → [02 · 读与查询](./02-read-and-query.md)。
