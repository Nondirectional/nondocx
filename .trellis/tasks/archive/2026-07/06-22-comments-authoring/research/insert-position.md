# Research — comments 创作侧插入位置探针验证

> 本文件记录 `06-22-comments-authoring` planning 阶段对 POI 5.2.5 批注锚点插入行为的探针验证。design.md §4 / implement.md 引用本文结论。
>
> 探针日期：2026-07-07。POI 版本：5.2.5。

## 1. 探针目的

回答 prd.md 的 **Q1**：整段批注的 `commentRangeStart/End` 插在段的什么位置？POI 的 `addNewCommentRangeStart()` 是否自动按 schema 顺序定位？

这是 authoring 脏活复杂度的核心风险点——tracked-changes 的 `addInsertion` 是「新建容器包新 run」，顺序天然正确；comments 是「往**已有内容的**段落里插锚点」，锚点必须落在已有 run 的外侧，复杂度可能高一档。

## 2. 探针方法

构造一个**已有内容**的段落（2 个 run），然后分别测三种方式插入批注锚点，dump `CTP` 子元素顺序：

- **方案A** `p.addNewCommentRangeStart()` —— 末尾追加套路
- **方案B** `p.insertNewCommentRangeStart(0)` —— 按 XmlBeans 数组索引插入
- **方案C** `addNew` 后用 `XmlCursor.moveXml` 把 start 移到段首

然后写文件 → unzip 看 `document.xml` 持久化结构 → `new XWPFDocument` 读回 → `getDocComments().getComments()` 验证可读。

## 3. 关键结果

### 3.1 方案A / B：POI 不按 schema 顺序，锚点落在段末 ❌

加入批注前：
```
[0] <r>              ← 已有文本A
[1] <r>              ← 已有文本B
```

`addNewCommentRangeStart()` / `insertNewCommentRangeStart(0)` 之后：
```
[0] <r>
[1] <r>
[2] <commentRangeStart id=0>   ← 都落在已有 run 之后！
[3] <commentRangeEnd id=0>
[4] <r><commentReference/></r>
```

**问题**：`commentRangeStart` 在所有 run **之后**、紧贴 `commentRangeEnd`，范围实际是**空的**（包住 0 个 run）。批注语义错误——不是「批注整段」，是「批注段末一个空区间」。

**根因**：`insertNewXxx(int)` 的索引是 XmlBeans 内部 **per-type 数组索引**，不是 `CTP` 的全局子位置索引。`insertNewCommentRangeStart(0)` 意思是「在 commentRangeStart 数组的第 0 位插一个」，但该数组此前为空，新元素仍被追加到 `CTP` 子序列末尾。POI / XmlBeans 不做 schema-order 排序。

### 3.2 方案C：XmlCursor move 可正确定位 ✅

`addNew` 创建后，用 `XmlCursor` 把 `commentRangeStart` 节点 move 到 `CTP` 第一个子之前：

```
[0] <commentRangeStart id=0>   ← 段首
[1] <r>                        ← 已有文本A（被范围包住）
[2] <r>                        ← 已有文本B（被范围包住）
[3] <commentRangeEnd id=0>
[4] <r><commentReference/></r>
```

范围正确包住整段。持久化（`document.xml`）与读回结构一致。

### 3.3 round-trip 与读侧

方案C 写文件 → 读回：

- 段落子元素顺序**保持**（start 在段首，end 在末尾 run 之前）。
- `getDocComments().getComments()` 正确读到批注（id/author/text 全对）。
- 即 authoring 写出的批注能被 read 子任务的 `Comments.list()` 读回（prd R3.1 闭环成立）。

## 4. 对实现的影响（design §4 / implement.md 引用）

**Q1 结论**：POI 的 `addNew`/`insertNew` 都不按 schema 顺序。authoring 必须用 **XmlCursor 手动定位**：

- `commentRangeStart` —— move 到 `CTP` 第一个子之前（包住整段）。
- `commentRangeEnd` + 引用 run —— `addNew` 追加到段末即可（它们本就属于段尾语义）。

这是 authoring 区别于 tracked-changes `addInsertion` 的核心脏活，全部下沉到 `internal/poi/CommentNodes.addWholeParagraphComment(...)`，公共 API（`Paragraph.addComment`）不见 XmlCursor。

### 具体算法

```
1. nextCommentId(doc) —— 扫已有批注 id 取 max+1（仿 TrackedChangeNodes.nextRevisionId）
2. doc.createComments().createComment(id) —— 建 comments.xml 条目，设 author/date/initials，加正文段落
3. CTP 上 addNewCommentRangeStart / addNewCommentRangeEnd，设 id
4. XmlCursor: rangeStart move 到 CTP 第一个子之前
5. CTP 上 addNewR，r 内 addNewCommentReference(id) —— 引用 run 在段末
```

## 5. 探针代码

一次性脚本（不入库）。关键片段：

```java
XWPFDocument doc = new XWPFDocument();
XWPFParagraph p = doc.createParagraph();
p.createRun().setText("已有文本A");
p.createRun().setText("已有文本B");   // 段落已有内容

XWPFComments xcom = doc.createComments();
XWPFComment c = xcom.createComment(BigInteger.valueOf(0));
c.setAuthor("non"); c.setDate(Calendar.getInstance()); c.setInitials("n");
c.createParagraph().createRun().setText("批注正文");

CTP ctp = p.getCTP();
CTMarkupRange start = ctp.addNewCommentRangeStart();
start.setId(BigInteger.valueOf(0));
CTMarkupRange end = ctp.addNewCommentRangeEnd();
end.setId(BigInteger.valueOf(0));
CTR refRun = ctp.addNewR();
refRun.addNewCommentReference().setId(BigInteger.valueOf(0));

// XmlCursor: 把 start 移到段首
XmlCursor pCur = ctp.newCursor();
XmlCursor startCur = start.newCursor();
try {
  pCur.toFirstChild();       // 第一个子（已有文本A 的 r）
  startCur.moveXml(pCur);    // start 移到 pCur 之前
} finally { pCur.dispose(); startCur.dispose(); }
```
