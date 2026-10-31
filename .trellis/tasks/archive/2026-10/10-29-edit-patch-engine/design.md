# 设计：edit_document patch 引擎

## 变更边界

**缺口**：compiler 面只能「整篇替换」，不能编辑既有文档。行为落点 = 新增 `PatchEngine`
（纯 compiler 包内新增，组合 core 已有 API：`insertParagraph(bodyIndex)`/`removeParagraph`/
`addTable`/`addRow`/`addRun` 等）；唯一重构是 `DocumentCompiler` 把逐块编译下沉为
`compileBlockInto`（create_document 行为由既有测试保护）。

**预计改动**：

| 文件 | 为什么 |
|---|---|
| `compiler/PatchEngine.java`（新） | op 解析/preflight/执行/dry-run |
| `compiler/DocumentCompiler.java` | 下沉 `compileBlockInto`；`applyRunFonts` 提为包内可见 |
| `CompilerTools.java` | 第 4 个工具 `edit_document` |
| `examples/eval/*` | 仅 runner 提示词提一句 edit_document（面本身自动多一工具） |
| docs/07、docs/11、spec | 文档 |

**不做**：修订/批注/图片 op、删表（core 无 API）、跨段移动、正则替换、demo 接入。

## op 集（v1）

```json
{"op":"insert","anchor":"p3","position":"after","block":"# 小节 {align=center}\n\n正文。"}
{"op":"insert","position":"end","block":"| a | b |\n|---|---|\n| 1 | 2 |"}
{"op":"replace_text","anchor":"p8","find":"旧","text":"新"}
{"op":"delete","anchor":"p5"}
{"op":"set_style","anchor":"p8","style":"body"}          // 或 "heading"
{"op":"set_style","anchor":"p8","align":"center","line_spacing":1.5}
{"op":"table.set_cell","anchor":"t0","row":1,"col":2,"text":"完成"}
{"op":"table.add_row","anchor":"t0","cells":["a","b"]}
```

## 关键语义

- **锚点**：`pN`/`tN` 与 `read_document` 同源编号（正文段落序/表格序）。preflight 一次性
  快照解析为活对象 + 文本（供 find 校验）。
- **原子性**：preflight 校验（锚点存在、参数合法、`find` 在段落文本中、row/col 在界内、
  insert 含表格块时 position=end、被本 patch 前序 op 删除的锚点不得再引用）→ 任一失败
  整体拒绝、**零写入**。执行期不再有可失败分支（冲突在 preflight 静态检出）。
- **insert 语义**：多块 markdown 按序编译插入；`after/before` 紧贴锚点（同锚点多次
  insert 后写的离锚点更近——主路径是多行 block 一次插入）；表格块只能 `end`。
- **replace_text**：单 run 内命中→原位改文本（保样式）；跨 run→整段重建为单 run，
  **warning 注明丢失段内直接格式**（段落级属性保留）。
- **set_style**：`body`/`heading` 套 report-cn 对应样式（含 eastAsia 分离），显式
  `align`/`line_spacing` 覆盖；不改 heading 级别。
- **generation**：edit_document 不递增（与其它写工具一致），接受 `expected_generation`。
- **dry_run**：返回逐条「将做什么」清单 + warnings，不写入。

## 返回信封

成功：`{applied, changed:[锚点…], warnings[], generation}`；
dry_run：`{dryRun:true, planned:[…], warnings[]}`；
失败：INVALID_ARGUMENT，消息含第几个 op、为什么、零写入声明。

## 评测

`--surface compiler --group baseline`（10 条）与 full 面（9/10、6.2 次）对比；
`tracked-replace`（无修订 op）与 `footer-page-number`（无页脚 op）按能力缺口如实记录。
compiler 面 system prompt 增补一句：多步编辑优先一次 edit_document 批量表达。
