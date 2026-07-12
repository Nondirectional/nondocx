package com.non.docx.toolkit;

import com.non.chain.tool.ToolDef;
import com.non.chain.tool.ToolParam;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.header.Footer;
import com.non.docx.core.api.header.Header;
import com.non.docx.core.api.text.Paragraph;
import com.non.docx.core.api.toc.TableOfContents;
import com.non.docx.core.api.toc.TocEntry;
import com.non.docx.toolkit.capability.CapabilityOperation;
import com.non.docx.toolkit.capability.ParamCapability;
import com.non.docx.toolkit.capability.ParamType;
import com.non.docx.toolkit.capability.ToolCapability;
import com.non.docx.toolkit.ref.ElementRef;
import com.non.docx.toolkit.ref.ElementRefs;
import com.non.docx.toolkit.ref.ElementResolver;
import com.non.docx.toolkit.ref.HeaderFooterRef;
import com.non.docx.toolkit.ref.RefResolutionException;
import com.non.docx.toolkit.ref.ReferenceContext;
import com.non.docx.toolkit.result.ToolResult;
import com.non.docx.toolkit.result.ToolResultCode;
import com.non.docx.toolkit.result.ToolResultRenderer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;

/**
 * 页眉 / 页脚（读取）+ 目录（TOC，只读）工具组（原 F + G 组）。
 *
 * <p><b>OOXML 三层递进（页眉页脚）：</b>
 *
 * <ul>
 *   <li><b>OOXML</b>：页眉页脚不在 {@code word/document.xml} 的 body 里，而是独立 ZIP part （{@code header1.xml} /
 *       {@code footer1.xml}），通过 section 的 {@code <w:sectPr>} 里的 {@code <w:headerReference>} /
 *       {@code <w:footerReference>} 引用。
 *   <li><b>POI</b>：{@link XWPFHeaderFooterPolicy} 负责按某个 {@code sectPr} 解析出已附加的页眉页脚 part。
 *       关键区别：{@code getDefaultHeader()} 只读返回（不存在返回 null），而 {@code createHeader(DEFAULT)}
 *       会<em>新建</em>并附加一个 part——后者会修改文档。
 *   <li><b>nondocx</b>：读写分离后，{@code Section.header()}/{@code footer()} 就是 POI {@code
 *       getDefaultHeader/Footer()} 的只读映射（null=不存在）；需要创建时用 {@code ensureHeader()}/{@code
 *       ensureFooter()}。所以本工具直接调 {@code header()}/{@code footer()} 即可安全只读。
 * </ul>
 *
 * <p><b>OOXML 三层递进（目录）：</b> TOC 不是独立元素,而是正文里的一个<b>域</b>(由 {@code fldChar} 的 begin/separate/end 界定);
 * nondocx 把「找域 → 解析条目」收进 core,这里只调 {@code doc.toc()} 拿到 {@link TableOfContents} 后逐条拼成纯文本。
 */
public final class HeaderFooterTocTools extends ToolkitToolContext {

  /** 接收门面注入的共享会话状态（与 SessionTools 共享同一份 sessions/seq）。 */
  HeaderFooterTocTools(Map<String, Document> sharedSessions, AtomicInteger sharedSeq) {
    super(sharedSessions, sharedSeq);
  }

  HeaderFooterTocTools(
      Map<String, Document> sharedSessions,
      AtomicInteger sharedSeq,
      ReferenceContext sharedReferences,
      Map<String, Long> sharedGenerations) {
    super(sharedSessions, sharedSeq, sharedReferences, sharedGenerations);
  }

  // ==================== 页眉 / 页脚（读取） ====================

  @ToolDef(
      name = "read_header_footer",
      description =
          "读取第 section_index 个 section 的默认页眉或页脚里第 paragraph_index 段的结构摘要。"
              + "part 支持 HEADER/FOOTER（大小写不敏感）。")
  @ToolCapability(operation = CapabilityOperation.READ, element = "header_footer")
  public String readHeaderFooter(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "part", description = "HEADER=页眉,FOOTER=页脚")
          @ParamCapability(
              type = ParamType.ENUM,
              enumValues = {"HEADER", "FOOTER"})
          String part,
      @ToolParam(name = "section_index", description = "section 索引（0 起）")
          @ParamCapability(type = ParamType.INTEGER)
          int sectionIndex,
      @ToolParam(name = "paragraph_index", description = "页眉/页脚内段落索引（0 起）")
          @ParamCapability(type = ParamType.INTEGER)
          int paragraphIndex) {
    boolean isHeader;
    if ("HEADER".equalsIgnoreCase(part)) {
      isHeader = true;
    } else if ("FOOTER".equalsIgnoreCase(part)) {
      isHeader = false;
    } else {
      ToolResult<Void> r =
          ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "part 仅支持 HEADER/FOOTER");
      return ToolResultRenderer.render(r);
    }
    return readHeaderFooterParagraph(docId, sectionIndex, paragraphIndex, isHeader);
  }

  /** 按稳定 HeaderFooterRef 读取整个页眉或页脚，并返回其中段落 ref。 */
  @ToolDef(
      name = "read_header_footer_ref",
      description = "按 canonical HeaderFooterRef 读取整个页眉或页脚，返回文本与内部段落 ref")
  @ToolCapability(operation = CapabilityOperation.READ, element = "header_footer")
  public String readHeaderFooterRef(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "ref", description = "canonical HeaderFooterRef")
          @ParamCapability(type = ParamType.REF)
          String ref) {
    Document doc = document(docId);
    if (doc == null) {
      return renderDocNotFound(docId);
    }
    ElementResolver resolver = elementResolver(docId);
    try {
      ElementRef parsed = ElementRefs.parse(ref);
      if (!(parsed instanceof HeaderFooterRef)) {
        ToolResult<Void> r =
            ToolResult.fail(ToolResultCode.REF_TYPE_MISMATCH, "该工具只接受 HeaderFooterRef");
        return ToolResultRenderer.render(r);
      }
      List<Paragraph> paragraphs;
      String part;
      try {
        paragraphs = resolver.resolveHeader((HeaderFooterRef) parsed).paragraphs();
        part = "页眉";
      } catch (RefResolutionException headerError) {
        paragraphs = resolver.resolveFooter((HeaderFooterRef) parsed).paragraphs();
        part = "页脚";
      }
      StringBuilder sb = new StringBuilder(part).append(" ref=").append(ref);
      for (int i = 0; i < paragraphs.size(); i++) {
        Paragraph paragraph = paragraphs.get(i);
        sb.append("\n段落 ")
            .append(i)
            .append(" ref=")
            .append(resolver.reference(paragraph).canonical())
            .append("\n文本: ")
            .append(paragraph.text());
      }
      ToolResult<String> result = ToolResult.ok(ref, sb.toString());
      return ToolResultRenderer.render(result);
    } catch (RefResolutionException e) {
      ToolResult<Void> result = ToolResult.fail(e.code().toToolResultCode(), e.render());
      return ToolResultRenderer.render(result);
    }
  }

  /**
   * 页眉/页脚段落读取的共享实现。
   *
   * <p>读写分离后 header()/footer() 只读返回，null 表示该 section 未设置页眉/页脚。
   */
  private String readHeaderFooterParagraph(
      String docId, int sectionIndex, int paragraphIndex, boolean isHeader) {
    Document doc = document(docId);
    if (doc == null) {
      return renderDocNotFound(docId);
    }
    var sections = doc.sections();
    if (outOfBounds(sectionIndex, sections.size())) {
      return renderIndexError("section 索引", sectionIndex, sections.size());
    }
    // 读写分离后 header()/footer() 只读返回，null 表示该 section 未设置页眉/页脚。
    Object hf =
        isHeader ? sections.get(sectionIndex).header() : sections.get(sectionIndex).footer();
    if (hf == null) {
      // 用提示串而非"错误"，让 Agent 知道这里确实没有内容，而不是索引算错。
      String message =
          (isHeader ? "页眉" : "页脚") + " section=" + sectionIndex + " 不存在（该 section 未设置页眉/页脚）";
      ToolResult<Void> result = ToolResult.ok(message);
      return ToolResultRenderer.render(result);
    }
    List<Paragraph> paras = isHeader ? ((Header) hf).paragraphs() : ((Footer) hf).paragraphs();
    if (outOfBounds(paragraphIndex, paras.size())) {
      return renderIndexError((isHeader ? "页眉" : "页脚") + "内段落索引", paragraphIndex, paras.size());
    }
    Paragraph p = paras.get(paragraphIndex);
    ElementResolver resolver = elementResolver(docId);
    HeaderFooterRef partRef =
        isHeader ? resolver.reference((Header) hf) : resolver.reference((Footer) hf);
    int runCount = p.runs().size();
    long hyperlinkCount = hyperlinkCount(p);
    String message =
        (isHeader ? "页眉" : "页脚")
            + " section="
            + sectionIndex
            + " 段落 "
            + paragraphIndex
            + "\npart ref: "
            + partRef.canonical()
            + "\nparagraph ref: "
            + resolver.reference(p).canonical()
            + "\n文本: "
            + p.text()
            + "\nrun 数: "
            + runCount
            + "\n超链接数: "
            + hyperlinkCount;
    ToolResult<Void> result = ToolResult.ok(message);
    return ToolResultRenderer.render(result);
  }

  // ==================== 目录（TOC，只读） ====================

  /**
   * 读取文档的目录(Table of Contents),一次返回所有条目(标题 / 层级 / 页码 / 内部锚点)。
   *
   * <p><b>只读 / 不刷新</b>: POI 没有 Word 的分页引擎,算不出页码,故创建/刷新目录不在能力范围。 这里返回的页码是文档里<b>已缓存的</b>值——若目录被标记为
   * {@code dirty}（源文档改动后未刷新）， 会在结果顶部提示「可能已过期」，Agent 应据此提醒用户页码可能不准。
   *
   * @param docId 文档句柄
   * @return 目录条目列表（多行纯文本）；无目录时返回提示串
   */
  @ToolDef(
      name = "read_toc",
      description =
          "读取文档的目录(TOC),一次返回所有条目:每条含标题、层级(1-9)、页码、内部锚点。" + "无目录时返回提示。需要看文档结构/目录时优先用它,不要逐段 read 盲读。")
  @ToolCapability(operation = CapabilityOperation.READ, element = "toc")
  public String readToc(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId) {
    Document doc = document(docId);
    if (doc == null) {
      return renderDocNotFound(docId);
    }
    TableOfContents toc = doc.toc();
    if (toc == null) {
      ToolResult<Void> result = ToolResult.ok("该文档没有目录(TOC 域)。");
      return ToolResultRenderer.render(result);
    }
    List<TocEntry> entries = toc.entries();
    if (entries.isEmpty()) {
      // 有 TOC 域但解析不出条目:罕见,可能 TOC 尚未在 Word 里生成(只有空域壳)。提示用户去 Word 刷新。
      ToolResult<Void> result = ToolResult.ok("存在目录域,但未能解析出条目(可能尚未在 Word 中生成,请打开文档刷新目录)。");
      return ToolResultRenderer.render(result);
    }
    StringBuilder sb = new StringBuilder();
    sb.append("目录(").append(entries.size()).append(" 条)");
    if (toc.dirty()) {
      sb.append(" · 已标记过期(页码可能不准,需在 Word 里刷新)");
    }
    sb.append('\n');
    for (int i = 0; i < entries.size(); i++) {
      TocEntry e = entries.get(i);
      sb.append('[').append(i).append("] ").append(e.level()).append("级 「").append(e.title());
      appendPageAndAnchor(sb, e);
      if (i < entries.size() - 1) {
        sb.append('\n');
      }
    }
    ToolResult<Integer> result = ToolResult.ok(entries.size(), sb.toString());
    return ToolResultRenderer.render(result);
  }

  /** 把条目的页码与锚点追加进结果串:页码非空则附「· 第N页」,有锚点则附「· 锚点 _Toc...」。 */
  private static void appendPageAndAnchor(StringBuilder sb, TocEntry e) {
    if (!e.pageNumber().isEmpty()) {
      sb.append("」· 第 ").append(e.pageNumber()).append(" 页");
    } else {
      sb.append("」");
    }
    if (e.anchor() != null) {
      sb.append(" · 锚点 ").append(e.anchor());
    }
  }
}
