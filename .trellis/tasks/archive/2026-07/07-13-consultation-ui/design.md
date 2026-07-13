# 前端协商实施可视化：技术设计

## 架构

前端以 append-only `UiEvent` 作为唯一事实源。SSE 每帧与 JSONL 每行先经同一 `decodeTraceEvent()` 规范化，再交给 `reduceEvent(state, event)` 更新按 `turnId` 分组的 `TimelineRun`。渲染层只消费 `TimelineRun`，不得从原始 payload 临时猜状态。

```text
SSE / GET /api/trace JSONL
          ↓
 decodeTraceEvent
          ↓
 reduceEvent
          ↓
 runs[turnId] + activeRunId
          ↓
 TimelineCard renderer
```

## 状态

- `consulting`：主 Agent 普通协商与只读工具 trace。
- `awaiting_authorization`：收到 `authorization_required`，保存 token，显示唯一“开始实施”。
- `executing`：点击实施后，显示固定取消按钮与阶段时间线。
- `completed`：收到 `commit`、`doc_changed` 和 `done`。
- `blocked`：收到 `blocked`。
- `rolled_back`：收到 `rolled_back`。
- `quality_failed`：质量文本包含未通过/错误时，文档仍已提交且可刷新。

`done` 只关闭当前网络请求；终态由前一业务事件决定。

## 卡片结构

每个 `turnId` 一张 `<details class="timeline-run">`：

1. 头部：状态徽标、时间、目标摘要。
2. 授权区：只在 `awaiting_authorization` 显示；token 从 DOM/状态移除后不可复用。
3. 分派区：按工具组列出任务与专家状态。
4. review/commit/rollback/quality 区：分别渲染，不复用旧 `step` 文本。
5. trace 区：按 agent 收集；活跃卡自动展开，历史卡折叠。

## 兼容与边界

- 删除旧 `step` 卡片 reducer 与 `.progress-card` 渲染路径；后端不再发其旧阶段语义。
- JSONL 没有 `turnId` 的历史行归入 `history` 卡，不能丢弃或抛异常。
- 回放使用同一 reducer，但禁止为历史授权按钮绑定有效 token。
- 取消只发一次 `/api/cancel`；按钮立即禁用，直到 `rolled_back` / `done`。

## 验证

- 为 decoder/reducer 写浏览器可执行单测或纯函数测试：实时帧与等价 JSONL 的最终状态相同。
- 手测协商、授权、执行、取消、阻断、回滚、质量未通过和刷新回放。
- 检查无 `step`/`progress-card` 旧状态机残留。
