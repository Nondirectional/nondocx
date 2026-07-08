# 01 · OOXML 批注模型

批注（comments）在 OOXML 里**不是一个元素**。它是一套「正文 + 锚点 + 元数据」**分离在多个 part** 的结构：

1. **批注正文** —— 在 `word/comments.xml`，每条一个 `<w:comment>`
2. **正文锚点** —— 散在 `word/document.xml`，用 `commentRangeStart`/`commentRangeEnd`/`commentReference` 标出被评论的范围
3. **线程关系**（可选）—— 在 `word/commentsExtended.xml`，靠 `paraId` 间接关联
4. **协作元数据**（可选）—— people.xml / commentsIds.xml / commentsExtensible.xml

这一篇把这套结构讲清楚，并指出 POI 在每个 part 上的坑。**这是批注教程最理论的一篇**，后面三篇都建立在这些概念上。

> 三层递进：先看 OOXML 长什么样 → 看 POI 怎么（不）表达 → 看 nondocx 怎么收容。

---

## 1. 核心结构：正文 + 锚点分离

批注最基础的形式是「正文 + 锚点」两 part 分离：

```xml
<!-- word/document.xml（正文，被评论的内容） -->
<w:p>
  <w:commentRangeStart w:id="0"/>              <!-- 批注范围起点 -->
  <w:r><w:t>被评论的正文</w:t></w:r>
  <w:commentRangeEnd w:id="0"/>                <!-- 批注范围终点 -->
  <w:r><w:commentReference w:id="0"/></w:r>    <!-- 引用（在独立 run 里） -->
</w:p>

<!-- word/comments.xml（批注正文，独立 part） -->
<w:comments xmlns:w="...">
  <w:comment w:id="0" w:author="审阅者甲" w:date="2026-07-08T10:00:00Z" w:initials="审">
    <w:p><w:r><w:t>这是批注内容</w:t></w:r></w:p>
  </w:comment>
</w:comments>
```

**三个 OOXML 关键点**：

1. **正文与锚点用同一个 `w:id` 配对**。`comments.xml` 里 `<w:comment w:id="0">` 与 `document.xml` 里三个锚点（`commentRangeStart`/`End`/`Reference`）的 `w:id="0"` 是同一个值。
2. **`comments.xml` 里 `<w:comment>` 的顺序是创建顺序，不等于正文顺序**。正文里 `commentRangeStart` 的出现顺序才是阅读顺序。这两个顺序可能不一致（先创建了 id=2 的批注，再创建 id=0）。
3. **锚点是叶子元素，可出现在任意层级**——段落内、表格单元格内的段落里、嵌套结构里。遍历找锚点必须深度优先走完整棵 body 树。

### POI 的坑：`getComments()` 返回部件顺序，不是正文顺序

POI 有完整的 comments.xml 高级 API：`XWPFDocument.getDocComments()` → `XWPFComments` → `XWPFComment`（实现 `IBody`，能 `getParagraphs()`/`createParagraph()`）。但有两个坑：

```java
// 坑 1：无任何批注时 getDocComments() 返回 null（POI 不自动创建空 part）
XWPFComments cs = doc.getDocComments();  // null when no comments

// 坑 2：getComments() 返回 comments.xml 的部件顺序（创建顺序），不是正文顺序
List<XWPFComment> list = cs.getComments();  // [id=2, id=0, id=1] —— 按 comments.xml 元素顺序
```

nondocx 的 `Comments.list()` 契约是**正文顺序**（先被评论的正文 → 先返回的批注），所以 `internal/poi/CommentNodes.collect` 自己用 `XmlCursor` 扫 `document.xml` 的 `commentRangeStart` 重排，**不直接委托** `getComments()`。

---

## 2. 四个 part 总览

基础形式只有 comments.xml + document.xml 两个 part。一旦涉及**回复线程**或**现代协作元数据**，就会有更多 part：

| part | 命名空间 | 作用 | POI 支持 |
|---|---|---|---|
| `comments.xml` | `w` | 批注正文（`<w:comment>`），author/date/initials + 段落 | ✅ 完整 API |
| `document.xml` | `w` | 正文锚点（rangeStart/End/Reference） | ⚠️ `addNew` 不排序 |
| `commentsExtended.xml` | `w15` | **线程关系**：`<w15:commentEx w15:paraId=.. w15:paraIdParent=../>` | ❌ 无 Java 类 |
| `commentsIds.xml` | `w16cid` | durableId 映射（协作元数据） | ❌ 无 Java 类 |
| `commentsExtensible.xml` | `w16cex` | w16cex 扩展（含 `w16du:dateUtc` 跨时区时间戳） | ❌ 无 Java 类 |
| `people.xml` | `w15` | 作者注册（`<w15:person>`），Word @mention 提示 | ❌ 无 Java 类 |

**后三个 part（commentsExtended / Ids / Extensible）+ people.xml 是 nondocx 「自维护 OOXML part」模式的来源** —— POI 对它们零支持，nondocx 用 OPC 层（`createPart` + `addRelationship` + DOM 读-改-写）从 part 级别白手起家。这是 nondocx 首次建立这种模式（spec [poi-bridge.md N23](../../.trellis/spec/backend/poi-bridge.md)）。

---

## 3. 线程关系：paraId 是中间 key

「批注 B 回复了批注 A」这件事，OOXML 用 `commentsExtended.xml` 表达，但关联**不是**靠批注 id，而是靠 `paraId`（段落身份标记）：

```xml
<!-- word/comments.xml —— 批注内首段带 w14:paraId -->
<w:comment w:id="0" ...>                          <!-- 根批注 A -->
  <w:p w14:paraId="0A1B2C3D">...</w:p>            <!-- A 的 paraId -->
</w:comment>
<w:comment w:id="1" ...>                          <!-- 回复批注 B -->
  <w:p w14:paraId="1B2C3D4E">...</w:p>            <!-- B 的 paraId -->
</w:comment>

<!-- word/commentsExtended.xml —— paraIdParent 指向父批注的 paraId -->
<w15:commentsEx>
  <w15:commentEx w15:paraId="1B2C3D4E" w15:paraIdParent="0A1B2C3D" w15:done="0"/>
                   <!-- B 的 paraId -->          <!-- A 的 paraId（不是 A 的 comment id！） -->
</w15:commentsEx>
```

**关键点**：`paraIdParent` 指向的是父批注的 **paraId**，不是父批注的 `w:id`。要得到「B 的 parentId（父 comment id）」需要**两步 join**：

1. comments.xml：批注内首段的 `w14:paraId` → 批注 `w:id`（建立 paraId↔commentId 映射）
2. commentsExtended：B 的 paraId → `paraIdParent`（得父 paraId）→ 再 join 回父 comment id

nondocx 的 `CommentNodes.ThreadResolver` 在 `collect` 入口建三张映射完成这个 join，产出 `Comment(c, paraId, parentId)`。**防御式**：无 commentsExtended / paraId 缺失 / join 失败时，parentId 为 null（根批注语义），不抛。

---

## 4. 基础设施：people.xml / paraId / RSID（04 详讲）

现代 Word 打开批注文档时，审阅面板会显示作者头像、@mention 提示、「合并修订」能对齐同一编辑会话的变更。这些靠三项「锦上添花」的基础设施元数据：

```xml
<!-- word/people.xml —— 作者注册（@mention 提示） -->
<w15:people>
  <w15:person w15:author="审阅者甲">
    <w15:presenceInfo w15:providerId="None" w15:userId="审阅者甲"/>
  </w15:person>
</w15:people>

<!-- 批注内段落的 w14:paraId（线程关系的 key，上面 §3） -->
<w:p w14:paraId="0A1B2C3D" w:rsidR="07DC5ECB" w:rsidRDefault="07DC5ECD">...</w:p>

<!-- word/settings.xml —— RSID（修订会话标识） -->
<w:settings>
  <w:rsids>
    <w:rsidRoot w:val="07DC5ECB"/>
    <w:rsid w:val="07DC5ECB"/>
  </w:rsids>
</w:settings>
```

这三项 POI 全部不提供（people.xml 无 Java 类、paraId 无自动注入、RSID 的 `CTDocRsids` 是 dangling reference）。nondocx 的 `AuthoringInfra` 在批注创作路径（`addComment` / `reply`）**自动注入**它们，公共 API 无感。

详见 [04 · 现代兼容基础设施](./04-infrastructure.md)。

---

## 5. nondocx 怎么把脏活收起来

这张图总结 nondocx 在 `api/comment/*` 暴露什么、把什么收进 `internal/`：

```
你的代码
  │  零 POI 泄露
  ▼
api/comment/*  (POI-free)
  ├── Comments        门面（list / get / reply）
  └── Comment         单条批注（holding-wrapper：持 XWPFComment；id/author/text/date/initials + paraId/parentId）
  │
  │  转发 / 包装
  ▼
internal/poi/  (脏活收容所)
  ├── CommentNodes          读（collect：body 顺序重排）+ 创作（addWholeParagraphComment）+ 回复（replyToComment）
  ├── CommentExtendedParts  四 part 自维护（commentsExtended/Ids/Extensible）—— OPC + DOM
  └── AuthoringInfra        基础设施（people.xml / paraId / RSID）
```

**所有 `org.apache.poi.*` 与 `org.openxmlformats.*` 类型只出现在 `internal/` 与 `Comment`/`Comments` 的构造函数签名（内部接缝）里**。公开表面干净。

> **与 tracked-changes 的结构类比**：tracked-changes 把脏活收进**一个** `TrackedChangeNodes`（因为四类修订同型）；comments 把脏活分散到**三个**类（`CommentNodes` 管读/创作/回复，`CommentExtendedParts` 管四 part 自维护，`AuthoringInfra` 管基础设施）—— 因为这三块的能力来源完全不同（POI 有 comments API / 无 extended part / 无基础设施）。

---

## 6. 一张速查表：各 part 的能力来源

| 能力 | OOXML 来源 | POI 支持 | nondocx 实现 |
|---|---|---|---|
| 读批注正文/元数据 | comments.xml | ✅ `XWPFComment` | `CommentNodes.collect` + body 顺序重排 |
| 创作范围批注 | document.xml 锚点 + comments.xml | ⚠️ `addNew` 不排序 | `CommentNodes.addWholeParagraphComment`（XmlCursor 定位） |
| 回复/线程 | commentsExtended.xml | ❌ 无 Java 类 | `CommentExtendedParts`（OPC 自维护） |
| durableId/dateUtc | commentsIds/Extensible.xml | ❌ 无 Java 类 | `CommentExtendedParts`（OPC 自维护） |
| people.xml（@mention） | people.xml | ❌ 无 Java 类 | `AuthoringInfra.registerAuthor`（OPC 自维护） |
| w14:paraId | 批注内段落属性 | ❌ 无自动注入 | `AuthoringInfra.setParaId` |
| RSID（合并修订） | settings.xml + 节点属性 | ❌ CTDocRsids dangling | `AuthoringInfra.documentRsid`（XmlCursor） |

---

## 下一步

概念清楚了，去看 nondocx 怎么**读**批注 → [02 · 读与查询](./02-read.md)。
