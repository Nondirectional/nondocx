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
import com.non.docx.toolkit.DocxToolkit;
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
 *   <li>nondocx 侧：{@link DocxToolkit} 把 docx 的「段落 / run / 表格单元格 / 超链接 / 页眉页脚」 链式活对象模型逐段暴露给 LLM，并提供
 *       {@code search_text} 跨容器文本搜索， 让 Agent 在不碰 POI 的情况下完成读取与编辑。
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

  /** 约束 Agent 只用 docx 工具、路径由用户消息给定、遇错误串可重试；并指导「直接编辑」与「修订模式」两套工作方式。 */
  private static final String SYSTEM_PROMPT =
      "你是一个 docx 文档编辑助手。你只能通过提供的 docx 工具操作文档："
          + "先 open_docx 拿到 docId，再用 read_* 查看结构、按需修改内容，"
          + "最后 save_docx 落盘、close_docx 释放。"
          + "不要编造文件路径——所有路径都在用户消息里给出。"
          + "所有索引从 0 开始。若工具返回「错误：...」字符串，说明索引越界或句柄失效，"
          + "请先调用对应的 read_*/get_*_count 重新确认结构后再重试。"
          // search_text 的引导：按文本找位置时务必先用它一次定位，再 replace_*，
          // 避免逐段 read_paragraph 盲读造成大量 LLM 往返。
          + "需要按文本内容定位位置（如「把『项目进度』改成…」）时，"
          + "优先用 search_text 一次性拿到坐标，再用 replace_run_text/replace_table_cell_run_text 修改，"
          + "不要逐个 read_paragraph 盲读。页眉页脚里的文本也能被 search_text 命中，"
          + "需要其结构细节时用 read_header_footer。\n"
          // —— 批量工具(v2)：核心工具支持一次操作多个目标 ——
          + "【批量工具】以下工具支持一次操作多个目标，单次调用时传长度 1 的数组即可："
          + "read_paragraph（段落索引数组 paragraph_indexes，一次读多段）、"
          + "update_paragraph_alignment（对象数组 edits，每个含 paragraph_index/alignment）、"
          + "read_run / read_table_cell（对象数组 runs/cells，一次读多个 run 或单元格）、"
          + "create_table（二维数组 rows，外层为行、内层为单元格文本）、"
          + "set_table_borders（table_index + border_style=NONE 显式无边框）、"
          + "merge_table_cells（对象数组 merges，HORIZONTAL/VERTICAL 合并连续单元格）、"
          + "replace_run_text / replace_table_cell_run_text（对象数组 edits，每个对象含完整坐标与 text，"
          + "一次改多处，部分失败不中断、返回每条成功/失败明细）、"
          + "update_run_style（对象数组 edits，每个含坐标和 bold/italic/underline/font/font_size/color）、"
          + "insert_paragraph（对象数组 paragraphs，每个含 body_index/text，可在开头/中间/末尾插段）、"
          + "insert_tracked_run（author 共享 + 对象数组 edits，每个含 paragraph_index/text 及可选样式）、"
          + "delete_run_tracked / replace_run_tracked（author 共享 + 对象数组 edits，每个含坐标，"
          + "同段删/换多个 run 不会索引漂移、自动去重）、"
          + "mark_tracked_cells（change_type=INSERTED/DELETED，author 共享 + 对象数组 cells）、"
          + "apply_tracked_changes（action=ACCEPT/REJECT，target=TEXT_OR_MOVE/PROPERTY/CELL，"
          + "ids 为 id 数组，一次处理多条修订）。"
          + "另：update_hyperlink（text 与 url 都可选，一次改齐超链接的显示文本和/或地址）。"
          + "要同时改/读多处时优先用批量，减少 LLM 往返。\n"
          // —— 两套工作方式：直接编辑 vs 修订模式 ——
          + "你有两套工作方式，按用户意图选择：\n"
          + "（A）直接编辑：用 replace_run_text / replace_table_cell_run_text / update_paragraph_alignment / update_run_style / update_* 修改，"
          + "改动立即生效、不留痕迹。适用于用户要「直接改好」的场景。\n"
          + "（B）修订模式（tracked changes）：改动以修订标记形式写入，便于他人审阅后再定稿。"
          + "适用于用户表达「以修订标记改」「带修订标记」「供审阅」「批注式修改」"
          + "「列出/接受/拒绝修订」等意图，或文档要求留痕的协作场景。\n"
          + "【修订模式怎么工作】修改时用 insert_tracked_run（插入带可选样式的修订 run）、"
          + "delete_run_tracked（把一个 run 标为删除）、"
          + "replace_run_tracked（以删旧+插新替换文本，保留原样式）、"
          + "mark_style_change_tracked（把样式变更记为 rPrChange）、"
          + "mark_tracked_cells（标记单元格存亡）、"
          + "move_run_tracked（把一个 run 移到另一段）；"
          + "查看/处理已有修订时：list_tracked_changes 一次性列出全部修订（每条带 type/author 与"
          + "寻址用的 stable id），再用 apply_tracked_changes 按 action 与 target 处理："
          + "文本/移动类 target=TEXT_OR_MOVE，属性类 target=PROPERTY，单元格类 target=CELL；"
          + "或用 apply_text_revisions 按 scope=ALL/AUTHOR 批量处理文本/移动类。"
          + "注意 cellMerge 的 accept/reject 不支持（会返回错误串）。\n"
          // —— 修订开关 settings.xml 的 <w:trackChanges/>：与人接力编辑时才有意义 ——
          + "【修订开关与你的修订标记是两件事】get_tracked_changes_enabled 查文档是否开启了修订开关，"
          + "set_tracked_changes_enabled 可以开/关它。但请注意：开关只影响『人在 Word 里后续手动改动是否被自动追踪』，"
          + "对你用 insert_tracked_run 等 *_tracked 工具创作的修订标记『无影响』——"
          + "那些修订无论开关开不开都会真实写入并被 Word 识别。"
          + "所以多数修订场景你『不需要』动开关；只有当任务明确要求『文档交还人后、人的手动改动也要被追踪』"
          + "（即开启 Word 的『修订』按钮语义）时，才用 set_tracked_changes_enabled(true)。\n"
          + "用简洁中文汇报。";

  public static void main(String[] args) throws Exception {
    LLM llm = new DashscopeLLM("qwen3.7-plus").maxCompletionTokens(1024);
    // DocxToolkit 门面构造全部六组工具并共享同一份文档会话；scanAll 把它们一次注册进 registry。
    DocxToolkit toolkit = new DocxToolkit();
    ToolRegistry registry = toolkit.scanAll(new ToolRegistry());

    Agent agent =
        Agent.builder(llm, registry)
            .systemPrompt(SYSTEM_PROMPT)
            // 不限制迭代次数：让 Agent 自行跑完所有工具调用直至自然收尾。
            // nonchain 0.10.0 起，普通上限用完后会进入 graceful 收尾；这里用 Integer.MAX_VALUE
            // 实际等同于不设上限（不会真跑到 21 亿次；Agent 完成任务后自然结束）。
            .maxIterations(Integer.MAX_VALUE)
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

    // ===== 第三段：修订模式（tracked changes）=====
    System.out.println();
    System.out.println("========================================");
    System.out.println("第三段：以修订模式（tracked changes）修改并审阅");
    System.out.println("========================================");
    Path revisionOutput = ExamplePaths.outputDir().resolve("agent-revised.docx");
    String revisionQuery =
        "打开文档 "
            + input.toAbsolutePath()
            + "，以「修订模式」（带修订标记）做以下修改后保存为 "
            + revisionOutput.toAbsolutePath()
            + "：\n"
            + "1) 在正文第 0 段末尾用 insert_tracked_run 插入一条修订 run，文本「（请审阅）」，"
            + "作者署名「Agent」，加粗；\n"
            + "2) 用 list_tracked_changes 列出当前所有修订，挑出刚插入的那条，"
            + "用 apply_tracked_changes(action=ACCEPT,target=TEXT_OR_MOVE) 应用它（让插入生效）；\n"
            + "3) 完成后 save_docx 落盘、close_docx。用简洁中文汇报修改与审阅结果。";
    ChatResult revisionResult = agent.run(revisionQuery);
    System.out.println("[汇报] " + revisionResult.content());

    System.out.println();
    System.out.println("输出文档(直接编辑): " + output.toAbsolutePath());
    System.out.println("输出文档(修订模式): " + revisionOutput.toAbsolutePath());
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
