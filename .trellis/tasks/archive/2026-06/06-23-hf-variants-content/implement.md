# Implement — 页眉页脚内表格与图片便捷方法

> 子任务 `06-23-hf-variants-content` 的执行计划。
> 需求见 `prd.md`；技术设计见 `design.md`。

## 执行顺序

### 步骤 1：实现 `Header.addTable()` + `Header.tables()`

编辑：`nondocx-core/src/main/java/com/non/docx/core/api/header/Header.java`

新增 import：
- `com.non.docx.core.api.table.Table`
- `java.util.AbstractList`（已有 List）

在 `addParagraph` 区块之后加两个方法（照 design.md 骨架）。

**Review gate**：`mvn -q compile`。

### 步骤 2：实现 `Footer.addTable()` + `Footer.tables()`

编辑：`nondocx-core/src/main/java/com/non/docx/core/api/header/Footer.java`

与 Header 对称。

### 步骤 3：写测试

新建：`nondocx-core/src/test/java/com/non/docx/core/api/header/HeaderContentTest.java`

| 方法 | 覆盖 |
|---|---|
| `headerTableRoundTrips` | 表格 round-trip |
| `headerImageRoundTripsViaParagraph` | 图片复用 Paragraph.addImage |
| `paragraphAndTableCoexistInHeader` | 段落+表格共存 |
| `headerTablesIsEmptyByDefault` | 空表 / 无表返空列表 |
| `headerAddTableYieldsEmptyTable` | addTable 返回真空表（无幽灵行）|
| `footerTableRoundTrips` | Footer 对称 |
| `footerImageRoundTripsViaParagraph` | Footer 图片 |

复制 `solidPng` helper（来自 `ImageTest`）。

**Review gate**：`mvn -q test -Dtest=HeaderContentTest` 全绿。

### 步骤 4：全量回归

```bash
mvn -pl nondocx-core spotless:apply
mvn -pl nondocx-core verify
```

## 验证命令

| 命令 | 用途 |
|---|---|
| `mvn -pl nondocx-core -q compile` | 编译 |
| `mvn -pl nondocx-core test -Dtest=HeaderContentTest` | 新测试 |
| `mvn -pl nondocx-core verify` | 全量回归 + spotless |

## Rollback 点

- `createTable(1,1)` 在某些 POI 版本抛异常 → 退回 `createTable(0,0)` 或 try/catch 兜底
- 图片 round-trip 失败（探针已通过，概率低）→ 走路径 B，新建 `internal/poi/HeaderPictures`

## 完成条件

- [ ] `Header.addTable()` + `Header.tables()`
- [ ] `Footer.addTable()` + `Footer.tables()`
- [ ] `HeaderContentTest` 7 用例全绿
- [ ] `mvn -pl nondocx-core verify` 全量绿
- [ ] 现有 `HeaderFooterTest` / `RoundTripTest` 无回归
- [ ] 公开签名无 POI 类型泄漏

## 不在本执行内

- spec 更新（N20：XWPFHeader 实现 IBody 的复用发现）—— docs-spec 子任务
- `Header.equals` 纳入表格 —— 诚实边界，不改
- `Header.addImage` 便捷方法 —— 不加（Paragraph.addImage 已可用）
