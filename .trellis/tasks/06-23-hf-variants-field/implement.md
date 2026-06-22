# Implement — 页码与通用简单域 API

> 子任务 `06-23-hf-variants-field` 的执行计划。
> 需求见 `prd.md`；技术设计见 `design.md`。

## 执行顺序

### 步骤 1：加载项目编码规范

实现前先加载 `trellis-before-dev` 注入的 spec：
- `.trellis/spec/backend/poi-bridge.md`（Rule 1-7、N9 手法）
- `.trellis/spec/backend/quality-guidelines.md`（content equality、POI-free 签名、fluent mutator）
- `.trellis/spec/backend/error-handling.md`（异常包装）
- `.trellis/spec/guides/teaching-approach.md`（三层递进 Javadoc）

### 步骤 2：实现 `Paragraph.addSimpleField(String)`

文件：`nondocx-core/src/main/java/com/non/docx/core/api/text/Paragraph.java`

在现有 `addHyperlink` / `addImage` 区块之后追加：

```java
public Run addSimpleField(String instruction) {
  Objects.requireNonNull(instruction, "instruction");
  if (instruction.isBlank()) {
    throw new IllegalArgumentException("instruction 不能为空白");
  }
  XWPFRun beginRun = delegate.createRun();
  CTFldChar begin = beginRun.getCTR().addNewFldChar();
  begin.setFldCharType(STFldCharType.BEGIN);

  XWPFRun instrRun = delegate.createRun();
  instrRun.getCTR().addNewInstrText().setStringValue(instruction);

  XWPFRun endRun = delegate.createRun();
  CTFldChar end = endRun.getCTR().addNewFldChar();
  end.setFldCharType(STFldCharType.END);

  return new Run(instrRun);
}
```

**需新增 import**：
- `org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFldChar`
- `org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType`

**Javadoc**：照 design.md 的三段式（OOXML 三 run 结构 → POI 无高层 API 操纵 CTR → nondocx 为什么放在 Paragraph 上）。务必中文。

**Review gate**：编译通过（`mvn -q compile`），签名不泄漏 POI 类型。

### 步骤 3：实现便捷方法 `addPageNumberField()` / `addPageCountField()`

同文件，紧接 `addSimpleField`：

```java
public Run addPageNumberField() {
  return addSimpleField("PAGE");
}

public Run addPageCountField() {
  return addSimpleField("NUMPAGES");
}
```

**Javadoc**：指向 `addSimpleField`，说明等价关系与典型用途（页码 / 总页数）。

### 步骤 4：写测试

新建文件：`nondocx-core/src/test/java/com/non/docx/core/api/text/SimpleFieldTest.java`

测试用例（按 design.md 测试策略）：

| 方法 | 覆盖 |
|---|---|
| `simpleFieldRoundTrips` | AC1：`addSimpleField("PAGE")` round-trip 后 instrText 读回 `PAGE` |
| `pageNumberFieldEqualsAddSimpleFieldPage` | AC2：便捷方法等价性 |
| `pageCountFieldEqualsAddSimpleFieldNumPages` | AC3：便捷方法等价性 |
| `arbitraryInstructionRoundTrips` | AC4：`DATE \@ yyyy`、`SECTION` 等通用指令 round-trip |
| `rejectsBlankInstruction` | AC5：空白抛 IAE |
| `rejectsNullInstruction` | AC5：null 抛 IAE |
| `returnedRunAcceptsStyle` | 返回的 run 可链式设样式 |
| `fieldRunsAppearInInlineElements` | AC7：3 个 run 出现在 `inlineElements()` |

**辅助方法**（测试内私有）：

```java
private static String readInstrText(Paragraph p) {
  for (XWPFRun run : p.raw().getRuns()) {
    if (run.getCTR().sizeOfInstrTextArray() > 0) {
      return run.getCTR().getInstrTextArray(0).getStringValue();
    }
  }
  return null;
}
```

**Review gate**：`mvn -q test -Dtest=SimpleFieldTest` 全绿。

### 步骤 5：回归验证

```bash
mvn -q verify
```

确认：
- `RunTest` / `ParagraphTest` / `RoundTripTest` / `InlineElementOrderTest` 全绿（AC6）
- `spotless:check` 绿

### 步骤 6：格式化

```bash
mvn spotless:apply
```

## 验证命令

| 命令 | 用途 |
|---|---|
| `mvn -q compile` | 编译通过、签名无 POI 泄漏 |
| `mvn -q test -Dtest=SimpleFieldTest` | 新测试通过 |
| `mvn -q verify` | 全量回归 + spotless |

## Rollback 点

- 步骤 2 实现后发现 POI 的 `STFldCharType` 在 lite schema 不可达 → 已通过 `TocFields` 现有 import 验证可达，rollback 概率低；若发生，退回到用字符串构造 `STFldCharType.Enum`（XmlBeans 的 `forString` 方法）
- 步骤 4 round-trip 测试失败（instrText 读回为 null）→ 检查 POI 是否需要 `fldChar` 与 `instrText` 在不同 run（已验证是），或 POI 读路径需用 `getInstrList` 而非 `getInstrTextArray`

## 完成条件

- [ ] `Paragraph.addSimpleField` + 2 个便捷方法实现，Javadoc 三层递进
- [ ] `SimpleFieldTest` 8 用例全绿
- [ ] `mvn -q verify` 全量绿（含现有回归）
- [ ] 公开签名无 POI 类型泄漏
- [ ] 异常为 `IllegalArgumentException`（null / 空白）

## 不在本执行内

- spec 更新（poi-bridge N21）—— 由 `docs-spec` 子任务统一落档
- 集成测试（在页眉 / 变体页眉里插域）—— 由 `docs-spec` 子任务集成验收
- 域读侧建模 —— 走 `raw()`，后续子任务
