# 09 · FAQ 与已知边界

这一篇把 nondocx **「能做、不能做、做得有条件」** 的事集中讲清楚。每条都标注了详细原理在哪篇。

> 这里讲的是**使用层面的边界**。源码级「为什么这样实现」的 gotcha 在 `.trellis/spec/backend/poi-bridge.md` 的 N1–N17，那是给改 nondocx 源码的人看的。

---

## 目录

- [常见问题](#常见问题)
- [已知边界](#已知边界)
- [长度单位速查](#长度单位速查)

---

## 常见问题

### Q1. 改完内容为什么不用 `save` 就生效了？

因为 nondocx 的每个类型都是**活对象**（holding-wrapper），读写穿透到底层 POI 委托，没有缓存。`run.bold()` 直接改了内存里的 `XWPFRun`。`save()` 只是把内存里的 POI 树序列化到磁盘。

详见 [02 架构 §活对象契约](./02-architecture.md#2-活对象契约holding-wrapper)。

### Q2. save 再 open，新文档和原文档相等吗？

相等。`equals/hashCode` 比较的是**从公开 getter 解析出的语义值**，不比委托引用、不比原始 XML。POI 写侧的归一化痕迹（空 rPr、重新分配的 numId 等）对 `equals` 不可见。

详见 [04 往返保真](./04-round-trip-and-equality.md)。但注意：活对象可变，**别当长期 HashMap 键**。

### Q3. 能把 `Paragraph`/`Run` 当 `HashMap` 的键吗？

不建议。活对象内容随时可变，放进容器后内容一改，`hashCode` 变了，桶位置错了，就「丢了」。

`equals/hashCode` 服务于**比较和测试**（断言、临时查找），不是稳定键。短期/方法内的 Map 可以，跨长期操作/跨线程不行。

详见 [04 §4](./04-round-trip-and-equality.md#4-活对象--长期-hashmap-键)。

### Q4. 怎么处理 nondocx 没封装的特性（域、OLE、公式、形状……）？

用 `raw()`。每个核心类型都有 `raw()` 返回底层 POI 对象：

```java
XWPFDocument raw = doc.raw();
// 用任意 POI 能力处理 nondocx 未封装的特性
```

走 `raw()` 后，POI 异常**原样传播**，不会被包装成 `DocxException`。详见 [08 异常与 raw 领地](./08-exceptions-and-raw.md)。

### Q5. 为什么有的 `equals` 不比较我读得到的东西？

**读得到但比较不到** 是有意的建模边界。`Document.equals` 只比 `bodyElements()` + `sections()`：

| 特性 | 读得到 | 参与 `Document.equals` |
|---|---|---|
| 段落、表格、分节、默认页眉页脚 | ✅ | ✅ |
| TOC（域形态） | ✅ | ✅（隐式，run/超链接已计入 body） |
| TOC（SDT 形态） | ✅ | ❌ |
| 修订（tracked changes） | ✅ | ❌ |
| 非默认页眉页脚 | 部分 | ❌ |

要比较这些，需单独断言（如 `tc.list().equals(...)`）。详见 [04 §5](./04-round-trip-and-equality.md#5-已知的不对称诚实边界)。

### Q6. 修订的 id 能跨 save 稳定吗？

不能。`TrackedChange.id()` 是**进程内稳定**标识：同一会话内 `list()`/`get()` 一致，但**不承诺 save→reopen 后仍稳定**。accept/reject 后文档树重写，旧 id 可能失效。

跨会话操作修订要重新 `list()` 取最新 id。详见 [05/02 §4](./05-tracked-changes/02-read-and-query.md#4-单条getstring-id)。

### Q7. accept/reject 后还能继续用之前的 `Paragraph`/`Run` 吗？

不可靠。accept/reject 重写文档树后，POI 的内存 `XWPFParagraph`/`XWPFRun` 包装器可能 `XmlValueDisconnected`。

**验证 accept 后的结构必须 save→reopen**，不能信任 accept 前的 wrapper。这是 TC 最易踩的坑。详见 [05/03 §6](./05-tracked-changes/03-accept-reject.md#6-重要的使用须知accept-后-poi-缓存失效)。

### Q8. 为什么 `addTable()`/`addRow()`/`addCell()` 比我预期的"干净"？

POI 的 `createTable()`/`createRow()`/`createCell()` 会**预填默认子元素**（默认行、镜像 grid 的 cell、空段落）。nondocx 在创建路径**剥离这些预填**，保证「addX = 恰好一个 X」语义。源码级细节见 `.trellis/spec/backend/poi-bridge.md N2`。

---

## 已知边界

### TOC {#toc}

| 能力 | 支持 |
|---|---|
| 读首个 TOC（两种形态） | ✅ |
| 读后续 TOC | ❌ 只取首个（多 TOC 罕见） |
| 创建/刷新 TOC | ❌ 需 Word 分页引擎，属 `raw()` |
| SDT 形态 TOC 参与 `Document.equals` | ❌（域形态隐式参与） |

**两种形态**（[02/05 教程已详述](./05-tracked-changes/01-concepts.md)）：

- **域形态**（较早 Word）：跨多段的 `fldChar` 域
- **SDT 形态**（较新 Word）：整个 TOC 包在 `<w:sdt>`/`<w:sdtContent>` 里

nondocx 两种都解析（脏活在 `internal/poi/TocFields`），对外暴露统一的 `TableOfContents`/`TocEntry`。要遍历各 TOC 或创建，走 `raw()`。

### 修订（tracked changes）{#tracked-changes}

| 能力 | 状态 |
|---|---|
| 文本类（ins/del）读 + accept/reject + 创作 | ✅ |
| 移动类（moveFrom/moveTo）读 + accept/reject（配对联动） + 创作 | ✅ |
| 属性类（rPrChange）读 + accept/reject + 创作（六样式） | ✅ |
| 单元格类（cellIns/cellDel）读 + accept/reject + 创作 | ✅ |
| **cellMerge** | ❌ 只读（CT 类型缺失）；accept/reject/创作都不支持 |
| **pPrChange / sectPrChange / tblPrChange / trPrChange** | ❌ CT 类型全缺（POI 精简 schema dangling reference） |
| **全局修订录制**（自动追踪所有写操作） | ❌ 创作是显式路线 |

**为什么部分 CT 类型不可达**：POI 精简 jar（poi-ooxml-lite）只保留 POI 自身调用的 CT 类。某些类型接口声明返回某类型，但 class 文件与 `.xsb` schema 资源都不在 jar 内（dangling reference），编译期/运行期都不可达。**判断某 CT 类型是否可达必须 `unzip -l`/`javap` 实测**，不能只看接口声明。

详见 [05/01 §6](./05-tracked-changes/01-concepts.md#6-cell-类单元格结构存亡wcellins--wcelldel--wcellmerge)。

### WPS 兼容性兜底

首次通过 `Section.ensureHeader()` / `Section.ensureFooter()` 创建默认页眉页脚时，若该节**尚未显式写入页面设置**，nondocx 会补一个兼容性最小值：**A4 + 四边 1 英寸边距**（`1440` twips）。

**为什么**：WPS 对「有页眉页脚引用但缺页面几何设置」的 `<w:sectPr>` 比较敏感，可能显示异常。Word/POI 自己 round-trip 没问题，但 WPS 不 forgiving。

**两个要点**：

- **只补缺失，不覆盖**：你显式调过 `paperSize(...)`/`margins(...)`，nondocx 不动你的设置
- **只读路径不触发**：`Section.header()`/`footer()` 只读，不创建，因此单纯读取不会触发兜底

源码级细节见 `.trellis/spec/backend/poi-bridge.md N8`。

### 图片单位

`Paragraph.addImage(bytes, type, wPx, hPx)` 的宽高是**像素**；`Image.width()`/`height()` 也是像素。库内部按 `Units.EMU_PER_PIXEL = 9525` 转 EMU（OOXML `<wp:extent>` 用 EMU 存储），所以 round-trip 精确到像素。源码级细节见 `.trellis/spec/backend/poi-bridge.md N3`。

### 列表清除

不要直接对底层调 `setNumID(null)` —— 会留下空 `<w:numId val=""/>`，XmlBeans 在下次 save/open 时报 `XmlValueOutOfRange`。nondocx 的 `Paragraph.clearList()` 用 `unsetNumPr()` 移除整个 `<w:numPr/>` 元素，已封装。源码级细节见 `.trellis/spec/backend/poi-bridge.md N4`。

### run 文本替换

POI 的 `XWPFRun.setText(String)` 在 run 已有文本时会**追加**而非替换（OOXML 允许一个 `<w:r>` 有多个 `<w:t>`）。nondocx 的 `Run.text(String)` 先清空所有 `<w:t>` 再写，保证替换语义。源码级细节见 `.trellis/spec/backend/poi-bridge.md N9`。

### 超链接 URL 修改

POI **没有** API 改超链接目标 URL。nondocx 的 `Hyperlink.url(String)` 通过**重建 OPC 关系**实现：

1. 读旧 rId → 2. `removeRelationship(oldRid)` → 3. `addExternalRelationship` 让 OpenXML4J **自动分配新 rId** → 4. `setHyperlinkId(newRid)`

注意：`save→reopen` 后 `url()` 才读得到新值（POI 有 open-time 缓存，内存读可能仍报旧值）。源码级细节见 `.trellis/spec/backend/poi-bridge.md N10`。

### 页眉页脚

| 能力 | 状态 |
|---|---|
| 默认（奇数页）页眉页脚读 + 创建 | ✅ |
| 偶数页 / 首页 页眉页脚 | ❌ MVP 只建模默认一份 |
| 读/写分离 | ✅ `header()`/`footer()` 只读；`ensureHeader()`/`ensureFooter()` 创建 |

要操作偶数页/首页页眉页脚，走 `raw()`。源码级细节见 `.trellis/spec/backend/poi-bridge.md N5`。

### 文档元数据、脚注、尾注

MVP 未建模，属 `raw()` 范畴（见 `XWPFDocument` 的 `getFootnotes()`/`getEndnotes()`/`getProperties()` 等 POI 方法）。

### JPMS（模块化）

`module-info.java` 暂不提供（pre-1.0）。消费方按 classpath 引入即可。

---

## 长度单位速查

| 单位 | 用在 | 换算 |
|---|---|---|
| **twips** | `Paragraph.indent`、`Section.margins` | 1 inch = 1440 twips |
| **磅（points）** | `Run.fontSize` | — |
| **像素（px）** | `Paragraph.addImage`、`Image.width/height` | 库内部按 9525 EMU/px 换算 |
| **倍数** | `Paragraph.lineSpacing` | 1.0 = 单倍 |

---

## 遇到没在这里的问题

1. 先查 [03 API 速查](./03-api-reference.md) 看方法签名和标记（🔄👁✏️）
2. 再查 [08 异常](./08-exceptions-and-raw.md) 看异常类型
3. 源码级「为什么这样实现」查 `.trellis/spec/backend/poi-bridge.md`（N1–N17）
4. 仍未解决，可能是 nondocx 尚未覆盖的场景 —— 走 `raw()` 或提 issue
