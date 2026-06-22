# 04 · 往返保真与内容相等性

这一篇回答两个问题：

1. 为什么 `doc.save(...)` 再 `Docx.open(...)` 回来，新 `Document` 和原文档 `equals` 相等？
2. 能不能把 nondocx 的活对象当 `HashMap` 的键？

**核心思想一句话**：`equals/hashCode` 比较的是**从公开 getter 解析出的语义值**，而不是底层 POI 委托、也不是原始 XML。

> 这一机制是 nondocx 的灵魂契约之一，是 [02 架构](./02-architecture.md) 中「活对象」三连的第三块拼图。

---

## 1. 问题：为什么 save→reopen 还能相等？

回忆 [02 架构](./02-architecture.md#2-活对象契约holding-wrapper)：每个 wrapper 持有一个 `final XWPF* delegate`。

```
Document A           save→reopen           Document B
─────────────        ─────────────         ─────────────
delegate = XWPF#1   →   序列化到磁盘   →   delegate = XWPF#2
                                           (全新的 POI 实例)
```

`A.delegate` 和 `B.delegate` 是**不同的对象引用**（`XWPFDocument#1` vs `#2`）。如果 `equals` 比的是委托引用，A 和 B 必然不相等 —— 那往返保真的断言就无从写起。

nondocx 的解法：**`equals/hashCode` 排除委托引用，只比较从委托解析出的语义值**。

---

## 2. 内容相等性：比的是解析值，不是 XML

### `Run` 的例子

```java
// nondocx-core/.../api/text/Run.java
public boolean equals(Object o) {
    if (!(o instanceof Run)) return false;
    Run that = (Run) o;
    return java.util.Objects.equals(this.text(), that.text())
        && java.util.Objects.equals(this.style(), that.style());
}
public int hashCode() {
    return java.util.Objects.hash(text(), style());
}
```

两个 run 相等 ⟺ **文本相同** 且 **六属性样式快照相同**（粗体/斜体/下划线/字体/字号/颜色）。

注意：**不比较 `CTR`（POI 对 `<w:r>` 的 CT 类型）的原始 XML**。这是关键 —— 因为原始 XML 里有 POI 写入时的各种归一化痕迹：

- 空的 `<w:rPr>` 残留
- 重新分配的 numbering id（同一段落 list 的 numId 在 reopen 后可能变）
- 默认属性的填充顺序差异
- 属性的大小写/顺序

这些都**对 `equals` 不可见**。比较只走公开 getter（`text()`、`style()`、`listKind()`、`color()` ...），这些 getter 已经把 XML 差异归一化掉了。

### `Paragraph` 的例子

```java
public boolean equals(Object o) {
    if (!(o instanceof Paragraph)) return false;
    Paragraph that = (Paragraph) o;
    return java.util.Objects.equals(this.inlineElements(), that.inlineElements())
        && java.util.Objects.equals(this.heading(), that.heading())
        && this.alignment() == that.alignment()
        && this.indentationLeft() == that.indentationLeft()
        && this.indentationFirstLine() == that.indentationFirstLine()
        && Double.doubleToLongBits(this.lineSpacing()) == Double.doubleToLongBits(that.lineSpacing())
        && java.util.Objects.equals(this.listKind(), that.listKind())
        && java.util.Objects.equals(this.listLevel(), that.listLevel());
}
```

比较的是：**内联元素序列 + 段落样式 + 列表成员**。一切从 getter 来，一切经过归一化。

### `Document` 的例子

```java
public boolean equals(Object o) {
    if (!(o instanceof Document)) return false;
    Document that = (Document) o;
    return java.util.Objects.equals(this.bodyElements(), that.bodyElements())
        && java.util.Objects.equals(this.sections(), that.sections());
}
```

只比两件事：**正文序列**（段落+表格的真实顺序）和**分节序列**（页面属性 + 节内默认页眉页脚内容）。

> 列表 `equals` 里的 `java.util.Objects.equals(list1, list2)` 走 `AbstractList.equals`，会逐个元素 `equals`。所以 `Document.equals` 自动递归到 `Paragraph.equals` → `Run.equals`。整个树都参与比较。

---

## 3. 一个完整往返断言长什么样

```java
// 简化自 nondocx-core/.../RoundTripTest.java
@Test
void fullDocumentRoundTripsEqual(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("round.docx");

    // 构造文档，save
    try (Document original = Docx.create()) {
        original.addParagraph("Hello").heading(HeadingLevel.H1);
        original.addParagraph().addRun("bold").bold();
        // ... 表格、图片、超链接、列表、分节、页眉页脚 ...
        original.save(file);
    }

    // 重新打开，断言相等
    try (Document reopened = Docx.open(file)) {
        // 不同 XWPFDocument 实例，但内容相等
        assertThat(reopened).isEqualTo(/* 重建出同样的内容，或直接持有 original 的快照 */);
    }
}
```

`RoundTripTest` 共 **8 个用例**，覆盖：完整文档、标题+样式 run、超链接、图片、列表、表格、分节属性、页眉页脚。这是 nondocx **「读和写都做、且往返不丢内容」** 的硬承诺。

> 这是 README 特性列表里「完整的读 *和* 写往返保真」的具体含义 —— 不是口号，是测试守住的契约。

---

## 4. 活对象 ≠ 长期 HashMap 键

**`Document`/`Paragraph`/`Run` 是可变活对象**，内容随时会改。这意味着：

```java
HashMap<Paragraph, String> map = new HashMap<>();
Paragraph p = doc.paragraph(0);
map.put(p, "标记");

// ... 之后在别处改了这个段落
doc.paragraph(0).addRun("追加文本");

map.get(p);  // ⚠️ 可能查不到 —— p.hashCode 变了，桶位置错了
```

- `equals` / `hashCode` 服务于**比较和测试**（断言、集合 contains、临时查找）。
- **不适合**作为长期 `HashMap`/`HashSet` 的键 —— 键放进容器后内容一旦变，就「丢了」。
- 这与 `String`、`Integer` 这类不可变键是本质区别。

**安全用法**：

- ✅ 在一次方法调用内用 `List.contains(paragraph)` 做查找
- ✅ 在测试里 `assertThat(reopened).isEqualTo(expected)`
- ✅ 临时 `Map<Paragraph, X>` 在 map 生命周期内不修改文档
- ❌ 跨长期操作/跨线程把 wrapper 当稳定的键

---

## 5. 已知的不对称（诚实边界）

并非所有读得到的东西都参与 `equals`。这是有意的：

| 特性 | 读得到吗 | 参与 `Document.equals` 吗 | 原因 |
|---|---|---|---|
| 段落、表格（body） | ✅ | ✅ | 核心承诺 |
| 分节、页面属性、默认页眉页脚 | ✅ | ✅ | 核心承诺 |
| **TOC（域形态）** | ✅（嵌在正文里） | ✅（隐式，run/超链接已计入 body） | 域形态无独立结构 |
| **TOC（SDT 形态）** | ✅ | ❌ | `<w:sdt>` 容器不在 `bodyElements()` 建模范围 |
| **修订（tracked changes）** | ✅（`trackedChanges()`） | ❌ | 修订是元数据，与正文内容正交 |
| 非默认页眉页脚（偶数页/首页） | 部分 | ❌ | MVP 只建模默认页眉页脚 |

**遇到不对称怎么办**：

- SDT 形态的 TOC 要做往返断言 → 直接比较 `doc.toc().entries()`，别依赖 `Document.equals`。
- 修订要做断言 → 比较 `trackedChanges().list()`，详见 [05/02](./05-tracked-changes/02-read-and-query.md)。

「读得到但比较不到」不是 bug，是建模范围的有意界定。`.trellis/spec/backend/poi-bridge.md N7` 有更细的源码级说明。

---

## 6. 一句话总结

> **`equals/hashCode` 比较的是从公开 getter 解析出的语义值**（不比委托引用、不比原始 XML），
> 因此 POI 的写侧归一化痕迹对 `equals` 不可见，往返保真能严格断言；
> 但活对象可变，**别当长期 HashMap 键**。

---

## 下一步

- 想看声明式构建如何利用这套相等性做测试 → [06 · 构建器轨道](./06-builder-track.md)
- 想看修订如何在这套相等性外另立比较 → [05 · tracked changes 教程](./05-tracked-changes/README.md)
- 遇到不相等的情况查边界 → [09 · FAQ](./09-faq-and-boundaries.md)
