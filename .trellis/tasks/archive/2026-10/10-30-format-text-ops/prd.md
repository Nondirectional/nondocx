# replace_text 行内格式与 set_style 加粗

## Goal

compiler 面补齐既有文本加粗能力：replace_text 的 text 按 Markdown 行内标记解析（**粗**/*斜*/代码），单 run 命中外科手术式拆分保前后缀样式（core 新增 Paragraph.copyRun）；set_style 增加 bold/italic 整段字符格式。数据信号：replace-with-format 任务模型曾尝试 set_style bold 被拒、写过字面 **。

## Background

- 已知能力缺口（docs/11 §6.9 记录）：compiler 面无「给既有文本加粗」op。eval 任务 replace-with-format 的 trace 显示模型把 `**` 字面写进 replace_text、尝试 `set_style bold` 被拒、最后靠 delete+insert Markdown 自救（9 次调用，全组最高）。
- 根因：该编译器的**内容语言是 Markdown**——create_document 正文、table.set_cell 都解析行内标记，唯独 replace_text 的 `text` 是唯一按字面处理的文本路径。模型的「字面 `**`」其实是正确直觉撞上了不一致的 API。

## Requirements

1. **replace_text 的 `text` 按行内标记解析**（`**粗体**` / `*斜体*` / `` `代码` ``，复用 `MarkdownParser.parseInline`）：
   - 纯文本（无标记）→ 行为与现在完全一致（单 run 原位替换保样式 / 跨 run 整段重建+警告）。
   - 含标记 + 单 run 命中 → **外科手术式拆分**：原 run 保前缀，替换片段逐 span 深拷贝原 run（含完整 rPr）再套加粗/斜体/代码字体，间隔与后缀文本同样以拷贝承载。该 run 内多处命中全部处理。
   - 含标记 + 跨 run 命中 → 整段重建为 span 序列（前缀纯文本 run + 格式 run + 后缀），沿用现有「格式丢失」警告。
2. **core 新增 `Paragraph.copyRun(int index)`**：深拷贝第 index 个 run（CTR `set()` 影子技巧，同 `Table.copyRowTo`）紧随其后插入，返回活对象。写穿透到文档树（save+reopen 可见）。
3. **set_style 增加 `bold`/`italic`**（boolean）：整段全部 run 套字符格式，可与 style=body/heading、align、line_spacing 叠加（后应用）。
4. eval 新任务 `bold-existing-phrase`（第 23 个）：给段落中某个**既有词**加粗、不动其他文字——验证子范围身份替换路径（`find=X text=**X**`）。

## Acceptance Criteria

- [ ] core：copyRun 拷贝完整 rPr（含 eastAsia）、返回对象写树、save+reopen 可见、越界抛 IllegalArgumentException。
- [ ] toolkit：上述各路径 + 纯文本行为不变回归。
- [ ] eval：23/23；replace-with-format 调用数显著低于 9；无 set_style 被拒。
- [ ] demo SurfaceSwitchTest 8 条目不变（无新工具，只有 op 语义扩展）。
- [ ] Spotless 通过；文档（docs/03/07/11、spec）同步。

## Out of Scope

- 不做取消既有加粗（unbold）；不做 underline/color 行内标记；不做 tracked 版本（tr.* 与格式叠加留后续）。
- 不改 full 面。
