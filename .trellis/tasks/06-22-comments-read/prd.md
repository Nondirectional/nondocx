# PRD — comments 读+查询

> 父任务：`06-22-comments`（planning）。
>
> **背景**：nondocx 当前对批注完全无支持。本子任务交付**只读消费侧**，是整个 comments 能力线的第一步——先把「读」做出来，后续子任务在它之上叠创作、回复、基础设施。
>
> **对照**：`06-18-tracked-changes-read`（只读消费侧：开关 + 修订枚举）。

## Goal

交付批注的只读消费能力：

1. **`Comments` 门面**（由 `Document.comments()` 返回）——枚举、按 id 查。
2. **`Comment` 值对象**——读 author / text / date / initials / id。
3. **`internal/poi/CommentNodes`** 脏活收容所——收 POI 5.2.5 的 `XWPFComments` / `XWPFComment`。

## User Value

完成后，用户可以：

```java
try (Document doc = Docx.open(Path.of("reviewed.docx"))) {
    Comments comments = doc.comments();
    for (Comment c : comments.list()) {
        System.out.printf("[%s @ %s] %s%n", c.author(), c.date(), c.text());
    }
    // 按 id 精确查
    Comment target = comments.get("0");
}
```

无需走 `raw()` 自己解 zip 读 `word/comments.xml`。

## Confirmed Facts（已 javap 实测 POI 5.2.5）

| POI 5.2.5 能力 | 签名 | nondocx 用途 |
|---|---|---|
| 文档级入口 | `XWPFDocument.getDocComments()` → `XWPFComments`（可能为 null） | `Comments` 门面委托 |
| 文档级创建 | `XWPFDocument.createComments()` | 仅在创作子任务用；读侧不用 |
| 枚举 | `XWPFComments.getComments()` → `List<XWPFComment>` | `list()` |
| 按 id 查 | `XWPFComments.getCommentByID(String)` | `get(id)` |
| 单条批注 | `XWPFComment.getId/getAuthor/getInitials/getDate/getText()` | `Comment` 值对象字段 |
| 单条批注段落 | `XWPFComment.getParagraphs()`（实现 `IBody`） | `Comment.text()` 可走 `getText()` 或拼段落 |

**边界**：

- `getDocComments()` 在文档**无任何批注**时可能返回 `null`（POI 不自动创建空 part）——`Comments` 门面需处理。
- `XWPFComment.getText()` 是 POI 提供的便捷方法（拼批注内所有段落文本）；若行为不稳，回退到自拼 `getParagraphs()` 的 `getText()`。

## Requirements

### R1. 门面 `Comments`

- [ ] **R1.1** `Document.comments()` 返回 `Comments` 门面（持有 `XWPFDocument` 委托）。
- [ ] **R1.2** `Comments.list()` 返回按文档顺序的批注列表（活视图，每次重算不缓存，对照 `TrackedChanges.list()`）。
- [ ] **R1.3** `Comments.get(String id)` 按 OOXML `w:id` 精确查；未命中抛 `NoSuchElementException`（不返回 null）——对照 `TrackedChanges.get()`。
- [ ] **R1.4** 文档无批注时 `list()` 返回空列表（不抛异常）；`get(id)` 仍按未命中抛 `NoSuchElementException`。

### R2. 值对象 `Comment`

- [ ] **R2.1** `Comment` 字段：`id()` / `author()` / `initials()` / `date()`（`Calendar`）/ `text()`。
- [ ] **R2.2** `Comment` 是不可变值对象（公开 API 无 setter）——对照 `TrackedChange`。
- [ ] **R2.3** `date()` 返回 `Optional<Calendar>` 或可空 `Calendar`（POI 的批注 date 可能缺失）。
- [ ] **R2.4** `text()` 拼接批注内全部段落文本（多段批注）。

### R3. 脏活收容所 `CommentNodes`

- [ ] **R3.1** `internal/poi/CommentNodes.collect(XWPFDocument)` 返回 `List<Comment>`（活算）。
- [ ] **R3.2** 处理 `getDocComments()` 返回 null 的情况（返回空列表）。
- [ ] **R3.3** 单个 `XWPFComment` 解析失败时跳过该条（防御式，对照 `TrackedChangeNodes` 的 walk 防御）。

### R4. 一致性约束（对照父任务 R3）

- [ ] 公共 API POI-free：`api/comment/` 源码不出现 `org.apache.poi`。
- [ ] `raw()` 出口：`Comments.raw()` 返回 `XWPFDocument`（批注无专属单一委托类型，同 `TrackedChanges.raw()`）。
- [ ] 异常包装：POI 异常不裸露，包成 `Docx*Exception`。
- [ ] `Comments` 不参与 `Document.equals`（对照 `TrackedChanges` / `TableOfContents` 不进 equals 的约定）。

## Acceptance Criteria

- [ ] AC1 `Comments.list()` 能读出 Word 产生的批注（用真实 .docx 测试夹具）。
- [ ] AC2 `Comments.get(id)` 精确命中；未命中抛 `NoSuchElementException`。
- [ ] AC3 无批注文档 `list()` 返回空列表，不抛。
- [ ] AC4 `Comment` 五字段（id/author/initials/date/text）读值正确。
- [ ] AC5 `Comments.list()` 是活视图——save→reopen 后重新 `doc.comments().list()` 反映新状态。
- [ ] AC6 `api/comment/` grep `org.apache.poi` 无匹配。
- [ ] AC7 现有 tracked-changes / TOC / paragraph 测试全绿（无回归）。

## Out of Scope

- **创作批注** —— 子任务 `06-22-comments-authoring`。
- **回复 / 线程** —— 子任务 `06-22-comments-reply-threads`。
- **批注的范围锚点定位**（commentRangeStart/End 在正文里指向哪里）—— 本子任务只读批注本身元数据，不解析锚点位置。留 future。
- **批注的 resolve/done 状态** —— 涉及 commentsExtended.xml，子任务 3。

## Open Questions（design.md 收敛）

- **Q1**：`Comment` 持 `XWPFComment` 委托（holding wrapper，可后续 raw），还是纯值对象（快照）？倾向 holding wrapper——与 `TrackedChange` 一致，且 POI 的 `XWPFComment` 实现了 `IBody` 有持续价值。
- **Q2**：`list()` 的「文档顺序」如何定义？批注在 `comments.xml` 里的顺序，还是按正文 commentRangeStart 的出现顺序？POI 的 `getComments()` 返回 part 内顺序，可能不等于正文顺序。需探针验证；若不一致，用正文 rangeStart 顺序重排。
