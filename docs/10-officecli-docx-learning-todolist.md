# OfficeCLI DOCX 可学习项优化 TODO

> 对比基线：2026-07-10，OfficeCLI `v1.0.135-1-gd3a52e8d`。
>
> 目标不是复制 OfficeCLI，也不是追求功能数量对齐。目标是吸收其中适合
> `nondocx` 的设计，继续保持 Java 强类型领域 API、活对象、往返保真、POI
> 隔离、修订与批注优势。

## 使用方式

- 按 `P0 → P1 → P2 → P3` 顺序推进。
- 每次只选一个 TODO 建 Trellis task，完成设计、实现、测试、文档和兼容性验证。
- 涉及渲染输出时，同时验证 Word 与 WPS；涉及 Agent 时，同时验证结构化结果与人类可读结果。
- 不因能力扩展破坏 `nondocx-core` 的强类型 API；字符串属性袋只能存在于 Agent/CLI 适配层。
- 新能力先确认 Apache POI/OOXML 可达性。POI 精简 schema 缺失时，明确保持 `raw()` 或放入可选模块。

## 优先级总览

| 优先级 | ID | 主题 | 主要收益 |
|---|---|---|---|
| P0 | P0-01 | 稳定语义寻址 | 解决索引漂移和跨阶段定位风险 |
| P0 | P0-02 | 结构化工具结果 | 消除字符串解析与错误语义歧义 |
| P0 | P0-03 ✅ | 机器可读能力契约 | 防止工具、文档、实现漂移 |
| P0 | P0-04 | 统一语义视图 | 为 Agent 提供低成本、可控上下文 |
| P0 | P0-05 | 写操作安全协议 | 防止空操作、越界和意外批量修改 |
| P0 | P0-06 | Agent 工作流技能文档 | 固化先读、再改、再验证的正确流程 |
| P1 | P1-01 | 结构化问题诊断 | 统一质量检查、兼容性和损坏报告 |
| P1 | P1-02 | 样式继承与国际化 | 提升中文、混排、RTL 和模板适配能力 |
| P1 | P1-03 | DOCX 包安全与健壮性 | 提升不可信文档输入安全和错误质量 |
| P1 | P1-04 | 可选视觉 QA 闭环 | 降低“结构正确、视觉错误”风险 |
| P1 | P1-05 | 文档元数据与设置 | 低成本补齐常见文档级能力 |
| P1 | P1-06 | Bookmark 与 Field | 支持模板、页码、交叉引用、邮件合并 |
| P1 | P1-07 | Styles 与 Numbering 模型 | 从直接格式升级为可维护文档设计系统 |
| P1 | P1-08 | 批处理与提交语义 | 统一部分失败、停止策略和执行报告 |
| P1 | P1-09 | 页眉页脚完整变体 | 补齐首页、奇偶页和字段页码 |
| P1 | P1-10 | Footnote 与 Endnote | 补齐报告、论文、法律文档基础能力 |
| P2 | P2-01 | SDT 与 Form Field | 支持结构化模板和表单文档 |
| P2 | P2-02 | TOC 创作与刷新策略 | 从只读升级为可创建、可交付 |
| P2 | P2-03 | Watermark 与 Textbox | 补齐常见版式元素 |
| P2 | P2-04 | OMML Equation | 支持技术和学术文档公式 |
| P2 | P2-05 | 文档保护与权限范围 | 支持只读、批注、修订和表单保护 |
| P3 | P3-01 | Native Chart | 可编辑原生图表，复杂度高 |
| P3 | P3-02 | Shape、Diagram、SmartArt | POI 支持薄，优先研究后决定 |
| P3 | P3-03 | OLE 与嵌入对象 | 安全和兼容成本高，默认不进 core |
| P3 | P3-04 | 可插拔扩展协议 | 为长尾格式/渲染器提供边界 |

---

## P0：基础能力

### [ ] P0-01 稳定语义寻址

**问题**

当前 Agent 工具主要使用：

- `paragraph_index`
- `run_index`
- `body_index`
- `table_index/row_index/cell_index`

插入或删除元素后，位置索引会漂移；正文段落与表格交错时，
`paragraph_index` 和 `body_index` 还具有不同语义。

**目标**

建立统一 `DocumentRef` / `ElementRef`，让分析、计划、审查和提交阶段使用同一种目标引用。

**设计建议**

- 引用类型至少包含：
  - `DocumentRef`
  - `ParagraphRef`
  - `RunRef`
  - `TableRef`
  - `CellRef`
  - `HeaderFooterRef`
  - `RevisionRef`
- 区分两类稳定性：
  - `SESSION`：当前打开会话内稳定。
  - `PERSISTENT`：save/reopen 后仍可重新解析。
- 段落存在 `w14:paraId` 时可作为持久候选；缺失时不要为了读取而修改文档。
- 无持久 ID 的元素使用会话 opaque ID，并绑定 `sessionGeneration`。
- 保留位置索引作为展示信息和兼容输入，但内部尽早解析为 `ElementRef`。
- 引用失效必须返回明确错误，如 `stale_ref`、`element_removed`、`generation_mismatch`。
- 写操作返回解析后的引用，避免调用方继续使用旧索引。

**实施清单**

- [ ] 定义引用模型与稳定性枚举。
- [ ] 定义 `ElementResolver`，统一完成 ref → 活对象解析。
- [ ] `DocumentSnapshot` 增加元素引用，不再只保存索引。
- [ ] `ConflictKey.targetRef` 改用规范化引用，不再由自由字符串承担协议。
- [ ] Body/Table/HeaderFooter/TrackedChange 工具同时接受 ref 和旧索引。
- [ ] 增加旧索引到 ref 的兼容适配，并标记弃用路线。

**验收**

- [ ] 读取段落后，在前方插入新段落，旧 `ParagraphRef` 仍指向原段落。
- [ ] 段落和表格交错时，不再混淆 `paragraph_index` 与 `body_index`。
- [ ] 删除目标后使用旧 ref，稳定返回 `element_removed`。
- [ ] close/reopen 后，SESSION ref 稳定返回 `generation_mismatch`。
- [ ] 有 `paraId` 的段落可在 save/reopen 后重新定位。

**OfficeCLI 参考**

- `schemas/help/docx/paragraph.json`：stable path 与 positional path 并存。
- `src/officecli/Handlers/Word/WordHandler.Selector.cs`
- `src/officecli/Handlers/Word/WordHandler.Navigation.cs`

---

### [ ] P0-02 结构化工具结果

**问题**

当前 toolkit 多数工具返回中文字符串。人类易读，但编排器和 Agent 难以稳定区分：

- 成功、部分成功、失败。
- 参数错误、越界、不支持、目标失效。
- 修改数量和实际影响范围。
- 可重试错误与不可重试错误。

**目标**

建立统一结构化结果，同时保留中文文本渲染。

**建议模型**

```java
ToolResult<T> {
  boolean success;
  String code;
  T data;
  List<ToolWarning> warnings;
  List<ElementRef> changedRefs;
  Integer matchedCount;
  String suggestion;
}
```

**实施清单**

- [ ] 定义稳定错误码目录。
- [ ] 定义 warning、partial result、batch item result。
- [ ] 增加 `ToolResultRenderer` 输出现有中文文本。
- [ ] 工具内部返回结构化对象；Agent 框架边界决定输出 JSON 或文本。
- [ ] `CommitCoordinator`、review、测试改为消费结构化结果。
- [ ] 禁止通过 `contains("错误")` 等字符串方式判断执行状态。

**建议错误码**

- `invalid_argument`
- `index_out_of_range`
- `stale_ref`
- `element_removed`
- `unsupported_feature`
- `no_changes_applied`
- `partial_failure`
- `document_closed`
- `document_corrupt`
- `compatibility_risk`

**验收**

- [ ] 单条成功、单条失败、批量部分失败均有稳定 JSON。
- [ ] 文本输出与结构化结果来自同一对象。
- [ ] 所有错误均能区分 code、message、suggestion。
- [ ] 编排层不再解析人类文本。

**OfficeCLI 参考**

- JSON envelope、warning code、suggestion、valid values。
- `src/officecli/Core/OutputFormatter.cs`
- `src/officecli/Core/CliException.cs`

---

### [x] P0-03 机器可读能力契约

**目标**

为 Agent 和文档生成一份可版本化能力清单，声明：

- 元素类型。
- 支持的 read/add/update/remove 操作。
- 参数类型、枚举、单位、是否必填。
- 读回格式。
- 示例。
- 稳定性与兼容性等级。
- 是否需要 Word/WPS 重新计算。

**原则**

- 只允许一个真实来源。
- 不手工维护互相复制的 schema、Javadoc、工具描述。
- 可以从注解、registry 或构建期模型生成 JSON。
- schema 必须有版本和摘要，便于 Agent 缓存与兼容判断。

**实施清单**

- [x] 设计 `CapabilityDescriptor`。 → `com.non.docx.toolkit.capability.model.{CapabilityManifest,ToolCapabilityDescriptor,ParamCapabilityDescriptor}`
- [x] 定义 element、operation、property、enum、example 模型。 → `CapabilityOperation`/`CapabilityLevel`/`ParamType` 枚举 + `@ToolCapability`/`@ParamCapability`/`@NestedParamCapability` 注解
- [x] 为 toolkit 增加 `describe_capabilities`。 → `CapabilityTools.describeCapabilities(element,operation,level)`
- [x] 构建时生成 `capabilities.json`。 → `CapabilityManifestGenerator` + exec-maven-plugin(process-classes)
- [x] 文档中的工具表从 schema 生成或校验。 → spec `orchestration-layer.md` 记录硬契约；manifest 是唯一来源
- [x] CI 增加能力契约测试：声明支持的能力必须有真实执行测试。 → `CapabilityContractTest`（全覆盖/测试覆盖/enum 完整性/digest 稳定）
- [x] 输出 schema digest，能力未变化时 Agent 可复用缓存。 → `CapabilityDigest`(SHA-256，排除 generatedAt)

**验收**

- [x] 新增工具参数但未更新能力来源时 CI 失败。 → 收集器对缺 `@ToolCapability`/`@ParamCapability` 的方法抛 `CapabilityDeclarationException`，构建失败
- [x] schema 中每个 enum 值都有解析测试。 → `CapabilityContractTest.枚举参数都声明了enumValues`（ENUM 类型必须有 enumValues）；3 个无测试工具经 allowlist 显式豁免
- [x] `describe_capabilities` 可按功能域过滤。 → 支持 element/operation/level 三维过滤
- [x] Agent 不需要猜属性名和枚举值。 → 55 个工具的参数/枚举值/单位全部进 manifest

**OfficeCLI 参考**

- `schemas/help/docx/*.json`
- `schemas/help/_schema.json`
- `src/officecli/Help/`
- `officecli --output-schema-crc`

---

### [ ] P0-04 统一语义视图

**目标**

把现有 `DocumentSnapshot` 提炼为可复用只读服务，不只服务多 Agent 编排。

**建议视图**

- `outline`：标题树、section、表格、图片、TOC、页眉页脚、修订概览。
- `text`：按文档顺序输出文本和元素引用。
- `annotated`：文本 + 样式 + 有效格式来源 + warning。
- `stats`：段落、表格、图片、字体、字号、样式、修订统计。
- `issues`：结构化问题列表。
- `element`：按 ref 获取一个元素的结构化详情。

**实施清单**

- [ ] 新增 `DocumentViewService`。
- [ ] 复用 `SnapshotBuilder`，避免第二套遍历。
- [ ] 输出强类型 DTO，不直接输出 POI 类型。
- [ ] 支持最大条数、文本截断和按需展开 run，控制 Agent 上下文。
- [ ] 每个视图包含 `snapshotVersion/sessionGeneration`。
- [ ] toolkit 的 overview/read 工具逐步改为该服务的薄适配层。

**验收**

- [ ] 一个大型文档无需全文 dump 即可定位目标。
- [ ] outline 中每项都带可用于后续修改的 ref。
- [ ] stats 与真实文档结构一致。
- [ ] 同一 snapshot 内所有视图引用一致。

**OfficeCLI 参考**

- `view text`
- `view annotated`
- `view outline`
- `view stats`
- `view issues`
- `src/officecli/Handlers/Word/WordHandler.View.cs`

---

### [ ] P0-05 写操作安全协议

**目标**

统一所有 Agent 写操作的前置检查、影响范围和 no-op 语义。

**实施清单**

- [ ] 写操作未提供任何可写字段时返回 `no_changes_applied`。
- [ ] 无匹配目标不能静默成功。
- [ ] 多目标修改必须回报 `matchedCount/changedCount`。
- [ ] 默认拒绝未限定范围的全局修改。
- [ ] 批量写入支持 `continueOnError` 与 `stopOnError`。
- [ ] 支持可选 `expectedGeneration`，防止旧计划修改新文档状态。
- [ ] 修改前后返回目标摘要，便于 review。
- [ ] 对删除、移动等索引敏感操作统一倒序或 ref 解析策略。

**验收**

- [ ] 空更新不再返回成功。
- [ ] 越界和零匹配具有不同错误码。
- [ ] 批量操作能明确区分成功数、失败数、未执行数。
- [ ] 旧 snapshot 驱动写操作时被阻止。

**OfficeCLI 参考**

- `MutationSelectorGuard`
- `set` 的 missing-property、match-count、unsupported-property 处理。
- resident busy 时不回退到第二写入者的安全原则。

---

### [ ] P0-06 Agent 工作流技能文档

**目标**

把正确使用方式从示例 system prompt 提炼为独立、可复用、版本化的 Agent 指南。

**建议流程**

1. 打开文档。
2. 获取 outline/stats/issues。
3. 使用 ref 定位目标。
4. 结构修改优先，内容修改其次，格式修改最后。
5. 每个结构修改后立即读回。
6. 保存前运行质量检查。
7. 有视觉能力时执行渲染检查。
8. 输出变更摘要和未解决 warning。

**实施清单**

- [ ] 新建 Agent skill/prompt 模板。
- [ ] 从 capability schema 引用参数，不重复列全部 API。
- [ ] 固化“help first，不猜枚举”的规则。
- [ ] 固化“先搜索/定位，再修改”的规则。
- [ ] 区分直接编辑与 tracked changes 两套路线。
- [ ] 增加模板、报告、法律文档、学术文档工作流。

**验收**

- [ ] 新 Agent 只读取该指南和 capability schema 即可完成基本编辑。
- [ ] 不再依赖散落在 examples 中的私有提示词。
- [ ] 指南版本与能力 schema 版本可追踪。

**OfficeCLI 参考**

- `skills/officecli-docx/SKILL.md`
- Help-first、orient → edit → save → QA 工作流。

---

## P1：高价值增强

### [ ] P1-01 结构化问题诊断

**目标**

把现有 `QualityCheckTools` 的报告升级为稳定问题模型。

**建议模型**

```java
DocumentIssue {
  String code;
  IssueCategory category;
  IssueSeverity severity;
  ElementRef ref;
  String message;
  String suggestion;
  Map<String, Object> details;
}
```

**实施清单**

- [ ] 统一 issue code 目录。
- [ ] 支持按 category/severity/code 过滤。
- [ ] 现有 10 项检查迁移到稳定 code。
- [ ] 增加 broken relationship、missing part、field stale 等结构问题。
- [ ] `QualitySummary` 保存结构化 issue，不保存拼接字符串。
- [ ] 文本报告由 renderer 生成。

**验收**

- [ ] issue code 在版本内稳定。
- [ ] 每个问题带明确 ref。
- [ ] Agent 可只请求 ERROR 或指定 code。
- [ ] 文本和 JSON 报告一致。

---

### [ ] P1-02 样式继承与国际化

**目标**

正确解析和修改：

- 直接格式。
- 段落/字符样式。
- basedOn 样式链。
- `docDefaults`。
- 主题字体。
- Latin / EastAsia / ComplexScript 字体槽。
- BCP-47 语言。
- RTL 与 bidi。

**实施清单**

- [ ] 新增 `EffectiveRunStyle`、`EffectiveParagraphStyle`。
- [ ] 每个有效值携带来源：direct/style/docDefaults/theme。
- [ ] Run API 支持字体槽和语言槽。
- [ ] Paragraph/Section/Table 支持 RTL。
- [ ] 增加中英混排、阿拉伯语、希伯来语测试文档。
- [ ] 更新 equality 边界，明确比较直接值还是有效值。

**验收**

- [ ] 中文和西文字体可独立设置并 round-trip。
- [ ] 样式继承值可正确读出来源。
- [ ] RTL 文档在 Word/WPS 至少一个目标组合中验证。
- [ ] 修改直接格式不会意外破坏样式定义。

**OfficeCLI 参考**

- `WordHandler.I18n.cs`
- `WordHandler.Helpers.Style.cs`
- paragraph/run/document schema 中 `effective.*` 与字体槽属性。

---

### [ ] P1-03 DOCX 包安全与健壮性

**目标**

对不可信输入提供明确限制和诊断，不发生 OOM、路径问题或模糊 POI 异常。

**实施清单**

- [ ] 确认并集中配置 POI `ZipSecureFile` 限制。
- [ ] 显式拒绝 0-byte 和非 ZIP DOCX。
- [ ] 检测 entry 数、解压后大小和压缩比。
- [ ] 检测 dangling internal relationship。
- [ ] 检测缺失 part、非法 content type、重复 relationship ID。
- [ ] 验证 external hyperlink URI。
- [ ] 默认只诊断，不在 open 时静默改写用户文件。
- [ ] 若提供 repair，必须是显式 opt-in，并输出修复报告和备份策略。

**验收**

- [ ] 构造 zip bomb 测试不会触发失控内存。
- [ ] 损坏关系返回稳定 `document_corrupt`/`broken_relationship`。
- [ ] 普通只读打开不会修改源文件。
- [ ] repair 前后均有测试和明确报告。

**OfficeCLI 参考**

- `DocumentHandlerFactory.GuardDecompressionBomb`
- dangling relationship 检测和修复。
- `HyperlinkUriValidator`
- `SsrfGuard`

---

### [ ] P1-04 可选视觉 QA 闭环

**边界**

不要在 `nondocx-core` 内直接重造完整 Word 分页引擎。

**建议架构**

- `nondocx-renderer-spi`
- `SemanticHtmlRenderer`
- `OnlyOfficeRendererAdapter`
- 可选 `LibreOfficeHeadlessAdapter`
- `RenderResult` + `RenderIssue`

**实施清单**

- [ ] 先实现语义 HTML：标题、段落、run、表格、图片、页眉页脚。
- [ ] 支持输出元素 ref 到 HTML `data-*` 属性。
- [ ] 检查图片溢出、表格宽度、空段落、标题层级和低对比度。
- [ ] 把当前 demo 的 OnlyOffice 刷新能力封装为 adapter。
- [ ] renderer 放独立模块，避免 core 增加浏览器/服务依赖。
- [ ] 定义“结构 QA”和“真实分页 QA”的差异。

**验收**

- [ ] 无 OnlyOffice 时仍可生成语义 HTML。
- [ ] HTML 问题能定位回 `ElementRef`。
- [ ] demo 可继续使用 OnlyOffice，但不再把它当唯一视觉接口。

**OfficeCLI 参考**

- `WordHandler.HtmlPreview*.cs`
- `view html`
- `watch`

---

### [ ] P1-05 文档元数据与设置

**范围**

- title
- subject
- author/creator
- keywords
- description
- lastModifiedBy
- created/modified
- compatibility mode
- updateFields
- document grid
- default fonts

**实施清单**

- [ ] 新增 `DocumentProperties`。
- [ ] 区分可写属性和只读属性。
- [ ] 支持 core properties 与 extended properties。
- [ ] 增加 Word/WPS round-trip 测试。

**OfficeCLI 参考**

- `schemas/help/docx/document.json`
- `WordHandler.Navigation.DocSettings.cs`
- `WordHandler.Set.DocSettings.cs`
- `WordHandler.Set.DocDefaults.cs`

---

### [ ] P1-06 Bookmark 与 Field

**优先 Field**

- PAGE
- NUMPAGES
- DATE
- REF
- PAGEREF
- SEQ
- MERGEFIELD
- DOCPROPERTY

**实施清单**

- [ ] `Bookmark` 领域模型与唯一名称校验。
- [ ] `Field` 领域模型，保留 begin/instr/separate/result/end 结构。
- [ ] 支持 field instruction 与 typed shortcut。
- [ ] 明确 cached result 与真实计算结果的差异。
- [ ] 支持设置 `updateFieldsOnOpen`。
- [ ] Agent issue 检查 stale/unevaluated field。
- [ ] Bookmark/REF 删除时检测悬挂引用。

**验收**

- [ ] Page X of Y 页脚能正确生成字段链。
- [ ] MERGEFIELD 不退化成普通占位文本。
- [ ] REF 指向不存在 bookmark 时产生结构化 issue。
- [ ] Word 打开后可按设置刷新字段。

**OfficeCLI 参考**

- `WordHandler.Helpers.Field.cs`
- `WordHandler.Add.Structure.cs`
- `WordBatchEmitter.Fields.cs`
- `schemas/help/docx/field.json`
- `schemas/help/docx/bookmark.json`

---

### [ ] P1-07 Styles 与 Numbering 模型

**目标**

从“直接修改 run/paragraph”升级为可维护的样式与编号系统。

**实施清单**

- [ ] `Styles`、`ParagraphStyle`、`CharacterStyle`。
- [ ] basedOn、next、linked style。
- [ ] 样式增删改查和引用保护。
- [ ] `AbstractNumbering`、`NumberingInstance`、`NumberingLevel`。
- [ ] 多级编号、restart、startOverride。
- [ ] Agent 优先使用 style，而不是批量写直接格式。

**验收**

- [ ] 修改 Heading1 定义可统一影响引用段落。
- [ ] 多级编号 1 / 1.1 / 1.1.1 正确 round-trip。
- [ ] 删除正在使用的 style/numbering 时明确拒绝或给迁移方案。

**OfficeCLI 参考**

- `style/styles/abstractNum/num/level` schema。
- `WordHandler.Helpers.Style.cs`
- `WordHandler.StyleList.cs`
- `Core/WordNumFmtRenderer.cs`

---

### [ ] P1-08 批处理与提交语义

**目标**

统一 toolkit batch、orchestration commit 和未来 CLI/API 的执行协议。

**实施清单**

- [ ] `BatchRequest`、`BatchItem`、`BatchResult`。
- [ ] `stopOnError`、`continueOnError`。
- [ ] preflight：先解析全部 ref 和参数，再决定是否执行。
- [ ] dry-run：返回预计影响范围。
- [ ] 统一 partial failure。
- [ ] 明确当前非事务语义。
- [ ] 研究 clone-document 执行后整体替换的可选事务模式。

**验收**

- [ ] 批量失败后调用方知道哪些已执行、哪些未执行。
- [ ] dry-run 不修改文档。
- [ ] preflight 能提前发现 stale ref 和冲突。

**OfficeCLI 参考**

- `CommandBuilder.Batch.cs`
- `Core/BatchExecutor.cs`
- Python/Node SDK 的统一 batch item 形状。

---

### [ ] P1-09 页眉页脚完整变体

**范围**

- default/odd
- first
- even
- linkToPrevious
- differentFirstPage
- oddAndEvenPages
- 页码字段

**验收**

- [ ] 多 section 文档能独立配置三种页眉页脚。
- [ ] linkToPrevious 不会意外复制或断开关系。
- [ ] Word/WPS 均验证首页和奇偶页。

---

### [ ] P1-10 Footnote 与 Endnote

**实施清单**

- [ ] 只读枚举和按 ID 获取。
- [ ] 正文引用与 note part 双向关系。
- [ ] 新建、删除和重新编号。
- [ ] note 内支持段落、run、超链接。
- [ ] 删除引用时清理孤儿 note。

**验收**

- [ ] 多脚注、多尾注 round-trip。
- [ ] 删除中间 note 后引用一致。
- [ ] 修订环境下行为有明确边界。

**OfficeCLI 参考**

- `schemas/help/docx/footnote.json`
- `schemas/help/docx/endnote.json`
- `WordHandler.Add.Structure.cs`

---

## P2：选择性扩展

### [ ] P2-01 SDT 与 Form Field

- [ ] 支持 block/inline/cell/row SDT 的读取。
- [ ] 支持 tag、alias、lock、placeholder、showingPlaceholder。
- [ ] 支持 checkbox、dropdown、text form field。
- [ ] 把 SDT 作为模板填充的首选结构，不再只靠文本占位符。
- [ ] 明确 legacy form field 与 modern content control 的区别。

**OfficeCLI 参考**

- `schemas/help/docx/sdt.json`
- `schemas/help/docx/formfield.json`
- `WordHandler.FormFields.cs`

---

### [ ] P2-02 TOC 创作与刷新策略

- [ ] 支持插入 TOC field。
- [ ] 支持 levels、hyperlinks、pageNumbers。
- [ ] 支持设置 `updateFieldsOnOpen`。
- [ ] 明确 nondocx 无分页引擎，不能承诺预计算页码。
- [ ] 提供静态 TOC fallback builder。
- [ ] 交付检查识别未刷新 TOC placeholder。

---

### [ ] P2-03 Watermark 与 Textbox

- [ ] 先实现文本 watermark。
- [ ] 再实现图片 watermark。
- [ ] Textbox 先只读，再评估创建和编辑。
- [ ] 独立兼容性测试 Word/WPS。
- [ ] 不让 VML/DrawingML 细节泄露到公开 API。

---

### [ ] P2-04 OMML Equation

- [ ] 先支持读取 OMML 原始结构和纯文本摘要。
- [ ] 评估 LaTeX → OMML 转换依赖和许可证。
- [ ] 转换失败必须保留原始公式。
- [ ] 不在 core 内引入重量级渲染器。

---

### [ ] P2-05 文档保护与权限范围

- [ ] readOnly/comments/trackedChanges/forms 模式。
- [ ] enforced 标记。
- [ ] 编辑范围与 permission start/end。
- [ ] 明确“声明保护”与真正加密的区别。

---

## P3：研究后决定

### [ ] P3-01 Native Chart

- [ ] 先只读图表元数据、标题、系列、数据引用。
- [ ] 再评估创建常用 bar/line/pie。
- [ ] 图表数据缓存和引用同步必须有测试。
- [ ] 建议独立 `nondocx-chart` 模块。

### [ ] P3-02 Shape、Diagram、SmartArt

- [ ] 调研 POI 支持程度与 XMLBeans CT 可达性。
- [ ] 优先只读和提取文本。
- [ ] 不为追求覆盖率把大量 raw XML helper 塞进 core。

### [ ] P3-03 OLE 与嵌入对象

- [ ] 默认只读元数据和安全提取。
- [ ] 限制大小、MIME、扩展名和目标路径。
- [ ] 禁止自动执行。
- [ ] 写入能力默认不进入 core。

### [ ] P3-04 可插拔扩展协议

适用于：

- 长尾 DOCX 元素。
- 外部渲染器。
- 文档修复器。
- PDF/HTML 导出器。

**原则**

- core 保持 POI + 强类型 DOCX 领域模型。
- 扩展协议不能成为第二套随意属性系统。
- 插件能力通过 capability schema 暴露。
- 进程内 SPI 优先；只有许可证、平台或隔离要求时才考虑 sidecar process。

**OfficeCLI 参考**

- `plugins/plugin-protocol.md`
- `Core/Plugins/`

---

## 明确不照搬

### [ ] 保持以下边界

- [ ] 不把 OfficeCLI 的字符串 `--prop key=value` 模型带入 `nondocx-core`。
- [ ] 不为未来 Excel/PPT 抽象通用 `DocumentNode`。
- [ ] 不复制 resident/named-pipe 架构；nondocx 已是进程内活对象。
- [ ] 不把 MCP/CLI 当 core 的主 API。
- [ ] 不在 core 中自建完整分页和 Word 级渲染引擎。
- [ ] 不为功能数量优先实现 OLE、SmartArt、3D、复杂 Shape。
- [ ] 不削弱现有 tracked changes 和 comments 强类型模型。
- [ ] 不在只读 open 时自动修复并覆盖源文件。
- [ ] 不维护两套手工同步的能力说明。

---

## 每个优化任务的 Definition of Done

每个 TODO 完成时必须同时满足：

- [ ] 有明确 OOXML 语义说明。
- [ ] 有 Apache POI 行为验证，不只依据接口猜测。
- [ ] 公开 API 无 POI 泄露，`raw()` 除外。
- [ ] 有正常、边界、损坏输入测试。
- [ ] 有 save → reopen round-trip 测试。
- [ ] 涉及渲染时有 Word/WPS 兼容性验证。
- [ ] 涉及 Agent 时有结构化 schema 和错误码。
- [ ] 文档、示例、能力契约同步。
- [ ] `.trellis/spec/backend/` 已记录新的硬契约或 gotcha。
- [ ] 未支持部分明确抛出或保留 `raw()`，不静默降级。

---

## 建议执行顺序

1. `P0-01` 稳定语义寻址。
2. `P0-02` 结构化工具结果。
3. `P0-03` 机器可读能力契约。
4. `P0-04` 统一语义视图。
5. `P0-05` 写操作安全协议。
6. `P0-06` Agent 工作流技能文档。
7. `P1-01` 结构化问题诊断。
8. `P1-03` DOCX 包安全与健壮性。
9. `P1-02` 样式继承与国际化。
10. `P1-06` Bookmark 与 Field。
11. `P1-07` Styles 与 Numbering。
12. `P1-05` 文档元数据与设置。
13. `P1-09` 页眉页脚完整变体。
14. `P1-10` Footnote 与 Endnote。
15. `P1-04` 可选视觉 QA 闭环。
16. `P1-08` 批处理与提交语义。
17. 按真实用户需求选择 P2。
18. P3 只做研究任务，不默认承诺实现。

## OfficeCLI 主要参考入口

本地相邻仓库：

- `../../OfficeCLI/schemas/help/docx/`
- `../../OfficeCLI/src/officecli/Handlers/Word/`
- `../../OfficeCLI/src/officecli/Core/`
- `../../OfficeCLI/skills/officecli-docx/SKILL.md`
- `../../OfficeCLI/plugins/plugin-protocol.md`

重点不是复制 C# 实现，而是提取：

- 对外契约。
- 错误语义。
- Agent 安全约束。
- 元素能力边界。
- 兼容性和验证策略。

