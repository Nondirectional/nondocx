# 05 · 修订（tracked changes）教程

**修订（tracked changes）** 是 docx 里「改动被记录、可审阅、可接受/撤销」的机制 —— Word 里点「修订」按钮进入的那种状态。nondocx 把它从头到尾做完了：**读、查、accept/reject、显式创作**。

这是 nondocx 目前覆盖最深的特性，也是 OOXML 里最绕的一块（标记散落各处、CT 类型分裂、配对无显式指针）。这套教程把它讲透。

> 教程全程沿用 [02 架构](../02-architecture.md) 的三层递进范式：**OOXML 是什么 → POI 如何表达 → nondocx 为什么这样设计**。

---

## 这个特性为什么难

先给你一个心理预期 —— docx 的修订**不是一个独立元素**：

- 它是**散落**在 `word/document.xml` 正文各处的标记元素（`<w:ins>`/`<w:del>`/`<w:moveFrom>`/…）
- 开关在**另一个文件** `word/settings.xml` 的 `<w:trackChanges/>`
- POI **没有** `XWPFTrackedChanges` 这类高级 API，要自己用 `XmlCursor` 遍历
- 移动类（move）配对**没有显式指针**，靠 author + text 启发式
- 属性类（rPrChange 等）的 CT 类型与文本类**不同**，且部分在 POI 精简 jar 里**缺失**

nondocx 把这些脏活全部收进 `internal/poi/TrackedChangeNodes`，对外只暴露一套干净的领域类型。这套教程帮你理解「封装了什么、为什么这样封装、边界在哪里」。

---

## 四篇导引

| # | 文档 | 解决什么 |
|---|---|---|
| 01 | [OOXML 修订模型](./01-concepts.md) | 修订在 docx 里长什么样 —— 开关、四大类标记（文本/移动/属性/单元格）、各自的 XML 结构 |
| 02 | [读与查询](./02-read-and-query.md) | `Document.trackedChanges()` 门面、`list()`/`get(id)`、`TrackedChange` 的 type/family/location/details 怎么读 |
| 03 | [accept / reject](./03-accept-reject.md) | 四大类（文本/移动/属性/单元格）的 accept/reject 语义、move 配对联动、family gate、POI 缓存失效 |
| 04 | [创作（authoring）](./04-authoring.md) | 怎么主动写出修订标记：插入、删除、替换、改样式（rPrChange）、cell 存亡、移动 |

**建议顺序读**：概念 → 读 → 处理 → 创作。如果只想做某一步，每篇都尽量自包含，按需跳读也行。

---

## 一个端到端示例（贯穿四篇）

下面这个例子把四篇的核心能力串起来 —— 创作修订 → 落盘 → 重开 → 查询 → accept。后续每篇会展开其中一段。

```java
import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.track.TrackedChange;
import io.github.nondirectional.docx.core.api.track.TrackedChanges;
import io.github.nondirectional.docx.core.api.track.TrackedChangeType;
import java.nio.file.Path;

Path file = Path.of("reviewed.docx");

// === 阶段 1：创作（见 04）===
// Agent 或程序主动写出修订标记 —— 与 <w:trackChanges/> 开关正交。
try (Document doc = Docx.open(Path.of("draft.docx"))) {
    // 把第一段第一个 run 标记为「删除并替换」
    doc.paragraph(0).run(0).replaceTracked("审阅者甲", "修订后的文本");
    // 末尾追加一条 tracked 插入
    doc.paragraph(0).addInsertion("审阅者甲", "补充一句");
    doc.save(file);
}

// === 阶段 2：读与查询（见 02）===
try (Document doc = Docx.open(file)) {
    TrackedChanges tc = doc.trackedChanges();
    System.out.println("修订模式开关: " + tc.enabled());   // settings.xml 的 <w:trackChanges/>
    System.out.println("共 " + tc.list().size() + " 条修订");
    for (TrackedChange c : tc.list()) {
        System.out.println("  " + c.type() + " by " + c.author() + " @ " + c.location());
    }

    // === 阶段 3：处理（见 03）===
    // 接受所有「审阅者甲」的文本类修订（ins/del）；属性类/单元格类走专用方法。
    int applied = tc.acceptByAuthor("审阅者甲");
    System.out.println("接受了 " + applied + " 条");
    doc.save(file);   // 重要：accept 后 POI wrapper 可能失效，验证结构需 save→reopen
}

// === 阶段 4：验证（accept 后必须 reopen）===
try (Document doc = Docx.open(file)) {
    System.out.println("剩余修订: " + doc.trackedChanges().list().size());  // 应为 0
}
```

> **重要提醒**（03 会详讲）：accept/reject 会重写文档树，此后内存里的 `Paragraph`/`Run` 包装器可能 `XmlValueDisconnected`。验证 accept 后的结构**必须 save→reopen**，不能信任 accept 前的 wrapper。

---

## 与 nondocx 核心契约的关系

修订特性在 [02 架构](../02-architecture.md) 的契约下做了几处**诚实的取舍**，每篇都会指明：

| 契约 | 修订如何遵守 / 偏离 |
|---|---|
| **活对象（holding-wrapper）** | 文本/移动/属性/cell 类的 `TrackedChange` 持有对应 CT 节点，标准 holding-wrapper；**仅 cellMerge 无委托**（CT 类型缺失，纯值对象） |
| **`raw()` 逃生舱** | 文本/移动类返回 `CTRunTrackChange`；**属性/cell 类 `raw()` 抛 `UnsupportedFeatureException`**（引导到专用写方法，方案 C） |
| **零 POI 泄露** | `api/track/*` 全部 POI-free；POI 脏活在 `internal/poi/TrackedChangeNodes` |
| **不参与 Document.equals** | 修订列表不纳入 [04 往返保真](../04-round-trip-and-equality.md) 的内容相等性 —— 比较修订要单独 `tc.list().equals(...)` |
| **稳定 id 仅进程内** | `TrackedChange.id()` 同会话稳定，**不承诺 save 后稳定**（accept 后想重新操作必须 `list()` 重取 id） |

---

## 可运行示例

教程代码片段都取自 `nondocx-examples/`：

- [`TrackedChangesExample.java`](../../nondocx-examples/src/main/java/io/github/nondirectional/docx/examples/TrackedChangesExample.java) —— 只读消费（02）
- [`TrackedAuthoringAdvancedExample.java`](../../nondocx-examples/src/main/java/io/github/nondirectional/docx/examples/TrackedAuthoringAdvancedExample.java) —— 高级类型创作（04）
- [`TrackedCellChangesExample.java`](../../nondocx-examples/src/main/java/io/github/nondirectional/docx/examples/TrackedCellChangesExample.java) —— 单元格修订（03/04）

---

## 下一步

开始读 [01 · OOXML 修订模型](./01-concepts.md)，从「修订在 docx 里长什么样」讲起。
