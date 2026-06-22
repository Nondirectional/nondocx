# Implement — toolkit 质量自检工具

> 子任务 `06-22-quality-check-tools` 的执行计划。design.md 见同目录。

---

## 实现顺序（每步独立可验证，绿了再下一步）

### 阶段 0：core 新建 Row 表格分页 API（先建依赖，再做检查器）

> 先做 R4，因为 check #4 依赖它。复用子任务 1 的 shading API 建立模式（typed accessor + internal/poi 桥接）。

- [ ] 0.1 新建 `internal/poi/RowNodes.java`：`applyHeaderRow(CTRow, boolean)` / `readHeaderRow(CTRow)` / `applyCantSplit(CTRow, boolean)` / `readCantSplit(CTRow)`。typed accessor 走 `CTRow.getTrPr()/addNewTrPr()` → `CTTrPrBase` 的 `tblHeader`/`cantSplit`。首行「内部 API」声明。
- [ ] 0.2 `Row.java`：+`headerRow(boolean)` / +`headerRow()` 读 / +`cantSplit(boolean)` / +`cantSplit()` 读。链式 mutator 返回 this。Javadoc 标注「表头行跨页重复 / 行不跨页拆分」语义。
- [ ] 0.3 扩展 `Row.equals/hashCode` 含 headerRow + cantSplit。
- [ ] 0.4 测试 `RowTablePaginationTest`：
  - `headerRow(true)` → XML 级断言 `<w:tblHeader>` 存在。
  - `cantSplit(true)` → XML 级断言 `<w:cantSplit>` 存在。
  - round-trip：设 → save → open → equals。
  - equals 扩展断言：设 headerRow 后两 Row 不等，都设后相等。
  - 既有 Row 测试无回归（fixture 无 headerRow，null==null）。
- [ ] 0.5 **验证**：`mvn -q -pl nondocx-core test`。

### 阶段 1：QualityCheckTools 骨架 + CheckResult 值对象

- [ ] 1.1 新建 `QualityCheckTools.java`：继承 `ToolkitToolContext`，构造接收 `(sharedSessions, sharedSeq)`。
- [ ] 1.2 内部静态类 `CheckResult(name, passed, message, severity)`，`toReportLine()` → `"❌/⚠️/✅ [name] message"`。
- [ ] 1.3 `checkQuality(docId, checks)` 方法骨架：取 `document(docId)`，docId 不存在返 `docNotFound`，否则跑检查拼报告。
- [ ] 1.4 报告拼装：每检查一行 + 末尾汇总（通过 X/Y | ❌ N errors | ⚠️ N warnings）。
- [ ] 1.5 `DocxToolkit` 门面：+`public final QualityCheckTools qualityCheck` 字段，构造注入，`scanAll` 注册。

### 阶段 2：逐项实现 10 项检查（每项一个方法 + 一个测试）

> 顺序：先易后难，先复用现成 API。

- [ ] 2.1 `checkLineSpacing`（#2）：遍历正文段落收集 `lineSpacing()`，>2 种值 → ⚠️。
- [ ] 2.2 `checkHeadingLevels`（#8）：收集 `heading()` 序列，跳级 → ⚠️。
- [ ] 2.3 `checkCjkIndent`（#7）：CJK 段落无首行缩进 → ⚠️。
- [ ] 2.4 `checkFontFallback`（#6）：罕见字体白名单外 → ⚠️。
- [ ] 2.5 `checkCleanliness`（#14）：占位符/Markdown/草稿正则命中 → ⚠️。
- [ ] 2.6 `checkBlankPages`（#1）：连续空段（≥3）→ ⚠️。若 nondocx 有 break API 加双分页检测，否则降级（design §9）。
- [ ] 2.7 `checkShadingSolid`（#11）：raw 读 `STShd.SOLID` → ❌，引用 `#shading-solid` 锚点。
- [ ] 2.8 `checkTablePagination`（#4）：用阶段 0 的 `Row.headerRow()/cantSplit()` 检查 → ⚠️。
- [ ] 2.9 `checkImageOverflow`（#5）：图片像素宽 ×1440/96 vs 可用宽度 → ❌。
- [ ] 2.10 `checkToc`（#12）：`doc.toc()` 为空或无 Heading → ⚠️。
- [ ] 2.11 每项一个单测：构造违规文档 → 断言报告含对应 ❌/⚠️ + 检查名；构造合规 → 断言该行 ✅。

### 阶段 3：集成测试

- [ ] 3.1 `QualityCheckToolsTest`：
  - 完整合规文档 → 全 ✅，汇总「通过 10/10」。
  - 多问题文档 → 报告含多个 ❌/⚠️，汇总计数正确。
  - `checks` 数组过滤：只跑指定项。
  - docId 不存在 → `docNotFound`。
- [ ] 3.2 `DocxToolkitTest`（若存在）或新建：门面注入第 7 个工具类，`scanAll` 注册 7 个，无回归。

### 阶段 4：收尾

- [ ] 4.1 `docs/07-toolkit.md`：补充 `QualityCheckTools` 章节，工具清单表加一行，列出 10 项检查。
- [ ] 4.2 跑全量：`mvn -q verify`（core + toolkit + examples）。
- [ ] 4.3 spotless：`mvn spotless:apply`。
- [ ] 4.4 POI-free grep：`QualityCheckTools` 公开签名零 POI（`checkQuality` 入参/返回都是 String/List，无 POI 类型）。检查器**内部**用 `cell.raw()` 是允许的（toolkit 不强制 POI-free，只有 core 的公开 API 强制）。

---

## 验证命令速查

```bash
# core 测试（阶段 0 后）
cd nondocx-core && mvn -q test

# toolkit 测试（阶段 1-3）
cd nondocx-toolkit && mvn -q test

# 全量（commit 前）
mvn -q verify

# 格式化
mvn spotless:apply

# 单测聚焦
mvn -pl nondocx-toolkit test -Dtest='QualityCheckToolsTest'
```

---

## 风险点与回滚

| 风险 | 触发 | 缓解 |
|---|---|---|
| `Row.equals` 扩展破坏既有 round-trip 基线 | 阶段 0.4 | 既有 fixture 无 headerRow/cantSplit，扩展后 null==null 不影响；若受影响 revert Row.equals commit。 |
| `checkImageOverflow` 的 DPI 换算不准（image.width() 是 96 DPI 像素） | 阶段 2.9 | `width_twips = px * 1440 / 96 = px * 15`。测试用已知尺寸图片断言。 |
| `checkShadingSolid` 走 raw 读 SOLID，但子任务 1 读侧归并了——需确认 raw 路径能读到原始 SOLID | 阶段 2.7 | `cell.raw().getCTTc().getTcPr().getShd().getVal()` 直接读 OOXML，不经 nondocx 归并，能读到 SOLID。测试验证。 |
| `doc.toc()` API 形态未确认 | 阶段 2.10 | 实现时读 `Document.toc()` 签名；若是只读 `TableOfContents`，`isEmpty()`/`entries()` 判断。 |
| font 白名单太严/太松 | 阶段 2.4 | 初版用最小安全集；测试用「宋体」（白名单，✅）+「方正姚体」（非白名单，⚠️）断言。 |

**回滚单位**：阶段 0（Row API）、阶段 1-3（toolkit）、阶段 4（docs）各自独立 commit。

---

## 实现前 review gate

- [ ] prd.md / design.md / implement.md 三份齐全。
- [ ] PRD Q1（内存）/ Q2（10 项 + 新建 API）已决议。
- [ ] design §2 POI schema 实情已实测（`CTTrPrBase` 的 tblHeader/cantSplit typed 可达）。
- [ ] design §9 的 4 个未决都是实现期可收敛的非阻塞项（无「待验证阻塞」）。
- [ ] 用户已 review 或明确批准进入实现。

> 当前状态：等待用户 review 这三份文档并批准进入 Phase 2 实现。
