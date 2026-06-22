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

### R2. Run 级批注（可选，倾向 v2）

- [ ] **R2.1** `Run.addComment(author, text)` —— 范围限定到单 run。
- [ ] 若 POI 在 run 内插 rangeStart/End 的复杂度过高，本子任务可只做整段，run 级留 future。

### R3. 与读侧的闭环

- [ ] **R3.1** 创作出的批注能被 `Comments.list()` 读回（跨子任务集成）。
- [ ] **R3.2** 创作出的批注 save→reopen round-trip 后，在 Word/WPS 打开能显示批注气泡（人工验收）。

### R4. 一致性约束

- [ ] 创作入口在**内容类型**（`Paragraph` / `Run`），不在门面——对照 `addInsertion`。
- [ ] POI-free 公共表面；CT 脏活在 `internal/poi/CommentNodes`。
- [ ] 现有普通写 API 行为不变。
- [ ] 异常遵守 `error-handling.md`。

## Acceptance Criteria

- [ ] AC1 `Paragraph.addComment(author, text)` 可用，返回 `Comment`。
- [ ] AC2 创作后 `doc.comments().list()` 能读回该批注，author/text 正确。
- [ ] AC3 save→reopen round-trip 后批注仍在（OOXML 结构完整）。
- [ ] AC4 在 Word / WPS 打开能看到批注气泡（人工验收，附截图或描述）。
- [ ] AC5 author 为 null/空白抛 `IllegalArgumentException`。
- [ ] AC6 新 API 加入 DocxAgentTools（若 toolkit 组工具需要扩展，可选）。

## Out of Scope

- **回复 / 线程** —— 子任务 `06-22-comments-reply-threads`。
- **people.xml / paraId / RSID 注入** —— 子任务 `06-22-comments-infrastructure`。本子任务的批注不含这些现代元数据，仍能在 Word 打开（只是少了 @mention 等高级功能）。
- **删除批注** —— 留 future（读侧能枚举后，删 = 撤 range + 删 comments.xml 条目，可后续补 `Comments.remove(id)`）。
- **批注范围跨多段** —— 本子任务范围 = 整段或单 run；跨段范围（addComment(startPara, endPara, ...)）留 future。

## Open Questions（design.md 收敛）

- **Q1**：整段批注的 rangeStart/End 插在段的什么位置？`CTP` 的子元素有 schema 顺序（pPr → r → ... → ins/del），rangeStart 应在所有 r 之前，rangeEnd + reference 在所有 r 之后。需探针验证 POI 的 `addNewCommentRangeStart()` 插在末尾还是有序位置；若末尾，需手动重排或用 XmlCursor 精准定位。
- **Q2**：commentReference run 的 rStyle `CommentReference` 是否需要 nondocx 显式建样式定义？POI 可能不自动建 styles.xml 里的 `CommentReference` 样式，需探针；若 Word 显示不依赖该样式，可省。
- **Q3**：run 级批注（R2）本子任务做不做？倾向「整段先做透，run 级评估复杂度后决定」——若 run 内 rangeStart/End 插入与整段同套路，顺手做；否则留 v2。
