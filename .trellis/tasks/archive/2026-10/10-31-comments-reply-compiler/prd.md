# Comments.reply 上 compiler 面

## Goal

用户（或 Agent）能在 compiler 面的既有批注上**回复**，并在 `read_document` 里立刻读到回复的作者、正文与线程关系——写读同批，不让复审把已正确的文档误报为「没回复」。

## Background

- core 已有完整回复能力 `Comments.reply(parentId, author, text)`，线程读侧 `Comment.parentId()` 也已落地；compiler 面只暴露了 `comment.add`（新建整段批注），回复既有批注无入口。
- `read_document` 的批注元数据行当前只有作者与内容（`<!-- 批注 pN [作者]: 内容 -->`），**没有批注 id**，模型无法寻址回复对象；也不显示回复关系。
- docs/11 §6.7 已确立「写读同批」原则：只加写侧 op 不补读侧观测，会重演 §6.6 的复审误报循环。
- eval baseline 当前 27 条；新增回复任务后为 28 条，计数锁在 `EvalTasksTest`。

## Requirements

1. **写侧 op**：`edit_document` 新增 `comment.reply`，参数 `comment_id`（read_document 暴露的批注 id）、`text`、`author` 必传；不需要段落 anchor，不改正文。
2. **preflight 语义**：批注 id 不存在时整体拒绝、零写入，错误消息带 op 序号与可用信息；仅允许回复根批注（MVP 一级回复），回复一条回复时拒绝并提示改回根批注。
3. **读侧 id**：`read_document` 每条批注元数据行携带批注 id（`#N`），使回复对象可寻址。
4. **读侧线程关系**：回复批注的元数据行标出 `回复#<父id>`，与父批注行同段相邻输出。
5. **读侧完整性**：标题段落上的批注也输出元数据行（修复现有提前 return 导致的观测盲区）。
6. **提示词**：`edit_document` 工具描述与示例、eval 系统提示词、demo compiler 面提示词都教 `comment.reply` 与 `comment_id` 来源。
7. **eval**：baseline 新增 `comment-reply` 任务（回复存在/作者正确/内容关键词/parentId 指向根批注/正文未动），并标记 full 面能力缺口。
8. **文档与 spec**：docs/07 的 op 表/示例/说明、docs/11 新小节、`agent-single.md` 同步；docs/03 因 core API 未变只做核对。

## Acceptance Criteria

- [ ] `edit_document` 的 `comment.reply` 能把回复写到指定批注：回复的 `author()`、`text()` 正确，`parentId()` 等于目标批注 id，目标批注仍是根批注（`parentId()` empty）。
- [ ] 回复不改变正文任何段落文本，也不改变原批注的作者/内容。
- [ ] 一次 patch 内多个 `comment.reply`（同一根批注或不同根批注）都能落地；任一 op 非法时整批零写入。
- [ ] `comment_id` 不存在、为空、或指向一条回复时，preflight 抛 `PatchException`，消息含 op 序号与原因；dry_run 只返回计划不写入。
- [ ] `read_document` 对根批注输出 `<!-- 批注 pN #<id> [作者]: 内容 -->`，对回复输出 `<!-- 批注 pN #<id> 回复#<父id> [作者]: 内容 -->`；标题段落的批注同样输出。
- [ ] eval baseline 变为 28 条，`comment-reply` 在 seed 上至少一条 check 失败（防恒真），在 compiler 面可通过。
- [ ] `mvn -q spotless:apply` 后全量 `mvn test` 全绿。

## Out of Scope

- 删除批注、resolve/done 状态、批注作者切换等 comment 生命周期能力。
- 多级回复（回复的回复）——MVP 明确拒绝，Word 线程由根批注 + 一级回复表达。
- full 面新增回复工具（full 面已知能力缺口，与 comment.add 同列）。
- 表格单元格内段落的批注/回复读写通道（PatchEngine 锚点与 read_document 表格渲染均不覆盖）。
- 改动 `read_document` 的批注行位置策略（仍紧跟被评段落；不做文档尾汇总）。
- core `Comments.reply` 的任何语义变更。
