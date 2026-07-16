# 实现计划：Demo 引入 nonchain 0.11.0 Skills

> 本文件只在用户确认 PRD/design 后执行。确认前保持任务 `planning`，不运行 `task.py start`，不改业务代码。

## 0. 启动门

- [ ] 用户确认 `prd.md`、`design.md`、`implement.md`。
- [ ] 执行 `rtk python3 ./.trellis/scripts/task.py start demo-skill-adoption`，状态切换为 `in_progress`。
- [ ] 重新读取 `trellis-before-dev` 注入的 backend 规范，并确认工作区已有改动不被覆盖。

## 1. 升级依赖并建立基线

- [ ] 根 `pom.xml` 将 `<chain.version>` 从 `0.10.0` 改为 `0.11.0`。
- [ ] 运行 `rtk mvn -q -DskipTests compile`，记录全 reactor 的兼容错误。
- [ ] 修复仅由 0.11 API 兼容性导致的编译问题；不引入 Demo 局部版本覆盖。

## 2. 建立 Skill 资源与注册器

- [ ] 新增 `nondocx-demo/src/main/resources/skills/inspect-document.md`。
- [ ] 新增 `edit-body.md`、`edit-table.md`、`tracked-changes.md`、`audit-quality.md`、`inspect-special-parts.md`。
- [ ] 每个正文控制在 300–600 中文字符，包含触发场景、步骤、边界和停止条件；不写完整 tool schema，不宣称不支持的写入能力。
- [ ] 新增 package-private `DemoSkills`：Java 元数据注册 + classpath UTF-8 读取 + 空/缺失 fail-fast。
- [ ] 用稳定 kebab-case 名称注册，构建后确认与普通 tool 名/保留名无冲突。

## 3. 接入 Agent

- [ ] `AgentBridge` 持有一次性 `SkillRegistry`，builder 添加 `.skillRegistry(...)` 和显式 `SkillInjectionMode.SYSTEM`。
- [ ] 基础 system prompt 说明 Skill 是可选过程知识、同一轮同一 Skill 不重复；不要求相关意图必须激活。
- [ ] 保留现有 current-document、禁止保存工具、dirty/quality/flush 和取消语义。
- [ ] 确认上传/重置只清 memory，不重建 Skill 定义；Skill 不进入 before/after tool interceptor。

## 4. 接入 SSE trace 与前端时间线

- [ ] `AgentBridge.traceEvent` 新增 `AgentEvent.SkillActivated` 分支，发 `skill_activated` trace，带 `turnId/agent/skill/description/contentLength`。
- [ ] 不发送 Skill 正文，不发 `tool_start/tool_end`，不改变 dirty 或实施状态。
- [ ] 更新 `app.js` reducer 和 trace 渲染，显示独立 `[Skill]` 行；确认实时帧和 JSONL 回放一致。
- [ ] 必要时补充最小 CSS，保持现有时间线视觉层级。
- [ ] 更新静态资源 query version，避免浏览器缓存旧 JS。

## 5. 测试

- [ ] 新增 `DemoSkillsTest`：资源加载、六条元数据、正文长度、缺失资源 fail-fast、无参数 schema。
- [ ] 新增确定性 Agent 测试：Mock LLM 点选 Skill，断言 SYSTEM 注入、tool result、`SkillActivated` 事件和多 Skill 叠加/同轮普通 tool 不互相污染。
- [ ] 新增 trace 映射测试或纯函数测试：字段完整、正文不泄漏、Skill 不标记 dirty。
- [ ] 运行 `rtk mvn -q -pl nondocx-demo -am -Dtest='!VllmSingleAgentIntegrationTest' test`。
- [ ] 运行 `rtk mvn -q test`，处理全 reactor 回归。
- [ ] 真实 DashScope smoke 仅手工运行，不作为 CI 门禁。

## 6. 文档与质量门

- [ ] 更新 `nondocx-demo/README.md`：六条 Skill、SYSTEM 注入、时间线观察、只读边界和六个示例问题；使用“可能激活”措辞。
- [ ] 运行 `rtk python3 ./.trellis/scripts/task.py validate demo-skill-adoption`。
- [ ] 运行 `rtk mvn -q spotless:check`（若仓库现有格式检查配置要求）。
- [ ] 运行 Trellis quality check，确认 PRD 验收项、跨层数据流、测试和文档一致。
- [ ] 用户验收后再进入提交/finish-work；本任务当前规划阶段不提交代码。

## 风险回滚点

- 依赖升级后先回到“只改版本”的工作树检查；不在未理解兼容错误前扩大修复范围。
- Skill 资源/注册器独立于 AgentBridge，可单独回退而保留 0.11 版本升级。
- SSE 事件和前端渲染独立于 Skill 内容，可在 UI 问题时回退展示层，不改变 Agent 行为。
