# 03 · accept / reject

[02](./02-read-and-query.md) 讲了怎么读修订。这一篇讲怎么**应用（accept）或撤销（reject）**它们 —— 破坏性写。

> accept = 让改动生效（插入成为正文、删除彻底消失、属性变更成为新值、单元格存亡生效）。
> reject = 让改动作废（插入被丢弃、删除原文恢复、属性回到旧值、单元格回到存亡前）。

---

## 1. 三粒度 × 四类的方法矩阵

nondocx 的 accept/reject 方法按**粒度**和**family**分成三组。这是这一篇的核心导航表：

| 粒度 \ family | TEXT + MOVE（通用方法） | PROPERTY（专用方法） | CELL（专用方法） |
|---|---|---|---|
| **全部** | `acceptAll()` / `rejectAll()` | ❌ 无（按 id 单条） | ❌ 无（按 id 单条） |
| **按作者** | `acceptByAuthor(author)` / `rejectByAuthor(author)` | ❌ 无 | ❌ 无 |
| **按 id** | `accept(id)` / `reject(id)` | `acceptProperty(id)` / `rejectProperty(id)` | `acceptCell(id)` / `rejectCell(id)` |

### 为什么要三组方法（family gate）

因为四类修订的**底层 CT 类型不同**、**写语义不同**：

- **TEXT/MOVE** 同型（都是 `CTRunTrackChange`），accept/reject mechanics 一样（move 只是多了配对联动） → 共用通用方法
- **PROPERTY** 的 CT 类型（`CTRPrChange` 等）不同，accept/reject 是**整树替换** → 专用方法
- **CELL** 的 accept/reject 作用于**整个 `<w:tc>` 祖父**（不是标记本身），写语义本质不同 → 专用方法

> 用错方法会得到明确的 `UnsupportedFeatureException`，不会静默失败。比如对一条属性类修订调 `accept(id)`，会抛「accept/reject 仅支持文本/移动类；请使用 acceptProperty/... 或 raw()」。

### 为什么「全部/按作者」粒度不含 PROPERTY/CELL

`acceptAll`/`rejectAll`/`acceptByAuthor`/`rejectByAuthor` 的 family gate 是 **`TEXT || MOVE`**，**不放宽到 CELL** —— 这是有意的范围控制：

> 单元格结构修订批量 accept/reject 风险高（一次批量删/插多个单元格，结构易错乱）。
> cell 类强制按 id 单条操作（`acceptCell(id)`），把破坏范围限定在调用方明确指定的那条。

---

## 2. TEXT 类的 accept / reject 语义

四个组合，每个对应一种 OOXML 树操作：

| | accept（生效） | reject（作废） |
|---|---|---|
| **`<w:ins>`** | 拆 `<w:ins>` 包装，内部 `<w:r>/<w:t>` 提升为正文 | 整个 `<w:ins>` 子树删除（插入被丢弃） |
| **`<w:del>`** | 整个 `<w:del>` 子树删除（删除永久生效） | 拆包装，且内部 `<w:delText>` **转回**普通 `<w:t>`，原文回到正文 |

### del 的 reject 要 delText→t 类型转换（关键）

`delText` 与 `t` 是**不同 OOXML 元素**（本地名 `delText` vs `t`）。reject `del` 必须先把删除文本从 `<w:delText>` 读出 → 移除 `delText` → 新建 `<w:t>` 重写 → 再搬 run、删包装。单纯 move 改名做不到。这个细节收在 `internal/poi/TrackedChangeNodes.rejectText`，用户无感，但解释了为什么 reject 不是「单纯的反向 move」。

---

## 3. MOVE 类的 accept / reject：配对联动

move 是**成对**的（`moveFrom` + `moveTo`）。accept/reject 必须**两端同时操作**，不能只处理一端。

| | accept | reject |
|---|---|---|
| **`moveFrom`**（同 del 语义） | 删除生效（源文本移除） | 删除撤销（源文本恢复） |
| **`moveTo`**（同 ins 语义） | 插入生效（目标文本保留） | 插入撤销（目标文本移除） |

### 配对启发式（无显式指针）

OOXML 的 `moveFrom`/`moveTo` **没有**互相指向的字段。nondocx 在 `findMoveCounterpart` 里用 **author + text** 启发式查配对端（[01](./01-concepts.md#4-move-类移动成对wmovefrom--wmoveto) 已详述）：

- 同一作者、相同文本的另一端
- date 不作硬约束
- 配对端缺失 → **抛 `NoSuchElementException`**（不静默降级；文档可能损坏）

### 操作顺序

accept/reject move 时，nondocx 先处理 from 端（移除/恢复源文本），再处理 to 端（保留/移除目标文本）。**两端算作 1 条**（`acceptAll` 计数时配对算 1）。

---

## 4. PROPERTY 类：专用方法 + 整树替换

对 rPrChange，用 `acceptProperty(id)` / `rejectProperty(id)`。

| | accept | reject |
|---|---|---|
| **rPrChange** | 保留外层新 rPr（当前属性），移除 `<w:rPrChange>` 标记 | 用旧值树（`<w:rPr>` 内的 `CTRPrOriginal`）**整树替换**外层 rPr，再移除标记 |

### reject 的整树替换

reject 属性类要恢复「改前的样子」：清空外层 rPr 现有直接子（除 rPrChange）→ 把旧 rPr 的直接子搬入外层 → 删 rPrChange。旧 rPr 是 `CTRPrOriginal` 类型（与 `CTRPr` 不同），走 XmlCursor 通用搬运。

### 为什么要专用方法（方案 C）

属性类的底层节点类型（`CTRPrChange`）与文本类（`CTRunTrackChange`）不同。`TrackedChange.raw()` 对属性类**抛 `UnsupportedFeatureException`**（[02 架构 §raw](../02-architecture.md#3-raw-逃生舱--唯一的-poi-出口) 的「不支持 ≠ 静默失败」原则）。调用方被明确引导到 `acceptProperty`/`rejectProperty`。

---

## 5. CELL 类：专用方法 + 作用于整个 `<w:tc>` 祖父

对 `cellIns`/`cellDel`，用 `acceptCell(id)` / `rejectCell(id)`。

**关键概念**：cell 类标记的是「**单元格本身**的存亡」，accept/reject 操作**整个 `<w:tc>` 祖父节点**，不是标记本身。

| | accept | reject |
|---|---|---|
| **`cellIns`**（单元格被插入） | 保留整个 `<w:tc>`，仅删标记 | **移除整个 `<w:tc>`** |
| **`cellDel`**（单元格被删除） | **移除整个 `<w:tc>`** | 保留 `<w:tc>`，仅删标记 |
| **`cellMerge`** | ❌ 抛 `UnsupportedFeatureException` | ❌ 抛 `UnsupportedFeatureException` |

### 为什么 cellMerge 不支持

`CTCellMergeTrackChange` 既无 Java 类（编译期不可达）也无 `.xsb` schema 资源（运行期不可反序列化）—— POI 精简 jar 的 dangling reference（[01 §6](./01-concepts.md#6-cell-类单元格结构存亡wcellins--wcelldel--wcellmerge)）。合并/拆分还涉及相邻单元格 vMerge 恢复，结构风险高。门面对 cellMerge 命中 `acceptCell`/`rejectCell` 都明确抛异常，不静默降级。

### 防御：祖父校验

`acceptCell`/`rejectCell` 从 `cellIns`/`cellDel` 节点开 cursor，`toParent()`×2 到祖父 `<w:tc>`，再按语义操作。防御：祖父本地名不是 `tc` 时抛 `DocxOperationException`，**不静默删错层级**。

---

## 6. 重要的使用须知：accept 后 POI 缓存失效

> 这是 accept/reject 最容易踩的坑。

accept/reject 会**重写文档树**（拆包装、删子树、整树替换、移除整个 tc）。此后：

- POI 的内存 `XWPFParagraph`/`XWPFRun` 包装器可能 `XmlValueDisconnected`
- **此前 `list()`/`get()` 返回的 `TrackedChange` 节点句柄可能失效**（曾触发 `XmlValueDisconnectedException`）

### nondocx 怎么应对

`acceptAll`/`rejectAll`/`acceptByAuthor`/`rejectByAuthor` 用 **「重算 → 应用第一条匹配 → 重算」循环**，而不是一次性收集后批量改：

```java
// 概念示意（TrackedChanges.applyRepeated）
while (true) {
    TrackedChange target = collectFirstMatch(...);   // 每次重算
    if (target == null) return count;
    applyOne(target, accept);                         // 只应用一条
    count++;
}
```

这保证每条都在当前文档状态下定位。

### 你应该怎么验证 accept 后的结构

```java
// ❌ 错误：accept 后直接信任旧的 wrapper
Paragraph p = doc.paragraph(0);
tc.acceptAll();
p.runs().size();   // 可能 XmlValueDisconnected

// ✅ 正确：accept 后 save→reopen 重新读
tc.acceptAll();
doc.save(file);
try (Document reopened = Docx.open(file)) {
    reopened.paragraph(0).runs().size();   // 可靠
    reopened.trackedChanges().list().size();  // 应为 0
}
```

---

## 7. 异常契约一览

| 调用情形 | 异常 |
|---|---|
| `accept(id)`/`reject(id)` 命中 TEXT/MOVE | 正常执行 |
| `accept(id)`/`reject(id)` 命中 PROPERTY/CELL | `UnsupportedFeatureException`（消息指引专用方法或 raw） |
| `acceptProperty(id)` 命中非 PROPERTY | `UnsupportedFeatureException` |
| `acceptCell(id)` 命中 cellMerge | `UnsupportedFeatureException`（指明走 raw） |
| `acceptCell(id)` 命中非 CELL | `UnsupportedFeatureException` |
| 任何 `accept*`/`reject*` 的 `id` 为 null/空白 | `IllegalArgumentException` |
| 任何 `accept*`/`reject*` 的 `id` 未命中 | `NoSuchElementException` |
| `acceptByAuthor`/`rejectByAuthor` 的 `author` 为 null/空白 | `IllegalArgumentException` |
| move 的配对端缺失 | `NoSuchElementException` |
| cell 类的祖父不是 `<w:tc>` | `DocxOperationException` |

所有异常都在 [08 异常](../08-exceptions-and-raw.md) 的 `DocxException` 家族内（或标准 JDK 异常）—— **POI 异常不会在公开表面裸露**。

---

## 8. 一个端到端示例

```java
Path file = Path.of("reviewed.docx");

try (Document doc = Docx.open(file)) {
    TrackedChanges tc = doc.trackedChanges();

    // (1) 接受「审阅者甲」的全部文本/移动类修订
    int applied = tc.acceptByAuthor("审阅者甲");
    System.out.println("接受了 " + applied + " 条文本/移动类");

    // (2) 单独处理属性类（通用 accept 不收 PROPERTY）
    for (TrackedChange c : tc.list()) {           // acceptByAuthor 后 list() 已重算
        if (c.family() == TrackedChangeFamily.PROPERTY) {
            tc.acceptProperty(c.id());
        }
    }

    // (3) 单独处理 cell 类（强制按 id，cellMerge 跳过）
    for (TrackedChange c : tc.list()) {
        if (c.family() == TrackedChangeFamily.CELL
            && c.type() != TrackedChangeType.CELL_MERGE) {
            tc.acceptCell(c.id());
        }
    }

    doc.save(file);   // 重要：accept 后验证结构需 save→reopen
}

// 验证
try (Document doc = Docx.open(file)) {
    int remaining = doc.trackedChanges().list().size();
    System.out.println("剩余修订（应只剩 cellMerge）: " + remaining);
}
```

> 注意每次 `tc.list()` 都是重算 —— 在 accept/reject 循环里 `list()` 会反映上一步的改动。

---

## 9. 一句话总结

> TEXT/MOVE 用通用 `accept`/`reject`（三粒度），PROPERTY 用 `acceptProperty`/`rejectProperty`，
> CELL 用 `acceptCell`/`rejectCell`（cellMerge 不支持）。family gate 用明确的 `UnsupportedFeatureException` 防错用。
> **accept 后 POI wrapper 失效，验证结构必须 save→reopen**。

---

## 下一步

读完、处理完，去看怎么**主动创作**修订 → [04 · 创作](./04-authoring.md)。
