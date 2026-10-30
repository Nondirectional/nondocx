# 补齐复审盲区：表格单元格/修订明细/页脚正文进 view 层

## Goal

§6.6 列出的三个开放复审盲区：view_element TABLE 加 cellTexts 矩阵预览 + CELL/REVISION 空实现补全；
RevisionSummary/Overview 加 countByAuthor 与 recent 明细（type/author/文本片段）；StatsView 加
footerText 预览。只富化既有工具输出，**工具数不变**（full 58 / compiler 6 基线不动）。

## 证据（runId `20261030-091648-612` 的复审误报）

| 盲区 | 复审原话 | 任务现状 |
|---|---|---|
| 表格单元格 | 「文本视图仅显示「A」，未出现「已完成」，无法确认目标单元格已修改」 | table-cell-edit 6 次（靠主模型自行 read_document 兜底） |
| 修订明细 | 「未见作者为「审查员」的修订记录」——outline 只有 countByType，无作者/无文本 | tracked-replace 6 次（复审全程误报未达成） |
| 页脚正文 | 「现有只读视图均未展示页脚内容」——stats 只有 hasFooter 布尔 | footer-page-number 4 次（复审「无法确认」） |

## Requirements

1. **snapshot 层**：`RevisionSummary` 增加 `countByAuthor: Map<String,Integer>` 与
   `recent`（最多 5 条：ref/type/author/snippet——TEXT 家族取 `TextChangeDetails.text()`
   截断，其他家族用家族名）；`SnapshotBuilder.buildRevision` 顺带产出（已遍历 list）。
2. **dto 层**：`RevisionOverview` 镜像新增字段 + 新 `RevisionEntry`（ref/type/author/snippet）；
   `view_outline` 输出自动带上（OutlineView 结构不变）。
3. **view_element**：
   - TABLE：`cellTexts` 矩阵预览（≤6 行，单元格文本截断；超出标 `…`），保留 rowCount/columnCount；
   - CELL：text + location（resolver 已支持 CellRef）；
   - REVISION：type/author/date/snippet（resolver 已支持 RevisionRef）。
4. **StatsView**：`footerText` 预览（截断 ~120 字符；无页脚为 null）。header 不加（无对应需求，
   深查走 HEADER_FOOTER ref 路径）。
5. **不动**：复审 prompt、工具集、review SubAgent 装配（两面共用 view 组，输出富化自动生效）。

## Acceptance Criteria

- [ ] toolkit 测试绿：cellTexts 矩阵、CELL/REVISION 详情、stats.footerText、outline 修订作者与 recent。
- [ ] eval 复跑 table-cell-edit / tracked-replace / footer-page-number：复审不再对已正确的文档误报。
- [ ] 全组复跑记录终值；工具数 58/6 不变（EvalRunnerTest 双锁不死）。
- [ ] spec 盲区清单更新（三项移出开放清单）；docs/11 §6.6 追记。
