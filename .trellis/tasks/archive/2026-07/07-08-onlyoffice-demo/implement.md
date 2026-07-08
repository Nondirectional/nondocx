# Implement · OnlyOffice 实时预览的 nondocx Agent 对话 demo

> 配套 `prd.md` + `design.md`。本文件是落地执行清单,供 `task.py start` 后逐步推进。

## 实现顺序(每步可独立验证)

### Phase 0 · 环境准备(非代码)
- [ ] 确认本机有 Docker Desktop
- [ ] `docker run -d --name oo-docs -p 9090:80 -e JWT_ENABLED=false onlyoffice/documentserver`
- [ ] 等 30-60s,`curl http://localhost:9090/healthcheck` 返回 `true`
- [ ] 确认 `DASHSCOPE_API_KEY` 环境变量可用(无 key 也能开发前端/后端骨架,只是对话跑不通)

### Phase 1 · Maven 骨架(最小可编译)
- [ ] 创建 `nondocx-demo/pom.xml`:parent = nondocx-parent,依赖 `nondocx-toolkit` + `javalin`(版本走父 POM property)
- [ ] 父 `pom.xml`:`<modules>` 加 `nondocx-demo`;`<properties>` 加 `<javalin.version>`;`dependencyManagement` 加 javalin
- [ ] 先放一个空的 `DemoServer.java`(只 `public static void main`),验证 `mvn -q -pl nondocx-demo -am compile` 通过
- **验证**:`mvn -q -pl nondocx-demo -am compile` 成功

### Phase 2 · 样例文档 + DocSession
- [ ] 复制 `nondocx-examples/.../sample-agent-input.docx` → `nondocx-demo/src/main/resources/sample-input.docx`
- [ ] 写 `SampleDocSeeder.java`:启动时 classpath → `target/demo-work/current.docx`(原子覆盖)
- [ ] 写 `DocSession.java`:持有 `docId`(Agent 句柄)、`Path currentFile`、`AtomicInteger keyVersion`;方法 `currentKey()`、`nextKey()`、`readBytes()`、`reset(seeder)`
- **验证**:单测/手测 —— seed 后文件存在,DocSession key 从 1 开始

### Phase 3 · OnlyOffice 静态预览(先打通 OO 链路,不接 Agent)
- [ ] 写 `DemoServer.java` Javalin 启动 + 静态资源挂载(`static/`) + 两个路由:
  - `GET /api/doc/config` 返回固定 config(key=`demo-v1`,url=`/api/doc/file`,mode=view)
  - `GET /api/doc/file` 返回 `current.docx` 字节
- [ ] 写 `static/index.html` + `app.js` 最小版:加载 OO `api.js`,`new DocsAPI.DocEditor`,能显示样例 docx
- **验证**:浏览器开 `localhost:8080`,左侧 OO 显示样例文档(首次转换 5-10s)
- **教学点**:讲 OnlyOffice 协作模型 + document.key

### Phase 4 · Agent 桥接(SSE 流式,但先不接刷新)
- [ ] 写 `AgentBridge.java`:照搬 `InteractiveDocxAgentExample` 的 Agent 装配(LLM + DocxToolkit.scanAll + memory + SYSTEM_PROMPT);持单实例
- [ ] `POST /api/chat`:接 message → `agent.run(message, consumer)`,consumer 把 AgentEvent 转 SSE 帧推出去
- [ ] 前端 `app.js`:加对话框 + EventSource 接收,渲染 text/tool_start/tool_end/done
- [ ] 串行化:`/api/chat` 用 `synchronized` 或单线程 executor
- **验证**:对话「打开 /abs/path/current.docx 并告诉我段落数」→ 看到流式回复 + 工具调用
- **教学点**:讲 SSE ↔ nonchain AgentEvent 桥接

### Phase 5 · 刷新机制(核心闭环)
- [ ] `AgentBridge` 在 `ToolEnd.name == "save_docx" && !result.contains("错误")` 时:`DocSession.nextKey()` + 推 `doc_changed` 帧
- [ ] save_docx 原子写:`DocSession` 改用临时文件 + `Files.move(ATOMIC_MOVE)`
- [ ] 前端收到 `doc_changed`:缓存 config 基础字段 → `destroyEditor()` → 用新 key `new DocEditor()`
- [ ] 前端 OO config events 挂 `onRequestRefreshFile` 兜底(同样销毁重建)
- **验证**:对话「把第一段改成『你好』并保存」→ OO 自动刷新显示新内容;连 3 轮仍正确
- **教学点**:讲 document.key 驱动刷新的完整链路

### Phase 6 · 文档切换原语(上传 / 重置 共用)
- [ ] `DocSession` 抽出 `switchDoc(byte[] bytes, String filename)` 统一原语:原子写 + key++
- [ ] `AgentBridge.clearMemory()` 暴露 `memory.clear()`;`DocSession` 持有当前 docId 引用,切换时调 `session.closeDocx(oldDocId)` 关旧会话
- [ ] `POST /api/doc/reset` 路由:用内置样例字节调 `switchDoc` + 清记忆 + 关旧会话 → 返回新 key
- **验证**:`curl -X POST localhost:8080/api/doc/reset` 返回新 key,OO 刷新回样例
- **教学点**:讲「文档切换 = 清记忆 + 关会话 + 换文件」三件套为什么缺一不可

### Phase 7 · 上传文档
- [ ] `POST /api/doc/upload`(multipart `file`):校验(.docx + ≤10MB + 非空)→ 失败返 400 不动会话;成功走 `switchDoc` + 清记忆 + 关旧会话 → 返回 `{ok,key,filename}`
- [ ] 前端工具栏:「上传文档」按钮(隐藏 `<input type=file accept=".docx">`)+「重置为样例」按钮
- [ ] 前端上传:fetch multipart → 成功后走 doc_changed 同款刷新(destroyEditor + new DocEditor)→ 对话框插系统提示「已加载文档:xxx.docx」
- [ ] 上传中 loading 态;失败红字提示
- **验证**:上传自有 docx → OO 显示新文档 → 对话「告诉我它有几段」Agent 基于新文档答;上传前先建立旧文档上下文,上传后 Agent 不引用旧内容(验证 clear);上传非 docx → 报错且会话不变

### Phase 8 · 收尾
- [ ] `DASHSCOPE_API_KEY` 缺失检测 + 前端提示条
- [ ] OO loading 态、输入禁用态、错误红字
- [ ] 写 `nondocx-demo/README.md`(Docker OO + mvn exec:java + 浏览器步骤;说明上传用法)
- **验证**:跑完所有 Acceptance Criteria

## 验证命令

```bash
# 编译
mvn -q -pl nondocx-demo -am package

# 起 OnlyOffice(一次性)
docker run -d --name oo-docs -p 9090:80 -e JWT_ENABLED=false onlyoffice/documentserver
curl http://localhost:9090/healthcheck  # 期望 true

# 起后端
export DASHSCOPE_API_KEY=sk-xxx
mvn -q -pl nondocx-demo exec:java -Dexec.mainClass=com.non.docx.demo.DemoServer

# 手测端点
curl http://localhost:8080/api/doc/config
curl -o /tmp/t.docx http://localhost:8080/api/doc/file && file /tmp/t.docx
curl -N -X POST http://localhost:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"message":"打开 /abs/path/to/target/demo-work/current.docx 并告诉我段落数"}'

# 上传文档
curl -X POST http://localhost:8080/api/doc/upload -F "file=@/path/to/我的报告.docx"
# 重置为样例
curl -X POST http://localhost:8080/api/doc/reset
```

## 风险点与回滚

| 风险 | 应对 |
|---|---|
| ~~Javalin 6.x 需 JDK 17+?项目是 JDK 11~~ | ✅ 已确认:Javalin 6.x 只需 Java 11(7.x 才要 17)。项目 JDK 11 兼容,用 javalin 6.x |
| OnlyOffice 拉不到文件(CORS / 网络隔离) | OO 在 docker 里访问 host 的 8080 需用 `host.docker.internal:8080` 而非 `localhost:8080`;config 里的 url 要用这个。Phase 3 验证时定位 |
| OO 转换 docx 失败(nondocx 生成的 docx 兼容性) | Phase 3 若失败,用 nondocx 的 qualityCheck 工具或换 Word 生成的样例对照排查 |
| nonchain Agent 并发崩溃 | Phase 4 必须串行化,不可跳过 |

**回滚点**:每个 Phase 结束都是可提交的稳定状态。若某 Phase 卡住,回退到上一 Phase 的提交即可,demo 不影响主库(core/toolkit/examples 零改动)。

## 完成后

- 跑 `task.py start`(开始实现时)→ 实现完 → `/trellis:check` 质量验证 → `/trellis:finish-work` 收尾
- 若有值得沉淀的知识(OO 集成模式、Javalin SSE 用法),用 `trellis-update-spec` 写进 `.trellis/spec/`
