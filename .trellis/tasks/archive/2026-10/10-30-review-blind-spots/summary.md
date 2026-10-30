# 复审盲区补齐 —— 任务总结

## 交付

| 位置 | 内容 |
|---|---|
| snapshot | `RevisionSummary` 增加 `countByAuthor` + `recent`（≤5 条：canonical ref/type/author/片段） |
| dto | `RevisionOverview` 镜像新字段 + 新 `RevisionEntry`；`StatsView` 增加 `footerText`（截断 120） |
| view | `view_element` TABLE 详情含 `cellTexts` 矩阵（≤6 行）；CELL/REVISION 详情实现；`view_element` 工具描述更新 |
| docs/spec | docs/11 §6.6 第二批追记；agent-single.md 盲区清单关闭 |

**只富化既有工具输出，不增工具数**——full 58 / compiler 6 基线不动。

## 结果

- 三任务复审全部从误报转为**达成 + 具体证据**：
  - table-cell-edit：「表格第 2 行第 2 列内容已为已完成」（看到矩阵）
  - tracked-replace：「del: 一百万元 / ins: 两百万元，author=审查员」（看到修订明细）
  - footer-page-number：「页脚文本为第 1 页」（看到 stats 预览）
- 终版全组（runId `20261030-093623-921`）：**10/10，平均 4.20**（4.40→4.20）。
- 测试：core 350 / toolkit 237 / examples 19 / demo 47 全绿，Spotless 绿。

## 设计要点

- 修订 `recent` 的 ref 用**完整 canonical**（resolver 签发），复审可直接 view_element 查详情，
  不是裸修订 id。
- CELL/REVISION 详情虽主要服务于已有 ref 生产者（read_table_cell / list_tracked_changes），
  但完成了 element 契约的「空实现补全」；复审的主路径是矩阵/概览直接可读。
- stats 的 footerText 恒可预览（无页脚 null）；深查仍走 HEADER_FOOTER ref 路径。
