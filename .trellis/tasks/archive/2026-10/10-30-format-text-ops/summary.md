# 总结：replace_text 行内格式与 set_style 加粗

## 交付

- **core** `Paragraph.copyRun(int i)`：深拷贝第 i 个 run 紧随其后插入。走 POI 自己的
  `insertNewRun`（树元素与包装同源创建登记——避开 `XWPFTable.addRow` 缓存外部包装的
  脱链陷阱），再 `getCTR().set(copy)` 填内容（影子技法）。完整 rPr 继承（含 eastAsia
  等未建模槽）。save+reopen 测试锁死。
- **PatchEngine.replace_text**：`text` 改按行内标记解析（`**粗**`/`*斜*`/`` `代码` ``，
  未闭合原样保留）。单 run 命中外科手术式拆分：源 run 保前缀，替换片段逐 span、间隔/
  后缀均为**源 run 的深拷贝**（倒序插入——顺序复制「上一份拷贝」会让格式逐份累积，
  测试抓住的真实坑）；跨 run 命中重建为 span 序列。纯文本路径行为与历史完全一致。
- **PatchEngine.set_style**：增加 `bold`/`italic` 布尔（整段字符格式，可与
  style/align/line_spacing 叠加，后应用）。
- **eval baseline 23**：新增 `bold-existing-phrase`（段内既有词加粗、其余文字不许动）。
- 提示词（EvalRunner + AgentBridge）教行内标记加粗与 `set_style bold`。

## 顺手修复

- 跨 run 整段重建旧实现按 `runs()` 计数循环删除，超链接可能幸存并造成重建后文本重复；
  现按 `inlineElements()` 清空（含超链接）。

## 验证

- 测试全绿：core 361（ParagraphTest +3）/ toolkit 268（PatchEngineTest +7）/
  examples 19（EvalTasksTest 23 条任务 + 防恒真）/ demo 47；Spotless 通过。
- eval runId `20261030-124819-241`（compiler 8 工具，qwen3.8-27b，temp 0）：
  **23/23，平均 4.26**（上轮 22 任务 4.41）。
  - replace-with-format **9→4 次**：模型首个 op 即 `replace_text text=**结论：通过**`
    （旧版里这一步是「错」的）——一步命中，无 set_style 试错、无 delete+insert 自愈。
  - bold-existing-phrase **首跑通过（4 次）**：`find=逾期率 text=**逾期率**` 身份替换，
    复审确认目标 run bold=true、其余文本未动。
  - `validate_document` 全程 0 调用；无警告修复循环；22 条回归零退化。

## 核心教训

接口对了，模型本来就会：不要给「模型的正确直觉」加护栏，要把接口改直觉一致——
该编译器全链路唯一按字面处理文本的路径（旧 replace_text）就是 bug。
