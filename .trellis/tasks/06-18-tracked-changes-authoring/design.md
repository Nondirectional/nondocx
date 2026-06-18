# Design — tracked changes 创作侧显式 API

> 配套 `prd.md`。本文记录文本类 tracked authoring 的技术设计：API 落点、返回值语义、OOXML 元数据写入与与只读模型的衔接。

## 1. 设计目标

本子任务的重点不是“让所有现有写 API 自动带修订”，而是建立一组**显式、可预期、不会污染现有普通写语义**的文本类 tracked authoring 能力。

需要定清的关键点：

1. 方法住在哪里
2. 每个方法返回什么，避免 live wrapper 语义混乱
3. 写入哪些 OOXML 修订元数据
4. 写出的节点如何被 read 子任务统一读回

## 2. 三层映射

### 2.1 OOXML 层

文本类创作主要对应：

- `<w:ins>` —— tracked insertion
- `<w:del>` —— tracked deletion
- replacement —— 不是独立元素，而是“删除旧内容 + 插入新内容”的组合

这些节点通常携带：

- `w:id`
- `w:author`
- `w:date`

对于删除文本，还需要注意正文文本与 `w:delText` 的差异。

### 2.2 POI 层

POI 不会因为打开 `<w:trackChanges/>` 就自动把普通写操作变成 `ins` / `del`。实现必须：

- 显式创建修订节点
- 显式写 author / date / 原始 `w:id`
- 必要时将现有 `CTR` / 文本节点重挂到修订容器下

### 2.3 nondocx 层

nondocx 在此子任务中的责任是：

- 提供显式 authoring API，而不是隐式 magic
- 让 tracked 写路径与普通写路径共存且互不污染
- 在返回值设计上避免让调用方持有“语义上已失效”的 wrapper

## 3. 公共 API 形态

### 3.1 方法落点

已定：创作侧方法住在内容所属类型上。

当前推荐的最小集合为：

- `Paragraph.addInsertion(String author, String text)`
- `Paragraph.addDeletion(String author, Run target)`
- `Run.replaceTracked(String author, String newText)`

### 3.2 返回值语义（已定推荐）

当前推荐如下：

- `addInsertion(...)` → 返回**新插入的 `Run`**
- `addDeletion(...)` → 返回 **`Paragraph`**（或同等“容器级 fluent 句柄”），**不返回原 `Run`**
- `replaceTracked(...)` → 返回**新插入的 replacement `Run`**

理由：

1. insertion 天然会产生一个新的可继续操作的 run
2. deletion 会把既有 run 迁入 `<w:del>` 语义路径，继续把原 `Run` 暴露给调用方容易制造“它还是稳定 live wrapper”的错觉
3. replacement 的后续链式操作大多数会落在“新文本”上，因此返回新的 insertion run 最符合直觉

## 4. 写入语义

### 4.1 tracked insertion

`Paragraph.addInsertion(author, text)` 的目标是：

- 创建一个新的 insertion 修订节点
- 在其中创建承载新文本的 run
- 返回该新 run 的 nondocx wrapper

默认推荐：

- 新 insertion run 按普通新 run 语义起步
- 不自动复制外部 run 的样式（因为它并不依附已有源 run）

### 4.2 tracked deletion

`Paragraph.addDeletion(author, target)` 的目标是：

- 将现有目标 run 显式标记为删除
- 让其从“普通正文 run”语义切换为“tracked deletion 内容”

由于这个操作会改变既有 run 的结构归属，当前推荐不返回原 `Run`，避免调用方继续把它当成普通 live run 使用。

### 4.3 tracked replacement

`Run.replaceTracked(author, newText)` 的目标是：

- 将当前 run 作为 deletion 部分
- 在其后创建 insertion 部分
- 返回“新插入的新文本 run”

默认推荐：

- replacement 生成的 insertion run 复制源 run 的文本样式，以更贴近“替换文本但保留格式”的用户直觉
- 若后续实现证明样式复制带来意外复杂度，应先回 design，再调整契约

## 5. 修订元数据

### 5.1 author

- `author` 由调用者显式传入
- `author == null` / 空白字符串 → `IllegalArgumentException`

### 5.2 date

- `date` 由 nondocx 自动生成
- 当前契约只要求写出合法、稳定的 OOXML 日期时间值
- 对外不承诺某个特定字符串格式作为公共 API 契约

### 5.3 原始 `w:id`

- authoring 子任务负责生成底层 OOXML 修订 `w:id`
- 该 `w:id` 是写入文档的原始修订元数据
- 它**不等于** read 子任务对外暴露的 nondocx 稳定 id

这两层 id 必须明确区分：

- 写侧：底层 OOXML `w:id`
- 读侧：nondocx 稳定引用 id

## 6. 与 tracked changes 开关的关系

已定：显式 tracked authoring 与 `<w:trackChanges/>` 开关**正交**。

也就是说：

- 普通写 API 不会因为开关开启而自动生成修订
- 显式 tracked API 也不依赖开关开启才能写出 `ins` / `del`

开关的意义是文档设置层的“记录修订模式”；
显式 tracked authoring 的意义是“nondocx 主动写出修订节点”。

## 7. 与 read 子任务的衔接

本子任务写出的文本类修订，最终应满足：

- `TrackedChanges.list()` 能读回
- `type` / `family` / `details()` / `location` 能与统一模型对齐
- 后续 `accept/reject` 子任务能按稳定 id 命中这些修订

因此实现时必须避免把“写侧便捷策略”直接硬编码成 read 公共契约。

## 8. 风险观察点

- 返回原 `Run` 容易让调用方误判 deletion 后的 live wrapper 语义
- replacement 的样式复制看似友好，但若底层结构不稳，会牵动 API 契约
- 底层原始 `w:id` 与 read 稳定 id 的概念混淆，是最容易埋雷的地方之一

## 9. 当前仍待收敛的问题

当前子任务级设计问题已按推荐方案收敛完成；若 replacement 的样式复制在实现中被证明不可稳定支撑，再回 planning 调整，而不是在实现中静默降级。