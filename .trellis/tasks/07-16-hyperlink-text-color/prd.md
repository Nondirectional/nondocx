# Agent 支持修改超链接文本颜色

## Goal

让 Agent 能在不破坏超链接目标和链接结构的前提下，把指定超链接的可见文本修改为调用方指定的颜色，完成“将超链接文本颜色修改为 XX 颜色”的文档编辑任务。

## User value

Agent 不必通过 `raw()` 下探 Apache POI，也不必把超链接误当普通 run；一次工具调用即可完成颜色修改并继续走现有保存流程。

## Confirmed facts

- `nondocx-core` 的 `Run` 已有 `color(String)` / `color()`，颜色约定为 6 位十六进制 RGB 字符串，例如 `FF0000`。
- `Hyperlink` 持有 `XWPFHyperlinkRun`，当前只公开 `text`、`url`、`raw`，没有颜色读写方法。
- `Paragraph.runs()` 只返回普通 run，不包含超链接；超链接必须通过 `inlineElements()` 识别。
- `nondocx-toolkit` 的 `BodyTools.update_run_style` 因上述寻址语义不能修改超链接；现有 `update_hyperlink` 已能按正文段落索引 + 超链接索引修改 text/url。
- `update_hyperlink` 当前支持 text/url 合并修改，字段均可选，修改后由调用方使用 `save_docx` 落盘；没有传任何修改字段时返回无变更结果。
- 项目要求公开 wrapper 使用 live delegate、fluent mutator、中文 Javadoc/工具描述，并用 round-trip + POI 交叉验证覆盖 DOCX 行为。
- 工作区已有用户未提交修改，集中在 demo 静态资源与质量检查；本任务规划不应覆盖或回退这些改动。

## Requirements

- 为 `Hyperlink` 提供与 `Run` 一致的颜色读写能力，颜色写入直接作用于超链接内部 run 的字符属性。
- 扩展现有 `update_hyperlink` 工具，使其支持可选 `color` 字段；保留原有 text/url 行为和兼容调用方式。
- Agent 可按现有段落索引 + 超链接索引定位目标；本任务不新增超链接稳定引用体系。
- 颜色使用现有 6 位十六进制 RGB 约定；调用后超链接 URL、显示文本及超链接关系保持不变。
- 更新必要的 API/toolkit 文档、工具能力元数据说明和自动化测试。

## Acceptance Criteria

- [ ] 对已有超链接设置颜色后，内存读取可得到指定颜色，显示文本与 URL 不变。
- [ ] 保存并重新打开 DOCX 后，超链接文本颜色仍为指定值，且超链接目标关系仍正确。
- [ ] Agent 通过 `update_hyperlink` 的 `color` 字段即可完成修改；工具返回成功结果，并要求沿用现有 `save_docx` 落盘流程。
- [ ] `update_hyperlink` 只传 `text` 或只传 `url` 的既有行为继续通过；未传 text/url/color 时仍返回无变更结果。
- [ ] 非法目标索引、非法颜色或不存在文档按既有 toolkit 错误契约返回，不破坏文档状态。
- [ ] 相关 core/toolkit 测试与项目质量检查通过，且工作区既有无关修改未被覆盖。

## Out of scope

- 不新增 `HyperlinkRef` 或改变现有超链接索引寻址模型。
- 不批量修改所有超链接，不新增按文本全局改色语义。
- 不改变 Word/WPS 默认超链接主题样式、访问后颜色或文档级主题配置。
- 不扩展到页眉、页脚、表格单元格中的超链接，除非后续明确提出；当前工具的超链接写入口只覆盖正文段落。

## Open questions

- ✅ 已确认：颜色参数沿用现有 `Run.color` 的 6 位十六进制 RGB 字符串（例如 `FF0000`），作为现有 `update_hyperlink` 的可选字段。
- 超链接颜色修改是否只覆盖正文段落，还是需要同时覆盖表格、页眉和页脚？

## Notes

- 这是跨 `nondocx-core` 与 `nondocx-toolkit` 的小型契约扩展；在用户确认范围后，再决定是否需要 `design.md` 与 `implement.md`。
