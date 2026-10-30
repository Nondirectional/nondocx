# 总结：full 面对齐观测面复跑

## 结果

| 指标 | full 面 | compiler 面 |
|---|---|---|
| 通过率 | **14/23（60.9%）**＊ | **23/23** |
| 平均调用 | **11.83**（2.8×） | **4.26** |
| 撞 24 轮上限 | 4 任务 | 0 |

＊runId `20261030-125616-728` 原始 15/23；bold-existing-phrase 空洞通过被识破后 check
加严，诚实口径 14/23（full 复验 `131105-091` 失败 / compiler `131042-172` 仍 4 次过）。

**核心结论**：8 个失败**全部是静态能力缺口，零「任务可做但模型做砸」**——full 面做
得到的 15 条它都做到了。原十任务子集 9/10、平均 7.0（旧 6.2）：观测面富化不改变
full 面 baseline（它修复的是复审误报，救不了物理不可能的任务）。

## 逐失败归因（8/8 静态缺口）

页脚写（ref 只解析正文→模型误改正文+review 循环 24 轮）、批注创作 ×2、fill_template
×2（条件块还需段落删除）、insert_paragraph 无列表参数、无段落删除工具、
**真空单元格陷阱**（add_table_row 新格 0 段落、无单元格建段工具→模型 24 次调用后
破坏性重建表头）、无 run 拆分（bold 只能整 run 加粗，27 次 bold↔unbold 振荡）。

## 三个新记录

1. **复审在不可能任务上产生修复循环**（footer trace：持续「未达成」→ 重试到上限）——
   循环本身是任务不可达的信号；能力缺口补齐前，上限截断是唯一止损。
2. **真空单元格陷阱（poi-bridge N2）首次被 eval 踩实**：spec 躺了两天的约束变成一等
   数据点，「加行」与「能写进加的行」之间隔着结构性缺口；compiler `table.add_row`
   一步内建格填文本（同任务 4 次过）。已回写 poi-bridge。
3. **check 要防退化通过**：bold-existing-phrase 初版 check 被「整段唯一 run 全加粗」
   空洞满足。修法＝严格子集断言（存在目标词以外的文字 ⇒ 必须存在非粗体 run）。
   9 条缺口任务按 footer 先例 flag `capabilityGap=true`
   （`EvalTasksTest#marksKnownCapabilityGapTasks` 锁定）。

## 交付物

- EvalTasks：bold check 严格子集断言 + 8 条新增 capabilityGap flag；EvalTasksTest 锁定更新。
- docs/11 §6.12；spec agent-single（结果块 + check 防退化约定 + 缺口 flag 约定）、
  poi-bridge N2（eval 证据）。
- 测试：examples 19 全绿（其余模块未动）。
