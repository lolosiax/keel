# Keel 零样板模块互访：Gradle 插件 + KSP

> 目标：模块之间可以像普通 Kotlin 一样互相调用（参数、返回值、回调），
> 开发者**不写代理、不写分发器、不写编解码、不写 plugin.json、不手建 API 工件**。
> 机制参考 protobuf：接口是唯一手写产物，其余全部构建期生成。
> 运行时约束见 [abi-design.md](abi-design.md)；本文件只讲「怎么让开发者无感」。

---

## 1. 结论

开发者最终只需要：

1. 在 `api` 子项目里写一个 `@ModuleService` 接口（加 DTO）；
2. 在 `impl` 子项目里写实现类 + 一个 `@KeelPluginMain` 类；
3. 根 `build.gradle.kts` 里写一个 `keelPlugin { pluginId = "queue" }`（`pluginId` 为目标形态；当前手写 `plugin.json`）。

其余**目标形态**由 `moe.lolosia.keel.plugin` Gradle 插件 + `keel-plugin-ksp` 生成
（当前 `keel-plugin-ksp` 只生成 Worker 入口，其余尚未实现）：

- `api` / `impl` 两个子项目的构建脚本、发布配置；
- 服务 Key、客户端代理、`ctx.queue` 访问器；
- 服务端分发器、服务注册、Worker 入口；
- 参数 / 返回值的 CBOR 编解码；
- 回调的注册 / 调用 / 注销；
- `plugin.json`（id / version / requires / services）。

**为什么仍然有 `api` 子项目**：Kotlin/Native 会把依赖的 klib 静态链进调用方二进制。
如果接口和实现放在同一个 klib，A 会把 B 的实现也链进自己的进程，运行期再加载 B 的 dylib
就变成两份代码。`api` 是隔离边界，但它由插件生成和配置，开发者只往里放接口。

---

## 2. 开发者视角

### 2.1 目录（目标形态，由 `keel init` 模板生成；当前是单项目）

```
keel-plugin-queue/
├── settings.gradle.kts        # 引入 :api / :impl，由 settings 插件提供（尚未实现）
├── build.gradle.kts           # 只有 plugins + keelPlugin { pluginId = "queue" }
├── api/
│   ├── build.gradle.kts       # 生成/模板，不手改
│   └── src/commonMain/kotlin/moe/lolosia/keel/plugins/queue/api/
│       └── QueueApi.kt        # ← 开发者写：接口 + DTO
└── impl/
    ├── build.gradle.kts       # 生成/模板，不手改
    └── src/commonMain/kotlin/moe/lolosia/keel/plugins/queue/
        └── QueuePlugin.kt     # ← 开发者写：实现 + 入口
```

### 2.2 B 模块：接口 + 实现

```kotlin
// api/QueueApi.kt
@ModuleService("queue")
interface QueueApi {
    fun first(userId: String): QueueInfo
    suspend fun start(userId: String): TaskId
    fun watch(userId: String, listener: (QueueInfo) -> Unit): Subscription
}

@Serializable
data class QueueInfo(val position: Int, val waiting: Int)

@Serializable
data class TaskId(val value: String)
```

```kotlin
// impl/QueuePlugin.kt
@ModuleServiceProvider
class QueueApiImpl(private val ctx: PluginContext) : QueueApi {
    override fun first(userId: String): QueueInfo = ...
    override suspend fun start(userId: String): TaskId = ...
    override fun watch(userId: String, listener: (QueueInfo) -> Unit): Subscription = ...
}

@KeelPluginMain
class QueuePlugin : Plugin {
    override fun onEnable() { /* 可选 */ }
}
```

`@ModuleServiceProvider` 的构造器可以是 `()` 或 `(PluginContext)`，KSP 自动选。

### 2.3 A 模块：声明依赖并调用

```kotlin
// A/build.gradle.kts
keel {
    pluginId = "foo"
    requires("queue")        // 自动加 keel-plugin-queue-api 依赖 + plugin.json requires
}
```

```kotlin
// A 的业务代码
class FooPlugin : Plugin {
    private lateinit var ctx: PluginContext

    override fun onLoad(context: PluginContext) { ctx = context }

    override fun onEnable() {
        val info = ctx.queue.first("u1")                 // 普通调用，返回值
        ctx.queue.watch("u1") { q -> println(q.position) } // 回调
        ctx.scope.launch { ctx.queue.start("u1") }       // suspend 调用
    }
}
```

- `ctx.queue` 是 KSP 生成的扩展属性，来自 B 的 `api` 工件。
- 同一模块内调用 B 的服务时，`ctx.queue` 直接返回本地实现，零往返。
- 跨模块时返回生成的客户端，调用点与普通 Kotlin 完全一致。

---

## 3. 构建期流水线

```
（目标，尚未实现）moe.lolosia.keel.settings → settings 插件：include(":api", ":impl")
moe.lolosia.keel.plugin                   → 根插件：keel { } DSL，配置两个子项目
        ├─ :api  → KMP 库 + KSP + 发布 keel-plugin-<id>-api
        └─ :impl → KMP sharedLib + KSP + 发布 keel-plugin-<id>
```

### 3.1 Gradle 插件做什么

`moe.lolosia.keel.plugin`：

1. 应用 `org.jetbrains.kotlin.multiplatform`、`kotlin.plugin.serialization`、KSP；
2. 给 `:api` 加 `keel-api` + `keel-plugin-ksp` 依赖，配置 KMP 库与 `maven-publish`；
3. 给 `:impl` 加 `:api` + `keel-api` + `keel-plugin-ksp`，配置 `sharedLib`；
4. 注册 `keelManifest`：把 `pluginId` / `version` / `requires` / `services` 写成
   `build/generated/keel/resources/plugin.json`，自动并入 `impl` 资源；
5. 配置发布：`keel-plugin-<id>`（impl）与 `keel-plugin-<id>-api`（api）；
6. 给消费方 `requires("queue")` 自动加 `implementation("moe.lolosia.keel.plugins:keel-plugin-queue-api:<版本>")`。

`moe.lolosia.keel.settings`（目标形态，尚未实现；只写一行）：

- 根据目录自动 `include(":api")` / `include(":impl")`；
- 统一版本目录与仓库。

### 3.2 KSP 分工

| 子项目 | 生成物 |
| --- | --- |
| `:api` | `<Service>Key`、`<Service>Client`、`PluginContext.<name>` 访问器、参数 / 返回值包装、回调描述符 |
| `:impl` | `<Service>Dispatcher`、`keelRegisterGeneratedServices(ctx)`、`keel_plugin_worker_main` |

KSP 只扫描**当前模块**的源码：`:api` 看到接口，`:impl` 看到实现与 `@KeelPluginMain`。
消费方 A 不需要对 B 的接口跑 KSP，直接用 B 发布好的客户端。

### 3.3 生成物示例（`api`）

```kotlin
val QueueApiKey = ServiceKey<QueueApi>("queue")

val PluginContext.queue: QueueApi
    get() = modules.get(QueueApiKey)
        ?: QueueApiClient(modules.caller(QueueApiKey), callbacks)

class QueueApiClient(
    private val caller: ServiceCaller,
    private val callbacks: CallbackRegistry,
) : QueueApi {
    override fun first(userId: String): QueueInfo =
        caller.callBlocking("first", FirstArgs(userId).encode()).decode()

    override suspend fun start(userId: String): TaskId =
        caller.call("start", StartArgs(userId).encode()).decode()

    override fun watch(userId: String, listener: (QueueInfo) -> Unit): Subscription {
        val id = callbacks.register { bytes -> listener(bytes.decode()) }
        val sub = caller.callBlocking("watch", WatchArgs(userId, id).encode()).decode<SubId>()
        return GeneratedSubscription(callbacks, id) {
            caller.callBlocking("unwatch", UnwatchArgs(sub).encode())
        }
    }
}
```

### 3.4 生成物示例（`impl`）

```kotlin
class QueueApiDispatcher(
    private val impl: QueueApi,
    private val callbacks: CallbackRegistry,
) {
    fun dispatch(method: String, payload: ByteArray, callerModule: Long): ByteArray = when (method) {
        "first" -> impl.first(payload.decode<FirstArgs>().userId).encode()
        "start" -> ... // suspend：交给运行时的派发器，完成后再回包
        "watch" -> {
            val args = payload.decode<WatchArgs>()
            val invoker = callbacks.invoker(callerModule, args.callbackId)
            impl.watch(args.userId) { info -> invoker.invoke(info.encode()) }
            SubId(...).encode()
        }
        else -> throw IllegalArgumentException(method)
    }
}

@CName("keel_plugin_worker_main")
fun keelPluginWorkerMain(...) {
    val ctx = KeelRuntime.createContext(...)
    val plugin = QueuePlugin()
    plugin.onLoad(ctx)
    keelRegisterGeneratedServices(ctx)   // 生成：register(QueueApiKey, QueueApiImpl(ctx))
    plugin.onEnable()
    KeelRuntime.runEventLoop(ctx)
    plugin.onDisable()
}
```

---

## 4. 调用语义

### 4.1 普通方法：阻塞 + 重入事件泵

生成的非 `suspend` 方法调用 `ServiceCaller.callBlocking`：

```
send SERVICE_CALL(correlation = 42)
loop:
    if 收到 correlation=42 的 REPLY: 返回
    else: dispatchOneEvent()      // 处理回调、别人的请求、取消等
```

- 只能在模块 Worker 线程调用；其它线程直接抛 `IllegalStateException`。
- 等待期间会处理其它事件，所以回包不会被自己饿死；代价是 Worker 不推进别的任务。
- 重入意味着「调用期间可能跑别的 handler」；不要对共享可变状态做假设。
- 调用深度上限（默认 8）防止 A→B→A 环无限重入；需要环状协作时改用事件。

### 4.2 `suspend` 方法

生成客户端直接 `caller.call(...)`：挂起本地协程，不阻塞 Worker；应答事件到达时在
Worker 的调度器上恢复。绝不跨 ABI 传 continuation。

### 4.3 回调

```
A: watch(userId) { q -> ... }
   callbacks.register → 7
   SERVICE_CALL watch(userId, 7)
B: impl.watch(userId, listener)
   listener 是生成的包装：invoker(source=A, 7).invoke(encode(info))
A Worker: CALLBACK_INVOKE { owner=A, id=7, payload } → 查表 → 调用用户 lambda
```

- 回调永远在**注册方自己的 Worker** 上执行，串行、有序。
- `Subscription.cancel()` 注销本地 id，并调用生成的 `unwatch`。
- 插件 disable 时运行时统一注销剩余回调。
- 回调参数类型必须可序列化；返回值目前只支持 `Unit`（P1 再扩）。

### 4.4 为什么不用 `staticCFunction`

见 [abi-design.md](abi-design.md) §5.3：它仍是原运行时的 Kotlin 代码，跨线程调用会进错运行时。
生成的回调协议只传数字 id + 字节，不传函数指针。

---

## 5. 类型与序列化

支持：

- `Unit`、`Boolean`、整数 / 浮点、`String`、枚举、`ByteArray`；
- `@Serializable` 类 / 数据类、`List` / `Set` / `Map`、可空类型；
- 函数类型参数 `(T) -> Unit`；
- 返回 `Subscription`（仅回调方法）。

生成器会为每个方法生成一个参数包装和一个返回值包装（`@Serializable`），
用 kotlinx.serialization CBOR 编解码；Gradle 插件自动应用序列化插件。

不支持：

- 不可序列化的任意对象 / 接口返回值 → 编译期报错，提示改用 `@Serializable` DTO 或（P1）句柄；
- `suspend` 函数类型参数（P1）；
- 泛型方法（P1）。

---

## 6. 依赖、版本与失败

- A 依赖 `keel-plugin-queue-api` 的版本范围；Host 启动时校验 `@ModuleService(version)`。
- `requires("queue")` 同时写入 `plugin.json`，Host 按拓扑启用。
- B 未启用 / 崩溃：A 的 `ctx.queue` 调用抛 `ServiceUnavailableException`；
  `get` / `require` 不缓存失效状态。
- 超时：生成客户端用统一默认值（3s），可在 `keel { callTimeoutMs = ... }` 覆盖。
- 幂等：命令方法建议带 `requestId` 参数；B 侧按 `(sourceModule, requestId)` 去重。

---

## 7. 性能

| 调用 | 路径 |
| --- | --- |
| 普通方法 | 2 次线程唤醒（请求 + 应答） |
| `suspend` 方法 | 同上，但不阻塞 Worker |
| 回调 | 1 次唤醒（被调方 → 注册方） |

载荷 CBOR；小消息微秒级。大对象用 `ByteArray`，不要塞 JSON。

---

## 8. 实施阶段

### DEMO

- `moe.lolosia.keel.settings` / `moe.lolosia.keel.plugin` 最小版：目录约定 + KSP + plugin.json
  （`moe.lolosia.keel.settings` 尚未实现；当前是单项目 + 手写 `plugin.json`）；
- `keel-plugin-ksp`：单个 `@ModuleService`，值参数 / 返回值，生成 Key / Client / Accessor / Dispatcher / Worker 入口
  （当前只生成 Worker 入口）；
- 普通方法用阻塞重入泵；无回调；
- 验收：A、B 各只写 2 个 Kotlin 文件 + 1 个 Gradle 块，调用跑通。

### P0

- 回调 + `Subscription`；
- `suspend` 方法；
- `requires(...)` 自动依赖与版本校验；
- 类型检查与清晰报错；
- `keel init` 模板（生成 api/impl 骨架）。

### P1

- 句柄返回（活对象代理）；
- 泛型 / `suspend` 回调；
- KSP 生成 `ctx.modules.<id>` 访问器；
- 权限声明与 Host 侧调用白名单；
- 方法级超时 / 重试策略。

---

## 9. 验收清单

- [ ] 新增一个 B 模块：只写接口 + 实现 + `keel {}`，不写任何代理 / 分发器 / plugin.json。
- [ ] A 模块只加 `requires("queue")` 就能 `ctx.queue.first(...)`，IDE 有完整补全。
- [ ] 普通方法、`suspend` 方法、回调三种形态都能编译并运行。
- [ ] A 的可执行文件里不包含 B 的实现代码（只含 `api` 工件）。
- [ ] 不支持的类型在编译期报错，并给出可操作的提示。
- [ ] 无手写 `@Serializable` 包装、无手写 Maven 发布配置。

---

*本文件描述目标形态；其中 `keel-plugin-ksp` / `keel-router-ksp` 与 Gradle 插件按「实施阶段」逐项落地。*
