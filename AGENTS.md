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

<!-- 以下为项目自有约定，位于 Trellis 托管块之外，trellis update 不会覆盖 -->

## Trellis 子代理派发约定

本项目主会话的角色是调度者：命中下列场景时优先派发对应 Trellis 子代理，不要在主会话里亲自做大段代码搜索或实现。

| 场景 | 子代理 | 关键约束 |
|---|---|---|
| 代码搜索 / 技术调研 | `trellis-research` | 发现必须持久化到当前任务 `research/` 目录，回复只给文件路径 + 一行摘要；严禁改用内置 Explore 子代理 |
| 活跃任务（in_progress）功能实现 | `trellis-implement` | 会自行读 spec 与 prd/design/implement 工件；禁止 `git commit/push/merge`，提交一律由主会话完成 |
| 编辑后的质量检查 | `trellis-check` | 对照 `.trellis/spec/` 审查未提交 diff，发现问题自行修复（不是只报告），并跑 lint/typecheck |

派发协议：子代理 prompt 首行必须是 `Active task: <task.py current 输出的任务路径>`；无活跃任务时显式写 `Active task: none` 并补足背景。目标子代理在当前平台不可用时，用 general-purpose 兜底，并把表中对应约束原样写进 prompt。