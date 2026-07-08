# Design · OnlyOffice 实时预览的 nondocx Agent 对话 demo

> 配套 `prd.md`。本文件记录技术架构、数据流、契约与关键取舍,供 `implement.md` 落地。

## 1. 架构总览

```
┌───────────────────────────── 浏览器 (http://localhost:8080) ─────────────────────────────┐
│  左侧:OnlyOffice iframe          │  右侧:对话框                                    │
│  ┌─────────────────────────┐     │  ┌──────────────────────────────────────────┐    │
│  │ DocsAPI.DocEditor       │     │  │ 消息流(用户/助手/工具)                  │    │
│  │  document.url → /api/   │     │  │ EventSource(SSE) ← POST /api/chat        │    │
│  │            doc/file     │     │  │ 输入框 + 发送 + 重置按钮                  │    │
│  └─────────────────────────┘     │  └──────────────────────────────────────────┘    │
│         ▲ key 改变时              │           ▲ SSE 帧                               │
│         │ destroyEditor +        │           │ text/tool_start/tool_end/            │
│         │ new DocEditor          │           │ doc_changed/done                      │
└─────────┼────────────────────────┼───────────┼─────────────────────────────────────────┘
          │ HTTP(拉 docx)         │           │
          ▼                        │           ▼
┌──────────────────────────── Javalin 后端 (nondocx-demo:8080) ───────────────────────────┐
│  GET  /api/doc/config   ── 返回 OO config(key/url/permissions)                          │
│  GET  /api/doc/file     ── 托管当前 docx(OO 拉取)                                       │
│  POST /api/chat         ── 驱动 Agent,SSE 回推事件;save_docx 成功 → key++ + doc_changed│
│  POST /api/doc/reset    ── 重置样例文档                                                  │
│                                                                                          │
│  AgentBridge ── nonchain Agent(复用 InteractiveDocxAgentExample 装配)                  │
│  DocSession  ── 单文档会话(docId + 文件路径 + 自增 key + 文件锁)                       │
└──────────────────────────────────┬───────────────────────────────────────────────────────┘
                                   │ save_docx 落盘
                                   ▼
                    target/demo-work/current.docx
                                   ▲
                                   │ OO 经 /api/doc/file 拉取
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│  OnlyOffice Document Server (Docker :9090, JWT_ENABLED=false)                            │
│  社区版,负责 docx → 渲染;前端用其 DocsAPI.DocEditor JS API 装配                         │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

**三服务分离**:浏览器(纯静态前端)、Javalin 后端(8080)、OnlyOffice Docs(9090)各自独立。后端是唯一新增的 Java 代码,OnlyOffice 与浏览器是现成/外部组件。

## 2. 核心契约

### 2.1 SSE 帧格式(`POST /api/chat` 响应)

Content-Type: `text/event-stream`,每帧一行 `data: <json>\n\n`。

| `type` | 字段 | 何时发 | 前端动作 |
|---|---|---|---|
| `text` | `delta: string` | nonchain `TextDelta` | 追加到当前助手气泡(打字机) |
| `tool_start` | `name, arguments` | nonchain `ToolStart` | 在助手气泡下加灰条「🔧 调用 save_docx(…)」 |
| `tool_end` | `name, result` | nonchain `ToolEnd` | 更新灰条为结果;**若 name=save_docx 且 result 不含「错误」→ 后端 key++ 后紧跟发 doc_changed** |
| `doc_changed` | `key: int, url: string` | save_docx 成功后 | 前端 `destroyEditor()` + `new DocEditor(新key)` |
| `error` | `message: string` | nonchain `AgentError` 或后端异常 | 红字显示,不中断对话 |
| `done` | — | nonchain `Complete` | 关闭 SSE,重新启用输入框 |

**SSE 生命周期**:每次 `POST /api/chat` 建立一个 SSE 连接,Agent 一轮 run 结束(Complete/error)即关闭。多轮对话靠 Agent 的 memory 维持,不靠 SSE 长连。

### 2.2 `GET /api/doc/config` 返回

```json
{
  "document": {
    "fileType": "docx",
    "key": "demo-v3",          ← 每次 save/reset 后自增
    "title": "nondocx demo.docx",
    "url": "http://localhost:8080/api/doc/file"
  },
  "documentType": "word",
  "editorConfig": {
    "mode": "view",            ← 预览模式(用户不在 OO 里编辑)
    "lang": "zh-CN"
  }
}
```
> 前端首次加载和每次 `doc_changed` 都会取最新 config(或直接用 doc_changed 帧里的 key 本地拼 config,减少一次往返 —— 见 §4 取舍)。

### 2.3 `POST /api/chat` 请求

```json
{ "message": "把第一段改成『你好』并保存" }
```

### 2.4 `POST /api/doc/reset` —— 无 body,返回 `{ "key": "demo-v5", "ok": true }`

### 2.5 `POST /api/doc/upload`(multipart/form-data,字段 `file`)
- 请求:`multipart/form-data`,字段 `file` = 用户上传的 `.docx`
- 处理(见 R6):校验 → 原子写 `current.docx` → `memory.clear()` + 关旧会话 → key++
- 成功返回:`{ "ok": true, "key": "demo-v6", "filename": "我的报告.docx" }`
- 失败返回(HTTP 400):`{ "ok": false, "error": "仅支持 .docx 文件" }`,**不**改动当前文档与 Agent 会话
- 前端收到成功响应 → 同 `doc_changed` 路径刷新 OnlyOffice(destroyEditor + new DocEditor)

## 3. 数据流

### 3.1 典型一轮对话(编辑)

1. 用户输入「把第一段改成『你好』并保存」→ `POST /api/chat`
2. 后端 `AgentBridge` 把 message 喂给 nonchain `agent.run(message, sseConsumer)`
3. Agent 思考 → `ToolStart(open_docx)` → `ToolEnd` → `ToolStart(read_paragraph)` → `ToolEnd` → `ToolStart(replace_run_text)` → `ToolEnd` → `ToolStart(save_docx)` → `ToolEnd(已保存到 …)`
   - 每个 ToolStart/ToolEnd 实时推 SSE 帧
   - **save_docx 的 ToolEnd 成功时**:后端 `DocSession.nextKey()` + 推 `doc_changed`
4. Agent 输出总结文本(`TextDelta` 流)→ `Complete`
5. 前端收到 `doc_changed` → `destroyEditor` → `new DocEditor(新 key)` → OO 向 `/api/doc/file` 拉新文件 → 渲染
6. 前端收到 `done` → 关闭 SSE,启用输入框

**刷新的精确时机**:`save_docx` 的 `ToolEnd` 成功那一刻,而非 Agent 整轮结束。这样用户在 Agent 还在说总结时,文档就已经刷新了,体感最实时。

### 3.2 上传文档(换操作对象)

1. 用户点「上传文档」选 `我的报告.docx` → `POST /api/doc/upload`(multipart)
2. 后端 `DocSession.upload(bytes, filename)`:
   - 校验扩展名/大小 → 失败返回 400,当前会话不动
   - 写临时文件 → `Files.move(ATOMIC_MOVE)` 原子覆盖 `current.docx`
   - `agentBridge.clearMemory()`(调 `ChatMemory.clear()`)—— 旧文档上下文失效
   - 关闭旧 docId 会话(`session.closeDocx(oldDocId)`),Agent 下次重新 `open_docx`
   - `nextKey()` → 返回新 key + filename
3. 前端收到 → destroyEditor + new DocEditor(新 key)→ OO 拉新文件渲染
4. 前端对话框插入系统提示「已加载文档:我的报告.docx」
5. 用户发「告诉我它有几段」→ Agent `open_docx`(新路径)→ 基于新文档回答

**为什么必须清记忆**:Agent 的 memory 里存着旧文档的「docId=doc-1,第 3 段是…」等上下文。若不清,用户说「改第 3 段」时 Agent 会拿旧 docId 去操作 —— 但旧 docId 已 close,或即使没 close 也指向旧文档,导致越界/改错文件。`clear()` 强制 Agent 重新 `open_docx` + 重新读结构,是最稳的做法。

**为什么还要关旧 docId**:光清 memory 不够 —— 旧 Document 活对象还在 `sessions` map 里占着(内存泄漏 + POI 句柄未释放)。显式 `closeDocx` 干净收尾。

## 4. 关键设计取舍

### 4.1 doc_changed 帧直接带 key vs 前端再拉 config
**选:doc_changed 帧直接带 key + url**,前端本地拼新 config 重建编辑器,省一次 `GET /api/doc/config` 往返。
- 代价:config 的其它字段(fileType/title/editorConfig)前端要缓存。这些字段 demo 里恒定,缓存合理。
- 首次加载仍走 `GET /api/doc/config` 拿完整 config。

### 4.2 save_docx 检测:靠工具名还是靠文件 mtime
**选:靠工具名**(`ToolEnd.name == "save_docx" && !result.contains("错误")`)。
- 文件 mtime 方案需后端轮询/watch,复杂;工具名方案零额外机制,且 save_docx 的成功/失败已在 result 字符串里(「已保存到 X」vs「错误:…」)。
- 风险:Agent 用了别的工具改文件?——toolkit 里**只有 save_docx 落盘**,其它工具改的是内存活文档,不会动磁盘。安全。

### 4.3 并发:串行化 /api/chat
nonchain Agent 单实例非线程安全(`DocxToolkit` 注释明确)。用 `synchronized` 或单线程 executor 串行化 `/api/chat`。demo 单人演示,排队可接受;不做多 Agent 池。

### 4.4 OnlyOffice JWT
社区版 docker 默认开 JWT。**显式 `JWT_ENABLED=false`** 关掉,demo 后端直接发明文 config,无需签名。生产才开。

### 4.5 Agent 对话期间文档被 OO 缓存
OnlyOffice 按 `document.key` 缓存转换结果。**每次 save 都换 key** = 强制 OO 重新拉取+转换,这正是刷新机制的核心。key 格式 `demo-v<N>`,N 单调递增。

### 4.6 文件并发读写
Agent save 写 `current.docx`,同时 OO 可能正在拉旧版本。
- save 是覆盖写(POI `doc.save(path)`)。极端情况 OO 拉到写一半的文件 → 转换失败。
- 缓解:save 写临时文件 + `Files.move(ATOMIC_MOVE)` 原子替换。代价低,值得做。

### 4.7 上传:清记忆 + 关会话(双管齐下,见 §3.2)
换文档时光 `memory.clear()` 不够(旧活文档仍占内存),只 `closeDocx` 不够(Agent 还能用残留 docId 指代)。**两者都做**才能彻底切换上下文。这是上传与 reset 共用的「文档切换」原语 —— reset 本质是「上传内置样例」。

### 4.8 上传校验严格度
**选:扩展名 + 大小双校验,不验内容**(不试图用 nondocx/POI 打开验证)。
- 扩展名 `.docx` 防误传;大小上限(10MB)防滥用。
- 不预开 Document 验证内容:① 多一次 POI 开销;② 若损坏,Agent `open_docx` 时自然会报错给用户(工具返回中文错误串),符合现有错误处理约定。校验只挡明显错误,深层校验交给 Agent。
Agent save 写 `current.docx`,同时 OO 可能正在拉旧版本。
- save 是覆盖写(POI `doc.save(path)`)。极端情况 OO 拉到写一半的文件 → 转换失败。
- 缓解:save 写临时文件 + `Files.move(ATOMIC_MOVE)` 原子替换。代价低,值得做。

## 5. 模块与文件清单

```
nondocx-demo/
├── pom.xml                      # 依赖:nondocx-toolkit + javalin(jetty/slf4j 传递)
├── README.md                    # 运行说明(Docker OO + mvn exec:java + 浏览器)
└── src/main/
    ├── java/com/non/docx/demo/
    │   ├── DemoServer.java      # main:启动 Javalin、装配 Agent、挂路由、seed 样例文档
    │   ├── DocSession.java      # 单文档会话:docId + Path currentFile + AtomicInteger key
    │   │                        #   方法:nextKey()/readBytes()/reset(seeder)/upload(bytes,name)
    │   │                        #   upload 与 save 均走临时文件 + Files.move(ATOMIC_MOVE) 原子写
    │   ├── AgentBridge.java     # 持有 Agent 实例;run(msg, emitter) 把 AgentEvent 转 SSE 帧
    │   │                        #   检测 save_docx 成功 → DocSession.nextKey() + 推 doc_changed
    │   │                        #   clearMemory() → memory.clear();暴露给 upload/reset 调用
    │   └── SampleDocSeeder.java # 启动时把 classpath sample-input.docx 复制到 target/demo-work/
    └── resources/
        ├── sample-input.docx    # 复制自 nondocx-examples 的 sample-agent-input.docx
        └── static/
            ├── index.html       # 左右分栏骨架(工具栏:上传文档 / 重置为样例)
            ├── app.js           # OO 装配 + EventSource 对话 + destroyEditor/new DocEditor 刷新
            │                    #   + 上传按钮(fetch multipart + 刷新)
            └── style.css        # 分栏 + 气泡 + 灰条 + 工具栏
```

## 6. 父 POM 改动(最小)

- `<modules>` 末尾加 `<module>nondocx-demo</module>`
- `<properties>` 加 `<javalin.version>6.x.x</javalin.version>`
- `<dependencyManagement>` 加 javalin dependency(版本用 property)

## 7. 兜底与边界处理

- **OO 未就绪**:前端 `api.js` 加载失败时显示「OnlyOffice 未启动,请先 docker run …」。
- **`onRequestRefreshFile` 事件**:前端 OO config 的 events 里挂这个回调,作为 doc_changed 漏接的兜底(同样走 destroyEditor + new DocEditor)。
- **DASHSCOPE_API_KEY 缺失**:`DemoServer.main` 检测;缺失则 AgentBridge 标记 `agentEnabled=false`,`/api/chat` 直接返回 `{type:error,message:"未配置 API Key"}`,前端对话框顶部显示提示条。OO 预览不受影响。
- **Agent 异常**:catch 后推 `{type:error}` 再 `done`,不让 SSE 连接悬挂。
- **OO 首次转换慢**:前端 OO placeholder 显示「正在转换文档…」loading。

## 8. 教学锚点(实现时三层递进讲解)

遵循 `.trellis/spec/guides/teaching-approach.md`,实现时对关键新概念做三层讲解:

1. **`document.key` 的本质**:OOXML 里没有直接对应物,它是 OnlyOffice 的缓存版本标识(文档服务侧概念)→ POI/nondocx 不涉及 → demo 为什么靠它驱动刷新(因为 OO 按 key 缓存转换结果)。
2. **OnlyOffice 协作模型**:OOXML docx 是 ZIP 打包的 XML parts → OO Document Server 是独立服务,靠 HTTP 拉文件 + 回调通知协作 → demo 为什么需要 `/api/doc/file` 路由(给 OO 拉文件)。
3. **SSE ↔ nonchain 流式回调**:nonchain `AgentEvent` 是 push 模型回调 → Javalin SSE 是 HTTP 长连流 → demo 的 AgentBridge 如何把前者桥接成后者(一行 `data:` 帧一个事件)。
