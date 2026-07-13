# demo 消息渲染优化执行计划

## 顺序

### 1. RouterAgent 增加阶段回调（toolkit 层）

- 给 RouterAgent 加一个可选的 `PhaseCallback`（函数式接口），在每个阶段完成时回调。
- `run()` 方法在 ANALYZE/PLAN/COMMIT 完成后各调一次 callback。
- callback 传入 phase + 相关产物（snapshot/expertPlans/mergedPlan/commitResult）。
- null callback 时不回调，不影响既有测试。

验证：`rtk mvn -q -pl nondocx-toolkit -am test`

### 2. 新建 OperationDescriptor（demo 层）

- 按 kind 分发的人话描述生成器。
- 从 payload 提取 text/heading_level/alignment 等，拼成人话。
- 未知 kind 降级为 intent 或 kind 名。

验证：编译通过。

### 3. 改造 AgentBridge 分阶段推送（demo 层）

- 注入 RouterAgent 的 PhaseCallback。
- 每个阶段回调时构造 step 帧（含 turnId/phase/status/title/detail/operations）。
- operation 描述用 OperationDescriptor 生成。
- 保留 doc_changed / done 帧不变。
- FAILED 时推 failed step 帧（含 error），不 save。

验证：编译通过。

### 4. 前端进度卡渲染（app.js + style.css）

- 新增 `handleStepFrame`：按 turnId 创建/更新进度卡。
- 新增 CSS：`.progress-card` / `.step-row` / `.step-icon.spin` / `.step-ops`。
- 移除旧的 `renderPlanFrame` / `renderSummaryFrame`。
- 保留 `doc_changed` / `error` / `done` 处理不变。

### 5. 端到端验证

- 手动跑 demo，确认分步卡片逐步出现、操作描述是人话、DONE 后 OO 刷新。
- FAILED 场景确认失败步骤 + 原因展示。

验证：`mvn -q -pl nondocx-demo -am verify`

## 验证命令

```bash
rtk mvn -q -pl nondocx-toolkit -am test
mvn -q -pl nondocx-demo -am verify
mvn spotless:apply
```

## 验收前检查

- 进度卡逐步出现（分析→计划→提交）
- operation 描述是人话，无技术标识
- DONE 后 OO 刷新
- FAILED 显示失败步骤 + 原因
- 无裸统计串和无上下文单行
- spotless 通过
