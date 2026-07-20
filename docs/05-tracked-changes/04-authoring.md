# 04 · 创作（authoring）

[03 accept/reject](./03-accept-reject.md) 讲了怎么处理文档里**已有**的修订。这一篇讲怎么**主动写出**新的修订标记 —— 「创作（authoring）」侧。

> **创作** = 程序（Agent 或普通 Java 程序）主动写出 `<w:ins>`/`<w:del>` 等标记，让改动以「修订」形式进入文档，便于后续审阅/接受。
> 它与普通编辑（`run.text("x").bold()`）的区别：普通编辑直接改文档、不留痕；创作改文档、留下可审阅的修订标记。

---

## 1. 入口不在门面，在内容类型上

这是个**关键设计决定**：

| 操作类型 | 入口位置 |
|---|---|
| **accept/reject**（处理已有修订） | `TrackedChanges` 门面 |
| **创作**（写出新修订） | **内容类型** `Paragraph` / `Run` / `Cell` 上 |

理由：accept/reject 是「对文档修订状态负责」，归门面；创作属于「**在某处写内容**」，归那个内容类型。这与 [02 架构](../02-architecture.md) 的「活对象各管各的委托」哲学一致。

---

## 2. 创作与开关正交（再次强调）

`<w:trackChanges/>` 开关（`tc.enable()`/`disable()`）**只控制后续在 Word 里手动改动是否被追踪**。它**不影响** nondocx 的创作 API：

```java
// 即使开关关闭，下面三行仍然写出修订标记
doc.paragraph(0).addInsertion("审阅者甲", "新增内容");
doc.paragraph(0).addDeletion("审阅者甲", someRun);
doc.paragraph(0).run(0).replaceTracked("审阅者甲", "替换文本");
```

创作的标记会进入 `tc.list()`，可被 `accept`/`reject`，与文档里 Word 产生的修订无差别。

**两套价值的分工**：

- 创作 API（程序主动产出修订）→ 适合 Agent 把改动以修订形式交给人审阅
- `tc.enable()`（打开开关）→ 适合「程序 → 人」接力，让人的后续手动改动也被追踪

---

## 3. 四类创作 API

### 3.1 文本类：插入 / 删除 / 替换

```java
// 插入：在段末追加一条 tracked 插入，返回新 run（可继续链式样式）
Run ins = doc.paragraph(0).addInsertion("审阅者甲", "新增的一句话");
ins.bold().color("FF0000");   // 链式样式 —— 见 §3.5 带格式插入

// 删除：把段内某个已有 run 标记为 tracked 删除
Paragraph p = doc.paragraph(0);
Run target = p.run(2);
p.addDeletion("审阅者甲", target);   // 返回 this（段落）

// 替换：del + ins 的组合，一步完成
Run newRun = doc.paragraph(0).run(0).replaceTracked("审阅者甲", "替换后的文本");
```

| 方法 | 签名 | 返回 |
|---|---|---|
| `Paragraph.addInsertion(author, text)` | ✏️🔄 | 新插入的 `Run` |
| `Paragraph.addDeletion(author, targetRun)` | ✏️🔄 | `this`（段落） |
| `Run.replaceTracked(author, newText)` | ✏️🔄 | 新插入的 `Run` |

**几个 OOXML 细节**：

- **插入**：段末新建 `<w:ins>`，内含一个带 `<w:t>` 的 `<w:r>`。
- **删除**：段内新建 `<w:del>`，把目标 run 的 `<w:t>` 转 `<w:delText>`，再把该 `<w:r>` 迁入 `<w:del>` 内部。
- **替换**：不是独立元素，是 `del`（原 run）+ `ins`（新文本）的组合；新 ins run 会**复制源 run 的六个内联样式属性**，保留原格式。

### 为什么 addDeletion 返回段落而非 Run

迁入 `<w:del>` 内部后，原 run 已非稳定的普通 live wrapper，继续暴露会误导。返回 `this` 段落让你继续在段上操作。

### 校验

- `addDeletion` 的 target **必须属于本段落**（否则抛 `NoSuchElementException`：「目标 run 不属于本段落」）
- `replaceTracked` 的 run **必须挂在段落上**（裸 CTR 构造的 run 抛 `NoSuchElementException`）
- author 为 null/空白 → `IllegalArgumentException`

### 3.2 带格式插入（零额外 API）

`addInsertion` 已经返回 `Run`，直接链式样式即可：

```java
doc.paragraph(0)
   .addInsertion("审阅者甲", "醒目新增")
   .bold().color("FF0000").fontSize(16);
```

**OOXML 语义**：`<w:ins>` 是包装元素，内部 run 的 `<w:rPr>` 独立 —— 样式后置无任何 OOXML 问题。

### 3.3 属性类：`commitStyleAsTracked`（rPrChange 创作）

「把这个 run 从样式 A 改成样式 B，并记录属性变更」是**两步式**：

```java
import io.github.nondirectional.docx.core.api.style.RunStyle;

Run r = doc.paragraph(0).run(0);
RunStyle before = r.style();            // 第一步：捕获改前快照（六样式）
r.bold().color("0000FF");               // 第二步：链式改样式（正常 API）
r.commitStyleAsTracked("审阅者甲", before);  // 第三步：提交属性修订
```

产出结构：

```xml
<w:rPr>                                    <!-- 新值（当前 rPr） -->
  <w:b/>
  <w:color w:val="0000FF"/>
  <w:rPrChange w:id="1" w:author="审阅者甲">
    <w:rPr><w:vanish/></w:rPr>             <!-- 旧值树（before 渲染） -->
  </w:rPrChange>
</w:rPr>
```

#### `CTRPrOriginal` 天然防递归（重要架构发现）

`CTRPrChange.addNewRPr()` 返回 **`CTRPrOriginal`**（不是 `CTRPr`）。`CTRPrOriginal` 的 schema **天然不含 `rPrChange` 子元素** —— 旧值树不可能递归嵌套 rPrChange。

> 这是 design 期最大的不确定性：「要不要手动剔除防递归」的明确答案 —— **架构已经防住**，无需手动处理。

#### `RunStyle` 的六样式

`RunStyle` 只覆盖六个内联样式：`bold`/`italic`/`underline`/`font`/`fontSize`/`color`。commitStyleAsTracked 只能追踪这六种属性的变更，其它 run 属性（如下划线颜色、字符间距等）的 rPrChange 创作仍走 `raw()`。

### 3.4 cell 类：`markInserted` / `markDeleted`

```java
// 把一个单元格标记为「tracked 插入」（表格新增了一格）
doc.table(0).row(0).cell(2).markInserted("审阅者甲");

// 把一个单元格标记为「tracked 删除」（这一格要被删）
doc.table(0).row(1).cell(1).markDeleted("审阅者甲");
```

| 方法 | 签名 | 返回 |
|---|---|---|
| `Cell.markInserted(author)` | ✏️🔄 | `this` |
| `Cell.markDeleted(author)` | ✏️🔄 | `this` |

**OOXML 结构**（[01 §6](./01-concepts.md#6-cell-类单元格结构存亡wcellins--wcelldel--wcellmerge) 的反向）：`<w:tcPr>` 内加 `<w:cellIns>` 或 `<w:cellDel>` 空属性节点（设 id/author/date）。

创作后这些标记**自然能被既有 read/accept-reject 处理**（与 Word 产生的同结构）。`acceptCell`/`rejectCell` 的语义见 [03 §5](./03-accept-reject.md#5-cell-类专用方法--作用于整个-wtc-祖父)。

> **cellMerge 不提供创作方法** —— `CTCellMergeTrackChange` 类型缺失（dangling reference），诚实排除。

### 3.5 move：`moveRunsFrom`

把一个段落的若干 run **移动**到另一个段落（接受方是目标段）：

```java
Paragraph source = doc.paragraph(0);
Paragraph target = doc.paragraph(1);
List<Run> toMove = List.of(source.run(0), source.run(1));

List<Run> moved = target.moveRunsFrom("审阅者甲", source, toMove);
// moved 是目标段新插入的 run（moveTo 内）
```

| 方法 | 签名 | 返回 |
|---|---|---|
| `Paragraph.moveRunsFrom(author, sourceParagraph, runs)` | ✏️ | 目标段新插入的 `List<Run>` |

**OOXML 结构**（四件配对，[01 §4](./01-concepts.md#4-move-类移动成对wmovefrom--wmoveto) 已详述）：

```xml
<!-- 源段 -->
<w:moveFromRangeStart w:id="10" w:name="_move_5"/>
<w:moveFrom ...><w:r><w:delText>...</w:delText></w:r></w:moveFrom>
<w:moveFromRangeEnd w:id="3"/>

<!-- 目标段 -->
<w:moveToRangeStart w:id="20" w:name="_move_5"/>      <!-- name 与源端相同 -->
<w:moveTo ...><w:r><w:t>...</w:t></w:r></w:moveTo>
<w:moveToRangeEnd w:id="21"/>
```

**两个关键点**：

1. **配对靠 rangeStart 的 `w:name`**（`_move_<baseId>`，文档内唯一），**不靠** moveFrom/moveTo 的 id。一次移动需 6 个独立 `w:id`。
2. **moveXml 后源 CTR 句柄 `XmlValueDisconnected`** —— 目标端 run 的文本必须在 moveXml **之前**预捕获，不能移动后再读源 run 的 delText。这个细节在 `internal/poi` 收着，用户无感。

**校验**：所有 run **必须属于 sourceParagraph**（否则抛 `IllegalArgumentException`）；runs 不能为空。

---

## 4. `nextRevisionId` 与两套 id 概念（重要，易混）

创作时要分配 `w:id`。nondocx 用 `nextRevisionId` 扫描文档已有修订的 `w:id` 取 `max+1`。

**两套 id 切勿混淆**：

| | `w:id`（OOXML 修订 id） | `TrackedChange.id()`（nondocx 稳定 id） |
|---|---|---|
| 来源 | OOXML 元素属性，创作时由 `nextRevisionId` 分配 | nondocx 生成的进程内标识（`type:location:w:id` 混合串） |
| 写入文档 | ✅ 是元数据，序列化到 docx | ❌ 不写入文档，只活在对 `TrackedChanges` 的访问中 |
| 稳定性 | 跨 save 保持（在文档里） | 进程内稳定，**不承诺跨 save** |
| 用途 | OOXML 层面的修订标识 | nondocx 调用方定位单条修订（`tc.get(id)`） |

> `TrackedChange.wId()`（public）专门读底层 `w:id`，跨两个委托槽（`runDelegate`/`propertyDelegate`）取非空那个。它的存在是因为 `raw()` 对属性/cell 类抛异常，而 `nextRevisionId` 需要扫所有 family 的 id —— `wId()` 填了这个空。

---

## 5. author / date / id 的约定

所有创作方法：

| 参数 | 谁负责 |
|---|---|
| `author` | **调用方必传**（null/空白抛 `IllegalArgumentException`） |
| `date` | 库自动分配 `Calendar.getInstance()`（当前时间） |
| `w:id` | 库自动分配（`nextRevisionId`，文档内 `max+1`） |

---

## 6. 一个完整创作示例

```java
import io.github.nondirectional.docx.core.api.style.RunStyle;

try (Document doc = Docx.open(Path.of("draft.docx"))) {
    Paragraph p0 = doc.paragraph(0);

    // (1) 末尾追加一条 tracked 插入，带样式
    p0.addInsertion("审阅者甲", "（补充说明）").italic();

    // (2) 把第 1 个 run 替换掉（del + ins 组合）
    Run r0 = p0.run(0);
    Run newRun = r0.replaceTracked("审阅者甲", "修订后的开头");

    // (3) 把第 3 个 run 标记为 tracked 删除
    p0.addDeletion("审阅者甲", p0.run(2));

    // (4) 改某 run 的样式并记录属性修订
    Run r4 = doc.paragraph(1).run(0);
    RunStyle before = r4.style();
    r4.bold().color("FF0000");
    r4.commitStyleAsTracked("审阅者乙", before);

    // (5) 表格里标记一个单元格为 tracked 插入
    doc.table(0).row(0).addCell().markInserted("审阅者乙");

    doc.save(Path.of("draft-tracked.docx"));
}
```

创作完成后，`doc.trackedChanges().list()` 能读到所有写出的修订，可走 [03 accept/reject](./03-accept-reject.md) 的全部流程。

---

## 7. 范围与边界

| 能力 | 支持 |
|---|---|
| 文本类创作（insertion/deletion/replacement） | ✅ |
| 带格式插入 | ✅（链式样式，零额外 API） |
| rPrChange 创作（`commitStyleAsTracked`） | ✅（六样式） |
| cellIns/cellDel 创作 | ✅ |
| move 创作（`moveRunsFrom`） | ✅ |
| **cellMerge 创作** | ❌（CT 类型缺失） |
| **pPrChange/sectPrChange/tblPrChange/trPrChange 创作** | ❌（CT 类型全缺） |
| **全局修订录制**（自动追踪所有既有写操作） | ❌（创作是显式 tracked 路线，不做隐式录制） |

> 「全局修订录制」是有意的范围排除 —— nondocx 的创作 API 是**显式**的：你想追踪哪处改动，就调对应的 `addInsertion`/`replaceTracked`/…。普通编辑 API（`run.text("x").bold()`）**不会**自动留痕。这避免了「同一份文档里有些改动被追踪、有些没被追踪」的混淆。

---

## 8. 与 accept/reject 的闭环

创作的修订与 Word 产生的修订**结构完全一致**，因此：

```java
// 创作
doc.paragraph(0).addInsertion("审阅者甲", "新增");
doc.save(file);

// reopen 后可以 accept/reject，与已有修订无差别
try (Document reopened = Docx.open(file)) {
    reopened.trackedChanges().acceptAll();
    reopened.save(file);
}
```

这就形成了 **「创作 → 落盘 → 查询 → accept/reject」** 的完整闭环 —— Agent 可以用 nondocx 把改动以修订形式交给人，人审阅后用 Word（或再用 nondocx）accept/reject。

---

## 9. 一句话总结

> 创作入口在内容类型（`Paragraph`/`Run`/`Cell`）而非门面；四类创作各对应一组方法；
> author 必传，date/w:id 自动分配；与开关正交；不支持的能力诚实排除（cellMerge/pPrChange 等走 raw）。
> 创作的修订与 Word 产生的无差别，闭环到 accept/reject。

---

## TC 教程完

四篇读完，你已掌握 nondocx 修订特性的**全部公开能力**：

1. [01 概念](./01-concepts.md) —— OOXML 修订模型
2. [02 读与查询](./02-read-and-query.md) —— 只读消费
3. [03 accept/reject](./03-accept-reject.md) —— 破坏性处理
4. [04 创作](./04-authoring.md) —— 主动写出修订

### 下一步去哪

- 接 LLM Agent 用工具调用做修订 → [07 · nondocx-toolkit](../07-toolkit.md)
- 看可运行的全流程示例 → [`TrackedAuthoringAdvancedExample.java`](../../nondocx-examples/src/main/java/io/github/nondirectional/docx/examples/TrackedAuthoringAdvancedExample.java) / [`TrackedCellChangesExample.java`](../../nondocx-examples/src/main/java/io/github/nondirectional/docx/examples/TrackedCellChangesExample.java)
- 查具体方法签名 → [03 · API 速查](../03-api-reference.md#trackedchanges--trackedchange修订)
- 遇到异常 → [08 · 异常与 raw 领地](../08-exceptions-and-raw.md)
