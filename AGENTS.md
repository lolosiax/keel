# AGENTS.md —— Keel 仓库约定

> 本仓库是 **Keel**：一个 Kotlin/Native 的模块化 Web 框架（Host + Plugin API + Launcher + Gradle 工具链）。
> 本文件是**规范性**文档（约定，不是教程）：改结构、加依赖、写插件都按这里来；与代码冲突时以代码为准，并顺手回来修本文。
> 深度材料见 [`doc/`](doc/)：可行性讨论在 [前序讨论.md](doc/前序讨论.md)，阶段计划在 [roadmap.md](doc/roadmap.md)。

## 0. 项目定位

- **心智模型**：Tomcat / WAR + Bukkit / Spigot。Host 是容器（生命周期、路由、上下文注入），Module 是部署单元（业务功能）。
- **运行时**：Kotlin/Native（KMP），目标 `mingwX64`（Windows 开发）、`linuxArm64`（开发板，代表机型 RK3566）、`linuxX64`；**没有 JVM 目标**。
- **交付形态**：
  - 常规模式：`Host` 可执行文件 + 每个插件一个动态库（`.dll` / `.so`）。
  - Launcher 模式：Host、插件与资源内嵌为**单文件**，启动时不释放 `.so` / `.dll`（做不到时退化为解包到缓存目录）。
- **硬约束**（来自 [前序讨论.md](doc/前序讨论.md)，不要在设计里假设可以绕过）：
  - Kotlin/Native 运行时**不可安全卸载**：`dlclose` 后 TLS / GC 会延迟崩溃，只能「引用计数归零但永不 unmap」。
  - Host 与插件是**两个独立运行时**：只能传 blittable 类型与不透明指针，不能传 Kotlin 对象 / 接口。
  - 协程不能跨 C ABI；异步一律退化为「投递 + 回调通知」，执行权留在各自 Worker 线程。
  - 热路径的跨边界载荷用 Protobuf / CBOR，**不要用 JSON**。

## 1. 目录与构建边界

每个顶层目录都是**独立的 Gradle 构建**，由根 `settings.gradle.kts` 用 `includeBuild` 聚合（composite build）。
单独构建某个部分用 `gradlew -p <目录> <任务>`，不要依赖别的目录被一起配置。

⚠️ **根目录的 `gradle.properties` 不会下发给 includeBuild**：需要 `kotlin.mpp.enableCInteropCommonization`
（`keel-api` 已需要）或其它构建属性的构建，必须在自己的目录里放一份 `gradle.properties`。

| 目录 | rootProject.name | 角色 | 是否发布 |
| --- | --- | --- | --- |
| `keel-classpath-gradle-plugin/` | `keel-classpath-gradle-plugin` | 普通产物的 `classpath.zip` 嵌入插件 `moe.lolosia.keel.classpath` | 是 |
| `keel-plugin-gradle-plugin/` | `keel-plugin-gradle-plugin` | `.keel` 打包插件 `moe.lolosia.keel.plugin` | 是 |
| `keel-publish-gradle-plugin/` | `keel-publish-gradle-plugin` | 发布插件 `moe.lolosia.keel.publish` | 是 |
| `keel-router-ksp/` | `keel-router-ksp` | Web 路由 KSP 处理器（构建期，不是 Gradle 插件） | 是 |
| `keel-classpath/` | `keel-classpath` | 独立资源 Classpath 库（zip / 目录 / 前缀视图），不依赖 keel-api | 是 |
| `keel-classpath-full/` | `keel-classpath-full` | Host 注册表、`ClasspathRef`、租约、跨模块代理 | 是 |
| `keel-api/` | `keel-api` | 插件契约：生命周期、上下文、消息、调度（`moe.lolosia.keel.api`） | 是 |
| `keel-host/` | `keel-host` | 主进程：插件加载、生命周期、Ktor 引擎、上下文注入 | 是（作为库） |
| `keel-launcher/` | `keel-launcher` | 单文件发行版入口，静态链接 Host 并内嵌插件 | 是（可执行文件） |
| `keel-plugin-hello/` | `keel-plugin-hello` | 示例插件；新插件按 `keel-plugin-<id>/` 平铺在顶层 | 是 |
| `doc/` | — | 设计与计划 | — |

依赖方向（禁止反向）：

```
keel-*-gradle-plugin / keel-router-ksp ──(构建期)──> keel-plugin-*  (KSP / 打包 / 发布)
keel-api  <──  keel-host  <──  keel-launcher
keel-api  <──  keel-plugin-*
```

- `keel-api` **不得**依赖 `keel-host`、`keel-launcher`、Ktor 或任何第三方服务端库；它是对外承诺的稳定面。
- `keel-plugin-*` 只能依赖 `keel-api`（以及自己声明的库）；需要宿主能力时走 API，不要反向 include `keel-host`。
- 跨构建的依赖一律写 Maven 坐标 `moe.lolosia.keel:<artifact>:<version>`；在根 composite 下 Gradle 会自动替换成本地工程。

## 2. 构建与验证

```powershell
# 全仓库（根聚合，逐个构建所有 includeBuild）
gradlew buildAll

# 单个部分
gradlew -p keel-api build
gradlew -p keel-host linkDebugExecutableMingwX64
gradlew -p keel-launcher linkReleaseExecutableLinuxArm64
gradlew -p keel-plugin-hello linkReleaseSharedLibLinuxArm64

# 工具链发布到自建 Maven
gradlew -p keel-classpath-gradle-plugin publish
gradlew -p keel-plugin-gradle-plugin publish
gradlew -p keel-publish-gradle-plugin publish
gradlew -p keel-router-ksp publish
```

- **改了公共 API 至少跑**：`gradlew -p keel-api build` + `gradlew -p keel-plugin-hello build`（消费方必须能编过）。
- 单独构建 `keel-host` / `keel-launcher` / `keel-plugin-*` 时，`keel-api`（以及 `keel-host`）必须已发布到 Maven；否则用根聚合，或加 `--include-build ../keel-api`。
- Kotlin/Native 首次编译会下载 konan 工具链；Windows 上链接前先确认没有旧的 `keel*.exe` 进程占用输出文件。

## 3. 版本、坐标与命名

- **唯一版本来源**：`gradle/libs.versions.toml` 的 `versions.keel`；所有构建用 `version = libs.versions.keel.get()`，不要各写各的。
- 开发版用 `-SNAPSHOT`；发布版去掉后缀并打 git tag（`v0.1.0`）。
- `group = "moe.lolosia.keel"`，包根 `moe.lolosia.keel`。工具链插件单独的 group 是 `moe.lolosia.keel.toolchain`。
- 工件命名：

| 工件 | 说明 |
| --- | --- |
| `keel-api` | 插件契约 |
| `keel-host` | 主进程库（launcher 静态链接） |
| `keel-launcher` | 单文件可执行产物 |
| `keel-plugin-<id>` | 功能插件，`<id>` 与 `plugin.json` 的 `id` 一致 |
| `keel-classpath-gradle-plugin` / `keel-plugin-gradle-plugin` / `keel-publish-gradle-plugin` / `keel-router-ksp` | 构建期工具 |

- Gradle 插件 ID 统一 `moe.lolosia.keel.<能力>`：`moe.lolosia.keel.publish`、`moe.lolosia.keel.classpath`、`moe.lolosia.keel.router`。

## 4. 代码风格

沿用后端工程的约定，**新代码一律照此写**：

1. **不写 `public`**：Kotlin 默认即 public；只有 `internal` / `private` 显式标注。
2. **import 用通配**：`import moe.lolosia.keel.api.*`，不逐条展开；同包不写 import。
3. **注释只写正经 KDoc**：用途 / 参数 / 不变量 / 真实约束。不写「改动历史」「参照方案第几节」。
4. **一个 `.kt` 只放一个顶层类型**；`private` 顶层声明（常量、单文件辅助函数）可随宿主文件放。
5. **散装函数 / 常量放同包 `package.kt`**；扩展函数按「被扩展的库」分域。
6. 不要把平台相关的 C interop 代码放 commonMain：`@CName`、`StableRef`、`CPointer` 一律进 `nativeMain` / 具体目标源集。
7. 日志走 API 的 `PluginLogger`，不要在库里 `println`；Host 侧统一一个 `Log` 门面。

## 5. 插件 API 与跨运行时边界

`keel-api` 是**两个 Kotlin/Native 运行时之间唯一合法的接触面**：生命周期、事件泵、Worker、
消息分发、事件总线、服务注册表、调度器与协程桥接都在这里。完整设计见 [doc/abi-design.md](doc/abi-design.md)。

- **一个线程只能进入一个 Kotlin/Native 运行时**。插件 Worker 由 C 侧 `pthread_create` / `CreateThread`
  创建；插件的 `@CName` 入口只能在该 Worker 上调用，Host 线程不得直接调用。
- 插件 dylib **对 Host 只暴露** `keel_plugin_bootstrap` / `keel_plugin_shutdown`（C 实现）；
  Kotlin 侧的 `@CName` 工厂（如 `keel_plugin_hello_create`）只允许由 bootstrap 在 Worker 线程上调用。
  队列、句柄表、分配器都是 hidden 符号，每个二进制各带一份，不导出。
- Kotlin 对象不跨边界：只能传 **C 结构体 / 字节 / 不透明句柄 / 纯 C 函数指针**。
  对象留在拥有者运行时，另一边拿到的是「句柄 + 本地代理」。
- 句柄 = `StableRef` 在 Host C 句柄表里的槽位，引用计数归零时投递 `DISPOSE` 事件，
  **由拥有者 Worker 在自己的线程上 dispose**（见 [abi-design.md](doc/abi-design.md) §5）。
- Kotlin 回调不转函数指针：注册「回调 id」，调用时投递事件，拥有者查表执行。
- 调度器 / 协程不跨运行时：透传的是调度命令与完成事件；`Messenger.request` 是本地 `suspend`，
  continuation 永不跨 ABI。
- **模块间调用**走 `ctx.modules.require(ServiceKey)` 的本地代理：查询用 `suspend` 请求 / 应答，
  命令用 ack 或 `post`；没有 `ctx.modules.moduleB` 这种字段。见 [abi-design.md](doc/abi-design.md) §6。
- 热路径载荷用 Protobuf / CBOR；结构体只在共享头文件里定义，通过指针传，Kotlin 侧不手写布局。
- 新增 API 时先问三个问题：
  1. 是否需要在两个运行时之间传？需要 → 只留 blittable 面，或改成「句柄 + 事件」。
  2. 是否是 suspend 能力？是 → 在 API 里用本地 `suspend` + 事件桥接，不要暴露到 ABI。
  3. 宿主升级后旧插件还能不能加载？不能 → 提升 `abiVersion` 并在 `plugin.json` 里声明兼容范围。

ABI 头文件在 [`keel-api/src/nativeInterop/cinterop/keel_abi.h`](keel-api/src/nativeInterop/cinterop/keel_abi.h)，
**改 ABI 先改文档与头文件，再改实现**。

## 6. 插件工程约定

- 每个插件是顶层 `keel-plugin-<id>/` 目录下的**独立 Gradle 构建**，内部固定分成 `api` / `impl` 两个子项目：
  - `api`：`@ModuleService` 接口 + DTO + KSP 生成的客户端（消费方只依赖它）；
  - `impl`：实现 + `@KeelPluginMain` + KSP 生成的分发器 / Worker 入口（产出 dylib）。
  - 两个子项目的构建脚本由 `moe.lolosia.keel.plugin` 配置，**不要手改**；开发者只写接口和实现。
- 插件产出**动态库**（`binaries.sharedLib`），`baseName = "keel-plugin-<id>"`；launcher 发行版另行静态并入，不改变插件工程形态。
  ⚠️ Kotlin/Native 会把 `baseName` 里的短横线换成下划线（实际产物是 `keel_plugin_hello.dll`），加载器按目录里的实际文件名扫描，不要写死。
- 插件工程至少包含：

```
keel-plugin-<id>/
  settings.gradle.kts                  # 由 settings 插件 include(":api", ":impl")
  build.gradle.kts                     # plugins { id("moe.lolosia.keel.plugin") } + keel { pluginId = "<id>" }
  api/src/commonMain/kotlin/.../api/XxxApi.kt      # 接口 + DTO（@ModuleService）
  impl/src/commonMain/kotlin/.../XxxPlugin.kt      # 实现（@ModuleServiceProvider）+ 入口（@KeelPluginMain）
```

- **不要手写**代理、分发器、CBOR 编解码、服务注册、`plugin.json`、Maven 发布配置；
  这些由 Gradle 插件 + KSP 生成，机制见 [doc/codegen-design.md](doc/codegen-design.md)。
- 消费方模块只加一行 `keel { requires("<id>") }`，然后 `ctx.<id>.method(...)`；
  同一模块内调用返回本地实现，跨模块返回生成的客户端。
- 新增插件 = 在顶层新建 `keel-plugin-<id>/` + 在根 `settings.gradle.kts` 加一行 `includeBuild`；不要塞进某个聚合目录。

- `plugin.json` 是部署单元的唯一清单；**不要**靠扫描类路径或反射找插件（Native 上没有反射）。
- **配置**：主线 `application.yaml` + 每插件 `config/plugins/<id>.yaml`，**共用同一个 `Config` 接口**。
  插件读自己的用 `ctx.config`；读 / 写主线用 `ctx.server.config`（读写都委托 Host）。
  插件自己的配置读取走本地快照、写入委托 Host；YAML 解析 / 文本编辑 / 落盘全在 Host，
  **必须保留注释**，插件不得直接读写配置文件。规则见 [doc/config-design.md](doc/config-design.md)。
- **Classpath**：插件用 `ctx.classpath`（自身 `.keel` 的 `resources/` 优先，缺失回落 Host）；
  跨插件只能用 `ctx.classpath.of("id")`，不暴露对方 `.keel` 路径。Host 侧用
  `server.classpath` / `server.classpathOf(id)`。`.keel` 资源不解包，卸载后读取立即失效。
  规则见 [doc/classpath-design.md](doc/classpath-design.md)。
- **Web 路由**：插件只写 `@Controller` / `@GET` 等注解，由 `keel-router-ksp` 生成路由表；
  Host 独占 Ktor 端口与中间件，插件**不得**创建引擎或 install Ktor 插件。
  请求上下文是 `Context` 快照，**没有 `ctx.call`**；规则见 [doc/web-design.md](doc/web-design.md)。
- **权限**：契约在 core（`@Permission` / `ctx.hasPermission` / `Authorizer`），实现是
  `keel-plugin-rbac`；Host 在分发前校验，无 Authorizer 时带权限的路由 **fail closed（503）**。
  只声明权限的业务插件不依赖 rbac 工件；需要管理角色 / 规则时才依赖 `keel-plugin-rbac-api`。
  见 [doc/authz-design.md](doc/authz-design.md)。
- 资源（模板、静态文件、配置默认值）放 `src/commonMain/resources`，由 `moe.lolosia.keel.classpath` 打包后在运行期读取。
- 插件不得写 Host 的工作目录；需要持久化时通过 API 申请自己的数据目录（P1 提供）。

## 7. Maven 发布与凭据

- 发布地址与账号**只放本机 `local.properties`**（已被 `.gitignore` 忽略），键名：

```properties
keel.repo=http://<maven-host>/repository/keel
keel.username=<user>
keel.password=<password>
```

- 自建 Maven 通常是内网 HTTP 或自签 HTTPS，仓库配置需 `isAllowInsecureProtocol = true`（各 `keel-*-gradle-plugin` / `keel-router-ksp` 的 `build.gradle.kts` 与各构建的 `settings.gradle.kts` 已处理）。
- **凭据、内网地址、口令一律不入库**，包括本文档与 `doc/`；用占位符。
- 构建期工具各自在自己的 `build.gradle.kts` 里配置发布仓库，没有统一入口；功能插件用 `moe.lolosia.keel.publish` 插件，不要在业务构建里手写 `publishing {}`。
- 发布顺序：`keel-*-gradle-plugin` / `keel-router-ksp` → `keel-api` → `keel-host` → `keel-plugin-*` → `keel-launcher`。改了 `keel-api` 必须确认消费方重新编译。

## 8. Monorepo 与 Sparse Checkout

- 整个仓库是一个 Monorepo；**每个顶层目录都能单独 clone 出来构建**（依赖从 Maven 取），这是拆分的前提。
- 只关心部分目录时用 cone 模式 sparse checkout：

```powershell
git sparse-checkout init --cone
git sparse-checkout set keel-api keel-host keel-launcher doc
```

- Sparse 后 `settings.gradle.kts` 里的 `includeBuild(...)` 会指向不存在的目录：**必须同步注释掉**缺失的行，否则 Gradle 配置阶段直接失败。
- 反向要求：任何目录的构建都不能读取兄弟目录里的源文件——跨目录只能通过 Maven 坐标或产物文件。

## 9. 文档地图

| 文档 | 内容 |
| --- | --- |
| [doc/前序讨论.md](doc/前序讨论.md) | Kotlin/Native 插件化的可行性讨论：运行时不可卸载、跨运行时对象、协程边界、序列化协议、风险清单 |
| [doc/abi-design.md](doc/abi-design.md) | **C ABI 与线程模型**：事件泵、Worker、句柄 / 代理、回调、事件总线、调度器桥接、内存与版本规则 |
| [doc/codegen-design.md](doc/codegen-design.md) | **零样板模块互访**：Gradle 插件 + KSP 生成客户端 / 分发器 / 访问器 / 回调 / plugin.json |
| [doc/config-design.md](doc/config-design.md) | **配置读写**：YAML + 注释保留、分层覆盖、插件配置隔离、原子落盘与变更通知 |
| [doc/web-design.md](doc/web-design.md) | **Web 路由**：Host 单端口、插件路由注册、Context 快照、流式响应、中间件与鉴权 |
| [doc/authz-design.md](doc/authz-design.md) | **权限边界**：core 只留 `@Permission` / `Authorizer` SPI，RBAC 实现归 `keel-plugin-rbac`，fail closed |
| [doc/capability-design.md](doc/capability-design.md) | **能力插件**：接口在 core、实现按需安装、稳定 C ABI、可卸载；首个示例 Lua 中间件引擎 |
| [doc/classpath-design.md](doc/classpath-design.md) | **Classpath 与 `.keel`**：资源命名空间、zip / 目录读取、插件包、注册表与卸载语义 |
| [doc/任务记忆.md](doc/任务记忆.md) | **长任务交接**：已完成模块、构建命令、验证结果、下一步与已知坑 |
| [doc/roadmap.md](doc/roadmap.md) | DEMO / P0 / P1 / P2 的目标、交付物、验收标准与待决问题 |

## 10. 当前待确认

- 泰山派上自建 Maven 的**实际地址、端口、仓库路径与鉴权方式**（找到后写进本机 `local.properties`，并把 `doc/roadmap.md` 的占位符替换掉）。
- capability provider（以 Lua 为示例）的可行性、默认分发形态（外部纯 C 库 vs 静态内嵌）与中间件沙箱边界，见 [doc/capability-design.md](doc/capability-design.md)。
- 目标 Kotlin/Native 版本与三个平台的实际可编译性（DEMO 第一件事就是跑通）。
- Launcher 在三个平台上能否**内存加载**插件动态库；不能时统一的回退目录与清理策略。
