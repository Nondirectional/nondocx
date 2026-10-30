# full 面复跑：补 tracked-replace-format 的对照数据

## Goal

baseline 24 条里 tracked-replace-format 只在 compiler 面验证过（4 次）。full 面有 tracked
工具但 ins run 不可寻址，理论上存在「先 `update_run_style` 加粗源 run →
`replace_run_tracked` 继承 rPr」的 workaround 链（源码确认：`replaceRunTracked` 新 run
复制旧 run 样式；且整 run 替换 `new_text=结论：通过。` 可同时满足全部 4 项 check——
check 4 只断言最终可见文本）。在 24 任务 baseline 上全量复跑 full 面，与 compiler
24/24@4.21（runId `20261030-152310-495`）对照，tracked-replace-format 逐条 trace 归因。

## Requirements

1. 跑 `--surface full --group baseline`（24 任务），报告与 trace 存档（runId 记入文档）。
2. tracked-replace-format 逐条 trace 归因：workaround 成立则记入 docs/11；不成立则区分
   静态缺口（物理不可达）与行为失败（可做而做砸）。
3. 口径与 §6.12 一致：同模型（qwen3.8-27B）同温度（0.0）、无 Skill、默认 24 轮上限、
   check 防退化口径不变（不为本轮结果改 check）。
4. 逐失败归因表（静态缺口 vs 行为），与 §6.12 的 8 缺口清单对照更新。

## 自主决策记录（无人值守，按推荐拍板）

1. **失败时是否 flag capabilityGap**：采用推荐——trace 确认**静态缺口**才 flag
   （`EvalTasksTest#marksKnownCapabilityGapTasks` 锁定同步）；行为失败不 flag，诚实记录。
   若 workaround 链在源码层面成立但模型没走通，属行为失败，不 flag——与 §6.12
   「flag 只标物理不可达」的先例一致。
2. **报告落点**：采用推荐——docs/11 **新增 §6.14**，不回改 §6.12（历史口径保持只读）；
   spec `agent-single.md` 评测基线结果块追加一行 full 面 24 任务口径。

## Acceptance Criteria

- [x] full 面 24 任务 runId `20261030-220341-436` 产出（14/24@9.88，58 工具），
      报告与逐任务 trace 存档于 `target/eval/<runId>/`。
- [x] tracked-replace-format trace 归因明确：**行为失败（可过而未过）**——模型走
      「target_text 外科 del + 段尾 bold ins」，ins 无位置参数；整 run 替换 workaround
      链由 toolkit 探针测试 `TrackedReplaceFormatWorkaroundTest` 实证可过全部 4 项
      check → 不 flag capabilityGap（flag 只标物理不可达）。
- [x] 其余 23 条与 §6.12 的 8 缺口清单对照：9 条 flag 任务全部如期失败、归因不变，
      通过集与 §6.12 完全一致（零回归、零意外通过）。
- [x] docs/11 §6.14 + spec agent-single 结果块同步；EvalTasks 未改（flag 无变更），
      toolkit 全量 `mvn test` 272 全绿。
- [x] 提交信息附 full（`20261030-220341-436`）与 compiler（`20261030-152310-495`）
      双 runId 与逐任务数据；失败诚实报告。
- [x] 任务归档 + journal 记录；最终汇报集中列出全部自主决策点。

## 执行中新发现（自主决策：记档不修）

- **move 对 accept 后 live view 崩溃**（fill-template 尾部 5 读工具连败根因）：scratch
  复现 + 根因定位（`XmlValueDisconnectedException`，POI 影子包装家族；落盘完好）。
  按「影响最小保守方案」记 TODO #009，不在 eval 观测任务里夹带产品修复。
- 诚实止损行为方差：comment-add 24→5 次、multi-step-combo 24→9 次（单次样本，只记录
  不下结论）。

## Out of Scope

- 不给 full 面补任何工具（缺口清单是结论不是待办——compiler 面已内建同名能力）。
- 不改 tracked-replace-format 的 check（防退化口径不变；本轮是观测不是加严）。
- 不重跑 compiler 全量（§6.13 终版 24/24@4.21 即对照基准，除非 flag 变更需要复验）。
