# Design — tracked changes 只读消费侧

> 配套 `prd.md`。本文记录该子任务的技术设计：统一门面、只读范围、数据模型、位置模型与行为契约。

## 1. 设计目标

本子任务的目标不是一次性完成全部 tracked changes 能力，而是先建立一个**最小、稳定、可扩展**的只读消费侧底座：

- 读取 tracked changes 开关状态
- 按文档顺序枚举修订
- 通过稳定 id 获取单条修订

设计重点是把下面几个契约先定稳：

1. 文档级统一入口
2. `TrackedChange` 顶层模型与 `details()` 分工
3. `TrackedChangeLocation` 的 path / segment 结构
4. `get(id)` / `list()` 的只读行为语义

## 2. 三层映射

### 2.1 OOXML 层

tracked changes 只读消费侧需要同时面对两类 XML：

- `word/settings.xml`
  - `<w:trackChanges/>`：文档是否开启修订记录
- `word/document.xml`
  - 文本类修订：`<w:ins>` / `<w:del>`
  - 高级类型：`moveFrom` / `moveTo` / `*PrChange` / `cellIns` / `cellDel`

### 2.2 POI 层

POI 没有完整的 tracked changes 高层 API；实现将依赖：

- `XWPFDocument` 提供文档级入口
- `CTSettings` / 相关 settings 访问路径读取 `<w:trackChanges/>`
- `CT*` XmlBeans 节点枚举正文中的各种修订节点

### 2.3 nondocx 层

nondocx 在此子任务中的责任是：

- 提供 POI-free 的统一只读门面
- 将底层节点解析为 `TrackedChange` 统一外壳
- 将文本 / 属性 / move / cell 差异放进 `details()`
- 将位置以结构化 path / segment 值对象暴露，而不是泄漏 CT 路径字符串

## 3. 公共 API 形态

### 3.1 文档级统一入口

当前已定：

- `Document.trackedChanges()`

返回一个统一门面对象 `TrackedChanges`。

### 3.2 门面职责

当前只读子任务中，`TrackedChanges` 负责：

- `enabled()` —— 读取 tracked changes 开关状态
- `list()` —— 返回按文档顺序排列的全部修订
- `get(String id)` —— 按稳定 id 获取单条修订

当前子任务**不负责**：

- 开关写入 / 修改
- `accept` / `reject`
- 便利筛选（`listByType` / `listByFamily` / `listByAuthor`）

第一版交付重点仍是稳定打通门面、开关读取、稳定 id 与位置模型；若高级类型在本子任务阶段尚未完整建模，由 `advanced-types` 子任务继续在同一 `TrackedChanges` 入口上补齐，而不是另起第二套读取 API。

### 3.3 命名风格

已定采用最简风格：

- `Document.trackedChanges()`
- `TrackedChanges.enabled()`
- `TrackedChanges.list()`
- `TrackedChanges.get(id)`

理由：与现有 `Document` 表面风格一致，避免把门面方法命名得过度冗长。

## 4. `TrackedChange` 顶层模型

### 4.1 已定方向

遵循父任务决策：

- `TrackedChange` 使用**统一外壳 + `details()`**
- `type` 使用**细粒度 OOXML kind**
- 同时提供粗粒度 `family`

### 4.2 包装形态 —— holding-wrapper 持 CT 节点（已定）

`TrackedChange` 采用 **holding-wrapper** 形态，**持有底层 CT 节点**，`raw()` 返回该 CT 节点。

这是已确认决策（非备选）。它与 nondocx 现有先例一致，而**不是**像 TOC（`poi-bridge.md` N11）那样的 Rule 1 偏差：

- `Section` 已经是先例：它持有 `(XWPFDocument document, CTSectPr delegate)`，`raw()` 返回 `CTSectPr`（一个 XmlBeans CT 类型，不是 `XWPF*`）。这与「`raw()` 是唯一允许出现 POI / XmlBeans 类型的签名」一致（`poi-bridge.md` Rule 3 + N1 的「内部接缝」例外）。
- tracked changes 同样没有 POI 高层 `XWPFTrackedChange` 类型，但**有干净的 per-revision CT 句柄**：文本类修订的 `<w:ins>` / `<w:del>` / `<w:moveFrom>` / `<w:moveTo>` 在精简 schema 下统一由 `CTRunTrackChange` 承载（`org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRunTrackChange`，POI 5.2.5 lite schema 已含该类型）。
- `CTRunTrackChange extends CTTrackChange`：`CTTrackChange` 提供 `author` / `date` / `id`；`CTRunTrackChange` 提供被修订内容（如 `getRList()` 返回插入/删除的 `CTR`）。

因此建模形态为：

- `TrackedChanges` 门面持有 `final XWPFDocument delegate`（同 `TableOfContents`），`enabled()` / `list()` / `get(id)` 都穿透到委托。
- `TrackedChange` 持有 `final CTRunTrackChange delegate`（或更宽的 `CTTrackChange`，若某类修订无 run 子节点），`raw()` 返回该 CT 节点。
- 构造函数作为「内部接缝」接受 CT 类型（与 `Section(XWPFDocument, CTSectPr)` 同构），在 Javadoc 注明。
- `equals` / `hashCode` 比较内容派生值（`type` / `family` / `location` / `details` 等公共 getter），不比较 CT 节点引用（`quality-guidelines.md` Rule 2 + `poi-bridge.md` N7）。

**与 TOC 的关键区别**：TOC 是 Rule 1 偏差（没有干净 per-entry POI 句柄、条目是缓存快照）；tracked changes **有**干净的 per-revision CT 节点，因此走 holding-wrapper，不走不可变解析值。

### 4.3 顶层字段职责

当前已定的顶层字段职责为：

- `id` —— nondocx 对外稳定 id（见 §4.5）
- `author` —— 修订作者（派生自 `CTTrackChange.getAuthor()`）
- `date` —— 修订时间（派生自 `CTTrackChange.getDate()`）
- `type` —— 具体修订 kind（如 `INS` / `DEL` / `RPR_CHANGE`）；由节点 OOXML 标签名决定
- `family` —— 粗粒度分组（如 `TEXT` / `PROPERTY` / `MOVE` / `CELL`）
- `location` —— 结构化位置值对象
- `details()` —— 具体 payload 语义

### 4.4 `details()` 分工

当前最小 details 族预期为：

- `TextChangeDetails`
- `PropertyChangeDetails`
- `MoveChangeDetails`
- `CellChangeDetails`

其中：

- `location` 负责回答“这个修订挂在文档哪里”
- `details()` 负责回答“这个修订改了什么”

对于属性类修订，已定：

- `location` 只表达结构位置
- 属性目标（如 `rPr` / `pPr` / `sectPr`）由 `PropertyChangeDetails` 表达

### 4.5 稳定 id 策略 —— 进程内稳定（已定）

`TrackedChange.id` 采用**混合版稳定 id 策略**，且「稳定」的边界已定为**进程内稳定**。

含义是：

- 对外暴露的是 nondocx 自己定义的稳定 id
- 公共契约**不直接等同于** OOXML 原始 `w:id`
- 内部生成时允许组合底层节点信息参与稳定化，例如：
  - `type` / kind
  - 结构化 `location`
  - 原始 `w:id`（若存在）

**稳定边界（决策 B）**：

- ✅ **进程内稳定**：对同一文档对象、在同一次 `Document` 会话内，对同一修订多次调用 `trackedChanges().list()` / `get(id)`，返回的 id 字符串相等，可被后续 `accept` / `reject` 子任务在同会话内复用。
- ❌ **不承诺跨 save/reopen 稳定**：文档 `save()` 后重新 `Docx.open()` 得到新 `Document` 实例时，**同一逻辑修订的 id 可能改变**。原因是 id 内部组合了 `location`（基于文档顺序索引），而文档往返可能改变结构顺序或 POI 的 `w:id` 分配。

这一边界的依据：

1. OOXML 的 `w:id` 本身只在**单次文档保存**内有意义，跨工具 / 跨往返不保证稳定。
2. 后续 `accept` / `reject` 子任务的典型流程是「开文档 → 读修订 → 对某条 accept/reject → 存盘」，整个会话在**同一 `Document` 实例**上完成，进程内稳定即可覆盖。
3. 若强行承诺跨往返稳定，必须以 `w:id` 为唯一来源，会与「高级类型无干净 `w:id`」「保留建模弹性」冲突。

**字符串格式不作为公共契约**：

- `TrackedChange.id()` 对外是不透明引用标识
- 调用者不应依赖其字符串可读结构、长度、前缀
- 真正用于理解与调试的主要信息应来自 `type` / `family` / `location` / `details()`

实现层面（Step 4）的要点：

- id 生成在 `internal/poi/` 下集中实现，公共 API 不暴露生成细节
- `TrackedChanges` 门面在**同一次 `list()` 调用内**为每条修订生成 id，并支持 `get(id)` 反查（门面可在字段里维护「本次会话的 id → CT 节点」映射，该映射属于进程内状态，不违反 holding-wrapper / 无快照精神——它不缓存修订内容，只缓存 id→节点引用）

## 5. `TrackedChangeLocation` 设计

### 5.1 已定方向

`TrackedChange.location` 第一版采用：

- **细粒度定位**
- **path / segment 层级结构**

而不是：

- 简单字符串摘要
- 固定字段平铺结构

### 5.2 为什么是 path / segment

OOXML 与 POI 中的位置天然是层级导航：

- 文档正文顺序
- 表格 / 行 / 单元格层级
- 段落 / run 层级

因此位置模型也应诚实表达这种嵌套结构，而不是把一组可空字段平铺成“伪简单对象”。

### 5.3 结构职责

`TrackedChangeLocation` 负责：

- 暴露可程序化消费的层级位置
- 支撑测试断言
- 为高级类型扩展预留空间

它可以提供：

- `segments()` 或同等能力
- `toString()` / 摘要显示

但字符串显示**不是**稳定公共契约。

### 5.4 segment 的职责边界

segment 负责结构层级。当前第一版的**最小集合已固定**为：

- body
- paragraph
- table
- row
- cell
- run

这样正好覆盖 read MVP 需要的正文顺序、表格层级与 run 级定位，同时避免把 section / property 等更高复杂度语义过早塞进位置模型。

但 segment 不负责承载属性变更目标；属性目标留给 `details()`。

### 5.5 segment 公共模型形态

当前已定：segment 公共模型采用**通用 `kind + index` 结构**。

推荐形态：

- `TrackedChangeLocation` 持有一条有序 path
- path 中的每个 segment 至少包含：
  - `kind` —— 一个结构段枚举（`BODY` / `PARAGRAPH` / `TABLE` / `ROW` / `CELL` / `RUN`）
  - `index` —— 该层级中的 0-based 索引

选择该方案而非多个专用 segment 值对象的原因：

1. 这六类 segment 当前共享相同的核心信息结构
2. Java 11 下通用结构更轻量，更利于比较、测试与序列化
3. path 的遍历、显示、断言逻辑更统一
4. 后续若新增 segment kind，扩展枚举即可，不必同步膨胀类型数量

## 6. 行为契约

### 6.1 `enabled()`

- 只读读取 tracked changes 开关状态
- 不产生任何写副作用
- 不负责开关写入

### 6.2 `list()`

- 返回全部修订
- 顺序按**文档出现顺序**；第一版至少稳定覆盖文本类修订，高级类型可由 `advanced-types` 子任务继续补齐
- 不按作者、类型或 id 重排

### 6.3 `get(id)`

- 入参 `id` 是 nondocx 对外稳定 id
- 用于精确定位单条修订
- 若未命中，抛 `NoSuchElementException`

这里的语义被视为“命中式访问”，而不是“可选 singleton 读取”。

## 7. 只读边界

本子任务刻意保持只读：

- 读取 `<w:trackChanges/>`
- 枚举并建模修订
- 不修改 `settings.xml`
- 不修改 `document.xml`

这与父任务的“读写分离”原则一致：

- 读路径只负责诚实暴露状态
- 写路径与 accept/reject 路径留给后续子任务

## 8. 与后续子任务的衔接

该子任务交付后，应为后续子任务提供稳定基础：

- `accept/reject` 子任务复用 `id`（**同会话内**——见 §4.5，跨 save/reopen 不保证稳定）
- authoring 子任务复用 `TrackedChange` / `type` / `family` / `details` 顶层约束
- advanced-types 子任务在不推翻 location / details 分工的前提下补齐高级类型

## 9. 当前仍待收敛的问题

子任务级契约问题已全部收敛：

- ✅ 包装形态：holding-wrapper 持 CT 节点（§4.2）
- ✅ 稳定 id 边界：进程内稳定（§4.5）
- ✅ 高级类型节点处理：实现时按复杂度判断（implement.md Step 5 已留弹性条款）

实现阶段唯一仍可能触发回 planning 的点：高级类型节点（`*PrChange` / `cellIns` 等）在 `list()` 中若需独立处理，可能需要回到 `advanced-types` 子任务统一规划，而非在本子任务硬塞。
