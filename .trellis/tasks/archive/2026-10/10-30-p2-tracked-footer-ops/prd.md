# P2 续：PatchEngine 修订与页脚 op（补齐 compiler 面 2 个缺口）

## Goal

给 edit_document 补 tracked.insert/tracked.delete/tracked.replace 与 footer.set_page_number 四个 op：core 新增范围级 tracked 替换与带缓存结果的简单域两个 API，read_document 增加 页脚元数据行；目标 compiler 面 baseline 10/10（当前 8/10，2 失败均为预告 op 缺口）。

## 行为缺口（最小）

1. 模型无法经 `edit_document` 创作修订（tracked changes）。
2. 模型无法经任何面写页脚页码（full 面 footer ref 是只读的，写侧缺口两个面都有；上一轮 footer-page-number 双面失败）。
3. `read_document` 看不到页脚状态 → 模型无法核对页脚写入结果。

## 关键事实（已实证，2026-10-30）

- **纯 PAGE 域 `footer.text()` 无数字**：`addSimpleField("PAGE")` 是 begin/instr/end 三 run，无缓存结果段，POI `getText()` 读出「第  页」。评测 check 要求文本含数字 → 需**带缓存结果的域**（begin/instr/separate/result/end 五 run，Word 打开重算，非渲染读者读到缓存值）。
- **tracked del 后 `Paragraph.text()` 不再含被删文本**；**tracked ins 的文本不出现在 `text()`**（ins 内 run 不被 POI `getRuns()` 暴露）→ 同批多 op 的偏移量必须按执行时活文本重算，不能只信 preflight 快照。
- core 修订原语矩阵已有 `addInsertion`（仅段末追加）、`addDeletion(run)`、`deleteTrackedRange(start,end)`、`Run.replaceTracked`（整 run）；**缺范围级替换**（del 范围 + ins 紧随其后定位）。直接组合现有原语会把 ins 放到段末（句号后），视觉顺序错误，不可用。
- review_intent 复审 SubAgent 两面都用完整 view 工具组（含 view_tracked_changes）→ 修订可核对性已具备。

## Requirements

### core（2 个新 API + 1 个内部下沉 helper）

1. `Paragraph.replaceTrackedRange(String author, int start, int end, String newText)`：
   - 校验同 `deleteTrackedRange`（author/范围/代理对/纯文本映射）；
   - 实现：复用 `TrackedChangeNodes.addDeletionSlice` 逐 run 切片删除 + 新内部 helper「定位 ins」（XmlCursor 把新建 `<w:ins>` 移到**最后一个 del 之后**）；ins 内 run 复制首个被切 run 的 rPr（对齐 `Run.replaceTracked` 的「替换保留格式」语义）。
2. `Paragraph.addSimpleField(String instruction, String cachedResult)` 重载：
   - 五 run 结构 begin/instrText/separate/result(w:t=cachedResult)/end；返回**结果 run**（可见文本载体，可链式设样式；与单参版返回 instr run 的差异写进 Javadoc）。
   - 单参版委托 `addSimpleField(instruction, null)` 行为不变。

### toolkit（PatchEngine v2 + 视图 + 工具描述）

3. PatchEngine 新增 4 op（op 集 6→10）：
   - `tracked.insert`：anchor + text + author → `addInsertion`（段末追加，位置语义正确）。
   - `tracked.delete`：anchor + find + author → `deleteTrackedRange`。
   - `tracked.replace`：anchor + find + text + author → `replaceTrackedRange`（最小 diff：只 del「find」范围 + ins 紧随新文本）。
   - `footer.set_page_number`：format（默认「第 {page} 页」，`{page}`→PAGE 域、`{pages}`→NUMPAGES 域，缓存结果 "1"）→ `ensureFooter()` 追加新段落；正文段落不受影响。
   - **一致性规则**：同批 tracked.* op 每 anchor 至多 1 条（修订偏移按执行时活文本重算，同锚点多条互相污染；错误消息引导拆两次 edit_document）。
   - find 多处命中全部替换（与 replace_text 语义一致）。
   - 原子性不变：preflight（anchor/find/author/format 合法性）→ 全过才写，任一失败零写入。
4. `MarkdownView.render` 末尾：页脚存在且非空时追加一行 `<!-- 页脚: … -->` 元数据注释（round-trip 安全：解析器剥注释）。
5. `CompilerTools.edit_document` 描述 + NestedParamCapability 更新（author/format 字段）。

### examples（eval）

6. `COMPILER_SYSTEM_PROMPT` 增补：修订任务用 tracked.* op（带 author）、页脚页码用 footer.set_page_number。
7. 新增测试：范围级替换的位置与样式、缓存域结构、页脚 op、原子性、同锚点冲突拒绝。

### 文档/规范

8. docs/07（CompilerTools 章节）、docs/11 §6.5（P2 续 A/B 结果）、`.trellis/spec/backend/agent-single.md` compiler 面约定（op 集、同锚点规则、缓存域事实）。

## 不做

- 页眉 op、页脚自由文本 op（无对应任务，对称扩展留待需要时）。
- 批注创作 op（baseline 无批注任务）。
- accept/reject 修订处理（属 TrackedChangeQueryTools 域，compiler 面不收）。
- full 面 58 入口不动；compiler 面 6 入口不动（只加 op 不加工具）；demo 不动。

## Acceptance Criteria

- [ ] toolkit/examples/demo 全部测试绿，Spotless 绿；能力清单 66 工具数不变（digest 变）。
- [ ] `mvn -q -pl nondocx-core,nondocx-toolkit install -DskipTests` 后 `EvalMain --surface compiler --group baseline` 达 10/10（至少 tracked-replace、footer-page-number 双双通过）。
- [ ] 修订产物语义抽查：del 只包住「一百万元」、ins 紧随其后；页脚域含 separate + 缓存结果。
- [ ] docs/07、docs/11、agent-single.md、demo README 无需动（demo 未变）。
