# 新增 examples 模块与开发者协作规范

## 需求

### 1. nondocx-examples 示例模块
- 新增 `nondocx-examples` Maven 子模块，依赖 `nondocx-core`
- 提供 `ComplexDocument.java` 综合示例，演示页面设置、页眉页脚、标题、段落格式化、表格、列表、超链接、图片等 API 组合使用
- 提供 `ExamplePaths.java` 示例输出路径工具，统一输出到 `target/examples-output/`
- 在 `.gitignore` 中忽略示例输出目录
- 在父 POM 中注册新模块

### 2. 开发者协作规范
- 在 `AGENTS.md` 中添加开发者偏好说明（面对面对话式开发、教学式实现）
- 编写 `.trellis/spec/guides/teaching-approach.md` 教学式开发指南

### 3. 目录结构文档
- 更新 `.trellis/spec/backend/directory-structure.md` 记录 examples 模块结构

## 验收标准
- [x] `nondocx-examples` 模块可编译（`mvn compile -pl nondocx-examples`）
- [x] `ComplexDocument` 可运行生成 docx 文档
- [x] 教学指南内容完整，涵盖三层递进教学法
- [x] AGENTS.md 的开发者偏好清晰可读
