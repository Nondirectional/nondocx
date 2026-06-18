# Implement Plan — tracked changes 创作侧显式 API

> 本文件记录该子任务进入实现前的执行顺序、测试建议与风险观察点。

## 1. Start 前门槛

- [ ] `prd.md` / `design.md` 已评审通过
- [ ] `06-18-tracked-changes-read` 的统一只读模型已可读回文本类修订
- [ ] 已确认当前子任务只覆盖文本类 authoring，不碰高级类型创建
- [ ] 已确认显式 tracked API 与开关语义正交
- [ ] 已补齐 `implement.jsonl` / `check.jsonl`

## 2. 建议实现顺序

### Step 1 — 先立公共 API 壳子

- [ ] 在 `Paragraph` / `Run` 上接入最小 authoring 入口
- [ ] 明确返回值与 Javadoc
- [ ] 明确 `author` / `text` / `newText` 的参数校验

目标：先把最容易被 public API 锁死的命名与返回值定下来。

### Step 2 — 实现 tracked insertion

- [ ] 打通 `Paragraph.addInsertion(String author, String text)`
- [ ] 写入底层修订元数据：author / date / 原始 `w:id`
- [ ] 返回新插入 run 的 wrapper
- [ ] 补最小测试：
  - [ ] 正文段落中的 insertion
  - [ ] 表格单元格段落中的 insertion（若当前内容 API 可直达）
  - [ ] round-trip 后 `TrackedChanges.list()` 能读回

### Step 3 — 实现 tracked deletion

- [ ] 打通 `Paragraph.addDeletion(String author, Run target)`
- [ ] 明确“现有 run 被迁入 deletion 语义路径”后的 wrapper 处理策略
- [ ] 保持不返回原 `Run`
- [ ] 补最小测试：
  - [ ] 既有 run 被标记为 deletion
  - [ ] round-trip 后 `TrackedChanges.list()` 能读回
  - [ ] 调用后不暴露误导性的旧 run 句柄

### Step 4 — 实现 tracked replacement

- [ ] 打通 `Run.replaceTracked(String author, String newText)`
- [ ] 生成 deletion + insertion 组合
- [ ] 返回新 insertion run
- [ ] 若按设计复制源 run 样式：
  - [ ] 明确复制范围
  - [ ] 补回归测试
- [ ] 补最小测试：
  - [ ] replacement 可被 read 子任务读回为对应文本类修订
  - [ ] 返回的新 run 可继续链式修改

### Step 5 — 验证正交边界

- [ ] 确认普通 `addRun()` / `text()` 等 API 行为无回归
- [ ] 确认 tracked API 不依赖 `<w:trackChanges/>` 开关开启
- [ ] 确认本子任务未无意引入 accept/reject 或高级类型创作

## 3. 建议验证命令

```bash
python3 ./.trellis/scripts/task.py validate 06-18-tracked-changes-authoring
mvn -pl nondocx-core test
```

若已有专项测试，可优先跑：

```bash
mvn -pl nondocx-core -Dtest='*Tracked*Author*Test,*TrackedChange*Test,*Paragraph*Test,*Run*Test' test
```

## 4. 建议 fixture / 覆盖面

至少准备：

- [ ] 新建 insertion 的最小文档
- [ ] 将现有 run 标记为 deletion 的文档
- [ ] replacement 文档
- [ ] 与 read 子任务联动的 round-trip fixture
- [ ] 作者 / 日期 / 原始 `w:id` 写入断言

## 5. 风险观察点

- [ ] 不要把底层原始 `w:id` 与 read 稳定 id 混成一个概念
- [ ] 不要返回已经失去普通 live run 语义的旧 wrapper
- [ ] replacement 的样式复制策略若不稳，不要在实现中偷偷降级而不回写设计
- [ ] 不要让 tracked API 影响普通写 API 语义

## 6. Rollback / 回退策略

- 若 deletion 后旧 run wrapper 语义无法解释清楚，优先回 `design.md` 调整返回值，再写代码。
- 若 replacement 样式复制过于复杂，回到设计层明确收窄契约，不要在实现中悄悄弱化。
- 若 read 子任务尚不能稳定读回 authoring 结果，先暂停该子任务 start，回到只读模型补基础。

## 7. Ready-to-start 判定

- [ ] `prd.md` / `design.md` / `implement.md` 三件套齐全
- [ ] authoring 返回值与 wrapper 语义已评审通过
- [ ] read 子任务能承接文本类写出结果
- [ ] `implement.jsonl` / `check.jsonl` 已补真实条目
