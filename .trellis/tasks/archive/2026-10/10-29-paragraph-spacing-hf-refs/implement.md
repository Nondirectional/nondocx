# 实现计划：正文行距 + header/footer ref 打通

## 1. 变更文件

生产代码：

- `nondocx-toolkit/src/main/java/.../BodyTools.java`
  - 新增 `update_paragraph_spacing`；
  - `read_run` 的 ref 路径允许 header/footer（只读）；
  - 写路径非正文 ref 返回清晰错误。
- `nondocx-toolkit/src/main/java/.../ref/ElementResolver.java`
  - `resolveAny(ParagraphRef)`；
  - `describe(Paragraph)` / `describe(Run)`；
  - 保持 `resolve(ParagraphRef)` body-only。
- `nondocx-toolkit/src/main/java/.../view/DocumentViewService.java`
  - paragraph/run/header_footer 的 `element` 视图补 location / 文本。
- `nondocx-toolkit/src/main/java/.../HeaderFooterTocTools.java`
  - 读取结果补 run ref。

测试：

- `nondocx-toolkit/src/test/java/.../BodySpacingAndHeaderFooterRefTest.java`
- 更新 `nondocx-examples/src/test/java/.../EvalRunnerTest.java` 工具数 57 → 58。

文档：

- `docs/07-toolkit.md`
- `docs/11-agent-authoring-architecture.md`
- 必要时 `.trellis/spec/backend/agent-single.md` 的评测基线说明。

## 2. 执行顺序

### Step 1：ElementResolver 只读位置解析

- 加 `resolveAny(ParagraphRef)`（扫描 `allParagraphs`）。
- 加 `describe(Paragraph)` / `describe(Run)`。
- 保留 `resolve(ParagraphRef)` 只扫正文。
- 单测：正文/页脚段落与 run 的位置描述正确。

### Step 2：新行距工具

- 在 `BodyTools` 加 `update_paragraph_spacing`；
- 复用 `resolveParagraphTarget`、`getStr`/`getDouble`、批量 result envelope；
- 参数：`ref`/`paragraph_index` + `line_spacing`，可选 on_error/expected_generation；
- 单测：批量、save→reopen、非法值、页脚 ref 拒绝。

### Step 3：读链路

- `DocumentViewService.element`：
  - PARAGRAPH 用 `resolveAny` + `describe`；
  - RUN 用 `describe`；
  - HEADER_FOOTER 解析 part 文本。
- `BodyTools.read_run`：有 `ref` 时允许非正文，输出位置；无 ref 仍走正文索引。
- `HeaderFooterTocTools`：`read_header_footer` 与 `_ref` 输出 run ref。

### Step 4：写工具清晰拒绝

- `resolveParagraphTarget`：body-only 失败时用 `resolveAny` 判断是否页眉/页脚/表格，
  抛 `IllegalArgumentException("... 当前写工具仅支持正文段落")`。
- `resolveRunTarget`：同样处理，错误消息含实际位置与「仅支持正文 run」。
- 单测：页脚 ref 写失败、文档不变。

### Step 5：能力契约 + 评测

```bash
mvn -q -pl nondocx-toolkit -am test
mvn -q -pl nondocx-examples -am test
mvn -q -pl nondocx-toolkit spotless:apply
```

- 重新生成 `capabilities.json`（62 个工具）。
- 复跑评测：
  - `--tasks body-font-line-spacing`
  - `--tasks footer-page-number`
- 检查前者是否通过、后者错误是否更清晰。

## 3. 验证命令

```bash
mvn -q -pl nondocx-toolkit -am -Dtest=BodySpacingAndHeaderFooterRefTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

mvn -q -pl nondocx-toolkit -am test
mvn -q -pl nondocx-examples -am test

mvn -q -pl nondocx-examples \
  -Dexec.mainClass=io.github.nondirectional.docx.examples.eval.EvalMain \
  exec:java -Dexec.args="--tasks body-font-line-spacing,footer-page-number"
```

## 4. 回滚点

- 新工具独立方法；删除方法 + capability 注解即可回滚。
- `resolveAny`/`describe` 是纯新增；写路径不改语义，只改错误消息。
- `DocumentViewService.element` / `read_run` 读分支可单独回滚。

## 5. 风险检查

- [x] 写工具是否仍然 body-only，页脚 ref 是否被拒绝且文档不变？
- [x] `resolveAny` 是否只被只读路径调用？
- [x] 行距 save→reopen 是否真的 1.5？
- [x] `capabilities.json` 是否 62 个工具且契约测试通过？
- [x] 评测工具数是否更新为 58？
- [x] 页脚 ref 读取是否不再出现 `element_removed`？

## 6. 实现偏差记录（2026-10-29）

- 除了设计中的 `view_element` / `read_run`，`read_paragraph` 也改为 `resolveAny`：
  传入页脚 ParagraphRef 时返回文本 + `位置: 页脚 ...`，而不是 `段落 -1` / element_removed。
- `view_element` 的 paragraph 属性补了 `lineSpacing`（>0 时输出），让复审能看到行距，
  不再只能从 run 字体/字号推断。
- `read_run` 的位置输出：正文 run 仍显示「段落 N run R」；页眉/页脚/表格 run 显示
  `位置: ...`，避免伪造正文索引。
- 两任务复跑：`body-font-line-spacing` 通过（6 次调用）；`footer-page-number` 仍失败
  （14 次调用）。失败过程：页脚 ref 可读，写 ref 被明确拒绝；模型随后退回正文索引
  `replace_run_text(paragraph_index=0)`，自己发现改错后又恢复正文，页脚未变。
  「正文不被误改」check 通过，但页脚任务仍失败。这证明 ref 校验不足以阻止
  「用户意图是页脚、模型选择正文索引」的静默错目标；下一步需要显式 `scope` 或 IR patch。
- 全量复跑（§7）：页脚任务 9 次调用，模型只对页脚 ref 调用写工具，均被拒绝后放弃，
  没有再误改正文。

## 7. 最终全量复跑（2026-10-29 17:49）

runId：`20261029-174952-467`（10 条任务，工具面 58）

| 指标 | v1 基线 | 修复后 |
|---|---|---|
| 通过率 | 8/10 | **9/10** |
| 平均调用 | 6.8 | 6.2 |
| `body-font-line-spacing` | ❌ 8 次，行距 -1.0 | ✅ 6 次，行距 1.5 |
| `footer-page-number` | ❌ 20 次 | ❌ 9 次，错误更清晰 |

页脚任务失败原因已收敛为「写工具仅支持正文」+「无 PAGE 域」；模型不再反复读 ref，
也不再误改正文。剩余缺口是页脚写入/PAGE 域，不在本任务范围。
完整报告：`target/eval/20261029-174952-467/report.md`。
