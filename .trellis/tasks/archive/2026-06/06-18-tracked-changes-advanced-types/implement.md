# Implement Plan — tracked changes 高级修订类型

> 本文件记录 advanced-types 子任务进入实现前的研究顺序、fixture 需求、拆分回退点与验证建议。

## 1. Start 前门槛

- [ ] `prd.md` / `design.md` 已评审通过
- [ ] read / accept-text 两个前置子任务的公共契约已稳定
- [ ] 已确认当前 advanced-types 仍保持为一个 research-first 子任务
- [ ] 已确认若复杂度失控，可以回 planning 继续拆分
- [ ] 已补齐 `implement.jsonl` / `check.jsonl`

## 2. 先研究，再实现

### Step 1 — 先补 research / fixture 基础

- [ ] 准备 move 样本
- [ ] 准备属性类样本
- [ ] 准备 `cellIns` / `cellDel` 样本
- [ ] 若有必要，在 `research/` 下为三类各写一份观察文档

目标：在写任何高级 accept/reject 代码前，先把真实 OOXML 形态看清。

### Step 2 — 先定 move

- [ ] 验证 `MOVE_FROM` / `MOVE_TO` 是否按设计作为两个 `TrackedChange`
- [ ] 验证 `MoveChangeDetails` 的配对表达方式
- [ ] 明确单条 id 命中任一端时的联动操作语义
- [ ] 补最小测试：
  - [ ] 成对 move
  - [ ] 配对端缺失 / 损坏

目标：先把高级类型里最容易牵动“稳定 id + 单条操作”的类型打稳。

### Step 3 — 再定属性类修订

- [ ] 明确 `PropertyChangeDetails` 的目标属性树表达
- [ ] 通过 fixture 判断 accept 时是整树替换还是字段级 merge
- [ ] 验证 `location` 与 `details()` 的职责分工没有被打穿
- [ ] 补最小测试：
  - [ ] `rPrChange`
  - [ ] `pPrChange`
  - [ ] 至少一个更高层属性类（如 `sectPrChange` 或 `tblPrChange`）

### Step 4 — 最后定 `cellIns` / `cellDel`

- [ ] 明确结构节点的保留 / 删除 / 恢复语义
- [ ] 验证它们不能按文本类思路处理
- [ ] 补最小测试：
  - [ ] `cellIns`
  - [ ] `cellDel`
  - [ ] 与表格内容路径 / location 的联动断言

### Step 5 — 回看是否需要拆分

- [ ] 若 move 的实现矩阵明显独立且复杂，回 planning 拆分
- [ ] 若属性类规则表爆炸，回 planning 拆分
- [ ] 若 `cellIns` / `cellDel` 需要完全独立的结构恢复逻辑，回 planning 拆分

## 3. 建议验证命令

```bash
python3 ./.trellis/scripts/task.py validate 06-18-tracked-changes-advanced-types
mvn -pl nondocx-core test
```

若已有专项测试，可优先跑窄范围：

```bash
mvn -pl nondocx-core -Dtest='*TrackedChange*Test,*Move*Test,*Property*Test,*Cell*Test' test
```

## 4. 建议 fixture / 覆盖面

至少准备：

- [ ] 成对 move fixture
- [ ] 缺损 move fixture
- [ ] `rPrChange` fixture
- [ ] `pPrChange` fixture
- [ ] 至少一个更高层属性类 fixture（section / table / row / cell property 之一）
- [ ] `cellIns` fixture
- [ ] `cellDel` fixture

## 5. 风险观察点

- [ ] move 若不显式处理配对，单条 id 语义会失真
- [ ] 属性类若不先看真实 XML，最容易把 `details()` 设计成空壳
- [ ] `cellIns` / `cellDel` 最容易被误当成文本类 `ins` / `del`
- [ ] 若 read 子任务尚未完整支持高级类型枚举，本子任务需要一并补齐，而不能假设它已存在

## 6. Rollback / 回退策略

- 若某类高级类型已经明显独立成单独工程量，停止实现，回 planning 拆分。
- 若统一 `TrackedChange` 模型在高级类型上被证明不够承载，先回父任务设计，不要在实现中偷偷发明第二套模型。
- 若配对、属性树、结构恢复三类问题互相影响到无法并行推进，按 move -> property -> cell 顺序收缩，不要三类同时硬推。

## 7. Ready-to-start 判定

- [ ] `prd.md` / `design.md` / `implement.md` 三件套齐全
- [ ] read / accept-text 前置契约已稳定
- [ ] 至少基础 fixture 清单已准备
- [ ] `implement.jsonl` / `check.jsonl` 已补真实条目
