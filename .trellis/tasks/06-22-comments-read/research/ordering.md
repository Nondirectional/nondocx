# Research — comments 顺序探针验证

> 本文件记录 `06-22-comments-read` planning 阶段对 POI 5.2.5 comments 行为的探针验证。design.md §2.1、§5.2、§4.4 引用本文结论。
>
> 探针日期：2026-06-22。POI 版本：5.2.5（经 `mvn dependency:build-classpath` + `javap`）。

## 1. 探针目的

回答 design.md 的两个 Open Question：

- **Q1**：`Comment` 能否走 holding-wrapper（持 `XWPFComment` 委托）？还是只能做纯值对象？
- **Q2**：`Comments.list()` 的「文档顺序」如何定义？`getComments()` 返回的顺序是否等于正文顺序？

## 2. 探针方法

用 `XWPFDocument` 内存构造一个 fixture：

- 3 条批注，按 `w:id` **2, 0, 1** 的顺序创建（故意非升序）
- 正文里 3 个段落，按 `w:id` **0, 1, 2** 的顺序各引用一条批注（`commentRangeStart` 顺序升序）
- 写文件 → 重新 `new XWPFDocument(inputStream)` 读回
- 调 `getDocComments().getComments()` 看返回顺序
- unzip 后看 `word/comments.xml` 里 `<w:comment>` 的持久化顺序

另构造一个**无批注**的文档，看 `getDocComments()` 返回什么。

## 3. 关键结果

### 3.1 Q1 结论：holding-wrapper 可行 ✅

`javap` 确认 POI 5.2.5 的 `XWPFComment` 是完整高级类型：

- 实现 `IBody`（提供 `getParagraphs()`）
- `getCtComment()` → `CTComment`（可拿底层 CT）
- `getId()` / `getAuthor()` / `getInitials()` / `getDate()` / `getText()` 全部可用
- `CTComment extends CTTrackChange`（与 tracked changes 同构，author/date/id 类型化）

→ `Comment` 持 `XWPFComment` 委托，`raw()` 返回它，holding-wrapper 形态成立。

### 3.2 Q2 结论：getComments() 返回 comments.xml 部件顺序，≠ 正文顺序 ❌

```
getComments() 返回顺序: [id=2, id=0, id=1]   ← comments.xml 部件顺序（创建顺序）
正文 rangeStart 顺序:    [id=0, id=1, id=2]   ← document.xml commentRangeStart 出现顺序
```

unzip 看 `word/comments.xml`：

```xml
<w:comment w:id="2">...</w:comment>   ← 先创建的
<w:comment w:id="0">...</w:comment>
<w:comment w:id="1">...</w:comment>   ← 持久化顺序 = 创建顺序
```

**POI 的 `getComments()` 不帮你按 body 重排。** 若 nondocx 直接委托，用户拿到的顺序会让批注的正文位置与列表位置错乱。

→ design §5 决策：`list()` 按 body 顺序（扫 `document.xml` 的 `commentRangeStart`），由 `CommentNodes.collect` 自己实现重排。

### 3.3 边界行为

| 场景 | 探针结果 | nondocx 处理 |
|---|---|---|
| `getDocComments()` 无批注文档 | 返回 `null` | `CommentNodes.collect` null-guard 返回空列表 |
| `getCommentByID("99")` miss | 返回 `null`（不抛） | `Comments.get(id)` 包装成 `NoSuchElementException` |
| `XWPFComment.getDate()` 未设 | 返回 `null` | `Comment.date()` 返回可空 `Calendar` |
| `XWPFComment.getText()` 多段批注 | 正确拼接段落文本 | `Comment.text()` 委托 |

## 4. 探针代码

留在 `scripts/` 外的一次性脚本（已删，不进仓库）。关键片段供复现：

```java
XWPFDocument poi = new XWPFDocument();
XWPFComments xcomments = poi.createComments();
// 故意按 id 2,0,1 创建
XWPFComment c2 = xcomments.createComment(BigInteger.valueOf(2));
c2.setAuthor("non2"); c2.createParagraph().createRun().setText("comment id=2");
XWPFComment c0 = xcomments.createComment(BigInteger.valueOf(0));
c0.setAuthor("non0"); c0.createParagraph().createRun().setText("comment id=0");
XWPFComment c1 = xcomments.createComment(BigInteger.valueOf(1));
c1.setAuthor("non1"); c1.createParagraph().createRun().setText("comment id=1");

// 正文按 id 0,1,2 顺序引用
CTBody body = poi.getDocument().getBody();
for (long id : new long[]{0, 1, 2}) {
  CTP p = body.addNewP();
  CTMarkupRange start = p.addNewCommentRangeStart();
  start.setId(BigInteger.valueOf(id));
  CTR r = p.addNewR();
  r.addNewT().setStringValue("body text for comment " + id);
  r.addNewCommentReference().setId(BigInteger.valueOf(id));
  CTMarkupRange end = p.addNewCommentRangeEnd();
  end.setId(BigInteger.valueOf(id));
}

// 写文件 → 读回
Path file = Files.createTempFile("probe", ".docx");
try (OutputStream out = Files.newOutputStream(file)) { poi.write(out); }
XWPFDocument reread = new XWPFDocument(Files.newInputStream(file));
XWPFComments rcom = reread.getDocComments();
for (XWPFComment c : rcom.getComments()) {
  System.out.println("id=" + c.getId() + " author=" + c.getAuthor());
}
// 输出: id=2, id=0, id=1  ← 创建顺序，非 body 顺序
```

## 5. 对实现的影响

`CommentNodes.collect(document)` 必须自己实现 body 顺序（design §5.4）：

1. 从 `getDocComments().getComments()` 建 `Map<id, XWPFComment>`
2. 用 `XmlCursor` 遍历 `CTBody`，按 `commentRangeStart` 出现顺序从 Map 取批注
3. 孤儿批注（Map 剩余）降级排末尾

不能直接返回 `getComments()` 的列表——那会违反 design §5.1 的顺序契约。
