# review 可观测性：view_element 暴露 alignment，根治 insert-centered-title 循环

## Goal

`view_element` 的段落详情属性增加 `alignment`（与 `{align=…}` 记号同名：left/center/right/both），
让只读复审 SubAgent 能看到对齐——根治 `insert-centered-title` 的 11~26 次调用循环；同步修正
docs/11 与 spec 里「记号混淆为主因」的旧归因。

## 证据链（runId `20261030-090245-055`，24 次调用）

1. **第 2 次调用就正确**：`insert start "# 项目周汇报 {align=center}"` → `fillHeading`
   显式 align 优先，H1+居中一次到位（评测 check 从此刻起即通过）。
2. **循环驱动者是复审 SubAgent**：8 次 `review_intent` 全部返回「部分达成：该标题段落
   未设置居中对齐」——`DocumentViewService.element` 的段落属性只有
   text/location/lineSpacing/headingLevel/listItem，**没有 alignment**，复审读不到，
   把「工具不可见」当「属性未设置」。
3. 主模型的反应合理但被误导：set_style align=center（重复应用）、style="align=center"
   （参数形状错误，已有 hint）、delete+重插……全是围绕一个已正确的文档空转。
4. **旧归因（记号混淆）不成立**：本次 trace 里主模型从未对 `{align=center}` 发起
   replace_text/delete；docs/11 §6.4 的「模型把记号误当正文」描述需要修正为
   复审盲区为主因（记号混淆最多是次要因素，prompt 里的警示句保留）。

## Requirements

1. `DocumentViewService.element` PARAGRAPH 分支：`alignment` 属性**恒出现**（无值即
   "left"——不给复审留下「属性缺失=？」的推断空间），命名与 `{align=…}` 写侧记号一致
   （left/center/right/both），让复审能把 `alignment:"center"` 与 `{align=center}` 对上。
2. **范围扩展（复跑证据）**：全组复跑暴露同族缺口——`heading-font` 24 次 / `body-font`
   9 次循环，复审读 run 字体只见 ascii 槽（"Arial/Times New Roman"），看不见编译器写的
   eastAsia 槽（黑体/宋体），把已正确的槽分离排版误报成「未设置」。修复：core 新增
   `Run.fontEastAsia()` 读 API（与 `font()` 对称）；`view_element` RUN 详情与
   `view_annotated`（expandRuns）均暴露 `fontEastAsia`。
3. 测试：alignment（居中→center、未设置→left、两端→both）；fontEastAsia（槽分离→
   黑体、四槽同写→与 font 一致、未设置→null）。
4. spec（agent-single.md 的 view_element 条目 + 属性可见性教训）与 docs/11 归因修正、§6.6 新增。
5. 不改：view_body（紧凑列表保持纯文本）、复审 prompt（可观测性修复后默认行为即正确）、
   COMPILER_SYSTEM_PROMPT（记号警示句保留）。

## Acceptance Criteria

- [x] toolkit/core 测试绿；alignment 与 fontEastAsia 暴露均有测试锁定。
- [x] `--tasks insert-centered-title` 复跑：**3 次调用**（历史 11~26；修复前当日基线 24）。
- [x] 同族修复验证：`heading-font` 24→**4**、`body-font-line-spacing` 9→**5**。
- [ ] 全组复跑记录新均值（修复前当日 8.0/6.8）。
- [ ] docs/11 §6.6 归因修正记录；spec view_element 条目更新。
