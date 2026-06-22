# Implement — WPS/Word 兼容性 spec + core 规避

> 子任务 `06-22-renderer-compatibility` 的执行计划。design.md 见同目录。

---

## 实现顺序（每步独立可验证，绿了再下一步）

### 阶段 0：spec 文档（零代码风险，先沉淀知识）

- [ ] 0.1 新建 `.trellis/spec/backend/renderer-compatibility.md`，按 design §5 写 ≥9 条规则四段式 + 稳定锚点 + 作用域。
- [ ] 0.2 更新 `.trellis/spec/backend/index.md`：Guidelines Index 表加一行；Pre-Development Checklist 加「兼容性默认值检查」项；Scope Boundaries 标注「兼容性已系统化」。
- [ ] 0.3 **验证**：spec 内部链接自洽；锚点列表整理好（交付给子任务 2）。

### 阶段 1：Shading 值对象 + Cell/Paragraph shading API

> 先做 shading，因为它最能体现「兼容性默认」价值（强制 CLEAR），且复用既有 XmlCursor 模式。

- [ ] 1.1 复测 `CTPPr` 在 lite jar 的 stripped 状态（design §9 未决）——`javap -cp <lite> CTPPr | grep -i shd`。若 stripped，确认走 XmlCursor。
- [ ] 1.2 新建 `api/style/ShadingPattern.java`（枚举，剔除 SOLID）+ `api/style/Shading.java`（不可变值对象 + equals/hashCode）。
- [ ] 1.3 新建 `internal/poi/ShadingNodes.java`：
  - `apply(CTTc tc, Shading)` / `apply(CTP p, Shading)` —— XmlCursor 插 `<w:shd>`。
  - `read(CTTc)` / `read(CTP)` —— XmlCursor 读 `<w:shd>` 解析回 Shading。
  - `remove(CTTc)` / `remove(CTP)` —— 删。
  - 首行 `Internal API — subject to change without notice.`
- [ ] 1.4 `Cell.java`：+`shading(String)` / +`shading(Shading)` / +`shading()` 读 / +`removeShading()`。链式返回 this。
- [ ] 1.5 `Paragraph.java`：对称加同四个方法。
- [ ] 1.6 **扩展 `Cell.equals/hashCode`** 包含 shading（content-equal 契约）。
- [ ] 1.7 测试 `ShadingTest` / `CellShadingTest` / `ParagraphShadingTest`：
  - round-trip：设 shading → save → open → equals。
  - POI cross-reference：nondocx 读 == POI 原生读。
  - **兼容性默认断言**：`cell.shading("F1F5F9")` 产出 pattern=CLEAR（XML 检查 `w:val="clear"`）。
  - 既有 round-trip 测试若因 equals 扩展而失败，更新基线（design §9 风险点）。
- [ ] 1.8 **验证**：`cd nondocx-core && mvn -q test`。

### 阶段 2：Cell verticalAlign（POI 高层直用，最简）

- [ ] 2.1 新建 `api/style/VerticalAlign.java`（枚举 TOP/CENTER/BOTTOM）。
- [ ] 2.2 `Cell.java`：+`verticalAlign(VerticalAlign)` / +`verticalAlign()` 读。映射到 POI `XWPFVertAlign`。
- [ ] 2.3 测试 `CellVerticalAlignTest`：round-trip + cross-reference。
- [ ] 2.4 **验证**：`mvn -q test`。

### 阶段 3：Table 列宽

- [ ] 3.1 复测 `CTTblGrid` / `CTGridCol` 在 lite jar 是否 typed 可达（design §9）。决定列宽走 typed 还是 XmlCursor。
- [ ] 3.2 新建 `internal/poi/TableWidthNodes.java`：列宽脏活。
- [ ] 3.3 `Table.java`：+`columnPercents(int[])` / +`columnWidths(int[])` / +`columnWidths()` 读。
- [ ] 3.4 测试 `TableColumnWidthTest`：
  - 百分比路径产出 `tblW type=PCT` + `gridCol` PCT（XML 断言）。
  - DXA 路径产出 DXA。
  - 后调覆盖前者（活对象语义）。
  - round-trip + cross-reference。
- [ ] 3.5 **验证**：`mvn -q test`。

### 阶段 4：Section.cleanEmptyPageNumbering

- [ ] 4.1 `Section.java`：+`cleanEmptyPageNumbering()` —— 扫 `CTSectPr`，空 pgNumType 则 `unsetPgNumType()`，返回 boolean。
- [ ] 4.2 测试 `SectionCleanPageNumberingTest`：
  - 构造空 pgNumType（用 PRD Q3 探针同款 POI 调用）→ 调方法 → 断言清理 + 返回 true。
  - 有 w:start 的 pgNumType → 调方法 → 断言保留 + 返回 false。
- [ ] 4.3 **验证**：`mvn -q test`。

### 阶段 5：收尾

- [ ] 5.1 跑全量：`mvn -q verify`（compile + test + spotless:check）。
- [ ] 5.2 spotless 格式化：`mvn spotless:apply`。
- [ ] 5.3 Javadoc 检查：所有新 public 方法有中文 Javadoc + `@throws`；`raw()` 类的 Javadoc 警告语不涉及本任务。
- [ ] 5.4 grep 验证 POI-free：`grep -rn "org.apache.poi" nondocx-core/src/main/java/com/non/docx/core/api/style nondocx-core/src/main/java/com/non/docx/core/api/table nondocx-core/src/main/java/com/non/docx/core/api/text nondocx-core/src/main/java/com/non/docx/core/api/section` —— 应只在 `raw()` 返回类型出现（本任务无新增 raw，应零命中）。
- [ ] 5.5 整理 spec 锚点清单，交付给子任务 2 `QualityCheckTools`（父任务 AC2 / 本任务 AC7）。

---

## 验证命令速查

```bash
# 单元测试（最快反馈）
cd nondocx-core && mvn -q test

# 全量验证（commit 前必跑）
mvn -q verify

# 格式化
mvn spotless:apply

# POI CT 可达性复测（design §9 未决项）
javap -cp ~/.m2/repository/org/apache/poi/poi-ooxml-lite/5.2.5/poi-ooxml-lite-5.2.5.jar \
  org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr | grep -i shd

# POI-free 验证（本任务应零命中）
grep -rn "org.apache.poi" nondocx-core/src/main/java/com/non/docx/core/api/{style,table,text,section}/
```

---

## 风险点与回滚

| 风险 | 触发 | 缓解 |
|---|---|---|
| `Cell.equals` 扩展含 shading 破坏既有 round-trip 基线 | 阶段 1.6/1.7 | 既有测试 fixture 若不含 shading，扩展 equals 不影响（shading 读回 null，null==null）。若 fixture 含 shading 之前未断言，需补断言。回滚：revert Cell.equals 那个 commit。 |
| `CTPPr` / `CTTblGrid` 实测发现也被 stripped 且 XmlCursor 路径复杂 | 阶段 1.1 / 3.1 | design 已预案走 XmlCursor；若 XmlCursor 也受阻，对应 API 降级为「抛 UnsupportedFeatureException + raw-only」，不阻塞其它 API。 |
| shading XmlCursor 插入顺序违反 OOXML schema（`<w:shd>` 在 CTTcPr 内的位置） | 阶段 1.3 | 参考 ECMA-376 `CTTcPr` 序列：`cnfStyle` → `tcW` → `gridSpan` → `hMerge` → `vMerge` → `tcBorders` → `shd` → ...。XmlCursor 插入需找正确锚点；测试用 Word 打开验证不报错。 |
| spec 规则与 core 实现不一致（spec 写了但 core 没做） | 阶段 0 vs 1-4 | spec 每条标作用域；user-guidance 类明确「core 不做」，不算不一致。 |

**回滚单位**：每个阶段对应独立 git commit（spec / shading / vAlign / 列宽 / pgNumType），任一阶段失败 revert 该 commit 不影响其它。

---

## 实现前 review gate

在 `task.py start` 前，确认：

- [ ] prd.md / design.md / implement.md 三份齐全（complex 任务硬要求）。
- [ ] PRD 所有 Open Questions 已决议（Q2-Q6 全部 ✅）。
- [ ] design.md 的 POI schema 实情（§2）已实测，无「待验证」阻塞项（剩余未决都是实现期可收敛的非阻塞项）。
- [ ] 用户已 review 或明确批准进入实现。

> 当前状态：等待用户 review 这三份文档并批准 `task.py start` 进入 Phase 2 实现。
