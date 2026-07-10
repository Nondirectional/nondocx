package com.non.docx.toolkit.orchestration.specialist;

import com.non.docx.toolkit.HeaderFooterTocTools;
import com.non.docx.toolkit.orchestration.ConflictKey;
import com.non.docx.toolkit.orchestration.DocumentSnapshot;
import com.non.docx.toolkit.orchestration.ExpertPlan;
import com.non.docx.toolkit.orchestration.Operation;
import com.non.docx.toolkit.orchestration.agent.ExpertAgent;
import com.non.docx.toolkit.orchestration.review.ReviewResult;
import com.non.docx.toolkit.orchestration.session.OrchestratorSession;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 页眉页脚目录领域专家：偏只读，处理页眉页脚/目录的读取与说明任务。
 *
 * <p><b>OOXML 三层递进（页眉目录专家）：</b>
 *
 * <ul>
 *   <li><b>OOXML</b>：页眉页脚是独立的 XML part（{@code header1.xml}/{@code footer1.xml}），目录是 {@code <w:sdt>}
 *       里的 TOC 字段。
 *   <li><b>POI</b>：{@code XWPFDocument.getHeaderList()/getFooterList()} 取页眉页脚； {@code
 *       getTableOfContents()} 取目录结构。
 *   <li><b>nondocx</b>：{@code HeaderFooterTocTools} 封装读取；本专家把读取结果组织成一条只读 operation， 其 payload
 *       携带读取报告，review 默认 APPROVED（只读不改）。
 * </ul>
 *
 * <p><b>第一版偏只读。</b> 不追求复杂写入——页眉页脚目录的写操作风险较高（影响全局版式），第一版只做读取与说明。
 */
public final class HeaderTocAgent implements ExpertAgent {

  private final HeaderFooterTocTools headerFooterToc;
  private final AtomicLong opIdSeq = new AtomicLong();

  public HeaderTocAgent(HeaderFooterTocTools headerFooterToc) {
    this.headerFooterToc = headerFooterToc;
  }

  @Override
  public String name() {
    return "HeaderTocAgent";
  }

  @Override
  public boolean relevantTo(String intent, DocumentSnapshot snapshot) {
    if (intent == null) return false;
    String lower = intent.toLowerCase(Locale.ROOT);
    return lower.contains("页眉")
        || lower.contains("页脚")
        || lower.contains("目录")
        || lower.contains("header")
        || lower.contains("footer")
        || lower.contains("toc");
  }

  @Override
  public ExpertPlan plan(OrchestratorSession session, DocumentSnapshot snapshot, String intent) {
    // 只读：读页眉页脚 + 目录，组织成报告 operation
    StringBuilder report = new StringBuilder();
    if (snapshot.overview().hasHeader() || snapshot.overview().hasFooter()) {
      try {
        // 读 section 0 的页眉段落 0 + 页脚段落 0（第一版只取首页摘要）
        String header = headerFooterToc.readHeaderFooter(session.docId(), "HEADER", 0, 0);
        report.append(header).append('\n');
      } catch (RuntimeException ignored) {
        report.append("（页眉读取失败）\n");
      }
      try {
        String footer = headerFooterToc.readHeaderFooter(session.docId(), "FOOTER", 0, 0);
        report.append(footer).append('\n');
      } catch (RuntimeException ignored) {
        report.append("（页脚读取失败）\n");
      }
    }
    if (snapshot.overview().hasToc()) {
      report.append('\n');
      try {
        String toc = headerFooterToc.readToc(session.docId());
        report.append(toc);
      } catch (RuntimeException ignored) {
        report.append("（目录读取失败）");
      }
    }

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("report", report.toString());
    Operation op =
        Operation.of(
            nextOpId(),
            "header-toc",
            "read_header_toc",
            "doc",
            payload,
            new ConflictKey("header-toc", "read_header_toc", "doc"),
            "读取页眉页脚目录",
            "只读说明，不改文档",
            "");
    // 只读 operation，标记 APPROVED
    op = op.withReview(ReviewResult.approved("只读分析"));

    return new ExpertPlan(
        name(),
        "header-toc-plan-" + session.sessionGeneration(),
        session.conversationId(),
        snapshot.snapshotVersion(),
        session.sessionGeneration(),
        List.of(op));
  }

  String nextOpId() {
    return "header-toc-op-" + opIdSeq.incrementAndGet();
  }
}
