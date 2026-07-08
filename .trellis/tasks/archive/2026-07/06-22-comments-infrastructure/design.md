# Design — comments 基础设施（people.xml / paraId / RSID）

> 配套 `prd.md`。本文记录三项基础设施自动注入的技术设计：people.xml（author 注册）、w14:paraId（补到 addComment 路径）、RSID（Document 级单例）。
>
> 探针依据见 docx skill `document.py`（people/RSID 实现）+ reply-threads 子任务的 N23（OPC part 自维护模式）。

## 1. 设计目标

给 nondocx 产出的批注补齐**现代 Word 兼容元数据**，让 Word 审阅面板显示完整作者信息、@mention 提示、合并修订对齐。这是「锦上添花」——缺了批注仍能用（子任务 1-3 已保证基本可用）。

需要定清的关键点：

1. 三项基础设施的注入点（在哪条创作路径、哪个时机）
2. people.xml 自维护（复用 N23 的 OPC 模式）
3. RSID 的 Document 级单例形态
4. paraId 收敛（把 reply-threads 的散落实现统一到 helper，补到 addComment）
5. 注入的幂等与防御式

**与前序子任务的关系**：
- **复用 N23 的 OPC part 自维护模式**（`MemoryPackagePart.clear()` + createPart 自动注册 Content_Types）——people.xml 与 commentsExtended 同型。
- **paraId 收敛**：reply-threads 在 `CommentNodes.setParagraphParaId`（私有）+ reply 路径用了 paraId；本子任务把它提升到 `AuthoringInfra`（public helper），addComment 也调用。

## 2. 三层映射

### 2.1 OOXML 层

```
word/people.xml (w15 命名空间, POI 无 Java 类):
  <w15:people>
    <w15:person w15:author="审阅者甲">
      <w15:presenceInfo w15:providerId="None" w15:userId="审阅者甲"/>
    </w15:person>
  </w15:people>

批注内段落的 w14:paraId (reply-threads 已部分做):
  <w:p w14:paraId="0A1B2C3D">...</w:p>

RSID:
  word/settings.xml:
    <w:settings>
      <w:rsids>
        <w:rsidRoot w:val="07DC5ECB"/>
        <w:rsid w:val="07DC5ECB"/>
      </w:rsids>
    </w:settings>
  节点级(批注创作产出的 <w:p>/<w:r>):
    <w:p w:rsidR="07DC5ECB" w:rsidRDefault="07DC5ECB" ...>
    <w:r w:rsidR="07DC5ECB">
```

### 2.2 POI 层

| 能力 | POI 支持 | nondocx 处理 |
|---|---|---|
| people.xml part | ❌ 无 Java 类 | OPC createPart + DOM（复用 N23 模式） |
| people.xml relationship + Content_Types | ❌ | createPart 自动注册 + addRelationship |
| w14:paraId 属性 | ❌ 无自动注入 | XmlCursor setAttributeText（reply-threads 已有 setParagraphParaId） |
| RSID settings.xml rsids 段 | ⚠️ CTSchema 有但无便捷 API | XmlCursor 操作 CTSettings |
| RSID 节点级 w:rsidR | ❌ | 创作/回复时 setAttribute |

**people.xml 的 OPC 自维护完全复用 N23 模式**：`ensurePart`（getPart 检查 → createPart + addRelationship）+ DOM 读-改-写（clear 后覆盖）。person 条目结构简单，且需 author 去重（先扫现有 person）。

**RSID 的 settings.xml**：POI 有 `XWPFDocument.getSettings()` → `XWPFSettings.getCTSettings()` → `CTSettings`，`<w:rsids>` 段的 typed 访问器声明存在（`getRsids`/`addNewRsids`/`isSetRsids`，返回 `CTDocRsids`）。**但探针确认 `CTDocRsids.class` 在 lite jar 里缺失**（javap 找不到类文件，只有 `.xsb` binary schema）——这是 N16 的「dangling reference」现象：CTSettings 声明返回 CTDocRsids，运行时 `getRsids()` 会 `ClassNotFoundException`。故 **RSID 实现用 XmlCursor 操作 settings.xml 原始 XML**（typed API 不可用）。算法：XmlCursor 定位 `<w:settings>` 的 `<w:rsids>` 子，存在读 rsidRoot、不存在则创建。

### 2.3 nondocx 层

- 新建 `internal/poi/AuthoringInfra`：三项基础设施的统一入口（people.xml 维护 + paraId 生成/设值 + RSID 生成/设值）。
- 注入点：`CommentNodes.addWholeParagraphComment`（addComment）+ `CommentNodes.replyToComment`（reply）在产出节点后调用 `AuthoringInfra`。
- `Document` 持 RSID 单例（构造时生成，`AuthoringInfra` 读取）。
- 公共 API 无感——用户调 `addComment`/`reply` 不变，基础设施自动注入。

## 3. AuthoringInfra 设计（新内部类）

`internal/poi/AuthoringInfra`，三个职责：

### 3.1 paraId 生成与设值

```java
// 生成 8 位 hex paraId (< 0x7FFFFFFF)。复用 CommentExtendedParts.randomHexId（已存在）。
public static String newParaId();

// 给段落设 w14:paraId。从 reply-threads 的 CommentNodes.setParagraphParaId 提升为 public。
public static void setParaId(XWPFParagraph p, String paraId);
```

### 3.2 people.xml 维护

```java
// 把 author 注册到 people.xml(幂等:已存在不重复加)。复用 N23 的 OPC 模式。
public static void registerAuthor(XWPFDocument doc, String author);
```

算法：
1. `ensurePart`（people.xml: `/word/people.xml` + `...people+xml` + relationship）。
2. DOM 读-改-写：扫现有 `<w15:person w15:author=..>`，author 精确匹配则跳过（幂等）。
3. 不存在则追加 `<w15:person w15:author="..."><w15:presenceInfo w15:providerId="None" w15:userId="..."/></w15:person>`。
4. XML 转义 author（防注入，对照 docx skill `html.escape`）。

### 3.3 RSID 生成与设值

```java
// Document 构造时调一次,生成 8 位 hex RSID。
public static String newRsid();

// 把 RSID 注册到 settings.xml 的 <w:rsids> 段(幂等)。
public static void registerRsid(XWPFDocument doc, String rsid);

// 给创作产出的段落标 RSID(rsidR + rsidRDefault)。
public static void stampRsid(XWPFParagraph p, String rsid);
// 给创作产出的 run 标 RSID(rsidR)。
public static void stampRsid(CTR r, String rsid);
```

**settings.xml rsids 注册算法**（对照 docx skill `_setup_rsid`）：
1. 取 `doc.getSettings().getCTSettings()`（或 XmlCursor 定位 settings）。
2. 检查 `<w:rsids>` 段是否存在；不存在则创建（schema 顺序：rsids 在 compat 之后，但 Word 宽容，追加到末尾也可）。
3. 检查 `<w:rsid w:val="本RSID"/>` 是否存在；不存在则追加。
4. 防御式：settings.xml 缺失/操作失败不阻断主流程（prd R5）。

## 4. 注入点改造

### 4.1 addComment 路径（补 paraId + RSID + people）

`CommentNodes.addWholeParagraphComment` 当前产出：批注内段落（无 paraId）+ 正文锚点（commentRangeStart/End + 引用 run）。

改造（在 return 前）：
```
// paraId: 给批注内首段补(reply-threads 的 reply 路径已有,addComment 路径补齐)
String paraId = AuthoringInfra.newParaId();
AuthoringInfra.setParaId(comment.getParagraphs().get(0), paraId);

// RSID: 给创作产出的段落 + run 标 RSID(Document 级单例,见 §5)
String rsid = RsidHolder.of(document);  // 见 §5
AuthoringInfra.stampRsid(comment.getParagraphs().get(0), rsid);
// 正文锚点的引用 run 也标
AuthoringInfra.stampRsid(refRun, rsid);

// people.xml: 注册 author
AuthoringInfra.registerAuthor(document, author);
```

**注意**：addComment 补 paraId 后，**既有 CommentsAuthoringTest 的结构断言会变**（批注内段落多了 w14:paraId，CTP 子元素或属性变化）。需更新对应测试断言。

### 4.2 reply 路径（补 RSID + people；paraId 已有）

`CommentNodes.replyToComment` 当前已补 paraId（reply + 父批注）。改造：
```
// paraId 已有(reply-threads)——但要把 setParagraphParaId 调用改为 AuthoringInfra.setParaId(收敛)
// RSID: 给回复批注的段落 + run 标 RSID
String rsid = RsidHolder.of(document);
AuthoringInfra.stampRsid(reply.getParagraphs().get(0), rsid);
// 正文锚点(commentRangeStart/End 不是 w:p/w:r,不标 RSID;引用 run 标)
// people.xml: 注册 author
AuthoringInfra.registerAuthor(document, author);
```

## 5. RSID 的 Document 级单例（RsidHolder）

`Document` 构造时生成一个 RSID，整个文档生命周期复用。问题：RSID 要在 `internal/poi/` 的注入点读到，但 `Document` 是 API 层。

方案：**`AuthoringInfra.documentRsid(XWPFDocument)`** —— 从 POI 文档的 settings.xml 读「已注册的 rsidRoot」作为该文档的 RSID。首次调用时（settings 无 rsids）生成并注册，后续调用读回。这样：
- 不需 `Document` 持字段（避免 API 层状态）。
- RSID 持久化在 settings.xml，save→reopen 后仍是同一个 RSID（真正的「文档级」）。
- `addComment`/`reply` 调 `AuthoringInfra.documentRsid(doc)` 拿当前文档 RSID。

```
documentRsid(doc):
  CTSettings s = doc.getSettings()...;
  if (rsids 段存在 && rsidRoot 有值): return rsidRoot.val;
  else:
    rsid = newRsid();
    registerRsid(doc, rsid);  // 建 rsids 段 + rsidRoot + rsid
    return rsid;
```

## 6. 幂等与防御式（prd R5）

- **people.xml 幂等**：registerAuthor 扫现有 person，author 精确匹配则跳过（AC5）。
- **RSID settings.xml 幂等**：registerRsid 检查 rsidRoot/rsid 是否已存在。
- **paraId 不幂等**（每次 newParaId 都是新随机值）——但创作路径每次产出新节点，天然不冲突。
- **防御式降级**：settings.xml 缺失/people.xml DOM 解析失败时，注入跳过不阻断主创作流程（批注正文仍完整写出）。

## 7. 与 tracked-changes 的隔离（AC6 / 父任务 Q4）

**不回溯**补基础设施到 tracked-changes 创作路径。`AuthoringInfra` 虽设计为可复用，但本子任务只在 comments 创作路径（addComment + reply）调用。tracked-changes 的 `TrackedChangeNodes.addInsertion` 等不调 `AuthoringInfra`——避免改动已稳定的 track 包（父任务 Q4 约束）。

## 8. 风险观察点

- **settings.xml rsids 的 typed 访问器**：精简 schema 下 `CTSettings.getRsids()` 是否存在需实现期确认；缺失则 XmlCursor 拼（与 N16 同型）。
- **CommentsAuthoringTest 结构断言**：addComment 补 paraId/RSID 后，既有测试的 CTP 子元素断言可能变化（paraId 是属性非子元素，但 RSID 也是属性——属性变化不断言，子元素顺序不变）。需验证。
- **people.xml 的 xmlns:w15 声明**：ensurePart 写空根时带 `xmlns:w15`，DOM 追加 person 时命名空间已生效。
- **AC4 Word 显示**：people.xml 让 Word 显示 author 信息（而非「未知」），但 presenceInfo 用占位 providerId="None"，无真实身份服务——@mention 提示可能不完整，这是 prd Out of Scope。

## 9. Out of Scope（对照 prd）

- 回溯补到 tracked-changes 创作路径（AC6）。
- presenceInfo 真实 providerId/userId（身份服务集成）。
- w16cex/w16cid 命名空间（reply-threads 已涉及）。
- dateUtc（reply-threads 已做）。

## 10. 待收敛问题

无。Q1–Q4 已收敛（prd）。design 已可支撑 implement.md。
