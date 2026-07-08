# Design — comments 文档+spec

> 配套 `prd.md`。本文收敛两个 Open Question,定稿文档结构与 spec 更新范围。

## 1. OQ 收敛

### Q1 ✅ 四篇结构(用户确认)

批注没有 accept/reject 概念(不是修订),故 tracked-changes 的「03 accept/reject + 04 authoring」不能直接对称。定稿**四篇**,内容重组:

| # | 文件 | 内容 | 对照 tracked |
|---|---|---|---|
| README | `README.md` | 章节索引 + 端到端示例 + 契约关系 | 05/README |
| 01 | `01-concepts.md` | OOXML 批注模型:comments.xml 结构、四 part 总览(extended/ids/extensible)、people.xml/paraId/RSID 基础设施概念 | 05/01 |
| 02 | `02-read.md` | 读与查询:`Comments` 门面、`list()`/`get(id)`、`Comment` 五字段 + paraId/parentId | 05/02 |
| 03 | `03-authoring.md` | 创作:`Paragraph.addComment`(整段范围)+ `Comments.reply`(回复线程) | 05/04 合并 |
| 04 | `04-infrastructure.md` | 基础设施教学展开:people.xml(@mention)、w14:paraId(线程 key)、RSID(文档级单例 + 合并修订) | comments 独有(tracked 无) |

**为什么 04 独立**:people.xml/paraId/RSID 是 comments 独有的「现代 Word 兼容」层,有自己的 OOXML 结构(w15/w14 命名空间、settings.xml rsids 段)、POI 缺口(CTDocRsids dangling、people 无 Java 类)、nondocx 自维护模式(OPC part + XmlCursor)。这层信息量大且独立,塞进 03 会让创作篇失焦。tracked-changes 没有这么集中的基础设施层(RSID/paraId 在 tracked 是隐式的)。

### Q2 ✅ spec 更新范围(survey 确认)

poi-bridge.md 的 **N18-N24 已经是「已实现」记录**(read/authoring/reply-threads/infrastructure 全部),spec 主体**无需改**。但有两处**旧的 out-of-scope 表述**要修:

- **`backend/index.md` 第 86 行**:Scope Boundaries 段把「Tracked changes」与 fields/OLE/math 并列标 raw-only——但 tracked changes 早已实现(N12-N17),comments 也已实现(N18-N24)。这里要把 tracked changes + comments 拿出来标为已实现,fields/OLE 等保留 raw-only。
- **根 `README.md` 特性列表(18-22 行)**:有详细 tracked-changes 条目,无 comments 条目——补 comments 条目(对称)。

**无需改**:`quality-guidelines.md`(无 comments out-of-scope 表述)、`error-handling.md`(无)、`renderer-compatibility.md`(批注无渲染兼容坑记录,N18-N24 未发现)。

## 2. 文档教学约定

每篇遵守 AGENTS.md 三层递进:「**OOXML 是什么 → POI 如何表达 → nondocx 为什么这样设计**」。对照 05-tracked-changes 风格:
- 先给 OOXML XML 结构(code block)
- 再说 POI 的 `XWPF*` 类型如何映射(或为何不映射)
- 最后 nondocx 的封装决策(引 N18-N24 的 spec 锚点)
- 每篇有可运行代码片段(取自 CommentsExample 或自包含)

## 3. API 速查段落(03-api-reference.md)

在 `TrackedChanges / TrackedChange` 段落(227 行)后、`速查:长度单位`(241 行)前,插入:

```
## `Comments` / `Comment`(批注)
入口 `doc.comments()` / `paragraph.addComment(...)`。完整 API 与语义见 [06 教程](./06-comments/README.md)。
| 方法 | 类别 |
| `doc.comments().list()` / `get(id)` | 只读枚举 |
| `doc.comments().reply(parentId, author, text)` | 回复(线程) |
| `paragraph.addComment(author, text)` | 创作(整段范围) |
| `comment.id()` / `author()` / `text()` / `date()` / `initials()` | 元数据 |
| `comment.parentId()` / `paraId()` | 线程 |
```

同时在 `Document` 段落的表格(49 行 trackedChanges() 附近)补 `comments()` 行,在 `Paragraph` 段落补 `addComment` 行。

## 4. 集成示例(CommentsExample.java)

对照 `TrackedChangesExample.java`,演示完整闭环:
1. 打开含内容的 docx
2. 读现有批注(若有)
3. 创作范围批注
4. 回复形成线程
5. save → reopen → 再读验证线程 + paraId/parentId

放 `nondocx-examples/src/main/java/com/non/docx/examples/CommentsExample.java`,有 main 方法可独立运行。

## 5. 父任务 AC 核验(R6)

逐条核验父任务 prd 的 AC1-AC5:
- AC1 五子任务交付(read✓/authoring✓/reply-threads✓/infrastructure✓/docs-spec 本任务)
- AC2 POI-free grep:`grep -rn "org.apache.poi" api/comment/` 应只在构造函数/raw() 接缝
- AC3 文档四篇对称(本任务交付)
- AC4 CommentsExample(本任务交付)
- AC5 无回归(全量测试绿)

## 6. 待收敛问题

无。Q1/Q2 已收敛。
