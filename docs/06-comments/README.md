# 06 · 批注（comments）教程

**批注（comments）** 是 docx 里「在正文边缘留下评论、可回复形成线程」的机制 —— Word 里点「新建批注」进入的那种。nondocx 把它从头到尾做完了：**读、查、创作、回复线程、现代 Word 兼容基础设施**。

这是 nondocx 里 OOXML 结构最「分散」的特性之一：批注正文在一个 part、锚点在另一个 part、线程关系在第三个 part、作者身份在第四个 part。这套教程把它讲透。

> 教程全程沿用 [02 架构](../02-architecture.md) 的三层递进范式：**OOXML 是什么 → POI 如何表达 → nondocx 为什么这样设计**。

---

## 这个特性为什么难

先给你一个心理预期 —— docx 的批注**不是一处内容**：

- 批注**正文**存在独立的 `word/comments.xml`，被评论的范围在 `word/document.xml` 里用锚点包裹
- **线程关系**（谁回复了谁）在**另一个** part `word/commentsExtended.xml`，靠 `paraId` 间接关联，**不是**靠批注 id
- POI 对后三个 part（commentsExtended / commentsIds / commentsExtensible）**没有 Java 类、没有 API**
- 现代兼容元数据（people.xml / paraId / RSID）POI 也都不提供，要 nondocx 自己维护

nondocx 把这些脏活全部收进 `internal/poi/`（`CommentNodes` / `CommentExtendedParts` / `AuthoringInfra`），对外只暴露一套干净的领域类型。这套教程帮你理解「封装了什么、为什么这样封装、边界在哪里」。

---

## 四篇导引

| # | 文档 | 解决什么 |
|---|---|---|
| 01 | [OOXML 批注模型](./01-concepts.md) | 批注在 docx 里长什么样 —— comments.xml 结构、四个 part 总览、锚点配对、people/paraId/RSID 基础设施概念 |
| 02 | [读与查询](./02-read.md) | `Document.comments()` 门面、`list()`/`get(id)`、`Comment` 的五字段 + paraId/parentId 怎么读 |
| 03 | [创作与回复](./03-authoring.md) | 怎么主动写批注：整段范围批注（`Paragraph.addComment`）、回复形成线程（`Comments.reply`） |
| 04 | [现代兼容基础设施](./04-infrastructure.md) | people.xml（@mention）、w14:paraId（线程 key）、RSID（文档级单例 + 合并修订）—— comments 独有的兼容层 |

**建议顺序读**：概念 → 读 → 创作 → 基础设施。前三篇覆盖核心能力，04 是「锦上添花」的兼容性展开（缺了批注仍能用，但 Word 体验打折）。

> **与修订（tracked changes）教程的区别**：批注没有 accept/reject 概念（不是修订，不会被「接受/撤销」，它只是评论）。所以本教程没有对应 [05/03 accept-reject](../05-tracked-changes/03-accept-reject.md) 的篇章。

---

## 一个端到端示例（贯穿四篇）

下面这个例子把四篇的核心能力串起来 —— 创作范围批注 → 回复形成线程 → 落盘 → 重开 → 读回验证。后续每篇会展开其中一段。

```java
import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.comment.Comment;
import io.github.nondirectional.docx.core.api.comment.Comments;
import java.nio.file.Path;

Path file = Path.of("reviewed.docx");

// === 阶段 1：创作 + 回复（见 03）===
try (Document doc = Docx.open(Path.of("draft.docx"))) {
    // 给第一段加一条范围批注（包住整段）
    Comment root = doc.paragraph(0).addComment("审阅者甲", "这段需要补充背景");
    // 回复这条批注，形成线程
    doc.comments().reply(root.id(), "审阅者乙", "已补充，见第二段");
    doc.save(file);
}

// === 阶段 2：读与查询（见 02）===
try (Document doc = Docx.open(file)) {
    Comments cs = doc.comments();
    System.out.println("共 " + cs.list().size() + " 条批注");
    for (Comment c : cs.list()) {
        System.out.println("  [" + c.id() + "] " + c.author() + ": " + c.text());
        if (c.parentId().isPresent()) {
            System.out.println("    (回复了批注 " + c.parentId().get() + ")");
        }
    }
    // 单条查询
    Comment first = cs.get("0");
    System.out.println("首条批注作者: " + first.author());
}
```

> **基础设施是自动的**：上面创作出的批注，nondocx 会**自动**注入 people.xml（作者注册）、paraId（线程身份）、RSID（修订会话标记）。你不用调任何额外方法 —— 04 会解释这些是怎么自动发生的。

---

## 与 nondocx 核心契约的关系

批注特性在 [02 架构](../02-architecture.md) 的契约下做了几处**诚实的取舍**，每篇都会指明：

| 契约 | 批注如何遵守 / 偏离 |
|---|---|
| **活对象（holding-wrapper）** | `Comment` 持有 `XWPFComment` 委托，标准 holding-wrapper；paraId/parentId 是构造时注入的解析值（不缓存读，每次 `list()` 重算） |
| **`raw()` 逃生舱** | `Comments.raw()` 返回 `XWPFDocument`（批注没有专属单一委托类型，整份文档是委托）；想直接操作 OOXML 时走它 |
| **零 POI 泄露** | `api/comment/*` 全部 POI-free；POI 脏活在 `internal/poi/`（CommentNodes / CommentExtendedParts / AuthoringInfra） |
| **不参与 Document.equals** | 批注列表不纳入 [04 往返保真](../04-round-trip-and-equality.md) 的内容相等性 —— 比较批注要单独 `comments().list()` |
| **稳定 id** | `Comment.id()` 透传 OOXML 的 `w:id`，**跨会话稳定**（不像 tracked-changes 的进程内 id） |

---

## 可运行示例

教程代码片段都取自 `nondocx-examples/`：

- [`CommentsExample.java`](../../nondocx-examples/src/main/java/io/github/nondirectional/docx/examples/CommentsExample.java) —— 完整闭环：创作范围批注 → 回复 → save→reopen → 读回验证线程

---

## 下一步

开始读 [01 · OOXML 批注模型](./01-concepts.md)，从「批注在 docx 里长什么样」讲起。
