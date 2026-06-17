# 技术设计 — examples 中新增 nonchain Agent docx 示例

> 配套文档：`prd.md`（需求与验收）。本文件只讲**技术边界、数据流、契约与取舍**。

---

## 1. 总体架构与边界

本任务横跨**两个模块**，改动必须分成两层，不要互相渗透：

```
nondocx-core   ← 只补"超链接可写"的最小公开封装（被动需求）
nondocx-examples ← 新增 Agent 示例 + 样例文档 + nonchain 依赖（主动交付）
```

### 1.1 nondocx-core 的改动（最小、可选、可独立回滚）

**只做一件事**：给 `Hyperlink` 补上最小可写能力，支撑 example 不必下探 `raw()`。

- 文件：`nondocx-core/src/main/java/com/non/docx/core/api/text/Hyperlink.java`
- 新增方法（签名见 §4）：
  - `Hyperlink text(String text)` —— 改显示文本
  - `Hyperlink url(String url)` —— 改目标 URL
- 约束（来自 `.trellis/spec/backend/poi-bridge.md`）：
  - holding wrapper，无缓存；setter 直接写入 `XWPFHyperlinkRun` 委托。
  - 返回 `this`（fluent mutator，见 quality-guidelines.md §Mutator style）。
  - POI 异常在公开方法上包装为 `DocxOperationException`（或对应类型），**不**泄露 `org.apache.poi.*`。
  - 全中文 Javadoc + `@throws` 指向 `Docx*Exception`。
  - `equals`/`hashCode` 语义**不变**（仍比较 `text()` + `url()`，因为 setter 写委托后 `text()`/`url()` 读回的就是新值）。

**为什么不在 core 里加更多**：本任务不是 docx 能力大扩展，core 只为"让 example 的工具链干净"而补最小一环。其余 docx 能力（图片、页眉页脚、修订…）仍是 Out of Scope。

### 1.2 nondocx-examples 的改动（本任务主交付）

新增内容：

| 类型 | 位置 | 说明 |
|------|------|------|
| Maven 依赖 | `nondocx-examples/pom.xml` | 新增 `com.non:chain:0.8.4` |
| Agent 工具类 | `src/main/java/com/non/docx/examples/agent/DocxAgentTools.java` | 一个有状态实例，承载所有 `@ToolDef` 方法 |
| Agent 示例类 | `src/main/java/com/non/docx/examples/agent/DocxAgentExample.java` | `main`，分两段演示 |
| 样例输入文档 | `src/main/resources/document/sample-agent-input.docx` | 固定样例，含段落 / run / 表格 / 超链接 |

> `src/main/resources/` 是本模块**首次**新增的目录。现有 examples 都是"运行时生成"，没有 resources。本任务引入固定样例输入，需要建这个目录。

---

## 2. 数据流：文档会话模型（docId）

决策点（prd Confirmed Facts）：工具以**有状态会话**工作，而不是每次传文件路径。

```
main                                    DocxAgentTools (单实例)
 │                                        │
 │  agent.run("...")                       │ 内部持 Map<String, Document> sessions
 │   └─ Agent 循环 ──> 调用 @ToolDef 方法   │
 │                         │               │
 │                         ▼               │
 │             open_docx(path)  ──────────► Docx.open(path) → 放入 sessions，返回 docId
 │             read_*(docId, …) ──────────► sessions.get(docId) 读
 │             replace_*(docId, …) ───────► sessions.get(docId) 改（活对象，直写）
 │             save_docx(docId, out) ─────► sessions.get(docId).save(out)
 │             close_docx(docId) ─────────► sessions.get(docId).close() + remove
```

关键点：

- `docId` 是一个**字符串句柄**（示例里用自增计数转字符串即可，例如 `"doc-1"`）。它是 LLM 在多步工具调用间持有的"文档引用"。
- 所有读/写工具**不再传文件路径**，只传 `docId` + 索引。这正是"细粒度编辑器"模型。
- `Document` 是 nondocx 的活对象包装；一次 `open`，后续读改写都走同一个底层 POI 文档，`save` 才落盘。这与 poi-bridge.md §Rule 1（holding wrapper、活对象直写）完全吻合。

---

## 3. 工具契约（@ToolDef 清单）

声明风格已定：`@ToolDef` / `@ToolParam` 注解扫描（一个实例类）。

### 3.1 工具命名与职责

> 设计取舍：**计数类工具不单独开**。`read_*` 工具在返回值里直接带"结构摘要"（run 数、超链接数、子段落数等），让 LLM 一次读到上下文，减少工具总数、降低选错率。仅"总数级"的计数（段落总数、表格总数）单独保留，因为 Agent 需要先知道边界。

#### A. 文档会话
| 工具 | 入参 | 返回 |
|------|------|------|
| `open_docx` | `path` | `docId` 或错误信息 |
| `save_docx` | `docId`, `output_path` | 保存结果（路径） |
| `close_docx` | `docId` | 关闭结果 |

#### B. 正文段落 / run
| 工具 | 入参 | 返回 |
|------|------|------|
| `get_paragraph_count` | `docId` | 段落数 |
| `read_paragraph` | `docId`, `paragraph_index` | 文本 + run 数 + 是否含超链接（结构摘要） |
| `read_run` | `docId`, `paragraph_index`, `run_index` | 文本 + 样式摘要 |
| `replace_run_text` | `docId`, `paragraph_index`, `run_index`, `text` | 结果 |
| `append_paragraph` | `docId`, `text` | 结果 |

#### C. 表格（下钻到 cell 内 paragraph / run）
| 工具 | 入参 | 返回 |
|------|------|------|
| `get_table_count` | `docId` | 表格数 |
| `read_table_cell` | `docId`, `table_index`, `row_index`, `cell_index` | 文本 + 段落数（结构摘要） |
| `read_table_cell_run` | `docId`, `table_index`, `row_index`, `cell_index`, `paragraph_index`, `run_index` | 文本 |
| `replace_table_cell_run_text` | `docId`, `table_index`, `row_index`, `cell_index`, `paragraph_index`, `run_index`, `text` | 结果 |

> 表格的寻址链 `table → row → cell → paragraph → run` 较深。`read_table_cell` 返回结构摘要（几个段落、各段几个 run），让 LLM 不必盲猜索引，降低越界错误。

#### D. 超链接（显示文本 + URL 双向改）
| 工具 | 入参 | 返回 |
|------|------|------|
| `read_hyperlink` | `docId`, `paragraph_index`, `hyperlink_index` | `{text, url}` |
| `update_hyperlink_text` | `docId`, `paragraph_index`, `hyperlink_index`, `text` | 结果 |
| `update_hyperlink_url` | `docId`, `paragraph_index`, `hyperlink_index`, `url` | 结果 |

> 超链接是段落 `inlineElements()` 里的一类。本任务不要求 Agent 自行计算 `hyperlink_index`——`read_paragraph` 的摘要会报告"该段含超链接"，第一版约定每段至多演示一个超链接，避免索引歧义（见 §5 样例文档约束）。

### 3.2 工具返回值约定

- 所有工具**统一返回 `String`**（`ToolRegistry` 走 `result.toString()`）。结构化信息用**短而稳定**的纯文本格式（如 `key: value` 多行），不引入 JSON 依赖，避免 example 变重。
- 越界 / docId 不存在：返回**中文错误描述字符串**（如 `"错误：段落索引 5 越界（共 3 段）"`)，而不是抛异常——Agent 能把错误读回并自行修正，更符合 Agent 循环语义。

### 3.3 工具类结构

```java
public final class DocxAgentTools {
  private final Map<String, Document> sessions = new HashMap<>();
  private final AtomicInteger seq = new AtomicInteger();

  @ToolDef(name = "open_docx", description = "打开一个 .docx 文件，返回文档句柄 docId")
  public String openDocx(@ToolParam(name="path", description="文档路径") String path) { ... }

  @ToolDef(name = "read_paragraph", description = "读取正文第 paragraph_index 段的结构摘要")
  public String readParagraph(
      @ToolParam(name="doc_id", description="文档句柄") String docId,
      @ToolParam(name="paragraph_index", description="段落索引(0 起)") int index) { ... }
  // … 其余工具
}
```

注意 `scan()` 用 `getDeclaredMethods()`，所以**所有 `@ToolDef` 方法必须直接声明在这个类里**，不能放父类。

---

## 4. nondocx-core Hyperlink 最小封装设计

### 4.1 新增签名

```java
/** 设置超链接的可见文本，返回 this。 */
public Hyperlink text(String text);

/** 设置超链接的目标 URL，返回 this。 */
public Hyperlink url(String url);
```

### 4.2 OOXML / POI / nondocx 三层说明（实现时按教学指南讲）

- **OOXML**：显示文本在 `w:hyperlink` 内的 `w:r` 里；目标 URL 不在元素上，而在文档关系部分，通过 `r:id` 引用。
- **POI**：
  - 改文本：`XWPFHyperlinkRun` 继承自 `XWPFRun`，`setText(...)` 改的是内部 run 文本（路径干净）。
  - 改 URL：**这是已知脏活**。POI 没有直接改关系的 API；`XWPFHyperlinkRun.getHyperlink(doc)` 只读。改 target 需要：取 `rId` → `document.getPackagePart().removeRelationship(rId)` → `addExternalRelationship(newUrl, XWPFRelation.HYPERLINK, …)`。
- **nondocx 封装动机**：把上述脏活藏进 `Hyperlink.url(...)`，对外只暴露一个中文 fluent setter，example 工具不碰 POI。

### 4.3 风险标记（implement 时必须用最小 POI cross-reference 测试验证）

> ⚠️ POI 改超链接 URL 的精确 API（`removeRelationship` + `addExternalRelationship`）在 5.2.5 上的行为需实测确认，重点验证：
> 1. 改 URL 后 `save → reopen → Hyperlink.url()` 读回新值。
> 2. rId 复用/重分配是否破坏文档关系完整性（POI 可能拒绝指定旧 rId）。
> 3. 若 POI 无法干净复用 rId，回退策略：保留 `update_hyperlink_text`（干净），`update_hyperlink_url` 标注为"受 POI 限制，需重建关系"并在 core 里用最小实现 + 测试兜底。
>
> 该验证结果**必须**沉淀为 nondocx-core 的一个 round-trip 测试（quality-guidelines.md §Testing Requirements），并视情况补一条 poi-bridge.md 的 gotcha 条目（走 trellis-update-spec）。

---

## 5. 样例输入文档（sample-agent-input.docx）

固定样例，决定 Agent 第一段"读"能看到什么、第二段"改"改什么。

结构约束（让示例稳定、索引无歧义）：

1. **正文 3~4 个段落**：
   - 1 个标题段（可选，含 1 个 run）。
   - 1~2 个普通段落，其中至少 1 段含**多个 run**（演示 `replace_run_text`）。
   - 1 个段落含**恰好 1 个超链接**（演示超链接读写）。
2. **1 个表格**：
   - 表头行 + 2~3 数据行，单元格内为单段落单 run（便于 `replace_table_cell_run_text` 演示）。
   - 至少一个单元格含 2 个 run，演示 cell 内 run 寻址。
3. 全中文内容，贴近"项目周报 / 技术说明"这类自然语境，让 LLM 的读取/编辑指令有现实感。

**样例文档怎么产生**：用 nondocx 现有 examples 风格（`Docx.create()` + builder）写一个**一次性生成脚本**，把 `.docx` 产物提交进 `src/main/resources/document/`。生成脚本本身**不**作为正式 example，可放在 `src/test/java/.../SampleDocGenerator` 或临时 main，产物入库后脚本可删/留作 fixture。

> 取舍：二进制 `.docx` 入库会增加一点仓库体积，但它让示例"打开已有文档"的语义最真实，也避免示例每次运行还要先造文档。与 nonchain 的 `chain-example` 放 `sample.docx` 的先例一致。

---

## 6. 示例 main 的两段流程

```java
public final class DocxAgentExample {
  public static void main(String[] args) {
    LLM llm = new DashscopeLLM("qwen-plus");          // 走环境变量 DASHSCOPE_API_KEY
    DocxAgentTools tools = new DocxAgentTools();
    ToolRegistry registry = new ToolRegistry().scan(tools);

    Agent agent = Agent.builder(llm, registry)
        .systemPrompt(SYSTEM_PROMPT)                  // 中文，约束 Agent 只用这些 docx 工具
        .maxIterations(8)                             // 两段各留足够轮次
        .callback(loggingCallback())                  // 打印 LLM/Tool 事件，便于教学观察
        .build();

    // ===== 第一段：读取 =====
    Path input = copyResourceToWorking("document/sample-agent-input.docx");
    agent.run("读取并汇报这份文档的结构：先用 open_docx 打开 <path>，"
            + "再查看段落数、表格数，读取关键段落、表格单元格和超链接，最后 close_docx。"
            + "用简洁中文汇报。".replace("<path>", input.toString()));

    // ===== 第二段：编辑并保存 =====
    Path output = ExamplePaths.outputDir().resolve("agent-edited.docx");
    agent.run("打开文档 <path>，执行以下修改后保存为 <out>："
            + "1) 把第 N 段第 1 个 run 文本改成 '...';"
            + "2) 修改表格 (1,1) 单元格的 run 文本为 '...';"
            + "3) 把某超链接的显示文本改成 '...'、目标 URL 改成 'https://...';"
            + "完成后 save_docx 并 close_docx。"
            .replace("<path>", input.toString())
            .replace("<out>", output.toString()));

    System.out.println("输出: " + output.toAbsolutePath());
  }
}
```

设计要点：

- **资源 → 工作目录**：样例在 jar 内（classpath），Agent 工具按文件路径打开，所以 `main` 先把 classpath 资源复制到 `target/examples-output/` 下再传路径给 Agent。避免 Agent 自己处理 classpath。
- **system prompt**：中文，明确告诉 Agent "你只能通过这些 docx 工具操作文档，不要编造路径，路径由用户消息给出"。
- **maxIterations**：两段都给够轮次（细粒度工具一轮可能调多个），默认 8。
- **callback**：复刻 `AgentLoopExample` 的日志回调，打印每轮 LLM/Tool 事件，体现"Agent/Tool 循环"。

### 6.1 稳定性取舍

真实 LLM 不确定。为让示例"大概率跑通且可观测"：

- 两段 prompt **预先给定路径和具体索引**（不依赖 Agent 自己数段落），降低越界率。
- 工具返回中文错误串而非抛异常，让 Agent 能自我修正。
- 不在 `main` 里断言 Agent 的自然语言输出内容（那是不可复现的），只断言**输出文件已生成**这一客观事实。

---

## 7. 依赖与构建

```xml
<!-- nondocx-examples/pom.xml 新增 -->
<dependency>
  <groupId>com.non</groupId>
  <artifactId>chain</artifactId>
  <version>0.8.4</version>
</dependency>
```

- `0.8.4` 为当前 release，已在本地 `~/.m2` 可解析（prd Confirmed Facts）。
- 不在父 `pom.xml` 的 `<dependencyManagement>` 里建版本属性**亦可**；但为风格统一，推荐在父 pom 加 `<chain.version>0.8.4</chain.version>` + dependencyManagement 条目（与现有 `poi.version` 等一致）。
- `chain` 会传递引入 openai-java SDK 等；确认不与 nondocx 现有 POI 版本冲突（POI 5.2.5，chain-document 也用 5.2.5，但本示例只引 `chain` 不引 `chain-document`，需在 implement 阶段跑 `mvn dependency:tree` 确认 POI 单一版本）。

---

## 8. 兼容性 / 回滚

- **core Hyperlink setter 是纯增量**：不改任何现有方法签名、不改 equals 语义。回滚 = 删两个方法 + 删测试，零影响。
- **examples 新增独立类 + 资源 + 依赖**：不动现有 examples。回滚 = 删新增文件 + 删 pom 依赖行。
- 本任务**不**改 `ExamplePaths`（沿用 `outputDir()`）。

---

## 9. 主要 Trade-off 汇总

| 决策 | 选择 | 代价 |
|------|------|------|
| 细粒度 + 会话模型 | docId 状态 | 工具多、类大；但最贴合"文档编辑器 Agent"语义 |
| 超链接改 URL 进 core | 补 `Hyperlink.url(...)` | 撞 POI 关系脏活（§4.3 风险）；但让 example 干净 |
| 样例 .docx 入库 | 提交二进制 | 仓库体积略增；换来"打开已有文档"真实语义 |
| 计数并入 read 摘要 | 减少工具数 | read 返回稍长；换来 Agent 选错率降低 |
| 工具返回中文错误串 | 不抛异常 | Agent 可自我修正；但需在 prompt 里说明"遇错误字符串请重试" |

---

**Language**: 本设计文档及所有交付代码均使用**中文**。
