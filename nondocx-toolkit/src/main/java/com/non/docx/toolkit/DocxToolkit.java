package com.non.docx.toolkit;

import com.non.chain.tool.ToolRegistry;

/**
 * nondocx-toolkit 的聚合门面：一次构造出全部七组工具，并保证它们共享同一份文档会话状态。
 *
 * <p><b>为什么需要门面。</b> 工具集按功能域拆成了七个类（{@link SessionTools} / {@link BodyTools} / {@link TableTools} /
 * {@link HeaderFooterTocTools} / {@link TrackedChangeQueryTools} / {@link
 * TrackedChangeAuthoringTools}），但它们必须<b>共享同一份</b> {@code sessions}/{@code seq}： Agent 在一轮对话里 {@code
 * open_docx}（SessionTools）打开的文档，紧接着 {@code read_paragraph}（BodyTools）、 {@code
 * list_tracked_changes}（TrackedChangeQueryTools）都要能按 docId 取回。门面负责把 SessionTools 自建的会话状态注入给其它五个类。
 *
 * <p><b>典型用法。</b>
 *
 * <pre>{@code
 * DocxToolkit toolkit = new DocxToolkit();
 * ToolRegistry registry = toolkit.scanAll(new ToolRegistry());
 * Agent agent = Agent.builder(llm, registry).systemPrompt(...).build();
 * }</pre>
 *
 * <p>测试或非 Agent 场景也可直接持有 toolkit 的各字段，逐个调用工具方法：
 *
 * <pre>{@code
 * DocxToolkit tk = new DocxToolkit();
 * String docId = tk.session.openDocx("/path/to/a.docx");
 * System.out.println(tk.body.readParagraph(docId, List.of(0)));
 * }</pre>
 *
 * <p><b>会话状态共享原理。</b> {@link SessionTools} 走 {@link ToolkitToolContext#ToolkitToolContext()}
 * 无参构造，自建 {@code sessions}/{@code seq}；门面再经 {@code SessionTools.sharedSessions()}/ {@code
 * sharedSeq()}（包级可见）把它们传给其它五个类的 {@code (sessions, seq)} 构造。 六个工具类因此共享<b>同一个</b> Map 与 AtomicInteger
 * 实例。
 *
 * <p><b>线程模型。</b> 与单工具类一致——为单 Agent 实例设计，内部状态未做并发保护，不要跨 Agent 共享。
 */
public final class DocxToolkit {

  /** 会话工具组：open/save/close + 文档概览。本组自建 sessions/seq，是会话状态的「源头」。 */
  public final SessionTools session;

  /** 正文工具组：正文 run / 超链接 / 文本搜索。 */
  public final BodyTools body;

  /** 表格工具组：单元格读 / 单元格内 run 读改。 */
  public final TableTools table;

  /** 页眉页脚 + 目录工具组（只读）。 */
  public final HeaderFooterTocTools headerFooterToc;

  /** 修订读取 / 处理工具组：开关查询、枚举、accept/reject。 */
  public final TrackedChangeQueryTools trackedChangeQuery;

  /** 修订创作工具组：insert / delete / replace / move / mark 样式与单元格。 */
  public final TrackedChangeAuthoringTools trackedChangeAuthoring;

  /** 文档质量自检工具组：版式/兼容性自检（空白页、行距、表格分页、图片溢出、SOLID 底纹等 10 项）。 */
  public final QualityCheckTools qualityCheck;

  /** 构造全部七组工具，共享同一份文档会话状态。 */
  public DocxToolkit() {
    // 先建会话源头：SessionTools 自建 sessions/seq。
    this.session = new SessionTools();
    // 再把这份会话注入给其余六组，保证它们 open 出的文档互相可见。
    this.body =
        new BodyTools(
            session.sharedSessions(),
            session.sharedSeq(),
            session.sharedReferences(),
            session.sharedGenerations());
    this.table =
        new TableTools(
            session.sharedSessions(),
            session.sharedSeq(),
            session.sharedReferences(),
            session.sharedGenerations());
    this.headerFooterToc =
        new HeaderFooterTocTools(
            session.sharedSessions(),
            session.sharedSeq(),
            session.sharedReferences(),
            session.sharedGenerations());
    this.trackedChangeQuery =
        new TrackedChangeQueryTools(
            session.sharedSessions(),
            session.sharedSeq(),
            session.sharedReferences(),
            session.sharedGenerations());
    this.trackedChangeAuthoring =
        new TrackedChangeAuthoringTools(
            session.sharedSessions(),
            session.sharedSeq(),
            session.sharedReferences(),
            session.sharedGenerations());
    this.qualityCheck =
        new QualityCheckTools(
            session.sharedSessions(),
            session.sharedSeq(),
            session.sharedReferences(),
            session.sharedGenerations());
  }

  /**
   * 把全部七组工具注册进一个 {@link ToolRegistry}，返回该 registry（可链式继续配置）。
   *
   * <p>{@code ToolRegistry.scan(Object)} 返回 {@code this}，故可链式 {@code .scan(a).scan(b)}。 这里把七个工具类逐一
   * scan 进同一个 registry，让 Agent 在一次会话里能调用任意一组的工具。
   *
   * @param registry 待注册的 registry（通常为 {@code new ToolRegistry()}）
   * @return 传入的 registry（已注册全部工具）
   */
  public ToolRegistry scanAll(ToolRegistry registry) {
    return registry
        .scan(session)
        .scan(body)
        .scan(table)
        .scan(headerFooterToc)
        .scan(trackedChangeQuery)
        .scan(trackedChangeAuthoring)
        .scan(qualityCheck);
  }
}
