# 批注创作：edit_document 增加 comment.add — 总结

## 交付

**写侧（op 10→11）**：`comment.add`（`anchor+text+author` 必传）→ core
`Paragraph.addComment` 整段范围批注。批注不改段落文本，**不受同锚点改文本独占约束**
（与 `replace_text` 可同批同段）。

**读侧（core）**：`Paragraph.comments()` —— 锚定在本段的批注列表（活对象语义）。
POI CTP 子树 `commentRangeStart` 扫描下沉 `CommentNodes.commentsAnchoredIn`（与
`collect` 共用 byId/ThreadResolver/防御式产出）。已发现并记档语义：`addComment`
锚点插在段首，同段多条按 XML 出现序读取为 **LIFO**。

**读侧（view + compiler，三处恒出现）**：
- `view_element` 段落详情 `comments` `[{id,author,text}]`（空数组=无）
- `view_stats` `commentCount`
- `view_outline` 批注概览 `CommentOverview{total,countByAuthor,recent[{paragraph,author,snippet}]}`
  （recent≤5、snippet≤40，与修订概览同型，ref 可直通 view_element）
- `read_document` 元数据行 `<!-- 批注 pN [作者]: 内容 -->`（整行注释，round-trip 安全）

**评测**：baseline 增至 11 条（`comment-add`：批注存在/作者/内容关键词/锚定段/正文不动；
防恒真由「批注存在」在 seed 上失败保证）。eval（runId `20261030-100451-694`）：
**compiler 面 11/11，平均 3.91**（上一版 10 任务 4.20）；comment-add 4 次调用
（current_document → read_document → comment.add → review 达成），复审引用批注原文，
无盲区误报。

## 关键决策

1. **观测面先行**：只加 op 不补观测会重演 §6.6 误报循环（修订作者盲区同族）——写读同批落地。
2. **映射下沉 core**：comment→paragraph 映射是 core API（`Paragraph.comments()`）而非
   toolkit raw() 扫描——POI 脏活不下放。
3. **不设 COMMENT ref kind**：段落详情内联批注足够，省一套 ref 体系。
4. **LIFO 语义记档**：测试断言顺序时容易踩（首条预期 FIFO 是错的）。

## 测试

core 355（+4：comments 映射/活对象/round-trip/空文档）/ toolkit 244（+7：PatchEngine
comment.add 3、view 观测 3、MarkdownView 批注行 1）/ examples 20（11 条任务 + 防恒真）/
demo 47。Spotless 绿。面锁不变（full 58 / compiler 6）。
