# Journal - non (Part 1)

> AI development session journal
> Started: 2026-06-16

---


## 2026-06-16 — nondocx-core MVP implemented (task `06-16-nondocx-core-mvp`)

Implemented the full nondocx-core MVP (docx read/write library over Apache POI) across Phases 0–8:

- **Scaffold**: parent POM `com.non:nondocx-parent` + `nondocx-core`, Apache 2.0, README, CI (JDK
  [11,17,21] matrix), Spotless (google-java-format) bound to `verify`.
- **Domain model**: `Docx` facade; `api/` (Document, text/Paragraph/Run/Hyperlink,
  table/Table/Row/Cell, section/Section/PaperSize/Orientation, image/Image/ImageType,
  header/Header/Footer, style/*, exception/DocxException hierarchy, BodyElement/InlineElement);
  `builder/` (DocumentBuilder/ParagraphBuilder/TableBuilder); `internal/` (poi/Mappers, Numbering,
  Pictures; util/Streams, Objects).
- **111 tests**, content-equal equals on all core types; `RoundTripTest` deep-equality across
  save→open green on first try (POI write-side normalization is invisible to equality because
  equals compares parsed values, not raw XML).
- All 9 prd acceptance criteria PASS; `trellis-check` verdict READY-TO-FINISH.
- Key Apache POI bridge gotchas (pre-populated children, EMU vs pixels, `unsetNumPr` vs
  `setNumID(null)`, create-on-access header/footer, image-in-run) captured in
  `spec/backend/poi-bridge.md` → Implementation Notes.
- **Deferred to v0.2**: OOXML template fixtures / TestDocxPackager (core acceptance met via
  programmatic construction + POI cross-reference); first-page/even-page header variants.



# 2026-06-17 — 创建 nondocx-examples 示例模块
#
# - 创建新 Maven 模块 `nondocx-examples`，继承 parent POM，依赖 `nondocx-core`
# - 9 个示例文件，全部通过编译，并成功运行生成 docx
# - 文件结构：
#   - `HelloWorld.java` — 最简创建→写入→保存
#   - `FormattingDemo.java` — 标题/对齐/Run 级格式化
#   - `TableExample.java` — 表格 + row(Consumer) 链式填充
#   - `ListExample.java` — 项目符号/编号/嵌套层级
#   - `HeaderFooterExample.java` — 页眉/页脚
#   - `HyperlinkExample.java` — 超链接
#   - `PageSetupExample.java` — 纸张/方向/边距
#   - `ImageExample.java` — 内联图片（运行时生成 PNG）
#   - `ComplexDocument.java` — 综合示例：完整项目报告
# - 所有输出到 `target/examples-output/`（已加入 .gitignore）
# - 更新父 POM 的 `<modules>`、`.gitignore`、`directory-structure.md`

## Session 1: 排查 WPS 页眉页脚不显示 + core 落盘兼容性补全

**Date**: 2026-06-17
**Task**: 排查 WPS 页眉页脚不显示 + core 落盘兼容性补全
**Branch**: `main`

### Summary

定位到 HeaderFooterExample 生成文档在 WPS 中页眉页脚不显示的根因——缺少显式 pgSz/pgMar。在 Section.header()/footer() 首次创建路径补齐缺失页面设置（A4 + 1 英寸边距），只补缺失不覆盖已有。补充测试、Javadoc、README 与 spec 文档。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `0d273d6` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 2: 新增 examples 模块与开发者协作规范

**Date**: 2026-06-17
**Task**: 新增 examples 模块与开发者协作规范
**Branch**: `main`

### Summary

新增 nondocx-examples 模块（ComplexDocument 综合示例 + 9 个独立示例），添加开发者协作规范（AGENTS.md 偏好说明、teaching-approach.md 教学指南），更新目录结构文档，启用 session_auto_commit。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `ca2b75b` | (see git log) |
| `df2ef88` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 3: examples 中新增 nonchain Agent docx 示例

**Date**: 2026-06-17
**Task**: examples 中新增 nonchain Agent docx 示例
**Branch**: `main`

### Summary

在 nondocx-examples 新增 nonchain Agent docx 示例：DocxAgentTools（会话/正文/表格/超链接四组 @ToolDef 工具，统一返回 String、越界返回中文错误串）+ DocxAgentExample（DashscopeLLM + ToolRegistry.scan 组装 Agent，两段流程：读取汇报→编辑保存）+ 固定样例输入文档与结构自检测试。端到端用真实 LLM 跑通并核验 agent-edited.docx。过程中发现并根治 POI N9（Run.text/Hyperlink.text 追加而非替换）与 N10（超链接 URL 重建关系），补 round-trip 回归测试，core 共 121 测试全过，poi-bridge.md 沉淀两条 gotcha。父 pom 加 chain 0.8.4 依赖管理并修复原 XML 结构错位。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `f258faf` | (see git log) |
| `6efa5ab` | (see git log) |
| `179222e` | (see git log) |
| `9210cce` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete
