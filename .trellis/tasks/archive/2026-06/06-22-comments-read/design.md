# Design — comments 只读消费侧

> 配套 `prd.md`。本文记录该子任务的技术设计：门面形态、`Comment` 值对象形态、`list()` 顺序契约、活对象语义与一致性约束。
>
> **对照**：`06-18-tracked-changes-read/design.md`（同样三层映射、holding-wrapper、活视图列表、`get(id)` 命中式访问）。本子任务范围比 tracked-changes-read 窄——comments 只有元数据读（author/text/date/initials/id），没有 family/type/location/details 多层抽象。

## 1. 设计目标

交付批注的只读消费底座：

- 通过 `Document.comments()` 拿到一个统一门面 `Comments`
- `Comments.list()` 按文档顺序枚举批注
- `Comments.get(id)` 按 OOXML `w:id` 精确命中单条批注
- `Comment` 暴露五个只读字段（id / author / initials / date / text）

设计重点是把下面几个契约先定稳：

1. 文档级入口与 `Comments` 门面职责
2. `Comment` 的 holding-wrapper 形态（Q1）
3. `list()` 的顺序契约（Q2）
4. 活对象语义、异常包装、POI-free 一致性约束

## 2. 三层映射

### 2.1 OOXML 层

comments 只读消费侧要面对**两个 part**：

- `word/comments.xml`（PackagePart `http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments`）
  - 每条批注是一个 `<w:comment w:id=".." w:author=".." w:date=".." w:initials="..">..</w:comment>` 元素
  - `w:comment` 的子内容是批注正文段落（`<w:p>/<w:r>/<w:t>`，与正文同构）
- `word/document.xml`
  - 批注的**锚点**散落在正文各处：`<w:commentRangeStart w:id=".."/>` 与 `<w:commentRangeEnd w:id=".."/>` 包裹被批注的正文范围；同 id 的 `<w:commentReference w:id=".."/>` 在 run 内引用

关键事实（探针验证，见 `research/ordering.md`）：

- `comments.xml` 内 `<w:comment>` 的顺序是**创建顺序**（插入顺序），不是 id 升序、不是正文锚点顺序。
- `document.xml` 内 `commentRangeStart` 的出现顺序才是**正文阅读顺序**。

### 2.2 POI 层

POI 5.2.5 对 comments 有完整高级 API（与 tracked changes 不同）：

| POI 能力 | 签名 | nondocx 用途 |
|---|---|---|
| 文档级入口 | `XWPFDocument.getDocComments()` → `XWPFComments` | `Comments` 门面委托入口 |
| 文档级创建 | `XWPFDocument.createComments()` | 仅 authoring 子任务用；读侧不用 |
| 枚举 | `XWPFComments.getComments()` → `List<XWPFComment>` | **不直接用作 `list()`**（顺序问题，见 §4） |
| 按 id 查 | `XWPFComments.getCommentByID(String)` → `XWPFComment`（miss 返回 null） | `get(id)` 反查 |
| 单条批注 | `XWPFComment.getId/getAuthor/getInitials/getDate/getText` | `Comment` 字段穿透 |
| 单条批注段落 | `XWPFComment.getParagraphs()`（`XWPFComment` 实现 `IBody`） | `Comment.text()` 委托 `getText()` |
| 底层 CT | `XWPFComment.getCtComment()` → `CTComment` | `Comment.raw()` |

**坑**：

1. `getDocComments()` 在文档**无任何批注**时返回 `null`（POI 不自动创建空 part）。`Comments` 门面与 `CommentNodes.collect` 必须处理。
2. `getComments()` 返回 `comments.xml` 部件顺序，**不等于正文顺序**（见 §4 Q2 决策）。nondocx 需自己扫 `document.xml` 重排。
3. `getCommentByID(String)` miss 时返回 `null`（不抛），nondocx 包装成 `NoSuchElementException`。
4. `XWPFComment.getDate()` 在文档未设 `w:date` 时返回 `null`——`Comment.date()` 需可空。
5. `CTComment extends CTTrackChange`（与 tracked changes 同构）：`author` / `date` / `id` 都是类型化属性。

### 2.3 nondocx 层

nondocx 在本子任务的责任：

- 提供 POI-free 统一只读门面 `Comments`，由 `Document.comments()` 返回
- 把「扫 document.xml 找 commentRangeStart 顺序 + 按 id 从 comments.xml 取正文」的脏活收进 `internal/poi/CommentNodes`
- 把单条 `XWPFComment` 包装成 holding-wrapper `Comment`
- 对外只暴露 `api/comment/` 下的干净类型，源码层不出现 `org.apache.poi`

## 3. 公共 API 形态

### 3.1 文档级统一入口

- `Document.comments()` —— 返回 `Comments` 门面（从不返回 null；即便文档无批注，门面对象本身仍存在）

接入点镜像 `Document.trackedChanges()`（418-419 行）：每次调用 `new Comments(delegate)`，不缓存门面对象。

**与 `toc()` 的区别**：`toc()` 在文档无 TOC 时返回 `null`；comments 与 trackedChanges 一样，「列表为空」本身是有意义的状态，门面总是非 null。

### 3.2 门面职责

`Comments` 负责两件只读事：

- `list()` —— 返回按**文档顺序**排列的全部批注
- `get(String id)` —— 按 OOXML `w:id` 精确命中单条批注

不负责（留后续子任务）：

- 创作批注（`06-22-comments-authoring`）
- 回复 / 线程（`06-22-comments-reply-threads`）
- resolve/done 状态（`commentsExtended.xml`，子任务 3）
- 锚点位置解析（commentRangeStart/End 指向正文哪里）

### 3.3 命名风格

最简风格，与现有 `Document` 表面一致：

- `Document.comments()`
- `Comments.list()`
- `Comments.get(id)`

## 4. `Comment` 值对象形态 —— holding-wrapper（Q1 决策）

### 4.1 决策

`Comment` 采用 **holding-wrapper** 形态：持有单个 `final XWPFComment` 委托，读写穿透，`raw()` 返回该委托。

**与 tracked-changes-read 的 `TrackedChange` 同构**，与 `Section`（持 `XWPFDocument` + `CTSectPr`）也同构。

### 4.2 为什么 holding-wrapper 而不是纯值对象

探针验证（POI 5.2.5）：

- `XWPFComment` 是 POI 的**完整高级类型**，实现 `IBody`，提供 `getParagraphs()` / `getCtComment()` 等持续有价值的访问。
- `CTComment extends CTTrackChange`：author / date / id 都是类型化属性，读取干净。
- 因此 `Comment` 持有 `XWPFComment` 后，后续 authoring / reply 子任务可以直接经 `raw()` 拿到可写委托做创作与回复——holding-wrapper 的「活对象」价值在此。

若是纯值对象（构造时一次性快照），后续子任务要写时还得绕回 `raw()` 取 POI 对象，引入「读时值对象 / 写时活对象」的分裂。holding-wrapper 一次定形，后续不返工。

### 4.3 与 TOC（N11）的关键区别

TOC 是 poi-bridge.md **Rule 1 的诚实偏差**（没有干净 per-entry POI 句柄、条目是缓存快照）。comments **有**干净的 per-comment POI 句柄（`XWPFComment`），因此走标准 holding-wrapper，**不偏离 Rule 1**。

### 4.4 字段职责

`Comment` 公开 5 个只读字段，全部穿透到委托：

| 字段 | 实现 | 缺失语义 |
|---|---|---|
| `id()` | `delegate.getId().toString()` | `w:id` 不会缺失（OOXML 强制） |
| `author()` | `delegate.getAuthor()` | 缺失返回空串 `""`（与 `TrackedChange.author()` 一致） |
| `initials()` | `delegate.getInitials()` | 缺失返回空串 `""` |
| `date()` | `delegate.getDate()` | 缺失返回 `null`（POI 探针确认 date 可空） |
| `text()` | `delegate.getText()` | 委托 POI（见 §4.5） |

**关于 `date()` 的返回类型**（PRD R2.3 列了 `Optional<Calendar>` 或可空 `Calendar` 两个选项）：

→ 选**可空 `Calendar`**。理由：与 `TrackedChange.date()`（返回可空 `Calendar`）一致；`Optional` 在 nondocx 现有 API 里无先例，引入会让 comments 成为孤例。

### 4.5 `text()` 委托 POI（决策）

`text()` 直接返回 `delegate.getText()`，**不**自己遍历 `getParagraphs()` 拼。

理由：

- POI 探针验证 `getText()` 正确返回了多段批注的拼接文本。
- POI-tested，省代码。
- **回退条款**：若后续测试发现 POI 的 `getText()` 在某些批注形态（如嵌套 SDT、多段落换行）下行为不稳，回退到自拼 `getParagraphs()` 的 `getText()`——这是实现细节，不改变公共契约（`text()` 仍返回 `String`）。

### 4.6 构造函数

`Comment` 构造函数是 **internal seam**（poi-bridge.md N1）：接受 `XWPFComment` 委托，仅由 `internal/poi/CommentNodes` 调用。Javadoc 明示这是内部接缝，用户通过 `Comments.list()` / `get(id)` 获取，不直接构造。

### 4.7 equals / hashCode / toString

- `equals` / `hashCode` 比较内容派生值（id / author / initials / date / text），**不比较委托引用**（quality-guidelines.md Rule 2 + poi-bridge.md N7）。与 `TrackedChange` 同模式。
- `toString` 给调试用中文摘要，格式与 `TrackedChange.toString()` 风格一致（`Comment{id=.., author="..", text=".."}`）。

## 5. `list()` 顺序契约 —— body 顺序（Q2 决策）

### 5.1 决策

`Comments.list()` 按 **body 顺序**返回：批注在 `document.xml` 里 `commentRangeStart` 的出现顺序。

### 5.2 为什么不是 comments.xml 部件顺序

探针验证（`research/ordering.md`，fixture 里三条评论按 id 2,0,1 创建；正文 rangeStart 顺序 0,1,2）：

```
getComments()   → [id=2, id=0, id=1]   ← comments.xml 部件顺序（创建顺序）
body order      → [id=0, id=1, id=2]   ← document.xml commentRangeStart 出现顺序
```

POI 的 `getComments()` 不帮你按 body 重排。若直接委托，用户会困惑：「我在第一段加的批注，为什么 `list()` 第三个才返回？」

### 5.3 为什么不是 w:id 升序

`w:id` 分配不保证对应阅读顺序：Word 会复用 id、WPS 分配规则不同。这是人造顺序，不是语义顺序。

### 5.4 body 顺序怎么实现

`CommentNodes.collect(document)`：

1. **先扫 `comments.xml`**：`getDocComments().getComments()` 拿到全部 `XWPFComment`，建一张 `Map<w:id 字符串, XWPFComment>`。
   - 若 `getDocComments()` 返回 `null`（无批注），直接返回空列表。
2. **再扫 `document.xml`**：用 `XmlCursor` 遍历 `CTBody`（与 `TrackedChangeNodes.walkBody` 同一套路），按出现顺序收集 `commentRangeStart` 的 `w:id`。
3. **按 body 出现顺序**从 Map 取 `XWPFComment`，包装成 `Comment` 产出。
4. **孤儿批注降级**：`comments.xml` 里有但 `document.xml` 里**没有** `commentRangeStart` 锚点的批注（损坏文档、手工删了锚点），按 `comments.xml` 部件顺序追加到末尾，不丢弃。
5. **防御式**：单个 `XWPFComment` 解析失败时跳过该条（PRD R3.3，对照 `TrackedChangeNodes` 的 walk 防御），不影响其余批注产出。

### 5.5 `get(id)` 的顺序无关性

`get(id)` 按 id 精确命中，与 `list()` 的顺序无关。内部可走 POI 的 `getCommentByID(id)` 反查（miss → 包装成 `NoSuchElementException`），也可走 `list()` 线性扫——实现细节。**初版走 `list()` 线性扫**，与 `TrackedChanges.get()` 同模式（保持一致性，避免引入两套查找路径）。

## 6. `Comments` 门面行为契约

### 6.1 `list()`

- 返回按 body 顺序的全部批注
- 活视图（与 `TrackedChanges.list()` 一致）：每次访问都从委托重算，不缓存批注列表
- 文档无批注时返回空列表，不抛
- 返回 `List<Comment>`（不可修改的 `AbstractList` 包装，同 `TrackedChanges.list()`）

### 6.2 `get(String id)`

- 入参是 OOXML `w:id` 的字符串形式
- 命中返回 `Comment`；未命中抛 `NoSuchElementException`（不返回 null）
- 语义是「命中式访问」，与 `TrackedChanges.get()` 一致

### 6.3 `raw()`

- 返回 `XWPFDocument`（批注无专属单一委托类型，同 `TrackedChanges.raw()`）
- 供后续 authoring / reply 子任务做写穿透

### 6.4 活对象语义

- `Comments` 持单个 `final XWPFDocument` 委托
- `list()` / `get(id)` 每次调用当场重算，不缓存
- 守住 poi-bridge.md Rule 1「holding wrapper, not cache」

## 7. 一致性约束（对照父任务 R4）

- **公共 API POI-free**：`api/comment/` 源码不出现 `org.apache.poi`（AC6）。`Comment` 构造函数接缝接受 `XWPFComment`，但 `XWPFComment` 在 javadoc 里引用、在 import 里出现——这条约束的实际边界是「公共方法签名不出现 POI 类型」，构造函数作为 internal seam 例外（同 `TrackedChange(CTRunTrackChange)`）。实现时用 grep 校验。
- **异常包装**：`CommentNodes.collect` / `Comments` 内的 POI 异常包成 `Docx*Exception`（poi-bridge.md Rule 4）；`raw()` 路径不包。
- **不参与 `Document.equals`**：`Comments` 不进 `Document` 的内容相等性（对照 `TrackedChanges` / `TableOfContents` 不进 equals 的约定）。

## 8. 与后续子任务的衔接

该子任务交付后，为后续子任务提供稳定基础：

- **authoring 子任务**：复用 `Comments` 门面 + `Comment` holding-wrapper。新增批注后 `list()` 自然读到（活视图）。创作经 `Comments.raw()` 或新增门面方法写 `comments.xml`。
- **reply / threads 子任务**：复用 `Comment` 形态，在 `comments.xml` 的 `w:comment` 内追加 `w:p>` 表达回复段落；`Comment` 可能新增 `replies()` 方法，但不改读侧契约。
- **resolve/done 子任务**：读 `commentsExtended.xml`，在 `Comment` 上叠加 `resolved()` 方法。

## 9. 当前仍待收敛的问题

子任务级契约问题已全部收敛：

- ✅ 包装形态：holding-wrapper 持 `XWPFComment`（§4）
- ✅ `list()` 顺序：body 顺序（§5）
- ✅ `text()` 实现：委托 POI `getText()`，不稳则回退自拼（§4.5）
- ✅ `date()` 类型：可空 `Calendar`（§4.4）
- ✅ `get(id)` miss 语义：抛 `NoSuchElementException`（§6.2）

实现阶段唯一仍可能触发回 planning 的点：`XWPFComment.getText()` 若在某种批注形态下行为异常（如批注含 SDT 或图片 run），可能需要回退到自拼——这是实现细节调整，不需回 planning，按 §4.5 回退条款处理。
