# 总结：full 面复跑——补 tracked-replace-format 的对照数据

## 结果

| 指标 | full 面（runId `20261030-220341-436`） | compiler 面（§6.13 终版 `20261030-152310-495`） |
|---|---|---|
| 通过率 | **14/24（58.3%）** | **24/24（100%）** |
| 平均调用 | **9.88**（2.3×） | **4.21** |
| 已知缺口失败 | 9（与 §6.12 清单完全一致，零回归） | 0 |
| 行为失败 | **1（tracked-replace-format）** | 0 |
| 撞 24 轮上限（空收尾） | 2（table-add-row、bold-existing-phrase） | 0 |

## 核心归因：tracked-replace-format = eval 历史首个「可过而未过」

- 模型路径（9 次）：定位 5 次 → `delete_run_tracked target_text=待定`（外科手术式，
  位置精确）→ `insert_tracked_run bold=true`（加粗✓，**只能插段尾**——唯一败着）→
  复审判「达成」→ 收尾。唯一失败 check：最终可见文本「结论：。通过」≠「结论：通过。」。
- **workaround 链实证可过**：`update_run_style` 加粗源 run → `replace_run_tracked
  new_text=结论：通过。`（新 run 复制源 rPr）→ save+reopen 后 4 项 check 全过，锁定为
  toolkit 测试 `TrackedReplaceFormatWorkaroundTest`。
- **决策：不 flag capabilityGap**（flag 只标物理不可达；任务可达 → 行为失败）。
- 三重叠加导致无法自纠：ins 无位置参数 × ins 段内位置对复审不可见 × live 读侧 ins
  不进 text()——**可观测性缺口伪装成能力缺口**（docs/11 §6.14 新原则）。

## 其余失败：9 条缺口如期，零回归

footer-page-number（24 次误改正文+review 振荡，正文残留被误改的「页脚」）、comment-add
（5 次诚实报告无工具）、fill-template（27 次，尾部 view 连败见下）、insert-list（24 次，
`update_paragraph_list` 名字正确但 full 面未注册）、delete-paragraph（5 次 tracked del
最接近解）、table-add-row（27 次真空单元格陷阱，空收尾截断）、multi-step-combo（9 次，
居中✓建表✓批注缺口）、fill-template-conditional-false（9 次，空列表行未删）、
bold-existing-phrase（27 次 bold↔unbold 振荡，空收尾截断）。

## 顺带踩实：move 对 accept 后 live view 崩溃（TODO #009）

fill-template 尾部 view/read/search/stats 5 工具连续「工具执行失败」。scratch 复现：
`apply_tracked_changes` 按 id 应用 move 对（move_to 应用即整对完成、move_from 报找不到
id——语义可辩护）后，任何 view 抛 `XmlValueDisconnectedException`
（`Paragraph.text()` → `CTR.getDelTextArray()`，accept 后残留 CTR 包装脱连——POI 影子
包装家族）。**落盘文档完好**（reopen 可读、无残留修订），纯 live 会话缺陷。自主决策：
记 TODO #009 不就地修（eval 观测任务不夹带产品修复）。

## 交付物

- toolkit 探针测试 `TrackedReplaceFormatWorkaroundTest`（1 条，全量 272 全绿）。
- docs/11 §6.14（新增，§6.12 不回改）；spec agent-single 结果块 +full 24 任务口径。
- TODO.md：#008 完成删除、#009 新增。
- eval 产物：`target/eval/20261030-220341-436/`（report + 24 任务 trace）。
