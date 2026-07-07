# Design — comments 创作侧显式 API（单条范围批注）

> 配套 `prd.md`。本文记录单条**整段**范围批注创作的技术设计：API 落点、返回值语义、OOXML 锚点写入算法、与读侧的闭环。
>
> 探针依据见 `research/insert-position.md`（Q1 决定性验证）。

## 1. 设计目标

交付**显式、可预期、不污染现有写语义**的整段批注创作能力。需要定清的关键点（对照 tracked-changes-authoring design §1）：

1. 方法住在哪里
2. 方法返回什么
3. OOXML 锚点怎么写（**本子任务的核心脏活**——见 §4）
4. 写出的批注如何被 read 子任务统一读回

**与 tracked-changes-authoring 的本质差异**：tracked 的 `addInsertion` 是「新建 `<w:ins>` 容器包新 run」，新节点天然在段末、顺序正确；comments 的 `addComment` 是「往**已有内容的**段落里插锚点」，锚点必须落在已有 run 的**外侧**（start 在前、end+reference 在后）。POI 不自动排序（探针 §3.1 证实），故必须 XmlCursor 手动定位——这是脏活集中点。

## 2. 三层映射

### 2.1 OOXML 层

一条整段批注在 OOXML 里是「正文 + 锚点」分离的两 part 结构（教学要点，read 子任务 `CommentNodes` javadoc 已述）：

```xml
正文 word/document.xml:
  <w:p>
    <w:commentRangeStart w:id="0"/>          ← 必须在段首（包住整段）
    <w:r><w:t>已有文本A</w:t></w:r>           ← 已有内容
    <w:r><w:t>已有文本B</w:t></w:r>
    <w:commentRangeEnd w:id="0"/>            ← 段末（run 之后）
    <w:r>                                    ← 引用 run（新建，在段末）
      <w:commentReference w:id="0"/>
    </w:r>
  </w:p>

批注内容 word/comments.xml:
  <w:comment w:id="0" w:author="..." w:date="..." w:initials="...">
    <w:p><w:r><w:t>批注正文</w:t></w:r></w:p>
  </w:comment>
```

三处锚点（`commentRangeStart` / `commentRangeEnd` / `commentReference`）与 `comments.xml` 的 `<w:comment>` 用**同一个 `w:id`** 配对。

### 2.2 POI 层

POI 5.2.5 能力（`javap` 实测，read 子任务 `research/ordering.md` §3.1 已确认）：

| 能力 | 签名 | 备注 |
|---|---|---|
| 创建 comments part | `XWPFDocument.createComments()` → `XWPFComments` | 文档无批注时 `getDocComments()` 返 null，创作必须先 `createComments()` |
| 创建单条批注 | `XWPFComments.createComment(BigInteger id)` → `XWPFComment` | 只建 comments.xml 条目，**不**动正文 |
| 设 author/date/initials | `XWPFComment.setAuthor/setDate/setInitials` | 类型化 |
| 批注内加段落 | `XWPFComment.createParagraph().createRun().setText(...)` | `XWPFComment` 实现 `IBody` |
| 正文 rangeStart/End | `CTP.addNewCommentRangeStart/End()` → `CTMarkupRange` | **追加到段末，不排序**（探针 §3.1） |
| 正文 rangeStart/End（按索引） | `CTP.insertNewCommentRangeStart(int)` | **索引是 per-type 数组索引，非全局位置；仍在段末**（探针 §3.1） |
| 正文 commentReference | `CTR.addNewCommentReference()` → `CTMarkup` | 在 run 内 |

**关键约束**：`addNew`/`insertNew` 都不按 schema 顺序。`commentRangeStart` 必须用 **XmlCursor** 手动 move 到 `CTP` 第一个子之前。

### 2.3 nondocx 层

nondocx 的责任（对照 tracked-changes-authoring design §2.3）：

- 提供显式 authoring API（`Paragraph.addComment`），不引入隐式 magic。
- 让批注写路径与普通写路径共存、互不污染（普通 `addRun` 不带批注语义）。
- 把 XmlCursor 定位脏活收进 `internal/poi/CommentNodes`，公共 API 表面 POI-free。
- 创作出的批注能被 read 子任务的 `Comments.list()` 读回（§7 闭环）。

## 3. 公共 API 形态

### 3.1 方法落点（已定）

创作入口在**内容类型** `Paragraph`，不在门面 `Comments`——对照 tracked-changes R3「门面管读/accept-reject、内容类型管创作」。run 级 API（`Run.addComment`）留 v2（prd R2）。

```java
// api/text/Paragraph.java
public Comment addComment(String author, String text)
```

### 3.2 返回值语义（已定）

**返回新建的 `Comment`（holding-wrapper，持新建的 `XWPFComment`）**。理由（对照 tracked design §3.2）：

1. 批注创作产生一个新的、可继续读的领域对象（读 author/id/text），返回它最符合直觉。
2. `Comment` 是 holding-wrapper（read 子任务 design §4 已定），持 `XWPFComment` 委托，返回后调用方可立即 `.id()` / `.author()` 拿到刚写的元数据，无需再走 `doc.comments().list()` 二次查询。
3. **不返回 `Paragraph`（this）**：批注创作不改变段落的"继续编辑"句柄语义（不像 `addDeletion` 返 this 是因为 deletion 迁移了 run 结构）；返回批注对象本身价值更高。

### 3.3 参数校验（对照 tracked design §5）

| 参数 | 规则 | 异常 |
|---|---|---|
| `author` | 非 null 且非空白 | `IllegalArgumentException`（对照 `addInsertion`） |
| `text` | 非 null（允许空串，写出空正文批注） | `IllegalArgumentException`（null 时） |

`date` 自动分配 `Calendar.getInstance()`（对照 tracked 创作约定）；`w:id` 自动分配（§5）。

## 4. OOXML 锚点写入算法（核心脏活）

`internal/poi/CommentNodes.addWholeParagraphComment(...)` 全权负责。算法（探针 §4 验证可行）：

```
输入: XWPFDocument doc, XWPFParagraph target, String author, Calendar date, String text
输出: 新建的 XWPFComment（已设元数据 + 正文）

1. nextCommentId(doc):
   - 扫 doc.getDocComments().getComments()（null 时视为空）取 max(id) + 1
   - 无批注时返回 0
   - 与 TrackedChangeNodes.nextRevisionId 同套路，但扫的是批注 id（CTMarkup 的 w:id）
     —— 注意：批注 id 与修订 id 是两套独立计数器，不混用 nextRevisionId

2. 确保 comments part 存在:
   - XWPFComments xcom = doc.getDocComments();
   - if (xcom == null) xcom = doc.createComments();   ← 首条批注时 part 尚不存在

3. 建批注条目（comments.xml）:
   - XWPFComment c = xcom.createComment(BigInteger.valueOf(id));
   - c.setAuthor(author); c.setDate(date); c.setInitials(derive initials from author);
   - c.createParagraph().createRun().setText(text);   ← 批注正文（单段）

4. 在正文 CTP 上建锚点:
   - CTP ctp = target.getCTP();
   - CTMarkupRange start = ctp.addNewCommentRangeStart(); start.setId(id);
   - CTMarkupRange end   = ctp.addNewCommentRangeEnd();   end.setId(id);
   - CTR refRun = ctp.addNewR();
   -   refRun.addNewCommentReference().setId(id);       ← 引用 run（段末）

5. XmlCursor 把 start 移到段首（探针 §3.2 方案C）:
   - XmlCursor pCur = ctp.newCursor(); XmlCursor startCur = start.newCursor();
   - try { pCur.toFirstChild(); startCur.moveXml(pCur); }
   -   // pCur 指向 CTP 原第一个子；moveXml 把 start 移到它之前
   - finally { pCur.dispose(); startCur.dispose(); }
   - end + refRun 留在段末（addNew 自然位置即正确语义）

6. return new Comment(c);   ← holding-wrapper 包装新建批注
```

**为什么 start 要 move 而 end/reference 不用**：探针 §3.1 证实 `addNew` 追加到段末——对 `commentRangeEnd` 与引用 run 而言，段末正是它们的正确语义位置（范围终点 + 引用紧跟其后）；只有 `commentRangeStart` 错位（应在段首却在段末）。

### 4.1 initials 派生

`XWPFComment.setInitials` 接受任意字符串。POI/Word 不强制 initials 与 author 的关系。本子任务的简单策略（对照 read 子任务对 initials 的透传口径）：

- **不派生**：`initials` 设为空串（`""`）。理由：派生规则（取首字母、取首词等）是产品偏好，无 OOXML 约束；空 initials 不影响 Word 显示（read 子任务已验证 initials 可缺失）。AC4 人工验收若 Word 显示需要 initials 再回退补派生逻辑。

## 5. 批注 id 分配（`nextCommentId`）

与 tracked-changes 的 `nextRevisionId` **完全独立**——两套 OOXML id 计数器（批注的 `CTMarkup` w:id vs 修订的 `CTTrackChange` w:id）。

```
nextCommentId(doc):
  XWPFComments xcom = doc.getDocComments();
  if (xcom == null) return 0;
  long max = -1;
  for (XWPFComment c : xcom.getComments()) {
    // XWPFComment.getId() 在 POI 5.2.5 返回 String,解析为 long 取 max;非数字 id 跳过
    String idStr = c.getId();
    if (idStr != null) max = Math.max(max, Long.parseLong(idStr));  // try/catch 跳过非数字
  }
  return max + 1;
```

**注意**：这里扫 `comments.xml` 的全部批注取 max，**不**扫 `document.xml` 的锚点。理由：`w:id` 的真源是 `comments.xml` 的 `<w:comment w:id=...>`；锚点只是引用同一个 id。与 `nextRevisionId` 扫 `collect()` 的口径对称（修订 id 的真源是修订节点本身）。

## 6. 与现有 API 的关系（不污染）

- **普通 `addRun` 不变**：`Paragraph.addRun(text)` 仍写普通 `<w:r>`，无批注语义。
- **读 API 不变**：`paragraph.text()` / `runs()` 行为不变；批注通过 `doc.comments().list()` 专门读取。
- **`Document.equals` 不纳入批注**：与 tracked changes / TOC 一致（`Comments` javadoc 已声明）。
- **与 `<w:trackChanges/>` 开关正交**：批注创作不依赖修订开关（与 tracked authoring design §6 同精神）。

## 7. 与 read 子任务的闭环

创作出的批注必须满足 read 子任务的读回契约（prd R3.1）：

- `Comments.list()` 扫 `document.xml` 的 `commentRangeStart` 按出现顺序产出——本子任务写出的 start 在段首，会被正确命中。
- `Comments.get(id)` 按 w:id 精确查询——本子任务返回的 `Comment.id()` 即写入的 w:id，可立即被 `get` 命中。
- **round-trip**：save → reopen 后 `Comments.list()` 仍能读到（探针 §3.3 已验证结构保持、可读）。

**验证点**（implement.md 落测试）：创作后立即 `doc.comments().list()` 应包含新批注；save→reopen 后再 `list()` 仍包含、author/text 正确。

## 8. Out of Scope（对照 prd）

- 回复 / 线程（`commentsExtended`）—— 子任务 3。
- `people.xml` / `paraId` / RSID 注入 —— 子任务 4。
- 删除批注（`Comments.remove(id)`）—— future。
- 跨段范围批注 —— future。
- run 级批注（`Run.addComment`）—— v2（prd R2）。

## 9. 风险观察点

- **Q2（rStyle CommentReference）**：不建样式定义，依赖 Word 批注窗格逻辑显示。低风险——AC4 人工验收若异常再回退（在 `addWholeParagraphComment` 里补 `refRun.addNewRPr().addNewRStyle().setVal("CommentReference")` + styles.xml 建样式）。
- **空段批注**：若段落无任何 run，`ctp.newCursor().toFirstChild()` 返回 false（无子）。算法需处理：无子时 start 直接 `addNew` 即在首位（无需 move）。implement 期加测试覆盖。
- **重复创作同段批注**：同一段多次 `addComment` 会产生多个嵌套范围——OOXML 允许，语义合法（LIFO 嵌套）。不在本子任务特殊处理。

## 10. 待收敛问题

无。Q1/Q3 已收敛（prd），Q2 标低风险实现期验证。design 已可支撑 implement.md。
