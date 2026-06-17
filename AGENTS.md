<!-- TRELLIS:START -->
# Trellis Instructions

These instructions are for AI assistants working in this project.

This project is managed by Trellis. The working knowledge you need lives under `.trellis/`:

- `.trellis/workflow.md` — development phases, when to create tasks, skill routing
- `.trellis/spec/` — package- and layer-scoped coding guidelines (read before writing code in a given layer)
- `.trellis/workspace/` — per-developer journals and session traces
- `.trellis/tasks/` — active and archived tasks (PRDs, research, jsonl context)

If a Trellis command is available on your platform (e.g. `/trellis:finish-work`, `/trellis:continue`), prefer it over manual steps. Not every platform exposes every command.

If you're using Codex or another agent-capable tool, additional project-scoped helpers may live in:
- `.agents/skills/` — reusable Trellis skills
- `.codex/agents/` — optional custom subagents

Managed by Trellis. Edits outside this block are preserved; edits inside may be overwritten by a future `trellis update`.

<!-- TRELLIS:END -->

<!-- MANUAL:START -->
<!-- Edits outside TRELLIS block are preserved across trellis update. -->

## Developer Preferences

此项目开发者 non 偏好以下协作方式，所有 AI 助手请遵守：

### 1. 面对面对话式开发（不用子代理）

- 实现工作直接在**主对话**中完成，不交给子代理
- 除非开发者明确要求，否则不要启动 sub-agent / sub-task
- 每一步的代码编辑、讨论、决策都在当前会话中透明进行

### 2. 教学式实现（Teaching-oriented）

开发者**不熟悉 Apache POI 和 OOXML**，希望在实现过程中学习。因此：

- 每次引入新概念时先解释：OOXML 中对应的 XML 结构是什么
- 然后说明 POI 的 `XWPF*` 类型如何映射该结构
- 最后才是 nondocx 为什么用某种方式封装
- 即「OOXML 是什么 → POI 如何表达 → nondocx 为什么这样设计」三层递进

详细约定见 `.trellis/spec/guides/teaching-approach.md`。

<!-- MANUAL:END -->
