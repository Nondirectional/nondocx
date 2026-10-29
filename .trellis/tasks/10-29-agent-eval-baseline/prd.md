# Agent 评测基线

## Goal

用 10 条 docx 编辑任务建立可重复的 Agent 评测基线，量化当前 `nondocx-toolkit`
在真实模型下的成功率、工具调用数、token、延迟和失败类型。

这是架构调整前的「测量工具」，不是新的 Agent 能力。目的是把「感觉效果不佳」
拆成可复现的数据，避免靠 prompt/工具直觉改来改去。

## Requirements

### R1. 任务集

固定 10 条任务，覆盖：

1. 末尾追加一段（简单写）
2. 开头插入居中标题（结构 + 对齐）
3. 精确替换文本（文本编辑）
4. 正文宋体小四 + 1.5 倍行距（全文格式；预期暴露正文行距工具缺口）
5. 所有标题设为黑体（样式）
6. 末尾创建 2×3 表格并加粗表头（表格）
7. 修改表格第 2 行第 2 列（表格坐标）
8. 修订模式替换文本（tracked changes）
9. 替换所有 TODO（搜索 + 批量）
10. 页脚插入页码（页眉页脚；预期暴露只读能力缺口）

每条任务必须包含：

- 唯一 id、标题、自然语言 prompt；
- 可重复生成的初始 `.docx`（代码构造，不依赖二进制 fixture）；
- 一组确定性 check（读取最终 docx 断言，不用 LLM 判断）。

### R2. 被测 Agent

- 使用与 demo 相同的工具面：`current_document` + `view_*` + `body` + `table` +
  `headerFooterToc` + `trackedChangeQuery` + `trackedChangeAuthoring` + 只读
  `review_intent` 复审 SubAgent。
- 使用与 `AgentBridge` 相同的 system prompt（去掉未加载的 Skill 段）。
- 不暴露保存工具；每轮结束后由 harness 应用层保存，模拟 demo 的强制 flush。
- 第一版不加载 demo 的 6 条 Skill，以隔离工具面本身；Skill 工具名一致性由后续
  静态检查覆盖。

### R3. 可配置模型

- base URL / model / API key 可通过 CLI 或环境变量配置。
- 默认值跟随当前可用的内网 vLLM：`qwen3.8-27b`。
- 温度默认 0.0，thinking budget 可配置，保证基线尽量可复现。
- 记录实际使用的模型、base URL、git commit、工具数量。

### R4. 结果与可观测性

每次运行输出：

- `report.json`：机器可读的完整结果；
- `report.md`：人可读摘要；
- 每条任务的 `in.docx` / `out.docx` / `trace.jsonl`，便于回放失败。

每条任务记录：

- 是否通过、每条 check 的通过/失败与消息；
- 工具调用序列（名称、参数、成功/失败、耗时）；
- 无效工具调用次数；
- prompt/completion token（从 LLM callback 汇总）；
- 总耗时；
- 失败类型：`none` / `check_failed` / `agent_error` / `save_error` / `timeout` /
  `tool_error`；
- 最终 assistant 文本（截断）。

### R5. 无模型也能跑单测

- 任务注册、seed 生成、check 逻辑、JSON 序列化必须有 JUnit 测试。
- 不把真实 LLM 调用放进默认 `mvn test`；真实 baseline 通过 CLI 手动运行。

## Constraints

- 不改 `nondocx-core` / `nondocx-toolkit` 的生产行为。
- 不引入新的运行时依赖（JSON 手写序列化）。
- 代码全部中文注释/Javadoc，遵循 Google Java Format。
- 新代码放在 `nondocx-examples` 的 `eval` 包，避免新增会发布的 Maven 模块。

## Acceptance Criteria

- [x] `nondocx-examples` 中存在可运行的 `EvalMain`，支持 `--base-url`、`--model`、
      `--tasks`、`--max-iterations`、`--timeout-seconds` 等参数。
- [x] 10 条任务全部注册，id 唯一，每条 seed 可生成并重新打开。
- [x] 每条任务的 check 在「未修改的 seed」上至少失败一次，证明 check 不是恒真。
- [x] 真实模型跑完 10 条任务，生成 `report.json` + `report.md`，包含 R4 字段。
- [x] 报告中的工具数量与 demo 工具面一致（允许不含 Skill）。
- [x] 默认 `mvn -q -pl nondocx-examples -am test` 全绿。
- [x] 文档 `docs/11-agent-authoring-architecture.md` 已记录方向，并在 `docs/README.md`
      建立索引。
- [x] 基线结果写入任务目录或 `target/eval`，并在任务收尾时总结成功率与主要失败类型。

## Out of Scope

- 不改工具、不改 prompt、不修 Skill、不做编译器。
- 不做多模型矩阵、不做 pass@k、不做 LLM-as-judge。
- 不做视觉渲染断言（后续 `render_preview` 阶段再加）。
