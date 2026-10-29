# edit_document patch 引擎总结（P2 / 任务 1）

> 2026-10-30 · `qwen3.8-27b` · 数据详见 `docs/11` §6.4

## A/B 结果（baseline 组 10 条）

| 指标 | full 面（58） | compiler 面（6） |
|---|---|---|
| 通过率 | 9/10 | 8/10（2 条 = 预告缺口：修订 op、页脚 op） |
| 可做任务 | 8/8（除 footer） | **8/8** |
| 简单编辑调用 | ~6 | 追加 3 / 精确替换 4 / **批量替换 4** / 建表 5 / 改格 6 |
| 平均调用 | 6.2 | 9.6（review/validate 固定开销 + 失败重试 + insert-title 波动拉高） |

runId：`20261030-001221-487`（终版）；中间修复过程见 docs/11 §6.4。

## Trace 抓出的三个真 bug（都已修 + 测试锁死）

1. 表格缺 `|---|` 分隔行 → 原降级把管道符原文倒进段落；现 ≥2 行仍按表格解析（warning）。
2. 单元格 `**任务**` 字面星号 → 解析剥标记；`table.set_cell` text 支持行内标记（加粗 run）。
3. 字体 check 读 ascii 槽 → 改读 eastAsia 槽（对 full 面四槽同写同样成立）。

另：`set_style style=align=center` 误用时错误信息现在直接给出正确写法（独立 align 字段）。

## 结构性收益（已兑现）

- 一次调用表达批量编辑（replace-all TODO：4 次 vs 逐段搜索修改）；
- 目标容器是 op 声明的一部分（短 ID 锚点）——「合法但错误」的索引空间被移除；
- 原子性：preflight 失败零写入（`PatchEngineTest#atomicPreflightFailureLeavesDocumentUntouched`）。

## 遗留

- 修订创作 / 批注 / 图片 op（P2 下一批）——补齐后 `tracked-replace` 可过，compiler 面有望追平 9/10+；
- 页脚/页眉写 op（与 full 面共同的缺口）；
- insert-centered-title 的记号混淆波动（11~23 次）：prompt 已加说明，仍偶发；
- demo 是否切换 compiler 面：等用户拍板。
