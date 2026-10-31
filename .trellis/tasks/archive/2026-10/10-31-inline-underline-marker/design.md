# 设计：<u> 行内标记

## 1. 语法契约

| 输入 | 结果 |
|---|---|
| `<u>文字</u>` | 一个 `underline=true` 的 span，text=`文字` |
| `<u>**粗**文</u>` | 两个 span：`粗`(bold+underline)、`文`(underline) |
| `<u>` 未闭合 / `</u>` 孤立 | 原样作为纯文本（宽容降级，与 `**`/`*` 同规则） |
| `<u></u>` | 不命中（`(.+?)` 至少 1 字符），原样作为纯文本——与 `****` 现状一致 |
| `<u><u>x</u></u>` | **不支持**；按最左闭合匹配，第二个 `</u>` 原样保留 |

字面量 `<u>` 无转义方式——与现状「字面 `*` 无法转义」同类限制，记入 docs 已知边界。

## 2. 数据模型（`MarkdownDoc.Span`）

现状：`Span(text, bold, italic, code)`，静态工厂 `Span.text(...)`，无 getter 前缀（record 风格方法名）。

改动：
- 增字段 `boolean underline` + `underline()` 访问器；
- 增 5 参构造器 `Span(text, bold, italic, code, underline)`；
- **保留 4 参构造器**委托到 5 参（`underline=false`）——`Span` 是 toolkit 公开类型，
  不为本次需求破坏既有调用方。

## 3. 解析（`MarkdownParser.parseInline`）

在 `INLINE` 正则（MarkdownParser.java:40）的交替分支末尾追加 `<u>(.+?)</u>`（不含换行、非贪婪）。
`parseInline` 的 token 分派增加一支：

```
else if (token.startsWith("<u>")) {
  for (Span inner : parseInline(token.substring(3, token.length() - 4))) {
    spans.add(inner.withUnderline());   // 递归解析内层标记，下划线取并集
  }
}
```

- 递归复用同一函数，`**`/`*`/`` ` `` 在内层照常生效；`withUnderline()` 是 `Span` 上的小构造方法。
- **粗/斜 token 也要能展开**：读侧输出 `**<u>x</u>**`，若 `**` 分支仍整体成一个 span，标签
  会成为粗体 run 的字面文本、round-trip 断链。故 `expandEmphasis`：内层含 `<u>` 时递归解析
  并取并集，不含 `<u>` 时维持 v1 的「整体一个 span、内层标记字面」语义（有测试锁旧行为）。
- 不引入嵌套同名标签解析器：非贪婪正则 + 递归已覆盖「内层其它标记」这一真实需求，
  同名嵌套是过度设计。
- `stripInlineMarkers` 不用改：它按 span.text() 拼接，`<u>` 自然被剥掉。

## 4. 写路径（四处 sink，必须同改）

1. `DocumentCompiler.fillPara`：create_document 正文与 `insert_paragraph`（PatchEngine 复用）。
2. `DocumentCompiler.fillListItem`：列表项是独立 span→run sink，不复用 `fillPara`。
3. `PatchEngine.applySpanFormat`：`replace_text`（单 run 拆分 + 跨 run 重建）、`header.set_text`、
   `tracked.insert/replace` 多 run 路径。
4. `PatchEngine` 里 `table.set_cell` 的独立 span 循环（不复用 `applySpanFormat`，它还要套
   profile 字体/字号）。

同时必须给 `formatted` / `plain` 快速路径判定加 `underline`——否则 `<u>` 会被当成纯文本
字面写入（这是「新增标记」类改动最容易漏的一处）。

各处沿用现有「bold/italic/code 各写一遍」的结构而不抽公共方法：职责不同（还要叠 body
字体/字号），抽公共反而要传 profile 分支。用测试锁多条路径产物一致。

**`create_document` 表格单元格不支持 `<u>`**：`MarkdownDoc.Table` 单元格是 `List<String>`，
解析期 `stripCellMarkers` 已剥标记，`**` 同样不生效。富文本单元格只有 `table.set_cell` 一条路。

`fillHeading` 不接 span（标题是单 run 扁平文本），故标题内 `<u>` 不生效——记入已知边界。

## 5. 读侧（`MarkdownView.renderRuns`）

现状按 `if bold → else if italic → else` 单标记渲染（MarkdownView.java:207-221），
且**完全不渲染下划线**。若不改读侧，写进去的下划线在 `read_document` 里不可见，复审必然误判
「没做」——这是 §6.6/§6.13 反复踩过的「写读不同批」坑。

改为：
```
inner = underline ? "<u>" + text + "</u>" : text
bold ? "**" + inner + "**" : italic ? "*" + inner + "*" : inner
```
- 组合输出 `**<u>x</u>**`，读回解析为 bold+underline，round-trip 闭合。
- `escapeText` 只作用于文本本身，标签在转义后拼接，不会被二次转义。
- `view_*` 观测面同步：`AnnotatedRun` 与 view 的 run properties 增加 `underline`
  （复审只持 `view_*` 时同样要能核对「加下划线」是否达成，§6.13 同族要求）。

## 6. 兼容性

- 不含 `<u>` 的输入：正则不命中，span 序列与现状逐一相同（含 4 参构造的默认值）。
- 读侧变化：带下划线的 run 从「纯文本」变成 `<u>…</u>`。这是**预期修复**；
  既有读侧测试若断言了「下划线 run 渲染为纯文本」，属需要同步更新的旧快照。
- eval：新任务入 baseline 组，计数锁 26 → 27；历史 runId 数据不回填。

## 7. 验证策略

- 单测：解析（含组合/降级）、两条写路径、读侧与 round-trip。
- 陷阱型断言：
  - `replace_text` 子范围拆分——只给命中片段加下划线，前后缀不得被顺手加上（对应 §6.11 的
    格式累积坑：拷贝必须始终复制源 run）；
  - tracked 路径按 `save+reopen` 口径断言（§6.13：ins 内 run 不被 `runs()` 暴露）。
- eval：`--surface compiler --group baseline` 全量；新任务若模型一次写成
  `replace_text find=… text=<u>…</u>` 即视为接口成立。

## 8. 未决问题（执行时按此拍板）

1. 是否给 `set_style` 加 `underline` 布尔？**不做**——本次只补标记语言；整段下划线需求出现时
   再按 `bold/italic` 的形态扩。
2. 是否同时支持 `<u>` 在 `fill_template` 占位符值里？**不做**——模板填充的值走字面文本路径，
   与本次「行内标记」不是同一契约，避免顺带改语义。
