# core 页眉页脚读写分离与 examples Agent 工具增强

## Goal

两个紧密耦合的主题，一起在上一任务（`06-17-examples-nonchain-agent-docx`）归档之后完成：

1. **core 页眉页脚读写分离** —— 把 `Section.header()`/`footer()` 从「访问即创建」改为纯只读
   （不存在返回 `null`），新增 `ensureHeader()`/`ensureFooter()` 作为显式创建路径；`Document`
   对称提供便捷委托；同步更新 spec 与测试。
2. **examples Agent 工具增强** —— 在上一任务的 docx Agent 工具集基础上，补齐「按文本定位」
   能力（`search_text`）与「读取页眉/页脚结构」能力（`read_header`/`read_footer`），并新增一个
   交互式 REPL 示例 `InteractiveDocxAgentExample`。

**为什么放一个任务里**：主题 2 的只读工具（`search_text` 遍历页眉页脚、`read_header`/`read_footer`）
直接依赖主题 1 带来的只读安全保证 —— 没有 `header()`/`footer()` 改为纯只读，这些只读遍历就会
凭空创建空页眉 part、污染文档。两者是「API 安全性」→「放心地只读遍历」的因果关系，不可拆分提交。

## Progress（实现进度）

> 最后更新：2026-06-18。
> 状态：两个主题代码均已提交并通过验证，本任务已归档。

### 主题一：core 页眉页脚读写分离

| 项 | 状态 | 说明 |
|----|------|------|
| `Section.header()`/`footer()` 改纯只读 | ✅ | 不存在返回 `null`，`catch POIXMLException` 归一化为 `null`，永不动文档 |
| 新增 `Section.ensureHeader()`/`ensureFooter()` | ✅ | 不存在才创建（含兼容性页面设置补齐），已存在原样返回；写入场景入口 |
| `Document` 对称委托 | ✅ | `header()`/`footer()`/`ensureHeader()`/`ensureFooter()` 四个便捷方法 |
| `equals`/`hashCode` 简化 | ✅ | 私有 `defaultHeader/FooterParagraphs` 不再规避 create-on-access，直接复用只读 `header()`/`footer()`（null→空列表） |
| spec 更新 | ✅ | `poi-bridge.md` N5 重写为读写分离说明；N8 更新为只在 `ensure*` 创建路径触发页面设置补齐 |
| 测试更新 + 新增 | ✅ | 迁移所有 create-on-access 调用至 `ensure*`；新增 `headerReturnsNullWhenAbsent`/`footerReturnsNullWhenAbsent`/`headerIsNullWhenAbsent` |
| example 调用点迁移 | ✅ | `ComplexDocument` / `HeaderFooterExample` 切到 `ensureHeader()`/`ensureFooter()` |
| core 验证 | ✅ | `mvn -pl nondocx-core test` 全绿，124 测试 |

### 主题二：examples Agent 工具增强

| 项 | 状态 | 说明 |
|----|------|------|
| `search_text` 工具 | ✅ | 横切正文段落 + 表格所有单元格 + 各 section 页眉/页脚，一次返回所有命中坐标（段落级匹配，标注含命中的 run） |
| `read_header`/`read_footer` 工具 | ✅ | 读取 section 默认页眉/页脚某段结构摘要；受益于读写分离，直接调只读 `header()`/`footer()`（null=不存在） |
| prompt 引导 | ✅ | `DocxAgentExample` system prompt 增加「按文本定位优先用 search_text」引导 |
| 模型升级 | ✅ | `qwen-plus` → `qwen3.7-plus` |
| 交互式 REPL 示例 | ✅ | 新增 `InteractiveDocxAgentExample`：`MessageWindowChatMemory` 多轮记忆 + 流式事件回显 + `:new`/`:help`/`:q` 命令 |
| examples 验证 | ✅ | `mvn -pl nondocx-examples -am compile` 全绿 |

## Confirmed Facts

- 上一任务已把 docx Agent 工具集（正文段落/run、表格单元格、超链接的读写）落地，但缺少
  「按文本内容定位位置」的能力 —— Agent 要改某段文字时只能 `get_count` → 逐个 `read_*` 盲读，
  每步一轮 LLM 往返，定位很慢。`search_text` 正是补这块。
- POI 自身就是读写分离的：`getDefaultHeader()`/`getDefaultFooter()` 只读返回（null=不存在），
  `createHeader(DEFAULT)`/`createFooter(DEFAULT)` 才新建并附加 part（会改文档）。
- 早期 nondocx 把两者合并进 `header()`/`footer()` 一个方法（访问即创建），是为了写入场景
  「取到就能用」的便利；代价是只读遍历（搜索、`equals`）会意外创建空 part、污染文档，
  以致 `Section.equals` 当初要用私有只读解析器绕开 `header()`。

## Requirements

- core：`header()`/`footer()` 必须是纯只读，永不动文档；创建路径必须有独立、命名明确的入口。
- core：`equals`/`hashCode` 不得因读取页眉页脚而修改文档。
- core：兼容性页面设置补齐（A4 + 1 英寸边距）只在真正需要创建 part 时触发，不得因只读访问触发。
- examples：`search_text` 覆盖正文段落、表格所有单元格、页眉、页脚四类容器。
- examples：`search_text` 命中粒度用段落 `text()`（POI 拼好的纯文本，天然跨 run）。
- examples：`search_text` 支持大小写/精确控制与命中数上限，上限到达时提示缩小关键词。
- examples：交互式示例需演示多轮记忆（用户指代「那个文档」能被理解）与流式输出。

## Acceptance Criteria

- [x] `Section.header()`/`footer()` 纯只读（null=不存在，永不动文档），`equals`/`hashCode` 直接复用。
- [x] `Section.ensureHeader()`/`ensureFooter()` 提供显式创建路径，含兼容性页面设置补齐。
- [x] `Document` 对称提供 `header/footer/ensureHeader/ensureFooter` 四个便捷方法。
- [x] spec `poi-bridge.md` N5/N8 反映读写分离后的语义。
- [x] core 测试覆盖「不存在返回 null」「只读访问不污染页面设置」，全绿。
- [x] `search_text` 覆盖正文 + 表格 + 页眉 + 页脚四类容器，返回命中坐标 + 含命中 run。
- [x] `read_header`/`read_footer` 利用只读 API 安全读取页眉页脚段落结构。
- [x] `InteractiveDocxAgentExample` 提供多轮记忆 + 流式输出 + REPL 命令。
- [x] `nondocx-core` 测试全绿（124）、`nondocx-examples` 编译全绿。

## 关键决策与沉淀（供后续任务参考）

1. **读写分离是 API 安全性的根本解，而非用私有绕过方法打补丁。** 早期用「`equals` 里私有只读
   解析器绕开 create-on-access」是局部止血；只有把公开 `header()`/`footer()` 本身改成只读，
   只读遍历才「by construction」安全，绕过样板才能删除。凡是「读会触发写」的 API 都值得这样重审。
2. **`search_text` 把线性扫描搬出 Agent 循环。** 按索引寻址的工具（`read_paragraph` 等）要求
   Agent 已知位置；按文本寻址的工具（`search_text`）把「找位置」这步一次性完成，避免每段一轮
   LLM 往返。设计 Agent 工具集时，要成对提供「按索引」和「按内容」两种寻址。
3. **`max_results` 用 `Integer` 而非 `int`。** nonchain 在 LLM 不传参时注入 `null`，包装类型能
   安全接住 null 再归一化为默认值，避免基本类型收到 null 触发 NPE。Agent 工具的可选参数一律
   用包装类型。
4. **交互式 vs 批处理是「同工具不同驱动」的对照。** `InteractiveDocxAgentExample` 与
   `DocxAgentExample` 共用同一套 `DocxAgentTools`，区别只在 memory + 流式 callback。这种
   「工具不变、驱动方式变」的拆分让联调与演示各取所需。
