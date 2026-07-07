# Research — comments 四 part 自维护探针验证

> 本文件记录 `06-22-comments-reply-threads` planning 阶段对 POI 5.2.5 OPC 自定义 part 生命周期的探针验证。design.md §3 / §4 引用本文结论。
>
> 探针日期：2026-07-07。POI 版本：5.2.5。

## 1. 探针目的

回答 prd 的 **Q1**（四 part 模板从哪来）+ R3.1–R3.4（四 part 自维护可行性）。POI 5.2.5 对 `commentsExtended.xml` / `commentsIds.xml` / `commentsExtensible.xml` **无 Java 类、无高级 API**（父任务 prd Confirmed Facts 已确认）。nondocx 必须自维护——核心未知是：**POI 的 OPC 层能否创建/注册/读写自定义 part？** 这是整个子任务技术可行性的命门。

## 2. 探针一：OPC 自定义 part 完整生命周期

端到端验证：创建 part → 写内容 → 加 relationship → save → unzip 检查 → reopen 读回。

### 2.1 关键 API（全部可达）

```java
OPCPackage pkg = document.getPackage();
PackagePartName name = PackagingURIHelper.createPartName("/word/commentsExtended.xml");
String contentType = "application/vnd.openxmlformats-officedocument.wordprocessingml.commentsExtended+xml";

// 1. 创建 part（[Content_Types].xml 的 Override 由 POI 自动注册!）
PackagePart part = pkg.createPart(name, contentType);

// 2. 写内容
try (OutputStream os = part.getOutputStream()) { os.write(xml.getBytes(UTF_8)); }

// 3. 加 relationship（document.xml part -> commentsExtended part）
document.getPackagePart().addRelationship(name, TargetMode.INTERNAL, relType);
```

### 2.2 验证结果（全绿）

| 验证点 | 结果 |
|---|---|
| `pkg.createPart(name, ct)` 创建 part | ✅ 成功 |
| `[Content_Types].xml` 自动注册 Override | ✅ **POI createPart 自动处理，无需手写** |
| `document.getPackagePart().addRelationship(...)` | ✅ |
| save 后 unzip：part 文件存在 | ✅ `word/commentsExtended.xml` |
| save 后 unzip：`[Content_Types].xml` 含 Override | ✅ |
| save 后 unzip：`word/_rels/document.xml.rels` 含关系 | ✅ |
| reopen 后 `pkg.getPart(name)` 读回 | ✅ |
| reopen 后读回内容正确（paraId 在） | ✅ |

### 2.3 幂等性（关键坑）

**重复 `createPart(同名)` 抛 `PartAlreadyExistsException`**：

```
org.apache.poi.openxml4j.exceptions.PartAlreadyExistsException:
A part with the name '/word/commentsExtended.xml' already exists
```

→ nondocx 自维护时必须**先 `getPart(name)` 检查是否存在**，存在则追加内容、不存在才 createPart。不能盲调 createPart。对应 prd R3.4 幂等要求。

## 3. 探针二：四 part 的真实 OOXML 结构

docx skill（`document-skills/docx`）的 `document.py` 已实现完整回复机制，其 `_add_to_*_xml` 方法揭示了每个 part 的真实条目结构（验证了 prd §Confirmed Facts）：

### 3.1 comments.xml — 批注正文（POI 支持，复用 authoring 路径）

回复批注在 comments.xml 里与普通批注**同构**——就是一个新的 `<w:comment w:id="1">`，POI 的 `createComment(id)` 直接支持。**无特殊结构。**

```xml
<w:comment w:id="1" w:author="回复者" w:date="..." w:initials="">
  <w:p w14:paraId="22222222" w14:textId="77777777">
    <w:r>...<w:t>回复正文</w:t></w:r>
  </w:p>
</w:comment>
```

**注意**：`w14:paraId` 在 `<w:p>`（批注内的段落）上，不在 `<w:comment>` 上。这是线程关系链的 key。

### 3.2 commentsExtended.xml — 线程关系（核心，POI 无 API）

```xml
<w15:commentsEx xmlns:w15="...">
  <w15:commentEx w15:paraId="11111111" w15:done="0"/>                        <!-- 父(根) -->
  <w15:commentEx w15:paraId="22222222" w15:paraIdParent="11111111" w15:done="0"/>  <!-- 子(回复) -->
</w15:commentsEx>
```

- **`w15:paraId`** = 本批注的 paraId（与 comments.xml 里批注内段落的 `w14:paraId` 一致）。
- **`w15:paraIdParent`** = 父批注的 paraId（缺失 = 根批注；存在 = 回复）。
- `w15:done` = resolve 状态（0=未解决，本子任务顺带写 0，不做专门 API）。

**这是线程关系的唯一真源。** parentId 的解析：commentsExtended 的 paraId ↔ comments.xml 的 paraId 配对。

### 3.3 commentsIds.xml — durableId 映射（POI 无 API）

```xml
<w16cid:commentsIds xmlns:w16cid="...">
  <w16cid:commentId w16cid:paraId="11111111" w16cid:durableId="1A2B3C4D"/>
</w16cid:commentsIds>
```

paraId ↔ durableId 映射。durableId 是跨会话稳定标识（用于协作场景）。**与线程结构无关。**

### 3.4 commentsExtensible.xml — w16cex 扩展（POI 无 API）

```xml
<w16cex:commentsExtensible xmlns:w16cex="...">
  <w16cex:commentExtensible w16cex:durableId="1A2B3C4D"/>
</w16cex:commentsExtensible>
```

只挂 durableId，承载 dateUtc 等扩展属性。**与线程结构无关。**

## 4. 关键判断：四 part 的必要性分级

从 OOXML 规范 + docx skill 实现 + 第一性原理分析：

| Part | 线程关系必需？ | 协作元数据？ | nondocx 处理 |
|---|---|---|---|
| comments.xml | ✅ 必需（批注正文） | — | 复用 authoring 路径 |
| **commentsExtended.xml** | ✅ **必需**（paraIdParent 是唯一线程链） | done 状态 | **本子任务核心** |
| commentsIds.xml | ❌ 非必需（线程不依赖） | durableId | **scope 决策点**（见下） |
| commentsExtensible.xml | ❌ 非必需（线程不依赖） | dateUtc 等 | **scope 决策点**（见下） |

**第一性原理结论**：线程关系**只依赖 commentsExtended.xml**。commentsIds/Extensible 是协作元数据（durableId/dateUtc），与"父-子"关系无关。

但 Word 的实现是否严格要求四 part 齐全才显示线程，是经验问题——探针二生成了 `/tmp/min-part-probe.docx`（只写 commentsExtended），需人工验收。

## 5. paraId 生成（Q4 最小必要集）

docx skill 的 `_generate_hex_id`：

```python
def _generate_hex_id() -> str:
    return f"{random.randint(1, 0x7FFFFFFE):08X}"  # 8 位十六进制，< 0x7FFFFFFF
```

OOXML 约束：`w14:paraId` 和 `durableId` 都必须 `< 0x7FFFFFFF`（符号位保护）。nondocx 用 `ThreadLocalRandom` 或 `SecureRandom` 生成 8 位 hex（大写），范围 `[1, 0x7FFFFFFE]`。

**Q4 最小必要集**：本子任务**必须生成 paraId**（线程关系靠它链），**可选生成 durableId**（仅当写 commentsIds/Extensible 时需要）。dateUtc 等留给子任务 4。

## 6. 对实现的影响（design.md §3/§4 引用）

1. **part 创建**：`OPCPackage.createPart` + `getOutputStream` 写内容 + `addRelationship`。Content_Types 自动注册。
2. **幂等**：先 `getPart(name)` 检查，存在追加、不存在 create（`PartAlreadyExistsException` 防御）。
3. **XML 写入**：四 part 无 Java 类，用 DOM 读-改-写（part 文件小，DOM 处理命名空间/转义比字符串拼接稳）。
4. **paraId**：8 位 hex 随机，`< 0x7FFFFFFF`。
5. **comments.xml 的 paraId**：POI 的 `XWPFComment.createParagraph()` 建的段落默认**无** `w14:paraId`，nondocx 要用 XmlCursor/CT 给批注内首段补 `w14:paraId`（这是 paraIdParent 链的 key）。

## 7. 实现期发现的关键坑：MemoryPackagePart.getOutputStream() 累加语义

**问题**：多次对一个 part `getOutputStream()` 写入，内容**累加**而非覆盖——实测三次 writeDom 后 part 里有**三段独立的 XML 文档**拼接（`<?xml?><w15:commentsEx>...</w15:commentsEx><?xml?><w15:commentsEx>...</w15:commentsEx>...`），是非法 XML，导致 readDom 解析失败（`[Fatal Error] 不允许有匹配 "[xX][mM][lL]" 的处理指令目标`）。

**根因**：POI 的 `MemoryPackagePart.getOutputStreamImpl()` 返回的流，关闭时是「把本次写入的字节**追加**到 part 现有 buffer」而非「替换」。这是与直觉相悖的语义——`getOutputStream` 名字暗示覆盖，实际累加。

**修复**：writeDom 前先 `((MemoryPackagePart) part).clear()` 清空 buffer，再 getOutputStream 写完整内容：
```java
if (part instanceof MemoryPackagePart) {
  ((MemoryPackagePart) part).clear();
}
try (OutputStream os = part.getOutputStream()) { ... 写完整 DOM ... }
```

**通用教训**：凡是对 POI OPC part 多次写入（读-改-写循环），都要先 clear 再写，否则内容累加致畸形。`MemoryPackagePart.clear()` 是 public 方法（基类 `PackagePart` 无，需 instanceof 判断——POI 默认运行时实现都是 MemoryPackagePart）。

**额外坑：DOM Transformer 的 standalone**：默认 Transformer 输出 `standalone="no"`，与 OOXML 惯例 `standalone="yes"` 不一致。Word 对此宽容（实测能解析），但若严格性要求，writeDom 需显式 `setOutputProperty(OutputKeys.STANDALONE, "yes")`（注意 Transformer 设了 yes 后，若 DOM document 本身的 standalone 属性为 false 仍会输出 no——需同时 `doc.setXmlStandalone(true)`）。
