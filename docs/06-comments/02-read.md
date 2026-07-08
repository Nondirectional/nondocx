# 02 · 读与查询

[01 概念](./01-concepts.md) 讲了批注在 OOXML 里长什么样。这一篇讲 **nondocx 怎么把它读出来** —— 只读消费侧。

> 入口只有一个：`doc.comments()` → `Comments` 门面。

---

## 1. 入口：`Document.comments()`

```java
Comments cs = doc.comments();
```

**两个设计决定**：

- **总是返回非 null**（即便文档无批注）。理由：`list()` 即便为空也是有意义的状态，调用方不必 null-guard。
- 门面持有单个 `final XWPFDocument` 委托。`list()` 与 `get(id)` 每次调用**当场重算**，不缓存 —— 守住 nondocx 的「无字段快照」契约（与 [02 架构](../02-architecture.md) 一致）。

---

## 2. 枚举：`list()`

```java
List<Comment> comments = cs.list();   // 按文档顺序
for (Comment c : comments) {
    System.out.println("[" + c.id() + "] " + c.author() + ": " + c.text());
}
```

**语义**：

- **按文档出现顺序**（`document.xml` 内 `commentRangeStart` 的出现顺序），**不**按 `comments.xml` 部件顺序、不按 `w:id` 数值重排
- **活视图**：每次访问（`size()`、`get(i)`）从委托重读，文档改动实时反映
- **可能为空**（文档无批注）

### 为什么不直接用 POI 的 `getComments()`

POI 的 `XWPFComments.getComments()` 返回 **`comments.xml` 部件顺序（创建顺序）**，不等于正文顺序。探针验证：三批注按 id 2,0,1 创建、body 按 0,1,2 锚定时，`getComments()` 返回 `[2,0,1]`。

nondocx 的 `list()` 契约是「先被评论的正文 → 先返回的批注」，所以 `internal/poi/CommentNodes.collect` 自己用 `XmlCursor` 扫 `document.xml` 的 `commentRangeStart` 重排：

1. 从 `getComments()` 建 `Map<id, XWPFComment>`
2. 用 `XmlCursor` 深度优先遍历 `CTBody` 整棵子树，按 `commentRangeStart` 出现顺序从 Map 取批注
3. 孤儿批注（comments.xml 有、document.xml 无锚点）按部件顺序追加末尾，不丢弃

### 遍历必须 push/pop（关键坑）

`commentRangeStart` 是叶子元素，可能在任意层级（段落内、表格单元格内段落里）。深度优先遍历整棵 body 子树时，`toFirstChild()` 进入子层后 cursor 停在子树深处，外层 `toNextSibling()` 会从错误位置继续 —— **漏掉同层后续兄弟**。

正确写法是递归下钻前 `cur.push()`、返回后 `cur.pop()` 恢复（spec [poi-bridge.md N18](../../.trellis/spec/backend/poi-bridge.md)）：

```java
do {
  if ("commentRangeStart".equals(localNameOf(cur))) { ... 产出 ... }
  cur.push();                      // 下钻前保存
  collectRangeStartIds(cur, ...);  // 递归
  cur.pop();                       // 恢复,让 toNextSibling 在正确兄弟层
} while (cur.toNextSibling());
```

---

## 3. 单条查询：`get(id)`

```java
Comment c = cs.get("0");   // 按 OOXML w:id 精确命中
```

**语义**：

- **命中式访问**：精确按 `Comment.id()` 定位。命中即返回；未命中抛 `NoSuchElementException`（**不**返回 null）
- 内部重新扫描一次批注列表（每次调用独立重算，活对象语义）
- `id` 为 null 抛 `IllegalArgumentException`

```java
cs.get("999");   // 抛 NoSuchElementException：找不到 id 为 999 的批注
cs.get(null);    // 抛 IllegalArgumentException
```

---

## 4. Comment 的字段

```java
Comment c = cs.list().get(0);
c.id();          // "0"  —— OOXML w:id，字符串（跨会话稳定）
c.author();      // "审阅者甲"
c.text();        // "这段需要补充背景"（多段拼接）
c.date();        // Calendar（可能为 null）
c.initials();    // String（可能为空串）
c.parentId();    // Optional<String>（根批注为 empty）
c.paraId();      // String（可能为 null，旧文档/创作未注入）
```

**五个基础字段**（id/author/text/date/initials）直接来自 `comments.xml` 的 `<w:comment>` 属性与正文。其中：

- **`id()`** 透传 OOXML 的 `w:id`，**跨会话稳定**（与 tracked-changes 的进程内稳定 id 不同，批注的 w:id 本就是文档级标识）
- **`text()`** 多段批注会拼接（POI 的 `getText()` 不稳时回退自拼）
- **`date()`** 返回 `Calendar`，可能为 `null`（旧文档或特殊工具产出的批注可能缺失）
- **`initials()`** 返回 `String`，可能为空串（不派生，见 [03 §2](./03-authoring.md)）

**两个线程字段**（paraId/parentId）来自 `commentsExtended.xml` 的两步 join（[01 §3](./01-concepts.md)）：

- **`parentId()`** 返回 `Optional<String>`：`isPresent()` 即「本批注是回复」，empty 即「根批注」
- **`paraId()`** 返回可空 `String`：线程关系的 key，旧文档或创作路径未注入时可能为 `null`

### 线程字段的两步 join

读「回复批注 B 的 parentId」需要两步 join（spec [poi-bridge.md N23](../../.trellis/spec/backend/poi-bridge.md)）：

1. comments.xml：批注内首段的 `w14:paraId` → 批注 `w:id`
2. commentsExtended：B 的 paraId → `paraIdParent` → 父 paraId → 父 comment id

**防御式**：无 commentsExtended / paraId 缺失 / join 失败时，parentId 为 empty（根批注语义），不抛 —— 畸形/旧文档不破坏读侧。

---

## 5. 不参与 Document.equals

批注列表**不纳入** `Document.equals` / `Document.hashCode` 的内容相等性（[04 往返保真](../04-round-trip-and-equality.md)）。理由与 tracked-changes 一致：批注是「评论文本」而非「文档内容」，两份文档内容相同但批注不同是常见场景。

```java
// 两份文档内容相同、批注不同 —— equals 仍为 true
assertThat(docA).isEqualTo(docB);
// 比较批注要单独做
assertThat(docA.comments().list()).isEqualTo(docB.comments().list());
```

`Comment` 自身的 `equals`/`hashCode` 比较**内容字段**（id/author/text/date/initials），**不**含 paraId/parentId（保 read 子任务的 round-trip 五字段相等性契约）。

---

## 6. Comment 是 holding-wrapper

`Comment` 持有单个 `final XWPFComment` 委托，标准 holding-wrapper：

```java
// 构造时注入委托 + 解析好的线程字段
Comment c = new Comment(xwpfComment, paraId, parentId);
```

- 读写穿透到委托（与 `Paragraph`/`Run` 同型）
- `paraId`/`parentId` 是构造时一次解析的值（非缓存读 —— 每次 `list()` 重算时重新构造 Comment）
- `raw()` 返回 `XWPFComment`（POI 逃生舱，想直接操作 OOXML 时用）

> **批注没有专属单一委托类型**：`Comments.raw()` 返回的是 `XWPFDocument`（整份文档），因为批注操作（创作/回复）需要文档级访问。这与 `TrackedChanges.raw()` 返回 `XWPFDocument` 同型。

---

## 下一步

读会了，去看怎么**创作**批注 → [03 · 创作与回复](./03-authoring.md)。
