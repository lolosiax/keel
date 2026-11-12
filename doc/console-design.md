# 控制台窗口插件设计（keel-plugin-console）

> 状态：设计稿，未实现。
> 目标：新增 `keel-plugin-console`，用 Win32 拉一个**独立控制台窗口**，打开时从日志归档里取出
> 带色日志全量回放，之后按 `Log.observe` 的 Flow 实时跟随；其他插件通过 Messenger 的
> `post` / `request` 开关窗口、查询状态；**不新增 `-api` 契约工件**。
> 相关：[log-design.md](log-design.md)（日志归档与订阅）、[abi-design.md](abi-design.md)（事件泵与跨运行时）、
> [../AGENTS.md](../AGENTS.md)（插件工程与代码风格）。

---

## 1. 结论

| 决策 | 选择 | 理由 |
| --- | --- | --- |
| 窗口归属 | 子进程 helper（`CREATE_NEW_CONSOLE`） | 一个进程只能有一个控制台；Host 已有控制台时 `AllocConsole` 直接失败，且关窗的 `CTRL_CLOSE_EVENT` 会终止整个进程 |
| 插件 ↔ helper 通道 | 两条**单向命名管道**（out / in）+ 长度前缀 RPC 帧 | 不用 helper 的 stdin/stdout：`CREATE_NEW_CONSOLE` 与 `STARTF_USESTDHANDLES` 组合行为不稳定，还会把 std 句柄从控制台挪走。不用一条双工管道：同步句柄上并发读写的语义不可靠，阻塞中的读会把写卡住 |
| 帧内载荷 | 原始终端字节 | helper 不解析 ANSI，直接交给 conhost 渲染，SGR / CSI / 输入行规程原样保留；扩展只加帧类型 |
| 日志来源 | `Log.observe(LogTextFormat.ANSI, startId = 0)` | Host 是唯一渲染方，颜色与 `plugin.json` 的 `color`、列宽完全一致；RAW 在插件侧渲染会退化成哈希色（见 §7.2） |
| 控制面 | 补完已声明的 `Messenger` | 接口语义（`post` / `request`）正好是「普通消息」，不需要 `-api` 工件；插件新增 `onMessage` 接收钩子 |
| 标题 | `TITLE` 帧 + 插件运行期单例保存 | 默认 `Keel Console`；被其他插件改过之后关窗 / 重开 / 插件重载都保留，见 §7.6 |
| 窗口输入 | 逐行转发给 Host 的 stdin 接收器 | helper 行模式收整行 → `IN` 帧 → 插件 `ctx.host.post(CONSOLE_INPUT)` → 与真实 stdin 同一条环形缓冲，由 Host 原有命令分发执行（§7.4） |
| helper 图标与版本资源 | RC 里写 `Keel Console` + keel 程序图标 | 资源管理器 / 任务栏看到的是 helper 自己的 PE 资源，与运行期标题互不影响 |
| 代码位置 | `keel/keel-plugin-console/` | 与 `keel-plugin-hello` 同级；Messenger 的框架改动与插件必须一起验证 |

---

## 2. 不变量

1. **窗口归 helper 进程所有**。Host 的 stdout、控制台与 Process 级 std 句柄都不受影响，插件也不去
   `freopen` / `AllocConsole`。
2. **插件不直接写窗口**：所有输出编成帧交给 helper，由 helper 写 `CONOUT$`。
3. **Worker 线程不做可能阻塞的 I/O**：日志 collect 只入队；写管道在插件自己的后台协程里。
4. **窗口状态只在 Worker 线程迁移**；后台线程只投事件，不改状态。
5. **不留孤儿**：关窗、插件停用、Host 崩溃三种情况下，窗口都不能继续存活。
6. **窗口内容不参与二次归档**：collect 里不调用 `Log.*`，否则形成「日志 → 唤醒 → 拉取 → 再打日志」回环。
7. **无窗口是一种正常状态**：helper 缺失、窗口被用户关掉、创建失败都可恢复，不是错误退出。

---

## 3. 平台约束

### 3.1 Windows 单控制台

- 一个进程最多附着到一个控制台。`GetConsoleWindow() != NULL` 时 `AllocConsole` 返回失败；
  `keelRun` / IDE / 终端启动的 Host 几乎总是已经有控制台。
- 关闭控制台窗口会给附着进程发 `CTRL_CLOSE_EVENT`，默认终止进程。若在 Host 进程内开窗，
  用户点 X 就等于杀掉整个服务；`FreeConsole` + 返回 TRUE 的技巧又会把 Host 自己的控制台拆掉。
- 因此窗口由子进程持有：关窗只死 helper，插件下次写入拿到 broken pipe 就把状态降为 `closed`，
  随时可以再开。

### 3.2 两个自主运行时

Host 与插件是两套 Kotlin/Native 运行时、两套 CRT（所以 `keel_event_release_default` 每个二进制各一份）。

- 插件**修不了** Host 的 `stdout` / FILE 句柄；
- 反过来 Host 也看不见 helper 的管道；
- 两边只能通过事件泵与管道字节交互。

顺带一个有利事实：`HostLogSink.useConsole` 在 `object` 初始化时就固定了
（[HostLogSink.kt](../keel-host/src/commonMain/kotlin/moe/lolosia/keel/host/HostLogSink.kt)），
所以插件事后开窗不会让 Host 往同一个窗口重复输出。

### 3.3 目标平台

插件只打 `mingwX64`，`plugin.json.lib` 也只有 `mingwX64`。Linux 上没有「控制台窗口」这个概念，
不部署这个插件；如果以后需要，再加一个 expect/actual 的空实现，`open` 直接回
`failed:windows-only`。

---

## 4. 结构

```
                 ┌──────────────── Host 进程 ────────────────┐
 hello / 其他插件 │  keel-plugin-console (dll)                │
   post ──────────┼─▶ Messenger ─▶ HostPump ─▶ inbox ─▶ Plugin.onMessage
                 │                                   │       │
                 │                                   │       ├─ 状态机（Worker）
                 │  LogArchive ─▶ LOG_FETCH ◀────────┼───────┘
                 │       ▲                            │
                 │   HostLogSink                       │  有界队列
                 │                                     ▼
                 │                              Dispatchers.Default 写协程
                 └──────────────────────────────┬───────┘
                                                │ 命名管道（RPC 帧）
                 ┌──────────────────────────────▼───────┐
                 │ helper（独立进程，CREATE_NEW_CONSOLE）│
                 │  CONOUT$ ◀── OUT 帧                   │
                 │  CONIN$  ──▶ IN / RESIZE 帧           │
                 └───────────────────────────────────────┘
```

---

## 5. Messenger 补全

### 5.1 API 面（keel-api）

```kotlin
@Serializable
data class Message(
    /** 目标插件 manifest id（`plugin.json.id`），如 `console`。 */
    val target: String,
    /** 自由字符串；Host 不解释。 */
    val channel: String,
    val payload: ByteArray,
    /** 接收方看到的发送方 manifest id；发送方留空，由 Host 填。 */
    val source: String = "",
)

interface Messenger {
    /** 单向通知，at-most-once；目标离线只告警，不抛异常。 */
    fun post(message: Message)

    /** 请求 / 应答；只在 Worker 线程调用，等待期间继续分发其它事件。 */
    suspend fun request(message: Message, timeoutMs: Long = 3_000): ByteArray
}
```

相对现有桩（`messaging/Messenger.kt`）的调整：

- 删掉 `correlationId: String?`：配对由 Host 的数字 `correlation` 完成，应用层不再保留一个没有消费者的字段。
- `request` 返回 `ByteArray` 而不是 `Message`：应答只有载荷有意义，`target` / `channel` 在回包里是噪音。
- `Message` 加 `@Serializable`，走 `ServiceWire`（CBOR），**不用 JSON**（AGENTS 热路径约定）。

接收钩子加在 `Plugin` 上，与 `onCommand` 同构：

```kotlin
/**
 * 收到其他插件的消息；返回应答载荷，`null` 表示未处理。
 * 只在 Worker 线程调用；内部再发起 request 会重入事件泵，按需使用。
 */
fun onMessage(message: Message): ByteArray? = null
```

默认实现让所有存量插件零改动编译。

入口位置沿用 `ctx.server.messenger`（`KeelServer` 已声明），不再往 `PluginContext` 加第二份。

### 5.2 ABI 与 subject

| 常量 | 值 | 方向 | 说明 |
| --- | --- | --- | --- |
| `KEEL_SUBJECT_MESSAGE` | `11u` | Host → 插件 | 载荷是 CBOR(`Message`)；`correlation == 0` 表示通知，不需要回包 |
| `HostSubjects.MESSAGE_POST` | `0x100E` | 插件 → Host | 载荷是 CBOR(`Message`)；Host 立刻回空 COMPLETE |
| `HostSubjects.MESSAGE_REQUEST` | `0x100F` | 插件 → Host | 同上；Host 不立即回，转发后由提供方回包 |
| `HostSubjects.CONSOLE_INPUT` | `0x1010` | 插件 → Host | 通知，载荷是 UTF-8 整行（不含 CR/LF）；Host 投入 stdin 环形缓冲 |

`keel_abi.h` 只加一个 subject `#define` 和一个 Host 内部使用的 C 函数
（`int keel_console_push(const char *text, uint32_t length)`），**不动结构体布局，因此不提升 `KEEL_ABI_VERSION`**。
但旧 Host 遇到新插件会走到 `error("Host 没有该 subject 的处理器")`，所以 Host 与插件必须同步升级；
发布说明里要写清这一点。

`keel_console_push` 与 `keel_console.c` 里的 stdin 读线程共用同一个 16 槽环形缓冲和同一把互斥量，
只是把「写端」从 C 读线程扩到 Host 的事件泵线程；它不会被跨运行时导出，插件那一侧仍然只能发事件。

### 5.3 Host 路由

```
MESSAGE_POST    → 目标解析 → pushToPlugin(COMMAND, MESSAGE, correlation = 0) → 立即回空
MESSAGE_REQUEST → 目标解析 → 分配 correlation → pushToPlugin(COMMAND, MESSAGE, correlation)
                             → forwards[correlation] = Forward(caller, callerCorrelation, deadline)
插件回 COMPLETE → forwards 查表 → 换回 caller 的 correlation → 推给调用方
```

- 目标解析：`HostPlugins.find(message.target)?.pluginId`；`target` 是 manifest id，不是数字 id。
- 复用现有 service 转发的 `forwards` 表；把 `ServiceForward` 泛化成 `Forward(kind = SERVICE | MESSAGE)`
  或单独再开一张表（推荐前者，超时清扫逻辑只有一份）。
- 插件侧分发：`PluginWorker.dispatchEvent` 增加一个分支，解码后调 `plugin.onMessage`；
  `correlation != 0` 时回 COMPLETE（有载荷带回载荷，未处理回 `KEEL_ERR_FAILED` + 错误信封）。

### 5.4 语义

| 场景 | `post` | `request` |
| --- | --- | --- |
| 目标不存在 | 丢弃 + 首次告警 | 回 `KeelRemoteException`（service unavailable） |
| 目标未启用 | 丢弃 + 首次告警 | 同上 |
| 目标 inbox 满（256） | 丢弃 + 首次告警 | 回 `KEEL_ERR_FULL` |
| `onMessage` 返回 null | 忽略（Host 只记一次 warning） | 回错误信封 |
| 超时 | 不适用 | 抛 `HostRequestException`，Host 清掉 `forwards` 条目 |

已知边界：**任何启用中的插件都能给任何插件发消息**，没有可见性 / 权限模型（与日志「全部可见」一致）。
等 rbac 落地后再决定是否加 `@Permission` 之类的门。

---

## 6. helper 与桥协议

### 6.1 为什么是 RPC 而不是 stdin/stdout

1. `CREATE_NEW_CONSOLE` + `STARTF_USESTDHANDLES` 的组合在文档与实测里都不够确定：子进程到底拿到新控制台的句柄
   还是继承的管道句柄，随启动方式变化。
2. 一旦用 stdio 当通道，helper 的 `stdin` / `stdout` 就不再是控制台句柄；后续要接
   `CONOUT$`、要 fork 子进程、要上 TUI 库都得绕。
3. 通道逻辑上只有一个（两个方向各一条单向管道），要同时承载「输出字节、输入字节、窗口尺寸、标题、
   模式切换、握手」，裸流必然要发明带内转义，和日志正文里的 ANSI 撞车。

所以：**两条单向命名管道 + 定长帧头**，载荷仍是原始终端字节。

### 6.2 帧格式

帧头 12 字节，全部小端：

| 偏移 | 长度 | 字段 | 说明 |
| --- | --- | --- | --- |
| 0 | 4 | magic | `KCN1` |
| 4 | 2 | version | 当前 `1`；不匹配直接关闭并报错 |
| 6 | 2 | type | 见下表 |
| 8 | 4 | length | 载荷字节数，上限 1 MiB；超限视为协议错误 |
| 12 | length | payload | |

未知 `type` **忽略**（向前兼容）；未知 `version` 断开。

### 6.3 帧类型

| 值 | 名称 | 方向 | 载荷 |
| --- | --- | --- | --- |
| `0x01` | `HELLO` | helper → 插件 | `u16 cols, u16 rows, u32 pid, u8 flags`（bit0 = VT 可用） |
| `0x02` | `OUT` | 插件 → helper | UTF-8 文本；helper 把孤立 `\n` 归一化为 `\r\n` |
| `0x03` | `IN` | helper → 插件 | UTF-8 文本；行模式下带行尾 `\n` |
| `0x04` | `RESIZE` | helper → 插件 | `u16 cols, u16 rows` |
| `0x05` | `TITLE` | 插件 → helper | UTF-8 标题；helper 立即 `SetConsoleTitleW`，空载荷表示回落默认名 |
| `0x06` | `MODE` | 插件 → helper | `u8 mode`（0 = line，1 = raw）、`u8 flags`（bit0 = echo） |
| `0x07` | `CLOSE` | helper → 插件 | `u8 reason`；预留，正常关窗以管道 EOF 为准 |
| `0x08` / `0x09` | `PING` / `PONG` | 双向 | 空；预留 |

### 6.4 TTY 语义怎么保留

| 能力 | 做法 |
| --- | --- |
| 着色 / 光标 / 清屏 / 滚动区域 | `OUT` 里的原始字节直送 `CONOUT$`，helper 不解析；`ENABLE_VIRTUAL_TERMINAL_PROCESSING` 打开 |
| 宽字符 | 控制台代码页设为 UTF-8（`SetConsoleOutputCP` / `SetConsoleCP`），`OUT` 直接写 UTF-8 字节 |
| 输入模式 | 关掉 `ENABLE_LINE_INPUT` / `ENABLE_ECHO_INPUT` / `ENABLE_PROCESSED_INPUT`，只留 `ENABLE_EXTENDED_FLAGS`，用 `ReadConsoleInputW` 收按键事件 |
| 行编辑 | helper 自己实现：左右/Home/End/Delete、Backspace、Ctrl+A/E、Ctrl+W（删词）、Ctrl+U（删到行首）、Ctrl+C（清行） |
| 历史 | 上下键翻阅本次运行的命令历史（最多 64 条，去重），不落盘 |
| 回显 | 每次都整行重绘：`\r` + `ESC[2K` + 提示符 `> ` + 缓冲区，再按显示宽度把光标左移；CJK 按 2 列计算 |
| 与日志共存 | 输出线程写日志前先擦除输入行，写完再重绘，读写由同一把屏幕锁串行化 |
| 窗口尺寸 | 每次写输出后比对 `GetConsoleScreenBufferInfo` 的窗口尺寸，变化时投 `RESIZE` |
| 原始按键 / 方向键 | 预留 `MODE raw`：切到 `ENABLE_VIRTUAL_TERMINAL_INPUT`，`IN` 里就是 VT 序列 |
| 回滚 | 启动时把 screen buffer 高度设为 `--scrollback`（默认 10000） |
| 全屏 TUI | 后续用 `OUT` 里的 CSI 自己画；helper 不拦 |
| Ctrl+C | helper 收到 `0x03` 清空当前行并回显 `^C`，不提交、不退出 |
| QuickEdit | 启动时清掉 `ENABLE_QUICK_EDIT_MODE`（需要同时置 `ENABLE_EXTENDED_FLAGS`），避免用户误选中把输出写阻塞 |

### 6.5 helper 生命周期

启动参数：`--out <pipe> --in <pipe> --title <text> --scrollback <n>`。

- `--out`：插件写、helper 读（`OUT` / `TITLE` / `MODE`）；
- `--in`：helper 写、插件读（`HELLO` / `IN` / `RESIZE` / `CLOSE`）。

1. 连接两条命名管道（短暂重试，避免插件端尚未 `ConnectNamedPipe`）；
2. 按 §6.4 设置控制台；用 `--title` 立即 `SetConsoleTitleW`（窗口一创建就带当前标题，不会先闪一下默认名）；
3. 把 RC 里的图标贴到窗口标题栏：`GetConsoleWindow()` + `SendMessageW(WM_SETICON, ICON_SMALL / ICON_BIG)`；
4. 发 `HELLO`；
5. 两个线程：
   - **写控制台**：读管道帧 → `OUT` / `TITLE` / `MODE` 生效，顺带检测尺寸变化；
   - **读控制台**：`ReadConsoleW` 整行 → `IN` 帧；
6. 退出条件：管道读不到（EOF / 错误）→ 退出 0；协议错误 → 退出 2；`CONOUT$` / `CONIN$` 打不开 → 退出 3；
7. 不装 `CTRL_CLOSE_EVENT` 处理器：关窗就让进程结束，插件通过 broken pipe 感知。

Host 崩溃时 OS 关闭所有句柄，helper 读到 EOF 自行退出，不会留窗口。

### 6.6 helper 的构建

- 源码：`keel-plugin-console/src/nativeCpp/console_host.c`，单个 C 文件、无 K/N 运行时、约 20 KB；
- 编译：照搬 [keel-abi/build.gradle.kts](../keel-abi/build.gradle.kts) 的 `toolchainFor()`
  （K/N 自带的 msys2 gcc / ar），新增 `Exec` 任务 `consoleHelper`，输出
  `build/generated/console/native/keel-console-host.exe`；
- 资源：同目录的 `console_host.rc` 用同一套 msys2 `windres` 编译，打包时一起链接，内容固定为
  `ProductName` / `FileDescription` = `Keel Console`、`OriginalFilename` = `keel-console-host.exe`、
  `FileVersion` 取自 `libs.versions.keel`（`windres -DKEEL_VERSION=...`），图标 `IDI_ICON1` 用构建用的
  `keel-icon.ico` 副本（`src/nativeCpp/keel-icon.ico`）。资源管理器 / 任务栏看到的就是这套 PE 资源；
  窗口标题栏图标由 §6.5 第 3 步的 `WM_SETICON` 补上（conhost 的窗口类自带默认图标，只写 RC 不够）；
- 图标副本的来源是 `doc/logo/generate-icon.py` 生成到 classpath 插件资源目录的 `keel-icon.ico`：
  跨目录读兄弟源码违反 AGENTS，所以在本目录放一份；**改 SVG 后重新生成并同步这份副本**；
- 打包：`keelPlugin { resources("build/generated/console") }`（[KeelPluginExtension.kt](../keel-plugin-gradle-plugin/src/main/kotlin/moe/lolosia/keel/gradle/plugin/KeelPluginExtension.kt) 支持额外资源目录），
  包内路径 `resources/native/keel-console-host.exe`；
- 运行期：`ctx.materialize("native/keel-console-host.exe")` 拿到真实路径再 `CreateProcessW`。
  `cacheDir` 每次加载独立，旧 helper 不会和新版本抢同一个文件。

---

## 7. 插件内部

### 7.1 状态机（只在 Worker 上迁移）

```
Closed ──open──▶ Opening ──HELLO──▶ Open(pid, pipe)
  ▲                 │ 失败            │
  └── Failed(msg) ◀─┘                │ 写失败 / 读 EOF / close
  └──────────────────────────────────┘
```

- `open` 幂等：已开时直接回 `open`；
- `close` 幂等：未开时回 `closed`；
- 状态迁移只由 Worker 上的协程执行；后台读写协程把「HELLO / EOF / 写失败 / RESIZE / IN」投进
  `Channel<BridgeEvent>`，由 Worker 消费。

### 7.2 日志流

```kotlin
Log.observe(LogTextFormat.ANSI, startId = config.startId)   // 0 = 全量回放，-1 = 只收新的
```

- **必须用 ANSI 文本视图**：Host 渲染时才带 `ModuleColors`（`plugin.json` 的 `color`）；
  `LogRecord.moduleAnsi` 是 `@Transient`，不参与 CBOR，插件拿 `observeRecords` 自行渲染会退化成哈希色。
- 每页 256 条 / 128 KiB，由 Host 侧攒页；`dropped = true` 时插件拿不到标志（`Log.observe` 只暴露文本），
  如需提示可先 `Log.history()` 对一下首条 id，v1 不做。
- collect 在 Worker 上（内部是反向请求），只负责解码 + 入队；**不要在 collect 里打日志**。
- 关闭订阅由 Flow 的 `finally` 自动发 `LOG_UNSUBSCRIBE`，不要手工调用内部 API。

### 7.3 写路径与背压

- 队列：行数 4096 / 字节 1 MiB，先到为准；满了**丢最旧**并计数，下一帧前补一行
  `已丢弃 N 行（窗口写入过慢）`（直接写窗口，不走 `Log`）。
- 写协程（`Dispatchers.Default`，插件自己的运行时）：从队列取行，拼到 64 KiB 就发一个 `OUT` 帧。
- 目的：用户拖动窗口 / 选中文本 / 控制台渲染慢时，最多丢历史，**不能卡住 Worker**，
  否则连 `close` 消息都处理不了。
- 关闭顺序：取消 collect → 停写协程 → 关管道写端（helper 读到 EOF 退出）→ 等 200 ms → 必要时
  `TerminateProcess`。

### 7.4 输入：转发给 Host 的 stdin 接收器

窗口不是独立 shell：每一行都送进 Host 的控制台命令分发器，效果等同于在 Host 自己的终端里敲。

```
helper ReadConsoleW（行模式，Enter 提交）
  → IN 帧
  → 后台读协程 → Channel<BridgeEvent>
  → Worker 上的 ConsoleInput：去尾部 CR/LF、空行丢弃、按 UTF-8 边界截断
  → ctx.host.post(HostSubjects.CONSOLE_INPUT, utf8)
  → HostPump → HostConsole.inject(line) → keel_console_push(...)
  → 与 stdin 读线程共用的环形缓冲 → HostConsole.poll() → dispatch()
  → Host 命令 / 插件 onCommand
输出：HostConsole.print → Log.info(tag = "Console") → 归档 → 本插件订阅 → 窗口
```

**为什么不交给 conhost 的行编辑**：在 Windows Terminal / ConPTY 下实测 `ENABLE_PROCESSED_INPUT`
设不上去（`SetConsoleMode` 返回成功但读回仍是关闭），cooked 模式因此失效——退格被原样回显成 `^H`，
回车只回 `\r` 不换行。所以 helper 关掉行输入与回显，自己读按键、自己编辑、自己回显，
行为与宿主无关。

**只处理回车**：Enter 提交一行，v1 不做 Tab 补全、不做历史、不做方向键解析。
- 空行（trim 后为空）不转发，避免无意义的命令分发。
- 行长上限与 C 侧一致（`KEEL_CONSOLE_LINE - 1` = 1023 字节）；插件在 UTF-8 字符边界截断，
  不把多字节字符劈开。
- 注入的行**不写日志**：窗口里已经由 helper 回显过，再走一遍日志会重复；因此 Host 自己的终端
  只看到命令结果、看不到「谁提交了什么」。需要审计时再单独加一行 `Log.banner("> ...")`。
- Host 侧不区分来源：注入行与真实 stdin 共用同一条环形缓冲，按到达顺序执行；ring 满时丢弃并 warn 一次。
- `HostConsole.start()` / `poll()` 只在服务模式（`effectiveServe != null`）运行。`--once` 模式下没有
  poll 循环，`keel_console_push` 返回 0，Host 记一次 warn，窗口仍能显示日志。
- 在窗口里关窗：输入 `console close`（Host 不认识时按启用顺序问插件，本插件 `onCommand` 接管），
  或直接点 X。`console close` 要延后一拍（`ctx.scheduler.schedule(0)`）再关，
  否则插件回包前窗口就没了，Host 的回显来不及写进去。
- 安全边界：任何启用中的插件都能注入任意 Host 命令（包括 `/stop`）；这与「插件能读全部日志」同级，
  v1 不加门，标注在 §12。

输入帧由后台读协程收进 `Channel<BridgeEvent>`，转发与状态变更仍在 Worker，避免跨线程访问 `ctx`。

### 7.5 控制协议（`channel = "console"`）

载荷是 UTF-8 文本，空格分隔：

| 请求 | 应答 |
| --- | --- |
| `open` | `open` / `failed:<reason>` |
| `close` | `closed` |
| `reopen` | `open` / `failed:<reason>` |
| `status` | `open` / `closed` / `failed:<reason>` |
| `title <text>` | `open` / `closed`；空 `<text>` 表示恢复默认名 |
| 其它 | `failed:unknown-command:<cmd>` |

`post` 接受 `open` / `close` / `reopen` / `title`（不需要应答）；`status` 必须走 `request`。
`status` 不能只读自己记的布尔值：先 `GetExitCodeProcess` 探活，非 `STILL_ACTIVE` 就降级成 `closed`。

同一套动作在 `Plugin.onCommand` 里也提供一份（`console open` / `console close` / `console status` /
`console title <text>` / `console clear`），这样在 Host 自己的终端和本窗口里都能直接操作；
窗口里输入的行会经 §7.4 转发回 Host，最终仍落到这里。

### 7.6 标题

控制台的窗口标题可以被任意进程改（`SetConsoleTitleW`），和 cmd 的行为一致：

- **默认名**：`config.title`，未配置时为 `Keel Console`。应用自身叫什么名（单 exe 的 `productName`）
  不自动带进来；要改名就改配置或由别的插件发消息。
- **状态持久化在进程内**：标题存在插件运行期的单例（`ConsoleState`）里，不是 `Plugin` 实例字段；
  关窗、重开、`onDisable → onEnable` 都保留，只有进程退出才回到配置默认值。**不写磁盘、不回写配置**。
- **生效时机**：
  1. `open` 时把当前标题作为 `--title` 传给 helper，窗口一创建就是正确的名字；
  2. 已开窗收到 `title <text>` → 发 `TITLE` 帧，helper 立即 `SetConsoleTitleW`；
  3. 未开窗收到 → 只更新内存状态，下次 `open` 生效。
- 标题只影响窗口文字；helper 的 `ProductName` / `FileDescription` 固定在 RC 里（§6.6），不随运行期变化。
- 编码：`CreateProcessW` 的命令行与 `SetConsoleTitleW` 都是 UTF-16，中文标题不需要额外转码；
  `TITLE` 帧里是 UTF-8，helper 收到后转宽字符再设置。

### 7.7 配置（`config/plugins/console.yaml`）

```yaml
autoOpen: false        # onEnable 时是否自动开窗
startId: 0             # 0 = 全量回放；-1 = 只收打开之后的新日志
maxReplayLines: 0      # 0 = 不限制；用于压缩首次回放
scrollback: 10000      # 控制台缓冲行数（helper 的 --scrollback）
title: "Keel Console"  # 初始标题；运行期被消息改过不写回这里
input: line            # line = 整行转发给 Host stdin；off = 不读输入；raw 预留
```

读取走 `ctx.config.read(ConsoleSettings.serializer())`，缺失键用默认值；**不写** Host 工作目录。

### 7.8 文件划分（遵循 AGENTS「一个 .kt 一个顶层类型」）

```
keel-plugin-console/
  settings.gradle.kts
  build.gradle.kts
  src/commonMain/kotlin/moe/lolosia/keel/plugins/console/
    ConsolePlugin.kt        @KeelPluginMain；生命周期、onMessage、onCommand
    ConsoleCommand.kt       控制通道字面量与解析
    ConsoleSettings.kt      配置 DTO
    ConsoleState.kt         运行期单例：当前标题、窗口状态、订阅游标
    ConsoleFrame.kt         帧类型与编解码
    BridgeEvent.kt          后台线程 → Worker 的事件
    LogPump.kt              Log.observe → 有界队列
    ConsoleInput.kt         IN 帧 → 去 CR/LF、截断、转发到 HostSubjects.CONSOLE_INPUT
    ConsoleWindow.kt        expect：ConsoleSession 接口 + openConsoleSession 工厂
  src/mingwX64Main/kotlin/moe/lolosia/keel/plugins/console/
    ConsoleWindow.mingw.kt  actual 工厂（openConsoleSession）
    MingwConsoleSession.kt  CreateNamedPipeW、CreateProcessW、读写协程
  src/commonMain/resources/plugin.json
  src/nativeCpp/console_host.c
  src/nativeCpp/console_host.rc
  src/nativeCpp/keel-icon.ico    由 doc/logo/generate-icon.py 生成后同步的副本
```

`plugin.json` 关键字段：`id = "console"`、`logTag = "Console"`、
`lib.mingwX64 = "lib/mingwX64/keel_plugin_console.dll"`、`requires = []`。

---

## 8. 打包与构建

```powershell
# 1. ABI：subject 常量 + keel_console_push
gradlew -p keel-abi build

# 2. API（改了公共面，按 AGENTS 必须连带编消费方）
gradlew -p keel-api build
gradlew -p keel-plugin-hello build

# 3. Host（Messenger 路由）
gradlew -p keel-host linkDebugExecutableMingwX64

# 4. 插件（含 helper 编译）
gradlew -p keel-plugin-console keelPackage
```

根 `settings.gradle.kts` 增加 `includeBuild("keel-plugin-console")`；`buildAll` 自动带上。
单 exe 场景下 helper 随 `resources/` 进 bundle，`ctx.materialize` 落盘后启动，不需要额外 `include(...)`。

---

## 9. 边界情况

| 情况 | 期望 |
| --- | --- |
| Host 无桌面会话 / 服务方式启动 | `CreateProcessW` 失败 → `failed:create-process:<code>`，插件本身不崩 |
| helper 缺失 / materialize 失败 | `failed:helper-missing`，可重复 `open` |
| 用户点 X 关窗 | helper 结束 → 插件 broken pipe → `closed`；Host 无感 |
| 窗口被关后立刻 `status` | 先探活再回，必然是 `closed` |
| 打开后日志风暴 | 队列满丢最旧 + 补提示；Worker 不阻塞 |
| 插件停用 | 取消 collect、关窗、退订；不留线程与窗口 |
| Host 崩溃 | OS 关句柄 → helper EOF 退出；无孤儿窗口 |
| 重复 `open` / `close` | 幂等，返回当前状态 |
| 未开窗时收到 `title` | 只更新运行期状态，下次 `open` 生效；重开不回到默认名 |
| `title ""` / 空参数 | 回落 `config.title`（默认 `Keel Console`） |
| 默认终端是 Windows Terminal | 窗口以 WT 标签页出现；标题仍可改，`WM_SETICON` 可能不生效（见 §11） |
| 目标插件不在线时 `post` | Host 丢弃 + 首次告警，不抛异常 |
| 窗口里输入空行 | 插件丢弃，不转发，也不产生命令 |
| 输入行超过 1023 字节 | 按 UTF-8 边界截断后转发（与 C 侧行长上限一致） |
| 窗口里输入 `/stop` | 与 Host 终端一致，走优雅停机 |
| 注入行与真实 stdin 同时到达 | 同一条环形缓冲，按到达顺序执行 |
| Host 不在服务模式（`--once`） | 注入失败并 warn；窗口仍正常显示日志 |

---

## 10. 分阶段与验收

**P1 Messenger + 基本开窗**

- `keel-abi` 常量、`keel-api` `PluginMessenger` + `Plugin.onMessage` + Worker 分支、
  `keel-host` 路由与超时清扫；
- 插件：`open` / `close`，`Log.observe(ANSI, 0)` 全量回放 + 实时；
- 验收：hello 通过 `post` 打开窗口；窗口里看到归档以来的全部日志（含横幅与异常段）且配色正确；
  关闭窗口后 Host 继续正常输出；再次 `open` 能重放。

**P2 状态查询、输入转发与尺寸**

- `status` / `reopen` / `title`；整行输入转发到 Host（只认回车，无补全）；`RESIZE` 帧；
- 验收：用户手动关窗后 `status` 立刻回 `closed`；窗口里敲 `help` / `plugins` 与 Host 终端结果一致，
  且输出回到窗口；`console close` 能关窗；`title Demo` 后关窗重开仍显示 `Demo`，
  `title ""` 恢复 `Keel Console`；拖动改尺寸后插件能收到 `RESIZE`。

**P3 单 exe 与打磨**

- bundle 模式验证（helper 从内存包 materialize 后启动）；
- 配置项（`autoOpen` / `startId` / `maxReplayLines`）；
- 视需要给 `LogFetchRequest` 加 `minLevel`（Host 侧过滤，插件不做文本解析）。

---

## 11. 风险与待验证

1. mingw 目标的 `platform.windows` 里 `CreateProcessW` / `CreateNamedPipeW` / `STARTUPINFOW` 的 interop
   是否完整（现有代码只用到 `FindResourceW` 一系）。
2. 插件运行时里 `Dispatchers.Default` 能否正常起线程并被 `onDisable` 干净取消。
3. `ctx.materialize` 在 bundle（内存 zip）模式下物化 exe 后 `CreateProcessW` 是否顺利。
4. 关窗 → broken pipe 的探测时延（`WriteFile` 失败或 `GetExitCodeProcess` 轮询）。
5. 从缓存目录启动 exe 可能被杀软拦截；失败要落到 `failed:` 而不是崩溃。
6. `SetConsoleScreenBufferSize` 在窗口已最大化 / 尺寸异常时可能失败，helper 要容错并继续。
7. `ReadConsoleW` 的行编辑与中文回显在不同代码页下的表现，需要实机确认。
8. `windres` 是否随 K/N 的 msys2 工具链一起存在（`keel-abi` 只用到 gcc / ar）；缺失时退回
   `gcc -c console_host.rc` 或改用 `rc.exe`。
9. Windows 11 把 Windows Terminal 设为默认终端时，`CREATE_NEW_CONSOLE` 得到的是 WT 的一个标签页：
   `SetConsoleTitleW` 有效，`WM_SETICON` / 独立窗口图标可能无效；这一场景下列为「已知限制」。
10. 图标副本会随 SVG 改动而过期，需要在 `generate-icon.py` 之后手工同步，发布前核对一次。
11. `keel_console_push` 可能早于 `keel_console_start` 被调用（插件 `onEnable` 早于 Host 进入 run 循环）：
    需要 push 自己懒初始化互斥量，否则第一批注入行会丢。
12. GUI / 无 stdin 模式下 stdin 读线程启动后立即 EOF 退出；环形缓冲仍应可用，注入行能否被
    `poll()` 取到需要实测。
13. 注入行走的是同一条 `poll()`，非交互模式下 `ConsolePrompt` 已禁用，但仍要确认不会多打 `> `。

---

## 12. 未决问题

1. `Messenger` 的可见性：目前任意插件可给任意插件发消息，是否需要与 rbac 对齐后再加权限门。
2. `forwards` 表复用：把 `ServiceForward` 泛化成带 `kind` 的 `Forward`，还是另开一张表；
   前者少一份超时逻辑，后者改动面更小。
3. 窗口是否需要「跟随 Host 的 `--no-console`」之类的联动；v1 默认完全独立。
4. 注入行的审计：要不要在 Host 侧额外记一行 `"> <line>"`（会同时出现在本窗口，造成重复），
   或只写日志文件；以及是否给 `CONSOLE_INPUT` 加权限门。
5. `--once` 模式下没有 poll 循环，是否需要为命令注入单独跑一个最小循环，或明确「该模式不支持」。
6. 是否允许同时开多个窗口（当前设计是单实例状态机）。
