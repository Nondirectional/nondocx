# Implement: 只读复审 SubAgent 实现意图达成度质检

> 对应 `prd.md` + `design.md`。有序实现清单。

## 前置:加载 spec

- 实现前用 `/trellis:before-dev` 或手读 `.trellis/spec/backend/agent-single.md`(本次会修订它)、`quality-guidelines.md`。
- 工具框架参考 nonchain-agent / nonchain-tool skill(SubAgent 注册、ContextSelector、BeforeToolCall/AfterToolCall)。

## 实现步骤(按依赖顺序)

### Step 1: 后端契约简化——DocumentTools 删除质检门控
- [ ] `DocumentTools.saveCurrentDocument`:删除 `runAllChecks` + `hasError` 门控,dirty 即落盘。
- [ ] 删除 `import QualityCheckTools.CheckResult`。
- [ ] `SaveOutcome`:`qualityReport` 字段语义改为"由 AgentBridge 复审结论填充",save 内部不再产出质检报告(传空或移除)。
- [ ] 验证:`mvn -pl nondocx-demo -am test -Dtest=DocumentToolsTest` 先红(测试待改),确认改动点。

### Step 2: 新建只读复审 SubAgent 定义
- [ ] 在 `AgentBridge` 构造器,把 `.scan(toolkit.qualityCheck)` 替换为构建 `review_intent` SubAgent:
  ```java
  ToolRegistry reviewTools = new ToolRegistry().scan(toolkit.view);  // 仅只读
  tools.registerSubAgent("review_intent", "[复审] 对比用户本轮请求与文档现状,返回达成度结论")
      .systemPrompt(REVIEW_PROMPT)
      .toolRegistry(reviewTools)
      .llm(llm)
      .maxIterations(6)
      .contextSelector(this::selectReviewContext);
  ```
- [ ] 定义 `REVIEW_PROMPT`:要求输出 `<verdict>达成|部分达成|未达成</verdict>` + `<diff>...</diff>`。
- [ ] 新增字段 `private volatile String currentTurnRequest;`,`runStream` 开头置位。
- [ ] 实现 `selectReviewContext(history, newMsg, args)`:返回 `List.of(Message.user("用户本轮修改请求:" + currentTurnRequest))`。

### Step 3: 主 Agent systemPrompt 更新
- [ ] `AgentBridge` systemPrompt:"完成修改后调用 check_quality 检查质量" → "完成修改后调用 review_intent 复审用户期望的修改是否达成"。

### Step 4: afterToolCall + 摘要适配
- [ ] `afterToolCall`:`"check_quality".equals(tool)` → `"review_intent".equals(tool)`(或按 SubAgent 工具名判定)。
- [ ] 复审结论原文挂 `Message.note("review_report", full)`(β note 隔离保留)。
- [ ] `summarizeQuality` → `summarizeReview`:解析 `<verdict>`,产"✓ 复审:达成"/"⚠ 复审:部分达成"/"✗ 复审:未达成"摘要喂主 Agent。
- [ ] `lastQualityReport` 字段保留,语义改为复审结论(供 edit_outcome 回传)。

### Step 5: isReadonly 判定
- [ ] 确认 `review_intent` 工具名判定为只读(不置 dirty)。SubAgent 调用不改文档,归只读。可能需加入 `READONLY_TOOL_EXACT` 或前缀集合。验证 `DirtyDetectionTest`。

### Step 6: onAgentComplete / edit_outcome
- [ ] `onAgentComplete`:`qualityChecked` 标志改为跟踪 `review_intent` 是否调用。
- [ ] `edit_outcome.qualityReport` 填复审结论原文。
- [ ] 删除"漏调 check_quality 兜底"注释/逻辑(无 error 可拦)。
- [ ] `deriveStatus`:复审未达成不进 rolled_back(软警告,已落盘)。

### Step 7: 前端适配
- [ ] `app.js`:`parseReviewReport(report)` 解析 `<verdict>`+`<diff>`。
- [ ] `renderReviewReport`:三态 badge + diff 文本。
- [ ] 删除/替换 `QUALITY_CHECK_LABELS`、`qualitySummary`(10项)、`renderQualityCheck`。
- [ ] 执行卡 quality 步骤文案 → "意图复审"。
- [ ] `style.css`:`.quality-stats`/`.quality-check`/`.quality-checks` → 复审 badge 样式(或复用 + 新增 verdict 态)。
- [ ] 更新 `MessageRenderingContractTest` 的前端结构断言。

### Step 8: Skill 更新
- [ ] `nondocx-demo/src/main/resources/skills/audit-quality.md`:场景从"解释版式问题清单"改为"复审用户期望修改达成度";引用 `review_intent`。
- [ ] `DemoSkills.java`:`audit-quality` description 文案更新(若需)。
- [ ] 同步 `target/classes/skills/`(构建产物,非源;构建时自动覆盖,但当前 git 脏文件里有它——确认是否纳入)。

### Step 9: spec 修订
- [ ] `.trellis/spec/backend/agent-single.md`:
  - 首段禁令句修订:区分"禁编辑/保存 SubAgent"与"允许只读复审 SubAgent"。
  - §历史:补 2026-07-17 只读复审 SubAgent 的引入与边界。
  - §Contracts:工具集说明(主 Agent 不含规则 check_quality,含 review_intent SubAgent)。
  - §Validation 矩阵:复审未达成 → status=saved 不回滚。
  - §Bad/Correct 代码示例:Wrong 改为"给复审 SubAgent 写工具";Correct 示例只读 ToolRegistry。

### Step 10: 测试更新与新增
- [ ] `DocumentToolsTest`:删 error 拒绝落盘用例;加 dirty 即落盘用例。
- [ ] `OutcomeFrameTest`:`deriveStatus` 矩阵更新。
- [ ] `DirtyDetectionTest`:`review_intent` 只读判定。
- [ ] `VllmSingleAgentIntegrationTest`:systemPrompt + 断言改 review_intent。
- [ ] `SkillAgentLinkTest`:mock 改 review_intent。
- [ ] **新增 `ReviewSubAgentTest`**:
  - 断言复审 SubAgent 的 ToolRegistry 仅含 view_*(无写/保存工具)—— spec 例外的物理保证。
  - `selectReviewContext` 注入本轮请求。
  - verdict 三态解析。

## 验证命令

```bash
# 全量编译 + 测试(toolkit 不应受影响)
mvn -q -DskipTests=false test

# 仅 demo 测试
mvn -pl nondocx-demo -am test

# toolkit 回归(确认 10 项规则能力未被波及)
mvn -pl nondocx-toolkit -am test

# 集成测试(需 VLLM 可达)
mvn -pl nondocx-demo -am test -Dtest=VllmSingleAgentIntegrationTest
```

## 风险文件 / 回滚点

- `AgentBridge.java`:核心改动,最大风险。SubAgent 注册语法、contextSelector 闭包捕获需小心(currentTurnRequest volatile + runStream 置位)。
- `DocumentTools.java`:门控删除,语义变化大。
- `.trellis/spec/backend/agent-single.md`:spec 修订要精确,避免弱化原禁令(仍禁编辑/保存 SubAgent)。
- 回滚:全部改动在 demo + spec,toolkit 零改动。`git checkout` demo 文件 + spec 即可还原。

## 完成 checklist(task.py start 前)

- [ ] prd.md / design.md / implement.md 三件齐全
- [ ] 关键不变量有测试(复审 SubAgent 仅只读工具)
- [ ] toolkit 测试不受影响(零回归)
- [ ] spec 修订不弱化原禁令边界
- [ ] 用户已 review 或批准
