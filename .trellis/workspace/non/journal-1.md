# Journal - non (Part 1)

> AI development session journal
> Started: 2026-06-16

---


## 2026-06-16 — nondocx-core MVP implemented (task `06-16-nondocx-core-mvp`)

Implemented the full nondocx-core MVP (docx read/write library over Apache POI) across Phases 0–8:

- **Scaffold**: parent POM `com.non:nondocx-parent` + `nondocx-core`, Apache 2.0, README, CI (JDK
  [11,17,21] matrix), Spotless (google-java-format) bound to `verify`.
- **Domain model**: `Docx` facade; `api/` (Document, text/Paragraph/Run/Hyperlink,
  table/Table/Row/Cell, section/Section/PaperSize/Orientation, image/Image/ImageType,
  header/Header/Footer, style/*, exception/DocxException hierarchy, BodyElement/InlineElement);
  `builder/` (DocumentBuilder/ParagraphBuilder/TableBuilder); `internal/` (poi/Mappers, Numbering,
  Pictures; util/Streams, Objects).
- **111 tests**, content-equal equals on all core types; `RoundTripTest` deep-equality across
  save→open green on first try (POI write-side normalization is invisible to equality because
  equals compares parsed values, not raw XML).
- All 9 prd acceptance criteria PASS; `trellis-check` verdict READY-TO-FINISH.
- Key Apache POI bridge gotchas (pre-populated children, EMU vs pixels, `unsetNumPr` vs
  `setNumID(null)`, create-on-access header/footer, image-in-run) captured in
  `spec/backend/poi-bridge.md` → Implementation Notes.
- **Deferred to v0.2**: OOXML template fixtures / TestDocxPackager (core acceptance met via
  programmatic construction + POI cross-reference); first-page/even-page header variants.



# 2026-06-17 — 创建 nondocx-examples 示例模块
#
# - 创建新 Maven 模块 `nondocx-examples`，继承 parent POM，依赖 `nondocx-core`
# - 9 个示例文件，全部通过编译，并成功运行生成 docx
# - 文件结构：
#   - `HelloWorld.java` — 最简创建→写入→保存
#   - `FormattingDemo.java` — 标题/对齐/Run 级格式化
#   - `TableExample.java` — 表格 + row(Consumer) 链式填充
#   - `ListExample.java` — 项目符号/编号/嵌套层级
#   - `HeaderFooterExample.java` — 页眉/页脚
#   - `HyperlinkExample.java` — 超链接
#   - `PageSetupExample.java` — 纸张/方向/边距
#   - `ImageExample.java` — 内联图片（运行时生成 PNG）
#   - `ComplexDocument.java` — 综合示例：完整项目报告
# - 所有输出到 `target/examples-output/`（已加入 .gitignore）
# - 更新父 POM 的 `<modules>`、`.gitignore`、`directory-structure.md`

## Session 1: 排查 WPS 页眉页脚不显示 + core 落盘兼容性补全

**Date**: 2026-06-17
**Task**: 排查 WPS 页眉页脚不显示 + core 落盘兼容性补全
**Branch**: `main`

### Summary

定位到 HeaderFooterExample 生成文档在 WPS 中页眉页脚不显示的根因——缺少显式 pgSz/pgMar。在 Section.header()/footer() 首次创建路径补齐缺失页面设置（A4 + 1 英寸边距），只补缺失不覆盖已有。补充测试、Javadoc、README 与 spec 文档。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `0d273d6` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 2: 新增 examples 模块与开发者协作规范

**Date**: 2026-06-17
**Task**: 新增 examples 模块与开发者协作规范
**Branch**: `main`

### Summary

新增 nondocx-examples 模块（ComplexDocument 综合示例 + 9 个独立示例），添加开发者协作规范（AGENTS.md 偏好说明、teaching-approach.md 教学指南），更新目录结构文档，启用 session_auto_commit。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `ca2b75b` | (see git log) |
| `df2ef88` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 3: examples 中新增 nonchain Agent docx 示例

**Date**: 2026-06-17
**Task**: examples 中新增 nonchain Agent docx 示例
**Branch**: `main`

### Summary

在 nondocx-examples 新增 nonchain Agent docx 示例：DocxAgentTools（会话/正文/表格/超链接四组 @ToolDef 工具，统一返回 String、越界返回中文错误串）+ DocxAgentExample（DashscopeLLM + ToolRegistry.scan 组装 Agent，两段流程：读取汇报→编辑保存）+ 固定样例输入文档与结构自检测试。端到端用真实 LLM 跑通并核验 agent-edited.docx。过程中发现并根治 POI N9（Run.text/Hyperlink.text 追加而非替换）与 N10（超链接 URL 重建关系），补 round-trip 回归测试，core 共 121 测试全过，poi-bridge.md 沉淀两条 gotcha。父 pom 加 chain 0.8.4 依赖管理并修复原 XML 结构错位。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `f258faf` | (see git log) |
| `6efa5ab` | (see git log) |
| `179222e` | (see git log) |
| `9210cce` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 4: core 页眉页脚读写分离与 examples Agent 工具增强

**Date**: 2026-06-18
**Task**: core 页眉页脚读写分离与 examples Agent 工具增强
**Branch**: `main`

### Summary

两个紧密耦合主题一起落地：(1) core 页眉页脚读写分离——header()/footer() 改纯只读（null=不存在，永不动文档），新增 ensureHeader()/ensureFooter() 显式创建路径，Document 对称委托，equals/hashCode 不再需私有绕过；spec poi-bridge.md N5/N8 同步重写。(2) examples Agent 工具增强——新增 search_text（横切正文/表格/页眉/页脚一次定位，把线性扫描搬出 Agent 循环）、read_header/read_footer（受益于只读 API）；DocxAgentExample 加 search_text prompt 引导 + 升级 qwen3.7-plus；新增 InteractiveDocxAgentExample REPL（MessageWindowChatMemory 多轮记忆 + 流式回显）。验证：core 124 测试全绿、examples 编译全绿。任务 06-18-header-footer-split-agent-tools 已归档。关键沉淀：读写分离是 API 安全根本解（非私有绕过补丁）；Agent 工具应成对提供按索引/按内容寻址；可选参数用包装类型接 nonchain 的 null 注入。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `b2642a8` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 5: 给 Agent 加读取 TOC 能力(支持域与 SDT 两种形态)

**Date**: 2026-06-18
**Task**: 给 Agent 加读取 TOC 能力(支持域与 SDT 两种形态)
**Branch**: `main`

### Summary

core 新增 TableOfContents/TocEntry + Document.toc(),解析逻辑收进 internal/poi/TocFields。关键:用 XmlCursor 穿透 <w:sdt>/<w:sdtContent> 收集段落(POI getParagraphs() 不返回 SDT 内段落),按 TOC1..9 样式识别条目,兼容域形态(老式大域)与 SDT 形态(每条目自带 PAGEREF 子域)。examples 新增 read_toc 工具 + sample-toc-input.docx。spec N11 沉淀两种形态与解析策略。坑:第一版只支持域形态,把 SDT 写成「尽力而为/盲区」,1072.docx 实测踩穿——真实 Word 普遍是 SDT 形态。教训:别拿自己手搓的样例当全部真相。core 128 + examples 2 测试绿,1072.docx 实测读出 20 条。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `37ed527` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete

---

## Session 6: tracked changes 只读消费侧(read 子任务实现)

**Task**: `06-18-tracked-changes-read`(父任务 `06-18-tracked-changes` 的第一个实现子任务)

### What

为 tracked changes 建立**最小但稳定**的只读消费侧底座:`Document.trackedChanges()` → `TrackedChanges` 门面,提供 `enabled()` / `list()` / `get(id)`。

### 实现产出

新增 `api/track` 包(9 个类型):
- `TrackedChanges`(门面,持有 `XWPFDocument`)、`TrackedChange`(holding-wrapper,持有 `CTRunTrackChange`)
- `TrackedChangeType` / `TrackedChangeFamily`(细粒度 kind + 粗粒度分组)
- `TrackedChangeLocation` / `TrackedChangeSegment` / `TrackedChangeSegmentKind`(path/segment 结构化位置)
- `ChangeDetails`(判别联合根)+ `TextChangeDetails`(文本类 payload)

新增 `internal/poi/TrackedChangeNodes`(唯一接触 OOXML 的脏活层):`isEnabled` 读 `<w:trackChanges/>`;`collect` 用 `XmlCursor` 按 body→table→row→cell→paragraph 深度遍历,命中 `ins/del/moveFrom/moveTo` 产出修订。

`Document.trackedChanges()` 接入(参照 `toc()` 模式,但总是返回非 null)。

测试:`TrackedChangesTest` 10 个用例。

### 关键决策(planning 评审拍板,已回写 design.md)

- **A. 包装形态**:`TrackedChange` 走 holding-wrapper 持 `CTRunTrackChange`(参照 `Section(XWPFDocument, CTSectPr)` 先例),**不是**不可变解析值——因 tracked changes 有干净 per-revision CT 节点(与 TOC 不同)。
- **B. 稳定 id 边界**:**进程内稳定**,不承诺跨 save/reopen 稳定。
- **C. 高级类型节点**:`list()` 目前跳过 `*PrChange`/`cellIns` 等,留给 advanced-types 子任务。

### 实现中发现的两条 POI/OOXML gotcha(留给 docs-spec 子任务回写 poi-bridge.md)

1. **开关读取**:`<w:trackChanges/>` 在 POI 暴露为 `XWPFSettings.isTrackRevisions()`(OOXML 元素名与 POI 方法名不一致)。
2. **四类文本/移动修订同型**:`ins`/`del`/`moveFrom`/`moveTo` 在精简 schema 下统一由 `CTRunTrackChange` 承载(继承 `CTTrackChange` 给 author/date,`CTMarkup` 给 id);`del`/`moveFrom` 用 `<w:delText>` 而非 `<w:t>`。

### Testing

- [OK] `TrackedChangesTest`:10/10 通过
- [OK] 全量 `mvn -pl nondocx-core verify`:**138 tests, 0 failures**,spotless clean
- [OK] 手工往返验证:带 `<w:ins>` 的 docx save→reopen 后修订正确重现

### AC 自检

AC1 开关 ✅ / AC2 文档顺序枚举(文本类)✅ / AC3 get(id) 命中+miss 抛 NoSuchElementException ✅ / AC4 无便利筛选 ✅ / AC5 现有 API 无回归 ✅

### Status

[OK] **实现与质检完成,待开发者验收 + commit**

### Next Steps

- 开发者验收
- commit;docs-spec 子任务负责把上述两条 gotcha 回写 spec、纠 README/异常示例的 tracked-changes raw-only 旧表述

---

## Session 7: read 子任务归档 + docs-spec 文档与 spec 收尾

**Task**: `06-18-tracked-changes-read`(归档)→ `06-18-tracked-changes-docs-spec`(实现)

### What

1. **归档 read 子任务**:read 代码已在 `0c673f0`/`e75c6e5` 落地并提交,但 task 状态一直停在 `in_progress`。`task.py archive` 把它移到 `archive/2026-06/`,scoped 自动提交 `82247c1`(仅触碰 read 源→目路径,未卷入其他未跟踪任务目录)。父任务进度变为 1/5(read 计入 done)。
2. **docs-spec 收尾**:核对 read 侧真实落地边界(以代码为 source of truth),纠正 4 处把 tracked changes 整体视作 raw-only 的过期描述,并回写 2 条 POI/OOXML gotcha。

### 改动产出(7 文件)

**纠正过期描述(4 处)**:
- `README.md`:raw-only 列举去掉「修订」,新增「修订只读消费」正面 bullet(写清覆盖文本类、accept/reject/创作侧待后续)
- `UnsupportedFeatureException.java`:典型示例从「修订更改未被封装」换成「文本框/形状」(真正仍 raw-only);顺带修了一个误引入的 `派进`→`深度` 错字
- `poi-bridge.md` Rule 3:`raw()` 覆盖的 out-of-scope 列举去掉 tracked changes,补说明是 partial wrap
- `error-handling.md` Rule 5:同上 + 「部分封装按已封装部分算支持」

**回写 gotcha(1 处)**:
- `poi-bridge.md` 新增 **N12** 注记:① 开关 `<w:trackChanges/>` ↔ POI `isTrackRevisions()` 名字不一致;② 四类文本/移动修订同型由 `CTRunTrackChange` 承载、`del`/`moveFrom` 用 `<w:delText>`;并说明为何对 Rule 1 无偏离(有干净 per-revision CT 节点,与 TOC N11 不同)

**连带**:`docs-spec/{implement,check}.jsonl` 的 `read/design.md` 路径同步到 `archive/2026-06/`。

### 诚实边界(贯穿全部改动)

- ✅ 已封装:只读消费(`enabled()`/`list()`/`get(id)`)
- ⚠️ 仍 raw-only:accept/reject、authoring、advanced-types(三块均未实现,文档保留此边界)

### Testing

- [OK] `task.py validate` — 9/9 entries 通过
- [OK] `mvn -pl nondocx-core test` — 138 tests, 0 failures,BUILD SUCCESS

### AC 自检

AC1 不再整体 raw-only ✅ / AC2 异常示例文案已换 ✅ / AC3 spec 与真实边界一致 ✅ / AC4 gotcha 已回写 N12 ✅ / AC5 未过度承诺 ✅

### Status

[OK] **实现与质检完成,待 commit**

### Next Steps

- commit(代码 + 文档 + spec + journal 一起)
- docs-spec 子任务归档

---

## Session 8: accept-text 子任务(文本类 accept/reject)

**Task**: `06-18-tracked-changes-accept-text`(实现)

### What

在 `TrackedChanges` 门面上补齐文本类修订(`ins`/`del`)的 accept/reject:全部、按作者、按稳定 id 三粒度。这是 read 侧稳定 id 契约的**第一个真实消费者**。

### 关键决策

1. **scope 守 PRD**:门面只对 `family == TEXT` 生效。底层 `acceptText`/`rejectText` 虽同型可处理 `moveTo`/`moveFrom`,但门面 gate 住——命中 `MOVE`/属性类/cell 类抛 `UnsupportedFeatureException`。
2. **破坏性写的安全模式**:`all`/`byAuthor` 用「重算 → 应用第一条 → 重算」循环。起因:探针验证时触发过 `XmlValueDisconnectedException`——一次 collect 后批量改会让其它节点 cursor 失效。重算循环规避了这个真实风险。
3. **CT 手术 mechanics(先探针后实现)**:这是代码库首个破坏性 CT 写。先写一次性探针确认 XmlBeans API 行为(`moveXml`/`removeXml`/`delText`→`t` 转换),删探针,再写生产代码。确认后 mechanics 直接复用。

### 改动产出(3 文件 + spec)

- `internal/poi/TrackedChangeNodes.java`:新增 `acceptText`/`rejectText` + 3 私有手术方法(`unwrapRunsAndRemove` / `removeNode` / `restoreDelTextToT`);类 Javadoc 从"只读"更新为"读+文本类写"
- `api/track/TrackedChanges.java`:新增 6 个门面写入口(`acceptAll/rejectAll/acceptByAuthor/rejectByAuthor/accept(id)/reject(id)`)+ `applyRepeated`/`applySingle`/`applyOne`/`requireAuthor` 私有;类 Javadoc 同步
- `TrackedChangesTest.java`:新增 11 用例 + `paragraphChildText` 结构断言辅助(直接遍历段落 CT,避开 POI `getText()` 对 ins/del 的含混行为)
- spec `poi-bridge.md` 新增 **N13**(accept/reject 的 XmlCursor 手术 mechanics、delText→t 转换、节点失效循环);同步更新 N12 范围行、Rule 3、`error-handling.md` Rule 5、README bullet——反映 accept/reject(文本类)已落地

### OOXML/POI/nondocx 三层(教学要点)

- **OOXML**:`ins`/`del` 是包住 `<w:r>` 的*包装元素*。accept=决定生效(拆包装),reject=撤销(丢弃)。`del` 里被删文本存为 `<w:delText>`(不是 `<w:t>`),reject 时要转回普通 `<w:t>`。
- **POI**:没有高层 accept/reject API。两把手术刀:`XmlCursor.moveXml(anchor)`(节点移到 anchor 之前,cursor 自动推进)、`cursor.removeXml()`(删节点)。
- **nondocx**:全部下沉 `internal/poi/TrackedChangeNodes`,门面 6 个干净方法。

### Testing

- [OK] 探针 3 场景(accept ins / reject del / reject ins)全绿,确认 mechanics
- [OK] `TrackedChangesTest`:21/21 通过(10 原 + 11 新)
- [OK] 全量 `mvn -pl nondocx-core verify`:**149 tests, 0 failures**,spotless clean

### AC 自检

AC1 acceptAll/rejectAll ✅ / AC2 byAuthor 精确+大小写敏感 ✅ / AC3 accept(id)/reject(id) 命中 ✅ / AC4 id miss 抛 NoSuchElementException ✅ / AC5 应用后 ins/del 标记消失、内容结果正确(ins 保留/del 移除/del-reject 恢复)✅ / AC6 现有读 API 无回归 ✅

### Status

[OK] **实现与质检完成,待 commit**

### Next Steps

- commit
- accept-text 子任务归档
- 下一子任务:authoring 或 advanced-types

---

## Session 9: authoring 子任务(显式创作 API)

**Task**: `06-18-tracked-changes-authoring`(实现)

### What

在 `Paragraph`/`Run` 上补齐文本类 tracked 创作的显式 API:`addInsertion` / `addDeletion` / `replaceTracked`。写出的修订能被 `TrackedChanges.list()` 读回,与开关正交。

### 关键决策

1. **方法落点在内容所属类型,不在门面**(design §3.1):创作属于「在某处写内容」,门面是「对文档修订状态负责」。与 accept/reject 落在门面不同。
2. **`addDeletion` 不返回原 Run**:迁入 deletion 语义路径后原 run 已非稳定普通 live wrapper,继续暴露会误导(design §3.2)。返回 `this` 段落。
3. **`replaceTracked` 复制源 run 样式**:替换前快照六个内联样式属性,应用到新 ins run。
4. **`w:id` 与稳定 id 是两套概念**:`nextRevisionId` 扫 max+1 生成底层 OOXML id;它与 read 侧 nondocx 稳定 id 混合串不是一回事(design §5.3)。

### 探针发现的两个 POI 坑(先探针后实现)

1. **`XWPFParagraph.getRuns()` 不暴露 ins/del 内的 run**:必须从 `CTRunTrackChange.getRList()` 拿 CTR,再 `new XWPFRun(ctr, paragraph)` 重构,否则返回的 Run 无法链式操作。
2. **迁既有 run 入 `<w:del>` 不是 `addNewR`**:要新建空 del → t 转 delText → `XmlCursor.toEndToken()`+`moveXml` 把 CTR 迁入 del 内部。

### 改动产出(4 文件 + spec + 新测试)

- `internal/poi/TrackedChangeNodes.java`:新增 `addInsertion` / `addDeletion` / `nextRevisionId`;类 Javadoc 补创作职责
- `api/text/Paragraph.java`:新增 `addInsertion` / `addDeletion` + `requireAuthor`/`containsRun` 私有
- `api/text/Run.java`:新增 `replaceTracked`(含样式复制)
- 新增 `TrackedAuthoringTest.java`:10 用例
- spec `poi-bridge.md` 新增 **N14**(两个 POI 坑 + w:id/稳定 id 概念区分);同步 N13 范围行、Rule 3、`error-handling.md` Rule 5、README bullet——反映 authoring 已落地

### OOXML/POI/nondocx 三层(教学要点)

- **OOXML**:`ins`/`del` 是包住 `<w:r>` 的包装元素。insertion=新建包装+run;deletion=迁既有 run 入包装 + t→delText;replacement=del+ins 组合。
- **POI**:无创建 tracked 修订的高层 API。两个坑:getRuns 不暴露包装内 run;迁 run 要 toEndToken+moveXml。
- **nondocx**:节点创建下沉 `internal/poi`,公共 API 在 `Paragraph`/`Run`,POI-free。

### Testing

- [OK] 探针 2 场景(insertion 读回 / deletion 迁入)全绿,确认 mechanics
- [OK] `TrackedAuthoringTest`:10/10 通过
- [OK] 全量 `mvn -pl nondocx-core verify`:**159 tests, 0 failures**,spotless clean

### AC 自检

AC1 insertion ✅ / AC2 deletion ✅ / AC3 replacement(del+ins,样式复制)✅ / AC4 元数据 author/date/w:id ✅ / AC5 写出可被 list() 读回 ✅ / AC6 普通写 API 无回归(普通 addRun 不产修订)✅

### Status

[OK] **实现与质检完成,待 commit**

### Next Steps

- commit
- authoring 子任务归档
- 最后一个子任务:advanced-types

---

## Session 10: advanced-types 子任务(move + property;cell 拆出)

**Task**: `06-18-tracked-changes-advanced-types`(research-first,实际做 move + property)

### What

研究先行(探针捕获真实 OOXML),据研究拆分范围:**做 move + property,cell 拆出独立子任务**。move 补 accept/reject 配对联动;property 补 rPrChange 的读 + accept/reject 整树替换。

### 研究产出(research/ooxml-forms.md,关键结论)

1. **move 与文本类完全同型**(CTRunTrackChange),read 侧已覆盖;accept/reject mechanics 直接复用。
2. **property 结构不同**:rPrChange 嵌在 `<w:rPr>` 内部(不是 run 包装层),类型是 CTRPrChange;与 CTRunTrackChange 共同父是 CTTrackChange(无 CTPrChange 中间类)。
3. **cell 嵌在 tcPr 内**,结构风险最高 → 拆出。
4. **配对无显式指针**:靠 author+text 启发式(date 不作硬约束)。

### 关键决策

1. **范围**:move + property;cell 拆新子任务(用户决策)。pPrChange 因 CTPPrChange 不在 POI 精简 classpath,v1 留边界。
2. **property 走方案 C**(用户决策):读统一(进 list() + PropertyChangeDetails),写专用(acceptProperty/rejectProperty)。不动现有 raw() 契约。
3. **TrackedChange 改双委托**:CTRunTrackChange(文本/移动)+ CTTrackChange(属性);raw() 对 property 抛 UnsupportedFeatureException;新增包内 propertyNode()。
4. **move 配对靠 author+text**:曾用 author+date+text 三元组,但 date 跨秒不稳,测试暴露后改为 author+text;孤立 move 抛异常不降级。

### 改动产出

- `TrackedChange`:双委托字段 + 属性构造函数(public)+ propertyNode() 包内接缝;raw() 对 property 抛
- `TrackedChangeNodes`:read walker 下钻 rPr 枚举 rPrChange;新增 acceptProperty/rejectProperty(整树替换,旧 rPr 是 CTRPrOriginal 走 XmlCursor 搬运)
- `TrackedChanges`:门面 gate 放宽到含 MOVE;applyMove 配对联动(findMoveCounterpart);acceptProperty/rejectProperty 专用写
- 新增 `PropertyChangeDetails` + `PropertyChangeKind`
- 新增 `TrackedAdvancedTypesTest`(8 用例)
- `research/ooxml-forms.md`(研究文档,保留)
- spec `poi-bridge.md` 新增 **N15**;同步 N14 范围行、Rule 3、error-handling Rule 5、README bullet

### 探针验证的 mechanics(先探针后实现)

- move 同型(CTRunTrackChangeImpl)确认
- property 嵌在 rPr 内、类型 CTRPrChange 确认
- 配对 author+text 启发式(测试 fixture 用 delText 表示 moveFrom 文本)

### Testing

- [OK] 探针 3 类(move/property/cell)真实 OOXML 捕获,结论入 research
- [OK] `TrackedAdvancedTypesTest`:8/8 通过
- [OK] 全量 `mvn -pl nondocx-core verify`:**167 tests, 0 failures**,spotless clean
- [OK] TrackedChange 双委托重构未破坏 read/accept/authoring 现有测试

### AC 自检

AC move 配对 accept/reject ✅ / AC move 孤立抛异常 ✅ / AC property(rPrChange)读 ✅ / AC property accept(留新树)✅ / AC property reject(旧树覆盖)✅ / AC property raw() 抛 + 专用写 ✅

### Status

[OK] **实现与质检完成,待 commit**

### Next Steps

- commit
- advanced-types 归档
- 回 planning 创建 `06-18-tracked-changes-cell-types` 子任务(cell)
- 父任务接近完成:剩 cell + pPrChange/sectPr 等更高层属性类


## Session 6: DocxAgentTools 工具批量改造(单次/多次调用)

**Date**: 2026-06-21
**Task**: DocxAgentTools 工具批量改造(单次/多次调用)
**Branch**: `feat/agent-tools-batch`

### Summary

把 Agent 示例的 21 个 docx 工具从「单次调用」升级为「通用版」:方法签名改为接收 List,长度 1 即单次。分三梯队完成。第一梯队(read_paragraph/replace_run_text/replace_table_cell_run_text/append_paragraph/accept+reject_text_or_move_revision)建立批量约定:单字段→平行数组、多字段→对象数组、写类 collect-errors。第二梯队(read_table_cell/insert+delete+replace_run_tracked/mark_cell_inserted+deleted)探针推翻了「批量删除需逆序」的原假设——实测 addDeletion 后 runs() 计数不变(索引不漂移),真正问题是重复操作同一 run 会抛 XmlValueDisconnectedException,故改用按坐标去重。第三梯队(read_run/accept+reject_property_change/accept+reject_cell_change + 合并 update_hyperlink)探针验证 accept 后 id(路径坐标编码)不漂移,可安全逐条循环;并把三类 accept/reject 的循环抽成通用 applyRevisionsByIds(用 BiConsumer 接收方法引用)。合并 update_hyperlink_text/url 为 update_hyperlink(text/url 都可选)。发现 POI 超链接关系缓存的既有行为(URL 改动需 save+reopen 才能稳定读回)。测试:新增 DocxAgentToolsBatchTest(22 个)+ 现有测试调用点同步,全模块 34 个测试全绿。两个探针驱动的发现是最有价值的部分。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `297fb80` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 7: 借鉴 docx skill: WPS/Word 渲染兼容性 spec + core API

**Date**: 2026-06-22
**Task**: 借鉴 docx skill: WPS/Word 渲染兼容性 spec + core API
**Branch**: `main`

### Summary

探索 zcode docx skill，对比 nondocx 后创建 docx-skill-adoption 父任务 + 2 P0 子任务。完成子任务 1 renderer-compatibility：新增 renderer-compatibility.md spec（9 条规则四段式+锚点）+ 连带新建 Cell/Paragraph.shading、Cell.verticalAlign、Table.columnPercents/columnWidths、Section.cleanEmptyPageNumbering API。实现期关键发现：XmlBeans 接口继承链让 typed accessor 在父接口（CTTcPrBase/CTPPrBase），XmlCursor 非必需；getFill() 返回 byte[] 须用 xgetFill().getStringValue()。45 新测试+288 全量测试全绿。spec 锚点清单已交付子任务 2 quality-check-tools。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `8583824` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 8: 借鉴 docx skill: toolkit 质量自检工具 + Row 分页 API

**Date**: 2026-06-22
**Task**: 借鉴 docx skill: toolkit 质量自检工具 + Row 分页 API
**Branch**: `main`

### Summary

完成 docx-skill-adoption 父任务的第 2 个 P0 子任务 quality-check-tools。新增 toolkit 第 7 个工具类 QualityCheckTools（10 项版式/兼容性自检，内存为主执行模型）+ core Row.headerRow()/cantSplit() API（为表格分页检查提供能力）。关键决策：Q1 内存为主（会话不跟踪文件路径，磁盘方案成本高）；Q2 9 项内存可跑 + 新建 API 补 #4。10 项检查借鉴 docx skill postcheck.py 但走 nondocx 活对象 API，shading-solid 走 raw 兜底，引用子任务 1 spec 锚点。25 新测试 + 313 全量测试全绿。至此父任务两子任务全部完成。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `97754e8` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 9: 页眉页脚编辑 API 补齐（首页/偶数页变体 + 图片/表格/页码域）

**Date**: 2026-06-23
**Task**: 页眉页脚编辑 API 补齐（首页/偶数页变体 + 图片/表格/页码域）
**Branch**: `main`

### Summary

为 nondocx 补齐页眉页脚的变体（首页/偶数页）、富内容（表格/图片）、页码域三条能力线。新增 HeaderFooterVariant 枚举与 Section/Document 变体重载，ensure 时显式补齐 POI 不自动写的 titlePg/evenAndOddHeaders 开关；Header/Footer 加 addTable/tables，图片经 Paragraph.addImage 直接复用（探针确认 XWPFHeader 实现 IBody）；Paragraph 加 addSimpleField + addPageNumberField/addPageCountField，产出标准 3-run 域结构。spec 落档 poi-bridge N19/N20/N21 + renderer-compatibility even-odd-headers。289 tests green。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `414f937` | (see git log) |
| `92230e8` | (see git log) |
| `32ba945` | (see git log) |
| `7464bb1` | (see git log) |
| `ec2f672` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 10: docx compare to tracked changes

**Date**: 2026-06-30
**Task**: docx compare to tracked changes
**Branch**: `main`

### Summary

完成 Docx.compare API、正文文本段落 compare MVP、回归测试与示例，并归档 compare 任务。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `59b6809` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 11: 归档 tracked changes 已完成子任务

**Date**: 2026-06-30
**Task**: 归档 tracked changes 已完成子任务
**Branch**: `main`

### Summary

验证并归档 tracked changes 单元格结构类与高级类型创作两个已完成但未归档的任务；comments-read 因仍有未提交代码改动暂不归档。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `07c3540` | (see git log) |
| `f63cc0f` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 12: 归档 comments-read 子任务

**Date**: 2026-06-30
**Task**: 归档 comments-read 子任务
**Branch**: `main`

### Summary

确认 comments-read 功能与测试已完成；补交注释与测试格式整理提交后，归档 06-22-comments-read 任务。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `e9b6538` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 13: compare batch 2: run 级样式保真落地与收尾

**Date**: 2026-07-07
**Task**: compare batch 2: run 级样式保真落地与收尾
**Branch**: `main`

### Summary

推进 06-30-compare-batch2-upgrades 任务至完成：为可归约成单一样式的纯文本段落补上 run 级六样式保真（删除看旧/插入看新/未改看旧），复杂混排与超链接/field 段落继续静默跳过。验证核心套件 306 测试全过、example main 实跑样式保真生效，并修复 spotless 格式。提交实现后走 finish-work 归档。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `2112a2f` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 14: comments 基础创作（单条范围批注）

**Date**: 2026-07-07
**Task**: comments 基础创作（单条范围批注）
**Branch**: `main`

### Summary

完成 comments 父任务第 2 个子任务 06-22-comments-authoring：Paragraph.addComment(author, text) → Comment 单条整段范围批注的显式创作。核心发现是 POI 的 addNew/insertNew 不按 OOXML schema 顺序排序——给已有内容的段落 addNewCommentRangeStart 会落到段末、范围为空，必须 XmlCursor 手动 move 到 CTP 第一个子之前（探针三方案 A/B/C 对比验证，仅 C 正确）。这是 comments 创作区别于 tracked-changes addInsertion（新建容器包新 run、顺序天然正确）的核心脏活。另发现 XWPFComment.getId() 返回 String 与 createComment(BigInteger) 入参类型不对称，nextCommentId 需解析回 long 取 max；批注 id 与修订 id 是两套独立 OOXML 计数器，不复用 nextRevisionId。initials 设空串、rStyle 不建样式（低风险，AC4 Word 人工验收通过）。交付：CommentNodes 下沉脏活 + Paragraph 公共入口 + CommentsAuthoringTest 7 用例；全量 313 tests green；spec 增 N22、N18 补交叉引用。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `15f5dc5` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 15: comments 回复+线程（commentsExtended 四 part 自维护）

**Date**: 2026-07-08
**Task**: comments 回复+线程（commentsExtended 四 part 自维护）
**Branch**: `main`

### Summary

完成 comments 父任务第 3 个子任务 06-22-comments-reply-threads：Comments.reply(parentId, author, text) → Comment 批注回复 + Comment.parentId()/paraId() 线程建模。POI 对 commentsExtended/Ids/Extensible 三个 part 零支持，nondocx 首次建立「自维护 OOXML part」模式：新建 CommentExtendedParts 内部类，用 OPCPackage.createPart（自动注册 Content_Types）+ addRelationship + DOM 读-改-写自维护三 part。线程读侧 ThreadResolver 在 collect 入口建 paraId↔commentId 映射，两步 join commentsExtended 的 paraIdParent 得 parentId（paraId 是中间 key，非直接 commentId→parentId）。paraId/durableId 8 位 hex（<0x7FFFFFFF）；authoring 产出的批注无 paraId，reply 时给父批注补 paraId 防 paraIdParent 链断。实现期踩到关键 POI 坑：MemoryPackagePart.getOutputStream() 累加语义——多次写入要先 clear() 再写，否则 part 内容拼出多份非法 XML（readDom 解析失败）。Comment 扩展 paraId/parentId 字段（不纳入 equals，保 read 五字段契约）。交付：CommentExtendedParts（新）+ CommentNodes.replyToComment/ThreadResolver + Comments.reply + Comment.paraId/parentId + CommentsReplyThreadsTest 8 用例；全量 321 tests green；spec 增 N23、N22 补交叉引用。AC4 Word 线程显示人工验收通过（父-子层级/多级链 A→B→C/兄弟回复都对）。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `f841ce4` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 16: comments 基础设施: people.xml/paraId/RSID 自动注入

**Date**: 2026-07-08
**Task**: comments 基础设施: people.xml/paraId/RSID 自动注入
**Branch**: `main`

### Summary

实现 comments-infrastructure 子任务:三项现代 Word 兼容基础设施自动注入。新建 internal/poi/AuthoringInfra 作为统一入口——people.xml(w15,复用 N23 OPC 自维护模式,ensurePart/readOrCreateDom/writeDom 提升为 package-private 共享)、w14:paraId(从 CommentNodes 私有 helper 提升到 public,补到 addComment 路径)、RSID(Document 级单例,持久化在 settings.xml <w:rsidRoot>;CTDocRsids 是 dangling reference 走 XmlCursor)。实现期关键发现:XmlCursor.beginElement 后 cursor 停在 END(非 START),建嵌套结构需 toNextToken 导航。tracked-changes 创作路径不接入(AC6 隔离)。CommentsInfrastructureTest 11 用例,全量 332 tests green、spotless clean。spec poi-bridge.md 增 N24。AC4 Word 人工验收留 review gate。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `5bf0b86` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 17: comments 文档+spec 收尾 — 父任务 06-22-comments 全部完成

**Date**: 2026-07-08
**Task**: comments 文档+spec 收尾 — 父任务 06-22-comments 全部完成
**Branch**: `main`

### Summary

完成 comments 能力线最后一步(docs-spec 子任务),父任务 06-22-comments 五子任务全部交付并归档。本会话含两项工作:① infrastructure 子任务(people.xml/paraId/RSID 自动注入,见前次 journal 详述);② docs-spec 子任务——docs/06-comments/ 四篇教学文档(README+01-concepts/02-read/03-authoring/04-infrastructure,对照 05-tracked-changes 三层递进)、03-api-reference.md 补 Comments/Comment 速查、docs/根 README 索引更新(不重编号,comments 作专题插入)、spec index.md 修正 Scope Boundaries(tracked+comments 从 raw-only 改标已落地)、CommentsExample.java 完整闭环示例。父任务 AC 全绿:AC1 五子任务/AC2 POI-free(仅内部接缝)/AC3 文档对称/AC4 示例可运行/AC5 全量 332 tests green。实现期修正了 02-read.md 三个字段类型写错(date/initials/paraId 非 Optional)。comments 能力线(read→authoring→reply-threads→infrastructure→docs-spec)对称 tracked-changes 完整收官。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `5bf0b86` | (see git log) |
| `c656828` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 18: OnlyOffice 实时预览的 nondocx Agent 对话 demo

**Date**: 2026-07-08
**Task**: OnlyOffice 实时预览的 nondocx Agent 对话 demo
**Branch**: `feat/onlyoffice-demo`

### Summary

新建 nondocx-demo 子模块,把 nondocx-toolkit 的 Agent 工具集包装成可交互网页:左侧 OnlyOffice 实时预览 docx,右侧 Agent 对话驱动编辑,save_docx 后自动刷新。复用 DocxToolkit + nonchain Agent(主库零改动)。后端 Javalin 6.6.0,SSE 流式推送 AgentEvent;前端纯静态 HTML/JS。支持上传自有文档(换文档清 Agent 记忆)。docker compose 编排 OnlyOffice,踩了三个坑并解决:首启 OOM(内存调 8GB+)、私有 IP 被 SSRF 防护挡(ALLOW_PRIVATE_IP_ADDRESS=true)、bind mount local.json 致 EBUSY 崩溃(改用环境变量)。全项目 mvn verify 绿。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `c2dd76b` | (see git log) |
| `679f002` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 19: Toolkit tool surface consolidation

**Date**: 2026-07-08
**Task**: Toolkit tool surface consolidation
**Branch**: `feat/onlyoffice-demo`

### Summary

Consolidated the nondocx toolkit Agent tool surface into intention-oriented entrypoints, removed pre-production compatibility methods, updated docs/examples/tests, and verified with full mvn test.

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `48db5f2` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 20: 完成 P0-01 稳定语义寻址

**Date**: 2026-07-10
**Task**: 完成 P0-01 稳定语义寻址
**Branch**: `main`

### Summary

完成稳定语义引用协议、工具接线、回归测试、Example、文档与 spec 更新，并通过全量 verify。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `7b1e52b` | (see git log) |
| `1891ea2` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 21: P0-02 结构化工具结果

**Date**: 2026-07-12
**Task**: P0-02 结构化工具结果
**Branch**: `main`

### Summary

建立统一 ToolResult<T> 双段格式（中文消息 + JSON envelope），消除全仓 startsWith/contains 错误 嗅探。新建 result/ 包（6 核心类 + 37 测试），迁移 55 个 @ToolDef 方法、4 executor、 DocxOrchestrator。TableTools 内部 7 处嗅探全消除。ToolkitToolContext 提取 6 个共享工厂。 nonchain 框架 Object.toString() 硬约束决定了 @ToolDef 必须返回 String、内部序列化的架构走向。 523 测试全过。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `77fec2f` | (see git log) |
| `0924a89` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 22: P0-03 机器可读能力契约

**Date**: 2026-07-12
**Task**: P0-03 机器可读能力契约
**Branch**: `main`

### Summary

为 nondocx-toolkit 建立机器可读能力契约：新增 capability 包（@ToolCapability/@ParamCapability/@NestedParamCapability 伴生注解 + CapabilityCollector 反射收集器 + CapabilityManifest 模型 + digest/jsonio），55 个 @ToolDef 工具全部标注能力元数据；describe_capabilities 工具支持 element/operation/level 过滤；exec-maven-plugin 在 process-classes 阶段生成 capabilities.json+digest；CapabilityContractTest 强制缺注解构建失败/声明工具须有测试/ENUM 须有 enumValues/digest 稳定。真实来源为 Java 代码+注解，不手写 schema。mvn verify 全绿（208 测试）。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `b6d25cf` | (see git log) |
| `5e7b13d` | (see git log) |
| `5eb682b` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 23: P0-04 统一语义视图

**Date**: 2026-07-12
**Task**: P0-04 统一语义视图
**Branch**: `main`

### Summary

实现 DocumentViewService 只读服务，复用 SnapshotBuilder 单遍历，提供 6 种语义视图（outline/text/annotated/stats/issues/element）。新增 ViewTools 工具类（6 个 view_* 工具），迁移 get_document_overview 为委托 stats 视图的薄适配层。QualityCheckTools.CheckResult 提升为 public + runAllChecks()。ToolkitToolContext 提升为 public abstract。ToolResultRenderer 配 Jackson FIELD visibility 支持无 getter DTO 序列化。25 个测试，mvn verify 全绿。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `3ee1393` | (see git log) |
| `2247b7b` | (see git log) |
| `7ad6194` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 24: 完成 Router 编排协议基建

**Date**: 2026-07-12
**Task**: 完成 Router 编排协议基建
**Branch**: `main`

### Summary

完成 Router 多子代理 foundation 收尾：确认协议模型、状态机、review 闸门与非事务失败重开语义；补充 orchestration 规范；修复 P0-05 进行中的可选写参数兼容调用，toolkit 聚合测试通过。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `b399b74` | (see git log) |
| `c9acef7` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 25: P0-05 写安全协议落地 + 批量归档全部 router 子代理任务

**Date**: 2026-07-13
**Task**: P0-05 写安全协议落地 + 批量归档全部 router 子代理任务
**Branch**: `main`

### Summary

落地 P0-05 写操作安全协议：ToolResult 新增 changedCount/skippedCount 计数字段；ToolkitToolContext 增 noChangesApplied/generationMismatch/checkExpectedGeneration 协议工厂；BodyTools/TableTools/TrackedChangeAuthoringTools/TrackedChangeQueryTools 全部写工具接入 expected_generation/on_error(stop)/confirm_all 参数与计数回报；BodyExecutor/TableExecutor/RevisionExecutor 调用点适配新签名；DocxToolkitBatchTest 40 用例全通过。会话内还批量归档了全部 8 个已完成任务（07-09 router 子代理系列 5 个 + 07-10 两个修复 + p0-05）。demo 模块的 VLLM 联调与 prompt 日志改动保留为工作区 WIP 未提交。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `805530d` | (see git log) |
| `fd209cb` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 26: Demo 执行链路可视化: LLM trace (prompt/response/thinking) 流式展示

**Date**: 2026-07-13
**Task**: Demo 执行链路可视化: LLM trace (prompt/response/thinking) 流式展示
**Branch**: `main`

### Summary

让 Demo 内部 Agent 的 LLM 调用过程可见。新增 LlmTraceEvent 值对象(4 事件 + agentName),ExpertAgent.plan 签名加 Consumer<LlmTraceEvent>,RouterAgent/DocxOrchestrator 增四参 run 重载透传 traceCb(与阶段级 PhaseCallback 并行)。LlmDocxExpert.callLlm 改 streamChat,逐 chunk 推 content/thinking delta。AgentBridge 转 SSE trace 帧。前端 app.js 在进度卡内渲染折叠区:prompt 只读块 + response/thinking 双 tab 逐字追加。6 个 ExpertAgent 实现类 + 2 测试 mock 签名对齐(5 个启发式专家忽略 traceCb)。toolkit 231 测试通过,demo 编译通过,index.html 零改动。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `4951b46` | (see git log) |
| `71bcc3a` | (see git log) |
| `2c269ea` | (see git log) |
| `39e2db7` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 27: 协商与专家实施分离

**Date**: 2026-07-13
**Task**: 协商与专家实施分离
**Branch**: `main`

### Summary

Demo 改为主 Agent 只读协商、按钮授权、分组专家实施、集中提交回滚、质量检查与 JSONL trace 回放。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `4d75d19` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 28: 前端协商实施可视化

**Date**: 2026-07-13
**Task**: 前端协商实施可视化
**Branch**: `main`

### Summary

Demo 前端改为统一 SSE/JSONL reducer 和按批次时间线，展示授权、实施、专家 trace、review、提交、回滚与质量状态。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `f5a7695` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 29: 主 Agent 协商边界修正

**Date**: 2026-07-13
**Task**: 主 Agent 协商边界修正
**Branch**: `main`

### Summary

主 Agent 移除能力清单工具并强化只协商 Prompt，写工具可行性与操作规划下放到授权后的专家。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `6891520` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 30: 专家流式操作解析修复

**Date**: 2026-07-13
**Task**: 专家流式操作解析修复
**Branch**: `main`

### Summary

专家优先解析累积的流式 content_delta，避免最终 ChatResult 内容缺失时有效 operation 被丢弃。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `HEAD` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 31: 专家分派与操作解析诊断

**Date**: 2026-07-13
**Task**: 专家分派与操作解析诊断
**Branch**: `main`

### Summary

修正文档标题分派到 body 专家，并补充专家 operation JSON 解析与合并诊断日志。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `ed72836` | (see git log) |
| `HEAD` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 32: 主 Agent 通过 SubAgent 编辑文档

**Date**: 2026-07-13
**Task**: 主 Agent 通过 SubAgent 编辑文档
**Branch**: `main`

### Summary

移除 RouterAgent 编排层，demo 改为主 Agent 工具调用无状态 SubAgent；增加受限保存、质量/取消回滚、标题插入字段与 VLLM 真实委派测试。非 VLLM 回归通过；VLLM 服务返回无效工具调用，测试保留失败健康门槛。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `dbae09f` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 33: SubAgent 兜底保存与真相更正 + SessionTools.reopen

**Date**: 2026-07-14
**Task**: SubAgent 兜底保存与真相更正 + SessionTools.reopen
**Branch**: `main`

### Summary

为主文档 Agent 引入编排层双重保障，弥合 SubAgent 自述与服务端真相的差距：toolkit 层新增 SessionTools.reopen（保持 docId 稳定重载磁盘版本，替代 close+open 避免 seq 漂移失效）；demo 层 AgentBridge 增加 attemptFallbackSave（SubAgent 漏调 save 时复用 saveCurrentDocument 走质检兜底落盘）与 correctSubAgentResult（用 execution 真相更正 SubAgent 自述 JSON，强制未保存时 success/changed=false），并将 current_document 拆出独立 CurrentDocumentTools 使主 Agent 可只读获取 docId 而不获得保存能力。测试全绿（2+3+7）。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `640c4fb` | (see git log) |
| `0e2bca7` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 34: demo 单 Agent 回归：撤销 SubAgent 委派层

**Date**: 2026-07-15
**Task**: demo 单 Agent 回归：撤销 SubAgent 委派层
**Branch**: `feat/demo-single-agent-revert`

### Summary

撤销主+SubAgent 双层拓扑回归单 Agent。保存从 LLM 工具改为 AgentEvent.Complete 时应用层强制 flush，从源头消灭漏调/谎报导致的真相弥合复杂度。删除 DocumentExecutionState/attemptFallbackSave/correctSubAgentResult 整层，新增 dirty 检测+α瘦身+β note+edit_outcome 系统帧。净减 907 行，demo 16 测试全绿。顺带清理前端 RouterAgent 时代 ~260 行死代码。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `82b9c57` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 35: Demo 引入 nonchain 0.11.0 Skills

**Date**: 2026-07-16
**Task**: Demo 引入 nonchain 0.11.0 Skills
**Branch**: `feat/demo-single-agent-revert`

### Summary

升级 nonchain 至 0.11.0，单 Agent 文档工作流接入 6 条顶层 Skill（inspect-document/edit-body/edit-table/tracked-changes/audit-quality/inspect-special-parts）。DemoSkills 注册器从 classpath /skills/*.md 加载过程知识并 fail-fast；AgentBridge 用 SkillInjectionMode.SYSTEM 接入，systemPrompt 加非路由约束。traceEvent 新增 SkillActivated 分支（抽 skillActivatedFields 纯函数），SSE skill_activated 帧含 skill/description/contentLength 不含正文，经 TraceJournal 落盘供 JSONL 回放。前端 app.js 独立渲染 [Skill] 行，不触发 dirty/executing。新增 3 个测试类 11 个确定性测试（可编程 Mock LLM 验证 SYSTEM 注入 + SkillActivated），全 reactor 测试绿。Skill 保持纯知识语义：不经过 tool interceptor、不标 dirty、不改 save 门控。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `dfd63f7` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 36: 质检目标改为只读复审 SubAgent 复审意图达成度

**Date**: 2026-07-17
**Task**: 质检目标改为只读复审 SubAgent 复审意图达成度
**Branch**: `feat/demo-single-agent-revert`

### Summary

将 Demo 质检从「10 项客观版式规则自检」重构为「只读复审 SubAgent review_intent 复审用户期望的修改是否达成」。复审 SubAgent 仅 scan toolkit.view（不持有写/保存工具），是 agent-single spec 允许的唯一 SubAgent 例外；输出三态结论（达成/部分/未达成）+ 差异，作为软警告不拦截保存。删除原质检 error 硬门控与漏调兜底，saveCurrentDocument 简化为 dirty 即落盘。toolkit 零改动（10 项规则作为通用能力保留供 view_issues 复用）。期间修复实测 bug：contextSelector 漏注入 doc_id 导致复审读不到文档误判未达成，补 ReviewSubAgentTest 锁定契约 + spec Gotcha。37 demo 测试 + 187 toolkit 测试全绿。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `efce1d8` | (see git log) |
| `a6e8419` | (see git log) |
| `c3c39d5` | (see git log) |
| `494487e` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete
