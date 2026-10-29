# 基线结果：Agent 评测 v1

> 运行：2026-10-29 16:46:37（Asia/Shanghai）
> runId：`20261029-164637-452`
> 模型：`qwen3.8-27b`（内网 vLLM）
> 工具面：57 个（56 个普通工具 + 1 个只读复审 SubAgent；不含 6 条 Skill）
> 产物：`target/eval/20261029-164637-452/`（`report.md` / `report.json` / 每条任务的 `out.docx` + `trace.jsonl`）

## 总览

| 指标 | 值 |
|---|---|
| 任务数 | 10 |
| 通过 | 8 |
| 失败 | 2 |
| 通过率 | 80% |
| 平均工具调用 | 6.8 |
| 总耗时 | 137.4s |
| 已知能力缺口失败 | 2 |
| 非能力缺口任务通过率 | 8/8（100%） |

> 后续：`10-29-paragraph-spacing-hf-refs` 已补正文行距工具并打通 header/footer 只读 ref；
> 本摘要保留 v1 原始数据，修复后复跑见 `docs/11` §6.2。

> token 记录为 0：当前 `chain 0.11.0` 的 VLLM streaming provider 未返回 usage。
> 报告已加注说明；升级 provider 后自动恢复，不需要改评测逻辑。

## 任务明细

| 任务 | 结果 | 调用 | 耗时 | 说明 |
|---|---|---:|---:|---|
| `append-paragraph` | ✅ | 4 | 7.5s | current_document + view_outline + insert + review |
| `insert-centered-title` | ✅ | 4 | 10.1s | 结构/对齐正确 |
| `replace-exact-text` | ✅ | 5 | 8.6s | 精确定位成功 |
| `body-font-line-spacing` | ❌ | 8 | 17.0s | 字体/字号成功；**正文行距无工具**，实际 -1.0 |
| `heading-font` | ✅ | 5 | 9.5s | 标题黑体成功 |
| `create-table-header` | ✅ | 6 | 17.8s | 建表 + 表头加粗分两步 |
| `table-cell-edit` | ✅ | 5 | 13.5s | 五级坐标一次命中 |
| `tracked-replace` | ✅ | 6 | 13.6s | 修订 INS/DEL + 作者正确 |
| `replace-all-occurrences` | ✅ | 5 | 10.7s | 三处 TODO 全部替换 |
| `footer-page-number` | ❌ | 20 | 29.3s | **页脚没有写工具**；模型反复读、最终放弃 |

## 关键发现

### 1. 基础任务都能过，但调用数偏高

最简单的「末尾追加一段」也要 4 次调用：`current_document` → `view_outline` → `insert_paragraph` → `review_intent`。
其中 `current_document` 和 `view_outline` 在 demo 已知当前文档/用户只要求追加时并不是必需的。
8 条非缺口任务平均 5.0 次调用，说明工具面仍偏「多步」。

### 2. 正文行距是明确的能力缺口

任务 4 的字体/字号都成功了（模型用 `update_run_style` 批量改 3 段），但：

- 正文没有 `update_paragraph_spacing` 类工具；
- 只有 `update_table_cell_paragraph_spacing`，作用于单元格；
- 读回 `Paragraph.lineSpacing()` = **-1.0**（未设置），check 失败。

模型在最终回复里准确指出了这个缺口，并建议用户去 Word 手动设置。
这不是模型乱改，而是工具面缺一块。

### 3. 页脚任务暴露了三个问题

任务 10 失败，但失败过程比结果更有信息量：

1. **没有页脚写工具**：`read_header_footer` / `read_header_footer_ref` 只读；
   `insert_paragraph` / `replace_run_text` / `update_run_style` 都只作用于正文。
   模型最终如实说明无法完成。
2. **页脚 ref 在 `view_element` 里解析失败**：
   `read_header_footer` 返回的段落 ref（`.../paragraph:session:cC0y`）传给 `view_element`
   得到失败；模型随后在 `read_run` 的 `run:0` 与 `run:session:r-0` 之间反复尝试 10+ 次。
   这说明 header/footer 的 ref 与正文元素解析路径没有打通。
3. **第一次运行观察到「静默改错目标」**：
   模型用 `replace_run_text(paragraph_index=0, run_index=0, text="第 X 页")` 得到成功，
   但实际改的是正文第 0 段，页脚仍然是「页脚」。
   这正是「索引不是身份」的典型后果：工具返回成功，目标却是错的。
   第二次运行已加入「正文不被误改」check，本次运行该 check 通过（模型没有再走这条路径），
   但风险真实存在。

### 4. 复审能工作，但仍由模型自愿触发

8 条通过的任务里大多调用了 `review_intent`；页脚任务没有调用（模型在失败说明里直接放弃）。
复审本身耗时约 5.3s，是单任务耗时的重要部分。
这和设计判断一致：复审应该由应用层在 dirty 后强制执行，而不是模型可选项。

## 对下一步的输入

- **P0 工具面**：先补正文段落行距/缩进/标题，和表格侧对齐；
  打通 header/footer ref 与 `view_element`/`read_run` 的解析。
- **P0 安全**：写工具不能对「不支持的容器」静默按正文索引成功；
  至少返回 `unsupported_target`，或要求显式 `scope=BODY|HEADER|FOOTER`。
- **P1 接口收敛**：基础任务 4~6 次调用仍偏高；Markdown/IR + 单 patch 工具应把
  简单任务压到 ≤3 次，复杂任务 ≤8 次。
- **P1 复审**：改为应用层强制，预期能减少一次模型决策并稳定质量口径。
- **P2 评测**：补 token（升级 provider 或改用非流式 usage）、加「with skills」配置做 A/B。

## 复现

```bash
mvn -q -pl nondocx-examples \
  -Dexec.mainClass=io.github.nondirectional.docx.examples.eval.EvalMain \
  exec:java -Dexec.args="--timeout-seconds 150 --max-iterations 20"
```

单条任务：

```bash
mvn -q -pl nondocx-examples \
  -Dexec.mainClass=io.github.nondirectional.docx.examples.eval.EvalMain \
  exec:java -Dexec.args="--tasks footer-page-number"
```

---

## 修复后复跑（2026-10-29 17:06）

对应任务：`10-29-paragraph-spacing-hf-refs`。

- 新增 `update_paragraph_spacing`，工具面 57 → 58；`body-font-line-spacing`
  **通过**（6 次调用，行距 1.5）。
- 页脚/页眉 ref 读取打通：`view_element` 可解析 footer paragraph ref，
  `read_run` 可解析 footer run ref，`view_element` 支持 HeaderFooterRef。
- 页脚任务仍失败（PAGE 域/页脚写入不在范围）。写工具对页脚 ref 明确返回
  「仅支持正文」；但模型随后退回正文索引 `replace_run_text(paragraph_index=0)`，
  把正文改成「第 页」。评测的「正文不被误改」check 捕获了这次静默改错目标。
- 结论：read ref 已打通；**写路径仍需要显式容器/scope，或走 IR patch**。
- 复跑产物：`target/eval/20261029-170825-571/`。
