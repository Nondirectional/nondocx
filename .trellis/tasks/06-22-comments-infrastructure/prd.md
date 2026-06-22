# PRD — comments 基础设施（people.xml / paraId / RSID）

> 父任务：`06-22-comments`（planning）。
>
> **背景**：前序子任务已交付读 + 创作单条 + 回复线程。本子任务补齐**现代 Word 兼容基础设施**——让 nondocx 产出的批注在 Word 里显示完整协作元数据（@mention、paraId 身份、RSID 合并追踪）。
>
> **对照定位**：`06-18-tracked-changes-cell-types`（结构性收尾，补齐前面子任务遗留的结构完整性）。

## Goal

交付三项基础设施自动注入：

1. **people.xml** —— 把批注 author 注册为 `w15:person`，Word 用它做 @mention 提示。
2. **w14:paraId 注入** —— 给每个新批注（及其段落）分配唯一 paraId，线程关系靠它。
3. **RSID 注入** —— 给批注创作路径产出的节点标 `w:rsidR` / `w:rsidRDefault`，Word 用它做「合并文档」差异追踪。
4. **w16du:dateUtc** —— 跨时区时间戳（可选，若子任务 3 未做）。

## User Value

用户感知层面：nondocx 产出的批注在 Word 的「审阅」面板里显示完整作者信息（带头像占位、@mention 可点），而非「未知作者」。RSID 让多份 nondocx 产出的文档在 Word「合并修订」时能正确对齐。

**注意**：这些是「锦上添花」的现代兼容性元数据——缺了它们批注仍能用（子任务 1-3 已保证基本可用），但 Word 体验打折。

## Confirmed Facts（已实测 POI 5.2.5）

OOXML 现代批注基础设施（来自 docx skill `document.py` 探针）：

```
people.xml (w15 命名空间):
  <w15:people>
    <w15:person w15:author="审阅者甲">
      <w15:presenceInfo w15:providerId="None" w15:userId="审阅者甲"/>
    </w15:person>
  </w15:people>

批注段落里的 paraId:
  <w:p w14:paraId="0A1B2C3D" w14:textId="77777777">  ← w14 命名空间
    ...
  </w:p>

RSID (settings.xml + 节点属性):
  <w:settings>
    <w:rsids>
      <w:rsidRoot w:val="07DC5ECB"/>
      <w:rsid w:val="07DC5ECB"/>
    </w:rsids>
  </w:settings>
  <w:p w:rsidR="07DC5ECB" w:rsidRDefault="07DC5ECB" ...>  ← 节点级
```

POI 5.2.5 能力：

| 能力 | POI 支持 | nondocx 自维护 |
|---|---|---|
| people.xml part | ❌ 无 Java 类（schema 在 lite jar） | XmlCursor 拼 |
| people.xml relationship + Content_Types | ❌ | 自注册 |
| w14:paraId 属性 | ❌ 无自动注入 | 创作 + 回复时手动 setAttribute |
| w14 命名空间声明 | ❌ 需手动加到 root | setAttribute 前确保 xmlns:w14 |
| RSID（settings.xml rsids 段） | ❌ 无便捷 API | XmlCursor 操作 settings.xml |
| RSID（节点级 w:rsidR） | ❌ | 创作 + 回复时 setAttribute |
| w16du:dateUtc | ❌ | 同 paraId |

## Requirements

### R1. people.xml 自动维护

- [ ] **R1.1** 首次添加批注（子任务 2 的 `addComment`）时，自动创建 `word/people.xml`（若不存在）。
- [ ] **R1.2** 把 author 注册为 `w15:person`（幂等，已存在不重复加）。
- [ ] **R1.3** 注册 people.xml 的 Content_Types Override + document.xml.rels Relationship。
- [ ] **R1.4** 确保根元素声明 `xmlns:w15` 命名空间。

### R2. w14:paraId 注入

- [ ] **R2.1** 创作批注（含回复）时，给批注内的每个 `<w:p>` 分配随机 8 位 hex paraId（约束 `< 0x7FFFFFFF`，OOXML spec）。
- [ ] **R2.2** 确保根元素声明 `xmlns:w14` 命名空间。
- [ ] **R2.3** 回复场景（子任务 3）若已依赖 paraId，本子任务把生成逻辑收敛到 `CommentNodes` 统一 helper。

### R3. RSID 注入

- [ ] **R3.1** `Document` 持有一个进程内 RSID（构造时随机生成 8 位 hex），所有批注创作路径产出的节点标该 RSID。
- [ ] **R3.2** settings.xml 的 `<w:rsids>` 段注册该 RSID（幂等）。
- [ ] **R3.3** 批注创作产出的 `<w:p>` 标 `w:rsidR` / `w:rsidRDefault`，`<w:r>` 标 `w:rsidR`。

### R4. w16du:dateUtc（可选）

- [ ] **R4.1** 若子任务 3 未做，本子任务给批注的 `w:ins`/`w:del`（若有）标 `w16du:dateUtc`（与 `w:date` 同值，UTC 时间戳）。
- [ ] **R4.2** 确保根元素声明 `xmlns:w16du` 命名空间。

### R5. 一致性约束

- [ ] 所有注入逻辑集中在 `internal/poi/CommentNodes`（或新增 `internal/poi/AuthoringInfra`），公共 API 无感。
- [ ] 注入幂等：重复调用不破坏结构（对照 docx skill `_inject_attributes_to_nodes` 的设计）。
- [ ] 注入失败（如 settings.xml 缺失）防御式降级，不阻断主创作流程。

## Acceptance Criteria

- [ ] AC1 创作批注后 unzip 产物，`word/people.xml` 存在且含 author 条目。
- [ ] AC2 批注内段落有 `w14:paraId` 唯一属性。
- [ ] AC3 批注创作产出的 `<w:p>`/`<w:r>` 有 RSID 属性；settings.xml 的 rsids 段含该 RSID。
- [ ] AC4 在 Word 打开 nondocx 产出的批注文档，审阅面板显示 author（而非「未知」），人工验收。
- [ ] AC5 注入幂等：连续两次 `addComment` 同 author，people.xml 只一条 person 条目。
- [ ] AC6 既有 tracked-changes 创作路径**不**回溯加这些基础设施（父任务 R3/Q4 约束，避免改动稳定包）。

## Out of Scope

- **回溯补到 tracked-changes 创作路径** —— 父任务 Q4 明确：基础设施仅作用于 comments 路径，tracked-changes 留 future（避免改动已稳定的 track 包）。
- **presenceInfo 的真实 providerId/userId** —— 本子任务用占位 `providerId="None"`（docx skill 同款），不做真实身份服务集成。
- **w16cex / w16cid 命名空间** —— 子任务 3 的 commentsExtensible.xml 已涉及，本子任务不重复。

## Open Questions（design.md 收敛）

- **Q1**：RSID 是 `Document` 级单例（一个文档一个 RSID），还是每次创作生成新 RSID？docx skill 用单例（`self.rsid`），倾向单例——Word 的 RSID 语义是「同一编辑会话」标记，一个文档一个 RSID 合理。
- **Q2**：paraId 生成器是否需要全局唯一性检查（扫描已有 paraId 避免冲突）？docx skill 用纯随机不查重；8 位 hex 空间大，冲突概率极低，倾向不查重。
- **Q3**：people.xml 的 author 去重——docx skill 用精确字符串匹配；nondocx 沿用，不做 normalize（如大小写/空格）。
- **Q4**：基础设施注入是作为 `CommentNodes` 的内部步骤，还是抽成独立 `AuthoringInfra` 类？倾向抽独立类（`internal/poi/AuthoringInfra`），便于未来 tracked-changes 也复用。
