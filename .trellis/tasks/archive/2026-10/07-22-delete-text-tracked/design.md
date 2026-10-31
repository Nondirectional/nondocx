# 技术设计：兼容式跨 run tracked 文本删除

## 1. 设计目标

在不改变 `delete_run_tracked` 既有调用契约的前提下，为同一个工具增加按正文段落精确文本定位的删除模式。旧模式删除整个普通 run；新模式可以删除一个普通段落中的任意精确文本，目标可以跨越多个普通 run，并以 `<w:del>` 修订保留审阅、接受和拒绝语义。

本设计只覆盖正文普通 run。超链接、图片、字段及其他非普通内联元素不作为可修改目标；若目标范围跨越不支持的内联内容，工具必须返回错误而不是降级为破坏性重写。

## 2. 边界与数据流

```text
Agent JSON
  → TrackedChangeAuthoringTools.deleteRunTracked
  → edit 定位/校验/规范化
  → Paragraph 的 live range authoring API
  → TrackedChangeNodes 的 XmlBeans/XmlCursor 写入
  → Document.trackedChanges() 读取
  → save_docx / accept / reject
```

### 2.1 Toolkit 边界

`TrackedChangeAuthoringTools.deleteRunTracked` 仍是唯一公开工具入口：

- `paragraph_index + run_index`：旧模式，删除完整普通 run；
- `paragraph_index + target_text + occurrence?`：新模式，按段落可见文本精确匹配；
- 同一 edit 必须且只能选择一种模式；`target_text` 为空、两种模式同时出现或缺少必要字段都属于该条 edit 的错误；
- `occurrence` 从 0 开始。未传时必须恰好唯一命中；传入时选择指定命中，越界报错；
- 匹配大小写敏感，不做模糊、正则或全局替换；
- 批量 edit 先按每个段落的原始可见文本完成 `target_text` 解析和重叠校验，再应用合法目标；非重叠范围按已应用右侧长度调整当前偏移。单条定位、边界拆分或重叠校验失败不得造成该条 edit 的半成品写入；批量整体继续沿用现有 collect-errors / `on_error` 语义。

工具描述和嵌套能力元数据需要明确写出两种定位方式及 `target_text`、`occurrence` 字段。旧 Java 兼容重载继续转发到当前带 `on_error` / `expected_generation` 的入口。

### 2.2 Core 边界

在 `Paragraph` 上增加无 POI 类型泄漏的 tracked range authoring 入口，建议形态为：

```java
public Paragraph deleteTrackedRange(String author, int start, int end)
```

其中范围为段落可见文本的半开区间 `[start, end)`。`start/end` 只作为 toolkit 到 core 的内部契约使用，外部 Agent 不直接传偏移；偏移单位沿用 Java `String` 的 UTF-16 code unit，并在边界校验中拒绝落在 surrogate pair 中间。这样 toolkit 的 `String.indexOf`、`substring` 和 core 的文本切片保持一致，emoji 等代理对不会被拆开。

Core 入口负责：

- 校验 author 非空白、范围有效；
- 从段落当前 inline 顺序建立文本片段到普通 run 的映射；
- 确认目标范围没有跨越不支持的内联元素或非文本 run；
- 将目标按普通 run 边界拆成若干 text slice；
- 将目标按普通 run 边界拆成若干 text slice；在 detached CTP 副本上完成所有 XML 改写，再一次性写回原段落。
- 将目标 slice 写入 tracked deletion；
- 返回仍可继续读取的段落句柄，不暴露已迁入 deletion 语义路径的旧 Run wrapper。

`Paragraph.addDeletion(author, Run)` 保持旧行为和签名，完整 run 模式可以继续使用它，或复用新的 XML 定位 helper，但不得改变旧调用的结果契约。

## 3. 文本定位模型

### 3.1 原始快照

Toolkit 对每个段落建立一次快照：

```text
段落文本:  言简意赅地说明通过本文件要达到的期望目标。
run 0:     言简意赅                         [0, 4)
run 1:     地说明通过本文件                   [4, 13)
run 2:     要达到的期望目标。                 [13, 25)
```

实际实现不应只用 `p.runs()` 的文本拼接后盲写：需要同时检查 `inlineElements()` 的顺序，识别超链接、图片等不可修改元素。如果 POI 段落文本与可建模的 inline 文本无法一致映射，目标 edit 返回 `UnsupportedFeatureException`/toolkit 可读错误。

### 3.2 旧 run 模式规范化

旧 `{paragraph_index, run_index}` 先解析为该普通 run 在段落快照中的 `[runStart, runEnd)`。因此旧模式与新模式最终都变成统一的 `TrackedTextRange`，但旧模式的成功输出仍沿用原来的 run 坐标和文本描述。

### 3.3 新文本模式规范化

对 `paragraph.text()`（或经过 inline 映射验证的等价文本）执行精确 `indexOf`：

- 无命中：该 edit 失败；
- 唯一命中：使用该范围；
- 多个命中且无 `occurrence`：歧义失败；
- 指定 `occurrence`：使用从 0 开始的命中；
- 目标为空：参数失败。

同一批次同一段落的重叠范围需要在写入前发现并拒绝冲突 edit；完全相同的目标也不能重复产生两条相同 tracked deletion。非重叠目标可以按从右到左应用，避免修改结构后影响尚未应用的左侧偏移。

## 4. OOXML 写入策略

### 4.1 顺序保持

现有 `TrackedChangeNodes.addDeletion` 的创作路径会新建 `<w:del>` 并把 run 迁入其中。范围删除不能把修订节点简单追加到段落末尾，否则删除首段或中间文本时可能改变正文阅读顺序。新增内部 helper 时必须以原始目标 CTR 为锚点，在其原位置插入 `<w:del>`，再把目标 CTR 迁入该 wrapper；完整 run 和拆分 slice 都要保持原序。

### 4.2 边界 run 拆分

对一个普通、纯文本 CTR，目标 slice 可能是三种情况：

1. 整个 run：直接将原 CTR 转为 `delText` 并迁入 `<w:del>`；
2. 前缀 + 目标 + 后缀：构造三个位置连续的 CTR，前缀/后缀留在正文，目标 CTR 转为 `delText` 后放入原位置的 `<w:del>`；
3. 仅前缀 + 目标或目标 + 后缀：对应地只构造两个片段。

新建片段应复制原 CTR 的完整 `rPr`（而不仅是 `RunStyle` 的六个字段），目标文本只转换为 `<w:delText>`；需要处理 `xml:space` 等文本节点属性，避免首尾空格在保存后丢失。若目标 CTR 含有无法安全切片的非文本子节点，返回显式不支持错误。

每个受样式边界影响的目标片段可以生成独立 `<w:del>`；它们在文档中按原顺序排列，共享 toolkit 传入的 author，均由现有 tracked changes 查询和 accept/reject 流程发现。首版不要求将整条跨 run edit 压缩为单个 revision wrapper。

### 4.3 失效 wrapper 与重算

XML 迁移或拆分后，原 `Run` wrapper 可能失效，不能继续读取其文本。实现必须在 XML 改写前快照所有文本、样式和坐标，并在 detached CTP 副本上完成改写；写回原 CTP 前通过 POI 的 `removeRun` 清理旧普通 run 缓存，写回后用 `addRun` 注册新的 live wrappers，保证原 `Paragraph` 仍可读取。accept/reject 后同一 POI 实例上的旧修订/Run wrapper 仍可能失效，集成测试通过 save→reopen 验证最终内容。

## 5. 兼容性与错误契约

- 旧调用只传 `run_index` 时不要求 `target_text`，返回中的“段落 N run M tracked del”格式保持可识别；
- 新调用返回目标文本、命中范围/涉及 run 数及成功标记，但不改变现有批量摘要、部分失败和 `expected_generation` 行为；
- 文档不存在、代次不匹配、段落/run 越界等继续走 toolkit 现有结果码；
- core 正常公共 API 的非法参数使用项目既有 `DocxOperationException` 子类及中文消息；POI/OpenXML 异常不得泄漏到 core 公共表面；
- 非普通内联内容必须显式返回不支持/参数错误，禁止静默忽略或按纯文本重建导致内容丢失；
- 不修改 `save_docx`、tracked changes family gate、accept/reject 的既有流程。

## 6. 重要取舍

### 采用兼容扩展，而非新公开工具

保留 `delete_run_tracked` 可以让既有 Agent prompt、Java 调用方和能力注册继续工作。工具内部多一种 selector 比新增工具再维护两套 authoring/metadata/文档更小。代价是工具名称不再只描述“完整 run”，因此描述和参数互斥规则必须写清楚。

### 采用精确文本，而非让 Agent 传字符偏移

精确文本匹配让 Agent 不需要理解 run 边界或计算偏移，且更容易在结果中回显实际删除内容。代价是重复文本需要 `occurrence`，文本匹配也不能表达“第几个相同字符”的所有低层场景；后续如确有需要，可在统一 range engine 上增加显式范围模式，不改变旧模式。

### 保守拒绝复杂内联内容

Word 的段落可同时包含 hyperlink、field、drawing 等内容，强行把它们当字符串切片会破坏关系或字段。第一版只对普通文本 run 做深度包装，遇到不支持目标明确失败；后续可独立扩展其他 inline family。

## 7. 回滚与操作注意

- 代码修改集中在 core tracked authoring、toolkit tracked authoring、测试和文档/能力描述；不触碰工作区已有无关 Trellis 脚本改动。
- 若 XML 顺序或 Word/WPS round-trip 出现问题，优先回滚新的 range 分支和内部 split helper；旧完整 run 分支必须保留并继续通过原有测试。
- 不在 `save` 阶段做自动修复；失败通过异常/工具结果暴露，文档是否落盘继续由现有应用层保存契约决定。
