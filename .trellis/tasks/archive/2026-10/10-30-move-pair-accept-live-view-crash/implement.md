# Implement：执行计划

> 上下文顺序：implement.jsonl 所列 spec/research → prd.md → design.md → 本文件。

## 前置事实（实现者必读）

- POI 5.2.5 源码已解包核对：`XWPFParagraph.runs/iruns` 为 **protected**（XWPFParagraph.java:49-50），
  `buildRunsInOrderFromXml` 为 private（:113）——重建逻辑照抄其语义（design §3.1 已列全分支）。
- 崩溃点 = `XWPFParagraph.getText()` 对每个缓存 run 先 `getDelTextArray()`；断连检测复用同一路径。
- 手术点文件：`nondocx-core/src/main/java/io/github/nondirectional/docx/core/internal/poi/TrackedChangeNodes.java`
  （moveRuns :819-907 / removeNode :211-218 / unwrapRunsAndRemove :226-250 / restoreDelTextToT :258-272）。
- toolkit 返回串：`nondocx-toolkit/.../TrackedChangeQueryTools.java`（applyRevisionsByIds :503-580，
  失败行 :550-557；@ToolDef description :216-220）。
- 受影响段 XWPFParagraph 获取：优先手术链下传（`Paragraph.moveRunsFrom` :768-798 有 from/to
  raw()）；accept/reject 路径从 XmlCursor 上溯 CTP 后在 `XWPFDocument.getParagraphs()`
  （含表格 cell 段落）比对 `getCTP()` 定位。

## 步骤

1. [ ] **桥接类**：新建 `org.apache.poi.xwpf.usermodel.XWPFParagraphCacheBridge`
   （nondocx-core，同包访问 protected）——`hasDisconnectedRuns` / `refreshIfDisconnected`，
   重建遍历照 design §3.1 分支清单；javadoc 中文，记明「POI 升级需对照新版
   buildRunsInOrderFromXml 复核」与 §3.3-5 已知边缘效应。
2. [ ] **手术点接线**：TrackedChangeNodes 四处手术后调用 `refreshIfDisconnected`（design §3.2
   表）；受影响段引用获取按前置事实第 4 条；仅 internal/poi 内部接线，无公开 API 变化。
3. [ ] **core 锁定测试** `TrackedMovePairLiveReadTest`（design §4.1）+ **AC3 用例**
   （open 已有修订→accept→live 读不抛，TrackedAdvancedTypesTest 裸构造手法）。
4. [ ] **toolkit**：`applyRevisionsByIds` 成功集合 + 失败行追加成对说明文案（不改计数）；
   @ToolDef description 补句；core `applyMove` javadoc 同步；新增 `TrackedMovePairApplyTest`
   （design §4.2）。
5. [ ] **门禁**：`mvn -q spotless:apply` → 全量 `mvn test`（core/toolkit/examples/demo）全绿；
   `TrackedAuthoringTest:137` 等 live 断言必须零改动通过（R5）。
6. [ ] **spec/docs**：poi-bridge.md:510「accept 后必须 save+reopen」须知升级为已修复（保留
   历史注记）；N17/N14 相关行按实况修订；TODO.md #009 状态更新（主会话完成）。

## 验证命令

```bash
mvn -q spotless:apply
mvn test
# 定点回归（失败时先跑）
mvn -q -pl nondocx-core test -Dtest='Tracked*Test'
mvn -q -pl nondocx-toolkit test -Dtest='*Tracked*Test,TrackedReplaceFormatWorkaroundTest'
```

## 回滚点

- 全部改动单分支单提交系列；桥接类与接线为独立逻辑单元，revert 即回滚，落盘 XML 零变化。

## 冲突上报

- 若实现中发现某现有测试断言与修复正面冲突（AC4 风险）：**不静默改断言**，停下在任务
  文档记录冲突，选影响最小保守方案（如收窄触发点）后继续。
