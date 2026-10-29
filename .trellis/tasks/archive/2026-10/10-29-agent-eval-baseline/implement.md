# 实现计划：Agent 评测基线

## 1. 变更边界

只新增：

- `nondocx-examples/src/main/java/.../examples/eval/**`
- `nondocx-examples/src/test/java/.../examples/eval/**`
- `docs/11-agent-authoring-architecture.md`
- `docs/README.md` 索引
- 本任务目录

不动：

- `nondocx-core` / `nondocx-toolkit` / `nondocx-demo` 生产代码；
- demo 的 Skill / prompt / 工具；
- 父 POM 的模块与发布配置。

## 2. 执行顺序

### Step 1：任务模型与 10 条任务

- `EvalTask`：id/title/prompt/seed/checks；
- `EvalAssert`：中文失败消息的轻量断言；
- `EvalTasks`：10 条任务 + `Docx.create()` fixture；
- 先写 `EvalTasksTest`：
  - 10 条、id 唯一；
  - 每条 seed 可 save/open；
  - 每条 check 在未修改 seed 上至少失败一次。

验证：

```bash
mvn -q -pl nondocx-examples -am -Dtest=EvalTasksTest test
```

### Step 2：JSON 与结果模型

- `JsonWriter`：Map/List/String/Number/Boolean/null，处理转义；
- `EvalResult`：check、tool call、failure kind；
- `JsonWriterTest`、`EvalResultTest`。

验证：

```bash
mvn -q -pl nondocx-examples -am -Dtest='JsonWriterTest,EvalResultTest' test
```

### Step 3：headless Agent 运行器

- `EvalRunner`：
  - 每任务 temp dir；
  - `DocxToolkit` + `current_document` + 与 demo 相同的工具面；
  - `review_intent` 只读 SubAgent；
  - system prompt 复制 `AgentBridge`（去 Skill 段）；
  - `AgentEvent.ToolStart/ToolEnd` 记录工具调用；
  - `ChainCallback.onLlmComplete` 汇总 token；
  - 结束后应用层 save；
  - 重新打开 out.docx 跑 checks；
  - 超时、异常、保存失败分类。
- 不在单测中调用真实模型；可加一个不依赖模型的 `EvalRunner` 构造/保存测试
  （仅 seed -> save -> reopen）。

### Step 4：CLI 与报告

- `EvalMain`：参数解析、环境变量、runId、git commit、模型配置；
- `ReportWriter`：`report.json` + `report.md` + `trace.jsonl`；
- `--list` 列出任务；`--tasks` 过滤；
- 控制台实时打印每任务状态。

验证：

```bash
mvn -q -pl nondocx-examples -am -DskipTests package
mvn -q -pl nondocx-examples \
  -Dexec.mainClass=io.github.nondirectional.docx.examples.eval.EvalMain \
  exec:java -Dexec.args="--list"
```

### Step 5：真实基线

```bash
mvn -q -pl nondocx-examples \
  -Dexec.mainClass=io.github.nondirectional.docx.examples.eval.EvalMain \
  exec:java
```

- 默认端点 `http://10.100.10.21:40002/v1`，默认模型 `qwen3.8-27b`；
- 10 条任务，单任务超时 180s；
- 结果在 `target/eval/<runId>/`；
- 记录：通过率、平均工具调用、无效调用、token、延迟、失败类型。

### Step 6：收尾

- 把基线摘要写入任务目录 `baseline-summary.md`（报告本体留在 target/）；
- 更新 `docs/11` 的评测章节（如果实现与设计有偏差）；
- `mvn -q -pl nondocx-examples -am test` 全绿；
- `mvn spotless:apply`（如格式化失败）；
- 检查 `git status`，只包含预期文件。

## 3. 验证命令

```bash
# 单测
mvn -q -pl nondocx-examples -am test

# 列出任务
mvn -q -pl nondocx-examples \
  -Dexec.mainClass=io.github.nondirectional.docx.examples.eval.EvalMain \
  exec:java -Dexec.args="--list"

# 真实基线（全量）
mvn -q -pl nondocx-examples \
  -Dexec.mainClass=io.github.nondirectional.docx.examples.eval.EvalMain \
  exec:java

# 单条调试
mvn -q -pl nondocx-examples \
  -Dexec.mainClass=io.github.nondirectional.docx.examples.eval.EvalMain \
  exec:java -Dexec.args="--tasks append-paragraph"
```

## 4. 回滚点

- 所有生产代码零改动；回滚只需删除新增的 eval 包/测试/文档任务目录。
- 若真实模型端点不可用，保留单测与 CLI 骨架，基线摘要标记「未运行」。

## 5. 风险检查

- [ ] `EvalTasksTest` 是否真的在 seed 上失败（不是恒真 check）？
- [ ] `out.docx` 是否每次都被保存，即使模型没调写工具？
- [ ] 超时任务是否不会阻塞 JVM 退出？
- [ ] report.json 是否包含 model/baseUrl/gitCommit/toolCount？
- [ ] 工具调用失败是否被记录为 `tool_error`，而不是被吞掉？
- [ ] 是否误改了 toolkit/demo 生产代码？

## 6. 实现偏差记录（2026-10-29）

- 增加 `EvalTask.capabilityGap` 标记，用于把「已知能力缺口失败」和「模型乱改」分开；
  `body-font-line-spacing` 与 `footer-page-number` 标记为 true。
- `footer-page-number` 增加「正文不被误改」check：第一次运行观察到
  `replace_run_text(paragraph_index=0)` 把正文改成「第 X 页」却返回成功的静默错目标现象。
- token 记录为 0：`chain 0.11.0` 的 VLLM streaming 未返回 usage；报告加注说明，
  不在本任务升级 provider。
- 真实基线结果：8/10 通过，平均 6.8 次调用，2 条失败均为能力缺口；
  详细记录见 `baseline-summary.md`。
