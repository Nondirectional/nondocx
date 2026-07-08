# Implement — comments 文档+spec

> 配套 `prd.md` + `design.md`。分批执行清单(用户选「分批实现+确认」)。

## 前置确认

- [x] prd OQ 已收敛(design §1):四篇结构;spec 仅改 index.md + README 旧表述。
- [x] 模板:05-tracked-changes 四篇 + 03-api-reference.md TrackedChanges 段落。
- [x] 素材:N18-N24 spec + docx skill routes/comment.md。

## 执行清单(分批)

### 批次 A:docs/06-comments/ 四篇 + README

- [ ] **A.0** `docs/06-comments/README.md` —— 章节索引 + 端到端示例 + 契约关系表(对照 05/README)。
- [ ] **A.1** `01-concepts.md` —— OOXML 批注模型:
  - comments.xml 结构(`<w:comment>` + 正文锚点 commentRangeStart/End/Reference)
  - 四 part 总览(extended 线程 / ids durableId / extensible dateUtc)
  - people.xml/paraId/RSID 基础设施概念(点到,04 展开)
  - 三层递进 + 与 tracked「四大类」的结构类比
- [ ] **A.2** `02-read.md` —— 读与查询:
  - `Comments` 门面(list/get)、body 顺序 vs 部件顺序、孤儿降级
  - `Comment` 五字段 + paraId/parentId
  - 活对象语义、不参与 Document.equals
- [ ] **A.3** `03-authoring.md` —— 创作 + 回复线程:
  - `Paragraph.addComment`(整段范围,锚点 XmlCursor 定位)
  - `Comments.reply`(线程,四 part 自维护)
  - 显式创作路线、与 trackChanges 开关正交
- [ ] **A.4** `04-infrastructure.md` —— 基础设施:
  - people.xml(@mention,w15,OPC 自维护)
  - w14:paraId(线程 key)
  - RSID(文档级单例 + settings.xml + 合并修订语义)
  - beginElement cursor 语义(实现期发现)
- [ ] **A.5** ⏸ 停下给用户确认四篇。

### 批次 B:API 速查 + 索引 + spec

- [ ] **B.1** `docs/03-api-reference.md` 补 Comments/Comment 段落 + Document/Paragraph 表格行(design §3)。
- [ ] **B.2** `docs/README.md` 加 06 章节索引。
- [ ] **B.3** 根 `README.md` 特性列表加 comments 条目 + 文档索引加 06 链接。
- [ ] **B.4** spec `.trellis/spec/backend/index.md` 修 Scope Boundaries(tracked+comments 标已实现)。
- [ ] **B.5** ⏸ 停下给用户确认。

### 批次 C:集成示例 + 父任务验收

- [ ] **C.1** `CommentsExample.java` —— 完整闭环示例(design §4)。
- [ ] **C.2** 编译验证示例(`mvn -pl nondocx-examples compile`)。
- [ ] **C.3** 父任务 AC 核验(design §5):AC2 grep + AC5 全量测试。
- [ ] **C.4** ⏸ 停下给用户确认,然后 commit。

## 验证命令

```bash
mvn -pl nondocx-examples compile -q                              # 示例编译
mvn -pl nondocx-core test -q                                     # 全量回归(AC5)
grep -rn "org.apache.poi" nondocx-core/src/main/java/com/non/docx/core/api/comment/  # AC2 POI-free(只 raw/ctor)
```

## Review Gate(task.py start 前)

planning 制品就绪,用户 review prd+design+implement 后 `task.py start`。
