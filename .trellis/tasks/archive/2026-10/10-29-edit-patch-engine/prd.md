# PRD：edit_document patch 引擎（P2）

> 路线图：`docs/11` §3.3 + §5 P2「编辑与模板」的第一块。
> 前置：任务 B（编译器 spike）已验证 compiler 面「新建文档」5/5、5.6 次调用；
> 本任务把同一套短 ID / Markdown 表示延伸到「编辑既有文档」。

## 背景与假设

baseline 组（10 条编辑任务）目前只能在 full 面跑：9/10、平均 6.2 次调用；且
「多步编辑」仍然要模型逐个调 insert/replace/style 工具。假设：把编辑收敛为
**一个原子 patch 工具**（ops 数组 + 短 ID 锚点 + dry_run），可以：

1. 进一步降低调用数（一次 edit_document 表达一批编辑）；
2. 从根上治「索引不是身份」类错目标——op 的目标容器（p/t 短 ID）是声明的一部分；
3. 让 compiler 面覆盖「编辑」场景，成为完整的第二工具面。

## 交付物

1. **`PatchEngine`**（`compiler` 包新类）：
   - 6 种 op：`insert`（before/after/文档首尾 + Markdown 块）、`replace_text`（段落内
     find→text，跨 run 时重建为单 run 并记 warning）、`delete`（段落/表格）、
     `set_style`（套 profile 的 body/heading 样式或显式 align/line_spacing）、
     `table.set_cell`、`table.add_row`；
   - 锚点：`p0..pN`（正文段落序）/ `t0..tN`（表格序），与 `read_document` 输出一致；
   - **原子**：先 preflight 全部锚点与参数，任一失败则整体拒绝、零写入；
   - `dry_run`：返回将发生的变更清单，不写入；
   - `expected_generation`：与既有写安全协议一致。
2. **`DocumentCompiler` 重构**：块编译下沉为 `compileBlockInto(Document, Block, profile)`
   （insert op 复用；create_document 行为不变，测试保护）。
3. **`edit_document` 工具**（CompilerTools 第 4 个）：ops 数组 + dry_run +
   expected_generation；返回 applied/changed/warnings。compiler 面 5→6 入口。
4. **评测**：baseline 组在 compiler 面复跑（`--surface compiler --group baseline`），
   与 full 面 9/10 / 6.2 次对比；`tracked-replace`/`footer-page-number` 为已知缺口
   （修订创作与页脚写入不在 v1 op 集，如实记录）。
5. **文档与 spec**：docs/07 工具表、docs/11 §P2 结果、agent-single.md compiler 面约定
   更新（6 入口 + edit op 语义 + 原子性保证）。

## 验收标准

- [ ] `mvn -pl nondocx-toolkit -am test` 全绿；能力清单 65→66；
- [ ] PatchEngine 单测：每个 op 的行为 + **原子性**（preflight 失败零写入）+ dry_run 不落盘
      + generation 不匹配拒绝 + 跨 run replace 的 warning；
- [ ] `examples` 全绿；compiler 面 6 入口测试锁死；
- [ ] 真实模型：baseline 组 compiler 面复跑，报告对比写入 docs/11 §P2；
- [ ] Spotless 全绿；全中文。

## 不做什么

- **修订创作 op（`revision.*`）/批注/图片 op**：下一批；
- **跨段移动/复制、样式 profile 自定义**：不做；
- **replace_text 正则/大小写选项**：精确子串匹配即可；
- **demo 接入 compiler 面**：仍等 A/B 数据齐全后由用户决定。
