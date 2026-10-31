# P2 续：修订与页脚 op —— 任务总结

## 交付

| 层 | 内容 |
|---|---|
| core | `Paragraph.replaceTrackedRange(author, start, end, newText)`（范围级最小 diff 修订替换）；`Paragraph.addSimpleField(instruction, cachedResult)`（带缓存结果五 run 域）；内部下沉 `TrackedChangeNodes.addInsertionAfter`（定位 ins） |
| toolkit | `PatchEngine` op 集 6→10：`tracked.insert/delete/replace` + `footer.set_page_number`；同锚点改文本独占（`claimAnchor`）；`MarkdownView` 页脚元数据行；`CompilerTools` schema/描述更新 |
| eval | `COMPILER_SYSTEM_PROMPT` 增补修订/页脚指引 |
| docs/spec | docs/07 CompilerTools 章节、docs/11 §6.5、agent-single.md 5 条新约定 |

## 结果

- **compiler 面 baseline 10/10**（上次 8/10；runId `20261030-010150-507`），首次反超 full 面（9/10）。
- `tracked-replace` 6 次、`footer-page-number` 4 次，均一次 `edit_document` 命中。
- 产物语义抽查通过：del 只包「一百万元」、ins 紧随其后（含 rPr 复制）；页脚域 begin/instr/separate/"1"/end，正文零改动。
- 测试：core 349 / toolkit 232 / examples 19 / demo 40 全绿；Spotless 绿；能力清单 66 不变（digest `60529a99…`）。

## 过程中的关键发现（已写进 spec）

1. **纯指令域读侧无数字**：`addSimpleField("PAGE")` 三 run 结构的 `footer.text()` 读出「第  页」——评测 check 要求文本含数字，必须写缓存结果（separate+result run，Word 打开重算）。
2. **XmlBeans moveXml 断连陷阱**：被移动节点的既有句柄会 `XmlValueDisconnectedException`，必须按 `w:id` 重查活句柄（`delById`/`insertedRunByRevisionId`）。
3. **POI 修订读侧盲区**：`w:ins`/`w:del` 内 run 不被 `XWPFParagraph.getRuns()` 暴露——tracked del 后 `text()` 移除被删文本、ins 文本不可见；且 accept/reject 在 POI 缓存之下改动，测试断言文本必须 save+reopen。
4. **同锚点独占的必要性**：修订偏移按执行时活文本计算，同批同锚点多条改文本 op 会互相污染——第二认领者整体拒绝（错误消息引导拆批）。

## 未做（后续候选）

- 批注创作 op（baseline 无对应任务）。
- 页眉 op、页脚自由文本 op。
- `insert-centered-title` 记号混淆的 prompt 治理（本次 26 次，波动 11~26）。
- demo 切 compiler 面（数据已齐：新建 5/5、编辑 10/10）。
