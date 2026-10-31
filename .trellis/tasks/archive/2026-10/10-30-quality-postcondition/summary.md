# 质检升级为编译器 post-condition（P2 收尾）— 总结

## 交付

`create_document` / `edit_document`（非 dry-run）/ `fill_template` 成功路径自动跑 10 项
质量检查：失败项全量 `data.qualityIssues`（`[{name,severity,message}]`，全过不设键），
message 追加摘要。非阻塞（写入已发生，检查纯读不回滚；检查异常按「无信号」吞掉）。

## 设计要点

- **噪音治理**：`toc` 建议项（「有标题但没插目录」）不进反馈——内容完整性建议非写质量
  信号，每次带标题 create 恒触发即噪音（要看走 validate_document）。seed 未排版段落的
  cjk-indent 警告保留，摘要标「提示性：除非用户要求排版规范，无需主动修复」。
- **提示词护栏**（eval+demo）：写结果自带质检、无需为 qualityIssues 追加修改、不必再
  单独 validate_document——防「看到警告就修用户没要的排版」的修复循环。
- validate_document 保留（全量编号报告，另一层用途）。

## 结果

runId `20261030-121824-988`：**22/22，平均 4.41**（上轮 4.68）。两个验证性事实：
1. `validate_document` 全程 **0 次调用**——post-condition 使其对写后校验冗余；
2. 无修复循环——最高调用任务 8 次（未新增）。

## 测试

toolkit 269（CompilerToolsTest +2：plain create 附 cjk-indent 无 toc；dry-run 无键 +
真写入含消息与 cjk-indent）/ examples 22 / demo 47 全绿，Spotless 绿。
