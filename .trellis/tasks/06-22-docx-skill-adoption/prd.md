# 借鉴 docx skill 升级 nondocx（父任务）

## Goal

把对 `zcode-plugins-official/document-skills/0.1.0/skills/docx` 的探索结论，转化为 nondocx 的两个 P0 落地任务。该 skill 与 nondocx 定位不同（前者是 LLM 提示词 + JS/Python 脚本，后者是 Java POI 封装库），**不能移植代码**，但它在三件事上的「知识沉淀方法学」明显比 nondocx 系统化，而这三件事恰好是 **Agent × docx** 场景（即 `nondocx-toolkit` 的目标用户）最痛的缺口。

父任务**不直接承载实现**，它负责：

- 保存探索结论与共同背景（避免子任务重复研究）
- 维护两个子任务的边界与一致性约束
- 在两个子任务完成后做集成验收

## 探索结论（共同背景）

| 借鉴项 | 落点 | 优先级 | 本父任务是否纳入 |
|---|---|---|---|
| **QualityCheckTools**（postcheck.py 15 项业务规则自检） | `nondocx-toolkit` 新增工具类 | **P0** | ✅ 子任务 1 |
| **WPS/Word 兼容性 spec + core 写路径规避** | `.trellis/spec/backend/` + `nondocx-core` 写路径 | **P0** | ✅ 子任务 2 |
| Scene 场景化预设（report/contract/official） | `nondocx-toolkit` 或新模块 | P1 | ❌ 本次不做 |
| 常见陷阱手册（症状/根因/修复） | `docs/10-common-pitfalls.md` | P2 | ❌ 本次不做 |
| 封面配方 / design-system | — | P3 | ❌ 留 `raw()` 边界 |

**借鉴原则**：只移植**方法学与规则清单**（语言/库无关的 OOXML/版式知识），不移植具体 JS/Python 代码。nondocx 用 Java + POI 重新实现。

## User Value

完成后，nondocx 的 Agent 用户能获得两个原本缺失的能力：

1. **写完就知道有没有问题**：Agent 保存 docx 后，调一个工具就能拿到版式/兼容性自检报告，不必自己肉眼排查或反复打开 Word/WPS 验证。
2. **默认产出双引擎兼容的文档**：库的写路径在默认值层面主动规避 WPS/Word 已知陷阱，而不是把兼容性知识全压在用户脑子里。

## 设计原则（跨子任务一致性约束）

参照 `06-22-comments` 父任务的 R3，作对称声明：

### R1. 借鉴的是方法学，不是代码

- [ ] 两个子任务均**不得**引入对 docx skill 的 Python/JS 文件运行时依赖。
- [ ] 规则清单可以从 skill 的 markdown 里提炼，但实现必须是 Java + POI。
- [ ] 在 PRD/design 中标注每条规则的 skill 出处（如「来自 postcheck.py check #N」），便于回溯。

### R2. 与 nondocx 现有契约不冲突

- [ ] 公共 API 继续 **POI-free**（quality-check 的检查器可内部用 POI 读 docx，但对外只暴露字符串报告 / nondocx 类型）。
- [ ] 异常继续遵守 `error-handling.md`；检查器发现的问题以**报告条目**返回，不抛异常中断。
- [ ] core 写路径的兼容性规避**只改默认值/补缺失**，不覆盖用户显式设置（与 `Section.ensureHeader` 的「只补不覆盖」精神一致，poi-bridge.md N8）。

### R3. 两个子任务的边界

- [ ] **quality-check-tools**：面向**已保存的 docx 文件**做事后检查，只读不改。
- [ ] **renderer-compatibility**：面向**写路径的默认值**做事前规避，检查器可消费它沉淀的规则。
- [ ] 两者**互补不重叠**：spec 是「应该怎么写」的知识库，工具是「实际写得对不对」的验证器。renderer-compatibility 沉淀的规则应能被 quality-check-tools 引用为检查项来源。

### R4. 交付顺序

- [ ] 默认顺序：renderer-compatibility（先沉淀知识）→ quality-check-tools（消费知识做检查器）。
- [ ] 但两者可并行起步，quality-check-tools 的规则清单可先从 postcheck.py 直译，后续再回填到 spec。

## Task Map（父 / 子任务拆分）

| # | 子任务 | 范围 | 主要产出 |
|---|---|---|---|
| 1 | `06-22-renderer-compatibility` | WPS/Word 兼容性 spec + core 写路径规避 | `spec/backend/renderer-compatibility.md` + core 默认值修正 |
| 2 | `06-22-quality-check-tools` | toolkit 质量自检工具 | `QualityCheckTools.java` + `check_quality` 工具方法 |

## Acceptance Criteria（父任务集成验收）

- [ ] AC1 两个子任务全部交付，各自 AC 全绿。
- [ ] AC2 `renderer-compatibility.md` 的规则与 `QualityCheckTools` 的检查项**可交叉引用**（spec 里每条规则有稳定锚点，工具报告能指向对应规则）。
- [ ] AC3 现有功能无回归（既有 core/toolkit 测试全绿）。
- [ ] AC4 在 `nondocx-examples` 新增一个示例：故意构造一个有兼容性问题的 docx → 用 `check_quality` 检出 → 修复 → 再检通过，演示完整闭环。

## 父任务保留的未决问题

- [ ] **Q1**：`QualityCheckTools` 的检查是跑在**磁盘上的 .docx 文件**（解包读 XML），还是跑在**内存中的 Document 对象**（走 POI API）？前者更接近 postcheck.py 语义、能查 XML 级问题，后者更快但受 POI API 屏蔽。倾向「磁盘文件」——子任务 2 design 收敛。
- [ ] **Q2**：core 写路径的兼容性规避，哪些做成**强制默认**（如 ShadingType 强制 CLEAR），哪些做成**可配置**（如表格宽度默认 PERCENTAGE 但允许 DXA）？倾向「安全默认 + 显式覆盖」——子任务 1 design 收敛。
- [ ] **Q3**：spec 里 WPS-only 的问题（如 `pgNumType` 空元素）是否要在 core 层主动清理？这超出「写路径默认值」范畴，可能涉及读路径。子任务 1 design 收敛。
