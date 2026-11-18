# `@PublicApi`：跨插件共享对象与自动生成 API（设计）

> 状态：**已定稿（v2-only）**（2026-11-18）。
> 本文是规范：描述 `@PublicApi` 的目标形态、生成管线、协议与边界；与代码冲突时以代码为准，并回填本文。
>
> 相关文档：
> - [public-api-migration.md](public-api-migration.md)：v1 `@ModuleService` → v2 `@PublicApi` 的迁移
> - [codegen-design.md](codegen-design.md)：模块互访与阻塞调用的约定
> - [plugin-contract-discussion.md](plugin-contract-discussion.md)：impl / api 两个工件的取舍
> - [abi-design.md](abi-design.md)：Host ABI 与句柄的早期草图

## 0. 决策摘要

- **只保留 v2**：`@PublicApi` 取代 `@ModuleService`，不维护两套并行机制。
- **impl 是唯一真源**：`@PublicApi` 声明写在 impl，api 工件完全由它生成；impl **不依赖** api。
- **一个 api 工件**：每个插件一个 `api/` 子工程，坐标默认 `keel-plugin-<id>-api`；镜像与 impl 同 FQN。
  可替换能力（rbac）的 api 子工程使用**中性坐标**（`keel-plugin-rbac-api`），与默认实现的名字解耦。
- **无状态服务 = 契约接口 + `@Singleton` 实现**：`@PublicApi` 标接口，`@Singleton` 标实现；调用走 `SharedCall`，句柄由 `PUBLIC_API_SINGLETON` 解析。
  实现类可以实现的契约**来自依赖**（klib）：第三方实现只依赖 api 工件即可，生成器只补提供者侧分发器。
- **`@KeelPluginMain` = 公开单例**：它语义上等价于 `@PublicApi` + `@Singleton`；没有 api 工程且只有它时跳过 api 生成。
- **有状态对象 = 可构造类**：`createInstance` + `initialization`，状态机 `Creating → Open → Closed`。
- **跨插件继承 = 镜像 + 声明方影子**：api 镜像负责编译期超类型，impl 影子负责基类体与基类状态。
- **复用 v1 的成熟设计**：契约侧 KSP、提供侧 KSP、从 klib 读契约、`serializer<T>()` + Args DTO、Key / 访问器命名、单例路由。
- **删除 v1**：`@ModuleService` / `ServiceCall` / `ServiceCaller` / `ServiceDispatcher` / `ServiceBinder` / `GeneratedServices` / `ServiceKey` / `SERVICE_CALL` / `HostServices` / `ctx.modules`，迁移顺序见 [public-api-migration.md](public-api-migration.md)。

---

## 1. 目标与非目标

### 目标

1. **不手写 api 合约**：api 工件从 impl 的 `@PublicApi` 生成，开发者只写一份实现。
2. **共享有状态对象**：跨插件传递的不只是值，还可以是带生命周期的对象引用。
3. **支持跨插件继承**：子类插件可以 `class Dog : Animal()`，能 `super.bark()`、能使用基类状态。
4. **一套机制**：无状态服务、单例、有状态对象、回调、子类都走 `@PublicApi`。

### 非目标

- 运行期卸载插件代码（Kotlin/Native 不支持）。
- 通过反射构造对象或发现成员。
- 用 IR 拦截调用点（见 §3.3）。
- 让 impl 与 api 共用同一个 Kotlin 类型（见 §3.3）。

---

## 2. 硬约束

1. **Kotlin/Native 运行时不卸载**。`destroy()` 只释放业务资源，不卸载代码。
2. **没有反射**。类型识别只能靠字符串 typeId + 字节；必须生成代码。
3. **klib 会被静态链进调用方**。带 `@CName` 入口的插件 dylib 不能进消费方；impl 的 klib 不能给消费方依赖。
4. **KSP 只能看到声明，看不到方法体**。任何「把 impl 方法体搬到 api」的方案都不成立。
5. **协程不跨 C ABI**。跨插件调用只能「投递 + 应答」，`suspend` 是本地语法糖。
6. **一个 Gradle 模块产两个可解析 klib 不可行**（classifier / capability 都不行）。
7. **编译期必须能看到超类型**。子类要写 `class Dog : Animal()`，`Animal` 就必须在 classpath 里；IR 补不出来。

---

## 3. 架构：impl 唯一真源，api 全生成

### 3.1 两个模块

```text
keel-plugin-animal/
├── settings.gradle.kts        # include(":api")
├── build.gradle.kts           # 插件主体（impl，dylib）
├── src/commonMain/kotlin/...  # ← 手写：@PublicApi 声明、@KeelPluginMain、实现
└── api/                       # ← 纯生成，空模块起步
    └── src/commonMain/kotlin/...
```

| 模块 | 放什么 | 谁依赖 |
| --- | --- | --- |
| `src`（impl） | 全部手写代码：`@PublicApi` 类型与成员、入口、服务、私有实现 | — |
| `api` | 由 `@PublicApi` 生成的镜像 / 代理 / Key / 访问器 / DTO 镜像；**没有手写合约** | 所有消费方 |

依赖方向：**消费方 → api；impl 不依赖 api**。没有环。

- 坐标不变：`keel-plugin-<id>`（dylib）、`keel-plugin-<id>-api`（api 工件）。
- 现有兄弟 `*-api` 工程按 [public-api-migration.md](public-api-migration.md) 逐个迁入 `api/`；迁入期间坐标保持不变，消费方只重新编译。

### 3.2 为什么不再保留 v1

v1 的 `@ModuleService` 是「手写契约接口 + 生成 Client/Dispatcher」；v2 的 `@PublicApi` 是「impl 注解 + 生成镜像/代理/Dispatcher」。两套并存会带来：

- 两套注解、两套 Key / 访问器命名、两套路由（`SERVICE_CALL` vs `SHARED_CALL`）；
- 同一个插件同时有 v1 和 v2 时，impl 依赖 v1 api，而 v2 镜像与 impl 同 FQN → 编译冲突；
- 为避免冲突又要引入 `.public` 包、显式 id、两套生成器，规则复杂度远高于迁移成本。

当前下游只有 klive、`lolosia-keel-plugins` 和本仓库，迁移成本可控，因此**选择 v2-only**。v1 的设计经验（契约侧生成、提供侧生成、从 klib 读契约、`serializer<T>()` + Args DTO、命名、单例路由）全部搬进 v2。

### 3.3 为什么是镜像，而不是共用同一个类型

要让 impl 与消费者共用同一个类型，类型必须住在共享工件里，于是 impl 依赖 api；而「不手写」又要求 api 从 impl 生成，即 api 依赖 impl：

```text
impl 依赖 api（类型身份） + api 依赖 impl（自动生成） = 环
```

两条出路：

| 路 | 做法 | 代价 |
| --- | --- | --- |
| A. 保住类型身份 | 手写 api，impl 依赖 api | 仍然要维护 api 合约 |
| B. 保住「不手写」 | api 是**镜像**，impl 不依赖 api | 两个类型；IDE「跳到实现」失效；漂移靠指纹兜底 |

本设计走 **B**。镜像与 impl 保持同一 FQN，但分属两个工件；因为 impl 不依赖 api，不会冲突，也不需要 `.public` 包或显式 id。
需要跨插件继承的类型额外多一步：声明方为外来实例保留「基类影子」，见 §6。

---

## 4. 生成管线

### 4.1 两遍生成

KSP 的 `CodeGenerator` 只能写当前编译的输出目录，所以「生成到 api」必然有两段：

```text
impl 源码
  → [impl KSP 提取契约模型] → public-api.json
        ├─→ impl 侧：分发器 / 影子 / KeelPluginPublicApi（进本目标 KSP 输出）
        └─→ api 侧：镜像 / 代理 / Key / 访问器 / DTO 镜像（进 api 子工程）
```

- 模型提取跑在**单个目标**（宿主机目标）上；模型与 target 无关，三个 target 各跑一次会互相覆盖。
- impl 侧生成物只依赖 impl 自己的注解，不需要读 api。
- api 侧生成器读模型，不依赖 impl 的编译 classpath；依赖方向仍是「api 从模型生成」。

### 4.2 落点：V1 提交制与 V2 自动制

| 变体 | 落点 | 顺序 | 代价 |
| --- | --- | --- | --- |
| **V1 提交制（起步）** | `api/src/commonMain/kotlin` | 无跨工程顺序问题 | 生成结果要提交；`keelApiCheck` 在 CI 比对是否过期 |
| V2 自动制 | `api/build/generated/keel/api` | `:api:compileKotlin*` 依赖模型任务 | 首次 sync / clean 后 api 短暂报红 |

V1 先跑通，再把落点切到 V2；两者使用同一个生成器，切换只是改输出目录与 sourceSet 挂载。

### 4.3 生成物清单

**impl 侧（`KeelPluginPublicApi`）**

- `setup`：上报类型声明、收集分发器、把 `@PublicApi` + `@Singleton` 的插件本体登记为单例；
- `release`：与 `setup` 对称，当前主要留作本地清理；
- 每个 `@PublicApi` 类型一个 `XxxDispatcher`；可继承类型一个 `XxxShadow`；
- 可构造类型登记 `constructor(...)`，声明了 `initialization` 的类型标记 `initialization = true`。

**api 侧**

- `Xxx` 镜像：接口 / open class / data class，方法体为桩，基类调用走 `base.*`；
- `XxxProxy`：消费方代理，虚方法转发、共享参数/返回值换 `SharedRef`；
- `XxxKey`：`PublicApiKey<Xxx>(typeId)`；
- `val PluginContext.xxx`：单例访问器（提供者缺席时抛异常）；
- `val PluginContext.xxxOrNull`：同一访问器，提供者缺席时返回 `null`（可替换能力按缺席降级）；
- DTO 镜像：`@Serializable` 数据类的镜像（2b-2 引入）。

**提供者侧（第三方实现）**

- 当 `@Singleton` 实现类实现的是**依赖里的** `@PublicApi` 契约（klib，没有源码）时，
  只生成 `XxxDispatcher` 与 `KeelPluginPublicApi.setup` 的声明 / 注册，不生成镜像与代理
  （api 工件里已有）；`declare` 的 typeId 仍是契约 FQN，因此与默认实现等价、可原位替换。

### 4.4 手写逃生口与冲突

- `@PublicApiHandler(Type::class)` 标注的类型视为「胶水手写」，生成器不再生成它的 dispatcher / shadow / proxy。适用于构造参数含 `PublicApiDirectory`、或分发逻辑无法从签名推断的类型。
- api 生成器只写自己的文件清单；写入前扫描 api 工程已有顶层声明：
  - 同名 `XxxProxy` / `XxxKey` / `ctx.xxx` → 跳过 + warning；
  - 访问器依赖的 proxy 或 key 被手写占用 → 访问器也跳过，提示自行接线；
  - 永不覆盖已有文件。
- `keelApiCheck`（V1）在 CI 重新生成并 diff，防止提交过期产物：各插件构建里的 `keelApiCheck`
  依赖 `keelApiGenerate`，再检查 `api/src/commonMain` 是否还有未提交改动。

---

## 5. 注解与类型规则

### 5.1 注解

```kotlin
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.ANNOTATION_CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
)
@Retention(AnnotationRetention.BINARY)
annotation class PublicApi(val id: String = "")

@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Singleton

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class PublicApiHandler(val type: KClass<*>)

@PublicApi
@Singleton
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class KeelPluginMain
```

- `@PublicApi`：标在类型上 = 该类型进入 api 工件；标在成员上 = 类模式下只有这些成员参与共享。**不再用参数区分单例**。
- `@Singleton`：标在实现类上 = 把它登记为它实现的所有 `@PublicApi` 类型的单例；如果类本身也是 `@PublicApi`，同时登记自己的类型。
  实现的契约可以来自本模块源码，也可以来自依赖（api 工件 / klib），后者只生成提供者侧分发器。
- `@Singleton` 不导出新类型，只决定「谁提供单例」。一个 typeId 只允许一个 `@Singleton`，重复时 Host 保留首个、拒绝后到者并告警。
- `@Singleton` 类必须至少实现一个 `@PublicApi` 类型，或自身标了 `@PublicApi`；否则生成期告警。
- `@KeelPluginMain`：插件入口，同时语义等价于 `@PublicApi` + `@Singleton`；插件主类只写这一个注解。
- **KSP 不会自动传播元注解**：生成器显式把带 `@KeelPluginMain` 的类当作 `@PublicApi` + `@Singleton`；扫描 `@PublicApi` / `@Singleton` 时排除 `ANNOTATION_CLASS`，避免把 `@KeelPluginMain` 自身当成可导出类型。
- `@PublicApiHandler` 是手写逃生口，不参与自动生成。
- 首版只提供 `@Singleton`；`@Service` 可以作为语义别名后续加（生成器同等处理），`@Component` 不增加语义，不提供。

### 5.2 类型映射

| 声明 | 生成到 api 的东西 |
| --- | --- |
| `@PublicApi interface Foo` + `@Singleton class FooImpl : Foo` | `interface Foo` + `FooProxy` + `FooKey` + `ctx.foo` |
| `@PublicApi interface Foo`（没有 `@Singleton` 实现） | `interface Foo` + `FooProxy` + `FooKey`（无访问器，供回调/手动注册） |
| `@PublicApi data class Foo(...)` | `data class Foo(...)` + `@Serializable` 镜像 |
| `@PublicApi class Foo`（不被继承） | `class Foo` 镜像 + `FooProxy` + `FooKey` |
| `@PublicApi open class Foo`（跨插件继承） | api 镜像 `Foo`（方法体为桩）+ 代理；impl 侧 `FooShadow : impl.Foo` |
| `@KeelPluginMain` 插件本体 | 插件单例 + `ctx.xxxPlugin` 访问器（语义上等同 `@PublicApi` + `@Singleton`） |
| `@Singleton class FooImpl : Foo`（impl 不导出） | 只在 impl 侧生成 dispatcher 与单例注册；api 不生成 `FooImpl` |
| 未标注成员 | 不导出（fail-soft，构建日志显式列出） |

### 5.3 无状态服务

v1 的 `@ModuleService` 在 v2 里的等价写法：

```kotlin
// impl
@PublicApi
interface HelloApi {
    suspend fun greet(name: String): String
}

@Singleton
class HelloServiceImpl : HelloApi {
    override suspend fun greet(name: String) = "hello, $name"
}

// 消费方（api 生成物）
ctx.helloApi.greet("world")
```

- `@PublicApi` 只描述契约；`@Singleton` 只描述提供者。`HelloServiceImpl` 不进入 api 工件，api 里只有 `HelloApi`。
- api 侧生成 `HelloApi` 镜像、`HelloApiProxy`、`HelloApiKey`、`ctx.helloApi`。
- impl 侧生成 `HelloApiDispatcher`，并把 `HelloServiceImpl` 登记为 `HelloApi` 的单例。
- 调用仍走 `SharedCall`；单例句柄由 `PUBLIC_API_SINGLETON` 解析，代理缓存它，不做每调用一次查表。
- 调用点与 v1 的 `ctx.helloApi.greet("world")` 基本一致，迁移成本集中在 impl 与 api 工件的移动。
- 自身就是契约的插件（如 `AnimalPlugin`）只写 `@KeelPluginMain`；它语义上已是 `@PublicApi` + `@Singleton`。

### 5.4 类型白名单与重载

禁止：泛型方法、`vararg`、默认参数、函数类型参数、平台类型、`Any` / `Nothing`、可变集合、内部类、`expect` / `actual`。

白名单：`Unit`、`Boolean`、整数族、`Float` / `Double`、`String`、枚举、`ByteArray`、
`List` / `Set` / `Map`（递归）、可空类型、`@PublicApi` 值类型、共享类型（按引用传）。

重载允许，线名按**参数类型**推导（不是参数名）：

```text
method = name                                  // 无重载
method = name + "/" + 参数类型规范化串          // initialization/String、initialization/String/Int
```

> 现状：重载**尚未实现**，生成器遇到重载会报错。线名规则保留为设计目标，实现时按上表推导。

属性按 §6.3 的 `SharedVar` / `SharedVal` 处理（P2）；接口上禁止属性，改写成 `suspend fun getX()`。

### 5.5 从 v1 搬过来的编码

- 多参数方法生成 `@Serializable` Args DTO（`XxxCallMethodArgs` / `XxxDispatchMethodArgs`）；
- 普通值用 `serializer<T>()` + CBOR；共享对象用 `SharedRef`；
- 不再限制 0/1 参数；重载仍按线名区分；
- 这套编码与 v1 的 `ServiceWire` 一致，迁移时线格式不变。

### 5.6 何时生成 api

生成器与 Gradle 插件一起判断，避免简单插件被迫建 api 工程：

| 情况 | 行为 |
| --- | --- |
| 有 `api/` 子工程 | 正常生成所有 `@PublicApi` 类型（含 `@KeelPluginMain` 插件本体） |
| 没有 `api/`，且只有 `@KeelPluginMain` | **跳过 api 生成，不告警** |
| 没有 `api/`，但存在其它 `@PublicApi` 类型 | 打 warning：缺少 api 工程；提示运行 `keelScaffoldApi` |
| 没有 `api/`，但有 `@Singleton` 实现且契约是 `@PublicApi` | 同上：契约需要 api 工程，打 warning |

- 判定由 Gradle 插件完成：它知道 `api/` 是否存在，并把 `keel.api.present` 传给 KSP。
- 只有插件本体时不生成 api，消费者也无法引用它；这是有意的「内部插件」形态。
- 需要暴露给其它插件时，创建 `api/`（或运行 `keelScaffoldApi`），生成器会把插件本体也导出；它的合约只包含标了 `@PublicApi` 的成员，没有成员时就是一个空类型。
- 「导出」只是编译期可见：api 工件是否发布、是否被消费方依赖，由插件作者决定；没有发布时别人拿不到。
- 因此不需要「有 api 工程但隐藏插件本体」的开关：同一插件的所有 `@PublicApi` 类型在同一个 api 工件里，拿到其它 API 就必然拿到插件本体；而插件本体的可调用面只由 `@PublicApi` 成员决定。

---

## 6. 跨插件继承

### 6.1 编译期前提与声明方影子

`class Dog : Animal()` 要编译，编译器必须看到 `Animal` 的类型与构造器；`super.bark()` 必须有可调用目标。KSP 读不到方法体，所以 api 的 `Animal` 只能是**镜像**，并由声明方补一个「基类影子」：

| 角色 | 在哪 | 内容 |
| --- | --- | --- |
| api 镜像 `Animal` | 消费方 | 没有存储：属性是访问器，方法是桩（转到声明方） |
| 基类影子 `AnimalShadow` | 声明方 | 生成的 `class AnimalShadow(handle) : impl.Animal()`：继承真实基类，持有裸字段、执行基类方法体、按 override 集合转发虚方法 |
| 子类 `Dog` | 实例方 | 只有派生状态；继承来的方法是桩，`super.x()` 走基类入口 |

路由：某个类型的外部调用统一进**声明方**（`typeId → declaring`），声明方持有影子并决定本地执行还是转发。

调用链（a 本地调 `dog.onUpdate()`，Dog 只覆盖了 `bark`）：

```text
a: Animal.onUpdate(桩) → 声明方 b
b: AnimalShadow.baseOnUpdate() 执行（裸字段可用）→ 调 bark()
   → bark 在 override 集合里 → 转发给实例方 a
a: Dog.bark → super.bark() → 桩 → b 的 baseBark()
b: AnimalShadow.baseBark() 执行（真实基类体）
```

### 6.2 `base.*` 与 override 集合

- 镜像桩与子类的 `super.xxx()` 只允许走 `base.xxx()`，绝不能调虚方法，否则影子与镜像互相转发 → 无限循环。
- 实例方注册句柄时上报「本类型实际覆盖的方法线名集合」；影子在集合里 → 转发给实例方，不在 → 就地 `super.xxx()`。
- 没有集合也能跑（全部转发），只是每次多两跳；它是「必要的数据、可选的性能开关」。

### 6.3 约束与代价

1. 基类必须可无参构造，可调用成员要 `open`。
2. 对象被拆成两半：基类态在声明方，派生态在实例方；`this` 在两侧不是同一个对象；`equals/hashCode` 按句柄。
3. 多一跳：caller → 声明方 →（覆盖方法时）实例方。
4. 声明方停用 → 该类型的所有跨插件实例失去基类半边，调用报错。
5. 影子随句柄惰性创建、随句柄销毁。

### 6.4 属性

`@PublicApi var age = 0` 在 api 侧生成 `SharedVar<Int>` / `SharedVal<Int>` 访问器：

- 只允许默认访问器的 backing field 属性；自定义 getter/setter、`lateinit`、委托、`private set`、`const`、扩展属性一律生成期报错。
- 访问器不持有值，只持有当前对象的句柄提供者；值落在声明方影子/真实实例的裸字段上。
- 每次 get/set 一次往返，不保证原子性；需要原子语义就写成方法。

---

## 7. 远程构造与生命周期

### 7.1 `createInstance` + `initialization`

```kotlin
@PublicApi
class DownloadQueue(
    private val id: Long,           // 构造前由 Host 分配，构造时已知
    private val ctx: PluginContext, // 本地注入，不来自远端
) {
    suspend fun initialization(config: QueueConfig) { ... }
    suspend fun initialization(config: QueueConfig, owner: String) { ... }

    @PublicApi suspend fun enqueue(url: String): TaskId
}
```

- 构造参数只允许：无参、`Long`（句柄）、`PluginContext`；远端参数一律走 `initialization`。
- `PluginContext` 由生成的工厂从 `KeelPluginPublicApi.setup(plugin, context)` 捕获。
- 本地创建走同一套生成代码，不过 Host。

### 7.2 状态机

```text
Creating → Open → Closed
```

| 状态 | 可做什么 |
| --- | --- |
| `Creating` | 只有创建者能调用 `initialization` 系列；其它方法抛 `shared object not initialized` |
| `Open` | 所有导出方法可用；`initialization` 再次调用报错 |
| `Closed` | 所有调用报错；对象由所有者释放 |

- `initialization` 抛异常 → Host 立刻标 `Closed` 并通知所有者释放，不留半成品。
- 失败/超时同样转 `Closed`。
- 没有声明 `initialization` 的类型构造完即 `Open`。

### 7.3 销毁与停用

- `destroy()` 是实现侧的释放钩子，不导出成远端方法；远端销毁走 Host。
- 任何人可 `destroy()`：幂等、先到先得、之后调用报错（与 `InputStream.close()` 同语义）。
- 插件停用：该插件**拥有**的句柄标 `Closed`；它创建过的句柄通知所有者销毁。
- 不做引用计数：代理无状态，只存句柄；Host 每次回答「还活着吗、发给谁」。

---

## 8. Host 句柄表与协议

### 8.1 句柄表

```text
handles: Map<Long, Entry>
Entry(
    owner: ULong,            // 实例所有者
    declaring: ULong,        // 类型声明方
    types: Set<String>,      // 导出的类型集合
    overrides: Set<String>,  // 实例方实际覆盖的线名
    singleton: Boolean,
    state: Creating | Open | Closed,
    creator: ULong,          // 仅创建者能 initialization
)

types: Map<String /* typeId */, TypeEntry(declaring, hasBase)>
singletons: Map<String /* typeId */, Long /* handle */>
```

- 句柄由 Host 分配，随登记返回给插件。
- 句柄是**对象级**的：一个对象一个 id；`types` 是它导出的类型集合。
- 调用按 `typeId → declaring` 进声明方；没有跨插件子类时 `declaring == owner`，退化成点对点转发。
- **同 typeId 冲突**：`types` 与 `singletons` 都只认**首个**提供者（按插件加载顺序）；
  后到者照常拿到自己的句柄、可以正常工作，但不会被类型路由 / 单例查找选中，Host 打 warn。
  插件停用时清理自己声明与注册的条目，因此「停用默认实现 → 启用第三方实现」不需要重启。
- `plugin.json` 的 `provides`（如 `authz`）与这里同一语义：首个认领者生效，第二个直接判冲突。

### 8.2 subject

| subject | 方向 | 载荷 |
| --- | --- | --- |
| `PUBLIC_API_DECLARE`（0x1013） | 插件 → Host | `PublicApiDeclareRequest(types)` |
| `PUBLIC_API_REGISTER`（0x1014） | 插件 → Host | `SharedRegistration(typeId, interfaces, overrides, singleton)` |
| `PUBLIC_API_SINGLETON`（0x1015） | 插件 → Host | UTF-8 typeId；应答 `SharedHandleReply(handle)` |
| `SHARED_CALL`（0x1016） | 插件 → Host → 目标 | `SharedCall(typeId, handle, method, args, owner, overrides)` |
| `PUBLIC_API_CREATE`（0x1017） | 插件 → Host → 提供者 | `SharedCreateRequest(typeId, args)`；回包 `SharedCreateReply(handle, state)` |

- 共享对象作为参数/返回值 = `SharedRef(handle)`，CBOR 编码，不需要新 subject。
- 回包复用 `COMPLETE` + correlation，不新增事件类型。
- 没有 `SHARED_CLOSED` 广播：失效惰性；需要「立刻让所有代理报错」时再加可选推送。

### 8.3 路由规则

```text
if (call.owner)            → owner            // 影子转发覆盖方法
else if (type.hasBase)     → declaring        // 先给声明方，由影子决定本地/转发
else                       → owner            // 纯接口直接给所有者
```

- `base.` 前缀只跑基类体，永远不进实例方。
- `Creating` 时只放行创建者的 `initialization*`，其它方法直接报 `shared object not initialized`。
- `Closed` 时所有调用报错。

---

## 9. 一致性与版本

- 生成器把规范化模型（typeId + 成员线名 + 参数/返回类型串，稳定排序）哈希成常量，写进 api 生成物与 provider 的注册载荷。
- Host 在 `REGISTER` / `CREATE` 时比对，不一致明确报「api 与实现版本不一致」。
- `keelApiCheck`（V1）在 CI 重新生成并 diff。
- 指纹粒度、api 与插件版本的绑定方式仍在 §11 的未决问题里。

---

## 10. 当前实现状态

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| Phase 1（`806c788`） | `@PublicApi` / share 包 / Host 句柄与类型表 / 双插件示例 | 已完成 |
| Phase 2（`9b23fbc`） | `createInstance` + `initialization` 状态机 | 已完成 |
| Phase 2a（`507776e`） | KSP 生成 impl 侧注册表并接入生命周期 | 已完成 |
| Phase 2a（`3c9bc15`） | KSP 生成 impl 侧 dispatcher / shadow / proxy，删除手写胶水 | 已完成 |
| **2b** | api 全生成：镜像 / 代理 / Key / 访问器（V1 提交制） | 已完成 |
| 2b-2 | api 生成：`@Serializable` DTO 镜像 | 已完成 |
| 2c | 消费方子类胶水：从 api klib 读契约，生成 overrides 与 dispatcher | 已完成 |
| 2c-2 | 提供者侧：`@Singleton` 实现依赖里的 `@PublicApi` 契约，只生成 dispatcher / 注册 | 已完成 |
| 2d | 访问器补齐 `ctx.xxxOrNull`；Host 同 typeId / `provides` 首提供者胜出 | 已完成 |
| 3 | v1 迁移：hello → basic-biz / rbac → 删除 `@ModuleService` | 已完成；rbac 默认实现 `keel-plugin-basic-rbac` 已落地 |

2b 采用 V1 提交制：KMP 下 KSP 的 `CodeGenerator` 只能写目标 fragment，commonMain 看不到生成声明；
因此由 impl 侧 KSP 通过 `keel.api.dir` 直接写入 `api/src/commonMain/kotlin`，产物随仓库提交。
`@PublicApi` 接口与 `@Serializable` DTO / 枚举的镜像直接搬 impl 的源文件（KSP 读不到默认值表达式，
搬源码才能保住默认参数 / `@SerialName` / KDoc），因此要求一个源文件只放一个顶层声明；
共享类仍渲染成「无状态桩 + `sharedHandle`」。DTO 按值走 `serializer<T>()`，不生成代理 / Key；
多参数方法在两侧各生成一个 `@Serializable` Args DTO（`XxxCallXxxArgs` / `XxxDispatchXxxArgs`），
单参数保持直编码以兼容已有线格式。
消费方子类（如 `Dog : Animal`）的 `XxxOverrides` 与 `XxxDispatcher` 同样由 KSP 生成，
通过 `keel.glue.dir` 写回本模块的 `src/commonMain/kotlin`。

---

## 11. 风险与未决问题

| 风险 / 未决 | 影响 | 处理 |
| --- | --- | --- |
| 镜像类型导致「跳到实现」失效 | 调试体验下降 | 生成物带 `// 对应 impl.Xxx` 注释；后续 IDEA 插件补导航 |
| api 与 impl 漂移 | decode 失败 / 字段错位 | `keelApiCheck`（重新生成 + git diff）已生效；契约指纹（§9）尚未实现 |
| 跨工程 task 顺序（V2） | clean 后 api 报红 | 默认 V1 提交制；V2 灰度 |
| 基类有非 `open` 成员或无法无参构造 | 影子无法覆盖 / 无法创建 | 生成期校验 |
| 代理上的 `is` / 私有成员不可用 | 业务代码误以为是本地对象 | 生成 `isXxx()` / `typeName`；未导出成员抛错 |
| 传出去的句柄没人销毁 | 对象活到所有者停用 | 任何人可销毁；所有者停用时统一清理 |
| 实例方法阻塞 provider Worker | 所有调用方一起卡 | 文档明确「对象单线程、调用串行」；鼓励 `suspend` |
| 任何插件知道 typeId 就能 create / 调用 | 越权访问 | `requires` / 权限白名单（P2） |

未决问题：

1. 模型提取跑在哪个 target（建议宿主机目标），三个目标各验一次。
2. 生成物写进 `src/` 时是否允许手工修改（建议禁止，由 `keelApiCheck` 覆盖）。
3. 指纹粒度：整类型一个哈希，还是按方法分片。
4. `initialization` 是否允许 reset / reopen，还是严格一次性。
5. 传出去的句柄默认活到所有者停用，还是约定「调用返回后即失效」。
6. 「可调用成员全 `open` + 可无参构造」的校验边界（属性、委托、`const`）。
7. api 工件与插件版本的关系（跟随 `keel` 版本 vs 独立升版）。

---

## 12. 与现有文档的关系

- [public-api-migration.md](public-api-migration.md)：v1 → v2 的映射、删除清单、迁移顺序与验收。
- [codegen-design.md](codegen-design.md)：模块互访与阻塞调用的约定；本文取代其中 `@ModuleService` 的契约形态。
- [plugin-contract-discussion.md](plugin-contract-discussion.md)：impl / api 两个工件的取舍；本文确定 api 为生成物。
- [abi-design.md](abi-design.md) §6.6 的「能力对象 = 句柄代理」与本方案同构，但这里用 Host 侧逻辑句柄（整数 id + 表），不是 C 句柄表的 `StableRef`。
