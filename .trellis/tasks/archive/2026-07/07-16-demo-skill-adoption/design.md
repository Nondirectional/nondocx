# 技术设计：Demo 引入 nonchain 0.11.0 Skills

## 1. 边界与目标

本任务只改 `nondocx-demo` 的 Agent 装配、Skill 正文、SSE/前端 trace、测试和文档，外加根 POM 的 nonchain 版本。
`nondocx-toolkit` 继续只提供 docx 工具；Skill 属于 Demo 应用层的过程知识。

运行时保持当前单 Agent 拓扑：

```text
用户请求
  -> Agent(普通 tools + SkillRegistry)
  -> LLM 自主选择 0..N 条 Skill
  -> Skill 独立注入 SYSTEM 消息
  -> Agent 继续调用 docx tools
  -> Complete 时应用层质检/flush
  -> SSE trace + OnlyOffice 刷新
```

## 2. Skill 装配

新增包内组件（建议命名 `DemoSkills`，package-private final class）：

- `create()` 创建 `SkillRegistry`，按固定顺序注册 6 条 Skill 的 name/description。
- `load(String resource)` 从 classpath `/skills/<name>.md` 读取 UTF-8 正文。
- 资源不存在、读取失败或 trim 后为空时抛出 `IllegalStateException`，使 Demo 在启动时快速暴露打包问题。
- 资源正文不解析 front matter；Skill 元数据仍由 Java 显式声明，避免引入额外 Markdown 解析依赖。

`AgentBridge` 构造时创建一次 registry，并在 Agent builder 中显式配置：

```java
.skillRegistry(DemoSkills.create())
.skillInjectionMode(SkillInjectionMode.SYSTEM)
```

DashScope 0.11 provider 默认声明支持多条 system；请求仍经过 nonchain 的
`prepareMessages(...)` 兼容层，未来替换为不支持多 system 的 provider 时可在 provider 侧降级，不改变 Demo transcript。

资源内容按“触发场景、操作顺序、边界、停止/报告条件”组织。不得把 `save_docx`、`open_docx` 等已被 Demo 禁止的工具重新写入 Skill。
基础 system prompt 增加两条非路由性约束：Skill 是可选过程知识；同一轮同一 Skill 不重复点选。不得写“命中某类请求必须先调用某 Skill”。

## 3. 六条 Skill 的职责边界

| Skill | 负责 | 不负责 |
|---|---|---|
| `inspect-document` | 结构/内容/定位的只读调查，输出事实与引用 | 主动修改、保存 |
| `edit-body` | 正文段落、run、样式、超链接的编辑流程 | 表格内部与修订语义 |
| `edit-table` | 表格结构、单元格、合并和表格相关编辑 | 页眉页脚写入 |
| `tracked-changes` | 修订查询、接受/拒绝、修订创作 | 把修订当普通文本直接覆盖 |
| `audit-quality` | 运行/解释 `check_quality`；用户要求时修复可修项并复检 | 无授权自动改版式；声称修复不可修项 |
| `inspect-special-parts` | 读取页眉、页脚、目录并报告事实 | 写入页眉、页脚、目录 |

Skill 间允许组合。例如“修复表格跨页问题”可同时激活 `edit-table` 和 `audit-quality`；模型不匹配时可只激活其中一条或不激活。

## 4. SSE 事件契约

### 4.1 后端映射

当前 `AgentBridge.traceEvent(...)` 对未知事件直接 return。新增分支：

```text
AgentEvent.SkillActivated
  -> {
       type: "trace",
       turnId,
       agent: "DocumentAgent",
       event: "skill_activated",
       skill: "edit-table",
       description: "...",
       contentLength: 428
     }
```

description 从 `SkillRegistry.get(skillName)` 读取；content 正文不进入 SSE 或 JSONL，避免把过程知识重复广播、扩大日志和暴露内部指令。

Skill 不发送 `tool_start`/`tool_end`，不经过 before/after interceptor；因此不会被 `isReadonly` 判定为写工具，也不会把本轮标记 dirty。

### 4.2 前端回放

`reduceTimelineEvent` 继续把 trace 归档到当前 run；`renderTimelineTrace` 对 `skill_activated` 使用独立格式：

```text
[Skill] edit-table
<description>
注入 428 字符
```

该行只属于 trace，不改变 run 的 `consulting/executing/completed` 状态。实时 SSE 与 `/api/trace` 回放共用同一 reducer。更新 `app.js` 的静态资源 query version，避免开发期缓存旧脚本。

## 5. 记忆与上传/重置语义

`SYSTEM` Skill 注入进入当前 Agent transcript，并由现有 `MessageWindowChatMemory(maxMessages=24)` 按 nonchain 规则保留/裁剪；正文限制在 300–600 字以控制上下文预算。

上传/重置时继续调用现有 `clearMemory()`：历史 Skill 注入随文档上下文清空，registry 定义本身保持不变。前端不维护“当前激活 Skill”全局状态，只展示发生过的激活事件，避免把窗口内的持久性误报成永久激活。

## 6. 测试设计

不依赖远程模型的测试使用 nonchain `AgentSkillTest` 同款可编程 Mock LLM：

1. `DemoSkillsTest`：6 个名称/description/资源均存在，正文非空且在约定长度，资源缺失时 fail-fast。
2. `DemoSkillsAgentTest`：Mock LLM 第一轮返回某 Skill 无参数 tool call，第二轮返回最终文本；断言工具 schema 带 `[Skill]`、第二轮含 tool result + SYSTEM 注入、事件含 `SkillActivated`。
3. `AgentBridge` trace 映射测试：断言 Skill 事件字段、无正文、不会产生普通 tool event；如直接测试 Javalin Context 成本过高，则把映射抽成 package-private 纯函数后测试。
4. 现有 Demo 单测与非远程 Maven 测试保持通过。

真实 DashScope smoke 只用于人工观察：选择 README 示例问题，查看 Skill 事件、普通工具事件、质量结果和保存刷新；不对“某模型必然激活某 Skill”做自动断言。

## 7. 兼容、风险与回滚

- 版本风险：根 POM 从 0.10.0 升到 0.11.0 可能暴露其它模块的兼容问题；先全 reactor compile/test，再处理真实编译错误，不做 Demo 局部版本覆盖。
- 路由风险：LLM 可不点 Skill；README 用“可能激活”，测试只验证框架链路，不伪造自主选择结果。
- 上下文风险：多 Skill 内容累加会占用窗口；资源长度上限、同轮去重提示和现有 24 条窗口共同控制。
- 兼容风险：`SYSTEM` 注入依赖 provider 的多 system 能力；nonchain 0.11 的请求副本归一化负责不支持模型的降级。
- 可观测性风险：Skill 不走 tool interceptor，必须单独处理 `SkillActivated`，否则 UI/日志静默丢失。
- 回滚点：若 0.11 升级导致非 Demo 模块失败，保留版本变更及错误记录，回滚前不提交业务代码；若前端事件有问题，可独立回退 SSE 映射/渲染而不移除 Skill 注册。
