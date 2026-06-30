# tracked changes 单元格结构类(cellIns/cellDel)读写

## Goal

从 `advanced-types` 拆出的子任务。advanced-types 研究阶段(research/ooxml-forms.md)确认三类高级修订里 **cell(cellIns/cellDel)结构风险最高、需独立工程量**,故按 implement.md §5/§6 的拆分回退点拆为本子任务。本子任务还顺带覆盖 advanced-types v1 留作边界的 **pPrChange / 更高层属性类**(其 CT 类型在 POI 精简 classpath 待引入)。

## Background(来自 advanced-types 研究)

研究探针已确认真实 OOXML 形态(research/ooxml-forms.md §1.3):
- `cellIns` / `cellDel` 嵌在 `<w:tcPr>`(单元格属性)内,不在 cell 包装层;CT 类型是 `CTTrackChangeImpl`(裸 id/author,无 run、无文本),表达"这个单元格是被插入/删除的"(表格结构修订)。
- read 侧目前完全不覆盖:`walkCell` 只下钻到 cell 内的 `<w:p>`,不读 `<w:tcPr>`。
- accept/reject 语义围绕结构节点保留/删除/恢复,**不能套用文本类 ins/del 的 mechanics**(误当文本类会写出结构错文档)。

pPrChange / sectPrChange 等更高层属性类:advanced-types v1 只覆盖了 rPrChange(CTRPrChange 在精简 classpath);pPrChange 的 CTPPrChange 当时未被引用、不在 classpath。本子任务需先让相关 CT 类型进入 classpath(可能需 poi-ooxml-lite 重新生成或引用),再补 read + accept/reject。

## Requirements

- **R1 — cell 读**:扩展 read walker,下钻 `<w:tcPr>` 枚举 `cellIns`/`cellDel` 为 `TrackedChange`(type=CELL_INS/CELL_DEL,family=CELL),`CellChangeDetails` 表达结构语义(插入/删除、作用对象摘要)。
- **R2 — cell accept/reject**:accept(结构生效,保留插入/删除的单元格)/reject(结构撤销,恢复/移除)。**必须**先研究真实前后状态再落实现细节,不能套文本类 mechanics。
- **R3 — pPrChange / 更高层属性类**:让相关 CT 类型进入 classpath;补 read(下钻 pPr/sectPr/tblPr 等)+ accept/reject(整树替换,沿用 advanced-types 的方案 C 专用写)。
- **R4 — 边界一致性**:更新 poi-bridge.md(N15 范围行)、error-handling.md、README 反映 cell/pPr 等已落地;诚实保留仍未覆盖的边界。

## Acceptance Criteria

- [ ] AC1 cellIns/cellDel 能被 `list()` 读回,`details()` 为 `CellChangeDetails`
- [ ] AC2 cell accept/reject 产生正确的结构结果(不写错文档,有 round-trip 验证)
- [ ] AC3 pPrChange(至少)能被读回;accept/reject 整树替换正确
- [ ] AC4 不破坏现有 move/property/text 的测试与契约
- [ ] AC5 spec/边界文档同步更新

## Notes

- **research-first**:本子任务仍应先补 cell 的真实前后状态 fixture(accept/reject 后表格结构怎么变),再写代码——cell 最容易写出结构错误文档。
- 若 pPrChange 的 CT 类型进入 classpath 的成本过高,可单独评估是否进一步拆分。
- 参考已落地实现:`TrackedChangeNodes.collectPropertyChanges`(property 读)、`TrackedChange` 双委托 + `propertyNode()`(property 写,方案 C)、`TrackedAdvancedTypesTest`(测试范式)。
