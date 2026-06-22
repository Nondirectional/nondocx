# Implement — 首页 / 偶数页页眉页脚变体 API

> 子任务 `06-23-hf-variants-variants` 的执行计划。
> 需求见 `prd.md`；技术设计见 `design.md`。

## 执行顺序

### 步骤 1：实现 `HeaderFooterVariant` 枚举

新建：`nondocx-core/src/main/java/com/non/docx/core/api/header/HeaderFooterVariant.java`

照 design.md 的 Javadoc（三变体 + 开关依赖 + WPS 提示）。POI-free。

### 步骤 2：`Mappers.toPoi(HeaderFooterVariant)`

编辑：`nondocx-core/src/main/java/com/non/docx/core/internal/poi/Mappers.java`

新增静态方法 `toPoi(HeaderFooterVariant)` → `STHdrFtr.Enum`。需新增 import：
- `com.non.docx.core.api.header.HeaderFooterVariant`
- `org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy`
- `org.openxmlformats.schemas.wordprocessingml.x2006.main.STHdrFtr`

**Review gate**：`mvn -q compile`。

### 步骤 3：重构 `Section` 的 header/footer 为变体感知

编辑：`nondocx-core/src/main/java/com/non/docx/core/api/section/Section.java`

**3a. 无参版本委托 DEFAULT**：
```java
public Header header()                          { return header(HeaderFooterVariant.DEFAULT); }
public Header ensureHeader()                    { return ensureHeader(HeaderFooterVariant.DEFAULT); }
public Footer footer()                          { return footer(HeaderFooterVariant.DEFAULT); }
public Footer ensureFooter()                    { return ensureFooter(HeaderFooterVariant.DEFAULT); }
```
（保持现有 Javadoc，方法体改为委托。）

**3b. 新增变体重载**：4 个公开方法 + 4 个私有统一入口（`resolveHeader` / `resolveFooter` + `readHeader` / `readFooter` 分派）。

**3c. 新增 `ensureVariantFlags(HeaderFooterVariant)`**：补 titlePg / evenAndOddHeaders 开关。

**3d. 新增 import**：
- `com.non.docx.core.api.header.HeaderFooterVariant`
- `org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSettings`

**Review gate**：`mvn -q compile` + 现有 `HeaderFooterTest` 仍绿（无参路径回归）。

### 步骤 4：`Document` 便捷重载

编辑：`nondocx-core/src/main/java/com/non/docx/core/api/Document.java`

新增 4 个变体便捷方法（委托 `section(0)`）。更新类级 Javadoc 里"header / footer accessors"段提及变体。

### 步骤 5：扩展 `Section.equals` / `hashCode`

新增 4 个私有只读解析方法（`firstHeaderParagraphs` / `firstFooterParagraphs` / `evenHeaderParagraphs` / `evenFooterParagraphs`），与现有 `defaultHeaderParagraphs` 同模式。

`equals` / `hashCode` 纳入这 4 个。

**Review gate**：`mvn -q test -Dtest=HeaderFooterTest,RoundTripTest` 绿（回归）。

### 步骤 6：写测试

编辑：`nondocx-core/src/test/java/com/non/docx/core/api/header/HeaderFooterTest.java`（扩展现有文件，与默认变体测试同风格）

新增用例：

| 方法 | 覆盖 |
|---|---|
| `firstHeaderRoundTrips` | AC1：FIRST 页眉 round-trip |
| `evenFooterRoundTrips` | AC2：EVEN 页脚 round-trip |
| `firstHeaderWritesTitlePg` | AC3：sectPr 有 titlePg |
| `evenHeaderWritesSettingsFlag` | AC4：settings 有 evenAndOddHeaders |
| `threeVariantsCoexist` | AC5：三变体共存 round-trip |
| `firstHeaderCreateOnce` | AC6：重复 ensure 返回同一 |
| `noArgEqualsDefault` | AC7：`header()` == `header(DEFAULT)` |
| `sectionEqualsIncludesVariants` | AC8：含变体的 equals round-trip |
| `firstVariantJavadocWpsWarning` | AC9：Javadoc 审查（人工 / grep） |

**Review gate**：`mvn -q test -Dtest=HeaderFooterTest` 全绿。

### 步骤 7：全量回归

```bash
mvn -pl nondocx-core verify
mvn -pl nondocx-core spotless:apply
```

## 验证命令

| 命令 | 用途 |
|---|---|
| `mvn -pl nondocx-core -q compile` | 编译通过 |
| `mvn -pl nondocx-core test -Dtest=HeaderFooterTest` | 变体测试 |
| `mvn -pl nondocx-core verify` | 全量回归 + spotless |

## Rollback 点

- 步骤 3 重构后现有 `HeaderFooterTest` 红 → 检查无参委托是否正确（`header()` 应 = `header(DEFAULT)`）
- 步骤 3 `document.getSettings().getCTSettings()` 不可达 → 已实测确认可达；若发生，退回用 `XWPFSettings.setEvenAndOddHeadings(true)`（注意方法名拼写 `Headings`）
- 步骤 5 `equals` 扩展后 `RoundTripTest` 红 → 检查 null 归一化（变体不存在时返空列表而非 null）

## 完成条件

- [ ] `HeaderFooterVariant` 枚举（POI-free）
- [ ] `Mappers.toPoi(HeaderFooterVariant)`
- [ ] `Section` 4 个变体重载 + 私有统一入口 + `ensureVariantFlags`
- [ ] `Document` 4 个便捷重载
- [ ] `Section.equals` / `hashCode` 纳入 FIRST/EVEN
- [ ] `HeaderFooterTest` 新增 9 用例全绿
- [ ] `mvn -pl nondocx-core verify` 全量绿
- [ ] 无参 API 行为无回归
- [ ] 公开签名无 POI 类型泄漏

## 不在本执行内

- spec 更新（poi-bridge N19）—— docs-spec 子任务统一落档
- WPS 渲染验证 —— docs-spec 子任务
- 页眉内富内容 —— content 子任务
