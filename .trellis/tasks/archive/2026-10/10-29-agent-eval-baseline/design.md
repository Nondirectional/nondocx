# 设计：Agent 评测基线

## 1. 边界

### 做

- 在 `nondocx-examples` 新增 `io.github.nondirectional.docx.examples.eval` 包：
  - 10 条任务的 seed / prompt / check；
  - 被测 Agent 的 headless 组装；
  - 真实模型运行器；
  - JSON + Markdown 报告。
- 使用与 `nondocx-demo` 的 `AgentBridge` 相同的工具面（不含 Skill），
  验证 toolkit 当前形态，而不是 demo 的 SSE/Javalin 外壳。

### 不做

- 不改 `nondocx-core` / `nondocx-toolkit` / `nondocx-demo` 生产代码。
- 不新增 Maven 模块，避免进入发布范围。
- 不改 prompt / 工具 / Skill。
- 不做视觉断言和多模型矩阵。

## 2. 为什么放在 `nondocx-examples`

`nondocx-examples`：

- 已依赖 `nondocx-toolkit`（传递 core + nonchain），工具面齐全；
- 已有 Agent 示例，放 headless 评测代码语义相近；
- 不是 `nondocx-core` 的发布 API；
- 可以直接用 `mvn -pl nondocx-examples exec:java` 运行。

后续如果评测成为长期基础设施，再抽独立 `nondocx-eval` 模块。

## 3. 代码结构

```text
nondocx-examples/src/main/java/io/github/nondirectional/docx/examples/eval/
├── EvalMain.java         CLI 参数、模型/工具配置、运行、写报告
├── EvalTask.java         任务模型：id/title/prompt/seed/checks
├── EvalTasks.java        10 条任务 + 文档 fixture
├── EvalRunner.java       单任务 headless Agent 运行 + 保存 + check
├── EvalResult.java       单任务结果 / check 结果 / 工具调用记录
├── EvalAssert.java       轻量断言工具（中文失败消息）
├── JsonWriter.java       最小 JSON 序列化（Map/List/String/Number/Boolean/null）
└── ReportWriter.java     report.json + report.md + trace.jsonl
```

测试：

```text
nondocx-examples/src/test/java/io/github/nondirectional/docx/examples/eval/
├── EvalTasksTest.java    10 条、id 唯一、seed 可打开、check 在 seed 上必失败
├── JsonWriterTest.java   转义/嵌套序列化
└── EvalResultTest.java   失败类型/汇总
```

## 4. 任务模型

```java
public final class EvalTask {
  public interface Seed { void apply(Document doc); }
  public interface Check { void verify(Document doc) throws Exception; }

  private final String id;
  private final String title;
  private final String prompt;
  private final Seed seed;
  private final List<NamedCheck> checks;
}
```

- `Seed` 用 `Docx.create()` 构造初始文档并 `save(in.docx)`；
- `NamedCheck` = 名称 + `Check`；
- check 抛 `AssertionError` 表示失败，消息必须包含「期望/实际」；
- 运行器逐个执行 check，收集结果，不因单条失败中断其余 check。

## 5. 10 条任务的断言

| id | seed | check |
|---|---|---|
| `append-paragraph` | 1 段「开头段落。」 | 段数 ≥2；最后一段含「本周进度正常」 |
| `insert-centered-title` | 1 段「正文」 | 第 0 段 =「项目周汇报」；H1；CENTER |
| `replace-exact-text` | 「旧版本说明。」+「保留内容。」 | 新文本存在、旧文本不存在；第二段不变 |
| `body-font-line-spacing` | 3 段正文 | 所有非空正文段落：行距 1.5、run 字体宋体、字号 12 |
| `heading-font` | H1 + H2 + 正文 | 所有标题段落的非空 run 字体 = 黑体 |
| `create-table-header` | 1 段标题 | 存在表格；2 行 × 3 列；表头文本正确；表头 run 加粗 |
| `table-cell-edit` | 2×2 表格 A/B/C/D | 第 2 行第 2 列 =「已完成」；A 不变 |
| `tracked-replace` | 「合同金额为一百万元。」 | `trackedChanges().list()` 非空；含 INS 与 DEL；作者 = 审查员 |
| `replace-all-occurrences` | 3 段含 TODO + 1 段其他 | 正文无 TODO；「已完成」出现 ≥3 次 |
| `footer-page-number` | 正文 + 页脚「页脚」 | 页脚文本含「第」和数字（或 PAGE 域） |

### 断言细节

- 字体/字号检查遍历该段落的所有非空 run；允许额外空 run。
- 行距用 `Math.abs(lineSpacing - 1.5) < 0.01`。
- 标题检查用 `Paragraph.heading()`，不从文本猜。
- 表格检查用 `Document.tables()`，新表取最后一个。
- 修订检查用 `TrackedChanges.list()` 的 `type()` / `author()`。
- 页脚检查用 `Document.footer()`；没有 footer 直接失败。

### 为什么 seed 上 check 必须失败

这是防止「tautological test」：如果一条 check 在未修改的 seed 上也能通过，
它就没有测量到模型是否完成了任务。`EvalTasksTest` 对每条任务执行这个反向断言。

## 6. 被测 Agent 组装

```java
DocxToolkit toolkit = new DocxToolkit();
String docId = open(seed);
ToolRegistry tools = new ToolRegistry()
    .scan(new CurrentDocumentTool(docId))
    .scan(toolkit.view)
    .scan(toolkit.body)
    .scan(toolkit.table)
    .scan(toolkit.headerFooterToc)
    .scan(toolkit.trackedChangeQuery)
    .scan(toolkit.trackedChangeAuthoring)
    .registerSubAgent("review_intent", "...")
        .systemPrompt(REVIEW_PROMPT)
        .toolRegistry(new ToolRegistry().scan(toolkit.view))
        .llm(llm)
        .maxIterations(6)
        .contextSelector(...) // 注入本轮请求 + docId
        .build();
Agent agent = Agent.builder(llm, tools)
    .maxIterations(maxIterations)
    .executor(null)
    .systemPrompt(SYSTEM_PROMPT)
    .callback(tokenCallback)
    .build();
```

### system prompt

复制 `AgentBridge` 的 prompt，删除「Skill 可选」段落（本基线不加载 Skill）。
保留：

- 先 `current_document`；
- 只读/写工具选择；
- 不调用保存工具；
- 完成后 `review_intent`；
- 不要用旧 doc_id。

### 保存

`Agent.run` 结束后，不论是否 dirty，都调用：

```java
toolkit.session.saveDocx(docId, out.toString())
```

- 模拟 demo 的应用层 flush；
- 若模型没写，保存的是 seed，checks 会失败；
- 保存失败记为 `save_error`。

## 7. 运行与超时

- 每个任务独立 `DocxToolkit` / temp dir / Agent，避免串状态。
- Agent 在单线程 daemon executor 中运行；`Future.get(timeoutSeconds)`。
- 超时取消 future，记录 `timeout`；daemon 线程不阻塞 JVM 退出。
- 任务按固定顺序执行，便于日志对比。
- `--tasks` 可只跑子集，便于调试。

## 8. 模型与配置

优先级：CLI > 环境变量 > 默认值。

| 参数 | 环境变量 | 默认 |
|---|---|---|
| `--base-url` | `NONDOCX_EVAL_BASE_URL` | `http://10.100.10.21:40002/v1` |
| `--model` | `NONDOCX_EVAL_MODEL` | `qwen3.8-27b` |
| `--api-key` | `NONDOCX_EVAL_API_KEY` | 空 |
| `--max-iterations` | — | 24 |
| `--timeout-seconds` | — | 180 |
| `--thinking-budget` | — | 512 |
| `--temperature` | — | 0.0 |
| `--output-dir` | — | `target/eval` |

- VLLM 用无 key 构造或带 key 构造；
- `maxCompletionTokens(8192)`，避免单轮过长；
- 温度 0.0 提高可复现性；
- 记录 git commit（`git rev-parse HEAD`，失败则空）。

## 9. 结果与报告

### 单任务结果

```text
EvalResult {
  taskId, title, passed, failureKind,
  checks[], toolCalls[], invalidToolCalls,
  promptTokens, completionTokens, durationMs,
  finalMessage, error, outputPath
}
```

`failureKind` 优先级：

1. `timeout`
2. `agent_error`
3. `save_error`
4. `tool_error`（有失败工具调用且没有通过 check）
5. `check_failed`
6. `none`

### report.json

顶层：

- `runId`, `startedAt`, `gitCommit`, `baseUrl`, `model`, `toolCount`, `skillCount`;
- `tasks[]`；
- `summary`：通过数/失败数/通过率/平均调用数/总 token/总耗时。

### report.md

- 配置与 commit；
- 汇总表：任务、状态、调用数、失败 check、耗时；
- 失败详情：每条 check 的期望/实际；
- 工具调用序列（名称 + 成功/失败）。

### 目录

```text
target/eval/<runId>/
├── report.json
├── report.md
├── append-paragraph/
│   ├── in.docx
│   ├── out.docx
│   ├── trace.jsonl
│   └── result.json
└── ...
```

`target/` 已在 gitignore，结果不污染仓库。需要长期保留时手动拷到任务目录。

## 10. 风险与权衡

| 风险 | 处理 |
|---|---|
| 评测代码与 demo 工具面漂移 | 本任务只做基线；后续抽 `DocumentAgentFactory` 复用时再统一。报告记录工具数量，漂移可见。 |
| 单次样本噪声 | 温度 0.0 + 固定 seed；第一版只求方向，不求统计显著。 |
| 模型端点不可用 | 任务记录 `agent_error`；报告保留错误消息，不静默跳过。 |
| 超时线程泄漏 | daemon executor + 每任务新实例；超时后 `cancel(true)`。 |
| JSON 手写转义 bug | 单独 `JsonWriterTest` 覆盖引号/换行/反斜杠/嵌套。 |
| check 太严导致全部失败 | 只断言任务明确要求的属性；允许额外内容/额外段落。 |
| check 太松变成恒真 | `EvalTasksTest` 强制每条任务在 seed 上至少失败一次。 |

## 11. 验收命令

```bash
# 单测（不调模型）
mvn -q -pl nondocx-examples -am test

# 真实基线（默认端点）
mvn -q -pl nondocx-examples \
  -Dexec.mainClass=io.github.nondirectional.docx.examples.eval.EvalMain \
  exec:java

# 子集 / 指定模型
mvn -q -pl nondocx-examples \
  -Dexec.mainClass=io.github.nondirectional.docx.examples.eval.EvalMain \
  exec:java -Dexec.args="--tasks append-paragraph,replace-all-occurrences --model qwen3.8-27b"
```
