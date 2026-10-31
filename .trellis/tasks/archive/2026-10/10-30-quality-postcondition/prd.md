# 质检升级为编译器 post-condition（P2 遗留收尾）

## 语义

docs/11 P2：「把 QualityCheckTools 升级成编译器的 post-condition，而不是事后可选检查」。
与「复审/保存由应用层强制」同精神：**写工具的返回自带质量反馈**，模型不必记得单独调
validate_document。

## 设计

- `CompilerTools` 三个写工具——create_document（换血后新文档）、edit_document（非
  dry-run）、fill_template——成功路径末尾自动跑 10 项检查：
  - `data.qualityIssues` = 全部失败项 `[{name,severity,message}]`（全过则不设键）；
  - message 追加「，质量检查 N 项未过（error 明细名 / warning 名）」或「，质量检查通过」。
- **非阻塞**：写入已发生，检查纯读、失败只报告不回滚；检查自身异常吞掉（不污染写结果）。
- dry_run 不跑（未写入无 post-condition 可言）。render_preview 只读不跑。
- validate_document 保留（全量报告带 v1/v2 编号，另一层用途）。
- 提示词（eval+demo compiler 面）加一句：写结果自带质检为提示性信息，除非用户要求
  排版规范，无需主动修复——防「警告修复循环」（seed 文档常有 cjk-indent 警告，非编辑
  之过）。

## 验收

- CompilerToolsTest：plain+CJK+表格 create → qualityIssues 含 cjk-indent/table-pagination；
  dry_run 无键；edit/fill 成功路径含「质量检查」消息。
- eval baseline 22 条复跑：通过率不降、平均调用不显著上升（假设：提示词护栏防修复循环）。
- docs/11 §6.10 + spec 更新；归档 + journal。

## 非目标

- 检查失败回滚/阻止写入；按写入范围做差分检查；新检查项。
