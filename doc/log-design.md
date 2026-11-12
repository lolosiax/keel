# 日志归档与订阅设计

> 状态：设计定稿，尚未实现（P1–P4 见 §9）。
> 目标：日志既能按控制台样式输出，也能被插件以**结构化对象**或**文本**两种方式消费，
> 且日志风暴不得填满事件泵；同时让发行版可以在「控制台应用」与「桌面应用」之间切换。
> 相关：[abi-design.md](abi-design.md)（事件泵与跨运行时约束）、[capability-design.md](capability-design.md)、[../AGENTS.md](../AGENTS.md) 第 4.10 节。

---

## 1. 不变量

1. **Host 是唯一渲染方**。`PLAIN` / `ANSI` 文本由 Host 生成，保证与 Console、`work/log/*.log` 完全一致。
2. **每条日志只存一份结构化数据**。文本按需渲染，不在缓冲区里存三份。
3. **RAW 是记录对象本身，不是 message 文本**。`LogRecord` 保留渲染前的全部字段；`message` 原样保存，Host 不解析、不加前缀、不转义、不截断。
4. **错误同时带结构与文本**。`LogError.chain` 供读详情，`LogError.text` 是完整渲染结果，供没有渲染能力的下游直接使用。
5. **日志正文永不进入插件收件箱**。跨运行时只投「有新日志」的合并唤醒，正文由插件按游标拉取。
6. **id 单调递增、淘汰不复用**。缺口用 `dropped` 标记，不伪造日志。
7. `Throwable`、Kotlin 对象、continuation 一律不跨运行时；异常在入队时就被转换成 `LogError`。

---

## 2. 数据模型（`keel-api`，`moe.lolosia.keel.api.log`）

### 2.1 `LogRecord`

```kotlin
@Serializable
data class LogRecord(
    /** 缓冲区内单调 id，从 1 开始；淘汰后不复用。 */
    val id: Long,
    /** 入队时的墙钟（epoch 毫秒），跨天也能排序。 */
    val timeMillis: Long,
    /** Host 本地时间 `HH:mm:ss`，与控制台显示一致。 */
    val time: String,
    val level: LogLevel,
    /** 已解析的模块名：显式 tag，或 Host / 插件的日志名（manifest `logTag` → `name` → `id`）。 */
    val module: String,
    /** 完整调用点；RAW 不缩短，文本视图渲染时才按列宽压缩。 */
    val site: LogSite? = null,
    /** 自定义来源列（HTTP 的 `ip:port` 等）；纯文本，未着色。 */
    val source: String? = null,
    /** 调用方原样传入的正文；可能是 JSON、SQL、普通句子。 */
    val message: String,
    /** 结构化错误；没有异常时为 null。 */
    val error: LogError? = null,
    /** 横幅这类非标准行：文本视图原样输出，不加时间 / 级别前缀。 */
    val banner: Boolean = false,
    /** 控制台强制模块色（SGR，如 `96`）。只在 Host 进程内使用，不参与序列化。 */
    @Transient val moduleAnsi: String? = null,
    /** 控制台已着色的来源列文本。只在 Host 进程内使用，不参与序列化。 */
    @Transient val sourceAnsi: String? = null,
)
```

`LogSite` 需要加 `@Serializable`（纯加注解，不影响编译器插件生成的构造调用）。

### 2.2 `LogError`

```kotlin
@Serializable
data class LogError(
    /** 外层在前的异常链；复用既有的 KeelErrorEntry / KeelErrorFrame。 */
    val chain: List<KeelErrorEntry> = emptyList(),
    /** 完整渲染文本：诊断行（Created / Throwed / Printed at）+ 折叠后的栈帧，可直接打印。 */
    val text: String = "",
    /** 本条日志的打印点，便于定位「谁打的这条日志」。 */
    val printedAt: String? = null,
    /** 帧数超过上限被截断。 */
    val truncated: Boolean = false,
)
```

为什么要同时保留结构与成品文本：

- `chain` 让下游能做详情面板、按模块分组、按 `type` 过滤，而不是正则拆栈；
- `text` 是**保底字段**：下游 API 可能只接受字符串（写文件、转发到第三方日志服务、旧版 UI），
  没有能力把 frames 渲染成 Java 风格栈，所以对象里必须自带成品文本；
- 两者必须来自同一次渲染：先调 `StackTraceFormat.throwable(error, printedAt)` 得到 `text`
  （它会读取 `ThrowTrace` 侧表），再调 `Throwable.toKeelError()` 得到 `chain`
  （它会 `release` 侧表条目，顺序反了文本里就没有 `Created / Throwed at`）；
- 帧的符号在 `KeelErrorFrame.symbol` 里保留原始形式（`kfun:` / `_Z`），需要可读名时用
  `StackTraceFormat.symbol()` 转换。

### 2.3 分页与文本行

```kotlin
/** PLAIN / ANSI：Host 渲染好的一行。 */
@Serializable
class LogLine(val id: Long, val text: String)

/** 一次拉取的结果；RAW 填 records，文本模式填 lines，两者不会同时出现。 */
@Serializable
class LogPage(
    /** 当前缓冲区最老的 id。 */
    val firstId: Long,
    /** 下一个待分配的 id。 */
    val nextId: Long,
    /** 请求的游标已被淘汰，出现缺口。 */
    val dropped: Boolean = false,
    val records: List<LogRecord> = emptyList(),
    val lines: List<LogLine> = emptyList(),
)

/** 传输层的三种视图；插件 API 用它选类型安全的入口。 */
enum class LogFormat { PLAIN, ANSI, RAW }
```

---

## 3. 环形缓冲区

### 3.1 结构

`keel-host` 的 `LogArchive`：固定数组 + 头指针，追加与淘汰都是 O(1)。

```kotlin
private var slots = arrayOfNulls<LogRecord>(maxRecords)
private var head = 0            // 最老一条
private var count = 0
private var nextId = 1L
private var totalBytes = 0L
```

- `maxRecords` 是主保护（兜住对象头开销），默认 10000；
- `maxMemoryBytes` 是软上限，默认 30 MiB；
- 任一先到就从 `head` 淘汰，直到两条都不超。

### 3.2 容量核算：不扫全量

每条记录入队时**只算一次**成本：

```
cost = 固定开销 + 2 * (time + module + source + message + error.text).length
     + 8                                        // timeMillis
     + 8 * (error.chain 每层: 常量)               // 只计条数，不逐帧扫描
```

- `String.length` 是 O(1)；K/N 的 String 是 UTF-16，1 char ≈ 2 字节，中文按 2 字节计，无需 `encodeToByteArray()`；
- 追加时 `totalBytes += cost`，淘汰时 `totalBytes -= evicted.cost`；
- **从不遍历整个缓冲区重新求和**；
- `error.text` 只在有异常时生成一次（见 §2.2），所以异常日志的成本在入队时就固定下来。

### 3.3 并发

`HostLogSink` 会被 Ktor 线程与事件泵线程同时调用，`LogArchive` 用一把锁保护
「追加 / 淘汰 / 分页读取」；锁实现放 `keel-host` 的 `nativeMain`（Linux `pthread_mutex`、
mingw `CRITICAL_SECTION`），包成 `LogLock`（expect/actual），不在 commonMain 出现平台类型。

订阅表（§6）只在事件泵线程读写，不参与这把锁。

### 3.4 配置

主线 `application.yaml`：

```yaml
log:
  archive:
    maxRecords: 10000
    maxMemoryBytes: 31457280
```

启动顺序：横幅打印前先按默认值建好 `LogArchive`，`HostConfigs` 初始化后再调用
`LogArchive.configure(...)`；缩小时立即按新上限淘汰一次。

---

## 4. 渲染

### 4.1 三种视图

| 模式 | 内容 | 生成方 |
|---|---|---|
| `PLAIN` | `HH:mm:ss 级别 模块 来源 : 消息`，异常另起一段；无 ANSI | Host |
| `ANSI` | 同上 + Spring Boot 风格配色（级别 / 模块 / 来源列） | Host |
| `RAW` | `LogRecord` 本身，不做任何渲染 | —— |

- 复用现有 `LogLineFormatter` 与 `TextWidth`：模块列 15 列、来源列 40 列、CJK 按显示宽度对齐；
- `banner = true` 的记录在 `PLAIN` / `ANSI` 下原样输出 `message`，不加前缀；
- `error.text` 在文本视图里另起一段，与控制台版式一致。

### 4.2 与控制台共用一条路径

`HostLogSink` 改为：**先构造 `LogRecord` → 入环 → 从同一条记录渲染控制台行与文件行**。
这样控制台、日志文件、插件拿到的文本三者逐字节一致，不会再出现「控制台和缓冲区不一样」的问题。

### 4.3 Banner

- `LogSink` 增加默认方法 `fun logRaw(text: String) = log(INFO, null, null, text, null)`；
- `HostLogSink` 覆盖 `logRaw`：构造 `LogRecord(banner = true, message = text, module = "")`，入环并写文件；有控制台时原样 `println`；
- `printBanner` 改为调用 `Log.banner(text)`，于是横幅成为 `id = 1` 的那条记录，三种视图都能取到。

---

## 5. 插件侧 API

### 5.1 函数表

`Log` 是每个运行时各一份的 object：Host 装 `HostLogSource`（直接读本地 `LogArchive`），
插件在 `PluginWorker.enter` 时装 `PluginLogSource`（走事件泵反向请求）。

```kotlin
enum class LogTextFormat { PLAIN, ANSI }

object Log {

    /** 批量拉取文本；startId 语义见 §5.2。 */
    fun history(
        format: LogTextFormat = LogTextFormat.PLAIN,
        startId: Long = 0,
        limit: Int = Int.MAX_VALUE,
    ): List<Pair<Long, String>>

    /** 批量拉取结构化记录（RAW）。 */
    fun records(startId: Long = 0, limit: Int = Int.MAX_VALUE): List<Pair<Long, LogRecord>>

    /** 取单独一条记录；id 已被淘汰或尚未分配时返回 null。 */
    fun get(id: Long): LogRecord?

    /** 取单独一条的渲染文本；语义同 get。 */
    fun getText(id: Long, format: LogTextFormat = LogTextFormat.PLAIN): String?

    /** 订阅文本流。 */
    fun observe(
        format: LogTextFormat = LogTextFormat.PLAIN,
        startId: Long = 0,
    ): Flow<Pair<Long, String>>

    /** 订阅结构化记录流。 */
    fun observeRecords(startId: Long = 0): Flow<Pair<Long, LogRecord>>

    /** 输出一条不经过格式化前缀的原始行（横幅等）。 */
    fun banner(text: String)
}
```

`history` 与 `records` 的 `startId` 语义相同；`get` / `getText` 用显式 id 单取，不参与重放。

### 5.2 `startId`

| startId | 行为 |
|---|---|
| `-1` | 只收订阅之后的新日志，历史为空（cursor 取 `nextId - 1`） |
| `0`（默认） | 从最老一条开始完整重放 |
| `> 0` | 只返回 `id > startId` 的记录 |

id 从 1 开始，`0` 与 `-1` 不会混淆。`get` 的入参是具体 id，命中已淘汰区间返回 null。

### 5.3 Flow 与背压

```kotlin
fun observeRecords(startId: Long): Flow<Pair<Long, LogRecord>> = flow {
    val sub = LogSubscriptions.subscribe(startId)      // callHost(LOG_SUBSCRIBE)
    try {
        var cursor = sub.cursor
        while (true) {                                  // 第一轮：把历史追平
            val page = fetch(cursor, raw = true)         // callHost(LOG_FETCH)
            page.records.forEach { emit(it.id to it) }
            cursor = page.nextId - 1
            if (page.nextId <= sub.cursor) break
        }
        while (true) {                                  // 之后：等唤醒再拉
            sub.awaitWake()                             // Channel<Unit>(CONFLATED).receive()
            while (true) {
                val page = fetch(cursor, raw = true)
                page.records.forEach { emit(it.id to it) }
                cursor = page.nextId - 1
                if (page.nextId <= cursor + 1) break
            }
        }
    } finally {
        sub.unsubscribe()
    }
}
```

- `emit` 下游慢 → Flow 挂起 → 停止 `fetch`，这就是背压；
- `awaitWake` 是挂起，不占 Worker 线程；
- `fetch` 复用 `callHost`，等待回包期间继续分发其它事件，回调 / 定时器不会被饿死；
- wake 用 `Channel<Unit>(CONFLATED)`，重复通知自动合并。

### 5.4 线程与生命周期

- 只能在插件 Worker 线程（或 Host 主线程）调用 / 收集；其它线程没有 inbox，直接失败；
- 插件停用 / 卸载时 Host 端 `LogHub.dropPlugin(pluginId)` 清掉订阅；`pushToPlugin` 失败也立即退订；
- Flow 取消时 `finally` 发 `LOG_UNSUBSCRIBE`，属于通知（correlation = 0）；
- 在收集器里再写 `Log.info` 会造成回环，约定不要这么做（文档里明确写出）。

---

## 6. 跨运行时协议

### 6.1 subject

`HostSubjects` 追加（延续 `0x100x` 段）：

| 常量 | 值 | 方向 | 说明 |
|---|---|---|---|
| `LOG_SUBSCRIBE` | `0x100A` | 插件 → Host | 请求，`{startId}` → `LogSubscribeReply` |
| `LOG_FETCH` | `0x100B` | 插件 → Host | 请求，`LogFetchRequest` → `LogPage` |
| `LOG_UNSUBSCRIBE` | `0x100C` | 插件 → Host | 通知，`{subscriptionId}` |

`keel_abi.h` 追加协议 subject：

```c
/* Host 通知插件：有新日志。无载荷，可合并，只表示「按游标来拉」。 */
#define KEEL_SUBJECT_LOG_WAKE 10u
```

### 6.2 载荷

```kotlin
@Serializable
class LogSubscribeReply(val subscriptionId: Long, val cursor: Long, val firstId: Long, val nextId: Long)

@Serializable
class LogFetchRequest(
    val subscriptionId: Long,
    val cursor: Long,
    val format: LogFormat = LogFormat.PLAIN,
    val maxEntries: Int = 256,
    val maxBytes: Int = 128 * 1024,
)
```

编码沿用 `ServiceWire`（CBOR）。`maxBytes` 按字符串长度估算，Host 侧攒够就提前返回本页。

### 6.3 wake + pull

```
HostLogSink.append(record)
        └─▶ LogHub.markDirty(record)        // 置订阅的 pending = true（合并）
HostPump 每轮结束
        └─▶ LogHub.flushWakes(budget = 8)   // 每个 pending 订阅投 1 条 LOG_WAKE
插件 dispatchEvent
        └─▶ KEEL_SUBJECT_LOG_WAKE → LogSubscriptions.signal()  // CONFLATED
Flow
        └─▶ awaitWake() → LOG_FETCH(cursor) → emit → cursor 前进
```

- `pending` 是布尔标记，**每个订阅在插件 inbox 里最多 1 条 wake**，256 容量不会被日志淹没；
- 每轮最多唤醒 8 个订阅，避免日志风暴时泵线程只顾着投唤醒；
- 插件离线（`pushToPlugin` 返回 false）→ 直接移除订阅；
- 插件长期不消费时只落后，不影响 Host 写入；落后到被淘汰则 `LogPage.dropped = true`。

### 6.4 分页上限

- 每页 ≤ 256 条 / ≤ 128 KiB；
- Host 渲染文本时按当前 `Log.moduleWidth` / `Log.displayNameWidth`；
- `dropped = true` 时记录从 `firstId` 开始，客户端可据此提示「有 N 条日志已被覆盖」。

---

## 7. 控制台应用 / 桌面应用

### 7.1 Gradle DSL

```kotlin
keelPlugin {
    executable {
        enabled = true
        console = true     // 默认 true；false = 桌面应用（Windows GUI 子系统）
    }
}
```

只影响单 exe（`.keel` 不受影响）；`keel-host` 由 K/N 自己链接，仍是控制台子系统。

### 7.2 Windows PE 子系统

单 exe 是「预编译 launcher + 注入资源」得到的，不能靠重新链接改子系统，因此在
`KeelExecutableTask` 注入资源之后直接改 PE 头：

1. `0x3C` 读 `e_lfanew`（4 字节 LE），校验 `PE\0\0`；
2. 跳过 20 字节 COFF 头，进入 Optional Header；
3. 偏移 **68** 处的 `Subsystem`（PE32 与 PE32+ 相同）写 `2`（`WINDOWS_GUI`）或 `3`（`WINDOWS_CONSOLE`）。

`UpdateResourceW` 不会改动 Optional Header，所以放在资源注入之后执行即可。

### 7.3 运行期控制台

- `console = true`：有控制台；被重定向到管道时继续写管道；
- `console = false`：GUI 子系统，启动时按顺序尝试：
  1. `GetConsoleWindow()` 已存在（从 cmd / PowerShell 启动）→ 直接用；
  2. `AttachConsole(ATTACH_PARENT_PROCESS)` 成功 → `freopen("CONOUT$", "w", stdout/stderr)`、
     `SetConsoleOutputCP(CP_UTF8)`；
  3. 都失败（资源管理器双击）→ 保持无控制台，只写文件与环形缓冲区；
  4. 显式 `--console` → 允许 `AllocConsole()` 新建窗口；`--no-console` 禁止自动附着。
- Linux：`console = false` 只表示不启用交互式提示符（`> `），日志照常写 stdout。

参数优先级：`--console` / `--no-console` > `KEEL_CONSOLE` 环境变量 > 构建期 `console` 值。
`keelRun` 默认注入 `KEEL_CONSOLE=1` 与既有的 `FORCE_COLOR=1`，保证 IDE / Gradle 下能看到日志。

无控制台**不影响**日志归档与订阅：插件拿到 `LogRecord` / 文本后自己展示，这正是 RAW 视图的价值。

---

## 8. 改动清单

| 模块 | 改动 |
|---|---|
| `keel-api` | 新增 `log/LogRecord.kt`、`log/LogError.kt`、`log/LogLine.kt`、`log/LogPage.kt`、`log/LogFormat.kt`、`log/LogTextFormat.kt`、`log/LogRecordFormat.kt`（Host / 插件共用的渲染器）、`log/LogHistory.kt` + `NoneLogHistory.kt`；`LogSite` 加 `@Serializable`；`Log` 增加 `history` / `records` / `get` / `getText` / `banner` / `installHistory`（`observe` 在 P2 加）；`LogSink.logRaw` 默认实现；`host/HostSubjects` 追加 3 个 subject |
| `keel-host` | `LogArchive.kt`（环 + 容量核算 + 分页）、`LogHub.kt`（订阅表 / 合并唤醒 / dropPlugin）、`LogLock`（expect + native actual）、`HostLogSource.kt`、`HostLogSink` 改为「建记录 → 渲染」、`HostPump` 处理 3 个 subject 与 `flushWakes`、`HostPlugins.stop` 清理订阅、`HostBootstrap` 改用 `Log.banner`、`currentTimeMillis()` |
| `keel-abi` | `keel_abi.h` 加 `KEEL_SUBJECT_LOG_WAKE`；`keel_console.c` 加 `keel_console_available` / `keel_console_attach` / `keel_console_alloc` |
| `keel-plugin-gradle-plugin` | `KeelExecutableExtension.console`、`KeelExecutableTask` 的 PE 子系统 patch、`KeelBundlePlugin` 透传、`KeelRunTask` 注入 `KEEL_CONSOLE=1` |
| `keel-plugin-hello` | 增加 `observeRecords(startId = -1)` 与 `getText(id)` 的最小示例 |
| `../AGENTS.md` | 第 4.10 节与文档地图指向本文 |

---

## 9. 分阶段与验收

**P1 本地环 + 渲染**
`LogRecord` / `LogError` / `LogArchive` / Banner 入环 / `history` / `records` / `get` / `getText` / `LogRecordFormat`。
验收：10000 条后正确淘汰；中文模块名列对齐；横幅是 `id = 1`；异常日志同时有 `chain` 与 `text`；
`LogArchive` 的追加 / 淘汰不遍历全量。

**P2 跨运行时订阅**
3 个 subject + `LogHub` 合并唤醒 + 插件侧 Flow。
验收：日志风暴下插件 inbox 不溢出；慢消费者只落后不阻塞泵；`-1` 只收新日志、`0` 完整重放；
`dropped` 在有覆盖时置位。

**P3 控制台 / 桌面**
PE 子系统 patch + 运行期 attach/alloc + `keelRun` 默认控制台。
验收：`console = false` 双击不弹窗；从 cmd 启动能看到日志；`--console` 能新建控制台；
`console = false` 时 `observeRecords` 仍能拿到完整日志。

**P4 文档与示例**
`AGENTS.md` 定稿约定（`log.archive`、`console`、订阅规则），`keel-plugin-hello` 示例可运行。

---

## 10. 未决问题

1. **跨插件可见性**：当前所有订阅者共享同一缓冲区，能看到 Host 与其它插件的日志。
   若担心泄露，后续可在 `LogSubscribeReply` 之前加 `moduleFilter` / 权限校验；P2 先按「可见全部」实现并在文档标注。
2. **`maxMemoryBytes` 口径**：本文按 UTF-16 `length * 2` 估算（O(1)、中文准确），
   不含对象头与数组开销；若要求「严格不超 30 MB」，需要把记录编码成 `ByteArray` 存储，读取时解码，代价明显，暂不采用。
3. **结构化 message**：暂无 `Log.info(obj)` 这类重载；需要结构化载荷时由调用方先序列化成字符串
   （RAW 会原样返回），等有明确需求再加 `payload` 字段。
4. **日志文件格式**：`work/log/*.log` 目前写渲染后的纯文本；是否需要同时写 JSONL（便于离线分析）另议。
