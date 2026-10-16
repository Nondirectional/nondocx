# 跨 run 文本 tracked 删除

## Goal

让 Agent 通过现有 `delete_run_tracked` 工具，既能继续删除整个普通 run，也能按段落中的精确可见文本删除跨多个 run 的文本，并以 tracked deletion 形式保留可审阅、可接受/拒绝的修订语义。

## User value

调用方不必先读取并推断段落的 run 切分方式，也不必为跨 run 文本提交多条脆弱的 run 坐标；一次兼容的工具调用即可完成精确文本删除，同时保留现有保存和修订处理流程。

## Confirmed facts

- `delete_run_tracked` 当前按正文段落索引 + 普通 run 索引定位，只能把整个 run 标记为 tracked deletion；现有旧调用和批量返回语义需要保持。
- `Paragraph.text()` 提供段落拼接后的可见文本；`Paragraph.runs()` 只包含普通 run，不包含超链接等其他内联元素。
- `Paragraph.addDeletion(author, run)` 当前只接受整个 `Run`，会把底层 `CTR` 迁入 `<w:del>`；core 尚无跨 run 或 run 内部分文本的 tracked 删除 API。
- `search_text` 已能按段落拼接文本命中跨 run 的关键词，但当前只辅助返回首个包含关键词的普通 run，不能直接完成跨 run 删除。
- tracked 删除不是立即移除；调用方仍通过现有 `save_docx` 落盘，并可通过现有 accept/reject 工具处理修订。
- 工作区已有用户未提交修改；本任务实施不得覆盖或回退与本功能无关的改动。

## Requirements

- R1. 保留 `delete_run_tracked` 的工具名、author、edits、批量、`on_error` 和 `expected_generation` 兼容调用方式；仅传 `paragraph_index` + `run_index` 时，行为仍是删除整个普通 run。
- R2. 为 `edits` 增加按文本定位的兼容模式：每条可传正文 `paragraph_index` + 精确 `target_text`，可选 `occurrence`；未传 `occurrence` 时只有唯一命中才允许修改；同一条 edit 必须明确使用整 run 或文本范围中的一种定位模式。
- R3. 文本定位必须支持目标文本跨多个普通 run，也支持只命中首尾 run 的部分文本；删除段落完整文本是该能力的特例。
- R4. 删除目标以外的文本、原有 run 样式和段落结构必须保留；不得改变超链接目标、图片、字段或其他未命中的内联内容。第一版仅覆盖正文普通 run，跨越不支持的内联元素时按错误契约拒绝。
- R5. 新模式产生与旧工具一致的 tracked deletion，继续由调用方使用 `save_docx` 落盘，并可由现有修订接受/拒绝流程处理。
- R6. 文本不存在、重复文本未明确选择、范围重叠、非法段落索引、非法 edit 或不存在文档时，按既有 toolkit 错误契约返回；定位/校验失败不得修改该条目标内容。重复文本只有显式 `occurrence` 才可定位。
- R7. 更新 core/toolkit API 文档、工具描述、能力元数据、Agent 使用说明和自动化测试；测试必须覆盖跨 run、边界部分删除、往返保存及 accept/reject 行为。

## Acceptance Criteria

- [ ] 既有 `{paragraph_index, run_index}` 调用继续成功删除完整 run，旧 Java 兼容入口和批量错误语义不回退。
- [ ] 对由三个普通 run 拼成的目标文本，单次 `delete_run_tracked` 传 `target_text` 即可创建 tracked deletion，不要求调用方知道三个 run 的索引。
- [ ] 只删除 run 内部一部分或跨 run 的首尾部分时，前缀和后缀仍可见且样式保持；中间完整 run 被正确标记删除。
- [ ] 保存并重新打开 DOCX 后，目标文本仍以修订形式存在，未命中文本和文档结构保持正确；接受修订后目标文本消失，拒绝修订后原文恢复。
- [ ] 重复目标文本、未找到文本、非法索引、跨越不支持内联元素和不存在文档均返回可读错误，且失败定位不破坏文档。
- [ ] 相关 core/toolkit 测试、能力元数据生成和项目质量检查通过，且工作区既有无关修改未被覆盖。

## Out of scope

- 不删除或重命名 `delete_run_tracked`，不改变旧 `run_index` 的整 run 语义。
- 不支持模糊匹配、按文本全局删除或默认删除所有重复命中；不新增长期稳定的文本引用体系。
- 第一版不扩展到表格单元格、页眉、页脚、超链接内部、图片、字段或其他非普通 run 内联内容。
- 不改变现有 `save_docx`、tracked changes accept/reject 或文档关系处理流程。
