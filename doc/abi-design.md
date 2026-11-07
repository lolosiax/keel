# Keel 跨运行时 ABI 设计（keel-api 的核心职责）

> 本文确认 `keel-api` 的边界与 C ABI 做法。结论先行，细节在后。
> 可行性前提与运行时约束见 [前序讨论.md](前序讨论.md)；阶段计划见 [roadmap.md](roadmap.md)。

---

## 1. 结论先行

`keel-api` 不是「一个消息 DTO 包」，它是**两个 Kotlin/Native 运行时之间唯一合法的接触面**，承担：

- 插件生命周期与钩子注册；
- 事件泵（event pump）与模块 Worker；
- 消息分发、请求 / 应答、取消；
- 事件总线（EventBus）与跨模块订阅；
- 服务注册表（ServicesManager）；
- 调度器与协程桥接；
- Kotlin 对象、回调、上下文的**跨边界投影**。

做法上必须接受三条硬事实：

1. **Kotlin 对象不能跨运行时搬运。** 能过边界的是：C 结构体、字节、不透明句柄、**纯 C 函数指针**。
   Kotlin 对象留在拥有者运行时，另一边拿到的是「句柄 + 本地代理」。
2. **Kotlin 回调不能以函数指针形式跨边界执行**（`staticCFunction` 生成的回调仍然属于原运行时，见 §5.3）。
   回调被建模为「回调 id + 事件」，在拥有者线程上查表调用；否则会在错误线程进入错误运行时。
3. **协程调度器不能透传对象。** 透传的是**调度命令**（延时、周期、取消）+ 完成事件；
   每个运行时用自己的调度器执行，continuation 永不跨 ABI。

对应的结构：

```
┌──────────────────────────── plugin 运行时 ────────────────────────────┐
│ 业务代码 → keel-api 接口（Plugin / EventBus / Scheduler / Messenger）  │
│           ↑ 本地实现或本地代理（Proxy）                                │
│           ↓ cinterop 绑定                                              │
│      C shim（队列 / 句柄引用计数 / 分配器；hidden，不进 Kotlin 运行时） │
└───────────────────────────────┬───────────────────────────────────────┘
        共享内存：事件队列、句柄槽、载荷
┌───────────────────────────────┴───────────────────────────────────────┐
│      C shim（Host 副本；同上，唯一区别是句柄表只在 Host 里）            │
│           ↑ cinterop 绑定                                              │
│ Host 桥接层（把事件翻译成 Kotlin 对象 / 服务调用）                      │
│ 业务代码 → Ktor / 插件加载器 / 事件总线 / 调度器                        │
└──────────────────────────────────────── host 运行时 ──────────────────┘
```

---

## 2. 线程模型（最重要的一条）

**一个线程只能进入一个 Kotlin/Native 运行时。**

两个运行时在同一进程里各有自己的 TLS、GC、线程状态；同一线程先后进入两个运行时，
会把两套运行时状态混在同一个线程上，表现为随机崩溃或内存损坏。因此：

- 插件 Worker 线程必须由 **C 侧的 `pthread_create` / `CreateThread`** 创建，
  该线程**只**进入插件运行时。
- Host 的线程（Ktor、加载器、定时器）**只**进入 Host 运行时，绝不直接调用插件的 `@CName` 函数。
- 插件的 `@CName` 入口只能在插件自己的 Worker 线程上被调用。
- 跨边界的所有操作（入队、唤醒、分配、引用计数）都是 **C 代码**，不是 Kotlin 函数。

### 加载时序（正确做法）

`bootstrap` 完全在 C 里执行，**不触碰 Kotlin**：它只保存函数表、建 inbox、起事件泵线程就返回。
插件真正「启动」是 Host 投递的第一条事件，结果无论成败都以 `COMPLETE` 事件回投 Host。

```
Host 线程                    C shim(host)          C shim(plugin dylib)
   │ dlopen/LoadLibrary ─────────>│
   │ dlsym keel_plugin_bootstrap ─>│
   │ dlsym plugin.json.entry ─────>│   (取地址，不调用)
   │ bootstrap(host_api, plugin_id, worker_main, &api)
   │                              │ 保存表 + 建 inbox
   │                              │ CreateThread ──┐ 事件泵线程（只进插件运行时）
   │ <──── bootstrap 返回 ────────│                │
   │ push COMMAND{subject=START, correlation=N} ──>│
   │                              │                │ pop → START
   │                              │                │ worker_main(&api)   ← 首次进入插件 Kotlin
   │                              │                │ 回填 on_event
   │ <──── COMPLETE{correlation=N, status} ────────│
   │ status==OK → 启用；否则记录失败并丢弃该插件   │
```

**反例**：Host 直接 `dlopen` 后调用插件导出的 `create()`。这个调用发生在 Host 线程上，
会让插件运行时初始化在 Host 线程里；该线程之后还要跑 Host 的 Kotlin 代码 → 两运行时同线程。

**另一个反例**：在 `bootstrap` 里直接调用 Kotlin 入口再起线程。那样「启动」发生在调用
`bootstrap` 的那个线程上（也就是 Host 线程），同样踩到上面的问题。必须让线程先起来，
再在泵线程上执行第一条消息。

---

## 3. C ABI 的定义位置与实现位置

| 内容 | 位置 | 说明 |
| --- | --- | --- |
| 结构体、枚举、函数指针、导出符号声明 | `keel-abi/src/nativeCpp/keel_abi.h` | 唯一布局来源，Host 与插件都通过 cinterop 使用 |
| cinterop 绑定 | `keel-api/src/nativeInterop/cinterop/keelabi.def` | 包名 `moe.lolosia.keel.abi.c`，只给框架内部用 |
| 队列 / 句柄表 / 分配器实现 | 顶层 `keel-abi` 构建 | 每目标编成静态库，Host / 插件各自链接一份 |
| 对外导出 | 插件 dylib 的 `keel_plugin_bootstrap` / `keel_plugin_shutdown` / `keel_plugin_worker_main` | `worker_main` 是 Kotlin `@CName` 入口，只由泵线程调用 |

导出细节：Kotlin 的 `@CName` 符号在 Windows 上**不会**自动进 PE 导出表，Host 就 `dlsym` 不到。
所以 `keel-abi-gradle-plugin` 给每个 sharedLib 生成一份 `.def` 精确列出上面三个符号
（`keelAbi { export("...") }` 可追加），并用 `-u keel_plugin_bootstrap` 把归档成员拉进链接。

要点：

- **结构体通过指针传**，不要按值传（Kotlin 的 `CValue` 每次字段访问可能复制，热路径代价高）。
- 结构体只用定宽类型，禁止 `long`、位域、`#pragma pack`；布局由编译器保证，Kotlin 侧不手写 `@CStruct`。
- C 实现必须编译进**最终二进制**：Kotlin/Native 库不会替消费者携带 C 对象文件。
  所以做成按目标的静态库（`keel-abi`，输出 `libkeel_abi.a`），Host / 插件在链接期各自并入一份。
- 每个副本有自己的全局状态（队列、分配器、句柄表）——这是有意的：
  句柄表只在 Host 副本里使用，队列则各自拥有自己的收件箱。
- 队列节点内存**一次性预分配**（定长环形缓冲），运行期不再 `malloc` / `free`；
  只有载荷是动态的，由发送方分配、发送方 release，避免跨 CRT 释放。

`keel_abi.h` 已包含：`KeelStatus`、`KeelEventKind`、`KEEL_SUBJECT_START`、`KeelEvent`、
`KeelEventRelease`、`KeelHostApi`、`KeelPluginApi`、`KeelPluginWorkerMain` / `KeelPluginHandlers`、
队列函数、`keel_plugin_bootstrap`。

- **不透明句柄给 cinterop 一份占位完整定义**：`KeelQueueTag` 在 `KEEL_CINTEROP`
  （由 `keelabi.def` 的 `compilerOpts` 注入）下是一个只有 `void *reserved` 的完整结构体，
  C 实现侧仍只看到前向声明。这样 cinterop 把类型生成到 `moe.lolosia.keel.abi.c`，而不是
  IDE 中间源集索引不到的 `cnames.structs`。Kotlin 侧只能持有指针，**不得解引用、分配、
  按值传递或 `sizeof`**；真要操作内部布局时，移除这个分支并把真实布局放进头文件。

---

## 4. 事件泵

### 4.1 队列

- 每个模块一个 **inbox**，Host 一个 inbox；队列由**接收方**创建并拥有。
- `KeelHostApi.host_inbox` 是**普通指针字段**（bootstrap 前填好、之后只读），不是函数指针。
  插件在自己的 Worker 线程上调 Host 的函数指针会进入 Host 运行时，违反 §2；
  需要「取 Host 收件箱」的地方一律直接读这个字段。
- 发送方用自己的 C shim 副本 `keel_queue_push(receiver.inbox, ...)`：
  只写共享内存 + 唤醒，不进入接收方 Kotlin 运行时。
- 跨平台原语：Linux 用 `pthread_mutex` + `pthread_cond`，Windows 用
  `CRITICAL_SECTION` + `CONDITION_VARIABLE`；都在 C shim 内，不暴露给 Kotlin。
- 单个队列内 FIFO；跨队列**没有全局顺序**，业务不能依赖它。

### 4.2 事件

```c
typedef struct KeelEvent {
    uint32_t size;          // sizeof(KeelEvent)
    uint16_t kind;          // COMMAND / COMPLETE / CANCEL / BUS / DISPOSE / SHUTDOWN / LOG
    uint16_t flags;
    uint64_t seq;
    uint64_t source;        // 发送方模块 id，0 = Host
    uint64_t target;        // 接收方模块 id，0 = Host
    uint64_t correlation;   // 请求 / 应答配对
    uint64_t subject;       // 句柄、服务 id、回调 id、KEEL_SUBJECT_START
    uint32_t payload_size;
    int32_t  status;        // KeelStatus；COMPLETE 用来回传结果
    void *payload;          // 发送方分配的载荷，可为 NULL
    KeelEventRelease release; // 发送方二进制的纯 C 归还函数，可为 NULL
    void *release_user;
} KeelEvent;
```

- 事件**自包含**：载荷地址、长度、归还函数都在结构体里。队列只做结构体拷贝，
  不接管载荷所有权；接收方复制完载荷后调用 `release(release_user, &event)`。
- 载荷是独立缓冲区，二进制格式（Protobuf / CBOR / 自定义定长结构），**不用 JSON**。
- 归还函数必须是纯 C（通常就是发送方 C shim 里的 `keel_event_release_default`），
  不能用 Kotlin 的 `ByteArray` 或另一个 CRT 的 `free`。
- 背压：队列有界。控制类事件（命令 / 取消 / 关闭）阻塞等待或返回 `KEEL_ERR_FULL`；
  总线类事件满了丢最新并计数，不能阻塞发送方 Worker。
- 日志事件：`KEEL_EVENT_LOG` 的 `status` 是 `KeelLogLevel`
  （TRACE / DEBUG / INFO / WARN / ERROR / FATAL），`payload` 是 UTF-8 日志正文。
  发送方用 `keel_payload_alloc` 分配、`keel_payload_copy` 写入，
  再用 `keel_event_set_default_release` 挂上本二进制的归还函数。

### 4.3 启动握手

插件启动不是 `bootstrap` 的同步副作用，而是一次事件往返：

```
Host:   push COMMAND { subject = KEEL_SUBJECT_START, correlation = N }
泵线程:  pop → worker_main(&api, &handlers)  ← 该线程第一次进入 Kotlin
        → handlers.on_event 由 Kotlin 回填
        → push COMPLETE { correlation = N, status = 启动结果 }  到 Host inbox
Host:   status == OK 才标记启用；否则记失败、关闭该插件队列
```

启动失败的原因（manifest 非法、Kotlin 初始化异常等）一律折进 `status`，
错误细节走载荷（UTF-8 消息），Host 不需要区分「bootstrap 失败」与「启动失败」。

### 4.4 事件泵循环（插件侧，C 实现）

```
loop:
  status = keel_queue_pop(inbox, &event, timeout)
  if status == TIMEOUT: continue                    # 泵线程是 C，不做定时任务
  if status == CLOSED:  break
  if kind == SHUTDOWN:  on_event(&event); break      # Kotlin 释放句柄
  dispatch(event)                                    # START → worker_main；其余 → on_event
  release(user, &event)                              # 复制完载荷后归还
```

Host 侧对称：Host 也有 inbox 与自己的 Worker，负责处理插件发来的请求 / 完成 / 取消。
Ktor 协程不直接处理队列事件，只做「投递 + 挂起等待本地完成」。

---

## 5. Kotlin 对象的跨边界方式：句柄 + 本地代理

**没有任何路径把 Kotlin 对象交给另一个运行时解引用。** 句柄只是一个能力令牌。

### 5.1 句柄协议

- 对象拥有者：`StableRef.create(obj)` 得到 `void*`，调用 `host_api->handle_new(stableRef, owner, ownerInbox)`
  得到 `KeelHandle`（Host C 句柄表里的槽位地址）。
- 句柄表在 **Host 的 C shim** 里集中管理：`{stable_ref, refcount, owner_module, owner_inbox}`。
  插件通过 `host_api->handle_*` 函数指针操作它；这些都是 C 调用，不会进入 Kotlin。
- 复制句柄必须 `handle_retain`；用完必须 `handle_release`。
- 计数归零时，Host C shim 向 `owner_inbox` 投递 `KEEL_EVENT_DISPOSE`（`subject` = 句柄），
  **由拥有者 Worker 在自己的线程上 `StableRef.dispose()`**。不要在归零时直接调 Kotlin。

### 5.2 代理

```
Host Kotlin:  SessionSnapshot（不可变）
              StableRef → handle
              ── 事件(subject=handle) ──> 插件 Worker
插件 Kotlin:  RemoteSession(handle) : Session
              userId  → 首次访问时发 GET 请求事件，或直接读事件里带的投影结构
              onChange → 注册“回调 id”，Host 侧变化时投递 BUS 事件
```

- **读多写少的上下文**（请求上下文、账户、RBAC）优先在发事件时把需要的字段投影成
  C 结构体 / 字节一起带过去，代理直接读本地副本，零往返。
- **需要实时一致性的对象**只传句柄，代理方法走请求 / 应答事件；不做透明远程对象。
- 代理对象永远是**本运行时的普通 Kotlin 对象**，可以自由持有、放进集合、被 GC。

### 5.3 回调（以及为什么不用 `staticCFunction`）

`staticCFunction` 能生成非捕获的 C 函数指针，但它**不是跨运行时回调的逃生口**：

- `staticCFunction { ... }` 生成的是「属于当前运行时的 Kotlin 函数」，只是类型上表现为
  `CFunction`；线程第一次调用它时会初始化该运行时的 TLS、GC 线程状态。
- 如果 Host 保存插件给的 `staticCFunction` 并在 Host 线程调用，就等价于在 Host 线程进入插件运行时，
  违反 §2。即使函数体只做「入队」也一样 —— 进入函数体之前已经跨进该运行时了。
- 它的正确用途是**同一运行时内部**：把回调交给 C 库（libcurl / UI / 第三方 SDK），
  由该运行时自己的线程回调；上下文经 `user_data` 传 `StableRef`，用完手动 dispose。
- 反向也成立：插件不能把 Host 的 `staticCFunction` 存下来在自己的 Worker 上调用。

跨运行时的回调按下面的协议：

- 回调注册 = `REGISTER_CALLBACK { callbackId, eventType }`；调用 = 投递事件
  `{ subject = callbackId, payload = 参数 }`，拥有者在自己的 Worker 上查回调表执行。
- 回调表由拥有者维护；注销时投递 `UNREGISTER_CALLBACK`，并释放相关句柄。
- 纯 C 回调（内存归还、日志 sink、队列唤醒）可以直接用函数指针，但函数体不得触碰 Kotlin 对象；
  ABI 里的 `KeelEventRelease` 就是这一类，它不是 `staticCFunction`。

### 5.4 调度器与协程

- 每个运行时一个本地 `Scheduler`，绑定自己的 Worker / 定时线程。
- `PluginContext.scope` 是插件 Worker 调度器上的 `CoroutineScope`；只在本线程恢复。
- 跨模块调用：`Messenger.request` 是**本地 `suspend`**——挂起本地协程，把请求事件投递出去；
  应答事件在 Worker 上恢复 continuation。ABI 里没有 `suspend`、没有 `Continuation`。
- 取消：本地 `invokeOnCancellation` → 发送 `CANCEL` 事件 → 对方 Worker 标记并回 `COMPLETE(cancelled)`；
  超时在发起方本地计时，不依赖对方。
- 「安全透传调度器」= 透传**调度语义**（延时、周期、取消、时区），不是透传 dispatcher 对象。

---

## 6. 模块间调用：依赖、查询、命令

业务请求落在 A，A 要从 B 拿数据或让 B 启动任务。两个运行时之间没有对象引用，
所以「调用」被拆成两种形态：**值对象（快照）**与**能力对象（句柄代理）**。

### 6.1 先决定返回什么

| 你要的 | 形态 | 例子 |
| --- | --- | --- |
| 一份数据 | 序列化 DTO（值） | `suspend fun first(userId): QueueInfo` |
| 一个能继续操作的东西 | 句柄代理（能力） | `val queue = ...; queue.start()` |
| 只是通知对方做事 | 命令事件（可带 ack） | `startQueue(userId)` |

`QueueInfo` 是快照：一次往返，之后与 B 的状态无关。
`QueueHandle` 是代理：每次方法调用都回 B 执行，适合长生命周期对象。

### 6.2 构建期：API 工件

B 拆成两个工件：

- `keel-plugin-queue`：实现，产物是 dylib；
- `keel-plugin-queue-api`：接口 + DTO + 客户端代理，KMP 库，只含 commonMain。

A 依赖 `keel-plugin-queue-api`，并在 `plugin.json` 声明：

```json
{ "requires": ["queue"] }
```

Host 按依赖拓扑启用；B 不可用时 A 拒绝启用，而不是半启动。

### 6.3 运行时：`ctx.modules` 拿到的是代理

模块是动态的，**没有 `ctx.modules.moduleB` 这种编译期字段**。按 key 查找：

```kotlin
// keel-plugin-queue-api/commonMain
interface QueueApi {
    suspend fun first(userId: String): QueueInfo
    suspend fun start(userId: String): QueueTaskId
}

val QueueApiKey = ServiceKey<QueueApi>("queue")

// A 模块
val queue = ctx.modules.require(QueueApiKey)   // 本地代理
val info = queue.first(userId)
```

- `require` 找不到时抛 `ServiceUnavailableException`；`get` 返回 `null`。
- **同一模块内**查到的是本地实现（直接调用，零往返）；**跨模块**才是代理。
- 代理内部持有 B 的 module id / inbox；调用点看起来是普通 Kotlin，实际是事件往返。
- 想写成 `ctx.modules.queue` 也可以在 P1 用 KSP 生成扩展属性，但它只是 `require` 的语法糖。

### 6.4 查询：请求 / 应答

```
A.worker: queue.first(userId)
  ↓ QueueClient（API 工件里的代理）
SERVICE_CALL { service="queue", method="first", correlation=42, payload=CBOR(userId) }
  ↓ 直接写入 B.inbox（不经过 Host 中转）
B.worker: 事件泵取出 → 查方法表 → QueueImpl.first(userId) → CBOR(QueueInfo)
  ↓ SERVICE_REPLY { correlation=42, payload=... }
A.worker: 按 correlation 找到挂起的 continuation → 恢复 → 返回 QueueInfo
```

- **调用方法必须是 `suspend`**。A 的 Worker 还要处理 B 的回包；同步阻塞等待会让回包没人处理，必然死锁。
- 默认超时（如 3s）在发起方计时；超时 / 协程取消会发 `CANCEL` 给 B。
- DTO 用 `@Serializable`，载荷 CBOR / Protobuf；不要传对象引用。
- 一次调用只有两次线程唤醒（A→B、B→A），Host 不参与转发。

### 6.5 命令：让 B 启动任务

两种语义，按「是否需要确认」选：

1. **需要确认已启动**：`suspend fun start(userId): QueueTaskId`。返回即表示 B 已受理；
   失败抛异常或返回错误码。
2. **纯通知**：`ctx.server.messenger.post(...)`。at-most-once，丢了不会重试；
   需要失败反馈就订阅 B 的 `EVENT_FAILED`。

长任务：

- `start` 返回 `TaskId`；
- B 通过定向事件推 `PROGRESS` / `DONE` / `FAILED`，`correlation` 或 `subject` 带 `TaskId`；
- A 的 `suspend` 只等到「已受理」，不要在一次 RPC 里等任务跑完。

幂等：命令带 `requestId`，B 按 `(sourceModule, requestId)` 去重；重试不会重复启动。

### 6.6 能力对象（`getUserQueue()` 返回代理）

```kotlin
val queue = ctx.modules.require(QueueApiKey).getUserQueue()  // 返回 QueueHandle 代理
queue.start()
```

- `QueueHandle` 由 A 运行时构造，持有 B 的句柄（`StableRef` 注册后的 `KeelHandle`）；
- 方法调用 = `SERVICE_CALL` + `subject=handle`；
- A 侧代理关闭 / GC 前必须 `release`，B 侧引用归零后 `DISPOSE`；
- 代理里不要缓存可变业务状态，它只是远端对象的本地把手。

### 6.7 失败、环与版本

- **B 崩溃**：B 的 inbox 关闭，A 所有 pending 请求立即以 `ServiceUnavailableException` 结束。
- **同步环**：A→B→A 的同步等待会互相占住 Worker；Host 记录调用深度，超过阈值直接拒绝。
  需要环状协作时用事件，不要用请求 / 应答。
- **版本**：`keel-plugin-queue-api` 的版本与 `plugin.json` 的 `requires` 范围共同决定兼容性；
  Host 在启用时校验，A 不应在运行期探测 B 的方法。
- **权限（P1）**：Host 按 `plugin.json` 声明允许 A 调用 B 的哪些 service / method，
  未声明的调用直接拒绝。

### 6.8 性能预算

| 操作 | 路径 |
| --- | --- |
| 查询 | A 入队 + B 出队 + B 入队 + A 出队（2 次唤醒） |
| 命令（ack） | 同上 |
| 命令（post） | A 入队 + B 出队（1 次唤醒） |

小载荷优先 CBOR；不要用 JSON。跨模块调用是微秒级边界开销，业务耗时远大于它时无需优化。

---

## 7. 事件总线（EventBus）

- **本地快路径**：本运行时内的订阅同步执行，按 `EventPriority` 排序；与 Bukkit 一致，
  `MONITOR` 最后执行且不应修改事件。
- **跨模块**：插件订阅时向 Host 投递 `BUS_SUBSCRIBE { eventTypeId }`；
  Host 维护「事件类型 → 模块列表」，发布时逐个 inbox 投递 `KEEL_EVENT_BUS`。
- 跨模块事件是**异步通知**：不保证优先级、不保证全局顺序、默认不可取消。
  需要「可取消 / 需聚合结果」的语义走请求 / 应答，不用事件总线。
- 订阅生命周期跟随插件：插件 disable 时 Host 清理它的订阅；插件崩溃后丢弃其队列。

---

## 8. 服务注册表与插件目录

- `ServiceRegistry` 本地注册 / 查找；`ServiceKey.id` 是跨模块唯一标识。
- 跨模块 `get` 返回**本地代理**：句柄 + 方法事件。代理的 `T` 只是类型承诺，
  Native 上没有反射，不做运行期类型校验。
- `PluginRegistry` 只读（id / metadata / enabled）；加载、启用、禁用属于 Host 运维面，
  不暴露给插件，避免插件互相操纵生命周期。

---

## 9. 版本与二进制兼容

- `KeelHostApi` / `KeelPluginApi` 首字段固定为 `{abi_version, struct_size}`。
- **只追加**：新能力加在表尾；旧字段顺序与签名永不修改。
- 插件在 `plugin.json` 声明 `abiVersion`；Host 启动时比对主版本，不匹配拒绝加载并给出可读错误。
- 可选能力用 feature 位（`uint64_t features`）表达，不靠「函数指针是否为空」猜。
- 符号可见性：Host 只调用 `keel_plugin_bootstrap` / `keel_plugin_shutdown`；
  Kotlin 工厂只由 bootstrap 在 Worker 线程调用。队列、句柄、分配器全部 hidden，
  防止 Host 与插件同名符号互相绑定。
- 调用约定统一 `cdecl`；三个目标都是 64 位，指针宽度一致。

---

## 10. 内存与错误

- **谁分配谁释放**：载荷由发送方分配，接收方调用发送方的 release；
  句柄槽由 Host C shim 分配，只由 Host C shim 释放。
- **禁止跨 CRT 释放**：Windows 上不同 DLL 的 `malloc/free` 不能配对。
- Kotlin `ByteArray` 不 pin 着交给对方：复制进 C 缓冲区再入队；`usePinned` 只覆盖单次 C 调用。
- 错误不跨 ABI 抛异常：函数返回 `KeelStatus`；业务错误作为 `COMPLETE` 事件的
  `flags + payload` 回传（错误码 + UTF-8 消息）。

---

## 11. 与 Bukkit / Spigot / Forge 的对应关系

| Bukkit / Spigot / Forge | Keel | 差异 |
| --- | --- | --- |
| `PluginManager` | Host 的插件加载器 + `PluginRegistry`（只读） | 加载 / 卸载是运维面，不暴露给插件 |
| `Plugin` / `JavaPlugin` | `Plugin` + `PluginMetadata` | 生命周期相同；入口是 C 符号，不是类名反射 |
| `EventBus` / `Listener` | `EventBus` + `EventHandler` + `EventPriority` | 本地同步；跨模块退化为异步通知 |
| `ServicesManager` | `ServiceRegistry` + `ServiceKey` | 跨模块拿到的是代理，不是实例 |
| `BukkitScheduler` | `Scheduler` + `TaskHandle` | 每运行时一个；不跨运行时共享 |
| `Messenger` / plugin channels | `Messenger` / `Message` | 载荷二进制；请求 / 应答显式建模 |
| Forge `IModBusEvent` | `EventBus` 的 `EventType` | 不做事件总线分域（P2 再评估） |

---

## 12. 实施顺序

### DEMO（可行性）

1. 顶层 `keel-abi`：C 源码 + 每目标静态库（`libkeel_abi.a`）。
   先做队列（定长环形缓冲、mutex/cond、超时）与 `keel_event_release_default`，
   用纯 C 的收发测试程序验证，不接 Kotlin。
2. `keel_plugin_bootstrap` / `keel_plugin_shutdown`：只建 inbox、起事件泵线程、join，
   全程不触碰 Kotlin。
3. `hello` 插件改成 Worker 模型：导出 `plugin.json.entry`，收到 `START` 时构造 Plugin、
   回填 `on_event`，随后收事件。
4. Host：加载 dylib、解析 entry、`bootstrap`、投递 `START`、等 `COMPLETE`；
   失败只记日志，不影响其它插件。
5. 验收：`bootstrap` 返回时插件 Kotlin 尚未运行；`worker_main` 只在泵线程执行；
   Host 线程从未进入插件运行时；队列在 10 分钟内稳定。

### P0

- 句柄 retain/release + 延迟 dispose；请求 / 应答 / 取消 / 超时。
- 本地 EventBus + 跨模块订阅；ServiceRegistry 本地版。
- `Messenger` 的 suspend 桥接与 Worker 调度器。
- 结构体投影（RequestContext）与字节载荷（Protobuf）。

### P1

- ABI 版本协商与 feature 位；队列背压策略与指标。
- 代理生成（减少手写样板）；两阶段批量序列化。
- 健康检查、死锁 / 泄漏检测、崩溃隔离。

---

## 13. 禁止清单

- ❌ 把 Kotlin 对象、lambda、`Continuation`、`CoroutineDispatcher` 直接穿过 ABI。
- ❌ 用 `staticCFunction` 把一方的 Kotlin 回调交给另一方调用（仍然是原运行时的 Kotlin 代码）。
- ❌ 在 Host 线程调用插件 `@CName`；在插件线程调用 Host Kotlin。
- ❌ 用 `@CStruct` 手写布局绕过共享头文件。
- ❌ 按值传大结构体；用 `CValue` 逐字段访问热路径。
- ❌ 跨运行时 `free` 对方分配的内存；跨 CRT 释放。
- ❌ 在事件回调里执行长业务逻辑（回调只入队 / 只唤醒）。
- ❌ 依赖跨模块事件的顺序或优先级。

---

*本文与 `keel-abi/src/nativeCpp/keel_abi.h` 同步维护；改 ABI 先改此文档与头文件，再改实现。*
