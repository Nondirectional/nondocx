# Design: P0-03 机器可读能力契约

## 架构总览

```
@ToolDef/@ToolParam (nonchain, RUNTIME)          ← 已有，反射可读 name/description/required
        +
@ToolCapability / @ParamCapability / @NestedParamCapability  ← 本任务新增，补 operation/element/level/type/enum/unit
        ||
        ▼
CapabilityCollector (反射收集器)
        ||
        ▼
CapabilityManifest (内存模型: version + digest + tools[] + elementIndex)
        ||
        +—→ describe_capabilities 工具 (运行时, ToolResult<CapabilityManifest> envelope)
        +—→ capabilities.json (构建期产物, Maven process-classes 阶段生成)
        +—→ CI 契约测试 (校验 manifest 与实现一致)
```

单一真实来源 = Java 代码 + 项目注解。manifest 是**只读派生产物**，从不手编，从不反向生成代码。

## 包结构

新增包 `com.non.docx.toolkit.capability`（与 `ref/`、`result/` 平级）：

```
nondocx-toolkit/src/main/java/com/non/docx/toolkit/capability/
├── ToolCapability.java            // @Target(METHOD) 注解
├── ParamCapability.java           // @Target(PARAMETER) 注解
├── NestedParamCapability.java     // @Target(PARAMETER) 可重复注解
├── CapabilityOperation.java       // enum: READ/ADD/UPDATE/REMOVE/QUERY/SESSION/QUALITY
├── CapabilityLevel.java           // enum: STABLE/WORD_ONLY/EXPERIMENTAL
├── ParamType.java                 // enum: STRING/INTEGER/NUMBER/BOOLEAN/ENUM/REF/STRING_ARRAY/OBJECT_ARRAY/PATH
├── model/
│   ├── CapabilityManifest.java    // 顶层: schemaVersion, digest, generatedAt, tools[], elementIndex
│   ├── ToolCapabilityDescriptor.java
│   └── ParamCapabilityDescriptor.java
├── CapabilityCollector.java       // 反射收集器: 7 工具类 → CapabilityManifest
├── CapabilityDigest.java          // 内容 hash (SHA-256 over canonical JSON)
├── CapabilityJsonIo.java          // manifest ↔ JSON 序列化 (复用 P0-02 的 Jackson)
└── CapabilityTools.java           // 第 8 个工具类: describe_capabilities

nondocx-toolkit/src/test/java/com/non/docx/toolkit/capability/
├── CapabilityCollectorTest.java   // 收集器 + 缺注解校验
├── CapabilityContractTest.java    // 契约: 声明的能力有测试覆盖、enum 有解析测试
└── CapabilityDigestTest.java      // digest 稳定性
```

## 注解契约

### `@ToolCapability`（METHOD, RUNTIME）

```java
@Retention(RUNTIME) @Target(METHOD)
public @interface ToolCapability {
  CapabilityOperation operation();                    // READ/ADD/UPDATE/REMOVE/QUERY/SESSION/QUALITY
  String element() default "";                        // 元素名，如 "paragraph"/"table"/"run"；空表示会话/质量元工具
  CapabilityLevel level() default CapabilityLevel.STABLE;
  boolean needsRecalc() default false;                // 是否需 Word/WPS 重新计算
  String since() default "0.0.1";                     // 引入版本
  String[] examples() default {};                     // 调用示例（CLI/JSON 片段）
}
```

**约束**：每个 `@ToolDef` 方法**必须**有且仅有一个 `@ToolCapability`。收集器发现 `@ToolDef` 缺 `@ToolCapability` → 抛 `CapabilityDeclarationException`，构建失败。

### `@ParamCapability`（PARAMETER, RUNTIME）

```java
@Retention(RUNTIME) @Target(PARAMETER)
public @interface ParamCapability {
  ParamType type();                                   // STRING/INTEGER/ENUM/...
  String[] enumValues() default {};                   // type=ENUM 时的合法值
  String unit() default "";                           // 单位，如 "twip"/"pt"/"percent"
  String defaultValue() default "";                   // 默认值（文档化，非运行时注入）
}
```

与 `@ToolParam` 同位标注。`name`/`description`/`required` 仍由 `@ToolParam` 提供，本注解只补 nonchain 缺失维度，不重复。

### `@NestedParamCapability`（PARAMETER, RUNTIME, 可重复）

```java
@Retention(RUNTIME) @Target(PARAMETER) @Repeatable(NestedParamCapabilities.class)
public @interface NestedParamCapability {
  String path();                    // 点分路径，如 "edits.alignment" / "edits.paragraph_index"
  ParamType type();
  String[] enumValues() default {};
  String unit() default "";
  String defaultValue() default "";
}
```

用于 `List<Map<String,Object>>` 嵌套对象参数（如 `update_paragraph_alignment` 的 `edits`）。收集器把 `@NestedParamCapability` 展开为 `ParamCapabilityDescriptor.nested[]`。

## 模型

### `CapabilityManifest`

```java
public final class CapabilityManifest {
  String schemaVersion;              // "nondocx-capability/v1"
  String digest;                     // SHA-256 over canonical tools JSON
  String generatedAt;                // ISO-8601
  List<ToolCapabilityDescriptor> tools;
  Map<String, List<String>> elementIndex;  // element → [tool names]，自动聚合
}
```

### `ToolCapabilityDescriptor`

```java
public final class ToolCapabilityDescriptor {
  String name;                       // @ToolDef.name，如 "update_paragraph_alignment"
  String description;                // @ToolDef.description
  CapabilityOperation operation;
  String element;
  CapabilityLevel level;
  boolean needsRecalc;
  String since;
  List<String> examples;
  List<ParamCapabilityDescriptor> params;
  List<ParamCapabilityDescriptor> nestedParams;  // 来自 @NestedParamCapability
}
```

### `ParamCapabilityDescriptor`

```java
public final class ParamCapabilityDescriptor {
  String name;                       // @ToolParam.name
  String description;                // @ToolParam.description
  ParamType type;
  boolean required;                  // @ToolParam.required
  List<String> enumValues;
  String unit;
  String defaultValue;
}
```

## CapabilityOperation 枚举映射

覆盖现有 56 工具的全部动词：

| 枚举 | 覆盖的动词前缀 |
|---|---|
| `READ` | read_/get_/search_/list_ |
| `ADD` | insert_/add_/create_ |
| `UPDATE` | update_/set_/replace_/merge_/mark_/move_/apply_ |
| `REMOVE` | remove_/delete_ |
| `QUERY` | get_tracked_changes_enabled 等开关查询 |
| `SESSION` | open_docx/save_docx/close_docx/doc_id/path |
| `QUALITY` | check_quality |

## CapabilityCollector 收集流程

```java
public final class CapabilityCollector {
  // 输入：DocxToolkit 的 7 个工具实例（复用现有字段）
  public static CapabilityManifest collect(Object... toolInstances) {
    // 1. 对每个 toolInstance，反射所有 @ToolDef 方法
    // 2. 每个方法要求有 @ToolCapability，否则抛异常
    // 3. 合并 @ToolDef(name,desc) + @ToolCapability(op,elem,level,...) + @ToolParam(name,desc,req) + @ParamCapability(type,enum,...) + @NestedParamCapability
    // 4. 构建 ToolCapabilityDescriptor
    // 5. 聚合 elementIndex
    // 6. 计算 digest (canonical JSON 的 SHA-256)
  }
}
```

**关键校验点**（收集期，构建即失败）：
- `@ToolDef` 方法无 `@ToolCapability` → 失败。
- `@ParamCapability.enumValues` 非空但 `type != ENUM` → 失败（枚举值必须配 ENUM 类型）。
- `@ParamCapability` 标在非 `@ToolParam` 参数上 → 失败。

## describe_capabilities 工具

新增 `CapabilityTools`（第 8 个工具类），在 `DocxToolkit` 构造时注入 7 个工具实例引用（仅用于反射，不参与会话状态）：

```java
@ToolDef(name = "describe_capabilities",
    description = "查询 nondocx-toolkit 的能力清单...")
@ToolCapability(operation = CapabilityOperation.QUERY, element = "", level = STABLE)
public String describeCapabilities(
    @ToolParam(name = "element", description = "按元素过滤，如 paragraph/table", required = false)
        String element,
    @ToolParam(name = "operation", description = "按操作过滤 READ/ADD/UPDATE/REMOVE", required = false)
        String operation,
    @ToolParam(name = "level", description = "按稳定性过滤 STABLE/WORD_ONLY/EXPERIMENTAL", required = false)
        String level) {
  // 过滤 manifest，返回 ToolResult<CapabilityManifest> envelope (P0-02)
}
```

**注意**：`describe_capabilities` 自身不注入到被反射的 7 个工具类里（避免自引用循环）；它的 `@ToolCapability` 仍参与完整 manifest，但不持有会话状态。

`DocxToolkit.scanAll` 增加 `.scan(capability)`。

## 构建期 capabilities.json 生成

**方案**：绑定到 `process-classes` 阶段的主类生成器，不引入新 maven 插件。

```
nondocx-toolkit/src/main/java/com/non/docx/toolkit/capability/CapabilityManifestGenerator.java
  // main(String[] args): args[0]=输出目录
  // 收集 7 工具类 → 写 capabilities.json + capabilities.digest
```

nondocx-toolkit/pom.xml 增配 `exec-maven-plugin`（或复用 `maven-antrun-plugin`）：
```xml
<execution>
  <phase>process-classes</phase>
  <goals><goal>java</goal></goals>
  <configuration>
    <mainClass>com.non.docx.toolkit.capability.CapabilityManifestGenerator</mainClass>
    <arguments><argument>${project.build.outputDirectory}</argument></arguments>
  </configuration>
</execution>
```

输出：`target/classes/capabilities.json` + `target/classes/capabilities.digest`（打入 classpath，运行时 `describe_capabilities` 和 Agent 均可读）。

**不进 git**（加入 .gitignore）：`capabilities.json`/`capabilities.digest` 是构建产物。

## CI 契约测试

### 测试 1：全覆盖校验（`CapabilityCollectorTest`）

```java
@Test void every_tooldef_has_toolcapability() {
  CapabilityManifest m = CapabilityCollector.collect(new SessionTools(), new BodyTools(...), ...);
  // 收集期已抛异常则自然失败；这里额外断言 tools.size() == 56+1(含 describe_capabilities)
}

@Test void no_orphan_param_capability() {
  // @ParamCapability 标在非 @ToolParam 参数上 → 收集抛异常
}
```

### 测试 2：声明的能力有测试覆盖（`CapabilityContractTest`）

- 扫描 `src/test/java`，按工具名（`@ToolDef.name`）匹配调用点（`tk.body.readParagraph(...)` / `toolRegistry` 执行路径）。
- 声明了 `@ToolCapability` 但无任何测试调用的工具 → 测试失败（参考 OfficeCLI `enforcement:strict`）。
- **过渡策略**：首次运行时输出"未覆盖工具"报告，与现有 182 个测试比对；task 完成前补齐或显式标记 `level=EXPERIMENTAL` 豁免。

### 测试 3：枚举值解析（`CapabilityContractTest`）

- 对每个声明 `enumValues` 的参数，断言每个枚举值被实际工具接受（通过最小调用或参数校验路径）。
- 例：`update_paragraph_alignment` 的 `alignment` 声明 `[LEFT,CENTER,RIGHT,JUSTIFY]` → 4 值均有接受测试。

### 测试 4：digest 稳定性（`CapabilityDigestTest`）

```java
@Test void digest_stable_when_unchanged() {
  String d1 = CapabilityCollector.collect(...).digest();
  String d2 = CapabilityCollector.collect(...).digest();
  assertThat(d1).isEqualTo(d2);  // 同一输入同 digest，不含 generatedAt
}
```

digest 计算时**排除 `generatedAt`**，只 hash tools + elementIndex 的 canonical JSON。

## 数据流与复用

- **复用 P0-02**：`describe_capabilities` 返回 `ToolResult<CapabilityManifest>`，经 `ToolResultRenderer` 输出"中文摘要 + JSON 尾段"，与现有 56 工具输出格式一致。
- **复用 P0-02 Jackson**：`CapabilityJsonIo` 用同一 `ObjectMapper`。
- **复用 P0-01**：element 字段值与 `ElementKind` 语义对齐（PARAGRAPH→"paragraph"），不引入新词汇。
- **复用 `DocxToolkit` 现有字段**：收集器直接接收 `DocxToolkit.session/body/...` 7 个实例，无需新构造路径。

## 兼容性与迁移

- 注解纯新增，零运行时开销（反射只在收集时发生，结果可缓存）。
- 56 工具分批标注（按工具类切片：Session→Body→Table→HeaderFooterToc→TrackedChangeQuery→TrackedChangeAuthoring→Quality），每批独立可编译。
- `DocxToolkit` 构造增加 `CapabilityTools` 字段，`scanAll` 增加 `.scan(capability)`。
- `capabilities.json`/`capabilities.digest` 加入 .gitignore，不污染版本库。

## 权衡

- **为什么不用注解处理器（APT）**：APT 在 nondocx 无先例，引入 complexity 高；RUNTIME 反射 + process-classes 主类生成器更轻，且收集器可被测试直接调用。
- **为什么 element 用字符串而非 ElementKind 枚举**：manifest 要序列化为 JSON 供 Agent 读，字符串更稳定；ElementKind 是 Java 内部模型。收集器内部可双向映射校验。
- **为什么 digest 排除 generatedAt**：让 Agent 可靠判断"能力是否变化"而不被时间戳干扰。
