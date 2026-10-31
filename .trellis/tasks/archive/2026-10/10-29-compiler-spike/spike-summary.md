# 编译器 spike 总结（P1 / 任务 B）

> 2026-10-29 · 模型 `qwen3.8-27b`（内网 vLLM，温度 0，无 Skill）· 数据见 `docs/11` §6.3

## A/B 结果（create 组 5 条任务）

| 指标 | full 面（58 工具） | compiler 面（5 工具） |
|---|---|---|
| 通过率 | 1/5（20%） | **5/5（100%）** |
| 平均工具调用 | 23.0 | **5.6** |
| 无效调用 | 多 | **0** |
| 总耗时 | 306s | 105s |
| runId | `target/eval/20261029-231126-331` | `target/eval/20261029-231703-151` |

典型 compiler 序列：`current_document → create_document → validate_document → review_intent`（4 次，
创作仅 1 次）。P1 假设「调用 10+ → 2~3」实测为创作调用 1~2 次、总调用 4.1× 下降。

## full 面失败归因

1. 无列表创建入口（`insert_paragraph` 只有 heading_level/alignment）×2；
2. 无正文首行缩进工具 ×1；
3. 建表 + 标题多步组合超载（27 次调用未完成）×1。

## 实测修正（写进了 spec/doc）

- POI `setFontFamily(name)` **四槽同写**；编译器 raw() 覆写 eastAsia 的价值是**分离**
  （西文 TNR + 中文宋体），不是「补缺失槽」。
- 编译器默认写表格分页控制（headerRow + cantSplit），`table-pagination` 恒过。

## 复现

```bash
mvn -q -pl nondocx-core,nondocx-toolkit install -DskipTests   # 先装新 toolkit
mvn -q -pl nondocx-examples -Dexec.mainClass=...eval.EvalMain exec:java \
  -Dexec.args="--surface compiler --group create"
mvn -q -pl nondocx-examples -Dexec.mainClass=...eval.EvalMain exec:java \
  -Dexec.args="--surface full --group create --timeout-seconds 240 --max-iterations 24"
```

注意：`-pl nondocx-examples`（不带 `-am`）会用本地仓库的 toolkit jar；改过 toolkit 后必须先
install，否则 `DocxToolkit.compiler` 字段在运行时不存在（本次踩过）。

## 遗留

- compiler 面尚未接入 demo（等用户决定）；
- `edit_document` patch 引擎、`fill_template`、`render_preview` 属 P2；
- eval token 计数仍为 0（chain 0.11.0 VLLM streaming 无 usage，已知限制）。
