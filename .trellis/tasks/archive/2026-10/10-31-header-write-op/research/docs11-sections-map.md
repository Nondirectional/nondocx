# Research: §6.12 / §6.6 / §6.13 / §6.5 定位与摘录

- Query: 「§6.12」「§6.6」「§6.13」「§6.5」是哪份文档的哪几节；full 面 capabilityGap flag 评估流程；写读同批/复审误报相关教训
- Scope: internal
- Date: 2026-10-31

## Findings

四个节号全部出自 **`docs/11-agent-authoring-architecture.md`**（《11 · Agent 写作架构建议：从「61 个工具」到「文档编译器」》，968 行）。§6 是「评测基线（任务 A）」（line 299 起），各小节按落地批次链式追加（§6.1~§6.14，最新 §6.14 line 862；后续新批次应从 §6.15 续编，不回改旧节）。

| 节 | 标题 | 行号 |
|---|---|---|
| §6.5 | 修订与页脚 op：补齐最后两个缺口（P2 续，2026-10-30） | 520-567 |
| §6.6 | 复审可观测性：一次被 trace 推翻的归因（2026-10-30） | 569-612 |
| §6.13 | tracked 行内格式：最后一个字面文本路径，与两次被踩实的旧坑（2026-10-30） | 819-860 |
| §6.12 | full 面对齐复跑：8 个失败全是静态缺口，零「模型做砸」（2026-10-30） | 765-817 |

（另两个高相关节：§6.7 批注创作 op「写读对称」先例 line 614-639；§6.9 末尾 line 697-699「页眉写不进 baseline」的已知边界声明——本任务 PRD 的直接出处。）

### §6.12 的 full 面 capabilityGap flag 评估流程（line 765-817）

背景：§6.11 后 compiler 面 23/23@4.26，full 面对照数字停在 §6.6 的 9/10——在 23 任务 baseline 上复跑 full 面（**runId 20261030-125616-728**，58 工具，同模型同温度）：14/23（60.9%）＊、平均 11.83（2.8×）、4 任务撞 24 轮上限。

评估流程（逐步）：

1. **复跑 full 面拿逐任务 trace**（调用数、失败 check、复审循环证据）。
2. **逐失败归因列表**（line 785-796 表格）：每个失败任务给出「调用数 + 静态缺口」二元组——footer-page-number（页脚只读，写工具 ref 只解析正文）、comment-add（无批注创作工具）、multi-step-combo（批注部分）、fill-template×2（无 fill_template + 段落删除）、insert-list（无列表参数/正文列表工具）、delete-paragraph（无正文段落删除）、table-add-row（真空单元格陷阱 poi-bridge N2）、bold-existing-phrase（无 run 拆分）。
3. **结论门槛**：「全部静态能力缺口，**零『任务可做但模型做砸』**」才打 flag——A/B 差距全部由能力缺口构成、与模型能力无关。
4. **flag 按 footer-page-number 先例打**（line 810-812）：8 个缺口任务（+先前 1 个）标 `capabilityGap=true`，由 `EvalTasksTest#marksKnownCapabilityGapTasks` 锁定 9 条。**flag 只影响报告分类列**（compiler 面全过时 inert），full 面报告从此自带缺口标注。
5. **口径诚实修正**：runId 728 原始 15/23，其中 bold-existing-phrase 是「空洞通过」——check 加严（严格子集断言）后按失败计，复验 runId `131105-091`（full）/`131042-172`（compiler 仍过）。
6. 后续 §6.14 补充原则：**「flag 只标物理不可达」**——tracked-replace-format 存在整 run 替换 workaround（`TrackedReplaceFormatWorkaroundTest` 实证可过全部 check），归「行为失败，不 flag」。

§6.12 记录的三个新记录（对页眉任务的直接参照）：复审在不可能任务上产生修复循环（footer trace：模型找到页脚 ref 但写工具只按正文索引寻址→误改正文→review 循环到 24 轮上限）；真空单元格陷阱；**check 要防工具面的退化通过**。

### §6.6 的教训内容（line 569-612，与复审误报相关）

事件：`insert-centered-title` 长期 11~26 次波动被归因为「模型把 `{align=center}` 记号误当正文」；单任务复跑（runId 20261030-090245-055）的 trace **推翻归因**：

- 第 2 次调用就正确（`fillHeading` 显式 align 优先）；循环驱动者是**复审 SubAgent**——`view_element` 段落详情没有 alignment 字段，复审把「工具不可见」当「属性未设置」，主模型围绕已正确文档空转。
- 修复 = 补观测面而非改 prompt：paragraph 详情恒输出 alignment。
- 同族缺口第二批：run 字体只见 ascii 槽、看不见 eastAsia 槽 → 修复 `Run.fontEastAsia()`。
- 终版 runId 20261030-091648-612：10/10@4.4；第二批富化（cellTexts/countByAuthor/footerText 预览）runId 20261030-093623-921：10/10@4.20。

**教训原文（写进 spec）**：「复审只能依据工具**可见**的属性下结论——『详情里没有』会被解读成『没设置』。给复审需要核对的属性（对齐/中文字体/行距）加观测面时**恒出现**优于『设置才出现』。」

### §6.13 的教训内容（line 819-860，与写读同批/字面路径相关）

- 补齐内容：core 多 run ins 变体 + PatchEngine tracked op 复用 `parseInline` + 修订观测面 bold/italic 恒出现；eval 探针 `tracked-replace-format` 入组（baseline 24）。
- 两个被踩实的旧坑：① accept 的 unwrap 游标失效（多 run ins 丢后半段文本）；② **`text()` 的 live/reopen 语义差异**——del 文本永不进 `text()`；ins 文本 live 不可见、save+reopen 后进入（eval 读落盘按 reopen 口径，agent 会话内 read_document 是 live 口径）。
- 首跑两个失败与修复（runId 20261030-150016-660/151033-643）：heading-font 回归（提示词改动挪动轨迹→错误消息要「把正确写法就地教全」）；**复审的格式证据缺口（§6.6 同族第三次）**——模型第一个 op 就正确，但修订内 run 不被 `runs()` 暴露，复审核对 bold 全落空→误报「未加粗」→模型连插 6 条纯文本 ins 污染文档。修复：修订条目恒带 bold/italic（false 也是证据）。原则重申：「复审只会依据可见属性下结论，修订内 run 的属性必须挂在修订条目上」。
- 「全链路唯一按字面处理文本的路径就是 bug」（内容语言统一原则，同款句也在 spec replace_text 条目）。

### §6.5 的教训内容（line 520-567，footer op 落地先例——页眉 op 的直接模板）

- `edit_document` op 集 6→10：tracked.insert/delete/replace + footer.set_page_number；工具数不变，能力清单 66。
- core 两个新 API：`Paragraph.replaceTrackedRange`（最小 diff）与 `Paragraph.addSimpleField(instruction, cachedResult)`（**五 run 缓存结果域**——纯指令域 POI 读侧无数字，这是 footer-page-number 双面失败的写侧根因）。
- 同锚点独占规则：tracked.*/replace_text 每批每锚点至多一条；**页脚 op 不占段落锚点，可与正文 op 同批**。
- **「已知语义边界（实证后写进 spec）」段**（line 561-564）：tracked 后 read_document 不含 ins 内容；核对修订用 review_intent；**页脚靠 `<!-- 页脚: … -->` 元数据行（MarkdownView v2 新增，round-trip 安全）**——页眉行落地后此处口径延续。
- 复跑：runId 20261030-010150-507，compiler 10/10 首次反超 full 9/10。

### 写读同批的总原则出处

spec `agent-single.md` comment.add 条目（~line 310-312）：「**新增『作者署名标记』类能力时写读必须同批落地：只有写侧 op 没有读侧观测，复审会把已正确的文档误报『未达成』（§6.6 同族教训）**」。docs/11 §6.7（line 616-618）是「按『写读对称』一次到位」的完整先例：comment.add 写侧 + core `Paragraph.comments()` + view 三处恒出现 + read_document 批注元数据行 + eval 任务同批入组。

## Caveats / Not Found

- `.trellis/tasks/archive/2026-10/` 下多个任务（10-30-full-surface-rerun 等）也引用这些节号，但**节号本体只在 docs/11**；spec agent-single.md 引用形式为「docs/11 §6.14」。
