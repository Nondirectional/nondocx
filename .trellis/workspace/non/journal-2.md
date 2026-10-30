# Journal - non (Part 2)

> Continuation from `journal-1.md` (archived at ~2000 lines)
> Started: 2026-10-30

---



## Session 51: full 面 24 任务复跑：tracked-replace-format 归因（首个「可过而未过」）
<!-- trellis-session: v=2 fp=2dcb9a499b338fe9 -->

**Date**: 2026-10-30
**Task**: full 面 24 任务复跑：tracked-replace-format 归因（首个「可过而未过」）
**Branch**: `feat/paragraph-spacing-hf-refs`

### Summary

full 面全量复跑 runId 20261030-220341-436：14/24@9.88（compiler 对照 24/24@4.21），9 条缺口 flag 任务如期失败零回归。tracked-replace-format 归行为失败不 flag：模型走 target_text 外科 del + 段尾 bold ins（insert_tracked_run 无位置参数），复审与 live 读侧均看不到 ins 位置错误；整 run 替换 workaround（update_run_style 加粗源 run → replace_run_tracked 继承 rPr）由新增 toolkit 探针测试 TrackedReplaceFormatWorkaroundTest 实证可过全部 check。顺带踩实 move 对 accept 后 live view 抛 XmlValueDisconnectedException（fill-template 尾部读工具连败根因，落盘完好），记 TODO #009。docs/11 新增 §6.14、spec agent-single 结果块同步、TODO #008 删除。

### Git Commits

| Hash | Message |
|------|---------|
| `65ba23a` | docs(eval): full 面 24 任务复跑——tracked-replace-format 全链首个「可过而未过」 |

### Status

[OK] **Completed**
