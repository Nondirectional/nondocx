# 复审可观测性：alignment + eastAsia 字体槽 —— 任务总结

## 交付

| 层 | 内容 |
|---|---|
| core | `Run.fontEastAsia()`——中文槽读 API（与 `font()` 对称；POI `getFontFamily` 只读 ascii 槽） |
| toolkit view | `view_element` paragraph 详情恒输出 `alignment`（left/center/right/both，与 `{align=…}` 记号同名）；run 详情 + `view_annotated`(expandRuns) 暴露 `fontEastAsia`（`AnnotatedRun` 加字段） |
| docs/spec | docs/11 §6.4/§6.5 归因修正 + §6.6 新增；agent-single.md 属性可见性教训 + 复审盲区开放清单 |

## 结果（runId `20261030-091648-612`）

| 指标 | full 面 | compiler 面（修复前当日） | compiler 面（修复后） |
|---|---|---|---|
| 通过率 | 9/10 | 10/10 | **10/10** |
| 平均调用 | 6.2 | 8.0 | **4.4** |

关键任务：insert-centered-title 26→**3**、heading-font 24→**4**、body-font 9→**5**。
compiler 面首次双指标超过 full 面。测试：core 350 / toolkit 234 / examples 19 / demo 47 全绿。

## 方法论收获（写进 spec）

1. **trace 归因必须看调用序列**，不能停留在旧假设：本次 trace 显示主模型第 2 次调用即正确、
   从未对 `{align=center}` 发起 replace_text——旧「记号混淆」归因完全错误，循环驱动者是复审。
2. **复审只能依据可见属性下结论**：「详情里没有」会被解读成「没设置」。补观测面
   （恒出现优于设置才出现）比改 prompt 有效且可测试。
3. 同族缺口一起扫：全组复跑暴露 heading-font/body-font 的字体槽盲区，与 alignment 同类同修。

## 仍开放的复审盲区（低频，已记录在 spec）

- 表格单元格内容（view_element 的 CELL 空 properties；table-cell-edit 复审靠模型自行 read_document 兜底）
- 修订记录查询（review 只扫 view 组，无 tracked-changes 工具；tracked-replace 复审误报未达成但 6 次通过）
- 页脚正文进 stats/outline（footer-page-number 复审「无法确认」但 4 次通过）
