# 设计：正文行距 + header/footer ref 打通

## 1. 边界

### 做

- `BodyTools` 新增 `update_paragraph_spacing`（批量、body-only）。
- `ElementResolver` 增加只读的「任意容器段落」解析 + 位置描述。
- `DocumentViewService.element` 支持 header/footer 的 paragraph/run/part ref。
- `BodyTools.read_run` 支持 header/footer RunRef（只读）。
- `HeaderFooterTocTools` 在读取结果中补 run ref。
- 写工具对非正文 ref 返回明确错误。

### 不做

- 不写页眉/页脚内容（包括 PAGE 域）。
- 不改 `ParagraphRef` canonical 格式。
- 不做正文缩进/段前段后（core 目前只有 `lineSpacing`/`indent`）。
- 不改表格单元格的写工具语义。

## 2. 为什么读和写要分开

当前 `ElementResolver.resolve(ParagraphRef)` 只扫正文 `document.paragraphs()`。
这是写工具的安全边界：`replace_run_text` / `update_paragraph_alignment` 等只应改正文，
不能因为一个 ref 就静默改页脚。

但读工具需要看到页眉/页脚。正确做法不是放宽 `resolve`，而是新增只读解析路径：

```java
public Paragraph resolveAny(ParagraphRef ref)   // 正文 + 表格 + 页眉/页脚
public Paragraph resolve(ParagraphRef ref)      // 保持 body-only，写工具继续用
public String describe(Paragraph paragraph)     // 人可读位置
public String describe(Run run)                 // 人可读位置
```

`resolveAny` 只给 `view_element` / `read_paragraph` 等只读路径使用；写路径继续走
`resolve` + body 扫描。这样「读打通」不会意外扩大写能力。

## 3. 位置描述

`ElementResolver.describe(...)` 统一生成：

| 容器 | 示例 |
|---|---|
| 正文 | `正文段落 2` |
| 表格 | `表格(0,1,2) 段落 0` |
| 页眉 | `页眉 section=0 variant=DEFAULT 段落 0` |
| 页脚 | `页脚 section=0 variant=DEFAULT 段落 0` |
| run | 段落位置 + ` run 0` |

`view_element` 的 `properties` 增加 `location`；`read_run` 的输出增加 `位置: ...`。
这解决「ref 能解析但模型不知道它属于页脚」的问题。

## 4. 新工具：update_paragraph_spacing

### 签名

```java
@ToolDef(name = "update_paragraph_spacing", ...)
@ToolCapability(operation = UPDATE, element = "paragraph")
public String updateParagraphSpacing(
    String docId,
    List<Map<String,Object>> edits,   // ref 或 paragraph_index + line_spacing
    String onError,
    Integer expectedGeneration)
```

### 语义

- 复用 `resolveParagraphTarget`：ref 必须是正文段落；`paragraph_index` 是正文索引。
- `line_spacing` 必须 > 0；否则该条失败，返回 `invalid_argument`。
- 逐条写入 `Paragraph.lineSpacing(double)`；部分失败不中断。
- 返回 changedRefs，便于后续复审。
- 可选 `expected_generation` 与其它正文写工具一致。

### 为什么不是 update_paragraph_format

用户请求是「行距」；core 没有段前/段后 API。命名保持单一职责，后续需要缩进再加
`update_paragraph_indent`，避免一个工具塞多个语义。

## 5. read_run 的只读路径

当前 `read_run` 全部走 `resolveRunTarget`，该 helper 只接受正文。改为：

```java
if (payload.containsKey("ref")) {
    RunRef ref = parse;
    Run run = resolver.resolve(ref);          // 已经覆盖页眉/页脚
    String location = resolver.describe(run);
    // 不要求它在正文列表里；只读路径允许非正文
    return new ReadRunTarget(run, location, resolver.reference(run));
}
// 无 ref：旧 paragraph_index + run_index，仍只解释为正文
```

写工具继续走 `resolveRunTarget`（body-only）。`resolveRunTarget` 对非正文 ref 改成
清晰错误：

```text
RunRef 位于 页脚 section=0 ...，当前写工具仅支持正文 run
```

而不是误导性的 `RunRef 不属于正文 run`。

## 6. header/footer 读取补 run ref

`read_header_footer` 在现有 `paragraph ref` 下追加：

```text
run 0 ref=doc:.../run:session:r-1 文本: ...
run 1 ref=...
```

`read_header_footer_ref` 对每个段落追加 run ref 列表。这样模型拿到 ref 后可以：

- `view_element(paragraph_ref)` 看段落详情；
- `view_element(run_ref)` 看 run 样式；
- `read_run(runs=[{ref: run_ref}])` 读文本/样式。

## 7. 测试

### toolkit 单测

`BodySpacingAndHeaderFooterRefTest`：

1. 正文两段设置 1.5/2.0 行距，save→reopen 读回；
2. `line_spacing=0` 返回失败；
3. 页脚 seed，`read_header_footer` 返回 run ref；
4. `view_element` 解析页脚 paragraph ref 成功，data 含文本 + `location`；
5. `read_run` 解析页脚 run ref 成功，输出含 `位置: 页脚`；
6. `view_element` 解析 HeaderFooterRef 成功，data 含 part 文本；
7. 对页脚 run ref 调 `replace_run_text` 返回失败，消息含「仅支持正文」，页脚文本未变；
8. 对页脚 paragraph ref 调 `update_paragraph_spacing` 返回失败，页脚/正文都未变。

### 评测复跑

- `body-font-line-spacing`：应可过（模型若选新工具）。
- `footer-page-number`：仍应失败（PAGE 域未实现），但错误从「ref 解析失败/反复读」
  变成「页眉页脚写入不支持」，且不再出现静默改正文。

## 8. 风险

| 风险 | 处理 |
|---|---|
| `resolveAny` 被写工具误用 | 只在 `DocumentViewService.element` / `read_paragraph` 使用；写工具仍走 `resolve`。测试锁定页脚写被拒。 |
| `describe` 扫描成本 | 只读单元素路径，文档规模下可接受；不进入批量视图。 |
| 新工具 capability 漏注解 | 全参数加 `@ToolCapability` / `@NestedParamCapability`；CI 的 capability 契约测试会兜底。 |
| 行距值非法 | `line_spacing <= 0` 逐条失败，不写入。 |
| 页脚 run ref 与正文 run 同名 | `resolve(RunRef)` 用 identity 而非索引；`describe` 明确位置。 |
| 评测工具数变化 | `EvalRunnerTest` 从 57 改为 58；基线报告标注新工具数。 |
