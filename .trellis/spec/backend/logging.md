# 日志 (Logging)

> nondocx 何时、如何使用日志。重点是 POI 5.x 带来的依赖陷阱与桥接修法，以及应用层日志规范。
> 库本身 (nondocx-core) 仍以异常为唯一对外错误通道，不引入日志。

---

## 分层原则：库 vs 应用

nondocx 的两个层面日志策略不同：

| 层面 | 模块 | 日志策略 |
|------|------|----------|
| **库 (library)** | `nondocx-core` | **不使用日志**。诊断信息走 `DocxException` 层次（见 [error-handling.md](./error-handling.md)）。库不应替调用方决定日志级别/格式。 |
| **应用 (application)** | `nondocx-demo` | **使用 SLF4J + slf4j-simple**。demo 是可运行 web 应用，需要运行时可观测性。 |

> **为什么库不用日志？** 库被嵌入到不可预测的宿主环境（可能是 CLI、web、批处理、测试）。
> 库里 `log.info(...)` 会污染宿主的日志流，且库无法知道什么级别合适。正确做法：库抛异常，
> 宿主（demo）决定怎么记。这是 nondocx 把"信息走异常"作为 MVP 约定的根本原因。

---

## 依赖陷阱：POI 5.x 的 log4j-api（关键 Gotcha）

### 症状

demo 启动时控制台出现：

```
ERROR StatusLogger Log4j2 could not find a logging implementation.
Please add log4j-core to the classpath. Using SimpleLogger to log to the console...
```

### 根因

POI 5.x 把日志门面从 `commons-logging` 换成了 **`log4j-api`**（Log4j2 门面）。它通过
传递依赖混进 demo classpath：

```
nondocx-demo → nondocx-toolkit → nondocx-core → poi-ooxml
  → poi-ooxml-lite → xmlbeans → log4j-api
```

`log4j-api` **本身只是一个门面**（定义 `LogManager.getLogger()` 等接口），启动时它的
`StatusLogger` 会扫描 classpath 寻找实现（`log4j-core`）。demo 用的是 `slf4j-simple`，
log4j2 找不到自己的实现就报 ERROR，然后降级用它自带的 fallback SimpleLogger
（格式不可控，且与我们业务日志分属两套系统）。

> **注意区分两个 SimpleLogger**：报错里说的 "SimpleLogger" 是 **log4j2 自带的降级 Logger**，
> 不是我们的 `slf4j-simple`。这是两套独立的日志系统。

### 修法：log4j-to-slf4j 桥接

加 `log4j-to-slf4j` 桥接包（替代 log4j-core），把 log4j-api 的调用**转发**给 SLF4J：

```xml
<!-- nondocx-demo/pom.xml -->
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-simple</artifactId>
    <version>2.0.13</version>
</dependency>
<!-- 桥接：POI 传递引入 log4j-api 但无实现 → 启动报 ERROR。
     此包替代 log4j-core，转发到 SLF4J。 -->
<dependency>
    <groupId>org.apache.logging.log4j</groupId>
    <artifactId>log4j-to-slf4j</artifactId>
    <version>${log4j-api.version}</version>  <!-- 跟随父 pom BOM -->
    <scope>runtime</scope>
</dependency>
```

桥接后的完整链路：

```
POI ──→ log4j-api ──→ log4j-to-slf4j ──→ slf4j-api ──→ slf4j-simple（统一出口）
我们业务代码 ─────────────────────────→ slf4j-api ──→ slf4j-simple
```

所有日志（POI 内部 + 业务代码）统一走 slf4j-simple 一个出口，受同一份
`simplelogger.properties` 控制。

### 验证要点

- classpath 里**不能有 log4j-core**——否则与桥接包冲突，抛
  `log4j-slf4j-impl` / `slf4j` 绑定冲突。
- `mvn dependency:tree | grep log4j-core` 应返回 0 行。

---

## 日志配置约定

### 文件位置

`nondocx-demo/src/main/resources/simplelogger.properties`（classpath 根，slf4j-simple 启动自动读）。

### 分级约定

| 级别 | 用途 | 示例 |
|------|------|------|
| **`info`** | 业务关键节点 | 启动 banner、收到对话、编排完成、save 结果、上传/重置文档 |
| **`debug`** | 编排细节 | OO 拉取、LLM 规划开始、bumpKey |
| **`warn`** | 可恢复异常 | LLM 调用失败返回空 plan、JSON 解析失败 |
| **`error`** | 不可恢复异常 | 编排异常、保存失败 |

### 按 logger 名降噪

Javalin / Jetty 的 INFO（路由注册、请求行）是噪音。在 `simplelogger.properties` 里压低：

```properties
org.slf4j.simpleLogger.log.com.non.docx.demo=debug
org.slf4j.simpleLogger.log.io.javalin=warn
org.slf4j.simpleLogger.log.org.eclipse.jetty=warn
```

---

## 应用层规范（demo 专属）

### Convention: 静默吞异常是反模式

**What**: 不要写 `catch (...) ignored` 或 `catch (...) {}` 把异常彻底吞掉。

**Why**: demo 里有大量 LLM 调用、JSON 解析、SSE 流输出，这些都是易错路径。静默吞掉后，
故障完全不可见——LLM 返回畸形 JSON 时用户只看到空结果，开发者无从排查。

**Fix**: 至少 `log.warn(...)` 记录原因。即使要返回降级结果（空 plan），也要留痕：

```java
// WRONG — 故障完全不可见
try {
    llmOutput = llm.chat(...);
} catch (RuntimeException ignored) {
    return "{\"operations\":[]}";
}

// CORRECT — 降级但留痕，可排查
try {
    llmOutput = llm.chat(...);
} catch (RuntimeException e) {
    log.warn("LLM 调用失败,返回空 plan: {}", rootMessage(e));
    return "{\"operations\":[]}";
}
```

**与 library 层的区别**：[error-handling.md Rule 4](./error-handling.md) 规定库代码
"never swallow, never return null on error"——库必须抛异常。应用层（demo）有时需要**降级
而非崩溃**（LLM 失败不应让整个 web 服务挂掉），但降级 ≠ 静默，必须有日志。

### Convention: System.out.println 只用于 main 入口 banner

**What**: 业务逻辑用 SLF4J Logger，不用 `System.out.println`。

**Why**: `println` 不受级别控制、不带时间戳/类名、无法降噪。启动 banner 作为一次性的
人类可读横幅可接受，但所有运行期日志必须走 Logger。

---

## 历史脉络

- **MVP 期**：nondocx-core 不引入日志框架，诊断走异常。这是 library 层的正确选择。
- **demo 落地期 (2026-07)**：demo 作为可运行应用首次引入日志（slf4j-simple），并踩到
  POI 5.x log4j-api 陷阱，以 `log4j-to-slf4j` 桥接收尾。本 spec 随此建立。
- **未来**：若 nondocx-core 库本身需要内部诊断日志，应在那时新增 spec 讨论（可能用
  SLF4J-api-only，把实现选择留给宿主）。

---

**Language**: 本规范及所有对外代码工件均使用**中文**编写。
