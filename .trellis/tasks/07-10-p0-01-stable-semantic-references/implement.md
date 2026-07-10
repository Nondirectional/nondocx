# P0-01 稳定语义寻址实施计划

## 执行顺序

- [x] 新增 `ref` 包：稳定性、元素类型、DocumentRef、ElementRef 和具体 ref 值对象。
- [x] 新增规范化 codec、`RefResolutionCode`、`RefResolutionException`。
- [x] 在 core internal/poi 增加只读 paragraph `paraId` helper，验证读取不改 OOXML。
- [x] 实现 `ElementResolver`，覆盖 paragraph/table/run/cell/header/footer/revision 扫描。
- [x] 把 `ReferenceContext` 接入 `SessionTools`、`ToolkitToolContext`、`DocxToolkit` 构造链。
- [x] 给 `ParagraphPreview`、`TablePreview` 增加 ref，升级 snapshot version。
- [x] 修改 `SnapshotBuilder`，使用当前 conversation/generation 签发 ref。
- [x] 把 `ConflictKey.targetRef` 改为 `ElementRef`，迁移 Operation、Agent 与测试调用点。
- [x] 给 Body/Table 工具增加 `ref` payload 兼容，并在写结果返回实际 ref。
- [x] 给 HeaderFooter/TrackedChange 工具增加 ref 接线和统一错误码渲染。
- [x] 更新工具描述、Javadoc、`docs/07-toolkit.md` 的索引弃用路线。
- [x] 新增 `StableSemanticReferenceExample` 并实际运行验证五个核心场景。

## 测试

- [x] 引用值对象内容相等、canonical round-trip、非法格式。
- [x] 段落前插入后 SESSION ref 仍指向原段落。
- [x] 删除段落/表格/单元格后返回 `element_removed`。
- [x] SESSION ref 跨 generation 返回 `generation_mismatch`。
- [x] 已有 `paraId` 段落 save/reopen 后 PERSISTENT ref 可重新定位。
- [x] 无 `paraId` 段落签发引用不修改原始 XML。
- [x] 段落/表格交错文档中 ref 与 `index/bodyIndex` 同时正确。
- [x] ref 与旧索引同时传入且不一致时拒绝执行。
- [x] `ConflictKey.sameTarget` 使用强类型 ref 正确去重。

## 验证命令

```bash
rtk mvn -q -pl nondocx-core -am test
rtk mvn -q -pl nondocx-toolkit -am test
rtk mvn -q spotless:apply
rtk mvn -q verify
```

## 验收结果（2026-07-10）

- [x] `rtk mvn -q -pl nondocx-core -am test`
- [x] `rtk mvn -q -pl nondocx-toolkit -am test`
- [x] `rtk mvn -q spotless:apply`
- [x] `rtk mvn -q verify`
- [x] `rtk git diff --check`
- [x] `StableSemanticReferenceExample` 实际运行，五个场景全部输出 `PASS`

## 重点审查

- public core API 不新增 POI 类型泄漏。
- 引用签发和 snapshot 构建是纯读取，不补写 `paraId`。
- resolver 不把位置索引当身份，不返回已从文档树删除的旧 wrapper。
- 新工具路径不绕过 generation/document/type 校验。
- 兼容索引逻辑集中，不在每个工具复制不同解析规则。

## 回滚点

- 值对象/resolver 与工具接线分开提交。
- snapshot version 升级与 `ConflictKey` 迁移保持同一可编译阶段。
- 若全工具接线无法一次完成，保留旧索引入口，但不得回退快照与冲突协议的强类型 ref。
