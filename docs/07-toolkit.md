# 07 · nondocx-toolkit

`nondocx-toolkit` 把 nondocx 的 docx 读写能力**封装成 LLM Agent 可调用的工具集**。它不是给人类用的 API（人类直接用 `nondocx-core`），而是给 **Agent × docx** 场景：让 LLM 像操作「文档编辑器」那样读取、编辑、保存 `.docx`。

> 底层基于 nonchain 的 `ToolRegistry` + `@ToolDef`。工具方法调的是 `nondocx-core` 的活对象 API，不重新实现任何 docx 语义。

---

## 1. 它解决什么问题

直接给 Agent 用 `nondocx-core` 不顺手 —— LLM 工具调用需要：

1. **粗粒度、自描述**的操作（不是 `run(0).bold()` 这种细粒度链式）
2. **字符串句柄**定位文档（Agent 不持有 Java 对象引用，靠 `docId`）
3. **错误以字符串返回**（不是抛异常，让 LLM 看到错误消息自己重试）
4. **批量操作**（一次调用处理多个目标，减少 LLM 往返）

`nondocx-toolkit` 把 `Document`/`Paragraph`/`Run` 的活对象 API 包装成满足以上四点的工具方法。

---

## 2. 一个最小可用例子

```java
import com.non.chain.agent.Agent;
import com.non.chain.tool.ToolRegistry;
import com.non.docx.toolkit.DocxToolkit;

// 1) 装上工具集
DocxToolkit toolkit = new DocxToolkit();
ToolRegistry registry = toolkit.scanAll(new ToolRegistry());

// 2) 组装 Agent
Agent agent = Agent.builder(llm, registry)
    .systemPrompt("你是 docx 文档编辑助手……")
    .build();

// 3) 跑起来 —— Agent 会自己调 open_docx / read_paragraph / save_docx 等工具
agent.run("把 /tmp/a.docx 第一段第一个 run 的文本改成 'Hello'");
```

> 取自 [`DocxAgentExample.java`](../nondocx-examples/src/main/java/com/non/docx/examples/agent/DocxAgentExample.java)，可运行（需 `DASHSCOPE_API_KEY`）。

非 Agent 场景也可直接持有 toolkit 的字段逐个调工具方法（见 §6）。

---

## 3. 六组工具 + 一个门面

工具按功能域分成**六个类**，由 `DocxToolkit` 门面统一装配：

| 字段 | 类型 | 职责 |
|---|---|---|
| `session` | `SessionTools` | open/save/close + 段落/表格计数（**会话状态的源头**） |
| `body` | `BodyTools` | 正文 run / 超链接 / 文本搜索 |
| `table` | `TableTools` | 单元格读 / 单元格内 run 读改 |
| `headerFooterToc` | `HeaderFooterTocTools` | 页眉页脚 + 目录（只读） |
| `trackedChangeQuery` | `TrackedChangeQueryTools` | 修订查询 / accept / reject |
| `trackedChangeAuthoring` | `TrackedChangeAuthoringTools` | 修订创作：insert/delete/replace/move/mark |

### 为什么需要门面（会话状态共享）

六个工具类必须**共享同一份** `sessions`/`seq`：

> Agent 在一轮对话里 `open_docx`（SessionTools）打开的文档，紧接着 `read_paragraph`（BodyTools）、`list_tracked_changes`（TrackedChangeQueryTools）都要能按 `docId` 取回。

门面负责把 `SessionTools` 自建的会话状态**注入**给其它五个类：

```java
// DocxToolkit 构造函数内部
this.session = new SessionTools();                          // 自建 sessions/seq
this.body = new BodyTools(session.sharedSessions(), session.sharedSeq());   // 注入
this.table = new TableTools(session.sharedSessions(), session.sharedSeq());
// ... 其余三类同样注入
```

`scanAll(registry)` 把六个类逐一 scan 进同一个 registry，让 Agent 一次会话能调任意一组工具。

> **线程模型**：为单 Agent 实例设计，内部状态未做并发保护，**不要跨 Agent 共享**同一个 `DocxToolkit`。

---

## 4. 工具清单（按组）

### SessionTools（会话）

| 工具 | 作用 |
|---|---|
| `open_docx` | 打开文件，返回 `docId` 句柄 |
| `save_docx` | 按 `docId` 保存到 `output_path`（覆盖写） |
| `close_docx` | 关闭并释放会话（幂等） |
| `get_paragraph_count` | 正文段落数 |
| `get_table_count` | 正文表格数 |

### BodyTools（正文）

| 工具 | 作用 | 批量 |
|---|---|---|
| `read_paragraph` | 读段落（含 run 列表） | ✅ `paragraph_indexes` 数组 |
| `read_run` | 读单个 run | ✅ `runs` 数组 |
| `replace_run_text` | 改 run 文本 | ✅ `edits` 数组 |
| `append_paragraph` | 末尾追加段 | ✅ `texts` 数组 |
| `read_hyperlink` | 读超链接 | 单条 |
| `update_hyperlink` | 改超链接 text/url（都可选） | 单条 |
| `search_text` | 跨容器文本搜索（页眉页脚也命中） | 返回多结果 |

### TableTools（表格）

| 工具 | 作用 | 批量 |
|---|---|---|
| `read_table_cell` | 读单元格 | ✅ `cells` 数组 |
| `read_table_cell_run` | 读单元格内 run | 单条 |
| `replace_table_cell_run_text` | 改单元格 run 文本 | ✅ `edits` 数组 |

### HeaderFooterTocTools（页眉页脚 + 目录，只读）

| 工具 | 作用 |
|---|---|
| `read_header` | 读页眉段落 |
| `read_footer` | 读页脚段落 |
| `read_toc` | 读首个目录的条目 |

### TrackedChangeQueryTools（修订查询 + 处理）

| 工具 | 作用 | 批量 |
|---|---|---|
| `get_tracked_changes_enabled` | 查修订开关 | — |
| `set_tracked_changes_enabled` | 改修订开关 | — |
| `list_tracked_changes` | 枚举修订 | — |
| `get_tracked_change` | 按 id 取单条 | — |
| `accept_text_or_move_revision` | accept 文本/移动类 | ✅ `ids` |
| `reject_text_or_move_revision` | reject 文本/移动类 | ✅ `ids` |
| `accept_all_text_revisions` / `reject_all_text_revisions` | 全部 accept/reject | — |
| `accept_text_revisions_by_author` / `reject_text_revisions_by_author` | 按作者 | — |
| `accept_property_change` / `reject_property_change` | 属性类 | ✅ `ids` |
| `accept_cell_change` / `reject_cell_change` | 单元格类 | ✅ `ids` |

> 修订的 family gate / 异常契约 与 core 一致，详见 [05/03 accept-reject](./05-tracked-changes/03-accept-reject.md)。

### TrackedChangeAuthoringTools（修订创作）

| 工具 | 作用 | 批量 |
|---|---|---|
| `insert_tracked_run` | tracked 插入 | ✅ `edits`（共享 author） |
| `delete_run_tracked` | tracked 删除 | ✅ `edits` |
| `replace_run_tracked` | tracked 替换 | ✅ `edits` |
| `mark_style_change_tracked` | rPrChange 创作（bold/italic/color） | 单条 |
| `mark_cell_inserted` / `mark_cell_deleted` | cell 存亡 | ✅ `cells` |
| `move_run_tracked` | 移动 run | 单条 |

---

## 5. 单次 vs 批量调用（v2 升级）

核心工具支持**一次操作多个目标**。单次调用时传长度 1 的数组即可，调用方不用区分两套 API。

**批量示例**（一次改三个 run）：

```json
replace_run_text({
  "doc_id": "d1",
  "edits": [
    {"paragraph_index": 0, "run_index": 0, "text": "新文本1"},
    {"paragraph_index": 0, "run_index": 2, "text": "新文本2"},
    {"paragraph_index": 1, "run_index": 0, "text": "新文本3"}
  ]
})
```

- **部分失败不中断**：批量操作中某条失败不影响其它条，返回每条的成功/失败明细
- **同段删/换多个 run 不索引漂移**：`delete_run_tracked`/`replace_run_tracked` 自动按从后往前顺序处理、自动去重
- **减少 LLM 往返**：要同时改/读多处时优先用批量

> 这套批量能力是 toolkit 相对于直接用 core 活对象 API 的主要价值之一 —— LLM 一轮思考能处理多个目标，不必为每个 run 单独发起一次工具调用。

---

## 6. 两套工作方式：直接编辑 vs 修订模式

`DocxAgentExample` 的 SYSTEM_PROMPT 把 Agent 工作方式显式分成两套：

| | 直接编辑 | 修订模式（tracked changes） |
|---|---|---|
| 工具 | `replace_run_text` / `replace_table_cell_run_text` / `update_*` | `insert_tracked_run` / `replace_run_tracked` / `delete_run_tracked` / `mark_*` |
| 效果 | 改动立即生效、不留痕 | 改动以修订标记写入，可审阅后定稿 |
| 适用 | 用户要「直接改好」 | 用户要审阅改动、可选接受/拒绝 |

Agent 按用户意图选择。配合 `search_text` 先定位再改，能显著降低 LLM 越界率（避免逐段 `read_paragraph` 盲读）。

---

## 7. 非 Agent 用法（直接持有字段）

测试或非 Agent 场景可直接调工具方法：

```java
DocxToolkit tk = new DocxToolkit();
String docId = tk.session.openDocx("/path/to/a.docx");
System.out.println(tk.body.readParagraph(docId, List.of(0)));  // 读第 0 段
System.out.println(tk.trackedChangeQuery.listTrackedChanges(docId));  // 枚举修订
tk.session.closeDocx(docId);
```

工具方法的 Java 签名就是带 `@ToolDef` 注解的 public 方法，参数对应工具 schema 的字段。

---

## 8. Maven 坐标

```xml
<dependency>
  <groupId>com.non</groupId>
  <artifactId>nondocx-toolkit</artifactId>
  <version>0.0.1-SNAPSHOT</version>
</dependency>
```

传递引入 `nondocx-core`。Agent 框架（nonchain）需消费方自行引入。

---

## 9. 可运行示例

| 示例 | 演示 |
|---|---|
| [`DocxAgentExample.java`](../nondocx-examples/src/main/java/com/non/docx/examples/agent/DocxAgentExample.java) | 两段流程：读结构 → 执行编辑（直接编辑 + 修订模式） |
| [`InteractiveDocxAgentExample.java`](../nondocx-examples/src/main/java/com/non/docx/examples/agent/InteractiveDocxAgentExample.java) | 交互式对话，不限迭代次数 |

需 `DASHSCOPE_API_KEY` 环境变量（阿里云灵积平台 API Key）。未设置时模块仍可编译，只是端到端跑不起来。

---

## 下一步

- 想知道 Agent 调工具时 docx 失败怎么呈现给 LLM → [08 · 异常与 raw 领地](./08-exceptions-and-raw.md)
- 想了解工具背后的修订语义 → [05 · tracked changes 教程](./05-tracked-changes/README.md)
- 查具体方法 → [03 · API 速查](./03-api-reference.md)
