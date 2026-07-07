# Design — comments 回复 + 线程（commentsExtended 四 part 自维护）

> 配套 `prd.md`。本文记录批注回复、线程建模、四 part 自维护的技术设计。
>
> 探针依据见 `research/part-lifecycle.md`（OPC part 生命周期 + 四 part 结构）。

## 1. 设计目标

交付批注**回复能力**（`Comments.reply`）+ **线程建模**（`Comment.parentId()` / `paraId()`）。核心工程挑战是 **POI 对 commentsExtended/Ids/Extensible 三个 part 完全无 Java 类、无 API**，nondocx 必须用 OPC 层自维护（探针证实可行）。

需要定清的关键点：

1. 回复 API 的形态与落点
2. 四 part 自维护的架构（创建/幂等/写入）
3. 线程关系的读侧解析（paraId ↔ commentId join）
4. `Comment` holding-wrapper 如何承载 `parentId` / `paraId`（POI 委托不提供这两个）
5. paraId / durableId 的生成

**与 authoring 子任务的本质差异**：authoring 的 POI 支持 `XWPFComments.createComment`，nondocx 只是补正文锚点；本子任务的 POI 对三个 part **零支持**，nondocx 要从 OPC part 级别白手起家。这是 nondocx 首次建立"自维护 OOXML part"模式。

## 2. 三层映射

### 2.1 OOXML 层

批注线程在 OOXML 里是**四 part 协作**（探针 §3 实测结构）：

```
comments.xml (批注正文, POI 支持):
  <w:comment w:id="0" w:author="non" ...>
    <w:p w14:paraId="11111111" ...>              ← paraId 在批注内段落上(线程链的 key)
      <w:r><w:t>父批注内容</w:t></w:r>
    </w:p>
  </w:comment>
  <w:comment w:id="1" w:author="回复者" ...>
    <w:p w14:paraId="22222222" ...>
      <w:r><w:t>回复正文</w:t></w:r>
    </w:p>
  </w:comment>

commentsExtended.xml (线程关系, POI 无 API):
  <w15:commentsEx>
    <w15:commentEx w15:paraId="11111111" w15:done="0"/>                          ← 根(无 paraIdParent)
    <w15:commentEx w15:paraId="22222222" w15:paraIdParent="11111111" w15:done="0"/> ← 子(paraIdParent=父 paraId)
  </w15:commentsEx>

commentsIds.xml (durableId 映射, POI 无 API):
  <w16cid:commentsIds>
    <w16cid:commentId w16cid:paraId="11111111" w16cid:durableId="1A2B3C4D"/>
    <w16cid:commentId w16cid:paraId="22222222" w16cid:durableId="2B3C4D5E"/>
  </w16cid:commentsIds>

commentsExtensible.xml (w16cex 扩展, POI 无 API):
  <w16cex:commentsExtensible>
    <w16cex:commentExtensible w16cex:durableId="1A2B3C4D"/>
    <w16cex:commentExtensible w16cex:durableId="2B3C4D5E"/>
  </w16cex:commentsExtensible>

正文 document.xml (锚点, 回复的锚点位置见 §4.3):
  父批注 commentRangeStart(id=0) → 回复批注 commentRangeStart(id=1) → ...内容...
  → 父 commentRangeEnd(id=0) → 回复 commentRangeEnd(id=1) → 父引用 run → 回复引用 run
```

**线程链的解析**（读侧核心）：commentsExtended 的 `paraIdParent` 是唯一线索。要得到"comment id=1 是 comment id=0 的回复"，需 join：

- comments.xml：批注内首段 `w14:paraId` → 批注 `w:id`（paraId→commentId）
- commentsExtended：`paraId` → `paraIdParent`（本批注 paraId → 父批注 paraId）
- 再 join：父 paraId → 父 commentId

### 2.2 POI 层

| 能力 | POI 5.2.5 | nondocx 处理 |
|---|---|---|
| comments.xml 读写 | ✅ `XWPFComments` | 复用 authoring 路径 |
| commentsExtended/Ids/Extensible | ❌ 无 Java 类 | **OPC createPart + 字符串 XML 写入**（探针 §2） |
| `[Content_Types].xml` Override | ✅ createPart 自动注册 | 无需手写 |
| document.xml.rels relationship | ✅ `addRelationship` | part 创建后加关系 |
| 批注内段落的 w14:paraId | ❌ POI createParagraph 不写 | XmlCursor/CT 补 |
| commentsExtended 的 paraIdParent | ❌ | 字符串 XML 拼 |

**关键 POI 能力**（探针 §2.1）：`OPCPackage.createPart(PackagePartName, contentType)` + `PackagePart.getOutputStream()` + `PackagePart.addRelationship(...)`。`[Content_Types].xml` 由 createPart **自动**注册 Override——这是简化关键，nondocx 不用手写 Content_Types。

**幂等坑**（探针 §2.3）：重复 `createPart(同名)` 抛 `PartAlreadyExistsException`。nondocx 必须先 `getPart(name)` 检查：存在则读出现有 XML 追加条目、不存在才 createPart。

### 2.3 nondocx 层

nondocx 的责任：

- 把 OPC part 创建/幂等/字符串 XML 写入的脏活收进 `internal/poi/CommentNodes`（扩展 authoring 已建的该类）。
- 公共 API（`Comments.reply` / `Comment.parentId` / `Comment.paraId`）POI-free。
- 读侧在 `CommentNodes.collect` 时解析 commentsExtended，把 `parentId` 注入 `Comment`。
- paraId/durableId/dateUtc 生成。

## 3. 四 part 自维护架构

### 3.1 part 写入器：`CommentExtendedParts`（新增内部类）

四 part 的"追加一条 commentEx/commentId/commentExtensible 条目"是重复模式。在 `internal/poi/` 新增一个小助手类 `CommentExtendedParts`（或作为 `CommentNodes` 的私有静态内部类），提供：

```java
// 给四 part 各追加一条条目(幂等:part 不存在则创建)
static void appendCommentEntries(XWPFDocument doc, CommentThreadInfo info);
//   info 含: paraId, parentParaId(null=根), durableId
//   → commentsExtended.xml 加 <w15:commentEx w15:paraId=.. [w15:paraIdParent=..] w15:done="0"/>
//   → commentsIds.xml 加 <w16cid:commentId w16cid:paraId=.. w16cid:durableId=../>
//   → commentsExtensible.xml 加 <w16cex:commentExtensible w16cex:durableId=../>
//   dateUtc 注入 commentsExtensible 的 commentExtensible 属性(w16du:dateUtc, 见 §3.4)
```

### 3.2 part 创建/幂等算法（探针 §2 验证）

```
ensurePart(doc, partName, contentType, rootElementNs):
  PackagePart part = doc.getPackage().getPart(partName);
  if (part == null):
    part = doc.getPackage().createPart(partName, contentType);   ← Content_Types 自动注册
    写入 part 初始内容: XML 声明 + 空根元素(带命名空间)
    doc.getPackagePart().addRelationship(partName, INTERNAL, relType);
  return part;
```

**三 part 的 partName / contentType / relType / 根元素**（探针 §3 实测）：

| part | partName | contentType | relType | 根元素 ns |
|---|---|---|---|---|
| commentsExtended | `/word/commentsExtended.xml` | `...wordprocessingml.commentsExtended+xml` | `.../08/relationships/commentsExtended` | `w15` (2012/wordml) |
| commentsIds | `/word/commentsIds.xml` | `...wordprocessingml.commentsIds+xml` | `.../08/relationships/commentsIds` | `w16cid` (2016/wordml/cid) |
| commentsExtensible | `/word/commentsExtensible.xml` | `...wordprocessingml.commentsExtensible+xml` | `.../08/relationships/commentsExtensible` | `w16cex` (2018/wordml/cex) |

### 3.3 追加条目：读-改-写

part 内容是普通 XML（无 XmlBeans 类型）。追加一条条目：

1. 读 part 现有内容（`part.getInputStream()`）。
2. 解析为 DOM 或字符串定位根元素闭合标签。
3. 在根元素闭合前插入新条目字符串。
4. 写回 `part.getOutputStream()`（覆盖）。

**实现选择**：用 `javax.xml.parsers.DocumentBuilder`（DOM）读写——比字符串拼接稳（处理命名空间、转义）。part 文件小（几 KB），DOM 开销可接受。

### 3.4 dateUtc 注入

`commentsExtensible.xml` 的条目可带 `w16du:dateUtc`（ISO-8601 UTC，如 `2026-07-07T12:34:56Z`）。docx skill 在创建时即注入。本子任务对齐：`commentExtensible` 条目带 `w16du:dateUtc`。

## 4. 回复创作算法

### 4.1 `Comments.reply(String parentId, String author, String text)`

```
1. 校验: parentId 命中(NoSuchElementException if miss); author 非空(IllegalArgumentException)
2. nextCommentId(doc) → 新 w:id (复用 authoring 的方法)
3. 生成 paraId = randomHex8() (< 0x7FFFFFFF)
   生成 durableId = randomHex8() (< 0x7FFFFFFF)
   now = Calendar.getInstance(); dateUtc = ISO8601.format(now)
4. 建 comments.xml 条目:
   XWPFComment c = getDocComments().createComment(id); 设 author/date/initials("")
   段落 = c.createParagraph(); 段落.createRun().setText(text)
   给批注内首段补 w14:paraId = paraId (POI 不写, 见 §4.2)
5. 正文锚点: 在父批注锚点附近插(§4.3)
6. 四 part 追加(§3.1): paraId=本, parentParaId=父批注的 paraId, durableId=本, dateUtc
7. return new Comment(c, paraId, parentId)   ← holding-wrapper + 线程字段
```

### 4.2 给批注内段落补 w14:paraId（POI 不写）

POI 的 `XWPFComment.createParagraph()` 建的段落**无** `w14:paraId`。但线程关系靠 paraId 链，必须补。用 CT：

```java
CTP p = commentParagraph.getCTP();
// XmlCursor 或 CT 给 <w:p> 加 w14:paraId 属性
org.apache.xmlbeans.XmlCursor cur = p.newCursor();
cur.setAttributeText(QName.valueOf("{http://schemas.microsoft.com/office/word/2010/wordml}paraId"), paraId);
cur.dispose();
```

**注意**：父批注（普通创作路径或既有文档）可能也没有 paraId——reply 时要先检查父批注是否有 paraId，没有则补一个（否则 paraIdParent 链断）。详见 §5 读侧解析的健壮性。

### 4.3 回复的正文锚点位置（对照 docx skill）

docx skill 的 `reply_to_comment` 锚点策略（§804-870）：在**父批注的 commentRangeStart 后**插新批注的 commentRangeStart；在**父批注的引用 run 后**插新批注的 commentRangeEnd + 引用 run。即回复批注的范围"紧跟"父批注范围、几乎重合。

nondocx 实现：用 XmlCursor 在 document.xml 定位父批注的 `commentRangeStart(w:id=parentId)`，在其后插新批注的 `commentRangeStart`；定位父批注引用 run，在其后插新批注的 `commentRangeEnd` + 引用 run。与 authoring 的 XmlCursor 定位脏活同型（N22），但定位基准是"父批注锚点"而非"段首"。

## 5. 线程读侧解析（`Comment.parentId()`）

### 5.1 `Comment` holding-wrapper 扩展

read 子任务的 `Comment(XWPFComment delegate)` 只持委托。本子任务要加 `parentId` / `paraId`，但 POI 委托不提供。**新增构造函数重载**：

```java
public Comment(XWPFComment delegate)                              // 既有:read 兼容,paraId/parentId 为 null
public Comment(XWPFComment delegate, String paraId, String parentId)  // 新增:线程字段注入
```

字段：`private final String paraId; private final String parentId;`（既有构造设 null）。

- `paraId()` 返回 `Optional<String>` 或可空 String（design §5.3 决策）。
- `parentId()` 返回 `Optional<String>`（根批注为 empty）。

### 5.2 读侧解析算法（扩展 `CommentNodes.collect`）

read 子任务的 collect 按 body 顺序产出 Comment。本子任务在产出前**预解析 commentsExtended**，建 `Map<paraId, parentParaId>` 与 `Map<paraId, commentId>`，产出时 join：

```
collect(doc):
  ... 既有:扫 body 按 commentRangeStart 顺序取 XWPFComment ...
  // 新增:解析 commentsExtended + 批注 paraId
  Map<String,String> paraIdToParent = parseCommentsExtended(doc);   // paraId → parentParaId
  Map<String,String> paraIdToCommentId = parseCommentParaIds(doc);  // 批注内首段 paraId → comment id
  // 产出时 join:
  for each XWPFComment c (按 body 顺序):
    String paraId = paraIdOfComment(c);          // 批注内首段的 w14:paraId
    String parentId = resolveParent(paraId, paraIdToParent, paraIdToCommentId);
    out.add(new Comment(c, paraId, parentId));
```

`resolveParent`：paraId → paraIdToParent 得 parentParaId → paraIdToCommentId 反查得 parentCommentId。

### 5.3 返回类型决策

- `paraId()` → 返回 **`String`（可空）**。理由：paraId 是底层 OOXML 标识，根批注/旧文档可能缺失，用 null 表示"无"比 Optional 更轻；与 `Comment.date()` 返回可空 `Calendar` 同型。
- `parentId()` → 返回 **`Optional<String>`**。理由：parentId 是面向用户的高层线程语义，"是不是回复"是布尔判断，Optional 的 `isPresent()` 表达更直接（prd R2.1 / User Value 示例都用 Optional）。

## 6. 与现有 API 的关系（不污染）

- **authoring 路径复用**：reply 的 comments.xml 条目创建、正文锚点 XmlCursor 定位，复用 authoring 的 `addWholeParagraphComment` 套路（但锚点位置不同——reply 锚在父批注附近，authoring 锚段首）。
- **读 API 扩展**：`Comment` 新增两个只读字段，既有五字段不变；既有构造函数保留（read 兼容）。
- **既有批注兼容**：无 commentsExtended 的文档（authoring 产出的、或旧文档），所有批注 `parentId()` 为 empty、`paraId()` 为 null——视为全根批注，不破坏。
- **POI-free 表面**：reply/parentId/paraId 公共 API 不见 OPC/POI 类型；脏活在 `internal/poi/`。

## 7. 风险观察点

- **paraId 缺失健壮性**：既有批注（authoring 产出或外部文档）可能无 paraId。reply 父批注无 paraId 时要补；读侧 paraId 缺失时 parentId 返 empty 不崩。
- **DOM 解析 part 的健壮性**：part 内容畸形时（手工损坏）DOM 解析可能抛。防御式：解析失败时该 part 视为"无线程信息"，parentId 全 empty 不崩。
- **幂等 race**：同一文档多次 reply，每次都读-改-写 part。单线程内安全；nondocx 不做多线程文档并发保证（与既有写 API 一致）。
- **AC4 Word 线程显示**：四 part 全做以对齐 Word 产出，但 Word 对 paraId 的具体校验（如唯一性、格式）需人工验收。

## 8. Out of Scope（对照 prd）

- people.xml 注入 —— 子任务 `06-22-comments-infrastructure`。
- 批注 resolve/done 状态 API —— commentsExtended 有 `done` 属性，本子任务写 0，不做专门读写 API（future）。
- 跨段批注的回复 —— 锚点固定在父批注锚点附近。
- 删除批注 / 删除回复 —— future。

## 9. 待收敛问题

无。Q1–Q4 已收敛（prd），四 part 全做已定。design 已可支撑 implement.md。
