# tracked op 行内格式

## Goal

修订模式 op 的 text 是全链路最后一条按字面处理的文本路径：tracked.insert/replace 写
`**粗体**` 会插进字面星号，且 ins run 不被 paragraph.runs() 暴露、无绕行。让
tracked.insert/tracked.replace 的 text 复用 parseInline（`**粗**`/`*斜*`/`代码`），
多 span 生成带 rPr 的 ins run；加 eval 探针任务验证。

## Requirements

1. core 多 run 修订变体：`addInsertion(author, texts)` / `replaceTrackedRange(author,
   start, end, texts)`——一条 `<w:ins>` 内每段文本一个 run（读侧仍一条 INS、文本拼接），
   replace 版每 run 继承被替换范围首 run 的完整 rPr；返回全部 run 供逐个设样式。
2. PatchEngine：tracked.insert/replace 的 text 解析行内标记，span 逐 run 叠加；纯文本
   路径行为不变。
3. eval 探针 `tracked-replace-format`（baseline 24）：以修订把「待定」替换为加粗的
   「通过」。
4. 修订观测面：修订内 run 不被 runs() 暴露（N14）→ 复审核对修订格式无证据——outline
   recent 条目与 REVISION 详情恒带 bold/italic。

## Acceptance Criteria

- [x] core 364（+3：多 run addInsertion 聚合+独立样式 save+reopen、入参校验、多 run
      replaceTrackedRange 继承 rPr + live/reopen text() 语义锁定）。
- [x] toolkit 271（+3：tracked replace/insert 行内标记单条 INS 多 run、accept 后正文
      带格式；view 修订 bold/italic 证据；含既有回归）。
- [x] eval 24/24，平均 4.21（runId `20261030-152310-495`）：tracked-replace-format
      首个 op 即正确，复审引用「ins，bold=true」判达成；23 条回归零退化。
- [x] 踩实并修复两个旧坑：accept unwrap 游标失效（多 run ins 丢文本）；text() 的
      live/reopen 语义差异（旧 javadoc 断言有误，已修正并写进 docs/07）。
- [x] Spotless 通过；docs/03/07/11 §6.13、spec 同步。

## Out of Scope

- 不做「对既有文字的格式变更本身算修订」（rPrChange，full 面
  mark_style_change_tracked 已有）。
- 不改 live text() 语义（会破坏 tracked 手术路径的 runs/text 映射校验）。
