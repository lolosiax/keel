# `@PublicApi`：API 自动生成与跨插件共享对象（讨论稿）

> 状态：**讨论记录，未定稿**（2026-11-17，第五稿）。架构前提：每个插件只有 **impl 与 api** 两个模块，
> api 是空模块、完全由 `@PublicApi` 生成，没有第三个共享库。回答两个问题：
> (1) 能不能不手写 `-api` 契约工程，让注解 + 工具链把 API 生成出来；
> (2) 能不能把「有状态的实例」以及「别的插件声明的子类」跨插件共享。
>
> 与 [codegen-design.md](codegen-design.md)（零样板模块互访的目标形态）、
> [plugin-contract-discussion.md](plugin-contract-discussion.md)（两个工件的取舍）配套。
> 结论确认后回填规范；与代码冲突时以代码为准。

---

## 0. 结论摘要

- **API 自动生成**成立：api 工件是 impl 的**镜像**，「不手写」与「共用同一个类型」不能兼得（§3）。
- **跨插件子类**成立，而且不限于接口：`super`、继承来的方法、基类状态都能用。
  机制是「api 镜像 + **声明方基类影子**」：基类体与裸字段都在影子里执行，
  虚方法按 override 集合转发给实例方（§4.5）。
- **override 集合是影子的一部分**：它决定「就地跑基类体」还是「转发给实例方」；
  不带也能正确运行，只是多两跳（§4.5.3）。
- **不需要 IR 拦截**：provider 不想写「判断是不是远程」的脏代码，这个诉求由**生成的镜像与影子**直接满足；
  IR 也解决不了编译期缺基类与 `this` 错位（§4.5.4）。
- **任何人可 `destroy()`**：与 `InputStream.close()` 同语义，幂等、先到先得、之后调用报错（§5.2）。

| 提案 | 可行 | 说明 |
| --- | --- | --- |
| 建空 api 工程，由工具链往里生成 API 代码 | 可行 | 需要 Gradle 子工程 + 源码目录 + 任务顺序；KSP 只能写自己的输出目录，要一个 Gradle task 搬运 |
| `@PublicApi interface` → 生成 API | 可行，成本最低 | api 是镜像接口；生成 Client / 本地适配器 |
| `@PublicApi class` → 生成 API，仅标注成员参与 | 可行 | 生成 api 侧接口/代理 + impl 侧适配器；impl 不能直接 `implements` 生成的类型 |
| 同步方法在 api 侧自动变 `suspend` | 可行且必要 | 一次远程调用 = 一次往返 |
| 属性输出到 api | 可行 | 生成 `SharedVar<T>` / `SharedVal<T>` 访问器对象（外部只有 `suspend`）；只允许默认访问器；值落在声明方影子/真实实例的裸字段上；不保证原子性；接口上 v1 禁止（§6.3） |
| 别的插件声明子类、`super`、调用未覆盖的基类方法 | 可行 | api 镜像 + **声明方基类影子**：基类体与裸字段都在影子上执行，虚方法按 override 集合转发给实例方（§4.5.1） |
| 只有声明子类的插件拿得到原始实例 | 成立 | 实例原对象在子类插件；声明方持有基类影子，第三方只有代理 |
| override 需要在协议里报告 | 需要 | 影子用它决定「本地跑基类体」还是「转发给实例方」；不带也能正确运行，只是多两跳（§4.5.3） |
| 同步调用方阻塞等待 + 告警 | 可行 | 用 `callBlocking`（重入事件泵），**不要 `runBlocking`**（§6.6） |
| 用 IR 拦截 `@PublicApi` 调用 | **不建议** | 目标由生成代理达成；IR 解决不了编译期与 `this`（§4.5.4） |
| 任何人可 `destroy()` | 可行，推荐 | 幂等、先到先得、之后调用报错（§5.2） |
| Host 统一管句柄 ID | 可行 | Host 只管「句柄 → 所有者 + 状态 + 类型描述符」，不管谁持有 |

---

## 1. 现状（讨论的事实基础）

| 事实 | 位置 |
| --- | --- |
| 契约必须手写在独立的 `-api` 工程里（`@ModuleService` 接口 + DTO） | [plugin-contract-discussion.md](plugin-contract-discussion.md) §1 |
| 跨模块调用已经**全部经过 Host**：`SERVICE_CALL` → Host 按 service id 找提供者 → 转发 | [HostPump.kt](../keel-host/src/nativeMain/kotlin/moe/lolosia/keel/host/plugin/HostPump.kt) `handleServiceCall` |
| Host 只保存 `service id → plugin id`，不理解方法签名 | [HostServices.kt](../keel-host/src/nativeMain/kotlin/moe/lolosia/keel/host/service/HostServices.kt) |
| 信封只有三段：`service` / `method` / `args`（CBOR） | [ServiceCall.kt](../keel-api/src/commonMain/kotlin/moe/lolosia/keel/api/service/ServiceCall.kt) |
| KSP 已能生成 Key / Client / Accessor / Dispatcher / `KeelPluginServices` | [KeelServiceGenerator.kt](../keel-plugin-ksp/src/main/kotlin/moe/lolosia/keel/ksp/plugin/KeelServiceGenerator.kt) |
| 阻塞调用已有「投递 + 重入事件泵」的实现约定 | [codegen-design.md](codegen-design.md) §4.1 |
| ABI 里 `handle_*` 目前恒为 null（句柄表未实现） | [任务记忆.md](任务记忆.md) §2.2 |
| `abi-design.md` 已经画过「能力对象 = 句柄代理」的草图，但没落地 | [abi-design.md](abi-design.md) §6.6 |

结论：**「Host 掌握句柄路由」没有架构阻力**——现在的跨插件调用本来就在 Host 手里过一道，
缺的只是 Host 侧多一张「句柄 → 所有者 / 状态 / 类型」的表。

---

## 2. 硬约束（不要试图绕过）

1. **Kotlin/Native 运行时不卸载**。`destroy()` 只能释放业务资源，不能卸载插件代码。
2. **没有反射**。类型识别只能靠名字（字符串 id）+ 字节，必须生成代码；不能按类名 new 对象。
3. **klib 会被静态链进调用方**。带 `@CName` 入口的插件 dylib 不能进消费方——impl 的 klib
   一旦被依赖，插件入口与实现都会被链进对方二进制。
4. **KSP 只能看到声明，看不到方法体**。任何「把 impl 里的方法体搬到 api」的方案都不成立。
5. **协程不跨 C ABI**。跨插件调用只能「投递 + 应答」，`suspend` 是本地语法糖。
6. **一个 Gradle 模块产两个可解析的 klib 不可行**（classifier / capability 都不行）。
7. **编译期必须能看到超类型**。子类要写 `class Dog : Animal()`，`Animal` 就必须在 C 的 classpath 里
   ——这一条决定了跨插件继承的形态，IR 也补不出来。

---

## 3. 镜像生成：不能同时要「不手写」和「同一个类型」

要让 impl 与消费者**共用同一个类型**，类型就得住在被共享的工件里，于是 impl 依赖它；
而「不手写」又要求它从 impl 生成，即它依赖 impl：

```
impl 依赖 api（类型身份）  +  api 依赖 impl（自动生成）  =  环
```

两条出路：

| 路 | 做法 | 代价 |
| --- | --- | --- |
| A. 保住类型身份 | 手写 api（impl 依赖 api） | 仍然要维护 api 源码 |
| B. 保住「不手写」 | api 是**镜像**，impl 不依赖 api | 两个类型；IDE「跳到实现」失效；漂移靠指纹兜底 |

本设计只走 B：所有类型都镜像进 api，impl 不依赖 api。
需要跨插件继承的类型额外多一步——声明方为外来实例保留「基类影子」，见 §4.5。

---

## 4. 可行方案

### 4.1 两个模块：impl 与 api

当前架构只有两个东西：

```text
keel-plugin-animal/
├── settings.gradle.kts
├── build.gradle.kts          # 插件主体（impl，dylib）
├── src/commonMain/kotlin/... # ← 手写：@PublicApi 声明、@KeelPluginMain、服务实现
└── api/                      # ← 纯生成，空模块起步
    └── src/commonMain/kotlin/...
```

| 模块 | 放什么 | 谁依赖 |
| --- | --- | --- |
| `src`（impl） | 全部手写代码：`@PublicApi` 类型与成员、入口、服务、私有实现 | — |
| `api` | 由 `@PublicApi` 生成的镜像类型 / 代理 / Client / `typeId` / 指纹；**没有手写代码** | 所有消费方 |

依赖方向：消费方 → `api`；impl **不依赖** `api`。**没有环。**

- `api` 为空模块起步：`moe.lolosia.keel.plugin` 生成子工程、注册 `sourceSets`、挂 KSP、
  配置生成任务与发布，开发者不用手写任何 api 代码。
- 生成到 `api/src`（提交制）或 `api/build/generated`（自动制），见 §4.2。
- 坐标：`keel-plugin-<id>`（dylib）、`keel-plugin-<id>-api`。

### 4.2 两遍生成的两种变体

KSP 的 `CodeGenerator` 只能写当前编译的输出目录，所以「生成到 api」必然有两段：

```
impl 源码 → [KSP 提取契约模型] → 模型
                                   ├─→ impl 侧：适配器 / 分发器 / 注册 / 影子（进本目标 KSP 输出）
                                   └─→ api 侧：镜像类型 / Client（进 api 生成目录或 src/）
```

| 变体 | 落点 | 顺序 | 代价 |
| --- | --- | --- | --- |
| **V1 提交制（推荐起步）** | `api/src/commonMain/kotlin` | 无跨工程顺序问题 | 生成结果要提交；`keelApiCheck` 在 CI 比对是否过期 |
| V2 自动制 | `api/build/generated/keel/api` | `:api:compileKotlin*` 依赖模型任务 | 首次 sync / clean 后 api 短暂报红 |

模型提取建议跑在**单个目标**（宿主机目标）上：模型与目标无关，三个 target 各跑一次会互相覆盖。

### 4.3 类型规则

```kotlin
// 标在类型上：这个类型出现在跨插件边界
@Target(AnnotationTarget.CLASS)
annotation class PublicApi(val id: String = "")

// 标在成员上：类模式下，只有这些成员进入 api
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class PublicApi(val id: String = "")
```

| 声明 | 生成到 api 的东西 |
| --- | --- |
| `@PublicApi interface Foo` | `interface Foo` + `FooClient` + `FooKey` + 访问器 |
| `@PublicApi data class Foo(...)` | `data class Foo(...)` + `@Serializable` |
| `@PublicApi class Foo`（不被继承） | `interface Foo` + `FooClient` + 适配器 |
| `@PublicApi open class Foo`（要跨插件继承） | api 里的镜像 `Foo`（方法体是桩）+ 调用方代理；impl 侧生成声明方影子 `FooShadow : Foo` |
| 未标注的成员 | 不导出（fail-soft，构建日志显式列出） |

禁止：泛型方法、`vararg`、默认参数、函数类型参数、返回另一个共享对象（P2 用 `SharedRef<T>`）、
平台类型、`Any` / `Nothing`、可变集合、内部类、`expect` / `actual`。
属性按 §6.3 单独处理（只允许默认访问器）。

白名单：`Unit`、`Boolean`、整数族、`Float` / `Double`、`String`、枚举、`ByteArray`、
`List` / `Set` / `Map`（递归）、可空类型、`@PublicApi` 值类型、共享类型（按引用传，见 §4.5）。

重载**允许**，线名按**参数类型**推导（不是参数名）：

```
method = name                                  // 无重载
method = name + "/" + 参数类型规范化串          // initialization/String、initialization/String/Int
```

### 4.4 为什么 impl 不能直接 `implements` 生成的类型

`override fun first()` 无法实现 `suspend fun first()`——这是两个签名。要求 impl 也写 `suspend`，
impl 就必须先看到 api 类型，又回到 §3 的环。所以生成物是**一对**：api 侧镜像接口 + impl 侧适配器。
适配器同时用作远端 `ServiceDispatcher` 与本地直调路径（不过 CBOR、不过 Host）。

---

### 4.5 子类与继承：`super`、非虚方法、基类状态

你的判断是对的：**实现不一定是虚函数**，子类要能 `super.foo()`、能直接调用基类里没被覆盖的方法、
能读写基类字段。这些都成立，但有一个**编译期**前提。

#### 4.5.1 硬前提与「声明方影子」

`class Dog : Animal()` 要编译，编译器必须看到 `Animal` 的类型与构造器；`super.bark()` 是直接调用，
必须有可调用的目标。KSP 读不到方法体，所以「从 impl 生成带实现的 api」不成立。

默认架构（impl + api）里，api 的 `Animal` 只能是**生成出来的镜像**。要让这个镜像在没有方法体的情况下
仍然支持 `super` 与基类状态，必须补一个机制：**声明方为每个外来子类实例保留一个「基类影子」**。

| 角色 | 在哪 | 内容 |
| --- | --- | --- |
| api 镜像 `Animal` | 消费方 | **没有存储**：属性是访问器，方法是桩（转到声明方） |
| 基类影子 `AnimalShadow` | **声明方 b** | 生成的 `class AnimalShadow(handle) : impl.Animal()`：继承真实基类，持有裸字段、执行基类方法体、把虚方法按需转发给实例方 |
| 子类 `Dog` | 实例方 a | 只有派生状态；继承来的方法是桩，`super.x()` 走基类入口 |

路由：某个类型的外部调用统一进**声明方**（`typeId → 声明插件`），声明方持有影子并决定本地执行还是转发。

于是你说的成立：**基类可以继续用裸属性**。基类体在影子上执行，读写的是影子自己的字段；
远程的 `SharedVar` 也路由到同一个影子，读的也是那一个字段。一份存储，不会分叉。

调用链（a 本地调 `dog.onUpdate()`，Dog 只覆盖了 `bark`）：

```
a: Animal.onUpdate(桩) → 声明方 b
b: AnimalShadow.baseOnUpdate() 执行（裸字段可用）→ 调 bark()
   → bark 在 override 集合里 → 转发给实例方 a
a: Dog.bark → super.bark() → 桩 → b 的 baseBark()
b: AnimalShadow.baseBark() 执行（真实基类体）
a: 打印 "bark bark bark"
```

约束与代价：

1. **基类必须可无参构造**（影子要 new 出来），可调用成员要 `open`（影子要覆盖成转发）。
2. **对象被拆成两半**：基类态在声明方，派生态在实例方；`this` 在两侧不是同一个对象，
   `equals/hashCode` 按句柄；基类方法看不到派生字段，派生类读基类属性要走访问器。
3. **多一跳**：caller → 声明方 →（覆盖方法时）实例方。
4. 声明方停用 → 该类型的所有跨插件实例失去基类半边，调用报 `ServiceUnavailable`。
5. 影子随句柄惰性创建、随句柄销毁。

#### 4.5.2 影子怎么生成：基类入口、转发、属性入口

```kotlin
// 声明方 b 生成（继承真实 impl 基类）
class AnimalShadow(private val h: HandleId) : Animal() {
    override fun onUpdate() =
        if (overridden("onUpdate")) forwardToInstance(h, "onUpdate") else super.onUpdate()

    override fun bark() =
        if (overridden("bark")) forwardToInstance(h, "bark") else super.bark()

    // 只跑基类体：镜像里的桩与子类的 super.x() 都只走这里
    internal fun baseOnUpdate() = super.onUpdate()
    internal fun baseBark() = super.bark()

    // 属性永远读影子自己的裸字段，不再转发
    internal fun getAge(): Int = super.age
    internal fun setAge(v: Int) { super.age = v }
}
```

- `baseXxx()` 是关键：镜像桩与 `super.xxx()` 只允许走基类体，**绝不能调虚方法**，
  否则影子与镜像互相转发 → 无限循环。
- `overridden(...)` 来自实例方注册句柄时上报的 override 集合：在集合里 → 转发给实例方；
  不在 → 就地 `super.xxx()`。
- 属性走 `getAge/setAge`，只读影子的裸字段；`SharedVar` 就是从实例方调到这两个入口。
- 影子必须能构造基类：**基类可无参构造、可调用成员全 `open`**，否则生成期报错。

#### 4.5.3 override 集合：影子用它决定本地还是转发

实例方注册句柄时上报「本类型实际覆盖的方法线名集合」。影子对每个虚方法：

- 在集合里 → 转发给实例方（那边跑真正的 override）；
- 不在 → 就地 `super.xxx()`，省一次往返。

没有集合也能跑（全部转发，实例方的继承桩再回基类入口），但每次多两跳；
所以它是「必要的数据、可选的性能开关」。注意它和 `baseXxx()` 是两件事：
`super.bark()` 永远只走 `baseBark()`，与集合内容无关。

#### 4.5.4 IR 能解决什么、不能解决什么

| IR 想解决的 | 实际 |
| --- | --- |
| 让「C 继承 B 的类」编译通过 | **不能**：编译期缺超类型，IR 运行在编译之后 |
| `super.feed()` / 基类方法里访问字段 | **不需要**：声明方的影子就是真实基类实例，`super` 与裸字段都在那里 |
| 调用点不要手写 `if (remote) ... else ...` | **不需要**：代理模型里调用点就是 `animal.speak()`，虚调用直接进代理 |
| 避免每个类型生成一个代理类 | 代理是**编译期生成的类**，没有运行期开销；IR 反而要在每个消费方挂编译器插件 |

IR 要重写的清单：`inline`、泛型、`super`、默认参数、桥方法、属性访问器、`equals/hashCode/toString`、
`suspend` 调用点、`is`。每一条都可能悄悄改坏语义，而且漏挂插件的消费方会退回本地调用、
变成极难定位的问题。仓库里的 logger 编译器插件足以说明这类东西的维护成本。

**结论：目标是「provider 不写脏代码」，用生成的代理达成；IR 不是必要条件，也不是充分条件。**

#### 4.5.5 传出去的子类实例

1. C 把 `dog` 作为参数传给 B 或 A。参数静态类型是 `Animal`（或 `@PublicApi` 接口），不是 DTO，
   于是走**引用**：注册句柄（`SHARED_REGISTER`，带上 override 集合与类型描述符），载荷里放 `HandleRef`。
2. 调用方按自己的类型面生成代理；声明方 B **惰性创建基类影子** `AnimalShadow(handle)`，
   只创建一次，随句柄销毁。
3. 调用统一进声明方：镜像桩 → `baseXxx()`；虚方法 → 按 override 集合决定就地执行还是转发给实例方 C。
4. C 是实例所有者，**只有 C 拿得到 `Dog` 原对象**；B 持有基类影子，A 持有代理。
5. 任何人 `destroy()` 之后，所有侧再调用都报错；C 停用时 Host 把它的句柄全部标 `Closed`，
   B 的影子随之销毁。

类型描述符（就是你说的「类名 + 继承链」）随句柄上报：

```text
concreteTypeId = "dog-plugin:moe.lolosia.dog.Dog"
interfaces     = ["animal-plugin:moe.lolosia.animal.Animal", ...]
```

- Host 用它校验「调用方声明的静态类型是否在句柄导出的类型集合里」，不匹配直接报错；
- 生成 `typeName` / `isAnimal()` 这类判断（字符串比较），**不要**指望 `dog is Animal` 跨运行期成立；
- **不要**用描述符构造对象或动态创建子类——没有反射。

代理实现的约定：

| 成员 | 行为 |
| --- | --- |
| 未导出成员 | 远程实例上调用抛 `NotRemoteCallableException` |
| `equals` / `hashCode` | 按句柄 ID，不转发（两侧各有一个对象，但不是同一个实例） |
| `toString` | 本地生成 `Dog@0x...`，不转发 |
| 非 `open` 成员 | 影子无法覆盖成转发 → 生成期要求基类可调用成员全 `open` |
| 类型判断 | 用生成的 `isXxx()`；`is` 只对本地对象成立 |
| `this` | 基类体里的 `this` 是影子，派生体里的 `this` 是 `Dog`；不要跨半边比较身份 |

---

### 4.6 远程构造：无参 / 只带 ID 的构造函数 + `suspend initialization()`

```kotlin
@PublicApi
class DownloadQueue(
    private val id: HandleId,           // 构造前分配，构造时已知
    private val ctx: PluginContext,     // 本地注入，不来自远端
) : ShareObject {
    suspend fun initialization(config: QueueConfig) { ... }                 // initialization/QueueConfig
    suspend fun initialization(config: QueueConfig, owner: String) { ... }  // initialization/QueueConfig/String
    @PublicApi suspend fun enqueue(url: String): TaskId
    override fun destroy() { /* 释放文件句柄 / 线程池 */ }
}
```

- **构造参数只允许**：无参、`HandleId`、`PluginContext`。远端参数一律走 `initialization`；
  `PluginContext` 由生成的工厂从 `KeelPluginServices.register(binder, context)` 捕获。
- **状态机** `Creating → Open → Closed`：`Creating` 时只有 `initialization` 系列可用，
  其它方法抛 `NotInitializedException`；没有声明 `initialization` 的类型构造完即 `Open`。
- **失败自动销毁**：`initialization` 抛异常 → Host 立刻标 `Closed` 并通知所有者释放，不留半成品；
  成功后再次初始化抛 `AlreadyInitializedException`。
- **只有创建者能调用 `initialization`**（Host 按 `creator` 校验），避免多方同时初始化。
- 本地创建走同一套生成代码，不过 Host。

---

## 5. 共享对象（有状态实例）

### 5.1 声明与使用

```kotlin
// 拥有者：onEnable 里登记
ctx.shared.publish(queue)

// 消费者：拿提供者发布的单例
val queue = ctx.shared.require(DownloadQueueKey)
queue.enqueue("https://...")

// 消费者：自己创建一个实例
val mine = ctx.shared.create(DownloadQueueKey)   // 处于 Creating
mine.initialization(QueueConfig(max = 8))
```

- `ShareObject` 定义在 `keel-api`（`moe.lolosia.keel.api.share`），只有一个 `destroy()`。
  自己定义而不复用 `kotlin.AutoCloseable`：后者在 common 的稳定性随版本变化。
- `destroy()` 是实现侧的释放钩子，**不导出成远端方法**；远端销毁走 Host 的 `SHARED_DESTROY`。
- 同插件内 `require` 返回本地适配器，零往返。

### 5.2 生命周期：任何人可 destroy，不做引用计数

按 `InputStream.close()` 的语义：

| 动作 | 谁可以做 | 语义 |
| --- | --- | --- |
| `publish(queue)` | provider | 本地登记 + 上报 Host；句柄归 provider |
| `create(key)` | 任意消费者 | Host 转发给 provider 构造；句柄归 creator（仅用于初始化权） |
| `initialization(...)` | **creator** | 一次性；成功转 `Open`，失败自动销毁 |
| `destroy()` | **任何人** | Host 标 `Closed` → 通知所有者执行 `ShareObject.destroy()`；幂等，先到先得 |
| 插件停用 | Host | 该插件**拥有**的句柄标 `Closed`；它创建过的句柄通知所有者销毁 |

- **销毁后报错是使用方的问题**：之后的调用统一抛 `SharedObjectClosedException`
  （`ServiceUnavailableException` 的兄弟类型）。
- **不需要知道谁持有**：代理无状态，只存句柄 ID；Host 每次回答「还活着吗、发给谁」。失效天然惰性。
- **不需要引用计数**：谁用完谁销毁；没人销毁就活到所有者停用，泄漏边界清晰。
- `destroy()` 与在途调用：已投给所有者的不取消（Worker 串行，天然排队）；`Closed` 之后的新调用失败。
  `ShareObject.destroy()` 必须容忍「`suspend` 方法还挂在中间」。

### 5.3 Host 侧句柄表

```text
handles: Map<HandleId, Entry>
Entry(owner: ModuleId, creator: ModuleId?,
      concreteTypeId: String, interfaces: List<String>,
      overrides: Set<String>,               // 实例方实际覆盖的线名；影子据此决定本地/转发
      state: Creating | Open | Closed, fingerprint: String)

types: Map<String /* typeId */, TypeEntry(declaring: ModuleId)>
singletons: Map<String /* typeId */, HandleId>
```

- `HandleId` 由**所有者分配**（`ownerModuleId shl 32 or seq`），随注册一次性上报；Host 只做校验。
- **句柄是对象级的**：一个对象一个 ID，`interfaces` 是它导出的类型集合（§5.7）。
- 共享对象的调用按 `typeId → declaring` 进**声明方**：声明方持有基类影子，
  属性与基类体都在那里执行，命中的 override 再转发给 `owner`。
  没有跨插件子类时 `declaring == owner`，退化成普通的点对点转发。
- `singletons` 让消费者的代理可以**完全惰性**：调用里 `handle = 0` 表示「按 typeId 找单例」。
- **没有 holders 字段**；转发沿用现有 `forwards` 表与 correlation 机制。

### 5.4 协议增补

| subject | 方向 | 载荷 |
| --- | --- | --- |
| `KEEL_SUBJECT_SHARED_REGISTER` | 插件 → Host | `SharedRegistration(handle, concreteTypeId, interfaces, overrides, fingerprint)` |
| `KEEL_SUBJECT_SHARED_CREATE` | 插件 → Host → provider | `SharedCreate(typeId)`；回包带 `handle` |
| `KEEL_SUBJECT_SHARED_CALL` | 插件 → Host | `SharedCall(typeId, handle /*0=单例*/, method, args)` |
| `KEEL_SUBJECT_SHARED_DESTROY` | 插件 → Host → owner | `SharedRef(handle)`；任何人可发 |

- 共享类型 / `@PublicApi` 接口作为参数或返回值 = `HandleRef(handle)`，CBOR 编码，不需要新 subject。
- 回包复用 `COMPLETE` + correlation，不新增事件类型。
- 没有 `SHARED_CLOSED` 广播：失效惰性（§5.2）。需要「立刻让所有代理报错」时再加 P2 的可选推送。
- `keel_abi.h` 里 subject 段是纯 `#define`，追加不影响结构体布局；仍按「改 ABI 先改文档与头文件」走。

### 5.5 失效、在途与异常

- Host 转发前检查 `state`：`Closed` 直接回错误 → `SharedObjectClosedException`。
- **provider 死亡**：它的句柄全部标 `Closed`、清掉 `singletons`；之后是 `ServiceUnavailable` /
  `SharedObjectClosed`，不是超时。
- **creator 死亡**：`creator == 该插件` 的句柄标 `Closed` 并通知 provider 销毁。
- 死锁：实例方法里再调另一个插件 → 与现有 A→B→A 环同一规则，
  照 [codegen-design.md](codegen-design.md) §4.1 的调用深度上限处理。

### 5.6 契约指纹

- 生成器把规范化模型（typeId + 成员线名 + 参数/返回类型串，稳定排序）哈希成常量，
  写进 api 生成物与 provider 的注册载荷；
- Host 在 `REGISTER` / `CREATE` 时比对，不一致明确报「api 与实现版本不一致」；
- `keelApiCheck`（V1）在 CI 重新生成并 diff。

### 5.7 一个对象实现两个接口：为什么不会炸

前提是**句柄按对象分配**：

1. 一个对象 = 一个 `HandleId`，`interfaces` 是它导出的类型集合；
2. 每个接口 / 基类各生成一个代理，代理都指向同一个句柄；
3. `destroy()` 作用于句柄：一次销毁，所有代理同时失效（§5.2 的「任何人可销毁」同样适用）。

- 两个接口都继承 `ShareObject` 时，生成的代理显式 `override fun destroy()`（Kotlin 要求），多一行转发。
- **会炸的写法**是按接口各分配一个句柄：一个销毁、另一个还活着，指向同一对象。明确禁止：
  `publish` 只接受具体类型，由生成器一次性登记全部导出类型。
- 跨接口强转：两个接口在同一个 api 工件里时生成 `fun asBar(): BarApi?`（同句柄、新代理）；
  跨插件的接口强转做不了——类型不同源。

### 5.8 回调：把本地实现传给对方

```kotlin
// B 声明（impl），api 生成镜像 + 分发器
@PublicApi interface BPluginCallback {
    suspend fun invoke()
}

// C：匿名实现，按引用传给 B
b.register(object : BPluginCallback {
    override suspend fun invoke() { /* C 的代码 */ }
})
```

**支持。** 这是最简单的一种句柄用法：没有基类、没有影子，只有「接口 + 句柄 + 分发」。

需要的三件生成物：

1. api 工件里为该接口生成**实现侧分发器** `BPluginCallbackDispatcher`（按方法名调用任意实现）。
   KSP 看不到匿名对象，所以分发器只能按接口生成；没有它，C 的运行时无法回答 `invoke`。
2. 参数编码：静态类型是 `@PublicApi` 接口 → `attach(对象)` 注册句柄
   （owner = C，`interfaces = [该接口]`），载荷放 `HandleRef`。
3. 接收方代理：B 收到句柄后包成实现 **B 自己**的 `BPluginCallback` 的代理，`invoke()` 转发回 C。

匿名类没问题：句柄只需要「导出的接口类型」，不需要具体类名；`concreteTypeId` 可以退化成
`c-plugin:<file>:<line>$1` 之类的诊断名。它只导出**静态声明的那个接口**——如果匿名对象还实现了别的
`@PublicApi` 接口，调用点看不到，需要显式再传一次或包一层具名类。

生命周期与语义：

- 句柄归 C；B 不再需要时应 `destroy()`，否则活到 C 停用（Host 统一清理）。
  建议 `register` 返回 `Subscription`（内部就是句柄 id），`cancel()` 等于销毁。
- 回调在 **C 的 Worker** 上执行；B 不要在持锁时调用，注意 A→B→A 的调用深度。
- C 的 `invoke()` 抛异常会以 `KeelRemoteException` 回到 B 的调用点。
- `register` 建议声明为 `suspend`：B 若在 `register` 内同步回调，C 的挂起点仍然能处理事件，
  不会死锁；用阻塞入口则要靠重入事件泵。

---

## 6. 实现上的几处取舍

### 6.1 构造期申请实例 → create + initialization

构造器 / `onLoad` / `onEnable` 都不是 `suspend`，构造期跨插件申请只能阻塞 Worker，
而且对方可能还没 `publish`。你的方案（无参或只带 ID 的构造 + `suspend initialization()`）正好绕开。

### 6.2 close 后不广播 → 惰性失效

所有调用都经过 Host，Host 知道句柄状态。广播只在「希望本地立刻感知」时有意义，放 P2。

### 6.3 属性 → 访问器对象

`@PublicApi var age = 0` 在 api 侧不生成函数，而是生成一个访问器属性：

```kotlin
// b 的共享对象（只 publish / create，不被跨插件继承）
@PublicApi
class Player {
    @PublicApi var age = 0
}

// api 侧生成（消费者看到的）
class Player {
    val age: SharedVar<Int> by lazy { SharedVar(handleProvider, "age") }
}
```

- `keel-api` 提供两个泛型：`SharedVar<T>`（`suspend get()` / `set(v)`）与 `SharedVal<T>`（只 get），
  **不需要**为每个属性生成一个 `AgeAccesser` 类型，也不需要手写 api。
- 访问器要在**调用时**读当前句柄。`SharedVar(this.id, "age")` 里的 `this.id` 是构造时的快照，
  本地对象的句柄是首次跨界时才惰性分配的，eager 版本会拿到 0；用 `by lazy` 或让访问器持有
  句柄提供者。
- 只允许**默认访问器的 backing field 属性**。自定义 getter/setter、`lateinit`、委托、`private set`、
  `const`、扩展属性一律生成期报错——远端无法复现它们的逻辑。
- **不保证原子性**：`x += 1` 是 get + set 两次往返，并发下会丢更新。需要原子语义就写成方法
  （`suspend fun increment()`），由拥有者在单线程上执行。
- 每次 `get` / `set` 都是一次往返。循环里读属性要先取到本地变量。
- **值存在哪里**：远程对象的基类属性由**声明方的影子**持有（§4.5）；`SharedVar` 只是入口，
  自己不存值。于是基类可以继续用裸 `var age = 0`，基类体与远程访问读写的是影子上的同一个字段。

| 场景 | 属性 |
| --- | --- |
| `publish` / `create` 出来的远程对象 | 正确（访问器 → 拥有者运行时里的真实字段） |
| 跨插件子类的实例 | 正确（访问器 → 声明方的基类影子，读它上面的裸字段） |
| 子类自己声明的属性 | 留在子类运行时；基类方法看不到（基类只知道自己声明的成员） |
| `@PublicApi interface` | v1 禁止，改写成 `suspend fun getAge()`（实现方无法自然地提供 `SharedVar`） |

访问器的判定与存储：

- 访问器**不持有值**，只持有「当前对象的句柄提供者」；
- 属性调用路由到**声明方**（`typeId → 声明插件`），在那里读/写影子上的裸字段；
- 所以「基类体读写裸属性」与「远程通过 `SharedVar` 读写」是同一份存储，不会分叉；
- 每次远程访问一次往返；`x += 1` 仍是 get + set，不保证原子性。

### 6.4 参数类型 → 补两条

- 必须**可镜像**：泛型实参递归满足白名单、没有函数类型、没有平台类型；
- `typeId` 带插件前缀（`animal-plugin:moe.lolosia...Animal`），Host 遇重复直接拒绝。

### 6.5 为什么不用 IR 拦截

见 §4.5.4。一句话：**IR 不是必要条件**（生成代理已经做到），**也不是充分条件**（编译期缺基类、
`this` 错位它都救不了），代价却是在每个消费方重写所有调用形态。

### 6.6 同步调用方：用 `callBlocking`，不要 `runBlocking`

- 代理方法生成成 `suspend`；作者把 impl 方法写成同步时，生成期打一条 **WARNING**
  （「跨插件调用会阻塞调用方 Worker，建议声明为 suspend」）。
- 需要同步调用点时生成 `fun fBlocking()`，内部用现成的 `callBlocking`：
  投递请求后在**自己的 Worker 上重入事件泵**，直到回包（[codegen-design.md](codegen-design.md) §4.1）。
- **不能用 `runBlocking`**：它在 Worker 线程上再开一个事件循环，而回包依赖同一个 Worker 的泵，
  两边互等 → 死锁；而且它不线程亲和，跨线程调用会进错运行时。
- 同步调用只能在模块自己的 Worker 线程发起；其它线程抛 `IllegalStateException`。

---

## 7. 风险与代价

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 镜像类型导致「跳到实现」失效、断点落在生成代码 | 调试体验下降 | 生成物带 `// 对应 impl.QueueImpl.first` 注释；IDEA 插件后续加导航 |
| api 与 impl 漂移 | 运行期 decode 失败 / 字段错位 | 契约指纹 + `keelApiCheck` |
| 跨工程 task 顺序（V2） | 首次 build / clean 后 api 报红 | 默认 V1 提交制；V2 灰度 |
| 任何人可 destroy | 别人正在用的对象被踢掉 | 有意为之（`InputStream.close()` 语义）；错误类型明确 |
| 基类有非 `open` 成员，或无法无参构造 | 影子无法覆盖成转发 / 无法创建 | 生成期校验：可调用成员全 `open`、可无参构造 |
| 代理上的 `is` / 私有成员不可用 | 业务代码以为拿到的是本地对象 | 生成 `isXxx()` / `typeName`；未导出成员抛 `NotRemoteCallableException` |
| 传出去的句柄没人销毁 | 对象活到所有者停用 | 任何人可销毁；所有者停用时 Host 统一清理 |
| 实例方法阻塞 provider 的 Worker | 所有调用方一起卡 | 文档明确「对象单线程、调用串行」；provider 侧鼓励 `suspend` |
| 任何插件知道 typeId 就能 create / 调用 | 越权访问 | `requires` / 权限白名单（P2），Host 在 CALL / CREATE 前校验 |

---

## 8. 分期与验收

### P0：接口镜像（解决「手写 api」这个真痛点）

- `@PublicApi interface` + `@PublicApi data class` 的镜像生成；
- `keelApi`（生成一次、写进 `api/src`）与 `keelApiCheck`（CI 比对）；
- 生成 api 的 `Key` / `Client` / 访问器；impl 侧 `Dispatcher` / `LocalAdapter` / 注册；
- 重载线名规则（`name/Type/Type`）；
- 复用现有 `SERVICE_CALL` 通道，**不加新协议**。

验收：删掉手写的 `api/`，只保留注解与实现，`keel-plugin-hello` 与一个新建的消费方插件仍能编译、调用跑通。

### P1：共享对象

- `ShareObject` / `SharedDirectory`（`publish` / `require` / `create` / `initialization` / `destroy`）；
- Host 句柄表（owner + state + creator + 类型描述符）、`SHARED_*` 四个 subject；
- `Creating → Open` 状态机、初始化失败自动销毁、任何人可 destroy、`SharedObjectClosedException`；
- 指纹登记与校验；provider / creator 停用两条清理路径。

验收：两个插件互相调用一个有状态对象的 `suspend` 方法；任意一方 `destroy()` 后新调用立刻抛
`SharedObjectClosedException`；provider 停用后 `--inspect` 里句柄全为 `Closed`、对象被 `destroy()` 一次。

### P2：跨插件继承

- 声明方影子的生成与校验（基类可无参构造、可调用成员全 `open`）；
- `FooShadow : impl.Foo` 生成、override 集合上报、`HandleRef` 参数、类型描述符、`isXxx()`；
- `SharedRef<T>` 返回值、跨接口强转、属性语法糖、回调参数、直接路由、权限白名单。

---

## 9. 未决问题

1. `@PublicApi` 与现有 `@ModuleService` 是否合并？建议不合并（生成契约 vs 手写契约）。
2. 模型提取跑在哪个 target 需要在三个目标上各验一次。
3. 生成物写进 `src/` 时是否允许手工修改（建议禁止，由 `keelApiCheck` 覆盖）。
4. 指纹粒度：整类型一个哈希，还是按方法分片。
5. `initialization` 是否允许 reset / reopen，还是严格一次性。
6. 传出去的句柄默认活到所有者停用，还是约定「调用返回后即失效」？前者简单，后者更省内存。
7. 「可调用成员全 `open` + 可无参构造」这条校验能否稳定实现（属性、委托、`const` 的判定）。
8. api 工件与插件版本的关系（跟随 `keel` 版本 vs 独立升版）。

---

## 10. 与现有文档的关系

- [codegen-design.md](codegen-design.md) §1 的「仍然有 `api` 子项目」结论**不变**；
  变化的是「谁写 api」：从开发者手写改成生成（或生成后提交）。
- [codegen-design.md](codegen-design.md) §2.2 的示例是「impl 直接实现 api 接口」，
  在镜像模式下不成立（§4.4），回填时需要改写。
- [abi-design.md](abi-design.md) §6.6 的「能力对象 = 句柄代理」与本方案同构，但这里用
  **Host 侧的逻辑句柄**（整数 id + 表），而不是 C 句柄表的 `StableRef`，因为生命周期是逻辑的。
- [plugin-contract-discussion.md](plugin-contract-discussion.md) §5.1 担心的「镜像类型 + 结构兼容」
  是可控的：两侧由同一模型生成，漂移只可能发生在**已发布的 api** 与**新 impl** 之间，由指纹兜住。
