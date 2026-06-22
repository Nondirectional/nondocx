# core 批注(comments)封装（父任务）

## Goal

将 nondocx 当前对「批注（comments）」的**完全空白**状态，规划并拆解为一组可独立验收的子任务，最终交付一套面向用户的一等公民封装。

这个父任务本身**不直接承载所有实现细节**；它负责：

- 保存源需求与总体目标
- 维护子任务拆分与边界
- 约束跨子任务的一致性
- 在所有子任务完成后做集成验收

> **设计参照**：本父任务完全对照 `06-18-tracked-changes` 父任务的五子任务结构（read → authoring → advanced types → infrastructure/cell → docs/spec），让两条能力线在 nondocx 里对称生长。

## User Value

完成后，nondocx 用户应能以领域 API 而非裸 POI / CT 操作批注：

- **消费侧**：枚举文档里的批注、按 id 查、读 author/text/date/initials
- **创作侧**：显式给某段内容添加范围批注、回复已有批注形成线程
- **兼容性**：产出的批注在现代 Word / WPS 里正确显示，包含必要的协作元数据（people.xml / paraId）

## Confirmed Facts（已 javap / unzip 实测 POI 5.2.5）

| 能力点 | POI 5.2.5 支持 | 备注 |
|---|---|---|
| `XWPFDocument.getDocComments()` / `createComments()` | ✅ 有 | 高层入口 |
| `XWPFComments.createComment(id)` / `getCommentByID(id)` | ✅ 有 | 增/查 |
| `XWPFComment`（含 author/initials/date/paragraphs，实现 `IBody`） | ✅ 有 | 单条批注包装 |
| `CTP.addNewCommentRangeStart/End()` | ✅ 有 typed 访问器 | 范围锚点 |
| `CTR.addNewCommentReference()` | ✅ 有 | 引用 run |
| `CTComment` / `CTComments` / `CTMarkupRange` schema | ✅ lite jar 已含 `.xsb` | 基础类型可达 |
| **`commentsExtended.xml`（批注回复/线程）** | ❌ POI 无 API | 需自写 part + 维护关系 |
| **`commentsIds.xml` / `commentsExtensible.xml`** | ❌ POI 无 API | 同上 |
| **`people.xml`（@mention 协作元数据）** | ❌ POI 无 API（schema 在，无 Java 类） | 需 XmlCursor 拼 |
| **w14:paraId / w16du:dateUtc 自动注入** | ❌ POI 无自动机制 | 需在创作入口注入 |

**关键判断**：

- 批注的**读 + 基础创作（单条范围批注）**POI 有现成 API，封装成本低（子任务 1、2）。
- 回复/线程 + 协作元数据基础设施 POI 不提供，要 nondocx 自己用 XmlCursor 拼（子任务 3、4）。

## 设计原则（跨子任务一致性约束）

本父任务参照 `06-18-tracked-changes` 父任务的 R3 约束，作对称声明：

### R1. 总体能力目标

- [ ] 最终交付覆盖**消费侧 + 创作侧**两条能力线。
- [ ] 消费侧最终应支持：批注枚举、按 id 查、读 author/text/date。
- [ ] 创作侧最终应支持：添加范围批注、回复已有批注（线程）。
- [ ] 创作出的批注在现代 Word / WPS 打开后正确显示（含必要协作元数据）。

### R2. 子任务边界必须清晰

- [ ] 每个子任务都必须有**独立、可验证**的交付物与验收标准。
- [ ] 父任务负责跨子任务的 API 一致性与最终集成，不把多个子任务的具体实现细节重新堆回父任务里。
- [ ] 若后续发现某一块还能进一步独立验证，可以继续增补子任务。

### R3. 跨子任务的一致性约束（对照 tracked-changes R3）

- [ ] **入口分置**：读/查询（`Comments` 门面）与创作（`Paragraph.addComment` 等内容类型方法）分置——与 `tracked-changes` 一致：门面管 accept/reject，内容类型管创作。
- [ ] 创作 API 坚持「**显式**」路线：想给某处加批注就显式调对应方法，不引入「全局批注录制」魔法。
- [ ] 现有不带批注的 API 行为保持不变；comments 的引入不得改变现有默认写语义。
- [ ] 现有只读 API（如 `paragraph.text()` / `runs()`）行为保持不变；批注通过专门能力读取，而不是偷偷改变旧 API 含义。
- [ ] 公共 API 继续保持 **POI-free**；CT / XmlBeans 脏活集中在 `internal/poi/`。
- [ ] 对外异常继续遵守 `error-handling.md`；POI / XmlBeans 细节不泄漏到公共表面。
- [ ] **活对象语义**：`Comments.list()` 每次重算不缓存，与 `TrackedChanges.list()` 一致。

### R4. 分阶段交付顺序

- [ ] 子任务的默认顺序为：只读消费侧 → 基础创作 → 回复+线程 → 基础设施 → 文档/spec 收尾。
- [ ] 如果实现中发现高风险子题（如 commentsExtended 关系维护、people.xml schema）需要再拆，优先继续拆分。

### R5. 与 tracked-changes 的对称性

- [ ] 包结构：`api/comment/` 公开层（POI-free）+ `internal/poi/CommentNodes` 脏活收容所——对照 `api/track/` + `internal/poi/TrackedChangeNodes`。
- [ ] 文档结构：`docs/06-comments/` 四篇——对照 `docs/05-tracked-changes/` 四篇。
- [ ] 异常契约：family gate 用 `UnsupportedFeatureException`（如 future 扩展类型）——对照 tracked-changes。

## Task Map（父 / 子任务拆分）

当前父任务拆分为以下子任务（对照 `06-18-tracked-changes` 的五子任务结构）：

| # | 子任务 | 对照 tracked-changes | 范围 |
|---|---|---|---|
| 1 | `06-22-comments-read` | `06-18-tracked-changes-read` | 只读消费侧：枚举、按 id 查、读元数据 |
| 2 | `06-22-comments-authoring` | `06-18-tracked-changes-authoring` | 基础创作：单条范围批注（Paragraph/Run.addComment） |
| 3 | `06-22-comments-reply-threads` | `06-18-tracked-changes-advanced-types` | 回复 + 线程（commentsExtended 四 part 自维护） |
| 4 | `06-22-comments-infrastructure` | `06-18-tracked-changes-cell-types`（结构收尾定位） | people.xml / paraId / RSID 基础设施 |
| 5 | `06-22-comments-docs-spec` | `06-18-tracked-changes-docs-spec` | 文档、API 速查、spec 更新、收尾 |

## 父任务保留的未决问题

- [ ] **Q1**：`Comment` 是不可变值对象还是 holding wrapper？参照 `TrackedChange` 的双委托模式，`Comment` 可能需要持 `XWPFComment` 委托（POI 已有高层类型），具体在子任务 1 design 决策。
- [ ] **Q2**：范围批注的 API 形态——`Paragraph.addComment(author, text)` 锚到整段，还是支持更细的 run 范围（`addComment(startRun, endRun, ...)`）？倾向先做整段，run 范围作 v2。子任务 2 design 收敛。
- [ ] **Q3**：commentsExtended 四 part 的关系维护是否需要在 nondocx 层做幂等保证（防止重复添加 relationship）？子任务 3 design 收敛。
- [ ] **Q4**：基础设施注入（RSID/paraId）是仅作用于批注创作路径，还是回溯补到现有 tracked-changes 创作路径？倾向「仅批注路径，tracked-changes 留作 future」——避免改动已稳定的 track 包。子任务 4 design 收敛。

## Acceptance Criteria（父任务集成验收）

- [ ] AC1 五个子任务全部交付，各自 AC 全绿。
- [ ] AC2 公开 API 表面 POI-free（grep `org.apache.poi` 不出现在 `api/comment/` 源码）。
- [ ] AC3 教学文档 `docs/06-comments/` 四篇完整，与 `docs/05-tracked-changes/` 结构对称。
- [ ] AC4 集成示例：`nondocx-examples` 新增 `CommentsExample.java`，演示「读 → 创作范围批注 → 回复 → save→reopen → 再读」完整闭环。
- [ ] AC5 现有功能无回归（既有 tracked-changes 测试全绿）。
