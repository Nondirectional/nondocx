# Implement Plan — comments 只读消费侧

> 本文件记录该子任务从 planning 进入实现前的执行顺序、验证方式与风险观察点。

## 1. Start 前门槛

- [ ] `prd.md` / `design.md` 已评审通过
- [ ] 已确认当前子任务仍保持最小集合范围：`list()` / `get(id)` + `Comment` 五字段
- [ ] 已确认创作、回复、resolve 状态不属于本子任务
- [ ] 已确认 `Comment` / `Comments` 的公共契约不再继续变动
- [ ] 已补齐实现 / 检查所需 spec 清单（`implement.jsonl` / `check.jsonl`）

**已收敛的关键决策（来自 planning 评审，实现时必须遵守）**：

- **包装形态**：`Comments` 门面持有 `final XWPFDocument`；`Comment` 持有 `final XWPFComment`，`raw()` 返回该委托。走标准 holding-wrapper（design §4），不偏离 poi-bridge.md Rule 1。
- **`list()` 顺序**：body 顺序（按 `document.xml` 内 `commentRangeStart` 的出现顺序），不是 `comments.xml` 部件顺序、不是 `w:id` 升序（design §5）。孤儿批注降级排末尾。
- **`text()` 实现**：委托 `XWPFComment.getText()`；若不稳则回退自拼 `getParagraphs()`（design §4.5 回退条款）。
- **`date()` 类型**：可空 `Calendar`（design §4.4），与 `TrackedChange.date()` 一致。
- **`get(id)` miss**：抛 `NoSuchElementException`，不返回 null（design §6.2）。
- **POI 事实**：`getDocComments()` 无批注时返回 `null`；`CTComment extends CTTrackChange`；`getCommentByID(String)` miss 返回 null。

## 2. 建议实现顺序

### Step 1 — 先落公共模型壳子

- [ ] 新增 `api/comment/Comments` 门面类型（持 `XWPFDocument`，构造函数 internal seam）
- [ ] 新增 `api/comment/Comment` holding-wrapper（持 `XWPFComment`，构造函数 internal seam）
- [ ] `Comment` 五字段 `id()` / `author()` / `initials()` / `date()` / `text()` 全部穿透到委托
- [ ] `Comment.raw()` 返回 `XWPFComment`
- [ ] `Comment.equals` / `hashCode` / `toString`（比较内容派生值，不比较委托引用）
- [ ] `Comments.raw()` 返回 `XWPFDocument`

目标：先把用户可见的静态模型与方法签名定下来，再接 POI 读取路径。这一步先不接 `Document.comments()`（避免编译期依赖未闭环）。

### Step 2 — 接入 `Document.comments()`

- [ ] 在 `Document` 加 `public Comments comments()` 方法（镜像 `trackedChanges()` 418-419 行）
- [ ] Javadoc 三层说明（OOXML comments.xml / POI XWPFComments / nondocx 门面）
- [ ] 明示「文档无批注时门面仍非 null，`list()` 返回空列表」

目标：把门面挂到文档入口上，让后续测试能经 `doc.comments()` 走到。

### Step 3 — 打脏活收容所 `CommentNodes`

- [ ] 新增 `internal/poi/CommentNodes`（对照 `TrackedChangeNodes` 的类 Javadoc 风格）
- [ ] `collect(XWPFDocument)` 返回 `List<Comment>`，实现 design §5.4 的 body 顺序算法：
  - [ ] `getDocComments()` 为 null → 返回空列表
  - [ ] 否则建 `Map<id 字符串, XWPFComment>`，从 `getComments()` 填充
  - [ ] 用 `XmlCursor` 遍历 `CTBody`，按出现顺序收集 `commentRangeStart` 的 `w:id`
  - [ ] 按 body 出现顺序从 Map 取 `XWPFComment`，包装成 `Comment` 产出（取出后从 Map 移除，防重复）
  - [ ] 遍历结束后 Map 剩余的是孤儿批注，按 `comments.xml` 部件顺序追加到末尾
- [ ] 防御式：单个 `XWPFComment` 解析失败时跳过（PRD R3.3），整个 walk 不在本类抛 POI 异常

目标：把 body 顺序算法这一个核心复杂度先打稳。

### Step 4 — 接 `Comments.list()` / `get(id)`

- [ ] `Comments.list()`：调 `CommentNodes.collect(delegate)`，包成不可修改的 `AbstractList`（同 `TrackedChanges.list()` 活视图模式）
- [ ] `Comments.get(String id)`：线性扫 `collect(delegate)`，按 `Comment.id()` 匹配；miss 抛 `NoSuchElementException`（中文消息带 id）
- [ ] POI 异常包装成 `Docx*Exception`（poi-bridge.md Rule 4）

目标：门面的两个核心只读方法跑通。

### Step 5 — 补验收测试

对照 PRD AC1–AC7，新增 `CommentsTest`（测试包路径 `com.non.docx.core.api.comment`），fixture 全部用内存 `XWPFDocument` 手搓 + 写临时文件 + `Docx.open` 重开（同 `TrackedChangesTest` 风格）：

- [ ] **AC3** 无批注文档 `list()` 返回空列表、不抛
- [ ] **AC1** Word 风格批注（commentRangeStart + 段落 + commentReference + commentRangeEnd + comments.xml）能被 `list()` 读出
- [ ] **AC2** `get(id)` 命中返回正确 `Comment`；miss 抛 `NoSuchElementException`
- [ ] **AC4** `Comment` 五字段（id/author/initials/date/text）读值正确，含 date 缺失场景
- [ ] **body 顺序**：fixture 里 `comments.xml` 顺序与 `document.xml` rangeStart 顺序故意不一致，验证 `list()` 按 body 顺序返回（这是 design §5 的核心契约，必须有定向测试）
- [ ] **孤儿批注**：`comments.xml` 有批注但 `document.xml` 无对应 `commentRangeStart`，验证降级排到末尾、不丢弃
- [ ] **多段批注**：`Comment.text()` 返回多段拼接文本
- [ ] **AC5** 活视图：save→reopen 后 `list()` 反映新状态
- [ ] **AC7** 现有 tracked-changes / TOC / paragraph 测试全绿（回归）

目标：让 design §4–§6 的每个契约都有对应测试。

### Step 6 — 收尾与回看

- [ ] 检查 `Document.comments()` 命名与 Javadoc（中文、三层说明）
- [ ] 检查 `Comment` / `Comments` 的中文文档说明
- [ ] 检查异常消息是否中文且带上下文
- [ ] **AC6** grep `org.apache.poi` on `api/comment/`：构造函数接缝的 import 之外无匹配
- [ ] 回看是否无意引入了创作 / 回复 / resolve 能力

## 3. 建议验证命令

```bash
python3 ./.trellis/scripts/task.py validate 06-22-comments-read
mvn -pl nondocx-core test
```

定向跑 comments 测试：

```bash
mvn -pl nondocx-core -Dtest='*Comments*Test,*Comment*Test' test
```

AC6 的 POI-free 校验：

```bash
grep -rn "org.apache.poi" nondocx-core/src/main/java/com/non/docx/core/api/comment/ \
  | grep -v "import org.apache.poi.xwpf.usermodel.XWPFComment;"
```

（构造函数接缝 import `XWPFComment` 是允许的，同 `TrackedChange` import `CTRunTrackChange`。）

## 4. 建议测试夹具 / 覆盖面

至少准备下列文档场景：

- [ ] 无任何批注的文档（`getDocComments()` 返回 null 路径）
- [ ] 单条批注（最小 happy path）
- [ ] 多条批注，`comments.xml` 顺序 ≠ `document.xml` rangeStart 顺序（body 顺序核心验证）
- [ ] 批注 date 缺失（`date()` 返回 null）
- [ ] 批注 initials 缺失（`initials()` 返回空串）
- [ ] 多段批注（`text()` 拼接）
- [ ] 孤儿批注（`comments.xml` 有、`document.xml` 无锚点）
- [ ] `get(id)` miss 场景

fixture 全部用 `XWPFDocument` 内存构造 + 写临时文件（参考 `TrackedChangesTest` 的 `try (XWPFDocument poi = new XWPFDocument()) { ... }` 模式）。comments 的 fixture 构造比 tracked-changes 复杂一点：要同时写 `document.xml` 的锚点（`commentRangeStart` / `commentReference` / `commentRangeEnd`）和 `comments.xml` 的正文（经 `poi.createComments().createComment(id)` + `setAuthor/setText` 等）。

## 5. 风险观察点

- [ ] **body 顺序算法的正确性是最大风险**：`commentRangeStart` 可能在任意层级（段落内、表格单元格内、嵌套），`XmlCursor` walk 要覆盖这些位置——若只扫 body 直接子的段落，会漏掉表格内批注。实现时参照 `TrackedChangeNodes.walkBody/walkTable/walkRow/walkCell` 的完整下钻，或确认 comments 的锚点只在段落级（探针 + fixture 验证）。
- [ ] **`commentRangeStart` 与 `commentReference` 的关系**：一个批注的 `commentRangeStart` / `commentRangeEnd` / `commentReference` 共享同一 `w:id`。body 顺序以 `commentRangeStart` 为准（它是范围的起点，最接近「正文出现位置」）。若某批注只有 `commentReference` 无 `rangeStart`（罕见，部分工具产出），算作孤儿降级。
- [ ] **不要把 `Comment.id()` 的 OOXML `w:id` 暴露成 nondocx 稳定 id 体系**：与 tracked-changes 不同，comments 的 `w:id` 在 OOXML 语义里就是跨会话稳定的外部标识（commentRangeStart 用它配对），nondocx 直接透传即可，不需要像 tracked-changes 那样造混合 id。保持 `Comment.id()` === `delegate.getId().toString()`。
- [ ] 不要在 read 子任务里偷偷实现创作或 resolve 能力。
- [ ] `XWPFComment.getText()` 若在测试中发现不稳，按 design §4.5 回退到自拼，不必回 planning。

## 6. Rollback / 回退策略

- 若 body 顺序算法在表格 / 嵌套场景下证明复杂度超预期（如锚点跨表格、跨 SDT），先回到 `design.md` 明确边界（是否承诺覆盖这些场景），再动代码。
- 若 `XWPFComment.getText()` 行为异常，按 design §4.5 回退自拼，不改变公共契约。
- 若 `get(id)` 需要支持 miss 返回 Optional 的语义（而非抛异常），先回 design 复审——目前与 `TrackedChanges.get()` 一致是抛异常。

## 7. Ready-to-start 判定

只有以下条件同时满足，才建议对该子任务执行 `task.py start`：

- [ ] `prd.md` / `design.md` / `implement.md` 三件套齐全
- [ ] holding-wrapper 形态、body 顺序契约、`text()` 委托策略已评审通过
- [ ] `implement.jsonl` / `check.jsonl` 已补真实条目
- [ ] 开发者认可当前仍是「最小集合」实现范围（`list()` / `get(id)` + 五字段）
