# examples 中新增 nonchain Agent docx 示例

## Goal

在 `nondocx-examples` 中新增一个基于 `nonchain` Agent 的可运行示例，演示：
- 如何把一组工具注册给 Agent
- Agent 如何通过这些工具获得读取、编辑并保存 `.docx` 的能力
- 该示例如何同时体现 `nonchain` 的 Agent/Tool 循环与 `nondocx` 的文档读写能力

## Progress（实现进度）

> 最后更新：2026-06-17。
> 状态：Phase 1~7 全部完成，待 commit。端到端已用真实 LLM 跑通并核验。

| Phase | 状态 | 说明 |
|-------|------|------|
| 0.1 基线 | ✅ | `578521d chore: spotless 修复 main 既有 Javadoc 格式问题` |
| 0.2 DASHSCOPE_API_KEY | ✅ | len=35（`.zshrc` 已 export；非交互 shell 需 `zsh -ic` 加载） |
| 1.1 三层讲解 | ✅ | OOXML（`w:hyperlink` + `r:id` → `_rels`）、POI（`XWPFHyperlinkRun`）、nondocx 封装动机 |
| 1.2 `Hyperlink.text(String)` | ✅ | 绕过 POI N9：先清空 `CTR` 所有 `<w:t>` 再 `setText` |
| 1.3 `Hyperlink.url(String)` | ✅ | 绕过 POI N10：`XWPFRelation.HYPERLINK.getRelation()`（非 `toString()`）建外部关系 |
| 1.4 HyperlinkTest | ✅ | 共 9 个测试全过 |
| 1.5 core verify | ✅ | spotless 绿 |
| 1.6 沉淀 POI gotcha | ✅ | N9/N10 已写入 `poi-bridge.md`（Phase 7.3） |
| 2.1 父 pom chain.version | ✅ | `<chain.version>0.8.4</chain.version>` + dependencyManagement（**修复了原 XML 结构错位**：dependency 误放到 `</dependencies>` 之外） |
| 2.2 examples pom 加依赖 | ✅ | 版本走父管理 |
| 2.3 / 2.4 编译与依赖树 | ✅ | POI 单一 5.2.5，chain 传递引入 openai-java 4.30.0（无冲突） |
| 3 样例文档 | ✅ | `SampleDocGenerator`（test）+ `sample-agent-input.docx`（resources）+ `SampleDocStructureTest` 结构自检 |
| 4 DocxAgentTools | ✅ | 会话/正文/表格/超链接四组 `@ToolDef` 工具，统一返回 String + 中文错误串 |
| 5 DocxAgentExample | ✅ | 两段流程 + 日志 callback；`maxIterations(20)`（原 8 不够细粒度读取用） |
| 6 端到端 | ✅ | 真实 LLM 跑通，核验 `agent-edited.docx` 三处改动全对 |
| 6.5 N9 通用修复 | ✅ | **端到端暴露 `Run.text` 也有 N9 追加 bug**；在 core 根因修复 + 2 个 round-trip 回归测试；core 共 121 测试 |
| 7.1-7.2 全量验证 | ✅ | `mvn verify` BUILD SUCCESS，spotless 绿 |
| 7.3 验收 + spec | ✅ | Acceptance Criteria 全勾选；poi-bridge.md N9/N10 沉淀 |
| 7.4 commit | ⏳ 待确认 | 见下方 trellis Phase 3.4 |

### 下一次续做的关键信息（避免重新踩坑）

1. **N9 已从 Hyperlink 专用问题升级为通用 Run 修复**：`Run.text(String)` 与 `Hyperlink.text(String)` 现在都用「先清空 CTR 的 `<w:t>` 再 setText」手法。任何未来「替换 run 类文本」的 setter 都必须照此办理（见 poi-bridge.md N9）。
2. **`exec:java` 工作目录是仓库根**（不是模块根），而 surefire 工作目录是模块根——生成脚本与测试里的相对路径需分别对待（生成器用 `nondocx-examples/src/...`，测试用 `src/...`）。
3. **maxIterations 必须 ≥ 16**：细粒度工具下，光第一段读取就需 ~9 轮，原默认 8/10 会超限。示例定为 20。
4. **改 core 后必须重新 `install`**（单模块 `-pl nondocx-examples` 不带 `-am` 时从本地仓库解析 core jar），否则 examples 编译用的是旧 jar。
5. **`Row.cell(String)` 返回 `Row`（链式），不是 `Cell`**；要拿活 Cell 填多 run 用 `cell(Consumer<Cell>)` 配置器，且新 Cell 经 `addCell()` 后内部无段落，需先 `addParagraph()`。
6. **`DocxAgentTools` 不能有私有构造器**——它是有状态工具载体，需被 `new DocxAgentTools()` 实例化。其他 example 类的私有构造器是因为它们只有静态方法。

## Confirmed Facts

- 当前仓库已有 `nondocx-examples` Maven 子模块，现有示例均位于 `nondocx-examples/src/main/java/com/non/docx/examples/`。
- 当前 examples 风格以 `public static void main(String[] args)` 为主，输出文件统一写入 `target/examples-output/`，由 `ExamplePaths` 负责。
- `nondocx-examples` 目前只依赖 `nondocx-core` 与测试依赖，尚未引入 `nonchain`。
- 邻接项目 `/Users/non/Projects/nonchain` 中：
  - Agent 核心依赖为 `com.non:chain`
  - `Agent.builder(llm, registry)` + `ToolRegistry` 是标准接入方式
  - `DashscopeLLM` 同时支持 `new DashscopeLLM("qwen-plus")`（环境变量）和 `new DashscopeLLM(apiKey, "qwen-plus")`（显式传入 API Key）
- 用户已确认：该示例应使用真实在线 LLM，而不是离线假 LLM。
- 用户偏好：API Key 通过环境变量提供；不在本仓库引入本地配置文件 secrets 方案。
- 用户当前意图：在 examples 中加入 nonchain 依赖，并新增一个“组织一系列工具交给 Agent，让 Agent 具备阅读、编辑 docx 文件能力”的示例。
- 示例输入文档采用仓库内固定样例，由示例运行时读取后交给 Agent 处理。
- 用户已确认：Agent 工具采用细粒度方案，而不是粗粒度任务工具。
- 用户已确认：工具以“已打开文档会话”模型工作（如 `open_docx` → `docId` → 读写 → `save/close`）。
- 用户已确认：第一版工具集不仅覆盖正文段落 / run，也要覆盖表格单元格与超链接。
- 用户已确认：超链接在第一版里不仅要可感知，还必须支持改写超链接文本 / 目标 URL。
- 用户已确认：超链接编辑需要同时支持“显示文本 + 目标 URL”双向修改。
- 用户已确认：示例演示流程采用 `main` 中分两段方式，先展示读取，再展示编辑并保存，以提高稳定性与可读性。
- 用户已确认：为支撑示例中的超链接编辑，可顺带为 `nondocx-core` 增加最小公开封装，而不是只在 example 内部使用 `raw()` / POI 绕过公开 API。
- 用户已确认：表格编辑在第一版里也要下钻到“单元格内段落 / run”粒度，与正文保持一致的细粒度模型。
- 依赖可行性已确认：`com.non:chain` 最新发布版本为 `0.8.4`（release，非 SNAPSHOT），且已在开发者本地 `~/.m2` 中可解析，`nondocx-examples` 可直接声明 `com.non:chain:0.8.4` 依赖。

## Requirements

- 在 `nondocx-examples` 中加入使用 `nonchain` Agent 所需的依赖。
- 新增一个示例类，展示把多个 docx 相关工具注册给 Agent。
- 工具集合至少覆盖“读取 docx 内容”和“编辑后保存 docx 内容”这两类能力。
- 示例应与现有 examples 模块风格一致，尽量保持可直接运行、输出路径清晰。
- 涉及 docx 能力讲解时，后续实现与说明需遵守教学式开发指南：先讲 OOXML，再讲 POI 映射，最后讲 nondocx 封装。

## Acceptance Criteria

- [x] `nondocx-examples` 增加了 `nonchain` 相关依赖，模块可编译。
  （父 pom 加 `<chain.version>0.8.4</chain.version>` + dependencyManagement；examples pom 声明 `com.non:chain`；`mvn verify` 全绿；依赖树 POI 单一版本 5.2.5。）
- [x] 存在一个新的、可定位的 Agent 示例类，清晰展示 ToolRegistry / Agent 的组装方式。
  （`com.non.docx.examples.agent.DocxAgentExample`，`ToolRegistry.scan(new DocxAgentTools())` + `Agent.builder(llm, registry)` 组装。）
- [x] 示例中的 Agent 可以通过注册工具完成一次 docx 读取流程。
  （端到端实测：Agent 自动 `open_docx → get_paragraph_count → get_table_count → read_paragraph → read_table_cell → read_hyperlink → close_docx`，并给出结构汇报。）
- [x] 示例中的 Agent 可以通过注册工具完成至少一次 docx 编辑并保存输出文档的流程。
  （端到端实测：`replace_run_text` + `replace_table_cell_run_text` + `update_hyperlink_text` + `update_hyperlink_url` + `save_docx`；核验 `agent-edited.docx` 三处改动全部正确落盘。）
- [x] 示例的运行方式、前置条件（如环境变量、输入文件、输出文件）清晰可见。
  （`DocxAgentExample` Javadoc 标注 `DASHSCOPE_API_KEY` 前置、运行命令、输入资源与输出路径；`SampleDocGenerator` Javadoc 标注如何重新生成样例。）

## Out of Scope

- 把 nondocx 正式封装成通用的 nonchain tool SDK。
- 在本任务中实现复杂多轮工作流、RAG、检索或 GUI。
- 覆盖所有 docx 能力（如图片、页眉页脚、修订、字段的全量 Agent 化操作）。
- 在本仓库引入本地配置文件 / secrets 方案（API Key 走环境变量）。

## Open Questions

（已全部解决，见 Confirmed Facts。）

- 工具声明风格已确认：采用 `@ToolDef` / `@ToolParam` 注解扫描方式（与 nonchain 自有 Agent 示例一致）。