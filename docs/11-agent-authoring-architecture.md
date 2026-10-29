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

### P2：编辑与模板

- `edit_document` patch 引擎，接 tracked changes / comments。
  （patch 引擎已落地并 A/B（§6.4）：6 op、原子、dry-run；修订/批注 op 是下一批。）
- `fill_template`：占位符、重复表格行、条件段。
- `render_preview`：LibreOffice headless → PDF/PNG。
- 把 `QualityCheckTools` 升级成编译器的 post-condition，而不是事后可选检查。

### P3：评测与多模型

- 20~30 条任务，覆盖：新建、改写、全文格式、表格、页眉页脚、修订、批注、
  模板填充、歧义指代、重复文本。
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
  ①`insert-centered-title` 的波动（11~23 次）：模型把 read_document 里的 `{align=center}`
  记号误当正文、被 set_style 拒绝后重建循环（已加错误提示指引 + prompt 说明，仍偶发）；
  ②失败任务的重试循环拉高均值（21/11 次）。这是提示词/交互问题，不是架构问题。

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
