package com.non.docx.toolkit;

import com.non.chain.tool.ToolDef;
import com.non.chain.tool.ToolParam;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.table.Cell;
import com.non.docx.core.api.text.Paragraph;
import com.non.docx.core.api.text.Run;
import com.non.docx.toolkit.capability.CapabilityOperation;
import com.non.docx.toolkit.capability.NestedParamCapability;
import com.non.docx.toolkit.capability.ParamCapability;
import com.non.docx.toolkit.capability.ParamType;
import com.non.docx.toolkit.capability.ToolCapability;
import com.non.docx.toolkit.ref.ReferenceContext;
import com.non.docx.toolkit.result.ToolResult;
import com.non.docx.toolkit.result.ToolResultCode;
import com.non.docx.toolkit.result.ToolResultRenderer;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 修订<b>创作</b>工具组（原 I 组）：以 tracked 方式插入 / 删除 / 替换 / 移动文本、改样式、标记单元格。
 *
 * <p>与 {@link TrackedChangeQueryTools}（读取/处理）<b>正交</b>：本类不读也不 accept/reject，只创作修订标记； 创作出的修订随后可被
 * {@code list_tracked_changes} 读回、被 accept/reject 系列处理。
 *
 * <p><b>OOXML 三层递进（创作）：</b>
 *
 * <ul>
 *   <li><b>OOXML</b>：创作修订就是往 {@code word/document.xml} 写入修订标记元素—— 插入 {@code <w:ins>}、删除 {@code
 *       <w:del>}（内含 {@code <w:delData>}）、移动 {@code <w:moveFrom>}/{@code <w:moveTo>}、 属性变更 {@code
 *       rPrChange}（嵌 {@code <w:rPr>}）、单元格结构 {@code cellIns}/{@code cellDel}（嵌 {@code <w:tcPr>}）。
 *   <li><b>POI</b>：无创作修订的高层 API。两个坑（见 poi-bridge.md N14）： {@code getRuns()} 不暴露 ins/del 包装内的
 *       run；迁既有 run 入 {@code <w:del>} 要 {@code XmlCursor} 搬运。
 *   <li><b>nondocx</b>：节点创建下沉 {@code internal/poi}，公共 API 在 {@code Paragraph}/{@code Run}/{@code
 *       Cell}， POI-free。
 * </ul>
 *
 * <p>全部沿用「显式 tracked 方法」：{@code author} 必传，{@code date}/{@code w:id} 自动分配， 与 {@code
 * <w:trackChanges/>} 开关<b>正交</b>——开关只管「人在 Word 里后续手动改动是否被追踪」， 对本类创作的修订标记无影响。
 */
public final class TrackedChangeAuthoringTools extends ToolkitToolContext {

  /** 接收门面注入的共享会话状态（与 SessionTools 共享同一份 sessions/seq）。 */
  TrackedChangeAuthoringTools(Map<String, Document> sharedSessions, AtomicInteger sharedSeq) {
    super(sharedSessions, sharedSeq);
  }

  TrackedChangeAuthoringTools(
      Map<String, Document> sharedSessions,
      AtomicInteger sharedSeq,
      ReferenceContext sharedReferences,
      Map<String, Long> sharedGenerations) {
    super(sharedSessions, sharedSeq, sharedReferences, sharedGenerations);
  }

  /**
   * 批量在若干段落末尾插入 tracked 插入修订(<w:ins>,带可选内联样式)。
   *
   * <p><b>批量语义（v2）。</b> {@code author} 是<b>共享顶层参数</b>(同一批插入通常同一作者);{@code edits}
   * 是<b>对象数组</b>,每个对象含:
   *
   * <ul>
   *   <li>{@code paragraph_index}:整数,必填,目标段落索引(0 起,正文段落)
   *   <li>{@code text}:字符串,必填,插入的文本
   *   <li>{@code bold}:布尔,可选,默认 false
   *   <li>{@code italic}:布尔,可选,默认 false
   *   <li>{@code color}:字符串,可选,颜色十六进制如 FF0000
   * </ul>
   *
   * <p>数组长度 1 即单段插入;多个即一次插多处(如给若干段落各加一句批注)。
   *
   * <p><b>失败语义:collect-errors。</b> 逐条尝试,段落越界/缺字段的条目记错误不中断;末尾汇总成功/失败条数。
   *
   * <p><b>无索引漂移。</b> insert 是"往段末追加新 run",不改既有 run 列表,条目间互不影响。创作出的修订可被 {@code list_tracked_changes}
   * 读回、{@code apply_tracked_changes} 处理。
   */
  @ToolDef(
      name = "insert_tracked_run",
      description =
          "批量在若干段落末尾插入被追踪的插入修订(<w:ins>),可选带内联样式(bold/italic/color)。"
              + "author 是共享修订作者(必填)。edits 是对象数组,每个对象含:"
              + "paragraph_index(int,目标段落索引 0 起)、text(string,插入文本),"
              + "以及可选 bold(bool)、italic(bool)、color(string,十六进制如 FF0000)。"
              + "单个对象用长度 1 的数组。部分失败不中断,返回每条成功/失败明细。"
              + "可选 on_error(continue=失败不中断默认,stop=遇首条失败即停);"
              + "可选 expected_generation 校验文档代次防止旧快照改新状态。")
  @ToolCapability(operation = CapabilityOperation.ADD, element = "tracked_change")
  public String insertTrackedRun(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "author", description = "修订作者(整批共享)")
          @ParamCapability(type = ParamType.STRING)
          String author,
      @ToolParam(
              name = "edits",
              description =
                  "对象数组,每个对象含 paragraph_index(int)、text(string),"
                      + "可选 bold(bool)、italic(bool)、color(string),"
                      + "如 [{\"paragraph_index\":0,\"text\":\"插入\",\"bold\":true}]")
          @NestedParamCapability(path = "edits.paragraph_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.text", type = ParamType.STRING)
          @NestedParamCapability(path = "edits.bold", type = ParamType.BOOLEAN)
          @NestedParamCapability(path = "edits.italic", type = ParamType.BOOLEAN)
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
      return renderGenerationMismatch(expectedGeneration, generations.getOrDefault(docId, 1L));
    }
    List<Paragraph> paragraphs = doc.paragraphs();
    List<Object> list = coerceList(edits);
    if (list.isEmpty()) {
      return renderInvalidArgument("edits 为空");
    }
    boolean stopOnError = "stop".equalsIgnoreCase(onError);
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
      int paragraphIndex;
      String text;
      try {
        paragraphIndex = getInt(m, "paragraph_index");
        text = getStr(m, "text");
      } catch (RuntimeException e) {
        sb.append(tag).append("错误:").append(e.getMessage());
        fail++;
        if (stopOnError) {
          skipped = list.size() - i - 1;
          break;
        }
        continue;
      }
      if (outOfBounds(paragraphIndex, paragraphs.size())) {
        sb.append(tag).append(indexError("段落索引", paragraphIndex, paragraphs.size()));
        fail++;
        if (stopOnError) {
          skipped = list.size() - i - 1;
          break;
        }
        continue;
      }
      boolean bold = boolVal(m.get("bold"));
      boolean italic = boolVal(m.get("italic"));
      String color = m.get("color") == null ? null : String.valueOf(m.get("color"));
      try {
        Run r = paragraphs.get(paragraphIndex).addInsertion(author, text);
        if (bold) {
          r.bold();
        }
        if (italic) {
          r.italic();
        }
        if (color != null && !color.isBlank()) {
          r.color(color);
        }
        sb.append(tag)
            .append("段落 ")
            .append(paragraphIndex)
            .append(" 插入 tracked run(文本=\"")
            .append(text)
            .append("\") ✓");
        ok++;
      } catch (RuntimeException e) {
        sb.append(tag).append("错误:").append(rootMessage(e));
        fail++;
        if (stopOnError) {
          skipped = list.size() - i - 1;
          break;
        }
      }
    }
    return renderBatchSummary(sb, ok, fail, skipped);
  }

  /**
   * 批量把若干已有 run 标记为被删除(tracked del,文字转为 {@code <w:delData>}、划删除线标记)。
   *
   * <p><b>批量语义（v2）。</b> {@code author} 共享;{@code edits} 是对象数组,每个对象含 {@code paragraph_index}(int)、
   * {@code run_index}(int)。数组长度 1 即单条删除。
   *
   * <p>核心语义:这不是立即删除——文字仍在文档里,只是被标为「待删除的修订」。accept 后才真正消失,reject 则恢复。
   *
   * <p><b>为什么是「快照 + 去重」而非「逆序」。</b> 探针验证:{@code addDeletion} 后 POI 仍把 {@code <w:del>} 里的 run 计入
   * {@code runs()},故<b>索引不漂移</b>(run0 永远是 run0);但被删 run 的 wrapper 会失效, 重复删同一 wrapper 会抛 {@code
   * XmlValueDisconnectedException}。因此正确做法是:先一次性按原始索引快照所有目标 wrapper, 再用 identity 去重后逐个删除——既规避
   * wrapper 失效,又让同段多个删除互不干扰。
   *
   * <p><b>失败语义:collect-errors。</b> 越界/缺字段/重复的条目记错误或提示不中断;末尾汇总成功/失败条数。
   */
  @ToolDef(
      name = "delete_run_tracked",
      description =
          "批量把正文若干 run 标记为被删除(tracked del:文字转 <w:delData>、划删除线)。"
              + "author 是共享修订作者(必填)。edits 是对象数组,每个对象含 "
              + "paragraph_index(int,段落索引 0 起)、run_index(int,run 索引 0 起)。"
              + "单个对象用长度 1 的数组。不是立即删除,accept 后才真正消失。"
              + "部分失败不中断,返回每条成功/失败明细。"
              + "可选 on_error(continue=失败不中断默认,stop=遇首条失败即停);"
              + "可选 expected_generation 校验文档代次防止旧快照改新状态。")
  @ToolCapability(operation = CapabilityOperation.REMOVE, element = "tracked_change")
  public String deleteRunTracked(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "author", description = "修订作者(整批共享)")
          @ParamCapability(type = ParamType.STRING)
          String author,
      @ToolParam(
              name = "edits",
              description =
                  "对象数组,每个对象含 paragraph_index(int)、run_index(int),"
                      + "如 [{\"paragraph_index\":0,\"run_index\":0}]")
          @NestedParamCapability(path = "edits.paragraph_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.run_index", type = ParamType.INTEGER)
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
      return renderGenerationMismatch(expectedGeneration, generations.getOrDefault(docId, 1L));
    }
    List<Object> list = coerceList(edits);
    if (list.isEmpty()) {
      return renderInvalidArgument("edits 为空");
    }
    var paragraphs = doc.paragraphs();
    boolean stopOnError = "stop".equalsIgnoreCase(onError);
    StringBuilder sb = new StringBuilder();
    int ok = 0;
    int fail = 0;
    int skipped = 0;
    // 按坐标去重:同一 (paragraphIndex, runIndex) 只删一次。
    // 注意 runs().get(i) 每次返回新的 Run 包装对象,不能用 identity 比较;改用坐标字符串。
    // 重复 addDeletion 同一 run 会抛 XmlValueDisconnectedException(见探针验证),故必须去重。
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      String tag = "[" + i + "] ";
      Object item = list.get(i);
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
      int paragraphIndex;
      int runIndex;
      try {
        paragraphIndex = getInt(m, "paragraph_index");
        runIndex = getInt(m, "run_index");
      } catch (RuntimeException e) {
        sb.append(tag).append("错误:").append(e.getMessage());
        fail++;
        if (stopOnError) {
          skipped = list.size() - i - 1;
          break;
        }
        continue;
      }
      if (outOfBounds(paragraphIndex, paragraphs.size())) {
        sb.append(tag).append(indexError("段落索引", paragraphIndex, paragraphs.size()));
        fail++;
        if (stopOnError) {
          skipped = list.size() - i - 1;
          break;
        }
        continue;
      }
      var runs = paragraphs.get(paragraphIndex).runs();
      if (outOfBounds(runIndex, runs.size())) {
        sb.append(tag).append(indexError("run 索引", runIndex, runs.size()));
        fail++;
        if (stopOnError) {
          skipped = list.size() - i - 1;
          break;
        }
        continue;
      }
      String coord = paragraphIndex + ":" + runIndex;
      if (!seen.add(coord)) {
        // 同一坐标已在本批删除过——再删会抛 XmlValueDisconnectedException,跳过并提示。
        sb.append(tag)
            .append("跳过:段落 ")
            .append(paragraphIndex)
            .append(" run ")
            .append(runIndex)
            .append(" 在本批已处理");
        fail++;
        if (stopOnError) {
          skipped = list.size() - i - 1;
          break;
        }
        continue;
      }
      Run target = runs.get(runIndex);
      try {
        // 先快照文本:addDeletion 会把 run 迁入 <w:del>、其 <w:t> 转为 <w:delData>,
        // 迁移后原 Run wrapper 已不可靠(读 text() 会 NPE),故必须先取。
        String text = target.text();
        paragraphs.get(paragraphIndex).addDeletion(author, target);
        sb.append(tag)
            .append("段落 ")
            .append(paragraphIndex)
            .append(" run ")
            .append(runIndex)
            .append(" tracked del(文本=\"")
            .append(text)
            .append("\") ✓");
        ok++;
      } catch (RuntimeException e) {
        sb.append(tag).append("错误:").append(rootMessage(e));
        fail++;
        if (stopOnError) {
          skipped = list.size() - i - 1;
          break;
        }
      }
    }
    return renderBatchSummary(sb, ok, fail, skipped);
  }

  /**
   * 批量以 tracked 方式替换若干 run 的文本(每个删旧 + 插新两条配对修订)。
   *
   * <p><b>批量语义（v2）。</b> {@code author} 共享;{@code edits} 是对象数组,每个对象含 {@code paragraph_index}(int)、
   * {@code run_index}(int)、{@code new_text}(string)。数组长度 1 即单条替换。
   *
   * <p>OOXML 没有「替换」元素——替换就是紧挨着的 {@code <w:del>}(旧文本)+{@code <w:ins>}(新文本)。新 run 复制旧 run 的样式,
   * 贴近「改字但保留格式」的直觉。
   *
   * <p><b>索引不漂移 + 快照去重</b>(同 {@link #deleteRunTracked}):{@code replaceTracked} 后 POI 仍把 run 计入
   * {@code runs()}, 故索引稳定;但 wrapper 会失效,重复替换同一 wrapper 会抛异常。故先快照、identity 去重、再逐个替换。
   *
   * <p><b>失败语义:collect-errors。</b>
   */
  @ToolDef(
      name = "replace_run_tracked",
      description =
          "批量以修订方式替换正文若干 run 的文本(tracked:每个删旧 + 插新两条配对修订)。"
              + "author 是共享修订作者(必填)。edits 是对象数组,每个对象含 "
              + "paragraph_index(int,段落索引 0 起)、run_index(int,run 索引 0 起)、new_text(string,新文本)。"
              + "新文本复制原 run 样式。单个对象用长度 1 的数组。部分失败不中断,返回每条成功/失败明细。"
              + "可选 on_error(continue=失败不中断默认,stop=遇首条失败即停);"
              + "可选 expected_generation 校验文档代次防止旧快照改新状态。")
  @ToolCapability(operation = CapabilityOperation.UPDATE, element = "tracked_change")
  public String replaceRunTracked(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "author", description = "修订作者(整批共享)")
          @ParamCapability(type = ParamType.STRING)
          String author,
      @ToolParam(
              name = "edits",
              description =
                  "对象数组,每个对象含 paragraph_index(int)、run_index(int)、new_text(string),"
                      + "如 [{\"paragraph_index\":0,\"run_index\":0,\"new_text\":\"新文本\"}]")
          @NestedParamCapability(path = "edits.paragraph_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.run_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "edits.new_text", type = ParamType.STRING)
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
      return renderGenerationMismatch(expectedGeneration, generations.getOrDefault(docId, 1L));
    }
    // replace 比 delete 多一个 new_text 参数,无法直接复用三参回调;在回调闭包里按条解析。
    List<Object> list = coerceList(edits);
    if (list.isEmpty()) {
      return renderInvalidArgument("edits 为空");
    }
    // 先把每条解析成 (para,run,newText) 或错误标记,再交给共享执行框架。
    // 这里复用 applyRunTrackedBatch 的核心思路,但需要 newText;为避免过度抽象,就地实现(结构与共享方法一致)。
    var paragraphs = doc.paragraphs();
    boolean stopOnError = "stop".equalsIgnoreCase(onError);
    StringBuilder sb = new StringBuilder();
    int ok = 0;
    int fail = 0;
    int skipped = 0;
    // 按坐标去重:同一 (paragraphIndex, runIndex) 只替换一次。runs().get(i) 每次返回新包装,
    // 不能 identity 比较,改用坐标字符串。重复 replaceTracked 同一 run 会抛 XmlValueDisconnectedException。
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      String tag = "[" + i + "] ";
      Object item = list.get(i);
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
      int paragraphIndex;
      int runIndex;
      String newText;
      try {
        paragraphIndex = getInt(m, "paragraph_index");
        runIndex = getInt(m, "run_index");
        newText = getStr(m, "new_text");
      } catch (RuntimeException e) {
        sb.append(tag).append("错误:").append(e.getMessage());
        fail++;
        if (stopOnError) {
          skipped = list.size() - i - 1;
          break;
        }
        continue;
      }
      if (outOfBounds(paragraphIndex, paragraphs.size())) {
        sb.append(tag).append(indexError("段落索引", paragraphIndex, paragraphs.size()));
        fail++;
        if (stopOnError) {
          skipped = list.size() - i - 1;
          break;
        }
        continue;
      }
      var runs = paragraphs.get(paragraphIndex).runs();
      if (outOfBounds(runIndex, runs.size())) {
        sb.append(tag).append(indexError("run 索引", runIndex, runs.size()));
        fail++;
        if (stopOnError) {
          skipped = list.size() - i - 1;
          break;
        }
        continue;
      }
      String coord = paragraphIndex + ":" + runIndex;
      if (!seen.add(coord)) {
        // 同一坐标已在本批替换过——再替换会抛 XmlValueDisconnectedException,跳过并提示。
        sb.append(tag)
            .append("跳过:段落 ")
            .append(paragraphIndex)
            .append(" run ")
            .append(runIndex)
            .append(" 在本批已处理");
        fail++;
        if (stopOnError) {
          skipped = list.size() - i - 1;
          break;
        }
        continue;
      }
      Run target = runs.get(runIndex);
      try {
        String oldText = target.text();
        target.replaceTracked(author, newText);
        sb.append(tag)
            .append("段落 ")
            .append(paragraphIndex)
            .append(" run ")
            .append(runIndex)
            .append(" 替换:\"")
            .append(oldText)
            .append("\" → \"")
            .append(newText)
            .append("\"(del+ins) ✓");
        ok++;
      } catch (RuntimeException e) {
        sb.append(tag).append("错误:").append(rootMessage(e));
        fail++;
        if (stopOnError) {
          skipped = list.size() - i - 1;
          break;
        }
      }
    }
    return renderBatchSummary(sb, ok, fail, skipped);
  }

  /**
   * 把一个 run 的内联样式变更记为 tracked rPrChange(属性修订)。
   *
   * <p>Agent 友好包装:一步到位。内部先快照改前样式、再应用目标样式、再 commitStyleAsTracked(底层两步式, 见 poi-bridge.md
   * N17)。未提供的样式参数(false/null)表示该属性「不显式设置」。
   */
  @ToolDef(
      name = "mark_style_change_tracked",
      description =
          "把一个 run 的样式变更记为被追踪的属性修订(rPrChange)。"
              + "内部:快照改前样式→应用目标样式→commitStyleAsTracked。reject 会回到旧样式。仅改你显式提供的样式参数。"
              + "可选 expected_generation 校验文档代次防止旧快照改新状态。")
  @ToolCapability(operation = CapabilityOperation.UPDATE, element = "tracked_change")
  public String markStyleChangeTracked(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "paragraph_index", description = "段落索引(0 起)")
          @ParamCapability(type = ParamType.INTEGER)
          int paragraphIndex,
      @ToolParam(name = "run_index", description = "run 索引(0 起)")
          @ParamCapability(type = ParamType.INTEGER)
          int runIndex,
      @ToolParam(name = "author", description = "修订作者") @ParamCapability(type = ParamType.STRING)
          String author,
      @ToolParam(name = "bold", description = "目标是否粗体(可选)", required = false)
          @ParamCapability(type = ParamType.BOOLEAN)
          boolean bold,
      @ToolParam(name = "italic", description = "目标是否斜体(可选)", required = false)
          @ParamCapability(type = ParamType.BOOLEAN)
          boolean italic,
      @ToolParam(name = "color", description = "目标颜色十六进制(可选)", required = false)
          @ParamCapability(type = ParamType.STRING)
          String color,
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
      return renderGenerationMismatch(expectedGeneration, generations.getOrDefault(docId, 1L));
    }
    List<Paragraph> paragraphs = doc.paragraphs();
    if (outOfBounds(paragraphIndex, paragraphs.size())) {
      return renderIndexError("段落索引", paragraphIndex, paragraphs.size());
    }
    var runs = paragraphs.get(paragraphIndex).runs();
    if (outOfBounds(runIndex, runs.size())) {
      return renderIndexError("run 索引", runIndex, runs.size());
    }
    try {
      Run r = runs.get(runIndex);
      com.non.docx.core.api.style.RunStyle before = r.style(); // 快照改前样式
      if (bold) {
        r.bold();
      }
      if (italic) {
        r.italic();
      }
      if (color != null && !color.isBlank()) {
        r.color(color);
      }
      r.commitStyleAsTracked(author, before);
      String message = "已把段落 " + paragraphIndex + " run " + runIndex + " 的样式变更记为 rPrChange";
      ToolResult<Void> result = ToolResult.ok(message);
      return ToolResultRenderer.render(result);
    } catch (RuntimeException e) {
      return renderInvalidArgument(rootMessage(e));
    }
  }

  @ToolDef(
      name = "mark_tracked_cells",
      description =
          "批量把表格若干单元格标记为被插入或被删除的 tracked cell 修订。"
              + "change_type=INSERTED 生成 cellIns(accept=保留、reject=移除);"
              + "change_type=DELETED 生成 cellDel(accept=移除、reject=保留)。"
              + "author 是共享修订作者(必填)。cells 是对象数组,每个对象含 "
              + "table_index(int,表格索引 0 起)、row_index(int,行索引 0 起)、cell_index(int,单元格索引 0 起)。"
              + "单个对象用长度 1 的数组。"
              + "部分失败不中断,返回每条成功/失败明细。"
              + "可选 on_error(continue=失败不中断默认,stop=遇首条失败即停);"
              + "可选 expected_generation 校验文档代次防止旧快照改新状态。")
  @ToolCapability(operation = CapabilityOperation.UPDATE, element = "tracked_change")
  public String markTrackedCells(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "change_type", description = "INSERTED=cellIns,DELETED=cellDel")
          @ParamCapability(
              type = ParamType.ENUM,
              enumValues = {"INSERTED", "DELETED"})
          String changeType,
      @ToolParam(name = "author", description = "修订作者(整批共享)")
          @ParamCapability(type = ParamType.STRING)
          String author,
      @ToolParam(
              name = "cells",
              description =
                  "对象数组,每个对象含 table_index、row_index、cell_index(int),"
                      + "如 [{\"table_index\":0,\"row_index\":0,\"cell_index\":0}]")
          @NestedParamCapability(path = "cells.table_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "cells.row_index", type = ParamType.INTEGER)
          @NestedParamCapability(path = "cells.cell_index", type = ParamType.INTEGER)
          List<Map<String, Object>> cells,
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
    if (changeType == null || changeType.isBlank()) {
      return renderInvalidArgument("change_type 仅支持 INSERTED 或 DELETED");
    }
    if (!checkExpectedGeneration(docId, expectedGeneration)) {
      return renderGenerationMismatch(expectedGeneration, generations.getOrDefault(docId, 1L));
    }
    boolean stopOnError = "stop".equalsIgnoreCase(onError);
    String normalized = changeType.trim().toUpperCase(java.util.Locale.ROOT);
    if ("INSERTED".equals(normalized)
        || "INSERT".equals(normalized)
        || "CELL_INS".equals(normalized)) {
    return markCellsBatch(docId, author, cells, true, stopOnError);
    }
    if ("DELETED".equals(normalized)
        || "DELETE".equals(normalized)
        || "CELL_DEL".equals(normalized)) {
    return markCellsBatch(docId, author, cells, false, stopOnError);
    }
    return renderInvalidArgument("change_type 仅支持 INSERTED 或 DELETED");
  }

  /**
   * 把源段的一个 run 移动到目标段(tracked move 修订,产出配对的 moveFrom/moveTo)。
   *
   * <p>接受方是目标段(与 addInsertion 同类型)。移动后可被 list_tracked_changes 读回为 MOVE_FROM + MOVE_TO。
   */
  @ToolDef(
      name = "move_run_tracked",
      description =
          "把源段(source_paragraph_index)的第 run_index 个 run 移动到目标段(target_paragraph_index),"
              + "产出配对的 tracked move 修订(moveFrom + moveTo)。author 必填。"
              + "可被 list 读回为 MOVE_FROM/MOVE_TO、accept/reject 联动处理。")
  @ToolCapability(operation = CapabilityOperation.UPDATE, element = "tracked_change")
  public String moveRunTracked(
      @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
          String docId,
      @ToolParam(name = "source_paragraph_index", description = "源段索引(0 起)")
          @ParamCapability(type = ParamType.INTEGER)
          int sourceParagraphIndex,
      @ToolParam(name = "run_index", description = "源段中要移动的 run 索引(0 起)")
          @ParamCapability(type = ParamType.INTEGER)
          int runIndex,
      @ToolParam(name = "target_paragraph_index", description = "目标段索引(0 起)")
          @ParamCapability(type = ParamType.INTEGER)
          int targetParagraphIndex,
      @ToolParam(name = "author", description = "修订作者") @ParamCapability(type = ParamType.STRING)
          String author) {
    Document doc = document(docId);
    if (doc == null) {
      return renderDocNotFound(docId);
    }
    List<Paragraph> paragraphs = doc.paragraphs();
    if (outOfBounds(sourceParagraphIndex, paragraphs.size())) {
      return renderIndexError("源段索引", sourceParagraphIndex, paragraphs.size());
    }
    if (outOfBounds(targetParagraphIndex, paragraphs.size())) {
      return renderIndexError("目标段索引", targetParagraphIndex, paragraphs.size());
    }
    var sourceRuns = paragraphs.get(sourceParagraphIndex).runs();
    if (outOfBounds(runIndex, sourceRuns.size())) {
      return renderIndexError("run 索引", runIndex, sourceRuns.size());
    }
    try {
      Run moving = sourceRuns.get(runIndex);
      paragraphs
          .get(targetParagraphIndex)
          .moveRunsFrom(author, paragraphs.get(sourceParagraphIndex), List.of(moving));
      String message =
          "已把段 "
              + sourceParagraphIndex
              + " run "
              + runIndex
              + " 移到段 "
              + targetParagraphIndex
              + "(moveFrom + moveTo 配对)";
      ToolResult<Void> result = ToolResult.ok(message);
      return ToolResultRenderer.render(result);
    } catch (RuntimeException e) {
      return renderInvalidArgument(rootMessage(e));
    }
  }

  // ==================== 组内辅助 ====================

  /**
   * 把批量 collect-errors 的中文摘要渲染为双段格式。
   *
   * <p>有失败项时用 {@code PARTIAL_FAILURE}；全成功用 {@code OK}。 P0-05: 汇总带 matchedCount(=ok+fail)/
   * changedCount(=ok)/skippedCount(stop 模式未执行数)。
   *
   * @param sb 含逐条明细的中文摘要（不含末尾汇总行）
   * @param ok 成功条数
   * @param fail 失败条数
   * @param skipped stop 模式下因前面失败而未执行的条目数（continue 模式传 0）
   */
  private static String renderBatchSummary(StringBuilder sb, int ok, int fail, int skipped) {
    sb.append("\n成功 ").append(ok).append(" 条,失败 ").append(fail).append(" 条");
    int matchedCount = ok + fail;
    ToolResult<Integer> result =
        fail > 0
            ? ToolResult.partial(
                ToolResultCode.PARTIAL_FAILURE,
                ok,
                sb.toString(),
                Collections.emptyList(),
                matchedCount,
                ok,
                skipped > 0 ? skipped : null)
            : ToolResult.ok(ok, sb.toString(), matchedCount, ok, Collections.emptyList());
    return ToolResultRenderer.render(result);
  }

  /**
   * 单元格标记批量执行的共享实现:解析 {@code cells} 坐标 → 逐个 resolveCell → {@code markInserted}/{@code markDeleted}。
   *
   * <p>{@code inserter=true} 调 {@code cell.markInserted}(cellIns),{@code false} 调 {@code
   * cell.markDeleted}(cellDel)。 collect-errors:越界/缺字段记错误不中断。 标记不增删 cell 列表,无索引漂移,无需去重(重复标记同一 cell 由
   * core 自身语义决定,这里不做额外拦截)。
   */
  private String markCellsBatch(
      String docId,
      String author,
      List<Map<String, Object>> cells,
      boolean inserter,
      boolean stopOnError) {
    Document doc = document(docId);
    if (doc == null) {
      return renderDocNotFound(docId);
    }
    List<Object> list = coerceList(cells);
    if (list.isEmpty()) {
      return renderInvalidArgument("cells 为空");
    }
    String tag = inserter ? "cellIns" : "cellDel";
    StringBuilder sb = new StringBuilder();
    int ok = 0;
    int fail = 0;
    int stoppedAt = -1;
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      String prefix = "[" + i + "] ";
      Object item = list.get(i);
      if (!(item instanceof Map)) {
        sb.append(prefix).append("错误:该条不是对象(").append(item).append(")");
        fail++;
        if (stopOnError) {
          stoppedAt = i;
          break;
        }
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) item;
      int tableIndex;
      int rowIndex;
      int cellIndex;
      try {
        tableIndex = getInt(m, "table_index");
        rowIndex = getInt(m, "row_index");
        cellIndex = getInt(m, "cell_index");
      } catch (RuntimeException e) {
        sb.append(prefix).append("错误:").append(e.getMessage());
        fail++;
        if (stopOnError) {
          stoppedAt = i;
          break;
        }
        continue;
      }
      Cell cell = resolveCell(docId, tableIndex, rowIndex, cellIndex);
      if (cell == null) {
        sb.append(prefix).append(cellResolveError(docId, tableIndex, rowIndex, cellIndex));
        fail++;
        if (stopOnError) {
          stoppedAt = i;
          break;
        }
        continue;
      }
      try {
        if (inserter) {
          cell.markInserted(author);
        } else {
          cell.markDeleted(author);
        }
        sb.append(prefix)
            .append("单元格 table[")
            .append(tableIndex)
            .append("].row[")
            .append(rowIndex)
            .append("].cell[")
            .append(cellIndex)
            .append("] 标记为 ")
            .append(tag)
            .append(" ✓");
        ok++;
      } catch (RuntimeException e) {
        sb.append(prefix).append("错误:").append(rootMessage(e));
        fail++;
        if (stopOnError) {
          stoppedAt = i;
          break;
        }
      }
    }
    int skipped = stoppedAt >= 0 ? list.size() - stoppedAt - 1 : 0;
    return renderBatchSummary(sb, ok, fail, skipped);
  }
  /** 兼容旧 Java 调用；等价于 on_error=continue 且未传 expected_generation。 */
  @Deprecated
  public String insertTrackedRun(String docId, String author, List<Map<String, Object>> edits) {
    return insertTrackedRun(docId, author, edits, null, null);
  }

  /** 兼容旧 Java 调用；等价于 on_error=continue 且未传 expected_generation。 */
  @Deprecated
  public String deleteRunTracked(String docId, String author, List<Map<String, Object>> edits) {
    return deleteRunTracked(docId, author, edits, null, null);
  }

  /** 兼容旧 Java 调用；等价于 on_error=continue 且未传 expected_generation。 */
  @Deprecated
  public String replaceRunTracked(String docId, String author, List<Map<String, Object>> edits) {
    return replaceRunTracked(docId, author, edits, null, null);
  }

  /** 兼容旧 Java 调用；等价于未传 expected_generation。 */
  @Deprecated
  public String markStyleChangeTracked(
      String docId,
      int paragraphIndex,
      int runIndex,
      String author,
      boolean bold,
      boolean italic,
      String color) {
    return markStyleChangeTracked(
        docId, paragraphIndex, runIndex, author, bold, italic, color, null);
  }

  /** 兼容旧 Java 调用；等价于 on_error=continue 且未传 expected_generation。 */
  @Deprecated
  public String markTrackedCells(
      String docId, String changeType, String author, List<Map<String, Object>> cells) {
    return markTrackedCells(docId, changeType, author, cells, null, null);
  }
}
