# Research: eval 体系结构、计数锁、baseline 复跑与 runId 提交惯例

- Query: eval 任务定义位置与格式、EvalTasksTest 计数锁、新增任务改动面、compiler baseline 复跑命令、runId 产出位置、提交附 runId 示例
- Scope: internal
- Date: 2026-10-31

## Findings

### 目录结构

```
nondocx-examples/src/main/java/io/github/nondirectional/docx/examples/eval/
├── EvalTask.java        # 任务模型（140 行）：id/title/prompt/Seed/NamedCheck/capabilityGap
├── EvalTasks.java       # 任务集定义（1238 行）：buildTasks()=baseline 组、buildCreateTasks()=create 组
├── EvalMain.java        # CLI 入口（356 行）：参数解析、runId 生成、跑批、写报告
├── EvalRunner.java      # Agent 装配与单任务执行（613 行）：baseRegistry(surface)、save_docx 强制落盘、runChecks
├── EvalConfig.java      # 运行配置（base-url/model/api-key/surface/group/outputDir 等）
├── EvalResult.java      # 结果模型（RunReport/TaskResult/CheckResult/FailureKind/Summary）
├── EvalAssert.java      # 断言助手（contains/notContains/equal/isTrue/assertBold/assertEastAsiaFont…）
├── ReportWriter.java / JsonWriter.java  # target/eval/<runId>/report.md 与 report.json
├── SampleDocGenerator.java / SampleTocDocStructureTest / SampleTocDocStructureTest 等 fixture
nondocx-examples/src/test/java/io/github/nondirectional/docx/examples/eval/
├── EvalTasksTest.java   # 评测集质量锁（计数/防恒真/flag 清单）
├── EvalRunnerTest.java  # 工具面数量锁
├── EvalResultTest.java / JsonWriterTest.java
```

任务**不是数据文件**，全部以 Java 代码定义（无 JSON/YAML 任务文件）。

### EvalTask 任务格式（EvalTask.java）

构造器（line 72-97）：`new EvalTask(id, title, prompt, seed, checks)` 或带第 6 参 `capabilityGap`（boolean，默认 false——注意 EvalTasks.java 里的调用传的是第 4 个位置参数后的 `true`，即 checks 之后）。

- `id`：kebab-case 稳定 id（报告与 `--tasks` 过滤用）
- `title`：人可读标题
- `prompt`：给模型的自然语言请求。**硬约束（spec agent-single.md ~line 209）：「评测任务考察工具选择本身——prompt 不得提示用哪个工具」**
- `seed`：`Document -> void`，在空文档上构造种子（每任务代码构造，非二进制 fixture）
- `checks`：`List<NamedCheck>`，至少一条；直接读最终 docx（save+reopen 口径，§6.13 教训），不依赖模型自述、无 LLM-as-judge
- `capabilityGap`：仅影响报告分类列，不影响判定

组路由（EvalTasks.java）：`all()`=baseline（TASKS）、`create()`=CREATE_TASKS、`group("baseline"/"create"/null)`、`byId()`、`catalog()`（id→title，供 `--list`）。

### EvalTasksTest 的「计数锁」（nondocx-examples/src/test/java/.../eval/EvalTasksTest.java）

| 测试 | 行 | 断言 |
|---|---|---|
| `hasTwentyFourUniqueTasks` | line 24-37 | `EvalTasks.all()` **hasSize(24)**；id 全局唯一、非空；title/prompt 非空；checks 非空 |
| `hasFiveCreateTasksWithUniqueIdsAcrossGroups` | line 39-54 | `EvalTasks.create()` hasSize(5)；create 组 id 以 `create-` 开头；两组 id 不重复 |
| `groupResolvesByName` | line 56-62 | group 名路由 |
| `catalogMatchesTasks` | line 64-76 | catalog 尺寸=all+create，byId 覆盖两组 |
| `marksKnownCapabilityGapTasks` | line 78-97 | capabilityGap 任务 id **containsExactly**（顺序敏感）9 条：`footer-page-number, comment-add, fill-template, insert-list, delete-paragraph, table-add-row, multi-step-combo, fill-template-conditional-false, bold-existing-phrase`（2026-10-30 runId 20261030-125616-728 逐条 trace 确认后加入） |
| `everySeedCanBeWrittenAndReopened` | line 99-109 | seed 可生成可重开且非空 |
| `everyTaskFailsAtLeastOneCheckOnUnmodifiedSeed` | line 111-134 | 防恒真锁：每任务在未修改 seed 上至少一条 check 抛 AssertionError |

配套的工具面数量锁（EvalRunnerTest.java）：

- `exposedToolCountMatchesDemoSurfaceWithoutSkills`：full 面 = **58**（57 普通工具 + 1 只读复审 SubAgent）
- `compilerSurfaceExposesEightEntriesOnly`：compiler 面 = **8**（current_document + create/read/validate/edit_document + fill_template + render_preview + 1 复审 SubAgent）

### 新增一个 eval 任务需要改的文件

1. `EvalTasks.java`：`buildTasks()` 里 `tasks.add(new EvalTask(...))`——baseline 组当前 24 条，历史惯例是**按批次 append 到列表尾部**（最新追加样例：`tracked-replace-format`，line 857-917，§6.13 入组时 baseline 23→24）
2. `EvalTasksTest.java`：计数锁 `hasTwentyFourUniqueTasks` 的 `24` → 新数字（**方法名里的数字也要改**，如 `hasTwentyFiveUniqueTasks`）
3. 若标 `capabilityGap=true`：`marksKnownCapabilityGapTasks` 的 containsExactly 清单同步加 id（顺序=EvalTasks 定义序）
4. `docs/11-agent-authoring-architecture.md`：新增 §6.x 小节记录（**不回改既有小节**——先例：65ba23a 提交「docs/11 新增 §6.14（§6.12 不回改）」）
5. `.trellis/spec/backend/agent-single.md`：「Convention: Agent 评测基线（headless）」段（line 160-210）——baseline 条数描述（~line 162「baseline 24 条」）与结果块追加新 runId 行
6. capabilityGap 是否该标：按 §6.12 先例「**flag 只标物理不可达**」（§6.14 tracked-replace-format 走 workaround 可过→行为失败不 flag）。注意页眉写**两面皆无**（spec：「已知边界：页眉写无 op（两面）」）——compiler 面 op 落地后可过；full 面若复跑则该任务属静态缺口，按 footer-page-number 先例应 flag
7. check 设计硬约束（spec agent-single ~line 172-176）：防恒真（seed 上至少一 check 失败）+ **防退化通过**（「能力不够的面会用什么退化解满足这条断言？」——bold-existing-phrase 严格子集断言先例，EvalTasks.java:827-836）

### compiler baseline 全量复跑命令与 runId 产出

spec agent-single.md line 180 的基础命令 + `--surface`（EvalMain.java 参数）：

```bash
mvn -q -pl nondocx-examples \
  -Dexec.mainClass=io.github.nondirectional.docx.examples.eval.EvalMain \
  exec:java -Dexec.args="--surface compiler --group baseline"
```

- 历史完整先例（`.trellis/tasks/archive/2026-10/10-29-compiler-spike/spike-summary.md:35`）：`-Dexec.args="--surface compiler --group create"`；full 面加 `--timeout-seconds 240 --max-iterations 24`
- 默认值（EvalMain.java Options，line 256-269）：model `qwen3.8-27b`、base-url `http://10.100.10.21:40002/v1`（env `NONDOCX_EVAL_*` 可覆盖）、temperature 0.0、max-iterations 24、timeout 180s、thinking-budget 512、output `target/eval`
- **runId 产出**：`target/eval/<runId>/`（EvalMain.java:81-82；runId = 启动时刻 `yyyyMMdd-HHmmss-SSS`，line 27-28）。目录内：`report.md` + `report.json`（ReportWriter，EvalMain line 179-180 打印绝对路径）+ 每任务子目录 `<task.id>/`（EvalRunner.run 收到 `runDir.resolve(task.id)`，含 in/out/trace/result，见 EvalRunner.java ~line 128 javadoc）
- 报告记录 gitCommit（`git rev-parse --short HEAD`，EvalMain.java:84/209-226）——所以惯例是**先提交代码再复跑**（runId 里带 @commit，如 §6.14 的 `@8f3cff4`）

### 「提交附 runId」的既有提交示例（git log）

| commit | body 中 runId 写法 |
|---|---|
| `fbf4b17` feat(core,toolkit): 修订与页脚 op | 「评测（compiler 面 baseline，qwen3.8-27b，runId 20261030-010150-507）：10/10（上次 8/10）…均一次 edit_document 命中」 |
| `381276f` feat(core,toolkit): tracked op 行内格式 | 「eval runId 20261030-152310-495（compiler 8 工具，27B，temp 0）：24/24，平均 4.21」+ 逐行为归因 + 各模块测试数 |
| `65ba23a` docs(eval): full 面 24 任务复跑 | 「runId: full 20261030-220341-436（@8f3cff4，58 工具，qwen3.8-27b，temp 0）/ compiler 对照 20261030-152310-495」+ 逐任务调用数/判定清单 |
| `a0a5b8f` test(examples): full 面对齐复跑 | 同族写法（§6.12 数据来源） |

惯例总结：feat/docs 提交 body 内附「runId + 面口径（工具数/模型/温度）+ 通过率/平均调用数 + 关键任务行为」；多面复跑时 full 与 compiler 各自 runId 并列。

## Caveats / Not Found

- `EvalTasks.java` 类 javadoc（line 21）仍写「22 条任务」，实际 buildTasks 已 24 条——类注释过时，计数真源是 EvalTasksTest。
- eval 无独立 README；运行口径文档集中在 spec `agent-single.md`「Agent 评测基线」段与 docs/11 §6。
