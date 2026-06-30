# Design — docx compare to tracked changes（MVP）

> 配套 `prd.md`。本文记录“旧版 `docx` + 新版 `docx` → 生成带修订结果文档”的第一版技术设计。重点不是追求 Word Compare 的全量保真，而是在现有 tracked authoring 基础上，交付一个边界诚实、结果可审阅、实现复杂度可控的 compare MVP。

## 1. 设计目标

第一版要解决的是：

1. 以**旧文档**为结果基线
2. 把**正文段落文本**差异写成标准 tracked changes
3. 结果文档可继续走现有 `trackedChanges().list()` / accept / reject
4. 不为了第一版 compare 去引入一整套高风险的 run 拆分 / 样式保真框架

第一版明确**不**解决：

- 与 Microsoft Word Compare 的行为完全一致
- 表格、页眉页脚、批注、图片、分节、样式变更的 compare
- 复杂内联结构（超链接 / 图片 / field）所在差异段落的保真改写
- move 识别
- run 级样式保真

## 2. 三层映射

### 2.1 OOXML 层

对比结果不是“高亮文本”，而是实际写入修订节点：

- 插入：`<w:ins>`
- 删除：`<w:del>`
- 替换：底层等价于 `del + ins`

正文的结构单位是段落 `<w:p>`，段内文本通常由多个 run `<w:r>` 组成；每个 run 还可能带自己的 `<w:rPr>`。这意味着“只改中间几个字符”在 OOXML 里往往需要把一个 run 拆成多段。

第一版为了避免 run 拆分，采用的策略不是“在原 run 上精细打点”，而是：

- 对支持的差异段落，**清空旧段落内联内容**
- 再按 diff 结果**重建一组新的纯文本 run / ins / del**

这会保住“文本差异 + 修订结构”两件事，但会丢失原有 run 级样式与原始 run 分段。

### 2.2 POI 层

Apache POI 没有高层的“compare two docx and emit tracked changes” API，也没有：

- 段落序列对齐能力
- 字符级 diff 能力
- 按字符边界拆 `XWPFRun` 并保留样式的高层能力

现有 nondocx 已经提供了三块可复用基础：

- `Paragraph.addInsertion(author, text)`
- `Paragraph.addDeletion(author, targetRun)`
- `Run.replaceTracked(author, newText)`

以及段落内容重建能力：

- `Paragraph.removeInlineElement(int)`
- `Paragraph.addRun(String)`

因此 compare MVP 的关键不是再找 POI 魔法 API，而是自己做：

1. 段落序列对齐
2. 段内文本 diff
3. 把 diff 重放成现有 authoring API 可表达的修订结构

### 2.3 nondocx 层

nondocx 第一版 compare 的责任是：

- 暴露一个**顶层 compare 入口**
- 复用现有 tracked authoring API 生成标准修订
- 对不支持的结构边界保持诚实

nondocx 第一版 compare **不**负责：

- 自动推断“最佳审阅语义”
- 隐式保留 run 样式
- 在 `toolkit` 层重新实现 compare 语义

## 3. 公共 API 形态

### 3.1 入口位置

compare 入口放在 `Docx` 静态工厂侧，而不是 `Document` 或 `TrackedChanges`：

- `Docx` 负责“外部源 → 文档对象”的顶层入口
- compare 天然是“两份文档 → 一份结果文档”
- `TrackedChanges` 门面负责“处理已有修订”，不负责“从两份文档生成修订”

### 3.2 MVP 推荐 API

第一版推荐最小 API 集合：

```java
Document result = Docx.compare(oldPath, newPath);
Document result = Docx.compare(oldPath, newPath, author);
```

其中：

- `compare(Path oldPath, Path newPath)` 使用默认作者
- `compare(Path oldPath, Path newPath, String author)` 允许显式指定作者

返回值是一个新的活跃 `Document`，调用方负责：

- `save(...)`
- `close()`

### 3.3 默认作者

推荐默认值：

```text
nondocx compare
```

理由：

- 中性、可识别
- 不假装代表真实业务用户
- 与“这是程序生成的审阅结果”语义一致

显式传入的 `author` 仍遵守现有 tracked authoring 约束：

- `null` / 空白 → `IllegalArgumentException`

### 3.4 暂不引入 Options 对象

第一版只有一个真实可配参数：`author`。为它单独引入 `CompareOptions` 会过早抽象。

后续若新增：

- 是否自动开启 `<w:trackChanges/>`
- 不支持段落的处理策略
- 默认作者
- 报告输出

再考虑引入 options 值对象。

## 4. 结果文档的生成策略

### 4.1 以旧文档为基线克隆

第一版结果文档从**旧文档的深拷贝**开始，而不是从空文档重建。

推荐路径：

1. `Docx.open(oldPath)` 打开旧文档
2. 将旧文档 `save` 到内存字节数组
3. 再 `Docx.open(new ByteArrayInputStream(bytes))` 得到独立结果文档
4. 用旧文档 + 新文档做 compare，把修订写入结果文档

这样做的价值：

- 结果默认保留旧文档里所有未纳入 compare 的结构
- 不需要第一版就重建节、页眉页脚、表格、图片等复杂结构
- 结果文档天然维持旧文档的整体布局基线

### 4.2 不自动改写 `<w:trackChanges/>` 开关

第一版 compare 只负责写出修订节点，不自动开启或关闭 `settings.xml` 中的 `<w:trackChanges/>`。

原因：

- 开关与已有修订标记正交
- 结果文档即使不开启开关，也能正常显示已生成的修订
- “是否让后续人工继续自动追踪”属于额外策略，第一版不引入

后续若有需要，可再加 options。

## 5. 段落级 compare 设计

### 5.1 仅处理正文段落序列

compare 的结构输入来自：

- 旧文档 `Document.paragraphs()`
- 新文档 `Document.paragraphs()`

明确**不**从 `bodyElements()` 级别做全结构 compare。表格等其它 body 元素保留旧文档原样。

### 5.2 段落对齐策略：顺序保持的精确文本锚点

虽然第一版不做“相似段落重配对”，但也不建议采用最粗暴的“相同索引硬对齐”，否则中间插入一段会导致后续全部级联误差。

推荐策略：

- 用段落纯文本 `Paragraph.text()` 作为对齐依据
- 做一个**顺序保持**的段落序列 diff
- 只把“纯文本完全相等”的段落当作稳定锚点
- 锚点之间的旧侧多出段落 → 整段删除
- 锚点之间的新侧多出段落 → 整段插入
- 两侧都存在但文本不同的同位置段落 → 进入段内文本 diff

这仍然是“按正文顺序对齐”，但避免了中间插入/删除导致后续全部错位。

明确不做：

- 相似度匹配
- 跨段移动识别
- 语义重排推断

### 5.3 支持段落与不支持段落

第一版只安全处理**纯文本 run 段落**。支持条件推荐定义为：

- `inlineElements()` 中全部元素都是 `Run`
- 不含 `Hyperlink`
- 不含 `Image`
- 不依赖 field 等复杂 inline 结构

对不支持段落：

- 若该段在旧新两侧文本相同 → 保留旧段落原样
- 若该段存在差异 → 结果中仍保留旧段落原样，**不尝试改写**

这是一条有意的、诚实的 MVP 边界。否则第一版必须先补 run 拆分、复杂 inline 重建与关系处理。

## 6. 段内文本 diff 设计

### 6.1 粒度：字符级（按 Unicode code point）

对已对齐且支持的段落，段内 diff 采用**字符级**。

实现上推荐按 **Unicode code point** 而不是 Java `char`：

- 中文场景天然适配
- 避免把代理对字符拆坏
- 比“按词 diff”更适合第一版

### 6.2 diff 结果形态

段内 diff 产出三种 segment：

- `EQUAL(text)`
- `DELETE(text)`
- `INSERT(text)`

第一版不额外建“REPLACE”段类型；替换在底层仍表现为：

- `DELETE(oldText)`
- `INSERT(newText)`

### 6.3 算法选择

第一版推荐使用简单、确定性的 LCS/动态规划方案，而不是引入更复杂的 Myers 优化实现。

理由：

- 先保证结果正确与可读
- 便于测试与调试
- 第一版目标文档规模有限时，性能足够

如果后续在真实文档上发现性能瓶颈，再单独优化。

## 7. 修订重放策略

### 7.1 对“已对齐且支持”的差异段落

对每个差异段落：

1. 保留旧段落对象本身与段落级属性（heading / alignment / indent / line spacing / list）
2. 清空该段落当前全部 inline 内容
3. 按 diff segment 顺序重放：
   - `EQUAL(text)` → `addRun(text)`
   - `DELETE(text)` → 先 `addRun(text)`，再 `addDeletion(author, thatRun)`
   - `INSERT(text)` → `addInsertion(author, text)`

核心含义：

- **段落容器保留**
- **内容重建**
- **run 级样式丢失**
- **修订结构正确**

### 7.2 对“整段删除”

整段删除沿用旧段落容器，清空其 inline 内容后，重建为一条或多条删除段。

MVP 推荐最简单语义：

- 用一条纯文本 run 承载整段旧文本
- 将该 run 标记为 tracked deletion

不再尝试保留原 run 分段。

### 7.3 对“整段插入”

整段插入需要在结果文档中新增段落，再把整段文本写成 insertion。

推荐策略：

- 在结果文档中定位插入锚点（旧文档中的后继段落；没有后继则末尾追加）
- 创建新段落
- 浅复制新段落的**段落级属性**（若现有 public API 能表达）
- 将整段文本写成 insertion

这里复制的是段落级属性，不是 run 级样式。

### 7.4 不使用 `replaceTracked`

虽然现有 `Run.replaceTracked(...)` 可表达“删除旧 run + 插入新文本”，但 compare MVP 不以它为主路径。

原因：

- 它以“已有 run”为中心
- compare MVP 对差异段落采用“清空后重建”
- 用 `DELETE + INSERT` 直接重放更简单、更统一

`replaceTracked` 仍保留给人工/Agent 精确编辑场景。

## 8. 段落插入位置与 body 顺序

虽然 compare 只对正文段落做分析，但结果文档里的新段落仍必须正确落在旧文档 body 顺序中。

推荐做法：

- 以“旧文档中后继段落”的 body 位置为锚点
- 调用 `Document.insertParagraph(bodyIndex)` 在该锚点前插入
- 若没有后继锚点，则 `addParagraph()` 追加到文末

这样即使旧文档中夹着表格，新增段落也能落在正确的 body 区间，而不需要 compare 表格本身。

## 9. 错误与边界行为

### 9.1 参数与输入

- 文件打不开 → 复用 `Docx.open(...)` 的异常契约
- `author == null` / 空白 → `IllegalArgumentException`

### 9.2 不支持结构

第一版对不支持结构**不抛整文档异常**。策略是：

- 保留旧文档原样
- 不为该处生成修订

这是“部分结果优先”的策略。其代价是：结果文档对这类差异**不完整**。因此必须通过：

- README
- Javadoc
- 测试命名

明确告诉用户边界。

### 9.3 公共异常风格

compare 作为 `Docx` 的公开 API，仍遵守现有异常约定：

- IO / 打开失败 → `DocxIOException`
- 非有效 docx → `DocxFormatException`
- compare 内部语义错误 → `DocxOperationException`（如后续需要）

不把 POI 异常直接暴露到 compare 公开表面。

## 10. 测试设计

第一版至少覆盖：

1. 两份纯文本 docx，无差异
2. 单段内字符级插入
3. 单段内字符级删除
4. 单段内字符级替换（底层应读回相邻 del + ins）
5. 中间插入整段，不导致后续全部错位
6. 中间删除整段，不导致后续全部错位
7. old 基线表格存在但不参与 compare，结果保留旧表格原样
8. 含超链接/图片的差异段落被跳过，结果保留旧段落原样
9. 默认 author 与显式 author
10. reopen 后 `trackedChanges().list()` 能读回预期修订

验证重点不是字符串，而是：

- 结果 `Document` 可保存并重新打开
- `TrackedChanges.list()` 的 type / author / details 数量符合预期

## 11. 风险观察点

第一版最重要的风险不是“算法写不出来”，而是“用户以为它比实际支持得更多”。

因此要持续守住三条：

1. **只承诺纯文本 run 段落**
2. **差异段落重建后不保 run 样式**
3. **不支持结构保留旧文档原样，不伪装成已比较**

## 12. 当前设计结论

第一版 compare 采取以下组合：

- 入口：`Docx.compare(...)`
- 结果基线：旧文档深拷贝
- 段落对齐：顺序保持的精确文本锚点
- 段内 diff：code point 级字符 diff
- 修订重放：清空差异段落内容后按 `run / ins / del` 重建
- 样式策略：不保 run 级样式；整段插入可浅复制段落级属性
- 不支持结构：保留旧文档原样
- 默认作者：`nondocx compare`

这是一个明确偏向“先交付可用 compare 主路径”的设计，而不是追求一次到位的 Word 级全量保真。
