# 实现计划：表格边界场景下段落插入落点修复

## 决策汇总

1. **修复策略**：改快照为 body 顺序视图（根因修复）
2. **数据模型**：ParagraphPreview/TablePreview 加 `bodyIndex` 字段，保留原 `index`（投影索引）不变
3. **normalizeBodyIndex**：保留但加防错检查（有表格时抛异常而非静默错位）

## 实现步骤

### Step 1: ParagraphPreview 加 bodyIndex 字段
- 文件：`nondocx-toolkit/.../snapshot/ParagraphPreview.java`
- 加 `private final int bodyIndex` 字段 + 构造参数 + getter + equals/hashCode
- Javadoc 说明 index（投影索引）与 bodyIndex（body 顺序索引）的区别

### Step 2: TablePreview 加 bodyIndex 字段
- 文件：`nondocx-toolkit/.../snapshot/TablePreview.java`
- 同上

### Step 3: SnapshotBuilder 改为遍历 bodyElements
- 文件：`nondocx-toolkit/.../snapshot/SnapshotBuilder.java`
- `buildParagraphs` + `buildTables` 合并为一次遍历 `doc.bodyElements()`
- 维护 paraIdx / tableIdx / bodyIdx 三计数器
- 需要引入 `BodyElement` 接口

### Step 4: BodyExecutor normalizeBodyIndex 加防错检查
- 文件：`nondocx-toolkit/.../orchestration/body/BodyExecutor.java`
- insert_paragraph 分支：若 payload 只有 paragraph_index 没有 body_index
  - 通过 toolkit 检查文档是否有表格
  - 无表格：保持等价 fallback（把 paragraph_index 当 body_index）
  - 有表格：抛 OperationExecutionException，提示「文档含表格，insert_paragraph 必须用 body_index」

### Step 5: LlmDocxExpert.buildPrompt 改渲染
- 文件：`nondocx-demo/.../LlmDocxExpert.java`
- 段落预览和表格预览合并为「body 顺序视图」
- 明确区分 body_index（insert 用）和 paragraph_index（replace/update 用）

### Step 6: 修复现有测试
- 文件：`nondocx-toolkit/.../orchestration/MergedPlanTest.java`
- ParagraphPreview 构造调用加 bodyIndex 参数

### Step 7: 新增测试
- 新文件：`nondocx-toolkit/src/test/java/.../snapshot/SnapshotBodyOrderTest.java`
- 覆盖三种 body 顺序：开头表格、中间表格、末尾表格
- 验证 ParagraphPreview.bodyIndex 和 TablePreview.bodyIndex 正确

### Step 8: 验证
- `mvn spotless:apply`
- `mvn -pl nondocx-toolkit -am verify`
- `mvn -pl nondocx-demo -am verify`

## 风险文件

- `ParagraphPreview.java` / `TablePreview.java`：构造签名变，破坏性
- `SnapshotBuilder.java`：核心逻辑改写
- `MergedPlanTest.java`：需同步更新
