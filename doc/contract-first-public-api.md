# Contract-first `@PublicApi`：去掉镜像、Context 作为共享契约

> 状态：讨论稿（2026-11-21）。
> 本文只讨论 `@PublicApi` 的 contract-first 改造与 `Context` 的共享方式；
> 数据库 / ORM 不在本文范围，见 [database-orm-plan.md](database-orm-plan.md)。
> 与现有实现冲突时以代码为准，并回填本文。
>
> **落地进度（2026-11-21）**：`Context` 组件化切片已实现（`Component` / `Context` /
> `LocalContext` / `WebRequestComponent` / `WebContext` + `ContextExt.kt` 语法糖）；
> `@LocalMember` 与生成器对本地成员（不进入 dispatcher、代理本地实现）的支持已落地，
> `keel-plugin-ksp` 同时补了重复 typeId 检查。
> `keel.api.mode`（默认 `MIRROR`）、core / contract-first 生成模式已落地；
> `keel-api` 已接 KSP，`ContextKey` / `ContextProxy` / `WebContextProxy` /
> `WebRequestComponentProxy` / `ComponentProxy` 已生成并随仓库提交。
> `@PublicApiProvider` 注解已添加，但 provider 发现 / 注册与跨插件 Context 传递**尚未接线**。

## 0. 决策摘要

- **新增 contract-first 模式**：契约手写在 api 模块里，KSP 不再生成同 FQN 的镜像。
- **Context = Components + 本地字段**：`Context` 核心只暴露公共组件方法；
  本地字段（`plugin` / `local` / `server` / `config`）放在不进 api 工件的 `LocalContext`。
- **Component 基接口**：`@PublicApi interface Component`；公共组件继承它；本地组件不标 `@PublicApi`，也不进入公共方法。
- **不同业务挂扩展函数**：Web 能力在 `WebRequestComponent` / `WebContext`，
  控制器通过 `ctx.request()` / `ctx.userId()` 这类扩展使用；数据库等业务同样以 `Context` 扩展提供。
- **`ContextImpl` 留在插件内部**：`internal` + `@PublicApiProvider(Context::class)`，只生成 dispatcher / 注册。
- **生成物归 api 模块**：proxy / Key / 访问器 / DTO / 值类型由 KSP 生成在 api 模块，其他模块直接导入。
- **provider 侧归 impl 模块**：dispatcher / 注册 / shadow 生成在当前 impl 模块。
- **不合并 klib**：Kotlin/Native 没有可靠的 klib 合并；重复 FQN 也不是合并能解决的问题。
- **handle 就是对象身份**：不引入 `contextId`。谁创建谁持有，Host 也不例外。
- **MIRROR 始终是默认模式**：只有插件作者要手写 API 时才切 `CONTRACT_FIRST`；`keel-api` 始终不是 MIRROR。
- **不允许混用构建**：同一个构建不能同时出现 MIRROR 与 CONTRACT_FIRST 的类型；坐标不变。
- **签名校验与 EventBus 暂缓**：本版本不做，后续再补。

---

## 1. 问题

### 1.1 Context 已经在 `keel-api` 里

`Context` 是所有插件共享的请求 / 调用上下文契约（不一定是 Web），位于
[Context.kt](../keel-api/src/commonMain/kotlin/moe/lolosia/keel/api/web/Context.kt)。
如果按当前 v2 的 `impl → api 镜像` 生成方式，再生成一个
`moe.lolosia.keel.api.web.Context`，就会和 `keel-api` 里的类型重名。

换目录不解决问题：Kotlin 的类型身份是 **FQN**，不是文件路径。

### 1.2 镜像层的代价

当前 v2 的决策是“impl 唯一真源，api 全生成”，代价是：

- 两遍生成：impl KSP 提取模型 → api 子工程 KSP 生成镜像；
- 镜像与 impl 可能漂移；
- 生成物要提交（V1 提交制）或依赖特殊构建顺序（V2 自动制）；
- 跨模块类型身份是“镜像”，IDE 跳转和类型关系不如直接手写契约直观；
- `Context` 这种 core 契约没有合适的 api 子工程落点。

### 1.3 现有开关不是 Manual

当前 KSP 并不会读一个 `Manual` 配置。实际机制是：

- `keel.api.dir` KSP 参数存在时，`KeelPublicApiGenerator.emitApi(...)` 生成镜像；
- 不存在时不生成，并对非 `@PublicApiHandler` 的类型打 warning；
- `@PublicApiHandler` 是**类型级**逃生口，对应 `TypeModel.manual`，不是模块级 Manual。

相关代码：

- [KeelPluginWorkerProcessor.kt](../keel-plugin-ksp/src/main/kotlin/moe/lolosia/keel/ksp/plugin/KeelPluginWorkerProcessor.kt)
- [KeelPublicApiGenerator.kt](../keel-plugin-ksp/src/main/kotlin/moe/lolosia/keel/ksp/plugin/KeelPublicApiGenerator.kt)

### 1.4 不合并 klib

Kotlin/Native 的 klib 不是普通 zip，官方没有“合并两个 klib”的稳定任务。自己写 Gradle task 解包再拼：

- 会破坏增量编译、IDE 索引和依赖解析；
- 两个 klib 里如果都有 `Context`，合并后仍然是重复声明；
- cinterop、`api(...)` 关系和发布都会变复杂。

两个 klib 同时在 classpath 上是正常的；真正要解决的是**让一个类型只有一个声明源**。

---

## 2. 目标与非目标

### 目标

1. `Context` 只有一个 FQN，位于 `keel-api`。
2. `Context` 核心是 **Components + 本地字段**，不假设所有 Context 都有 Web 请求。
3. `Component` 作为公共组件基接口标 `@PublicApi`；Web 请求是 `WebRequestComponent`。
4. 去掉 `Context` 的生成镜像，同时保留 provider / consumer 的句柄调用能力。
5. KSP 在 api 模块生成 proxy / Key / 访问器 / DTO，在 impl 模块生成 provider 代码，不再依赖镜像的两遍生成。
6. 插件私有契约仍可共享，但契约由开发者手写，不再生成镜像。
7. 保持现有 handle / `SharedRef` / `SharedCall` 的运行时语义。
8. 不同构建可以分别选 MIRROR / CONTRACT_FIRST；同一个构建不允许混用。

### 非目标

- 运行期卸载插件代码。
- 合并 klib。
- 用反射发现类型。
- 把 Kotlin 对象直接跨 DLL 传递。
- 用 `contextId` 代替 handle。
- 数据库 / ORM（见另一份文档）。

---

## 3. 术语

| 术语 | 含义 |
| --- | --- |
| contract | 手写的共享契约（接口 / open class / 值类型），消费方和提供方都依赖它 |
| contract artifact | 存放 contract 的共享工件；`Context` 用 `keel-api`，插件私有契约用 `api/` 或独立库 |
| impl | 插件的具体实现；可以是 `internal`，放在 `.impl` 包 |
| provider | 提供 impl 的插件；KSP 生成 dispatcher / 注册 |
| consumer | 调用契约的插件；直接 import api 模块里的 proxy / Key / 访问器 |
| Component | 挂在 Context 上的公共组件基接口；`@PublicApi`，可跨插件；本地组件不标它 |
| mirror | 当前 v2 从 impl 生成到 api 的“同 FQN 镜像” |
| handle | Host 分配的对象句柄；对象级唯一身份 |

---

## 4. 架构

### 4.1 contract-first

```text
api 模块（手写契约 + KSP 生成的 proxy / Key / 访问器）
        ↑                         ↑
        │                         │
     provider                  consumer
  impl + dispatcher         直接 import api
        │                         │
        └──── SharedCall ─────────┘
                 handle
```

- 契约是唯一真源；impl 依赖契约，而不是契约从 impl 生成。
- provider 侧只生成 dispatcher / 注册；impl 可以 `internal`。
- consumer 不生成代码，只依赖 api 模块。
- 运行时仍然通过 handle 调用，不传 Kotlin 对象。

### 4.2 依赖方向会反转

当前规则是：

```text
impl 不依赖自己的 api；api 从 impl 生成
```

contract-first 是：

```text
impl 依赖 contract artifact；contract 手写
```

这是一次架构方向变化，需要同步修改：

- `AGENTS.md` 的“impl 不依赖 api”约定；
- [public-api-design.md](public-api-design.md) 的生成管线。

本版本不新增 `keelApiCheck` 的签名 diff；impl 是否实现契约由 Kotlin 编译器保证，
签名 / 版本校验后续再补。

### 4.3 注解角色

建议把“契约”和“提供者”分开：

```kotlin
// 公共组件基接口：手写契约，不生成镜像
@PublicApi
interface Component {
    @PublicApi suspend fun id(): String
}

// Context 核心：只暴露组件，不假设一定有 Web 请求
@PublicApi(id = "keel.context")
interface Context {
    @PublicApi suspend fun component(id: String): Component?
    @PublicApi suspend fun components(): List<Component>
}

// 提供者：只生成 dispatcher / 注册
@PublicApiProvider(Context::class)
internal class ContextImpl(...) : Context
```

注解建议：

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class PublicApiProvider(vararg val contracts: KClass<*>)
```

`vararg` 让一个实现可以同时提供多个契约（如 `Context` 与 `WebContext`），
单契约写法 `@PublicApiProvider(Context::class)` 不变。

规则：

- `@PublicApi` 标在契约上 = 共享契约，不生成镜像；
- `@PublicApiProvider` 标在 impl 上 = 生成 provider dispatcher / 注册；
- 同一个类型不能同时被当成“契约”和“待镜像的 impl”；
- `@PublicApiHandler` 保留为手写逃生口；
- `@Singleton` 继续表示单例提供者，可与 `@PublicApiProvider` 组合；
- `@KeelPluginMain` 只是插件入口语法糖；`keel-api` 不是插件，不需要它，KSP 的 contract 处理也不能依赖它；
- **源码最小化**：手写契约与 impl，proxy / Key / 访问器 / DTO / 值类型 / shadow 都交给 KSP；
- **DTO / 值类型仍由 KSP 生成**：源码只写最少声明，能自动生成的不要手写；
- **shadow 仍由 KSP 生成**：手写契约不意味着手写 shadow。

### 4.4 KSP 模式

新增 KSP 参数：

```kotlin
ksp {
    arg("keel.api.mode", "mirror") // 默认；要手写 API 时改成 contract-first
}
```

| 模式 | 行为 |
| --- | --- |
| `MIRROR`（默认） | 保持现状：impl 的 `@PublicApi` 生成到 api 模块 |
| `CONTRACT_FIRST` | 不生成镜像；api 模块生成 proxy / Key / 访问器 / DTO；impl 模块生成 dispatcher / 注册 / shadow |

约束：

- `MIRROR` **始终存在**，是默认模式，不会在后续版本移除；
- 只有插件作者要手写 API 时才切 `CONTRACT_FIRST`；
- `keel-api` 始终是 `CONTRACT_FIRST`，不走 MIRROR；
- **同一个构建不允许混用**：不能一部分类型走 MIRROR、另一部分走 CONTRACT_FIRST；
- `keel.api.dir` 只在 `MIRROR` 模式使用；
- 模块级模式决定该构建的生成方式，类型级角色由 `@PublicApi` / `@PublicApiProvider` 决定；
- 工件坐标不变，仍使用现有 `keel-plugin-<id>` / `-api` 坐标。

api 模块只有两种形态：

1. **MIRROR 生成的 api**：impl 配 MIRROR，KSP 生成 api；`api/` 不允许手改。
2. **CONTRACT_FIRST 的 api**：api 包是手写契约，配置为 CONTRACT_FIRST；impl 导入它并依赖它。

不存在“生成的 api 又配 CONTRACT_FIRST”这种混合形态。

### 4.5 生成落点

| 生成物 | 落点 | 说明 |
| --- | --- | --- |
| contract | api 模块 | 手写；这是唯一真源 |
| proxy / Key / 访问器 | api 模块 | KSP 生成；其他模块直接导入 |
| DTO / 值类型 | api 模块 | KSP 生成；源码只写最少声明 |
| provider dispatcher | impl 模块 | KSP 生成；与 impl 同模块，能访问 `internal` |
| provider 注册 | impl 模块的 `KeelPluginPublicApi.setup` | 现有生成入口 |
| shadow | impl / provider 模块 | KSP 生成，优先自动生成 |

- **没有“每个 consumer 各生成一份”**：consumer 只依赖 api 模块，使用其中已有的 proxy / Key / 访问器。
- `Context` 的 api 模块就是 `keel-api`；`ContextImpl` 所在插件只生成 provider 侧。

api 模块的 KSP 生成物落在 api 模块自己的 `commonMain` / 目标输出，由 api 模块构建负责；
consumer 不生成这些文件，只 import api 模块的产物。

---

## 5. Context

### 5.1 契约形态

Context 的核心是 **Components + 本地字段**，不是“Web 请求上下文”：

```kotlin
@PublicApi
interface Component {
    // 本地生命周期字段：非 null 表示已挂载；@LocalMember 让它不进入跨插件契约面
    @LocalMember
    var context: Context?

    @PublicApi suspend fun id(): String

    // 挂载期间调用无效果；Context.destroy 会先解绑再调用
    @PublicApi suspend fun destroy() {
        if (context != null) return
    }
}

@PublicApi(id = "keel.context")
interface Context {
    @PublicApi suspend fun components(): List<Component>
    @PublicApi suspend fun component(id: String): Component?
    @PublicApi suspend fun addComponent(component: Component)
    @PublicApi suspend fun removeComponent(id: String): Component?
    @PublicApi suspend fun destroy()
}

// 本地成员：不标 @PublicApi，不进入跨插件面
interface LocalContext : Context {
    val plugin: PluginContext
    val local: MutableMap<String, Any?>
    val server: KeelServer get() = plugin.server
    val config: Config get() = plugin.config
}
```

- `component(id)` 以 `Component.id()` 为查询键；
- `addComponent` 随时可调用（任意插件、任意时刻），同 id 替换；挂载时把
  `component.context` 指向当前 Context，替换掉的旧组件会被自动解绑；
- `removeComponent` **只解除挂载**（清空 `component.context`），返回被移除的实例，不调用 `destroy`；
  是否销毁由调用者决定，remove 后忘记销毁导致泄漏也是调用者的责任；
- `Component.destroy()` 默认实现是 `if (context != null) return`：**挂载期间第三方调用无效果**；
  有资源的实现覆盖时要保留这个判断（忘了写就是实现方的 bug）；
- **`Context.destroy()` 负责级联释放**：先清空组件表并解绑全部组件，再按挂载逆序调用
  `destroy()`；持有方要像 try-with-resources 一样保证它被执行（例如 `finally`），重复调用是空操作；
- 本地字段放在独立的 `LocalContext`：现有 KSP 对接口是「全部成员导出」，
  把 `plugin` 这类类型写在 `@PublicApi Context` 上会被将来的 proxy 生成器拖进契约；
  个别确实要挂在契约类型上的本地状态（如 `Component.context`）用 `@LocalMember` 显式标注：
  KSP 不生成它的跨界分发，生成的代理在本地用 `null` 初始化；
- **本地成员不靠「接口一律不导出」的隐式规则**：必须逐成员标 `@LocalMember`，
  需要跨界读取的状态写成 suspend 访问器（`suspend fun getX()`），不要导出属性；
- 控制器拿到的 `ctx` 静态类型仍是 `Context`，本地字段与业务能力都由扩展补齐。

Web 请求只是其中一个组件，不是所有 Context 都有：

```kotlin
@PublicApi
interface WebRequestComponent : Component {
    @PublicApi suspend fun request(): HttpRequest
    @PublicApi suspend fun pathParameters(): Map<String, String>
    @PublicApi suspend fun user(): UserSnapshot?

    companion object { const val TYPE_ID = "keel.web.request" }
}

@PublicApi
interface WebContext : Context {
    @PublicApi suspend fun webRequest(): WebRequestComponent?
}
```

业务代码不直接拼组件查询，而是用 `ContextExt.kt` 里的扩展：

```kotlin
val Context.plugin: PluginContext            // as? LocalContext
val Context.config: Config                   // 同上
suspend fun Context.request(): HttpRequest   // requireWebRequest()
suspend fun Context.user(): UserSnapshot?
suspend fun Context.userId(): String?
suspend fun Context.query(name: String): String?
suspend fun Context.hasPermission(permission: String): Boolean
```

约束：

- **Components 不删**：Context 可以用于 Web、定时任务、控制台、插件内部调用等场景；
- `Component` 是公共组件的基接口，标 `@PublicApi`；
- 公共组件接口继承 `Component`；本地组件不标 `@PublicApi`，放在 `Context.local`；
- 接口上禁止导出属性，公共属性写成 `suspend fun getX()`；
  纯本地的状态用 `@LocalMember` 标注（见 §5.1）；
- 禁止 `Any`、泛型方法、可变集合、函数类型；
- **公共组件用显式类型化方法访问**：`Context` 只提供 `Component` 基类查询；
  具体组件由 `WebContext.webRequest()` 这类子接口 / 方法暴露；
  不要靠 `component(id) as XxxComponent` 跨插件强转，proxy 不保证实现具体接口；
- `Session` / `user` 属于组件（或 `WebRequestComponent` 的方法），不是 Context 核心字段；
- 不采用 `PublicContext` 拆分，也不等 open class 属性 P2；本版本直接用方法形态。

### 5.2 `ContextImpl`

```kotlin
// keel-api 内部；当前只有本地实现
internal class RequestContextImpl(
    override val plugin: PluginContext,
    request: HttpRequest,
    pathParameters: Map<String, String>,
    user: UserSnapshot?,
) : LocalContext, WebContext {
    override val local: MutableMap<String, Any?> = mutableMapOf()

    private val componentMap = mutableMapOf<String, Component>()

    init {
        val webRequest = WebRequestComponentImpl(request, pathParameters, user)
        webRequest.context = this
        componentMap[WebRequestComponent.TYPE_ID] = webRequest
    }

    override suspend fun components(): List<Component> = componentMap.values.toList()
    override suspend fun component(id: String): Component? = componentMap[id]

    override suspend fun addComponent(component: Component) {
        val id = component.id()
        val owner = component.context
        require(owner == null || owner === this) { "组件 $id 已挂载在其它 Context 上" }
        val previous = componentMap.put(id, component)
        if (previous != null && previous !== component) previous.context = null
        component.context = this
    }

    override suspend fun removeComponent(id: String): Component? {
        val removed = componentMap.remove(id) ?: return null
        removed.context = null
        return removed
    }

    override suspend fun webRequest(): WebRequestComponent? =
        componentMap[WebRequestComponent.TYPE_ID] as? WebRequestComponent

    override suspend fun destroy() {
        val mounted = componentMap.values.toList().asReversed()
        componentMap.clear()
        mounted.forEach { it.context = null }   // 先解绑
        var failure: Throwable? = null
        for (component in mounted) {            // 再逆序销毁
            try { component.destroy() } catch (t: Throwable) { if (failure == null) failure = t }
        }
        failure?.let { throw it }
    }
}
```

- 本版本 Context 只在插件运行时内创建（`PluginRoutes` 按请求构造），还没有跨插件的
  provider 注册，所以没有 `@PublicApiProvider`；
- `WebRequestComponent` 由独立的 `WebRequestComponentImpl` 挂进组件表，Context 自身不再实现它；
- 挂载 / 卸载维护 `component.context`；销毁先清表、再解绑、最后逆序调用 `destroy()`，
  单个失败不中断其余组件；
- HTTP 场景的持有方是 `PluginRoutes.dispatch`，它在 `finally` 里调用 `context.destroy()`，异常路径也不会漏释放；
- 非 Web 的通用 `ContextImpl` 等定时任务 / 控制台真的需要再加，不提前造；
- impl 是 `internal`，`keel-api` 本身就是 `Context` 的 api 模块，不新建包。

### 5.3 Proxy / Key

`keel-api` 就是 `Context` 的 api 模块；contract-first 阶段由它的 KSP 生成 proxy / Key：

```text
keel-api/
  Context.kt                 # 手写契约
  ContextProxy.kt            # KSP core 模式生成
  ContextKey.kt              # KSP core 模式生成：PublicApiKey<Context>("keel.context")
```

- **已落地**：`keel-api` 已应用 KSP（`keel.api.mode=CONTRACT_FIRST`），
  `ContextKey` / `ContextProxy` / `WebContextProxy` / `WebRequestComponentProxy` / `ComponentProxy`
  由 core 模式生成到 `src/commonMain/kotlin`，随仓库提交（V1 提交制）；
- 其他插件依赖 `keel-api`，直接 import 这些生成物；
- **不存在“每个 consumer 各生成一份”**；
- `ContextImpl` 所在插件只生成 provider 侧的 dispatcher / 注册。

### 5.4 创建与所有权

`Context` 是 `@PublicApi` 有状态对象，handle 就是它的身份：

- **谁创建谁持有**：Host 创建时 Host 持有，插件创建时插件持有；
- 创建者在请求 / 子操作结束时 `destroy()`；它按挂载逆序级联销毁全部组件，持有方用 `try/finally` 保证执行；
- 传给下游时传的是 handle，不是 Kotlin 对象；
- **下游不要 destroy 别人创建的 Context**：没有引用计数，提前关闭会让上游调用失败。

不需要 `contextId`：

```text
Context object -> handle H
plugin A: ContextProxy(H)
plugin B: ContextProxy(H)
```

`H` 本身就是“这个 Context”的唯一标识。

### 5.5 子类

- `Context` 是接口：子类只是另一个实现，handle 的 `types` 包含 `Context`，按接口调用；
- `Context` 是 `@PublicApi open class`：跨插件继承仍需要声明方 `ContextShadow`；
  **shadow 优先由 KSP 生成**，不从手写契约之外再手写一份。

### 5.6 组件

Components 是 Context 的一等公民，不删除：

- **公共组件**：接口继承 `Component`，标 `@PublicApi`；
  跨插件走 handle 或序列化值；
  通过 `WebContext.webRequest()` / `MyContext.myComponent()` 这类**声明方提供的显式方法**访问，
  不要用 `component(id) as XxxComponent` 强转。
- **挂载与卸载**：`Context.addComponent` / `removeComponent` 是契约方法，任何插件随时可调；
  同 id 替换；add 自动把 `component.context` 指向当前 Context，remove 自动清空；
  真正释放由 `Context.destroy()` 先解绑、再按逆序级联执行。
  本地 Context 直接在 Worker 的组件表上操作；跨插件 Context（下一轮）会变成反向请求，
  由 Context 所有者的 Worker 执行，组件实例留在挂载方的运行时里，只把句柄挂上去。
- **本地组件**：不标 `@PublicApi`，放在 `LocalContext.local` / 内部 map；
  不进入 api 模块，不跨 DLL。
- **Web 请求**：`WebRequestComponent`，只在 `WebContext` 上存在；
  定时任务、控制台、插件内部调用使用普通 `Context`，不伪造 Web 请求。
- **Session / user**：作为组件或 `WebRequestComponent` 的方法，不是 Context 核心字段。

`Session` 如果是 `@PublicApi` 类型，可以有 handle；
如果只是 `HashMap<String, Any?>`，只能本地使用。参考项目的 `SessionMap` 存 JSON 值，
是可序列化公共数据的形态。

#### 5.6.1 Component 取用：Unity 风格 `getComponent<T>`

> 本版本未实现：当前契约只有 `component(id)`，业务使用声明方的 typed 方法
> （`WebContext.webRequest()`）与 `Context` 扩展；下面的泛型写法留作后续方案。

最终 API 可以写成 Unity 风格：

```kotlin
suspend inline fun <reified T : Component> Context.getComponent(id: String? = null): T? {
    val typeId = T::class.qualifiedName ?: return null
    val component = component(typeId) ?: return null
    return component as? T
}
```

它是 **客户端 inline reified 扩展**，不是 `@PublicApi` 成员；
`@PublicApi` 禁止泛型方法，所以不能把 `getComponent<T>` 放进跨插件契约。

它成立分两种情况：

1. **本地组件**
   - `ContextImpl` 里存的是真实对象；
   - `component(typeId)` 返回 `AComponent` 实例；
   - `as? T` 直接成功。

2. **跨插件组件**
   - 注册时保留具体类型：`types = { AComponent, Component }`；
   - 查找走非泛型 bridge：`component(typeId)`，`typeId` 由 `T::class` 或稳定 key 提供；
   - 消费方生成 `ComponentProxies`，按 `typeId` 返回具体代理：
     ```kotlin
     object ComponentProxies {
         fun create(typeId: String, handle: Long): Component = when (typeId) {
             AComponentKey.id -> AComponentProxy(handle)
             else -> ComponentProxy(handle)
         }
     }
     ```
   - 消费方持有 `AComponent` 的 api 类型；
   - `getComponent<AComponent>()` 返回 `AComponentProxy`，`as? AComponent` 成功。

关键点：

- `as T` 只是类型检查，**不会创建代理**；
- 如果 bridge 返回 `ComponentProxy`，`is AComponent` 一定是 false；
- 要让 `is AComponent` 成功，返回对象必须已经是 `AComponentProxy`；
- 因此 Unity 写法只是上层糖，底层必须有 typed key / typeId + proxy factory。

两种落地方式：

- **A. 泛型 helper + proxy factory**：`getComponent<T>` 统一，消费方 api 模块生成 `ComponentProxies`；
- **B. typed 方法 + helper 转发**：`AContext.aComponent(): AComponent?`，helper 只做最后的 `as? T`。
  B 更稳，不依赖运行时泛型推断；A 更接近 Unity 的写法。

### 5.7 生命周期事件（本版本不做）

`ContextCreated` / `ContextDestroyed` 等 EventBus 事件本版本不做，等 EventBus 完成后再补。

- 届时仍用 Context 的 handle 作为身份，不需要 `contextId`；
- 没有引用计数时，创建者 destroy 后其他持有者不会自动收到通知；
  如需跨插件销毁通知，由 Host 在 destroy 时广播。

---

## 6. KSP 需要改什么

### 6.1 注解

- `@PublicApi`：契约标记；在 `CONTRACT_FIRST` 下不生成镜像。
- `@PublicApiProvider(contract)`：provider 标记；生成 dispatcher / 注册。
- `@PublicApiHandler`：保留，手写逃生口。
- `@Singleton`：继续表示单例提供者。
- `@LocalMember`：契约里的本地成员标记；不进入 dispatcher，
  属性必须是可空 `var`（生成的代理用 `null` 初始化），方法必须带默认实现。

### 6.2 模型

`TypeModel` 需要拆两个 FQN：

```text
contractFqn   // Context
implFqn       // ContextImpl
typeId        // keel.context（或 contractFqn）
```

当前生成器基本用同一个 `fqn` 做镜像、typeId、dispatcher，这是重名的根源。

### 6.3 生成分支

```text
mode == MIRROR:
    保持现状：impl @PublicApi -> emitApi(mirror/proxy/key)

mode == CONTRACT_FIRST:
    api 模块:
        @PublicApi contract    -> 不生成镜像
        生成 proxy / Key / 访问器 / DTO / 值类型
    impl 模块:
        @PublicApiProvider     -> 生成 dispatcher / 注册
        跨插件继承             -> 生成 shadow
```

### 6.4 冲突检测

生成镜像前检查目标 FQN 是否已存在于依赖：

- 存在 -> 报错，提示改用 contract-first；
- 不要静默生成重复类；
- `CONTRACT_FIRST` 下 `@PublicApi` 类型如果带实现体，应按契约处理，不按 impl 镜像处理。

已落地：同一构建里多个 `@PublicApi` 使用同一个 `typeId` 时直接报错；
跨依赖的目标 FQN 检测仍未实现（等签名校验一起补）。

### 6.5 签名校验（本版本不做）

本版本不新增签名 / 版本校验：

- `impl` 是否实现 contract 由 Kotlin 编译器保证；
- `keelApiCheck` 暂不改语义；
- 跨版本兼容、稳定 `id` 与版本策略后续再补。

---

## 7. 迁移计划

### 阶段 0：新增模式，不动默认

- ✅ 新增 `keel.api.mode`，默认 `MIRROR`；
- ✅ 新增 `@PublicApiProvider`（`vararg contracts`）；
- ✅ 现有插件行为不变（hello / basic-biz / rbac 构建通过）；
- ✅ `keel-api` 单独标记为 `CONTRACT_FIRST`；
- KSP 单测仍未接入（靠构建 + 生成物检查）。

### 阶段 1：Context 垂直切片

1. ✅ `keel-api` 手写 `Context` 契约（方法形态），并拆出 `LocalContext` / `WebRequestComponent` / `WebContext`；
2. ✅ `keel-api` 作为 api 模块，由 KSP 生成 `ContextProxy` / `ContextKey`（以及 `WebContext` / `Component` / `WebRequestComponent` 的代理与 Key）；
3. ⏳ 插件内 `internal ContextImpl : Context`，标 `@PublicApiProvider(Context::class)`；
4. ⏳ impl 模块只生成 `ContextDispatcher` + 注册，不生成 `Context` 镜像
   （dispatcher 生成器已支持接口继承展开；provider 发现 / 注册入口待接线）；
5. 插件 A 创建 Context，插件 B 拿到 handle 调用 `components()`；若 A 提供 `WebContext`，再调用 `webRequest()`；
6. 本地组件在 B 侧不可见；公共组件通过显式方法可见；
7. 所有目标编译通过（`keel-api` / hello / basic-biz / rbac 已验证）。

### 阶段 2：一个插件契约迁移

- 选一个低风险契约（如 hello）改成手写 contract + provider；
- api 模块由 KSP 生成 proxy / Key / 访问器 / DTO；
- impl 模块生成 dispatcher / 注册 / shadow；
- consumer 只依赖 api 模块，不各自生成；
- 验证 `@Singleton` / 有状态对象 / 跨插件继承。

### 阶段 3：长期并存

- **`MIRROR` 始终是默认模式**，不移除；
- 只有需要手写 API 的插件才切 `CONTRACT_FIRST`；
- `keel-api` 始终是 `CONTRACT_FIRST`；
- 同一个构建不允许混用两种模式；
- 工件坐标不变，仍是 `keel-plugin-<id>` / `-api`。

---

## 8. 验收

> 以下为 contract-first 全部落地后的验收。本版本只完成 Context 组件化切片：
> 与本地实现相关的条目已满足；`ContextProxy` / `ContextKey` / provider / handle 相关条目待后续。

- `Context` 在 classpath 中只有一个 FQN；
- `keel-api` 中没有 `Context` 镜像；`ContextProxy` / `ContextKey` 由 `keel-api` 的 KSP 生成；
- provider 模块生成 `ContextDispatcher` / 注册，且能访问 `internal ContextImpl`；
- consumer 只依赖 `keel-api`，能通过 handle 调用 `Context` 的公共方法；
- `Context` 不依赖 Web 请求也能创建；`WebRequestComponent` 只在 `WebContext` 上存在；
- `Component` 基接口标 `@PublicApi`；公共组件通过类型化方法访问；
- `getComponent<AComponent>()` 跨插件时返回 `AComponentProxy`；返回 `ComponentProxy` 时 cast 失败是有意行为；
- 本地组件不跨插件可见；
- 创建者 destroy 后，后续调用得到明确错误；
- `MIRROR` 模式的现有插件仍能构建；
- 同一个构建没有混用 MIRROR / CONTRACT_FIRST；
- 工件坐标未变化；
- DTO / 值类型 / shadow 由 KSP 生成，不需要手写；
- `mingwX64` / `linuxX64` / `linuxArm64` 至少各跑一次编译；
- 不引入 klib 合并任务。

---

## 9. 已定决策与实现备注

### 已定

- **Context = Components + 本地字段**：`Component` 是公共组件基接口，标 `@PublicApi`；本地组件不标。
  本地字段（`plugin` / `local` / `server` / `config`）放在不进 api 工件的 `LocalContext`。
- **业务能力走 `Context` 扩展**：Web 用 `ctx.request()` / `ctx.userId()`；数据库等后续业务同样加扩展。
- **组件挂载随时可发生**：`addComponent` / `removeComponent` 在 `Context` 契约上；
  add/remove 自动维护 `component.context`；`Context.destroy()` 先解绑、再逆序级联释放组件，
  持有方必须像 try-with-resources 一样保证它被执行。
- **跨插件 Context 传递下一轮做**：届时 add/remove 变成反向请求，组件实例留在挂载方运行时，
  只把句柄挂到 Context 所有者的组件表上。
- **不是所有 Context 都有 Web 请求**：Web 请求是 `WebRequestComponent`，Web 场景用 `WebContext`；普通 Context 不伪造请求。
- **公共组件用显式类型化方法访问**：`WebContext.webRequest()` / `MyContext.myComponent()`；
  不用泛型 `component<T>()`，也不依赖 `component(id) as Xxx` 的跨插件强转。
- **`Context` 契约采用方法形态**，不拆 `PublicContext`、不等 open class 属性 P2。
- **proxy / Key / 访问器 / DTO / 值类型生成在 api 模块**；consumer 只 import，不各自生成。
- **谁创建谁持有**，Host 也不例外；下游不 destroy 别人创建的 Context。
- **shadow 优先由 KSP 生成**。
- **签名校验、EventBus 生命周期事件本版本不做**。

### 实现备注

1. **`@KeelPluginMain` 是插件入口语法糖**
   - 它只用于插件 dylib 的 Worker 入口；
   - `keel-api` 不是插件，不需要这个注解；
   - KSP 的 contract-first 处理不能依赖 `@KeelPluginMain`，否则 core 模块无法生成 `ContextProxy` / `ContextKey`；
   - 需要给 `keel-plugin-ksp` 增加一个不要求插件入口的 core/contract 处理模式。

2. **`@PublicApiProvider` 的契约发现**
   - provider 只写 `@PublicApiProvider(Contract::class)`，impl 不需要额外 `@PublicApi`；
   - 导出的方法列表来自 contract，不来自 impl 注解；
   - KSP 从依赖的源码 / klib 解析 contract；impl 是否实现契约由 Kotlin 编译器保证；
   - `@Singleton` 仍可与 `@PublicApiProvider` 组合。

3. **api 模块只有两种形态**
   - MIRROR：impl 生成 api，`api/` 不可手改；
   - CONTRACT_FIRST：api 包是手写契约，配置为 CONTRACT_FIRST，impl 导入并依赖它；
   - 不存在“生成的 api 又配 CONTRACT_FIRST”。

4. **模式与坐标**
   - `MIRROR` 始终是默认模式，不移除；
   - `keel-api` 始终是 `CONTRACT_FIRST`；
   - 同一个构建不允许混用两种模式；
   - 工件坐标不变，仍是 `keel-plugin-<id>` / `-api`。

5. **`Component` 命名**
   - 统一叫 `Component`（不叫 `IComponent`）；
   - 公共组件接口继承 `Component`；
   - `Context` 只提供 `Component` 基类查询；具体组件由声明方的 `WebContext` / `MyContext` 子接口提供类型化方法。

6. **Component 取用**
   - 最终 API 可以是 Unity 风格 `Context.getComponent<T>()`，但它是 inline reified 客户端 helper，不是 `@PublicApi` 成员；
   - 跨插件必须走非泛型 bridge `component(id, typeId)`，并由消费方生成 `ComponentProxies` 按 typeId 返回具体代理；
   - `as T` 只做检查，不创建代理；返回 `ComponentProxy` 时 `is AComponent` 一定失败；
   - 更稳的落地是 `AContext.aComponent()` 这类 typed 方法，helper 只做最后转发。

---

## 10. 参考

- [public-api-design.md](public-api-design.md)：`@PublicApi` 的现有生成管线、句柄、生命周期与跨插件继承。
- [codegen-design.md](codegen-design.md)：模块互访与阻塞调用约定。
- [AGENTS.md](../AGENTS.md)：构建边界、插件工程与 `@PublicApi` 现状约定。
- [Context.kt](../keel-api/src/commonMain/kotlin/moe/lolosia/keel/api/web/Context.kt)：当前 Context 契约。
- [KeelPublicApiGenerator.kt](../keel-plugin-ksp/src/main/kotlin/moe/lolosia/keel/ksp/plugin/KeelPublicApiGenerator.kt)：当前镜像 / proxy / key 生成器。
- [KeelPluginWorkerProcessor.kt](../keel-plugin-ksp/src/main/kotlin/moe/lolosia/keel/ksp/plugin/KeelPluginWorkerProcessor.kt)：`keel.api.dir` / `KeelPluginPublicApi` 入口。
