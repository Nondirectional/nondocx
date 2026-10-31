package io.github.nondirectional.docx.examples.eval;

import io.github.nondirectional.docx.core.api.Document;
import io.github.nondirectional.docx.core.api.header.Footer;
import io.github.nondirectional.docx.core.api.header.Header;
import io.github.nondirectional.docx.core.api.style.Alignment;
import io.github.nondirectional.docx.core.api.style.HeadingLevel;
import io.github.nondirectional.docx.core.api.table.Cell;
import io.github.nondirectional.docx.core.api.table.Row;
import io.github.nondirectional.docx.core.api.table.Table;
import io.github.nondirectional.docx.core.api.text.Paragraph;
import io.github.nondirectional.docx.core.api.text.Run;
import io.github.nondirectional.docx.core.api.track.PropertyChangeDetails;
import io.github.nondirectional.docx.core.api.track.TextChangeDetails;
import io.github.nondirectional.docx.core.api.track.TrackedChange;
import io.github.nondirectional.docx.core.api.track.TrackedChangeType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测集 baseline 组的 28 条任务（初版 12 条 + P3 系列扩展与探针；计数锁在 {@code EvalTasksTest}）。
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
 *   <li>12：模板填充，检查 fill_template（值/条件块/表格模板行）。
 *   <li>13~22（P3 扩展）：列表/段落删除/歧义指代（按内容特征定位）/重复文本范围限定/ 带格式替换/表格加行/多步组合批/模板假值条件与空列表/同段双修订（同锚点独占）/
 *       全文行距多锚点——覆盖 docs/11 P3 的「歧义指代、重复文本」缺口。
 *   <li>23~26：既有词加粗/修订带格式替换（行内标记链路探针）与页眉写文本（header.set_text 写读同批 + 防退化加粗 run 断言）/以修订方式加粗既有词
 *       （tracked.set_style 的 rPrChange 语义 + 防 tracked.replace 身份替换 workaround）。
 *   <li>27：段内既有词加下划线（{@code <u>} 行内标记；check 用严格子集防「整段加下划线」退化通过，与 bold-existing-phrase 同型； full 面
 *       gap flag 同根因：无 run 拆分）。
 *   <li>28：批注回复（{@code comment.reply} 的一级线程 + 读侧 id 寻址；full 面无回复工具，gap flag）。
 * </ul>
 */
public final class EvalTasks {

  private static final List<EvalTask> TASKS = buildTasks();
  private static final List<EvalTask> CREATE_TASKS = buildCreateTasks();

  private EvalTasks() {}

  /** 返回 baseline 任务组（固定顺序）：对既有文档做编辑的 28 条任务。 */
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
                    })),
            // full 面已知缺口：无批注创作工具（toolkit 仅 view 侧批注观测）
            true));

    tasks.add(
        new EvalTask(
            "fill-template",
            "填充模板占位符",
            "当前文档是一份模板。请用以下数据填充：甲方=「北京启迪科技」，乙方=「华信咨询」，"
                + "urgent=true、deadline=「2026-11-05」，明细为两条：「调研/3」「开发/5」。",
            doc -> {
              doc.addParagraph("甲方：{{party_a}}");
              doc.addParagraph("乙方：{{party_b}}");
              doc.addParagraph("{{#urgent}}");
              doc.addParagraph("加急：请于 {{deadline}} 前交付。");
              doc.addParagraph("{{/urgent}}");
              Table t = doc.addTable();
              t.row(r -> r.cell("事项").cell("人日"));
              t.row(r -> r.cell("{{#items}}{{name}}").cell("{{qty}}"));
            },
            List.of(
                EvalTask.NamedCheck.of(
                    "占位符已填", doc -> EvalAssert.notContains(EvalAssert.bodyText(doc), "{{", "正文")),
                EvalTask.NamedCheck.of(
                    "值正确",
                    doc -> {
                      EvalAssert.contains(
                          requireParagraph(doc, 0, "甲方段").text(), "北京启迪科技", "第 0 段");
                      EvalAssert.contains(requireParagraph(doc, 1, "乙方段").text(), "华信咨询", "第 1 段");
                    }),
                EvalTask.NamedCheck.of(
                    "条件块保留并填充",
                    doc ->
                        EvalAssert.contains(
                            requireParagraph(doc, 2, "加急段").text(), "2026-11-05", "加急段")),
                EvalTask.NamedCheck.of(
                    "表格行已展开",
                    doc -> {
                      Table table = requireTable(doc, 0, "明细表");
                      EvalAssert.isTrue(
                          table.rows().size() >= 3, "期望表头+2 条明细，实际 " + table.rows().size() + " 行");
                      EvalAssert.equal("调研", table.rows().get(1).cells().get(0).text(), "明细 1 事项");
                      EvalAssert.equal("3", table.rows().get(1).cells().get(1).text(), "明细 1 人日");
                      EvalAssert.equal("开发", table.rows().get(2).cells().get(0).text(), "明细 2 事项");
                      EvalAssert.equal("5", table.rows().get(2).cells().get(1).text(), "明细 2 人日");
                    })),
            // full 面已知缺口：无 fill_template，且条件块整段删除需要段落删除工具
            true));

    // ==================== P3 扩展（第 13~22 条）：列表/删除/歧义指代/范围限定/格式/组合 ====================

    tasks.add(
        new EvalTask(
            "insert-list",
            "文末插入列表",
            "在文档末尾追加一个无序列表，包含三项：「模块开发」「接口联调」「文档编写」。",
            doc -> {
              doc.addParagraph("项目背景");
              doc.addParagraph("以下为待办事项。");
            },
            List.of(
                EvalTask.NamedCheck.of(
                    "末尾出现三项列表",
                    doc -> {
                      java.util.List<String> items = trailingListItems(doc);
                      EvalAssert.isTrue(items.size() >= 3, "期望末尾至少 3 个无序列表项，实际 " + items.size());
                      String joined = String.join(",", items);
                      EvalAssert.contains(joined, "模块开发", "列表项");
                      EvalAssert.contains(joined, "接口联调", "列表项");
                      EvalAssert.contains(joined, "文档编写", "列表项");
                    }),
                EvalTask.NamedCheck.of(
                    "原段落未动",
                    doc -> {
                      EvalAssert.equal("项目背景", requireParagraph(doc, 0, "背景段").text(), "第 0 段");
                      EvalAssert.equal("以下为待办事项。", requireParagraph(doc, 1, "引导段").text(), "第 1 段");
                    })),
            // full 面已知缺口：insert_paragraph 无列表参数（§6.3 create 组已证实），正文段无列表工具
            true));

    tasks.add(
        new EvalTask(
            "delete-paragraph",
            "删除指定段落",
            "删除备注段（以「备注：」开头的那段），其余内容保持不变。",
            doc -> {
              doc.addParagraph("项目概述。");
              doc.addParagraph("备注：此段为草稿说明，稍后删除。");
              doc.addParagraph("进度正常。");
            },
            List.of(
                EvalTask.NamedCheck.of(
                    "备注段已删", doc -> EvalAssert.notContains(EvalAssert.bodyText(doc), "备注", "正文")),
                EvalTask.NamedCheck.of(
                    "其余段落原样",
                    doc -> {
                      EvalAssert.isTrue(
                          doc.paragraphs().size() == 2, "期望剩 2 段，实际 " + doc.paragraphs().size());
                      EvalAssert.equal("项目概述。", requireParagraph(doc, 0, "概述段").text(), "第 0 段");
                      EvalAssert.equal("进度正常。", requireParagraph(doc, 1, "进度段").text(), "第 1 段");
                    })),
            // full 面已知缺口：无正文段落删除工具（仅 remove_table_row/cell、tracked run 删除）
            true));

    tasks.add(
        new EvalTask(
            "ambiguous-target",
            "按内容特征定位段落",
            "在提到「超支」的那一段末尾追加一句：「已上报管理层。」。其他段落不要动。",
            doc -> {
              doc.addParagraph("本季度预算执行情况良好。");
              doc.addParagraph("市场费用超支 12%，已提交书面说明。");
              doc.addParagraph("预算调整申请已受理。");
            },
            List.of(
                EvalTask.NamedCheck.of(
                    "目标段已追加",
                    doc -> {
                      Paragraph target = null;
                      for (Paragraph p : doc.paragraphs()) {
                        if (p.text().contains("超支")) {
                          target = p;
                        }
                      }
                      EvalAssert.isTrue(target != null, "超支段不应被删除");
                      EvalAssert.contains(target.text(), "已上报管理层", "超支段");
                      EvalAssert.isTrue(target.text().trim().endsWith("已上报管理层。"), "追加句应在段末");
                    }),
                EvalTask.NamedCheck.of(
                    "其他段未被误改",
                    doc -> {
                      EvalAssert.equal(
                          "本季度预算执行情况良好。", requireParagraph(doc, 0, "首段").text(), "第 0 段");
                      EvalAssert.equal(
                          "预算调整申请已受理。", requireParagraph(doc, 2, "末段").text(), "第 2 段");
                      EvalAssert.notContains(requireParagraph(doc, 0, "首段").text(), "已上报管理层", "首段");
                    }))));

    tasks.add(
        new EvalTask(
            "scoped-replace",
            "只改指定段的重复词",
            "三段里都有「待定」。只把第二段（方案二）里的「待定」改成「已确认」，" + "第一段和第三段保持「待定」不变。",
            doc -> {
              doc.addParagraph("方案一：待定。");
              doc.addParagraph("方案二：待定。");
              doc.addParagraph("方案三：待定。");
            },
            List.of(
                EvalTask.NamedCheck.of(
                    "第二段已改",
                    doc -> {
                      String text = requireParagraph(doc, 1, "方案二段").text();
                      EvalAssert.contains(text, "已确认", "第 1 段");
                      EvalAssert.notContains(text, "待定", "第 1 段");
                    }),
                EvalTask.NamedCheck.of(
                    "一三段未被误改",
                    doc -> {
                      for (int i : new int[] {0, 2}) {
                        String text = requireParagraph(doc, i, "方案段 " + i).text();
                        EvalAssert.contains(text, "待定", "第 " + i + " 段");
                        EvalAssert.notContains(text, "已确认", "第 " + i + " 段");
                      }
                    }))));

    tasks.add(
        new EvalTask(
            "replace-with-format",
            "整段替换为加粗文本",
            "把最后一段替换为加粗的「结论：通过」。",
            doc -> {
              doc.addParagraph("分析过程与数据核验略。");
              doc.addParagraph("结论待定");
            },
            List.of(
                EvalTask.NamedCheck.of(
                    "末段内容正确",
                    doc -> {
                      Paragraph last = requireParagraph(doc, doc.paragraphs().size() - 1, "末段");
                      EvalAssert.equal("结论：通过", last.text().trim(), "末段文本");
                    }),
                EvalTask.NamedCheck.of(
                    "加粗已应用",
                    doc -> {
                      Paragraph last = requireParagraph(doc, doc.paragraphs().size() - 1, "末段");
                      EvalAssert.assertBold(last, true, "末段");
                    }),
                EvalTask.NamedCheck.of(
                    "段落数不变（替换而非追加）",
                    doc ->
                        EvalAssert.isTrue(
                            doc.paragraphs().size() == 2,
                            "期望 2 段，实际 " + doc.paragraphs().size())))));

    tasks.add(
        new EvalTask(
            "table-add-row",
            "表格末尾加一行",
            "在表格末尾追加一行数据：「编译器 / 进行中」。表格其他内容不变。",
            doc -> {
              doc.addParagraph("进展如下：");
              Table t = doc.addTable();
              t.row(r -> r.cell("模块").cell("状态"));
              t.row(r -> r.cell("解析器").cell("完成"));
            },
            List.of(
                EvalTask.NamedCheck.of(
                    "新行已追加",
                    doc -> {
                      Table table = requireTable(doc, 0, "进展表");
                      EvalAssert.isTrue(
                          table.rows().size() == 3, "期望 3 行，实际 " + table.rows().size());
                      EvalAssert.equal("编译器", table.rows().get(2).cells().get(0).text(), "新行模块");
                      EvalAssert.equal("进行中", table.rows().get(2).cells().get(1).text(), "新行状态");
                    }),
                EvalTask.NamedCheck.of(
                    "原有行不变",
                    doc -> {
                      Table table = requireTable(doc, 0, "进展表");
                      EvalAssert.equal("模块", table.rows().get(0).cells().get(0).text(), "表头");
                      EvalAssert.equal("解析器", table.rows().get(1).cells().get(0).text(), "第 1 行模块");
                      EvalAssert.equal("完成", table.rows().get(1).cells().get(1).text(), "第 1 行状态");
                    })),
            // full 面已知缺口：add_table_row 追加的单元格是 0 段落真空结构（poi-bridge N2），
            // 无「单元格内建段落」工具可填充——模型陷入不可能状态后破坏性重建（runId 728 trace）
            true));

    tasks.add(
        new EvalTask(
            "multi-step-combo",
            "一次完成三处修改",
            "对文档做三处修改：①标题改为居中；②给正文段加一条批注（作者「审查员」，"
                + "内容提到「核实」）；③文档末尾插入一个 2 列表格（表头：事项、结论，"
                + "数据行：评审、通过）。",
            doc -> {
              doc.addParagraph("项目进展").heading(HeadingLevel.H1);
              doc.addParagraph("各项工作推进顺利。");
            },
            List.of(
                EvalTask.NamedCheck.of(
                    "标题已居中",
                    doc ->
                        EvalAssert.equal(
                            Alignment.CENTER, requireParagraph(doc, 0, "标题段").alignment(), "标题对齐")),
                EvalTask.NamedCheck.of(
                    "批注已加",
                    doc -> {
                      Paragraph body = requireParagraph(doc, 1, "正文段");
                      EvalAssert.isTrue(!body.comments().isEmpty(), "期望正文段有批注");
                      EvalAssert.equal("审查员", body.comments().get(0).author(), "批注作者");
                      EvalAssert.contains(body.comments().get(0).text(), "核实", "批注内容");
                    }),
                EvalTask.NamedCheck.of(
                    "表格已插入",
                    doc -> {
                      Table table = requireTable(doc, 0, "评审表");
                      EvalAssert.equal("事项", table.rows().get(0).cells().get(0).text(), "表头 1");
                      EvalAssert.equal("结论", table.rows().get(0).cells().get(1).text(), "表头 2");
                      EvalAssert.equal("评审", table.rows().get(1).cells().get(0).text(), "数据 1");
                      EvalAssert.equal("通过", table.rows().get(1).cells().get(1).text(), "数据 2");
                    })),
            // full 面已知缺口：批注部分（无批注创作工具；居中/插表两步可达成）
            true));

    tasks.add(
        new EvalTask(
            "fill-template-conditional-false",
            "模板填充（假值条件与空列表）",
            "当前文档是模板。用以下数据填充：name=「数据中台项目」，draft=false，" + "confidential=true，approvers 为空列表（[]）。",
            doc -> {
              doc.addParagraph("项目：{{name}}");
              doc.addParagraph("{{#draft}}");
              doc.addParagraph("草稿状态段落。");
              doc.addParagraph("{{/draft}}");
              doc.addParagraph("{{#confidential}}");
              doc.addParagraph("本文档保密，仅限项目组。");
              doc.addParagraph("{{/confidential}}");
              Table t = doc.addTable();
              t.row(r -> r.cell("事项").cell("负责人"));
              t.row(r -> r.cell("{{#approvers}}{{person}}").cell("已审"));
            },
            List.of(
                EvalTask.NamedCheck.of(
                    "占位符已清零", doc -> EvalAssert.notContains(EvalAssert.bodyText(doc), "{{", "正文")),
                EvalTask.NamedCheck.of(
                    "假值块已删、真值块保留",
                    doc -> {
                      EvalAssert.notContains(EvalAssert.bodyText(doc), "草稿状态段落", "正文");
                      EvalAssert.notContains(EvalAssert.bodyText(doc), "draft", "正文");
                      EvalAssert.contains(EvalAssert.bodyText(doc), "本文档保密", "正文");
                      EvalAssert.contains(EvalAssert.bodyText(doc), "数据中台项目", "正文");
                    }),
                EvalTask.NamedCheck.of(
                    "空列表模板行已删",
                    doc -> {
                      Table table = requireTable(doc, 0, "审批表");
                      EvalAssert.isTrue(
                          table.rows().size() == 1, "期望只剩表头 1 行，实际 " + table.rows().size());
                      EvalAssert.equal("事项", table.rows().get(0).cells().get(0).text(), "表头");
                    })),
            // full 面已知缺口：同 fill-template（无 fill_template + 无段落/表格行删除组合）
            true));

    tasks.add(
        new EvalTask(
            "tracked-insert-delete",
            "修订模式插入并删除",
            "以修订模式完成两处修改，作者都填「审查员」：①删除段落开头的「草稿：」前缀；" + "②在同一段末尾追加「（已复核。）」。",
            doc -> doc.addParagraph("草稿：本文档为初版说明。"),
            List.of(
                EvalTask.NamedCheck.of(
                    "存在删除修订",
                    doc -> {
                      boolean found = false;
                      for (TrackedChange change : doc.trackedChanges().list()) {
                        if (change.type() == TrackedChangeType.DEL
                            && "审查员".equals(change.author())) {
                          found = true;
                        }
                      }
                      EvalAssert.isTrue(found, "期望存在作者=审查员的删除修订");
                    }),
                EvalTask.NamedCheck.of(
                    "存在插入修订",
                    doc -> {
                      boolean found = false;
                      for (TrackedChange change : doc.trackedChanges().list()) {
                        if (change.type() == TrackedChangeType.INS
                            && "审查员".equals(change.author())) {
                          found = true;
                        }
                      }
                      EvalAssert.isTrue(found, "期望存在作者=审查员的插入修订");
                    }),
                EvalTask.NamedCheck.of(
                    "正文可见文本正确",
                    doc -> {
                      // tracked del 后「草稿：」从 text() 消失；ins 内容对 text() 不可见
                      EvalAssert.notContains(EvalAssert.bodyText(doc), "草稿", "正文");
                      EvalAssert.contains(EvalAssert.bodyText(doc), "本文档为初版说明。", "正文");
                    }))));

    tasks.add(
        new EvalTask(
            "line-spacing-all",
            "全文行距统一",
            "把全文段落的行距统一改为 2 倍。",
            doc -> {
              doc.addParagraph("第一段：默认行距。");
              doc.addParagraph("第二段：默认行距。");
              doc.addParagraph("第三段：默认行距。");
            },
            List.of(
                EvalTask.NamedCheck.of(
                    "全部段落 2 倍行距",
                    doc -> {
                      for (int i = 0; i < doc.paragraphs().size(); i++) {
                        Paragraph p = requireParagraph(doc, i, "第 " + i + " 段");
                        EvalAssert.closeTo(2.0, p.lineSpacing(), 0.01, "第 " + i + " 段行距");
                      }
                    }),
                EvalTask.NamedCheck.of(
                    "正文未被误改", doc -> EvalAssert.contains(EvalAssert.bodyText(doc), "第一段", "正文")))));

    tasks.add(
        new EvalTask(
            "bold-existing-phrase",
            "段内既有词加粗",
            "把第二段中的「逾期率」一词加粗显示，其他内容一律保持不变。",
            doc -> {
              doc.addParagraph("首段：本月经营情况概述。");
              doc.addParagraph("本月风险指标：逾期率 3.2%，环比上升 0.4 个百分点。");
            },
            List.of(
                EvalTask.NamedCheck.of(
                    "目标词已加粗",
                    doc -> {
                      Paragraph p = requireParagraph(doc, 1, "第二段");
                      boolean hit = false;
                      for (Run run : p.runs()) {
                        if (run.text().contains("逾期率")) {
                          hit = true;
                          EvalAssert.isTrue(run.isBold(), "含「逾期率」的 run「" + run.text() + "」应为粗体");
                        }
                      }
                      EvalAssert.isTrue(hit, "未找到含「逾期率」的 run");
                      // 严格子集：段内还有目标词以外的文字时，必须存在非粗体 run——
                      // 整段唯一 run 全加粗（无 run 拆分能力的工具面）不算「只加粗该词」
                      boolean anyPlain = false;
                      for (Run run : p.runs()) {
                        if (!run.text().isBlank() && !run.isBold()) {
                          anyPlain = true;
                          break;
                        }
                      }
                      EvalAssert.isTrue(anyPlain, "整段非空 run 全部加粗——目标词以外的文字也被加粗（未做 run 拆分）");
                    }),
                EvalTask.NamedCheck.of(
                    "其余文字未被误加粗",
                    doc -> {
                      Paragraph p = requireParagraph(doc, 1, "第二段");
                      for (Run run : p.runs()) {
                        if (!run.text().isBlank() && !run.text().contains("逾期率")) {
                          EvalAssert.isTrue(!run.isBold(), "不含目标词的 run「" + run.text() + "」不应被加粗");
                        }
                      }
                    }),
                EvalTask.NamedCheck.of(
                    "段落文本未变",
                    doc ->
                        EvalAssert.equal(
                            "本月风险指标：逾期率 3.2%，环比上升 0.4 个百分点。",
                            requireParagraph(doc, 1, "第二段").text().trim(), "第二段文本"))),
            // full 面已知缺口：无 run 拆分能力，只能整 run 加粗（严格子集 check 必然失败）
            true));

    tasks.add(
        new EvalTask(
            "tracked-replace-format",
            "修订模式带格式替换",
            "以修订模式把「待定」替换为加粗的「通过」，修订作者填「审查员」。",
            doc -> doc.addParagraph("结论：待定。"),
            List.of(
                EvalTask.NamedCheck.of(
                    "存在删除修订",
                    doc -> {
                      boolean found = false;
                      for (TrackedChange change : doc.trackedChanges().list()) {
                        if (change.type() == TrackedChangeType.DEL
                            && "审查员".equals(change.author())) {
                          found = true;
                        }
                      }
                      EvalAssert.isTrue(found, "期望存在作者=审查员的删除修订");
                    }),
                EvalTask.NamedCheck.of(
                    "插入修订文本正确",
                    doc -> {
                      TrackedChange ins = null;
                      for (TrackedChange change : doc.trackedChanges().list()) {
                        if (change.type() == TrackedChangeType.INS
                            && "审查员".equals(change.author())) {
                          ins = change;
                        }
                      }
                      EvalAssert.isTrue(ins != null, "期望存在作者=审查员的插入修订");
                      String text = ((TextChangeDetails) ins.details()).text();
                      EvalAssert.contains(text, "通过", "插入修订文本");
                      // 行内标记必须被解析——字面星号 = 没吃到内容语言
                      EvalAssert.notContains(text, "*", "插入修订文本");
                    }),
                EvalTask.NamedCheck.of(
                    "插入修订已加粗",
                    doc -> {
                      TrackedChange ins = null;
                      for (TrackedChange change : doc.trackedChanges().list()) {
                        if (change.type() == TrackedChangeType.INS) {
                          ins = change;
                        }
                      }
                      EvalAssert.isTrue(ins != null, "期望存在插入修订");
                      // ins 内 run 不被 paragraph.runs() 暴露，经 raw() 断言非空 run 全部加粗
                      var runs = ins.raw().getRList();
                      EvalAssert.isTrue(runs.size() > 0, "插入修订内应有 run");
                      for (var run : runs) {
                        boolean bold = run.isSetRPr() && run.getRPr().sizeOfBArray() > 0;
                        EvalAssert.isTrue(bold, "插入修订的 run 应为粗体（文本: " + run + "）");
                      }
                    }),
                EvalTask.NamedCheck.of(
                    "正文未被非修订修改",
                    doc -> {
                      // eval 读 save+reopen 后文档：del 文本永不进 text()，ins 文本在 reopen 后
                      // 进入 text()（live/reopen 语义差异，TrackedAuthoringTest 锁定）
                      EvalAssert.equal(
                          "结论：通过。", requireParagraph(doc, 0, "结论段").text(), "第 0 段可见文本");
                    }))));

    tasks.add(
        new EvalTask(
            "header-write-text",
            "页眉写入文本",
            "在页眉写入一行文本：「项目季度报告——内部资料」，其中「内部资料」加粗显示。",
            // seed 无页眉：「页眉存在」check 在 seed 上失败（防恒真）
            doc -> doc.addParagraph("正文"),
            List.of(
                EvalTask.NamedCheck.of(
                    "页眉存在", doc -> EvalAssert.isTrue(doc.header() != null, "期望存在页眉")),
                EvalTask.NamedCheck.of(
                    "正文不被误改",
                    doc -> EvalAssert.equal("正文", requireParagraph(doc, 0, "正文").text(), "第 0 段")),
                EvalTask.NamedCheck.of(
                    "页眉含文本且指定词加粗",
                    doc -> {
                      Header header = doc.header();
                      EvalAssert.isTrue(header != null, "期望存在页眉");
                      String text = header == null ? "" : header.text();
                      EvalAssert.contains(text, "项目季度报告", "页眉文本");
                      EvalAssert.contains(text, "内部资料", "页眉文本");
                      // 行内标记必须被解析——字面星号 = 没吃到内容语言
                      EvalAssert.notContains(text, "*", "页眉文本");
                      if (header == null) {
                        return;
                      }
                      boolean boldHit = false;
                      for (Paragraph p : header.paragraphs()) {
                        for (Run run : p.runs()) {
                          if (run.text().contains("内部资料")) {
                            boldHit = true;
                            EvalAssert.isTrue(run.isBold(), "含「内部资料」的 run「" + run.text() + "」应为粗体");
                          }
                        }
                      }
                      EvalAssert.isTrue(boldHit, "未找到含「内部资料」的 run（页眉文本「" + text + "」）");
                      // 严格子集：页眉还有目标词以外的文字 ⇒ 必须存在非粗体 run——
                      // 整行唯一 run 全加粗不算「只加粗指定词」（bold-existing-phrase 同型防退化）
                      boolean anyPlain = false;
                      for (Paragraph p : header.paragraphs()) {
                        for (Run run : p.runs()) {
                          if (!run.text().isBlank() && !run.isBold()) {
                            anyPlain = true;
                            break;
                          }
                        }
                      }
                      EvalAssert.isTrue(anyPlain, "页眉非空 run 全部加粗——目标词以外的文字也被加粗");
                    })),
            // full 面已知缺口：写工具 ref 只解析正文（页眉物理不可达），与 footer-page-number 同型
            true));

    tasks.add(
        new EvalTask(
            "tracked-bold-phrase",
            "以修订方式加粗既有词",
            // 不提工具名（spec 硬约束）；「以修订方式…保留修订痕迹」是 tracked 族语义的自然语言表达
            "以修订方式把第二段中的「逾期率」一词加粗，保留修订痕迹供审阅者确认，其他内容一律保持不变。",
            // seed 与 bold-existing-phrase 同构：目标词与邻文同 run（full 面无 run 拆分，物理不可达）
            doc -> {
              doc.addParagraph("首段：本月经营情况概述。");
              doc.addParagraph("本月风险指标：逾期率 3.2%，环比上升 0.4 个百分点。");
            },
            List.of(
                EvalTask.NamedCheck.of(
                    "存在加粗属性修订且无删除修订",
                    doc -> {
                      // save+reopen 口径：RPR_CHANGE 存在（新摘要含 b、旧不含 b、作者非空）；
                      // 无 DEL 修订堵 tracked.replace「身份替换+加粗」的退化 workaround（§6.14 实证可冒充）
                      int delCount = 0;
                      boolean rprFound = false;
                      for (TrackedChange change : doc.trackedChanges().list()) {
                        if (change.type() == TrackedChangeType.DEL) {
                          delCount++;
                        }
                        if (change.type() == TrackedChangeType.RPR_CHANGE
                            && change.details() instanceof PropertyChangeDetails) {
                          PropertyChangeDetails p = (PropertyChangeDetails) change.details();
                          if (p.newSummary().contains("b")
                              && !p.oldSummary().contains("b")
                              && !change.author().isBlank()) {
                            rprFound = true;
                          }
                        }
                      }
                      EvalAssert.isTrue(
                          rprFound,
                          "期望存在 rPrChange 属性修订（新摘要含 b、旧摘要不含 b、作者非空）——" + "以修订加粗既有词应留下格式变更修订");
                      EvalAssert.isTrue(
                          delCount == 0,
                          "不应存在删除修订（期望 rPrChange 格式修订，而非 tracked.replace 身份替换），"
                              + "实际有 "
                              + delCount
                              + " 条");
                    }),
                EvalTask.NamedCheck.of(
                    "目标词已加粗（严格子集）",
                    doc -> {
                      Paragraph p = requireParagraph(doc, 1, "第二段");
                      boolean hit = false;
                      for (Run run : p.runs()) {
                        if (run.text().contains("逾期率")) {
                          hit = true;
                          EvalAssert.isTrue(run.isBold(), "含「逾期率」的 run「" + run.text() + "」应为粗体");
                        }
                      }
                      EvalAssert.isTrue(hit, "未找到含「逾期率」的 run");
                      // 严格子集：段内还有目标词以外的文字时，必须存在非粗体 run——
                      // 整段唯一 run 全加粗（无 run 拆分能力的工具面）不算「只加粗该词」
                      boolean anyPlain = false;
                      for (Run run : p.runs()) {
                        if (!run.text().isBlank() && !run.isBold()) {
                          anyPlain = true;
                          break;
                        }
                      }
                      EvalAssert.isTrue(anyPlain, "整段非空 run 全部加粗——目标词以外的文字也被加粗（未做 run 拆分）");
                    }),
                EvalTask.NamedCheck.of(
                    "其余文字未被误加粗",
                    doc -> {
                      Paragraph p = requireParagraph(doc, 1, "第二段");
                      for (Run run : p.runs()) {
                        if (!run.text().isBlank() && !run.text().contains("逾期率")) {
                          EvalAssert.isTrue(!run.isBold(), "不含目标词的 run「" + run.text() + "」不应被加粗");
                        }
                      }
                    }),
                EvalTask.NamedCheck.of(
                    "段落文本未变",
                    doc ->
                        EvalAssert.equal(
                            "本月风险指标：逾期率 3.2%，环比上升 0.4 个百分点。",
                            requireParagraph(doc, 1, "第二段").text().trim(), "第二段文本"))),
            // full 面已知缺口：mark_style_change_tracked 按 run_index 定位、无 run 拆分
            // （bold-existing-phrase 同根因）；compiler 面 tracked.set_style 可过
            true));

    tasks.add(
        new EvalTask(
            "underline-existing-phrase",
            "段内既有词加下划线",
            "给第二段中的「逾期率」一词加下划线，其他内容一律保持不变。",
            doc -> {
              doc.addParagraph("首段：本月经营情况概述。");
              doc.addParagraph("本月风险指标：逾期率 3.2%，环比上升 0.4 个百分点。");
            },
            List.of(
                EvalTask.NamedCheck.of(
                    "目标词已加下划线",
                    doc -> {
                      Paragraph p = requireParagraph(doc, 1, "第二段");
                      boolean hit = false;
                      for (Run run : p.runs()) {
                        if (run.text().contains("逾期率")) {
                          hit = true;
                          EvalAssert.isTrue(
                              run.isUnderline(), "含「逾期率」的 run「" + run.text() + "」应带下划线");
                        }
                      }
                      EvalAssert.isTrue(hit, "未找到含「逾期率」的 run");
                      // 严格子集：段内还有目标词以外的文字时，必须存在非下划线 run——
                      // 整段唯一 run 全加下划线（无 run 拆分能力的工具面）不算「只给该词加下划线」
                      boolean anyPlain = false;
                      for (Run run : p.runs()) {
                        if (!run.text().isBlank() && !run.isUnderline()) {
                          anyPlain = true;
                          break;
                        }
                      }
                      EvalAssert.isTrue(anyPlain, "整段非空 run 全部加下划线——目标词以外的文字也被加下划线（未做 run 拆分）");
                    }),
                EvalTask.NamedCheck.of(
                    "其余文字未被误加下划线",
                    doc -> {
                      Paragraph p = requireParagraph(doc, 1, "第二段");
                      for (Run run : p.runs()) {
                        if (!run.text().isBlank() && !run.text().contains("逾期率")) {
                          EvalAssert.isTrue(
                              !run.isUnderline(), "不含目标词的 run「" + run.text() + "」不应带下划线");
                        }
                      }
                    }),
                EvalTask.NamedCheck.of(
                    "段落文本未变",
                    doc ->
                        EvalAssert.equal(
                            "本月风险指标：逾期率 3.2%，环比上升 0.4 个百分点。",
                            requireParagraph(doc, 1, "第二段").text().trim(), "第二段文本"))),
            // full 面已知缺口：update_run_style 按 run 定位、无 run 拆分，只能整 run 加下划线
            // （bold-existing-phrase 同根因）；compiler 面 <u> 行内标记可过
            true));

    tasks.add(
        new EvalTask(
            "comment-reply",
            "回复已有批注",
            "第二段已有一条「审查员」的批注。请以「复核员」身份回复这条批注，" + "说明同意但要求补充上季度数据。不要修改正文。",
            doc -> {
              doc.addParagraph("项目背景");
              Paragraph budget = doc.addParagraph("本季度预算为一百五十万元。");
              budget.addComment("审查员", "请核实该预算金额的数据来源。");
              doc.addParagraph("执行计划");
            },
            List.of(
                EvalTask.NamedCheck.of(
                    "存在回复批注",
                    doc ->
                        EvalAssert.isTrue(
                            doc.comments().list().stream().anyMatch(c -> c.parentId().isPresent()),
                            "期望至少 1 条回复批注（parentId 非空），实际 0 条")),
                EvalTask.NamedCheck.of(
                    "回复作者正确",
                    doc -> {
                      boolean found = false;
                      for (io.github.nondirectional.docx.core.api.comment.Comment c :
                          doc.comments().list()) {
                        if (c.parentId().isPresent() && "复核员".equals(c.author())) {
                          found = true;
                          break;
                        }
                      }
                      EvalAssert.isTrue(found, "期望存在作者为「复核员」的回复批注");
                    }),
                EvalTask.NamedCheck.of(
                    "回复提到上季度数据",
                    doc -> {
                      boolean found = false;
                      for (io.github.nondirectional.docx.core.api.comment.Comment c :
                          doc.comments().list()) {
                        if (c.parentId().isPresent()
                            && c.text() != null
                            && c.text().contains("上季度")) {
                          found = true;
                          break;
                        }
                      }
                      EvalAssert.isTrue(found, "期望回复内容包含「上季度」");
                    }),
                EvalTask.NamedCheck.of(
                    "回复挂在审查员根批注下",
                    doc -> {
                      io.github.nondirectional.docx.core.api.comment.Comment root = null;
                      io.github.nondirectional.docx.core.api.comment.Comment reply = null;
                      for (io.github.nondirectional.docx.core.api.comment.Comment c :
                          doc.comments().list()) {
                        if (c.parentId().isEmpty() && "审查员".equals(c.author())) {
                          root = c;
                        } else if (c.parentId().isPresent() && "复核员".equals(c.author())) {
                          reply = c;
                        }
                      }
                      EvalAssert.isTrue(root != null, "期望存在作者为「审查员」的根批注（seed 原有）");
                      EvalAssert.isTrue(reply != null, "期望存在作者为「复核员」的回复批注");
                      EvalAssert.equal(
                          root.id(),
                          reply.parentId().orElse(""),
                          "回复批注的 parentId（应指向审查员根批注 #" + root.id() + "）");
                    }),
                EvalTask.NamedCheck.of(
                    "正文与根批注未被修改",
                    doc -> {
                      EvalAssert.equal("项目背景", requireParagraph(doc, 0, "背景段").text(), "第 0 段");
                      EvalAssert.equal(
                          "本季度预算为一百五十万元。", requireParagraph(doc, 1, "预算段").text(), "第 1 段");
                      EvalAssert.equal("执行计划", requireParagraph(doc, 2, "计划段").text(), "第 2 段");
                      io.github.nondirectional.docx.core.api.comment.Comment root = null;
                      for (io.github.nondirectional.docx.core.api.comment.Comment c :
                          doc.comments().list()) {
                        if (c.parentId().isEmpty() && "审查员".equals(c.author())) {
                          root = c;
                        }
                      }
                      EvalAssert.isTrue(root != null, "期望根批注仍在（回复不应替换根批注）");
                      EvalAssert.equal("请核实该预算金额的数据来源。", root.text(), "审查员根批注内容");
                    })),
            // full 面已知缺口：无批注回复工具（full 面只有批注只读观测；回复与 comment.add 同属缺失的创作能力）
            true));

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

  /** 从末尾向前收集连续的无序列表项文本（遇到非列表段即停），供 insert-list 类 check。 */
  private static java.util.List<String> trailingListItems(Document doc) {
    java.util.List<String> items = new ArrayList<>();
    List<Paragraph> paras = doc.paragraphs();
    for (int i = paras.size() - 1; i >= 0; i--) {
      Paragraph p = paras.get(i);
      if (p.listKind() == io.github.nondirectional.docx.core.api.style.ListKind.BULLET) {
        items.add(0, p.text());
      } else {
        break;
      }
    }
    return items;
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
