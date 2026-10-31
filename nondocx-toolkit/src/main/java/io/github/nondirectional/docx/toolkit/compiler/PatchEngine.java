package io.github.nondirectional.docx.toolkit.compiler;

import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.style.Alignment;
import io.github.nondirectional.docx.core.api.style.RunStyle;
import io.github.nondirectional.docx.core.api.table.Table;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import io.github.nondirectional.docx.core.api.text.Run;
import io.github.nondirectional.docx.toolkit.compiler.MarkdownDoc.Block;
import io.github.nondirectional.docx.toolkit.compiler.MarkdownDoc.Heading;
import io.github.nondirectional.docx.toolkit.compiler.MarkdownDoc.ListItem;
import io.github.nondirectional.docx.toolkit.compiler.MarkdownDoc.Para;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文档 patch 引擎：对<b>既有</b>文档应用一批原子编辑操作。
 *
 * <p>它是「文档 IR + 编译器」路线的编辑侧（`docs/11` §3.3）：模型用一次 {@code edit_document} 表达一批编辑（ops 数组），锚点是 {@code
 * read_document} 输出的 短 ID（{@code pN}/{@code tN}），插入内容用 Markdown 块表达（复用 {@link MarkdownParser} +
 * {@link DocumentCompiler}，不发明第二套内容语言）。
 *
 * <p><b>原子性。</b> 先 preflight 全部 op：锚点存在、参数合法、{@code find} 在段落文本中、 行列在界内、表格块只允许插到文档末尾、被本 patch 前序 op
 * 删除的锚点不得再引用。任一 失败抛 {@link PatchException}，<b>零写入</b>。执行期不存在可预期失败分支；POI 意外异常 直接上抛（由工具层报告「可能部分应用」）。
 *
 * <p><b>op 集（v6，14 种）：</b>
 *
 * <ul>
 *   <li>{@code insert}——Markdown 块插入 {@code before}/{@code after} 锚点或文档 {@code start}/{code
 *       end}；含表格块时仅允许 {@code end}（core 无定位插表 API）；
 *   <li>{@code replace_text}——段落内精确子串替换；{@code text} 支持行内标记（{@code **粗**}/{@code *斜*}/{@code
 *       `代码`}/{@code <u>下划线</u>}，与 create_document/table.set_cell 同一内容语言）：单 run 命中按 span 拆分（前后缀经
 *       {@code copyRun} 继承原样式），跨 run 时整段重建（丢段内直接格式，记 warning）；
 *   <li>{@code delete}——删除段落（删表不在 v2：core 无 API）；
 *   <li>{@code set_style}——套 profile 的 {@code body}/{@code heading} 样式，或显式 {@code align}/{@code
 *       line_spacing}/{@code bold}/{@code italic}（bold/italic 为整段字符格式，可与其余叠加）；
 *   <li>{@code table.set_cell}——改单元格文本；
 *   <li>{@code table.add_row}——末尾加一行（可带初始文本）；
 *   <li>{@code tracked.insert} / {@code tracked.delete} / {@code tracked.replace}——修订模式 （tracked
 *       changes）写作：ins / del / del+ins 配对，{@code author} 必传，{@code text} 支持 {@code **粗**}/{@code
 *       *斜*}/{@code `代码`}/{@code <u>下划线</u>} 行内标记（多 span 为同一 ins 内多 run，各持 rPr）。替换是最小 diff——只
 *       del「find」范围、ins 紧随其后（core {@code replaceTrackedRange}）；
 *   <li>{@code tracked.set_style}——以修订方式（rPrChange）变更<b>既有文字</b>的 bold/italic：{@code anchor}+
 *       {@code find} 定位（多处命中全部处理），{@code bold}/{@code italic} 至少给一个（true 设置 / false 取消）， {@code
 *       author} 必传。目标词与邻文同 run 时先拆 run 再逐 run 提交属性修订（core {@code Run.commitStyleAsTracked}），文本不变；
 *   <li>{@code footer.set_page_number}——页脚追加页码段落，{@code format} 支持 {@code {page}}/{@code {pages}}
 *       占位符（PAGE/NUMPAGES 域，带缓存结果 "1"，非渲染读者也能读到数字）；
 *   <li>{@code header.set_text}——页眉追加一段文本，{@code text} 必填非空、支持行内标记（{@code **粗**}/{@code *斜*}/{@code
 *       `代码`}/{@code <u>下划线</u>}，与 replace_text 同一内容语言）；追加语义（重复调用追加新段，不替换既有页眉）， 不占段落锚点；
 *   <li>{@code comment.add}——给锚点段落加整段范围批注（core {@code Paragraph.addComment}），{@code author}
 *       必传。不改段落文本，不受同锚点改文本独占约束；
 *   <li>{@code comment.reply}——回复一条<b>既有根批注</b>（core {@code Comments.reply}）：{@code comment_id}
 *       （{@code read_document} 批注行的 {@code #N}，允许带前导 {@code #}）+ {@code text} + {@code author}
 *       必传，<b>无 anchor</b>、不改正文、不占段落锚点。MVP 只允许一级回复——目标是回复时整体拒绝并提示改回根批注。
 * </ul>
 *
 * <p><b>同锚点独占（v2）。</b> tracked.* 与 {@code replace_text} 都改段落文本，而修订的偏移量按 <b>执行时活文本</b> 计算（tracked
 * del 后 text() 会移除被删文本）——同批多个改文本 op 共享锚点会互相污染。 规则：每个段落锚点在一批 ops 里<b>至多一个改文本 op</b>（tracked.* 或
 * replace_text），违反则整体拒绝并提示拆批。{@code tracked.set_style} 不改文本，但执行期拆 run 会<b>重建段落 run 结构</b>，同批其他改文本
 * op 的 run 定位可能失效——保守纳入同一独占规则。
 *
 * <p>op 数组来自 nonchain 还原的 {@code List<LinkedHashMap>}；本类只读 Map，不依赖 JSON 库。
 */
public final class PatchEngine {

  /** preflight 失败：消息含第几个 op 与原因；抛出时保证零写入。 */
  public static final class PatchException extends IllegalArgumentException {

    PatchException(String message) {
      super(message);
    }
  }

  /** 一次 patch 的结果（执行或 dry-run）。 */
  public static final class PatchResult {

    private final boolean dryRun;
    private final int applied;
    private final List<String> changed;
    private final List<String> warnings;
    private final List<String> planned;

    PatchResult(
        boolean dryRun,
        int applied,
        List<String> changed,
        List<String> warnings,
        List<String> planned) {
      this.dryRun = dryRun;
      this.applied = applied;
      this.changed = List.copyOf(changed);
      this.warnings = List.copyOf(warnings);
      this.planned = List.copyOf(planned);
    }

    /** 是否 dry-run（未写入）。 */
    public boolean dryRun() {
      return dryRun;
    }

    /** 实际应用的 op 数（dry-run 时为计划数）。 */
    public int applied() {
      return applied;
    }

    /** 被触碰的锚点（执行模式）。 */
    public List<String> changed() {
      return changed;
    }

    /** 宽容降级/格式丢失警告。 */
    public List<String> warnings() {
      return warnings;
    }

    /** dry-run 的逐条计划描述。 */
    public List<String> planned() {
      return planned;
    }
  }

  private PatchEngine() {}

  /**
   * 对文档应用一批 ops。
   *
   * @param doc 活文档
   * @param ops op 的 Map 列表（顺序执行）
   * @param profile set_style 用的样式 profile
   * @param dryRun {@code true} 只返回计划，不写入
   * @return 执行结果
   * @throws PatchException preflight 失败（零写入）
   */
  public static PatchResult apply(
      Document doc, List<Object> ops, StyleProfile profile, boolean dryRun) {
    if (ops == null || ops.isEmpty()) {
      throw new PatchException("ops 为空，至少传一条操作");
    }
    StyleProfile p = profile == null ? StyleProfile.builtin("plain") : profile;
    Snapshot snapshot = new Snapshot(doc);
    Set<String> deleted = new HashSet<>();
    Map<String, Integer> textClaims = new HashMap<>();
    List<PlannedOp> plannedOps = new ArrayList<>();
    List<String> plannedText = new ArrayList<>();
    for (int i = 0; i < ops.size(); i++) {
      Object raw = ops.get(i);
      if (!(raw instanceof Map)) {
        throw new PatchException("op[" + i + "] 不是对象: " + raw);
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> op = (Map<String, Object>) raw;
      PlannedOp planned = planOp(i, op, snapshot, deleted, textClaims, p);
      plannedOps.add(planned);
      plannedText.add("op[" + i + "] " + planned.describe());
    }
    if (dryRun) {
      return new PatchResult(true, plannedOps.size(), List.of(), List.of(), plannedText);
    }
    List<String> changed = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    for (PlannedOp planned : plannedOps) {
      planned.execute(doc, snapshot, changed, warnings, p);
    }
    return new PatchResult(false, plannedOps.size(), changed, warnings, List.of());
  }

  // ==================== preflight：解析 + 校验 ====================

  private static PlannedOp planOp(
      int index,
      Map<String, Object> op,
      Snapshot snapshot,
      Set<String> deleted,
      Map<String, Integer> textClaims,
      StyleProfile p) {
    String kind = str(op, "op", index);
    String anchor = op.get("anchor") == null ? null : String.valueOf(op.get("anchor")).trim();
    if (anchor != null && !anchor.isEmpty() && deleted.contains(anchor)) {
      throw new PatchException("op[" + index + "] 锚点 " + anchor + " 已被本 patch 前序 op 删除，不能再用");
    }
    switch (kind) {
      case "insert":
        return planInsert(index, op, anchor, snapshot, p);
      case "replace_text":
        claimAnchor(index, anchor, "replace_text", textClaims);
        return planReplaceText(index, op, anchor, snapshot);
      case "delete":
        return planDelete(index, op, anchor, snapshot, deleted);
      case "set_style":
        return planSetStyle(index, op, anchor, snapshot);
      case "table.set_cell":
        return planTableSetCell(index, op, anchor, snapshot);
      case "table.add_row":
        return planTableAddRow(index, op, anchor, snapshot);
      case "tracked.insert":
        claimAnchor(index, anchor, "tracked.insert", textClaims);
        return planTrackedInsert(index, op, anchor, snapshot);
      case "tracked.delete":
        claimAnchor(index, anchor, "tracked.delete", textClaims);
        return planTrackedFindOp(index, op, anchor, snapshot, true);
      case "tracked.replace":
        claimAnchor(index, anchor, "tracked.replace", textClaims);
        return planTrackedFindOp(index, op, anchor, snapshot, false);
      case "tracked.set_style":
        claimAnchor(index, anchor, "tracked.set_style", textClaims);
        return planTrackedSetStyle(index, op, anchor, snapshot);
      case "footer.set_page_number":
        return planFooterPageNumber(index, op);
      case "header.set_text":
        return planHeaderSetText(index, op);
      case "comment.add":
        return planCommentAdd(index, op, anchor, snapshot);
      case "comment.reply":
        return planCommentReply(index, op, snapshot);
      default:
        throw new PatchException(
            "op["
                + index
                + "] 未知操作类型: "
                + kind
                + "（可用: insert/replace_text/delete/set_style/table.set_cell/table.add_row/"
                + "tracked.insert/tracked.delete/tracked.replace/tracked.set_style/"
                + "footer.set_page_number/header.set_text/comment.add/comment.reply）");
    }
  }

  /** 同锚点改文本独占：tracked.* 与 replace_text 共用。第二个认领者整体拒绝（零写入），提示拆成两次 edit_document。 */
  private static void claimAnchor(
      int index, String anchor, String kind, Map<String, Integer> claims) {
    if (anchor == null || anchor.isEmpty()) {
      throw new PatchException("op[" + index + "] " + kind + " 需要 anchor（段落短 ID）");
    }
    Integer previous = claims.putIfAbsent(anchor, index);
    if (previous != null) {
      throw new PatchException(
          "op["
              + index
              + "] 锚点 "
              + anchor
              + " 已被 op["
              + previous
              + "] 认领——同一批 ops 里每个段落至多一个改文本 op（tracked.*/replace_text），"
              + "请拆成两次 edit_document 依次调用");
    }
  }

  private static PlannedOp planInsert(
      int index, Map<String, Object> op, String anchor, Snapshot snapshot, StyleProfile p) {
    String position =
        op.get("position") == null ? "end" : String.valueOf(op.get("position")).trim();
    String block = str(op, "block", index);
    MarkdownDoc parsed = MarkdownParser.parse(block);
    if (parsed.blocks().isEmpty()) {
      throw new PatchException("op[" + index + "] block 为空或全是空白，无内容可插入");
    }
    boolean hasTable = parsed.blocks().stream().anyMatch(b -> b instanceof MarkdownDoc.Table);
    if (hasTable && !"end".equals(position)) {
      throw new PatchException("op[" + index + "] block 含表格，只支持 position=end（core 无定位插表 API）");
    }
    Paragraph anchorParagraph;
    switch (position) {
      case "before":
      case "after":
        anchorParagraph = snapshot.requireParagraph(anchor, index);
        break;
      case "start":
      case "end":
        anchorParagraph = null;
        break;
      default:
        throw new PatchException(
            "op[" + index + "] 未知 position: " + position + "（可用 before/after/start/end）");
    }
    List<Block> blocks = parsed.blocks();
    List<String> warnings = new ArrayList<>(parsed.warnings());
    return new PlannedOp("insert " + position + " " + describeBlocks(blocks)) {
      @Override
      void execute(
          Document doc,
          Snapshot snap,
          List<String> changed,
          List<String> warningsOut,
          StyleProfile profile) {
        warningsOut.addAll(warnings);
        // 单元素数组承载可变插入位置（匿名类只能捕获 effectively final 局部变量）
        int[] insertAt = {resolveBodyIndex(doc, snap, anchorParagraph, position)};
        for (Block b : blocks) {
          if (b instanceof Heading) {
            Paragraph paragraph = doc.insertParagraph(insertAt[0]);
            paragraph.heading(DocumentCompiler.headingLevelOf((Heading) b));
            DocumentCompiler.fillHeading(paragraph, (Heading) b, profile);
            insertAt[0]++;
          } else if (b instanceof Para) {
            DocumentCompiler.fillPara(doc.insertParagraph(insertAt[0]), (Para) b, profile);
            insertAt[0]++;
          } else if (b instanceof ListItem) {
            ListItem item = (ListItem) b;
            Paragraph paragraph = doc.insertParagraph(insertAt[0]);
            paragraph.list(item.kind(), Math.min(item.level(), 8));
            DocumentCompiler.fillListItem(paragraph, item, profile);
            insertAt[0]++;
          } else if (b instanceof MarkdownDoc.Table) {
            DocumentCompiler.compileTableInto(doc, (MarkdownDoc.Table) b, profile);
          }
        }
        if (anchor != null && anchorParagraph != null) {
          changed.add(anchor);
        }
      }
    };
  }

  private static PlannedOp planReplaceText(
      int index, Map<String, Object> op, String anchor, Snapshot snapshot) {
    Paragraph paragraph = snapshot.requireParagraph(anchor, index);
    String find = str(op, "find", index);
    String text = str(op, "text", index);
    String current = paragraph.text();
    if (!current.contains(find)) {
      throw new PatchException(
          "op[" + index + "] 段落 " + anchor + " 文本中找不到「" + find + "」（实际: " + current + "）");
    }
    // text 按行内标记解析（Markdown 是本编译器的统一内容语言，与 create_document/table.set_cell 一致）；
    // 纯文本（无标记）保持原行为不变。
    List<MarkdownDoc.Span> spans = MarkdownParser.parseInline(text);
    boolean formatted =
        spans.stream().anyMatch(s -> s.bold() || s.italic() || s.code() || s.underline());
    return new PlannedOp(
        "replace_text " + anchor + " 「" + find + "」→「" + text + "」" + (formatted ? "（含格式）" : "")) {
      @Override
      void execute(
          Document doc,
          Snapshot snap,
          List<String> changed,
          List<String> warningsOut,
          StyleProfile profile) {
        int runIndex = runContaining(paragraph, find);
        if (runIndex >= 0 && !formatted) {
          // 单 run 内命中：原位替换保样式。
          Run run = paragraph.run(runIndex);
          run.text(run.text().replace(find, text));
        } else if (runIndex >= 0) {
          replaceFormattedInRun(paragraph, runIndex, find, spans, profile);
        } else if (formatted) {
          // 跨 run 命中 + 带格式：整段重建为 span 序列（前后缀纯文本 run + 格式 run）。
          String full = paragraph.text();
          removeAllInline(paragraph);
          int pos = 0;
          for (int[] occ : occurrencesOf(full, find)) {
            if (occ[0] > pos) {
              paragraph.addRun(full.substring(pos, occ[0]));
            }
            for (MarkdownDoc.Span span : spans) {
              applySpanFormat(paragraph.addRun(span.text()), span, profile);
            }
            pos = occ[1];
          }
          if (pos < full.length()) {
            paragraph.addRun(full.substring(pos));
          }
          warningsOut.add("replace_text " + anchor + " 跨 run 命中，段落已重建（段内直接格式丢失，替换片段按行内标记重建）");
        } else {
          // 跨 run 命中（纯文本）：整段重建为单 run（丢段内直接格式）。
          String rebuilt = paragraph.text().replace(find, text);
          removeAllInline(paragraph);
          paragraph.addRun(rebuilt);
          warningsOut.add("replace_text " + anchor + " 跨 run 命中，段落已重建为单 run（段内直接格式丢失，段落属性保留）");
        }
        changed.add(anchor);
      }
    };
  }

  /**
   * 单 run 内的带格式替换。源 run 只保留前缀文本；替换片段逐 span、以及命中之间的间隔/后缀文本，均以<b>源 run 的深拷贝</b>承载 （完整 rPr 继承，含
   * eastAsia 等未建模槽），span 再叠加粗/斜/下划线/代码字体。源 run 内多处命中全部处理（非重叠，与 {@code String.replace} 语义一致）。源 run
   * 剩空文本时移除。
   *
   * <p>插入顺序陷阱：拷贝必须始终复制源 run——若顺序拷贝「上一份拷贝」，格式会逐份累积（第二份起继承第一份的加粗）。 因此所有 piece 都从源 run 索引拷贝（每次都插在源 run
   * 紧后），按 piece <b>倒序</b>插入，最终恰好是正向顺序。
   */
  private static void replaceFormattedInRun(
      Paragraph paragraph,
      int runIndex,
      String find,
      List<MarkdownDoc.Span> spans,
      StyleProfile profile) {
    Run source = paragraph.run(runIndex);
    String text = source.text();
    List<int[]> occurrences = occurrencesOf(text, find);
    // 正向 piece 序列：每个命中 → 逐 span；命中之间与结尾 → 原文本片段（纯 span，applySpanFormat 对其无操作）
    List<MarkdownDoc.Span> pieces = new ArrayList<>();
    for (int k = 0; k < occurrences.size(); k++) {
      pieces.addAll(spans);
      int gapStart = occurrences.get(k)[1];
      int gapEnd = k + 1 < occurrences.size() ? occurrences.get(k + 1)[0] : text.length();
      if (gapEnd > gapStart) {
        pieces.add(MarkdownDoc.Span.text(text.substring(gapStart, gapEnd)));
      }
    }
    source.text(text.substring(0, occurrences.get(0)[0]));
    for (int i = pieces.size() - 1; i >= 0; i--) {
      MarkdownDoc.Span piece = pieces.get(i);
      Run copy = paragraph.copyRun(runIndex);
      copy.text(piece.text());
      applySpanFormat(copy, piece, profile);
    }
    if (source.text().isEmpty()) {
      for (int i = 0; i < paragraph.inlineElements().size(); i++) {
        if (paragraph.inlineElement(i) instanceof Run) {
          Run element = (Run) paragraph.inlineElement(i);
          if (element.raw() == source.raw()) {
            paragraph.removeInlineElement(i);
            break;
          }
        }
      }
    }
  }

  /** 逐 span 套行内格式：粗/斜/下划线叠加在继承的样式上；代码 span 两槽均改 profile 等宽字体（与 table.set_cell 一致）。 */
  private static void applySpanFormat(Run run, MarkdownDoc.Span span, StyleProfile profile) {
    if (span.bold()) {
      run.bold();
    }
    if (span.italic()) {
      run.italic();
    }
    if (span.underline()) {
      run.underline();
    }
    if (span.code()) {
      DocumentCompiler.applyRunFonts(run, profile.codeFont(), profile.codeFont());
    }
  }

  /**
   * spans → 有序文本段列表（供 tracked 多 run API），与传入 spans <b>索引一一对应</b>—— 调用方以相同下标把 {@link
   * #applySpanFormat} 成对应用到返回的 run 上。parseInline 契约不产生空 span（空入参在 {@code str()}
   * 已被拒），故不做过滤；若未来解析器变化出现空段，core 多 run API 的空串校验会把它暴露为执行期错误。
   */
  private static List<String> spanTexts(List<MarkdownDoc.Span> spans) {
    List<String> texts = new ArrayList<>(spans.size());
    for (MarkdownDoc.Span span : spans) {
      texts.add(span.text());
    }
    return texts;
  }

  /** 清空段落全部内联元素（含超链接等），供整段重建。 */
  private static void removeAllInline(Paragraph paragraph) {
    while (paragraph.inlineElements().size() > 0) {
      paragraph.removeInlineElement(paragraph.inlineElements().size() - 1);
    }
  }

  /** 在文本中从左到右收集 find 的全部非重叠命中（半开区间），与 {@code String.replace} 的替换语义一致。 */
  private static List<int[]> occurrencesOf(String text, String find) {
    List<int[]> ranges = new ArrayList<>();
    int from = 0;
    while (from <= text.length()) {
      int found = text.indexOf(find, from);
      if (found < 0) {
        break;
      }
      ranges.add(new int[] {found, found + find.length()});
      from = found + find.length();
    }
    return ranges;
  }

  private static PlannedOp planDelete(
      int index, Map<String, Object> op, String anchor, Snapshot snapshot, Set<String> deleted) {
    Paragraph paragraph = snapshot.requireParagraph(anchor, index);
    deleted.add(anchor);
    return new PlannedOp("delete " + anchor) {
      @Override
      void execute(
          Document doc,
          Snapshot snap,
          List<String> changed,
          List<String> warningsOut,
          StyleProfile profile) {
        for (int i = 0; i < doc.paragraphs().size(); i++) {
          if (doc.paragraphs().get(i).raw() == paragraph.raw()) {
            doc.removeParagraph(i);
            changed.add(anchor);
            return;
          }
        }
        throw new IllegalStateException("段落 " + anchor + " 在执行期找不到（不应发生）");
      }
    };
  }

  private static PlannedOp planSetStyle(
      int index, Map<String, Object> op, String anchor, Snapshot snapshot) {
    Paragraph paragraph = snapshot.requireParagraph(anchor, index);
    String style = op.get("style") == null ? null : String.valueOf(op.get("style")).trim();
    Object alignRaw = op.get("align");
    Object spacingRaw = op.get("line_spacing");
    Object boldRaw = op.get("bold");
    Object italicRaw = op.get("italic");
    if (style == null
        && alignRaw == null
        && spacingRaw == null
        && boldRaw == null
        && italicRaw == null) {
      throw new PatchException(
          "op["
              + index
              + "] set_style 需要 style（body/heading）或 align/line_spacing/bold/italic 至少其一");
    }
    if (style != null && !"body".equals(style) && !"heading".equals(style)) {
      String example =
          style.contains("=")
              ? "；对齐/行距是独立字段：{\"op\":\"set_style\",\"anchor\":\"p0\",\"align\":\"center\"}"
              : "；bold/italic 是独立布尔字段：{\"op\":\"set_style\",\"anchor\":\"p0\",\"bold\":true}";
      throw new PatchException(
          "op["
              + index
              + "] 未知 style: "
              + style
              + "（可用 body/heading）"
              + example
              + "；字体不写进 style——由 style_profile 决定（标题黑体：style=heading + style_profile=report-cn）");
    }
    Alignment align = alignRaw == null ? null : MarkdownParser.alignmentOf(strValue(alignRaw));
    Double spacing = spacingRaw == null ? null : doubleValue(spacingRaw, index);
    Boolean bold = boldRaw == null ? null : booleanValue(boldRaw, index, "bold");
    Boolean italic = italicRaw == null ? null : booleanValue(italicRaw, index, "italic");
    String described =
        "set_style "
            + anchor
            + (style == null ? "" : " style=" + style)
            + (bold == null ? "" : " bold=" + bold)
            + (italic == null ? "" : " italic=" + italic);
    return new PlannedOp(described) {
      @Override
      void execute(
          Document doc,
          Snapshot snap,
          List<String> changed,
          List<String> warningsOut,
          StyleProfile profile) {
        if ("body".equals(style)) {
          if (profile.bodyLineSpacing() != null) {
            paragraph.lineSpacing(profile.bodyLineSpacing());
          }
          if (profile.firstLineIndentTwips() > 0) {
            paragraph.indent(0, profile.firstLineIndentTwips());
          }
          for (Run run : paragraph.runs()) {
            DocumentCompiler.applyRunFonts(run, profile.bodyLatin(), profile.bodyEastAsia());
            if (profile.bodySizePt() != null) {
              run.fontSize(profile.bodySizePt());
            }
          }
        } else if ("heading".equals(style)) {
          Integer level = paragraph.heading() == null ? 1 : paragraph.heading().ordinal() + 1;
          for (Run run : paragraph.runs()) {
            DocumentCompiler.applyRunFonts(run, profile.headingLatin(), profile.headingEastAsia());
            if (profile.headingSizePt(level) != null) {
              run.fontSize(profile.headingSizePt(level));
            }
          }
        }
        if (align != null) {
          paragraph.alignment(align);
        }
        if (spacing != null && spacing > 0) {
          paragraph.lineSpacing(spacing);
        }
        // bold/italic 后应用：可叠加在 style=body/heading 的字体/字号之上（两者正交）
        if (bold != null) {
          for (Run run : paragraph.runs()) {
            run.bold(bold);
          }
        }
        if (italic != null) {
          for (Run run : paragraph.runs()) {
            run.italic(italic);
          }
        }
        changed.add(anchor);
      }
    };
  }

  private static PlannedOp planTableSetCell(
      int index, Map<String, Object> op, String anchor, Snapshot snapshot) {
    Table table = snapshot.requireTable(anchor, index);
    int row = intValue(op.get("row"), index, "row");
    int col = intValue(op.get("col"), index, "col");
    String text = str(op, "text", index);
    if (row < 0 || row >= table.rows().size()) {
      throw new PatchException(
          "op[" + index + "] " + anchor + " row " + row + " 越界（共 " + table.rows().size() + " 行）");
    }
    int cols = table.rows().get(row).cells().size();
    if (col < 0 || col >= cols) {
      throw new PatchException(
          "op[" + index + "] " + anchor + " col " + col + " 越界（该行共 " + cols + " 列）");
    }
    // text 按 Markdown 行内标记解析：**任务** → 文本「任务」+ 加粗 run（Markdown 是内容语言）。
    List<MarkdownDoc.Span> spans = MarkdownParser.parseInline(text);
    return new PlannedOp("table.set_cell " + anchor + " r" + row + "c" + col) {
      @Override
      void execute(
          Document doc,
          Snapshot snap,
          List<String> changed,
          List<String> warningsOut,
          StyleProfile profile) {
        var cell = table.rows().get(row).cells().get(col);
        boolean plain =
            spans.size() == 1
                && !spans.get(0).bold()
                && !spans.get(0).italic()
                && !spans.get(0).code()
                && !spans.get(0).underline();
        if (plain) {
          cell.text(spans.get(0).text());
        } else {
          // 重建单元格首段为多 run，逐 span 套行内格式 + profile 字体
          var paragraph = cell.paragraph(0);
          while (paragraph.runs().size() > 0) {
            paragraph.removeInlineElement(paragraph.runs().size() - 1);
          }
          for (MarkdownDoc.Span span : spans) {
            Run run = paragraph.addRun(span.text());
            if (span.bold()) {
              run.bold();
            }
            if (span.italic()) {
              run.italic();
            }
            if (span.underline()) {
              run.underline();
            }
            if (span.code()) {
              DocumentCompiler.applyRunFonts(run, profile.codeFont(), profile.codeFont());
            } else {
              DocumentCompiler.applyRunFonts(run, profile.bodyLatin(), profile.bodyEastAsia());
            }
            if (profile.bodySizePt() != null) {
              run.fontSize(profile.bodySizePt());
            }
          }
        }
        changed.add(anchor);
      }
    };
  }

  private static PlannedOp planTableAddRow(
      int index, Map<String, Object> op, String anchor, Snapshot snapshot) {
    Table table = snapshot.requireTable(anchor, index);
    List<String> cells = new ArrayList<>();
    Object raw = op.get("cells");
    if (raw instanceof List) {
      for (Object cell : (List<?>) raw) {
        cells.add(cell == null ? "" : String.valueOf(cell));
      }
    }
    if (table.rows().isEmpty()) {
      throw new PatchException("op[" + index + "] " + anchor + " 是空表格，无法 add_row");
    }
    int colCount = table.rows().get(0).cells().size();
    if (cells.size() > colCount) {
      throw new PatchException(
          "op[" + index + "] " + anchor + " cells 有 " + cells.size() + " 个，超过表列数 " + colCount);
    }
    return new PlannedOp("table.add_row " + anchor + " (" + cells.size() + " 格)") {
      @Override
      void execute(
          Document doc,
          Snapshot snap,
          List<String> changed,
          List<String> warningsOut,
          StyleProfile profile) {
        var row = table.addRow();
        row.cantSplit(true);
        for (int c = 0; c < colCount; c++) {
          String text = c < cells.size() ? cells.get(c) : "";
          // addRow() 建出的行没有单元格，按 index 逐个补建（与编译器 writeCell 同一陷阱）
          io.github.nondirectional.docx.core.api.table.Cell cell;
          if (c < row.cells().size()) {
            cell = row.cells().get(c);
          } else {
            cell = row.addCell();
          }
          cell.text(text);
        }
        changed.add(anchor);
      }
    };
  }

  // ==================== 执行期辅助 ====================

  /**
   * tracked.insert：段末追加一条 ins 修订（位置语义正确——addInsertion 就是段末追加）。 {@code text} 支持行内标记（与 replace_text
   * 同一内容语言）：多 span 生成同一 {@code <w:ins>} 内的多 run（读侧仍一条 INS），span 逐 run 叠加粗/斜/下划线/代码字体。
   */
  private static PlannedOp planTrackedInsert(
      int index, Map<String, Object> op, String anchor, Snapshot snapshot) {
    Paragraph paragraph = snapshot.requireParagraph(anchor, index);
    String text = str(op, "text", index);
    String author = author(op, index);
    List<MarkdownDoc.Span> spans = MarkdownParser.parseInline(text);
    boolean formatted =
        spans.stream().anyMatch(s -> s.bold() || s.italic() || s.code() || s.underline());
    return new PlannedOp(
        "tracked.insert " + anchor + " +" + text.length() + " 字" + (formatted ? "（含格式）" : "")) {
      @Override
      void execute(
          Document doc,
          Snapshot snap,
          List<String> changed,
          List<String> warningsOut,
          StyleProfile profile) {
        if (formatted) {
          List<Run> inserted = paragraph.addInsertion(author, spanTexts(spans));
          for (int i = 0; i < inserted.size(); i++) {
            applySpanFormat(inserted.get(i), spans.get(i), profile);
          }
        } else {
          paragraph.addInsertion(author, text);
        }
        changed.add(anchor);
      }
    };
  }

  /**
   * tracked.delete / tracked.replace：按 find 定位（多处命中全部处理，与 replace_text 一致）。
   *
   * <p>preflight 只做存在性校验；<b>偏移量在执行时按活文本计算</b>（tracked del 会把被删文本从 text() 里移走，preflight
   * 快照不能复用），从右到左应用避免偏移漂移。同锚点独占规则（{@link #claimAnchor}）保证执行时 文本就是 preflight 时看到的文本。
   *
   * <p>tracked.replace 的 {@code text} 支持行内标记：多 span 时每次命中生成一条 {@code <w:ins>} 内的多 run（每个 run
   * 继承被替换范围首个 run 的 rPr，再按 span 叠加格式）。
   */
  private static PlannedOp planTrackedFindOp(
      int index, Map<String, Object> op, String anchor, Snapshot snapshot, boolean deleteOnly) {
    Paragraph paragraph = snapshot.requireParagraph(anchor, index);
    String find = str(op, "find", index);
    String author = author(op, index);
    String text = deleteOnly ? null : str(op, "text", index);
    List<MarkdownDoc.Span> spans = deleteOnly ? null : MarkdownParser.parseInline(text);
    boolean formatted =
        spans != null
            && spans.stream().anyMatch(s -> s.bold() || s.italic() || s.code() || s.underline());
    if (!paragraph.text().contains(find)) {
      throw new PatchException(
          "op[" + index + "] 段落 " + anchor + " 文本中找不到「" + find + "」（实际: " + paragraph.text() + "）");
    }
    String described = deleteOnly ? "tracked.delete" : "tracked.replace";
    return new PlannedOp(
        described
            + " "
            + anchor
            + " 「"
            + find
            + "」"
            + (deleteOnly ? "" : "→「" + text + "」" + (formatted ? "（含格式）" : ""))) {
      @Override
      void execute(
          Document doc,
          Snapshot snap,
          List<String> changed,
          List<String> warningsOut,
          StyleProfile profile) {
        int[] applied = {0};
        forEachOccurrenceRightToLeft(
            paragraph,
            find,
            (start, end) -> {
              if (deleteOnly) {
                paragraph.deleteTrackedRange(author, start, end);
              } else if (formatted) {
                List<Run> inserted =
                    paragraph.replaceTrackedRange(author, start, end, spanTexts(spans));
                for (int i = 0; i < inserted.size(); i++) {
                  applySpanFormat(inserted.get(i), spans.get(i), profile);
                }
              } else {
                paragraph.replaceTrackedRange(author, start, end, text);
              }
              applied[0]++;
            });
        if (applied[0] == 0) {
          // 独占规则下不应发生；真发生说明契约被打破，按执行期意外上抛
          throw new IllegalStateException(
              "tracked op 执行期未命中「" + find + "」（不应发生，文本在 preflight 后被改动）");
        }
        changed.add(anchor);
      }
    };
  }

  /**
   * tracked.set_style：以修订方式（rPrChange）变更<b>既有文字</b>的 bold/italic（v5）。
   *
   * <p>{@code anchor}+{@code find} 定位（与 tracked.delete/replace 同模型，多处命中全部处理、 从右到左应用）；{@code
   * bold}/{@code italic} 是显式布尔三态——<b>true 设置 / false 取消（{@code w:val="off"}）/ 缺省不动</b>，至少给一个；
   * {@code author} 必传。执行语义：命中片段与邻文同 run 时先拆 run（{@link #trackedSetStyleOnRange}），再对每个目标 run 「拆分后快照
   * → 改样式 → core {@code Run.commitStyleAsTracked}」两步式提交属性修订。段落文本不变。
   *
   * <p>幂等边界：对已含 rPrChange 的 run 再次 commit 会追加新 id 的 rPrChange（core 语义），本 op 不去重。方向性观测边界：取消加粗与设置加粗的
   * newSummary 同为 {@code [b]}（summarize 只看本地名），方向证据看 run 实际属性呈现。
   */
  private static PlannedOp planTrackedSetStyle(
      int index, Map<String, Object> op, String anchor, Snapshot snapshot) {
    Paragraph paragraph = snapshot.requireParagraph(anchor, index);
    String find = str(op, "find", index);
    String author = author(op, index);
    Boolean bold = op.get("bold") == null ? null : booleanValue(op.get("bold"), index, "bold");
    Boolean italic =
        op.get("italic") == null ? null : booleanValue(op.get("italic"), index, "italic");
    if (bold == null && italic == null) {
      throw new PatchException(
          "op["
              + index
              + "] tracked.set_style 未指定任何格式变更；需至少提供 bold 或 italic（true 设置 / false 取消），"
              + "如 {\"op\":\"tracked.set_style\",\"anchor\":\"p0\",\"find\":\"逾期率\",\"bold\":true,"
              + "\"author\":\"审查员\"}");
    }
    if (!paragraph.text().contains(find)) {
      throw new PatchException(
          "op["
              + index
              + "] 段落 "
              + anchor
              + " 文本中找不到「"
              + find
              + "」（实际: "
              + paragraph.text()
              + "）；find 是段内既有文字的精确子串，正确写法如 "
              + "{\"op\":\"tracked.set_style\",\"anchor\":\"p0\",\"find\":\"逾期率\",\"bold\":true,"
              + "\"author\":\"审查员\"}");
    }
    String described =
        "tracked.set_style "
            + anchor
            + " 「"
            + find
            + "」"
            + (bold == null ? "" : " bold=" + bold)
            + (italic == null ? "" : " italic=" + italic)
            + " author="
            + author;
    return new PlannedOp(described) {
      @Override
      void execute(
          Document doc,
          Snapshot snap,
          List<String> changed,
          List<String> warningsOut,
          StyleProfile profile) {
        // 本 op 不改段落文本（拆 run 保持文本），occurrences 与 replace_text 同为非重叠语义；
        // 从右到左应用与 tracked 家族一致（对纯格式变更是防御性习惯，保持同族口径）。
        List<int[]> ranges = occurrencesOf(paragraph.text(), find);
        if (ranges.isEmpty()) {
          // 独占规则下不应发生；真发生说明契约被打破，按执行期意外上抛
          throw new IllegalStateException(
              "tracked.set_style 执行期未命中「" + find + "」（不应发生，文本在 preflight 后被改动）");
        }
        for (int i = ranges.size() - 1; i >= 0; i--) {
          trackedSetStyleOnRange(
              paragraph, ranges.get(i)[0], ranges.get(i)[1], bold, italic, author);
        }
        changed.add(anchor);
      }
    };
  }

  /**
   * 对段落活文本 {@code [start, end)} 区间应用 tracked 格式变更：把与区间<b>部分相交</b>的 run 拆出「命中片段独占的 run」（copyRun
   * 深拷贝完整 rPr 继承，<b>倒序插入</b>——顺序拷贝「上一份拷贝」会格式逐份累积，PatchEngineTest 抓过的真实坑），再对每个目标 run 两步式提交
   * rPrChange（快照在拆分<b>后</b>取：拷贝继承源 run 样式）。
   *
   * <p>run 遍历<b>从右到左</b>：拆分插入的拷贝落在源 run 之后（索引更大），右到左处理不会重访已处理/新插入的 run。命中片段落在超链接等非普通 run
   * 上时按执行期意外上抛（修订格式只能作用于普通 run）。
   *
   * @return 本区间内提交了属性修订的 run 数
   */
  private static int trackedSetStyleOnRange(
      Paragraph paragraph, int start, int end, Boolean bold, Boolean italic, String author) {
    // 先捕获普通 run 句柄与其文本区间（执行期拆分会改变 runs() 视图）
    List<Run> runs = new ArrayList<>(paragraph.runs());
    int[] starts = new int[runs.size()];
    int cursor = 0;
    for (int i = 0; i < runs.size(); i++) {
      starts[i] = cursor;
      String text = runs.get(i).text();
      cursor += text == null ? 0 : text.length();
    }
    int styled = 0;
    for (int i = runs.size() - 1; i >= 0; i--) {
      Run source = runs.get(i);
      String text = source.text() == null ? "" : source.text();
      int runStart = starts[i];
      int runEnd = runStart + text.length();
      if (text.isEmpty() || runEnd <= start || runStart >= end) {
        continue; // 与目标区间无交集
      }
      Run target;
      if (runStart >= start && runEnd <= end) {
        target = source; // 整 run 命中（或此前拆分的片段 run），无需再拆
      } else {
        // 部分相交：源 run 留前缀，命中片段 + 后缀以深拷贝承载、倒序插在源 run 紧后
        int a = Math.max(start - runStart, 0);
        int b = Math.min(end - runStart, text.length());
        List<String> pieces = new ArrayList<>(2);
        pieces.add(text.substring(a, b));
        if (b < text.length()) {
          pieces.add(text.substring(b));
        }
        for (int k = pieces.size() - 1; k >= 0; k--) {
          paragraph.copyRun(i).text(pieces.get(k));
        }
        target = paragraph.run(i + 1);
        source.text(a > 0 ? text.substring(0, a) : "");
        if (a == 0) {
          removeEmptyRun(paragraph, source);
        }
      }
      RunStyle before = target.style();
      if (bold != null) {
        target.bold(bold);
      }
      if (italic != null) {
        target.italic(italic);
      }
      target.commitStyleAsTracked(author, before);
      styled++;
    }
    if (styled == 0) {
      // 命中片段不落在任何普通 run 上（如整段在超链接内）——结构性边缘，按执行期意外上抛
      throw new IllegalStateException(
          "tracked.set_style 的命中区间 ["
              + start
              + ","
              + end
              + ") 未落在任何普通 run 上"
              + "（只含超链接/图片等不可格式元素），无法提交属性修订");
    }
    return styled;
  }

  /** 按对象身份移除段落里的指定 run（用于拆分后源 run 剩空文本时清理，与 replaceFormattedInRun 同手法）。 */
  private static void removeEmptyRun(Paragraph paragraph, Run run) {
    for (int i = 0; i < paragraph.inlineElements().size(); i++) {
      if (paragraph.inlineElement(i) instanceof Run) {
        Run element = (Run) paragraph.inlineElement(i);
        if (element.raw() == run.raw()) {
          paragraph.removeInlineElement(i);
          return;
        }
      }
    }
  }

  /**
   * comment.add：给锚点段落加整段范围批注（core {@code Paragraph.addComment}）。不改段落文本—— 不参与同锚点改文本独占（{@link
   * #claimAnchor}），可与 replace_text 同批作用于同段。
   */
  private static PlannedOp planCommentAdd(
      int index, Map<String, Object> op, String anchor, Snapshot snapshot) {
    Paragraph paragraph = snapshot.requireParagraph(anchor, index);
    String text = str(op, "text", index);
    String author = author(op, index);
    return new PlannedOp("comment.add " + anchor + " 「" + text + "」") {
      @Override
      void execute(
          Document doc,
          Snapshot snap,
          List<String> changed,
          List<String> warningsOut,
          StyleProfile profile) {
        paragraph.addComment(author, text);
        changed.add(anchor);
      }
    };
  }

  /**
   * comment.reply（v6）：回复一条既有<b>根批注</b>（core {@code Comments.reply}）。
   *
   * <p>按 {@code comment_id} 寻址（{@code read_document} 批注行的 {@code #N}，容忍前导 {@code #}）——
   * 回复挂在批注上而不是段落上，故<b>不需要 anchor</b>、不占段落锚点（与 {@code footer.set_page_number}/{@code
   * header.set_text} 同型），可与正文 op 同批；也不改段落文本，不受同锚点改文本独占约束。
   *
   * <p>preflight 读文档校验（零写入）：id 归一化后为空、批注不存在、目标是回复（MVP 只做一级回复）都在这里整体拒绝。执行期 core 的 {@code
   * replyToComment} 不应出现可预期失败。返回值在此丢弃——模型要核对应走 {@code read_document}（与 {@code comment.add}
   * 只记锚点同姿态）。
   */
  private static PlannedOp planCommentReply(int index, Map<String, Object> op, Snapshot snapshot) {
    String rawId = op.get("comment_id") == null ? "" : String.valueOf(op.get("comment_id")).trim();
    // 模型常直接复制读侧的「#N」——归一化掉前导 #，避免一次无谓失败（不承诺接受 id=3 等其它形态）
    if (rawId.startsWith("#")) {
      rawId = rawId.substring(1).trim();
    }
    if (rawId.isEmpty()) {
      throw new PatchException(
          "op["
              + index
              + "] 缺少必填字段 comment_id（read_document 批注行的 #N，如 "
              + "{\"op\":\"comment.reply\",\"comment_id\":\"3\",\"text\":\"同意\",\"author\":\"复核员\"}）");
    }
    String text = str(op, "text", index);
    String author = author(op, index);
    io.github.nondirectional.docx.core.api.comment.Comment parent;
    try {
      parent = snapshot.doc.comments().get(rawId);
    } catch (java.util.NoSuchElementException e) {
      throw new PatchException("op[" + index + "] 批注 #" + rawId + " 不存在（read_document 查看可用批注 id）");
    }
    if (parent.parentId().isPresent()) {
      throw new PatchException(
          "op["
              + index
              + "] 批注 #"
              + rawId
              + " 本身是回复，MVP 只支持回复根批注（请回复其根批注 #"
              + parent.parentId().get()
              + "）");
    }
    String commentId = rawId;
    return new PlannedOp("comment.reply #" + commentId + " 「" + text + "」") {
      @Override
      void execute(
          Document doc,
          Snapshot snap,
          List<String> changed,
          List<String> warningsOut,
          StyleProfile profile) {
        doc.comments().reply(commentId, author, text);
        changed.add("comment#" + commentId);
      }
    };
  }

  /** footer.set_page_number：页脚追加页码段落（不触碰正文段落）。 */
  private static PlannedOp planFooterPageNumber(int index, Map<String, Object> op) {
    String format =
        op.get("format") == null || String.valueOf(op.get("format")).trim().isEmpty()
            ? "第 {page} 页"
            : String.valueOf(op.get("format")).trim();
    // 先扫占位符合法性（未知 token 优先报具体名字），再校验至少含一个占位符
    int brace = format.indexOf('{', 0);
    while (brace >= 0) {
      int close = format.indexOf('}', brace + 1);
      String token = close > brace ? format.substring(brace, close + 1) : "";
      if (!"{page}".equals(token) && !"{pages}".equals(token)) {
        throw new PatchException(
            "op["
                + index
                + "] format 含未知占位符 "
                + (token.isEmpty() ? "'{'（未闭合）" : token)
                + "（可用: {page}/{pages}）");
      }
      brace = format.indexOf('{', brace + 1);
    }
    if (!format.contains("{page}") && !format.contains("{pages}")) {
      throw new PatchException(
          "op[" + index + "] format 需要含 {page}（当前页码）或 {pages}（总页数）占位符，如「第 {page} 页 / 共 {pages} 页」");
    }
    return new PlannedOp("footer.set_page_number \"" + format + "\"") {
      @Override
      void execute(
          Document doc,
          Snapshot snap,
          List<String> changed,
          List<String> warningsOut,
          StyleProfile profile) {
        Paragraph paragraph = doc.ensureFooter().addParagraph();
        int cursor = 0;
        while (cursor < format.length()) {
          int page = format.indexOf("{page}", cursor);
          int pages = format.indexOf("{pages}", cursor);
          // {pages} 优先于 {page}（前者是后者的前缀扩展）
          int hit;
          boolean isPages;
          if (pages >= 0 && (page < 0 || pages <= page)) {
            hit = pages;
            isPages = true;
          } else if (page >= 0) {
            hit = page;
            isPages = false;
          } else {
            hit = -1;
            isPages = false;
          }
          if (hit < 0) {
            appendLiteral(paragraph, format.substring(cursor));
            break;
          }
          if (hit > cursor) {
            appendLiteral(paragraph, format.substring(cursor, hit));
          }
          // 带缓存结果的域：Word/WPS 打开时重算，POI/文本抽取器读到缓存值 "1"
          paragraph.addSimpleField(isPages ? "NUMPAGES" : "PAGE", "1");
          cursor = hit + (isPages ? 7 : 6);
        }
        changed.add("footer");
      }
    };
  }

  /** 追加字面文本段（空段跳过）。 */
  private static void appendLiteral(Paragraph paragraph, String literal) {
    if (!literal.isEmpty()) {
      paragraph.addRun(literal);
    }
  }

  /**
   * header.set_text：向默认 section 页眉<b>追加</b>一段文本（不触碰正文段落，与 footer.set_page_number 同型，不占段落锚点）。 {@code
   * text} 必填非空、按行内标记解析（与 replace_text 同一内容语言）：多 span 逐 run 写入并套粗/斜/下划线/代码字体。
   * 追加语义——重复调用追加新段，不替换既有页眉内容（core 无 Header 替换 API，替换留 future）。
   */
  private static PlannedOp planHeaderSetText(int index, Map<String, Object> op) {
    String text = str(op, "text", index);
    if (text.trim().isEmpty()) {
      throw new PatchException("op[" + index + "] text 不能为空白（要写进页眉的文本）");
    }
    text = text.trim();
    // text 按行内标记解析（Markdown 是本编译器的统一内容语言，与 replace_text/tracked.* 一致）
    List<MarkdownDoc.Span> spans = MarkdownParser.parseInline(text);
    return new PlannedOp("header.set_text \"" + text + "\"") {
      @Override
      void execute(
          Document doc,
          Snapshot snap,
          List<String> changed,
          List<String> warningsOut,
          StyleProfile profile) {
        // ensureHeader 幂等创建（写路径，N5），addParagraph 返回普通 Paragraph——行内写入与正文同 API
        Paragraph paragraph = doc.ensureHeader().addParagraph();
        for (MarkdownDoc.Span span : spans) {
          applySpanFormat(paragraph.addRun(span.text()), span, profile);
        }
        changed.add("header");
      }
    };
  }

  /** 在段落活文本里从右到左枚举 find 的全部命中（半开区间），供 tracked op 依次应用。 */
  private interface OccurrenceVisitor {

    void accept(int start, int end);
  }

  private static void forEachOccurrenceRightToLeft(
      Paragraph paragraph, String find, OccurrenceVisitor visitor) {
    String text = paragraph.text();
    List<int[]> ranges = new ArrayList<>();
    int from = 0;
    while (from <= text.length()) {
      int found = text.indexOf(find, from);
      if (found < 0) {
        break;
      }
      ranges.add(new int[] {found, found + find.length()});
      from = found + 1;
    }
    for (int i = ranges.size() - 1; i >= 0; i--) {
      visitor.accept(ranges.get(i)[0], ranges.get(i)[1]);
    }
  }

  /** 修订作者必填（空白拒绝）。 */
  private static String author(Map<String, Object> op, int index) {
    String author = op.get("author") == null ? "" : String.valueOf(op.get("author")).trim();
    if (author.isEmpty()) {
      throw new PatchException("op[" + index + "] 缺少必填字段 author（修订作者，Word 修订窗格显示它）");
    }
    return author;
  }

  /** 定位插入的 body 序号：start=0；end=末尾；before/after=锚点当前 body 位置（±1）。 */
  private static int resolveBodyIndex(
      Document doc, Snapshot snap, Paragraph anchorParagraph, String position) {
    if (anchorParagraph == null) {
      return "start".equals(position) ? 0 : doc.bodyElements().size();
    }
    for (int i = 0; i < doc.bodyElements().size(); i++) {
      var element = doc.bodyElements().get(i);
      if (element instanceof Paragraph && ((Paragraph) element).raw() == anchorParagraph.raw()) {
        return "after".equals(position) ? i + 1 : i;
      }
    }
    throw new IllegalStateException("插入锚点在执行期找不到（不应发生）");
  }

  /** 命中 find 的 run 序号（find 完整落在单个 run 内返回索引，否则 -1）。 */
  private static int runContaining(Paragraph paragraph, String find) {
    for (int i = 0; i < paragraph.runs().size(); i++) {
      String text = paragraph.run(i).text();
      if (text != null && text.contains(find)) {
        return i;
      }
    }
    return -1;
  }

  private static String describeBlocks(List<Block> blocks) {
    List<String> parts = new ArrayList<>();
    for (Block b : blocks) {
      if (b instanceof Heading) {
        parts.add("H" + ((Heading) b).level());
      } else if (b instanceof Para) {
        parts.add("段落");
      } else if (b instanceof ListItem) {
        parts.add("列表项");
      } else if (b instanceof MarkdownDoc.Table) {
        parts.add("表格");
      }
    }
    return String.join("+", parts);
  }

  // ==================== 入参归一化 ====================

  private static String str(Map<String, Object> op, String key, int index) {
    Object value = op.get(key);
    if (value == null || String.valueOf(value).isEmpty()) {
      throw new PatchException("op[" + index + "] 缺少必填字段 " + key);
    }
    return String.valueOf(value);
  }

  private static String strValue(Object value) {
    return String.valueOf(value).trim();
  }

  private static int intValue(Object value, int index, String key) {
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(value).trim());
    } catch (NumberFormatException e) {
      throw new PatchException("op[" + index + "] " + key + " 不是整数: " + value);
    }
  }

  private static Double doubleValue(Object value, int index) {
    if (value instanceof Number) {
      return ((Number) value).doubleValue();
    }
    try {
      return Double.parseDouble(String.valueOf(value).trim());
    } catch (NumberFormatException e) {
      throw new PatchException("op[" + index + "] line_spacing 不是数字: " + value);
    }
  }

  /** 布尔入参归一化：接受 Boolean 或 "true"/"false"（忽略大小写），其余整体拒绝（零写入）。 */
  private static Boolean booleanValue(Object value, int index, String key) {
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    String text = String.valueOf(value).trim();
    if ("true".equalsIgnoreCase(text)) {
      return Boolean.TRUE;
    }
    if ("false".equalsIgnoreCase(text)) {
      return Boolean.FALSE;
    }
    throw new PatchException("op[" + index + "] " + key + " 不是布尔（true/false）: " + value);
  }

  // ==================== 内部结构 ====================

  /** preflight 期对文档的一次快照：短 ID → 活对象。 */
  private static final class Snapshot {

    /** 活文档本身：comment.reply 的批注存在性校验需要按 id 查批注（无段落/表格锚点可用）。 */
    final Document doc;

    final Map<String, Paragraph> paragraphs = new HashMap<>();
    final Map<String, Table> tables = new HashMap<>();

    Snapshot(Document doc) {
      this.doc = doc;
      int p = 0;
      int t = 0;
      for (var element : doc.bodyElements()) {
        if (element instanceof Paragraph) {
          paragraphs.put("p" + p++, (Paragraph) element);
        } else if (element instanceof Table) {
          tables.put("t" + t++, (Table) element);
        }
      }
    }

    Paragraph requireParagraph(String anchor, int index) {
      Paragraph paragraph = anchor == null ? null : paragraphs.get(anchor);
      if (paragraph == null) {
        throw new PatchException(
            "op["
                + index
                + "] 段落锚点 "
                + anchor
                + " 不存在（read_document 后再引用，共 "
                + paragraphs.size()
                + " 段）");
      }
      return paragraph;
    }

    Table requireTable(String anchor, int index) {
      Table table = anchor == null ? null : tables.get(anchor);
      if (table == null) {
        throw new PatchException(
            "op[" + index + "] 表格锚点 " + anchor + " 不存在（共 " + tables.size() + " 个表格）");
      }
      return table;
    }
  }

  /** preflight 产出的可执行计划（执行期不再有可预期失败）。 */
  abstract static class PlannedOp {

    private final String description;

    PlannedOp(String description) {
      this.description = description;
    }

    String describe() {
      return description;
    }

    abstract void execute(
        Document doc,
        Snapshot snap,
        List<String> changed,
        List<String> warnings,
        StyleProfile profile);
  }
}
