package io.github.nondirectional.docx.examples.eval;

import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.header.Footer;
import io.github.nondirectional.docx.core.api.style.Alignment;
import io.github.nondirectional.docx.core.api.style.HeadingLevel;
import io.github.nondirectional.docx.core.api.table.Cell;
import io.github.nondirectional.docx.core.api.table.Row;
import io.github.nondirectional.docx.core.api.table.Table;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import io.github.nondirectional.docx.core.api.track.TrackedChange;
import io.github.nondirectional.docx.core.api.track.TrackedChangeType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 第一版评测集的 11 条任务。
 *
 * <p>每条任务都从代码构造 seed，断言只依赖最终 docx 的结构/文本/样式，不依赖模型自述。 任务选择的原则不是「覆盖全部能力」，而是「能区分模型问题、接口问题、能力缺失」：
 *
 * <ul>
 *   <li>1~3：基础写入，应该能过；过不了说明工具/提示词有硬伤。
 *   <li>4~5：全文格式，检查是否有批量样式能力。
 *   <li>6~7：表格，检查多级坐标与建表/样式分步。
 *   <li>8~9：修订与批量替换，检查语义工具选择。
 *   <li>10：页脚页码，检查只读能力缺口。
 *   <li>11：批注创作，检查 comment.add 与批注只读观测（锚定段/作者/正文不动）。
 * </ul>
 */
public final class EvalTasks {

  private static final List<EvalTask> TASKS = buildTasks();
  private static final List<EvalTask> CREATE_TASKS = buildCreateTasks();

  private EvalTasks() {}

  /** 返回 baseline 任务组（固定顺序）：对既有文档做编辑的 11 条任务。 */
  public static List<EvalTask> all() {
    return TASKS;
  }

  /** 返回 create 任务组（固定顺序）：从零生成整份文档的 5 条任务，供工具面 A/B。 */
  public static List<EvalTask> create() {
    return CREATE_TASKS;
  }

  /** 按组名取任务：{@code baseline}（默认）/ {@code create}；未知组名返回 null。 */
  public static List<EvalTask> group(String name) {
    if (name == null || name.isBlank() || "baseline".equals(name)) {
      return TASKS;
    }
    if ("create".equals(name)) {
      return CREATE_TASKS;
    }
    return null;
  }

  /** 按 id 查找（覆盖两组）；不存在返回 null。 */
  public static EvalTask byId(String id) {
    for (EvalTask task : TASKS) {
      if (task.id().equals(id)) {
        return task;
      }
    }
    for (EvalTask task : CREATE_TASKS) {
      if (task.id().equals(id)) {
        return task;
      }
    }
    return null;
  }

  /** 返回 id → 标题的稳定映射（覆盖两组），供 CLI --list 使用。 */
  public static Map<String, String> catalog() {
    Map<String, String> catalog = new LinkedHashMap<>();
    for (EvalTask task : TASKS) {
      catalog.put(task.id(), task.title());
    }
    for (EvalTask task : CREATE_TASKS) {
      catalog.put(task.id(), task.title());
    }
    return catalog;
  }

  private static List<EvalTask> buildTasks() {
    List<EvalTask> tasks = new ArrayList<>();

    tasks.add(
        new EvalTask(
            "append-paragraph",
            "末尾追加一段",
            "在文档末尾追加一段：本周进度正常。",
            doc -> doc.addParagraph("开头段落。"),
            List.of(
                EvalTask.NamedCheck.of(
                    "段落数增加",
                    doc ->
                        EvalAssert.isTrue(
                            doc.paragraphs().size() >= 2,
                            "期望至少 2 段，实际 " + doc.paragraphs().size())),
                EvalTask.NamedCheck.of(
                    "末尾包含新段落",
                    doc -> EvalAssert.contains(lastNonBlankText(doc), "本周进度正常", "最后一个非空段落")))));

    tasks.add(
        new EvalTask(
            "insert-centered-title",
            "开头插入居中标题",
            "在文档开头插入一个居中标题「项目周汇报」。",
            doc -> doc.addParagraph("正文"),
            List.of(
                EvalTask.NamedCheck.of(
                    "首段文本",
                    doc ->
                        EvalAssert.equal(
                            "项目周汇报", requireParagraph(doc, 0, "第 0 段").text(), "第 0 段文本")),
                EvalTask.NamedCheck.of(
                    "首段是一级标题",
                    doc ->
                        EvalAssert.equal(
                            HeadingLevel.H1,
                            requireParagraph(doc, 0, "第 0 段").heading(),
                            "第 0 段标题级别")),
                EvalTask.NamedCheck.of(
                    "首段居中",
                    doc ->
                        EvalAssert.equal(
                            Alignment.CENTER,
                            requireParagraph(doc, 0, "第 0 段").alignment(),
                            "第 0 段对齐")))));

    tasks.add(
        new EvalTask(
            "replace-exact-text",
            "精确替换文本",
            "把文档中的「旧版本说明」替换为「新版本说明」，不要改其他内容。",
            doc -> {
              doc.addParagraph("旧版本说明。");
              doc.addParagraph("保留内容。");
            },
            List.of(
                EvalTask.NamedCheck.of(
                    "新文本出现", doc -> EvalAssert.contains(EvalAssert.bodyText(doc), "新版本说明", "正文")),
                EvalTask.NamedCheck.of(
                    "旧文本消失",
                    doc -> EvalAssert.notContains(EvalAssert.bodyText(doc), "旧版本说明", "正文")),
                EvalTask.NamedCheck.of(
                    "其他段落不变",
                    doc ->
                        EvalAssert.equal(
                            "保留内容。", requireParagraph(doc, 1, "第 1 段").text(), "第 1 段")))));

    tasks.add(
        new EvalTask(
            "body-font-line-spacing",
            "正文宋体小四 + 1.5 倍行距",
            "把正文所有段落设置为宋体、小四（12pt）、1.5 倍行距。",
            doc -> {
              doc.addParagraph("第一段正文。");
              doc.addParagraph("第二段正文。");
              doc.addParagraph("第三段正文。");
            },
            List.of(
                EvalTask.NamedCheck.of(
                    "正文行距 1.5",
                    doc -> {
                      for (Paragraph p : nonBlankBodyParagraphs(doc)) {
                        EvalAssert.closeTo(1.5, p.lineSpacing(), 0.01, "段落「" + p.text() + "」行距");
                      }
                    }),
                EvalTask.NamedCheck.of(
                    "正文宋体",
                    doc -> {
                      for (Paragraph p : nonBlankBodyParagraphs(doc)) {
                        // 中文按 eastAsia 槽渲染：full 面 font() 四槽同写、compiler 面
                        // ascii/eastAsia 分离，读 eastAsia 槽对两个工具面都成立。
                        EvalAssert.assertEastAsiaFont(p, "宋体", "段落「" + p.text() + "」");
                      }
                    }),
                EvalTask.NamedCheck.of(
                    "正文字号 12",
                    doc -> {
                      for (Paragraph p : nonBlankBodyParagraphs(doc)) {
                        EvalAssert.assertFontSize(p, 12, "段落「" + p.text() + "」字号");
                      }
                    }))));

    tasks.add(
        new EvalTask(
            "heading-font",
            "所有标题设为黑体",
            "把文档里所有标题的字体设置为黑体，正文不要改。",
            doc -> {
              heading(doc, HeadingLevel.H1, "项目周报");
              heading(doc, HeadingLevel.H2, "本周进展");
              doc.addParagraph("本周完成了表格与超链接封装。");
            },
            List.of(
                EvalTask.NamedCheck.of(
                    "标题数量",
                    doc ->
                        EvalAssert.isTrue(
                            headingParagraphs(doc).size() >= 2,
                            "期望至少 2 个标题，实际 " + headingParagraphs(doc).size())),
                EvalTask.NamedCheck.of(
                    "标题黑体",
                    doc -> {
                      for (Paragraph p : headingParagraphs(doc)) {
                        EvalAssert.assertEastAsiaFont(p, "黑体", "标题「" + p.text() + "」");
                      }
                    }))));

    tasks.add(
        new EvalTask(
            "create-table-header",
            "建表并加粗表头",
            "在文档末尾插入一个 2 行 3 列的表格，表头是「任务、负责人、状态」，" + "第二行填「联调、小王、完成」，表头加粗。",
            doc -> doc.addParagraph("项目周报"),
            List.of(
                EvalTask.NamedCheck.of(
                    "表格存在",
                    doc ->
                        EvalAssert.isTrue(
                            doc.tables().size() >= 1, "期望至少 1 个表格，实际 " + doc.tables().size())),
                EvalTask.NamedCheck.of(
                    "2 行 3 列",
                    doc -> {
                      Table table = requireLastTable(doc, "建表任务");
                      EvalAssert.equal(2, table.rows().size(), "表格行数");
                      for (Row row : table.rows()) {
                        EvalAssert.equal(3, row.cells().size(), "行单元格数");
                      }
                    }),
                EvalTask.NamedCheck.of(
                    "表头文本",
                    doc -> {
                      Table table = requireLastTable(doc, "建表任务");
                      EvalAssert.isTrue(!table.rows().isEmpty(), "表格没有行，无法检查表头");
                      Row header = table.rows().get(0);
                      EvalAssert.equal("任务", header.cells().get(0).text(), "表头 1");
                      EvalAssert.equal("负责人", header.cells().get(1).text(), "表头 2");
                      EvalAssert.equal("状态", header.cells().get(2).text(), "表头 3");
                    }),
                EvalTask.NamedCheck.of(
                    "表头加粗",
                    doc -> {
                      Table table = requireLastTable(doc, "建表任务");
                      EvalAssert.isTrue(!table.rows().isEmpty(), "表格没有行，无法检查表头");
                      Row header = table.rows().get(0);
                      for (Cell cell : header.cells()) {
                        EvalAssert.assertBold(
                            cell.paragraph(0), true, "表头单元格「" + cell.text() + "」");
                      }
                    }))));

    tasks.add(
        new EvalTask(
            "table-cell-edit",
            "修改表格单元格",
            "把表格第 2 行第 2 列的内容改为「已完成」。",
            doc -> {
              doc.addParagraph("进度表");
              Table table = doc.addTable();
              Row row0 = table.addRow();
              row0.addCell().text("A");
              row0.addCell().text("B");
              Row row1 = table.addRow();
              row1.addCell().text("C");
              row1.addCell().text("D");
            },
            List.of(
                EvalTask.NamedCheck.of(
                    "目标单元格",
                    doc -> {
                      Table table = requireTable(doc, 0, "进度表");
                      EvalAssert.isTrue(table.rows().size() >= 2, "表格行数不足 2");
                      EvalAssert.isTrue(table.rows().get(1).cells().size() >= 2, "第 2 行单元格不足 2");
                      EvalAssert.equal(
                          "已完成", table.rows().get(1).cells().get(1).text(), "第 2 行第 2 列");
                    }),
                EvalTask.NamedCheck.of(
                    "其他单元格不变",
                    doc -> {
                      Table table = requireTable(doc, 0, "进度表");
                      EvalAssert.isTrue(!table.rows().isEmpty(), "表格没有行");
                      EvalAssert.isTrue(!table.rows().get(0).cells().isEmpty(), "第 1 行没有单元格");
                      EvalAssert.equal(
                          "A", table.rows().get(0).cells().get(0).text(), "第 1 行第 1 列");
                    }))));

    tasks.add(
        new EvalTask(
            "tracked-replace",
            "修订模式替换",
            "以修订模式把「一百万元」改成「两百万元」，作者填「审查员」。",
            doc -> doc.addParagraph("合同金额为一百万元。"),
            List.of(
                EvalTask.NamedCheck.of(
                    "存在修订",
                    doc ->
                        EvalAssert.isTrue(
                            !doc.trackedChanges().list().isEmpty(), "期望至少 1 条修订，实际 0 条")),
                EvalTask.NamedCheck.of(
                    "作者正确",
                    doc -> {
                      boolean found = false;
                      for (TrackedChange change : doc.trackedChanges().list()) {
                        if ("审查员".equals(change.author())) {
                          found = true;
                          break;
                        }
                      }
                      EvalAssert.isTrue(found, "期望存在作者为「审查员」的修订");
                    }),
                EvalTask.NamedCheck.of(
                    "包含插入与删除",
                    doc -> {
                      boolean ins = false;
                      boolean del = false;
                      for (TrackedChange change : doc.trackedChanges().list()) {
                        if (change.type() == TrackedChangeType.INS) {
                          ins = true;
                        }
                        if (change.type() == TrackedChangeType.DEL) {
                          del = true;
                        }
                      }
                      EvalAssert.isTrue(ins, "期望存在插入修订（INS）");
                      EvalAssert.isTrue(del, "期望存在删除修订（DEL）");
                    }))));

    tasks.add(
        new EvalTask(
            "replace-all-occurrences",
            "替换所有 TODO",
            "把文档里所有「TODO」替换为「已完成」。",
            doc -> {
              doc.addParagraph("TODO: 完成接口");
              doc.addParagraph("TODO: 补充测试");
              doc.addParagraph("TODO: 更新文档");
              doc.addParagraph("其他内容。");
            },
            List.of(
                EvalTask.NamedCheck.of(
                    "TODO 消失",
                    doc -> EvalAssert.notContains(EvalAssert.bodyText(doc), "TODO", "正文")),
                EvalTask.NamedCheck.of(
                    "三处都已替换",
                    doc -> {
                      int count = countOccurrences(EvalAssert.bodyText(doc), "已完成");
                      EvalAssert.isTrue(count >= 3, "期望「已完成」至少 3 处，实际 " + count);
                    }))));

    tasks.add(
        new EvalTask(
            "footer-page-number",
            "页脚插入页码",
            "在页脚插入页码，格式为「第 X 页」。",
            doc -> {
              doc.addParagraph("正文");
              doc.ensureFooter().addParagraph().addRun("页脚");
            },
            List.of(
                EvalTask.NamedCheck.of(
                    "页脚存在", doc -> EvalAssert.isTrue(doc.footer() != null, "期望存在页脚")),
                EvalTask.NamedCheck.of(
                    "正文不被误改",
                    doc -> EvalAssert.equal("正文", requireParagraph(doc, 0, "正文").text(), "第 0 段")),
                EvalTask.NamedCheck.of(
                    "页脚含页码",
                    doc -> {
                      Footer footer = doc.footer();
                      String text = footer == null ? "" : footer.text();
                      EvalAssert.contains(text, "第", "页脚文本");
                      EvalAssert.isTrue(containsDigit(text), "页脚文本应包含数字，实际「" + text + "」");
                    })),
            true));

    tasks.add(
        new EvalTask(
            "comment-add",
            "给段落加批注",
            "给第二段（「本季度预算为一百五十万元」）加一条批注，作者填「审查员」，" + "批注内容需要提到「数据来源」。不要修改正文。",
            doc -> {
              doc.addParagraph("项目背景");
              doc.addParagraph("本季度预算为一百五十万元。");
              doc.addParagraph("执行计划");
            },
            List.of(
                EvalTask.NamedCheck.of(
                    "批注存在",
                    doc ->
                        EvalAssert.isTrue(!doc.comments().list().isEmpty(), "期望至少 1 条批注，实际 0 条")),
                EvalTask.NamedCheck.of(
                    "作者正确",
                    doc -> {
                      boolean found = false;
                      for (io.github.nondirectional.docx.core.api.comment.Comment c :
                          doc.comments().list()) {
                        if ("审查员".equals(c.author())) {
                          found = true;
                          break;
                        }
                      }
                      EvalAssert.isTrue(found, "期望存在作者为「审查员」的批注");
                    }),
                EvalTask.NamedCheck.of(
                    "内容提到数据来源",
                    doc -> {
                      boolean found = false;
                      for (io.github.nondirectional.docx.core.api.comment.Comment c :
                          doc.comments().list()) {
                        if (c.text() != null && c.text().contains("数据来源")) {
                          found = true;
                          break;
                        }
                      }
                      EvalAssert.isTrue(found, "期望批注内容包含「数据来源」");
                    }),
                EvalTask.NamedCheck.of(
                    "锚定在预算段",
                    doc -> {
                      Paragraph budget = requireParagraph(doc, 1, "预算段");
                      EvalAssert.isTrue(!budget.comments().isEmpty(), "期望批注锚定在第二段（预算段）");
                    }),
                EvalTask.NamedCheck.of(
                    "正文未修改",
                    doc -> {
                      EvalAssert.equal("项目背景", requireParagraph(doc, 0, "背景段").text(), "第 0 段");
                      EvalAssert.equal(
                          "本季度预算为一百五十万元。", requireParagraph(doc, 1, "预算段").text(), "第 1 段");
                      EvalAssert.equal("执行计划", requireParagraph(doc, 2, "计划段").text(), "第 2 段");
                    }))));

    return List.copyOf(tasks);
  }

  // ==================== create 任务组：从零生成整份文档 ====================
  //
  // 供「工具面 A/B」：同一批任务分别在 full 面（58 工具，逐步 insert/update）与
  // compiler 面（5 入口，一次 create_document）上跑，对比通过率与调用数。
  // seed 统一为一个占位段落（非空可重开、check 恒失败），任务语义是「替换整份文档」。

  private static List<EvalTask> buildCreateTasks() {
    List<EvalTask> tasks = new ArrayList<>();

    tasks.add(
        new EvalTask(
            "create-weekly-report",
            "新建项目周报",
            "把当前文档替换为一份全新的项目周报：居中一级标题「项目周报」，正文两段"
                + "（内容围绕本周进展，自行撰写），其中至少一个关键词加粗，"
                + "末尾一个 3 列表格（表头：模块、状态、负责人，两行数据）。",
            doc -> doc.addParagraph("（待生成）"),
            List.of(
                EvalTask.NamedCheck.of(
                    "标题居中",
                    doc -> {
                      Paragraph title = requireParagraph(doc, 0, "标题段");
                      EvalAssert.equal("项目周报", title.text(), "标题文本");
                      EvalAssert.equal(HeadingLevel.H1, title.heading(), "标题级别");
                      EvalAssert.equal(Alignment.CENTER, title.alignment(), "标题对齐");
                    }),
                EvalTask.NamedCheck.of(
                    "正文两段且含加粗",
                    doc -> {
                      List<Paragraph> body = nonHeadingNonListParagraphs(doc);
                      EvalAssert.isTrue(body.size() >= 2, "期望至少 2 个正文段落，实际 " + body.size());
                      boolean anyBold =
                          body.stream()
                              .anyMatch(
                                  p ->
                                      EvalAssert.textRuns(p).stream()
                                          .anyMatch(
                                              io.github.nondirectional.docx.core.api.text.Run
                                                  ::isBold));
                      EvalAssert.isTrue(anyBold, "期望正文中存在加粗 run");
                    }),
                EvalTask.NamedCheck.of(
                    "表格 3 行 3 列且表头加粗",
                    doc -> {
                      Table table = requireTable(doc, 0, "周报表");
                      EvalAssert.isTrue(
                          table.rows().size() >= 3, "期望至少 3 行（表头+2 数据），实际 " + table.rows().size());
                      EvalAssert.isTrue(
                          table.rows().get(0).cells().size() >= 3,
                          "期望至少 3 列，实际 " + table.rows().get(0).cells().size());
                      EvalAssert.equal("模块", table.rows().get(0).cells().get(0).text(), "表头第 1 格");
                      EvalAssert.assertBold(
                          table.rows().get(0).cells().get(0).paragraph(0), true, "表头第 1 格");
                    }))));

    tasks.add(
        new EvalTask(
            "create-styled-body",
            "新建带排版正文",
            "生成一份中文实验报告替换当前文档：居中标题「实验报告」（黑体），" + "正文三段（内容自拟），要求宋体小四（12pt）、1.5 倍行距、首行缩进两个字符。",
            doc -> doc.addParagraph("（待生成）"),
            List.of(
                EvalTask.NamedCheck.of(
                    "标题黑体居中",
                    doc -> {
                      Paragraph title = requireParagraph(doc, 0, "标题段");
                      EvalAssert.equal("实验报告", title.text(), "标题文本");
                      EvalAssert.equal(Alignment.CENTER, title.alignment(), "标题对齐");
                      EvalAssert.assertEastAsiaFont(title, "黑体", "标题 eastAsia 字体");
                    }),
                EvalTask.NamedCheck.of(
                    "正文宋体小四",
                    doc -> {
                      Paragraph body = requireParagraph(doc, 1, "正文第 1 段");
                      EvalAssert.assertEastAsiaFont(body, "宋体", "正文 eastAsia 字体");
                      EvalAssert.assertFontSize(body, 12, "正文字号");
                    }),
                EvalTask.NamedCheck.of(
                    "1.5 倍行距",
                    doc ->
                        EvalAssert.closeTo(
                            1.5, requireParagraph(doc, 1, "正文第 1 段").lineSpacing(), 0.01, "正文行距")),
                EvalTask.NamedCheck.of(
                    "首行缩进两个字符",
                    doc -> {
                      int indent = requireParagraph(doc, 1, "正文第 1 段").indentationFirstLine();
                      EvalAssert.isTrue(
                          indent >= 400 && indent <= 560, "首行缩进期望约 480 缇（2×12pt×20），实际 " + indent);
                    }))));

    tasks.add(
        new EvalTask(
            "create-meeting-notes",
            "新建会议纪要",
            "把当前文档替换为会议纪要：一级标题「会议纪要」，两个二级小节" + "「讨论事项」与「行动项」，每节至少一段内容；" + "「行动项」一节用无序列表列出 3 条待办。",
            doc -> doc.addParagraph("（待生成）"),
            List.of(
                EvalTask.NamedCheck.of(
                    "标题与小节",
                    doc -> {
                      EvalAssert.equal("会议纪要", requireParagraph(doc, 0, "标题段").text(), "标题文本");
                      List<Paragraph> headings = headingParagraphs(doc);
                      long h2Count =
                          headings.stream().filter(p -> p.heading() == HeadingLevel.H2).count();
                      EvalAssert.isTrue(h2Count >= 2, "期望至少 2 个二级标题，实际 " + h2Count);
                      EvalAssert.contains(EvalAssert.bodyText(doc), "讨论事项", "小节标题");
                      EvalAssert.contains(EvalAssert.bodyText(doc), "行动项", "小节标题");
                    }),
                EvalTask.NamedCheck.of(
                    "行动项 3 条无序列表",
                    doc -> {
                      long bullets =
                          doc.paragraphs().stream()
                              .filter(
                                  p ->
                                      p.listKind()
                                          == io.github.nondirectional.docx.core.api.style.ListKind
                                              .BULLET)
                              .count();
                      EvalAssert.isTrue(bullets >= 3, "期望至少 3 个无序列表项，实际 " + bullets);
                    }))));

    tasks.add(
        new EvalTask(
            "create-data-table",
            "新建数据表文档",
            "生成一个数据表文档替换当前文档：标题「模块状态一览」，一个 4 列表格"
                + "（表头：模块、状态、负责人、备注；3 行数据，其中一行的备注为「加急」），"
                + "表格之后跟一段总结说明。",
            doc -> doc.addParagraph("（待生成）"),
            List.of(
                EvalTask.NamedCheck.of(
                    "表格 4 行 4 列",
                    doc -> {
                      Table table = requireTable(doc, 0, "数据表");
                      EvalAssert.isTrue(
                          table.rows().size() >= 4, "期望至少 4 行（表头+3 数据），实际 " + table.rows().size());
                      EvalAssert.isTrue(
                          table.rows().get(0).cells().size() >= 4,
                          "期望至少 4 列，实际 " + table.rows().get(0).cells().size());
                      EvalAssert.equal("模块", table.rows().get(0).cells().get(0).text(), "表头第 1 格");
                    }),
                EvalTask.NamedCheck.of(
                    "含加急备注", doc -> EvalAssert.contains(EvalAssert.bodyText(doc), "加急", "全文")),
                EvalTask.NamedCheck.of(
                    "表格后有说明段",
                    doc -> {
                      // body 顺序上最后一个元素应是段落（总结说明），不是表格
                      int size = doc.bodyElements().size();
                      EvalAssert.isTrue(size >= 2, "期望 body 至少 2 个元素，实际 " + size);
                      Object last = doc.bodyElements().get(size - 1);
                      EvalAssert.isTrue(
                          last instanceof Paragraph,
                          "body 最后一个元素应为段落（说明文字），实际是 " + last.getClass().getSimpleName());
                      String text = ((Paragraph) last).text();
                      EvalAssert.isTrue(text != null && !text.isBlank(), "末段应为非空说明文字");
                    }))));

    tasks.add(
        new EvalTask(
            "create-research-report",
            "新建调研报告",
            "写一份完整的技术调研报告替换当前文档：居中标题「技术选型调研报告」，"
                + "三个二级小节，每节至少两段正文；第二节包含一个 3 列对比表；"
                + "最后一节末尾用有序列表给出 3 条结论。",
            doc -> doc.addParagraph("（待生成）"),
            List.of(
                EvalTask.NamedCheck.of(
                    "标题与三个小节",
                    doc -> {
                      Paragraph title = requireParagraph(doc, 0, "标题段");
                      EvalAssert.equal("技术选型调研报告", title.text(), "标题文本");
                      EvalAssert.equal(Alignment.CENTER, title.alignment(), "标题对齐");
                      long h2Count =
                          headingParagraphs(doc).stream()
                              .filter(p -> p.heading() == HeadingLevel.H2)
                              .count();
                      EvalAssert.isTrue(h2Count >= 3, "期望至少 3 个二级标题，实际 " + h2Count);
                    }),
                EvalTask.NamedCheck.of(
                    "对比表存在",
                    doc -> {
                      Table table = requireTable(doc, 0, "对比表");
                      EvalAssert.isTrue(
                          table.rows().get(0).cells().size() >= 3,
                          "期望至少 3 列，实际 " + table.rows().get(0).cells().size());
                    }),
                EvalTask.NamedCheck.of(
                    "3 条有序结论",
                    doc -> {
                      long numbered =
                          doc.paragraphs().stream()
                              .filter(
                                  p ->
                                      p.listKind()
                                          == io.github.nondirectional.docx.core.api.style.ListKind
                                              .NUMBERED)
                              .count();
                      EvalAssert.isTrue(numbered >= 3, "期望至少 3 个有序列表项，实际 " + numbered);
                    }),
                EvalTask.NamedCheck.of(
                    "篇幅达标",
                    doc ->
                        EvalAssert.isTrue(
                            doc.paragraphs().size() >= 10,
                            "期望至少 10 段，实际 " + doc.paragraphs().size())))));

    return List.copyOf(tasks);
  }

  /** 非标题、非列表的正文段落（create 任务「正文两段」类 check 用）。 */
  private static List<Paragraph> nonHeadingNonListParagraphs(Document doc) {
    List<Paragraph> result = new ArrayList<>();
    for (Paragraph p : doc.paragraphs()) {
      if (p.heading() == null && p.listKind() == null && p.text() != null && !p.text().isBlank()) {
        result.add(p);
      }
    }
    return result;
  }

  // ==================== fixture helpers ====================

  private static Paragraph requireParagraph(Document doc, int index, String what) {
    EvalAssert.isTrue(
        doc.paragraphs().size() > index,
        what + "：期望至少 " + (index + 1) + " 段，实际 " + doc.paragraphs().size());
    return doc.paragraphs().get(index);
  }

  private static Table requireTable(Document doc, int index, String what) {
    EvalAssert.isTrue(
        doc.tables().size() > index,
        what + "：期望至少 " + (index + 1) + " 个表格，实际 " + doc.tables().size());
    return doc.tables().get(index);
  }

  private static Table requireLastTable(Document doc, String what) {
    EvalAssert.isTrue(doc.tables().size() > 0, what + "：期望至少 1 个表格，实际 0");
    return doc.tables().get(doc.tables().size() - 1);
  }

  private static void heading(Document doc, HeadingLevel level, String text) {
    doc.addParagraph(text).heading(level);
  }

  private static List<Paragraph> nonBlankBodyParagraphs(Document doc) {
    List<Paragraph> result = new ArrayList<>();
    for (Paragraph p : doc.paragraphs()) {
      if (p.text() != null && !p.text().isBlank()) {
        result.add(p);
      }
    }
    return result;
  }

  private static List<Paragraph> headingParagraphs(Document doc) {
    List<Paragraph> result = new ArrayList<>();
    for (Paragraph p : doc.paragraphs()) {
      if (p.heading() != null) {
        result.add(p);
      }
    }
    return result;
  }

  private static String lastNonBlankText(Document doc) {
    List<Paragraph> paragraphs = doc.paragraphs();
    for (int i = paragraphs.size() - 1; i >= 0; i--) {
      String text = paragraphs.get(i).text();
      if (text != null && !text.isBlank()) {
        return text;
      }
    }
    return "";
  }

  private static int countOccurrences(String text, String needle) {
    if (text == null || text.isEmpty() || needle == null || needle.isEmpty()) {
      return 0;
    }
    int count = 0;
    int index = 0;
    while ((index = text.indexOf(needle, index)) >= 0) {
      count++;
      index += needle.length();
    }
    return count;
  }

  private static boolean containsDigit(String text) {
    if (text == null) {
      return false;
    }
    for (int i = 0; i < text.length(); i++) {
      if (Character.isDigit(text.charAt(i))) {
        return true;
      }
    }
    return false;
  }
}
