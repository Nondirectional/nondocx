# 执行计划：<u> 行内标记

## 0. 准备

- 读 `prd.md` → `design.md`；已读 spec：`.trellis/spec/backend/agent-single.md`（内容语言与
  run 拆分约定）、`.trellis/spec/backend/quality-guidelines.md`。
- 不改 core、不改 demo；全部改动落在 `nondocx-toolkit` + `nondocx-examples` + docs/spec。

## 1. 数据模型（toolkit/compiler）

- [ ] `MarkdownDoc.Span`：增 `underline` 字段、`underline()` 取值、5 参构造器；
      4 参构造器委托（`underline=false`）。
- [ ] 增 `Span withUnderline()`（或等价构造方法），供解析器递归时打标。
- [ ] `Span.text(...)` 静态工厂保持不变。

## 2. 解析

- [ ] `MarkdownParser.INLINE` 追加 `<u>(.+?)</u>` 分支（不含换行、非贪婪）。
- [ ] `parseInline` token 分派增加 `<u>` 分支：对内部子串递归 `parseInline`，逐 span
      `withUnderline()` 后并入结果。
- [ ] 解析单测：基本、与粗/斜/代码组合、未闭合与孤立标签原样、空文本、中文/空格边界、
      不含标记的输入 span 序列与改动前完全一致。

## 3. 写路径

- [ ] `DocumentCompiler.fillPara`：span 应用处追加 `run.underline()`。
- [ ] `PatchEngine.applySpanFormat`：追加 `run.underline()`。
- [ ] 测试（写路径）：
  - create_document 正文/列表/表格单元格 → `isUnderline()` + save+reopen；
  - `replace_text` 单 run 子范围拆分：仅命中断带下划线，前后缀不加；
  - `replace_text` 跨 run 重建路径带 `<u>`；
  - `table.set_cell`、`header.set_text`；
  - `tracked.insert` / `tracked.replace`：reopen 后 ins 内 run `isUnderline()`。

## 4. 读侧

- [ ] `MarkdownView.renderRuns`：先包 `<u>…</u>`，再按 bold/italic 外层包裹；
      下划线 run 不再退化为纯文本。
- [ ] 测试：读视图含 `<u>`；粗体+下划线输出 `**<u>x</u>**`，读回后两属性都在。

## 5. eval

- [ ] `EvalTasks` 新增 baseline 任务（建议 id `underline-existing-phrase`，标题「段内既有词加下划线」）：
      seed 与 `bold-existing-phrase` 同构，prompt 要求给「逾期率」加下划线、其余不动；
      check 断言目标 run `isUnderline()` 且邻文未被改动（防「整段加下划线」退化通过，参照
      bold-existing-phrase 的 `anyPlain` 反例）。
- [ ] `EvalTasksTest` 计数锁 26 → 27。

## 6. 质量门禁

```bash
mvn -q spotless:apply
mvn test                      # core/toolkit/examples/demo 全绿
```

- [ ] 全量测试绿。
- [ ] 变更包：toolkit + examples。按 spec 的 Quality Check 做最后一遍全量核查。

## 7. eval 复跑

```bash
mvn -q -pl nondocx-core,nondocx-toolkit install -DskipTests
mvn -q -pl nondocx-examples \
  -Dexec.mainClass=io.github.nondirectional.docx.examples.eval.EvalMain exec:java \
  -Dexec.args="--surface compiler --group baseline"
```

- [ ] baseline 全量通过（当前 26 任务基线；本任务后 27）。失败逐条归因，不许沉默。
- [ ] runId 与逐任务数据记入 docs/11 新小节；提交信息附 runId。

## 8. 文档与 spec

- [ ] docs/07-toolkit：行内标记清单补 `<u>…</u>`（含未闭合降级、不支持同名嵌套、标题内不生效）。
- [ ] docs/11：新增 §6.x「`<u>` 下划线：内容语言最后一个缺形态」，含 eval 数据与已知边界。
- [ ] `.trellis/spec/backend/agent-single.md`：行内标记列表（line ~384 段）同步，写读同批要求。
- [ ] TODO.md：#003 状态改「📋 已立项（任务路径）」；顺带删除已完工的 #004 条目
      （`10-31-tracked-format-change` 已归档）。

## 9. 提交（主会话执行，子代理不 commit）

- 工作提交：toolkit 代码 + 测试 + eval + docs/spec，中文、`feat(toolkit,examples):` 前缀，
  提交信息附 runId。
- 之后归档任务、记 journal（由主会话按 workflow Phase 3.4/3.5 执行）。

## 回滚点

- 第 2 步后若解析契约有争议：回滚到只加字段（step 1），保留 4 参构造兼容，重新定语法。
- 第 4 步若读侧改动破坏既有读视图快照：以「round-trip 保真」为准更新快照，不回滚功能；
  若无法闭合 round-trip，撤回读侧并降级为已知缺陷上报（不允许写读不同批上线）。
