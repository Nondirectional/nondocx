# 设计：文档 IR 编译器 spike

## 变更边界（写码前声明）

**最小行为缺口**：新建文档场景下，Agent 没有「一次表达整篇文档」的入口；本任务补一个
Markdown→docx 编译闭环（3 工具）+ 评测 A/B 证明/证伪它比 58 工具面更省调用。

**行为落点**：

- 会话换血逻辑已存在于 `SessionTools.reopen`（同 docId 换 Document、generation+1、
  invalidate refs）——真正的缺口是「从内存 Document 换血」，下沉到基类
  `ToolkitToolContext.replaceSessionDocument` 供 reopen 与 compiler 共用；
- Markdown 解析/编译/Markdown 视图是**新增能力**，落 `nondocx-toolkit` 新包 `compiler`，
  只组合 core 已有活对象 API（`DocumentBuilder`、`Paragraph.heading/alignment/lineSpacing/
  indent/list`、`Run.bold/italic/font/fontSize`、`Table.row/cell/columnPercents`）；
  唯一越出 core 公开 API 的是 eastAsia 字体槽（`raw()` 补写，Javadoc 标注理由）。

**预计改动文件**：

| 文件 | 为什么 |
|---|---|
| `toolkit/compiler/MarkdownDoc.java`（新） | IR：块/行内 span 记录 |
| `toolkit/compiler/MarkdownParser.java`（新） | Markdown 子集 → IR，宽容降级 |
| `toolkit/compiler/StyleProfile.java`（新） | report-cn/plain 两个内置 profile |
| `toolkit/compiler/DocumentCompiler.java`（新） | IR + profile → Document |
| `toolkit/compiler/MarkdownView.java`（新） | Document → 带 p0/t0 短 ID 的 Markdown 视图 |
| `toolkit/CompilerTools.java`（新） | 3 个 @ToolDef 工具 |
| `toolkit/ToolkitToolContext.java` | 增 `replaceSessionDocument` |
| `toolkit/SessionTools.java` | `reopen` 委托新原语（行为不变，测试保护） |
| `toolkit/DocxToolkit.java` | 新字段 `compiler`，scanAll 纳入 |
| `toolkit/capability/CapabilityTools.java` | manifest 收集 compiler 组 |
| `examples/eval/EvalTask(+s/Config/Main/Runner/Result/ReportWriter)` | create 任务组、--surface/--group |
| `docs/07-toolkit.md`、`docs/11`、`.trellis/spec/backend/agent-single.md` | 文档与约定 |

**明确不做**：edit patch、render_preview、fill_template、demo 面改造、core eastAsia 公开 API。

**行为不变的证明**：`SessionTools.reopen` 重构由现有 reopen 相关测试 +
`BodySpacingAndHeaderFooterRefTest` 等回归保护（generation 递增、ref 失效语义不变）。

## IR 设计（v1）

```java
MarkdownDoc { List<Block> blocks; List<String> warnings; }
Block =
  | Heading(int level, String text, Alignment align /*可空*/)
  | Para(List<Span> spans, Alignment align)
  | Table(List<String> header, List<List<String>> rows)
  | ListItem(ListKind kind, int level, List<Span> spans)
Span = (String text, boolean bold, boolean italic, boolean code)
```

解析规则要点：

- 块级：`#{1,6} ` 标题；`|…|` 连续行 + `|---|` 分隔行 = 表格（第二行不是分隔行则整块
  降级为段落并记 warning）；`- `/`* ` → BULLET、`\d+. ` → NUMBERED，缩进 2 空格一级；
  `> ` 前缀剥掉按段落处理（降级）；行尾 `{align=…}` 只在标题/段落行识别；
- 行内：先扫 `` `code` ``（内部不再解析强调），再 `**…**`、`*…*`；不匹配任何标记按纯文本；
- 表格单元格按纯文本（不做行内解析，spike 取舍）；`\|` 转义支持；
- 未知语法不抛异常 → 段落 + warning（create_document 结果里回显 warnings）。

## StyleProfile（v1）

| 键 | report-cn | plain |
|---|---|---|
| body.eastAsia / latin | 宋体（eastAsia 槽）/ Times New Roman（ascii 槽） | 不设 |
| body.sizePt | 12（小四） | 不设 |
| body.lineSpacing | 1.5 | 不设 |
| body.firstLineIndentChars | 2（→ 缇 = chars×pt×20） | 不设 |
| heading.eastAsia / latin | 黑体 / Arial | 不设 |
| heading.size H1/H2/H3 | 16 / 14 / 12（H4+ 同 H3） | 不设 |
| heading H1 对齐 | CENTER | 不设 |
| tableHeader | bold + shading F2F2F2 | 不设 |
| table 列宽 | columnPercents 等分 | 不设 |
| code 字体 | Consolas | 不设 |

实测修正：POI `setFontFamily(name)` 会把 ascii/hAnsi/cs/eastAsia 四槽写成同名（2026-10-29
full 面评测产物 XML 证实）。编译器 raw() 的价值不是「补缺失的槽」，而是**分离**两槽：
`font(latin)` 四槽同名后，只覆写 eastAsia 为中文字体。

```java
run.font(profile.latin());                       // 四槽同名（core 公开 API）
CTRPr rPr = …; CTFonts f = …; f.setEastAsia(profile.eastAsia());  // 只覆写 eastAsia（raw-only）
```

## 工具契约

- `create_document(doc_id, markdown, style_profile="report-cn")`
  → 解析+编译 → `replaceSessionDocument(docId, fresh)`
  → ok：`{docId, generation, paragraphs, tables, profile, warnings[]}`；
  解析/编译失败（如 markdown 为空）→ INVALID_ARGUMENT，**不触碰会话**。
- `read_document(doc_id, max_chars=6000)`
  → Markdown 视图 + 短 ID（p 序号=正文段落序，t 序号=表格序，与 body 顺序无关、稳定可复述）；
  超长截断 + `truncated:true`。
- `validate_document(doc_id)` → `qualityCheck.runAllChecks(doc)` → 问题数组
  （id v1/v2…、severity、message、suggestion）。

read_document 输出形如：

```markdown
# 项目周报 {align=center} <!-- p0 -->
本周完成了核心封装。 <!-- p1 -->
- 待办一 <!-- p2 -->
| 模块 | 状态 | <!-- t0 -->
```

## 评测 A/B 设计

- 新任务组 `create`（5 条，seed=空文档，check 断言最终 docx：文本、标题级、对齐、
  双字体槽、行距、缩进、表格维度/表头加粗/底纹、列表 kind）；
- 两个面跑同一组：full 面（58 工具，靠 insert_paragraph/create_table/… 逐步搭）vs
  compiler 面（5 入口，期望 1~2 次 create_document + 少量 read/validate）；
- 判定口径与 baseline 一致：通过率、平均调用数、无效调用率；
- compiler 面 system prompt 重写：说明「当前文档将被你生成的整篇文档替换」+
  profile 说明 +「保存由系统完成」。

## 风险与取舍

- **宽容解析 vs 严格报错**：选宽容+warnings——LLM 产出的 Markdown 一定有瑕疵，
  编译器的工作是兜底而不是拒绝；
- **短 ID 稳定性**：p/t 序号按「当前文档顺序」编号，create_document 替换后需重新
  read_document（generation 已 bump，旧 ID 不承诺稳定——spike 内无 edit op，够用）；
- **表格单元格纯文本**：若 A/B 显示表内富文本是刚需，P2 的 edit patch 一并解决；
- **qwen3.8-27b 对 Markdown 工具的理解**：若 compiler 面失败模式是「不会用工具」，
  在报告里如实记录，属于接口描述问题而非假设失败。
