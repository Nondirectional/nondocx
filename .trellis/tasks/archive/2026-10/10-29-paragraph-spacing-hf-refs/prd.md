# 正文行距工具与 header/footer ref 打通

## Goal

修复评测基线暴露的两个 P0 接口问题：

1. 正文段落没有行距工具，模型只能改字体/字号，无法完成「1.5 倍行距」。
2. `read_header_footer` 签发的页眉/页脚段落 ref 无法被 `view_element` / `read_run`
   解析；`read_run` 也不接受页眉/页脚 run ref，模型只能反复读、最终放弃。

本次把这两条读取/编辑链路补齐；页脚 PAGE 域写入、页眉页脚完整写入仍不在范围内。

## Requirements

### R1. 正文行距工具

- 新增 `update_paragraph_spacing`（body-only 批量工具）：
  - 输入 `doc_id` + `edits` 数组；
  - 每个 edit 支持 `ref`（canonical ParagraphRef）或 `paragraph_index`；
  - 必填 `line_spacing`（number，单倍行高倍数，如 1.5）；
  - 返回逐条成功/失败明细、changedRefs、matched/changed 计数；
  - 与 `update_paragraph_alignment` 一致的 `on_error` / `expected_generation` 语义；
  - 写工具仍只接受正文段落；页眉/页脚/表格段落 ref 必须返回清晰错误，不能静默按正文索引写。
- 行距通过 `Paragraph.lineSpacing(double)` 写 core；保存后重新打开应读回 1.5。

### R2. header/footer ref 读取打通

- `ElementResolver` 增加「任意容器段落解析」：
  - 正文、表格单元格、页眉、页脚段落均可解析；
  - 保持现有 `resolve(ParagraphRef)` 的写工具语义（body-only），新增只读路径。
- `view_element`：
  - 接受页眉/页脚段落 ref，返回文本 + 位置（如 `页脚 section=0 段落 0`）；
  - 接受页眉/页脚 run ref，返回文本/样式 + 位置；
  - 接受 HeaderFooterRef，返回该 part 的文本与段落数（可选，低成本）。
- `read_run`：
  - 支持 canonical RunRef 指向页眉/页脚 run，返回文本/样式并标注位置；
  - 旧 `paragraph_index + run_index` 仍只解释为正文。
- `read_header_footer` / `read_header_footer_ref`：
  - 在返回中补充每个段落的 run ref，让模型能接着 `read_run` / `view_element`。
- 写工具（`replace_run_text` / `update_run_style` / `update_paragraph_alignment` /
  新增行距工具）遇到页眉/页脚/表格 ref 时返回明确「仅支持正文」错误，不伪装成功。

## Acceptance Criteria

- [x] 新工具 `update_paragraph_spacing` 注册成功，capability manifest 工具数从 61 变为 62。
- [x] 单条/批量行距写入生效；save → reopen 读回 1.5；`line_spacing <= 0` 返回参数错误。
- [x] `view_element` 能解析 `read_header_footer` 返回的页脚段落 ref，返回文本和位置。
- [x] `read_run` 能解析页脚 RunRef，返回文本/样式和位置。
- [x] `read_header_footer` / `read_header_footer_ref` 输出 run ref。
- [x] 对页脚 ParagraphRef/RunRef 调用写工具时返回清晰失败，文档不被修改。
- [x] `mvn -q -pl nondocx-toolkit -am test` 全绿；`mvn -q -pl nondocx-examples -am test` 全绿。
- [x] `docs/07-toolkit.md` 工具表更新；`docs/11` 的基线缺口说明更新为「读取已打通，写入仍缺」。
- [x] 用评测任务 `body-font-line-spacing` 与 `footer-page-number` 复跑，确认前者可过、后者错误更清晰。

## Out of Scope

- 页脚 PAGE 域创建/刷新。
- 页眉页脚段落/run 写入（本次只打通读取 + 明确拒绝写入）。
- 正文段落缩进/段前段后间距（core 目前只暴露 lineSpacing/indent；本次只做行距）。
- 改变 `ParagraphRef` 的 canonical 格式或持久化语义。
