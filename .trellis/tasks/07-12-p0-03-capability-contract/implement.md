# Implement: P0-03 机器可读能力契约

## 执行切片（每片独立可编译、可回滚）

### 切片 0：基础设施（纯新增，零标注）

- [ ] 新建 `capability/` 包
- [ ] 定义注解：`@ToolCapability`、`@ParamCapability`、`@NestedParamCapability`（+ `@NestedParamCapabilities` 容器）
- [ ] 定义枚举：`CapabilityOperation`（7 值）、`CapabilityLevel`（3 值）、`ParamType`（9 值）
- [ ] 定义模型：`CapabilityManifest`、`ToolCapabilityDescriptor`、`ParamCapabilityDescriptor`（不可变值对象，仿 P0-02 `ToolResult` 风格）
- [ ] 定义异常：`CapabilityDeclarationException`
- [ ] 实现 `CapabilityDigest`（SHA-256，排除 generatedAt）
- [ ] 实现 `CapabilityJsonIo`（复用 P0-02 Jackson ObjectMapper）
- **验证**：`mvn -q -pl nondocx-toolkit compile` 通过

### 切片 1：反射收集器

- [ ] 实现 `CapabilityCollector.collect(Object...)`
  - 反射 `@ToolDef` 方法 + 合并 `@ToolCapability` + 参数层 `@ToolParam`/`@ParamCapability`/`@NestedParamCapability`
  - 校验：`@ToolDef` 必须有 `@ToolCapability`；`enumValues` 非空必须 `type=ENUM`；`@ParamCapability` 必须配 `@ToolParam`
  - 聚合 `elementIndex`
  - 计算 `digest`
- [ ] 单测 `CapabilityCollectorTest`：用一个 fixture 工具类验证合并 + 校验 + elementIndex 聚合
- **验证**：`mvn -q -pl nondocx-toolkit test -Dtest=CapabilityCollectorTest`

### 切片 2：标注 56 个工具（按工具类分批）

每个工具类标注后立即编译，确保不破坏：

- [ ] `SessionTools`（4 工具）：open/save/close/doc_id/path/get_document_overview → SESSION/READ
- [ ] `BodyTools`（10 工具）：read_paragraph/run/hyperlink → READ；insert_paragraph → ADD；update_paragraph_alignment/update_run_style/replace_run_text → UPDATE；search_text → READ
- [ ] `TableTools`（26 工具）：read_* → READ；create_table/add_table_row/add_table_cell → ADD；update_* → UPDATE；remove_* → REMOVE；set_* → UPDATE；merge_table_cells → UPDATE
- [ ] `HeaderFooterTocTools`（3 工具）：read_header_footer/read_header_footer_ref/read_toc → READ
- [ ] `TrackedChangeQueryTools`（6 工具）：get_/list_ → READ/QUERY
- [ ] `TrackedChangeAuthoringTools`（6 工具）：insert_/replace_/move_/mark_/apply_ → ADD/UPDATE
- [ ] `QualityCheckTools`（1 工具）：check_quality → QUALITY
- **每批后验证**：`mvn -q -pl nondocx-toolkit compile`
- **标注要点**：
  - 对齐 element 字段（paragraph/run/table/cell/row/header_footer/toc/hyperlink/tracked_change/document）
  - 标注 `level`：默认 STABLE；涉及 w14:paraId/PERSISTENT ref 的标 WORD_ONLY；POI 薄支持的标 EXPERIMENTAL
  - 标注 `needsRecalc`：仅 field/TOC 相关（当前工具基本为 false）
  - 嵌套参数（`edits`、`edits[].alignment` 等）用 `@NestedParamCapability` 覆盖枚举

### 切片 3：describe_capabilities 工具

- [ ] 实现 `CapabilityTools`，持有 7 工具实例引用（构造注入）
- [ ] 实现 `describe_capabilities` 工具：支持 element/operation/level 过滤，返回 `ToolResult<CapabilityManifest>`
- [ ] `DocxToolkit` 增加 `capability` 字段 + 构造 + `scanAll` 增加 `.scan(capability)`
- [ ] `describe_capabilities` 自身标注 `@ToolCapability(operation=QUERY, element="", level=STABLE)`
- [ ] 单测：过滤、空过滤返回全部、envelope 格式
- **验证**：`mvn -q -pl nondocx-toolkit test -Dtest=CapabilityToolsTest`；demo 端到端可调用

### 切片 4：构建期 capabilities.json 生成

- [ ] 实现 `CapabilityManifestGenerator.main(String[] args)`：收集 → 写 `capabilities.json` + `capabilities.digest`
- [ ] nondocx-toolkit/pom.xml 增配 `exec-maven-plugin`（phase=process-classes）
- [ ] `.gitignore` 增加 `nondocx-toolkit/target/classes/capabilities.json`、`capabilities.digest`
- [ ] 单测 `CapabilityManifestGeneratorTest`：生成到 tmpdir，断言文件存在 + JSON 可回解析
- **验证**：`mvn -q -pl nondocx-toolkit process-classes` 后 `ls target/classes/capabilities.*` 存在

### 切片 5：CI 契约测试

- [ ] `CapabilityContractTest.every_tooldef_has_toolcapability`：收集 7 工具类，断言零遗漏（构建期收集器已抛异常，这里做 56+1 计数断言）
- [ ] `CapabilityContractTest.declared_capability_has_test_coverage`：扫描测试源码，按工具名匹配调用点；未覆盖工具失败（首次输出报告，task 前补齐或标 EXPERIMENTAL 豁免）
- [ ] `CapabilityContractTest.enum_values_parseable`：每个 `enumValues` 声明值有接受测试
- [ ] `CapabilityDigestTest.digest_stable_when_unchanged`：同输入同 digest
- **验证**：`mvn -q -pl nondocx-toolkit verify`

### 切片 6：全量验证与文档

- [ ] `mvn -q verify` 全绿（含 spotless）
- [ ] `.trellis/spec/backend/` 记录能力契约硬约束（spec update 阶段）
- [ ] 更新 docs/10 todolist 勾选 P0-03 实施清单与验收

## 验证命令

```bash
# 单模块快速反馈
mvn -q -pl nondocx-toolkit compile
mvn -q -pl nondocx-toolkit test -Dtest='Capability*Test'

# 构建期产物
mvn -q -pl nondocx-toolkit process-classes
ls nondocx-toolkit/target/classes/capabilities.*

# 全量
mvn -q verify

# spotless
mvn -q -pl nondocx-toolkit spotless:apply
```

## 风险与回滚点

- **风险 R1：56 工具标注遗漏**。缓解：切片 2 每批编译 + 切片 5 契约测试零遗漏校验（收集期抛异常）。
- **风险 R2：契约测试"未覆盖工具"大量失败**。缓解：首次运行只输出报告，与现有 182 测试比对；task 完成前补齐或标 EXPERIMENTAL 豁免。若覆盖缺口过大，回到 PRD 收窄 R6 强度。
- **风险 R3：exec-maven-plugin 与 Java 11 模块/类路径问题**。缓解：先用测试（surefire）生成验证流程，再迁到 process-classes；若 exec 有问题，回退用 JUnit 测试生成（测试阶段写文件）。
- **风险 R4：嵌套参数标注路径约定不一致**。缓解：design 固定点分路径（`edits.alignment`），收集器校验路径必须以第一个 `@ToolParam(name=X)` 的 X 为前缀。

**回滚点**：每个切片是一个独立 commit；切片 0-1 是纯新增，可随时回退；切片 2 标注是注解叠加，删除注解即回退；切片 3-4-5 是新代码 + pom 配置，git revert 即可。

## 审查门

- 切片 2 完成后（56 工具全标注）：人工抽查 5-10 个工具的标注正确性（operation/element/level 是否合理）。
- 切片 4 完成后：人工检查生成的 `capabilities.json`，确认 elementIndex 聚合合理、无枚举遗漏。
- 切片 5 完成后：确认契约测试报告无意外失败。
