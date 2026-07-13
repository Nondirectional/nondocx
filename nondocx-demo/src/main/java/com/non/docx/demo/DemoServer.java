package com.non.docx.demo;

import io.javalin.Javalin;
import io.javalin.http.Context;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * nondocx demo 主入口:启动 Javalin web 服务,装配 OnlyOffice 预览 + (后续)Agent 对话。
 *
 * <p><b>Phase 3 范围</b>:打通 OnlyOffice 静态预览链路 ——
 *
 * <ul>
 *   <li>{@code GET /} —— 返回静态首页
 *   <li>{@code GET /api/doc/config} —— 返回 OnlyOffice config(给前端 new DocsAPI.DocEditor 用)
 *   <li>{@code GET /api/doc/file} —— 托管当前 docx 字节(给 OnlyOffice Server 拉取)
 * </ul>
 *
 * <p>Agent 对话(Phase 4)、刷新机制(Phase 5)、上传(Phase 7)在后续 Phase 补。
 *
 * <p><b>OnlyOffice 协作模型回顾</b>(详见 design.md §1):前端 {@code new DocsAPI.DocEditor(config)} → OO
 * Document Server 按 {@code document.url} 回头拉 docx → 转换 → 渲染回 iframe。 因此 {@code /api/doc/file} 是<b>给
 * OO Server 访问的</b>,不是给浏览器;OO 在 Docker 里,要用 {@code host.docker.internal:8080} 才能拉到宿主机。
 */
public final class DemoServer {

  private static final Logger log = LoggerFactory.getLogger(DemoServer.class);

  /** 后端监听端口。 */
  private static final int PORT = 8080;

  /**
   * 宿主机对外的 base url(给 OnlyOffice Server 回拉 docx 用)。
   *
   * <p>OO 跑在 Docker 容器里,容器内 {@code localhost} 指向容器自己;访问宿主机要用 {@code
   * host.docker.internal}(macOS/Windows Docker Desktop 提供)。Linux 需改用 {@code
   * --add-host=host.docker.internal:host-gateway}。
   */
  private static final String EXTERNAL_BASE = "http://host.docker.internal:" + PORT;

  /** 工作目录(放 current.docx)。 */
  private static final Path WORK_DIR = Path.of("target", "demo-work");

  /**
   * 串行化对话请求的锁。
   *
   * <p>nonchain Agent 单实例非线程安全(DocxToolkit 注释明确),两次并发 /api/chat 会撞坏内部状态。 demo 场景单人
   * 演示,用一把全局锁把对话请求串行化最简单可靠。
   */
  private static final Object CHAT_LOCK = new Object();

  public static void main(String[] args) throws Exception {
    // 0) DashScope API key(可选:缺失则 Agent 不可用,但预览仍正常)
    //    String apiKey = System.getenv("DASHSCOPE_API_KEY");
    String apiKey = "REDACTED_DASHSCOPE_API_KEY";

    // 1) 落地样例文档到工作目录
    SampleDocSeeder seeder = new SampleDocSeeder(WORK_DIR);
    Path currentFile = seeder.seed();
    DocSession session = new DocSession(currentFile, SampleDocSeeder.SAMPLE_FILENAME);

    // 2) 装配 Agent 桥接(单例)
    AgentBridge agentBridge =
        new AgentBridge(apiKey, session.currentFile().toAbsolutePath().toString());

    // 3) 启动 Javalin
    Javalin app =
        Javalin.create(
            config -> {
              // 托管前端静态资源(classpath:/static → /)
              config.staticFiles.add(
                  staticFiles -> {
                    staticFiles.hostedPath = "/";
                    staticFiles.location = io.javalin.http.staticfiles.Location.CLASSPATH;
                    staticFiles.directory = "/static";
                    // 开发期禁用静态资源缓存，避免改了 app.js 浏览器仍加载旧版
                    staticFiles.headers =
                        java.util.Map.of("Cache-Control", "no-cache, no-store, must-revalidate");
                  });
              // 关掉默认的启动 banner,自己打一条
              config.showJavalinBanner = false;
            });
    app.start(PORT);

    log.info("========================================");
    log.info("nondocx demo 已启动");
    log.info("  浏览器:        http://localhost:{}", PORT);
    log.info("  当前文档:      {}", session.currentFile().toAbsolutePath());
    log.info("  OnlyOffice key: {}", session.currentKey());
    log.info("  Agent:         {}", agentBridge.enabled() ? "可用" : "未配置 DASHSCOPE_API_KEY,对话禁用");
    log.info("========================================");

    // 4) 路由
    registerDocRoutes(app, session, agentBridge);
    registerChatRoute(app, session, agentBridge);
    registerExecutionRoutes(app, session, agentBridge);
    registerDocSwitchRoutes(app, session, agentBridge, seeder);
  }

  /** 挂上 OnlyOffice 预览相关路由(config / file / status)。 */
  private static void registerDocRoutes(Javalin app, DocSession session, AgentBridge agentBridge) {
    // 返回 OnlyOffice config —— 前端 new DocsAPI.DocEditor(config) 时用
    app.get(
        "/api/doc/config",
        ctx -> {
          ctx.json(onlyOfficeConfig(session));
        });

    // 返回 demo 状态(给前端判断 Agent 是否可用、显示提示条)
    app.get(
        "/api/status",
        ctx -> {
          ctx.json(java.util.Map.of("agentEnabled", agentBridge.enabled()));
        });

    // 托管当前 docx 字节 —— OnlyOffice Server 按 document.url 回头拉这个
    app.get(
        "/api/doc/file",
        ctx -> {
          log.debug("OO 拉取 docx (key={})", session.currentKey());
          ctx.contentType("application/octet-stream");
          ctx.header("Content-Disposition", "attachment; filename=\"current.docx\"");
          ctx.header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
          ctx.header("Pragma", "no-cache");
          ctx.header("Expires", "0");
          ctx.result(session.readBytes());
        });
  }

  /**
   * 挂上 Agent 对话路由(POST,SSE 流式响应)。
   *
   * <p><b>为什么用 POST 而非 EventSource。</b> 浏览器原生 {@code EventSource} 只支持 GET,消息只能走 query string(中文需
   * URL 编码、长度受限)。工业界 LLM 对话框惯例用 {@code fetch(POST) + ReadableStream} 手动解析 SSE 流——POST body 传消息,响应是
   * SSE 格式。前端 {@code app.js} 实现了这套解析。
   *
   * <p>后端实现:设置 SSE 响应头后,直接往输出流写 {@code data: {json}\n\n} 帧。 用 {@link #CHAT_LOCK} 串行化 (Agent 非线程安全)。
   */
  private static void registerChatRoute(Javalin app, DocSession session, AgentBridge agentBridge) {
    app.post(
        "/api/chat",
        ctx -> {
          // 解析 POST body:期望 JSON { "message": "..." }
          String body = ctx.body();
          String message = extractMessage(body);
          if (message == null || message.isBlank()) {
            log.warn("收到空消息,返回 400");
            ctx.status(400);
            ctx.json(java.util.Map.of("ok", false, "error", "消息不能为空"));
            return;
          }
          // 切成 SSE 流
          setupSseResponse(ctx);
          log.info("收到对话: {}", message);
          // 串行化:Agent 非线程安全
          synchronized (CHAT_LOCK) {
            agentBridge.runStream(message, ctx, session);
          }
          // runStream 内部 flush 完所有帧后,响应自然结束
        });
  }

  /** 显式实施与取消入口；普通聊天永远不会写入文档。 */
  private static void registerExecutionRoutes(
      Javalin app, DocSession session, AgentBridge agentBridge) {
    app.post(
        "/api/execute",
        ctx -> {
          String token = extractString(ctx.body(), "token");
          if (token == null || token.isBlank()) {
            ctx.status(400);
            ctx.json(java.util.Map.of("ok", false, "error", "授权 token 不能为空"));
            return;
          }
          setupSseResponse(ctx);
          synchronized (CHAT_LOCK) {
            agentBridge.executeStream(token, ctx, session);
          }
        });
    app.post(
        "/api/cancel",
        ctx -> {
          agentBridge.cancel();
          ctx.json(java.util.Map.of("ok", true));
        });
    app.get(
        "/api/trace",
        ctx -> {
          ctx.contentType("application/x-ndjson");
          ctx.result(agentBridge.traceReplay());
        });
  }

  /** 设置 SSE 响应头。 */
  private static void setupSseResponse(Context ctx) {
    ctx.contentType("text/event-stream");
    ctx.header("Cache-Control", "no-cache");
    ctx.header("Connection", "keep-alive");
    ctx.header("X-Accel-Buffering", "no"); // 防 nginx 缓冲
  }

  /**
   * 挂上文档切换路由(重置 / 上传)。
   *
   * <p>两者共用同一套「文档切换」原语:
   *
   * <ol>
   *   <li>{@link DocSession#replaceWith(byte[], String)} 原子替换磁盘文件
   *   <li>{@link AgentBridge#clearMemory()} 清空 Agent 对话记忆(避免指代旧文档结构)
   *   <li>{@link DocSession#bumpKey()} 换 OO key,让前端刷新预览
   * </ol>
   *
   * <p><b>为什么不关旧 docId。</b> 理想做法还应 {@code closeDocx(旧docId)} 释放 POI 句柄,但 AgentBridge 不持有
   * DocxToolkit 引用。demo 单文档场景,失忆后 Agent 会 open 新 docId,旧句柄泄漏一两个无关紧要(重启即清); 正确性已由 clearMemory 保证。详见
   * design.md §3.2。
   */
  private static void registerDocSwitchRoutes(
      Javalin app, DocSession session, AgentBridge agentBridge, SampleDocSeeder seeder) {
    // 重置为内置样例
    app.post(
        "/api/doc/reset",
        ctx -> {
          try {
            byte[] sampleBytes = seeder.sampleBytes();
            session.replaceWith(sampleBytes, SampleDocSeeder.SAMPLE_FILENAME);
            agentBridge.clearMemory();
            String newKey = session.bumpKey();
            log.info("已重置为样例文档 (newKey={})", newKey);
            ctx.json(java.util.Map.of("ok", true, "key", newKey, "filename", session.filename()));
          } catch (RuntimeException e) {
            log.error("重置失败", e);
            ctx.status(500);
            ctx.json(java.util.Map.of("ok", false, "error", "重置失败:" + e.getMessage()));
          }
        });

    // 上传用户文档
    app.post(
        "/api/doc/upload",
        ctx -> {
          var uploadedFile = ctx.uploadedFile("file");
          if (uploadedFile == null) {
            ctx.status(400);
            ctx.json(java.util.Map.of("ok", false, "error", "未提供文件"));
            return;
          }
          String filename = uploadedFile.filename();
          // 校验:.docx 扩展名 + 大小上限 10MB
          if (filename == null || !filename.toLowerCase().endsWith(".docx")) {
            ctx.status(400);
            ctx.json(java.util.Map.of("ok", false, "error", "仅支持 .docx 文件"));
            return;
          }
          byte[] bytes = uploadedFile.content().readAllBytes();
          if (bytes.length == 0) {
            ctx.status(400);
            ctx.json(java.util.Map.of("ok", false, "error", "文件为空"));
            return;
          }
          if (bytes.length > 10 * 1024 * 1024) {
            ctx.status(400);
            ctx.json(java.util.Map.of("ok", false, "error", "文件过大(上限 10MB)"));
            return;
          }
          try {
            // 校验通过:走文档切换原语
            session.replaceWith(bytes, filename);
            agentBridge.clearMemory();
            String newKey = session.bumpKey();
            ctx.json(java.util.Map.of("ok", true, "key", newKey, "filename", filename));
            log.info("已上传文档: {} ({} 字节, newKey={})", filename, bytes.length, newKey);
          } catch (RuntimeException e) {
            log.error("上传保存失败: {}", filename, e);
            ctx.status(500);
            ctx.json(java.util.Map.of("ok", false, "error", "保存失败:" + e.getMessage()));
          }
        });
  }

  /** 简易 JSON 提取:从 {"message":"..."} 取 message 值(不引 JSON 库)。 */
  private static String extractMessage(String json) {
    return extractString(json, "message");
  }

  /** 从扁平 JSON 对象提取一个字符串字段。 */
  private static String extractString(String json, String key) {
    if (json == null || json.isBlank()) {
      return null;
    }
    // 找 "message":"..." 模式
    int idx = json.indexOf("\"" + key + "\"");
    if (idx < 0) {
      return null;
    }
    int colon = json.indexOf(':', idx);
    if (colon < 0) {
      return null;
    }
    int start = json.indexOf('"', colon);
    if (start < 0) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    for (int i = start + 1; i < json.length(); i++) {
      char c = json.charAt(i);
      if (c == '"' && json.charAt(i - 1) != '\\') {
        break;
      }
      if (c == '\\' && i + 1 < json.length()) {
        char next = json.charAt(i + 1);
        switch (next) {
          case 'n':
            sb.append('\n');
            break;
          case 't':
            sb.append('\t');
            break;
          case '"':
            sb.append('"');
            break;
          case '\\':
            sb.append('\\');
            break;
          default:
            sb.append(next);
        }
        i++;
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /**
   * 构造 OnlyOffice config(预览模式)。
   *
   * <p><b>JWT 关闭</b>:社区版 Docker 用 {@code JWT_ENABLED=false},config 可明文,无需签名。 生产环境才需要 JWT 签名 config。
   *
   * <p><b>url 用 EXTERNAL_BASE</b>:这个 url 是 OO Server(Docker 容器)去访问的,必须用容器能解析到宿主机的地址。
   */
  private static java.util.Map<String, Object> onlyOfficeConfig(DocSession session) {
    String key = session.currentKey();
    return java.util.Map.of(
        "document",
        java.util.Map.of(
            "fileType",
            "docx",
            "key",
            key,
            "title",
            session.filename(),
            "url",
            EXTERNAL_BASE + "/api/doc/file?key=" + key,
            "permissions",
            java.util.Map.of("edit", false, "download", true)),
        "documentType",
        "word",
        "editorConfig",
        java.util.Map.of(
            "mode", "view",
            "lang", "zh-CN"));
  }

  private DemoServer() {}
}
