# Design — compare batch 2 样式保真增强

> 配套 `prd.md`。本文记录 compare 第二批升级的技术设计。第二批不再扩正文结构范围，而是收口到一个更窄、更可控的目标：在现有 compare 语义不变的前提下，为“简单纯文本段落”补上 run 级可见样式保真。

## 1. 设计目标

第二批要解决的是：

1. 继续以**旧文档**为 compare 结果基线
2. 保持 compare 仍输出标准 tracked changes（`<w:ins>` / `<w:del>`）
3. 对“可归约成单一样式的纯文本段落”，让修订结果保住 run 级可见字符样式
4. 不引入新的公开 API 对象，不改变默认 compare 调用方式
5. 对复杂混排与复杂 inline 结构继续保持诚实边界

第二批明确**不**解决：

- 超链接段落 compare
- field 段落 compare
- 图片、表格、页眉页脚、批注、分节 compare
- 纯样式变化 compare（即不生成 `rPrChange`）
- 段落级格式 compare（`w:pPr`）
- 原始 run 分段边界保真

## 2. 三层映射

### 2.1 OOXML 层

这批工作的关键 OOXML 事实有两条：

1. 文本修订仍然是：
   - 插入：`<w:ins>`
   - 删除：`<w:del>`
   - 替换：底层仍是 `del + ins`
2. run 级可见字符样式在 `<w:rPr>`，而不是在段落 `<w:p>` 本身

因此第二批不是去创造新的修订元素，而是在现有 `<w:ins>` / `<w:del>` 内部生成的 `<w:r>` 上，补齐合适的 `<w:rPr>`。

这也决定了边界：

- 如果段落能被归约成“同一种 `w:rPr` 语义”，则即使物理上有多个 `w:r`，也仍可安全视为简单场景
- 如果段落内部存在多个不同 `w:rPr` 片段，则当前 compare 的纯文本 diff 结果无法稳定映射回原始样式区间，应继续跳过

### 2.2 POI 层

POI 没有“按 diff 结果保留 run 样式”的 compare API。

但当前仓库已经有两块关键能力：

- `Run.style()`：读取 run 的六种可见字符样式快照
- `Run.replaceTracked(...)`：已证明“文本修订 + 样式复制”这条路在 tracked changes 里可行

第二批不会引入新的底层 OOXML 类型，而是复用这些现有能力，做：

1. 识别段落能否归约成单一样式
2. 取得旧段 / 新段各自的样式快照
3. 在重放 `EQUAL / DEL / INS` 片段时，把对应样式复制到新建 run

### 2.3 nondocx 层

第二批 compare 的职责是：

- 保持现有 `Docx.compare(...)` API 不变
- 在默认 compare 语义内增强“简单段落”的可读性
- 对外明确暴露“哪些段落会保样式，哪些仍跳过”

第二批 compare **不**负责：

- 让调用方配置不同的 compare 策略
- 在静默跳过之外新增 runtime report
- 把纯样式变化伪装成文本变化

## 3. 公共 API 形态

### 3.1 不新增公开入口

第二批继续使用：

```java
Document result = Docx.compare(oldPath, newPath);
Document result = Docx.compare(oldPath, newPath, author);
```

不新增：

- `CompareOptions`
- `compare(..., strategy)`
- `compareWithReport(...)`

原因不是“以后永远不需要”，而是当前已收敛出的增强项都属于默认语义质量提升，不需要把选择权暴露给调用方。

### 3.2 对外契约需要更新

第二批必须更新 Javadoc / 示例 / 文档，明确说明：

- compare 仍以旧文档为基线
- 纯样式变化仍视为无差异
- 简单纯文本段落支持 run 级样式保真
- 复杂混排与复杂 inline 结构仍静默跳过

## 4. 第二批支持范围

### 4.1 支持段落：单一样式纯文本段落

第二批只支持：

- `inlineElements()` 全部是 `Run`
- 不含 `Hyperlink`
- 不含 `Image`
- 不含 field 相关结构
- 旧段和新段都能归约成单一 `Run.style()`

“单一样式”定义为：

- 允许有多个连续 run
- 只要这些 run 的 `Run.style()` 完全相同，就视为同一种样式语义
- 不承诺保留原始 run 边界，只承诺保留视觉样式语义

### 4.2 不支持段落：复杂混排 / 复杂结构

以下场景继续不支持：

- 一个段落内有多种不同 `Run.style()`
- `Hyperlink`
- 图片
- field
- 其它非 `Run` inline 元素

若这类段落文本无变化：

- 保持旧段落原样

若这类段落文本有变化：

- 继续整段跳过 compare
- 不做“文本对但样式丢失”的降级结果
- 不额外抛异常或发 runtime report

## 5. 样式语义设计

### 5.1 只覆盖 run 级六样式

第二批只保以下六种 run 级可见字符样式：

- `bold`
- `italic`
- `underline`
- `font`
- `fontSize`
- `color`

对应 OOXML：

- `w:rPr`

明确不覆盖：

- `w:pPr` 下的对齐、缩进、列表、段落底纹
- 更细粒度的其它 run 属性

### 5.2 纯样式变化仍视为无差异

若：

- 两侧段落文本完全相同
- 只有 `Run.style()` 不同
- 本批又不输出 `rPrChange`

则仍视为：

- 无差异

这条语义保证 compare 不会把“样式变化”伪装成文本变化。

### 5.3 差异片段的样式来源

对一个支持段落，若文本差异被重放成：

- `EQUAL`
- `DEL`
- `INS`

则样式来源固定为：

- `EQUAL`：旧文档样式
- `DEL`：旧文档样式
- `INS`：新文档样式

对于替换：

- 仍是 `DEL + INS`
- 删除片段看旧样式
- 插入片段看新样式

对于整段新增 / 整段删除：

- 整段 insertion 看新版样式
- 整段 deletion 看旧版样式

## 6. 样式保真与 run 分段

第二批的目标是“视觉语义保真”，不是“物理 run 边界保真”。

这意味着：

- 如果一个段落原本有多个样式完全相同的 run
- compare 结果允许把它们重建成更少的 run，甚至一个 run
- 只要最终 `Run.style()` 语义与预期一致，即视为满足目标

原因：

- 当前 compare 仍是基于 `Paragraph.text()` 做 diff
- 仓库里没有“字符区间 ↔ 原始 run 分段”的现成基础设施
- 若把 run 边界保真也纳入本批，就会把任务升级成 run 片段级 compare

## 7. 实现路线

### 7.1 不改对齐层，先改支持判定层

段落序列对齐仍沿用第一版：

- 基于 `Paragraph.text()` 的顺序保持 LCS 锚点

第二批的主要变化不在“段落怎么对齐”，而在：

- 一个差异段落是否支持样式保真
- 若支持，如何把旧/新样式注入 `EQUAL / DEL / INS`

### 7.2 引入“可归约单一样式”判定

推荐在 compare 支撑层新增类似内部概念：

- `UniformParagraphStyle`
- 或 `CollapsedRunStyle`

职责：

1. 遍历段落 `runs()`
2. 校验是否全部是纯文本 run
3. 校验每个 run 的 `Run.style()` 是否完全一致
4. 返回统一样式快照

若失败，则该段落不进入样式保真路径。

### 7.3 支持段落的重放路径

对支持段落：

1. 旧段拿到 `oldStyle`
2. 新段拿到 `newStyle`
3. 清空目标段 inline 内容
4. 重放片段：
   - `EQUAL(text)` → `addRun(text)` 并应用 `oldStyle`
   - `DEL(text)` → `addRun(text)` 并应用 `oldStyle`，再 `addDeletion(...)`
   - `INS(text)` → `addInsertion(...)` 后对返回 run 应用 `newStyle`

这样满足我们已经确认的默认语义：

- 旧基线
- 删除看旧
- 插入看新
- 未改看旧

### 7.4 不支持段落继续走旧边界

对不支持段落：

- 当前无差异 → 原样保留
- 当前有差异 → 原样保留

第二批不引入“部分样式保真失败时回落到裸文本修订”的策略。

## 8. 测试设计

### 8.1 主要验证层

必须继续遵守仓库质量约定：

1. round-trip
2. `trackedChanges().list()` 读回
3. 必要时 POI / OOXML 交叉验证

### 8.2 新增关键测试类别

至少覆盖：

1. 单一样式段内插入，插入 run 保留新版样式
2. 单一样式段内删除，删除 run 保留旧版样式
3. 单一样式段内替换，`DEL` 看旧样式、`INS` 看新样式
4. 整段新增保留新版样式
5. 整段删除保留旧版样式
6. 多个同样式 run 仍视为可支持场景
7. 多 run 混合样式段落继续跳过
8. 文本相同仅样式不同，仍无修订
9. compare 结果 reopen 后样式仍可读回

### 8.3 契约验证

除代码测试外，还应验证：

- Javadoc 已写清支持边界
- example/文档已展示“简单段落支持、复杂段落跳过”

## 9. 风险与回退

### 9.1 主要风险

风险主要不在 tracked changes，而在“样式来源与段落可支持性判定”：

- 把多 run 同样式误判成复杂场景，会导致覆盖率过低
- 把复杂混排误判成简单场景，会输出半正确结果
- `addDeletion(...)` / `addInsertion(...)` 后样式应用顺序若不稳，可能导致最终 run 样式丢失

### 9.2 回退策略

若第二批实现过程中发现“同样式多 run 归约”判断不稳定，可临时收紧为：

- 仅支持单个 run 的纯文本段落

但这应被视为临时回退，不是首选设计。

若样式在 tracked deletion / insertion 上无法稳定落盘，应优先补 OOXML 断言与底层修复，而不是偷偷取消样式保真目标。

## 10. 后续里程碑（不纳入本批）

若第二批完成后要继续扩 compare，可按以下顺序开新任务：

1. 超链接段落 compare
2. field 段落 compare
3. 纯样式变化 compare（`rPrChange`）
4. 段落级格式 compare（`w:pPr`）
5. 多 run 混排样式保真

这些都不应混入本批实现。
