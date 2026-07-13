# 段落插入落点修复：表格边界场景

## Goal

修复用户描述「在文档开头/末尾添加段落」时，当文档开头/末尾元素是表格，段落落点不符合用户直觉的问题。同时评估图片/图标等其他块级元素是否同病。

## 问题复现

文档 body 顺序形如 `[Table, P1, P2]`（开头是表格）时：
- 用户意图：「在文档开头加一段」→ 期望落点：表格**之前**
- LLM 常产出：`insert_paragraph` 带 `body_index=0` 或 `paragraph_index=0`

文档 body 顺序形如 `[P1, P2, Table]`（末尾是表格）时：
- 用户意图：「在文档末尾加一段」→ 期望落点：表格**之后**
- LLM 常产出：`insert_paragraph` 带 `body_index=2`（末尾表格的 index）或 `paragraph_index=1`（末尾段落 index）

## 已确认事实（代码探索）

### core 层 `Document.insertParagraph(bodyIndex)` 语义

- `bodyElements()` 包含段落**和表格**，两者交错按真实 body 顺序。表格和段落各占一个 slot。
- `insertParagraph(bodyIndex)` 语义：**在「当前处于 bodyIndex 位置的 body 元素」之前插入**新段落。
  - 当 bodyIndex 指向表格 → 新段落落在表格**之前**
  - 当 bodyIndex == size → 末尾追加（`createParagraph()`），落在所有元素之后
- 因此 **core 层 `insertParagraph` 本身在表格边界场景下行为是自洽的**。

### 根因：快照的段落索引 vs body 索引错位

- `SnapshotBuilder.buildParagraphs()` 只遍历 `doc.paragraphs()`（**跳过表格的段落投影视图**），给每个段落编号 0,1,2...
- 段落预览里 `[0] 开头` `[1] 结尾` 完全不体现表格在哪里、body 顺序如何。
- LLM 基于这份「扁平段落列表」产出 `body_index`，把段落索引当 body 索引用，在表格存在时必然错位。

### 两种触发路径

1. **`insert_paragraph`（BodyTools 批量路径）**：BodyExecutor 走 `normalizeBodyIndex`，把 LLM 的 `paragraph_index` 直接当 `body_index`，在表格存在时错位。
2. **`insert_heading`（core 直接路径）**：BodyExecutor `resolveBodyIndex`，若 LLM 给了 `position=start`，bodyIndex=0，指向表格 → 落在表格前（正确）。若 `position=end`，bodyIndex=size → 追加到末尾（正确）。**但**若 LLM 在文档末尾是表格时给了 `position=after:N` 且 N 是末尾段落索引（而非 body 索引），会算错。

### 结论

- core 的插入语义没问题。
- 真正的问题是**快照给 LLM 的位置信息不足以区分「段落索引」和「body 索引」**，导致 LLM 无法正确表达「在表格之前/之后」。

## 图片/图标结论（已排除）

图片/图标**不存在同类问题**。OOXML 里图片是 `<w:drawing>` 嵌在 `<w:r>` run 里，run 嵌在 `<w:p>` 段落里，永远不是 `<w:body>` 直接子元素。`modeledBody()` 只含段落和表格，图片住在段落内部不占独立 slot。**本问题是表格独有的**（表格与段落同为 body 直接子元素）。

## 影响面（代码探索确认）

- `ParagraphPreview.index` / `TablePreview.index` 下游消费点：**仅 LlmDocxExpert.buildPrompt 2 处纯展示**。
- BodyAgent.findParagraphContaining：按文本匹配，返回**列表下标**（碰巧等于 paragraph index），不读 `.index()` 字段。
- BodyExecutor：完全通过 operation payload 的 `paragraph_index`/`body_index` 工作，不读 preview index。
- ReadCoordinator：完全不碰 preview。
- **无任何测试断言 preview index 数值**，不存在 SnapshotBuilderTest。
- 影响面极小：核心改动在 SnapshotBuilder + LlmDocxExpert prompt + BodyExecutor normalizeBodyIndex 语义。

## Requirements

1. 快照给 LLM 的预览必须反映真实 body 顺序（段落和表格交错编号），让 LLM 看见表格在哪里。
2. 快照里每个段落必须同时携带「段落索引」（用于 replace/update 类操作）和「body 索引」（用于 insert 类操作），消除歧义。
3. LlmDocxExpert 的 prompt 必须明确区分 paragraph_index 和 body_index，并用 body 顺序视图渲染。
4. BodyExecutor 的 normalizeBodyIndex 在表格存在时不再把 paragraph_index 当 body_index（去掉误导性容错，或翻译为正确的 body 索引）。
5. 新增测试覆盖表格边界场景（开头表格、末尾表格、中间表格）。

## Acceptance Criteria

- [ ] 文档 `[表格, 段落A, 段落B]` 时，LLM 收到的快照明确展示 body 顺序：段落A 的 body 索引是 1（不是 0）。
- [ ] insert_paragraph 在开头表格场景（body_index=0）→ 段落落在表格之前。
- [ ] insert_paragraph 在末尾表格场景（body_index=bodySize）→ 段落落在表格之后。
- [ ] 现有 replace_run_text/update_run_style/update_paragraph_alignment（用 paragraph_index）在表格存在时仍正确定位。
- [ ] mvn verify 全绿，spotless 清洁。
