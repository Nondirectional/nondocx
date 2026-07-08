# OnlyOffice 实时预览的 nondocx Agent 对话 demo

## Goal

为 nondocx 制作一个**可交互的前端 demo**:左侧用 OnlyOffice 实时预览 `.docx`,右侧通过自然语言对话框驱动 Agent 编辑文档;Agent 每次 `save_docx` 落盘后,OnlyOffice 自动刷新显示最新内容。

**两种文档来源**:① 启动即就绪的内置样例文档(空白起步);② **用户上传自己的 `.docx`** 接着编辑。两者都通过 Agent 对话操作(用户**不**在 OnlyOffice 里直接编辑 —— OO 仅作只读预览)。

**用户价值**:把 nondocx「库 + Agent 工具集」的完整能力用一个看得见、摸得着的界面展示出来 —— 让访客(和开发者自己)一眼看到「对文档说话 → 文档就变了」的效果,比现有终端 REPL 示例(`InteractiveDocxAgentExample`)直观得多,适合做项目主页 demo / 演示录像。上传自有文档则让访客能立刻拿自己的内容试,而非只能玩内置样例。

## 现状(已通过代码探索确认)

- ✅ `nondocx-toolkit` 已把 docx 读写封装成完整的 `@ToolDef` 工具集(`open_docx`/`save_docx`/`read_*`/`replace_*`/修订/质量检查等),七组工具由 `DocxToolkit` 门面统一装配,共享同一份文档会话。
- ✅ `nonchain` 0.8.4 是项目依赖的自研 Agent 框架,支持**流式事件回调**(`AgentEvent.TextDelta`/`ToolStart`/`ToolEnd`/`Complete`),且支持多轮记忆(`MessageWindowChatMemory`)。**本身不自带 web 能力**(纯库)。
- ✅ `nondocx-examples/agent/InteractiveDocxAgentExample` 已是**终端 REPL 版**的 Agent 对话 —— 本 demo 本质是把它升级为 Web 版,Agent 装配逻辑可几乎照搬。
- ✅ 样例文档已有:`nondocx-examples/src/main/resources/document/sample-agent-input.docx`。
- ✅ OnlyOffice 刷新机制官方确认:**不能直接 reload 同一实例**,需「换 `document.key` → `destroyEditor()` → `new DocsAPI.DocEditor()`」;另有 `onRequestRefreshFile` 事件兜底。

## 技术选型(已与用户确认)

| 决策 | 选定 | 理由 |
|---|---|---|
| 后端框架 | **Javalin 6.x** | 轻量(~1MB)、API 极简、原生支持 SSE 与静态资源托管,贴合 demo 性质 |
| OnlyOffice 部署 | **本地 Docker**(`onlyoffice/documentserver`,`JWT_ENABLED=false`) | 一条命令起服务,社区版免 JWT,demo 最快上手 |
| 代码位置 | **新建 `nondocx-demo` Maven 子模块** | 与 core/toolkit/examples 平级,不污染现有模块;依赖 toolkit 传递引入 core + nonchain |
| 事件推送 | **SSE(Server-Sent Events)** | 单向流,天然贴合 nonchain 的 `AgentEvent` 回调;前端 `EventSource` 即打字机效果,零额外依赖 |
| 前端形态 | **纯静态 HTML/JS** | 无构建链,单 `index.html` + `app.js` 启动即用;demo 性质,后续演进再迁框架 |

## Requirements

### R1 · 后端 web 桥接(Javalin,nondocx-demo 模块)
- 新建 `nondocx-demo` 子模块,`pom.xml` 依赖 `nondocx-toolkit`(传递 core + nonchain)+ javalin + slf4j。
- 父 `pom.xml` 的 `<modules>` 加 `nondocx-demo`;`dependencyManagement` 管控 javalin 版本。
- 装配 nonchain Agent —— **完全复用** `InteractiveDocxAgentExample` 的构造方式(`DashscopeLLM` + `DocxToolkit.scanAll` + `MessageWindowChatMemory` + SYSTEM_PROMPT),不重写任何 Agent 逻辑。
- 路由:
  - `GET /` —— 返回静态首页(`static/index.html`)
  - `GET /api/doc/config` —— 返回 OnlyOffice config(`document.key`/`url`/`title`/`permissions`,permissions.edit=false 预览模式)
  - `GET /api/doc/file` —— 静态托管当前 docx 文件供 OnlyOffice 拉取
  - `POST /api/chat` —— 接收用户消息,SSE 回推 Agent 事件流;**检测到 `save_docx` 落盘成功时**,`document.key` 自增并额外推 `doc_changed` 帧
  - `POST /api/doc/upload` —— **上传用户 `.docx`**(multipart 文件)→ 替换工作文件 → 清空 Agent 记忆 → 返回新 key(让前端刷新)。详见 R6
  - `POST /api/doc/reset` —— 重置回样例文档(新 key,让前端刷新)
- 并发模型:`/api/chat` **串行化**(单 Agent 实例非线程安全,一次只处理一条对话,排队后续请求)—— 符合 demo 单人演示场景。
- 启动时检测 `DASHSCOPE_API_KEY`,缺失则允许启动但前端对话框显示提示(OnlyOffice 仍可预览样例文档)。

### R2 · Agent 事件 → SSE 帧映射
- 把 nonchain `AgentEvent` 子类序列化为一行 `data: {json}` 的 SSE 帧:
  | nonchain 事件 | SSE 帧 | 前端动作 |
  |---|---|---|
  | `TextDelta` | `{"type":"text","delta":"…"}` | 追加到助手气泡(打字机) |
  | `ToolStart` | `{"type":"tool_start","name":…,"arguments":…}` | 显示「正在调用 X」 |
  | `ToolEnd` | `{"type":"tool_end","name":…,"result":…}` | 显示结果;**name=save_docx 且成功 → 触发刷新** |
  | `Complete` | `{"type":"done"}` | 关闭 SSE 连接 |
  | (save_docx 后) | `{"type":"doc_changed","key":N}` | 前端销毁重建编辑器 |

### R3 · OnlyOffice 刷新机制(核心)
- 刷新触发点:后端在 `ToolEnd.name == "save_docx"` 且结果不含「错误」时,`document.key++` 并推 `doc_changed`。
- 前端收到 `doc_changed`:① `docEditor.destroyEditor()` ② 用新 key `new DocsAPI.DocEditor(placeholder, config)` ③ 新实例自动向 `/api/doc/file` 拉最新文件。
- 兜底:前端监听 OnlyOffice 的 `onRequestRefreshFile` 事件,漏接 `doc_changed` 时也能走销毁重建路径,双保险。

### R4 · 前端界面(纯静态)
- 左右分栏:左 OnlyOffice 预览 iframe,右对话窗(消息流 + 输入框)。
- 对话框:用户消息靠右气泡,Agent 文本流式靠左气泡(打字机),工具调用以折叠/灰条展示。
- 输入框 Enter 发送;Agent 处理中禁用输入。
- OnlyOffice 首次加载有 loading 态(首次转换 docx 需 5-10s)。
- 「重置文档」按钮 → `POST /api/doc/reset`。

### R5 · 样例文档与工作目录
- 样例 docx 放 `nondocx-demo/src/main/resources/sample-input.docx`(复制自 examples 模块)。
- 启动时复制到工作目录 `nondocx-demo/target/demo-work/current.docx`,Agent 在对话中 `open_docx` 这个路径。
- `save_docx` 写回同一路径(覆盖)→ OnlyOffice 下次拉取即新版本。
- 工作目录 `target/` 已被全局 gitignore 覆盖,无需额外规则。

### R6 · 上传文档编辑(用户自有 .docx)
**场景**:用户点「上传文档」选一份自己的 `.docx`,Agent 后续基于这份文档操作(读结构、改内容、保存),OnlyOffice 同步预览。

- `POST /api/doc/upload`(multipart/form-data,字段 `file`):
  1. 校验扩展名 `.docx` + 非空 + 大小上限(建议 10MB,demo 防滥用)
  2. 原子写入工作目录 `current.docx`(临时文件 + `Files.move(ATOMIC_MOVE)`)
  3. **清空 Agent 记忆**(`ChatMemory.clear()`)—— 换了文档,旧文档的 docId/段落结构等上下文必须失效,避免 Agent「记着上一个文档的第 3 段」导致越界
  4. 关闭旧文档会话(`DocxToolkit.session.closeDocx(docId)`)—— 释放旧活文档,Agent 下次会重新 `open_docx` 新文件
  5. `document.key++` → 返回 `{ ok, key, filename }`,前端据此刷新 OnlyOffice
- 前端:工具栏「上传文档」按钮(隐藏 `<input type=file accept=".docx">`)+「重置为样例」按钮并列;上传中显示 loading;上传成功后对话框显示系统提示「已加载文档:xxx.docx,可以开始对话」。
- **注意**:用户**不**在 OnlyOffice 里编辑(预览模式 `permissions.edit=false` 不变);所有编辑都经 Agent 对话。上传只是"换一份文档作为 Agent 的操作对象"。
- 校验失败(非 docx / 超限 / 损坏)返回中文错误,前端红字提示,**不**清空当前会话(保留原文档可继续用)。

## Acceptance Criteria

- [ ] `mvn -q -pl nondocx-demo -am package` 编译通过(JDK 11+)
- [ ] `docker run` 起的 OnlyOffice healthcheck 通过(`http://localhost:9090/healthcheck`)
- [ ] 后端启动后,浏览器打开 `http://localhost:8080`,左侧 OnlyOffice 正确加载并显示样例 docx
- [ ] 右侧对话框输入「把第一段第一句话改成『你好,demo』并保存」,Agent 流式回复可见(打字机效果),工具调用过程可见
- [ ] Agent 调 `save_docx` 成功后,**OnlyOffice 自动刷新**显示新内容(无需手动操作)
- [ ] 连续 3+ 轮对话(每轮都 save)后,OnlyOffice 仍能正确刷新(验证 key 自增链路稳定)
- [ ] 点「重置文档」按钮,文档回到样例状态
- [ ] 点「上传文档」选一份自有 `.docx` → 上传成功 → OnlyOffice 刷新显示新文档 → 后续对话(如「告诉我它有几段」)Agent 基于新文档回答
- [ ] 上传后 Agent 记忆已清空:先对样例文档对话建立上下文,再上传新文档,Agent **不**引用旧文档的内容(验证 `memory.clear()`)
- [ ] 上传非 `.docx` 文件 → 前端红字报错,当前文档与会话**不受影响**,仍可继续对话
- [ ] 缺 `DASHSCOPE_API_KEY` 时,后端能启动、OnlyOffice 能预览,对话框显示明确提示

## Out of Scope

- ❌ 用户系统 / 多用户 / 权限(demo 单人本地跑)
- ❌ 多文档管理 / 文档列表(单文档会话够演示)
- ❌ OnlyOffice 协同编辑回写(预览模式 `permissions.edit=false`,文档由 Agent 单向修改;用户不在 OO 里编辑)
- ❌ JWT / HTTPS(本地 demo,社区版 OO 禁用 JWT)
- ❌ 前端构建工具 / 框架(纯静态够用)
- ❌ 生产级并发处理(串行化队列已满足 demo)
- ❌ 多 LLM provider 切换(demo 固定 DashScope)

## Resolved Decisions(brainstorm 确认)

| 问题 | 决定 | 依据 |
|---|---|---|
| 前端界面语言 | **全中文** | 项目基调(代码注释/Javadoc/异常消息全中文) |
| Agent system prompt | **照搬 `InteractiveDocxAgentExample.SYSTEM_PROMPT`** | 已迭代成熟,含 search_text 引导、批量工具引导等 |
| LLM 模型 | **`qwen3.7-plus`** | 现有示例已验证可用 |
| 多轮记忆 | **保留**(`MessageWindowChatMemory`,maxMessages=30) | 用户确认 —— 支持指代「刚才那个段落」 |
| 预设快捷指令按钮 | **不加,纯自由输入** | 用户确认 —— 保持简洁 |
| 部署形态 | **本期仅 `mvn exec:java` 本地启动** | 打包分发留后续任务 |
| 端口 | **后端 8080、OO 9090** | 避开常见占用 |

## Open Questions

(无 —— 所有关键决策已收敛。)`

## Notes

- 教学维度(遵循 `.trellis/spec/guides/teaching-approach.md`):实现时每个新概念三层递进 —— OOXML 结构 → POI/OnlyOffice 如何表达 → nondocx demo 为何如此封装。重点讲:`document.key` 的本质、OnlyOffice 协作模型(独立服务 + 回调拉文件)、SSE 与 nonchain 流式回调的对接。
- 本任务为**复杂任务**,完成 brainstorm 后需补 `design.md`(技术设计)与 `implement.md`(执行清单)才能 `task.py start`。
