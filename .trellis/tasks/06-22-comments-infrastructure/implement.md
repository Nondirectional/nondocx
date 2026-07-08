# Implement — comments 基础设施（people.xml / paraId / RSID）

> 配套 `prd.md` + `design.md`。本文是有序执行清单、验证命令与回退点。

## 前置确认（开工前）

- [x] prd.md 4 个 Open Question 全收敛；scope 明确（dateUtc 跳过，paraId 补到 addComment，people/RSID 全新）。
- [x] design.md 三项基础设施架构已定；探针确认 CTDocRsids.class 缺失，RSID 走 XmlCursor。
- [x] reply-threads 已建 OPC part 自维护模式（N23）+ CommentExtendedParts（含 randomHexId 可复用）。

## 执行清单（有序）

### 1. 新建 `internal/poi/AuthoringInfra` 类

三项基础设施的统一入口。逐步加方法：

- [ ] **1.1** `newParaId()` → 复用 `CommentExtendedParts.randomHexId()`（已存在，8 位 hex `< 0x7FFFFFFF`）。
- [ ] **1.2** `setParaId(XWPFParagraph p, String paraId)` —— 从 reply-threads 的 `CommentNodes.setParagraphParaId`（私有）提升为 public，逻辑不变（XmlCursor setAttributeText `{w14}paraId`）。
- [ ] **1.3** `newRsid()` → 8 位大写 hex 随机（RSID 无 `< 0x7FFFFFFF` 约束，但仍用 8 位 hex；可复用 randomHexId 或独立实现）。
- [ ] **1.4** `registerAuthor(XWPFDocument doc, String author)` —— people.xml 维护（design §3.2）：
  - `ensurePart`（复用 CommentExtendedParts 的 OPC 模式，或抽公共 helper）：`/word/people.xml` + `...people+xml` contentType + relationship。
  - DOM 读-改-写（clear 后覆盖）：扫现有 `<w15:person w15:author=..>`，author 精确匹配跳过；不存在则追加 person 条目（XML 转义 author）。
  - 防御式：DOM 失败跳过。
- [ ] **1.5** `documentRsid(XWPFDocument doc)` → String（design §5）：
  - XmlCursor 读 settings.xml 的 `<w:rsids>/<w:rsidRoot w:val=..>`；存在返回 val。
  - 不存在：生成 newRsid，建 rsids 段（rsidRoot + rsid），返回。
  - 防御式：settings.xml 缺失/操作失败时返回临时 newRsid（不阻断，但不持久化）。
- [ ] **1.6** `stampRsid(XWPFParagraph p, String rsid)` —— 给段落设 `w:rsidR` + `w:rsidRDefault`（XmlCursor setAttributeText，`{w}rsidR` / `{w}rsidRDefault`）。
- [ ] **1.7** `stampRsid(CTR r, String rsid)` —— 给 run 设 `w:rsidR`。
- [ ] **1.8** Javadoc：每方法写「OOXML 结构 + 注入语义」，类顶说明这是 comments 路径的现代 Word 兼容基础设施（tracked-changes 不接入，AC6）。

### 2. addComment 路径接入（`CommentNodes.addWholeParagraphComment`）

- [ ] **2.1** 在 return 前，给批注内首段补 paraId：
  ```java
  String paraId = AuthoringInfra.newParaId();
  AuthoringInfra.setParaId(comment.getParagraphs().get(0), paraId);
  ```
- [ ] **2.2** 给创作产出的节点标 RSID：
  ```java
  String rsid = AuthoringInfra.documentRsid(document);
  AuthoringInfra.stampRsid(comment.getParagraphs().get(0), rsid);  // 批注内段落
  AuthoringInfra.stampRsid(refRun, rsid);                          // 正文引用 run
  ```
- [ ] **2.3** 注册 author 到 people.xml：
  ```java
  AuthoringInfra.registerAuthor(document, author);
  ```

### 3. reply 路径接入（`CommentNodes.replyToComment`）

- [ ] **3.1** paraId 收敛：把现有的 `setParagraphParaId`（私有）调用改为 `AuthoringInfra.setParaId`（统一入口）。逻辑不变。
- [ ] **3.2** 给回复批注的段落 + 引用 run 标 RSID（同 §2.2）。
- [ ] **3.3** 注册 author 到 people.xml（同 §2.3）。
- [ ] **3.4** 父批注 paraId 补全（`ensureCommentParaId`）保留，但内部改调 `AuthoringInfra.newParaId`/`setParaId`。

### 4. 清理：CommentNodes 的私有 paraId helper

- [ ] **4.1** `setParagraphParaId`（私有）提升到 `AuthoringInfra.setParaId`（public）后，删除 CommentNodes 的私有副本。
- [ ] **4.2** `ensureCommentParaId` 内部改用 AuthoringInfra。

### 5. 测试：`CommentsInfrastructureTest`（新建）

覆盖 AC1–AC3、AC5、AC6：

- [ ] **5.1** AC1+AC5 people.xml：addComment 后 unzip 验证 `word/people.xml` 存在且含 author 条目；连续两次同 author 的 addComment，people.xml 只一条 person（幂等）。
- [ ] **5.2** AC2 paraId：addComment 后批注内段落有 `w14:paraId`（8 位 hex）；reply 后回复批注内段落也有。
- [ ] **5.3** AC3 RSID：addComment 后批注内段落有 `w:rsidR`/`w:rsidRDefault`；正文引用 run 有 `w:rsidR`；settings.xml 的 rsids 段含该 RSID（rsidRoot）。
- [ ] **5.4** RSID 文档级单例：同一文档两次 addComment，两次的 RSID 相同（documentRsid 读回同一个）；不同文档 RSID 不同（概率上）。
- [ ] **5.5** AC6 隔离：既有 tracked-changes 创作路径产出的节点**无** RSID（grep tracked authoring 测试的产物，或断言 TrackedChangeNodes 不调 AuthoringInfra）。
- [ ] **5.6** round-trip：addComment → save → reopen，people.xml/rsids/paraId 都持久化。
- [ ] **5.7** 防御式：故意破坏 settings.xml（如删 rsids 段），addComment 仍成功（降级，不阻断）。
- [ ] **5.8** 更新既有 CommentsAuthoringTest：addComment 现在产出 paraId/RSID，若有结构断言受影响则更新（paraId/RSID 是属性非子元素，子元素顺序应不变——验证）。

AC4（Word 显示 author）留 review gate 人工项。

### 6. 验证

- [ ] **6.1** `mvn -pl nondocx-core test -Dtest=CommentsInfrastructureTest` —— 新测试全绿。
- [ ] **6.2** `mvn -pl nondocx-core test` —— 全量回归（重点 CommentsTest/CommentsAuthoringTest/CommentsReplyThreadsTest + tracked-changes 无回归）。
- [ ] **6.3** `grep -rn "AuthoringInfra" nondocx-core/src/main/java/com/non/docx/core/internal/poi/TrackedChangeNodes.java` —— 确认 tracked-changes 未接入（AC6）。
- [ ] **6.4** AC4 人工验收：Word 打开看审阅面板 author 显示。

### 7. 收尾

- [ ] **7.1** spec：poi-bridge.md 增 N24（people.xml 自维护 + RSID settings.xml XmlCursor + paraId 收敛 + CTDocRsids dangling reference）。
- [ ] **7.2** research：若实现期发现新边界（如 settings.xml rsids schema 顺序要求），补充记录。

## 验证命令汇总

```bash
mvn -pl nondocx-core test -Dtest=CommentsInfrastructureTest
mvn -pl nondocx-core test
grep -rn "AuthoringInfra" nondocx-core/src/main/java/com/non/docx/core/internal/poi/TrackedChangeNodes.java  # 应无输出
```

## 回退点

| 风险 | 触发条件 | 回退动作 |
|---|---|---|
| CTDocRsids 运行时缺失 | getRsids() 抛 ClassNotFoundException | 已定走 XmlCursor（design §2.2），typed API 不用 |
| CommentsAuthoringTest 结构断言失败 | addComment 补 paraId/RSID 后子元素顺序变 | paraId/RSID 是属性,子元素顺序应不变;若断言查属性则更新 |
| people.xml DOM 累加 | 多次 registerAuthor 内容重复 | 复用 N23 的 clear() 模式;author 去重先扫 |
| settings.xml schema 顺序 | rsids 段位置不合规 Word 报错 | Word 通常宽容;严格则 XmlCursor 按 schema 顺序插(compat 后) |

## Review Gate（task.py start 前）

planning 制品就绪后,请用户 review `prd.md` + `design.md` + `implement.md`,确认后再 `task.py start 06-22-comments-infrastructure` 进入 Phase 2。
