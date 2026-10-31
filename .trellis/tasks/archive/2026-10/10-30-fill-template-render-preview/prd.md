# fill_template 与 render_preview：P2 收尾两块

## 背景

docs/11 §3.4/§3.6/§5-P2 把「渲染预览 + 模板填充」列为 5~6 工具 compiler 面的最后两块。
`comment.add` 已落地（§6.7，11/11@3.91），本任务补齐剩余两块。

## 范围与语义（MVP）

### fill_template（模板填充，原地编辑）

模板 = 当前会话文档（含占位符的 docx）。语法 = **mustache 子集**：

- `{{key}}`：文本替换（值 String/Number/Boolean）。**跨 run 占位符**（Word 拆 run 是常态）：
  段落 `text()` 合并判定；含 `{{` 的段落重写为单 run（克隆首 run 样式），记 warning
  （与 replace_text 跨 run 同一取舍）。
- `{{#key}}…{{/key}}` **段落级条件块**：独立整段标记（trim 后恰为标记）；key 为
  falsy（false/缺失/空列表）删除块内全部段落，truthy 保留（标记行本身总是删除）。
- **表格行级重复**：任一单元格含 `{{#key}}` 的行 = 模板行；data[key] 为列表时按元素
  复制（行内 `{{field}}` 优先取元素字段，回退外层 data；`{{#key}}`/`{{/key}}` 标记剥掉）；
  空列表删行；key 缺失/非列表保留原样 + warning。
- 缺 key 的 `{{key}}` 保留原样 + warning（模型可补数据重试，比静默清空安全）。
- 不支持嵌套 section。data 来自 nonchain 还原的 Map（JSON 对象）。

### render_preview（渲染预览，只读）

- `render_preview(doc_id, format=pdf|png)`：文档存临时 docx → `soffice --headless
  --convert-to` → 返回产物路径/大小。PNG = 首页（soffice convert-to 限制，文档注明）。
- soffice 路径：env `NONDOCX_SOFFICE`（默认 `soffice`）；超时 60s；失败返回结构化错误。
- 只读：不改正文、不置 dirty（demo 的 READONLY_TOOL_PREFIXES += `render_`）。

### 装配与面

- 两工具进 `CompilerTools`（@ToolDef），eval+demo 的 compiler 面 `scan(toolkit.compiler)`
  自动收录：compiler 面 6→8（面锁测试同步改）；full 面 58 不动（不 scan compiler，A/B
  基线保持）。
- 系统提示词（eval COMPILER_SYSTEM_PROMPT + demo systemPromptFor）教两工具。

### 评测

- baseline 增至 12 条：`fill-template`（seed=含占位符/条件块/模板行的模板，checks=
  替换文本/条件块处理/表格行数与内容/未消费占位符为 0）。render_preview **不进 eval**
  ——eval check 只读文档状态、够不到文件系统副作用；覆盖走集成测试（本机 soffice，
  `assumeTrue` 无 soffice 跳过）。

## 验收

- core/toolkit/examples/demo 测试全绿，Spotless 绿；面锁 full 58 / compiler 8。
- eval compiler 面 baseline 12/12（回归 11 条不退化）；fill-template 复审达成。
- render_preview 集成测试（本机）产出非空 PDF。

## 非目标

- mustache 反转/部分模板/嵌套 section；SDT 内容控件（docs/10 长期项）；
  demo OnlyOffice 转 PDF；render 结果的视觉 QA（LLM 看图）。
