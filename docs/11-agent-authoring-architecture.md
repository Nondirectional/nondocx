# 11 · Agent 写作架构建议：从「61 个工具」到「文档编译器」

> 状态：建议稿（2026-10-29）；P0 已落地（正文行距工具 + header/footer ref 读路径），
> **P1 编译器 spike 已落地并通过 A/B 验证（§6.3）**，P2/P3 待启动。
>
> 本文记录一次针对 `nondocx-toolkit` 的架构复盘：为什么当前 Agent 写 docx 的效果不稳定，
> 以及接下来建议走的方向。评测基线（§6）与编译器 spike 的结果以实测数据回填在本文。

---

## 1. 结论

`nondocx-core` 的底子很好：活对象、往返保真、修订、批注、稳定引用、语义视图都已经具备。
问题主要不在 core，也不在模型，而在 **Agent 接口层**：

> 当前 toolkit 把「写文档」变成了「让 LLM 调 61 个底层 API」。
> 但自然语言写 docx 更像一个编译问题：**意图 → 文档模型/IR → 排版与样式策略 → OOXML**。

现在缺了中间的「文档模型 + 编译器」，模型只能靠工具调用硬扛。任务一复杂，调用次数、
上下文、寻址错误就会一起放大。

建议的方向：

1. **保留 core**，把 toolkit 的 Agent 接口从「API 包装」升级为「文档 IR + 编译器」。
2. **主 Agent 只暴露 5~6 个工具**：读文档、建文档、改文档、校验、渲染预览、模板填充。
3. **底层 61 个工具不删除**，降级为专家/逃生接口，不再默认塞给主模型。
4. **样式、CJK 排版、Word/WPS 兼容性**由确定性的样式表和编译器负责，不交给模型逐 run 设置。
5. **保存、复审等质量动作**继续由应用层强制，不依赖模型自觉。
6. **先建评测基线**，再改架构；否则「效果好不好」无法量化。

---

## 2. 当前实现观察

### 2.1 工具面过载

生成后的 `nondocx-toolkit/target/classes/capabilities.json` 记录：

- 工具总数 **61**；
- 表格相关工具 **25** 个（`read_table_cell` / `read_table_row` / `read_table_cell_run`、
  `update_table_cell_paragraph_*` 六件套、`add/remove/merge` 等）；
- 工具描述按字符粗估约 2.4 万字符，约 **1 万+ token**（实际取决于 tokenizer）。

demo 还额外挂了 `current_document`、只读复审 SubAgent 和 6 条 Skill，单轮向模型暴露
**60+ 个 function**。每轮都发一遍，成本和选择难度都很高。

### 2.2 工具粒度是 API 粒度，不是任务粒度

- `insert_paragraph` 只支持「单 run + 纯文本 + 标题/对齐」，富文本要拆成多次调用；
- `create_table` 只能追加到末尾，表头加粗还要再调一次单元格 run 样式；
- 正文没有 `update_paragraph_spacing`，只有 `update_table_cell_paragraph_spacing`；
- 没有 `create_document`，只有 `open_docx`；「写一份周报」没有入口；
- 页眉页脚/TOC 只读；批注没有工具。

结果是：一个「正文宋体小四、1.5 倍行距」的常见需求，需要十几次 run/cell 调用，
而且正文行距根本做不到。

### 2.3 寻址与上下文成本

- 读工具返回 `paragraph_index` + canonical ref 两套寻址；模型倾向用更短的索引，
  但索引在插入/删除后会漂移。
- canonical ref 形如 `doc:ZG9jLTE@g1/paragraph:session:cC0x`，又长又难抄；
  模型复制错误后得到 `stale_ref` / `invalid_ref`。
- 部分读工具把同一段文本同时放进 `message` 和 `data`，渲染器再输出一遍；
  一次读多段就是双倍 token。

### 2.4 质量动作依赖模型自觉

- `review_intent` 是一个工具，模型可调可不调；实测有任务完全不调。
- 保存已经由应用层强制（这是对的），但复审仍由模型决定。
- 质量检查是启发式规则，缺少「编译后验证 + 渲染后验证」的闭环。

### 2.5 Skill 与工具名漂移

demo 的 6 条 Skill 中出现了不存在的工具名，例如：

- `view_body` / `view_table`（实际是 `view_text` / `view_outline` / `read_table_row`）
- `replace_paragraph` / `delete_paragraph`（实际没有这两个工具）
- `set_cell_text` / `merge_cells`（实际是 `replace_table_cell_run_text` / `merge_table_cells`）
- `add_or_replace_hyperlink`（实际是 `update_hyperlink`）

模型按 Skill 调用必然失败，失败后可能开始编造相近工具名。
**Skill 本来是稳定性手段，现在反而成了错误来源。**

### 2.6 缺少 Agent 级评测

core/toolkit 的单测很扎实，但没有「给模型一个任务，检查最终 docx」的评测集。
仓库里唯一的真实 LLM 集成测试已在 `a9b6b6d` 被移除。
因此「效果不佳」无法拆解为：模型选错、参数错、Skill 错、能力缺失，还是渲染问题。

---

## 3. 目标架构

```text
用户自然语言
      ↓
Agent（5~6 个工具）
  ├── read_document    → 文档的 Markdown/IR 视图（短 ID）
  ├── create_document  → 文档编译器 ─┐
  ├── edit_document    → patch 引擎 ─┼→ nondocx-core → .docx
  ├── validate_document→ 结构/样式/兼容性检查
  ├── render_preview   → PDF/PNG 视觉检查
  └── fill_template    → 模板 + JSON 数据（可选，优先级高）
```

### 3.1 给 LLM 的文档表示层

新建文档时，模型直接输出 Markdown 子集：

```markdown
# 项目周汇报 {align=center}

本周完成了 **nondocx** 的表格封装，整体进度符合预期。

| 模块 | 状态 | 负责人 |
|---|---|---|
| nondocx-core | 已完成 | non |
| nondocx-examples | 进行中 | non |
```

编辑现有文档时，读出来带短 ID：

```markdown
# 项目周报 · 第 12 周
<!-- p1 -->

本周完成了 nondocx 的表格与超链接封装。 <!-- p2 -->
```

后续 patch 只引用 `p2`、`t1.r0.c1`。内部仍用稳定 ref，模型侧用短 ID，
工具负责解析；**不要给模型看长 canonical ref**。

### 3.2 样式 profile，而不是让模型设字体字号

```yaml
title:
  font: {eastAsia: 黑体, latin: Arial}
  size: 22
  align: center
h1:
  font: {eastAsia: 黑体, latin: Arial}
  size: 16
body:
  font: {eastAsia: 宋体, latin: Times New Roman}
  size: 12
  line_spacing: 1.5
  first_line_indent: 2em
table.header:
  bold: true
  shading: F2F2F2
```

用户说「正文小四宋体、1.5 倍行距」，模型只需一次
`set_style(selector=body, style=body.cn)`，而不是 9 次 run 样式 + 9 次单元格行距。
CJK 的 `eastAsia`/`latin` 字体槽、首行缩进、行距这些 Word 坑，全部由编译器兜底。

### 3.3 用一个 patch 工具替代逐 API 编辑

`edit_document` 接收 ops 数组，原子执行，支持 `dry_run`：

```json
{
  "base_version": 7,
  "ops": [
    {"op": "insert_after", "anchor": "p3",
     "block": {"type": "heading", "level": 1, "text": "项目周汇报", "align": "center"}},
    {"op": "replace_text", "anchor": "p8", "find": "旧文本", "text": "新文本"},
    {"op": "set_style", "anchor": "p8", "style": "body"},
    {"op": "table.set_cell", "anchor": "t1", "row": 0, "col": 1, "text": "完成"},
    {"op": "revision.accept", "anchor": "rev3"}
  ]
}
```

第一批建议只做 6 种 op：

- `insert_after` / `replace_text` / `delete`
- `set_style`
- `table.set_cell` / `table.add_row`
- `revision.accept` / `revision.reject`
- 后续再加 `comment.add`、`image.insert`

返回：

```json
{"success": true, "version": 8, "applied": 5, "changed": ["p3","p8","t1"], "warnings": []}
```

关键点：

- **原子**：先 preflight 全部 anchor，再统一写入；失败不落盘。
- **短 ID + 文本锚点**：`p8`、`"find": "旧文本"` 比五级坐标稳。
- **base_version**：防止旧快照改新状态。
- **dry_run**：先返回 diff，确认后再执行。

### 3.4 五个工具，而不是六十个

| 工具 | 作用 |
|---|---|
| `read_document` | 返回紧凑 Markdown/IR：outline / text / table，带短 ID，分页 |
| `create_document` | Markdown + style profile → 新 docx |
| `edit_document` | 对现有文档应用 patch，原子、可 dry-run |
| `validate_document` | 结构、样式、兼容性、空白页、表格分页等，返回带 ID 的问题 |
| `render_preview` | LibreOffice/OnlyOffice 渲染 PDF/PNG，做视觉 QA |
| `fill_template` | 模板 + JSON 数据，处理占位符和重复表格行 |

`open/save/close/current_document/doc_id` 全部从模型可见的 schema 里拿掉。
运行时已经知道当前文档，直接注入；保存继续由应用层在结束时强制 flush。

### 3.5 复审与保存一样，由应用层强制

既然 `review_intent` 是质量门，就不应该由模型决定是否调用。建议：

- 本轮 dirty 时，应用层在 flush 前自动跑只读复审；
- 复审输入 = 用户本轮请求 + 最终文档；
- 结论随 `edit_outcome` 回传，不新增工具；
- 模型不需要「记得」复审，也不会漏掉。

### 3.6 模板优先

如果目标是产品化，大多数企业文档不是自由排版，而是「固定模板 + 数据 + 少量叙述」。
建议增加独立路径：

```text
模板 docx（占位符/重复行/条件段）
        + Agent 产出的 JSON 数据
        + fill_template
        → 稳定、可审计、符合品牌规范的 docx
```

`fill_template` 的可靠性会比「让模型从零排版」高一个数量级。自由创作保留为第二条路径，
而不是唯一路径。

---

## 4. 与现状的对比

| 维度 | 现在 | 建议 |
|---|---|---|
| 工具数 | 61 | 5~6 |
| 单轮 schema | 约 1 万+ token（粗估） | 约 2k |
| 新建文档 | 无 | `create_document(markdown)` |
| 全文格式 | 十几次 run/cell 调用 | 一次 `set_style` |
| 定位 | 长 ref + 多级索引 | 短 ID / 文本锚点 |
| 样式 | 模型设字体字号 | 样式 profile 统一编译 |
| 复审 | 模型自觉调用 | 应用层强制 |
| 验证 | 10 项启发式 | `validate` + 渲染预览 |
| 评测 | 手工 demo | 20~30 条任务基线 |

---

## 5. 落地顺序

### P0：先止血（1~3 天）

1. **修模型配置**：环境变量 + 启动校验，确认端点真的提供该模型。✅ 已修（2026-10-29）：
   demo 增 `LlmConfig`（`NONDOCX_LLM_BASE_URL`/`NONDOCX_LLM_MODEL` 环境变量，默认
   `qwen3.8-27b`），启动时 `GET /models` 校验，模型不存在即禁用 Agent 并在日志/对话框
   给出可用模型清单——不再留到第一次对话才 404。
2. **修/删 Skill**：所有工具名对齐真实 registry；加 CI 检查，Skill 提到的工具名必须存在。
3. **裁剪 demo 工具面**：主 Agent 只暴露 `view_*`、`read_*`、`search_text`、
   `insert_paragraph`、`replace_run_text`、`update_run_style`、`create_table`、
   少量单元格工具、修订工具；隐藏 `describe_capabilities` 和 25 个表格工具中的大部分。
4. **补正文段落行距/缩进/标题工具**，和表格侧对齐。
5. **加 `create_document`**：现在「写一份文档」没有入口。
6. **修读结果重复**：`message` 给人看，`data` 给模型看；或读工具只返回一份紧凑文本。
7. **建 10 条任务的评测基线**，先不要改架构，量出当前成功率、平均调用数、错误类型。

### P1：做编译器 spike（1~2 周）——✅ 已完成，结果见 §6.3

- `read_document`：基于现有 `DocumentViewService` 输出 Markdown + 短 ID。
- `create_document`：Markdown 子集 + style profile → `nondocx-core`。
- `validate_document`：复用 `QualityCheckTools`，输出带 ID 的问题。
- 只做 3 个工具，先在「新建文档」场景对比 10 条任务。
- 如果调用数能从 10+ 降到 2~3，再继续投入。
  （实测：5 条 create 任务，总调用 23.0 → 5.6、创作调用 1~2 次、通过率 20% → 100%——继续投入。）

### P2：编辑与模板（已全部落地，2026-10-30）

- `edit_document` patch 引擎，接 tracked changes / comments。
  （已落地并 A/B（§6.4~§6.8）：11 op（含 tracked.*/页脚页码/批注）、原子、dry-run。）
- `fill_template`：占位符、重复表格行、条件段。（§6.8）
- `render_preview`：LibreOffice headless → PDF/PNG。（§6.8）
- 把 `QualityCheckTools` 升级成编译器的 post-condition，而不是事后可选检查。（§6.10）

### P3：评测与多模型

- 20~30 条任务，覆盖：新建、改写、全文格式、表格、页眉页脚、修订、批注、
  模板填充、歧义指代、重复文本。**（已完成：baseline 12→22 见 §6.9、→23 见 §6.11；
  14B 对比延后——本地仅 27B。）**
- 指标：任务成功率、工具调用数、无效调用率、token、延迟、渲染缺陷率。
- 用 trace journal 做回放；失败分类：规划 / 寻址 / 工具选择 / 参数 schema /
  能力缺失 / 渲染 / 模型。
- 在 14B 和 27B 上各跑一遍，确认是接口问题还是模型能力问题。

---

## 6. 评测基线（任务 A）

第一版先做 10 条任务，不追求覆盖全部能力，而是覆盖最容易区分「模型问题」和
「接口/能力问题」的场景：

| # | 任务 | 类型 | 预期暴露的问题 |
|---|---|---|---|
| 1 | 末尾追加一段 | 简单写 | 基本可用性 |
| 2 | 开头插入居中标题 | 结构 + 对齐 | 插入位置、标题/对齐 |
| 3 | 精确替换一段文本 | 文本编辑 | 定位、run 边界 |
| 4 | 正文宋体小四 + 1.5 倍行距 | 全文格式 | **缺正文行距工具** |
| 5 | 所有标题设为黑体 | 样式 | 标题识别、批量样式 |
| 6 | 末尾建 2×3 表格并加粗表头 | 表格 | 建表 + 表头样式分步 |
| 7 | 修改表格第 2 行第 2 列 | 表格 | 五级坐标寻址 |
| 8 | 修订模式替换文本 | 修订 | 修订创作 vs 普通覆盖 |
| 9 | 替换所有 TODO | 批量 | 搜索 + 批量编辑 |
| 10 | 页脚插入页码 | 页眉页脚 | **只读能力缺口** |

评测不只看最终文本，还要记录：

- 每条 check 的通过/失败；
- 工具调用序列（名称、参数、成功/失败）；
- 无效工具调用次数；
- prompt/completion token；
- 耗时；
- 失败分类。

指标口径：

- **任务成功率**：10 条任务中所有 check 通过的比例；
- **平均工具调用数**：越低越好，简单任务应 ≤3；
- **无效调用率**：调用不存在/参数错误/目标失效的比例；
- **token / 延迟**：同样任务下的成本；
- **能力缺口**：失败但模型行为合理的任务，用 `capabilityGap` 标记，
  不和「模型乱改」混在一起。

第一版基线不加载 demo 的 6 条 Skill，以隔离工具面本身；Skill 的工具名一致性问题
由单独的静态检查覆盖。后续可以加「with skills」配置做 A/B。

### 6.1 v1 基线结果（2026-10-29）

实现位于 `nondocx-examples/.../examples/eval/`，通过
`EvalMain` 运行。第一版结果：

| 指标 | 值 |
|---|---|
| 模型 | `qwen3.8-27b`（内网 vLLM） |
| 工具面 | 57（56 普通 + 1 只读复审；不含 Skill） |
| 通过率 | **8/10（80%）** |
| 平均工具调用 | **6.8** |
| 总耗时 | 137.4s |
| 已知能力缺口失败 | 2 |
| 非能力缺口任务通过率 | 8/8 |

失败的两条都是预期中的能力缺口：

- **正文行距**：字体/字号成功，但 `Paragraph.lineSpacing()` 仍为 `-1.0`；
  工具集没有正文段落行距接口。
- **页脚页码**：没有页脚写工具；模型反复读页脚 ref 后放弃。
  同时暴露出 `read_header_footer` 返回的段落 ref 在 `view_element` 中解析失败；
  第一次运行还观察到 `replace_run_text(paragraph_index=0)` 把正文改成「第 X 页」
  却返回成功的「静默改错目标」风险。

基础任务 4~6 次调用，说明当前工具面仍然偏多步。v1 的完整报告、每条任务的
`out.docx` / `trace.jsonl` 在 `target/eval/<runId>/`；任务目录的
`baseline-summary.md` 记录了明细与复现命令。

复现：

```bash
mvn -q -pl nondocx-examples \
  -Dexec.mainClass=io.github.nondirectional.docx.examples.eval.EvalMain \
  exec:java -Dexec.args="--timeout-seconds 150 --max-iterations 20"
```

### 6.2 修复后复跑（2026-10-29）

新增 `update_paragraph_spacing`（demo 工具面 57→58；toolkit manifest 61→62），并打通 header/footer ref 的只读解析。
复跑两条缺口任务：

| 任务 | 修复前 | 修复后 |
|---|---|---|
| `body-font-line-spacing` | ❌ 8 次调用，行距 `-1.0` | ✅ 6 次调用，行距 1.5 |
| `footer-page-number` | ❌ 20 次调用，ref 解析失败 + 反复读 | ❌ 9 次调用，ref 可读、写 ref 被明确拒绝；无 PAGE 域 |

页脚任务仍然失败：PAGE 域创建/页脚写入不在本次范围。修复后模型确实读到了页脚
paragraph/run ref，`update_run_style` / `update_paragraph_alignment` 对页脚 ref 返回了明确的
「仅支持正文」错误，随后模型放弃，没有再误改正文。较早的一次两任务复跑中，模型曾退回正文索引
`replace_run_text(paragraph_index=0)`，随后自己发现并恢复；这说明「仅靠 ref 校验」仍无法阻止
模型主动选择合法但错误的正文索引。

这正是「索引不是身份」的剩余风险：**当用户意图是页脚、而正文索引恰好合法时，
工具无法知道模型选错了容器**。下一步要么给写工具加显式 `scope`/容器校验，
要么走文档 IR + patch 的路线，让目标容器成为 op 的一部分。

修复后的全量复跑（10 条）：

| 指标 | v1 | 修复后 |
|---|---|---|
| 通过率 | 8/10 | **9/10** |
| 平均调用 | 6.8 | 6.2 |
| `body-font-line-spacing` | ❌ 8 次 | ✅ 6 次 |
| `footer-page-number` | ❌ 20 次 | ❌ 9 次 |

`capabilityGap` 现在只标记 `footer-page-number`；最终复跑产物在
`target/eval/20261029-174952-467/`。

### 6.3 编译器 spike A/B（任务 B，2026-10-29）

P1 落地：`nondocx-toolkit` 新增第 9 组工具 `CompilerTools`
（`create_document` / `read_document` / `validate_document`，EXPERIMENTAL 级，能力清单
62→65），外加 Markdown 子集解析器 + 文档 IR + 样式 profile（`report-cn`/`plain`）+ 编译器。
评测端新增 `create` 任务组（5 条从零建文档任务）与 `--surface full|compiler` 开关，
同一批任务在两个工具面上跑（`qwen3.8-27b`，温度 0，无 Skill）：

| 指标 | full 面（58 工具） | compiler 面（5 工具） |
|---|---|---|
| 通过率 | 1/5（20%） | **5/5（100%）** |
| 平均工具调用 | 23.0 | **5.6** |
| 无效调用 | 多（见各任务报告） | **0** |
| 总耗时 | 306s | 105s |
| runId | `20261029-231126-331` | `20261029-231703-151` |

compiler 面的典型调用序列（`create-data-table`，4 次，零无效）：

```text
current_document → create_document → validate_document → review_intent
```

其中真正的「创作」只有 1 次 `create_document`；`current_document`/`validate`/`review`
是固定开销（2~3 次）。最复杂的 `create-styled-body`（宋体小四 + 1.5 行距 + 首行缩进 +
黑体居中标题）也只需 2 次 `create_document`（第一次 review 后补正）共 8 次调用——
同样的任务在 full 面跑了 25 次仍因「无正文缩进工具」失败。

full 面 4 条失败的归因（诚实口径）：

- **无列表创建入口** ×2：`insert_paragraph` 只有 `heading_level`/`alignment` 参数，
  BULLET/NUMBERED 列表在 full 面物理不可达（`create-meeting-notes`/`create-research-report`）；
- **无正文首行缩进工具** ×1：BodyTools 无任何 indent 写入口（`create-styled-body`）；
- **建表 + 标题多步组合超载** ×1：27 次调用后标题仍未设成 H1、表格未建（`create-weekly-report`）。

也就是说 full 面的失败一半是**能力缺口**、一半是**多步组合超载**；compiler 面两类问题
都不存在——能力由编译器覆盖（列表/缩进/表格一次成型），多步组合收敛为一次提交。

**两个顺带的实测修正**：

1. **POI `setFontFamily(name)` 是四槽同写**（ascii/hAnsi/cs/eastAsia 写同一字体名），
   不是「只写 ascii 槽」。编译器经 `raw()` 覆写 eastAsia 槽的价值因此不是「补缺失」，
   而是**分离**——「西文 Times New Roman + 中文宋体」的规范双槽，公开 API 做不到。
2. **表格分页控制**（首行 `headerRow` + 数据行 `cantSplit`）core 早有读写 API，
   编译器现在默认写入，`table-pagination` 质检项在编译产物上恒通过。

**结论**：P1 假设成立——「调用数从 10+ 降到 2~3」实测为**创作调用 1~2 次、
总调用 23.0 → 5.6（4.1×）**，同时通过率 20% → 100%。值得继续投入 P2
（`edit_document` patch 引擎 + 模板/渲染预览），并把 compiler 面作为新建文档场景的
默认路径接入 demo。

复现：

```bash
# compiler 面
mvn -q -pl nondocx-examples -Dexec.mainClass=...eval.EvalMain exec:java \
  -Dexec.args="--surface compiler --group create"
# full 面
mvn -q -pl nondocx-examples -Dexec.mainClass=...eval.EvalMain exec:java \
  -Dexec.args="--surface full --group create --timeout-seconds 240 --max-iterations 24"
```


### 6.4 edit_document patch 引擎 A/B（任务 P2，2026-10-30）

P2 第一块落地：`PatchEngine` + `edit_document`（compiler 面第 4 个工具，5→6 入口；能力清单
65→66）。6 种 op（insert / replace_text / delete / set_style / table.set_cell /
table.add_row），锚点 = `read_document` 短 ID，插入块复用 Markdown 解析器；**先 preflight
全部 op 再统一写入，任一失败零改动**；支持 dry_run 与 expected_generation。

baseline 组（10 条编辑任务）在 compiler 面的终版结果（`qwen3.8-27b`，温度 0）：

| 指标 | full 面（58 工具） | compiler 面（6 工具） |
|---|---|---|
| 通过率 | 9/10 | **8/10**（缺口见下） |
| 平均调用 | 6.2 | 9.6 |
| 通过任务平均调用 | ~6 | 8.0 |
| runId | `20261029-174952-467` | `20261030-001221-487` |

**读法（诚实口径）**：

- compiler 面的 2 条失败全是**预告的 op 集缺口**：`tracked-replace`（修订创作 op 未做）、
  `footer-page-number`（页脚 op 未做）。在 8 条可做任务上 compiler 面 **8/8**；
  full 面的 9/10 里多出的那条正是修订——修订 op 属 P2 后续，不是 patch 架构问题。
- **简单编辑的调用数显著下降**：追加一段 3 次、精确替换 4 次、批量替换所有 TODO
  4 次（一次 edit_document 批量 ops，full 面要搜索+逐段改）、建表 5 次、改单元格 6 次。
- **平均调用反而略高**的原因：review/validate 固定开销（每任务 +2~3）+ 两个失效模式——
  ①`insert-centered-title` 的波动（11~23 次）：当时的归因是「模型把 `{align=center}` 记号
  误当正文」，§6.6 的 trace 复盘**推翻**了它——真正的主因是复审 SubAgent 看不到 alignment
  而持续误报「未居中」，主模型围绕已正确的文档空转；
  ②失败任务的重试循环拉高均值（21/11 次）。

**过程中修的三个真 bug（都被 trace 抓到）**：

1. **表格缺分隔行**：LLM 常漏写 `|---|`，原降级策略把带管道符的原文倒进段落产生脏文本；
   现改为 ≥2 行管道块仍按表格解析（首行为表头，记 warning）。
2. **单元格字面星号**：`| **任务** |` 的标记被原样保留；现解析时剥标记，且
   `table.set_cell` 的 text 支持行内标记（`**任务**` → 文本「任务」+ 加粗 run）。
3. **字体检查口径**：评测 check 读 ascii 槽，而 split 双槽的正确读法是 eastAsia 槽
   （对 full 面四槽同写也成立）；修正后 body-font/heading-font 两个任务在 compiler 面
   首次 set_style 就通过。

**结论**：patch 架构的两条结构性收益兑现——**一次调用表达批量编辑**（replace-all 4 次）
与**目标容器成为 op 声明的一部分**（短 ID 锚点，无「合法但错误」的索引空间）。调用数
持平（8.0 vs ~6）主要败在固定开销与提示词层面，值得在 P2 后续（修订/批注 op、demo 接入
compiler 面）继续压。

复现：

```bash
mvn -q -pl nondocx-core,nondocx-toolkit install -DskipTests
mvn -q -pl nondocx-examples -Dexec.mainClass=...eval.EvalMain exec:java \
  -Dexec.args="--surface compiler --group baseline"
```

### 6.5 修订与页脚 op：补齐最后两个缺口（P2 续，2026-10-30）

§6.4 的 2 条失败是预告的 op 集缺口，本批补齐：`edit_document` op 集 6→10——
`tracked.insert` / `tracked.delete` / `tracked.replace`（修订创作，author 必传）+
`footer.set_page_number`（页脚页码，`{page}`/`{pages}` 占位符）。工具数不变（6 入口），
能力清单仍 66。

**core 侧两个新 API**（POI 没有的高层语义）：

- `Paragraph.replaceTrackedRange(author, start, end, newText)`——**范围级最小 diff 修订替换**：
  逐 run 切片 del「find」范围 + ins 紧随其后（复用 `addDeletionSlice`，新下沉
  `addInsertionAfter` 定位 ins——`addInsertion` 只能段末追加，直接组合会把新文本放到句号后）。
  ins run 复制范围内首个 run 的 rPr（替换保留格式）。
- `Paragraph.addSimpleField(instruction, cachedResult)`——**带缓存结果的简单域**（五 run：
  begin/instr/separate/result/end）。实证：纯指令域的 `footer.text()` 读出「第  页」无数字
  （POI 不计算域值）；缓存结果让所有 POI 系读者读到「1」，Word/WPS 打开时重算。这是
  footer-page-number 双面失败（full 面 20 次调用）的根因之一——写侧此前没有能用的域 API。

**同锚点独占规则**：`tracked.*` 与 `replace_text` 每批对同一段落锚点至多一条（修订偏移按
执行时活文本从右到左计算——tracked del 会把被删文本从 `text()` 移走、ins 对 POI 读侧不可见，
同锚点多条互相污染），第二个认领者整体拒绝并提示拆批。页脚 op 不占段落锚点，可与正文 op 同批。

baseline 组复跑（同口径 `qwen3.8-27b`，温度 0，runId `20261030-010150-507`）：

| 指标 | full 面（58 工具） | compiler 面（6 工具） |
|---|---|---|
| 通过率 | 9/10 | **10/10** |
| 平均调用 | 6.2 | 8.0 |
| `tracked-replace` | ✅（修订面工具） | ✅ **6 次**（1 次 edit_document 直达） |
| `footer-page-number` | ❌（20 次，capabilityGap） | ✅ **4 次**（1 次 edit_document 直达） |

**读法（诚实口径）**：

- 两个新任务都是**一次 edit_document 命中**：`tracked.replace` 带 author=审查员单发；
  `footer.set_page_number` format=「第 {page} 页」单发。产物语义抽查：del 只包住
  「一百万元」、ins 紧随其后；页脚域含 separate + 缓存 "1"，正文零改动。
- compiler 面**首次反超 full 面**（10/10 vs 9/10）：多出的正是 full 面至今过不了的
  footer-page-number——不是模型问题，是 full 面没有可用的写侧域 API。
- 平均 8.0 仍高于 full 的 6.2：大头是 `insert-centered-title` 的 26 次波动（其余 9 任务
  平均 6.0）。当时把它归因于「记号混淆提示词问题」——§6.6 的 trace 复盘推翻了这一归因，
  根因是复审可观测性缺口，修复后该任务 3 次调用收敛。
- 已知语义边界（实证后写进 spec）：tracked 修订后 `read_document` 正文视图不含 ins 内容、
  被删文本已移除（POI `getRuns()` 不暴露 `w:ins`/`w:del` 内 run）——核对修订用
  review_intent（其 view 面含 view_tracked_changes），页脚靠 `<!-- 页脚: … -->` 元数据行
  （MarkdownView v2 新增，round-trip 安全）。

**结论**：op 集补齐后 compiler 面 baseline 10/10，「文档 IR + patch」路线在编辑侧也站住了。
demo 已于同日默认切到 compiler 面（`NONDOCX_AGENT_SURFACE=full` 回退，见 nondocx-demo/README）。

### 6.6 复审可观测性：一次被 trace 推翻的归因（2026-10-30）

`insert-centered-title` 长期波动（11~26 次）一直被归因为「模型把 `{align=center}` 记号误当正文」
（§6.4 原文，本次修正）。单任务复跑（runId `20261030-090245-055`，24 次调用）的 trace
**推翻**了它：

- **第 2 次调用就正确**：`insert "# 项目周汇报 {align=center}"`——`fillHeading` 显式 align
  优先，H1+居中一次到位（评测 check 从此刻起即通过）。
- **循环驱动者是复审 SubAgent**：8 次 `review_intent` 全部「部分达成：未设置居中对齐」——
  `view_element` 的段落详情只有 text/location/lineSpacing/headingLevel，**没有 alignment**，
  复审把「工具不可见」当「属性未设置」。主模型的 set_style/重插全是围绕已正确文档的空转；
  它从未对 `{align=center}` 发起过 replace_text。

**修复 = 补观测面，不是改 prompt**：paragraph 详情恒输出 `alignment`（未设置=left，命名与
`{align=…}` 记号一致）。复跑 3 次调用收敛（current_document → insert → review 达成）。

全组复跑又暴露**同族缺口**：`heading-font` 24 次、`body-font` 9 次——复审读 run 字体只见
ascii 槽（"Arial/Times New Roman"），看不见编译器写的 eastAsia 槽（黑体/宋体），把已正确的
槽分离排版误报成「未设置字体」。修复：core 新增 `Run.fontEastAsia()`（与 `font()` 对称的
中文槽读法），`view_element` run 详情与 `view_annotated`（expandRuns）均暴露 `fontEastAsia`。

终版（runId `20261030-091648-612`，同口径）：

| 指标 | full 面 | compiler 面（§6.5 时） | compiler 面（本次） |
|---|---|---|---|
| 通过率 | 9/10 | 10/10 | **10/10** |
| 平均调用 | 6.2 | 8.0 | **4.4** |
| 最慢任务 | ~20（footer） | 26（centered-title） | 6（table-cell-edit） |

逐任务（次）：append 3 / centered-title **3**（原 26）/ replace-exact 4 / body-font **5**
（原 9）/ heading-font **4**（原 24）/ 建表 5 / 改格 6 / tracked-replace 6 / replace-all 4 /
footer 4。compiler 面首次在**通过率与调用数双指标**上超过 full 面。

**教训（写进 spec）**：复审只能依据工具**可见**的属性下结论——「详情里没有」会被解读成
「没设置」。给复审需要核对的属性（对齐/中文字体/行距）加观测面时**恒出现**优于「设置才出现」。

**第二批（同日）**：把 §6.6 初版留下的三个开放盲区一并补齐——表格详情含 `cellTexts`
矩阵预览（CELL/REVISION 详情同时补全实现）；`view_outline` 修订概览带 `countByAuthor` 与
`recent` 明细（type/author/文本片段，ref 可直通 view_element）；`view_stats` 带 `footerText`
预览。**只富化既有工具输出，不增工具数**（full 58 / compiler 6 基线不动）。终版
（runId `20261030-093623-921`）：**10/10，平均 4.20**——table-cell-edit 6→5、
tracked-replace 6→4（复审首次给出「del: 一百万元 / ins: 两百万元，author=审查员」的具体
证据）、footer-page-number 4→3（复审读到「页脚文本为第 1 页」）。至此 baseline 十个任务的
复审不再对已正确的文档误报。

### 6.7 批注创作 op：观测面先行的增量（2026-10-30）

批注是修订的近亲（同为「作者署名的协作标记」），core 早已备好全链路
（`Paragraph.addComment` / `doc.comments()`），但 compiler 面没有 op、view 层零观测——
若只加 op 不补观测，批注任务会重演 §6.6 的误报循环。本次按「写读对称」一次到位：

- **写侧**：`edit_document` 新增 `comment.add`（op 10→11，`anchor+text+author` 必传，
  整段范围批注）。批注不改段落文本，**不受同锚点改文本独占约束**（可与 `replace_text`
  同批作用于同段）——这是它与 tracked.\* 的关键差异。
- **读侧（core）**：新增 `Paragraph.comments()`——锚定在本段的批注列表（活对象语义）。
  这是 comment→paragraph 映射的唯一公共入口，POI 扫描（CTP 子树内 `commentRangeStart`）
  下沉 `CommentNodes.commentsAnchoredIn`。已知语义：`addComment` 把锚点插在段首，同段
  多条批注按 XML 出现序读取是**后写先出**（LIFO）。
- **读侧（view/compiler）**：三处恒出现——`view_element` 段落详情 `comments`
  `[{id,author,text}]`（空数组=无）；`view_stats` `commentCount`；`view_outline` 批注概览
  `CommentOverview{total,countByAuthor,recent[{paragraph,author,snippet}]}`（与修订概览
  同型，ref 可直通 view_element）。compiler 面的 `read_document` 以整行元数据注释呈现
  `<!-- 批注 pN [作者]: 内容 -->`（round-trip 安全，解析器跳过整行注释）。
- **评测**：baseline 增至 11 条（`comment-add`：锚定段/作者/内容关键词/正文不动四类
  check，防恒真由「批注存在」在 seed 上失败保证）。

结果（runId `20261030-100451-694`，compiler 面）：**11/11，平均 3.91**（§6.6 终版 10 任务
4.20）。`comment-add` 首跑即过：current_document → read_document →
`comment.add`（p1，「请核实该预算金额的数据来源。」）→ review 达成，4 次调用；复审给出
具体证据（引用批注原文与作者），未出现 §6.6 式盲区循环——观测面先行一步到位的收益。

### 6.8 模板填充与渲染预览：P2 收尾（2026-10-30）

P2 路线图最后两块落地，compiler 面 6→8 入口（full 面 58 不动，A/B 基线保持）：

- **fill_template**（模板 = 当前文档，mustache 子集）：`{{key}}` 值替换——**跨 run 占位符**
  按 `text()` 合并命中（Word 拆 run 是常态），重写用「首 run 承载全文、其余清空」而不是删光
  重建：重建需克隆样式，而 `Run.font()` 写侧四槽同写会把西文字体名灌进 eastAsia 槽污染中文；
  `{{#key}}…{{/key}}` 条件块（falsy 删块、标记行恒删）；表格模板行按列表复制——core 新增
  `Table.copyRowTo`（XML 深拷贝经 POI `addRow(row,pos)` 同步行缓存，底纹/边框/行属性保留；
  直接改 CTTbl 绕缓存是与 tracked changes 同族的陷阱）。缺 key 保留原样 + warning——模型可
  补数据重试，宽容优于静默清空。
- **render_preview**：文档 → soffice headless → PDF/PNG（首页），只读不置 dirty。工程坑：
  LibreOffice 同 profile 单实例锁（每次调用独立 `-env:UserInstallation` 规避）；无 soffice
  的环境返回明确错误而非挂死。**不进 eval**——eval check 只读文档状态、够不到文件系统副
  作用，覆盖走 `assumeTrue(soffice)` 集成测试。
- eval baseline 增至 12 条（`fill-template`：占位符清零/值正确/条件块/表格行展开四类 check）。

结果（runId `20261030-104446-951`，compiler 面 8 工具）：**12/12，平均 4.50**——11 条回归
任务零退化（面 6→8 未引入工具选择混乱），`fill-template` 首次修复后 5 次调用通过
（current_document → read_document → fill_template → read_document → review 达成，
复审引用「调研/3、开发/5」具体证据）。值得一提的插曲：首版 eval 11/12，fill-template 的
check 失败但工具报告成功、复审也「达成」——trace + 落盘 XML 验尸发现 **POI 5.3
`XWPFTable.addRow(row,pos)` 的脱链陷阱**（字节码验证：传入 wrapper 进缓存、内容却复制进自建
tr；经返回行的写入落进不在文档树里的对象，内存读正确、落盘原样）。修复 = core
`copyRowTo` 改用**影子行技法**（手动 `insertNewTr` 填充树内元素 → `addRow` 同步缓存 →
`removeTr` 删影子行），配 save+reopen 回归测试锁死——纯内存断言测不出这类「内存 vs XML」
分裂，教训已写进 spec。

### 6.9 P3 第一步：baseline 12→22（2026-10-30）

补齐 P3 要求的「歧义指代、重复文本」及组合类缺口，新增 10 条（seed/check 全部确定性、
防恒真由 `EvalTasksTest` 全组强制）：

| id | 考点 |
|---|---|
| insert-list | Markdown 列表块创作（3 次调用收敛，全组最少之一） |
| delete-paragraph | 按内容描述定位并删除段落 |
| ambiguous-target | **歧义指代**：三段中两段含「预算」，按「超支」定位唯一目标段（4 次） |
| scoped-replace | **重复文本 + 范围限定**：三段都有「待定」，只改第二段（4 次） |
| replace-with-format | 带格式替换：末段换成加粗文本——`replace_text` 当时还不吃 Markdown，模型需自我纠错（§6.11 已修复：现在支持行内标记） |
| table-add-row | 表格加行（4 次） |
| multi-step-combo | 一次 edit_document 批内三件事：居中+批注+文末插表（5 次） |
| fill-template-false | 模板假值条件块删除、真值块保留、空列表删行（5 次） |
| tracked-insert-delete | 同段 tracked.insert+delete——同锚点独占拒绝后按提示拆两次调用（6 次） |
| line-spacing-all | 多锚点 set_style 一批表达全文行距（4 次） |

结果（runId `20261030-113314-518`，compiler 面 8 工具，27B）：**22/22，平均 4.68**。
12 条回归任务零退化；10 条新任务**首跑全过**。两个值得记录的行为：

- **replace-with-format 9 次**（全组最高）：模型先 `replace_text` 填了字面 `**结论：通过**`
  （replace_text 是纯文本替换，不吃 Markdown——正确行为），复核发现后试
  `set_style style=bold`（不存在，明确拒绝），最终 delete+insert `**…**` 收敛。暴露的
  真实缺口：compiler 面**没有「给既有文本加粗」的 op**（full 面有 run 级样式工具），
  模型靠 delete+insert 自愈。是否补 `set_style.bold` 类 op 由后续评测数据决定。
- **tracked-insert-delete 6 次**：第一批两个 tracked op 共享锚点被独占规则整体拒绝
  （错误消息明确提示「拆成两次 edit_document」），模型一次拆分即通过——契约可教学性
  得到验证。

14B/27B 双模型对比延后（本地仅 27B）；页眉写不进 baseline（compiler 面无页眉 op，
属已知能力边界，与 §6.4 时页脚缺口同型——出现真实需求再补）。

### 6.10 质量 post-condition：写结果自带质检反馈（P2 收尾，2026-10-30）

P2 最后一项：「把 `QualityCheckTools` 升级成编译器的 post-condition，而不是事后可选检查」。
与「复审/保存由应用层强制」（§3.5）同精神——**写工具的返回自带质量信号**，模型不必记得
单独调 `validate_document`：

- `create_document`（换血后新文档）、`edit_document`（非 dry-run）、`fill_template` 三个
  写工具的成功路径自动跑 10 项检查：失败项全量放 `data.qualityIssues`
  （`[{name,severity,message}]`，全过不设键），message 追加摘要（「质量检查通过」/「N 项
  未过 + 名称 + **提示性：除非用户要求排版规范，无需主动修复**」）。
- **非阻塞**：写入已发生，检查纯读不回滚；检查自身异常按「无信号」处理。dry_run 不跑
  （未写入无 post-condition 可言）。`validate_document` 保留（全量报告 + 编号，另一层用途）。
- **噪音治理**：`toc` 建议项（「有标题但没插目录」）不进 post-condition——它是内容完整性
  建议而非写质量信号，每次带标题的 create 都会触发，进来就是恒定噪音；要看它走
  validate_document。seed 未排版段落的 cjk-indent 警告**保留**但摘要明确「提示性」——
  提示词同步声明「无需为 qualityIssues 追加修改、不必再单独 validate」。

eval（runId `20261030-121824-988`）：**22/22，平均 4.41**（§6.9 时 4.68）——post-condition
未触发「警告修复循环」（护栏生效），且 `validate_document` 全程 **0 次调用**（写结果自带
质检后它对写后校验已是冗余；最高调用任务 8 次，未新增）。

### 6.11 replace_text 行内格式与 set_style 加粗：把模型的正确直觉变成正确结果（2026-10-30）

§6.9 记录的 replace-with-format 9 次自愈暴露了缺口：compiler 面没有「给既有文本加粗」的
op。trace 复盘给出的诊断不是「模型不会」，而是 **API 不自洽**——该编译器的内容语言是
Markdown（create_document 正文、table.set_cell 都解析行内标记），`replace_text` 的 `text`
却是全链路唯一按字面处理的文本路径。模型写 `text=**结论：通过**` 是正确直觉撞上不一致
的接口；它随后试的 `set_style style=bold` 也是合理猜测。

修法（不新增 op，补齐语义）：

- **`replace_text` 的 `text` 改按行内标记解析**（`**粗**`/`*斜*`/`` `代码` ``，复用
  `MarkdownParser.parseInline`；未闭合标记原样保留，纯文本路径行为与 v1 完全一致）。
  单 run 命中做**外科手术式拆分**：源 run 保留前缀，替换片段逐 span、命中间/后缀文本
  均以源 run 的**深拷贝**承载（完整 rPr 继承，含 eastAsia 槽），span 再叠加粗/斜/代码
  字体。给既有文字加粗 = 身份替换 `find=逾期率 text=**逾期率**`。
- **core 新增 `Paragraph.copyRun(int i)`**：深拷贝第 i 个 run 紧随其后插入。与
  `Table.copyRowTo` 同一「影子」家族，但更稳：让 POI 自己的 `insertNewRun` 创建**并登记**
  树元素与包装（避开 `XWPFTable.addRow` 缓存外部包装的陷阱），再 `getCTR().set(copy)`
  填内容。save+reopen 测试锁定。
- **`set_style` 增加 `bold`/`italic` 布尔**：整段字符格式，可与 style=body/heading、
  align、line_spacing 叠加（后应用，与字体/字号正交）。给「写成 `style=bold`」的猜测
  一条正确的表达路径（错误提示也会引导成独立布尔字段）。

**实现期的拆分顺序陷阱**（测试抓住）：拷贝必须始终复制**源 run**——若顺序 `copyRun(cursor++)`
复制「上一份拷贝」，格式会逐份累积（第二份起继承第一份的加粗）。正确做法：所有 piece
都从源 run 索引拷贝（每次都插在源 run 紧后），按 piece **倒序**插入，最终恰好正向。

eval baseline 扩到 **23**：新增 `bold-existing-phrase`（段内既有词加粗、其余文字不许动）
验证子范围身份替换路径；replace-with-format 从「模型自我纠错」变成「一步正确」。
跨 run 整段重建顺手修了一个潜在边角：重建现在会清掉超链接（旧实现按 runs() 计数循环
删除，超链接可能幸存并造成重建后文本重复）。

结果（runId `20261030-124819-241`，compiler 面 8 工具，27B）：**23/23，平均 4.26**
（§6.10 时 22 任务 4.41）。两个直接验证：

- **replace-with-format 9→4 次**：模型的第一个（旧版本里「错」的）op 就是
  `replace_text text=**结论：通过**`——一步命中，不再有字面星号、不再试
  `set_style style=bold`、不再 delete+insert 自愈。接口对了，模型本来就会。
- **bold-existing-phrase 首跑通过（4 次）**：身份替换 `find=逾期率 text=**逾期率**`，
  复审确认目标 run bold=true 且其余文本未动——子范围拆分路径（前后缀继承原样式）成立。

`validate_document` 全程仍 0 次调用，无警告修复循环；22 条回归任务零退化。

### 6.12 full 面对齐复跑：8 个失败全是静态缺口，零「模型做砸」（2026-10-30）

§6.11 后 compiler 面是 23/23@4.26，而 full 面的对照数字停在 §6.6 的 9/10@6.2（runId
`20261030-091648-612`）——中间隔了五个观测/能力批次。在 23 任务 baseline 上复跑 full 面
（runId `20261030-125616-728`，58 工具，同模型同温度）：

| 指标 | full 面 | compiler 面（§6.11） |
|---|---|---|
| 通过率 | **14/23（60.9%）**＊ | **23/23（100%）** |
| 平均调用 | **11.83**（2.8×） | **4.26** |
| 撞 24 轮迭代上限 | **4 任务**（footer/insert-list/table-add-row/multi-step-combo） | 0 |
| 已知能力缺口失败 | 9（复跑后全量 flag，见下） | 0 |

＊runId 728 原始口径 15/23——其中 bold-existing-phrase 是**空洞通过**（见下），check
加严后按失败计（复验 runId `131105-091` 失败 / compiler `131042-172` 仍 4 次通过）。
原十任务子集 9/10、平均 7.0 次调用（612 时 6.2）：**观测面富化没有改变 full 面
baseline**——它修复的是「复审误报已正确的文档」，对物理不可能的任务无能为力。

**逐失败归因（全部静态能力缺口，零「任务可做但模型做砸」）**：

| 任务 | 调用 | 缺口 |
|---|---|---|
| footer-page-number | 24 | 页脚只读（写工具 ref 只解析正文）——模型误把正文「正文」改成「第 页」后 review 循环到上限 |
| comment-add | 24 | 无批注创作工具 |
| multi-step-combo | 24 | 批注部分（居中✓ 插表✓——两步可达成） |
| fill-template | 26 | 无 fill_template；条件块整段删除还需段落删除 |
| fill-template-conditional-false | 13 | 同上 + 空列表模板行删除 |
| insert-list | 24 | `insert_paragraph` 无列表参数、正文段无列表工具（§6.3 create 组同款缺口） |
| delete-paragraph | 5 | 无正文段落删除工具——模型用 tracked run del 做最接近的事后快速放弃 |
| table-add-row | 24 | **真空单元格陷阱**（poi-bridge N2）：`add_table_row` 的新格 0 段落，无「单元格内建段落」工具可填——模型陷入不可能状态后**破坏性重建表头**（表头「模块」被删错位） |
| bold-existing-phrase（加严后） | 27 | 无 run 拆分——只能整 run 加粗，bold↔unbold 振荡，与「只加粗该词」不可调和 |

**三个新记录**：

1. **复审在不可能任务上产生修复循环**。footer trace：模型正确找到页脚 run 的 ref，
   但 `replace_run_text` 只按正文索引寻址（误改正文），复审持续报「未达成」→ 模型重试
   到 24 轮上限。与 §6.10 post-condition 的噪音治理同族、方向相反：post-condition
   过滤「不该修的信号」，这里的循环本身是「任务不可达」的信号——能力缺口补齐前，
   上限截断是唯一止损。
2. **真空单元格陷阱首次在 eval 里被踩实**。N2 在 spec 里躺了两天，这次模型用 24 次
   调用 + 一次破坏性重建把它变成一等数据点：**「加行」与「能写进加的行」之间隔着
   一个段落创建工具**。compiler 的 `table.add_row` 一步内建格填文本，同一任务 4 次通过。
3. **check 要防工具面的退化通过**。bold-existing-phrase 初版 check 在「整段唯一 run
   全加粗」时空洞通过（「其余文字未被误加粗」找不到非目标 run 可检）。修复：目标词
   check 追加**严格子集断言**（段内存在目标词以外的文字 ⇒ 必须存在非粗体 run）。
   该任务连同其余 7 个缺口任务已按 footer-page-number 先例 flag `capabilityGap=true`
   （`EvalTasksTest#marksKnownCapabilityGapTasks` 锁定 9 条）——flag 只影响报告分类
   （compiler 面全过时 inert），full 面报告从此自带缺口标注。

**结论**：A/B 差距（100% vs 60.9%，4.26 vs 11.83 次）**全部由能力缺口构成**，与模型
能力无关——full 面做得到的任务（15 条）它都做到了。compiler 面的胜势来自把缺口逐个
内建（页脚域/批注/列表/段落删除/表格行/模板/行内格式/run 拆分），而 full 面的缺口
是结构性的：细粒度工具天生凑不齐「整段级语义」。

---

## 7. 明确不做什么

- **不继续加工具、加 Skill、加 SubAgent 来补**。复杂度已经超过模型能稳定驾驭的范围。
- **不在接口收敛前微调模型**。那是在拟合一个坏接口；接口对了，小模型也能做好。
- **不把 61 个工具原样包成 MCP**。MCP 只是传输层，不解决工具粒度和表示问题。
- **不依赖模型「记得」复审/保存**。保存已经收回应用层了，复审也应该一样。
- **不把 `paragraph_index` 当身份**。索引只适合显示，不适合跨调用。

---

## 8. 一句话

**core 已经是护城河；下一个护城河不是更多工具，而是「文档 IR + 编译器 + 模板 + 验证闭环」。**

把 LLM 从「POI 程序员」变成「文档作者」，让它写 Markdown/IR，让编译器负责 Word 的脏活。
工具从 61 个降到 5 个，调用从十几次降到两三次，效果和成本都会立刻不一样。

---

## 附录：一次最小探针

2026-10-29，用当前端点 `qwen3.8-27b` 跑了一个最小探针（不是完整 demo，未接复审 SubAgent）：

| 任务 | 工具调用 | 结果 |
|---|---|---|
| 开头居中标题 + 末尾追加一段 | 3 次 | 成功，7.3s |
| 全文宋体小四 + 1.5 倍行距 + 标题黑体 | 12 次 | 字体/标题成功，正文行距无工具，模型如实报告 |
| 末尾 2×3 表格 + 表头加粗 | 5 次（含复审） | 成功，但建表 + 表头样式分两次 |

观察：

- 简单任务模型能做好，说明模型不是主要瓶颈；
- 稍复杂一点就 10+ 次调用，其中一半在读结构；
- 模型拿到 canonical ref 仍倾向用 `paragraph_index`；
- 复审是否调用取决于模型；
- 模型最后说「均成功落盘」，但其实没有保存工具，是应用层 flush 的功劳。

这些观察和代码结构一致：**接口层需要收敛，而不是继续加能力。**
