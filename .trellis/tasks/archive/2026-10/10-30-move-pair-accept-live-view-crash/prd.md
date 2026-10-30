# move 对 accept 后 live view 崩溃修复（XmlValueDisconnectedException）

## Goal

成对修订（move_to/move_from）在 live 会话中创作并 accept 后，任何 view/读不再抛
`XmlValueDisconnectedException`；根因在 core 层修复（POI 段落包装缓存悬空），复现步骤
转为锁定测试，并补充 `apply_tracked_changes` 对成对修订的返回语义说明（不改行为）。

来源：TODO #009、docs/11 §6.14、full 面复跑 runId `20261030-220341-436`（fill-template
尾部 5 个读工具连败，模型因此失去复核能力）。

## 背景（调研已实证，见 research/）

- 悬空引用在 **move 创作时**产生（`TrackedChangeNodes.moveRuns` 的 `runCur.moveXml(fromCur)`，
  TrackedChangeNodes.java:869-874），不在 accept：源段直接子 CTR 迁入 `<w:moveFrom>` 的瞬间，
  POI `XWPFParagraph`（文档 open 时固化于 `XWPFDocument.paragraphs`）的 `runs`/`iruns` 缓存里
  对应 `XWPFRun` 持有的旧 CTR 句柄即断连。accept 只是顺带让修订消失。
- 暴露点：POI 5.2.5 `XWPFParagraph.getText()` 对每个缓存 run 先调 `getDelTextArray()`，
  断连处必抛。所有经 POI 缓存包装的读路径（`text()`/`runs()`/toolkit 全部 view/read 工具）同炸。
- 落盘走 XML 树序列化不经 POI 缓存，故完好；save+reopen 后一切正常——纯 live 会话缺陷。
- 同族边界：open 时已存在的修订（ins/del/moveFrom 内 run 在 POI 缓存）经 accept/reject 的
  removeNode/unwrap 后同样断连——spec poi-bridge.md:510 记录为「测试须知」（accept 后必须
  save+reopen），本任务将其升级为 core 层修复。

## Requirements

1. **R1（崩溃修复，core 修根因）**：move 对在 live 会话创作后、以及按 id accept（含
   `apply_text_revisions scope=ALL` 路径）后，live 会话对受影响段落的任何读
   （`text()` / `runs()` / toolkit view/read 工具）不抛 `XmlValueDisconnectedException`，
   读值与 reopen 口径一致或保持既有已锁定语义（见 design 语义保证节）。
2. **R2（同族覆盖）**：open 时已存在的文本类修订（ins/del/moveFrom/moveTo 包装）accept/reject
   后，live 读同样不抛——即 poi-bridge.md:510 家族边界一并修复。
3. **R3（锁定测试）**：TODO #009 复现步骤转为锁定测试：
   - core：两段文档 → `moveRunsFrom` → live 读源段（现状崩，修复后不抛且值正确）→
     accept move_to → live 读全部段 → 第二端 accept 报 `NoSuchElementException`（语义锁定，
     不改行为）→ save+reopen 终态断言（源段文本已移走、目标段含被移文本、零残留修订）。
   - toolkit：`move_run_tracked` → `apply_tracked_changes(ACCEPT, TEXT_OR_MOVE, [move_to id,
     move_from id])` → 断言 PARTIAL_FAILURE + 失败 1 条（行为不变）→ `view_outline` /
     `view_text` 正常返回（现状崩）。
4. **R4（返回语义说明，不改行为）**：按 id 批量应用 move 对时，「应用一半即整对完成、
   配对端随后报找不到属预期」在返回串或工具描述中说明；ok/fail 计数、PARTIAL_FAILURE
   触发条件、异常类型均不变。
5. **R5（语义不回退）**：已锁定的 live/reopen 语义差异（§6.13：XML 手术新建的 ins 在 live
   `text()` 不可见、save+reopen 后可见）必须保持——`TrackedAuthoringTest` 现有 live 断言
   （如 :137 `assertThat(paragraph.text()).isEqualTo("结论：。")`）不得翻车；eval 基线不动。

## Constraints

- core 修根因，view/toolkit 读侧不加兜底 try-catch（避免掩盖家族问题）。
- 项目自建包装层无缓存，悬空全在 POI 层；修复落点在 `internal/poi`（项目指定的 POI 脏活位置）。
- 「零 POI 泄漏于公开 API」：桥接/重建逻辑不得把 XMLBeans/POI 类型暴露到 `api/` 公开签名。
- 全中文对外（javadoc/异常消息/测试注释）。
- 质量门禁：`mvn -q spotless:apply` 后全量 `mvn test` 全绿（core/toolkit/examples/demo）。

## Acceptance Criteria

- [ ] AC1：core 锁定测试——live 会话 `moveRunsFrom` 后读源段、accept(move_to) 后读全部段，
      均不抛异常且值符合 design 语义保证节；第二端 `NoSuchElementException` 行为保持；
      save+reopen 终态正确（文本移走、零残留修订）。
- [ ] AC2：toolkit 锁定测试——TODO #009 复现步骤走通，`apply_tracked_changes` 返回
      PARTIAL_FAILURE（失败 1 条，行为不变），返回串含成对修订说明（R4）；随后
      `view_outline`/`view_text` 正常返回不抛。
- [ ] AC3：open 时已存在修订的文档 accept/reject 后 live 读不抛（R2，poi-bridge.md:510
      家族边界正面锁定测试）。
- [ ] AC4：`TrackedAuthoringTest` 等现有测试零改动通过（R5 语义不回退；若发现某断言与修复
      正面冲突，回到 design 评估并显式记录，不允许静默改断言）。
- [ ] AC5：spec 更新——poi-bridge.md:510「accept/reject 后必须 save+reopen」须知升级为
      core 已修复；N17/N14 相关条目按实况修订。
- [ ] AC6：全量 `mvn test` 全绿；提交信息中文、scope 前缀。

## Out of Scope

- 不改 accept/reject 的 XML 语义与 id/配对规则（findMoveCounterpart 等）。
- 不改「XML 手术新建的 ins 在 live text() 不可见」的既有语义（不借机消除 live/reopen 差异）。
- 不动 insert_tracked_run 无位置参数等工具面缺口（属 TODO 其它条目）。
- pPrChange 等更高层属性类修订（CT 类型缺失，raw-only）不扩面。
