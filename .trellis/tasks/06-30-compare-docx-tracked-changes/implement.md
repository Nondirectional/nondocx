# Implement Plan — docx compare to tracked changes（MVP）

> 本文件记录 compare MVP 的建议实施顺序、验证命令、风险回退点与 start 前门槛。目标是把“正文纯文本段落 compare → 修订结果文档”稳定落地，而不是在实现中偷偷扩 scope。

## 1. Start 前门槛

- [ ] `prd.md` / `design.md` / `implement.md` 已评审通过
- [ ] 已确认第一版只覆盖正文纯文本段落
- [ ] 已确认差异段落不保 run 级样式
- [ ] 已确认复杂 inline 段落（超链接 / 图片 / field）差异时跳过改写、保留旧段落原样
- [ ] 已确认 compare 入口放在 `Docx`

## 2. 建议实现顺序

### Step 1 — 先立 API 壳子与最小测试骨架

- [ ] 在 `Docx` 增加 compare 入口：
  - [ ] `compare(Path oldPath, Path newPath)`
  - [ ] `compare(Path oldPath, Path newPath, String author)`
- [ ] 明确默认作者常量（推荐 `nondocx compare`）
- [ ] 补 Javadoc，写清楚第一版范围与限制
- [ ] 先补一个最小编译期测试或空实现测试，锁住 public surface

目标：先把最容易被 public API 锁死的入口、默认作者、异常边界定下来。

### Step 2 — 落地“旧文档深拷贝”为结果基线

- [ ] 在 core 内部补一个小的 compare 支撑实现（可在 `Docx` 内联，或下沉到 `internal/`）
- [ ] 实现“旧文档 → 内存字节 → reopen”为独立结果文档
- [ ] 验证结果文档与旧文档在 compare 前内容一致，但不是同一 POI 实例
- [ ] 补测试：
  - [ ] compare 前无差异时，结果保存 reopen 后与旧文档内容相等

风险观察：

- 不要直接在旧文档实例上改写，否则 compare 调用会污染调用者传入的基线文档对象

### Step 3 — 实现段落支持判定

- [ ] 增加“支持纯文本 run 段落”的判定逻辑
- [ ] 规则至少包括：
  - [ ] `inlineElements()` 全部是 `Run`
  - [ ] 不含 `Hyperlink`
  - [ ] 不含 `Image`
- [ ] 对不支持段落提供清晰的内部语义：保持旧段落原样，不进入重建路径
- [ ] 补测试：
  - [ ] 纯文本段落返回支持
  - [ ] 含超链接段落返回不支持
  - [ ] 含图片段落返回不支持

### Step 4 — 实现段落序列对齐

- [ ] 基于 `Document.paragraphs()` 的纯文本内容实现顺序保持的段落 diff
- [ ] 先只把“完全相等的段落文本”当锚点
- [ ] 产出结构化操作序列，至少区分：
  - [ ] `EQUAL_PARAGRAPH`
  - [ ] `DELETE_PARAGRAPH`
  - [ ] `INSERT_PARAGRAPH`
  - [ ] `MODIFY_PARAGRAPH`
- [ ] 补测试：
  - [ ] 中间插入一段，不导致后续全部错位
  - [ ] 中间删除一段，不导致后续全部错位
  - [ ] 多段连续新增 / 删除

风险观察：

- 若这里退化成“按索引硬对齐”，后续所有段内 diff 都会被级联污染

### Step 5 — 实现 code point 级段内 diff

- [ ] 针对 `MODIFY_PARAGRAPH` 段实现 code point 级文本 diff
- [ ] 输出 segment：
  - [ ] `EQUAL(text)`
  - [ ] `DELETE(text)`
  - [ ] `INSERT(text)`
- [ ] 先用简单 LCS / 动态规划实现
- [ ] 补测试：
  - [ ] 中文字符插入
  - [ ] 中文字符删除
  - [ ] 中文字符替换
  - [ ] 混合英文/数字
  - [ ] 含代理对字符（emoji 或扩展字符）不拆坏

### Step 6 — 实现差异段落重建与修订重放

- [ ] 为支持段落实现“清空 inline 内容 → 重建”的内部流程
- [ ] `EQUAL(text)` → `addRun(text)`
- [ ] `DELETE(text)` → `addRun(text)` 后 `addDeletion(author, run)`
- [ ] `INSERT(text)` → `addInsertion(author, text)`
- [ ] 注意按正确顺序重放，保证阅读顺序与 diff 一致
- [ ] 补测试：
  - [ ] 单段内 insertion
  - [ ] 单段内 deletion
  - [ ] 单段内 replacement（读回应看到 del + ins）
  - [ ] 相邻多段修订的顺序稳定

风险观察：

- `addDeletion` 会迁移 run，必须先拿到要删的 run 再操作，避免后续索引推移带来混乱

### Step 7 — 实现整段删除 / 整段插入

- [ ] 整段删除：
  - [ ] 清空旧段落 inline 内容
  - [ ] 用整段旧文本重建一个 run
  - [ ] 将其标记为 deletion
- [ ] 整段插入：
  - [ ] 基于 body 锚点计算插入位置
  - [ ] 新建段落
  - [ ] 按需要浅复制段落级属性（如 heading / alignment / indent / lineSpacing / list）
  - [ ] 把整段新文本写成 insertion
- [ ] 补测试：
  - [ ] 文首新增段落
  - [ ] 中间新增段落
  - [ ] 文末新增段落
  - [ ] 单段删除

风险观察：

- 插入段落必须基于 `bodyIndex` 而不是简单 `addParagraph()`，否则遇到旧文档中夹表格时位置会错

### Step 8 — 不支持结构的保留策略

- [ ] 当差异落在不支持段落上时，显式走“跳过 compare、保留旧段落原样”
- [ ] 对无差异的不支持段落无需特殊处理
- [ ] 补测试：
  - [ ] 含超链接且有差异的段落被保留旧原样
  - [ ] 含图片且有差异的段落被保留旧原样
  - [ ] 旧文档中的表格完整保留

### Step 9 — 结果验证与文档收尾

- [ ] 保存 compare 结果并 reopen 验证
- [ ] 用 `trackedChanges().list()` 校验修订数量 / 类型 / 作者
- [ ] 更新对外文档（至少 API 速查 / compare Javadoc）
- [ ] 若实现中沉淀出可复用 gotcha，回写 spec

## 3. 建议文件落点

推荐最小改动面：

- [ ] `nondocx-core/src/main/java/com/non/docx/core/Docx.java`
- [ ] `nondocx-core/src/main/java/com/non/docx/core/internal/...` 新增 compare 支撑类
- [ ] `nondocx-core/src/test/java/com/non/docx/core/...` 新增 compare 测试
- [ ] 必要时补 `docs/03-api-reference.md` 或 quick-start / FAQ 中的边界说明

compare 的算法与支撑逻辑推荐下沉 `internal/`，不要把大量实现细节塞进 `Docx`。

## 4. 建议测试矩阵

至少准备以下测试用例：

- [ ] `compare_noDiff_returnsEquivalentOldBaseline`
- [ ] `compare_insertsTextAsTrackedInsertion`
- [ ] `compare_deletesTextAsTrackedDeletion`
- [ ] `compare_replacesTextAsDelPlusIns`
- [ ] `compare_insertParagraphInMiddle_preservesFollowingAlignment`
- [ ] `compare_deleteParagraphInMiddle_preservesFollowingAlignment`
- [ ] `compare_keepsOldTableUntouched`
- [ ] `compare_skipsChangedHyperlinkParagraph`
- [ ] `compare_usesDefaultAuthorWhenNotProvided`
- [ ] `compare_usesExplicitAuthorWhenProvided`

验证层次：

- [ ] 公开 API 调用层断言
- [ ] reopen 后 `trackedChanges().list()` 断言
- [ ] 必要时底层 OOXML 交叉验证

## 5. 建议验证命令

```bash
python3 ./.trellis/scripts/task.py validate 06-30-compare-docx-tracked-changes
mvn -pl nondocx-core test
```

若先做专项验证，可优先跑：

```bash
mvn -pl nondocx-core -Dtest='*Compare*Test,*Tracked*Test,*Paragraph*Test,*Run*Test' test
```

## 6. 风险观察点

- [ ] 不要把 compare 实现成“直接修改旧文档实例”
- [ ] 不要让第一版偷偷扩展到表格 / 页眉页脚 / 样式保真
- [ ] 不要假装复杂 inline 段落已被比较
- [ ] 不要在实现中临时引入 `CompareOptions` 等过早抽象
- [ ] 不要把 run 级样式丢失这个事实藏起来

## 7. Rollback / 回退策略

- 若段落序列对齐实现明显不稳，先退回“精确文本锚点 + 简单连续块”策略，不要升级到复杂相似度算法
- 若 code point 级 diff 在性能上立刻失控，先限制输入规模或换更简单实现，再评估是否需要单独优化任务
- 若“整段插入需复制段落级属性”过于复杂，可先只保留默认段落属性并回写设计，不要在实现中默默乱复制
- 若复杂 inline 跳过策略引发 API 误解，优先补文档与测试命名，不要急着扩 scope

## 8. Ready-to-start 判定

- [ ] compare 入口、默认作者、支持范围已评审通过
- [ ] “旧文档深拷贝 + 段落重建”路线已被接受
- [ ] 不支持结构保留旧原样的边界已接受
- [ ] `design.md` / `implement.md` 与 `prd.md` 一致
- [ ] 用户明确同意进入 `task.py start` / Phase 2
