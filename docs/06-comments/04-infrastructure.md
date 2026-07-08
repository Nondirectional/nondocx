# 04 · 现代兼容基础设施

前三篇讲了批注的核心能力（读、创作、回复）。这一篇讲三项「锦上添花」的**现代 Word 兼容元数据** —— 它们让 nondocx 产出的批注在 Word 审阅面板里显示完整作者信息、@mention 提示，以及让多份文档在 Word「合并修订」时正确对齐。

> **缺了这些批注仍能用**（前三篇的能力已保证基本可用），但 Word 体验打折：审阅面板可能显示「未知作者」、@mention 不提示、合并修订对不齐。这是「现代兼容」层。

这三项 POI 全部不提供，nondocx 的 `internal/poi/AuthoringInfra` 在创作路径（`addComment`/`reply`）**自动注入**它们，公共 API 无感。

---

## 1. people.xml：作者注册（@mention 提示）

### 它解决什么

Word 审阅面板显示批注作者时，会查 `word/people.xml` 找作者的身份信息（头像占位、@mention 提示）。没有这个 part，Word 显示「未知作者」，@mention 功能不工作。

### OOXML 结构

```xml
<!-- word/people.xml（w15 命名空间） -->
<w15:people xmlns:w15="http://schemas.microsoft.com/office/word/2012/wordml">
  <w15:person w15:author="审阅者甲">
    <w15:presenceInfo w15:providerId="None" w15:userId="审阅者甲"/>
  </w15:person>
</w15:people>
```

### POI 的缺口 → nondocx 的处理

POI 对 people.xml **无 Java 类、无 API**。nondocx 复用 [03](./03-authoring.md) 讲过的「自维护 OOXML part」模式（spec [poi-bridge.md N24](../../.trellis/spec/backend/poi-bridge.md)）：

- `createPart` 自动注册 [Content_Types].xml Override（`people+xml`）+ `addRelationship` 手动加关系
- DOM 读-改-写：扫现有 `<w15:person w15:author=..>`，author 精确匹配跳过（幂等），不存在则追加

**幂等**：同一 author 连续注册（多次 addComment 同作者），people.xml 只一条 person 条目。author 去重用**精确字符串匹配**（不 normalize —— author 是用户显式传入的标识，normalize 会改变语义）。

**`presenceInfo` 用占位 `providerId="None"`** —— 这是 docx skill 同款做法。真实身份服务集成（真实 providerId/userId）是 Out of Scope，本层只保证 Word 能识别作者、显示 @mention 提示。

---

## 2. w14:paraId：段落身份标记（线程 key）

### 它解决什么

paraId 是段落（`<w:p>`）的身份标记。线程关系（`commentsExtended.xml` 的 `paraIdParent`）靠它间接关联 —— 没有 paraId，回复批注无法指向父批注（[01 §3](./01-concepts.md)）。

### OOXML 结构

```xml
<!-- 批注内首段的 w14:paraId（w14 命名空间） -->
<w:p w14:paraId="0A1B2C3D">...</w:p>
```

`w14` 命名空间：`http://schemas.microsoft.com/office/word/2010/wordml`。

### POI 的缺口 → nondocx 的处理

POI 的 `createParagraph` **不写** paraId。nondocx 的 `AuthoringInfra.setParaId` 用 `XmlCursor.setAttributeText` 给段落设 `{w14}paraId` 属性。

**生成规则**：8 位大写 hex，范围 `[1, 0x7FFFFFFE]`（OOXML 约束必须 `< 0x7FFFFFFF`）。**不查重** —— 8 位 hex 空间 ~2³¹，单文档批注数远低于此，冲突概率可忽略；查重需全扫文档所有 paraId，成本不值。

**收敛**：reply-threads 子任务最初在 `CommentNodes` 里有一个私有 `setParagraphParaId`；infrastructure 子任务把它提升到 `AuthoringInfra.setParaId`（public），addComment 路径也调用，统一入口。

---

## 3. RSID：修订会话标识（合并修订对齐）

### 它解决什么

RSID（Revision Save ID）标记「同一次编辑会话」产出的节点。Word 的「合并文档」/「合并修订」功能用它对齐：同一会话的变更归为一组。多份 nondocx 产出的文档在 Word 合并时，RSID 让变更正确分组。

### OOXML 结构

RSID 写两处：

```xml
<!-- ① word/settings.xml —— rsids 段（文档级） -->
<w:settings>
  <w:rsids>
    <w:rsidRoot w:val="07DC5ECB"/>      <!-- 文档的 RSID 根 -->
    <w:rsid w:val="07DC5ECB"/>          <!-- 同值 -->
  </w:rsids>
</w:settings>

<!-- ② 节点级 —— 创作产出的 <w:p>/<w:r> 标 rsidR -->
<w:p w:rsidR="07DC5ECB" w:rsidRDefault="07DC5ECB">...</w:p>
<w:r w:rsidR="07DC5ECB">...</w:r>
```

### POI 的缺口 → nondocx 的处理

POI 的 `CTSettings.getRsids()`/`addNewRsids()` 声明返回 `CTDocRsids`，但 lite jar 缺该 class 文件 —— 这是 **dangling reference**（与 tracked-changes 的 cellMerge 同型，spec [poi-bridge.md N16](../../.trellis/spec/backend/poi-bridge.md)）：typed 访问器运行期抛 `ClassNotFoundException`。故 nondocx 用 `XmlCursor` 操作 `CTSettings` 原始 XML。

**Document 级单例**（关键设计）：RSID 持久化在 settings.xml 的 `<w:rsidRoot>` —— `AuthoringInfra.documentRsid(doc)` 首次调用时生成并注册，后续调用读回。故：

- **save → reopen 后仍是同一个 RSID**（真正的「文档级」，持久化在 settings.xml）
- 同一文档多次创作（addComment/reply）的节点标**同一个** RSID（Word「同一编辑会话」语义）
- 不同文档（不同 settings.xml）概率上不同

这避免了 `Document` API 层持 RSID 字段 —— RSID 状态留在 settings.xml，不在 API 层引入状态。

### XmlCursor 的 beginElement 坑（实现期发现）

往 settings.xml 建 `<w:rsids>` 嵌套结构（含 rsidRoot + rsid）时，`XmlCursor.beginElement(QName)` 的语义是「在当前位置之前插入新元素，**cursor 移到新元素的 END**」（非直觉的 START）。

因此建嵌套子的正确导航是：

```
cursor 在容器 END → beginElement(child) 插子(cursor 在 child END) → 设属性
→ toNextToken 从 child END 移到容器 END → beginElement(nextChild) 插第二个子
```

**误用 `toParent` 会插错层级**：`toParent` 从元素 END 回到**自身** START（而非父元素），导致子嵌进子。凡是要用 XmlCursor 建嵌套结构的场景，都要记住 `beginElement` 后 cursor 在 END（spec N24）。

---

## 4. 注入是自动的、防御式的

### 自动

三项注入集中在 `CommentNodes.stampAuthoringInfrastructure`（创作路径的私有 helper），`addComment`/`reply` 返回前调用。公共 API 无感：

```java
// 这一行背后,people.xml / paraId / RSID 都已自动注入
Comment c = doc.paragraph(0).addComment("审阅者甲", "批注");
```

### 防御式

注入失败（settings.xml 缺失、people.xml DOM 解析失败等）**不阻断主创作流程** —— 批注正文仍完整写出，只是缺失某项基础设施（spec N24，prd R5）。

### 幂等

| 基础设施 | 幂等口径 |
|---|---|
| people.xml | author 精确匹配去重 |
| RSID settings.xml | 检查 rsidRoot 已存在 |
| paraId | 每次新随机（创作路径每次产出新节点，天然不冲突） |

---

## 5. 与 tracked-changes 的隔离

`AuthoringInfra` 虽设计为可复用，但**仅被 comments 创作路径**（`addComment` + `reply`）调用。tracked-changes 的 `TrackedChangeNodes` **不**接入 —— 避免改动已稳定的 track 包（父任务 Q4 约束）。

这是有意的范围控制：批注是「现代协作」特性，需要完整兼容元数据；tracked-changes 是「修订追踪」特性，其 RSID/paraId 由 Word 产生时自带，nondocx 创作侧不回溯补。若未来 tracked-changes 也需要这些基础设施，再单独开子任务接入。

---

## 6. 不参与 equals

people.xml / paraId / RSID 都**不纳入** `Comment.equals`/`Document.equals`。理由：

- 它们是「兼容元数据」，非「内容」
- paraId 是随机生成的，两份等价文档的 paraId 必然不同
- RSID 是会话标识，比较它无意义

比较批注内容用 `Comment.equals`（id/author/text/date/initials 五字段），比较文档内容用 `Document.equals`（不含批注）。

---

## 收尾

四篇讲完了。你现在掌握了 nondocx 批注特性的全部公开能力：

- [01 概念](./01-concepts.md) —— OOXML 批注模型、四 part 总览
- [02 读](./02-read.md) —— `Comments` 门面、`list()`/`get(id)`、`Comment` 字段
- [03 创作](./03-authoring.md) —— `Paragraph.addComment`、`Comments.reply`
- [04 基础设施](./04-infrastructure.md) —— people.xml / paraId / RSID（本篇）

可运行示例见 [`CommentsExample.java`](../../nondocx-examples/src/main/java/com/non/docx/examples/CommentsExample.java)。API 速查见 [03-api-reference.md](../03-api-reference.md)。
