# P0-03 机器可读能力契约

## Goal

为 `nondocx-toolkit` 建立一份可版本化、机器可读的能力清单（capability manifest），声明：
每个工具/能力支持的操作（read/add/update/remove）、参数（类型、枚举、单位、是否必填）、
读回格式、示例、稳定性与兼容性等级、是否需要 Word/WPS 重新计算。

让 Agent 无需猜测属性名与枚举值；让"声明支持的能力"与"真实可执行的能力"在 CI 中强制一致，
防止工具、文档、实现三者漂移。

## 已确认事实

### nondocx 现状（探索确认）

- **56 个 `@ToolDef` 工具**，分布在 7 个工具类：
  - `SessionTools`（4）、`BodyTools`（10）、`TableTools`（26）、`HeaderFooterTocTools`（3）、
    `TrackedChangeQueryTools`（6）、`TrackedChangeAuthoringTools`（6）、`QualityCheckTools`（1）。
- **注册机制**：`DocxToolkit.scanAll(ToolRegistry)` 对每个工具实例调用
  `com.non.chain.tool.ToolRegistry.scan(Object)`，**反射扫描** `@ToolDef` 方法。
- **nonchain 框架注解硬约束**（javap 确认 `chain-0.10.0.jar`）：
  - `@ToolDef`：只有 `name()` + `description()`，无 stability/category/compatibility/recalc 字段。
  - `@ToolParam`：只有 `name()` + `description()` + `required()`，无 enum/unit/default/format 字段。
  - 枚举/单位目前只活在 `description` 自由文本里。
  - `ToolRegistry` 内部 `ToolEntry`/`ParamDef` 是私有字段，未作为公共元数据 API 暴露。
- **P0-01/P0-02 已落地的基础设施（可复用）**：
  - `com.non.docx.toolkit.ref`：`ElementKind` 枚举（PARAGRAPH/RUN/TABLE/CELL/HEADER_FOOTER/REVISION/OPERATION_TARGET）—— 元素类型分类已有雏形。
  - `com.non.docx.toolkit.result`：`ToolResultCode`（16 码，含 `COMPATIBILITY_RISK`）、`ToolResult<T>` envelope —— 读回格式已规范化。
- **无任何现有能力契约/schema/manifest/help 基础设施**。grep `capability|manifest|toolDescriptor|describeTools|schema` 仅命中 POI schema 导入与无关的 snapshotVersion。P0-03 是全新基础设施。
- **构建**：Maven 多模块（parent + core/toolkit/examples/demo）。父 pom 已配 compiler(11)/surefire/source/javadoc/spotless。**无注解处理器、无代码生成 maven 插件**。

### OfficeCLI 参考（`/Users/non/Projects/OfficeCLI`）

- **手写 JSON 是单一真实来源**：`schemas/help/_schema.json`（meta schema，JSON Schema draft 2020-12）+
  `schemas/help/docx/<element>.json`（每个元素一份，如 `paragraph.json` 1405 行）。
- **element-centric 组织**：每份 schema 声明 `format/element/operations{add,set,get,query,remove}/
  paths{stable,positional}/properties{<name>:{type,values,aliases,add,set,get,examples,readback,
  enforcement:strict|report,appliesWhen,requires}}/children`。
- **同一 schema 驱动三件事**：`<format> <op> <element> --help --json` 帮助渲染、契约测试
  （声明 `enforcement:strict` 的 property 必须有真实 Add/Get 执行测试）、release wiki 生成。
- `--output-schema-crc` 输出 digest 供 Agent 缓存与兼容判断。
- `extends`（如 `"extends": "_shared/paragraph"`）做 schema 继承复用。

### nondocx 与 OfficeCLI 的根本差异

OfficeCLI 的真实来源是**手写 schema JSON**，实现（C# Handler）去适配 schema。
nondocx 的真实来源是 **Java 强类型 `@ToolDef` 方法**（已在 P0-01/P0-02 投资 ref/result 模型）。
→ 若照搬"手写 schema 为权威"，会与 nondocx 已有的强类型 API 投资冲突，产生第二套需手工同步的真实来源
（违反 todolist 原则："不手工维护互相复制的 schema、Javadoc、工具描述"）。

## 已定决策

- **D1 真实来源 = Java 代码 + 项目本地伴生注解，反射生成 manifest**。
  不手写 JSON schema 为权威（会与 P0-01/P0-02 强类型投资冲突，产生第二套需同步来源）。
  nonchain `@ToolDef`/`@ToolParam` 确认为 RUNTIME 保留，可反射读 name/description/required。
- **D2 注解粒度 = 方法级 + 参数级双注解**：
  - `@ToolCapability`（放 `@ToolDef` 方法上）：声明操作类别（read/add/update/remove）、
    元素类型（复用 `ElementKind`）、兼容性等级、是否需 Word/WPS recalc。
  - `@ParamCapability`（放 `@ToolParam` 上）：声明 type/enum/unit/default。
  - 嵌套对象（如 `edits[].alignment`）用 `@NestedParamCapability` 重复标注覆盖枚举。
  - 代价：标注 56 工具 × 多参数；收益：元数据与代码同位、覆盖最全。
- **D3 交付范围 = 三件全做**：
  (1) `describe_capabilities` 运行时工具（支持按功能域/元素过滤）；
  (2) 构建期生成 `capabilities.json` + digest（供 Agent 缓存与兼容判断）；
  (3) CI 能力契约测试（声明的能力必须有真实执行测试，参考 OfficeCLI `enforcement:strict`）。
- **D4 兼容性建模 = 三级稳定性枚举 + needsRecalc 布尔**：
  - `CapabilityLevel.STABLE`（OOXML 标准，Word/WPS 均支持，round-trip 已验证）
  - `CapabilityLevel.WORD_ONLY`（仅 Word 正确呈现，如 `w14:paraId`）
  - `CapabilityLevel.EXPERIMENTAL`（POI 支持薄或 round-trip 未充分验证）
  - `boolean needsRecalc`（是否需要 Word/WPS 重新计算，如字段刷新、TOC 更新）。
- **D5 manifest 组织 = tool-centric 基础 + element-centric 聚合视图**：
  基础结构与 `@ToolDef` 一一对应（每工具一条 `ToolCapabilityDescriptor`）；
  同时提供 element-centric 聚合视图（按 `@ToolCapability.element` 自动分组，
  如 paragraph 下聚合 read_paragraph/insert_paragraph/update_paragraph_alignment）。
  56 个工具命名遵循 `<verb>_<element>[_<property>]` 模式，element 归属可从注解字段直接推导，无需重复标注。

## 需求

### R1 能力元数据模型

- 定义 `CapabilityManifest`（顶层：version、digest、generatedAt、tools、elementIndex）。
- 定义 `ToolCapabilityDescriptor`（name、description、operation、element、level、needsRecalc、params、examples、since）。
- 定义 `ParamCapabilityDescriptor`（name、description、type、required、enumValues、unit、defaultValue、nested）。
- 定义 `CapabilityLevel` 枚举（STABLE / WORD_ONLY / EXPERIMENTAL）。
- 定义 `CapabilityOperation` 枚举（READ / ADD / UPDATE / REMOVE / QUERY / SESSION / QUALITY —— 覆盖现有动词）。

### R2 伴生注解

- `@ToolCapability`（METHOD, RUNTIME）：operation、element、level、needsRecalc、since、examples。
- `@ParamCapability`（PARAMETER, RUNTIME）：type、enumValues、unit、defaultValue。
- `@NestedParamCapability`（PARAMETER, RUNTIME, 可重复）：path（如 `"edits.alignment"`）、type、enumValues、unit、defaultValue。

### R3 反射收集器

- `CapabilityCollector`：遍历 `DocxToolkit` 的 7 个工具类，对每个 `@ToolDef` 方法合并 nonchain 注解（name/description）+ `@ToolParam`（name/description/required）+ 项目注解 → `ToolCapabilityDescriptor`。
- 校验：每个 `@ToolDef` 方法必须有 `@ToolCapability`（缺则构建失败，保证全覆盖）。
- 计算 manifest digest（内容 hash），能力未变化时 digest 不变。

### R4 describe_capabilities 工具

- 新增 `CapabilityTools`（第 8 个工具类），提供 `describe_capabilities` 工具。
- 支持过滤：按 element、按 operation、按 level。
- 返回结构化 `ToolResult<CapabilityManifest>`（复用 P0-02 envelope）。

### R5 构建期 capabilities.json

- Maven 阶段（`process-classes` 或独立 goal）调用 `CapabilityCollector`，输出 `capabilities.json` 到 classpath/构建产物。
- 输出 digest 文件，Agent 据此判断是否复用缓存。

### R6 CI 能力契约测试

- 测试：manifest 中每个声明的工具必须有对应 `@Test` 执行覆盖（按工具名匹配测试）。
- 测试：每个 enumValues 声明必须有解析测试（枚举值可被实际接受）。
- 测试：digest 在能力未变时稳定。
- 缺 `@ToolCapability` 的 `@ToolDef` 方法 → 构建失败。

## 验收标准

- [ ] 新增工具参数但未更新 `@ToolCapability`/`@ParamCapability` 时 CI 失败（构建期校验）。
- [ ] 声明 `enumValues` 的参数，每个枚举值都有解析/接受测试。
- [ ] `describe_capabilities` 可按 element/operation/level 过滤，返回稳定结构化结果。
- [ ] Agent 通过 `describe_capabilities` 或 `capabilities.json` 即可知全部参数名、类型、枚举值、单位，无需猜测。
- [ ] 构建期 `capabilities.json` + digest 生成成功，digest 在能力未变时稳定。
- [ ] 全部 56 个 `@ToolDef` 工具均有 `@ToolCapability` 标注（零遗漏，构建校验）。
- [ ] `mvn -q verify` 通过，无 Spotless 或现有回归失败。

## 范围外

- 不从 manifest 反向生成工具代码（manifest 是只读描述，非代码生成输入）。
- 不在本任务实现 P0-04（统一语义视图）——`describe_capabilities` 只描述能力，不 dump 文档内容。
- 不引入跨进程 schema 服务或网络接口；manifest 是进程内 + 构建期文件。
- 不重写 nonchain `@ToolDef`/`@ToolParam` 注解（框架约束）。
- 不为 Excel/PPT 预留通用 DocumentNode 抽象（违反 todolist "明确不照搬"）。
- `raw()` 能力不纳入结构化 manifest（POI 逃生舱，单独标注为 EXPERIMENTAL 即可）。

## 兼容与迁移

- `@ToolCapability`/`@ParamCapability` 是纯新增注解，不改变现有 `@ToolDef`/`@ToolParam` 语义。
- 现有 56 个工具分批标注，每批独立可编译；未标注的方法在过渡期可临时豁免（warning），但 task 完成前必须全覆盖。
- `describe_capabilities` 是新增工具，不破坏现有工具清单。
- `capabilities.json` 是构建产物，不进 git（加入 .gitignore），可随时重新生成。
