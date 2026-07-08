# 03 · 创作与回复

[02 读](./02-read.md) 讲了怎么读批注。这一篇讲怎么**主动写出**批注 —— 「创作（authoring）」侧：给某段内容加范围批注，以及对已有批注回复形成线程。

> 批注没有 accept/reject 概念（不是修订，不会被「接受/撤销」，它只是评论）。所以本篇把 tracked-changes 教程里拆成两篇（accept/reject + authoring）的内容合并 —— 批注只有「创作」，没有「处理」。

---

## 1. 两个入口，分置在内容类型与门面

| 操作类型 | 入口位置 |
|---|---|
| **创作根批注**（给某段加新批注） | **内容类型** `Paragraph.addComment` |
| **回复已有批注**（形成线程） | **门面** `Comments.reply` |

这是个**关键设计决定**，与 tracked-changes 对称（spec [poi-bridge.md N22](../../.trellis/spec/backend/poi-bridge.md)）：

- 创作根批注属于「**在某处写内容**」，归那个内容类型（`Paragraph`，同 `addInsertion`/`addDeletion`）
- 回复属于「**对已有批注做处理**」，归门面（`Comments`，同 `TrackedChanges.accept`）

---

## 2. 创作范围批注：`Paragraph.addComment`

```java
Comment c = doc.paragraph(0).addComment("审阅者甲", "这段需要补充背景");
// 返回新建的 Comment，可立即读元数据
System.out.println(c.id());       // "0"
System.out.println(c.author());   // "审阅者甲"
```

**范围语义是「整段」**：批注的 `commentRangeStart` 在段首、`commentRangeEnd` 在段末，包住本段全部已有内容。

### OOXML 形态

创作出的结构（[01 §1](./01-concepts.md) 的展开）：

```xml
<!-- word/document.xml —— 正文锚点 -->
<w:p>
  <w:commentRangeStart w:id="0"/>     <!-- 必须在段首（包住整段） -->
  <w:r>已有文本</w:r>
  <w:commentRangeEnd w:id="0"/>       <!-- run 之后（段末语义位置） -->
  <w:r><w:commentReference w:id="0"/></w:r>   <!-- 引用 run（新建，段末） -->
</w:p>
<!-- word/comments.xml —— 批注正文 -->
<w:comment w:id="0" w:author="审阅者甲" w:date="..." w:initials="">
  <w:p><w:r><w:t>这段需要补充背景</w:t></w:r></w:p>
</w:comment>
```

### POI 的坑：`addNew` 不按 schema 顺序

`XWPFComments.createComment(id)` 只建 comments.xml 条目，**不动正文**。正文锚点要自己用 `CTP.addNewCommentRangeStart()` 等建。而 POI 的 `addNew`/`insertNew` **都不按 OOXML schema 顺序排序** —— `commentRangeStart` 会被追加到段末（在所有已有 run 之后），导致范围实际为空（包住 0 个 run）：

```
addNew 前:  [r, r]
addNew 后:  [r, r, commentRangeStart, commentRangeEnd, r(引用)]   ← start 在段末,范围为空
```

nondocx 的 `CommentNodes.addWholeParagraphComment` 用 `XmlCursor` 把 `commentRangeStart` 手动 move 到 CTP 第一个子之前（spec [poi-bridge.md N22](../../.trellis/spec/backend/poi-bridge.md)）：

```java
XmlCursor pCur = ctp.newCursor();
XmlCursor startCur = start.newCursor();
if (pCur.toFirstChild()) {      // 空段时返 false,跳过 move
  startCur.moveXml(pCur);       // 把 start 移到 pCur(原第一个子)之前
}
```

`commentRangeEnd` + 引用 run 留在段末不动 —— `addNew` 的自然位置（段末）就是它们的正确语义位置。**只有 `commentRangeStart` 错位需 move**。凡是要往「已有内容的容器」里按 schema 顺序插元素的 POI/XmlBeans 场景，都要假设 `addNew` 不排序，自己用 XmlCursor 定位。

### 边界：空段

空段（CTP 无任何子元素）时 `addNew` 自然把 `commentRangeStart` 放在首位，无需 move（`toFirstChild` 返 false 跳过）。空段批注是合法的。

### initials 设空串

`XWPFComment.setInitials` 接受任意字符串，POI/Word 不约束 initials 与 author 的关系。nondocx 创作时设空串 —— 派生规则（取首字母等）是产品偏好，无 OOXML 约束，空 initials 不影响 Word 显示。

---

## 3. 回复：`Comments.reply`

```java
Comment root = doc.paragraph(0).addComment("审阅者甲", "这段需要补充背景");
Comment reply = doc.comments().reply(root.id(), "审阅者乙", "已补充,见第二段");

System.out.println(reply.parentId().get());   // root.id() —— 回复了根批注
```

**回复 = 普通批注 + 线程关系**。创作四步：

1. comments.xml 加回复条目（同普通批注），给其内首段补 `w14:paraId`
2. 正文锚点：在父批注 `commentRangeStart` 后插新 `commentRangeStart`；在父批注引用 run 后插新 `commentRangeEnd` + 引用 run（回复范围紧贴父范围、几乎重合）
3. 检查父批注 paraId：无则补（否则 paraIdParent 链断）
4. 四 part 追加线程关系 + durableId/dateUtc

### 线程关系靠 paraId（不是 comment id）

回复的线程关系写在 `commentsExtended.xml`，但 `paraIdParent` 指向父批注的 **paraId**，不是父批注的 `w:id`（[01 §3](./01-concepts.md)）：

```xml
<!-- commentsExtended.xml -->
<w15:commentEx w15:paraId="1B2C3D4E" w15:paraIdParent="0A1B2C3D" w15:done="0"/>
                   <!-- 回复 paraId -->          <!-- 父批注 paraId -->
```

读侧要得「回复的 parentId（父 comment id）」需两步 join（[02 §4](./02-read.md)）。创作侧 `replyToComment` 负责：给回复批注补 paraId、补父批注 paraId（若无）、在 commentsExtended 写 paraIdParent。

### 四 part 自维护（POI 零支持）

回复涉及的三个 part（commentsExtended / commentsIds / commentsExtensible）POI **无 Java 类、无 API**。nondocx 用 OPC 层自维护（spec [poi-bridge.md N23](../../.trellis/spec/backend/poi-bridge.md)）：

- `createPart` 自动注册 [Content_Types].xml 的 Override
- `addRelationship` 手动加 document.xml → part 的关系
- DOM 读-改-写（part 内容小，DOM 处理命名空间/转义比字符串拼接稳）

**幂等坑**：`MemoryPackagePart.getOutputStream()` 是**累加**而非覆盖语义 —— 多次写入会让 part 拼出多份 XML 文档（非法 XML）。nondocx 写前先 `clear()`（spec N23）。

---

## 4. 创作与基础设施自动注入

创作出的批注，nondocx 会**自动**注入三项现代兼容元数据（[04 详讲](./04-infrastructure.md)）：

- **people.xml** —— author 注册为 `<w15:person>`（Word @mention 提示）
- **w14:paraId** —— 批注内段落的身份标记（线程 key）
- **RSID** —— 节点级 `w:rsidR`/`w:rsidRDefault` + settings.xml 的 `<w:rsids>`（Word 合并修订）

你**不用调任何额外方法** —— `addComment`/`reply` 返回时这些已注入。这是「公共 API 无感」的设计目标。

```java
// 这一行背后,people.xml / paraId / RSID 都已自动注入
Comment c = doc.paragraph(0).addComment("审阅者甲", "批注");
```

---

## 5. 参数校验

```java
doc.paragraph(0).addComment(null, "x");     // IllegalArgumentException（author 不能 null）
doc.paragraph(0).addComment("   ", "x");    // IllegalArgumentException（author 不能空白）
doc.paragraph(0).addComment("甲", null);    // IllegalArgumentException（text 不能 null）

doc.comments().reply(null, "乙", "回复");   // IllegalArgumentException
doc.comments().reply("999", "乙", "回复");  // NoSuchElementException（父批注不存在）
```

author 必传（与 tracked-changes 的创作 API 对称）；date / w:id / paraId / durableId 自动分配；text 允许空串（写出空正文批注）。

---

## 6. round-trip 验证

创作后 save → reopen，批注与线程都持久化：

```java
Path file = Path.of("out.docx");
try (Document doc = Docx.open(Path.of("draft.docx"))) {
    Comment root = doc.paragraph(0).addComment("甲", "根批注");
    doc.comments().reply(root.id(), "乙", "回复");
    doc.save(file);
}
try (Document doc = Docx.open(file)) {
    List<Comment> list = doc.comments().list();
    assertThat(list).hasSize(2);
    // 回复批注的 parentId 指向根批注 id
    Comment reply = list.stream()
        .filter(c -> c.parentId().isPresent()).findFirst().orElseThrow();
    assertThat(reply.parentId().get()).isEqualTo(root.id());
}
```

---

## 7. 不返回 this

`addComment` 返回新建的 `Comment`（不返回 `this` 段落）。与 `addDeletion`（返 `this`，因 deletion 迁移了 run 结构需返段落句柄继续编辑）不同 —— 批注创作不改变段落的「继续编辑」语义，返回批注对象本身价值更高。

---

## 下一步

核心能力讲完了。接下来看那些「自动注入」的基础设施到底是怎么回事 → [04 · 现代兼容基础设施](./04-infrastructure.md)。
