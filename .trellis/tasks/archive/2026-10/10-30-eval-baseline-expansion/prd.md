# P3 评测集扩展：baseline 12→22 条

## 背景

docs/11 P3 要求 20~30 条任务，覆盖：新建、改写、全文格式、表格、页眉页脚、修订、批注、
模板填充、**歧义指代、重复文本**。当前 baseline 12 条缺后两类与多步组合。
14B+27B 双模型对比延后（本地仅 27B，记录在 docs/11 P3 说明里）。

## 新增 10 条（全部 compiler 面可达成，无新 capabilityGap）

| id | 考点 |
|---|---|
| insert-list | 列表创作（Markdown 列表块） |
| delete-paragraph | 段落删除（按内容描述定位） |
| ambiguous-target | 歧义指代：三段中两段含「预算」，按「超支」定位唯一目标段 |
| scoped-replace | 重复文本+范围限定：三段都有「待定」，只改第二段 |
| replace-with-format | 带格式替换：末段换成加粗「结论：通过」（delete+insert 组合） |
| table-add-row | 表格加行 |
| multi-step-combo | 多步组合：一次 edit_document 批内居中+批注+文末插表 |
| fill-template-false | 模板变体：假值条件块删除、真值保留、空列表删行 |
| tracked-insert-delete | 修订变体：同段 tracked.insert+tracked.delete（同锚点独占约束，需拆两次调用） |
| line-spacing-all | 全文行距（多锚点 set_style 一批表达） |

## 设计原则（沿用现有）

- seed 代码构造；check 只读最终 docx、确定性、无 LLM-judge；
- 防恒真：每条至少一个 check 在未修改 seed 上必败（EvalTasksTest 全组强制）；
- prompt 不提示工具选择（考察工具选择本身）。

## 验收

- EvalTasksTest 22 条全绿（防恒真含新任务）；
- eval compiler 面单轮跑通 22 条，报告 pass rate / 平均调用 / invalid calls；
- 失败任务按 trace 归因（规划/寻址/工具选择/参数/能力缺失），修复后复跑；
- docs/11 P3 小节 + spec 更新；归档 + journal。

## 非目标

- 14B 对比（无模型）；页眉写任务（compiler 面无 op，已知缺口记录不进 baseline）；
- 渲染缺陷率指标（render_preview 不进 eval，维持现状）。
