# Implement Plan — core 修订(tracked changes)封装（父任务）

> 本文件记录父任务级执行顺序与开始实现前的门槛；不是子任务实现细节清单。

## 1. 前置门槛（开始任何子任务前）

- [x] 父任务 `prd.md` / `design.md` 已复审并达成一致
- [x] `TrackedChange` 非文本类建模方向已拍板（方向 B：统一基类 + 类型化 details）
- [x] `TrackedChange` 公共表面形态已拍板（B2：统一外壳 + `details()` 判别联合）
- [x] `TrackedChange.type` 粒度已拍板（type=细粒度 kind，另提供 family）
- [x] 已确认当前父任务保持为 parent task，不直接承接全部实现
- [x] 子任务树已创建且边界清晰
- [x] `implement.jsonl` / `check.jsonl` 已补齐真实 spec 清单

## 2. 建议执行顺序

### Step 1 — 完成父任务 planning 收口

- [x] 已决定高级修订类型当前先不进一步拆分，保留 `advanced-types` 子任务并研究优先
- [ ] 若后续 research 证明某一类高级修订复杂度独立且过高，再回到 planning 拆分
- [ ] 必要时为高级类型补 research 文档骨架

### Step 2 — 完善各子任务 planning 工件

- [x] `06-18-tracked-changes-read`：已补齐 PRD / Design / Implement，并收敛统一门面、稳定 id、location 与只读边界
- [x] `06-18-tracked-changes-accept-text`：已补齐 PRD / Design / Implement，并收敛文本类 accept / reject 粒度与失败语义
- [x] `06-18-tracked-changes-authoring`：已补齐 PRD / Design / Implement，并收敛显式 tracked 写 API 与返回值语义
- [x] `06-18-tracked-changes-advanced-types`：已补齐 PRD / Design / Implement，并收敛 research-first 边界与统一模型接入策略
- [x] `06-18-tracked-changes-docs-spec`：已补齐 PRD / Design / Implement，并收敛文档 / spec 收尾范围与更新顺序

### Step 3 — 优先启动低风险子任务

建议顺序：

1. `06-18-tracked-changes-read`
2. `06-18-tracked-changes-accept-text`
3. `06-18-tracked-changes-authoring`
4. `06-18-tracked-changes-advanced-types`
5. `06-18-tracked-changes-docs-spec`

原因：

- 只读消费侧能最早验证 OOXML 枚举路径是否可行
- 文本类 accept/reject 能先建立最小 CT 手术经验
- 创作侧再接入时，更容易复用前两步的模型与测试夹具
- 高级类型留到最后，避免它反向绑死前面的 API

### Step 4 — 高级类型研究与再拆分判断

在开始 `06-18-tracked-changes-advanced-types` 前必须完成：

- [ ] move 真实 OOXML 配对 / 范围研究
- [ ] `*PrChange` 的 accept / reject 语义表格
- [ ] `cellIns` / `cellDel` 的结构语义说明
- [ ] 判断该子任务是否要继续拆分

### Step 5 — 最终集成与收尾

- [ ] 校验所有子任务的公共 API 命名与行为一致
- [ ] 校验旧 API 无回归
- [ ] 更新 spec、README、Javadoc、示例
- [ ] 由父任务执行最终集成验收

## 3. 建议验证命令

> 这些命令是父任务层面的建议；具体子任务可补充更细的测试命令。

```bash
python3 ./.trellis/scripts/task.py validate 06-18-tracked-changes
python3 ./.trellis/scripts/task.py list --mine
mvn test
```

如需只跑核心模块，可在子任务内补充更窄命令，例如：

```bash
mvn -pl nondocx-core test
```

## 4. 风险文件 / 观察点

开始任何子任务实现前，优先关注：

- `nondocx-core/src/main/java/com/non/docx/core/api/**`
- `nondocx-core/src/main/java/com/non/docx/core/internal/poi/**`
- `nondocx-core/src/test/java/**`
- `.trellis/spec/backend/poi-bridge.md`
- `.trellis/spec/backend/error-handling.md`
- `.trellis/spec/guides/teaching-approach.md`

重点观察：

- 是否意外破坏现有 holding-wrapper 语义
- 是否把 CT 细节泄漏到公共 API
- 是否让旧 getter 获得新的“修订解释”副作用
- 是否为高级类型过早锁死错误的数据模型

## 5. Rollback / 回退策略

- 若某子任务实现过程中发现其边界判断错误，优先**回到 planning，重写该子任务文档**，而不是带着歧义继续写代码。
- 若高级类型研究显示复杂度远超预期，优先**继续拆子任务**，不要倒逼前面子任务扩大抽象负担。
- 若发现公共模型已被错误字段名绑死，应先回退设计，再推进实现；不要靠注释硬解释坏模型。

## 6. Ready-to-start 判定

只有以下条件同时满足，父任务才算完成 planning：

- [x] 父任务三个文档（`prd.md` / `design.md` / `implement.md`）齐全
- [x] `TrackedChange` 非文本类建模问题已明确
- [x] 子任务树稳定
- [x] 至少第一个子任务（`tracked-changes-read`）的 planning 三件套已可独立评审
- [x] 相关 spec 清单已写入 JSONL
