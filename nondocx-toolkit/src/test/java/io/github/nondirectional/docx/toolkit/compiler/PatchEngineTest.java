package io.github.nondirectional.docx.toolkit.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.style.Alignment;
import io.github.nondirectional.docx.core.api.style.HeadingLevel;
import io.github.nondirectional.docx.core.api.style.ListKind;
import io.github.nondirectional.docx.toolkit.compiler.PatchEngine.PatchException;
import io.github.nondirectional.docx.toolkit.compiler.PatchEngine.PatchResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;

/**
 * {@link PatchEngine} 契约测试：各 op 的行为 + 原子性 + dry-run。
 *
 * <p>最重的断言是<b>原子性</b>：preflight 失败时文档必须保持原样（零写入）——这是 edit_document 对模型的承诺，坏了就是「部分应用」的静默错目标回归。
 */
class PatchEngineTest {

  private static final StyleProfile REPORT_CN = StyleProfile.builtin("report-cn");

  @Test
  void insertAfterAnchorWithHeadingParagraphAndList() {
    Document doc = sample();
    try {
      PatchResult result =
          PatchEngine.apply(
              doc,
              ops(
                  op(
                      "op", "insert",
                      "anchor", "p0",
                      "position", "after",
                      "block", "## 新小节 {align=center}\n\n- 待办一\n- 待办二")),
              REPORT_CN,
              false);
      assertThat(result.applied()).isEqualTo(1);
      // p0 后插入 3 个块：H2、两个列表项
      assertThat(doc.paragraph(0).text()).isEqualTo("第一段");
      assertThat(doc.paragraph(1).heading()).isEqualTo(HeadingLevel.H2);
      assertThat(doc.paragraph(1).alignment()).isEqualTo(Alignment.CENTER);
      assertThat(doc.paragraph(2).listKind()).isEqualTo(ListKind.BULLET);
      assertThat(doc.paragraph(3).listKind()).isEqualTo(ListKind.BULLET);
      assertThat(doc.paragraph(4).text()).isEqualTo("第二段");
    } finally {
      doc.close();
    }
  }

  @Test
  void insertAtStartAndEnd() {
    Document doc = sample();
    try {
      PatchEngine.apply(
          doc,
          ops(
              op("op", "insert", "position", "start", "block", "# 文首标题"),
              op("op", "insert", "position", "end", "block", "结尾一段。")),
          REPORT_CN,
          false);
      assertThat(doc.paragraph(0).heading()).isEqualTo(HeadingLevel.H1);
      assertThat(doc.paragraph(0).text()).isEqualTo("文首标题");
      assertThat(doc.paragraph(doc.paragraphs().size() - 1).text()).isEqualTo("结尾一段。");
    } finally {
      doc.close();
    }
  }

  @Test
  void insertTableOnlyAtEnd() {
    Document doc = sample();
    try {
      // 非末尾插表格 → preflight 拒绝
      assertThatThrownBy(
              () ->
                  PatchEngine.apply(
                      doc,
                      ops(
                          op(
                              "op", "insert",
                              "anchor", "p0",
                              "position", "after",
                              "block", "| a | b |\n|---|---|\n| 1 | 2 |")),
                      REPORT_CN,
                      false))
          .isInstanceOf(PatchException.class)
          .hasMessageContaining("表格")
          .hasMessageContaining("end");
      assertThat(doc.tables()).isEmpty();
      // end 插表格 → 成功
      PatchEngine.apply(
          doc,
          ops(
              op(
                  "op", "insert",
                  "position", "end",
                  "block", "| a | b |\n|---|---|\n| 1 | 2 |")),
          REPORT_CN,
          false);
      assertThat(doc.tables()).hasSize(1);
      assertThat(doc.tables().get(0).rows()).hasSize(2);
    } finally {
      doc.close();
    }
  }

  @Test
  void replaceTextInsideSingleRunKeepsRunStyle() {
    Document doc = sample();
    try {
      doc.paragraph(0).run(0).bold();
      PatchEngine.apply(
          doc,
          ops(op("op", "replace_text", "anchor", "p0", "find", "第一", "text", "首要")),
          REPORT_CN,
          false);
      assertThat(doc.paragraph(0).text()).isEqualTo("首要段");
      assertThat(doc.paragraph(0).run(0).isBold()).isTrue();
    } finally {
      doc.close();
    }
  }

  @Test
  void replaceTextAcrossRunsRebuildsWithWarning() {
    Document doc = sample();
    try {
      // 把「第一段」拆成两个 run，find 跨 run
      Paragraph_Without_Helpers.split(doc.paragraph(0));
      PatchResult result =
          PatchEngine.apply(
              doc,
              ops(op("op", "replace_text", "anchor", "p0", "find", "第一段", "text", "新的整段")),
              REPORT_CN,
              false);
      assertThat(doc.paragraph(0).text()).isEqualTo("新的整段");
      assertThat(doc.paragraph(0).runs()).hasSize(1);
      assertThat(result.warnings()).anyMatch(w -> w.contains("跨 run"));
    } finally {
      doc.close();
    }
  }

  @Test
  void deleteRemovesParagraphAndConflictIsRejected() {
    Document doc = sample();
    try {
      // delete p0 后再 replace_text p0 → 前序删除冲突，整体拒绝
      assertThatThrownBy(
              () ->
                  PatchEngine.apply(
                      doc,
                      ops(
                          op("op", "delete", "anchor", "p0"),
                          op(
                              "op", "replace_text",
                              "anchor", "p0",
                              "find", "第一",
                              "text", "x")),
                      REPORT_CN,
                      false))
          .isInstanceOf(PatchException.class)
          .hasMessageContaining("已被本 patch 前序 op 删除");
      assertThat(doc.paragraph(0).text()).isEqualTo("第一段");

      PatchEngine.apply(doc, ops(op("op", "delete", "anchor", "p1")), REPORT_CN, false);
      assertThat(doc.paragraphs()).hasSize(1);
      assertThat(doc.paragraph(0).text()).isEqualTo("第一段");
    } finally {
      doc.close();
    }
  }

  @Test
  void setStyleBodyAppliesProfileFontsSpacingIndent() {
    Document doc = sample();
    try {
      PatchEngine.apply(
          doc, ops(op("op", "set_style", "anchor", "p0", "style", "body")), REPORT_CN, false);
      var paragraph = doc.paragraph(0);
      assertThat(paragraph.lineSpacing()).isEqualTo(1.5);
      assertThat(paragraph.indentationFirstLine()).isEqualTo(480);
      assertThat(paragraph.run(0).font()).isEqualTo("Times New Roman");
      assertThat(eastAsia(paragraph.run(0))).isEqualTo("宋体");
      assertThat(paragraph.run(0).fontSize()).isEqualTo(12);
    } finally {
      doc.close();
    }
  }

  @Test
  void setStyleHeadingAndExplicitOverrides() {
    Document doc = sample();
    try {
      doc.paragraph(0).heading(HeadingLevel.H2);
      PatchEngine.apply(
          doc,
          ops(
              op("op", "set_style", "anchor", "p0", "style", "heading"),
              op("op", "set_style", "anchor", "p1", "align", "center", "line_spacing", 2.0)),
          REPORT_CN,
          false);
      assertThat(doc.paragraph(0).run(0).fontSize()).isEqualTo(14);
      assertThat(eastAsia(doc.paragraph(0).run(0))).isEqualTo("黑体");
      assertThat(doc.paragraph(1).alignment()).isEqualTo(Alignment.CENTER);
      assertThat(doc.paragraph(1).lineSpacing()).isEqualTo(2.0);
    } finally {
      doc.close();
    }
  }

  @Test
  void setStyleBoldItalicApplyToAllRunsAndStackOnBody() {
    Document doc = sample();
    try {
      doc.paragraph(0).addRun("追加");
      PatchEngine.apply(
          doc,
          ops(
              op("op", "set_style", "anchor", "p0", "style", "body", "bold", true),
              op("op", "set_style", "anchor", "p1", "italic", "false")),
          REPORT_CN,
          false);
      // bold 与 style=body 叠加：字体/字号来自 profile，bold 作用于全部 run
      assertThat(doc.paragraph(0).run(0).isBold()).isTrue();
      assertThat(doc.paragraph(0).run(1).isBold()).isTrue();
      assertThat(doc.paragraph(0).run(0).font()).isEqualTo("Times New Roman");
      assertThat(eastAsia(doc.paragraph(0).run(0))).isEqualTo("宋体");
      // italic=false 显式关闭
      assertThat(doc.paragraph(1).run(0).isItalic()).isFalse();
      // body 行距等段落属性仍生效
      assertThat(doc.paragraph(0).lineSpacing()).isEqualTo(1.5);
    } finally {
      doc.close();
    }
  }

  @Test
  void setStyleRejectsNonBooleanBoldAtomically() {
    Document doc = sample();
    try {
      assertThatThrownBy(
              () ->
                  PatchEngine.apply(
                      doc,
                      ops(op("op", "set_style", "anchor", "p0", "bold", "yes")),
                      REPORT_CN,
                      false))
          .isInstanceOf(PatchException.class)
          .hasMessageContaining("bold")
          .hasMessageContaining("布尔");
      assertThat(doc.paragraph(0).run(0).isBold()).isFalse();
    } finally {
      doc.close();
    }
  }

  @Test
  void replaceTextWithBoldMarkerSplitsRunPreservingSurroundingStyle() {
    Document doc = sample();
    try {
      var paragraph = doc.paragraph(0); // 「第一段」
      paragraph.run(0).fontSize(14);
      var ctr = paragraph.run(0).raw().getCTR();
      var rpr = ctr.isSetRPr() ? ctr.getRPr() : ctr.addNewRPr();
      var fonts = rpr.sizeOfRFontsArray() > 0 ? rpr.getRFontsArray(0) : rpr.addNewRFonts();
      fonts.setEastAsia("宋体");

      PatchEngine.apply(
          doc,
          ops(op("op", "replace_text", "anchor", "p0", "find", "第一", "text", "**首要**")),
          REPORT_CN,
          false);

      // 源 run 前缀为空已移除；替换片段 + 后缀各成 run，均继承源样式（字号 + eastAsia 槽）
      assertThat(paragraph.text()).isEqualTo("首要段");
      assertThat(paragraph.runs()).hasSize(2);
      assertThat(paragraph.run(0).text()).isEqualTo("首要");
      assertThat(paragraph.run(0).isBold()).isTrue();
      assertThat(paragraph.run(1).text()).isEqualTo("段");
      assertThat(paragraph.run(1).isBold()).isFalse();
      assertThat(paragraph.run(0).fontSize()).isEqualTo(14);
      assertThat(paragraph.run(1).fontSize()).isEqualTo(14);
      assertThat(eastAsia(paragraph.run(0))).isEqualTo("宋体");
      assertThat(eastAsia(paragraph.run(1))).isEqualTo("宋体");
    } finally {
      doc.close();
    }
  }

  @Test
  void replaceTextWithMarkerAcrossRunsRebuildsAsSpanSequence() {
    Document doc = sample();
    try {
      Paragraph_Without_Helpers.split(doc.paragraph(0)); // 「第一」+「段」
      PatchResult result =
          PatchEngine.apply(
              doc,
              ops(
                  op(
                      "op", "replace_text",
                      "anchor", "p0",
                      "find", "第一段",
                      "text", "**新的**整段")),
              REPORT_CN,
              false);
      assertThat(doc.paragraph(0).text()).isEqualTo("新的整段");
      assertThat(doc.paragraph(0).runs()).hasSize(2);
      assertThat(doc.paragraph(0).run(0).isBold()).isTrue();
      assertThat(doc.paragraph(0).run(1).isBold()).isFalse();
      assertThat(result.warnings()).anyMatch(w -> w.contains("跨 run"));
    } finally {
      doc.close();
    }
  }

  @Test
  void replaceTextFormattedHandlesAllNonOverlappingOccurrences() {
    Document doc = Docx.create();
    try {
      doc.addParagraph("甲乙甲乙甲");
      PatchEngine.apply(
          doc,
          ops(op("op", "replace_text", "anchor", "p0", "find", "甲乙", "text", "**XY**")),
          REPORT_CN,
          false);
      // 与 String.replace 的非重叠语义一致：两处命中 + 尾缀
      assertThat(doc.paragraph(0).text()).isEqualTo("XYXY甲");
      assertThat(doc.paragraph(0).runs()).hasSize(3);
      assertThat(doc.paragraph(0).run(0).isBold()).isTrue();
      assertThat(doc.paragraph(0).run(1).isBold()).isTrue();
      assertThat(doc.paragraph(0).run(2).isBold()).isFalse();
      assertThat(doc.paragraph(0).run(2).text()).isEqualTo("甲");
    } finally {
      doc.close();
    }
  }

  @Test
  void replaceTextUnmatchedMarkerStaysLiteralSingleRun() {
    Document doc = sample();
    try {
      // 未闭合的单个 * 不是标记：按纯文本原位替换
      PatchEngine.apply(
          doc,
          ops(op("op", "replace_text", "anchor", "p0", "find", "第一", "text", "3*4")),
          REPORT_CN,
          false);
      assertThat(doc.paragraph(0).text()).isEqualTo("3*4段");
      assertThat(doc.paragraph(0).runs()).hasSize(1);
      assertThat(doc.paragraph(0).run(0).isItalic()).isFalse();
    } finally {
      doc.close();
    }
  }

  @Test
  void replaceTextFormattedSurvivesSaveAndReopen(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("formatted.docx");
    Document doc = sample();
    try {
      doc.paragraph(0).run(0).fontSize(14);
      PatchEngine.apply(
          doc,
          ops(op("op", "replace_text", "anchor", "p0", "find", "第一", "text", "**首要**")),
          REPORT_CN,
          false);
      doc.save(file);
    } finally {
      doc.close();
    }
    try (Document reopened = Docx.open(file)) {
      // save+reopen 才能暴露「拷贝写进了非树对象」这类 POI 缓存陷阱
      assertThat(reopened.paragraph(0).text()).isEqualTo("首要段");
      assertThat(reopened.paragraph(0).run(0).text()).isEqualTo("首要");
      assertThat(reopened.paragraph(0).run(0).isBold()).isTrue();
      assertThat(reopened.paragraph(0).run(0).fontSize()).isEqualTo(14);
      assertThat(reopened.paragraph(0).run(1).text()).isEqualTo("段");
      assertThat(reopened.paragraph(0).run(1).isBold()).isFalse();
    }
  }

  @Test
  void tableOpsSetCellAndAddRow() {
    Document doc = sample();
    try {
      PatchEngine.apply(
          doc,
          ops(
              op(
                  "op", "insert",
                  "position", "end",
                  "block", "| 模块 | 状态 |\n|---|---|\n| core | 进行中 |")),
          REPORT_CN,
          false);
      // t0 短 ID 寻址
      PatchEngine.apply(
          doc,
          ops(
              op(
                  "op", "table.set_cell",
                  "anchor", "t0",
                  "row", 1,
                  "col", 1,
                  "text", "已完成"),
              op("op", "table.add_row", "anchor", "t0", "cells", List.of("toolkit", "进行中"))),
          REPORT_CN,
          false);
      var table = doc.tables().get(0);
      assertThat(table.rows()).hasSize(3);
      assertThat(table.rows().get(1).cells().get(1).text()).isEqualTo("已完成");
      assertThat(table.rows().get(2).cells().get(0).text()).isEqualTo("toolkit");
      assertThat(table.rows().get(2).cantSplit()).isTrue();

      // set_cell 的 text 支持行内标记：**加粗** → 文本剥标记 + 加粗 run
      PatchEngine.apply(
          doc,
          ops(op("op", "table.set_cell", "anchor", "t0", "row", 1, "col", 0, "text", "**核心**")),
          REPORT_CN,
          false);
      var styled = doc.tables().get(0).rows().get(1).cells().get(0);
      assertThat(styled.text()).isEqualTo("核心");
      assertThat(styled.paragraph(0).run(0).isBold()).isTrue();
    } finally {
      doc.close();
    }
  }

  @Test
  void atomicPreflightFailureLeavesDocumentUntouched() {
    Document doc = sample();
    try {
      String before = doc.paragraph(1).text();
      assertThatThrownBy(
              () ->
                  PatchEngine.apply(
                      doc,
                      ops(
                          // op[0] 合法（会被应用）
                          op("op", "insert", "position", "end", "block", "追加段。"),
                          // op[1] 非法：锚点不存在
                          op("op", "set_style", "anchor", "p99", "style", "body"),
                          // op[2] 非法：find 不在段落中
                          op(
                              "op", "replace_text",
                              "anchor", "p0",
                              "find", "不存在的内容",
                              "text", "x")),
                      REPORT_CN,
                      false))
          .isInstanceOf(PatchException.class);
      // 零写入：段落数与文本保持原样
      assertThat(doc.paragraphs()).hasSize(2);
      assertThat(doc.paragraph(1).text()).isEqualTo(before);
      assertThat(doc.paragraph(0).text()).isEqualTo("第一段");
    } finally {
      doc.close();
    }
  }

  @Test
  void dryRunReturnsPlanWithoutWriting() {
    Document doc = sample();
    try {
      PatchResult result =
          PatchEngine.apply(
              doc,
              ops(
                  op("op", "insert", "position", "end", "block", "追加段。"),
                  op("op", "set_style", "anchor", "p0", "style", "body")),
              REPORT_CN,
              true);
      assertThat(result.dryRun()).isTrue();
      assertThat(result.planned()).hasSize(2);
      assertThat(result.planned().get(0)).contains("insert");
      // 未写入
      assertThat(doc.paragraphs()).hasSize(2);
      assertThat(doc.paragraph(0).lineSpacing()).isLessThanOrEqualTo(0);
      // dry-run 也会做 preflight：非法 op 同样整体报错
      assertThatThrownBy(
              () ->
                  PatchEngine.apply(doc, ops(op("op", "delete", "anchor", "p9")), REPORT_CN, true))
          .isInstanceOf(PatchException.class);
    } finally {
      doc.close();
    }
  }

  @Test
  void emptyOpsAndUnknownOpAreRejected() {
    Document doc = sample();
    try {
      assertThatThrownBy(() -> PatchEngine.apply(doc, List.of(), REPORT_CN, false))
          .isInstanceOf(PatchException.class)
          .hasMessageContaining("ops 为空");
      assertThatThrownBy(
              () ->
                  PatchEngine.apply(doc, ops(op("op", "rename", "anchor", "p0")), REPORT_CN, false))
          .isInstanceOf(PatchException.class)
          .hasMessageContaining("未知操作类型");
    } finally {
      doc.close();
    }
  }

  // ==================== tracked.* op（v2） ====================

  @Test
  void trackedReplaceCreatesMinimalDiffRevisions() throws Exception {
    java.nio.file.Path file = java.nio.file.Files.createTempFile("tracked-replace", ".docx");
    Document doc = Docx.create();
    try {
      doc.addParagraph("正文");
      doc.addParagraph("合同金额为一百万元。");
      PatchResult result =
          PatchEngine.apply(
              doc,
              ops(
                  op(
                      "op", "tracked.replace",
                      "anchor", "p1",
                      "find", "一百万元",
                      "text", "两百万元",
                      "author", "审查员")),
              REPORT_CN,
              false);
      assertThat(result.applied()).isEqualTo(1);
      assertThat(result.changed()).containsExactly("p1");
      // 最小 diff：只 del「一百万元」+ ins 紧随其后，两句修订
      assertThat(doc.trackedChanges().list()).hasSize(2);
      assertThat(doc.trackedChanges().list()).allMatch(c -> "审查员".equals(c.author()));
      doc.save(file);
    } finally {
      doc.close();
    }
    try (Document opened = Docx.open(file)) {
      // accept 后正文完成替换（accept/reject 改动 POI 缓存之下，按惯例 save+reopen 再读）
      assertThat(opened.trackedChanges().acceptAll()).isEqualTo(2);
      java.nio.file.Path accepted = java.nio.file.Path.of(file + ".accepted.docx");
      opened.save(accepted);
      try (Document result = Docx.open(accepted)) {
        assertThat(result.paragraph(1).text()).isEqualTo("合同金额为两百万元。");
      }
    }
  }

  @Test
  void trackedReplaceWithInlineFormatCreatesOneInsWithStyledRuns() throws Exception {
    java.nio.file.Path file = java.nio.file.Files.createTempFile("tracked-fmt-replace", ".docx");
    Document doc = Docx.create();
    try {
      doc.addParagraph("结论：待定。");
      PatchResult result =
          PatchEngine.apply(
              doc,
              ops(
                  op(
                      "op", "tracked.replace",
                      "anchor", "p0",
                      "find", "待定",
                      "text", "**通过**（修订版）",
                      "author", "审查员")),
              REPORT_CN,
              false);
      assertThat(result.applied()).isEqualTo(1);
      // del + 一条 ins（读侧聚合，多 run 文本拼接）；无字面星号
      var changes = doc.trackedChanges().list();
      assertThat(changes).hasSize(2);
      var ins =
          changes.stream()
              .filter(
                  c ->
                      c.type()
                          == io.github.nondirectional.docx.core.api.track.TrackedChangeType.INS)
              .findFirst()
              .orElseThrow();
      assertThat(
              ((io.github.nondirectional.docx.core.api.track.TextChangeDetails) ins.details())
                  .text())
          .isEqualTo("通过（修订版）");
      // ins 内两个 run：首个粗体，次个无格式（源 run 无 rPr，plain span 不产生 rPr）
      var rList = ins.raw().getRList();
      assertThat(rList).hasSize(2);
      assertThat(rList.get(0).getRPr().sizeOfBArray()).isEqualTo(1);
      assertThat(rList.get(1).isSetRPr() ? rList.get(1).getRPr().sizeOfBArray() : 0).isZero();
      doc.save(file);
    } finally {
      doc.close();
    }
    try (Document opened = Docx.open(file)) {
      // save+reopen 后格式落盘；accept 后正文完成带格式替换
      var ins =
          opened.trackedChanges().list().stream()
              .filter(
                  c ->
                      c.type()
                          == io.github.nondirectional.docx.core.api.track.TrackedChangeType.INS)
              .findFirst()
              .orElseThrow();
      assertThat(ins.raw().getRList().get(0).getRPr().sizeOfBArray()).isEqualTo(1);
      assertThat(opened.trackedChanges().acceptAll()).isEqualTo(2);
      java.nio.file.Path accepted = java.nio.file.Path.of(file + ".accepted.docx");
      opened.save(accepted);
      try (Document result = Docx.open(accepted)) {
        assertThat(result.paragraph(0).text()).isEqualTo("结论：通过（修订版）。");
        // accept 拆掉 ins 包装后，加粗 run 进入普通 runs() 视图
        assertThat(result.paragraph(0).run(1).isBold()).isTrue();
        assertThat(result.paragraph(0).run(2).isBold()).isFalse();
      }
    }
  }

  @Test
  void trackedInsertWithInlineFormatStylesRunsInOneRevision() {
    Document doc = Docx.create();
    try {
      doc.addParagraph("正文");
      PatchResult result =
          PatchEngine.apply(
              doc,
              ops(
                  op(
                      "op", "tracked.insert",
                      "anchor", "p0",
                      "text", "**要点**说明",
                      "author", "审查员")),
              REPORT_CN,
              false);
      assertThat(result.applied()).isEqualTo(1);
      // 一条 INS 修订（不是每个 span 一条）
      var changes = doc.trackedChanges().list();
      assertThat(changes).hasSize(1);
      assertThat(changes.get(0).type())
          .isEqualTo(io.github.nondirectional.docx.core.api.track.TrackedChangeType.INS);
      var rList = changes.get(0).raw().getRList();
      assertThat(rList).hasSize(2);
      assertThat(rList.get(0).getRPr().sizeOfBArray()).isEqualTo(1);
      assertThat(rList.get(1).isSetRPr() ? rList.get(1).getRPr().sizeOfBArray() : 0).isZero();
    } finally {
      doc.close();
    }
  }

  @Test
  void trackedReplaceAllOccurrencesAndTrackedInsertDelete() throws Exception {
    java.nio.file.Path file = java.nio.file.Files.createTempFile("tracked-multi", ".docx");
    Document doc = Docx.create();
    try {
      doc.addParagraph("TODO 甲，TODO 乙。");
      PatchEngine.apply(
          doc,
          ops(
              op(
                  "op",
                  "tracked.replace",
                  "anchor",
                  "p0",
                  "find",
                  "TODO",
                  "text",
                  "完成",
                  "author",
                  "a")),
          REPORT_CN,
          false);
      // 两处命中 → 2 del + 2 ins
      assertThat(doc.trackedChanges().list()).hasSize(4);
      doc.save(file);
    } finally {
      doc.close();
    }
    try (Document opened = Docx.open(file)) {
      assertThat(opened.trackedChanges().acceptAll()).isEqualTo(4);
      java.nio.file.Path accepted = java.nio.file.Path.of(file + ".accepted.docx");
      opened.save(accepted);
      try (Document result = Docx.open(accepted)) {
        assertThat(result.paragraph(0).text()).isEqualTo("完成 甲，完成 乙。");
      }
    }

    // tracked.insert：段末追加 ins 修订；tracked.delete：find 定位删除
    Document second = Docx.create();
    try {
      second.addParagraph("段落。");
      second.addParagraph("删除我：目标文本。");
      PatchEngine.apply(
          second,
          ops(
              op("op", "tracked.insert", "anchor", "p0", "text", "（补）", "author", "a"),
              op("op", "tracked.delete", "anchor", "p1", "find", "目标文本", "author", "a")),
          REPORT_CN,
          false);
      assertThat(second.trackedChanges().list()).hasSize(2);
      assertThat(second.trackedChanges().list().get(0).author()).isEqualTo("a");
      // del 已从 text() 移除（POI 盲区：ins 不可见、del 移除）
      assertThat(second.paragraph(1).text()).isEqualTo("删除我：。");
    } finally {
      second.close();
    }
  }

  @Test
  void sameAnchorTextOpsAreExclusivelyClaimed() {
    Document doc = sample();
    try {
      // replace_text 与 tracked.replace 同锚点 → 整体拒绝、零写入
      assertThatThrownBy(
              () ->
                  PatchEngine.apply(
                      doc,
                      ops(
                          op("op", "replace_text", "anchor", "p0", "find", "第一", "text", "x"),
                          op(
                              "op", "tracked.replace",
                              "anchor", "p0",
                              "find", "第一",
                              "text", "y",
                              "author", "a")),
                      REPORT_CN,
                      false))
          .isInstanceOf(PatchException.class)
          .hasMessageContaining("至多一个改文本 op");
      assertThat(doc.paragraph(0).text()).isEqualTo("第一段");
      assertThat(doc.trackedChanges().list()).isEmpty();
      // 不同锚点的 tracked ops 可同批
      PatchEngine.apply(
          doc,
          ops(
              op(
                  "op",
                  "tracked.replace",
                  "anchor",
                  "p0",
                  "find",
                  "第一",
                  "text",
                  "首要",
                  "author",
                  "a"),
              op("op", "tracked.insert", "anchor", "p1", "text", "尾", "author", "a")),
          REPORT_CN,
          false);
      assertThat(doc.trackedChanges().list()).hasSize(3);
    } finally {
      doc.close();
    }
  }

  @Test
  void trackedPreflightValidatesFindAndAuthorAtomically() {
    Document doc = sample();
    try {
      assertThatThrownBy(
              () ->
                  PatchEngine.apply(
                      doc,
                      ops(
                          op(
                              "op",
                              "tracked.replace",
                              "anchor",
                              "p0",
                              "find",
                              "没有的",
                              "text",
                              "x",
                              "author",
                              "a")),
                      REPORT_CN,
                      false))
          .isInstanceOf(PatchException.class)
          .hasMessageContaining("找不到");
      assertThatThrownBy(
              () ->
                  PatchEngine.apply(
                      doc,
                      ops(op("op", "tracked.delete", "anchor", "p0", "find", "第一")),
                      REPORT_CN,
                      false))
          .isInstanceOf(PatchException.class)
          .hasMessageContaining("author");
      assertThat(doc.trackedChanges().list()).isEmpty();
      assertThat(doc.paragraph(0).text()).isEqualTo("第一段");
    } finally {
      doc.close();
    }
  }

  // ==================== tracked.set_style op（v5） ====================

  @Test
  void trackedSetStyleBoldsPhraseInsideSharedRunAndKeepsText() throws Exception {
    java.nio.file.Path file = java.nio.file.Files.createTempFile("tracked-set-style", ".docx");
    Document doc = Docx.create();
    try {
      // 目标词与邻文同 run：必须先拆 run 再只对目标 run 提交 rPrChange
      doc.addParagraph("本月风险指标：逾期率 3.2%，环比上升 0.4 个百分点。");
      PatchResult result =
          PatchEngine.apply(
              doc,
              ops(
                  op(
                      "op",
                      "tracked.set_style",
                      "anchor",
                      "p0",
                      "find",
                      "逾期率",
                      "bold",
                      true,
                      "author",
                      "审查员")),
              REPORT_CN,
              false);
      assertThat(result.applied()).isEqualTo(1);
      assertThat(result.changed()).containsExactly("p0");
      doc.save(file);
    } finally {
      doc.close();
    }
    try (Document opened = Docx.open(file)) {
      // save+reopen：恰好 1 条 RPR_CHANGE，author 正确，newSummary 含 b、oldSummary 不含 b
      var changes = opened.trackedChanges().list();
      assertThat(changes).hasSize(1);
      var change = changes.get(0);
      assertThat(change.type())
          .isEqualTo(io.github.nondirectional.docx.core.api.track.TrackedChangeType.RPR_CHANGE);
      assertThat(change.author()).isEqualTo("审查员");
      var details =
          (io.github.nondirectional.docx.core.api.track.PropertyChangeDetails) change.details();
      assertThat(details.newSummary()).contains("b");
      assertThat(details.oldSummary()).doesNotContain("b");
      // 目标词加粗、邻文不加粗（严格子集）、段落文本不变
      io.github.nondirectional.docx.core.api.text.Paragraph p = opened.paragraph(0);
      assertThat(p.text()).isEqualTo("本月风险指标：逾期率 3.2%，环比上升 0.4 个百分点。");
      boolean targetBold = false;
      boolean neighborPlain = false;
      for (var run : p.runs()) {
        if (run.text().contains("逾期率")) {
          targetBold = run.isBold();
        } else if (!run.text().isBlank()) {
          assertThat(run.isBold()).as("run「%s」不应被加粗", run.text()).isFalse();
          neighborPlain = true;
        }
      }
      assertThat(targetBold).as("含「逾期率」的 run 应为粗体").isTrue();
      assertThat(neighborPlain).as("段内应有未加粗的邻文 run（严格子集，防整段加粗退化）").isTrue();
    }
  }

  @Test
  void trackedSetStyleAcceptKeepsBoldAndRejectRestores() throws Exception {
    // accept：样式保留、修订消失；reject：还原旧样式（accept/reject 在 POI 缓存之下改动，一律 save+reopen 再断言）
    java.nio.file.Path file = java.nio.file.Files.createTempFile("tracked-style-accept", ".docx");
    Document doc = Docx.create();
    try {
      doc.addParagraph("本月风险指标：逾期率 3.2%。");
      PatchEngine.apply(
          doc,
          ops(
              op(
                  "op",
                  "tracked.set_style",
                  "anchor",
                  "p0",
                  "find",
                  "逾期率",
                  "bold",
                  true,
                  "author",
                  "审查员")),
          REPORT_CN,
          false);
      doc.save(file);
    } finally {
      doc.close();
    }
    // accept 路径：删 rPrChange 标记，新值（加粗）生效
    try (Document opened = Docx.open(file)) {
      String id = opened.trackedChanges().list().get(0).id();
      opened.trackedChanges().acceptProperty(id);
      java.nio.file.Path accepted = java.nio.file.Path.of(file + ".accepted.docx");
      opened.save(accepted);
      try (Document result = Docx.open(accepted)) {
        assertThat(result.trackedChanges().list()).isEmpty();
        assertThat(boldOfRunContaining(result.paragraph(0), "逾期率")).isTrue();
      }
    }
    // reject 路径：旧值树整树覆盖（reject 后 POI 句柄断连，save+reopen 读）
    try (Document opened = Docx.open(file)) {
      String id = opened.trackedChanges().list().get(0).id();
      opened.trackedChanges().rejectProperty(id);
      java.nio.file.Path rejected = java.nio.file.Path.of(file + ".rejected.docx");
      opened.save(rejected);
      try (Document result = Docx.open(rejected)) {
        assertThat(result.trackedChanges().list()).isEmpty();
        assertThat(boldOfRunContaining(result.paragraph(0), "逾期率")).isFalse();
        assertThat(result.paragraph(0).text()).isEqualTo("本月风险指标：逾期率 3.2%。");
      }
    }
  }

  @Test
  void trackedSetStylePreflightRejectsMissingFormatFindMissAndAnchorConflict() {
    Document doc = sample();
    try {
      // bold/italic 均缺省 → 拒绝并就地教学正确写法
      assertThatThrownBy(
              () ->
                  PatchEngine.apply(
                      doc,
                      ops(
                          op(
                              "op",
                              "tracked.set_style",
                              "anchor",
                              "p0",
                              "find",
                              "第一",
                              "author",
                              "a")),
                      REPORT_CN,
                      false))
          .isInstanceOf(PatchException.class)
          .hasMessageContaining("至少提供 bold 或 italic")
          .hasMessageContaining("bold");
      // find 未命中 → 拒绝并教学正确写法
      assertThatThrownBy(
              () ->
                  PatchEngine.apply(
                      doc,
                      ops(
                          op(
                              "op",
                              "tracked.set_style",
                              "anchor",
                              "p0",
                              "find",
                              "没有的",
                              "bold",
                              true,
                              "author",
                              "a")),
                      REPORT_CN,
                      false))
          .isInstanceOf(PatchException.class)
          .hasMessageContaining("找不到")
          .hasMessageContaining("正确写法");
      // 同锚点与 replace_text 同批 → 整体拒绝提示拆批（拆 run 重建 run 结构，纳入独占）
      assertThatThrownBy(
              () ->
                  PatchEngine.apply(
                      doc,
                      ops(
                          op("op", "replace_text", "anchor", "p0", "find", "第一", "text", "x"),
                          op(
                              "op",
                              "tracked.set_style",
                              "anchor",
                              "p0",
                              "find",
                              "第一",
                              "bold",
                              true,
                              "author",
                              "a")),
                      REPORT_CN,
                      false))
          .isInstanceOf(PatchException.class)
          .hasMessageContaining("至多一个改文本 op");
      // 原子性：零写入（无修订、未拆 run、文本未动）
      assertThat(doc.paragraph(0).text()).isEqualTo("第一段");
      assertThat(doc.paragraph(0).runs()).hasSize(1);
      assertThat(doc.trackedChanges().list()).isEmpty();
    } finally {
      doc.close();
    }
  }

  @Test
  void trackedSetStyleHandlesMultipleOccurrencesAndItalicOff() throws Exception {
    java.nio.file.Path file = java.nio.file.Files.createTempFile("tracked-style-multi", ".docx");
    Document doc = Docx.create();
    try {
      doc.addParagraph("关键 甲，关键 乙。"); // 单 run 两处命中 → 全部处理
      doc.addParagraph("重点倾斜文字。"); // 斜体 run，取消其中「倾斜」的斜体
      doc.paragraph(1).run(0).italic();
      PatchResult result =
          PatchEngine.apply(
              doc,
              ops(
                  op(
                      "op",
                      "tracked.set_style",
                      "anchor",
                      "p0",
                      "find",
                      "关键",
                      "bold",
                      true,
                      "author",
                      "审查员"),
                  op(
                      "op",
                      "tracked.set_style",
                      "anchor",
                      "p1",
                      "find",
                      "倾斜",
                      "italic",
                      false,
                      "author",
                      "审查员")),
              REPORT_CN,
              false);
      assertThat(result.applied()).isEqualTo(2);
      // 本 op 不改文本（拆 run 保持拼接文本不变）
      assertThat(doc.paragraph(0).text()).isEqualTo("关键 甲，关键 乙。");
      assertThat(doc.paragraph(1).text()).isEqualTo("重点倾斜文字。");
      doc.save(file);
    } finally {
      doc.close();
    }
    try (Document opened = Docx.open(file)) {
      // 两处命中 → 2 条 + 取消斜体 1 条 = 3 条 RPR_CHANGE（无 DEL/INS——不是文本替换）
      var changes = opened.trackedChanges().list();
      assertThat(changes).hasSize(3);
      assertThat(changes)
          .allMatch(
              c ->
                  c.type()
                      == io.github.nondirectional.docx.core.api.track.TrackedChangeType.RPR_CHANGE);
      int boldHits = 0;
      for (var run : opened.paragraph(0).runs()) {
        if (run.text().contains("关键")) {
          assertThat(run.isBold()).as("run「%s」应为粗体", run.text()).isTrue();
          boldHits++;
        } else if (!run.text().isBlank()) {
          assertThat(run.isBold()).as("run「%s」不应被加粗", run.text()).isFalse();
        }
      }
      assertThat(boldHits).isEqualTo(2);
      // italic=false 写 w:val="off"：目标 run 取消斜体且仍有 RPR_CHANGE；邻文斜体保留
      assertThat(italicOfRunContaining(opened.paragraph(1), "倾斜")).isFalse();
      assertThat(italicOfRunContaining(opened.paragraph(1), "重点")).isTrue();
    }
  }

  private static boolean boldOfRunContaining(
      io.github.nondirectional.docx.core.api.text.Paragraph paragraph, String needle) {
    for (var run : paragraph.runs()) {
      if (run.text().contains(needle)) {
        return run.isBold();
      }
    }
    throw new IllegalStateException("未找到含「" + needle + "」的 run");
  }

  private static boolean italicOfRunContaining(
      io.github.nondirectional.docx.core.api.text.Paragraph paragraph, String needle) {
    for (var run : paragraph.runs()) {
      if (run.text().contains(needle)) {
        return run.isItalic();
      }
    }
    throw new IllegalStateException("未找到含「" + needle + "」的 run");
  }

  // ==================== footer.set_page_number op（v2） ====================

  @Test
  void footerPageNumberAppendsCachedFieldReadableByPoi() {
    Document doc = Docx.create();
    try {
      doc.addParagraph("正文");
      doc.ensureFooter().addParagraph().addRun("页脚");
      int paragraphsBefore = doc.paragraphs().size();
      PatchResult result =
          PatchEngine.apply(doc, ops(op("op", "footer.set_page_number")), REPORT_CN, false);
      assertThat(result.changed()).containsExactly("footer");
      // 正文不动
      assertThat(doc.paragraphs()).hasSize(paragraphsBefore);
      assertThat(doc.paragraph(0).text()).isEqualTo("正文");
      // POI 文本读侧能读到「第 1 页」（带缓存结果的域）
      String footerText = doc.footer().text();
      assertThat(footerText).contains("第").contains("1");
      assertThat(footerText).doesNotContain("{page}");
      // 域指令确实写入（PAGE）
      boolean hasPageInstr = false;
      for (var run : doc.footer().paragraph(1).raw().getRuns()) {
        if (run.getCTR().sizeOfInstrTextArray() > 0
            && run.getCTR().getInstrTextArray(0).getStringValue().contains("PAGE")) {
          hasPageInstr = true;
        }
      }
      assertThat(hasPageInstr).isTrue();
    } finally {
      doc.close();
    }
  }

  @Test
  void footerPageNumberCustomFormatAndValidation() {
    Document doc = Docx.create();
    try {
      // 自定义 format：PAGE + NUMPAGES 双域
      PatchEngine.apply(
          doc,
          ops(op("op", "footer.set_page_number", "format", "第 {page} 页 / 共 {pages} 页")),
          REPORT_CN,
          false);
      assertThat(doc.footer().text()).contains("第 1 页 / 共 1 页");
      // 无占位符 / 未知占位符 → 拒绝且不创建页脚段落
      assertThatThrownBy(
              () ->
                  PatchEngine.apply(
                      doc,
                      ops(op("op", "footer.set_page_number", "format", "第几页")),
                      REPORT_CN,
                      false))
          .isInstanceOf(PatchException.class)
          .hasMessageContaining("{page}");
      assertThatThrownBy(
              () ->
                  PatchEngine.apply(
                      doc,
                      ops(op("op", "footer.set_page_number", "format", "第 {count} 页")),
                      REPORT_CN,
                      false))
          .isInstanceOf(PatchException.class)
          .hasMessageContaining("{count}");
      assertThat(doc.footer().paragraphs()).hasSize(1);
      // 页脚 op 可与正文 op 同批（不占锚点）
      doc.addParagraph("正文段");
      PatchEngine.apply(
          doc,
          ops(
              op("op", "footer.set_page_number"),
              op("op", "replace_text", "anchor", "p0", "find", "正文", "text", "改写")),
          REPORT_CN,
          false);
      assertThat(doc.paragraph(0).text()).isEqualTo("改写段");
      assertThat(doc.footer().paragraphs()).hasSize(2);
    } finally {
      doc.close();
    }
  }

  // ==================== header.set_text op（v4） ====================

  @Test
  void headerSetTextAppendsParagraphWithInlineBoldRun() {
    Document doc = sample();
    try {
      int paragraphsBefore = doc.paragraphs().size();
      PatchResult result =
          PatchEngine.apply(
              doc, ops(op("op", "header.set_text", "text", "**机密** 内部文档")), REPORT_CN, false);
      assertThat(result.changed()).containsExactly("header");
      // 正文完全不动（写入目标在正文之外，不占段落锚点）
      assertThat(doc.paragraphs()).hasSize(paragraphsBefore);
      assertThat(doc.paragraph(0).text()).isEqualTo("第一段");
      // 页眉文本 = 剥标记后的拼接内容（Header.text() 多段落以换行拼接，此处用 contains）
      assertThat(doc.header().text()).contains("机密 内部文档").doesNotContain("**");
      // **粗体** 落 run：首 run 加粗，其余 run 非粗体
      assertThat(doc.header().paragraph(0).run(0).isBold()).isTrue();
      assertThat(doc.header().paragraph(0).run(0).text()).isEqualTo("机密");
      assertThat(doc.header().paragraph(0).run(1).isBold()).isFalse();
      assertThat(doc.header().paragraph(0).run(1).text()).isEqualTo(" 内部文档");
    } finally {
      doc.close();
    }
  }

  @Test
  void headerSetTextValidationAppendSemanticsAndBatchCoexistence() {
    Document doc = sample();
    try {
      // text 缺失 / 空白 → preflight 拒绝且零写入（seed 无页眉，拒绝后仍无页眉）
      assertThatThrownBy(
              () -> PatchEngine.apply(doc, ops(op("op", "header.set_text")), REPORT_CN, false))
          .isInstanceOf(PatchException.class)
          .hasMessageContaining("text");
      assertThatThrownBy(
              () ->
                  PatchEngine.apply(
                      doc, ops(op("op", "header.set_text", "text", "   ")), REPORT_CN, false))
          .isInstanceOf(PatchException.class)
          .hasMessageContaining("空白");
      assertThat(doc.header()).isNull();
      assertThat(doc.paragraphs()).hasSize(2);
      // 追加语义：两次调用页眉追加两段（不替换既有段）
      PatchEngine.apply(doc, ops(op("op", "header.set_text", "text", "第一行")), REPORT_CN, false);
      // 页眉 op 可与正文 op 同批（不占锚点，replace_text 不受影响）
      PatchEngine.apply(
          doc,
          ops(
              op("op", "header.set_text", "text", "第二行"),
              op("op", "replace_text", "anchor", "p0", "find", "第一段", "text", "改写")),
          REPORT_CN,
          false);
      assertThat(doc.header().paragraphs()).hasSize(2);
      assertThat(doc.header().paragraph(0).text()).isEqualTo("第一行");
      assertThat(doc.header().paragraph(1).text()).isEqualTo("第二行");
      assertThat(doc.paragraph(0).text()).isEqualTo("改写");
    } finally {
      doc.close();
    }
  }

  // ==================== comment.add op（v3） ====================

  @Test
  void commentAddCreatesWholeParagraphCommentReadableBack() {
    Document doc = sample();
    try {
      PatchResult result =
          PatchEngine.apply(
              doc,
              ops(
                  op(
                      "op", "comment.add",
                      "anchor", "p1",
                      "text", "这段需要补充依据",
                      "author", "审查员")),
              REPORT_CN,
              false);
      assertThat(result.changed()).containsExactly("p1");
      // 文档级读回：1 条，author/text 正确
      assertThat(doc.comments().list()).hasSize(1);
      assertThat(doc.comments().list().get(0).author()).isEqualTo("审查员");
      assertThat(doc.comments().list().get(0).text()).isEqualTo("这段需要补充依据");
      // comment→paragraph 映射：挂在 p1，不挂 p0
      assertThat(doc.paragraph(0).comments()).isEmpty();
      assertThat(doc.paragraph(1).comments()).hasSize(1);
      // 批注不改正文
      assertThat(doc.paragraph(1).text()).isEqualTo("第二段");
    } finally {
      doc.close();
    }
  }

  @Test
  void commentAddValidatesAnchorTextAuthorAtomically() {
    Document doc = sample();
    try {
      // author/text 必填、锚点必须存在
      assertThatThrownBy(
              () ->
                  PatchEngine.apply(
                      doc,
                      ops(op("op", "comment.add", "anchor", "p0", "text", "批注")),
                      REPORT_CN,
                      false))
          .isInstanceOf(PatchException.class)
          .hasMessageContaining("author");
      assertThatThrownBy(
              () ->
                  PatchEngine.apply(
                      doc,
                      ops(op("op", "comment.add", "anchor", "p0", "author", "甲")),
                      REPORT_CN,
                      false))
          .isInstanceOf(PatchException.class)
          .hasMessageContaining("text");
      assertThatThrownBy(
              () ->
                  PatchEngine.apply(
                      doc,
                      ops(op("op", "comment.add", "anchor", "p9", "text", "批注", "author", "甲")),
                      REPORT_CN,
                      false))
          .isInstanceOf(PatchException.class)
          .hasMessageContaining("p9");
      // 原子性：同批第二条非法 → 第一条也不写入（零批注）
      assertThatThrownBy(
              () ->
                  PatchEngine.apply(
                      doc,
                      ops(
                          op("op", "comment.add", "anchor", "p0", "text", "好", "author", "甲"),
                          op("op", "comment.add", "anchor", "p8", "text", "坏", "author", "乙")),
                      REPORT_CN,
                      false))
          .isInstanceOf(PatchException.class)
          .hasMessageContaining("p8");
      assertThat(doc.comments().list()).isEmpty();
    } finally {
      doc.close();
    }
  }

  @Test
  void commentAddSharesBatchWithReplaceTextOnSameAnchor() {
    Document doc = sample();
    try {
      // comment.add 不改段落文本，不受同锚点改文本独占约束
      PatchEngine.apply(
          doc,
          ops(
              op("op", "replace_text", "anchor", "p0", "find", "第一段", "text", "首段"),
              op("op", "comment.add", "anchor", "p0", "text", "已改写", "author", "审查员")),
          REPORT_CN,
          false);
      assertThat(doc.paragraph(0).text()).isEqualTo("首段");
      assertThat(doc.paragraph(0).comments()).hasSize(1);
      assertThat(doc.paragraph(0).comments().get(0).text()).isEqualTo("已改写");
    } finally {
      doc.close();
    }
  }

  // ==================== comment.reply op（v6） ====================

  @Test
  void commentReplyCreatesThreadedReplyOnRootComment(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("comment-reply.docx");
    Document doc = sample();
    try {
      String rootId = doc.paragraph(1).addComment("审查员", "请核实该预算金额的数据来源。").id();
      // comment_id 容忍读侧复制过来的前导「#」
      PatchResult result =
          PatchEngine.apply(
              doc,
              ops(
                  op(
                      "op", "comment.reply",
                      "comment_id", "#" + rootId,
                      "text", "同意，请补充上季度数据。",
                      "author", "复核员")),
              REPORT_CN,
              false);
      assertThat(result.applied()).isEqualTo(1);
      assertThat(result.changed()).containsExactly("comment#" + rootId);
      // 线程关系：回复的 parentId 命中根批注，根批注仍是根（parentId empty）
      assertThat(doc.comments().list()).hasSize(2);
      var reply =
          doc.comments().list().stream()
              .filter(c -> c.parentId().isPresent())
              .findFirst()
              .orElseThrow();
      assertThat(reply.author()).isEqualTo("复核员");
      assertThat(reply.text()).isEqualTo("同意，请补充上季度数据。");
      assertThat(reply.parentId()).contains(rootId);
      assertThat(doc.comments().get(rootId).parentId()).isEmpty();
      // 不改正文，也不改原批注
      assertThat(doc.paragraph(0).text()).isEqualTo("第一段");
      assertThat(doc.paragraph(1).text()).isEqualTo("第二段");
      assertThat(doc.comments().get(rootId).author()).isEqualTo("审查员");
      assertThat(doc.comments().get(rootId).text()).isEqualTo("请核实该预算金额的数据来源。");
      doc.save(file);
    } finally {
      doc.close();
    }
    try (Document reopened = Docx.open(file)) {
      // save+reopen：线程字段来自 commentsExtended.xml 的 join，只有落盘后重开才验证真实结构
      assertThat(reopened.comments().list()).hasSize(2);
      var root =
          reopened.comments().list().stream()
              .filter(c -> c.parentId().isEmpty())
              .findFirst()
              .orElseThrow();
      var reply =
          reopened.comments().list().stream()
              .filter(c -> c.parentId().isPresent())
              .findFirst()
              .orElseThrow();
      assertThat(reply.author()).isEqualTo("复核员");
      assertThat(reply.text()).isEqualTo("同意，请补充上季度数据。");
      // 落盘后 parentId 必须精确指向根批注 id（只断言 isPresent 会漏掉「挂错父批注」）
      assertThat(reply.parentId()).contains(root.id());
    }
  }

  @Test
  void commentReplySupportsMultipleRepliesAndMixesWithBodyOps() {
    Document doc = sample();
    try {
      String firstRoot = doc.paragraph(0).addComment("审查员", "首段需要补数据").id();
      String secondRoot = doc.paragraph(1).addComment("编辑", "第二段措辞待定").id();
      PatchResult result =
          PatchEngine.apply(
              doc,
              ops(
                  // 同一根批注两条回复 + 另一根批注一条回复 + 正文改写同批（reply 不占锚点）
                  op(
                      "op",
                      "comment.reply",
                      "comment_id",
                      firstRoot,
                      "text",
                      "同意一",
                      "author",
                      "复核员"),
                  op(
                      "op",
                      "comment.reply",
                      "comment_id",
                      firstRoot,
                      "text",
                      "补充：上季度数据",
                      "author",
                      "复核员"),
                  op(
                      "op",
                      "comment.reply",
                      "comment_id",
                      secondRoot,
                      "text",
                      "已改",
                      "author",
                      "复核员"),
                  op("op", "replace_text", "anchor", "p0", "find", "第一段", "text", "首段")),
              REPORT_CN,
              false);
      assertThat(result.applied()).isEqualTo(4);
      assertThat(result.changed())
          .containsExactly(
              "comment#" + firstRoot, "comment#" + firstRoot, "comment#" + secondRoot, "p0");
      assertThat(doc.paragraph(0).text()).isEqualTo("首段");
      // 根 2 条 + 回复 3 条
      assertThat(doc.comments().list()).hasSize(5);
      long repliesToFirst =
          doc.comments().list().stream()
              .filter(c -> c.parentId().filter(firstRoot::equals).isPresent())
              .count();
      assertThat(repliesToFirst).isEqualTo(2);
      assertThat(
              doc.comments().list().stream()
                  .filter(c -> c.parentId().filter(secondRoot::equals).isPresent())
                  .count())
          .isEqualTo(1);
    } finally {
      doc.close();
    }
  }

  @Test
  void commentReplyPreflightRejectsInvalidTargetsAtomically() {
    Document doc = sample();
    try {
      String rootId = doc.paragraph(1).addComment("审查员", "根批注").id();
      String replyId = doc.comments().reply(rootId, "复核员", "已有回复").id();
      // comment_id 缺失 / 纯空白 / 只有前导 #
      assertThatThrownBy(
              () ->
                  PatchEngine.apply(
                      doc,
                      ops(op("op", "comment.reply", "text", "x", "author", "甲")),
                      REPORT_CN,
                      false))
          .isInstanceOf(PatchException.class)
          .hasMessageContaining("comment_id");
      assertThatThrownBy(
              () ->
                  PatchEngine.apply(
                      doc,
                      ops(
                          op(
                              "op", "comment.reply",
                              "comment_id", " # ",
                              "text", "x",
                              "author", "甲")),
                      REPORT_CN,
                      false))
          .isInstanceOf(PatchException.class)
          .hasMessageContaining("comment_id");
      // 批注 id 不存在
      assertThatThrownBy(
              () ->
                  PatchEngine.apply(
                      doc,
                      ops(
                          op(
                              "op",
                              "comment.reply",
                              "comment_id",
                              "999",
                              "text",
                              "x",
                              "author",
                              "甲")),
                      REPORT_CN,
                      false))
          .isInstanceOf(PatchException.class)
          .hasMessageContaining("op[0]")
          .hasMessageContaining("#999")
          .hasMessageContaining("不存在");
      // 目标本身是回复 → MVP 只支持一级回复，并提示改回根批注
      assertThatThrownBy(
              () ->
                  PatchEngine.apply(
                      doc,
                      ops(
                          op(
                              "op", "comment.reply",
                              "comment_id", replyId,
                              "text", "x",
                              "author", "甲")),
                      REPORT_CN,
                      false))
          .isInstanceOf(PatchException.class)
          .hasMessageContaining("op[0]")
          .hasMessageContaining("只支持回复根批注")
          .hasMessageContaining("#" + rootId);
      // author 空白 / text 缺失
      assertThatThrownBy(
              () ->
                  PatchEngine.apply(
                      doc,
                      ops(
                          op(
                              "op",
                              "comment.reply",
                              "comment_id",
                              rootId,
                              "text",
                              "x",
                              "author",
                              "  ")),
                      REPORT_CN,
                      false))
          .isInstanceOf(PatchException.class)
          .hasMessageContaining("author");
      assertThatThrownBy(
              () ->
                  PatchEngine.apply(
                      doc,
                      ops(op("op", "comment.reply", "comment_id", rootId, "author", "甲")),
                      REPORT_CN,
                      false))
          .isInstanceOf(PatchException.class)
          .hasMessageContaining("text");
      // 原子性：同批前一条合法回复 + 后一条非法 → 零写入（批注数不变）
      assertThatThrownBy(
              () ->
                  PatchEngine.apply(
                      doc,
                      ops(
                          op(
                              "op",
                              "comment.reply",
                              "comment_id",
                              rootId,
                              "text",
                              "好",
                              "author",
                              "甲"),
                          op(
                              "op",
                              "comment.reply",
                              "comment_id",
                              "999",
                              "text",
                              "坏",
                              "author",
                              "乙")),
                      REPORT_CN,
                      false))
          .isInstanceOf(PatchException.class)
          .hasMessageContaining("#999");
      assertThat(doc.comments().list()).hasSize(2);
      assertThat(doc.paragraph(1).text()).isEqualTo("第二段");
    } finally {
      doc.close();
    }
  }

  @Test
  void commentReplyDryRunPlansWithoutWriting() {
    Document doc = sample();
    try {
      String rootId = doc.paragraph(0).addComment("审查员", "根批注").id();
      PatchResult result =
          PatchEngine.apply(
              doc,
              ops(
                  op(
                      "op", "comment.reply",
                      "comment_id", rootId,
                      "text", "同意，请补充上季度数据。",
                      "author", "复核员")),
              REPORT_CN,
              true);
      assertThat(result.dryRun()).isTrue();
      assertThat(result.applied()).isEqualTo(1);
      assertThat(result.planned()).hasSize(1);
      assertThat(result.planned().get(0)).contains("comment.reply #" + rootId);
      assertThat(result.changed()).isEmpty();
      // 未写入：只有根批注
      assertThat(doc.comments().list()).hasSize(1);
      assertThat(doc.comments().list().get(0).parentId()).isEmpty();
      // dry-run 同样做 preflight：非法目标整体报错
      assertThatThrownBy(
              () ->
                  PatchEngine.apply(
                      doc,
                      ops(
                          op(
                              "op",
                              "comment.reply",
                              "comment_id",
                              "999",
                              "text",
                              "x",
                              "author",
                              "甲")),
                      REPORT_CN,
                      true))
          .isInstanceOf(PatchException.class)
          .hasMessageContaining("不存在");
    } finally {
      doc.close();
    }
  }

  // ==================== <u> 下划线标记 ====================

  @Test
  void replaceTextWithUnderlineMarkerUnderlinesOnlyMatchedRange(@TempDir Path tmp)
      throws Exception {
    Document doc = sample();
    Path file = tmp.resolve("underline-split.docx");
    try {
      var paragraph = doc.paragraph(0); // 「第一段」
      paragraph.run(0).fontSize(14);
      var ctr = paragraph.run(0).raw().getCTR();
      var rpr = ctr.isSetRPr() ? ctr.getRPr() : ctr.addNewRPr();
      var fonts = rpr.sizeOfRFontsArray() > 0 ? rpr.getRFontsArray(0) : rpr.addNewRFonts();
      fonts.setEastAsia("宋体");

      // 命中段在中间：「第」+「一」+「段」，前缀与后缀都非空——两边的拷贝都不得被顺手加上下划线
      PatchEngine.apply(
          doc,
          ops(op("op", "replace_text", "anchor", "p0", "find", "一", "text", "<u>壹</u>")),
          REPORT_CN,
          false);

      assertThat(paragraph.text()).isEqualTo("第壹段");
      assertThat(paragraph.runs()).hasSize(3);
      assertThat(paragraph.run(0).text()).isEqualTo("第");
      assertThat(paragraph.run(0).isUnderline()).isFalse();
      assertThat(paragraph.run(1).text()).isEqualTo("壹");
      assertThat(paragraph.run(1).isUnderline()).isTrue();
      assertThat(paragraph.run(2).text()).isEqualTo("段");
      assertThat(paragraph.run(2).isUnderline()).isFalse();
      // 拆分出的片段继承源 run 完整样式（字号 + eastAsia 槽）
      for (int i = 0; i < 3; i++) {
        assertThat(paragraph.run(i).fontSize()).isEqualTo(14);
        assertThat(eastAsia(paragraph.run(i))).isEqualTo("宋体");
      }
      doc.save(file);
    } finally {
      doc.close();
    }
    try (Document reopened = Docx.open(file)) {
      // save+reopen：拷贝若写进非树对象，内存读正确、落盘原样（poi-bridge §3 的缓存陷阱只有落盘可暴露）
      assertThat(reopened.paragraph(0).text()).isEqualTo("第壹段");
      assertThat(reopened.paragraph(0).run(0).isUnderline()).isFalse();
      assertThat(reopened.paragraph(0).run(1).text()).isEqualTo("壹");
      assertThat(reopened.paragraph(0).run(1).isUnderline()).isTrue();
      assertThat(reopened.paragraph(0).run(2).isUnderline()).isFalse();
      assertThat(reopened.paragraph(0).run(1).fontSize()).isEqualTo(14);
    }
  }

  @Test
  void replaceTextWithUnderlineAcrossRunsRebuildsAsSpanSequence() {
    Document doc = sample();
    try {
      Paragraph_Without_Helpers.split(doc.paragraph(0)); // 「第一」+「段」
      PatchResult result =
          PatchEngine.apply(
              doc,
              ops(
                  op(
                      "op", "replace_text",
                      "anchor", "p0",
                      "find", "第一段",
                      "text", "前<u>新的</u>")),
              REPORT_CN,
              false);
      assertThat(doc.paragraph(0).text()).isEqualTo("前新的");
      assertThat(doc.paragraph(0).runs()).hasSize(2);
      assertThat(doc.paragraph(0).run(0).text()).isEqualTo("前");
      assertThat(doc.paragraph(0).run(0).isUnderline()).isFalse();
      assertThat(doc.paragraph(0).run(1).text()).isEqualTo("新的");
      assertThat(doc.paragraph(0).run(1).isUnderline()).isTrue();
      assertThat(result.warnings()).anyMatch(w -> w.contains("跨 run"));
    } finally {
      doc.close();
    }
  }

  @Test
  void tableSetCellAppliesUnderlineAndPlainPathKeepsSingleRun() {
    Document doc = sample();
    try {
      PatchEngine.apply(
          doc,
          ops(
              op(
                  "op", "insert",
                  "position", "end",
                  "block", "| 模块 | 状态 |\n|---|---|\n| core | 进行中 |")),
          REPORT_CN,
          false);
      // 富文本单元格：`<u>` 落 run
      PatchEngine.apply(
          doc,
          ops(op("op", "table.set_cell", "anchor", "t0", "row", 1, "col", 0, "text", "<u>核心</u>")),
          REPORT_CN,
          false);
      var styled = doc.tables().get(0).rows().get(1).cells().get(0);
      assertThat(styled.text()).isEqualTo("核心");
      assertThat(styled.paragraph(0).run(0).isUnderline()).isTrue();

      // 纯文本路径不变：单 run、无下划线
      PatchEngine.apply(
          doc,
          ops(op("op", "table.set_cell", "anchor", "t0", "row", 1, "col", 1, "text", "已完成")),
          REPORT_CN,
          false);
      var plain = doc.tables().get(0).rows().get(1).cells().get(1);
      assertThat(plain.text()).isEqualTo("已完成");
      assertThat(plain.paragraph(0).runs()).hasSize(1);
      assertThat(plain.paragraph(0).run(0).isUnderline()).isFalse();
    } finally {
      doc.close();
    }
  }

  @Test
  void headerSetTextAppliesUnderlineToMatchedSpan() {
    Document doc = sample();
    try {
      PatchResult result =
          PatchEngine.apply(
              doc, ops(op("op", "header.set_text", "text", "<u>机密</u> 内部文档")), REPORT_CN, false);
      assertThat(result.changed()).containsExactly("header");
      assertThat(doc.header().text()).contains("机密 内部文档").doesNotContain("<u>");
      assertThat(doc.header().paragraph(0).run(0).text()).isEqualTo("机密");
      assertThat(doc.header().paragraph(0).run(0).isUnderline()).isTrue();
      assertThat(doc.header().paragraph(0).run(1).text()).isEqualTo(" 内部文档");
      assertThat(doc.header().paragraph(0).run(1).isUnderline()).isFalse();
    } finally {
      doc.close();
    }
  }

  @Test
  void trackedInsertWithUnderlineKeepsFormatAfterReopen() throws Exception {
    java.nio.file.Path file = java.nio.file.Files.createTempFile("tracked-underline-ins", ".docx");
    Document doc = Docx.create();
    try {
      doc.addParagraph("正文");
      PatchEngine.apply(
          doc,
          ops(
              op(
                  "op", "tracked.insert",
                  "anchor", "p0",
                  "text", "<u>要点</u>说明",
                  "author", "审查员")),
          REPORT_CN,
          false);
      // 一条 INS 修订、两个 run；下划线写在 run 的 rPr 上（live 口径先看原始 rPr）
      var changes = doc.trackedChanges().list();
      assertThat(changes).hasSize(1);
      var rList = changes.get(0).raw().getRList();
      assertThat(rList).hasSize(2);
      assertThat(rList.get(0).getRPr().sizeOfUArray()).isEqualTo(1);
      assertThat(rList.get(1).isSetRPr() ? rList.get(1).getRPr().sizeOfUArray() : 0).isZero();
      doc.save(file);
    } finally {
      doc.close();
    }
    try (Document opened = Docx.open(file)) {
      // reopen 口径：ins 内 run 的 rPr 落盘可读
      var ins =
          opened.trackedChanges().list().stream()
              .filter(
                  c ->
                      c.type()
                          == io.github.nondirectional.docx.core.api.track.TrackedChangeType.INS)
              .findFirst()
              .orElseThrow();
      assertThat(ins.raw().getRList().get(0).getRPr().sizeOfUArray()).isEqualTo(1);
      assertThat(opened.trackedChanges().acceptAll()).isEqualTo(1);
      java.nio.file.Path accepted = java.nio.file.Path.of(file + ".accepted.docx");
      opened.save(accepted);
      try (Document result = Docx.open(accepted)) {
        assertThat(result.paragraph(0).text()).isEqualTo("正文要点说明");
        assertThat(result.paragraph(0).run(1).text()).isEqualTo("要点");
        assertThat(result.paragraph(0).run(1).isUnderline()).isTrue();
        assertThat(result.paragraph(0).run(2).isUnderline()).isFalse();
      }
    }
  }

  @Test
  void trackedReplaceWithUnderlineKeepsFormatAfterReopen() throws Exception {
    java.nio.file.Path file = java.nio.file.Files.createTempFile("tracked-underline-rep", ".docx");
    Document doc = Docx.create();
    try {
      doc.addParagraph("结论：待定。");
      PatchEngine.apply(
          doc,
          ops(
              op(
                  "op", "tracked.replace",
                  "anchor", "p0",
                  "find", "待定",
                  "text", "<u>通过</u>",
                  "author", "审查员")),
          REPORT_CN,
          false);
      var changes = doc.trackedChanges().list();
      assertThat(changes).hasSize(2); // del + ins
      var ins =
          changes.stream()
              .filter(
                  c ->
                      c.type()
                          == io.github.nondirectional.docx.core.api.track.TrackedChangeType.INS)
              .findFirst()
              .orElseThrow();
      assertThat(ins.raw().getRList()).hasSize(1);
      assertThat(ins.raw().getRList().get(0).getRPr().sizeOfUArray()).isEqualTo(1);
      doc.save(file);
    } finally {
      doc.close();
    }
    try (Document opened = Docx.open(file)) {
      assertThat(opened.trackedChanges().acceptAll()).isEqualTo(2);
      java.nio.file.Path accepted = java.nio.file.Path.of(file + ".accepted.docx");
      opened.save(accepted);
      try (Document result = Docx.open(accepted)) {
        assertThat(result.paragraph(0).text()).isEqualTo("结论：通过。");
        // accept 拆掉 ins 包装后：前缀 / 下划线片段 / 后缀 三段，只有中段带下划线
        assertThat(result.paragraph(0).run(0).text()).isEqualTo("结论：");
        assertThat(result.paragraph(0).run(0).isUnderline()).isFalse();
        assertThat(result.paragraph(0).run(1).text()).isEqualTo("通过");
        assertThat(result.paragraph(0).run(1).isUnderline()).isTrue();
        assertThat(result.paragraph(0).run(2).isUnderline()).isFalse();
      }
    }
  }

  // ==================== fixture ====================

  private static Document sample() {
    Document doc = Docx.create();
    doc.addParagraph("第一段");
    doc.addParagraph("第二段");
    return doc;
  }

  private static List<Object> ops(Map<String, Object>... items) {
    return new ArrayList<>(List.of(items));
  }

  @SafeVarargs
  private static Map<String, Object> op(Object... pairs) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      map.put(String.valueOf(pairs[i]), pairs[i + 1]);
    }
    return map;
  }

  /** 把段落的 run 拆成两半（制造跨 run 文本）。 */
  private static final class Paragraph_Without_Helpers {

    private static void split(io.github.nondirectional.docx.core.api.text.Paragraph paragraph) {
      String text = paragraph.text();
      paragraph.removeInlineElement(0);
      int mid = text.length() / 2;
      paragraph.addRun(text.substring(0, mid));
      paragraph.addRun(text.substring(mid));
    }
  }

  private static String eastAsia(io.github.nondirectional.docx.core.api.text.Run run) {
    CTRPr rpr = run.raw().getCTR().getRPr();
    if (rpr == null || rpr.sizeOfRFontsArray() == 0) {
      return null;
    }
    CTFonts fonts = rpr.getRFontsArray(0);
    return fonts.getEastAsia();
  }
}
