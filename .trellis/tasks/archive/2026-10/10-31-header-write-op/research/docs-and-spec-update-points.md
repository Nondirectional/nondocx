# Research: docs/03、07、11 与 spec 的更新点清单

- Query: 三份文档各自是什么、op 集列表位置、已知边界段落位置、spec 相关文件与需更新点
- Scope: internal
- Date: 2026-10-31

## Findings

### docs/03-api-reference.md（285 行）——core API 速查

性质：`Docx`/`Document`/`Paragraph`/`Run`/`Table`/`Section`/`Header·Footer`/`TOC`/`TrackedChanges`/`Comments` 的方法级速查表（✏️🔄 写 / 👁 读标记）。

与本任务相关位置：

| 位置 | 内容 | 是否需动 |
|---|---|---|
| line 189-201 `## Header / Footer` | `paragraphs()/paragraph(i)`、`addParagraph()`、`text()`、`raw()`；「只支持默认（奇数页）一份」；读写分离注记（`Section.header()` 只读、`ensureHeader()` 创建） | 若 core 无新公开 API（PRD：复用 `ensureHeader`）则**无需改**；若为行内标记新增 core 变体方法（先例：`addInsertion(author, texts)` 多 run 变体进 §6.13 时 core 有新 API）则在此表加行 |
| line 172-187 `## Section` | `header()/footer()` 👁 vs `ensureHeader()/ensureFooter()` ✏️🔄 | 同上 |

### docs/07-toolkit.md（483 行）——nondocx-toolkit 工具文档

性质：toolkit 的工具组清单 + 门面 + 稳定寻址/批量 + 双工作方式。§4「工具清单（按组）」（line 86 起）。

需更新的确切位置：

| 位置 | 内容 | 更新点 |
|---|---|---|
| line 123-134 `### HeaderFooterTocTools（页眉页脚 + 目录，只读）` | 工具表 + blockquote：「**写工具仍只接受正文 ref**…页脚 PAGE 域写入仍不在范围内」 | 页眉写 op 落地后该 blockquote 的「不在范围内」口径需复核措辞（full 面仍只读；compiler 面有了页眉写 op） |
| line 209 `### CompilerTools（编译器，实验性）` 小节起 | 6 工具表（line 216-224） | line 221 `edit_document` 行的 op 枚举括号「(insert/replace_text/delete/set_style/table.set_cell/table.add_row/tracked.*/footer.set_page_number/comment.add)」加页眉 op |
| line 219 | `read_document` 行：「页脚以 `<!-- 页脚: … -->` 元数据行呈现」 | 补「页眉以 `<!-- 页眉: … -->` 元数据行呈现」 |
| line ~253-268「**edit_document 的 op 集**（v2…）」JSON 代码块 | 逐 op 示例行，`{"op":"footer.set_page_number"}` 在末尾附近 | 新 op 的示例行（对齐 footer 行的一行注释风格） |
| line ~297 `**footer.set_page_number**` 描述段 | 页脚 op 的语义描述（追加段落、format 占位符、缓存结果、不占锚点） | 对称新增 `**header.<op>**` 描述段 |
| line 285 「已知语义边界（**live/reopen 不同**…）」 | tracked.* 段内的边界声明 | 若页眉 op 引入新边界（如与 tracked/round-trip 的交互）在此家族补记；另 docs/09-faq-and-boundaries.md 是 FAQ 边界专篇（本任务未点名） |

### docs/11-agent-authoring-architecture.md（968 行）——架构建议 + 全部评测批次记录

性质：§1-§5 为架构论证（61 工具→编译器、五入口、P0-P3 落地顺序），§6 为评测基线流水账（§6.1~§6.14），§7 明确不做什么。

需更新点：

- **新增 §6.15**（§6.14 line 862-929 之后、「## 7. 明确不做什么」line 931 之前）：记录页眉写 op + read_document 页眉元数据行 + eval 新任务 + compiler baseline 复跑结果。先例：不回改既有小节（65ba23a「docs/11 新增 §6.14（§6.12 不回改）」）。
- §6.9 line 697-699「页眉写不进 baseline（compiler 面无页眉 op，属已知能力边界，与 §6.4 时页脚缺口同型——出现真实需求再补）」：历史陈述，按不回改惯例保留；新小节声明缺口已补。

### spec（.trellis/spec/backend/）文件清单

| 文件 | 与本任务相关性 |
|---|---|
| `agent-single.md`（366 行） | **核心**。grep「PatchEngine/CompilerTools/op 集/edit_document」仅此文件命中。三段需更新（见下） |
| `poi-bridge.md` | 间接：POI 陷阱库。footer 域缓存先例在 ~line 590「诚实边界(读侧走 raw)：…与 TOC 的『写不了、只读』边界对称，这里是『写得出来、读走 raw』」；tracked ins 的 iruns 语义（~line 688）。若页眉 op 踩新 POI 坑在此补 N 条 |
| `quality-guidelines.md` / `logging.md` / `error-handling.md` / `directory-structure.md` / `renderer-compatibility.md` / `index.md` | 无 op 集/PatchEngine 直接内容（grep 无命中） |

`agent-single.md` 的三个更新点：

1. **「Convention: 编译器工具面（compiler surface，实验性）」（line 252-364）**：
   - op 相关条目群：`edit_document 原子性`（line 281-286，preflight 清单）、`同锚点改文本独占`（line 287-292）、`页脚页码域必须带缓存结果`（line ~293-297）、`read_document 的页脚元数据行`（line ~298-300：「`MarkdownView` 在正文后输出 `<!-- 页脚: … -->`（存在且非空才输出）…round-trip 由 CompilerPipelineTest 锁定」）——新增页眉 op 条目（是否占锚点、元数据行格式、缓存/占位符语义）与页眉元数据行条目。
   - 「compiler 面是 8 入口」条目（line ~359-362）：op 增加不增入口，无需改数字，但 op 枚举若有列举处需同步。
2. **「Convention: Agent 评测基线（headless）」（line 160-210）**：
   - line ~162「baseline 24 条（12 初版 + 11 条 P3 扩展…）」计数与描述更新；
   - 结果块 line ~200-203「已知边界：**页眉写无 op（两面）**」——本任务完成后此句需改写（compiler 面已补；full 面若仍无则注明仅 full）；
   - 新 runId 结果行追加（各批次先例都在此块链式追加）。
3. **「Convention: ref 的容器边界（读/写分离）」（line 211-250）**：full 面页眉只读边界的规范依据——页眉写 op 只加在 compiler 面 PatchEngine，此段 full 面规则应保持不动；若措辞受影响需核对。

### 代码侧同源更新点（实现时对照，非 spec）

op 新增在 PatchEngine/CompilerTools 的落点（grep 实证）：

- `PatchEngine.java` op switch（line ~174-207）：新增 `case "header.<op>"`；**default 分支的「可用: insert/…/comment.add」错误消息字符串同步**（line ~205-209）
- `PatchEngine.planFooterPageNumber`（line 803-860）= 页眉 op 的实现模板（ensureFooter→ensureHeader、format 校验、占位符扫描）
- `CompilerTools.edit_document` 的 `@ToolDef description`（line ~255-272，op 枚举长文案）+ `ops` 参数的 `@NestedParamCapability` 列表（line ~283-301，新 op 的每个参数要声明 path）
- 测试锁定位置：`PatchEngineTest`（原子性/独占锁）、`CompilerToolsTest`（line 261 已有页脚断言样例 `contains("<!-- 页脚: 第 1 页 -->")`）、`CompilerPipelineTest`（元数据行 round-trip 锁，line 234）

## Caveats / Not Found

- `.trellis/spec/backend/index.md` 未逐行核对其是否列有 agent-small 段落索引（各 spec 为 Convention/Scenario/Gotcha 叙事体，无自动索引，更新正文即可）。
- docs/03 是否需要动完全取决于 core 是否新增公开方法（PRD 倾向复用 ensureHeader，则 docs/03 无改动）。
