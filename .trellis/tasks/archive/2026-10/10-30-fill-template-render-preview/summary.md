# fill_template 与 render_preview（P2 收尾）— 总结

## 交付

**fill_template**（模板 = 当前会话文档，mustache 子集，原地填充）：
- `{{key}}` 值替换：跨 run 占位符按 `text()` 合并命中（Word 拆 run 常态）；重写用「首 run
  承载全文、其余清空」——不做样式克隆（`Run.font()` 写侧四槽同写会把西文字体灌进
  eastAsia 槽污染中文）。
- `{{#key}}…{{/key}}` 段落级条件块（falsy=删除块、标记行恒删；空列表/空白串均为 falsy）。
- 表格模板行（任一单元格含 `{{#key}}`）：按 data[key] 列表复制，行内字段先取元素、回退
  外层，标量元素绑 `{{.}}`；XML 深拷贝保留底纹/边框/行属性；空列表删行。
- 宽容语义：缺 key 保留原样 + warning；无结构性拒绝路径。

**render_preview**（只读）：soffice headless → PDF/PNG（首页）。独立
`-env:UserInstallation` profile 防实例锁；`NONDOCX_SOFFICE` 可配；60s 超时销毁；
`render_` 进 demo 只读前缀（不置 dirty）。不进 eval（check 够不到文件系统副作用），
覆盖走 `assumeTrue(soffice)` 集成测试（本机实跑通过）。

**面**：compiler 6→8（eval `EvalRunnerTest` + demo `SurfaceSwitchTest` 双锁）；full 58
不动；`ParamType` 增 OBJECT（data 参数）。

## 本次最大的坑：POI 5.3 表行缓存脱链（字节码验证）

首版 eval 11/12：fill-template 工具报告成功、复审「达成」，但 check 读落盘文件失败。
验尸 out.docx：XML 里复制行全部是未填的模板原文。根因：`XWPFTable.addRow(row,pos)`
字节码——`insertNewTr(pos)` + `setTrArray(pos, row.getCtRow())`（内容**复制**进树）+
`tableRows.add(pos, row)`（**传入 wrapper** 进缓存）。传入脱链 `.copy()` 时，返回行的
后续写入落进不在文档树里的对象：内存读（走 wrapper）正确、落盘 XML 原样。
`getRows()` 无缓存重建路径。

**修复（影子行技法）**：core `copyRowTo` 手动 `insertNewTr` 得到树内元素并 `set(copy)`
填充 → 传包树内元素的 wrapper 给 `addRow`（缓存正确；XML 多出一行复制内容）→
`removeTr(target)` 删影子行。树与缓存最终同一对象。回归测试
`copyRowToReturnedRowWritesIntoDocumentTree` + TemplateFiller 的 save+reopen 断言锁死
——纯内存断言测不出「内存 vs XML」分裂。

## 评测

runId `20261030-104446-951`（compiler 面 8 工具）：**12/12，平均 4.50**。11 条回归任务
零退化（面 6→8 无工具选择混乱）；fill-template 5 次调用
（current_document → read → fill_template → read → review 达成），复审引用
「调研/3、开发/5」具体证据。

## 测试

core 361（TableTest +4：拷贝定位/追加/cantSplit/越界/落盘回归）/ toolkit 266
（TemplateFiller 9、RenderPreview 4、CompilerTools +2）/ examples 21 / demo 47。
Spotless 绿。
