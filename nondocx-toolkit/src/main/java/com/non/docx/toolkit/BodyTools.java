package com.non.docx.toolkit;

import com.non.chain.tool.ToolDef;
import com.non.chain.tool.ToolParam;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.InlineElement;
import com.non.docx.core.api.header.Footer;
import com.non.docx.core.api.header.Header;
import com.non.docx.core.api.style.Alignment;
import com.non.docx.core.api.text.Hyperlink;
import com.non.docx.core.api.text.Paragraph;
import com.non.docx.core.api.text.Run;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 正文 / run / 超链接 / 文本搜索工具组（原 B + D + E 组）。
 *
 * <p>覆盖正文段落与 run 的读写、超链接的读写、跨容器文本搜索。这三块都作用在<b>正文</b>（{@code word/document.xml} 的 body 直属元素）上，归为一类。
 *
 * <p><b>OOXML 三层递进（正文结构）：</b>
 *
 * <ul>
 *   <li><b>OOXML</b>：{@code word/document.xml} 的正文是 {@code <w:p>}（段落）与 {@code <w:tbl>}（表格） 的有序序列；
 *       段落内的内联内容是 {@code <w:r>}（run）和 {@code <w:hyperlink>}（超链接，其内仍含 {@code <w:r>}）。
 *   <li><b>POI</b>：对应 {@code XWPFParagraph} → {@code XWPFRun} / {@code XWPFHyperlinkRun}； {@code
 *       getRuns()} 只暴露普通 run，超链接要单独数。
 *   <li><b>nondocx</b>：{@code doc.paragraphs().get(i).runs().get(j)}（普通 run）与 {@code
 *       .inlineElements()}（含超链接在内的全部内联元素）。
 * </ul>
 *
 * <p>本类<b>复用</b> {@link ToolkitToolContext} 的会话状态与纯辅助方法，经由门面注入同一份 sessions。
 */
public final class BodyTools extends ToolkitToolContext {

  /**
   * 接收门面注入的共享会话状态（与 SessionTools 共享同一份 sessions/seq）。 这样本类的 {@code open} 是 SessionTools 负责，本类只读写。
   */
  BodyTools(Map<String, Document> sharedSessions, AtomicInteger sharedSeq) {
    super(sharedSessions, sharedSeq);
  }

  // ==================== 正文段落 / run ====================

  /**
   * 读取正文多个段落的结构摘要（文本 + run 数 + 是否含超链接 + 段落对齐）。
   *
   * <p><b>批量语义（v2）。</b> 入参是<b>段落索引数组</b> {@code paragraph_indexes},长度 1 即单次读取,
   * 多个即一次读多段——避免"了解文档结构"这类场景里逐段调用造成大量 LLM 往返。 越界的索引不会中断整批, 而是标在结果里("索引越界,共 N"),让 Agent 据此修正后重读。
   *
   * <p>摘要里带上 run 数与超链接数，让 Agent 一次读到寻址所需的上下文，不必再盲猜索引。
   *
   * <p><b>OOXML → POI → nondocx 三层</b>:读多段在三层上都没有结构变化—— OOXML 仍是 {@code <w:p>} 序列,POI 仍是 {@code
   * XWPFParagraph} 列表,nondocx 仍是 {@code doc.paragraphs()}; 工具层只是把 "取一段"循环 N 次,活对象链与单次版完全一致。
   */
  @ToolDef(
      name = "read_paragraph",
      description =
          "读取正文多个段落的结构摘要(文本、run 数、是否含超链接、段落对齐)。"
              + "paragraph_indexes 是段落索引数组(0 起),长度 1 即单次读,可一次读多段。"
              + "越界索引不中断整批,会在结果里标注。")
  public String readParagraph(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "paragraph_indexes", description = "段落索引数组(0 起),如 [0,1,2];单次传 [0]")
          List<Integer> paragraphIndexes) {
    Document doc = document(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    var paragraphs = doc.paragraphs();
    List<Object> indexes = coerceList(paragraphIndexes);
    if (indexes.isEmpty()) {
      return "段落索引数组为空";
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < indexes.size(); i++) {
      int idx = ((Number) indexes.get(i)).intValue();
      if (i > 0) {
        sb.append('\n');
      }
      if (outOfBounds(idx, paragraphs.size())) {
        sb.append("段落 ")
            .append(idx)
            .append(": ")
            .append(indexError("段落索引", idx, paragraphs.size()));
        continue;
      }
      Paragraph p = paragraphs.get(idx);
      int runCount = p.runs().size();
      long hyperlinkCount = hyperlinkCount(p);
      sb.append("段落 ").append(idx).append('\n');
      sb.append("文本: ").append(p.text()).append('\n');
      sb.append("对齐: ").append(p.alignment()).append('\n');
      sb.append("run 数: ").append(runCount).append('\n');
      sb.append("超链接数: ").append(hyperlinkCount);
    }
    return sb.toString();
  }

  /**
   * 批量修改正文若干段落的水平对齐方式（活对象直写，需 save_docx 落盘）。
   *
   * <p><b>OOXML → POI → nondocx 三层</b>:段落对齐写在段落属性 {@code <w:pPr>} 的 {@code <w:jc>} 上, 例如居中是 {@code
   * <w:jc w:val="center"/>};POI 暴露为 {@code XWPFParagraph#setAlignment}; nondocx 封装为 {@link
   * Paragraph#alignment(Alignment)},并只暴露 {@code LEFT/CENTER/RIGHT/JUSTIFY} 四种常用值。
   *
   * <p><b>批量语义（v3）。</b> 入参是对象数组 {@code edits},每个对象含 {@code paragraph_index} 与 {@code
   * alignment}。alignment 大小写不敏感,支持 {@code LEFT}、{@code CENTER}、{@code RIGHT}、{@code JUSTIFY}。
   */
  @ToolDef(
      name = "update_paragraph_alignment",
      description =
          "批量修改正文若干段落的水平对齐方式(改完需 save_docx 落盘)。edits 是对象数组,每个对象含 "
              + "paragraph_index(int,段落索引 0 起)、alignment(string,LEFT/CENTER/RIGHT/JUSTIFY,大小写不敏感)。"
              + "部分失败不中断,返回每条成功/失败明细。")
  public String updateParagraphAlignment(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(
              name = "edits",
              description =
                  "对象数组,每个对象含 paragraph_index(int)、alignment(string),"
                      + "如 [{\"paragraph_index\":0,\"alignment\":\"CENTER\"}]")
          List<Map<String, Object>> edits) {
    Document doc = document(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    var paragraphs = doc.paragraphs();
    List<Object> list = coerceList(edits);
    if (list.isEmpty()) {
      return "edits 为空";
    }
    StringBuilder sb = new StringBuilder();
    int ok = 0;
    int fail = 0;
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      Object item = list.get(i);
      String tag = "[" + i + "] ";
      if (!(item instanceof Map)) {
        sb.append(tag).append("错误:该条不是对象(").append(item).append(")");
        fail++;
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) item;
      int paragraphIndex;
      Alignment alignment;
      try {
        paragraphIndex = getInt(m, "paragraph_index");
        alignment = parseAlignment(getStr(m, "alignment"));
      } catch (RuntimeException e) {
        sb.append(tag).append("错误:").append(e.getMessage());
        fail++;
        continue;
      }
      if (outOfBounds(paragraphIndex, paragraphs.size())) {
        sb.append(tag).append(indexError("段落索引", paragraphIndex, paragraphs.size()));
        fail++;
        continue;
      }
      try {
        paragraphs.get(paragraphIndex).alignment(alignment);
      } catch (RuntimeException e) {
        sb.append(tag).append("错误:").append(rootMessage(e));
        fail++;
        continue;
      }
      sb.append(tag)
          .append("段落 ")
          .append(paragraphIndex)
          .append(" 对齐 → ")
          .append(alignment)
          .append(" ✓");
      ok++;
    }
    sb.append("\n成功 ").append(ok).append(" 条,失败 ").append(fail).append(" 条");
    return sb.toString();
  }

  /**
   * 批量读取正文若干 run 的文本与样式摘要。
   *
   * <p><b>批量语义（v2）。</b> 入参是<b>对象数组</b> {@code runs},每个对象含 {@code paragraph_index}(int)、{@code
   * run_index}(int)。 数组长度 1 即读单个 run。读类幂等,越界坐标标注不中断。
   */
  @ToolDef(
      name = "read_run",
      description =
          "批量读取正文若干 run 的文本与样式摘要。"
              + "runs 是对象数组,每个对象含 paragraph_index(int,段落索引 0 起)、run_index(int,run 索引 0 起,不含超链接)。"
              + "单个对象用长度 1 的数组。越界坐标不中断,会在结果里标注。")
  public String readRun(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(
              name = "runs",
              description =
                  "对象数组,每个对象含 paragraph_index(int)、run_index(int),"
                      + "如 [{\"paragraph_index\":0,\"run_index\":0}]")
          List<Map<String, Object>> runs) {
    Document doc = document(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    var paragraphs = doc.paragraphs();
    List<Object> list = coerceList(runs);
    if (list.isEmpty()) {
      return "runs 为空";
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      Object item = list.get(i);
      String tag = "[" + i + "] ";
      if (!(item instanceof Map)) {
        sb.append(tag).append("错误:该条不是对象(").append(item).append(")");
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) item;
      int paragraphIndex;
      int runIndex;
      try {
        paragraphIndex = getInt(m, "paragraph_index");
        runIndex = getInt(m, "run_index");
      } catch (RuntimeException e) {
        sb.append(tag).append("错误:").append(e.getMessage());
        continue;
      }
      if (outOfBounds(paragraphIndex, paragraphs.size())) {
        sb.append(tag).append(indexError("段落索引", paragraphIndex, paragraphs.size()));
        continue;
      }
      var paraRuns = paragraphs.get(paragraphIndex).runs();
      if (outOfBounds(runIndex, paraRuns.size())) {
        sb.append(tag).append(indexError("run 索引", runIndex, paraRuns.size()));
        continue;
      }
      Run run = paraRuns.get(runIndex);
      sb.append(tag)
          .append("段落 ")
          .append(paragraphIndex)
          .append(" run ")
          .append(runIndex)
          .append("\n文本: ")
          .append(run.text())
          .append("\n样式: ")
          .append(run.style());
    }
    return sb.toString();
  }

  /**
   * 批量替换正文若干 run 的文本（活对象直写，需 save_docx 落盘）。
   *
   * <p><b>批量语义（v2）。</b> 入参是<b>对象数组</b> {@code edits},每个对象描述一次替换:
   *
   * <ul>
   *   <li>{@code paragraph_index}:整数,必填,段落索引(0 起)
   *   <li>{@code run_index}:整数,必填,run 索引(0 起,不含超链接)
   *   <li>{@code text}:字符串,必填,新文本
   * </ul>
   *
   * <p>数组长度 1 即单次替换;多个即一次改多处(如同时改标题、日期、负责人)。 与"先 search_text 定位再批量 replace_run_text"是天然搭档。
   *
   * <p><b>失败语义:collect-errors。</b> 逐条尝试,某条越界/缺字段不会中断整批—— 成功的真写入(活对象直写),
   * 失败的记中文错误串;末尾汇总成功/失败条数。理由:活对象改动难以整体回滚,逐条收集错误更贴合 Agent 循环 "读回错误自行修正"的现有约定,也不浪费整批调用。
   *
   * <p><b>无需逆序。</b> 文本替换只改 run 的文本内容,不增删 run 列表,故同段多次替换不影响后续条目的 run 索引。
   */
  @ToolDef(
      name = "replace_run_text",
      description =
          "批量替换正文若干 run 的文本(改完需 save_docx 落盘)。edits 是对象数组,每个对象含字段:"
              + "paragraph_index(整数,段落索引 0 起)、run_index(整数,run 索引 0 起,不含超链接)、"
              + "text(字符串,新文本)。单个对象用长度 1 的数组。可一次改多处;部分失败不中断,"
              + "返回每条成功/失败明细。")
  public String replaceRunText(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(
              name = "edits",
              description =
                  "对象数组,每个对象含 paragraph_index(int)、run_index(int)、text(string),"
                      + "如 [{\"paragraph_index\":0,\"run_index\":0,\"text\":\"新文本\"}]")
          List<Map<String, Object>> edits) {
    Document doc = document(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    var paragraphs = doc.paragraphs();
    List<Object> list = coerceList(edits);
    if (list.isEmpty()) {
      return "edits 为空";
    }
    StringBuilder sb = new StringBuilder();
    int ok = 0;
    int fail = 0;
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      Object item = list.get(i);
      String tag = "[" + i + "] ";
      if (!(item instanceof Map)) {
        sb.append(tag).append("错误:该条不是对象(").append(item).append(")");
        fail++;
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) item;
      int paragraphIndex;
      int runIndex;
      String text;
      try {
        paragraphIndex = getInt(m, "paragraph_index");
        runIndex = getInt(m, "run_index");
        text = getStr(m, "text");
      } catch (RuntimeException e) {
        sb.append(tag).append("错误:").append(e.getMessage());
        fail++;
        continue;
      }
      if (outOfBounds(paragraphIndex, paragraphs.size())) {
        sb.append(tag).append(indexError("段落索引", paragraphIndex, paragraphs.size()));
        fail++;
        continue;
      }
      var runs = paragraphs.get(paragraphIndex).runs();
      if (outOfBounds(runIndex, runs.size())) {
        sb.append(tag).append(indexError("run 索引", runIndex, runs.size()));
        fail++;
        continue;
      }
      runs.get(runIndex).text(text);
      sb.append(tag)
          .append("段落 ")
          .append(paragraphIndex)
          .append(" run ")
          .append(runIndex)
          .append(" → \"")
          .append(text)
          .append("\" ✓");
      ok++;
    }
    sb.append("\n成功 ").append(ok).append(" 条,失败 ").append(fail).append(" 条");
    return sb.toString();
  }

  private static Alignment parseAlignment(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("alignment 不能为空");
    }
    try {
      return Alignment.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("alignment 仅支持 LEFT/CENTER/RIGHT/JUSTIFY:" + raw);
    }
  }

  /**
   * 批量修改正文若干 run 的内联样式（活对象直写，需 save_docx 落盘）。
   *
   * <p><b>OOXML → POI → nondocx 三层</b>:run 样式写在 {@code <w:rPr>} 下,例如 {@code <w:b>}、 {@code
   * <w:i>}、{@code <w:u>}、{@code <w:rFonts>}、{@code <w:sz>}、{@code <w:color>}。POI 暴露为 {@code
   * XWPFRun#setBold/setItalic/setUnderline/setFontFamily/setFontSize/setColor}; nondocx 用 {@link
   * Run#bold(boolean)} / {@link Run#italic(boolean)} 等链式方法封装这些写入。
   *
   * <p><b>批量语义（v3）。</b> 入参是对象数组 {@code edits},每个对象含 {@code paragraph_index}、 {@code
   * run_index},以及一个或多个样式字段:{@code bold}、{@code italic}、{@code underline}、 {@code font}、{@code
   * font_size}、{@code color}。布尔字段按"是否存在"判断,因此显式传 {@code false} 可清除对应样式；未传字段不改。
   */
  @ToolDef(
      name = "update_run_style",
      description =
          "批量修改正文若干 run 的内联样式(改完需 save_docx 落盘)。edits 是对象数组,每个对象含 "
              + "paragraph_index(int)、run_index(int),以及可选样式字段:"
              + "bold(bool)、italic(bool)、underline(bool)、font(string)、font_size(int)、color(string,十六进制如 FF0000)。"
              + "布尔字段显式传 false 可清除样式;未传字段不改。部分失败不中断,返回每条成功/失败明细。")
  public String updateRunStyle(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(
              name = "edits",
              description =
                  "对象数组,每个对象含 paragraph_index(int)、run_index(int),"
                      + "以及可选 bold/italic/underline/font/font_size/color,"
                      + "如 [{\"paragraph_index\":0,\"run_index\":0,\"bold\":true,\"color\":\"FF0000\"}]")
          List<Map<String, Object>> edits) {
    Document doc = document(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    var paragraphs = doc.paragraphs();
    List<Object> list = coerceList(edits);
    if (list.isEmpty()) {
      return "edits 为空";
    }
    StringBuilder sb = new StringBuilder();
    int ok = 0;
    int fail = 0;
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      Object item = list.get(i);
      String tag = "[" + i + "] ";
      if (!(item instanceof Map)) {
        sb.append(tag).append("错误:该条不是对象(").append(item).append(")");
        fail++;
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) item;
      int paragraphIndex;
      int runIndex;
      try {
        paragraphIndex = getInt(m, "paragraph_index");
        runIndex = getInt(m, "run_index");
      } catch (RuntimeException e) {
        sb.append(tag).append("错误:").append(e.getMessage());
        fail++;
        continue;
      }
      if (outOfBounds(paragraphIndex, paragraphs.size())) {
        sb.append(tag).append(indexError("段落索引", paragraphIndex, paragraphs.size()));
        fail++;
        continue;
      }
      var runs = paragraphs.get(paragraphIndex).runs();
      if (outOfBounds(runIndex, runs.size())) {
        sb.append(tag).append(indexError("run 索引", runIndex, runs.size()));
        fail++;
        continue;
      }
      Run run = runs.get(runIndex);
      List<String> changed = new ArrayList<>();
      try {
        if (m.containsKey("bold")) {
          boolean value = boolVal(m.get("bold"));
          run.bold(value);
          changed.add("bold=" + value);
        }
        if (m.containsKey("italic")) {
          boolean value = boolVal(m.get("italic"));
          run.italic(value);
          changed.add("italic=" + value);
        }
        if (m.containsKey("underline")) {
          boolean value = boolVal(m.get("underline"));
          run.underline(value);
          changed.add("underline=" + value);
        }
        if (m.containsKey("font")) {
          String value = getStr(m, "font");
          run.font(value);
          changed.add("font=" + value);
        }
        if (m.containsKey("font_size")) {
          int value = getInt(m, "font_size");
          run.fontSize(value);
          changed.add("font_size=" + value);
        }
        if (m.containsKey("color")) {
          String value = getStr(m, "color");
          run.color(value);
          changed.add("color=" + value);
        }
      } catch (RuntimeException e) {
        sb.append(tag).append("错误:").append(rootMessage(e));
        fail++;
        continue;
      }
      if (changed.isEmpty()) {
        sb.append(tag).append("错误:未提供任何样式字段");
        fail++;
        continue;
      }
      sb.append(tag)
          .append("段落 ")
          .append(paragraphIndex)
          .append(" run ")
          .append(runIndex)
          .append(" 样式 → ")
          .append(String.join("、", changed))
          .append(" ✓");
      ok++;
    }
    sb.append("\n成功 ").append(ok).append(" 条,失败 ").append(fail).append(" 条");
    return sb.toString();
  }

  /**
   * 按正文 body 顺序批量插入若干单 run 段落。
   *
   * <p><b>OOXML → POI → nondocx 三层</b>:OOXML 的正文是 {@code <w:body>} 下 {@code <w:p>} 与 {@code
   * <w:tbl>} 的有序序列,所以"文档开头/中间"插入本质是在某个 body 子元素前插入新的 {@code <w:p>}。 POI 通过 {@code
   * XWPFDocument.insertNewParagraph(XmlCursor)} 完成这个位置插入; nondocx 已封装为 {@link
   * Document#insertParagraph(int)},这里复用它而不穿透 raw。
   *
   * <p><b>批量语义（v3）。</b> 入参是对象数组 {@code paragraphs},每个对象含:
   *
   * <ul>
   *   <li>{@code body_index}:整数,必填,正文 body 顺序索引(0 起);{@code bodyElements().size()} 表示末尾
   *   <li>{@code text}:字符串,必填,新段落文本
   * </ul>
   *
   * <p>按数组顺序执行。若多条使用同一个 {@code body_index},第二条会插在第一条之后,从而保持 Agent 传入顺序。 越界/缺字段按 collect-errors
   * 处理,成功项立即写入,失败项不中断整批。
   */
  @ToolDef(
      name = "insert_paragraph",
      description =
          "按正文 body 顺序批量插入若干单 run 段落(改完需 save_docx 落盘)。"
              + "paragraphs 是对象数组,每个对象含 body_index(int,正文 body 顺序索引 0 起;body 元素总数表示末尾)、"
              + "text(string,新段落文本)。body_index=0 可在文档开头插入;中间索引可插在段落或表格前。"
              + "部分失败不中断,返回每条成功/失败明细。")
  public String insertParagraph(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(
              name = "paragraphs",
              description =
                  "对象数组,每个对象含 body_index(int)、text(string),"
                      + "如 [{\"body_index\":0,\"text\":\"标题\"},{\"body_index\":3,\"text\":\"中间段\"}]")
          List<Map<String, Object>> paragraphs) {
    Document doc = document(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    List<Object> list = coerceList(paragraphs);
    if (list.isEmpty()) {
      return "paragraphs 为空";
    }
    StringBuilder sb = new StringBuilder();
    int ok = 0;
    int fail = 0;
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      Object item = list.get(i);
      String tag = "[" + i + "] ";
      if (!(item instanceof Map)) {
        sb.append(tag).append("错误:该条不是对象(").append(item).append(")");
        fail++;
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) item;
      int bodyIndex;
      String text;
      try {
        bodyIndex = getInt(m, "body_index");
        text = getStr(m, "text");
      } catch (RuntimeException e) {
        sb.append(tag).append("错误:").append(e.getMessage());
        fail++;
        continue;
      }
      int bodySize = doc.bodyElements().size();
      if (bodyIndex < 0 || bodyIndex > bodySize) {
        sb.append(tag)
            .append("错误：body_index ")
            .append(bodyIndex)
            .append(" 越界（共 ")
            .append(bodySize)
            .append("）");
        fail++;
        continue;
      }
      try {
        doc.insertParagraph(bodyIndex).addRun(text);
      } catch (RuntimeException e) {
        sb.append(tag).append("错误:").append(rootMessage(e));
        fail++;
        continue;
      }
      sb.append(tag)
          .append("body ")
          .append(bodyIndex)
          .append(" 插入段落 → \"")
          .append(text)
          .append("\" ✓");
      ok++;
    }
    sb.append("\n成功 ").append(ok).append(" 条,失败 ").append(fail).append(" 条");
    return sb.toString();
  }

  // ==================== 超链接（显示文本 + URL 双向改） ====================

  /**
   * 读取正文某段的第 hyperlink_index 个超链接（0 起），返回显示文本与目标 URL。
   *
   * <p>超链接是段落 {@code inlineElements()} 里的一类（而非 {@code runs()}），与 nondocx 模型一致。
   */
  @ToolDef(
      name = "read_hyperlink",
      description = "读取正文第 paragraph_index 段第 hyperlink_index 个超链接（0 起）的显示文本与目标 URL")
  public String readHyperlink(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "paragraph_index", description = "段落索引（0 起）") int paragraphIndex,
      @ToolParam(name = "hyperlink_index", description = "超链接索引（0 起）") int hyperlinkIndex) {
    Hyperlink link = locateHyperlink(docId, paragraphIndex, hyperlinkIndex);
    if (link == null) {
      return locateHyperlinkFailed(docId, paragraphIndex, hyperlinkIndex);
    }
    return "显示文本: " + link.text() + "\n目标 URL: " + link.url();
  }

  /**
   * 修改正文某段某超链接的显示文本和/或目标 URL（活对象直写，需 save_docx 落盘）。
   *
   * <p><b>合并说明（v2）。</b> 旧版有 {@code update_hyperlink_text} 和 {@code update_hyperlink_url}
   * 两个工具,改一个超链接 要调两次。现合并为 {@code update_hyperlink}:{@code text} 与 {@code url} 都<b>可选</b>,至少传一个—— 传
   * {@code text} 改显示文本、传 {@code url} 改目标地址、两个都传则一次改齐。
   *
   * <p>超链接是段落 {@code inlineElements()} 里的一类(而非 {@code runs()}),与 nondocx 模型一致。
   */
  @ToolDef(
      name = "update_hyperlink",
      description =
          "修改正文第 paragraph_index 段第 hyperlink_index 个超链接(均 0 起)的显示文本和/或目标 URL。"
              + "text 与 url 都可选,至少传一个:只传 text 改显示文本、只传 url 改地址、都传则一次改齐。"
              + "改完需 save_docx 落盘。")
  public String updateHyperlink(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "paragraph_index", description = "段落索引（0 起）") int paragraphIndex,
      @ToolParam(name = "hyperlink_index", description = "超链接索引（0 起）") int hyperlinkIndex,
      @ToolParam(name = "text", description = "新的显示文本(可选,不传则不改)", required = false) String text,
      @ToolParam(name = "url", description = "新的目标 URL(可选,不传则不改)", required = false) String url) {
    if ((text == null || text.isEmpty()) && (url == null || url.isEmpty())) {
      return "错误:text 和 url 至少传一个";
    }
    Hyperlink link = locateHyperlink(docId, paragraphIndex, hyperlinkIndex);
    if (link == null) {
      return locateHyperlinkFailed(docId, paragraphIndex, hyperlinkIndex);
    }
    List<String> done = new ArrayList<>();
    if (text != null && !text.isEmpty()) {
      link.text(text);
      done.add("显示文本 → \"" + text + "\"");
    }
    if (url != null && !url.isEmpty()) {
      try {
        link.url(url);
      } catch (RuntimeException e) {
        return "错误：无法修改超链接 URL（" + rootMessage(e) + "）";
      }
      done.add("URL → " + url);
    }
    return "已修改：段落 " + paragraphIndex + " 超链接 " + hyperlinkIndex + " 的 " + String.join("、", done);
  }

  // ==================== 文本搜索（横切所有容器，一次定位） ====================

  /** search_text 的默认命中数上限（max_results 未传时使用），平衡"够用"与返回体长度。 */
  private static final int SEARCH_DEFAULT_MAX = 50;

  /**
   * 在整份文档里搜索 keyword，一次返回所有命中位置的坐标。
   *
   * <p><b>为什么需要这个工具。</b> 现有的 {@code read_paragraph} / {@code read_table_cell} 都是
   * <em>按索引寻址</em>——知道位置才能读。但 Agent 要改某段文字时，往往不知道它在第几段、第几个单元格， 只能 {@code get_document_overview} →
   * 逐个 {@code read_paragraph} 盲读，每步都是一轮 LLM 往返， 定位特别慢。本工具把"线性扫描"从 Agent 循环里搬出来：一次调用遍历正文段落、表格所有单元格、
   * 各 section 的页眉页脚段落，直接吐出所有命中坐标。
   *
   * <p><b>遍历范围（OOXML 三层对应）：</b>
   *
   * <ul>
   *   <li><b>正文段落</b> —— {@code doc.paragraphs()}，对应 {@code word/document.xml} 里 body 直属的 {@code
   *       <w:p>}。
   *   <li><b>表格单元格内段落</b> —— {@code doc.tables().get(t).rows().get(r).cells().get(c).paragraphs()}，
   *       对应 {@code <w:tbl>} → {@code <w:tr>} → {@code <w:tc>} 内的 {@code <w:p>}。表格 cell 内才再有段落，
   *       这就是为什么表格寻址比段落深三层。
   *   <li><b>页眉 / 页脚段落</b> —— {@code doc.sections().get(s).header()/footer()}（只读，null=不存在）， 对应独立
   *       ZIP part（{@code header1.xml} / {@code footer1.xml}），通过 section 的 {@code <w:sectPr>} 引用。
   * </ul>
   *
   * <p><b>命中粒度。</b> 用段落 {@code text()}（POI 拼好的纯文本）做匹配——天然跨 run， 即使"项"+"目进度"分属两个 run
   * 也能命中整词"项目进度"。返回里另附"哪个 run 含命中" （逐 run 找首个 {@code text()} 含关键词的），便于直接喂给 {@code
   * replace_run_text}。
   *
   * <p><b>匹配规则。</b> {@code exact=false}（默认）忽略大小写 + 子串包含；{@code exact=true} 精确相等。
   *
   * <p><b>命中上限。</b> 由 {@code max_results} 控制：{@code >0} 为上限；{@code 0} 或负数表示不限（全部返回）。 默认 {@value
   * #SEARCH_DEFAULT_MAX}。命中数达到上限时会提示"可能还有更多，请缩小关键词"。 某个词在文档里分布极广时，Agent 可主动传更大的 max_results（或 0
   * 不限）拿全量，自行取舍。
   *
   * @param keyword 要找的文本
   * @param exact 是否精确匹配（默认 false=忽略大小写的子串包含）
   * @param maxResults 命中数上限；>0 为上限，0 或负数表示不限（默认 {@value #SEARCH_DEFAULT_MAX}）
   * @return 所有命中坐标的多行纯文本；无命中时返回提示串
   */
  @ToolDef(
      name = "search_text",
      description =
          "在整份文档（正文段落 + 表格单元格 + 页眉 + 页脚）里搜索 keyword，"
              + "一次返回所有命中位置坐标（段落级匹配，标注含命中的 run）。"
              + "max_results 控制上限：>0 为上限，0 或负数=不限（默认 50）。"
              + "按文本改内容前优先用它定位，不要逐段 read 盲读。")
  public String searchText(
      @ToolParam(name = "doc_id", description = "文档句柄") String docId,
      @ToolParam(name = "keyword", description = "要查找的文本") String keyword,
      @ToolParam(name = "exact", description = "true=精确相等；false（默认）=忽略大小写的子串包含") boolean exact,
      @ToolParam(name = "max_results", description = "命中数上限：>0 为上限，0 或负数=不限（默认 50）。命中很多时可调大或传 0")
          Integer maxResults) {
    Document doc = document(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    // 用 Integer 而非 int：LLM 不传该参数时 nonchain 会注入 null，
    // 包装类型能安全接住 null，这里再归一化为默认值（避免基本类型收到 null 触发 NPE）。
    // 归一化：null 或 <=0 视为不限（用极大值，循环自然由真实命中数收尾）；>0 原样用。
    int limit = (maxResults == null || maxResults <= 0) ? Integer.MAX_VALUE : maxResults;
    List<String> hits = new ArrayList<>();

    // 1) 正文段落
    var paragraphs = doc.paragraphs();
    for (int i = 0; i < paragraphs.size() && hits.size() < limit; i++) {
      Paragraph p = paragraphs.get(i);
      String hit = matchBodyParagraph(i, p, keyword, exact);
      if (hit != null) {
        hits.add(hit);
      }
    }

    // 2) 表格单元格内段落
    var tables = doc.tables();
    for (int t = 0; t < tables.size() && hits.size() < limit; t++) {
      var rows = tables.get(t).rows();
      for (int r = 0; r < rows.size() && hits.size() < limit; r++) {
        var cells = rows.get(r).cells();
        for (int c = 0; c < cells.size() && hits.size() < limit; c++) {
          var paras = cells.get(c).paragraphs();
          for (int pi = 0; pi < paras.size() && hits.size() < limit; pi++) {
            String hit = matchCellParagraph(t, r, c, pi, paras.get(pi), keyword, exact);
            if (hit != null) {
              hits.add(hit);
            }
          }
        }
      }
    }

    // 3) 页眉 / 页脚。读写分离后 Section.header()/footer() 本身就是只读的（null=不存在），
    //    所以这里直接遍历、null 跳过即可，不会再像旧 API 那样"读一遍凭空创建空页眉"。
    var sections = doc.sections();
    for (int s = 0; s < sections.size() && hits.size() < limit; s++) {
      searchHeaderFooter(sections.get(s).header(), "页眉", s, keyword, exact, hits, limit);
      searchHeaderFooter(sections.get(s).footer(), "页脚", s, keyword, exact, hits, limit);
    }

    // 大文档可能命中数远超上限。上面三段循环都以 hits.size() < limit 为条件提前退出，
    // 所以一旦 hits.size() 达到 limit，就说明至少还没扫完，提示 Agent 缩小关键词。
    // （limit == Integer.MAX_VALUE 即"不限"时，hits.size() 不可能 >= 它，自然不会误报。）
    boolean possiblyMore = hits.size() >= limit;

    if (hits.isEmpty()) {
      return "未找到「" + keyword + "」";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("找到 ").append(hits.size()).append(" 处「").append(keyword).append("」：\n");
    for (int i = 0; i < hits.size(); i++) {
      sb.append('[').append(i + 1).append("] ").append(hits.get(i));
      if (i < hits.size() - 1) {
        sb.append('\n');
      }
    }
    if (possiblyMore) {
      sb.append("\n（已达 ").append(limit).append(" 处上限，可能还有更多；请用更长的关键词缩小范围，或调大 max_results）");
    }
    return sb.toString();
  }

  // ==================== 搜索 / 超链接 组内辅助 ====================

  /** 正文段落命中 → "正文段落 N · run R 含命中\n文本: ..."；未命中返回 null。 */
  private static String matchBodyParagraph(
      int paragraphIndex, Paragraph p, String keyword, boolean exact) {
    if (!matches(p.text(), keyword, exact)) {
      return null;
    }
    int runIdx = firstRunContaining(p, keyword, exact);
    return "正文段落 " + paragraphIndex + " · run " + runIdx + " 含命中\n文本: " + p.text();
  }

  /** 表格单元格段落命中 → "表格(t,r,c) 段落 P · run R 含命中\n文本: ..."；未命中返回 null。 */
  private static String matchCellParagraph(
      int t, int r, int c, int paragraphIndex, Paragraph p, String keyword, boolean exact) {
    if (!matches(p.text(), keyword, exact)) {
      return null;
    }
    int runIdx = firstRunContaining(p, keyword, exact);
    return "表格("
        + t
        + ","
        + r
        + ","
        + c
        + ") 段落 "
        + paragraphIndex
        + " · run "
        + runIdx
        + " 含命中\n文本: "
        + p.text();
  }

  /** 页眉/页脚段落命中 → "[页眉|页脚] section=S 段落 P · run R 含命中\n文本: ..."；未命中返回 null。 */
  private static String matchHeaderFooterParagraph(
      String kind,
      int sectionIndex,
      int paragraphIndex,
      Paragraph p,
      String keyword,
      boolean exact) {
    if (!matches(p.text(), keyword, exact)) {
      return null;
    }
    int runIdx = firstRunContaining(p, keyword, exact);
    return kind
        + " section="
        + sectionIndex
        + " 段落 "
        + paragraphIndex
        + " · run "
        + runIdx
        + " 含命中\n文本: "
        + p.text();
  }

  /**
   * 遍历一个页眉/页脚的段落做搜索匹配，命中追加进 hits。{@code headerOrFooter} 为 null（该 section 无此 part）时直接返回。
   *
   * <p>读写分离后 {@code Section.header()}/{@code footer()} 不存在时返回 null 而非创建空 part， 所以这里只需 null
   * 跳过即可安全只读遍历，无需像旧版那样自建 POI 解析。
   */
  private static void searchHeaderFooter(
      Object headerOrFooter,
      String kind,
      int sectionIndex,
      String keyword,
      boolean exact,
      List<String> hits,
      int limit) {
    if (headerOrFooter == null) {
      return;
    }
    List<Paragraph> paras =
        headerOrFooter instanceof Header
            ? ((Header) headerOrFooter).paragraphs()
            : ((Footer) headerOrFooter).paragraphs();
    for (int pi = 0; pi < paras.size() && hits.size() < limit; pi++) {
      String hit =
          matchHeaderFooterParagraph(kind, sectionIndex, pi, paras.get(pi), keyword, exact);
      if (hit != null) {
        hits.add(hit);
      }
    }
  }

  /** 段落文本是否命中关键词。 */
  private static boolean matches(String text, String keyword, boolean exact) {
    if (text == null || keyword == null) {
      return false;
    }
    if (exact) {
      return text.equals(keyword);
    }
    return text.toLowerCase(java.util.Locale.ROOT)
        .contains(keyword.toLowerCase(java.util.Locale.ROOT));
  }

  /**
   * 段落内首个 text() 含关键词的 run 索引；找不到（例如关键词横跨多个 run）返回 -1。
   *
   * <p>这里按 {@code runs()}（普通 run）计数，与 {@code replace_run_text} 的 run_index 语义一致。 超链接里的文本不计入 run
   * 索引，如需改超链接用 {@code update_hyperlink}。
   */
  private static int firstRunContaining(Paragraph p, String keyword, boolean exact) {
    var runs = p.runs();
    for (int i = 0; i < runs.size(); i++) {
      if (matches(runs.get(i).text(), keyword, exact)) {
        return i;
      }
    }
    return -1;
  }

  /** 定位段落内第 hyperlinkIndex 个超链接；docId/段落/超链接任一无效返回 {@code null}。 调用方据此决定返回哪个中文错误串。 */
  private Hyperlink locateHyperlink(String docId, int paragraphIndex, int hyperlinkIndex) {
    Document doc = document(docId);
    if (doc == null) {
      return null;
    }
    var paragraphs = doc.paragraphs();
    if (outOfBounds(paragraphIndex, paragraphs.size())) {
      return null;
    }
    int seen = 0;
    for (InlineElement e : paragraphs.get(paragraphIndex).inlineElements()) {
      if (e instanceof Hyperlink) {
        if (seen == hyperlinkIndex) {
          return (Hyperlink) e;
        }
        seen++;
      }
    }
    return null;
  }

  /** 配合 {@link #locateHyperlink}：返回定位失败时的中文错误串（需重新解析边界以给准确数字）。 */
  private String locateHyperlinkFailed(String docId, int paragraphIndex, int hyperlinkIndex) {
    Document doc = document(docId);
    if (doc == null) {
      return docNotFound(docId);
    }
    var paragraphs = doc.paragraphs();
    if (outOfBounds(paragraphIndex, paragraphs.size())) {
      return indexError("段落索引", paragraphIndex, paragraphs.size());
    }
    long count = hyperlinkCount(paragraphs.get(paragraphIndex));
    return "错误：超链接索引 " + hyperlinkIndex + " 越界（该段含 " + count + " 个超链接）";
  }
}
