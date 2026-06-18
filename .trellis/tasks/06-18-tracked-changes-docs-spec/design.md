# Design — tracked changes 文档与 spec 收尾

> 配套 `prd.md`。本文记录 tracked changes 文档与 spec 收尾任务的技术设计：哪些文件是 source of truth、按什么原则更新、以及如何避免“文档先于实现跑太远”。

## 1. 设计目标

本子任务不是写一篇“tracked changes 宣传稿”，而是完成**文档与真实能力的重新对齐**。

设计重点是：

1. 明确哪些文件必须更新
2. 明确更新时以什么为准
3. 明确哪些内容不应该过度承诺

## 2. 更新原则

### 2.1 真实实现优先

文档与 spec 的 source of truth 是：

- 已落地的代码
- 已确认的子任务设计边界
- 已验证的测试行为

而不是早期 PRD 愿景。

### 2.2 部分支持就诚实写部分支持

tracked changes 的收尾必须遵守：

- 文本类已支持，就写文本类已支持
- 高级类型若仍在进行中，就写清“仍在进行中 / 仍需 raw() / 仍由后续子任务补齐”
- 不用“tracked changes 已支持”这种过于笼统、可能误导的表述

### 2.3 不再把 tracked changes 整体视为 raw-only

这是本子任务最关键的纠偏点：

- tracked changes 将不再整体归入“未封装 / raw-only”列表
- 但仍允许未覆盖的具体子能力保留在真实 out-of-scope 范围内

## 3. 文件更新矩阵

### 3.1 README

README 负责：

- 更新 raw() / out-of-scope 总览
- 在能力描述中反映 tracked changes 已进入正式封装
- 不要求一开始就单独开辟大章节；优先更新已有能力总览与 API 示例即可

### 3.2 `UnsupportedFeatureException`

该文件负责：

- 替换“修订更改未被封装”这一过期示例
- 保留“超出深度封装范围时引导用户使用 raw()”的核心语义

推荐改法：

- 示例改成其他尚未封装或仍部分 raw-only 的能力（如形状 / 文本框 / 某类高级对象）
- 避免继续把 tracked changes 作为整体验收失败的例子

### 3.3 `poi-bridge.md`

该文件负责：

- 去掉“tracked changes 整体仍属 out-of-scope”的旧表述
- 记录实现过程中真正踩到的 POI / OOXML gotcha
- 说明 tracked changes 仍沿用统一门面、details、location、internal/poi 下沉等桥接原则

### 3.4 `error-handling.md`

该文件负责：

- 更新 unsupported feature 的示例集合
- 确保异常文案示例与真实能力边界一致

## 4. 不新增独立教程章节（当前推荐）

当前推荐：

- **先不为 tracked changes 单独新增大型 README / examples 章节**
- 先更新已有能力总览、异常示例、spec 与必要的 API 示例

原因：

1. 当前 tracked changes 仍是分子任务渐进落地
2. 若过早写完整教程，很容易在高级类型尚未完成时误导用户
3. 先保证“说明正确”比“说明很长”更重要

## 5. spec 回写策略

### 5.1 回写 gotcha

只要 tracked changes 实现过程中出现非显而易见的 POI / OOXML 行为，就应回写到 spec。

优先回写位置：

- `poi-bridge.md`
- 必要时 `error-handling.md`

### 5.2 回写时机

- 文本类子任务完成后，可先回写文本类 gotcha
- 高级类型完成后，再补高级类型 gotcha
- docs-spec 子任务负责统一审视这些回写是否已经完成、是否需要整理措辞

## 6. 边界保护

- 不承诺尚未完成的高级类型能力
- 不为了“看起来完整”而删除所有 raw() 提示
- 不在 README / spec 中发明与代码不一致的 API 名称或能力矩阵

## 7. 当前仍待收敛的问题

当前子任务级设计问题已按推荐方案收敛完成；后续只需在实现收尾时按真实代码边界核对文档，不再额外扩写大章节。