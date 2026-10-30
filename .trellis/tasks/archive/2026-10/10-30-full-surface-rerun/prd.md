# full 面对齐观测面复跑

## Goal

compiler 面已连续五个观测/能力批次演进（§6.6 两批 view 富化、§6.7 批注观测、§6.8
fill/render、§6.10 post-condition、§6.11 行内格式），full 面的对照数字停在 §6.6 的
9/10@6.2（runId `20261030-091648-612`）。在 23 任务 baseline 上复跑 full 面（58 工具，
同模型同温度），与 compiler 23/23@4.26 对齐比较，失败逐条 trace 归因（能力缺口 vs
模型行为），诚实记录。

## Requirements

1. 跑 `--surface full --group baseline`（23 任务），报告与 trace 存档。
2. 逐失败 trace 归因：静态能力缺口（工具面物理不可达）还是行为失败（可做而做砸）。
3. check 质量：发现退化通过（能力弱的面用退化解满足字面断言）即加固 check 并双面复验。
4. 静态缺口任务按 footer-page-number 先例 flag `capabilityGap=true`，锁测试同步。

## Acceptance Criteria

- [x] runId `20261030-125616-728`：原始 15/23@11.83；bold-existing-phrase 空洞通过被
      识破（整 run 全加粗），check 加严格子集断言后 full 诚实口径 **14/23**
      （复验 runId `131105-091` 失败 / compiler `131042-172` 仍 4 次通过）。
- [x] 8 个失败逐条归因：**全部静态缺口，零行为失败**；4 任务撞 24 轮上限。
- [x] 9 条缺口任务 flag + `marksKnownCapabilityGapTasks` 锁定。
- [x] 真空单元格陷阱（poi-bridge N2）首次被 eval 踩实并回写 spec。
- [x] docs/11 §6.12、spec（agent-single 结果块 + check 防退化 + 缺口 flag 约定）同步。

## Out of Scope

- 不给 full 面补任何工具（缺口清单是结论，不是待办——compiler 面已内建同名能力）。
- 不重跑 compiler 全量（单任务复验已足够，其余 22 条不受 check 改动影响）。
