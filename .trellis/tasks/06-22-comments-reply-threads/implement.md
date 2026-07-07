# Implement — comments 回复 + 线程（commentsExtended 四 part 自维护）

> 配套 `prd.md` + `design.md`。本文是有序执行清单、验证命令与回退点。

## 前置确认（开工前）

- [x] prd.md 四个 Open Question 全收敛（探针 `research/part-lifecycle.md`）。
- [x] design.md 四 part 自维护架构 + 回复算法 + 线程建模已定。
- [x] 探针证实 OPC createPart + addRelationship 可行，Content_Types 自动注册，幂等需防 PartAlreadyExistsException。
- [x] read/authoring 子任务成果已落地：`Comment`/`Comments` + `CommentNodes`（collect + nextCommentId + addWholeParagraphComment）。

## 执行清单（有序）

### 1. paraId / durableId / dateUtc 生成工具

在 `internal/poi/CommentNodes`（或新建 `internal/poi/CommentIds` 小类）加：

- [ ] **1.1** `randomHexId()` → 8 位大写 hex，范围 `[1, 0x7FFFFFFE]`（OOXML paraId/durableId 约束，对照 docx skill `_generate_hex_id`）。
- [ ] **1.2** `dateUtcNow()` → ISO-8601 UTC 字符串（如 `2026-07-07T12:34:56Z`，对照 docx skill timestamp）。

### 2. 四 part 写入助手：`CommentExtendedParts`（新建 internal/poi 类）

- [ ] **2.1** `ensurePart(doc, partName, contentType, relType, rootElementXml)` —— 幂等创建 part：
  - `getPart(name)` 检查；null 时 `createPart` + 写空根元素 + `addRelationship`（design §3.2）。
  - 抛 `PartAlreadyExistsException` 的防御（虽然先 getPart 已防，但探针证实的坑要文档化）。
- [ ] **2.2** `appendEntries(doc, paraId, parentParaId, durableId, dateUtc)` —— 三 part 各追加一条：
  - commentsExtended：`<w15:commentEx w15:paraId=.. [w15:paraIdParent=..] w15:done="0"/>`
  - commentsIds：`<w16cid:commentId w16cid:paraId=.. w16cid:durableId=../>`
  - commentsExtensible：`<w16cex:commentExtensible w16cex:durableId=.. w16du:dateUtc=../>`
  - 用 DOM 读-改-写（design §3.3），防御式解析失败跳过。
- [ ] **2.3** Javadoc 写 OPC part 自维护模式 + 引用 `research/part-lifecycle.md`。

### 3. 线程读侧解析（扩展 `CommentNodes.collect`）

- [ ] **3.1** `parseCommentsExtended(doc)` → `Map<paraId, parentParaId>`：
  - 读 `/word/commentsExtended.xml` part（`getPart`，null 返空 Map）。
  - DOM 解析 `<w15:commentEx>`，取 `w15:paraId` + `w15:paraIdParent`。
  - 防御式：解析失败返空 Map。
- [ ] **3.2** `paraIdOfComment(XWPFComment)` → `String`：批注内首段落的 `w14:paraId`（CT/cursor 读，无则 null）。
- [ ] **3.3** `collect` 扩展：产出 `Comment` 时 join paraId→parentId，调新构造函数 `new Comment(c, paraId, parentId)`。

### 4. `Comment` 扩展线程字段

- [ ] **4.1** 新增构造函数 `Comment(XWPFComment delegate, String paraId, String parentId)`；既有单参构造保留（paraId/parentId 设 null）。
- [ ] **4.2** 字段 `private final String paraId; private final String parentId;`
- [ ] **4.3** `public String paraId()` —— 返回可空 String（无则 null）。
- [ ] **4.4** `public Optional<String> parentId()` —— 根批注/无 paraId 时 empty。
- [ ] **4.5** equals/hashCode 是否纳入新字段：**不纳入**（paraId/parentId 是派生元数据，纳入会破坏 read 子任务的 round-trip 相等性契约——既有测试基于五字段）。文档化此决策。
- [ ] **4.6** Javadoc 更新：标注线程字段来自 commentsExtended，read 子任务的五字段语义不变。

### 5. `Comments.reply` 门面入口

- [ ] **5.1** `Comments` 新增 `public Comment reply(String parentId, String author, String text)`：
  - 校验：parentId 命中（遍历 list，miss 抛 `NoSuchElementException`）；author 非空（`IllegalArgumentException`）。
  - 调 `CommentNodes.replyToComment(delegate, parentComment, author, text)`，返回新 Comment。
- [ ] **5.2** Javadoc：三层映射 + 「回复入口在门面，对照 tc.accept」+ 线程产出说明。

### 6. `CommentNodes.replyToComment` 创作脏活

- [ ] **6.1** `replyToComment(XWPFDocument doc, XWPFComment parent, String author, String text)` → `XWPFComment`：
  - nextCommentId → 新 id；randomHexId → paraId/durableId；dateUtcNow。
  - 建 comments.xml 条目（createComment + 设元数据 + 正文段落）。
  - 给新批注内首段补 w14:paraId（design §4.2）。
  - **检查父批注 paraId**：父批注内首段无 paraId 时补一个（健壮性，design §7）。
  - 正文锚点：XmlCursor 定位父批注 commentRangeStart，其后插新 commentRangeStart；定位父引用 run，其后插新 commentRangeEnd + 引用 run（design §4.3）。
  - `CommentExtendedParts.appendEntries(doc, newParaId, parentParaId, durableId, dateUtc)`。
  - return new Comment(newXWPFComment, newParaId, parentId)。
- [ ] **6.2** Javadoc 引用 design §4 + research。

### 7. 测试：`CommentsReplyThreadsTest`（新建）

参照 `CommentsAuthoringTest` 的 XmlBeans 手搓 fixture 风格。覆盖 prd AC1–AC6：

- [ ] **7.1** AC1+AC2：authoring 建根批注 → reply → 新 Comment 的 parentId() 命中父 id、author/text 正确。
- [ ] **7.2** AC3 round-trip：reply → save → reopen → list() 还原线程（parentId 链完整）。
- [ ] **7.3** AC4 准备：生成 Word 验收 docx（父批注 + 回复，四 part 齐全）。
- [ ] **7.4** AC5 幂等：连续多次 reply，四 part 的 Content_Types/relationship 不重复（unzip 检查 Override/relationship 数）。
- [ ] **7.5** AC6：reply(不存在的 parentId, ...) 抛 NoSuchElementException；reply(parentId, null/blank, ...) 抛 IllegalArgumentException。
- [ ] **7.6** 兼容性：无 commentsExtended 的文档（authoring 产出）list() 不崩，所有批注 parentId() empty、paraId() null。
- [ ] **7.7** 多级回复：A → reply B → reply C（C 的 parent 是 B），线程链 A←B←C 正确。
- [ ] **7.8** 结构断言：unzip 验证四 part 文件存在、Content_Types 含三个 Override、document.xml.rels 含三个 relationship。

### 8. 验证

- [ ] **8.1** `mvn -pl nondocx-core test -Dtest=CommentsReplyThreadsTest` —— 新测试全绿。
- [ ] **8.2** `mvn -pl nondocx-core test` —— 全量回归（重点 `CommentsTest` + `CommentsAuthoringTest` + tracked-changes 无回归）。
- [ ] **8.3** `grep -rn "org.apache.poi" nondocx-core/src/main/java/com/non/docx/core/api/comment/` —— 公共表面 POI-free（构造函数接缝例外）。
- [ ] **8.4** AC4 人工验收：Word/WPS 打开看线程化批注（父下挂子回复）。

### 9. 收尾

- [ ] **9.1** 更新 `research/part-lifecycle.md` 若实现期发现新边界。
- [ ] **9.2** spec：poi-bridge.md 增 N23（OPC 自维护 part 模式 + paraId/durableId 生成 + 线程读侧 join）。

## 验证命令汇总

```bash
mvn -pl nondocx-core test -Dtest=CommentsReplyThreadsTest   # 新测试
mvn -pl nondocx-core test                                    # 全量回归
grep -rn "org.apache.poi" nondocx-core/src/main/java/com/non/docx/core/api/comment/
```

## 回退点

| 风险 | 触发条件 | 回退动作 |
|---|---|---|
| Word 不显示线程 | AC4 失败 | 检查四 part 结构是否齐全；paraId 格式是否 < 0x7FFFFFFF；正文锚点位置是否在父锚点附近 |
| DOM 解析 part 抛 | 畸形 part | 防御式 try/catch，该 part 视为无线程信息 |
| PartAlreadyExistsException | 重复 createPart | 先 getPart 检查（design §3.2 已定） |
| 既有 Comment 构造函数破坏 read 测试 | equals 纳入新字段 | 新字段不纳入 equals（design §4.5 已定） |

## Review Gate（task.py start 前）

planning 制品就绪后，请用户 review `prd.md` + `design.md` + `implement.md`，确认后再 `task.py start 06-22-comments-reply-threads` 进入 Phase 2。
