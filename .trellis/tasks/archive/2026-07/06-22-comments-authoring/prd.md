# PRD — comments 基础创作（单条范围批注）

> 父任务：`06-22-comments`（planning）。
>
> **背景**：前序子任务 `06-22-comments-read` 已交付只读消费侧（`Comments` 门面 + `Comment` 值对象）。本子任务补齐**创作侧 MVP**——单条范围批注。
>
> **对照**：`06-18-tracked-changes-authoring`（显式创作：插入/删除/替换，仅文本类）。

## Goal

交付单条范围批注的显式创作 API，沿用父任务 R3「显式方法、不引入全局录制」约束：

1. **`Paragraph.addComment(author, text)`** —— 给整段加批注（范围 = 整段）。
2. **`Run.addComment(author, text)`**（可选）—— 给单 run 加批注（范围 = 该 run）。
3. 创作出的批注能被 `Comments.list()` 读回，形成「创作 → 读」闭环。

## User Value

```java
try (Document doc = Docx.open(Path.of("draft.docx"))) {
    // 给第 2 段加一条批注
    doc.paragraph(1).addComment("审阅者甲", "这段需要补充背景");

    doc.save(Path.of("draft-commented.docx"));
}
```

完成后在 Word/WPS 打开 `draft-commented.docx` 能看到批注气泡。

## Confirmed Facts（已 javap 实测 POI 5.2.5）

OOXML 批注结构（来自 docx skill `ooxml.md` + `document.py` 探针）：

```
正文 word/document.xml:
  <w:p>
    <w:commentRangeStart w:id="1"/>          ← 范围起点
    <w:r><w:t>被批注的内容</w:t></w:r>
    <w:commentRangeEnd w:id="1"/>            ← 范围终点
    <w:r>
      <w:rPr><w:rStyle w:val="CommentReference"/></w:rPr>
      <w:commentReference w:id="1"/>         ← 引用 run
    </w:r>
  </w:p>

批注内容 word/comments.xml:
  <w:comments>
    <w:comment w:id="1" w:author="..." w:date="..." w:initials="...">
      <w:p><w:r><w:t>批注正文</w:t></w:r></w:p>
    </w:comment>
  </w:comments>
```

POI 5.2.5 能力：

| 能力 | 签名 |
|---|---|
| 创建 comments part | `XWPFDocument.createComments()` → `XWPFComments` |
| 创建单条批注 | `XWPFComments.createComment(BigInteger id)` → `XWPFComment` |
| 设 author/date/initials | `XWPFComment.setAuthor/setDate/setInitials` |
| 批注内加段落 | `XWPFComment.createParagraph()`（实现 `IBody`） |
| 正文 rangeStart/End | `CTP.addNewCommentRangeStart/End()` → `CTMarkupRange` |
| 正文 commentReference | `CTR.addNewCommentReference()` → `CTMarkup` |

**关键**：POI 的 `XWPFComments.createComment(id)` 只建 comments.xml 里的条目，**不会**自动在正文插 rangeStart/End/Reference。后者要 nondocx 自己用 `CTP.addNewCommentRangeStart()` 等做——这是创作侧的主要脏活。

## Requirements

### R1. 整段批注

- [ ] **R1.1** `Paragraph.addComment(String author, String text)` 返回 `Comment`（新建的批注）。
- [ ] **R1.2** OOXML 语义：在段首插 `commentRangeStart`，段末插 `commentRangeEnd` + 引用 run；comments.xml 加条目。
- [ ] **R1.3** author 必传（null/空白抛 `IllegalArgumentException`，对照 `addInsertion`）。
- [ ] **R1.4** date 自动分配 `Calendar.getInstance()`（对照 tracked 创作约定）。
- [ ] **R1.5** `w:id` 自动分配（扫描已有批注 id 取 max+1，对照 `TrackedChangeNodes.nextRevisionId`）。

### R2. Run 级批注（明确留 v2）

- **决策（2026-07-07 收敛）**：本子任务**只做整段批注**，`Run.addComment(author, text)` 不在本子任务交付。
- 探针结论：run 级 = 整段算法 + 多一步「在 CTP 子序列里定位目标 CTR」，复杂度增量不大，但 MVP 优先闭环，run 级作为细化另起子任务或并入 reply-threads 批次（见 design §6 future）。
- 创作入口在 `Paragraph`（与 R4 一致）；run 级 API 形态留给 future design 决策。

### R3. 与读侧的闭环

- [ ] **R3.1** 创作出的批注能被 `Comments.list()` 读回（跨子任务集成）。
- [ ] **R3.2** 创作出的批注 save→reopen round-trip 后，在 Word/WPS 打开能显示批注气泡（人工验收）。

### R4. 一致性约束

- [ ] 创作入口在**内容类型**（本子任务即 `Paragraph`），不在门面——对照 `addInsertion`。
- [ ] POI-free 公共表面；CT 脏活在 `internal/poi/CommentNodes`。
- [ ] 现有普通写 API 行为不变。
- [ ] 异常遵守 `error-handling.md`。

## Acceptance Criteria

- [ ] AC1 `Paragraph.addComment(author, text)` 可用，返回 `Comment`。
- [ ] AC2 创作后 `doc.comments().list()` 能读回该批注，author/text 正确。
- [ ] AC3 save→reopen round-trip 后批注仍在（OOXML 结构完整）。
- [ ] AC4 在 Word / WPS 打开能看到批注气泡（人工验收，附截图或描述）。
- [ ] AC5 author 为 null/空白抛 `IllegalArgumentException`。
- [ ] AC6 toolkit / example 扩展**不在本子任务**（对称 tracked-changes-authoring：核心 API + 测试先行，toolkit/example 留 `comments-docs-spec` 子任务）。

## Out of Scope

- **回复 / 线程** —— 子任务 `06-22-comments-reply-threads`。
- **people.xml / paraId / RSID 注入** —— 子任务 `06-22-comments-infrastructure`。本子任务的批注不含这些现代元数据，仍能在 Word 打开（只是少了 @mention 等高级功能）。
- **删除批注** —— 留 future（读侧能枚举后，删 = 撤 range + 删 comments.xml 条目，可后续补 `Comments.remove(id)`）。
- **批注范围跨多段** —— 本子任务范围 = 整段或单 run；跨段范围（addComment(startPara, endPara, ...)）留 future。

## Open Questions（已收敛 → 见 design.md）

- **Q1 ✅ 已收敛**（探针见 `research/insert-position.md`）：POI 的 `addNew`/`insertNew` 都不按 schema 顺序，`commentRangeStart` 会落到段末（范围空）。必须用 **XmlCursor 手动把 start move 到 CTP 第一个子之前**——这是 authoring 区别于 tracked-changes `addInsertion` 的核心脏活，下沉到 `internal/poi/CommentNodes`。
- **Q2 ⚠️ 低风险，实现期验证**：`commentReference` run 的 `rStyle=CommentReference` **不建**样式定义。理由：Word 批注气泡显示由批注窗格逻辑处理，不依赖该字符样式；read 子任务探针代码亦未建任何样式，round-trip 正常。AC4 人工验收若 Word 显示异常再回退补。
- **Q3 ✅ 已收敛**：run 级批注留 v2（见 R2）。
