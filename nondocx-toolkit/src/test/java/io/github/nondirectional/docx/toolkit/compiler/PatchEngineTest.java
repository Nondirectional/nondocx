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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;

/**
 * {@link PatchEngine} 契约测试：6 种 op 的行为 + 原子性 + dry-run。
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
