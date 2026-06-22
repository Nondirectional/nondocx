# 08 · 异常与 `raw()` 领地

nondocx 的错误处理有两套规则，**分别对应两条调用路径**：

1. **普通公开方法** —— POI 异常包装成 `DocxException` 家族
2. **`raw()` 路径** —— POI 异常原样传播，nondocx 不插手

这一篇讲清楚两条路径的边界、异常层级、以及怎么写健壮的调用代码。

> 这两套规则在 [02 架构](./02-architecture.md#3-raw-逃生舱--唯一的-poi-出口) 已立契约，这里展开实战细节。

---

## 1. 异常层级

```
RuntimeException                          ← Java 标准库
└── DocxException                         ← nondocx 公开 API 的单一根类型（unchecked）
    ├── DocxIOException                   ← 文件/流读写失败
    ├── DocxFormatException               ← 内容不是有效 docx
    ├── DocxOperationException            ← 语义层错误（索引越界、前置条件不满足）
    └── UnsupportedFeatureException       ← 显式声明某特性未封装，指向 raw()
```

**四个设计决定**：

| 决定 | 说明 |
|---|---|
| **全 unchecked** | 所有异常继承 `RuntimeException`，不强制 `catch`/`throws`。想统一处理时只需 `catch (DocxException e)` |
| **单一根类型** | `DocxException` 是广泛处理 nondocx 失败的**唯一**类型。`catch (DocxException e)` 一网打尽 |
| **POI 异常在公开表面永不裸露** | 除 `raw()` 路径外，POI 的 `IOException`/`POIXMLException` 等都被包装进上面某一种（cause 保留） |
| **消息全中文** | 与代码注释、Javadoc 一致，所有异常消息均为中文，常带上下文 |

---

## 2. 四个子类详解

### `DocxIOException` —— IO 失败

文件读不了、流写不出去、磁盘满等。

```java
public DocxIOException(String message, Throwable cause)
```

携带 cause（通常是被包装的 `java.io.IOException`）。典型来源：

- `Docx.open(path)` 文件缺失/不可读
- `doc.save(path)` 目标路径不可写
- `doc.close()` 关闭底层资源失败

### `DocxFormatException` —— 格式无效

源存在、能读到字节，但**不是有效 docx**。

```java
public DocxFormatException(String message, String path)
public DocxFormatException(String message, String path, Throwable cause)
public String getPath()    // 出问题的源路径，可能为 null（如 InputStream 来源）
```

携带 `path`（源路径），方便定位。典型来源：

- `Docx.open(...)` 收到一个 `.txt` 或损坏的 zip
- POI 在解析 `XWPFDocument` 时抛 `POIXMLException` / `NotOfficeXmlFileException`

### `DocxOperationException` —— 语义层错误

参数或状态不符合操作前置条件。

```java
public DocxOperationException(String message)
public DocxOperationException(String message, String context)
public String getContext()    // 上下文描述，可能为 null
```

典型来源：

- 索引越界：`doc.paragraph(99)`（只有 3 段）
- 前置条件不满足：`addDeletion(author, runNotInThisParagraph)`
- 操作冲突：cell 类 accept/reject 命中祖父节点不是 `<w:tc>`

### `UnsupportedFeatureException` —— 不支持的特性

**最特殊的子类**。它**不是 bug**，是 nondocx 主动声明「这个能力没封装，请走 `raw()`」。

```java
public UnsupportedFeatureException(String message)    // 消息会指引走 raw()
```

典型场景：

- 对属性类/cell 类修订调 `raw()`（[05/02](./05-tracked-changes/02-read-and-query.md)）
- 对 cellMerge 调 `acceptCell`/`rejectCell`（[05/03](./05-tracked-changes/03-accept-reject.md#5-cell-类专用方法--作用于整个-wtc-祖父)）
- 触碰到尚未封装的特性（域、OLE、公式、水印、形状等）

> 「不支持」是 nondocx 的**诚实答案**。绝不会静默返回空列表装作没事（见 §4）。

---

## 3. 怎么写健壮的调用代码

### 最简单：一个 catch

```java
try {
    try (Document doc = Docx.open(path)) {
        doc.paragraph(0).run(0).text("new");
        doc.save(outPath);
    }
} catch (DocxException e) {
    // 涵盖 IO、格式、操作、不支持
    log.error("处理 docx 失败: {}", e.getMessage(), e);
}
```

这是最常见写法 —— `DocxException` 一网打尽，因为对调用方而言「为什么失败」往往只用于日志，不驱动重试逻辑。

### 需要区分类型时

```java
try {
    try (Document doc = Docx.open(path)) {
        // ...
    }
} catch (DocxFormatException e) {
    // 用户传了非 docx 文件 → 提示「请上传 .docx」
    return "请上传有效的 .docx 文件（" + e.getPath() + "）";
} catch (DocxIOException e) {
    // 文件读不了/写不出 → 可重试或提示权限
    return "文件读写失败: " + e.getMessage();
} catch (DocxOperationException e) {
    // 索引越界等 → 通常是调用方 bug，不该静默
    throw e;
} catch (UnsupportedFeatureException e) {
    // 文档用了未封装的特性 → 引导用户简化文档或走 raw
    return "该文档使用了 nondocx 暂不支持的特性: " + e.getMessage();
}
```

> 子类 catch 的顺序：**子类在前、父类在后**。`UnsupportedFeatureException`/`DocxFormatException` 等都是 `DocxException` 的子类，先 catch 父类会让子类 catch 块变成 unreachable（编译错误）。

### 注意：不强制 catch

因为全 unchecked，**编译器不会提醒你处理**。这意味着：

- 库方法签名干净，没有 `throws DocxException`
- 调用方**有责任**判断是否需要 catch（通常只在边界层 catch：HTTP handler、CLI 入口、Agent 工具方法）

---

## 4. `raw()` 领地 —— 另一条路径

走 `raw()` 后，规则完全不同：

```java
try (Document doc = Docx.open(path)) {
    XWPFDocument raw = doc.raw();
    // ↓ 通过 raw 调 POI 方法，抛的 POI 异常原样传播
    raw.createTable().addNewCol();   // 假设这是某个 POI 方法
} catch (org.apache.poi.ooxml.POIXMLException e) {
    // ⚠️ POI 异常裸露 —— DocxException 的 catch 接不住
}
```

### 三条领地规则

| 规则 | 含义 |
|---|---|
| **POI 异常不包装** | 你选了 `raw()`，就是选了 POI 的行为，POI 的异常（`IOException`/`POIXMLException`/`XmlValueDisconnectedException` 等）原样传播 |
| **`DocxException` catch 接不住** | POI 异常**不是** `DocxException` 的子类。混合代码要分别 catch |
| **规则由"调用点"决定，不是"调用者"** | 即使同一段代码里，只要那一行是 `doc.paragraph(0)` 走的就是包装路径；那一行是 `doc.raw().createParagraph()` 走的就是 POI 路径 |

### 混合代码的写法

```java
try (Document doc = Docx.open(path)) {
    // 包装路径：DocxException
    doc.paragraph(0).run(0).bold();

    // raw 路径：POI 异常原样
    org.apache.poi.xwpf.usermodel.XWPFDocument raw = doc.raw();
    raw.getSettings().setTrackRevisions(true);
} catch (DocxException e) {
    // 接包装路径的失败
    log.error("nondocx 失败", e);
} catch (RuntimeException e) {
    // ⚠️ raw 路径的 POI 异常只能用更宽的 catch 兜底
    // (POI 异常都是 RuntimeException 的子类，但不是 DocxException)
    log.error("POI/raw 失败", e);
}
```

> 顺序：`DocxException` 在前（更具体），`RuntimeException` 在后（更宽）。这是处理混合代码的标准模式。

---

## 5. 为什么这个不对称是有意的

回顾 [02 架构](./02-architecture.md#3-raw-逃生舱--唯一的-poi-出口) 的对比表：

| 调用点 | POI 异常处理 |
|---|---|
| 普通公开方法 | **包装**进 `DocxException` 家族 |
| `raw()` 返回值上的调用 | **不包装**，原样传播 |

这个不对称的**意图**：

- **在 wrapper 内部**，nondocx 拥有抽象，负责给你干净的异常 —— 让调用方代码不被 POI 的实现细节污染
- **在 `raw()` 路径**，你**主动跳进 POI 的领地**，POI 的行为对你原样生效 —— 因为你已经放弃了 nondocx 的抽象，直接操作底层

nondocx 不会假装能包装 POI 所有行为（POI 有大量与 OOXML 直觉不符的实现细节，见 `.trellis/spec/backend/poi-bridge.md` N1–N17）。`raw()` 是诚实的逃生舱，不是「包装失败时的兜底」。

---

## 6. 不支持 ≠ 静默失败

碰到 nondocx 没封装的特性，**绝**不会：

- ❌ 返回空列表装作没东西（静默降级）
- ❌ 返回 null 让调用方踩坑
- ❌ 半包一个带 TODO 的东西

只有两条诚实出路：

1. **走 `raw()`** —— 永远允许，自己用 POI 处理
2. **抛 `UnsupportedFeatureException`** —— 某些主动检查点（如属性/cell 修订的 `raw()`、cellMerge 的 accept/reject）会显式抛，消息指明走 `raw()`

**调用方判断「不支持」的正确姿势**：

```java
// 不要靠返回值猜（nondocx 不会用空列表表示不支持）
List<X> results = doc.someMethod();
if (results.isEmpty()) { ... }   // ⚠️ 这只说明"没有"，不说明"不支持"

// 而是靠异常类型判断
try {
    tc.acceptCell(cellMergeId);
} catch (UnsupportedFeatureException e) {
    // 明确的不支持，按需走 raw()
    handleViaRaw(cellMergeId);
}
```

---

## 7. 一张速查表：异常从哪来

| 异常 | 典型触发点 | cause | 上下文字段 |
|---|---|---|---|
| `DocxIOException` | 文件读写失败 | ✅ 有（`IOException`） | — |
| `DocxFormatException` | 非 docx 内容 | 可选 | `getPath()` |
| `DocxOperationException` | 索引越界、前置条件 | — | `getContext()` |
| `UnsupportedFeatureException` | 主动声明不支持 | — | 消息本身指引 `raw()` |
| （POI 异常裸露） | `raw()` 路径 | — | POI 自带 |

---

## 下一步

- 遇到具体边界问题 → [09 · FAQ 与已知边界](./09-faq-and-boundaries.md)
- 想看哪些特性走 `raw()` → [02 · 架构 §raw](./02-architecture.md#3-raw-逃生舱--唯一的-poi-出口)
- 修订场景的不支持列表 → [05/03 §7](./05-tracked-changes/03-accept-reject.md#7-异常契约一览)
