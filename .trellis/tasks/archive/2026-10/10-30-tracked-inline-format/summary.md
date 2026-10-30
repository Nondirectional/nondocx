# 总结：tracked op 行内格式

## 交付

- **core**：`addInsertion(author, texts)` / `replaceTrackedRange(author, start, end,
  texts)` 多 run 变体——一条 `<w:ins>` 内每段文本一个 run（读侧聚合一条 INS、文本拼接），
  replace 版每 run 继承被替换范围首 run 完整 rPr；单文本版委托多 run 版。TrackedChangeNodes
  对应 `addInsertion`/`addInsertionAfter` 的 List 重载。
- **PatchEngine**：tracked.insert/replace 的 `text` 解析行内标记（内容语言统一，全链路
  再无字面文本路径），span 逐 run 叠加粗/斜/代码字体；纯文本路径不变（测试锁定）。
- **修订观测面**：outline `recent[]` 每条与 REVISION 详情恒带 `bold`/`italic`（经 raw()
  读 ins/del 内 CTR rPr 聚合，false 也是证据）——修订内 run 不被 runs() 暴露（N14），
  复审核对修订格式此前无证据可查。
- eval 探针 `tracked-replace-format` 入组（baseline 24）；提示词/工具描述/文档同步。

## 踩实的三个坑（都进了 spec）

1. **accept unwrap 游标失效**（多 run ins 踩出）：`child.moveXml + toNextSibling` 循环在
   首个 moveXml 后游标失效，后续 run 随 wrapper 被 `removeXml` 删——**accept 丢文本**。
   单 run 时代从未暴露。修法：run 引用快照 + 逐 run 独立 cursor。
2. **`text()` live/reopen 语义不同**（eval 实证）：del 永不进 text()；ins live 不可见
   （POI getText 按 iruns，手术新增 ins 不入 live iruns）、reopen 后进入（iruns 重建自
   XML）。旧 javadoc「不含 ins 文本」是 live 口径被当成普适——已修正 core javadoc +
   docs/07 + eval check（按 reopen 口径「结论：通过。」）。
3. **复审格式证据缺口**（§6.6 同族第三次）：首跑模型第一个 op 即正确，但复审在
   annotated/run 视图查 bold 落空 → 误报「未加粗」→ 模型连插 6 条纯文本 ins 污染文档
   （runId `20261030-151033-643`）。修订观测面补 bold/italic 后一步收敛。

## 顺手修复

- heading-font 回归（提示词加句 → temp 0 轨迹漂移 → `style=font=黑体` 20 次 dry_run
  循环）：未知 style 错误提示补「字体不写进 style——标题黑体 = style=heading +
  style_profile=report-cn」。

## 结果

- 测试：core 364（+3）/ toolkit 271（+3）/ examples 19（24 条任务+防恒真）/ demo 47；
  Spotless 通过。
- eval runId `20261030-152310-495`（compiler 8 工具，27B，temp 0）：**24/24，平均 4.21**。
  tracked-replace-format 4 次：首个 op `tracked.replace text=**通过**` 即正确，复审引用
  「ins，bold=true」判达成。`validate_document` 全程 0 调用；23 条回归零退化。
  （过程 runId：`20261030-150016-660` 22/24 → `20261030-151033-643` 23/24 → 终版 24/24。）
