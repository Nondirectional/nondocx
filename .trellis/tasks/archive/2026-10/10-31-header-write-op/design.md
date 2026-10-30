# Design:compiler 面页眉写 op 与读视图页眉元数据行

> 依据 research/ 目录 9 份调研文件(先例全部实证到 file:line)。

## 1. 总体形态

对齐 footer.set_page_number 先例(PatchEngine.java:198/:803-868),新增 compiler 面 op:

```
header.set_text   text(必填,支持行内标记)
```

- **追加语义**:每次执行向默认 section 页眉追加一段(`doc.ensureHeader().addParagraph()`),不替换既有内容(footer 先例同为追加,测试证据 PatchEngineTest.java:862/873)。
- **不占段落锚点**:dispatch case 不调 `claimAnchor`;plan 函数签名 `(int index, Map<String,Object> op)`,无 anchor/snapshot;`changed.add("header")` 报字面量;写入目标在正文之外,不动 `doc.bodyElements()`。
- **core 零新增**:`ensureHeader()`(Document.java:339)已存在;`Header.addParagraph()` 返回普通 `Paragraph`,行内标记写入与正文同 API(逐 span `addRun(span.text())` + `PatchEngine.applySpanFormat(run, span, profile)`,复用 tracked.insert 模式)。
- **MVP 只写默认 section**(A2):`doc.ensureHeader()` = `section(0).ensureHeader(DEFAULT)`,多 section/变体留 future。

## 2. 参数与校验(preflight,零写入)

- `text`:必填字符串;缺失/空白 → `PatchException`(消息含 op 名与原因,对齐 footer 的两段式校验风格)。
- 行内标记:复用 `MarkdownParser.parseInline` 全部标记集(bold/italic/code 等),与正文内容语言一致,不裁剪(实现最简且语义统一)。
- 不做占位符域(页码进页眉留 future)——页眉写文本 MVP 为纯内容+行内标记。

## 3. read_document 页眉元数据行

`MarkdownView.render()` 尾部、页脚行(MarkdownView.java:84-92)**之前**新增(与页面物理顺序一致):

```java
// 页眉元数据:对齐页脚行惯例;读侧只读入口 header(),绝不因读视图创建页眉 part(N5)
io.github.nondirectional.docx.core.api.header.Header header = doc.header();
if (header != null) {
  String headerText = header.text();
  if (headerText != null && !headerText.isBlank()) {
    sb.append("<!-- 页眉: ").append(headerText.trim().replace("\n", " / ")).append(" -->\n");
  }
}
```

格式惯例完全对齐页脚行:存在且非空才输出;多行 `\n` → `" / "`;截断发生时提前 return 该行不输出;注释形态 round-trip 被解析器剥掉。`MarkdownView` javadoc(:20-22)「页眉…不在本视图内」同步改写。

## 4. 提示词(两处同口径,缺一即 A/B 数据不可比)

| 位置 | 修改 |
|---|---|
| nondocx-demo AgentBridge.java:296 附近 | 加一句:「用户要求在页眉写文本时用 header.set_text op(text 支持行内标记);」 |
| nondocx-examples EvalRunner.java:85 附近 | 同句(AgentBridge.java:264 注释明言两处同口径) |

## 5. eval 新任务(baseline 第 25 条)

- id `header-write-text`(kebab-case,footer-page-number 同型「容器-内容」语义)。
- seed:`doc.addParagraph("正文")`,**不建页眉**(无页眉起点 → 「页眉存在」check 在 seed 上失败,天然满足防恒真)。
- prompt(不提工具):要求在页眉写入指定文本,其中部分词加粗(覆盖行内标记链路)。
- check 三件套(对齐 footer-page-number 模式 + reopen 口径):
  1. 页眉存在(`doc.header() != null`);
  2. 页眉文本含关键词 + 指定词为加粗 run(防退化:纯文本写入不满足);
  3. 正文不被误改(p0 text 不变)。
- `capabilityGap=true`:full 面写工具 ref 只解析正文(agent-single.md「ref 的容器边界」),页眉写物理不可达——与 footer-page-number 同型先例。
- EvalTasksTest 双锁:`hasTwentyFourUniqueTasks` → `hasTwentyFiveUniqueTasks` + `hasSize(24)` → `hasSize(25)`;`marksKnownCapabilityGapTasks` containsExactly 清单尾部追加(顺序敏感,插入位=定义序)。
- EvalTasks.java 类 javadoc 计数(现写 22,实际 24)顺手修正为 25。

## 6. 文档与 spec

| 文件 | 更新 |
|---|---|
| docs/07-toolkit.md | line 221 op 枚举加 header.set_text;line 219 read_document 行补页眉元数据句;~253-268 op 集 JSON 块加示例行;~297 对称新增 `**header.set_text**` 描述段;line 123-134 blockquote 口径复核(full 面仍只读,compiler 面已可写) |
| docs/11-agent-authoring-architecture.md | 新增 §6.15(§6.14 后、§7 前):op + 元数据行 + eval 新任务 + 复跑结果(runId);§6.9:697-699 历史陈述不回改 |
| docs/03-api-reference.md | **预计零改动**(core 无新公开 API);实现完成后复核确认,若意外新增 core API 则补表行 |
| .trellis/spec/backend/agent-single.md | ① compiler surface 段(~252-364):新增页眉 op 条目(追加语义/不占锚点/行内标记)+ 页眉元数据行条目;② 评测基线段(~160-210):baseline 25 条描述、「已知边界:页眉写无 op(两面)」改写为「仅 full 面」、runId 结果行链式追加;③ ref 容器边界段(211-250)full 面规则不动,仅复核措辞 |

## 7. 无人值守新增拍板汇总(除 PRD A1/A2 外)

| # | 问题 | 拍板 | 理由 |
|---|---|---|---|
| A3 | op 命名 | `header.set_text` | 对齐 footer.set_page_number 命名结构(容器.动作) |
| A4 | 写入语义 | 追加一段(非替换) | D1「对齐先例形态」直接推论;替换需 core 新增 Header 清空/替换 API,违反 D4;「重复调用追加、替换留 future」写入已知边界 |
| A5 | text 参数 | 必填非空;复用 parseInline 全标记集;无占位符域 | 与正文内容语言统一;页码域进页眉留 future |
| A6 | 元数据行位置 | render 尾部、页脚行之前 | 与页面物理顺序一致(页眉在上) |
| A7 | eval 任务 id | `header-write-text`,prompt 含加粗要求 | 覆盖行内标记全链路;防退化 check 锁加粗 run |
| A8 | 分支 | 留在 feat/paragraph-spacing-hf-refs | 既有惯例:多任务同分支连续提交 |
| A9 | docs/03 | 预计零改动,完成后复核确认 | core 零新增时 API 速查表无新条目;与种子指令「docs/03/07/11」的字面清单存在偏差,按实际落地并在汇报标注 |
| A10 | 脏文件(.trellis 模板/AGENTS.md/.codebuddy) | 不混入功能提交,收尾单独 chore 提交 | trellis update 工具链产物,与本任务功能无关 |
| A11 | full 面评估 | 复跑 full 面 baseline 留归因证据后 flag;若环境无法跑模型则按 footer 同型先例+spec 既有「两面无 op」声明降级 flag 并标注 | §6.12 精神:flag 需复跑证据;降级属保守路径 |
| A12 | 提交时序 | feat 提交(代码)→ install → 复跑(runId@gitCommit 含新代码,可复现)→ amend feat body 附 runId → docs 提交附 runId | EvalMain 报告记录 gitCommit,§6.14 runId@8f3cff4 + 381276f/65ba23a 先例共同指向该时序 |

## 8. 兼容与回滚

- 纯新增 op 与读侧行,不改既有 op 行为;read_document 输出新增行只在页眉存在且非空时出现,既有空文档文档 round-trip 不变。
- 回滚:revert 单次功能提交即可(op、视图行、提示词、eval 任务、文档同批提交)。
