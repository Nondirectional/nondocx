package com.non.docx.examples.agent;

import com.non.chain.agent.Agent;
import com.non.chain.agent.AgentEvent;
import com.non.chain.callback.ChainCallback;
import com.non.chain.callback.event.LlmCompleteEvent;
import com.non.chain.callback.event.ToolCompleteEvent;
import com.non.chain.memory.MessageWindowChatMemory;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;
import com.non.chain.tool.ToolRegistry;
import com.non.docx.examples.ExamplePaths;
import com.non.docx.toolkit.DocxToolkit;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * nonchain Agent × nondocx 的<b>交互式 / 实时对话</b>示例：在终端里和 docx 编辑助手多轮对话，用于联调与测试。
 *
 * <p>它和 {@link DocxAgentExample} 是<b>同一套工具、不同驱动方式</b>的对照：
 *
 * <ul>
 *   <li>{@link DocxAgentExample} —— 批处理驱动：{@code main} 里写死两段 query（先读后改），跑完即退出，适合「一次性演示」。
 *   <li>本类 {@code InteractiveDocxAgentExample} —— REPL 驱动：循环读取 stdin 一行用户消息， 调 Agent 实时输出，直到输入
 *       {@code :q} 退出。适合「反复试探 / 联调 prompt / 测试工具边界」。
 * </ul>
 *
 * <p><b>实时对话靠 nonchain 的两块能力拼出来</b>（无需自己造轮子）：
 *
 * <ol>
 *   <li><b>多轮记忆</b>：{@link Agent.Builder#memory(com.non.chain.memory.ChatMemory)} + {@link
 *       MessageWindowChatMemory}。配置后 {@code agent.run(query)} 会自动把每轮 user/assistant/tool 消息存进
 *       memory，并在下一轮携带历史上下文—— 于是「你刚才打开的那个文档」「表格第二行那个单元格」这种指代能被 Agent 记住。 滑动窗口（{@code
 *       maxMessages}）负责在对话变长时裁剪老消息，控制 token 成本。
 *   <li><b>流式输出</b>：{@link Agent#run(String, java.util.function.Consumer)} 接一个 {@code
 *       Consumer<AgentEvent>} 回调。Agent 循环每产生一个增量（一段文本、一次工具开始 / 结束）就回调一次， 终端因此可以「token
 *       一边生成一边打印」，而不是憋到整段回复才显示——这正是「实时」的体感来源。
 * </ol>
 *
 * <p><b>会话生命周期</b>：和批处理示例不同，这里不预置输入文档。你在对话里告诉 Agent 要操作哪个文件 （或先 {@code :new} 让它从空白文档开始），Agent 自己
 * {@code open_docx} / {@code save_docx}。 这更贴近真实联调场景：你想测什么就发什么指令。
 *
 * <p><b>运行前置</b>：环境变量 {@code DASHSCOPE_API_KEY}。运行：
 *
 * <pre>{@code
 * mvn -q -pl nondocx-examples exec:java \
 *   -Dexec.mainClass=com.non.docx.examples.agent.InteractiveDocxAgentExample
 * }</pre>
 *
 * <p>进入后输入任意中文指令（例如「打开 /path/to/a.docx 并告诉我段落数」），输入 {@code :q} 退出， {@code :new <path>} 让 Agent
 * 从一份样例文档副本开始测试。
 */
public final class InteractiveDocxAgentExample {

  /** 样例输入文档在 classpath 中的位置（{@code :new} 时复制一份到输出目录供 Agent 操作）。 */
  private static final String SAMPLE_RESOURCE = "/document/sample-agent-input.docx";

  /** 约束 Agent 只用 docx 工具、路径由用户消息给定、遇错误串可重试。与批处理示例保持一致。 */
  private static final String SYSTEM_PROMPT =
      "你是一个 docx 文档编辑助手。你只能通过提供的 docx 工具操作文档："
          + "先 open_docx 拿到 docId，再用 read_* 查看结构、用 replace_*/update_* 修改内容，"
          + "最后 save_docx 落盘、close_docx 释放。"
          + "不要编造文件路径——所有路径都在用户消息里给出。"
          + "所有索引从 0 开始。若工具返回「错误：...」字符串，说明索引越界或句柄失效，"
          + "请先调用对应的 read_*/get_*_count 重新确认结构后再重试。"
          // search_text 的引导：按文本找位置时务必先用它一次定位，再 replace_*，
          // 避免逐段 read_paragraph 盲读造成大量 LLM 往返——这正是本交互示例的联调重点。
          + "需要按文本内容定位位置（如「把『项目进度』改成…」）时，"
          + "优先用 search_text 一次性拿到坐标，再用 replace_run_text/replace_table_cell_run_text 修改，"
          + "不要逐个 read_paragraph 盲读。页眉页脚里的文本也能被 search_text 命中，"
          + "需要其结构细节时用 read_header_footer。"
          // —— 批量工具(v2)：核心工具支持一次操作多个目标 ——
          + "【批量工具】read_paragraph（段落索引数组 paragraph_indexes）、"
          + "update_paragraph_alignment（对象数组 edits，每个含 paragraph_index/alignment）、"
          + "read_run / read_table_cell（对象数组 runs/cells）、"
          + "create_table（二维数组 rows，外层为行、内层为单元格文本）、"
          + "set_table_borders（table_index + border_style=NONE）、merge_table_cells（对象数组 merges）、"
          + "replace_run_text / replace_table_cell_run_text（对象数组 edits，含完整坐标与 text，"
          + "部分失败不中断、返回每条明细）、update_run_style（对象数组 edits，含坐标与样式字段）、"
          + "insert_paragraph（对象数组 paragraphs，每个含 body_index/text）、"
          + "insert_tracked_run / delete_run_tracked / replace_run_tracked / mark_tracked_cells"
          + "（author 共享 + 对象数组 edits/cells）、"
          + "apply_tracked_changes（action=ACCEPT/REJECT，target=TEXT_OR_MOVE/PROPERTY/CELL，ids 为 id 数组）、"
          + "update_hyperlink（text 与 url 都可选，一次改齐）"
          + "都支持一次操作多个目标，单次调用传长度 1 的数组即可；要同时改/读多处时优先用批量。"
          // 目录(TOC)读取引导:文档有目录时,read_toc 一次拿到「标题/层级/页码/锚点」,
          // 比逐段 read 拼结构高效得多;页码是文档缓存值,过期(dirty)时结果会标注。
          + "需要看文档的目录(章节结构 + 页码)时用 read_toc 一次拿到全部条目，"
          + "不要逐段 read_paragraph 盲拼。"
          // 这是「交互式」特有的补充：多轮对话里用户会用「刚才那个文档」「第二个表格」这类指代，
          // 显式告诉 Agent 它有记忆，能减少 Agent 反复要求用户重述路径。
          + "这是一段连续的多轮对话：记住用户此前给出的文件路径、docId 与已读到的结构，"
          + "用户后续的指代（「那个文档」「第 2 个表格」）应理解为指代此前上下文，除非明确更换。"
          + "用简洁中文汇报。";

  /** 滑动窗口保留的最近消息数。交互测试里工具调用频繁，留宽一点避免 Agent「忘事」。 */
  private static final int MEMORY_MAX_MESSAGES = 30;

  public static void main(String[] args) throws Exception {
    if (System.getenv("DASHSCOPE_API_KEY") == null) {
      System.err.println("缺少环境变量 DASHSCOPE_API_KEY，无法启动。");
      System.err.println("请设置后再运行，例如：export DASHSCOPE_API_KEY=sk-xxx");
      return;
    }

    LLM llm = new DashscopeLLM("qwen3.7-plus").maxCompletionTokens(1024);
    // DocxToolkit 门面构造全部六组工具并共享同一份文档会话；scanAll 把它们一次注册进 registry。
    DocxToolkit toolkit = new DocxToolkit();
    ToolRegistry registry = toolkit.scanAll(new ToolRegistry());

    Agent agent =
        Agent.builder(llm, registry)
            .systemPrompt(SYSTEM_PROMPT)
            // 不限制迭代次数：让 Agent 自行跑完所有工具调用直至自然收尾。
            // nonchain 0.10.0 起，普通上限用完后会进入 graceful 收尾；这里用 Integer.MAX_VALUE
            // 实际等同于不设上限。
            .maxIterations(Integer.MAX_VALUE)
            // ★ 多轮记忆：开启后每轮 user/assistant/tool 消息自动入栈，
            //   下一轮 run 会把历史一起发给 LLM，于是「刚才那个文档」能被理解。
            //   不配 memory 的话每次 run 都是「失忆」的单轮对话。
            .memory(
                MessageWindowChatMemory.builder()
                    .maxMessages(MEMORY_MAX_MESSAGES)
                    .conversationId("interactive-docx")
                    .build())
            .callback(loggingCallback())
            .build();

    printHelp();
    BufferedReader reader =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    while (true) {
      System.out.println();
      System.out.print("你 › ");
      System.out.flush();
      String line = reader.readLine();
      if (line == null) {
        // stdin 关闭（例如管道喂入结束时），优雅退出。
        break;
      }
      String input = line.trim();
      if (input.isEmpty()) {
        continue;
      }
      if (":q".equals(input)
          || ":quit".equalsIgnoreCase(input)
          || ":exit".equalsIgnoreCase(input)) {
        System.out.println("再见。");
        break;
      }
      if (input.startsWith(":new")) {
        handleNewDoc(input);
        continue;
      }
      if (":help".equalsIgnoreCase(input)) {
        printHelp();
        continue;
      }
      // 普通对话：流式 run，事件实时回显。
      System.out.print("助手 › ");
      System.out.flush();
      try {
        agent.run(input, InteractiveDocxAgentExample::handleEvent);
      } catch (RuntimeException e) {
        // Agent 内部异常不应让 REPL 挂掉——打印后继续下一轮。
        // 若需恢复 0.9.x「超 maxIterations 抛异常」语义，可在 Builder 上显式配置 graceTurns(0)。
        System.err.println("[错误] " + rootMessage(e));
      }
    }
  }

  /**
   * 处理 {@code :new [path]}：把 classpath 里的样例文档复制一份到输出目录（或用户指定路径）， 打印路径供你复制粘贴到对话里。
   *
   * <p>为什么不直接替你 {@code open_docx}？因为示例要演示「Agent 自己打开文档」这条正常路径； 这里只负责把一个可玩的文件落到磁盘上，剩下交给对话。
   */
  private static void handleNewDoc(String input) throws IOException {
    String[] parts = input.split("\\s+", 2);
    Path target;
    if (parts.length > 1 && !parts[1].trim().isEmpty()) {
      target = Path.of(parts[1].trim()).toAbsolutePath();
    } else {
      target = ExamplePaths.outputDir().resolve("interactive-input.docx");
    }
    try (InputStream in = InteractiveDocxAgentExample.class.getResourceAsStream(SAMPLE_RESOURCE)) {
      if (in == null) {
        System.err.println("找不到 classpath 资源: " + SAMPLE_RESOURCE);
        return;
      }
      Files.createDirectories(target.getParent());
      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
    }
    System.out.println("已准备样例文档副本：");
    System.out.println("  " + target);
    System.out.println("接下来可以发：打开 " + target + " 并告诉我它的结构。");
  }

  /**
   * 流式事件回显：把 Agent 循环的增量实时打到终端。
   *
   * <p>对照 {@code StreamingAgentExample} 的 handleEvent，这里做了交互式友好的微调：
   *
   * <ul>
   *   <li>{@link AgentEvent.TextDelta} —— 直接 {@code print} 不换行，模拟打字机流式效果。
   *   <li>{@link AgentEvent.ToolStart} / {@link AgentEvent.ToolEnd} —— 用灰一点的前缀行显示，
   *       让「助手说话」和「助手在调工具」视觉上分开。
   *   <li>{@link AgentEvent.AgentError} —— 走 stderr。
   * </ul>
   */
  private static void handleEvent(AgentEvent event) {
    if (event instanceof AgentEvent.TextDelta) {
      System.out.print(((AgentEvent.TextDelta) event).delta());
      System.out.flush();
    } else if (event instanceof AgentEvent.ToolStart) {
      AgentEvent.ToolStart ts = (AgentEvent.ToolStart) event;
      System.out.println();
      System.out.println("  [调用工具] " + ts.toolName() + "(" + ts.arguments() + ")");
    } else if (event instanceof AgentEvent.ToolEnd) {
      AgentEvent.ToolEnd te = (AgentEvent.ToolEnd) event;
      System.out.println("  [工具结果] " + te.result());
      // 工具结束后，下一段 TextDelta 会紧接输出；这里不强制换行，
      // 让 Agent 的总结文本自然跟在工具结果后面。
    } else if (event instanceof AgentEvent.AgentError) {
      System.err.println("  [Agent 错误] " + rootMessage(((AgentEvent.AgentError) event).error()));
    }
    // RoundStart/RoundEnd/ThinkingDelta/ToolCallDelta 这里不单独打印，
    // 避免在交互场景里刷屏；需要时可按 StreamingAgentExample 的写法补充。
  }

  private static void printHelp() {
    System.out.println("========================================");
    System.out.println("交互式 docx 编辑助手（nonchain Agent × nondocx）");
    System.out.println("========================================");
    System.out.println("直接输入中文指令与助手对话，例如：");
    System.out.println("  打开 /abs/path/to/a.docx 并告诉我它有几段、几个表格");
    System.out.println("  把第 1 段第 0 个 run 改成「你好」，然后保存到 /abs/path/out.docx");
    System.out.println("  打开 /abs/path/with-toc.docx 并用 read_toc 读出目录");
    System.out.println("命令：");
    System.out.println("  :new [path]   准备一份样例文档副本（默认写到 target/examples-output/）");
    System.out.println("  :help         显示本帮助");
    System.out.println("  :q            退出");
  }

  /** 与批处理示例一致的日志回调：打印每轮 LLM 耗时与工具耗时，便于观察 Agent 循环开销。 */
  private static ChainCallback loggingCallback() {
    return new ChainCallback() {
      @Override
      public void onLlmComplete(LlmCompleteEvent event) {
        System.out.println();
        System.out.println("  [LLM] 耗时 " + event.latencyMs() + "ms");
      }

      @Override
      public void onToolComplete(ToolCompleteEvent event) {
        System.out.println("  [Tool] " + event.toolName() + " 耗时 " + event.latencyMs() + "ms");
      }
    };
  }

  private static String rootMessage(Throwable e) {
    Throwable cur = e;
    while (cur.getCause() != null && cur.getCause() != cur) {
      cur = cur.getCause();
    }
    return cur.getMessage();
  }

  private InteractiveDocxAgentExample() {}
}
