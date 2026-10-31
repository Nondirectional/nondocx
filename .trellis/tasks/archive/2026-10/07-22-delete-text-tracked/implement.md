# 实施计划：兼容式跨 run tracked 文本删除

## 1. 实施前提与边界

- 任务状态必须先由 `planning` 进入 `in_progress`；完成 `prd.md`、`design.md`、本文件以及真实的 `implement.jsonl` / `check.jsonl` 条目后，再执行 `task.py start`。
- 不回退工作区已有的 `.trellis/` 无关修改；修改前先查看目标文件当前内容，并只对目标片段做 hashline 编辑。
- 第一版只处理正文普通文本 run；保留现有 `delete_run_tracked` 的整 run 入口。

## 2. 有序实施步骤

### 切片 A：core 范围删除模型与 XML 写入

- [x] 在 `nondocx-core` 中确定 `Paragraph.deleteTrackedRange(author, start, end)` 的最终公开 Javadoc 与异常契约，明确半开区间、UTF-16 偏移和代理对边界。
- [x] 为正文 inline 顺序建立普通 run 文本片段映射；识别目标范围是否跨越 hyperlink、image、field 或其他不可安全切片内容。
- [x] 在 `TrackedChangeNodes` 中增加按原 CTR 位置插入 `<w:del>` 的内部 helper，避免修订节点被追加到段落末尾而改变阅读顺序。
- [x] 实现纯文本 CTR 的前缀/目标/后缀拆分：复制完整 `rPr`，保留 `xml:space` 等文本属性，将目标片段转换为 `<w:delText>` 并迁入原位置的 tracked wrapper。
- [x] 处理整 run、跨两个 run、跨三个及以上 run、首尾部分命中等情况；写入过程中只使用改写前快照，避免读取失效的 Run wrapper。
- [x] 保持 `Paragraph.addDeletion(author, Run)`、`Run.replaceTracked` 和既有 accept/reject 行为兼容；必要时让旧 helper 委托到位置保持正确的内部实现，但不改变公开签名。

### 切片 B：toolkit 兼容入口与批量规范化

- [x] 在 `TrackedChangeAuthoringTools.deleteRunTracked` 中加入互斥 selector 解析：旧 `run_index` 与新 `target_text` + 可选 `occurrence`。
- [ ] 对每个段落建立一次原始文本/普通 run 区间快照；将旧坐标和新文本命中统一规范化为范围，未命中、重复歧义、occurrence 越界和不支持 inline 记录为该条错误。
- [ ] 检测同一批次的重叠或重复范围；保留 collect-errors 与 `on_error=stop` 语义，合法的非冲突范围按右到左应用。
- [x] 更新 `@ToolDef` 中文描述、`@NestedParamCapability` 的 `target_text` / `occurrence` 声明，以及返回摘要中的实际删除文本/涉及 run 数信息。
- [x] 保持 doc not found、generation mismatch、索引越界和旧 Java 兼容重载行为不变。

### 切片 C：core 测试与 OOXML 交叉验证

- [x] 增加 core 单元测试：唯一目标跨三个 run 删除、首 run 部分删除、中间 run 完整删除、尾 run 部分删除、整段文本删除。
- [x] 验证边界 run 的粗体/斜体/下划线/字体/字号/颜色及完整 `rPr` 保留；至少覆盖首尾空格和 Unicode 代理对边界不被拆开。
- [ ] 保存并重新打开 DOCX，验证段落文本、修订数量和原始 XML 顺序；使用 POI 原生 `XWPFDocument` / XML 断言确认 `<w:del>`、`<w:delText>` 和未命中文本位置。
- [x] 通过现有 tracked changes API 做 accept/reject 往返：accept 后目标消失，reject 后目标文本恢复；验证必须 save→reopen，不复用可能失效的旧 wrapper。
- [ ] 增加不支持 hyperlink/image/field 或非纯文本目标的错误测试，确认失败不破坏原文。

### 切片 D：toolkit、能力元数据与 Agent 文档

- [x] 在 `DocxToolkitTrackedChangesTest` / 批量测试中覆盖旧 payload、新 `target_text` payload、跨 run、部分边界、occurrence、重复歧义、重叠范围、部分失败和未知文档。
- [x] 验证 `delete_run_tracked` 的能力元数据仍标记为 tracked-change remove，并包含两种 selector 的参数类型和可选性。
- [x] 更新 `docs/07-toolkit.md` 的工具表、批量语义和示例；仓库无 `DocxAgentExample` / `InteractiveDocxAgentExample` 文件，故仅同步 toolkit 文档和工具元数据。
- [ ] 检查单 Agent registry/dirty 识别没有遗漏：工具名称不变，不新增 save 工具，不改变写工具归类。

### 切片 E：质量门与回顾

- [x] 运行 core、toolkit 定向测试和全仓质量检查。
- [ ] 检查 git diff 只包含本任务目标文件及计划中允许的文档/元数据变化，确认 `.trellis/` 既有无关修改未被覆盖。
- [ ] 如实现过程中发现 OOXML 顺序、wrapper 失效或 WPS/Word 差异，先补测试和设计说明，再决定是否扩展范围；不得通过静默降级绕过错误。
- [x] 在归档前更新必要的 backend spec（若形成可复用的跨 run XML 操作契约），记录验证结果并提交本任务变更。

## 3. 验证命令

按切片逐步执行：

```bash
mvn -q -pl nondocx-core -am test
mvn -q -pl nondocx-toolkit -am test
mvn -q -pl nondocx-toolkit -am test -Dtest='*TrackedChangesTest,*BatchTest'
mvn -q verify
```

如能力元数据由构建插件生成，额外检查生成的 `capabilities.json` 中 `delete_run_tracked` 的描述、参数和 capability 映射；生成目录不作为手工源码提交目标，除非项目现有流程明确要求提交。

## 4. 风险点与回滚点

- **XML 顺序风险**：若删除段首/段中后读回文本顺序变化，回滚新的 range helper，保留旧整 run 删除路径，先补充位置锚定探针。
- **切片样式风险**：若完整 `rPr` 复制失败或首尾空格丢失，停止扩展并补齐 XML 结构测试，不退化为只复制六个高层样式字段。
- **wrapper 失效风险**：若批量操作触发 `XmlValueDisconnectedException`，改为全部预解析、右到左应用、每次从 live delegate 重取，不捕获后静默继续。
- **批量兼容风险**：若旧坐标索引在新逻辑下漂移，恢复旧模式原实现或单独分支处理，并用现有 no-drift 测试锁定行为。
- **工具元数据风险**：若生成能力文件没有新字段，优先检查注解声明和生成流程，不手工修改 target 产物冒充源码修复。

## 5. 开始实施前复核

- [ ] 用户已确认重复 `target_text` 未传 `occurrence` 时返回歧义错误。
- [ ] 用户已审阅本 PRD、技术设计和执行计划，接受“工具名兼容扩展、正文普通 run、精确文本匹配”的范围。
- [ ] `task.py start` 后再进入代码编辑；实现阶段每轮完成后运行 `trellis-check` 质量检查。
