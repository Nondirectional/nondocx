# nondocx-core MVP: docx 读写封装库

## 目标 (Goal)

提供一个基于 Apache POI 的、现代、流畅、领域友好的 docx 读写 Java 库。把 POI 啰嗦难用的 `XWPF*` API 封装成直观的领域模型（`Document` / `Paragraph` / `Run` / `Table` …），让开发者用少量代码完成 docx 的读取、构造与修改，同时保留对底层 POI 的逃生舱。

## 背景 (Background)

Apache POI 的 `XWPFDocument` 是 Java 生态读写 docx 的事实标准底座，功能完整但 API 啰嗦：

- `XWPFRun` 要手动切分内联样式，粗体/字号/字体散落在多个 setter；
- 表格的行列单元格操作繁琐，合并单元格、样式继承不透明；
- 段落/分节/页面属性分布在多个 XmlBeans 类型上，心智负担重。

直接使用 POI 的代价是样板代码多、易错、可读性差。本库定位为「通用友好封装」，在 POI 之上提供一层薄而流畅的领域抽象。

## 目标用户 (Target Users)

需要在 Java 应用（JDK 11+）中读取、构造、修改 docx 文件的开发者：报表生成、文档批量处理、数据抽取等场景。

## 功能需求 (Functional Requirements) — MVP 范围

MVP 覆盖以下 docx 概念，均要求**读 + 写双向**闭环。分为三档：

### 第 1 档 · 文档骨架（基础，必须）

- **Document** — 文档容器，打开 / 创建 / 保存
- **Paragraph** — 段落，增删改查、插入
- **Run** — 文本片段 + 内联样式（粗体 / 斜体 / 下划线 / 字体 / 字号 / 颜色）
- **段落级样式** — 标题样式 (H1–H6)、对齐、缩进、行距
- **Table / TableRow / TableCell** — 表格的行列单元格操作

### 第 2 档 · 常见富内容

- **Image** — 图片插入与读取（内联图片）
- **Hyperlink** — 超链接
- **列表 / 编号** — 有序 / 无序列表（支持嵌套层级，作为段落级属性）

### 第 3 档 · 部分（性价比优先）

- **页面属性** — 纸张大小、页边距、横竖向、分节 (`Section`)
- **页眉 / 页脚** — section-scoped `Header` / `Footer`

## 非功能需求 (Non-Functional Requirements)

- **Java 兼容性**：最低 JDK 11，兼容 17 / 21（通过 CI 矩阵验证）
- **可发布性**：Maven 多模块项目，坐标 `com.non:nondocx-core`，SemVer 版本策略（`0.0.1` → `0.0.2` → … → `1.0.0`）
- **对外文档语言**：README、Javadoc、代码注释、异常消息**全英文**（面向国际开源生态）
- **许可证**：Apache License 2.0（与 POI 一致）
- **零额外配置**：用户引入依赖即可用（POI 为 compile scope，对用户透明）

## 约束 (Constraints)

| 维度 | 约束 |
|------|------|
| 构建工具 | Maven |
| 模块布局 | 立即多模块：父 POM `nondocx-parent` + `nondocx-core`（MVP 唯一实质模块） |
| 坐标 | `groupId=com.non`，`artifactId=nondocx-core` |
| POI 版本 | Apache POI 5.2.5，scope=compile，父 POM `dependencyManagement` 锁定传递依赖 |
| Lombok | 不使用（核心类型手写代理 + 内容相等 `equals/hashCode`） |
| 测试框架 | JUnit 5 + AssertJ，POI 原生做交叉参照 |
| 代码质量 | MVP 集成 Spotless（格式化）；Checkstyle / SpotBugs / Error Prone 推迟 |
| CI | GitHub Actions，JDK 矩阵 `[11, 17, 21]` |

## 验收标准 (Acceptance Criteria)

- [ ] **往返保真 (Round-trip)** 核心标准：用本库构造一篇含标题、段落、表格、图片、列表、分节页眉页脚的文档，`save` → 重新 `open`，**领域对象内容相等**（深粒度 `equals`，文本 + 样式 + 结构全等，不含底层 POI 引用）
- [ ] 第 1 档功能全部读写闭环
- [ ] 第 2 档功能全部读写闭环
- [ ] 第 3 档（页面属性 + 页眉页脚）读写闭环
- [ ] 所有核心 API 类型提供 `raw()` 逃生舱，返回对应 `XWPF*` 类型；`raw()` 路径可直接透传 POI 原生异常
- [ ] 除 `raw()` 外，公开 API 的异常均为自建 `DocxException` 体系（全 unchecked），用户无需 import `org.apache.poi.*` 异常
- [ ] `mvn verify` 在 JDK 11 / 17 / 21 三档 CI 矩阵全绿
- [ ] `spotless:check` 通过
- [ ] 项目脚手架齐全：父 POM、`nondocx-core` 模块、LICENSE (Apache 2.0)、README（英文）

## 不在范围 (Out of Scope)

- **第 4 档高级特性**：修订追踪 (tracked changes)、域代码 (fields)、嵌入 OLE 对象、OMML 数学公式、水印、文本框、形状 (Drawing/Shape) —— MVP 仅提供逃生舱兜底，不做深封装
- **目录 (TOC)、文档元数据、脚注 / 尾注** —— 推迟到 v0.2（TOC 涉及域代码刷新，脚注 POI API 较绕）
- **JPMS `module-info.java`** —— 推迟到 v1.0 前（当前用命名约定隔离 `internal` 包）
- **上层模块**（模板引擎 `nondocx-template`、转换器 `nondocx-converter`）—— 未来扩展模块，MVP 不创建空壳
- **发布到 Maven Central** —— 单独后续任务（涉及 `com.non` 域名所有权 / Sonatype 账号配置）
- **Checkstyle / SpotBugs / Error Prone** —— 库稳定后引入
