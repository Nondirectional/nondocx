# Design — tracked changes accept/reject 文本类

> 配套 `prd.md`。本文记录文本类 tracked changes 的 accept / reject 设计：统一门面、OOXML 手术语义、失败契约与与后续高级类型的边界。

## 1. 设计目标

本子任务的核心不是“再发明一套修订模型”，而是在父任务和 read 子任务已确定的统一模型之上，先让 `ins` / `del` 两类文本修订具备可靠的**破坏性应用能力**。

本子任务重点定清：

1. 哪些入口方法属于文本类 accept / reject
2. `ins` / `del` 在 OOXML 层的 accept / reject 分别是什么意思
3. 单条 / byAuthor / all 三种粒度如何落在统一门面上
4. 对非文本类修订保持什么边界行为

## 2. 三层映射

### 2.1 OOXML 层

文本类 tracked changes 的核心节点是：

- `<w:ins>` —— 被插入并处于“待接受/拒绝”状态的内容
- `<w:del>` —— 被删除但仍保留在修订记录中的内容

accept / reject 的语义是：

- **accept `ins`**：内容成为正文的一部分，修订包装被移除
- **reject `ins`**：整段插入内容被丢弃
- **accept `del`**：删除生效，因此被删内容从文档中移除
- **reject `del`**：删除被撤销，因此原内容回到正文中

### 2.2 POI 层

POI 不提供“accept revision / reject revision”的高层 API。实现需要：

- 通过 `XWPFDocument` / `CT*` 找到目标修订节点
- 对 `ins` / `del` 做 CT 级节点重挂、删除或文本节点类型修正
- 在必要时处理 `w:delText` 与普通 `w:t` 的转换

### 2.3 nondocx 层

nondocx 在此子任务中的责任是：

- 沿用统一 `TrackedChanges` 门面，不新增第二套写入口
- 沿用 read 子任务的稳定 id，而不是重新发明“写侧 id”
- 将所有 CT 手术封装到 `internal/poi/`，公共 API 仍保持 POI-free

## 3. 公共 API 形态

### 3.1 门面入口

当前推荐沿用统一门面，在 `TrackedChanges` 上提供：

- `acceptAll()`
- `rejectAll()`
- `acceptByAuthor(String author)`
- `rejectByAuthor(String author)`
- `accept(String id)`
- `reject(String id)`

### 3.2 为什么仍放在门面上

原因很简单：

- accept / reject 会直接改文档结构
- 操作后，原列表中的多个 `TrackedChange` 快照都可能失效
- 因此不适合把写方法挂到单个 `TrackedChange` 值对象上

门面是唯一能对“文档当前状态”负责的地方。

## 4. 文本类 CT 手术语义

### 4.1 `ins`

- **accept**：拆除 `<w:ins>` 包装，保留其内部正文内容
- **reject**：移除整个 `<w:ins>` 子树

### 4.2 `del`

- **accept**：移除整个 `<w:del>` 子树
- **reject**：拆除 `<w:del>` 包装，并将内部删除文本恢复为普通正文文本

### 4.3 表格中的文本类修订

若 `ins` / `del` 出现在表格单元格内部的段落 / run 路径中，本子任务仍负责处理，前提是其本质仍属于文本类修订，而不是 `cellIns` / `cellDel` 结构型修订。

## 5. 粒度语义

### 5.1 all

- `acceptAll()` / `rejectAll()` 作用于当前文档中的全部文本类修订
- 不要求对高级类型生效

### 5.2 byAuthor

当前推荐：

- 作者匹配采用**大小写敏感的精确字符串匹配**
- `author == null` 或空白字符串属于非法参数

原因：避免在第一版引入额外的大小写折叠、trim 归一化与区域性规则。

### 5.3 单条 id

- `accept(id)` / `reject(id)` 的入参是 read 子任务定义的 nondocx 稳定 id
- 若该 id 未命中，抛 `NoSuchElementException`
- 若该 id 命中的是当前任务范围外的高级类型修订，实现阶段可先按 `UnsupportedFeatureException` 处理，待 advanced-types 子任务补齐

## 6. 失败契约

### 6.1 参数非法

推荐：

- `id == null` / 空白 → `IllegalArgumentException`
- `author == null` / 空白 → `IllegalArgumentException`

### 6.2 命中失败

- 稳定 id 不存在 → `NoSuchElementException`

### 6.3 当前任务范围外的修订类型

- 若门面已经能解析到非文本类修订，但本子任务尚未支持其写语义：推荐抛 `UnsupportedFeatureException`
- 这只是子任务阶段性边界，不是最终父任务能力边界

## 7. 与 read / advanced-types 的边界

### 7.1 与 read 子任务

- read 负责：稳定 id、`TrackedChange` 顶层模型、`location`、`details()` 分工
- 本子任务负责：在此基础上增加文本类破坏性写操作

### 7.2 与 advanced-types 子任务

- advanced-types 负责补齐 move / 属性类 / `cellIns` / `cellDel` 的写语义
- 本子任务不应为这些高级类型提前引入特化模型，避免把边界写坏

## 8. 测试关注点

最关键的测试不只是“调用成功”，而是调用后文档语义正确：

- accept 插入后，插入内容保留但修订包装消失
- reject 插入后，插入内容彻底消失
- accept 删除后，被删内容彻底消失
- reject 删除后，被删内容恢复为正文文本
- byAuthor 仅影响目标作者
- 单条 id 只影响目标修订

## 9. 当前仍待收敛的问题

当前子任务级设计问题已按推荐方案收敛完成；后续若实现中发现文本类 accept / reject 与高级类型边界重新耦合，再回 planning 更新。