# Implement:compiler 面页眉写 op 与读视图页眉元数据行

> 前置:design.md 已拍板全部分叉。执行顺序即下表;每步验证命令见各节。

## 执行清单

### 阶段一:实现(派 trellis-implement)

- [x] 1. toolkit PatchEngine(nondocx-toolkit/.../compiler/PatchEngine.java)
  - 类 javadoc op 集清单(:46-47)加 `header.set_text`;op 集计数注释(:29)11 种→12 种
  - planOp switch 加 `case "header.set_text"`(:198 附近,不调 claimAnchor)
  - 未知 op 报错文案(:203-210)同步
  - 新增 `planHeaderSetText(int index, Map<String,Object> op)`:text 必填非空校验(PatchException,零写入);执行体 `doc.ensureHeader().addParagraph()` 后逐 span `addRun(span.text())` + `applySpanFormat(run, span, profile)`(复用 MarkdownParser.parseInline + tracked.insert 模式);`changed.add("header")`;planned 描述 `header.set_text "<text>"`
- [x] 2. toolkit CompilerTools(CompilerTools.java)
  - edit_document @ToolDef 描述(:253-271)加 header.set_text 一句(不占 anchor、text 支持行内标记、追加一段)
  - ops 参数示例 JSON(:282-287)加 `{"op":"header.set_text","text":"..."}` 形态
  - NestedParamCapability(:288-303):确认 `ops.text` 已存在(replace_text 用),若已覆盖则不加
- [x] 3. toolkit MarkdownView(MarkdownView.java)
  - render() 尾部页脚行之前加 `<!-- 页眉: … -->`(存在且非空才输出、多行 " / "、读侧用 doc.header() 只读)
  - javadoc(:20-22)改写(页眉进视图)
- [x] 4. demo AgentBridge(AgentBridge.java:296 附近)+ examples EvalRunner(EvalRunner.java:85 附近)提示词同口径加一句
- [x] 5. 测试(写读同批,D2):
  - PatchEngineTest:text 缺失/空白拒绝且零写入;写入后页眉含文本、changed 含 "header";**bold** 行内标记落 run;正文锚点不动 + 与 replace_text 同批共存;追加语义(两次调用页眉两段)
  - CompilerToolsTest:edit_document 带 header op → read_document 读回 `<!-- 页眉: … -->`;无页眉时无该行
  - CompilerPipelineTest:页眉元数据行 round-trip 锁(注释被剥掉,不产生脏块)

### 阶段二:eval(派 trellis-implement 或主会话)

- [x] 6. EvalTasks.java:新增 `header-write-text`(baseline 尾部第 25 条;seed 无页眉;prompt 不提工具、含加粗要求;check 三件套:页眉存在/页眉含关键词+加粗 run/正文不误改;reopen 口径;capabilityGap=true);类 javadoc 计数顺手修正
- [x] 7. EvalTasksTest:hasSize(24)→25 + 方法名 hasTwentyFourUniqueTasks→hasTwentyFiveUniqueTasks;capabilityGap containsExactly 清单尾部加 header-write-text

### 阶段三:质量门禁与提交时序(主会话执行)

> A12(拍板):eval 报告记录 gitCommit(EvalMain.java:84/209-226),惯例「先提交代码再复跑」使 runId@commit 可复现(§6.14 先例 @8f3cff4);feat body 附 runId(381276f 形态)+ docs 提交引用同一 runId(65ba23a 形态)→ 时序 = feat 提交 → 复跑 → amend feat 附 runId → docs 提交。

- [x] 8. `mvn -q spotless:apply`(或项目等价命令)后全量 `mvn test` 全绿
- [x] 9. feat 提交(中文、scope 前缀,按实际 scope 如 `feat(toolkit,demo,examples)`;脏文件 .trellis 模板/AGENTS.md/.codebuddy 不混入)
- [x] 10. `mvn -q -pl nondocx-core,nondocx-toolkit install`(eval 依赖本地仓库新构件;必要时 -DskipTests,全量测试已在步骤 8 覆盖)
- [x] 11. 复跑 compiler baseline 全量:`mvn -q -pl nondocx-examples -Dexec.mainClass=io.github.nondirectional.docx.examples.eval.EvalMain exec:java -Dexec.args="--surface compiler --group baseline"`,记录 runId(target/eval/<runId>/),确认 25/25 含 header-write-text 通过;amend feat 提交 body 附 runId
- [ ] 12. full 面按 §6.12 评估:复跑 full baseline,header-write-text 失败归因(写 ref 只解析正文,物理不可达)→ 维持 capabilityGap flag;若环境无法跑模型,按 A11 降级并在汇报标注

### 阶段四:文档与 spec(主会话或派 trellis-implement)

- [ ] 13. docs/07-toolkit.md 四处(design §6 表);docs/11 新增 §6.15(含 runId);docs/03 复核(预计零改动,A9);spec agent-single.md 三处(op 条目/元数据行条目、baseline 25 条+已知边界改写+runId 链式追加、ref 容器段复核);docs 提交(scope 前缀 docs,附 runId)

### 阶段五:收尾(主会话)

- [ ] 14. 归档任务(task.py archive 对应命令)、记 journal(.trellis/workspace/non/journal-2.md);脏文件(.trellis 模板/AGENTS.md/.codebuddy)单独 chore 提交
- [ ] 15. 最终汇报:集中列出全部自主决策点(PRD A1/A2 + design A3-A12)

## 验证命令速查

```bash
# 格式化 + 全量测试
mvn -q spotless:apply && mvn test
# eval 依赖安装
mvn -q -pl nondocx-core,nondocx-toolkit install
# compiler baseline 复跑(runId 见 target/eval/)
mvn -q -pl nondocx-examples -Dexec.mainClass=io.github.nondirectional.docx.examples.eval.EvalMain exec:java -Dexec.args="--surface compiler --group baseline"
```

(EvalMain 全名以 EvalTasks.java 同包实际值为准;复跑命令来自 research/eval-structure-and-rerun.md。)

## 回滚点

- 每阶段完成后 git 可 revert;功能全部改动同批一次提交,回滚=revert 该提交。
- eval 复跑若暴露 op 缺陷:回到阶段一修复后从步骤 8 重走门禁。
