# PRD：文档 IR 编译器 spike（P1）

> 路线图：`docs/11-agent-authoring-architecture.md` §5 P1「做编译器 spike」。
> 前置：A（评测基线 10 条任务）与正文行距/header-footer ref 修复已完成（9/10 通过，平均 6.2 次调用）。

## 背景与假设

当前 58 工具面在「新建文档」场景没有入口、在「整篇排版」场景需要 10+ 次调用。
路线图假设：把「写 docx」从 61 个 API 调用改成一个编译问题
（意图 → Markdown/IR → 样式 profile → OOXML），主 Agent 只需 3~5 个工具，
新建文档任务的调用数可以从 10+ 降到 2~3。

本任务做一个**可评测的 spike**：不追求覆盖所有排版能力，只覆盖新建文档场景的
最小闭环，用同一批评测任务在两个工具面上 A/B，拿数据决定是否继续投入。

## 交付物

1. **Markdown 子集解析器 + 文档 IR**（`nondocx-toolkit` 新包 `compiler`）
   - 支持：`#`~`######` 标题、段落、`**粗体**`/`*斜体*`/`` `代码` `` 行内标记、
     `- `/`* ` 无序列表、`1. ` 有序列表、`|…|` 表格（含表头分隔行）、
     行尾 `{align=center|left|right|both}` 属性；
   - 不支持的语法**宽容降级**为普通段落文本，不失败。
2. **样式 profile**：内置 `report-cn`（黑体标题、宋体/Times New Roman 正文小四、
   1.5 倍行距、首行缩进 2 字符、表头加粗灰底、等宽列宽）与 `plain`（只做结构不设样式）。
   - CJK 关键坑由编译器兜底：**ascii 与 eastAsia 字体槽分离**（core 公开 API 的 font() 走 POI
     四槽同名，做不了「西文 TNR + 中文宋体」分离；编译器经 `raw()` 只覆写 eastAsia 槽）。
3. **编译器**：IR + profile → `nondocx-core`（DocumentBuilder/活对象 API），产出可往返的 Document。
4. **三个 Agent 工具**（`CompilerTools`，挂到 `DocxToolkit` 新字段 `compiler`）：
   - `create_document(doc_id, markdown, style_profile)`：编译出的新文档**替换**会话中该
     doc_id 的文档（句柄不变、generation+1、旧引用失效——对齐 `reopen` 语义）；
   - `read_document(doc_id, max_chars)`：整篇 Markdown 视图，段落/表格带短 ID（`p0`/`t0`），
     含标题层级、对齐属性、粗斜体、列表标记；
   - `validate_document(doc_id)`：复用 `QualityCheckTools.runAllChecks`，输出带序号的问题清单。
   - `scanAll` 包含 compiler 组（能力清单 62→65）；**demo/eval full 面不 scan 它**（默认面不变）。
5. **会话替换原语**：`ToolkitToolContext.replaceSessionDocument(docId, fresh)`，
   `SessionTools.reopen` 重构为委托它（复用，不复制会话换血逻辑）。
6. **评测 A/B**：
   - 新任务组 `create`（5 条新建文档任务：周报、带格式正文、会议纪要、数据表、多节长文），
     seed 为空文档，check 只读最终 docx（含 eastAsia 槽断言）；
   - `EvalConfig`/`EvalMain` 增加 `--surface full|compiler` 与 `--group baseline|create`；
   - compiler 面 = `current_document` + 3 编译工具 + 只读 `review_intent`（4+1=5 个入口）。
7. **文档与 spec**：`docs/07-toolkit.md` 工具表、`docs/11` §P1 结果、
   `.trellis/spec/backend/agent-single.md` 增补编译器面约定。

## 验收标准

- [ ] `mvn -pl nondocx-toolkit -am test` 全绿；能力清单 65 个工具，契约测试覆盖新工具；
- [ ] `mvn -pl nondocx-examples -am test` 全绿；新任务组通过防恒真测试；compiler 面工具数=5；
- [ ] 编译单测断言：eastAsia+ascii 双槽、行距 1.5、首行缩进、表头加粗+底纹、
      列表 kind/level、对齐属性、保存重开后全部保持；
- [ ] 真实模型 A/B（`qwen3.8-27b`）：create 组在 full 面与 compiler 面各跑一遍，
      报告记录两面的通过率与平均调用数，写入 `docs/11` §P1；
- [ ] Spotless 全绿；全中文文档/Javadoc/提交信息。

## 不做什么（明确出界）

- **不做 `edit_document` patch 引擎**（P2）：本任务不含对现有文档的结构化编辑；
- **不做 `render_preview`/`fill_template`**（P2）；
- **不改 demo 工具面**：demo 仍用旧 58 面（compiler 面是否进 demo 由 A/B 数据决定）；
- **不做 eastAsia 的 core 公开 API**（那是另一个 core 任务；本任务经 raw() 兜底并在
  Javadoc 标注）；
- **Markdown 不支持**：嵌套强调、图片、链接、代码块语法高亮、HTML、脚注、引用块渲染。
