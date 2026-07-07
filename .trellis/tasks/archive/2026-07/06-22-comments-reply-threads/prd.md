# PRD — comments 回复+线程（commentsExtended）

> 父任务：`06-22-comments`（planning）。
>
> **背景**：前序子任务 `06-22-comments-authoring` 已交付单条范围批注创作。本子任务补齐**回复能力 + 线程结构**——支持对已有批注回复，形成父子线程。
>
> **对照**：`06-18-tracked-changes-advanced-types`（高级类型：move / 属性 / cell）。

## Goal

交付批注回复 + 线程能力，核心是自维护 OOXML 批注的**四个 part**（POI 不提供 API）：

1. **`Comments.reply(String parentId, String text)`** —— 对已有批注回复。
2. **线程建模** —— `Comment` 暴露 `parentId()` / `replies()`，可遍历线程树。
3. **四 part 自维护**：`comments.xml` + `commentsExtended.xml` + `commentsIds.xml` + `commentsExtensible.xml`。

## User Value

```java
try (Document doc = Docx.open(Path.of("commented.docx"))) {
    Comments comments = doc.comments();

    // 对 id=0 的批注回复(author 必传)
    comments.reply("0", "审阅者乙", "同意，但建议补充数据来源");

    // 遍历线程
    for (Comment c : comments.list()) {
        if (c.parentId().isPresent()) {
            System.out.println("  ↳ 回复 " + c.parentId().get() + ": " + c.text());
        } else {
            System.out.println("根批注: " + c.text());
        }
    }
}
```

完成后在 Word 打开能看到**线程化**的批注（父批注下挂子回复）。

## Confirmed Facts（已实测 POI 5.2.5）

OOXML 批注线程结构（来自 docx skill `document.py` 探针）：

```
comments.xml           — 批注内容（POI 支持）
commentsExtended.xml   — 线程关系（w15:paraIdParent 链父批注）  ← POI 无 API
commentsIds.xml        — durableId 映射                          ← POI 无 API
commentsExtensible.xml — w16cex 扩展（dateUtc 等）                ← POI 无 API
```

**回复的 OOXML 机制**（来自 docx skill `document.py` 第 804-870 行）：

- 在父批注的 `commentRangeStart` 后插新批注的 `commentRangeStart` + `commentRangeEnd` + `commentReference` run。
- comments.xml 里加新批注条目（同普通批注）。
- **commentsExtended.xml** 里给新批注标 `w15:paraIdParent`（指向父批注的 paraId）—— 这是「线程」的关键。
- commentsIds.xml / commentsExtensible.xml 同步加映射。

**POI 边界**：

| Part | POI 5.2.5 | nondocx 自维护 |
|---|---|---|
| comments.xml | ✅ `XWPFComments` | 复用 |
| commentsExtended.xml | ❌ 无 Java 类 | XmlCursor 拼 + Content_Types 注册 |
| commentsIds.xml | ❌ 无 Java 类 | 同上 |
| commentsExtensible.xml | ❌ 无 Java 类 | 同上 |
| people.xml | ❌ 无 Java 类 | 子任务 4 |

## Requirements

### R1. 回复 API

- [ ] **R1.1** `Comments.reply(String parentId, String author, String text)` 返回新 `Comment`（回复批注）。
- [ ] **R1.2** parentId 未命中抛 `NoSuchElementException`（对照 `get(id)`）；author null/空白抛 `IllegalArgumentException`（对照 `addComment`）。
- [ ] **R1.3** author 必传（Q3 收敛：与 `addComment` 对称，回复者常与原作者不同）。
- [ ] **R1.4** date / w:id / paraId / durableId 自动分配。

### R2. 线程建模

- [ ] **R2.1** `Comment.parentId()` 返回 `Optional<String>`（根批注为 empty）。
- [ ] **R2.2** `Comment.paraId()` 返回批注的 w14:paraId（线程关系靠它链）。
- [ ] **R2.3** 读侧 `Comments.list()` 能区分根批注与回复（基于 commentsExtended.xml 的 paraIdParent）。

### R3. 四 part 自维护

- [ ] **R3.1** 首次添加回复时，自动创建 `commentsExtended.xml` / `commentsIds.xml` / `commentsExtensible.xml`（从模板或空 part 起步）。
- [ ] **R3.2** 在 `[Content_Types].xml` 注册三个 part 的 Override。
- [ ] **R3.3** 在 `word/_rels/document.xml.rels` 加三个 part 的 Relationship。
- [ ] **R3.4** 幂等：重复 reply 不重复注册 relationship / Override。

### R4. 一致性约束

- [ ] 回复入口在**门面**（`Comments.reply`），不在内容类型——对照 `tc.accept`（处理已有结构的破坏性写在门面）。
- [ ] POI-free 公共表面；四 part 维护脏活在 `internal/poi/CommentNodes`。
- [ ] 创作出的线程 save→reopen round-trip 后，Word 能正确显示父子关系。

## Acceptance Criteria

- [ ] AC1 `Comments.reply(parentId, author, text)` 可用，返回新 `Comment`。
- [ ] AC2 回复后 `Comment.parentId()` 返回父批注 id。
- [ ] AC3 save→reopen 后 `Comments.list()` 能正确还原线程关系（parent 链完整）。
- [ ] AC4 在 Word 打开能看到**线程化**批注（父批注下挂子回复，人工验收）。
- [ ] AC5 四 part 的 Content_Types / relationship 注册幂等（重复 reply 不破坏结构）。
- [ ] AC6 parentId 未命中抛 `NoSuchElementException`。

## Out of Scope

- **people.xml 注入** —— 子任务 `06-22-comments-infrastructure`。
- **批注 resolve/done 状态** —— commentsExtended.xml 里有 `done` 属性，本子任务可顺带读写，但不做专门 API（留 future）。
- **跨段批注的回复** —— 回复锚点固定在父批注的 rangeStart 后，不涉及跨段定位。

## Open Questions（已收敛 → 见 design.md）

- **Q1 ✅ 已收敛**（探针见 `research/part-lifecycle.md`）：四 part 用 **POI 的 OPC API 代码内嵌创建**——`OPCPackage.createPart(partName, contentType)` + `getOutputStream` 写内容 + `addRelationship` 加关系。`[Content_Types].xml` 的 Override **由 POI createPart 自动注册**，无需手写。不用静态模板文件（docx skill 的字符串模板对照参考，但 nondocx 代码内嵌 XML）。**幂等**：重复 createPart 抛 `PartAlreadyExistsException`，nondocx 先 `getPart` 检查（存在追加、不存在 create）。
- **Q2 ✅ 已收敛**：`Comment.parentId()` 读 commentsExtended.xml 的 `w15:paraIdParent`。解析需 paraId→commentId 映射——在 `Comments.list()` 时扫 commentsExtended 建 `paraId→parentId`，再扫 comments.xml 建 `paraId→commentId`（批注内首段的 `w14:paraId`），两端 join 得 `commentId→parentId`。详见 design §5。
- **Q3 ✅ 已收敛**：回复 author **必传**（与 `addComment` 对称：`Comments.reply(String parentId, String author, String text)`）。理由：回复者常与原作者不同；authoring 已定 author 必传，reply 保持一致。
- **Q4 ✅ 已收敛**：本子任务四 part 全做（含 durableId/dateUtc 生成）。paraId / durableId 用 8 位 hex 随机（`< 0x7FFFFFFF`，OOXML 约束）；dateUtc 用 ISO-8601 UTC。对照 docx skill 的 `_generate_hex_id`。**决策依据**：四 part 全做以完全对称 Word 自身产出，人工验收无悬念；durableId/dateUtc 本属子任务 4 范围，提前到本子任务与线程关系一并交付更内聚。
