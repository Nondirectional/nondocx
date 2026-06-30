# Research — 单元格结构类修订的真实 OOXML 形态

> 本文件是 cell 子任务「先研究,再实现」(implement.md §1 Step 1-2)的产出。
> 用一次性探针 `CellTypesProbeTest` 捕获真实 XML 与 accept/reject 手术结果(探针已删除),据本结论定实现范围。

## 1. javap 实测:CT 类型在 POI 5.2.5 精简 jar 内的可达性

| CT 类型 / 访问器 | 在 lite 5.2.5 jar 内 | 说明 |
|---|---|---|
| `CTTcPr.getCellIns()` / `getCellDel()` | ✅ 已在 | 返回 `CTTrackChange`(与 property 类同委托)。定义在 `CTTcPrInner`(被 `CTTcPr` 继承)。 |
| `CTTrackChange` / `CTMarkup` | ✅ 已在 | `CTTrackChange` 给 author/date,继承 `CTMarkup` 给 id。 |
| `CTCellMergeTrackChange` | ❌ **缺失** | 被 `CTTcPr.getCellMerge()` 在签名里引用,但类文件不在 jar 内。**编译期不可达**——`tcPr.getCellMerge()` 这一行连 javac 都过不了(报「无法访问 CTCellMergeTrackChange」),比运行期 NoClassDefFoundError 更强的证据。 |
| `CTTcPrChange` | ❌ 缺失 | 同 cellMerge 的 dangling 模式。 |
| `CTPPrChange` / `CTSectPrChange` / `CTTblPrChange` / `CTTrPrChange` | ❌ 全缺 | pPrChange 等更高层属性类全部受阻,移出本子任务。 |

**关键发现——POI lite 的 dangling reference 生成模式**:CT 接口可以声明返回某个类型,但该类型的 class 文件**不在** lite jar 内(只有 POI 自身运行时调用到的类才被保留进 lite jar)。因此「某 CT 类型是否可达」**必须 `unzip -l` / `javap` 实测**,不能只看接口声明。这正是 spec N16 要记录的核心知识点。

## 2. cellIns / cellDel 的真实 OOXML 形态(探针 dump 确认)

探针构造 `tc.addNewTcPr().addNewCellIns()` 后 dump 的原始 XML:

```xml
<w:tc>
  <w:tcPr>
    <w:cellIns w:id="1" w:author="non"/>
  </w:tcPr>
  <w:p><w:r><w:t>单元格内容</w:t></w:r></w:p>
</w:tc>
```

**结论**(与 design §1.2 一致):
- `cellIns` / `cellDel` 嵌在 `<w:tcPr>`(单元格属性)内,是**裸属性元素**(只有 id/author/date,无 run、无文本)。
- 标记的是「**这个单元格(`tc`)本身是被插入/删除的**」——表格结构修订,不是文本内容修订。
- `CTTrackChange` 类型与 property 类(`rPrChange` → `CTRPrChange` → `CTTrackChange`)的**委托类型完全相同**,可复用 `TrackedChange` 已有的持 `CTTrackChange` 委托的构造函数与 `propertyNode()` 接缝。

## 3. accept / reject 手术结果(探针验证)

### 3.1 accept cellIns(删标记、保留 tc)

探针在 cellIns 节点上 `removeXml()` 后:
- `tc.isSetTcPr()` → **true**(tcPr 仍在)。
- `tc.getTcPr().isSetCellIns()` → **false**(标记已删)。
- 单元格内的段落/run 完整保留。

### 3.2 reject cellIns(移除整个 tc)

探针从 cellIns 节点开 cursor,`toParent()×2` 后 dump cursor 本地名 → **`tc`**(确认到达祖父 `<w:tc>`,不是 `tr` 或 `tcPr`),再 `removeXml()`:
- 行内 tc 数从 **2 → 1**(整个 tc 子树消失,含其内段落/run/标记)。

**结论**:`toParent()×2` 从 cellIns 准确到达祖父 `tc`,accept/reject 的 XmlCursor 手术可行且语义正确。cellDel 的 accept/reject 与 cellIns 对称(accept 移除 tc、reject 保留 tc)。

## 4. cellMerge 的可达性结论

`CTCellMergeTrackChange` **编译期就不可达**——不是运行期才抛 NoClassDefFoundError,而是 javac 直接拒绝(`tcPr.getCellMerge()` 这行写出来就编译失败)。因此 cellMerge:
- **读**:只能走 XmlCursor 在 `tcPr` 子里按本地名 `cellMerge` 探测。
- **accept/reject**:抛 `UnsupportedFeatureException`(合并/拆分涉及相邻单元格 vMerge 恢复,结构风险高)。

## 5. 实现范围据此收敛

- **cellIns / cellDel**:完整 read(typed `getCellIns()`/`getCellDel()`)+ accept/reject(XmlCursor 作用于整个 `tc`)。复用 property 类的 `CTTrackChange` 委托,零新 CT 类型。
- **cellMerge**:只读(XmlCursor),accept/reject 抛 `UnsupportedFeatureException`。
- **pPrChange 等**:CT 类型全缺,移出本子任务。

## 6. 探针删除

`CellTypesProbeTest` 是研究一次性产物,结论已落入本文件。按「探针先于生产代码、确认后删除」的项目惯例(同 advanced-types 的 `AdvancedTypesProbeTest`),实现阶段开始前删除。
