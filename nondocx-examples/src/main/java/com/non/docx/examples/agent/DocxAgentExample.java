package com.non.docx.examples.agent;

import com.non.chain.ChatResult;
import com.non.chain.agent.Agent;
import com.non.chain.callback.ChainCallback;
import com.non.chain.callback.event.LlmCompleteEvent;
import com.non.chain.callback.event.ToolCompleteEvent;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;
import com.non.chain.tool.ToolRegistry;
import com.non.docx.examples.ExamplePaths;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * nonchain Agent × nondocx 综合示例：把一组 docx 读写工具注册给 Agent， 让 Agent 像操作「文档编辑器」那样读取、编辑并保存一份 .docx。
 *
 * <p><b>它演示了什么</b>
 *
 * <ol>
 *   <li>nonchain 侧：如何用 {@link ToolRegistry#scan(Object)} 把一个实例类的 {@link
 *       com.non.chain.tool.ToolDef @ToolDef} 方法批量注册为工具， 再用 {@link Agent.Builder} 组装出 LLM + 工具循环的
 *       Agent。
 *   <li>nondocx 侧：{@link DocxAgentTools} 把 docx 的「段落 / run / 表格单元格 / 超链接 / 页眉页脚」 链式活对象模型逐段暴露给
 *       LLM，并提供 {@code search_text} 跨容器文本搜索， 让 Agent 在不碰 POI 的情况下完成读取与编辑。
 * </ol>
 *
 * <p><b>运行前置</b>：环境变量 {@code DASHSCOPE_API_KEY}（阿里云灵积平台 API Key）。 未设置时本示例无法端到端运行（会启动失败），但模块本身可正常编译。
 *
 * <p><b>两段流程</b>（design §6）：先让 Agent 读取并汇报文档结构， 再让 Agent 执行一次编辑（改 run 文本、改表格单元格、改超链接文本与
 * URL）并保存为输出文档。prompt 里预置路径与索引以降低 LLM 越界率（design §6.1 稳定性取舍）。
 *
 * <pre>{@code
 * mvn -q -pl nondocx-examples exec:java \
 *   -Dexec.mainClass=com.non.docx.examples.agent.DocxAgentExample
 * }</pre>
 */
public final class DocxAgentExample {

  /** 样例输入文档在 classpath 中的位置（由 {@code SampleDocGenerator} 生成并入库）。 */
  private static final String SAMPLE_RESOURCE = "/document/sample-agent-input.docx";

  /** 约束 Agent 只用 docx 工具、路径由用户消息给定、遇错误串可重试。 */
  private static final String SYSTEM_PROMPT =
      "你是一个 docx 文档编辑助手。你只能通过提供的 docx 工具操作文档："
          + "先 open_docx 拿到 docId，再用 read_* 查看结构、用 replace_*/update_* 修改内容，"
          + "最后 save_docx 落盘、close_docx 释放。"
          + "不要编造文件路径——所有路径都在用户消息里给出。"
          + "所有索引从 0 开始。若工具返回「错误：...」字符串，说明索引越界或句柄失效，"
          + "请先调用对应的 read_*/get_*_count 重新确认结构后再重试。"
          // search_text 的引导：按文本找位置时务必先用它一次定位，再 replace_*，
          // 避免逐段 read_paragraph 盲读造成大量 LLM 往返。
          + "需要按文本内容定位位置（如「把『项目进度』改成…」）时，"
          + "优先用 search_text 一次性拿到坐标，再用 replace_run_text/replace_table_cell_run_text 修改，"
          + "不要逐个 read_paragraph 盲读。页眉页脚里的文本也能被 search_text 命中，"
          + "需要其结构细节时用 read_header/read_footer。"
          + "用简洁中文汇报。";

  public static void main(String[] args) throws Exception {
    LLM llm = new DashscopeLLM("qwen3.7-plus").maxCompletionTokens(1024);
    DocxAgentTools tools = new DocxAgentTools();
    ToolRegistry registry = new ToolRegistry().scan(tools);

    Agent agent =
        Agent.builder(llm, registry)
            .systemPrompt(SYSTEM_PROMPT)
            // 两段流程（先读后改）合计工具调用可能十余次，留足迭代空间，
            // 否则细粒度工具会把 maxIterations 用在 read 上、来不及收尾汇报。
            .maxIterations(20)
            .callback(loggingCallback())
            .build();

    // 把 classpath 内的样例文档复制到工作目录，让 Agent 工具按文件路径打开。
    Path input = copyResourceToWorking(SAMPLE_RESOURCE);
    Path output = ExamplePaths.outputDir().resolve("agent-edited.docx");

    // ===== 第一段：读取并汇报文档结构 =====
    System.out.println("========================================");
    System.out.println("第一段：读取文档结构");
    System.out.println("========================================");
    String readQuery =
        "读取并汇报这份文档的结构：先用 open_docx 打开 "
            + input.toAbsolutePath()
            + "，再查看段落数与表格数，"
            + "读取关键段落、表格单元格和超链接的内容，最后 close_docx。用简洁中文汇报。";
    ChatResult readResult = agent.run(readQuery);
    System.out.println("[汇报] " + readResult.content());
    System.out.println();

    // ===== 第二段：编辑并保存 =====
    System.out.println("========================================");
    System.out.println("第二段：编辑文档并保存");
    System.out.println("========================================");
    String editQuery =
        "打开文档 "
            + input.toAbsolutePath()
            + "，执行以下修改后保存为 "
            + output.toAbsolutePath()
            + "：\n"
            + "1) 把正文第 1 段第 0 个 run 的文本改成「本周 nondocx 封装已全部完成，」；\n"
            + "2) 把表格 (0, 2, 1) 单元格第 0 段第 0 个 run 的文本改成「已完成」；\n"
            + "3) 把正文第 2 段第 0 个超链接的显示文本改成「nondocx 项目主页」、"
            + "目标 URL 改成「https://github.com/Nondirectional/nondocx/blob/main/README.md」；\n"
            + "完成后 save_docx 落盘，再 close_docx。";
    ChatResult editResult = agent.run(editQuery);
    System.out.println("[汇报] " + editResult.content());

    System.out.println();
    System.out.println("输出文档: " + output.toAbsolutePath());
  }

  /**
   * 把 classpath 资源复制到 {@code target/examples-output/} 下，返回其路径。
   *
   * <p>Agent 工具按文件路径 {@code open_docx}，而样例文档在 jar 内（classpath）， 所以 main 先把它落到磁盘再传路径。
   */
  private static Path copyResourceToWorking(String resource) throws IOException {
    Path target = ExamplePaths.outputDir().resolve("sample-agent-input.docx");
    try (InputStream in = DocxAgentExample.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IOException("classpath 资源不存在: " + resource);
      }
      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
    }
    return target;
  }

  /** 复刻 AgentLoopExample 的日志回调：打印每轮 LLM 耗时与工具调用结果，便于教学观察 Agent 循环。 */
  private static ChainCallback loggingCallback() {
    return new ChainCallback() {
      @Override
      public void onLlmComplete(LlmCompleteEvent event) {
        System.out.println("[LLM] 耗时 " + event.latencyMs() + "ms");
      }

      @Override
      public void onToolComplete(ToolCompleteEvent event) {
        System.out.println(
            "[Tool] " + event.toolName() + " 耗时 " + event.latencyMs() + "ms → " + event.result());
      }
    };
  }

  private DocxAgentExample() {}
}
