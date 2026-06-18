# Implement Plan — tracked changes 只读消费侧

> 本文件记录该子任务从 planning 进入实现前的执行顺序、验证方式与风险观察点。

## 1. Start 前门槛

- [ ] `prd.md` / `design.md` 已评审通过
- [ ] 已确认当前子任务仍保持最小集合范围：`enabled()` / `list()` / `get(id)`
- [ ] 已确认开关写入、便利筛选、accept/reject 不属于本子任务
- [ ] 已确认 `TrackedChange` / `TrackedChangeLocation` 的公共契约不再继续变动
- [ ] 已补齐实现 / 检查所需 spec 清单（`implement.jsonl` / `check.jsonl`）

**已收敛的关键决策（来自 planning 评审，实现时必须遵守）**：

- **包装形态**：`TrackedChanges` 门面持有 `final XWPFDocument`；`TrackedChange` 持有 `final CTRunTrackChange`（或父接口 `CTTrackChange`），`raw()` 返回该 CT 节点。走 holding-wrapper，**不是**不可变解析值（详见 design §4.2）。
- **CT 类型事实**：文本类修订 `<w:ins>` / `<w:del>` / `<w:moveFrom>` / `<w:moveTo>` 在精简 schema 下统一由 `CTRunTrackChange` 承载（POI 5.2.5，`org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRunTrackChange`，扩展 `CTTrackChange`）。`CTTrackChange` 提供 `author` / `date` / `id`；`CTRunTrackChange.getRList()` 给被修订的 `CTR`。
- **稳定 id 边界**：**进程内稳定**。不承诺跨 `save()`/`reopen` 稳定（详见 design §4.5）。后续 accept/reject 子任务默认在同会话操作。

## 2. 建议实现顺序

### Step 1 — 先落公共模型壳子

- [ ] 新增 `TrackedChanges` 门面类型
- [ ] 新增 `TrackedChange` 统一外壳
- [ ] 新增 `TrackedChangeFamily` / `TrackedChangeType`（命名以实际设计为准）
- [ ] 新增 `TrackedChangeLocation`
- [ ] 新增 location segment kind / segment 值对象（通用 `kind + index`）
- [ ] 新增最小 `details()` 体系的公共父类型与只读子类型骨架

目标：先把用户可见的静态模型与方法签名定下来，再接 POI / CT 读取路径。

### Step 2 — 打开关读取路径

- [ ] 从 `XWPFDocument` / settings 路径读取 `<w:trackChanges/>`
- [ ] 接入 `TrackedChanges.enabled()`
- [ ] 补最小测试：
  - [ ] 开关存在时返回 true
  - [ ] 开关缺失时返回 false

目标：先拿到最容易验证的一条只读能力，确保门面对象已接到 `Document.trackedChanges()`。

### Step 3 — 打位置模型与枚举骨架

- [ ] 明确正文顺序扫描策略
- [ ] 在扫描过程中生成 `TrackedChangeLocation` path
- [ ] 第一版先确保 `body / paragraph / table / row / cell / run` 六类 segment 能稳定产出
- [ ] 补位置断言测试：
  - [ ] 正文段落内修订位置
  - [ ] 表格单元格内修订位置
  - [ ] run 级定位

目标：先证明“枚举正确、顺序正确、定位正确”中的定位部分成立。

### Step 4 — 打稳定 id 路径

- [ ] 实现混合版稳定 id 生成策略（`type + location + 原始 w:id` 组合，集中在 `internal/poi/`）
- [ ] 保持 id 对外不透明，不把字符串格式写入公共契约
- [ ] 在 `TrackedChanges` 门面维护进程内 `id → CT 节点` 映射，支撑 `get(id)` 反查（该映射只缓存 id→节点引用，不缓存修订内容，不违反 holding-wrapper / 无字段快照精神）
- [ ] 补测试：
  - [ ] 同一文档同一修订在**同会话多次** `list()` 中 id 稳定
  - [ ] `get(id)` 命中返回对应修订
  - [ ] `get(id)` miss 抛 `NoSuchElementException`
  - [ ] （可选负向测试）`save()` + `reopen` 后 id 不保证相等——记录为已知边界，不当作 bug

目标：先把后续 accept/reject 会依赖的「同会话稳定引用能力」打稳。

### Step 5 — 打 `list()` 读取能力

- [ ] 实现按文档顺序枚举修订
- [ ] 将底层节点映射为 `TrackedChange + details()`
- [ ] 第一版至少确保文本类修订列表行为与顶层契约跑通
- [ ] 若读取过程中已遇到高级类型节点：
  - [ ] 能否先以顶层 `type/family/location` + 对应 details 骨架承接，需按实现时复杂度判断
  - [ ] 若发现高级类型读取本身就超出当前任务负荷，立即回 planning，不要在实现中硬塞

目标：让 `TrackedChanges.list()` 可以稳定返回结果，并与父任务设计一致。

### Step 6 — 收尾与回看

- [ ] 检查 `Document.trackedChanges()` 命名与 Javadoc
- [ ] 检查 `TrackedChange` / `location` / `details()` 的中文文档说明
- [ ] 检查异常消息是否中文且带上下文
- [ ] 回看是否无意引入了便利筛选或写入能力

## 3. 建议验证命令

```bash
python3 ./.trellis/scripts/task.py validate 06-18-tracked-changes-read
mvn -pl nondocx-core test
```

若已补充定向测试类，可优先跑更窄范围，例如：

```bash
mvn -pl nondocx-core -Dtest='*TrackedChange*Test,*TrackedChanges*Test' test
```

## 4. 建议测试夹具 / 覆盖面

至少准备下列文档场景：

- [ ] 仅含 `<w:trackChanges/>` 开关、无修订内容
- [ ] 正文段落中的文本类修订（`ins` / `del`）
- [ ] 表格单元格中的文本类修订
- [ ] 同一文档多个修订，验证文档顺序
- [ ] `get(id)` miss 场景

如果在 read 阶段就会遇到高级类型节点，至少准备：

- [ ] 一个属性类修订 fixture（哪怕只用于观测 location/details 分工）
- [ ] 一个 move 或 cell 类 fixture（用于判断是否需要回 planning 拆分）

## 5. 风险观察点

- [ ] 不要把 `TrackedChange.id()` 的内部生成细节暴露成公共格式承诺
- [ ] 不要让 `location` 承担属性语义；属性目标仍在 `details()`
- [ ] 不要修改旧 getter 的默认含义来“顺手暴露修订”
- [ ] 不要在 read 子任务里偷偷实现写入或 accept/reject
- [ ] 若高级类型读取让当前子任务显著膨胀，应回 planning 而不是继续堆实现

## 6. Rollback / 回退策略

- 若 `TrackedChange` / `location` / `details()` 的公共模型在实现中被证明不够用，先回到 `design.md` 改设计，再动代码。
- 若高级类型读取在 read 子任务中暴露出独立复杂度，回到父任务 / 子任务 planning 重新判断边界。
- 若稳定 id 方案需要公共格式承诺才能成立，先停下来复审该决策，不要直接把格式写死进 API 文档。

## 7. Ready-to-start 判定

只有以下条件同时满足，才建议对该子任务执行 `task.py start`：

- [ ] `prd.md` / `design.md` / `implement.md` 三件套齐全
- [ ] 统一门面、稳定 id、`location`、`details()` 分工已评审通过
- [ ] `implement.jsonl` / `check.jsonl` 已补真实条目
- [ ] 开发者认可当前仍是“最小集合”实现范围
