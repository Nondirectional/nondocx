package com.non.docx.demo;

import com.non.chain.agent.Agent;
import com.non.chain.agent.AgentEvent;
import com.non.chain.callback.ChainCallback;
import com.non.chain.callback.event.LlmCompleteEvent;
import com.non.chain.callback.event.ToolCompleteEvent;
import com.non.chain.memory.ChatMemory;
import com.non.chain.memory.MessageWindowChatMemory;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;
import com.non.chain.tool.ToolRegistry;
import com.non.docx.toolkit.DocxToolkit;
import io.javalin.http.Context;
import java.io.OutputStream;
import java.util.function.Consumer;

/**
 * Agent 桥接层:持有 nonchain Agent 单例,把 {@link AgentEvent} 流式回调转成 SSE 帧推给前端。
 *
 * <p><b>核心桥接:push 回调 → HTTP 流。</b>
 *
 * <ul>
 *   <li>nonchain 是<b>同步 push 回调</b>模型——{@code agent.run(msg, consumer)} 在同一线程里跑, 每出一个事件调一次
 *       consumer。
 *   <li>SSE 是<b>HTTP 长连接流</b>——服务端持续写 {@code data: {json}\n\n},直到关闭。
 *   <li>桥接:本类实现的 consumer 把每个 {@link AgentEvent} 序列化成 JSON,经 {@link #writeFrame} 直接写到
 *       响应输出流。两者天然贴合(都是「有数据就推」)。
 * </ul>
 *
 * <p><b>SSE 生命周期。</b> 每次 {@code POST /api/chat} 建一个 HTTP 响应流,本类的 {@link #runStream} 在该流上 同步跑
 * Agent(阻塞到 Complete),然后写 {@code done} 帧、flush 后响应自然结束。多轮对话靠 Agent 的 memory 维持,不靠 SSE 长连。
 *
 * <p><b>save_docx 检测(Phase 5)。</b> toolkit 的 {@code save_docx} 成功/失败<b>都返回字符串</b>(不抛异常): 成功 {@code
 * "已保存到 …"},失败 {@code "错误：…"}/{@code "文档 … 未打开"}。本类在 {@link AgentEvent.ToolEnd} 里检测 {@code name ==
 * "save_docx"} 且结果不含「错误」/「未打开」,据此触发 {@link DocSession#bumpKey()} + 推 {@code doc_changed} 帧(Phase 5
 * 接入)。
 *
 * <p><b>线程模型。</b> Agent 单实例非线程安全({@code DocxToolkit} 注释明确)。本类<b>不是</b>线程安全的, 由路由层串行化(demo
 * 单对话队列)保证一次只跑一个 {@link #run}。
 */
final class AgentBridge {

  /** Agent 是否可用(取决于 API key 是否配置)。 */
  private final boolean enabled;

  /** Agent 单例(若 enabled);否则 null。 */
  private final Agent agent;

  /** Agent 的 memory 引用(用于上传/重置时清空)。 */
  private final ChatMemory memory;

  /**
   * @param apiKey DashScope API key;null/空则 Agent 不可用,前端对话框显示提示
   * @param currentDocPath 当前文档磁盘路径(Agent 在对话里 open_docx 这个路径)
   */
  AgentBridge(String apiKey, String currentDocPath) {
    this.memory =
        MessageWindowChatMemory.builder().maxMessages(30).conversationId("nondocx-demo").build();

    if (apiKey == null || apiKey.isBlank()) {
      this.enabled = false;
      this.agent = null;
      return;
    }

    this.enabled = true;
    LLM llm = new DashscopeLLM("qwen3.7-plus").maxCompletionTokens(1024);
    DocxToolkit toolkit = new DocxToolkit();
    ToolRegistry registry = toolkit.scanAll(new ToolRegistry());

    this.agent =
        Agent.builder(llm, registry)
            .systemPrompt(systemPrompt(currentDocPath))
            .maxIterations(Integer.MAX_VALUE)
            .memory(memory)
            .callback(loggingCallback())
            .build();
  }

  /** Agent 是否可用(有无 API key)。 */
  boolean enabled() {
    return enabled;
  }

  /** 清空 Agent 对话记忆(上传/重置文档时调,避免 Agent 指代旧文档结构)。 */
  void clearMemory() {
    memory.clear();
  }

  /**
   * 在给定 HTTP 上下文上跑一轮 Agent 对话,直接把 SSE 帧写到响应输出流。
   *
   * <p>同步阻塞:调用方(Javalin POST handler)会阻塞直到 Agent 跑完(Complete/error),期间本方法持续写 SSE 帧。 每帧写完后 {@code
   * flush()},确保前端实时收到(否则会被 Jetty 缓冲)。
   *
   * @param message 用户消息
   * @param ctx 当前请求的 Javalin 上下文(响应头已由路由层设为 text/event-stream)
   * @param session 文档会话(用于 save_docx 检测后 bumpKey)
   */
  void runStream(String message, Context ctx, DocSession session) {
    if (!enabled) {
      writeFrame(ctx, frame("error", "message", "未配置 DASHSCOPE_API_KEY,无法对话。"));
      writeFrame(ctx, frame("done"));
      flush(ctx);
      return;
    }
    try {
      agent.run(message, eventConsumer(ctx, session));
    } catch (RuntimeException e) {
      writeFrame(ctx, frame("error", "message", rootMessage(e)));
    } finally {
      writeFrame(ctx, frame("done"));
      flush(ctx);
    }
  }

  /** 把每种 {@link AgentEvent} 序列化成 SSE 帧写到响应流;检测 save_docx 成功(Phase 5)。 */
  private Consumer<AgentEvent> eventConsumer(Context ctx, DocSession session) {
    return event -> {
      if (event instanceof AgentEvent.TextDelta) {
        String delta = ((AgentEvent.TextDelta) event).delta();
        writeFrame(ctx, frame("text", "delta", delta));
        flush(ctx); // TextDelta 每条都 flush,保证打字机实时性
      } else if (event instanceof AgentEvent.ToolStart) {
        AgentEvent.ToolStart ts = (AgentEvent.ToolStart) event;
        writeFrame(ctx, frame3("tool_start", "name", ts.toolName(), "arguments", ts.arguments()));
        flush(ctx);
      } else if (event instanceof AgentEvent.ToolEnd) {
        AgentEvent.ToolEnd te = (AgentEvent.ToolEnd) event;
        writeFrame(ctx, frame3("tool_end", "name", te.toolName(), "result", te.result()));
        // Phase 5 核心:save_docx 成功 → bump key + 推 doc_changed
        if ("save_docx".equals(te.toolName()) && isSaveSuccess(te.result())) {
          String newKey = session.bumpKey();
          writeFrame(ctx, frame("doc_changed", "key", newKey));
        }
        flush(ctx);
      } else if (event instanceof AgentEvent.AgentError) {
        writeFrame(
            ctx, frame("error", "message", rootMessage(((AgentEvent.AgentError) event).error())));
        flush(ctx);
      }
      // Complete / RoundStart / RoundEnd / ThinkingDelta / ToolCallDelta 不单独推帧,
      // done 帧在 runStream() 的 finally 里推。
    };
  }

  // ==================== SSE 帧输出(直接写 OutputStream + flush) ====================
  //
  // 为什么不用 Javalin 的 SseClient:POST 路由下没有 SseClient(SseClient 只在 app.sse() GET handler 里有)。
  // 这里手动写 "data: {json}\n\n" 到输出流,每次 flush 保证实时推送。
  // JSON 序列化用 Jackson(经 Javalin 传递引入),把 Map → JSON 字符串。

  /** 把一个帧 Map 序列化成 SSE 格式写到响应流(不 flush,由调用方决定)。 */
  private static void writeFrame(Context ctx, java.util.Map<String, Object> data) {
    try {
      String json = ctx.jsonMapper().toJsonString(data, java.util.Map.class);
      OutputStream out = ctx.res().getOutputStream();
      out.write(("data: " + json + "\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (java.io.IOException e) {
      throw new RuntimeException("写 SSE 帧失败", e);
    }
  }

  /** flush 响应输出流,让缓冲的帧立即发给前端。 */
  private static void flush(Context ctx) {
    try {
      ctx.res().flushBuffer();
    } catch (java.io.IOException e) {
      throw new RuntimeException("flush 响应失败", e);
    }
  }

  /**
   * 判断 save_docx 是否成功。
   *
   * <p>toolkit 约定:成功 {@code "已保存到 …"},失败含 {@code "错误"} 或 {@code "未打开"}。 这里用「不含失败标志」 判断,容错性更好。
   */
  private static boolean isSaveSuccess(String result) {
    return result != null
        && !result.contains("错误")
        && !result.contains("未打开")
        && result.contains("已保存");
  }

  // ==================== SSE 帧构造(Map → JSON 由 writeFrame 序列化) ====================
  //
  // 帧装成 LinkedHashMap(保留字段顺序),writeFrame 用 ctx.jsonMapper() 序列化成 JSON。
  // 字段顺序与转义都交给 Jackson 处理,稳妥无歧义。

  /** 构造 {@code {type, key: value}} 帧。 */
  private static java.util.Map<String, Object> frame(String type, String key, String value) {
    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("type", type);
    m.put(key, value);
    return m;
  }

  /** 构造 {@code {type, key1: value1, key2: value2}} 帧。 */
  private static java.util.Map<String, Object> frame3(
      String type, String key1, String value1, String key2, String value2) {
    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("type", type);
    m.put(key1, value1);
    m.put(key2, value2);
    return m;
  }

  /** 构造 {@code {type}} 单字段帧。 */
  private static java.util.Map<String, Object> frame(String type) {
    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("type", type);
    return m;
  }

  /** 取异常根因消息。 */
  private static String rootMessage(Throwable e) {
    Throwable cur = e;
    while (cur.getCause() != null && cur.getCause() != cur) {
      cur = cur.getCause();
    }
    return cur.getMessage();
  }

  // ==================== Agent 装配 ====================

  /** 照搬 InteractiveDocxAgentExample 的 system prompt(已迭代成熟)。 */
  private static String systemPrompt(String currentDocPath) {
    return "你是一个 docx 文档编辑助手。你只能通过提供的 docx 工具操作文档："
        + "先 open_docx 拿到 docId，再用 read_* 查看结构、用 replace_*/update_* 修改内容，"
        + "最后 save_docx 落盘、close_docx 释放。"
        + "不要编造文件路径——当前文档路径是 "
        + currentDocPath
        + "。用户说到「这个文档」「当前文档」时，就用 open_docx 打开这个路径。"
        + "所有索引从 0 开始。若工具返回「错误：...」字符串，说明索引越界或句柄失效，"
        + "请先调用对应的 read_*/get_*_count 重新确认结构后再重试。"
        + "需要按文本内容定位位置（如「把『项目进度』改成…」）时，"
        + "优先用 search_text 一次性拿到坐标，再用 replace_run_text/replace_table_cell_run_text 修改，"
        + "不要逐个 read_paragraph 盲读。"
        + "这是一段连续的多轮对话：记住用户此前给出的文件路径、docId 与已读到的结构，"
        + "用户后续的指代（「那个文档」「第 2 个表格」）应理解为指代此前上下文，除非明确更换。"
        + "用简洁中文汇报。";
  }

  /** 日志回调:打印每轮 LLM / 工具耗时,便于观察 Agent 循环开销。 */
  private static ChainCallback loggingCallback() {
    return new ChainCallback() {
      @Override
      public void onLlmComplete(LlmCompleteEvent event) {
        System.out.println("  [LLM] 耗时 " + event.latencyMs() + "ms");
      }

      @Override
      public void onToolComplete(ToolCompleteEvent event) {
        System.out.println("  [Tool] " + event.toolName() + " 耗时 " + event.latencyMs() + "ms");
      }
    };
  }
}
