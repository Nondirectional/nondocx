# 页眉页脚能力 spec 更新与集成验收

## Goal

在前三个子任务（field / variants / content）完成后，**把实现中确认的知识沉淀进 spec**，并对整个父任务做**集成验收**，确保三条能力线协同工作、无回归。

本子任务**依赖** 前三者完成。

## User Value

完成后，`.trellis/spec/` 准确反映 nondocx 的页眉页脚能力边界，未来维护者与 AI 助手读 spec 就能知道：

- 变体的 POI 坑（`createHeader(FIRST/EVEN)` 不自动写开关）
- 图片 / 表格在页眉里的复用条件（`XWPFHeader` 实现 `IBody`）
- 域的实际值由渲染引擎计算的边界
- 跨引擎兼容性观察（titlePg 已知坑、evenAndOddHeaders 是否有新坑）

## Confirmed Facts

- spec 现有相关条目：
  - `poi-bridge.md` N5（读写分离）、N8（创建时补齐页面设置）—— 已记录默认变体。
  - `renderer-compatibility.md` `#title-page-suppress`（titlePg 在 WPS 抑制不可靠）—— 已记录。
- 待新增的 spec 条目（由本子任务落档）：
  - **poi-bridge N19**：首页 / 偶数页变体的 POI 坑（`createHeader(FIRST)` 不写 `titlePg`、`createHeader(EVEN)` 不写 settings 开关）。
  - **poi-bridge N20**（若 content 子任务路径 A 成立）：`XWPFHeader` 实现 `IBody`，`Paragraph.addImage` / `createTable` 天然可复用。
  - **renderer-compatibility**（条件性）：若 variants/content 实现中发现 evenAndOddHeaders 或页眉图片有新跨引擎坑，新增锚点。
- 父任务已确定：docs-spec 必须最后做。

## Requirements

### R1. spec 更新

- [ ] 在 `poi-bridge.md` 新增 **N19**：首页 / 偶数页变体的 POI 坑，四段式（OOXML 结构 / POI 的坑 / nondocx 规避 / 与 Rule 1 的关系）。
- [ ] 在 `poi-bridge.md` 新增 **N20**（若 content 路径 A 成立）：`XWPFHeader` 实现 `IBody` 的复用发现。
- [ ] 在 `renderer-compatibility.md` 评估是否新增 evenAndOddHeaders / 页眉图片的兼容性锚点（若实现中未发现新坑，明确记录「已验证无坑」）。
- [ ] 若新增 renderer-compatibility 锚点，同步更新「锚点速查表」。
- [ ] `directory-structure.md` 若有 api 包结构变化（如 `HeaderFooterVariant` 新文件），同步更新。

### R2. 集成验收

- [ ] 三条能力线协同测试：在首页变体页眉里放表格 + 图片 + 页码域，round-trip 全部存活。
- [ ] 全量 `mvn -q verify` 绿（compile + test + spotless）。
- [ ] `RoundTripTest` 与既有 `HeaderFooterTest` 无回归。
- [ ] 现有公开 API 的 Javadoc 全部更新（新方法的「三层递进」教学注释齐全）。
- [ ] 父任务 `prd.md` 的 Acceptance Criteria（AC1-AC3）逐项确认。

### R3. 回归确认

- [ ] 默认（奇数页）页眉页脚 API 行为无变化（向后兼容）。
- [ ] `Document.equals` / `Section.equals` 扩展不破坏既有 round-trip 断言。
- [ ] 无 POI 类型泄漏到公开签名（`raw()` 除外）。

## Acceptance Criteria

- [ ] AC1 `poi-bridge.md` N19 落档，四段式完整，含「与 Rule 1 的关系」声明。
- [ ] AC2 `renderer-compatibility.md` 关于 evenAndOddHeaders / 页眉图片的结论明确记录（新增锚点 或 明确「已验证无坑」）。
- [ ] AC3 三能力线集成测试通过（首页变体页眉内表格 + 图片 + 页码域 round-trip）。
- [ ] AC4 全量 `mvn -q verify` 绿。
- [ ] AC5 父任务 `prd.md` 的 AC1-AC3 全部勾选。
- [ ] AC6 公开 API Javadoc 教学注释齐全（三层递进）。

## Out of Scope

- **重新实现已完成的子任务** —— 本子任务只做 spec 沉淀与集成验收，不改实现（除非集成测试发现 bug，此时回到对应子任务修）。
- **新增未在父任务规划的能力** —— 若集成验收发现遗漏，回到父任务 planning 重新拆分，不在本子任务里临时加塞。

## Open Questions

- 是否需要在 README 或用户文档里加一个「页眉页脚能力概览」章节？（取决于项目 README 现状，由本子任务实现时决定）

## Notes

- 本子任务的实现结论直接决定 spec 的最终形态，因此必须在 field / variants / content 三者全部完成后才能 start。
- 推荐执行顺序：先跑集成测试确认无回归 → 再写 spec（spec 写的是已验证的事实）。
