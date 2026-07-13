# nondocx-demo

> nondocx 的可交互前端 demo:左侧 OnlyOffice 实时预览 `.docx`,右侧通过自然语言对话框驱动
> Agent 编辑文档,Agent 每次保存后 OnlyOffice 自动刷新。

## 它演示什么

把 nondocx「库 + Agent 工具集」的能力用一个网页直观展示出来:

- **对文档说话,文档就变了** —— 输入「把第一段改成『你好』并保存」,Agent 调用 docx 工具修改并落盘
- **实时刷新** —— Agent 保存的瞬间,左侧 OnlyOffice 销毁重建,显示最新内容
- **上传自有文档** —— 点「上传文档」选你自己的 `.docx`,Agent 接着帮你编辑

## 架构(三服务分离)

```
浏览器 (localhost:8080)
├── 左:OnlyOffice iframe 预览
└── 右:对话框 → POST /api/chat (SSE 流式响应)
        │
        ▼
Javalin 后端 (nondocx-demo:8080)
└── 主 Agent 通过 invoke_subagent 工具唤起无状态文档 SubAgent
    └── SubAgent 复用 DocxToolkit 读取、编辑、质量检查并保存当前文档
        │ save_current_document 落盘 → key++
        ▼
OnlyOffice Document Server (Docker :9090)
└── 按 document.key 缓存版本;key 变了就重新拉文件、转换、渲染
```

**核心刷新机制**:OnlyOffice 不能直接 reload 同一实例。每次 SubAgent `save_current_document` 成功,后端让
`document.key` 自增,前端收到后 `destroyEditor()` + `new DocsAPI.DocEditor(新key)`,OnlyOffice
因 key 变化而重新拉文件 —— 这就是「自动刷新」的全部秘密。

## 运行

### 1. 启动 OnlyOffice Document Server(项目根目录的 docker-compose)

```bash
docker compose up -d
```

首次启动需 30-60s。验证就绪:

```bash
docker compose ps          # STATUS 显示 (healthy) 即就绪
# 或直接探活:
curl http://localhost:9090/healthcheck   # 期望返回 true
```

> compose 已固化 `host.docker.internal:host-gateway` 映射 —— Linux/macOS/Windows
> 三平台统一,OO 容器都能回拉宿主机的 docx 文件,无需手动加 `--add-host`。
>
> compose 还通过环境变量 `ALLOW_PRIVATE_IP_ADDRESS=true` 放行 OO 从私有 IP 下载文档 ——
> OO 8.x+ 默认禁止私有 IP 下载(防 SSRF),而 demo 后端就在宿主机,不放行会报
> `is not allowed. Because, It is private IP address`。详见下方 FAQ。

### 2. 配置 Agent(可选,但推荐)

Agent 用阿里云灵积(DashScope)的 qwen 模型。设置 API key:

```bash
export DASHSCOPE_API_KEY=sk-xxxxx
```

> 没有 key 也能启动 —— OnlyOffice 预览照常,只是对话框不能用(会显示提示条)。

### 3. 启动后端

```bash
mvn -q -pl nondocx-demo exec:java -Dexec.mainClass=com.non.docx.demo.DemoServer
```

启动成功会打印:

```
========================================
nondocx demo 已启动
  浏览器:        http://localhost:8080
  当前文档:      /path/to/target/demo-work/current.docx
  OnlyOffice key: demo-v1
  Agent:         可用
========================================
```

### 4. 打开浏览器

访问 **http://localhost:8080**:

- 左侧自动加载内置样例文档(首次 OO 转换需 5-10s)
- 右侧输入框试着发:「**打开当前文档并告诉我它有几段**」
- 或:「**把第一段改成『你好,demo』并保存**」—— 看 Agent 调工具、流式回复、文档自动刷新

## 上传自有文档

点右上角「📤 上传文档」选一份 `.docx`:

- 上传后 Agent 会**忘掉之前对话**(换文档必须清空上下文,否则会指代旧文档结构)
- OnlyOffice 刷新显示你的文档,后续对话基于这份新文档
- 「↺ 重置样例」按钮可恢复内置样例

> 上传的文档只在本进程内存活(落在 `target/demo-work/current.docx`,gitignore 已覆盖)。

## 常见问题

### OnlyOffice 一直转圈 / 显示「无法连接」

确认容器在跑、就绪、9090 端口可达:

```bash
docker compose ps          # 确认 STATUS 含 (healthy)
curl http://localhost:9090/healthcheck
```

### OO 日志报 `is not allowed. Because, It is private IP address`

这是 OO 8.x+ 的 SSRF 防护:默认禁止从私有 IP 下载文档。demo 后端在宿主机,
`host.docker.internal` 解析出的正是私有 IP,会被挡。

解决:**确认 compose 设了 `ALLOW_PRIVATE_IP_ADDRESS=true` 环境变量**(项目已自带)。
OO 的启动脚本原生支持这个环境变量,启动时自动写入 local.json。

> 为什么不直接挂载 local.json?OO 启动时会用「写临时文件 + rename」方式重写 local.json,
> 而 Docker 的 bind mount 会锁定文件导致 `EBUSY: resource busy or locked`,DocService
> 反复崩溃重启,前端表现为「下载失败」。用环境变量让 OO 自己改自己,避开这个冲突。

> ⚠ 这关闭了 SSRF 防护,仅适用于本地可信 demo。生产环境改用公网 IP + JWT。

### OO 容器首启被杀(exit 137)

OnlyOffice 首启要在内存里生成全套字体/主题,峰值很高。若 Docker Desktop 内存不足
会被 OOM(代码 137)。处理:① Docker Desktop → Settings → Resources → Memory 调到
**8GB+**(官方推荐);② compose 已配 `restart: unless-stopped`,首启被杀会自动重试,
第二次起时缓存已落盘,通常能成功。

### 对话框提示「未配置 DASHSCOPE_API_KEY」

设置环境变量后重启后端(见上「2. 配置 Agent」)。

### Agent 改完文档但 OnlyOffice 没刷新

看浏览器控制台有没有 `[刷新] save 成功,新 key:` 日志。若没有,可能是 SubAgent 未保存成功——在对话里重新提出编辑请求。

### 上传后 Agent 还在说旧文档的事

正常情况下后端会清空 Agent 记忆。若发生,刷新整个页面(F5)即可,因为重启页面会重建
OnlyOffice 实例 + 重新拉文档。

## 限制(demo 性质)

- 单文档会话:一次操作一份文档;上传/重置会切换文档并清空对话
- 单对话串行:一次只处理一条消息(排队),Agent 非线程安全
- 预览模式:用户**不**在 OnlyOffice 里直接编辑,所有编辑经 Agent 对话
- 无 JWT/HTTPS:本地 demo,社区版 OnlyOffice 禁用 JWT
- 无前端构建:纯静态 HTML/JS

## 相关

- [nondocx-toolkit 文档](../docs/07-toolkit.md) —— 本 demo 用的 Agent 工具集
- [InteractiveDocxAgentExample](../nondocx-examples/src/main/java/com/non/docx/examples/agent/InteractiveDocxAgentExample.java)
  —— 终端版 REPL,本 demo 是它的 Web 升级版
