# TODO

> 账本：活跃待办（当下该做的）+ 休眠项（有意搁置，「出现真实需求再补」）+ 明确不做（决定已闭环，防止旧案重提）。
> 每条注明搁置原因、唤醒条件与出处；完成后从本文件划掉并照常走 journal。

## 活跃待办

- [ ] **14B vs 27B 对比**——P3 路线图最后一项（docs/11 §5 P3）。**卡在外部条件**：本地只部署了
  qwen3.8-27b，等 14B 模型到位后同口径复跑 `--surface compiler --group baseline`（24 任务），
  区分「接口问题 vs 模型能力」。
- [ ] **full 面跑 `tracked-replace-format`**——新任务（baseline 24）只在 compiler 面验证过。
  随下次 full 面复跑观察模型的 workaround 路径（推测：先 `update_run_style` 加粗源 run →
  `replace_run_tracked` 继承 rPr；ins run 本身不可寻址）。full 面 58 工具 vs compiler 8 的
  A/B 对照里它是目前唯一没有 full 面数据的任务。

## 休眠项（有意搁置，出现真实需求再补）

- [ ] **页眉写 op**——两面都没有（compiler 无 op；full 面页眉只读）。spec
  `agent-single.md` 已知边界。先例：页脚 PAGE 域（docs/11 §6.5）当年也是等真实需求出现
  才补的 `footer.set_page_number`。**唤醒条件**：出现真实的页眉编辑需求（eval 任务或用户请求）。
- [ ] **`Comments.reply` 上 compiler 面**——core 有 API，compiler 面只暴露了 `comment.add`
  整段批注；回复批注暂无入口。**唤醒条件**：出现「回复批注」类真实需求，届时按
  §6.7 的「写读同批落地」原则一并补读侧观测。
- [ ] **rPrChange：格式变更本身算一条修订**——「给既有文字加粗并留下修订痕迹」的严格形态。
  full 面已有 `mark_style_change_tracked`；compiler 面明确不做（`10-30-tracked-inline-format`
  Out of Scope——行内格式的 ins 版已覆盖「新插入内容带格式」，对既有文字的格式修订是另一种
  语义）。**唤醒条件**：明确的「以修订方式改变既有文字格式」需求。
- [ ] **unbold / 下划线 / 颜色 行内标记**——`parseInline` 只支持 `**粗**`/`*斜*`/`` `代码` ``；
  取消加粗无法用正标记表达（需要 `**` 之外的语法或显式布尔）。§6.11 Out of Scope。
  **唤醒条件**：eval/用户出现「去掉加粗」类请求且现路径（set_style bold=false 整段）不够用。
- [ ] **chain 0.11.0 vLLM streaming 不返回 usage**——eval 报告 token 恒为 0（spec
  `agent-single.md` 已知限制）。**唤醒条件**：chain 升级 provider 后自动恢复，无需改代码；
  恢复后首个 runId 在 docs/11 记一笔即可。
- [ ] **demo 端到端 LLM 联调**——本地无 `DASHSCOPE_API_KEY`，demo 的 LLM 轮次未跑过真模型；
  工具面/提示词/保存链路已由 47 个 demo 测试验证。**唤醒条件**：拿到 key 后跑一轮
  `/api/chat` 冒烟（建表 + 修订 + 模板填充各一条），重点看 SSE `edit_outcome` 帧与
  `doc_changed` 时序。

## 明确不做（决定已闭环，勿重开）

- **不给 full 面补页脚/批注/列表/段落删除/真空单元格/模板/run 拆分工具**——8 个静态缺口
  全部由 compiler 面内建能力覆盖，A/B 数据已闭环（docs/11 §6.12：14/23 vs 23/23，
  失败 100% 是缺口而非模型）。
- **不把 61 个工具原样包成 MCP / 不恢复 SubAgent 委派层**——docs/11 §7 与 spec
  `agent-single.md` 的结构性决定。
- **不改 live `text()` 语义去「包含」ins 文本**——会破坏 tracked 手术路径的 runs/text
  映射校验；live/reopen 差异已写进 javadoc 与 docs/07，按口径断言即可。
