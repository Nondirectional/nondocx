# 实施计划：comment.reply 上 compiler 面

## 前置

- 任务状态 planning；`task.py start` 后进入实施。
- 工作区干净（本任务只新增 `.trellis/tasks/10-31-comments-reply-compiler/`）。
- 不改 core；不改 compiler 面工具数量（仍 8 入口）。

## 实施清单（按序）

1. **PatchEngine：新增 `comment.reply`**
   - `Snapshot` 增加 `final Document doc` 字段（构造函数已有入参，仅保存引用）。
   - `planOp` switch 增 `case "comment.reply": return planCommentReply(index, op, snapshot);`
   - 新增 `planCommentReply`：归一化 `comment_id`（trim、去前导 `#`）、`str(op,"text")`、`author(op)`；
     preflight 查 `snapshot.doc.comments().get(id)`、拒绝 `parentId().isPresent()`；execute 调
     `doc.comments().reply(id, author, text)`，`changed.add("comment#" + id)`。
   - 类注释 op 集 v5/13 → v6/14，补条目；default 错误消息枚举加 `comment.reply`。
   - 文件：`nondocx-toolkit/src/main/java/io/github/nondirectional/docx/toolkit/compiler/PatchEngine.java`

2. **MarkdownView：id + 回复关系 + 标题修复**
   - 抽出 `renderCommentLines(sb, paragraph, seq)`；普通段落与标题段落都调用，消除标题分支的提前 `return`。
   - 行格式：`<!-- 批注 pN #id [作者]: 内容 -->`；有 parentId 时插 ` 回复#父id` 在 `#id` 之后、`[作者]` 之前。
   - 文件：`nondocx-toolkit/.../compiler/MarkdownView.java`

3. **CompilerTools：描述与示例**
   - `read_document` 描述补批注行 id/回复说明。
   - `edit_document` 描述 op 列表加 `comment.reply`；示例 JSON 增一条回复。
   - 文件：`nondocx-toolkit/.../CompilerTools.java`

4. **toolkit 测试**
   - `PatchEngineTest`：新增 reply 成功/多回复/混批/preflight（空 id、不存在、回复的回复、空 author、缺 text）/dry_run 用例。
   - `CompilerPipelineTest`：更新旧批注行断言为带 id；新增回复行 `回复#id` 断言、标题段落批注行断言、round-trip 不变。
   - 文件：`nondocx-toolkit/src/test/java/.../compiler/PatchEngineTest.java`、`CompilerPipelineTest.java`

5. **提示词**
   - eval：`EvalRunner` compiler 系统提示词加 `comment.reply` 一句。
   - demo：`AgentBridge` 同句。
   - 文件：`nondocx-examples/.../eval/EvalRunner.java`、`nondocx-demo/.../AgentBridge.java`

6. **eval 任务**
   - `EvalTasks` 增 `comment-reply`（seed 带根批注；5 类 check；`capabilityGap=true`）；类注释/`all()` 注释 27→28。
   - `EvalTasksTest`：`hasSize(28)` + 方法名；`marksKnownCapabilityGapTasks` 的 `containsExactly` 加 `comment-reply`。
   - 文件：`nondocx-examples/.../eval/EvalTasks.java`、`nondocx-examples/src/test/.../eval/EvalTasksTest.java`

7. **文档与 spec**
   - `docs/07-toolkit.md`：工具表 op 枚举、op 示例、comment.add 段落后补 `comment.reply` 说明、read_document 描述。
   - `docs/11-agent-authoring-architecture.md`：新增 `### 6.18 批注回复：一级线程的写读同批（2026-10-31）`（记录设计取舍、读格式、eval 结果）。
   - `.trellis/spec/backend/agent-single.md`：comment.add 条目后补 `comment.reply`（一级策略、读行格式、preflight 语义）。
   - `docs/03-api-reference.md`：核对 `Comments.reply`/`parentId()` 已存在，若无需改则记录「无需更新」。
   - `TODO.md`：完成后整条删除 #005（按 TODO 状态流转约定）。

8. **质量门禁**
   - `mvn -q spotless:apply`
   - `mvn test`（全量，core/toolkit/examples/demo）
   - eval（端点可用时）：`mvn -q -pl nondocx-core,nondocx-toolkit install` 后
     `nondocx-examples` 跑 `--surface compiler --group baseline` 全量 28 条；提交信息附 runId 与逐任务数据。
     端点不可用：不伪造，记录「未跑」及原因。
   - 终检：全包 spec Quality Check（见 `get_context.py --mode packages`）、grep 无旧 op 枚举/旧批注行格式残留。

## 验证命令

```bash
cd /Users/non/Projects/nondocx
mvn -q spotless:apply
mvn test
# eval（如需）
mvn -q -pl nondocx-core,nondocx-toolkit install
mvn -q -pl nondocx-examples -DskipTests package
# 具体 main 入口与参数按 examples/README 或 eval 现有用法执行
```

## 风险文件与回滚点

| 文件 | 风险 | 回滚 |
|---|---|---|
| `PatchEngine.java` | op 集/错误消息/快照改动影响所有 op | 只回滚新增 case、方法与注释行 |
| `MarkdownView.java` | 标题分支重构影响标题渲染 | 恢复标题提前 return，仅保留普通段落批注行 |
| `EvalTasks.java` / `EvalTasksTest.java` | 计数锁与 gap 清单精确匹配 | 回到 27 条与旧清单 |
| `docs/07` / `docs/11` / spec | 文档枚举遗漏 | grep `comment.add` 检查相邻段落 |

## 完成前检查（`task.py start` 前）

- [x] `prd.md` 需求/验收可测
- [x] `design.md` 边界、契约、兼容性、回滚已写
- [x] `implement.md` 有序清单与验证命令已写
- [ ] `implement.jsonl` / `check.jsonl` 真实条目
- [ ] 用户批准最终规划摘要
