# 排查 HeaderFooterExample 在 WPS 中页眉页脚未显示的问题

## Goal

定位 `nondocx-examples/src/main/java/com/non/docx/examples/HeaderFooterExample.java` 生成的 `.docx` 在 WPS 中看不到页眉/页脚的原因，并给出最小可验证修复方向。

## Confirmed Facts

- 运行 `HeaderFooterExample` 后，输出文件为 `target/examples-output/header-footer-example.docx`。
- 解包生成文件后，能看到：
  - `word/header1.xml`
  - `word/footer1.xml`
  - `word/document.xml` 末尾的 `<w:sectPr>` 中存在 `<w:headerReference>` / `<w:footerReference>`
- 这说明：**页眉/页脚 part 已经写入，关系也已经挂到节属性上；问题不是“完全没写进去”**。
- 与 `nondocx-examples/src/main/java/com/non/docx/examples/ComplexDocument.java` 生成的文档对比：
  - `ComplexDocument` 的 `<w:sectPr>` 除了 header/footer 引用外，还包含显式的 `<w:pgSz>` 与 `<w:pgMar>`
  - `HeaderFooterExample` 的 `<w:sectPr>` 只有 header/footer 引用，没有显式页面设置
- `nondocx-core` 现有测试（`HeaderFooterTest`、`RoundTripTest`）验证了 POI 往返与库内读取正常，但**没有 WPS 兼容性烟雾测试**。

## Requirements

- 解释问题时区分三层：
  1. OOXML 层：页眉页脚由哪些 XML part / 引用组成
  2. POI 层：`XWPFHeaderFooterPolicy` 如何创建这些 part
  3. nondocx 层：当前 example / API 为什么会生成这样的结构
- 给出“最可能根因”以及“如何最小验证”的方案。
- 如果需要改代码，优先最小改动并保留 example 的教学价值。

## Acceptance Criteria

- [ ] 能指出生成文档里页眉页脚**确实已写入**，并给出对应 OOXML 证据
- [ ] 能指出与 WPS 显示行为最相关的结构差异
- [ ] 能给出一个最小修复/验证方案（例如补显式页面设置）
- [ ] 若进入实现，修改范围应清晰且便于用 WPS 再次人工验证

## Out of Scope

- 在本轮中重构整个页眉页脚 API
- 一次性补齐所有 Office/WPS 兼容性问题
- 在未确认根因前扩散修改到大量核心代码

## Open Questions

- 本轮是否只做“定位 + 给出修复建议”，还是要我顺手把 example / 核心代码也修掉并补测试？
