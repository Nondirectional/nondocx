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

---

## Session 52: move 对 accept 后 live view 崩溃修复（POI 段落缓存按需重建）
<!-- trellis-session: v=2 fp=2dcb9a499b338fe9 -->

**Date**: 2026-10-30
**Task**: move-pair-accept-live-view-crash（TODO #009，无人值守）
**Branch**: `feat/paragraph-spacing-hf-refs`

### Summary

修 TODO #009：live 会话 tracked 手术后任何读抛 XmlValueDisconnectedException。调研探针实证根因在 move 创作的 moveXml 断连 POI XWPFParagraph 固化缓存（非 accept）；修法为 POI 同包桥接 XWPFParagraphCacheBridge「按需重建」（检测到断连才照 buildRunsInOrderFromXml 全分支重建，无反射），TrackedChangeNodes 五处手术点接线。按需门槛保住 §6.13 live/reopen 语义差异（TrackedAuthoringTest live 断言零改动）。check 阶段实证修正 design 前提（addDeletion 同 moveXml 断连，补接线）并连带修复 replace_run_tracked 依赖陈旧缓存索引的回归（DocxToolkitBatchTest 抓出，重构为快照解析→执行）。apply_tracked_changes 失败行补成对修订说明（计数/PARTIAL_FAILURE/异常零变化）。新增 TrackedMovePairLiveReadTest（首个正面锁定 live 崩溃）+ TrackedMovePairApplyTest（TODO #009 复现步骤）。全量 707 tests green + spotless clean。spec poi-bridge.md :510 须知升级 + 新增 N25；TODO #009 删除；docs/11 §6.14 补注记。

### Git Commits

| Hash | Message |
|------|---------|
| `e59a2ec` | fix(core,toolkit): move/accept 后 live 读崩溃根治——POI 段落缓存按需重建 |
| `bb13079` | chore(task): archive 10-30-move-pair-accept-live-view-crash |

### Status

[OK] **Completed**


## Session 53: 页眉写 op:compiler 面 header.set_text 与读视图页眉元数据行(无人值守)
<!-- trellis-session: v=2 fp=1dd0ac242b221dec -->

**Date**: 2026-10-31
**Task**: 页眉写 op:compiler 面 header.set_text 与读视图页眉元数据行(无人值守)
**Branch**: `feat/paragraph-spacing-hf-refs`

### Summary

compiler 面新增 header.set_text op(第 12 种,追加语义/不占锚点/行内标记,core 零新增)+ MarkdownView 页眉元数据行(写读同批);eval header-write-text 入组(25 条)。全量 mvn test 全绿;compiler baseline 25/25 平均 4.28(runId 20261031-021426-557);full 面 11/25 受 502 干扰但通过集与 §6.14 兼容,header-write-text 干净归因物理不可达、capabilityGap flag 成立(runId 20261031-021922-108)。§6.15 核心发现:复审误报被 read_document 元数据行自证闭环(§6.6 教训反面验证)。docs/07/11 + spec 同批更新。

### Git Commits

| Hash | Message |
|------|---------|
| `e98d9f6` | feat(toolkit,demo,examples): 页眉写 op(header.set_text)与读视图页眉元数据行 |
| `5fd458c` | docs(07,11),spec(agent-single): 页眉写 op 文档与已知边界更新 |

### Status

[OK] **Completed**


## Session 54: rPrChange:以修订方式变更既有文字格式 tracked.set_style(无人值守)
<!-- trellis-session: v=2 fp=1dd0ac242b221dec -->

**Date**: 2026-10-31
**Task**: rPrChange:以修订方式变更既有文字格式(bold/italic 起步)(无人值守)
**Branch**: `feat/paragraph-spacing-hf-refs`

### Summary

compiler 面新增第 13 种 op tracked.set_style(v5):以 rPrChange 属性修订变更既有文字 bold/italic(find 定位/三态显式/copyRun 拆 run 后两步式 commitStyleAsTracked,core 零改动,参与同锚点独占);读侧观测写读同批三层(read_document 修订元数据行/view REVISION property 摘要/outline recent[] 取证);eval tracked-bold-phrase 入组(26 条,check 含 RPR_CHANGE 存在且无 DEL 防 tracked.replace 冒充)。全量 mvn test 四模块 718 用例全绿;compiler baseline 26/26 平均 4.23(runId 20261031-092431-976 @0c5de5f),新任务首个 op 一步命中、复审直接引用 rpr_change 证据判达成——§6.16 核心发现:观测证据进复审判位,从 §6.15 的误报自证回路推进到首判即中。capabilityGap=true(full 面 mark_style_change_tracked 按 run_index 定位无 run 拆分,依 bold-existing-phrase 同根因归因,本批未跑 full 面对照)。docs/07/11 §6.16 + spec 同批更新。

无人值守自主决策(D1-D8 详见任务 prd/design):独立 op 命名 tracked.set_style;bold/italic 三态(false=取消 w:val="off");claimAnchor 参与独占;read_document 修订行覆盖全修订类型;core 零改动(summarize 方向性缺口记边界不修);gap flag 依同根因先例归因不跑 full 面;沿用当前分支滚动提交。

### Git Commits

| Hash | Message |
|------|---------|
| `0c5de5f` | feat(toolkit,demo,examples): 以修订方式变更既有文字格式的 op(tracked.set_style)与修订观测呈现 |
| `d1bff46` | docs(07,11),spec(agent-single): tracked.set_style op 文档与修订观测面呈现 |

### Status

[OK] **Completed**


## Session 55: 行内标记 <u> 下划线：内容语言补齐
<!-- trellis-session: v=2 fp=b54853374261de7a -->

**Date**: 2026-10-31
**Task**: 行内标记 <u> 下划线：内容语言补齐
**Branch**: `feat/paragraph-spacing-hf-refs`

### Summary

实现 TODO #003（收窄为 <u>）：MarkdownParser 解析 + Span.underline + 四处 span→run sink + MarkdownView 写读同批 + view_* 观测字段；eval 新增 underline-existing-phrase（baseline 27，capabilityGap）；runId 20261031-232318-163 全量 27/27@4.19，738 测试全绿；docs/07、docs/11 §6.17、spec 同步；TODO 删除已完工 #004。

### Git Commits

| Hash | Message |
|------|---------|
| `cabea1d` | feat(toolkit,demo,examples): <u> 行内下划线标记与既有词加下划线 eval 任务 |
| `655565f` | docs(07,11),spec(agent-single): <u> 行内标记文档与已知边界 |

### Status

[OK] **Completed**
