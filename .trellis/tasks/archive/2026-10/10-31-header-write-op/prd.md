# PRD:compiler 面页眉写 op 与读视图页眉元数据行

## Goal

compiler 面新增页眉写 op(对齐 footer.set_page_number 形态,text 支持行内标记,不占段落锚点),read_document 同批落地页眉元数据行;core 复用 ensureHeader 最小化新增;eval 新增页眉写文本任务并复跑 compiler baseline。

## 背景

两面都没有页眉写能力:

- compiler 面(编译式工具面):PatchEngine 无页眉写 op;
- full 面(全量工具面):header 只读。

core 已有 `ensureHeader()` 创建入口(N5 读写分离),缺工具面暴露。直接先例:footer.set_page_number(§6.5)。

## Requirements

### R1 页眉写 op(compiler 面)

- 新 op 写入页眉文本;不占段落锚点(不影响正文段落锚点编号)。
- text 支持行内标记(与正文内容语言一致,如粗体/斜体等既有标记集)。
- MVP 只写默认 section(见 A2)。

### R2 read_document 页眉元数据行

- read_document 视图输出页眉元数据行,格式对齐「<!-- 页脚: … -->」惯例。
- 无页眉/空页眉时的表现对齐页脚元数据行的既有惯例。

### R3 eval

- eval 新增页眉写文本任务,入组 compiler baseline;新任务入组后更新 EvalTasksTest 计数锁。
- full 面按 §6.12 流程评估 capabilityGap flag(页眉写为 compiler 面能力,full 面预期 gap)。

### R4 core 最小化新增

- 优先复用 ensureHeader + 既有 Paragraph API,core 不做大改。

### R5 文档

- docs/03/07/11 + spec 更新:op 集新增条目、已知边界更新。

## 已拍板决策(用户种子指令给定)

| # | 决策 | 来源 |
|---|---|---|
| D1 | op 形态对齐 footer.set_page_number,不占段落锚点 | 种子指令「已讨论的设计方案」 |
| D2 | 写读同批落地(只写不读会被复审误报,§6.6/§6.13 教训) | 种子指令「质量门禁」 |
| D3 | PatchEngine 新 op + CompilerTools 描述 + 提示词;read_document 视图同步 | 种子指令「已讨论的设计方案」 |
| D4 | core 最小化新增,优先复用 ensureHeader + 既有 Paragraph API | 种子指令 |
| D5 | 中文提交、scope 前缀 | 种子指令「文档」 |

## 无人值守自主拍板(按推荐采纳)

| # | 问题 | 拍板 | 理由 |
|---|---|---|---|
| A1 | 行内标记是否支持 | 支持 | 种子指令推荐;内容语言统一,页眉与正文用同一套标记解析 |
| A2 | 多 section 支持 | MVP 只写默认 section(文档首个/默认 section) | 种子指令推荐;先例 footer.set_page_number 同样按单 section 处理,多 section 留待后续需求 |

## 约束

- 质量门禁:spotless:apply 后全量 `mvn test` 全绿。
- eval 前 install core+toolkit,复跑 compiler baseline 全量;提交附 runId。
- 写读同批必须有测试(写 op 测试 + read_document 页眉元数据行测试)。
- 文档 docs/03/07/11 + spec 同步更新(op 集、已知边界)。
- 完成后归档任务并记 journal;最终汇报集中列出全部自主决策点。

## Acceptance Criteria

- [ ] compiler 面可通过新页眉写 op 写入页眉文本(含行内标记),不产生段落锚点偏移;core 侧改动最小。
- [ ] read_document 输出页眉元数据行,与页脚元数据行惯例一致;写后读链路有测试覆盖(core + toolkit 两面)。
- [ ] eval 新任务入组,compiler baseline 全量复跑通过,EvalTasksTest 计数锁更新,提交信息附 runId;full 面 capabilityGap flag 按 §6.12 评估。
- [ ] spotless:apply 后全量 `mvn test` 全绿。
- [ ] docs/03/07/11 + spec 已更新(op 集条目、已知边界)。
- [ ] 任务归档、journal 记录、最终汇报列出全部自主决策点。
