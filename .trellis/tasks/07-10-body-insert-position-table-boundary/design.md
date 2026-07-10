# 设计：表格边界场景下段落插入落点修复

## 核心思路

**保持 `ParagraphPreview.index` 语义不变（段落投影索引，跳过表格），新增 `bodyIndex` 字段（body 顺序索引，含表格）。TablePreview 同理新增 `bodyIndex`。**

这样：
- replace/update 类操作仍用 `paragraph_index`（列表下标语义不变，BodyAgent.findParagraphContaining 逻辑不动）
- insert 类操作改用 `body_index`（LLM 从 body 顺序视图里直接读到正确的 body 索引）
- LLM 在 prompt 里同时看到两个索引，不再混淆

## 改动清单

### 1. ParagraphPreview 加 bodyIndex 字段

`nondocx-toolkit/.../snapshot/ParagraphPreview.java`

```java
public final class ParagraphPreview {
  private final int index;       // 段落投影索引（不变，replace/update 用）
  private final int bodyIndex;   // 新增：body 顺序索引（insert 用）
  private final String text;
  private final String headingLevel;
  private final boolean listItem;

  // 构造加 bodyIndex 参数
  // equals/hashCode 加 bodyIndex
}
```

### 2. TablePreview 加 bodyIndex 字段

`nondocx-toolkit/.../snapshot/TablePreview.java`

```java
public final class TablePreview {
  private final int index;       // 表格投影索引（不变）
  private final int bodyIndex;   // 新增：body 顺序索引
  // 其余不变
}
```

### 3. SnapshotBuilder 改为遍历 bodyElements

`nondocx-toolkit/.../snapshot/SnapshotBuilder.java`

当前 `buildParagraphs` 只遍历 `doc.paragraphs()`，`buildTables` 只遍历 `doc.tables()`。
改为：遍历 `doc.bodyElements()`，维护两个计数器（paraIdx / tableIdx）和 bodyIdx，给段落和表格分别打上 body 索引。

```java
private BodyPreviews buildParagraphsAndTables(Document doc) {
  List<ParagraphPreview> paras = new ArrayList<>();
  List<TablePreview> tables = new ArrayList<>();
  int paraIdx = 0;
  int tableIdx = 0;
  int bodyIdx = 0;
  for (BodyElement be : doc.bodyElements()) {
    if (be instanceof Paragraph) {
      Paragraph p = (Paragraph) be;
      // ... 构造 ParagraphPreview(paraIdx, bodyIdx, text, heading, listItem)
      paras.add(...);
      paraIdx++;
    } else if (be instanceof Table) {
      Table t = (Table) be;
      // ... 构造 TablePreview(tableIdx, bodyIdx, rowCount, colCount, samples)
      tables.add(...);
      tableIdx++;
    }
    bodyIdx++;
  }
  return new BodyPreviews(paras, tables);
}
```

### 4. LlmDocxExpert.buildPrompt 改渲染

`nondocx-demo/.../LlmDocxExpert.java`

把扁平段落列表改为 **body 顺序交错视图**，同时保留段落索引映射：

```
## 文档结构（body 顺序）
- [body:0] 表格0 (3×2)
- [body:1] 段落: 开头文本 (para:0)
- [body:2] 段落: 结尾文本 (para:1)

## body 顺序说明
- body:N 是正文 body 的绝对位置（含表格），insert_paragraph 的 body_index 用这个
- para:N 是段落索引（跳过表格），replace_run_text/update_run_style/update_paragraph_alignment 的 paragraph_index 用这个
```

### 5. BodyExecutor.normalizeBodyIndex 去掉误导性容错

当前 `normalizeBodyIndex` 把 `paragraph_index` 当 `body_index` 用，注释说「无表格时等价」。
改为：**不再自动翻译**。insert_paragraph 必须用 `body_index`。如果 LLM 只给了 `paragraph_index`，记录但不翻译（让它失败暴露问题，而非静默错位）。

### 6. 测试

新增 `SnapshotBuilderBodyOrderTest`（或类似名），覆盖：
- 文档 `[表格, 段落A, 段落B]`：段落A 的 bodyIndex=1、index=0；段落B 的 bodyIndex=2、index=1
- 文档 `[段落A, 表格, 段落B]`：表格 bodyIndex=1
- 文档 `[段落A, 段落B, 表格]`：表格 bodyIndex=2
- 现有 SnapshotBuilder 逻辑不回归

BodyAgent 相关测试确认 paragraph_index 语义不变。

## 不改的部分

- core 层 `Document.insertParagraph` / `bodyElements`：语义已正确，不动
- `ParagraphPreview.index`：保持段落投影索引语义
- `TablePreview.index`：保持表格投影索引语义
- BodyAgent.findParagraphContaining：返回列表下标当 paragraphIndex，逻辑不变
- ReadCoordinator：不碰 preview，不变

## 风险

- **Schema 变更**：`ParagraphPreview` / `TablePreview` 加字段是破坏性改动（构造函数签名变）。影响面已确认：MergedPlanTest 构造 ParagraphPreview 的唯一测试点需同步更新。
- **prompt token 增加**：body 顺序视图比扁平列表稍长，但表格信息本来就少，影响可控。
