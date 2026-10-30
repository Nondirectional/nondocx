# 批注创作：edit_document 增加 comment.add

## 背景

core 早已具备批注创作/读取全链路（`Paragraph.addComment(author,text)` 整段范围批注 + `doc.comments()` 正文顺序枚举），但两处断层：

1. **compiler 面（edit_document）没有批注 op**——模型无法执行「给这段加批注」类指令；
2. **view 层零批注观测**（`grep comment toolkit/view` 无命中）——按本会话确立的复审观测原则（复审只能从可见属性下结论），批注任务即使做对了，复审也会误报「未达成」。

## 目标（MVP）

1. core：`Paragraph.comments()` —— 锚定在本段的批注列表（活对象语义），为 view/eval 提供 comment→paragraph 映射（这是唯一新增 core API；POI 扫描脏活下沉 `CommentNodes`）。
2. PatchEngine：`comment.add` op（op 10→11）——`{op, anchor, text, author}`，整段范围批注，author 必填（与 tracked.* 一致）；不改段落文本，不参与同锚点改文本独占。
3. view 观测（三处，与修订观测层完全对称）：
   - `view_element` 段落详情：恒出现 `comments` 数组 `[{id,author,text}]`（空=无批注）；
   - `view_stats`：恒出现 `commentCount`（0=无）；
   - `view_outline`：`CommentOverview{total,countByAuthor,recent[{paragraph(ref),author,snippet}]}`（recent≤5，snippet≤40，与 RevisionOverview 同型）。
4. MarkdownView（compiler 面 read_document）：批注元数据行 `<!-- 批注 pN [作者]: 内容 -->`（整行注释，round-trip 安全——解析器整行注释跳过）。
5. eval：baseline 增加 `comment-add` 任务（种子 3 段、要求给指定段加批注，checks：批注存在/作者正确/内容关键词/锚定正确段/正文未动）；COMPILER_SYSTEM_PROMPT 与 demo AgentBridge compiler 面提示词教 comment.add。
6. 文档：docs/07 op 清单、docs/11 §6.7、spec agent-single.md、docs/03 core API 行。

## 非目标

- `Comments.reply`（批注回复线程）不进 compiler 面；
- 批注 resolve/删除；
- COMMENT 独立 ElementKind ref（段落详情内联足够）；
- full 面（58 工具）不加新工具——surface 锁不动。

## 验收

- core/toolkit/examples/demo 全部测试绿，Spotless 绿；
- eval baseline（含新 comment-add 任务）compiler 面全绿，comment-add 复审「达成」且引用具体证据；
- EvalRunnerTest 面锁（58/6）不变。
