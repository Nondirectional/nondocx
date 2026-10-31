# 技术设计：comment.reply 上 compiler 面

## 1. 边界与分层

| 层 | 是否改 | 内容 |
|---|---|---|
| core | 否 | `Comments.reply` / `Paragraph.comments()` / `Comment.parentId()` 已满足 |
| toolkit `PatchEngine` | 是 | 新增 `comment.reply` op（preflight + execute） |
| toolkit `MarkdownView` | 是 | 批注行带 id 与 `回复#父id`；标题段落也输出批注行 |
| toolkit `CompilerTools` | 是 | `edit_document` 描述/示例、`read_document` 描述 |
| examples eval | 是 | 系统提示词 + `comment-reply` 任务（27→28） |
| demo | 是 | compiler 面系统提示词 |
| docs / spec | 是 | docs/07、docs/11、`agent-single.md`；docs/03 核对 |

约束：core 公开 API 零改动；compiler 面工具数不变（仍 8 入口），只在 `edit_document` 的 op 集内扩展。

## 2. 写侧契约：`comment.reply`

```json
{"op":"comment.reply","comment_id":"3","text":"同意，请补充上季度数据。","author":"复核员"}
```

- **必填**：`comment_id`（非空，允许带前导 `#`，归一化时去掉）、`text`（必填**非空串**——实现走
  `PatchEngine.str()`，与 `comment.add` 的实际行为一致；core `Comments.reply` 本身允许空串，但工具面
  不接受空正文批注）、`author`（非空白）。
- **不需要 `anchor`**；传入也忽略（与 `footer.set_page_number`/`header.set_text` 同型，不占段落锚点）。
- **preflight**：
  1. 归一化 id：`trim` + 去掉前导 `#`；空则 `PatchException("op[i] 缺少必填字段 comment_id")`。
  2. `snapshot.doc.comments().get(id)` 命中失败 → `PatchException("op[i] 批注 #id 不存在（read_document 查看可用批注 id）")`，整批零写入。
  3. `parentId().isPresent()` → `PatchException("op[i] 批注 #id 本身是回复，MVP 只支持回复根批注（请回复其根批注）")`。
  4. 计划描述：`comment.reply #id 「text」`。
- **execute**：`doc.comments().reply(id, author, text)`；`changed.add("comment#" + id)`。返回值在此丢弃（模型要核对应走 `read_document`，与 comment.add 的 `changed` 只记锚点同姿态）。
- **dry_run**：只产出计划文本，不写文档。
- **同批多 op**：允许同一根批注多条回复、不同根批注各一条；preflight 只读文档，执行期逐条调用 core，不共享可变状态。
- **原子性**：与其他 op 一致——所有 op 先 preflight，任一失败整批零写入；执行期 core 的 `replyToComment` 不应出现可预期失败。

## 3. 读侧契约：`MarkdownView`

普通段落与标题段落统一在段落行之后输出批注行。格式：

```
<!-- 批注 p1 #3 [审查员]: 请核实该预算金额的数据来源。 -->
<!-- 批注 p1 #4 回复#3 [复核员]: 同意，请补充上季度数据。 -->
```

- `#3` = `Comment.id()`；`回复#3` 仅在 `parentId()` 存在时输出。
- 顺序：沿用 `paragraph.comments()` 的 XML 出现序；回复锚点紧贴父批注 start 之后，因此父行在前、回复行紧随（同段多条根批注仍可能 LIFO，属既有语义）。
- 文本转义沿用 `escapeText`；整行注释由 `MarkdownParser` 跳过，round-trip 不产生脏块。
- **标题修复**：当前 `renderParagraph` 对标题提前 `return`，批注行被跳过。改为「标题/普通段落各自输出正文行后，统一走批注行输出」，不改变标题正文格式。
- 读侧不新增工具、不改 `view_*` DTO（`view_element` 的 comments 已带 id；本任务不扩 parentId，避免超出 TODO 范围）。

## 4. 提示词与工具描述

- `CompilerTools.edit_document` 描述追加：`comment.reply（回复批注，comment_id+text+author 必传，comment_id 取 read_document 批注行的 #N，仅回复根批注）`；op 示例追加一条。
- `CompilerTools.read_document` 描述补一句：批注元数据行带 `#id` 与 `回复#父id`。
- `EvalRunner` 与 `AgentBridge` 的 compiler 面系统提示词各追加一句相同语义。
- `PatchEngine` 类注释 op 集 `v5，13 种` → `v6，14 种`，并补 `comment.reply` 条目；default 错误消息的可用 op 列表同步。

## 5. eval 任务 `comment-reply`

- seed：三段（背景/预算/计划），预算段由 seed 直接 `addComment("审查员", "请核实该预算金额的数据来源。")` 建根批注。
- prompt：「第二段已有一条「审查员」的批注。请以「复核员」身份回复这条批注，说明同意但要求补充上季度数据。不要修改正文。」
- checks：
  1. 存在回复（`parentId().isPresent()`）。
  2. 回复作者 = `复核员`。
  3. 回复正文含「上季度」。
  4. 回复的 `parentId()` = 审查员根批注的 id。
  5. 三段正文未变；根批注内容未变。
- `capabilityGap = true`（full 面无回复工具），并加入 `EvalTasksTest.marksKnownCapabilityGapTasks` 的 `containsExactly` 清单。
- 计数锁：`EvalTasks` 类注释/`all()` 注释 27→28，`EvalTasksTest` 方法名与 `hasSize(27)`→`hasSize(28)`。

## 6. 兼容性与风险

- **读格式变更**：`read_document` 批注行从 `<!-- 批注 pN [作者]: … -->` 变为带 `#id`。这是模型面向文本，无二进制/API 兼容问题；`CompilerPipelineTest` 的旧断言同步更新。任何依赖旧格式的下游提示词需一起改（本任务已覆盖 eval/demo 两处）。
- **标题行为变更**：标题段落现在会多出批注行；只影响「标题上确实有批注」的文档，属修复。
- **回复 id 归一化**：模型可能复制 `#3`，归一化避免一次无谓失败；不承诺接受其他前缀（如 `id=3`）。
- **一级回复限制**：若真实需求出现「回复的回复」，core 已支持，只需去掉 preflight 检查并让读侧按 parentId 展示；本任务按 TODO 推荐先收紧。
- **eval 真模型复跑依赖外部端点**：代码与单测必须全绿；compiler 面 28 条全量复跑需要可用 27B 端点，若不可用则在汇报中诚实标注「未跑」，不伪造 runId。
- **回滚**：删除 `comment.reply` case/方法、恢复 `MarkdownView` 行格式与标题提前 return、还原提示词/eval 任务/文档即可；core 无迁移、无落盘格式变更（回复本身是标准 OOXML 线程结构，core 已验收）。

## 7. 验证策略

- 单测（toolkit）：
  - reply 成功：作者/正文/parentId、根批注不变、正文不变、`changed` 含 `comment#id`。
  - 同批两条回复、reply 与 replace_text 同段共存。
  - preflight：id 为空/不存在/指向回复、author 空白、text 缺失 → `PatchException` 且零写入。
  - dry_run 不写入、计划文本正确。
  - `MarkdownView` 根批注行带 id、回复行带 `回复#id`、标题段落批注行可见、round-trip 不变。
- core：不新增测试（既有 `CommentsReplyThreadsTest` 覆盖）。
- eval：`comment-reply` seed 防恒真（`EvalTasksTest`）；真模型跑基线 28 条。
- 文档/提示词：人工核对与 grep 无遗漏旧枚举。
