# Research: eval 相近任务样例与新增任务的命名入组惯例

- Query: 页脚写文本/页码任务的定义样例；新「页眉写文本」任务应如何命名入组
- Scope: internal
- Date: 2026-10-31

## Findings

### 最相近样例：footer-page-number（EvalTasks.java line 361-384）

baseline 组第 10 条（组内顺序即 List.add 顺序）。完整结构：

```java
tasks.add(
    new EvalTask(
        "footer-page-number",              // id：kebab-case，对象-动作式
        "页脚插入页码",                     // title
        "在页脚插入页码，格式为「第 X 页」。", // prompt：自然语言，不提工具名
        doc -> {                            // seed：正文一段 + 已存在的页脚占位
          doc.addParagraph("正文");
          doc.ensureFooter().addParagraph().addRun("页脚");
        },
        List.of(
            EvalTask.NamedCheck.of("页脚存在",
                doc -> EvalAssert.isTrue(doc.footer() != null, "期望存在页脚")),
            EvalTask.NamedCheck.of("正文不被误改",
                doc -> EvalAssert.equal("正文", requireParagraph(doc, 0, "正文").text(), "第 0 段")),
            EvalTask.NamedCheck.of("页脚含页码",
                doc -> { /* footer.text() 含「第」+ containsDigit */ })),
        true));                             // capabilityGap=true（full 面无页脚写）
```

check 三件套模式：**容器存在 → 正文不被误改（防错目标，§6.12 footer trace 的教训）→ 内容关键词**。seed 里的 `doc.ensureFooter().addParagraph().addRun("页脚")` 保证「页脚存在」check 在 seed 上**通过**而内容 check 失败（防恒真锁由后两条承担）。

### 命名与入组惯例（从既有 29 条任务归纳）

1. **id**：kebab-case；既有语义模式为「动作-对象」（append-paragraph、delete-paragraph、insert-list）或「对象-动作/属性」（footer-page-number、table-add-row、heading-font、line-spacing-all、body-font-line-spacing）。页脚写文本的对应命名空间是 `footer-*`，页眉任务对称取 `header-*` 前缀（如 header-write-text 形态，具体名由主会话定）。id 一旦入组即稳定（报告按 id 追踪、--tasks 过滤、capabilityGap 清单 containsExactly 顺序敏感）。
2. **入组位置**：`buildTasks()`（baseline 组）内按落地批次 **append 到列表尾部**——先例：P3 扩展 10 条整体追加在 fill-template 之后（line 489 注释「P3 扩展（第 13~22 条）」）；`tracked-replace-format`（line 857-917）作为 §6.13 的探针追加为第 24 条。create 组 id 另有 `create-` 前缀强制（EvalTasksTest line 46）。
3. **capabilityGap 判定流程**（详见 docs11-sections-map.md §6.12）：复跑 full 面 → 逐失败 trace 归因 →「静态能力缺口（物理不可达）」才 flag；存在 workaround 可达 = 行为失败不 flag。页眉写现状：**两面皆无 op**（spec agent-single「已知边界：页眉写无 op（两面）」）——compiler 面 op 落地后任务可过；full 面无页眉写工具且写 ref 只解析正文（ref 容器边界），若复跑 full 面该任务按 footer-page-number 先例属 flag 候选，且 `marksKnownCapabilityGapTasks` 的 containsExactly 清单要同步（插入位置=任务在 EvalTasks 中的定义序）。
4. **prompt 硬约束**：不得提示用哪个工具（spec agent-single ~line 209）。
5. **check 硬约束**：a) seed 上至少一 check 失败（EvalTasksTest everyTaskFailsAtLeastOneCheckOnUnmodifiedSeed）；b) 防退化通过——「能力不够的面会用什么退化解满足这条断言？」（bold-existing-phrase 严格子集断言先例，line 827-836）；c) 断言读 save+reopen 后文档（reopen 口径，§6.13）。
6. **计数锁联动**：`hasTwentyFourUniqueTasks` 的 24 与方法名数字改 25；spec agent-single baseline 条数描述与 docs/11 新小节同批更新。

### 其他可参照的「容器/外部区域」任务

- `multi-step-combo`（line 668-703）：多目标混合任务（含批注部分缺口的拆步归因写法）。
- `tracked-insert-delete`（line 744-781）：同段双修订 + 独占拒绝行为的探针任务（check 针对「作者正确」的循环写法）。
- `comment-add`（line 386-442）：批注创作任务的 check 五件套（存在/作者/内容关键词/锚定段/正文未修改）——「写读同批」先例 §6.7 的配套任务。

## Caveats / Not Found

- eval 中没有已存在的「页眉」字样任务（grep header/页眉 无命中，EvalTasks 仅 footer-page-number 涉及正文外容器）。
- 若页眉任务 seed 需要「无页眉」起点：`doc.header()` 只读返回 null，seed 直接不建页眉即可（与 footer-page-number 的 seed 先建占位不同，取决于 check 设计——防恒真要求至少一条 check 在 seed 上失败，无页眉起点天然满足「页眉存在」类 check）。
