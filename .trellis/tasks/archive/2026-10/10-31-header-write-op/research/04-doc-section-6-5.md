# Research: 「§6.5」文档定位与要点（docs/11-agent-authoring-architecture.md）

- Query: §6.5 指哪份文档的哪一节，摘出该节要点
- Scope: internal
- Date: 2026-10-31

## Findings

### 定位

**§6.5 = `docs/11-agent-authoring-architecture.md` 第 6.5 节**（约 :520 起），标题：

> `### 6.5 修订与页脚 op：补齐最后两个缺口（P2 续，2026-10-30）`

（含 footer.set_page_number 的全部文档：TODO.md、docs/07-toolkit.md、docs/11、spec/backend/agent-single.md、两个归档任务与一个归档研究文件——其中成节的叙述性文档只有 docs/11 §6.5。）

### 该节内容要点（原文摘编，约 docs/11:520-570）

1. **背景**：§6.4 的 2 条 eval 失败是预告的 op 集缺口，本批补齐。`edit_document` op 集 6→10：`tracked.insert` / `tracked.delete` / `tracked.replace`（修订创作，author 必传）+ `footer.set_page_number`（页脚页码，`{page}`/`{pages}` 占位符）。工具数不变（6 入口）。

2. **core 侧两个新 API**（POI 没有的高层语义）：
   - `Paragraph.replaceTrackedRange(author, start, end, newText)`——范围级最小 diff 修订替换（逐 run 切片 del + ins 紧随其后，ins run 复制范围内首个 run 的 rPr）。
   - `Paragraph.addSimpleField(instruction, cachedResult)`——带缓存结果的简单域（五 run：begin/instr/separate/result/end）。实证：纯指令域的 `footer.text()` 读出「第  页」无数字（POI 不计算域值）；缓存结果让所有 POI 系读者读到「1」，Word/WPS 打开时重算。**这是 footer-page-number 双面失败（full 面 20 次调用）的根因之一——写侧此前没有能用的域 API。**

3. **同锚点独占规则**：tracked.* 与 replace_text 每批对同一段落锚点至多一条（修订偏移按执行时活文本从右到左计算），第二个认领者整体拒绝并提示拆批。**页脚 op 不占段落锚点，可与正文 op 同批**（本任务页眉 op「不占锚点」要求的直接出处）。

4. **baseline 复跑数据**（qwen3.8-27b，温度 0，runId 20261030-010150-507）：full 面 9/10、compiler 面 10/10 首次反超；footer-page-number 在 full 面 ❌（20 次，capabilityGap——full 面没有可用的写侧域 API）、compiler 面 ✅（4 次，1 次 edit_document 直达）。

5. **产物语义抽查口径**：页脚域含 separate + 缓存 "1"，正文零改动。

6. **已知语义边界（实证后写进 spec）**：tracked 修订后 read_document 正文视图不含 ins 内容、被删文本已移除；**页脚靠 `<!-- 页脚: … -->` 元数据行（MarkdownView v2 新增，round-trip 安全）**——即读侧观测行是与写 op 配套同批落地的先例，本任务「read_document 同批落地页眉元数据行」正对齐此模式。

### 相邻节参考（同文档）

- §6.6（复审可观测性）：教训「复审只能依据工具可见的属性下结论」——页眉 op 若无读侧元数据行会重演误报循环（§6.7 comment.add 也按「写读对称」一次到位）。
- §6.7（批注创作 op）：「写侧 + 观测面」成对增量的一体化先例。

### spec 侧对应条目

`.trellis/spec/backend/agent-single.md:297-305`：
- 「页脚页码域必须带缓存结果」条目（addSimpleField 五 run 形态、缓存值固定 "1"、format 占位符）；
- 「read_document 的页脚元数据行」条目（`<!-- 页脚: … -->` 存在且非空才输出，round-trip 安全，测试 CompilerPipelineTest#markdownViewShowsFooterAsMetadataAndKeepsRoundTrip）。

## Caveats / Not Found

- docs/11 行号按当前文件估算（§6.5 标题 grep 实测 :520）；引用时以标题为准。
- docs/07-toolkit.md 也含 footer.set_page_number（工具文档），未逐条摘录。
