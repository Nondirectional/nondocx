# 02 · 读与查询

[01 概念](./01-concepts.md) 讲了修订在 OOXML 里长什么样。这一篇讲 **nondocx 怎么把它读出来** —— 只读消费侧。

> 入口只有一个：`doc.trackedChanges()` → `TrackedChanges` 门面。

---

## 1. 入口：`Document.trackedChanges()`

```java
TrackedChanges tc = doc.trackedChanges();
```

**两个设计决定**：

- **总是返回非 null**（不像 `doc.toc()` 无目录时返回 null）。理由：修订的「开关状态」与「列表」总是有意义的，即便关闭、即便列表为空。
- 门面持有单个 `final XWPFDocument` 委托。`list()` 与 `get(id)` 每次调用**当场重算**，不缓存 —— 守住 nondocx 的「无字段快照」契约（与 [02 架构](../02-architecture.md#2-活对象契约holding-wrapper) 一致）。

---

## 2. 开关：`enabled()` / `enable()` / `disable()`

| 方法 | 说明 |
|---|---|
| `tc.enabled()` 👁 | 读 `settings.xml` 的 `<w:trackChanges/>`：存在即 true |
| `tc.enable()` ✏️ | 加 `<w:trackChanges/>`（幂等，已开则 no-op） |
| `tc.disable()` ✏️ | 移除 `<w:trackChanges/>`（幂等） |

```java
TrackedChanges tc = doc.trackedChanges();
if (!tc.enabled()) {
    tc.enable();      // 让接力编辑（人在 Word 里继续改）被追踪
}
```

### 开关与 authoring 正交（重要）

`<w:trackChanges/>` **只控制后续在 Word 里手动改动是否被追踪**。它**不影响**：

- 文档里**已有**修订标记（`<w:ins>` 等）的可见性 / 可接受性
- nondocx 的创作 API（`Paragraph.addInsertion` 等）—— 这些直接产出修订标记，**不依赖**开关

开关的典型价值是 **「Agent → 人」接力**：Agent 用创作 API 产出带修订的文档交还给人，人继续在 Word 里手动改动；如果 `enabled()`，人的改动也被追踪，便于后续审阅。

---

## 3. 枚举：`list()`

```java
List<TrackedChange> changes = tc.list();   // 按文档顺序
for (TrackedChange c : changes) {
    System.out.println(c.type() + " by " + c.author());
}
```

**语义**：

- **按文档出现顺序**，不按作者/类型/id 重排
- **活视图**：每次访问（`size()`、`get(i)`）从委托重读，文档改动实时反映
- **可能为空**（文档无修订）

### 遍历顺序示例

假设 `word/document.xml` 是：

```
段落 0: 原文本 + <w:ins> + <w:del>
段落 1: <w:ins>
表格[0] → row[0] → cell[0] → 段落: <w:ins>
```

`list()` 按深度优先、文档顺序产出 **4 条**：段0 ins → 段0 del → 段1 ins → cell 内 ins。这个顺序与你肉眼读 XML 的顺序一致。

---

## 4. 单条：`get(String id)`

```java
TrackedChange first = tc.list().get(0);
TrackedChange byId = tc.get(first.id());   // 用 id 重新取
assert byId.equals(first);
```

**语义**：

- **命中式访问**：精确按 `TrackedChange.id()` 定位
- **命中即返回；未命中抛 `NoSuchElementException`**（不返回 null）—— 「按稳定标识精确定位」被视为「该有就有、没有就是错」
- 内部重新扫描一次（活对象语义，无缓存）

### id 的进程内稳定性（重要边界）

`TrackedChange.id()` 是 nondocx 对外的**进程内稳定**标识：

- ✅ **同一次 `list()` 调用内**稳定，可在该调用内被 `get(id)` 反查
- ✅ **同一会话内**，`list()` 两次调用的 id 也一致（同一委托、同一文档顺序、同一 id 生成规则）
- ❌ **不承诺 `save()` 后重新 `Docx.open()` 仍稳定** —— 修订没有跨会话的天然稳定句柄
- ❌ **accept/reject 操作后**，文档树被重写，旧 id 可能失效；想再操作必须重新 `list()` 取新 id

> id 的字符串格式**不是公共契约**（不透明引用标识）。理解与调试应看 `type()`/`family()`/`location()`/`details()`，别解析 id 字符串。

---

## 5. 解读一条 `TrackedChange`

`list()`/`get()` 返回的 `TrackedChange` 携带七个公开字段：

| 字段 | 类型 | 含义 |
|---|---|---|
| `id()` | `String` | nondocx 稳定 id（进程内稳定，见上） |
| `author()` | `String` | 修订作者（派生自 `w:author`，无则空串） |
| `date()` | `Calendar` | 修订时间（派生自 `w:date`，无则 null） |
| `type()` | `TrackedChangeType` | 细粒度 kind：`INS`/`DEL`/`MOVE_FROM`/`MOVE_TO`/`RPR_CHANGE`/`CELL_INS`/… |
| `family()` | `TrackedChangeFamily` | 粗粒度分组（派生自 type）：`TEXT`/`MOVE`/`PROPERTY`/`CELL` |
| `location()` | `TrackedChangeLocation` | 结构化位置（segment path） |
| `details()` | `ChangeDetails` | 具体 payload（按 family 分子类型） |

### `type` vs `family`

`type` 是细粒度（与 OOXML 元素一一对应），`family` 是 `type` 的超集分组。映射（详见 [01](./01-concepts.md#8-一张速查表四类对比)）：

```
TEXT     ← INS, DEL
MOVE     ← MOVE_FROM, MOVE_TO
PROPERTY ← RPR_CHANGE, PPR_CHANGE, SECT_PR_CHANGE   （后两个 CT 类型缺失，不出现）
CELL     ← CELL_INS, CELL_DEL, CELL_MERGE
```

`family` 的价值是**accept/reject 的 gate**：通用方法 `accept(id)` 只收 TEXT/MOVE；属性类要走 `acceptProperty(id)`、cell 类要走 `acceptCell(id)`。详见 [03 accept/reject](./03-accept-reject.md)。

### `location()`：segment path

位置是一条**从 body 逐层下钻的 path**，由若干 segment 组成。每个 segment 有 `kind`（`BODY`/`PARAGRAPH`/`TABLE`/`ROW`/`CELL`/`RUN`）+ `index`。

```
正文第 2 段、第 1 个 run 上的插入：  body[0] > paragraph[1] > run[0]
第 1 个表格、第 2 行、第 3 个 cell 内的删除：body[0] > table[0] > row[1] > cell[2]
```

读取示例（取自 [`TrackedChangesExample`](../../nondocx-examples/src/main/java/com/non/docx/examples/TrackedChangesExample.java#L143)）：

```java
private static String pathString(TrackedChangeLocation location) {
    List<TrackedChangeSegment> segs = location.segments();
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < segs.size(); i++) {
        if (i > 0) sb.append(" > ");
        TrackedChangeSegment s = segs.get(i);
        sb.append(s.kind().name().toLowerCase()).append('[').append(s.index()).append(']');
    }
    return sb.toString();
}
```

**两个注意**：

- cell 类修订挂在 `<w:tcPr>`，比单元格内段落**高一层**，path 停在 `[BODY, TABLE, ROW, CELL]`（不含 `PARAGRAPH`）。这是区分「单元格本身被插入」与「单元格内的文本被插入」的依据。
- `TrackedChangeLocation.toString()` 渲染可读 path，但**字符串格式不是稳定契约**；程序判断应读 `segments()` 的结构化二元组。

### `details()`：按 family 分子类型

`details()` 返回 `ChangeDetails` 接口，按 family 用 `instanceof` 判断子类型再读字段：

| family | details 子类型 | 字段 |
|---|---|---|
| TEXT / MOVE | `TextChangeDetails` | `text()` —— 修订的文本内容 |
| PROPERTY | `PropertyChangeDetails` | `kind()`（`RUN_PROPERTIES`/`PARAGRAPH_PROPERTIES`）+ `newSummary()` + `oldSummary()`（新旧 rPr 直接子本地名摘要） |
| CELL | `CellChangeDetails` | `kind()`（`CELL_INSERTION`/`CELL_DELETION`/`UNCONFIRMED_MERGE`） |

```java
ChangeDetails d = change.details();
if (d instanceof TextChangeDetails td) {
    System.out.println("文本: " + td.text());
} else if (d instanceof PropertyChangeDetails pd) {
    System.out.println("属性: " + pd.kind() + " 新=" + pd.newSummary() + " 旧=" + pd.oldSummary());
} else if (d instanceof CellChangeDetails cd) {
    System.out.println("单元格: " + cd.kind());
}
```

---

## 6. 一个完整的只读示例

简化自 [`TrackedChangesExample.java`](../../nondocx-examples/src/main/java/com/non/docx/examples/TrackedChangesExample.java)（移除造样例部分）：

```java
try (Document doc = Docx.open(Path.of("reviewed.docx"))) {
    TrackedChanges tc = doc.trackedChanges();

    // (a) 开关状态
    System.out.println("修订模式开启? " + tc.enabled());

    // (b) 枚举
    List<TrackedChange> list = tc.list();
    System.out.println("共 " + list.size() + " 条修订（按文档顺序）:");
    for (int i = 0; i < list.size(); i++) {
        TrackedChange c = list.get(i);
        StringBuilder line = new StringBuilder();
        line.append("  #").append(i + 1).append(" type=").append(c.type());
        line.append(", family=").append(c.family());
        line.append(", author=\"").append(c.author()).append("\"");
        if (c.details() instanceof TextChangeDetails td) {
            line.append(", text=\"").append(td.text()).append("\"");
        }
        line.append(", location=").append(c.location());
        System.out.println(line);
    }

    // (c) 按稳定 id 取单条
    if (!list.isEmpty()) {
        TrackedChange byId = tc.get(list.get(0).id());
        System.out.println("按 id 取回首条: " + byId.type());
    }
}
```

---

## 7. 边界与陷阱

| 情形 | 行为 |
|---|---|
| 文档无修订 | `list()` 返回空列表，不抛异常 |
| `get(id)` 未命中 | 抛 `NoSuchElementException`（不返回 null） |
| `get(id)` 传 null/空白 | 抛 `IllegalArgumentException` |
| save→reopen 后用旧 id 查 | 可能 `NoSuchElementException`（id 不跨会话稳定）；应改用 `list()` 重取 |
| accept/reject 后用旧 id 查 | 同上，文档树已重写；必须 `list()` 重取 |
| `Document.equals` | **不比较修订**；要比较修订需 `tc.list().equals(...)` 单独断言 |

> 修订不参与 `Document.equals`（与 TOC 类似），是 [04 往返保真](../04-round-trip-and-equality.md#5-已知的不对称诚实边界) 列出的「读得到但比较不到」的有意边界。

---

## 下一步

读完修订，去看怎么**应用或撤销** → [03 · accept / reject](./03-accept-reject.md)。
