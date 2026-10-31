# comment.reply 上 compiler 面：现状证据

> 规划期（2026-10-31）对代码、测试、文档的就地核查。所有行号以当前工作区为准。

## 1. core 能力已完整，无需改动

- `Comments.reply(parentId, author, text)` 已实现：`nondocx-core/src/main/java/io/github/nondirectional/docx/core/api/comment/Comments.java:148`。
  - 校验 parentId/author/text；父批注不存在抛 `NoSuchElementException`。
  - 返回新建 `Comment`，`parentId()` 命中入参。
- 线程读侧：`Comment.paraId()` / `Comment.parentId()`（`Comment.java:160/175`），由 `CommentNodes.collect` 从
  `commentsExtended.xml` 两步 join 注入。
- comment→paragraph 映射：`Paragraph.comments()`（`Paragraph.java:372`），活对象语义、CTP 子树扫描。
  - 回复锚点插在父批注 `commentRangeStart` 之后（`CommentNodes.replyToComment`，
    `CommentNodes.java:512-554`），因此同段内父批注先于回复出现在 `paragraph.comments()` 中。
- core 验收测试已覆盖：`CommentsReplyThreadsTest`（reply 返回值/parentId/round-trip/多回复幂等/未命中异常）。

**结论：本任务不碰 core。**

## 2. compiler 写侧：只有 comment.add

- `PatchEngine.planCommentAdd`（`PatchEngine.java:969-986`）→ `Paragraph.addComment(author, text)`。
- op 集声明 v5 共 13 种（`PatchEngine.java:30`）；switch 在 `PatchEngine.java:182-223`；default 错误消息枚举
  可用 op（`PatchEngine.java:220-222`），新增 op 必须同步。
- `Snapshot` 只存 paragraphs/tables（`PatchEngine.java:1222-1237`），不持 `Document`。若要在 preflight 校验批注
  存在性，需要让 `Snapshot` 持有 doc（内部字段，无外部契约）。
- `comment.add` 的 `changed` 记段落锚点；footer/header 记特殊 token（`footer`/`header`），为
  `comment.reply` 记 `comment#<id>` 提供同族先例。

## 3. compiler 读侧：批注行无 id、无回复关系

- `MarkdownView.renderParagraph`（`MarkdownView.java:169-205`）：
  - 标题段落在 `:177-187` 提前 `return`，**批注行不会输出**——comment.add 在标题段落上写入后读不到（既有观测
    盲区，与本任务 read 侧同族）。
  - 普通段落批注行格式：`<!-- 批注 pN [作者]: 内容 -->`（`:196-204`），只有作者/内容，**没有 id**。
- `view_element` 段落详情 `comments` 已带 `id/author/text`（`DocumentViewServiceTest.java:560-567`），说明
  id 在 view 层可见，只是 `read_document` 行没暴露。
- 现有测试锁定旧行格式：`CompilerPipelineTest.java:274` 断言
  `<!-- 批注 p1 [审查员]: 这段需要补充依据 -->`，改格式必须同步。

## 4. 提示词与文档触点

- `edit_document` 工具描述：`CompilerTools.java:253-274`（op 列表 `:271`、示例 `:289-294`）。
- eval 系统提示词：`nondocx-examples/.../eval/EvalRunner.java:91`。
- demo 系统提示词：`nondocx-demo/.../AgentBridge.java:303`。
- 文档：`docs/07-toolkit.md:222`（工具表 op 枚举）、`:278`（op 示例）、`:326-331`（comment.add 说明）；
  `docs/11-agent-authoring-architecture.md` §6.7（`:614-638`）是「写读同批」先例，当前最后一节为 §6.17。
- `docs/03-api-reference.md:260/265` 已记录 `Comments.reply` 与 `parentId()`，core 不变则无需改。

## 5. eval 现状与计数锁

- baseline 当前 **27** 条，不是 TODO #005 里写的 24：`EvalTasks.java:23/50`，`EvalTasksTest.java:25-27`
  （`hasTwentySevenUniqueTasks` / `hasSize(27)`）。新增 `comment-reply` 后应为 **28**。
- 防恒真：`EvalTasksTest.everyTaskFailsAtLeastOneCheckOnUnmodifiedSeed`（`:114-137`）——seed 上必须至少一条
  check 失败，所以「回复存在」类 check 天然满足。
- full 面能力缺口清单是 `containsExactly` 精确匹配（`EvalTasksTest.java:79-100`），新增 gap 任务必须加入。
- comment-add 任务结构可复用作模板：`EvalTasks.java:392-448`（5 类 check + `capabilityGap=true`）。
- seed 可写批注：`Document.addParagraph(String)` 返回 `Paragraph`（`Document.java:163`），
  `Paragraph.addComment(author, text)`（`Paragraph.java:345`）。

## 6. spec 中的写读同批约定（摘录，agent-single.md 注入会被截断，故在此落一份）

- 新增「作者署名标记」类能力时，**写读必须同批落地**：只有写侧 op 没有读侧观测，复审会把已正确的文档
  误报「未达成」（§6.6 同族教训）。
- `comment.add` 的既有口径：`anchor+text+author` 必传；不改段落文本，不参与同锚点改文本独占
  （`claimAnchor` 只管 `tracked.*`/`replace_text`）。
- comment→paragraph 映射唯一公共入口是 core `Paragraph.comments()`（活对象语义）。
- 同段多条批注按 XML 出现序读取是**后写先出（LIFO）**；`addComment` 把 `commentRangeStart` 插在段首。
- `read_document` 元数据行与页眉/页脚/修订行同族：整行 HTML 注释，`MarkdownParser` 跳过，round-trip 安全。
- 新 op 必须同步：`PatchEngine` 类注释 op 集计数、default 错误消息枚举、`CompilerTools` 描述/示例、
  eval/demo 系统提示词、docs/07 + docs/11、spec——缺一处就会出现「工具会用但提示词不教」或文档漂移。

## 7. 已知语义边界

- 同段多条批注按 XML 出现序读取是「后写先出」（LIFO）——docs/11 §6.7、spec `agent-single.md` 已记。
  本任务读行逐条输出，不承诺业务顺序；回复紧跟父批注出现。
- 表格单元格内段落不在 `PatchEngine.Snapshot` 锚点范围，`read_document` 表格也只渲染纯文本——批注/回复的
  表格通道本任务不扩大。
- 标题段落批注行缺失是既有 bug，修它是「读侧观测同步」的最小必要部分（否则挂在标题上的回复不可见）。
