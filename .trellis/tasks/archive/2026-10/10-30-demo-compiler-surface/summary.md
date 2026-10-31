# demo 切 compiler 面 —— 任务总结

## 交付

| 位置 | 内容 |
|---|---|
| `AgentBridge` | `buildToolRegistry(surface,…)` 静态装配（compiler = documentTools + `toolkit.compiler`；full = 原六组 scan）；`systemPromptFor` / `skillRegistryFor` / `surfaceFromRaw`；`validate_` 归只读前缀 |
| `DemoServer` | `/api/status` 增加 `surface` 字段 |
| 前端 | `index.html` surface chip + `app.js` 填充 + `style.css` 样式 |
| README | `NONDOCX_AGENT_SURFACE` 文档 + 工具面说明（数据依据） |
| spec | agent-single.md 新增「demo 默认 compiler 面」约定（配套随面切换、复审共用、validate_ 只读） |
| 测试 | `SurfaceSwitchTest` 7 条：6 入口恰等、无 full 面写工具、full 不回归、dirty 分类、prompt/Skill 随面、surface 解析 |

## 结果

- 默认面 = **compiler**（6 入口，与 eval 同构）；`NONDOCX_AGENT_SURFACE=full` 回退 58+ 细粒度面（含 6 条 Skill）。
- 冒烟：服务启动，`/api/status` → `{"surface":"compiler",...}`，chip 挂载。
- 全绿：core 349 / toolkit 232 / examples 19 / demo 47（+7）。

## 设计要点

- **Skill 不能跨面复用**：full 面 Skill 正文教 `insert_paragraph` 等工具用法，compiler 面没有这些工具——注入即误导，故 compiler 面空 `SkillRegistry`。
- **复审 SubAgent 两面共用**（仅 `toolkit.view`）——spec 的只读物理保证不变。
- **`validate_document` 只读**：质量检查不改文档，归只读前缀后纯咨询轮不再触发无谓落盘。
- 非法 env 值回退默认 compiler（demo 保持可用），记 error 日志。

## 未验证 / 后续

- 带真实 LLM key 的端到端对话未跑（本机无 DASHSCOPE_API_KEY）；装配已由测试与 eval 同构保证。
- `insert-centered-title` 记号混淆 prompt 治理（评测 26 次波动）仍是提示词层遗留。
