package io.github.nondirectional.docx.toolkit;

import com.non.chain.tool.ToolDef;
import com.non.chain.tool.ToolParam;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.InlineElement;
import io.github.nondirectional.docx.core.api.header.Footer;
import io.github.nondirectional.docx.core.api.header.Header;
import io.github.nondirectional.docx.core.api.style.Alignment;
import io.github.nondirectional.docx.core.api.style.HeadingLevel;
import io.github.nondirectional.docx.core.api.text.Hyperlink;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import io.github.nondirectional.docx.core.api.text.Run;
import io.github.nondirectional.docx.toolkit.capability.CapabilityOperation;
import io.github.nondirectional.docx.toolkit.capability.NestedParamCapability;
import io.github.nondirectional.docx.toolkit.capability.ParamCapability;
import io.github.nondirectional.docx.toolkit.capability.ParamType;
import io.github.nondirectional.docx.toolkit.capability.ToolCapability;
import io.github.nondirectional.docx.toolkit.ref.ElementRef;
import io.github.nondirectional.docx.toolkit.ref.ElementRefs;
import io.github.nondirectional.docx.toolkit.ref.ElementResolver;
import io.github.nondirectional.docx.toolkit.ref.ParagraphRef;
import io.github.nondirectional.docx.toolkit.ref.RefResolutionException;
import io.github.nondirectional.docx.toolkit.ref.ReferenceContext;
import io.github.nondirectional.docx.toolkit.ref.RunRef;
import io.github.nondirectional.docx.toolkit.result.ToolResult;
import io.github.nondirectional.docx.toolkit.result.ToolResultCode;
import io.github.nondirectional.docx.toolkit.result.ToolResultRenderer;
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

  BodyTools(
      Map<String, Document> sharedSessions,
      AtomicInteger sharedSeq,
      ReferenceContext sharedReferences,
      Map<String, Long> sharedGenerations) {
    super(sharedSessions, sharedSeq, sharedReferences, sharedGenerations);
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
              + "paragraph_indexes 可混合传段落索引(0 起)或 canonical ParagraphRef 字符串,"
              + "长度 1 即单次读,可一次读多段。"
              + "越界索引不中断整批,会在结果里标注。")
  @ToolCapability(operation = CapabilityOperation.READ, element = "paragraph")
  public String readParagraph(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "paragraph_indexes", description = "段落索引数组(0 起),如 [0,1,2];单次传 [0]")
          @ParamCapability(type = ParamType.OBJECT_ARRAY)
          List<Integer> paragraphIndexes) {
    Document doc = document(docId);
    if (doc == null) {
      return renderDocNotFound(docId);
    }
    var paragraphs = doc.paragraphs();
    List<Object> indexes = coerceList(paragraphIndexes);
    if (indexes.isEmpty()) {
      ToolResult<Void> result =
          ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "段落索引数组为空", "至少传一个段落索引");
      return ToolResultRenderer.render(result);
    }
    StringBuilder sb = new StringBuilder();
    ElementResolver resolver = elementResolver(docId);
    for (int i = 0; i < indexes.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      Object target = indexes.get(i);
      Paragraph p;
      int idx;
      try {
        if (target instanceof Number) {
          idx = ((Number) target).intValue();
          if (outOfBounds(idx, paragraphs.size())) {
            sb.append("段落 ")
                .append(idx)
                .append(": ")
                .append(indexError("段落索引", idx, paragraphs.size()));
            continue;
          }
          p = paragraphs.get(idx);
        } else if (target instanceof String) {
          ElementRef parsed = ElementRefs.parse((String) target);
          if (!(parsed instanceof ParagraphRef)) {
            sb.append("错误[ref_type_mismatch]：read_paragraph 只接受 ParagraphRef");
            continue;
          }
          p = resolver.resolve((ParagraphRef) parsed);
          idx = paragraphIndex(paragraphs, p);
        } else {
          sb.append("错误[invalid_ref]：段落目标必须是索引或 ParagraphRef");
          continue;
        }
      } catch (RefResolutionException e) {
        sb.append(e.render());
        continue;
      }
      ParagraphRef ref = resolver.reference(p);
      int runCount = p.runs().size();
      long hyperlinkCount = hyperlinkCount(p);
      sb.append("段落 ").append(idx).append('\n');
      sb.append("ref: ").append(ref.canonical()).append('\n');
      sb.append("文本: ").append(p.text()).append('\n');
      sb.append("对齐: ").append(p.alignment()).append('\n');
      sb.append("run 数: ").append(runCount).append('\n');
      sb.append("超链接数: ").append(hyperlinkCount);
    }
    ToolResult<String> result = ToolResult.ok(sb.toString(), sb.toString());
    return ToolResultRenderer.render(result);
  }

  private static int paragraphIndex(List<Paragraph> paragraphs, Paragraph target) {
    for (int i = 0; i < paragraphs.size(); i++) {
      if (paragraphs.get(i).raw() == target.raw()) {
        return i;
      }
    }
    return -1;
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
              + "部分失败不中断,返回每条成功/失败明细。"
              + "可选 expected_generation 校验文档代次防止旧快照改新状态;"
              + "可选 on_error(continue=失败不中断默认,stop=遇首条失败即停)。")
  @ToolCapability(operation = CapabilityOperation.UPDATE, element = "paragraph")
  public String updateParagraphAlignment(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(
              name = "edits",
              description =
                  "对象数组,每个对象含 paragraph_index(int)、alignment(string),"
                      + "如 [{\"paragraph_index\":0,\"alignment\":\"CENTER\"}]")
          @NestedParamCapability(path = "edits.paragraph_index", type = ParamType.INTEGER)
          @NestedParamCapability(
              path = "edits.alignment",
              type = ParamType.ENUM,
              enumValues = {"LEFT", "CENTER", "RIGHT", "JUSTIFY"})
          List<Map<String, Object>> edits,
      @ToolParam(
              name = "on_error",
              description = "continue=失败不中断(默认),stop=遇首条失败即停",
              required = false)
          @ParamCapability(
              type = ParamType.ENUM,
              enumValues = {"continue", "stop"})
          String onError,
      @ToolParam(
              name = "expected_generation",
              description = "可选。调用方持有的 session generation,与当前不符则拒绝写入(防止旧快照修改新状态)。不传则跳过校验。",
              required = false)
          @ParamCapability(type = ParamType.INTEGER)
          Integer expectedGeneration) {
    Document doc = document(docId);
    if (doc == null) {
      return renderDocNotFound(docId);
    }
    if (!checkExpectedGeneration(docId, expectedGeneration)) {
      long current = generations.getOrDefault(docId, 1L);
      return renderGenerationMismatch(expectedGeneration, current);
    }
    boolean stopOnError = "stop".equalsIgnoreCase(onError);
    var paragraphs = doc.paragraphs();
    List<Object> list = coerceList(edits);
    if (list.isEmpty()) {
      ToolResult<Void> result =
          ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "edits 为空", "至少传一条修改");
      return ToolResultRenderer.render(result);
    }
    StringBuilder sb = new StringBuilder();
    int ok = 0;
    int fail = 0;
    int skipped = 0;
    List<String> changedRefs = new ArrayList<>();
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      Object item = list.get(i);
      String tag = "[" + i + "] ";
      if (!(item instanceof Map)) {
        sb.append(tag).append("错误:该条不是对象(").append(item).append(")");
        fail++;
        if (stopOnError) {
          skipped = list.size() - i - 1;
          break;
        }
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) item;
      ParagraphTarget target;
      Alignment alignment;
      try {
        target = resolveParagraphTarget(docId, paragraphs, m);
        alignment = parseAlignment(getStr(m, "alignment"));
      } catch (RuntimeException e) {
        sb.append(tag).append(renderError(e));
        fail++;
        if (stopOnError) {
          skipped = list.size() - i - 1;
          break;
        }
        continue;
      }
      try {
        target.paragraph.alignment(alignment);
      } catch (RuntimeException e) {
        sb.append(tag).append("错误:").append(rootMessage(e));
        fail++;
        if (stopOnError) {
          skipped = list.size() - i - 1;
          break;
        }
        continue;
      }
      sb.append(tag)
          .append("段落 ")
          .append(target.paragraphIndex)
          .append(" 对齐 → ")
          .append(alignment)
          .append(" ref=")
          .append(target.ref.canonical())
          .append(" ✓");
      changedRefs.add(target.ref.canonical());
      ok++;
    }
    sb.append("\n成功 ").append(ok).append(" 条,失败 ").append(fail).append(" 条");
    int matchedCount = ok + fail;
    ToolResult<List<String>> result =
        fail > 0
            ? ToolResult.partial(
                ToolResultCode.PARTIAL_FAILURE,
                changedRefs,
                sb.toString(),
                null,
                matchedCount,
                ok,
                stopOnError ? skipped : null)
            : ToolResult.ok(changedRefs, sb.toString(), matchedCount, ok, changedRefs);
    return ToolResultRenderer.render(result);
  }

  /**
   * 批量读取正文若干 run 的文本与样式摘要。
   *
   * <p><b>批量语义。</b> 入参是对象数组 {@code runs}。每个对象优先传 canonical {@code RunRef} 字段 {@code ref}；旧调用方仍可传
   * {@code paragraph_index}+{@code run_index}。两种寻址同时出现时必须指向同一 run。数组长度 1 即读单个 run；读类幂等，单条失败不影响其它条目。
   */
  @ToolDef(
      name = "read_run",
      description =
          "批量读取正文若干 run 的文本与样式摘要。"
              + "runs 是对象数组,每个对象可传 canonical RunRef 字段 ref,或传"
              + " paragraph_index(int,段落索引 0 起)+run_index(int,run 索引 0 起,不含超链接)。"
              + "ref 与索引同时提供时必须指向同一 run。"
              + "单个对象用长度 1 的数组。越界坐标不中断,会在结果里标注。")
  @ToolCapability(operation = CapabilityOperation.READ, element = "run")
  public String readRun(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(
              name = "runs",
              description =
                  "对象数组,每个对象含 ref(string),或 paragraph_index(int)+run_index(int),"
                      + "如 [{\"ref\":\"doc:.../run:session:r-1\"}]")
          @NestedParamCapability(path = "runs.ref", type = ParamType.REF)
          @NestedParamCapability(path = "runs.paragraph_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "runs.run_index", type = ParamType.INTEGER)
          List<Map<String, Object>> runs) {
    Document doc = document(docId);
    if (doc == null) {
      return renderDocNotFound(docId);
    }
    var paragraphs = doc.paragraphs();
    List<Object> list = coerceList(runs);
    if (list.isEmpty()) {
      ToolResult<Void> result =
          ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "runs 为空", "至少传一个 run 坐标");
      return ToolResultRenderer.render(result);
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
      RunTarget target;
      try {
        target = resolveRunTarget(docId, paragraphs, m);
      } catch (RuntimeException e) {
        sb.append(tag).append(renderError(e));
        continue;
      }
      sb.append(tag)
          .append("段落 ")
          .append(target.paragraphIndex)
          .append(" run ")
          .append(target.runIndex)
          .append(" ref=")
          .append(target.ref.canonical())
          .append("\n文本: ")
          .append(target.run.text())
          .append("\n样式: ")
          .append(target.run.style());
    }
    ToolResult<String> result = ToolResult.ok(sb.toString(), sb.toString());
    return ToolResultRenderer.render(result);
  }

  /**
   * 批量替换正文若干 run 的文本（活对象直写，需 save_docx 落盘）。
   *
   * <p><b>批量语义（v2）。</b> 入参是<b>对象数组</b> {@code edits},每个对象描述一次替换:
   *
   * <ul>
   *   <li>{@code ref}:canonical {@code RunRef}，推荐
   *   <li>{@code paragraph_index}+{@code run_index}:旧索引兼容入口
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
              + "返回每条成功/失败明细。"
              + "可选 expected_generation 校验文档代次防止旧快照改新状态;"
              + "可选 on_error(continue=失败不中断默认,stop=遇首条失败即停)。")
  @ToolCapability(operation = CapabilityOperation.UPDATE, element = "run")
  public String replaceRunText(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(
              name = "edits",
              description =
                  "对象数组,每个对象含 paragraph_index(int)、run_index(int)、text(string),"
                      + "如 [{\"paragraph_index\":0,\"run_index\":0,\"text\":\"新文本\"}]")
          @NestedParamCapability(path = "edits.paragraph_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.run_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.text", type = ParamType.STRING)
          List<Map<String, Object>> edits,
      @ToolParam(
              name = "on_error",
              description = "continue=失败不中断(默认),stop=遇首条失败即停",
              required = false)
          @ParamCapability(
              type = ParamType.ENUM,
              enumValues = {"continue", "stop"})
          String onError,
      @ToolParam(
              name = "expected_generation",
              description = "可选。调用方持有的 session generation,与当前不符则拒绝写入(防止旧快照修改新状态)。不传则跳过校验。",
              required = false)
          @ParamCapability(type = ParamType.INTEGER)
          Integer expectedGeneration) {
    Document doc = document(docId);
    if (doc == null) {
      return renderDocNotFound(docId);
    }
    if (!checkExpectedGeneration(docId, expectedGeneration)) {
      long current = generations.getOrDefault(docId, 1L);
      return renderGenerationMismatch(expectedGeneration, current);
    }
    boolean stopOnError = "stop".equalsIgnoreCase(onError);
    var paragraphs = doc.paragraphs();
    List<Object> list = coerceList(edits);
    if (list.isEmpty()) {
      ToolResult<Void> result =
          ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "edits 为空", "至少传一条修改");
      return ToolResultRenderer.render(result);
    }
    StringBuilder sb = new StringBuilder();
    int ok = 0;
    int fail = 0;
    int skipped = 0;
    List<String> changedRefs = new ArrayList<>();
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      Object item = list.get(i);
      String tag = "[" + i + "] ";
      if (!(item instanceof Map)) {
        sb.append(tag).append("错误:该条不是对象(").append(item).append(")");
        fail++;
        if (stopOnError) {
          skipped = list.size() - i - 1;
          break;
        }
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) item;
      RunTarget target;
      String text;
      try {
        target = resolveRunTarget(docId, paragraphs, m);
        text = getStr(m, "text");
      } catch (RuntimeException e) {
        sb.append(tag).append(renderError(e));
        fail++;
        if (stopOnError) {
          skipped = list.size() - i - 1;
          break;
        }
        continue;
      }
      target.run.text(text);
      sb.append(tag)
          .append("段落 ")
          .append(target.paragraphIndex)
          .append(" run ")
          .append(target.runIndex)
          .append(" → \"")
          .append(text)
          .append("\" ref=")
          .append(target.ref.canonical())
          .append(" ✓");
      changedRefs.add(target.ref.canonical());
      ok++;
    }
    sb.append("\n成功 ").append(ok).append(" 条,失败 ").append(fail).append(" 条");
    int matchedCount = ok + fail;
    ToolResult<List<String>> result =
        fail > 0
            ? ToolResult.partial(
                ToolResultCode.PARTIAL_FAILURE,
                changedRefs,
                sb.toString(),
                null,
                matchedCount,
                ok,
                stopOnError ? skipped : null)
            : ToolResult.ok(changedRefs, sb.toString(), matchedCount, ok, changedRefs);
    return ToolResultRenderer.render(result);
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

  private static HeadingLevel parseHeadingLevel(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("heading_level 不能为空");
    }
    try {
      return HeadingLevel.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("heading_level 仅支持 H1/H2/H3/H4/H5/H6:" + raw);
    }
  }

  private ParagraphTarget resolveParagraphTarget(
      String docId, List<Paragraph> paragraphs, Map<String, Object> payload) {
    ElementResolver resolver = elementResolver(docId);
    if (payload.containsKey("ref")) {
      ElementRef parsed = ElementRefs.parse(getStr(payload, "ref"));
      if (!(parsed instanceof ParagraphRef)) {
        throw new RefResolutionException(
            io.github.nondirectional.docx.toolkit.ref.RefResolutionCode.REF_TYPE_MISMATCH,
            "该操作只接受 ParagraphRef");
      }
      Paragraph paragraph = resolver.resolve((ParagraphRef) parsed);
      int currentIndex = paragraphIndex(paragraphs, paragraph);
      if (currentIndex < 0) {
        throw new RefResolutionException(
            io.github.nondirectional.docx.toolkit.ref.RefResolutionCode.DOCUMENT_MISMATCH,
            "ParagraphRef 不属于正文段落");
      }
      if (payload.containsKey("paragraph_index")
          && getInt(payload, "paragraph_index") != currentIndex) {
        throw new RefResolutionException(
            io.github.nondirectional.docx.toolkit.ref.RefResolutionCode.STALE_REF,
            "ref 与 paragraph_index 指向不同段落");
      }
      return new ParagraphTarget(paragraph, currentIndex, resolver.reference(paragraph));
    }
    int index = getInt(payload, "paragraph_index");
    if (outOfBounds(index, paragraphs.size())) {
      throw new IllegalArgumentException(indexError("段落索引", index, paragraphs.size()));
    }
    Paragraph paragraph = paragraphs.get(index);
    return new ParagraphTarget(paragraph, index, resolver.reference(paragraph));
  }

  private RunTarget resolveRunTarget(
      String docId, List<Paragraph> paragraphs, Map<String, Object> payload) {
    ElementResolver resolver = elementResolver(docId);
    if (payload.containsKey("ref")) {
      ElementRef parsed = ElementRefs.parse(getStr(payload, "ref"));
      if (!(parsed instanceof RunRef)) {
        throw new RefResolutionException(
            io.github.nondirectional.docx.toolkit.ref.RefResolutionCode.REF_TYPE_MISMATCH,
            "该操作只接受 RunRef");
      }
      Run run = resolver.resolve((RunRef) parsed);
      for (int p = 0; p < paragraphs.size(); p++) {
        List<Run> runs = paragraphs.get(p).runs();
        for (int r = 0; r < runs.size(); r++) {
          if (runs.get(r).raw() == run.raw()) {
            if (payload.containsKey("paragraph_index") && getInt(payload, "paragraph_index") != p) {
              throw new RefResolutionException(
                  io.github.nondirectional.docx.toolkit.ref.RefResolutionCode.STALE_REF,
                  "ref 与 paragraph_index 指向不同段落");
            }
            if (payload.containsKey("run_index") && getInt(payload, "run_index") != r) {
              throw new RefResolutionException(
                  io.github.nondirectional.docx.toolkit.ref.RefResolutionCode.STALE_REF,
                  "ref 与 run_index 指向不同 run");
            }
            return new RunTarget(run, p, r, resolver.reference(run));
          }
        }
      }
      throw new RefResolutionException(
          io.github.nondirectional.docx.toolkit.ref.RefResolutionCode.DOCUMENT_MISMATCH,
          "RunRef 不属于正文 run");
    }
    int paragraphIndex = getInt(payload, "paragraph_index");
    if (outOfBounds(paragraphIndex, paragraphs.size())) {
      throw new IllegalArgumentException(indexError("段落索引", paragraphIndex, paragraphs.size()));
    }
    List<Run> runs = paragraphs.get(paragraphIndex).runs();
    int runIndex = getInt(payload, "run_index");
    if (outOfBounds(runIndex, runs.size())) {
      throw new IllegalArgumentException(indexError("run 索引", runIndex, runs.size()));
    }
    Run run = runs.get(runIndex);
    return new RunTarget(run, paragraphIndex, runIndex, resolver.reference(run));
  }

  private static String renderError(RuntimeException e) {
    if (e instanceof RefResolutionException) {
      return ((RefResolutionException) e).render();
    }
    return "错误:" + e.getMessage();
  }

  private static final class ParagraphTarget {
    private final Paragraph paragraph;
    private final int paragraphIndex;
    private final ParagraphRef ref;

    private ParagraphTarget(Paragraph paragraph, int paragraphIndex, ParagraphRef ref) {
      this.paragraph = paragraph;
      this.paragraphIndex = paragraphIndex;
      this.ref = ref;
    }
  }

  private static final class RunTarget {
    private final Run run;
    private final int paragraphIndex;
    private final int runIndex;
    private final RunRef ref;

    private RunTarget(Run run, int paragraphIndex, int runIndex, RunRef ref) {
      this.run = run;
      this.paragraphIndex = paragraphIndex;
      this.runIndex = runIndex;
      this.ref = ref;
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
   * <p><b>批量语义。</b> 入参是对象数组 {@code edits}。每个对象优先传 canonical {@code RunRef} 字段 {@code ref}；旧调用方仍可传
   * {@code paragraph_index}+{@code run_index}。另外提供一个或多个样式字段：{@code bold}、{@code italic}、{@code
   * underline}、{@code font}、{@code font_size}、{@code color}。布尔字段按"是否存在"判断，因此显式传 {@code false}
   * 可清除对应样式；未传字段不改。
   */
  @ToolDef(
      name = "update_run_style",
      description =
          "批量修改正文若干 run 的内联样式(改完需 save_docx 落盘)。edits 是对象数组,每个对象含 "
              + "canonical RunRef 字段 ref,或 paragraph_index(int)+run_index(int);"
              + "ref 与索引同时提供时必须指向同一 run。另含可选样式字段:"
              + "bold(bool)、italic(bool)、underline(bool)、font(string)、font_size(int)、color(string,十六进制如 FF0000)。"
              + "布尔字段显式传 false 可清除样式;未传字段不改。部分失败不中断,返回每条成功/失败明细。"
              + "可选 expected_generation 校验文档代次防止旧快照改新状态;"
              + "可选 on_error(continue=失败不中断默认,stop=遇首条失败即停)。")
  @ToolCapability(operation = CapabilityOperation.UPDATE, element = "run")
  public String updateRunStyle(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(
              name = "edits",
              description =
                  "对象数组,每个对象含 ref(string),或 paragraph_index(int)+run_index(int),"
                      + "以及可选 bold/italic/underline/font/font_size/color,"
                      + "如 [{\"ref\":\"doc:.../run:session:r-1\",\"bold\":true,\"color\":\"FF0000\"}]")
          @NestedParamCapability(path = "edits.ref", type = ParamType.REF)
          @NestedParamCapability(path = "edits.paragraph_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.run_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.bold", type = ParamType.BOOLEAN)
          @NestedParamCapability(path = "edits.italic", type = ParamType.BOOLEAN)
          @NestedParamCapability(path = "edits.underline", type = ParamType.BOOLEAN)
          @NestedParamCapability(path = "edits.font", type = ParamType.STRING)
          @NestedParamCapability(path = "edits.font_size", type = ParamType.INTEGER, unit = "pt")
          @NestedParamCapability(path = "edits.color", type = ParamType.STRING)
          List<Map<String, Object>> edits,
      @ToolParam(
              name = "on_error",
              description = "continue=失败不中断(默认),stop=遇首条失败即停",
              required = false)
          @ParamCapability(
              type = ParamType.ENUM,
              enumValues = {"continue", "stop"})
          String onError,
      @ToolParam(
              name = "expected_generation",
              description = "可选。调用方持有的 session generation,与当前不符则拒绝写入(防止旧快照修改新状态)。不传则跳过校验。",
              required = false)
          @ParamCapability(type = ParamType.INTEGER)
          Integer expectedGeneration) {
    Document doc = document(docId);
    if (doc == null) {
      return renderDocNotFound(docId);
    }
    if (!checkExpectedGeneration(docId, expectedGeneration)) {
      long current = generations.getOrDefault(docId, 1L);
      return renderGenerationMismatch(expectedGeneration, current);
    }
    boolean stopOnError = "stop".equalsIgnoreCase(onError);
    var paragraphs = doc.paragraphs();
    List<Object> list = coerceList(edits);
    if (list.isEmpty()) {
      ToolResult<Void> result =
          ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "edits 为空", "至少传一条修改");
      return ToolResultRenderer.render(result);
    }
    StringBuilder sb = new StringBuilder();
    int ok = 0;
    int fail = 0;
    int skipped = 0;
    List<String> changedRefs = new ArrayList<>();
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      Object item = list.get(i);
      String tag = "[" + i + "] ";
      if (!(item instanceof Map)) {
        sb.append(tag).append("错误:该条不是对象(").append(item).append(")");
        fail++;
        if (stopOnError) {
          skipped = list.size() - i - 1;
          break;
        }
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) item;
      RunTarget target;
      try {
        target = resolveRunTarget(docId, paragraphs, m);
      } catch (RuntimeException e) {
        sb.append(tag).append(renderError(e));
        fail++;
        if (stopOnError) {
          skipped = list.size() - i - 1;
          break;
        }
        continue;
      }
      Run run = target.run;
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
        if (stopOnError) {
          skipped = list.size() - i - 1;
          break;
        }
        continue;
      }
      if (changed.isEmpty()) {
        // R1 空更新:用 NO_CHANGES_APPLIED 语义,但单条失败仍计入 fail。
        sb.append(tag).append(noChangesAppliedResult("未提供任何样式字段").message());
        fail++;
        if (stopOnError) {
          skipped = list.size() - i - 1;
          break;
        }
        continue;
      }
      sb.append(tag)
          .append("段落 ")
          .append(target.paragraphIndex)
          .append(" run ")
          .append(target.runIndex)
          .append(" 样式 → ")
          .append(String.join("、", changed))
          .append(" ref=")
          .append(target.ref.canonical())
          .append(" ✓");
      changedRefs.add(target.ref.canonical());
      ok++;
    }
    sb.append("\n成功 ").append(ok).append(" 条,失败 ").append(fail).append(" 条");
    int matchedCount = ok + fail;
    ToolResult<List<String>> result =
        fail > 0
            ? ToolResult.partial(
                ToolResultCode.PARTIAL_FAILURE,
                changedRefs,
                sb.toString(),
                null,
                matchedCount,
                ok,
                stopOnError ? skipped : null)
            : ToolResult.ok(changedRefs, sb.toString(), matchedCount, ok, changedRefs);
    return ToolResultRenderer.render(result);
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
   *   <li>{@code heading_level}:可选，H1 到 H6
   *   <li>{@code alignment}:可选，LEFT/CENTER/RIGHT/JUSTIFY
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
              + "text(string,新段落文本),可选 heading_level(H1-H6) 和 alignment(LEFT/CENTER/RIGHT/JUSTIFY)。"
              + "body_index=0 可在文档开头插入;中间索引可插在段落或表格前。"
              + "部分失败不中断,返回每条成功/失败明细。"
              + "可选 expected_generation 校验文档代次防止旧快照改新状态;"
              + "可选 on_error(continue=失败不中断默认,stop=遇首条失败即停)。")
  @ToolCapability(operation = CapabilityOperation.ADD, element = "paragraph")
  public String insertParagraph(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(
              name = "paragraphs",
              description =
                  "对象数组,每个对象含 body_index(int)、text(string),"
                      + "可选 heading_level、alignment，如 [{\"body_index\":0,\"text\":\"标题\",\"heading_level\":\"H1\",\"alignment\":\"CENTER\"}]")
          @NestedParamCapability(path = "paragraphs.body_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "paragraphs.text", type = ParamType.STRING)
          @NestedParamCapability(
              path = "paragraphs.heading_level",
              type = ParamType.ENUM,
              enumValues = {"H1", "H2", "H3", "H4", "H5", "H6"})
          @NestedParamCapability(
              path = "paragraphs.alignment",
              type = ParamType.ENUM,
              enumValues = {"LEFT", "CENTER", "RIGHT", "JUSTIFY"})
          List<Map<String, Object>> paragraphs,
      @ToolParam(
              name = "on_error",
              description = "continue=失败不中断(默认),stop=遇首条失败即停",
              required = false)
          @ParamCapability(
              type = ParamType.ENUM,
              enumValues = {"continue", "stop"})
          String onError,
      @ToolParam(
              name = "expected_generation",
              description = "可选。调用方持有的 session generation,与当前不符则拒绝写入(防止旧快照修改新状态)。不传则跳过校验。",
              required = false)
          @ParamCapability(type = ParamType.INTEGER)
          Integer expectedGeneration) {
    Document doc = document(docId);
    if (doc == null) {
      return renderDocNotFound(docId);
    }
    if (!checkExpectedGeneration(docId, expectedGeneration)) {
      long current = generations.getOrDefault(docId, 1L);
      return renderGenerationMismatch(expectedGeneration, current);
    }
    boolean stopOnError = "stop".equalsIgnoreCase(onError);
    List<Object> list = coerceList(paragraphs);
    if (list.isEmpty()) {
      ToolResult<Void> result =
          ToolResult.fail(ToolResultCode.INVALID_ARGUMENT, "paragraphs 为空", "至少传一条插入");
      return ToolResultRenderer.render(result);
    }
    StringBuilder sb = new StringBuilder();
    int ok = 0;
    int fail = 0;
    int skipped = 0;
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      Object item = list.get(i);
      String tag = "[" + i + "] ";
      if (!(item instanceof Map)) {
        sb.append(tag).append("错误:该条不是对象(").append(item).append(")");
        fail++;
        if (stopOnError) {
          skipped = list.size() - i - 1;
          break;
        }
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) item;
      int bodyIndex;
      String text;
      HeadingLevel headingLevel;
      Alignment alignment;
      try {
        bodyIndex = getInt(m, "body_index");
        text = getStr(m, "text");
        headingLevel =
            m.containsKey("heading_level") ? parseHeadingLevel(getStr(m, "heading_level")) : null;
        alignment = m.containsKey("alignment") ? parseAlignment(getStr(m, "alignment")) : null;
      } catch (RuntimeException e) {
        sb.append(tag).append("错误:").append(e.getMessage());
        fail++;
        if (stopOnError) {
          skipped = list.size() - i - 1;
          break;
        }
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
        if (stopOnError) {
          skipped = list.size() - i - 1;
          break;
        }
        continue;
      }
      try {
        Paragraph inserted = doc.insertParagraph(bodyIndex);
        inserted.addRun(text);
        if (headingLevel != null) inserted.heading(headingLevel);
        if (alignment != null) inserted.alignment(alignment);
      } catch (RuntimeException e) {
        sb.append(tag).append("错误:").append(rootMessage(e));
        fail++;
        if (stopOnError) {
          skipped = list.size() - i - 1;
          break;
        }
        continue;
      }
      sb.append(tag)
          .append("body ")
          .append(bodyIndex)
          .append(" 插入段落 → \"")
          .append(text)
          .append("\"");
      if (headingLevel != null) sb.append(" heading=").append(headingLevel);
      if (alignment != null) sb.append(" alignment=").append(alignment);
      sb.append(" ✓");
      ok++;
    }
    sb.append("\n成功 ").append(ok).append(" 条,失败 ").append(fail).append(" 条");
    int matchedCount = ok + fail;
    ToolResult<Integer> result =
        fail > 0
            ? ToolResult.partial(
                ToolResultCode.PARTIAL_FAILURE,
                ok,
                sb.toString(),
                null,
                matchedCount,
                ok,
                stopOnError ? skipped : null)
            : ToolResult.ok(ok, sb.toString(), matchedCount, ok, null);
    return ToolResultRenderer.render(result);
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
  @ToolCapability(operation = CapabilityOperation.READ, element = "hyperlink")
  public String readHyperlink(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "paragraph_index", description = "段落索引（0 起）")
          @ParamCapability(type = ParamType.INTEGER)
          int paragraphIndex,
      @ToolParam(name = "hyperlink_index", description = "超链接索引（0 起）")
          @ParamCapability(type = ParamType.INTEGER)
          int hyperlinkIndex) {
    Hyperlink link = locateHyperlink(docId, paragraphIndex, hyperlinkIndex);
    if (link == null) {
      ToolResult<Void> failed = locateHyperlinkFailed(docId, paragraphIndex, hyperlinkIndex);
      return ToolResultRenderer.render(failed);
    }
    String summary = "显示文本: " + link.text() + "\n目标 URL: " + link.url();
    ToolResult<String> result = ToolResult.ok(summary, summary);
    return ToolResultRenderer.render(result);
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
              + "改完需 save_docx 落盘。"
              + "可选 expected_generation 校验文档代次防止旧快照改新状态。")
  @ToolCapability(operation = CapabilityOperation.UPDATE, element = "hyperlink")
  public String updateHyperlink(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "paragraph_index", description = "段落索引（0 起）")
          @ParamCapability(type = ParamType.INTEGER)
          int paragraphIndex,
      @ToolParam(name = "hyperlink_index", description = "超链接索引（0 起）")
          @ParamCapability(type = ParamType.INTEGER)
          int hyperlinkIndex,
      @ToolParam(name = "text", description = "新的显示文本(可选,不传则不改)", required = false)
          @ParamCapability(type = ParamType.STRING)
          String text,
      @ToolParam(name = "url", description = "新的目标 URL(可选,不传则不改)", required = false)
          @ParamCapability(type = ParamType.STRING)
          String url,
      @ToolParam(
              name = "expected_generation",
              description = "可选。调用方持有的 session generation,与当前不符则拒绝写入(防止旧快照修改新状态)。不传则跳过校验。",
              required = false)
          @ParamCapability(type = ParamType.INTEGER)
          Integer expectedGeneration) {
    if ((text == null || text.isEmpty()) && (url == null || url.isEmpty())) {
      // R1 空更新:text 与 url 都未提供 → NO_CHANGES_APPLIED(非参数错误)。
      return renderNoChangesApplied("未提供 text 或 url");
    }
    Document doc = document(docId);
    if (doc == null) {
      return renderDocNotFound(docId);
    }
    if (!checkExpectedGeneration(docId, expectedGeneration)) {
      long current = generations.getOrDefault(docId, 1L);
      return renderGenerationMismatch(expectedGeneration, current);
    }
    Hyperlink link = locateHyperlink(docId, paragraphIndex, hyperlinkIndex);
    if (link == null) {
      ToolResult<Void> failed = locateHyperlinkFailed(docId, paragraphIndex, hyperlinkIndex);
      return ToolResultRenderer.render(failed);
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
        ToolResult<Void> result =
            ToolResult.fail(
                ToolResultCode.INVALID_ARGUMENT, "错误：无法修改超链接 URL（" + rootMessage(e) + "）");
        return ToolResultRenderer.render(result);
      }
      done.add("URL → " + url);
    }
    String message =
        "已修改：段落 " + paragraphIndex + " 超链接 " + hyperlinkIndex + " 的 " + String.join("、", done);
    ToolResult<String> result = ToolResult.ok(message, message);
    return ToolResultRenderer.render(result);
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
  @ToolCapability(operation = CapabilityOperation.READ, element = "document")
  public String searchText(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "keyword", description = "要查找的文本") @ParamCapability(type = ParamType.STRING)
          String keyword,
      @ToolParam(name = "exact", description = "true=精确相等；false（默认）=忽略大小写的子串包含")
          @ParamCapability(type = ParamType.BOOLEAN, defaultValue = "false")
          boolean exact,
      @ToolParam(name = "max_results", description = "命中数上限：>0 为上限，0 或负数=不限（默认 50）。命中很多时可调大或传 0")
          @ParamCapability(type = ParamType.INTEGER, defaultValue = "50")
          Integer maxResults) {
    Document doc = document(docId);
    if (doc == null) {
      return renderDocNotFound(docId);
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
      String message = "未找到「" + keyword + "」";
      ToolResult<List<String>> result = ToolResult.ok(hits, message, 0, null);
      return ToolResultRenderer.render(result);
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
    ToolResult<List<String>> result = ToolResult.ok(hits, sb.toString(), hits.size(), null);
    return ToolResultRenderer.render(result);
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

  /**
   * 配合 {@link #locateHyperlink}：返回定位失败时的结构化结果（需重新解析边界以给准确数字）。
   *
   * <p>非 {@code @ToolDef} 的内部 helper，直接返回 {@link ToolResult} 不走 String 边界。
   */
  private ToolResult<Void> locateHyperlinkFailed(
      String docId, int paragraphIndex, int hyperlinkIndex) {
    Document doc = document(docId);
    if (doc == null) {
      return docNotFoundResult(docId);
    }
    var paragraphs = doc.paragraphs();
    if (outOfBounds(paragraphIndex, paragraphs.size())) {
      return ToolResult.fail(
          ToolResultCode.INDEX_OUT_OF_RANGE,
          "错误：段落索引 " + paragraphIndex + " 越界（共 " + paragraphs.size() + "）",
          "使用 0.." + Math.max(0, paragraphs.size() - 1));
    }
    long count = hyperlinkCount(paragraphs.get(paragraphIndex));
    return ToolResult.fail(
        ToolResultCode.INDEX_OUT_OF_RANGE,
        "错误：超链接索引 " + hyperlinkIndex + " 越界（该段含 " + count + " 个超链接）",
        "使用 0.." + Math.max(0L, count - 1L));
  }

  /** 兼容旧 Java 调用；等价于未传 expected_generation。 */
  @Deprecated
  public String updateHyperlink(
      String docId, int paragraphIndex, int hyperlinkIndex, String text, String url) {
    return updateHyperlink(docId, paragraphIndex, hyperlinkIndex, text, url, null);
  }
}
