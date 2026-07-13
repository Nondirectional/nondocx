# 前端协商实施可视化：实施计划

1. 提取 `UiEvent` decoder、`TimelineState` reducer 与纯渲染数据模型，先添加事件序列测试。
2. 删除旧 `step/progress-card` 渲染；实现按 `turnId` 的时间线卡片与状态徽标。
3. 实现授权卡、执行取消卡、DispatchPlan 专家分派、review、提交/回滚、质量结果组件。
4. 让 trace renderer 接受主 Agent 工具 start/end、专家 prompt/response/thinking，并按活跃/历史策略展开。
5. 重写 JSONL 回放为 reducer 输入；历史事件禁用授权操作。
6. 更新 CSS 与 HTML 文案，确保状态颜色、按钮禁用与桌面布局可读。
7. 执行前端语法检查、Maven verify、浏览器手测；更新 README 截图/使用说明如有必要。

## 风险

- SSE 事件字段不统一：decoder 负责兼容，渲染层不得直接访问裸字段。
- 事件到达顺序：`trace` 可早于业务事件，reducer 需先创建占位 run。
- 回放不能重新调用 `/api/execute` 或 `/api/cancel`。
