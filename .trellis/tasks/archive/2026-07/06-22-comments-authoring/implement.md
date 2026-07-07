# Implement — comments 基础创作（单条范围批注）

> 配套 `prd.md` + `design.md`。本文是有序执行清单、验证命令与回退点。

## 前置确认（开工前）

- [x] prd.md 三个 Open Question 已收敛（Q1/Q3 定、Q2 标低风险）。
- [x] design.md §4 锚点写入算法已探针验证（`research/insert-position.md`）。
- [x] read 子任务成果已落地：`api/comment/{Comment,Comments}` + `internal/poi/CommentNodes`，且 `Comment.raw()` / `Comments.raw()` 已埋 authoring 接缝（javadoc 明示）。

## 执行清单（有序）

### 1. `internal/poi/CommentNodes`：创作脏活下沉

在现有 `CommentNodes`（当前只有只读 `collect`）追加两个静态方法：

- [ ] **1.1** `nextCommentId(XWPFDocument doc)` → `BigInteger`
  - 扫 `getDocComments().getComments()` 取 max(id)+1；null 或空返 0（design §5）。
  - 注意：与 `TrackedChangeNodes.nextRevisionId` **独立计数器**，不混用。
- [ ] **1.2** `addWholeParagraphComment(XWPFParagraph target, String author, String text, Calendar date, BigInteger id)` → `XWPFComment`
  - 算法见 design §4：建 comments.xml 条目 → CTP 上 addNew 三锚点 → XmlCursor 把 start move 到段首 → 返回新建 `XWPFComment`。
  - **边界**：空段（CTP 无子）时 `toFirstChild()` 返 false，start 的 `addNew` 自然在首位，跳过 move（design §9）。
  - doc 从 `target.getDocument()` 拿（不在签名暴露，减少参数）；comments part 用 `getDocComments()` ?? `createComments()`。
  - initials 设空串（design §4.1）。
- [ ] **1.3** Javadoc 写「OOXML / POI / nondocx 三层」+ 引用 `research/insert-position.md`（教学约定）。

### 2. `api/text/Paragraph`：公共创作入口

- [ ] **2.1** 新增 `public Comment addComment(String author, String text)`
  - `requireAuthor(author)`（复用现有私有方法）+ `Objects.requireNonNull(text, "text")`。
  - `Calendar now = Calendar.getInstance()`；`BigInteger id = CommentNodes.nextCommentId(delegate.getDocument())`。
  - `XWPFComment created = CommentNodes.addWholeParagraphComment(delegate, author, text, now, id)`。
  - `return new Comment(created);`
- [ ] **2.2** import `com.non.docx.core.api.comment.Comment`。
- [ ] **2.3** Javadoc：三层映射 + 「与 addInsertion 同属显式创作入口」「返回新建 Comment holding-wrapper」+ `@throws`（对照 `addInsertion` javadoc 风格）。

### 3. 测试：`CommentsAuthoringTest`（新建）

位置：`nondocx-core/src/test/java/com/non/docx/core/api/comment/CommentsAuthoringTest.java`

参照 `CommentsTest` 的 XmlBeans 手搓 fixture 风格（确定性、不依赖 Word）。覆盖 prd AC1–AC5：

- [ ] **3.1** AC1 + AC2：给已有内容的段落 `addComment`，断言返回的 `Comment` 的 id/author/text 正确；`doc.comments().list()` 能读回该批注。
- [ ] **3.2** AC3 round-trip：`addComment` → save → reopen → `doc.comments().list()` 仍读到，author/text 正确；并用 XmlBeans 探针断言 `document.xml` 里 `commentRangeStart` 在段首、`commentRangeEnd` 在 run 之后（结构完整）。
- [ ] **3.3** AC5：`addComment(null, ...)` / `addComment("  ", ...)` 抛 `IllegalArgumentException`；`addComment(author, null)` 抛 `IllegalArgumentException`。
- [ ] **3.4** 多条批注 id 自增：同段或不同段连续 `addComment` 两次，第二条 id = 第一条 +1（验证 `nextCommentId`）。
- [ ] **3.5** 空段批注：给无 run 的段落 `addComment`，round-trip 后能读回（覆盖 design §9 边界）。
- [ ] **3.6** 不污染现有写：`addRun` 后 `paragraph.runs()` 不含批注锚点 run（验证 design §6）。

AC4（Word/WPS 人工验收）不在单测覆盖——留作 3.4 review gate 的人工项。

### 4. 验证

- [ ] **4.1** `mvn -pl nondocx-core test -Dtest=CommentsAuthoringTest` —— 新测试全绿。
- [ ] **4.2** `mvn -pl nondocx-core test` —— 全量回归（重点看 `CommentsTest`、tracked-changes 相关测试无回归，prd 父任务 AC5）。
- [ ] **4.3** `grep -rn "org.apache.poi" nondocx-core/src/main/java/com/non/docx/core/api/comment/ nondocx-core/src/main/java/com/non/docx/core/api/text/Paragraph.java` —— 确认公共 API 表面 POI-free（`Comment` 构造函数接缝的 import 除外，对照 read 子任务）。

### 5. 收尾

- [ ] **5.1** 更新 `research/insert-position.md` 若实现期发现新边界（如 moveXml 在某些 POI 版本的行为差异）。
- [ ] **5.2** AC4 人工验收准备：跑一个一次性的 example（不入库）save 出 docx，在 Word/WPS 打开看批注气泡。若显示异常 → 触发 design §9 的 Q2 回退（补 rStyle + styles.xml）。

## 验证命令汇总

```bash
# 单测（快）
mvn -pl nondocx-core test -Dtest=CommentsAuthoringTest

# 全量回归
mvn -pl nondocx-core test

# POI-free 检查
grep -rn "org.apache.poi" \
  nondocx-core/src/main/java/com/non/docx/core/api/comment/ \
  nondocx-core/src/main/java/com/non/docx/core/api/text/Paragraph.java
```

## 回退点

| 风险 | 触发条件 | 回退动作 |
|---|---|---|
| Q2 rStyle | AC4 Word 显示批注引用异常 | 在 `addWholeParagraphComment` 给 refRun 加 `RStyle=CommentReference` + styles.xml 建样式定义；回 design 补 §4.1 |
| XmlCursor move 异常 | 某些 POI 版本 moveXml 行为不稳 | 改用 `insertNewCommentRangeStart` + 手动节点重排；回 design §4 调整算法 |
| nextCommentId 与锚点 id 不一致 | 测试发现 id 冲突 | 检查是否误用了 `nextRevisionId`（两套计数器） |

## Review Gate（task.py start 前）

planning 制品就绪后，请用户 review `prd.md` + `design.md` + `implement.md`，确认后再 `task.py start 06-22-comments-authoring` 进入 Phase 2 实现。
