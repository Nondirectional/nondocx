# 行内标记 <u> 下划线：compiler 内容语言扩展

## Goal

给 compiler 的内容语言补上「下划线」这一行内形态：`<u>文字</u>` 在 create_document 正文/列表/表格、
replace_text、header.set_text、tracked.insert/replace 中一律生效，且 read_document 能把它读回来。

## 背景

compiler 的内容语言是 Markdown 子集，`MarkdownParser.parseInline` 目前只认
`**粗**`/`*斜*`/`` `代码` ``（MarkdownParser.java:40 的 `INLINE`，MarkdownDoc.Span 只有
text/bold/italic/code）。下划线在 core 侧早已具备（`Run.underline(b)` / `isUnderline()`），
缺的只是编译器入口——模型看到 Word 文档里的下划线内容，没有任何语法能表达它。

本条来自 TODO #003；原条目的「取消加粗 / 颜色」两部分按条目自身推荐不进标记语言
（`set_style bold=false` 已覆盖整段；颜色留 `set_style` 扩展），本次只做 `<u>`。

## 范围

**做**：`<u>…</u>` 的解析、Span 承载、全部写路径应用、读侧回显、eval 任务、docs/spec 同步。

**不做**：
- `<s>` 删除线——core 没有删除线 API（`Run` 六属性无 strike，全仓无 `CTStrike` 使用），
  需要动 core + 「六样式」契约，另立条目。
- 颜色标记（同 TODO #003 推荐：留 set_style）。
- 子范围「取消加粗」语法（同 TODO #003 推荐：不进标记语言）。
- 嵌套 `<u><u>…</u></u>`（同名标签嵌套不做，见 design.md 的语法契约）。
- 标题（`#` 行）内的 `<u>`——标题编译是单 run 扁平文本，语义另行评估。

## Requirements

1. `<u>文字</u>` 在 `parseInline` 中解析为 `underline=true` 的 span；`<u>` 内可再含
   `**粗**`/`*斜*`/`` `代码` ``，下划线取并集。
2. 未闭合或孤立的 `<u>`/`</u>` 原样保留（与「未闭合标记宽容降级」一致）。
3. 所有 `<u>` 产生效果的位置与现有 `**` 完全一致：
   - `create_document` 正文段落、列表项；
   - `edit_document` 的 `replace_text`（含身份替换子范围拆分）、`table.set_cell`、`header.set_text`；
   - `tracked.insert` / `tracked.replace` 的多 run 修订路径。
   - `create_document` 的表格单元格保持纯文本通道（解析期剥标记，`**` 同样不生效），不在本条扩展范围。
4. `read_document` 的 Markdown 视图对带下划线的 run 回显 `<u>…</u>`，与 `**`/`*` 同级；
   粗体+下划线组合输出 `**<u>文字</u>**`，读回后两个属性都还原。
5. 既有纯文本路径行为不变：不含 `<u>` 的输入产出与 v1 完全相同的 span/run 结构。
6. eval baseline 新增一条任务并通过计数锁；全量 baseline 复跑无回归。

## Acceptance Criteria

- [ ] `MarkdownParserTest` 覆盖：基本 `<u>x</u>`、与粗/斜/代码组合、未闭合原样、空文本、
      中文与空格边界。
- [ ] `CompilerPipelineTest`（或同族）覆盖：create_document 正文/列表的 `<u>` 落到
      `run.isUnderline()`，save+reopen 后仍成立；表格单元格按「与 `**` 同口径」锁剥标签行为。
- [ ] `PatchEngineTest` 覆盖：`replace_text` 子范围（单 run 拆分）与跨 run 重建两条路径的
      `<u>`；`header.set_text` 的 `<u>`；`tracked.insert`/`tracked.replace` 的 `<u>`（reopen 口径）。
- [ ] 读侧测试：`MarkdownView` 输出含 `<u>…</u>`；读回 create 后下划线仍在（round-trip）。
- [ ] `EvalTasksTest` 计数锁更新，新任务在 baseline 组内且 id 唯一。
- [ ] `mvn -q spotless:apply` 后全量 `mvn test` 全绿（core/toolkit/examples/demo）。
- [ ] eval 复跑 `--surface compiler --group baseline` 全量通过，runId 记入 docs/11。

## 已确认决策（来自 TODO #003 的收敛结论）

- 语法形态：HTML 式 `<u>`，不引入新的 Markdown 强调符（避免与 `*`/`` ` `` 冲突）。
- 颜色、取消加粗不进标记语言。
- 删除线不做（本条范围外）。
