# Design — core 修订(tracked changes)封装（父任务）

> 配套 `prd.md`（需求层）。本文只记录**父任务级**技术设计：总体边界、父子任务契约、
> 跨子任务一致性、高风险点与收敛顺序。具体子任务的落地 API、CT 手术细节与测试夹具，应在各自任务下展开。

## 1. 设计目标

这个父任务现在不再承担“把所有 tracked changes 细节一次性定完”的职责，而是承担：

1. 固定全局设计方向
2. 拆清哪些能力可以独立实现 / 独立验收
3. 提前暴露跨子任务契约，避免后面各做各的
4. 把高风险问题收敛到明确的前置决策 / 研究项

## 2. 总体架构与边界

### 2.1 三层边界

- **OOXML 层**：tracked changes 是 `word/document.xml` 与 `settings.xml` 里的修订标记与开关。
  - 开关：`settings.xml` 中的 `<w:trackChanges/>`
  - 文本类修订：典型是 `<w:ins>` / `<w:del>` 包裹内容
  - 高级类型：`moveFrom` / `moveTo`、`*PrChange`、`cellIns` / `cellDel`
- **POI 层**：POI 提供 `XWPF*` 包装与底层 `CT*` XmlBeans 访问，但**没有完整的 tracked changes 高层 API**。
- **nondocx 层**：公共表面仍保持 POI-free；复杂 OOXML / CT 变换集中到 `internal/poi/`。

### 2.2 全局 API 方向（已定）

以下方向作为跨子任务契约固定下来：

1. **消费侧统一门面**：修订能力以文档级统一入口暴露，而不是散落到各个类型。
2. **创作侧显式方法**：被追踪的插入 / 删除 / 替换由拥有内容的类型显式提供方法；不开“自动录制”模式。
3. **兼容性优先**：旧 API 不偷偷改变含义，不让 `paragraph.text()` / `runs()` 变成“带修订语义”的新接口。
4. **POI 脏活下沉**：公共 API 不泄漏 `org.apache.poi.*`；CT 操作进入 `internal/poi/`。

## 3. 父 / 子任务契约

### 3.1 子任务职责划分

#### 子任务 A — `06-18-tracked-changes-read`

目标：建立最小、稳定的只读消费侧基础。

负责：
- 追踪开关的读取
- 修订枚举基础能力
- 修订列表的最小公共数据模型雏形

不负责：
- accept / reject
- 创作侧 API
- move / `*PrChange` / `cellIns` 等高风险高级类型
- 开关写入 / 修改

#### 子任务 B — `06-18-tracked-changes-accept-text`

目标：先把**文本类修订**的 accept / reject 跑通。

负责：
- `w:ins` / `w:del`
- 粒度：all / byAuthor / 单条
- 文本类修订的 CT 手术与回归测试

不负责：
- 创作侧 API
- move / 属性类 / 单元格类修订

#### 子任务 C — `06-18-tracked-changes-authoring`

目标：建立显式 tracked 写 API。

负责：
- 插入 / 删除 / 替换的写路径
- author / date / id 等元数据生成策略
- 与只读枚举的最小互通

不负责：
- 自动追踪所有既有写操作
- 高级修订类型的创建

#### 子任务 D — `06-18-tracked-changes-advanced-types`

目标：补齐高级修订类型。

负责：
- move
- `*PrChange`
- `cellIns` / `cellDel`
- 这些类型的枚举与 accept / reject 语义

前置条件：
- 必须先解决 `TrackedChange` 对非文本类修订的建模问题
- 必须先完成相关 OOXML / POI 研究与夹具策略

#### 子任务 E — `06-18-tracked-changes-docs-spec`

目标：把产品边界、示例、异常文案、spec 全部收尾。

负责：
- 规范文档同步
- unsupported 示例更新
- README / example / Javadoc 对齐
- 父任务最终集成验收支持

## 4. 跨子任务的固定契约

### 4.1 开关语义

- 修订开关只表示 `settings.xml` 中的 `<w:trackChanges/>`。
- **它不是自动追踪机制**。
- 也就是说：
  - 打开开关 ≠ nondocx 之后的普通写操作会自动生成修订
  - 创作侧 tracked API 仍然需要显式调用

### 4.2 旧 API 兼容性

- 现有 `Paragraph` / `Run` / `Document` 等已存在 API 的默认含义保持不变。
- 任何“修订中的文本 / 结构”都不应通过篡改旧 getter 的行为来暴露。
- 读取修订应通过专门的修订能力完成。

### 4.3 公共模型必须对用户诚实

这里的“诚实”指：

- 不把结构类修订伪装成纯文本替换
- 不让一个字段名暗示比实际更强的语义
- 不为了 API 看起来简单，就把高级 OOXML 差异全部压扁成难以解释的字符串

这条契约直接约束后面的 `TrackedChange` 设计。

### 4.4 文档顺序优先

若无更强反证，修订枚举顺序默认按**文档出现顺序**暴露，而不是按 `id`、作者或类型重排。这样最符合用户阅读与调试直觉。

### 4.5 错误处理

- 公共 API 继续按 `error-handling.md` 包装异常。
- “不支持的高级修订子类型”不能默默吞掉；需要在设计上先定义为：
  - 明确不支持并抛 `UnsupportedFeatureException`，或
  - 当前子任务范围内保证覆盖

## 5. `TrackedChange` 建模问题（当前核心开放点）

这是当前父任务最关键、也是进入高级类型前必须定下的问题。

### 5.1 为什么它是问题

对于文本类修订，用户很容易理解：

- `oldText` = 被删掉的文本
- `newText` = 新插入的文本

但对下列类型，这种直觉会失效：

- **属性变更**：变化的是 `w:rPr` / `w:pPr` / `w:sectPr`，不是正文文本
- **move**：本质上是“从哪移动到哪”的结构关系，不只是 old/new 文本
- **`cellIns` / `cellDel`**：作用对象是表格单元格结构，而不是单个 run 文本

如果还强行只给 `oldText/newText`，API 看起来简单，但语义会变假。

### 5.2 已选方向：统一基类 + 类型化 details

方向 B 已确认作为父任务级设计决策。

核心思路：

- `TrackedChange` 只承载所有修订共有的元数据
- 具体差异通过类型化 details 暴露
- 文本类、属性类、移动类、单元格类修订都以**诚实模型**表达，而不是强压成 `oldText/newText`

当前最小类型集合预期为：

- `TextChangeDetails`
- `PropertyChangeDetails`
- `MoveChangeDetails`
- `CellChangeDetails`

这样做的直接含义是：

- 文本类修订可以自然表达 before / after 文本
- 属性类修订可以表达“哪类属性发生变化”
- move 可以表达来源 / 目标关系
- cell 级修订可以表达结构作用域，而不是伪装成普通文本改动

### 5.3 已确认的推荐落点

当前推荐保持为：

1. **共有字段最小化**：`TrackedChange` 只放所有类型都稳定成立的字段
   - `id`
   - `author`
   - `date`
   - `type`
   - `location`
2. **差异信息下沉到 details**：不再把 `oldText/newText` 作为所有修订的统一核心字段
3. **公共模型优先诚实，再考虑便利**：宁可让高级类型多一个 details 对象，也不要用错误字段名制造假语义

这样能保证：

- 子任务 A 先把统一枚举外壳做起来
- 子任务 B / C 聚焦文本类 details
- 子任务 D 在不推翻前面模型的前提下扩展高级类型

### 5.4 已确认的公共表面形态

方向 B 的具体落点也已确定为：**方案 B2：统一外壳 + `details()` 判别联合**。

推荐形态：

- `TrackedChange` 作为统一公共对象
- `TrackedChangeDetails` 作为共享 details 接口（或同等共享父类型）
- 具体 details 子类型：
  - `TextChangeDetails`
  - `PropertyChangeDetails`
  - `MoveChangeDetails`
  - `CellChangeDetails`

这样做与现有 `BodyElement` / `InlineElement` 的共享类型模式更接近：

- 顶层列表与遍历保持统一
- 差异语义通过子类型承载
- Java 11 下不依赖 sealed class，也能保持 API 可扩展

### 5.5 已确认的类型粒度

`TrackedChange` 的类型信息确定为：**`type` 用细粒度 OOXML kind，同时提供派生的 `family`**。

推荐分工：

- `type()`：保留具体 kind，例如 `INS` / `DEL` / `MOVE_FROM` / `MOVE_TO` / `RPR_CHANGE`
- `family()`：提供较粗的用户侧分组，例如 `TEXT` / `MOVE` / `PROPERTY` / `CELL`
- `details()`：承载具体 payload 语义，而不是和 `type` / `family` 争夺职责

这样可以同时满足三件事：

1. **不丢底层事实**：OOXML 节点差异不会被过早压平
2. **筛选足够方便**：用户可以按 family 做粗筛，再按 type 做精筛
3. **高级类型可扩展**：后续补充新的 kind 时，不必推翻顶层模型

## 6. 高风险点与收敛策略

### 6.1 move 修订

风险：配对、范围、单条 accept/reject 语义可能比“同 id 配对”更复杂。

收敛策略：
- 在子任务 D 开始前，先补一份 OOXML 研究文档
- 研究结论再决定是否把 move 再拆出独立子任务
- 当前先**保留 `06-18-tracked-changes-advanced-types` 为单一子任务**；只有在研究表明 move 的配对 / 作用域语义明显独立且实现风险高时，再继续拆分。

### 6.2 `*PrChange`

风险：accept 时到底是整棵属性树替换、字段级 merge，还是按子类型分别处理，不能靠一句“覆盖当前属性树”带过。

收敛策略：
- 先用 fixture 看真实 XML
- 再把每种属性变更的 accept / reject 规则写成表格化设计
- 当前先保留在同一 advanced-types 子任务内；若属性变更规则表最终显著膨胀，再单独拆出 property-changes 子任务。

### 6.3 `cellIns` / `cellDel`

风险：表格结构类修订通常比文本类更难处理；“同 ins/del，只是作用域不同”这个说法太粗。

收敛策略：
- 在子任务 D 里单列一节表格结构语义
- 若研究后发现难度明显超出预期，继续拆任务

## 7. 父任务完成条件

父任务只有在以下条件同时满足时才算 ready / complete：

1. 子任务边界稳定，且没有明显重叠 / 漏洞
2. `TrackedChange` 非文本类建模问题已定
3. 高风险高级类型已有研究结论与实现归属
4. 子任务按顺序完成并通过验证
5. spec / docs / 示例 / 异常文案全部对齐

## 8. 与实现期文档的关系

- `prd.md`：记录需求、范围、验收、开放问题
- `design.md`（本文）：记录总体设计、子任务边界、跨任务契约、风险收敛
- `implement.md`：记录执行顺序、研究前置项、验证与 rollback 观察点

子任务开始前，应在各自目录内继续细化其专属 PRD / Design / Implement。