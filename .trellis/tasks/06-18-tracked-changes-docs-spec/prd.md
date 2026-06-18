# tracked changes 文档与 spec 收尾

## Goal

在 tracked changes 相关实现逐步落地后，统一完成**文档、异常示例、README 与 Trellis spec** 的收尾工作，使项目对“tracked changes 是否已被封装、封装到了什么程度”给出一致、诚实、可执行的说明。

## User Value

完成后：

- 开发者阅读 README / Javadoc / spec 时，不会再看到与真实能力冲突的“修订仍是 raw-only”描述
- 后续 AI / 人类协作者可以通过 spec 正确理解 tracked changes 的封装边界
- `UnsupportedFeatureException` 等示例文案与真实 out-of-scope 范围保持一致

## Confirmed Facts

- 当前 README、`UnsupportedFeatureException`、`poi-bridge.md`、`error-handling.md` 仍存在将 tracked changes 视为 out-of-scope / raw-only 的描述。
- 父任务已决定：tracked changes 将不再整体停留在 raw-only 范围内，而是以分子任务方式逐步进入正式封装。
- 本子任务是收尾任务，天然依赖前面几个实现子任务的真实落地结果。

## Requirements

### R1. 文档范围覆盖

- [ ] 更新 README 中关于 raw() / out-of-scope 的能力描述。
- [ ] 更新 `UnsupportedFeatureException` 的示例文案与说明。
- [ ] 更新 `.trellis/spec/backend/poi-bridge.md` 中关于 tracked changes 的 out-of-scope 描述。
- [ ] 更新 `.trellis/spec/backend/error-handling.md` 中关于 unsupported feature 示例的描述。
- [ ] 如实现过程中形成新的 tracked changes gotcha，应写回相关 spec。

### R2. 说明必须诚实反映真实进度

- [ ] 不得把“部分支持”写成“完整支持”。
- [ ] 必须明确哪些 tracked changes 能力已封装，哪些仍留待后续子任务或 raw()。
- [ ] 若高级类型尚未完成，文档应保留真实边界，而不是提前宣布 done。

### R3. 与实现子任务保持对齐

- [ ] 文档说明必须以已合并 / 已确认的实现结果为准，而不是以早期规划为准。
- [ ] 若某个子任务实现过程中调整了契约或边界，本子任务负责把这些变化同步回文档与 spec。

### R4. 面向未来维护

- [ ] 文档应帮助后续协作者理解 tracked changes 的统一门面、稳定 id、location、details 等核心模型。
- [ ] spec 应记录实现过程中发现的 POI / OOXML gotcha，避免未来重复踩坑。

## Acceptance Criteria

- [ ] AC1 README 不再把 tracked changes 整体描述为 raw-only。
- [ ] AC2 `UnsupportedFeatureException` 示例文案不再使用“修订更改未被封装”这一过期示例。
- [ ] AC3 `poi-bridge.md` / `error-handling.md` 对 tracked changes 的描述与真实实现边界一致。
- [ ] AC4 若实现中出现新的 tracked changes gotcha，已被写回 spec 或相关说明文档。
- [ ] AC5 文档不会把未完成的高级类型能力误写成已完整支持。

## Out of Scope

- tracked changes 核心实现本身
- 新增第二套用户教程体系
- 在 README / spec 中承诺尚未交付的能力

## Open Questions

当前子任务级开放问题已按推荐方案收敛完成：

- 先不为 tracked changes 单独新增大型 README / examples 章节
- 优先更新已有能力总览、异常示例、spec 与必要的 API 示例
## Notes

- 本子任务建议补 `design.md` 与 `implement.md` 后再进入 `task.py start`。
- 文档收尾的“正确性”不取决于写得多华丽，而取决于它是否与真实代码边界一致。