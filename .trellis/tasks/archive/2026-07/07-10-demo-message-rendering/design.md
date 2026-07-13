# demo 消息渲染优化设计

## 目标

把 AgentBridge 从「整轮推 2 帧」改为「分阶段推多个 step 帧」，前端把 step 帧逐步渲染为嵌入式进度卡。operation 描述由后端规则映射生成人话。

## 后端：分阶段帧协议

### 当前帧序列（一次性）

```
plan → tool_end(save) → doc_changed → summary → done
```

### 新帧序列（分阶段流式）

一轮对话用 `turnId` 关联所有帧，前端用 `turnId` 找到同一张进度卡追加更新：

```
step(analyze)     → ANALYZE 完成，推分析摘要
step(plan)        → PLAN 完成，推人话操作清单
step(commit)      → COMMIT 完成（或失败），推执行结果
doc_changed       → save 成功后刷新 OO（保留不变）
done              → 本轮结束（保留不变）
```

### step 帧结构

```json
{
  "type": "step",
  "turnId": "turn-1",
  "phase": "analyze|plan|commit",
  "status": "done|failed",
  "title": "分析文档结构",
  "detail": "4 个段落，1 个表格",
  "operations": [
    {"description": "插入 H1 标题「项目周报」，居中", "status": "pending|done|failed"}
  ],
  "error": "失败原因（仅 failed 时）"
}
```

- `turnId`：每轮对话生成一个，前端用它定位同一张卡片追加更新。
- `phase`：三个阶段，前端按 phase 渲染对应行。
- `title`：阶段的简短人话（「分析文档结构」「生成编辑计划」「执行修改」）。
- `detail`：阶段的补充信息（分析阶段给文档摘要，计划阶段省略，提交阶段给结果统计）。
- `operations[].description`：人话描述，由后端规则映射生成。
- `operations[].status`：pending（计划中）→ done（执行成功）→ failed（执行失败）。

### 后端推送时机改造

当前 `AgentBridge.runStream` 调一次 `orchestrator.plan()` 拿到完整 `RouterResult` 再推帧。要实现分阶段流式，需要让 RouterAgent 在每个阶段完成时回调 AgentBridge 推帧。

两种方案：

**方案 A：RouterAgent 增加阶段回调**——给 RouterAgent 注入一个 `Consumer<PhaseEvent>` 回调，每个阶段完成时调用。改动 toolkit 层，但 RouterAgent 本来就有清晰的状态机，加回调很自然。

**方案 B：AgentBridge 拆分调用**——AgentBridge 先调 `orchestrator.analyze()` 推 analyze 帧，再调低层 plan 推 plan 帧，再手动调 commit 推 commit 帧。不改 toolkit，但 AgentBridge 需要重复 RouterAgent 的编排逻辑。

选**方案 A**——RouterAgent 的状态机天然适合回调，且避免编排逻辑泄漏到 demo 层。

## 后端：operation 人话描述生成

新建 `OperationDescriptor`（demo 模块），按 kind 分发：

| kind | 人话模板 | 示例 |
|---|---|---|
| `insert_heading` | 插入 {heading_level} 标题「{text}」{alignment?} | 插入 H1 标题「项目周报」，居中 |
| `insert_paragraph` | 插入段落「{text}」 | 插入段落「这是新段落」 |
| `replace_run_text` | 替换文字为「{text}」 | 替换文字为「Hello」 |
| `update_run_style` | 修改样式{bold?加粗}{font_size?字号} | 修改样式：加粗，字号 16 |
| `update_paragraph_alignment` | 设置{alignment}对齐 | 设置居中对齐 |
| `replace_table_cell_run_text` | 表格单元格改为「{text}」 | 表格(0,1,2)改为「完成」 |
| `check_quality` | 质量检查 | 质量检查 |
| `apply_revision` | {action}修订 | 接受所有文本修订 |
| 其他 | {kind} | replace_run_text |

未知 kind 降级为 intent 字段或 kind 名本身。

## 前端：进度卡渲染

### HTML 结构

每轮对话创建一个 `.progress-card` 容器，内含 `.step-row` 列表：

```html
<div class="progress-card">
  <div class="step-row done">
    <span class="step-icon">✅</span>
    <span class="step-title">分析文档结构</span>
    <span class="step-detail">4 个段落，1 个表格</span>
  </div>
  <div class="step-row done">
    <span class="step-icon">✅</span>
    <span class="step-title">生成编辑计划</span>
    <div class="step-ops">
      <div class="step-op done">插入 H1 标题「项目周报」，居中</div>
    </div>
  </div>
  <div class="step-row active">
    <span class="step-icon spin">⏳</span>
    <span class="step-title">执行修改...</span>
  </div>
</div>
```

### step 帧处理逻辑

前端用 `turnId` 缓存当前卡片引用。收到 step 帧时：
1. 如果该 turnId 的卡片不存在 → 创建新卡片
2. 按 phase 找到或创建对应 `.step-row`
3. 更新该行的 status/title/detail/operations
4. 把前一行的 active 改为 done

### CSS 新增

- `.progress-card`：圆角容器，灰底，内边距
- `.step-row`：flex 行，图标 + 文字
- `.step-row.done .step-icon`：✅ 绿色
- `.step-row.active .step-icon`：⏳ 带 CSS spin 动画
- `.step-row.failed`：❌ 红色
- `.step-ops`：计划阶段的操作清单子容器，缩进
- `.step-op`：单条操作描述

## 风险

- RouterAgent 加回调改动了 toolkit 层接口——但回调是可选注入（null 时不回调），不影响既有测试。
- operation 描述映射表需要随新 kind 扩充——降级策略保证未知 kind 不崩溃。
- 前端从「多帧各渲染」改为「单卡片追加更新」，需要处理 turnId 关联和状态转换。
