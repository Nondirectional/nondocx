# P3 评测集扩展：baseline 12→22 — 总结

## 交付

10 条新任务（全部确定性 check、防恒真由 EvalTasksTest 全组强制、prompt 不提示工具）：

| id | 考点 | 调用数 |
|---|---|---|
| insert-list | Markdown 列表块 | 3 |
| delete-paragraph | 按内容定位删除 | 4 |
| ambiguous-target | 歧义指代（两段含「预算」，按「超支」定位） | 4 |
| scoped-replace | 重复文本范围限定（三段同词只改第二段） | 4 |
| replace-with-format | 带格式替换（考察自我纠错） | 9 |
| table-add-row | 表格加行 | 4 |
| multi-step-combo | 一次批内三件事 | 5 |
| fill-template-conditional-false | 假值条件块+空列表 | 5 |
| tracked-insert-delete | 同段双修订（独占→拆分） | 6 |
| line-spacing-all | 多锚点 set_style | 4 |

## 结果

runId `20261030-113314-518`（compiler 面 8 工具，qwen3.8-27B）：
**22/22，平均 4.68**。12 条回归零退化，10 条新任务首跑全过。

## 两个记录在案的行为

1. **replace-with-format 9 次**（全组最高）：`replace_text` 填字面 `**` → 复核发现 →
   `set_style bold` 不存在（明确拒绝）→ delete+insert 收敛。暴露 compiler 面缺口：
   无「给既有文本加粗」op；模型能自愈，是否补 op 看后续数据。
2. **tracked-insert-delete 6 次**：同段双 tracked op 被独占规则整体拒绝（错误消息教
   「拆两次调用」），模型一次拆分通过——契约可教学性验证。

## 延后与边界

- 14B/27B 双模型对比延后（本地仅 27B）。
- 页眉写不进 baseline（compiler 面无 op，与 §6.4 页脚缺口同型）。

## 测试

examples 22（含防恒真全组）/ Spotless 绿；docs/11 §6.9 + P3 状态、spec 评测基线小节更新。
