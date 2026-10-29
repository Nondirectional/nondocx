# demo 切 compiler 面：6 入口工具面成为默认

## Goal

AgentBridge 主工具面默认切到 compiler（current_document + create/read/validate/edit_document + review_intent = 6 入口），system prompt 与 Skill 策略随面切换，`NONDOCX_AGENT_SURFACE=full` 可回退；`/api/status` 暴露 surface；测试锁定双面装配。

## 背景与依据

- 评测数据已齐（docs/11 §6.3/§6.4/§6.5）：compiler 面 create 组 5/5（5.6 次 vs full 23.0）、
  baseline 组 10/10（vs full 9/10）。
- demo 当前主面 = full（7 个 scan 组 + review SubAgent，58+ 入口），system prompt 与
  6 条 DemoSkills 全是 full 面的过程知识——切面后必须同步换，否则 Skill 会教模型用不存在的工具。

## 行为缺口（最小）

1. AgentBridge `buildToolRegistry()` 只装配 full 面。
2. system prompt 只有一条（full 语义）。
3. DemoSkills 与 compiler 面不兼容（教 insert_paragraph 等不存在的工具）。
4. dirty 检测：`validate_document` 不在只读前缀集（会被当写工具置 dirty，纯咨询轮触发无谓落盘）。
5. `/api/status` 无 surface 字段。

## Requirements

1. **面选择**：`surfaceFromRaw(String)` 解析 `NONDOCX_AGENT_SURFACE`（trim/小写）——
   空→`compiler`（默认）；`compiler`/`full` 合法；非法值记 error 日志并回退 `compiler`。
2. **工具装配**：`buildToolRegistry(surface, documentTools, toolkit, llm, reviewSelector)`
   package-visible static：
   - 两面共用：`scan(documentTools)`（current_document）+ 只读复审 SubAgent `review_intent`
     （仅 `toolkit.view`，不变——spec 的物理保证）。
   - compiler 面：`scan(toolkit.compiler)`（4 工具）→ 主面 5 regular + 1 SubAgent = 6 入口。
   - full 面：现行 6 组 scan（view/body/table/headerFooterToc/trackedChangeQuery/trackedChangeAuthoring）。
3. **system prompt**：`systemPromptFor(surface)` —— compiler 版改编自 eval 的
   COMPILER_SYSTEM_PROMPT（Markdown 一次提交、read/edit_document、记号非正文、tracked.*/页脚、
   不保存、review_intent、不记忆 docId）；full 版保持现行文本不动。
4. **Skill 策略**：`skillRegistryFor(surface)`——compiler→空 `SkillRegistry`（full 面 Skill
   是错误指导）；full→`DemoSkills.create()`。Agent 构造保持 `.skillRegistry(...)` 调用形态。
5. **只读分类**：READONLY_TOOL_PREFIXES 增加 `validate_`（validate_document 不置 dirty）。
6. **状态暴露**：`AgentBridge.surface()` + `/api/status` 增加 `surface` 字段；前端状态区显示。
7. **README**：环境变量文档补 `NONDOCX_AGENT_SURFACE`。
8. **不动**：dirty/save/取消/记忆瘦身/复审 SubAgent 装配语义；OnlyOffice 预览；eval 代码。

## Acceptance Criteria

- [ ] demo 测试：compiler 面主 registry 恰为 5 regular（current_document + create/read/validate/edit_document）+ review_intent SubAgent，且无 full 面写工具（insert_paragraph/replace_run_text/create_table/set_cell_text）。
- [ ] full 面 registry 保留全部现行工具（含 insert_paragraph 等）。
- [ ] `validate_document` 判只读；`create_document`/`edit_document` 判写（默认路径）。
- [ ] `systemPromptFor`：compiler 版含 edit_document/tracked.*/footer.set_page_number/不保存指引；full 版与现行一致。
- [ ] `skillRegistryFor`：compiler 空、full 6 条。
- [ ] `surfaceFromRaw`：null/空白→compiler；full→full；`fast`（非法）→compiler。
- [ ] 全模块测试绿 + Spotless 绿；README 更新。
