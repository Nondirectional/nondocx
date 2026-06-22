# PRD — comments 文档+spec

> 父任务：`06-22-comments`（planning）。
>
> **背景**：前序四个子任务已交付完整 comments 能力（读 + 创作 + 回复线程 + 基础设施）。本子任务做**教学文档 + spec 更新 + API 速查补全 + 最终收尾**，是整个 comments 能力线的最后一步。
>
> **对照**：`06-18-tracked-changes-docs-spec`（文档、异常示例、spec 更新、收尾）。

## Goal

交付完整的教学与规格文档，让 nondocx 用户能自学批注能力：

1. **`docs/06-comments/` 四篇**——完全对照 `docs/05-tracked-changes/` 的四篇结构。
2. **`docs/03-api-reference.md` 补全**——Comments / Comment 段落。
3. **`docs/README.md` 索引更新**——加入 06 章节。
4. **spec 更新**——`.trellis/spec/` 里把批注从「out of scope」改为已支持。
5. **集成示例**——`nondocx-examples` 新增 `CommentsExample.java`。

## User Value

用户读完 `docs/06-comments/` 四篇，能掌握 nondocx 批注特性的全部公开能力，与 tracked-changes 教程形成对称的学习路径。

## Confirmed Facts

- `docs/05-tracked-changes/` 四篇结构已验证有效（concepts → read → accept/reject → authoring），作为模板。
- nondocx 教学风格约定（AGENTS.md `## Developer Preferences`）：三层递进「OOXML 是什么 → POI 如何表达 → nondocx 为什么这样设计」。
- docx skill 的 `routes/comment.md` + `document.py` 提供了完整的 OOXML 批注知识素材。

## Requirements

### R1. docs/06-comments/ 四篇

- [ ] **R1.1** `docs/06-comments/README.md` —— 章节索引（对照 `05-tracked-changes/README.md`）。
- [ ] **R1.2** `docs/06-comments/01-concepts.md` —— OOXML 批注模型：
  - 三层递进：comments.xml 结构 → POI 的 XWPFComment → nondocx 的 Comment。
  - 四 part 总览（comments / commentsExtended / commentsIds / commentsExtensible）。
  - people.xml / paraId / RSID 基础设施概念。
  - 对照 tracked-changes 的「四大类」做「批注的结构层次」类比。
- [ ] **R1.3** `docs/06-comments/02-read.md` —— 读与查询（对照 `02-read-and-query.md`）。
- [ ] **R1.4** `docs/06-comments/03-authoring.md` —— 创作（范围批注 + 回复线程，对照 `04-authoring.md`）。
  - 注：批注没有 accept/reject 概念（不是修订），所以三篇并四篇：把 tracked 的 03 accept/reject 与 04 authoring 合并为 comments 的 03。
  - 或保留四篇：03 = 创作（单条），04 = 回复线程 + 基础设施。design 决策。
- [ ] **R1.5** 每篇遵守三层递进教学约定（AGENTS.md）。

### R2. API 速查补全

- [ ] **R2.1** `docs/03-api-reference.md` 新增 `Comments & Comment 批注` 段落（对照 `TrackedChanges & TrackedChange 修订`段落）。
- [ ] **R2.2** 列出全部公开方法签名 + 一行说明。

### R3. 索引与 README

- [ ] **R3.1** `docs/README.md` 加入 `06-comments/` 章节。
- [ ] **R3.2** 项目根 `README.md` 的「特性」列表加入「批注（comments）读取、创作与回复线程」条目。

### R4. spec 更新

- [ ] **R4.1** `.trellis/spec/` 里把批注从 out-of-scope / raw()-only 描述改为已支持（具体文件待查 spec 现状）。
- [ ] **R4.2** 若 spec 有「OOXML 特性覆盖矩阵」，更新批注一栏。

### R5. 集成示例

- [ ] **R5.1** `nondocx-examples/` 新增 `CommentsExample.java`，演示完整闭环：
  - 读现有批注 → 创作范围批注 → 回复 → save→reopen → 再读验证线程。
- [ ] **R5.2** 示例可独立运行（`mvn exec:java` 或 main 方法）。

### R6. 父任务集成验收

- [ ] **R6.1** 父任务 `06-22-comments` 的 AC2（POI-free grep）、AC3（文档对称）、AC4（集成示例）、AC5（无回归）逐条核验。
- [ ] **R6.2** 五个子任务的 AC 全绿确认。

## Acceptance Criteria

- [ ] AC1 `docs/06-comments/` 四篇（或合并为三篇，design 决策）完整，结构与 `05-tracked-changes/` 对称。
- [ ] AC2 每篇遵守三层递进教学约定。
- [ ] AC3 `docs/03-api-reference.md` 含 Comments / Comment 段落。
- [ ] AC4 `CommentsExample.java` 可运行，演示完整闭环。
- [ ] AC5 spec 更新把批注标为已支持。
- [ ] AC6 父任务所有 AC 核验通过。
- [ ] AC7 全套测试绿（含新批注测试 + 既有测试无回归）。

## Out of Scope

- **批注的 resolve/done API** —— 留 future（读侧能枚举后，可在后续子任务补 `Comment.resolved()` / `Comments.resolve(id)`）。
- **删除批注 API** —— 留 future。
- **批注的富文本（批注内有表格/图片）** —— POI 的 XWPFComment 实现 IBody 理论上支持，但教学文档不展开，留 raw()。
- **批注范围跨段定位** —— 留 future。

## Open Questions（design.md 收敛）

- **Q1**：docs/06-comments/ 是三篇还是四篇？tracked-changes 是四篇（concepts/read/accept-reject/authoring），但批注无 accept/reject。倾向：
  - 01 concepts
  - 02 read
  - 03 authoring（单条 + 回复合并）
  - 或加 04 infrastructure（paraId/RSID/people.xml 的教学展开）
  - design 阶段定稿。
- **Q2**：spec 更新的具体文件——需先 `get_context.py --mode packages` 看 spec 现状，定位批注当前在哪个 spec 文件里被标为 out-of-scope。
