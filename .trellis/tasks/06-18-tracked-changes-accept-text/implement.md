# Implement Plan — tracked changes accept/reject 文本类

> 本文件记录该子任务进入实现前的执行顺序、测试矩阵与风险观察点。

## 1. Start 前门槛

- [ ] `prd.md` / `design.md` 已评审通过
- [ ] `06-18-tracked-changes-read` 的稳定 id、统一门面与列表模型已可复用
- [ ] 已确认当前子任务仍只覆盖 `ins` / `del` 文本类修订
- [ ] 已确认高级类型 accept / reject 留给 `advanced-types`
- [ ] 已补齐 `implement.jsonl` / `check.jsonl`

## 2. 建议实现顺序

### Step 1 — 先接统一门面写入口

- [ ] 在 `TrackedChanges` 上接入：
  - [ ] `acceptAll()`
  - [ ] `rejectAll()`
  - [ ] `acceptByAuthor(String)`
  - [ ] `rejectByAuthor(String)`
  - [ ] `accept(String id)`
  - [ ] `reject(String id)`
- [ ] 明确参数校验与异常消息

### Step 2 — 先实现 `ins`

- [ ] 打通 `accept(ins)`
- [ ] 打通 `reject(ins)`
- [ ] 补最小 fixture / 回归测试：
  - [ ] 正文段落中的 `ins`
  - [ ] 表格单元格内的 `ins`
  - [ ] byAuthor 对 `ins` 的筛选
  - [ ] 单条 id 对 `ins` 的命中

目标：先拿下语义最直观的一类文本修订。

### Step 3 — 再实现 `del`

- [ ] 打通 `accept(del)`
- [ ] 打通 `reject(del)`
- [ ] 明确 `w:delText` 恢复为普通正文文本的路径
- [ ] 补最小 fixture / 回归测试：
  - [ ] 正文段落中的 `del`
  - [ ] 表格单元格内的 `del`
  - [ ] byAuthor 对 `del` 的筛选
  - [ ] 单条 id 对 `del` 的命中

目标：让文本类 accept / reject 双向都成立。

### Step 4 — 打 all / byAuthor / 单条矩阵

- [ ] `acceptAll()` / `rejectAll()`
- [ ] `acceptByAuthor()` / `rejectByAuthor()`
- [ ] `accept(id)` / `reject(id)`
- [ ] 补矩阵测试：
  - [ ] 同文档同时含 `ins` 与 `del`
  - [ ] 同文档含多个作者
  - [ ] 单条操作不影响其他文本类修订
  - [ ] miss id 抛 `NoSuchElementException`
  - [ ] 非法 author / id 抛 `IllegalArgumentException`

### Step 5 — 验证边界不外溢

- [ ] 若读取模型中已能看到高级类型修订，确认本子任务不误处理它们
- [ ] 对当前任务范围外的命中行为按设计验证（例如 `UnsupportedFeatureException`）
- [ ] 确认未无意引入开关写入、便利筛选或创作侧 API

## 3. 建议验证命令

```bash
python3 ./.trellis/scripts/task.py validate 06-18-tracked-changes-accept-text
mvn -pl nondocx-core test
```

如已补充专项测试类，可优先跑窄范围：

```bash
mvn -pl nondocx-core -Dtest='*TrackedChange*Test,*Revision*Test,*Accept*Test,*Reject*Test' test
```

## 4. 建议 fixture / 覆盖面

至少准备：

- [ ] 正文段落中的 `ins`
- [ ] 正文段落中的 `del`
- [ ] 表格单元格中的 `ins`
- [ ] 表格单元格中的 `del`
- [ ] 同文档多作者
- [ ] 同文档多条文本类修订混排
- [ ] 单条 id miss

若 read 子任务已能列出高级类型：

- [ ] 一个非文本类修订 fixture，用于验证本任务不越界处理

## 5. 风险观察点

- [ ] 不要把 `TrackedChange` 值对象误当作 live handle
- [ ] 不要让 `accept/reject` 写逻辑泄漏到 read 子任务公共模型中
- [ ] 不要误处理 `cellIns` / `cellDel` 这类结构型修订
- [ ] `del` 的 reject 路径要特别留意文本恢复语义
- [ ] byAuthor 逻辑要与字符串匹配契约保持一致

## 6. Rollback / 回退策略

- 若 `del` 的恢复语义无法在当前模型下正确表达，先回 `design.md` 调整，再继续实现。
- 若高级类型读取结果与文本类边界交缠严重，回到父任务重新评估 `accept-text` 与 `advanced-types` 的拆分。
- 若单条 id 语义依赖 read 子任务尚未稳定的实现细节，先暂停 start，回到前置任务完善只读模型。

## 7. Ready-to-start 判定

- [ ] `prd.md` / `design.md` / `implement.md` 三件套齐全
- [ ] read 子任务公共契约已稳定
- [ ] 文本类范围边界已评审通过
- [ ] `implement.jsonl` / `check.jsonl` 已补真实条目
