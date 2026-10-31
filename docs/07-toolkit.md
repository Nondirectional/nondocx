# 07 · nondocx-toolkit

`nondocx-toolkit` 把 nondocx 的 docx 读写能力**封装成 LLM Agent 可调用的工具集**。它不是给人类用的 API（人类直接用 `nondocx-core`），而是给 **Agent × docx** 场景：让 LLM 像操作「文档编辑器」那样读取、编辑、保存 `.docx`。

> 底层基于 nonchain 的 `ToolRegistry` + `@ToolDef`。工具方法调的是 `nondocx-core` 的活对象 API，不重新实现任何 docx 语义。

---

## 1. 它解决什么问题

直接给 Agent 用 `nondocx-core` 不顺手 —— LLM 工具调用需要：

1. **粗粒度、自描述**的操作（不是 `run(0).bold()` 这种细粒度链式）
2. **字符串句柄**定位文档（Agent 不持有 Java 对象引用，靠 `docId`）
3. **错误以字符串返回**（不是抛异常，让 LLM 看到错误消息自己重试）
4. **批量操作**（一次调用处理多个目标，减少 LLM 往返）

`nondocx-toolkit` 把 `Document`/`Paragraph`/`Run` 的活对象 API 包装成满足以上四点的工具方法。

---

## 2. 一个最小可用例子

```java
import com.non.chain.agent.Agent;
import com.non.chain.tool.ToolRegistry;
import io.github.nondirectional.docx.toolkit.DocxToolkit;

// 1) 装上工具集
DocxToolkit toolkit = new DocxToolkit();
ToolRegistry registry = toolkit.scanAll(new ToolRegistry());

// 2) 组装 Agent
Agent agent = Agent.builder(llm, registry)
    .systemPrompt("你是 docx 文档编辑助手……")
    .build();

// 3) 跑起来 —— Agent 会自己调 open_docx / read_paragraph / save_docx 等工具
agent.run("把 /tmp/a.docx 第一段第一个 run 的文本改成 'Hello'");
```

> 取自 [`DocxAgentExample.java`](../nondocx-examples/src/main/java/io/github/nondirectional/docx/examples/agent/DocxAgentExample.java)，可运行（需 `DASHSCOPE_API_KEY`）。

非 Agent 场景也可直接持有 toolkit 的字段逐个调工具方法（见 §6）。

---

## 3. 工具组 + 一个门面

工具按功能域分成**九个类**，由 `DocxToolkit` 门面统一装配：

| 字段 | 类型 | 职责 |
|---|---|---|
| `session` | `SessionTools` | open/save/close + 文档结构概览（**会话状态的源头**） |
| `body` | `BodyTools` | 正文 run / 超链接 / 文本搜索 |
| `table` | `TableTools` | 单元格读 / 单元格内 run 读改 |
| `headerFooterToc` | `HeaderFooterTocTools` | 页眉页脚 + 目录（只读） |
| `trackedChangeQuery` | `TrackedChangeQueryTools` | 修订查询 / accept / reject |
| `trackedChangeAuthoring` | `TrackedChangeAuthoringTools` | 修订创作：insert/delete/replace/move/mark |
| `qualityCheck` | `QualityCheckTools` | 版式/兼容性自检（10 项检查，报告不修复） |
| `view` | `ViewTools` | 语义视图（outline/text/annotated/stats/issues/element） |
| `compiler` | `CompilerTools` | 文档编译器（实验性）：Markdown → docx + patch 编辑，create/read/validate/edit_document |

### 为什么需要门面（会话状态共享）

七个工具类必须**共享同一份** `sessions`/`seq`：

> Agent 在一轮对话里 `open_docx`（SessionTools）打开的文档，紧接着 `read_paragraph`（BodyTools）、`list_tracked_changes`（TrackedChangeQueryTools）都要能按 `docId` 取回。

门面负责把 `SessionTools` 自建的会话状态**注入**给其它六个类：

```java
// DocxToolkit 构造函数内部
this.session = new SessionTools();                          // 自建 sessions/seq
this.body = new BodyTools(session.sharedSessions(), session.sharedSeq());   // 注入
this.table = new TableTools(session.sharedSessions(), session.sharedSeq());
// ... 其余五类同样注入
```

`scanAll(registry)` 把七个类逐一 scan 进同一个 registry，让 Agent 一次会话能调任意一组工具。

> **线程模型**：为单 Agent 实例设计，内部状态未做并发保护，**不要跨 Agent 共享**同一个 `DocxToolkit`。

---

## 4. 工具清单（按组）

### SessionTools（会话）

| 工具 | 作用 |
|---|---|
| `open_docx` | 打开文件，返回 `docId` 句柄 |
| `save_docx` | 按 `docId` 保存到 `output_path`（覆盖写） |
| `close_docx` | 关闭并释放会话（幂等） |
| `get_document_overview` | 文档结构概览：正文段落数、正文表格数、body 元素数、section 数 |

### BodyTools（正文）

| 工具 | 作用 | 批量 |
|---|---|---|
| `read_paragraph` | 读段落（索引或 `ParagraphRef`；ref 可指向页眉/页脚） | ✅ `paragraph_indexes` 数组 |
| `update_paragraph_alignment` | 改段落对齐（`ParagraphRef` 或旧索引） | ✅ `edits` 数组 |
| `update_paragraph_spacing` | 批量改正文段落行距（`ParagraphRef` 或索引） | ✅ `edits` 数组 |
| `read_run` | 读 run（`RunRef` 或旧索引；`RunRef` 可指向页眉/页脚） | ✅ `runs` 数组 |
| `replace_run_text` | 改 run 文本（`RunRef` 或旧索引） | ✅ `edits` 数组 |
| `update_run_style` | 改 run 样式（`RunRef` 或旧索引） | ✅ `edits` 数组 |
| `insert_paragraph` | 按 body 顺序插入段（开头/中间/末尾） | ✅ `paragraphs` 对象数组 |
| `read_hyperlink` | 读超链接 | 单条 |
| `update_hyperlink` | 改超链接 text/url（都可选） | 单条 |
| `search_text` | 跨容器文本搜索（页眉页脚也命中） | 返回多结果 |

### TableTools（表格）

| 工具 | 作用 | 批量 |
|---|---|---|
| `create_table` | 末尾创建表格并按二维数组填充单元格 | `rows` 二维数组 |
| `set_table_borders` | 设置表格边框（当前支持 NONE 无边框） | 单条 |
| `merge_table_cells` | 合并单元格（HORIZONTAL / VERTICAL） | ✅ `merges` 对象数组 |
| `read_table_cell` | 读单元格（`CellRef` 或旧坐标） | ✅ `cells` 数组 |
| `read_table_cell_run` | 读单元格内 run（`RunRef` 或旧五级坐标） | 单条 |
| `replace_table_cell_run_text` | 改单元格 run 文本（`RunRef` 或旧五级坐标） | ✅ `edits` 数组 |

### HeaderFooterTocTools（页眉页脚 + 目录，只读）

| 工具 | 作用 |
|---|---|
| `read_header_footer` | 按 section/paragraph 索引读页眉/页脚，返回 part ref、paragraph ref 与每个 run 的 run ref |
| `read_header_footer_ref` | 按 `HeaderFooterRef` 读整个页眉/页脚及内部段落/run ref |
| `read_toc` | 读首个目录的条目 |

> 页眉/页脚 ref 现在可被 `view_element`（段落/run/part）与 `read_run`（run）解析，
> 返回结果带 `location`（如 `页脚 section=0 variant=default 段落 0`）。
> 写工具仍只接受正文 ref；对页脚段落/run 调用会返回明确的「仅支持正文」错误，
> 不会按正文索引静默修改。本面（full）页眉/页脚写入不在范围内——页眉/页脚写入由
> compiler 面的 `header.set_text`/`footer.set_page_number` op 提供。

### TrackedChangeQueryTools（修订查询 + 处理）

| 工具 | 作用 | 批量 |
|---|---|---|
| `get_tracked_changes_enabled` | 查修订开关 | — |
| `set_tracked_changes_enabled` | 改修订开关 | — |
| `list_tracked_changes` | 枚举修订，同时返回 `RevisionRef` 与兼容 stable id | — |
| `get_tracked_change` | 按 `RevisionRef` 或兼容 stable id 取单条 | — |
| `apply_tracked_changes` | 按 `action=ACCEPT/REJECT`、`target=TEXT_OR_MOVE/PROPERTY/CELL` 处理指定 refs/ids | ✅ `ids` |
| `apply_text_revisions` | 按 `action=ACCEPT/REJECT`、`scope=ALL/AUTHOR` 批量处理文本/移动类修订 | — |

> 修订的 family gate / 异常契约 与 core 一致，详见 [05/03 accept-reject](./05-tracked-changes/03-accept-reject.md)。

### TrackedChangeAuthoringTools（修订创作）

| 工具 | 作用 | 批量 |
|---|---|---|
| `insert_tracked_run` | tracked 插入 | ✅ `edits`（共享 author） |
| `delete_run_tracked` | tracked 删除（支持整 run 或跨 run 精确文本） | ✅ `edits` |
| `replace_run_tracked` | tracked 替换 | ✅ `edits` |
| `mark_style_change_tracked` | rPrChange 创作（bold/italic/color） | 单条 |
| `mark_tracked_cells` | 按 `change_type=INSERTED/DELETED` 标记 cell 存亡 | ✅ `cells` |
| `move_run_tracked` | 移动 run | 单条 |


`delete_run_tracked` 的每条 `edit` 二选一：旧模式传 `paragraph_index + run_index` 删除整个普通 run；新模式传 `paragraph_index + target_text` 删除段落中的精确可见文本，目标可以跨多个普通 run 或只占 run 的一部分。`target_text` 大小写敏感；未传 `occurrence` 时必须唯一命中，重复文本要传从 0 开始的 `occurrence`。第一版只覆盖正文普通 run，改完仍需调用方按现有流程 `save_docx` 落盘。

### QualityCheckTools（文档质量自检）

对内存中的文档跑版式/兼容性自检，返回 ❌/⚠️/✅ 报告。让 Agent 写完文档后能自查「版式有没有问题」，而不必肉眼排查或反复打开 Word/WPS 验证。

| 工具 | 作用 |
|---|---|
| `check_quality` | 跑版式/兼容性自检，返回结构化报告（可选 `checks` 数组过滤） |

**10 项内置检查**（借鉴 docx skill 的 `postcheck.py`，但走 nondocx 内存活对象 API 而非解包读 XML）：

| 检查项 | 报警条件 | 级别 |
|---|---|---|
| `blank-pages` | 连续 ≥3 个空段（可能产生空白页） | ⚠️ |
| `line-spacing` | 正文存在 ≥2 种行距 | ⚠️ |
| `table-pagination` | 多行表格首行未设 `headerRow` 或数据行未设 `cantSplit` | ⚠️ |
| `image-overflow` | 图片宽度超过页面可用区 | ❌ |
| `font-fallback` | 使用了白名单外的罕见字体（目标系统可能缺失） | ⚠️ |
| `cjk-indent` | CJK 正文段落无首行缩进 | ⚠️ |
| `heading-levels` | 标题层级跳级（如 H1→H3） | ⚠️ |
| `shading-solid` | 误用 SOLID 底纹（WPS 渲染为黑块，见 [renderer-compatibility.md](../.trellis/spec/backend/renderer-compatibility.md#shading-solid) `#shading-solid`） | ❌ |
| `toc` | 有标题无 TOC 域，或有 TOC 域但无 Heading 标题 | ⚠️ |
| `cleanliness` | 占位符（TODO/待填写）/ Markdown 残留（`**bold**`/`[link](url)`） | ⚠️ |

**只报告不修复**——检查发现问题后，Agent 调用其它工具方法修复（如 `table.row(0).headerRow(true)` 修表格分页、`section.cleanEmptyPageNumbering()` 清空页码元素）。

**调用示例**：

```json
check_quality({
  "doc_id": "d1",
  "checks": ["shading-solid", "table-pagination"]  // 可选；空则全量
})
```

返回示例：

```
📋 文档质量自检报告: d1
  ❌ [shading-solid] 误用 SOLID 底纹（WPS 显示黑块）：单元格（表格1,行2,列1）。见 renderer-compatibility.md#shading-solid
  ⚠️ [table-pagination] 表格 1 首行未设 headerRow（跨页时表头不重复）
  ───
  通过 0/2 | ❌ 1 errors | ⚠️ 1 warnings
```

---

### CompilerTools（编译器，实验性）

「文档 IR + 编译器」路线（[11 · Agent 写作架构建议](11-agent-authoring-architecture.md)）的 Agent 入口：
把「写整份文档」从几十次细粒度 API 调用收敛为**一次 Markdown 提交**。模型只表达结构与内容，
排版脏活（CJK 双字体槽、行距换算、首行缩进、表头样式、WPS 安全列宽、表格分页控制）由
编译器 + 样式 profile 兜底。

| 工具 | 作用 |
|---|---|
| `create_document` | Markdown 子集 + 样式 profile 一次性编译整份 docx，**替换** doc_id 会话文档（句柄不变、generation+1） |
| `read_document` | 整篇文档的紧凑 Markdown 视图，行内格式按 `**粗**`/`*斜*`/`<u>下划线</u>` 回显（粗+下划线为 `**<u>…</u>**`，读回可还原），段落/表格带 `<!-- pN -->`/`<!-- tN -->` 短 ID，页眉/页脚以 `<!-- 页眉: … -->`/`<!-- 页脚: … -->` 元数据行呈现，修订以文档尾 `<!-- 修订 … -->` 元数据行呈现（无修订不输出），与写侧同构（round-trip） |
| `validate_document` | 复用 10 项质量检查，输出带 `v1/v2` 编号的问题清单 |
| `edit_document` | 对既有文档应用一批**原子**编辑 ops（insert/replace_text/delete/set_style/table.set_cell/table.add_row/tracked.*/footer.set_page_number/header.set_text/comment.add），短 ID 锚点，支持 dry_run |
| `fill_template` | mustache 子集数据填充：`{{key}}` 值替换（跨 run 命中）、`{{#key}}…{{/key}}` 条件块、表格模板行按列表复制（保留格式） |
| `render_preview` | LibreOffice headless 渲染 PDF/PNG（只读；soffice 路径经 `NONDOCX_SOFFICE` 配置，png 为首页） |

**Markdown 子集**：`#`~`######` 标题（可带 `{align=center|left|right|both}` 行尾属性）、段落、
`**粗体**`/`*斜体*`/`` `代码` ``/`<u>下划线</u>`、`- `/`1. ` 列表（缩进 2 空格一级）、`|…|` 表格
（首行为表头，支持 `\|` 转义）、围栏代码块（降级为等宽段落）。未知语法**宽容降级**为段落
并记 warnings，不拒绝。

`<u>…</u>` 的边界（与 `**` 同姿态）：内层可再写粗/斜/代码（下划线取并集）；未闭合或孤立的
`<u>`/`</u>`、空的 `<u></u>` 一律原样保留为文本（`****` 同规则）；不解析同名嵌套
（`<u><u>x</u></u>` 按最左闭合匹配、第二个 `</u>` 原样留字面）；标题（`#` 行）内不生效——
标题是单 run 扁平文本，标签被剥掉但不下划线；表格单元格在 `create_document` 里是**纯文本
通道**（`**` 与 `<u>` 同为剥标记），富文本单元格走 `edit_document` 的 `table.set_cell`。
字面量 `<u>` 无转义方式（与「字面 `*` 无法转义」同类限制）。

**样式 profile**（v1 内置两个）：

| profile | 策略 |
|---|---|
| `report-cn` | 黑体标题（H1 16pt 居中/H2 14pt/H3 12pt）、宋体+Times New Roman 正文小四（12pt）、1.5 倍行距、首行缩进 2 字符、表头加粗灰底（F2F2F2）、等宽百分比列宽、表头行跨页重复 + 数据行不拆分 |
| `plain` | 只做结构，不设任何排版属性 |

**CJK 关键兜底**：core 公开 API 的 `font()` 走 POI 四槽同写（ascii/hAnsi/cs/eastAsia 同一
字体名），而中文报告的规范排版是「西文 Times New Roman + 中文宋体」的**分离**双槽——
编译器经 `raw()` 只覆写 eastAsia 槽实现分离，这是公开 API 做不到的。

**调用示例**：

```json
create_document({
  "doc_id": "d1",
  "markdown": "# 项目周报 {align=center}\n\n本周完成了 **表格封装**。\n\n| 模块 | 状态 |\n|---|---|\n| core | 完成 |",
  "style_profile": "report-cn"
})
```

**edit_document 的 op 集**（v5，锚点 = `read_document` 的短 ID）：

```json
{"op":"insert","anchor":"p3","position":"after","block":"# 小节 {align=center}\n\n正文。"}
{"op":"replace_text","anchor":"p8","find":"旧文本","text":"新文本"}
{"op":"replace_text","anchor":"p8","find":"待定","text":"**通过**"}    // 替换后加粗（行内标记）
{"op":"replace_text","anchor":"p8","find":"逾期率","text":"<u>逾期率</u>"}  // 替换后加下划线（行内标记）
{"op":"delete","anchor":"p5"}
{"op":"set_style","anchor":"p8","style":"body"}          // 或 heading；也可只用 align/line_spacing/bold/italic
{"op":"set_style","anchor":"p8","bold":true}             // 整段字符格式，可与 style/align 叠加
{"op":"table.set_cell","anchor":"t0","row":1,"col":2,"text":"完成"}
{"op":"table.add_row","anchor":"t0","cells":["a","b"]}
{"op":"tracked.replace","anchor":"p1","find":"一百万元","text":"两百万元","author":"审查员"}
{"op":"tracked.replace","anchor":"p1","find":"待定","text":"**通过**","author":"审查员"}  // 修订也吃行内标记
{"op":"tracked.insert","anchor":"p1","text":"补充说明。","author":"审查员"}
{"op":"tracked.delete","anchor":"p1","find":"过期内容","author":"审查员"}
{"op":"tracked.set_style","anchor":"p2","find":"逾期率","bold":true,"author":"审查员"}  // 格式变更本身算修订(rPrChange)
{"op":"footer.set_page_number"}                          // format 默认「第 {page} 页」
{"op":"header.set_text","text":"**机密** 内部文档"}       // 页眉写文本（行内标记）
{"op":"comment.add","anchor":"p1","text":"金额需复核","author":"审查员"}
```

先 preflight 全部 op 再统一写入：锚点不存在、`find` 不在文中、行列越界、表格块非 end
插入、引用本 patch 前序删除的锚点、同一锚点多个改文本 op——任一失败**整体拒绝、零改动**。

**replace_text 的行内格式**：`text` 与 create_document 正文、table.set_cell 同一内容语言，
支持 `**粗体**`/`*斜体*`/`` `代码` ``/`<u>下划线</u>`（未闭合标记原样保留）。给既有文字加粗 = 身份替换
`find=逾期率 text=**逾期率**`；加下划线同理 `text=<u>逾期率</u>`。单 run 命中按 span **外科手术式拆分**：前后缀经 core
`Paragraph.copyRun` 深拷贝源 run（完整 rPr 含 eastAsia 槽继承），替换片段再套粗/斜/下划线/代码
字体；跨 run 命中整段重建（丢段内直接格式，记 warning）。纯文本（无标记）行为与 v1
完全一致。

**tracked.\*（修订模式）**：产出 Word 可审阅的 ins/del 修订（`author` 必传）。`tracked.replace`
是**最小 diff**——只 del「find」范围、ins 紧随其后（core `Paragraph.replaceTrackedRange`），
不是整段重建。`find` 多处命中全部替换。`tracked.insert`/`tracked.replace` 的 `text`
支持行内标记（`**粗**`/`*斜*`/`` `代码` ``/`<u>下划线</u>`，与 replace_text 同一内容语言）：多 span 生成
**同一 `<w:ins>` 内的多 run**（读侧仍一条 INS、文本拼接），每个 run 继承被替换范围首
run 的 rPr 再按 span 叠加格式。已知语义边界（**live/reopen 不同**，2026-10-30 实证修正）：
del 文本（delText）**永不**进入 `text()`；ins 文本在 **live 对象**（agent 会话内的
`read_document`）不可见，但 **save+reopen 后**会进入 `text()`（POI `getText()` 按 iruns
走，reopen 时 iruns 重建自 XML）——eval 读落盘文档按 reopen 口径。两种状态下 `runs()`
均不暴露 ins 内 run（run 级工具够不着，样式只能经创作 op 内部完成）——读修订用
`trackedChanges()`。

**tracked.set_style（格式变更修订）**：对既有文字的**格式变更本身算修订**（rPrChange 属性
修订，区别于 tracked.replace 的 del+ins 文本修订）。`find` 定位段内既有文字（精确子串，
多处命中全部处理）；`bold`/`italic` 三态显式设置——`true` 设置、`false` 取消
（`w:val="off"`）、缺省不动，**至少给一个**；`author` 必传。命中片段与邻文同 run 时按
`copyRun` 外科拆分（与 replace_text 同技法）后对目标 run 走两步式
`commitStyleAsTracked`：拆分后快照 → 改格式 → 提交，旧值树进 `<w:rPrChange>`（accept
保留新格式 / reject 还原旧格式）。段落 `text()` 不变；拆 run 重建段落结构，**参与**同锚点
改文本独占。读侧三处可见：`read_document` 文档尾 `<!-- 修订#N: RPR_CHANGE author=… 属
性=RUN_PROPERTIES 新[b] 旧[] -->` 元数据行、view REVISION 详情的 property 摘要、outline
recent[] 的 bold/italic 取证。已知边界：属性摘要只记属性名不记方向（「取消加粗」的新摘要
同为 `[b]`），方向以 run 实际格式呈现为准。

**footer.set_page_number**：页脚**追加**页码段落（不触碰正文），`format` 支持 `{page}`/`{pages}`
占位符（PAGE/NUMPAGES 域）。域带**缓存结果** "1"（五 run 形态：begin/instr/separate/result/end）
——POI/文本抽取器能读到「第 1 页」，Word/WPS 打开时按当前文档重算。同锚点独占规则不适用于
页脚 op（它不占段落锚点）。

**header.set_text（页眉写文本）**：页眉**追加**一个文本段落（不触碰正文），`text` 必填非空，
支持行内标记（`**粗体**`/`*斜体*`/`` `代码` ``/`<u>下划线</u>`，与 replace_text 同一内容语言）。写默认
section 的默认页眉（core `ensureHeader`，不存在则创建）；重复调用追加新段、**不替换**
既有页眉内容（替换语义留 future）。与页脚 op 同型：不占段落锚点、可与正文 op 同批。
读侧 `read_document` 以 `<!-- 页眉: … -->` 元数据行呈现（页脚行之前，存在且非空才输出）。

**comment.add（批注）**：给锚点段落加**整段范围**批注（core `Paragraph.addComment`），
`text`/`author` 必传（`author` 是 Word 批注窗格显示的作者名）。批注不改段落文本，不受同锚点
改文本独占约束（可与 `replace_text` 同批作用于同段）。读侧三处可见：`read_document` 的
`<!-- 批注 pN [作者]: 内容 -->` 元数据行、view 层段落详情 `comments` 数组（core
`Paragraph.comments()` 映射）与 outline 批注概览。

**fill_template（模板填充）**：模板 = 当前文档，语法是 mustache 子集（不嵌套）——`{{key}}`
值替换；`{{#key}}…{{/key}}` **整段标记**为条件块（falsy = false/缺失/空列表/空白串时删块，
标记行恒删）；表格里任一单元格含 `{{#key}}` 的行是**模板行**，`data[key]` 为列表时按元素复制
（core `Table.copyRowTo` XML 深拷贝——底纹/边框/行属性保留），行内 `{{field}}` 先取元素字段、
回退外层，标量元素用 `{{.}}`。**跨 run 占位符**按 `text()` 合并命中，重写用「首 run 承载全文」
（保留首 run 字体槽，规避 `Run.font()` 四槽同写污染 eastAsia）。缺 key 的占位符**保留原样** +
warning（补数据重试即可，不静默清空）。

**render_preview（渲染预览）**：当前文档 → LibreOffice headless → PDF 或 PNG（首页）。只读
（不改文档、不置 dirty），产物落临时目录。soffice 路径经环境变量 `NONDOCX_SOFFICE`（默认
`soffice`）；每次调用用独立 user profile 规避 LibreOffice 实例锁；超时 60s。结构检查看不见的
视觉缺陷（分页/溢出/表格断行）靠它。

**质量 post-condition**：`create_document`/`edit_document`/`fill_template` 的成功结果自动附带
10 项质量检查反馈（失败项 `qualityIssues` + message 摘要）——写完即见质量信号，无需单独
`validate_document`（后者保留给全量编号报告）。非阻塞；`toc` 建议项不进反馈（防恒定噪音）；
摘要标注「提示性」，seed 未排版段落的 cjk-indent 类警告无需模型主动修复。

**位置约定**：本组在 `DocxToolkit.scanAll` 里（能力清单第 9 组，EXPERIMENTAL 级），但
**默认不进 demo/eval 的 full 工具面**——是否成为默认面由评测数据决定（见 doc 11 §P1/P2）。

---

## 5. 稳定寻址与批量调用

### 5.1 稳定语义引用（P0-01）

toolkit 现在用强类型 ref 表达元素身份：`ParagraphRef`、`RunRef`、`TableRef`、`CellRef`、
`HeaderFooterRef`、`RevisionRef`。规范化字符串只用于工具 payload、日志和传输；内部由
`ElementRefs.parse(...)` 统一解析，不把位置索引当身份。

- **SESSION**：只在当前 `sessionGeneration` 内有效。close/reopen 后解析旧 ref 返回
  `generation_mismatch`。
- **PERSISTENT**：第一版支持“原本已有 `w14:paraId`”的正文段落。save/reopen 后仍可按 paraId
  定位；纯读取和签发 ref 不会自动补写 paraId。
- **位置变化不漂移**：在目标前插入段落后，旧 ref 仍解析到原对象。
- **删除明确失效**：段落、run、表格、单元格或修订被删除后，旧 ref 返回
  `element_removed`，不会返回已脱离文档树的旧 wrapper。
- **双入口校验**：ref 与旧索引/坐标同时提供时必须指向同一元素，否则返回
  `stale_ref` 并拒绝写入。

旧索引暂时保留兼容，但新调用方应保存读操作返回的 canonical ref，并在后续 plan/review/commit
持续复用。索引只用于显示和迁移期兼容，不再承担稳定身份。

```json
replace_run_text({
  "doc_id": "doc-1",
  "edits": [
    {"ref": "doc:.../run:session:r-3", "text": "新文本"}
  ]
})
```

稳定错误码包括：`stale_ref`、`element_removed`、`generation_mismatch`、
`document_mismatch`、`ref_type_mismatch`、`invalid_ref`。

### 5.2 单次 vs 批量调用（v2 升级）

核心工具支持**一次操作多个目标**。单次调用时传长度 1 的数组即可，调用方不用区分两套 API。

**批量示例**（一次改三个 run）：

```json
replace_run_text({
  "doc_id": "d1",
  "edits": [
    {"paragraph_index": 0, "run_index": 0, "text": "新文本1"},
    {"paragraph_index": 0, "run_index": 2, "text": "新文本2"},
    {"paragraph_index": 1, "run_index": 0, "text": "新文本3"}
  ]
})
```


跨 run 精确删除示例：

```json
 delete_run_tracked({
  "doc_id": "d1",
  "author": "甲",
  "edits": [{
    "paragraph_index": 0,
    "target_text": "言简意赅地说明通过本文件要达到的期望目标。"
  }]
})
```

重复文本时显式指定 `"occurrence": 1`；未指定且命中多次会返回歧义错误，不会修改文档。

- **部分失败不中断**：批量操作中某条失败不影响其它条，返回每条的成功/失败明细
- **同段删/换多个 run 不索引漂移**：旧 `delete_run_tracked` run 模式自动去重；跨 run 文本模式按段落精确文本定位，重复目标必须显式指定 occurrence
- **减少 LLM 往返**：要同时改/读多处时优先用批量

> 这套批量能力是 toolkit 相对于直接用 core 活对象 API 的主要价值之一 —— LLM 一轮思考能处理多个目标，不必为每个 run 单独发起一次工具调用。

---

## 6. 两套工作方式：直接编辑 vs 修订模式

`DocxAgentExample` 的 SYSTEM_PROMPT 把 Agent 工作方式显式分成两套：

| | 直接编辑 | 修订模式（tracked changes） |
|---|---|---|
| 工具 | `replace_run_text` / `replace_table_cell_run_text` / `update_*` | `insert_tracked_run` / `replace_run_tracked` / `delete_run_tracked` / `mark_*` |
| 效果 | 改动立即生效、不留痕 | 改动以修订标记写入，可审阅后定稿 |
| 适用 | 用户要「直接改好」 | 用户要审阅改动、可选接受/拒绝 |

Agent 按用户意图选择。配合 `search_text` 先定位再改，能显著降低 LLM 越界率（避免逐段 `read_paragraph` 盲读）。

---

## 7. 非 Agent 用法（直接持有字段）

测试或非 Agent 场景可直接调工具方法：

```java
DocxToolkit tk = new DocxToolkit();
String docId = tk.session.openDocx("/path/to/a.docx");
System.out.println(tk.body.readParagraph(docId, List.of(0)));  // 读第 0 段
System.out.println(tk.trackedChangeQuery.listTrackedChanges(docId));  // 枚举修订
tk.session.closeDocx(docId);
```

工具方法的 Java 签名就是带 `@ToolDef` 注解的 public 方法，参数对应工具 schema 的字段。

---

## 8. Maven 坐标

```xml
<dependency>
  <groupId>io.github.nondirectional</groupId>
  <artifactId>nondocx-toolkit</artifactId>
  <version>0.0.2</version>
</dependency>
```

传递引入 `nondocx-core`。Agent 框架（nonchain）需消费方自行引入。

---

## 9. Agent + SubAgent

toolkit 提供共享会话的文档工具；调用方决定 Agent 拓扑。推荐把只读工具给主 Agent，把写入工具给无状态 SubAgent，并由主 Agent 的工具调用触发实施。

```text
主 Agent（view_*）
  └─ invoke_subagent(task)
       └─ SubAgent（读、写、质量检查、受限保存）
            └─ DocxToolkit -> nondocx-core -> Apache POI
```

SubAgent 应只获得当前文档的句柄和受限保存工具，不能打开、关闭或切换任意路径文档。保存前由应用层运行质量检查：错误阻止保存，警告随结果返回。失败或取消时，应用层重开磁盘文档，丢弃未保存的内存修改。

---

## 10. 可运行示例

| 示例 | 演示 |
|---|---|
| [`StableSemanticReferenceExample.java`](../nondocx-examples/src/main/java/io/github/nondirectional/docx/examples/StableSemanticReferenceExample.java) | P0-01：前插不漂移、删除 `element_removed`、SESSION 跨代失效、PERSISTENT reopen 后仍命中 |
| [`DocxAgentExample.java`](../nondocx-examples/src/main/java/io/github/nondirectional/docx/examples/agent/DocxAgentExample.java) | LLM 直连工具的两段流程：读结构 → 执行编辑（直接编辑 + 修订模式） |
| [`InteractiveDocxAgentExample.java`](../nondocx-examples/src/main/java/io/github/nondirectional/docx/examples/agent/InteractiveDocxAgentExample.java) | LLM 交互式对话，不限迭代次数 |

`DocxAgentExample` / `InteractiveDocxAgentExample` 需 `DASHSCOPE_API_KEY` 环境变量。

稳定语义引用示例无需 API key：

```bash
rtk mvn -q -pl nondocx-examples -am -DskipTests install
rtk mvn -q -pl nondocx-examples \
  -Dexec.mainClass=io.github.nondirectional.docx.examples.StableSemanticReferenceExample exec:java
```

---

## 下一步

- 想知道 Agent 调工具时 docx 失败怎么呈现给 LLM → [08 · 异常与 raw 领地](./08-exceptions-and-raw.md)
- 想了解工具背后的修订语义 → [05 · tracked changes 教程](./05-tracked-changes/README.md)
- 查具体方法 → [03 · API 速查](./03-api-reference.md)
