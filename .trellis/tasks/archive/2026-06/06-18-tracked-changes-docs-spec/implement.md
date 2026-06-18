# Implement Plan — tracked changes 文档与 spec 收尾

> 本文件记录 docs/spec 收尾任务的更新顺序、依赖检查点与 finish 前核对清单。

## 1. Start 前门槛

- [ ] `prd.md` / `design.md` 已评审通过
- [ ] 前置实现子任务的真实边界已足够稳定，可作为文档 source of truth
- [ ] 已补齐 `implement.jsonl` / `check.jsonl`

## 2. 建议更新顺序

### Step 1 — 先核对真实边界

- [ ] 核对 read 子任务最终已落地到什么程度
- [ ] 核对 accept-text 子任务最终已落地到什么程度
- [ ] 核对 authoring 子任务最终已落地到什么程度
- [ ] 核对 advanced-types 子任务最终已落地到什么程度

目标：先确认“代码真的支持到哪”，再改文档。

### Step 2 — 更新最明显过期的说明

- [ ] README 中“tracked changes 整体 raw-only”相关描述
- [ ] `UnsupportedFeatureException` 示例文案
- [ ] `poi-bridge.md` 中 tracked changes 整体 out-of-scope 描述
- [ ] `error-handling.md` 中 unsupported feature 示例描述

### Step 3 — 回写 gotcha

- [ ] 整理文本类实现阶段发现的 POI / OOXML gotcha
- [ ] 整理高级类型实现阶段发现的 gotcha
- [ ] 判断这些信息写回 `poi-bridge.md` 还是 `error-handling.md`

### Step 4 — 回看是否需要额外示例

- [ ] 若已有 tracked changes API 已足够稳定，补最小 README / Javadoc 示例
- [ ] 当前推荐仍是不新增大章节，只更新已有能力总览与必要示例

### Step 5 — finish 前一致性核对

- [ ] 文档不再把 tracked changes 整体视为 raw-only
- [ ] 文档没有把尚未完成的高级类型写成 done
- [ ] spec 与异常示例与代码真实边界一致

## 3. 建议验证命令

```bash
python3 ./.trellis/scripts/task.py validate 06-18-tracked-changes-docs-spec
mvn -pl nondocx-core test
```

## 4. 风险观察点

- [ ] 不要把规划稿里的愿景直接抄进 README / spec
- [ ] 不要为了“好看”把 raw() 仍必要的能力也写成已封装
- [ ] 不要忽略 `UnsupportedFeatureException` 这类用户第一眼会看到的示例文案
- [ ] 不要漏掉实现阶段新发现的 gotcha

## 5. Rollback / 回退策略

- 若实现边界在临近 finish 时仍不稳定，宁可暂缓文档定稿，也不要先写死错误说明。
- 若高级类型仍明显未定，不写“大而全”说明，先保留诚实边界。

## 6. Ready-to-start 判定

- [ ] `prd.md` / `design.md` / `implement.md` 三件套齐全
- [ ] 前置实现子任务已有足够稳定的真实边界可供引用
- [ ] `implement.jsonl` / `check.jsonl` 已补真实条目
