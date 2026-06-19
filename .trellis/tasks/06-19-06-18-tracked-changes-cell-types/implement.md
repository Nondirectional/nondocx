# Implement Plan — tracked changes 单元格结构类(cellIns/cellDel/cellMerge)读写

> 本文件记录 cell 子任务进入实现前的有序执行计划、fixture 需求、验证命令与回退点。配套 `prd.md` / `design.md`。
>
> **关键修正**:planning 阶段预估的「需先引入 CT 类型」前提经实测推翻(见 design §1.1)。本计划据此重排:cellIns/cellDel 直接走 typed 访问器,pPrChange 移出,cellMerge 只读。

## 0. Start 前门槛

- [x] `prd.md` 已存在(planning 期已写)
- [x] `design.md` 已写(本子任务)
- [x] `implement.md` 已写(本子任务)
- [ ] `implement.jsonl` / `check.jsonl` 已补真实条目(spec/research 引用)
- [ ] read / accept-text / advanced-types(move+property)三个前置子任务的公共契约已稳定(`TrackedChange` 双委托 + `propertyNode()` 已就绪,cell 直接复用)
- [ ] 用户已评审三件套并同意 start

## 1. 先验证,再写码(research-first 的 cell 版)

cell 是 advanced-types research 点名的「最高结构风险」类。实现前先用一次性探针**确认真实前后状态**,不能凭推断写 accept/reject。

### Step 1 — 探针确认 cellIns/cellDel 的真实 XML 与 accept/reject 结果

- [ ] 写一次性探针测试 `CellTypesProbeTest`(实现期创建,确认后删除,沿用 advanced-types 探针惯例):
  - [ ] 构造一个 1×2 表格,其中一个 cell 带 `cellIns`(经 `tc.getTcPr().addNewCellIns()`)。
  - [ ] 构造一个带 `cellDel` 的同类 fixture。
  - [ ] dump 原始 `document.xml`,确认 `<w:tcPr><w:cellIns .../></w:tcPr>` 形态与 design §1.2 一致。
  - [ ] 手工(在探针里用 XmlCursor)模拟 accept cellIns(移除标记、保留 tc)与 reject cellIns(移除整个 tc),dump 结果 XML,确认:
    - accept cellIns 后:`<w:tc>` 仍在,`<w:tcPr>` 内无 `cellIns`。
    - reject cellIns 后:整个 `<w:tc>` 消失(含其内段落)。
  - [ ] 同样验证 cellDel 的 accept(移除 tc)/ reject(保留 tc、删标记)。
- [ ] 把结论落入 `research/cell-forms.md`(一次性产物,实现完成后保留作为 design 依据,与 advanced-types 的 `ooxml-forms.md` 同位)。

**目标**:在写任何 cell accept/reject 生产代码前,先用真实 XML 把「作用于整个 `<w:tc>`」的语义验证清楚(design §5)。这是 cell 唯一的新结构手术,验证错了后面全错。

### Step 2 — 确认 cellMerge 的 XmlCursor 读取可行

- [ ] 在同一探针里构造带 `cellMerge` 的 fixture(用 XmlCursor 直接插 `<w:cellMerge w:id=.. w:author=../>`,**不**调 `getCellMerge()`——会 NoClassDefFoundError)。
- [ ] 验证 XmlCursor 在 `tcPr` 子里按本地名 `cellMerge` 能读出 author/id/date。
- [ ] 确认 `getCellMerge()` typed 路径确实抛 NoClassDefFoundError(坐实 design §1.1 的 dangling reference 结论)。

## 2. read 侧扩展

### Step 3 — 扩展 walkCell 读 tcPr

- [ ] 在 `TrackedChangeNodes.walkCell` 进入 cell 后、下钻段落前,先读 `tcPr`:
  - [ ] `cellIns`:`tc.isSetTcPr() && tc.getTcPr().isSetCellIns()` → 取 `getCellIns()`(`CTTrackChange`),产出 `CELL_INS` + `CellChangeDetails`。
  - [ ] `cellDel`:同理 → `CELL_DEL`。
  - [ ] location path **不加** `paragraph` segment(design §4.2):停在 `[BODY, TABLE, ROW, CELL]`。
- [ ] cellMerge 走 XmlCursor 在 `tcPr` 子里找本地名 `cellMerge`,读 author/id/date 产出只读 `CELL_MERGE` + `CellChangeDetails(kind=UNCONFIRMED_MERGE)`。

### Step 4 — 新增 details 与枚举

- [ ] 新增 `CellChangeDetails implements ChangeDetails`:
  - 字段 `CellChangeKind kind`(`CELL_INSERTION` / `CELL_DELETION` / `UNCONFIRMED_MERGE`)。
  - 不可变值对象,`equals`/`hashCode` 比 `kind`。
- [ ] 新增 `CellChangeKind` 枚举。
- [ ] `TrackedChangeType` 已预留 `CELL_INS`/`CELL_DEL`;**新增** `CELL_MERGE`(`family = CELL`)。

### Step 5 — cellMerge 的 TrackedChange 构造形态

- [ ] 定 cellMerge 读出的 `TrackedChange` 怎么构造(design §4.3 路推荐荐:纯值、不持可写委托)。
  - 若复用 property 构造函数(持 `CTTrackChange`):cellMerge 节点不是 `CTTrackChange`(类型缺失),需另开构造变体或传 sentinel。
  - 实现期定具体形态,保证 `raw()` 与 `acceptCell`/`rejectCell` 对 cellMerge 都抛 `UnsupportedFeatureException`。

## 3. accept / reject 侧

### Step 6 — TrackedChangeNodes 新增 cell 结构手术

- [ ] 新增 `acceptCell(CTTrackChange node, TrackedChangeType type)`:
  - `type == CELL_INS`:保留 `tc`、删标记。
  - `type == CELL_DEL`:移除整个 `tc`。
- [ ] 新增 `rejectCell(CTTrackChange node, TrackedChangeType type)`:
  - `type == CELL_INS`:移除整个 `tc`。
  - `type == CELL_DEL`:保留 `tc`、删标记。
- [ ] 共用 helper:`toTc(node)` —— 从 `CTTrackChange` 开 cursor,`toParent()`×2 到 `tc`(`CTTc`),返回其 cursor。
- [ ] cellMerge **不**进 accept/reject;门面对 cellMerge 调用直接抛 `UnsupportedFeatureException`。

### Step 7 — 门面 acceptCell / rejectCell

- [ ] `TrackedChanges` 新增 `acceptCell(String id)` / `rejectCell(String id)`(方案 C,同 property 的专用方法)。
- [ ] 命中后校验 `family == CELL`;非 cell 抛 `UnsupportedFeatureException`。
- [ ] cellIns/cellDel 经 `propertyNode()`(复用现成包内接缝)取节点,调 `TrackedChangeNodes.acceptCell/rejectCell`。
- [ ] cellMerge 命中时:抛 `UnsupportedFeatureException`(消息指明 cellMerge accept/reject 未支持,建议 raw())。

### Step 8 — 既有 applySingle / applyProperty 的 family gate

- [ ] 确认 `accept(id)` / `reject(id)` 对 cell 类仍抛 `UnsupportedFeatureException`(family 既非 TEXT 也非 MOVE)——既有 gate 天然挡住,但更新异常消息指向 `acceptCell`/`rejectCell`。
- [ ] `acceptAll` / `rejectAll` / `acceptByAuthor` / `rejectByAuthor` 的 family gate(`TEXT || MOVE`)**不**放宽到 CELL——cell 结构修订不应被批量 accept/reject 误伤(结构删除批量执行风险高)。这是有意的范围控制。

## 4. 边界与文档同步

### Step 9 — spec 更新

- [ ] `poi-bridge.md` 新增 **N16**(design §9):记录 lite schema 的 dangling reference 模式、cellIns/cellDel 的 typed 可达性、cell accept/reject 作用于整个 tc 的语义。
- [ ] 更新 N15 的范围行:cellIns/cellDel/cellMerge 由本子任务补齐;pPrChange/sectPrChange/tblPrChange/trPrChange 仍为已知边界(CT 类型缺失)。
- [ ] `error-handling.md`:cellMerge accept/reject 的 `UnsupportedFeatureException` 消息模板。
- [ ] README:tracked changes 支持矩阵补 cell 类。

### Step 10 — 父任务边界更新

- [ ] 父任务 `06-18-tracked-changes` 的 `prd.md` / `implement.md`:把 pPrChange 等更高层属性类从「本(原)子任务」改为「单独评估的新子任务」,理由(CT 类型缺失)写明。

## 5. 验证命令

```bash
# 子任务校验
python3 ./.trellis/scripts/task.py validate 06-18-tracked-changes-cell-types

# 窄范围测试优先(cell/property/advanced)
mvn -pl nondocx-core -Dtest='*TrackedChange*Test,*Cell*Test,*Property*Test,*Advanced*Test' test

# 全量回归(确认不破坏 move/property/text)
mvn -pl nondocx-core test
```

## 6. fixture / 覆盖面清单

- [ ] `cellIns` 单格 fixture(读回为 CELL_INS + CellChangeDetails)。
- [ ] `cellDel` 单格 fixture。
- [ ] `cellMerge` fixture(XmlCursor 手搓 `<w:cellMerge>`,读回为 CELL_MERGE + UNCONFIRMED_MERGE)。
- [ ] cellIns accept:保留 tc、删标记(round-trip 验证 tc 仍在、cellIns 消失)。
- [ ] cellIns reject:移除整个 tc(验证 tc 消失)。
- [ ] cellDel accept:移除整个 tc。
- [ ] cellDel reject:保留 tc、删标记。
- [ ] cellMerge accept/reject 抛 `UnsupportedFeatureException`。
- [ ] `acceptCell` 命中非 cell(文本类/property)抛 `UnsupportedFeatureException`。
- [ ] cell 修订的 location path 不含 paragraph segment(`[BODY, TABLE, ROW, CELL]`)。
- [ ] cell 修订与单元格内文本类修订(同 tc 内的 ins)共存:两条都读出、location 正确分层。
- [ ] `acceptAll` 不误伤 cell 类(cell 类在批量操作后仍存在)。

## 7. 风险观察点

- [ ] cell accept/reject 若误作用于标记本身(而非整个 tc),会留下「本应删除却仍存在」的单元格——design §5 已定作用于 tc,但实现期必须用探针验证(Step 1)。
- [ ] cellMerge 的 XmlCursor 读取若与 cellIns/cellDel 的 typed 读取混在 `walkCell`,风格分裂——接受(cellMerge 是 dangling reference 的被迫方案)。
- [ ] `toParent()`×2 若文档结构异常(如 cellIns 直接挂在 tc 而非 tcPr,畸形文档),需防御:校验父链是 tcPr→tc,否则跳过该节点(防御式,不抛)。
- [ ] cellMerge 的 TrackedChange 若无委托槽,`equals`/`hashCode` 不能比委托引用(本就不比,只比顶层字段)——确认不破。

## 8. Rollback / 回退策略

- 若 cellMerge 的 XmlCursor 读取在真实文档上不稳定(如命名空间前缀差异),**降级**:cellMerge 读侧也抛 `UnsupportedFeatureException`(完全不读 cellMerge),把 cellMerge 整体移出本子任务。cellIns/cellDel 不受影响。
- 若 cell accept/reject 的「移除整个 tc」在 round-trip 后产生畸形文档(如表格只剩一个 cell、行结构错),**回退**:仅实现「删标记」版 accept/reject(语义不完整但文档合法),并在 N16 诚实标记为「保留 tc、仅删标记」的已知偏差。
- 若 `TrackedChange` 三委托形态(cellMerge 无委托)复杂度失控,**回退**:cellMerge 读侧也移出,本子任务只做 cellIns/cellDel(两个 typed 类)。

## 9. Ready-to-start 判定

- [ ] `prd.md` / `design.md` / `implement.md` 三件套齐全
- [ ] `implement.jsonl` / `check.jsonl` 已补真实条目
- [ ] read / accept-text / advanced-types 前置契约已稳定(`TrackedChange` 双委托 + `propertyNode()` 就绪)
- [ ] 至少基础 fixture 清单已准备(§6)
- [ ] 用户已评审并同意 start
