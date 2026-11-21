# AGENTS.md —— Keel 仓库约定

> 本仓库是 **Keel**：一个 Kotlin/Native 的模块化 Web 框架（Host + Plugin API + Launcher + Gradle 工具链）。
> 本文件是**规范性**文档（约定，不是教程）：改结构、加依赖、写插件都按这里来；与代码冲突时以代码为准，并顺手回来修本文。
> 深度材料见 [`doc/`](doc/)：可行性讨论在 [前序讨论.md](doc/前序讨论.md)，阶段计划在 [roadmap.md](doc/roadmap.md)。

## 0. 项目定位

- **心智模型**：Tomcat / WAR + Bukkit / Spigot。Host 是容器（生命周期、路由、上下文注入），Module 是部署单元（业务功能）。
- **运行时**：Kotlin/Native（KMP），目标 `mingwX64`（Windows 开发）、`linuxArm64`（开发板，代表机型 RK3566）、`linuxX64`；**没有 JVM 目标**。
- **交付形态**：
  - 常规模式：`Host` 可执行文件 + 每个插件一个动态库（`.dll` / `.so`）。
  - Launcher 模式：`Host` + 插件 + 资源内嵌为**单文件**；`.keel` 与 `resources/` 不落盘（内存 zip），
    只有插件 / 能力包的动态库物化到缓存目录后 `dlopen`。
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
| `keel-gradle-common/` | `keel-gradle-common` | 所有工具链插件共用的 `keel { }` 根容器（只按类型注册 section，不单独 apply） | 是 |
| `keel-classpath-gradle-plugin/` | `keel-classpath-gradle-plugin` | 普通产物的 `classpath.zip` 嵌入 + Windows 版本资源插件 `moe.lolosia.keel.classpath` | 是 |
| `keel-thirdparty-gradle-plugin/` | `keel-thirdparty-gradle-plugin` | 第三方原生库插件的消费接线与许可门禁 `moe.lolosia.keel.thirdparty` | 是 |
| `keel-thirdparty/` | `keel-thirdparty` | 第三方原生库的生成侧（manifest / `.def` / licenses / 源码编译 / dist / 发布）；本身不进 `buildAll`，但 `keel-database-sqlite` 会通过 composite 按需拉起 sqlite 的编译与 staging | 否 |
| `keel-plugin-gradle-plugin/` | `keel-plugin-gradle-plugin` | `.keel` 打包插件 `moe.lolosia.keel.plugin`；单 exe 产出 `moe.lolosia.keel.bundle`（由前者自动 apply） | 是 |
| `keel-publish-gradle-plugin/` | `keel-publish-gradle-plugin` | 发布插件 `moe.lolosia.keel.publish` | 是 |
| `keel-router-ksp/` | `keel-router-ksp` | Web 路由 KSP 处理器（构建期，不是 Gradle 插件） | 是 |
| `keel-plugin-ksp/` | `keel-plugin-ksp` | Worker 入口 KSP 处理器：`@KeelPluginMain` → 生成 `keel_plugin_worker_main` | 是 |
| `keel-abi-gradle-plugin/` | `keel-abi-gradle-plugin` | ABI 注入插件 `moe.lolosia.keel.abi`（解出静态库并挂 linkerOpts） | 是 |
| `keel-logger-gradle-plugin/` | `keel-logger-gradle-plugin` | 日志改写插件 `moe.lolosia.keel.logger`（把编译器插件挂到 KMP native） | 是 |
| `keel-logger-compiler-plugin/` | `keel-logger-compiler-plugin` | 日志调用 IR 改写：`Log.x(...)` → `Log.of(来源...).x(...)` | 是 |
| `keel-abi/` | `keel-abi` | 跨运行时 ABI 的 C 实现与每目标静态库（`nativeCpp/keel_abi.h` 是唯一布局来源） | 是 |
| `keel-classpath/` | `keel-classpath` | 独立资源 Classpath 库（zip / 目录 / 前缀视图），不依赖 keel-api | 是 |
| `keel-classpath-full/` | `keel-classpath-full` | Host 注册表、`ClasspathRef`、generation 失效、跨模块代理 | 是 |
| `keel-api/` | `keel-api` | 插件契约：生命周期、上下文、消息、调度（`moe.lolosia.keel.api`） | 是 |
| `keel-database-api/` | `keel-database-api` | 数据库能力 ABI + 插件侧 Kotlin 门面（不含驱动、不含加载器） | 是 |
| `keel-database-mariadb/` | `keel-database-mariadb` | `kind: capability` 的 MariaDB 驱动包（纯 C，打成 `.keel`） | 是（`.keel`） |
| `keel-database-sqlite/` | `keel-database-sqlite` | `kind: capability` 的 SQLite 驱动包（纯 C + 静态 amalgamation，打成 `.keel`） | 是（`.keel`） |
| `keel-plugin-basic-rbac/` | `keel-plugin-basic-rbac` + `api/`（坐标 `keel-plugin-rbac-api`） | rbac 默认实现（`provides: ["authz"]`）与契约；坐标是中性的，第三方实现可原位替代 | 是 |
| `keel-plugin-basic-biz/` | `keel-plugin-basic-biz` + `api/`（坐标 `keel-plugin-basic-biz-api`） | 用户 / 账户 / 设置插件；契约与 DTO 在 impl，api 由 KSP 生成 | 是 |
| `keel-host/` | `keel-host` | 主进程：插件加载、生命周期、Ktor 引擎、上下文注入 | 是（作为库） |
| `keel-launcher/` | `keel-launcher` | 单文件发行版入口，静态链接 Host 并内嵌插件 | 是（可执行文件） |
| `keel-plugin-hello/` | `keel-plugin-hello` | 示例插件；新插件按 `keel-plugin-<id>/` 平铺在顶层 | 是 |
| `keel-idea-plugin/` | `keel-idea-plugin` | IntelliJ IDEA 插件：识别插件工程、构建 / 运行分离 | 否（不参与 `buildAll`） |
| `doc/` | — | 设计与计划；图标源文件在 `doc/logo/`（SVG + 生成脚本，不参与构建） | — |

依赖方向（禁止反向）：

```
keel-*-gradle-plugin / keel-router-ksp ──(构建期)──> keel-plugin-*  (KSP / 打包 / 发布)
keel-api  <──  keel-host  <──  keel-launcher
keel-api  <──  keel-database-api  <──  keel-plugin-*
keel-api  <──  keel-plugin-basic-biz/api（契约工件，由 impl 的 @PublicApi 生成）
keel-api  <──  keel-plugin-rbac-api（契约工件，由 keel-plugin-basic-rbac 生成）
keel-plugin-rbac-api  <──  第三方 rbac 实现（可替换 keel-plugin-basic-rbac）
keel-database-mariadb ──(仅头文件)──> keel-database-api / keel-abi
```

- `keel-api` **不得**依赖 `keel-host`、`keel-launcher`、Ktor 或任何第三方服务端库；它是对外承诺的稳定面。
- `keel-plugin-*` 只能依赖 `keel-api`、`keel-database-api` 与各 `-api` 契约库（以及自己声明的库）；
  需要宿主能力时走 API，不要反向 include `keel-host`。
- `*-api` 是消费方工件：v2 由 impl 的 `@PublicApi` 生成（镜像 / 代理 / Key / 访问器 / DTO，随仓库提交），
  impl **不依赖**自己的 api，生成的访问器同时提供 `ctx.xxx` 与缺席时返回 null 的 `ctx.xxxOrNull`。
- **可替换能力**（当前只有 rbac）：契约工件的坐标必须中性（`keel-plugin-rbac-api`），默认实现与第三方实现
  都实现同一组接口；第三方实现只依赖该工件，KSP 会顺着它实现的 `@PublicApi` 接口生成分发器。
  Host 对同一 typeId 只认最先声明的提供者，`plugin.json` 的能力（`provides: ["authz"]`）同样只认第一个认领者。
- 跨构建的依赖一律写 Maven 坐标 `moe.lolosia.keel:<artifact>:<version>`；在根 composite 下 Gradle 会自动替换成本地工程。

## 2. 构建与验证

```powershell
# 全仓库（根聚合，逐个构建所有 includeBuild）
gradlew buildAll

# 单个部分
gradlew -p keel-api build
gradlew -p keel-logger-compiler-plugin build
gradlew -p keel-logger-gradle-plugin build
gradlew -p keel-host linkDebugExecutableMingwX64
gradlew -p keel-launcher linkReleaseExecutableLinuxArm64
gradlew -p keel-plugin-hello linkReleaseSharedLibLinuxArm64

# 只组装运行目录（不启动 Host），供 IDE / 调试器使用
gradlew -p keel-plugin-hello keelPrepareRun

# V1 提交制：确认插件的 api/ 生成物已经随源码提交（过期则失败）
gradlew -p keel-plugin-animal keelApiCheck
gradlew -p keel-plugin-hello keelApiCheck
gradlew -p keel-plugin-basic-biz keelApiCheck

# IDEA 插件（独立构建，不参与 buildAll）
gradlew -p keel-idea-plugin buildPlugin
gradlew -p keel-idea-plugin runIde

# 工具链发布到自建 Maven
gradlew -p keel-gradle-common publish
gradlew -p keel-classpath-gradle-plugin publish
gradlew -p keel-thirdparty-gradle-plugin publish
gradlew -p keel-plugin-gradle-plugin publish
gradlew -p keel-publish-gradle-plugin publish
gradlew -p keel-abi-gradle-plugin publish
gradlew -p keel-logger-gradle-plugin publish
gradlew -p keel-logger-compiler-plugin publish
gradlew -p keel-router-ksp publish
gradlew -p keel-plugin-ksp publish

# 第三方原生库：生成侧单独构建（不进 buildAll）
gradlew -p keel-thirdparty compileAll       # 下载/解压源码并交叉编译（codecs.gradle.kts + sqlite.gradle.kts）
gradlew -p keel-thirdparty stageAll         # 把 out/ 拆成 dist/<lib>/<target>
gradlew -p keel-thirdparty publishAll       # 打包并按 Maven 坐标发布
# 只发布某个库（驱动构建常用）：-Pkeel.thirdparty.libs=sqlite
gradlew -p keel-thirdparty publishTpSqliteMingwX64PublicationToMavenLocal "-Pkeel.thirdparty.libs=sqlite"
# keel-database-sqlite 在仓库内会通过 composite 自动触发 sqlite 的编译与 staging，
# 单独 clone 该目录时才需要上面这条发布命令（或 -Pkeel.thirdparty.dist=<keel-thirdparty>/build/dist）。
# 编译侧 Windows / Linux 共用同一份 Kotlin 实现（无 shell / Python 依赖），
# 也可用 -Pkeel.thirdparty.out=<已在别处编译好的 out 目录> 复用产物。
```

- **改了公共 API 至少跑**：`gradlew -p keel-api build` + `gradlew -p keel-plugin-hello build`（消费方必须能编过）。
- 单独构建 `keel-host` / `keel-launcher` / `keel-plugin-*` 时，`keel-api`（以及 `keel-host`）必须已发布到 Maven；否则用根聚合，或加 `--include-build ../keel-api`。
- Kotlin/Native 首次编译会下载 konan 工具链；Windows 上链接前先确认没有旧的 `keel*.exe` 进程占用输出文件。
- **KSP 生成约定**（`keel-router-ksp` / `keel-plugin-ksp` 都适用）：
  - 两个处理器是**成对**的：`keel-router-ksp` 生成与 `@KeelPluginMain` 同包的 `KeelPluginRoutes`
    （实现 `GeneratedRoutes`），`keel-plugin-ksp` 生成的 Worker 入口把它交给 `PluginWorker`；
    `moe.lolosia.keel.plugin` 会同时挂上两者，单独配 KSP 时必须两个都加。
  - 汇总多个源文件的输出，`Dependencies` 必须写 `Dependencies(aggregating = true, *sourceFiles)`；
    只写 `aggregating = true` 会漏扫未改动文件，症状是「编译通过但接口全 404」。
  - 会创建文件的处理器不要在首轮 `deferred`：KSP 会重跑同一轮，重复 `createNewFile` 直接抛 `FileAlreadyExistsException`。
  - 处理器依赖必须赶在 `ksp<target>` 配置创建时挂上（用 `configurations.configureEach`）；
    晚于 KSP 的 `afterEvaluate` 再加就只有空 classpath，症状是「KSP 跑完但什么都没生成」。
  - `keel-plugin-ksp` 的 `keel.api.mode` 默认 `MIRROR`（impl 生成 api 镜像）；
    `CONTRACT_FIRST` 用于手写契约的 api 模块。`keel-api` 始终是 `CONTRACT_FIRST` 且没有
    `@KeelPluginMain`（core 模式），只生成 proxy / Key 并写回 `src/commonMain/kotlin`（V1 提交制）。
  - `@PublicApiProvider(vararg contracts)` 生成「多契约并集」分发器：plugin 模式写进
    `KeelPluginPublicApi.setup` 并登记实例；core 模式用 `keel.provider.dir` 生成
    `<Impl>Dispatcher` 与 `<Impl>Provider.register(directory, impl)`，由实例创建方在跨界时调用。
- **当前没有自动化测试**（`commonTest` 未接入）；验证靠编译 + `keel-host --inspect` / `--plugins` 冒烟，
  历史输出见 [doc/记忆/验证记录.md](doc/记忆/验证记录.md)。

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
| `keel-classpath` | 独立资源 Classpath 库 |
| `keel-classpath-full` | Host 注册表 / `ClasspathRef` / 跨模块代理 |
| `keel-abi` | C ABI 静态库 + 头文件工件 |
| `keel-database-api` | 数据库能力包 ABI + 插件侧 Kotlin 门面（数据插件的唯一查库依赖，不含驱动） |
| `keel-plugin-rbac-api` | rbac 契约库（`keel-plugin-basic-rbac/api/` 生成；默认实现与第三方实现都实现它） |
| `keel-plugin-basic-biz-api` | basic-biz 的 api 工件（`keel-plugin-basic-biz/api/`，全生成：镜像 / 代理 / Key / 访问器 / DTO） |
| `keel-plugin-basic-rbac` | rbac 默认实现（`plugin.json` 的 `id = "rbac"`，`provides: ["authz"]`），可被第三方 `.keel` 原位替换 |
| `keel-plugin-<id>` | 功能插件，`<id>` 与 `plugin.json` 的 `id` 一致 |
| `keel-database-<driver>` | `kind: capability` 的数据库驱动包（`keel-database-mariadb` / `-pgsql` …），丢进 `plugins/` |
| `keel-gradle-common` | `keel { }` 根容器；section 类型随各自插件分发 |
| `keel-classpath-gradle-plugin` / `keel-plugin-gradle-plugin` / `keel-publish-gradle-plugin` / `keel-abi-gradle-plugin` / `keel-logger-gradle-plugin` / `keel-thirdparty-gradle-plugin` | 构建期 Gradle 插件 |
| `keel-logger-compiler-plugin` | Kotlin 编译器插件（由 `moe.lolosia.keel.logger` 挂载，不单独 apply） |
| `keel-router-ksp` / `keel-plugin-ksp` | 构建期 KSP 处理器 |

- `keel-idea-plugin` 是 IntelliJ IDEA 插件（IntelliJ Platform Gradle Plugin 2.x，目标社区版），
  只消费运行目录，不发布 Maven 工件；根 `buildAll` 显式跳过它。

- Gradle 插件 ID 统一 `moe.lolosia.keel.<能力>`：`moe.lolosia.keel.publish`、`moe.lolosia.keel.classpath`、`moe.lolosia.keel.plugin`、`moe.lolosia.keel.bundle`、`moe.lolosia.keel.abi`、`moe.lolosia.keel.logger`、`moe.lolosia.keel.thirdparty`。
- **配置入口统一为根容器 `keel { }`**：`keel { plugin { } / classpath { } / abi { } / logger { } / publish { } / thirdparty { } }`。
  `keel-gradle-common` 只提供 `KeelExtension` 容器（`register` / `find` / `get`），**不知道任何 section 类型**；
  每个插件 apply 时注册自己的 section，并在 `moe.lolosia.keel.gradle.common` 包里提供 DSL 扩展函数。
  因此 build 脚本需要一行 `import moe.lolosia.keel.gradle.common.*`；应用了哪个插件，classpath 里才存在哪个 section。
  旧的顶层扩展名（`keelPlugin` / `keelClasspath` / `keelAbi` / `keelLogger` / `keelPublish` / `keelThirdparty`）保留为同一对象的别名。
  新增 section 不需要改 common，插件可独立升级；只有容器 API 变化时才需要同步所有工具链插件版本。
- `keel-router-ksp` / `keel-plugin-ksp` 是 **KSP 处理器**，不是 Gradle 插件，没有插件 ID。

## 4. 代码风格

沿用后端工程的约定，**新代码一律照此写**：

1. **不写 `public`**：Kotlin 默认即 public；只有 `internal` / `private` 显式标注。
2. **import 用通配**：`import moe.lolosia.keel.api.*`，不逐条展开；同包不写 import。
3. **注释只写正经 KDoc**：用途 / 参数 / 不变量 / 真实约束。
   - 不写「改动历史」「迁移自哪里」「参照方案第几节」这类叙述 —— 那是 git 的事。
   - 注释里**不要出现 `/*`**：Kotlin 块注释可嵌套，`/db/*` 会把后面的代码吞掉并报 `Unclosed comment`。
4. **一个 `.kt` 只放一个顶层类型**（class / object / interface / enum），嵌套类与内部类随宿主保留。
   - 例外 1：`keel-api/src/commonMain/.../api/web/Annotations.kt`（注解集中一处）。
   - 例外 2：KSP 处理器允许 `XxxProvider` + `private class XxxProcessor` 同文件。
   - `private` 顶层声明不受此限：单行常量、只服务本文件的辅助函数，就近放使用处。
5. **散装函数 / 常量放同包 `package.kt`**；工厂函数（如 `classpathFromZipFile`）也属于散装函数。
6. **扩展函数的归属**：
   - 扩展**外部库**类型 → 按库分域到 `util/<库名>`，域内按 `<接收者类型名>Ext.kt` 分文件；
   - 扩展**自研**类型 → 与类型同域；
   - 只服务单个文件的 `private` 扩展留在原文件。
7. 不要把平台相关的 C interop 代码放 commonMain：`@CName`、`StableRef`、`CPointer` 一律进 `nativeMain` / 具体目标源集。
8. **控制器专属 DTO 作为该 `object` 的内部类**；跨控制器共用才提成独立文件（或进 `dto/` 包）。
9. **时间字段统一 `kotlin.time.Instant`**，不要用 `LocalDateTime`；只有日志展示本地时间。
10. 日志统一走 `keel-api` 的 `Log`（`Log.info(...)` 等），不要在库里 `println`；
    `moe.lolosia.keel.logger` 会在编译期把调用改写成带 `LogSite` 的形式（类名 / 文件 / 行号 / 包 / 类 / 函数 / 类型）。
    - `ctx.logger`（`PluginLogger`）仍然可用，但新代码优先用 `Log`，才能拿到调用点。
    - Host 侧 `HostLogSink` 与插件侧 `NativePluginLogger` 都是 `LogSink` 实现。
    - 输出为 `HH:mm:ss 级别 模块 来源 : 消息`；模块列默认 15，Host 默认 `Host`，
      插件默认取 manifest 的 `logTag`（留空回落 `name`、再 `id`），
      需要自定义时传 `tag = "Loader"` 等。
    - 插件名与日志模块名可以不同：`name` 写 `A's Something Plugin`，
      `logTag` 可写 `A's Some`；`color` 按 `logTag` 登记。
      模块列按**终端显示宽度**补齐（CJK 等宽字符算 2 列、组合符 0 列），
      中文标签不会破坏后续列的对齐。
    - 来源列默认 40，超出时保留类名，包段从右往左逐级加长直到用满预算，
      仍放不下才从左丢段。
    - 终端支持 ANSI 时配色：整段时间默认色；TRACE 亮灰、DEBUG 浅蓝、INFO 绿、
      WARN 黄、ERROR 红、FATAL 红底黑字；模块名哈希取 12 色盘、来源类名 cyan、
      `:行号` faint。重定向或 `NO_COLOR` 自动关闭，`FORCE_COLOR` / `CLICOLOR=0` 可覆盖。
    - `plugin.json` 的 `color`（`#RRGGBB` / `#RGB`）会让该插件模块名改用 truecolor；
      未配置时用哈希色盘。
    - 异常诊断：同一编译器插件还给异常构造与 `throw` 插桩，写入 `ThrowTrace` 侧表
      （容量 64、60 秒 TTL），日志渲染时在每层异常头后输出
      `Created at / Throwed at / Printed at`，诊断行与栈帧之间空一行；
      跨运行时通过 `KeelErrorEntry.createdAt / throwSites` 传递。可用
      `keelLogger { exceptionTrace = false }` 关闭。
    - 需要自定义来源列（如 HTTP 的客户端地址）但仍想保留调用点时，用 `Log.site()`：
      编译器插件会把占位调用换成真实的 `LogSite`，再传给
      `Log.emitSource(..., site = Log.site())`，`Printed at` 由 sink 统一渲染。
      `Log.site()` 也可以单独取用（`val site = Log.site()`），不依赖任何日志级别方法；
      未应用插件时返回 null，`inline` 函数里取到的是 inline 体内的行号。
    - 例外：CLI 的用法说明与 `--plugins` / `--inspect` 的结果输出可以直接写 stdout。
    - 日志归档与订阅见 [doc/log-design.md](doc/log-design.md)：Host 先归档成 `LogRecord` 再渲染，
      插件用 `Log.history` / `records` / `get` / `getText` / `observe` / `observeRecords` 消费
      （PLAIN / ANSI 是 Host 渲染的文本，RAW 是结构化记录 + 自带完整文本的 `LogError`）。
      缓冲上限由 `application.yaml` 的 `log.archive.maxRecords` / `maxMemoryBytes` 控制；
      正文不进事件泵，只投可合并的唤醒，由插件按游标拉取。
    - 「控制台应用 / 桌面应用」由 `keelPlugin.executable.console` 决定（改写 PE 子系统），
      运行期 `--console` / `--no-console` / `KEEL_CONSOLE` 可覆盖；无控制台时只跳过 stdout，
      归档与订阅照常工作。

## 5. 插件 API 与跨运行时边界

`keel-api` 是**两个 Kotlin/Native 运行时之间唯一合法的接触面**：生命周期、事件泵、Worker、
消息分发、事件总线、服务注册表、调度器与协程桥接都在这里。完整设计见 [doc/abi-design.md](doc/abi-design.md)。

- **一个线程只能进入一个 Kotlin/Native 运行时**。插件 Worker 由 C 侧 `pthread_create` / `CreateThread`
  创建；插件的 `@CName` 入口只能在该 Worker 上调用，Host 线程不得直接调用。
- 插件 dylib **对 Host 只暴露** `keel_plugin_bootstrap`、`keel_plugin_worker_main`
  （Kotlin `@CName` 入口，`plugin.json.entry` 指向它），以及三个关闭函数：
  `keel_plugin_request_shutdown` / `keel_plugin_pump_running` / `keel_plugin_join`
  （`keel_plugin_shutdown` 仍是它们的组合，供旧 Host 使用）。
  Worker 入口只允许由 bootstrap 在事件泵线程上调用；Host 只 `dlsym`、不调用。
  mingw 上这些符号由 `keel-abi-gradle-plugin` 生成的 `.def` 强制导出（Kotlin 符号默认不进导出表）。
  停用插件时 Host 先 `request_shutdown`，在等待期间继续消费事件（`onDisable` 的反向请求要得到应答），
  再用 `pump_running` 轮询、最后 `join`；超过 5s 未退出则放弃 join 并告警。
  队列、句柄表、分配器都是 hidden 符号，每个二进制各带一份，不导出。
- Kotlin 对象不跨边界：只能传 **C 结构体 / 字节 / 不透明句柄 / 纯 C 函数指针**。
  对象留在拥有者运行时，另一边拿到的是「句柄 + 本地代理」。
- 句柄 = `StableRef` 在 Host C 句柄表里的槽位，引用计数归零时投递 `DISPOSE` 事件，
  **由拥有者 Worker 在自己的线程上 dispose**（见 [abi-design.md](doc/abi-design.md) §5）。
- Kotlin 回调不转函数指针：注册「回调 id」，调用时投递事件，拥有者查表执行。
- 调度器 / 协程不跨运行时：透传的是调度命令与完成事件；`Messenger.request` 是本地 `suspend`，
  continuation 永不跨 ABI。
- **模块间调用**走 `@PublicApi`：契约在声明方，api 工件由 KSP 生成 `ctx.<契约>` / `ctx.<契约>OrNull`
  访问器；调用经 Host 路由到句柄，查询用 `suspend` 请求 / 应答。v1 的 `ServiceKey` / `ctx.modules`
  与 `SERVICE_REGISTER` / `SERVICE_CALL` 通道已删除（C 侧常量按 ABI 版本保留，未使用）。
- 热路径载荷用 Protobuf / CBOR；结构体只在共享头文件里定义，通过指针传，Kotlin 侧不手写布局。
- 新增 API 时先问三个问题：
  1. 是否需要在两个运行时之间传？需要 → 只留 blittable 面，或改成「句柄 + 事件」。
  2. 是否是 suspend 能力？是 → 在 API 里用本地 `suspend` + 事件桥接，不要暴露到 ABI。
  3. 宿主升级后旧插件还能不能加载？不能 → 提升 `abiVersion` 并在 `plugin.json` 里声明兼容范围。

ABI 头文件在 [`keel-abi/src/nativeCpp/keel_abi.h`](keel-abi/src/nativeCpp/keel_abi.h)，
**改 ABI 先改文档与头文件，再改实现**。

- **不透明结构体给 cinterop 一个占位完整定义**：`keel_abi.h` 里 `KeelQueueTag` 在 `KEEL_CINTEROP`
  下是一个只有 `void *reserved` 的完整结构体（宏由 `keelabi.def` 的 `compilerOpts` 注入），
  C 实现侧才看到真正的前向声明。这样 cinterop 把类型生成到 `moe.lolosia.keel.abi.c`，而不是
  IDE 中间源集索引不到的 `cnames.structs`。Kotlin 侧只能持有该类型的指针，**不得解引用、分配、
  按值传递或 `sizeof`**；真要操作内部布局时，移除这个分支并把真实布局放进头文件。

## 6. 插件工程约定

- 每个插件是顶层 `keel-plugin-<id>/` 目录下的**独立 Gradle 构建**。
  - **当前形态（单项目）**：`build.gradle.kts` 里 `plugins { id("moe.lolosia.keel.plugin") }`，
    用 `keel { plugin { target("mingwX64") } }` 选择打包目标；`plugin.json` 放在
    `src/commonMain/resources/plugin.json`，由打包插件复制到 `.keel` 根部。
  - **契约与 api 工件**：契约与实现同在 `src/commonMain`（`@PublicApi` 接口 + `@Serializable` DTO；
    `@Singleton` 实现由 KSP 登记）。需要给外部消费时加 `api/` 子工程，`keel.api.dir` 指向它，
    镜像 / 代理 / Key / 访问器由 KSP 生成并随仓库提交（V1 提交制，`keelApiCheck` 校验）。
  - Worker 入口不手写：`moe.lolosia.keel.plugin` 自动 apply KSP，并把 `keel-plugin-ksp`
    挂到每个 native 目标的 `ksp<target>` 配置上；`@KeelPluginMain` 类由处理器生成
    `keel_plugin_worker_main`，开发者只实现 `Plugin` 生命周期钩子。
- 插件产出**动态库**（`binaries.sharedLib`），`baseName = "keel-plugin-<id>"`；launcher 发行版另行静态并入，不改变插件工程形态。
  ⚠️ Kotlin/Native 会把 `baseName` 里的短横线换成下划线（实际产物是 `keel_plugin_hello.dll`），加载器按目录里的实际文件名扫描，不要写死。
- 插件工程至少包含：

```
keel-plugin-<id>/
  settings.gradle.kts                          # 独立构建；按需 includeBuild 依赖的 Gradle 插件
  build.gradle.kts                             # plugins { id("moe.lolosia.keel.plugin") } + keel { plugin { target(...) } }
  src/commonMain/kotlin/.../XxxPlugin.kt       # 实现（@KeelPluginMain）
  src/commonMain/resources/plugin.json         # 部署清单（id / name / logTag / version / entry / lib / resources / requires / color）
```

- **目标形态**：代理、分发器、CBOR 编解码、`plugin.json`、Maven 发布配置由 Gradle 插件 + KSP 生成，
  机制见 [doc/codegen-design.md](doc/codegen-design.md)。
  **当前落地**：`plugin.json` 由开发者手写在 `src/commonMain/resources/`；KSP 已生成 Worker 入口、
  `@PublicApi` 注册表与 api 工件，依赖解析和发布配置仍手写。
- 消费方使用生成的 `ctx.<契约>` / `ctx.<契约>OrNull`；可替换能力按 `OrNull` 缺席降级。
  目前还没有 `keel { plugin { requires(...) } }` 的自动接线。
- 新增插件 = 在顶层新建 `keel-plugin-<id>/` + 在根 `settings.gradle.kts` 加一行 `includeBuild`；不要塞进某个聚合目录。

- **运行任务分层**：`keelPrepareHost` 只解出 Host，`keelPrepareRun` 只打包 / 拷贝 `.keel` 与配置，
  `keelRun` = 两者 + exec。IDE 侧把 `keelPrepareRun` 挂成 Gradle before-run task（输出走 IDEA 的
  Build 面板），构建完成后再直接启动 `build/keel-run/host/keel-host[.exe]`；
  主 Run 控制台只保留 Host 日志。
- `keelRun` / IDEA 运行配置默认注入 `FORCE_COLOR=1`（显式 `NO_COLOR` / `CLICOLOR=0` 时尊重用户设置），
  否则 Gradle exec 把 stdout 接到管道后 Host 的 ANSI 探测会关闭颜色。

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
- **权限**：判定契约在 core（`@Permission` / `ctx.hasPermission` / `Authorizer` / `UserSnapshot.rules`），
  默认实现是 `keel-plugin-basic-rbac`（`plugin.json` 的 `id = "rbac"`，`provides: ["authz"]`），
  第三方可原位替换；Host 在分发前校验，无 Authorizer 时带权限的路由 **fail closed（503）**。
  业务插件**不需要**依赖 rbac 契约：`ctx.user.rules` / `ctx.hasPermission` 已经够用；
  只有需要精细查询或管理角色 / 规则时才依赖 `keel-plugin-rbac-api`（用 `ctx.rbacReaderOrNull` 降级）。
  节点目录统一用 `ctx.host.permissionCatalog()`，不再单独定义契约。
  见 [doc/authz-design.md](doc/authz-design.md)。
- 资源（模板、静态文件、配置默认值）放 `src/commonMain/resources`，由 `moe.lolosia.keel.classpath` 打包后在运行期读取。
- 插件不写 Host 的工作目录：临时文件与日志写 `ctx.cacheDir`（每次加载独占、下次启动回收），
  需要跨重启保留的数据写 `ctx.dataDir`（`<dataRoot>/data/<id>/`，Host 不回收）；
  `LoadLibrary` / WebView 这类需要**真实文件**的资源用 `ctx.materialize("native/x.dll")` 物化到缓存目录。
  小文本仍走 `ctx.classpath`。两者都是反向请求，只能在 Worker 线程调用。

### 6.1 单 exe 产出（`keelPlugin.executable`）

- 用户工程**始终只是插件工程**：不声明 application / executable，不加 `keel-host` 依赖。
  单 exe 由 `moe.lolosia.keel.bundle`（`moe.lolosia.keel.plugin` 自动 apply）把本工程 `.keel`
  与 `include(...)` 的 `.keel` 摊平成 bundle，再注入**预编译 launcher 副本**。
- 默认关闭；开启后同一工程同时产出 `.keel` 与单文件，产物在 `build/keel/<target>/<name>[.exe]`。

```kotlin
import moe.lolosia.keel.gradle.common.*

keel {
    plugin {
        executable {
            enabled = true                                   // 默认 false
            name = "myapp"                                   // 默认 project.name；Windows 自动补 .exe
            appId = "myapp"                                  // 用户目录名，默认 project.name；与 exe 文件名解耦
            console = true                                   // false = 桌面应用（Windows GUI 子系统）
            dataMode = KeelDataMode.PROGRAM                  // PROGRAM（默认）/ USERDATA（用户目录）
            include(file("libs/keel-database-mariadb-0.1.0.keel"))
            include(project(":keel-plugin-hello"))           // 同构建工程，自动依赖对应的打包任务
            launcher = null                                  // 可选：本地 launcher，便于调试
            buildType = "RELEASE"                            // DEBUG = keelPackage + launcher-dev；RELEASE = keelPackageRelease + launcher
            compression = "zstd"                             // bundle 容器：zstd（默认）/ deflate
            compressionLevel = 19                            // zstd 级别
            windows { productName = "My App"; icon = "app.ico" }   // 与 keel { classpath { windows {} } } 同形
        }
    }
}
```

- **debug / release 拆成两个打包任务**：`keelPackage`（debug，供 `keelRun` / 调试）与
  `keelPackageRelease`（release sharedLib + strip，供单 exe 与发布）。`executable.buildType = "RELEASE"`
  时本工程与 `include(project)` 都走 release 任务，launcher 也解析 `keel-launcher-<target>`；
  CI 可用 `-Pkeel.buildType=RELEASE` 覆盖。IDEA 的 `keelPrepareRun` 仍固定 debug，断点不受影响。
- 注入方式：Windows 写 PE 资源（`.rc` → `windres` → JNA `UpdateResource`，资源 `IDR_KEEL_BUNDLE` /
  `KEELBUNDLE`），Linux 在文件尾追加 32 字节 trailer（`offset` / `size` / `format` / `crc32` / `KEELBNDL`）。
  两处格式与 `KeelExecutableTask` 里的常量必须一致。`UpdateResource` 会丢掉尾部 COFF 符号表却保留旧指针，
  所以 release 在注入前先 `strip`，注入后再清零 `PointerToSymbolTable` / `NumberOfSymbols`。
- bundle 容器：默认 `zstd`，单帧 + 索引（`KELB`：magic / version / format / indexLength / rawSize / frameSize），
  跨文件共享压缩窗口；`deflate` 退回普通 zip。两种都由 Host 侧 `BundleArchiveClasspath` 打开，
  **`.keel` 自身的 zip 压缩方式不变**。
- 运行期语义：bundle 只在内存里打开，`.keel` 与 `resources/` **不落盘**；
  只有插件 / 能力包的动态库物化到 `$cacheRoot/<id>/<version>/`。
- 同 id 以磁盘 `plugins/` 优先：磁盘 manifest 可解析即「认领」该 id，加载失败也不回退内嵌版本。
  运行时 `install` 不能覆盖已加载的内嵌插件（K/N 无法卸载），会保留文件并要求重启。
- **目录**：`dataRoot`（稳定，可配置）放 `application.yaml`、`config/plugins/`、插件持久化数据
  `data/<id>/` 与日志 `logs/`；`cacheRoot`（可整体删除）放物化动态库与每次加载的资源目录。
  默认都落在程序目录：`--plugins` 模式取启动工作目录（`keelRun` 的 `build/keel-run`），
  单文件模式取 exe 所在目录；`KEEL_DATA_HOME` / `KEEL_CACHE_HOME` 可分别重定向，
  `KEEL_HOME` 仍作为 `KEEL_DATA_HOME` 的别名。程序目录不可写时回退到
  `<用户数据目录>/<app-id>`（Windows `%LOCALAPPDATA%\<app-id>`，Linux `$XDG_DATA_HOME/<app-id>`）
  与 `<用户缓存目录>/<app-id>`。
- 桌面应用在构建期把 `keelPlugin.executable.dataMode` 设为 `KeelDataMode.USERDATA`，默认目录直接落到
  `<用户数据目录>/<app-id>`，不依赖用户手配环境变量；该默认值写在 PE 资源
  `IDR_KEEL_LAYOUT` / Linux footer 的 format 高位，运行期环境变量仍优先。
  CI 可用 `-Pkeel.dataMode=USERDATA` 覆盖。
- `<app-id>` 来自 `keelPlugin.executable.appId`（默认工程名），构建期写进 PE 资源
  `IDR_KEEL_APPID` / Linux footer 前的 appid trailer；**不要**用产物文件名推导 ——
  exe 改名、带版本号的产物名都不应改变用户数据目录。没有该标记的旧产物才回退到 exe 文件名。
- `plugins/` 与 `.cache/` 在 `dataRoot` 相邻位置，不再共用同一个根。
- HTTP 端口：`--http` > `KEEL_HTTP_PORT` > `application.yaml` 的 `server.port` > 7191（仅 bundle 模式默认开）。
- `.keel` 与 bundle 都按 `lib/<target>/` 裁剪，只带当前目标的动态库。

## 7. Maven 发布与凭据

- 发布地址与账号**只放本机 `local.properties`**（已被 `.gitignore` 忽略），键名：

```properties
keel.repo=http://<maven-host>/repository/keel
keel.username=<user>
keel.password=<password>
```

- 自建 Maven 通常是内网 HTTP 或自签 HTTPS，仓库配置需 `isAllowInsecureProtocol = true`（各 `keel-*-gradle-plugin` / `keel-router-ksp` / `keel-plugin-ksp` 的 `build.gradle.kts` 与各构建的 `settings.gradle.kts` 已处理）。
- **凭据、内网地址、口令一律不入库**，包括本文档与 `doc/`；用占位符。
- 构建期工具各自在自己的 `build.gradle.kts` 里配置发布仓库，没有统一入口；功能插件用 `moe.lolosia.keel.publish` 插件，不要在业务构建里手写 `publishing {}`。
- 发布顺序：`keel-gradle-common` → `keel-*-gradle-plugin` / `keel-router-ksp` / `keel-plugin-ksp` → `keel-abi` → `keel-classpath` → `keel-api` → `keel-classpath-full` → `keel-host` → `keel-plugin-*` → `keel-launcher`；`keel-thirdparty`（sqlite 等原生库）必须先于 `keel-database-*` 驱动发布。改了 `keel-api` 必须确认消费方重新编译。

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
| [doc/plugin-contract-discussion.md](doc/plugin-contract-discussion.md) | **插件契约形态讨论记录**（未定稿）：`-impl` 命名取舍、Gradle 多工件可行性、`@KeelExposed` 分期与待决问题 |
| [doc/public-api-design.md](doc/public-api-design.md) | **`@PublicApi` 讨论稿**：impl + api 两模块下从注解生成 api 工件、跨插件继承的声明方基类影子（`super` / 裸字段 / override 集合）、`create` + `initialization` 远程构造、任何人可 `destroy` 与 Host 句柄表 |
| [doc/config-design.md](doc/config-design.md) | **配置读写**：YAML + 注释保留、分层覆盖、插件配置隔离、原子落盘与变更通知 |
| [doc/web-design.md](doc/web-design.md) | **Web 路由**：Host 单端口、插件路由注册、Context 快照、流式响应、中间件与鉴权 |
| [doc/authz-design.md](doc/authz-design.md) | **权限边界**：core 只留 `@Permission` / `Authorizer` SPI，RBAC 默认实现归 `keel-plugin-basic-rbac`（可替换），fail closed |
| [doc/basic-stack-design.md](doc/basic-stack-design.md) | **基础栈**：`keel-database-api` 是插件侧查库 klib（ABI + 门面），`keel-database-mariadb` 是 `kind: capability` 驱动包，Host 加载器在 `keel-host`；`keel-plugin-basic-rbac` / `keel-plugin-basic-biz` 的边界、RBAC 与 biz 的交叉裁决、Host 认证/授权强制点与分阶段落地 |
| [doc/capability-design.md](doc/capability-design.md) | **能力插件**：接口在 core、实现按需安装、稳定 C ABI、可卸载；首个示例 Lua 中间件引擎 |
| [doc/classpath-design.md](doc/classpath-design.md) | **Classpath 与 `.keel`**：资源命名空间、zip / 目录读取、插件包、注册表与卸载语义 |
| [doc/log-design.md](doc/log-design.md) | **日志归档与订阅**：环形缓冲与容量核算、文本 / 结构化两种视图、插件 Flow 订阅与背压、控制台 / 桌面应用切换 |
| [doc/console-design.md](doc/console-design.md) | **控制台窗口插件**：子进程 helper + 双向管道帧协议、TTY 语义保留、Messenger 补全、日志 Flow 回放与背压 |
| [doc/debug-design.md](doc/debug-design.md) | **调试支持（讨论稿）**：原生 LLDB 路线与自建 XDebugger 调试器的取舍、IR 插桩 + C agent + 协议的必要部件、语义边界与最小切片 |
| [doc/ui-design.md](doc/ui-design.md) | **桌面 UI 系统（keel-plugin-deck，讨论稿）**：控件级跨运行时不可行，对象级透传可收敛为「1 个句柄 + 快照 DTO」；RML 以 URL 交给 deck 解析；窗口由 helper 进程持有、帧循环与 Host 事件泵解耦 |
| [doc/任务记忆.md](doc/任务记忆.md) | **长任务交接（冷启动先读这里）**：现状速览、设计决策、下一步、坑索引。只读本文，不要全量读 |
| [doc/记忆/实现现状.md](doc/记忆/实现现状.md) | **逐模块实现现状**（原任务记忆 §3；编号不变）+ 关键编译命令 |
| [doc/记忆/验证记录.md](doc/记忆/验证记录.md) | **历史冒烟快照**（原任务记忆 §4）；归档材料，平时不用读 |
| [doc/记忆/踩坑.md](doc/记忆/踩坑.md) | **踩坑记录**（原任务记忆 §7），按构建 / KSP / ABI / 日志 / HTTP / 控制台 / 运行期分组 |
| [doc/roadmap.md](doc/roadmap.md) | DEMO / P0 / P1 / P2 的目标、交付物、验收标准与待决问题 |
| [doc/thirdparty-licensing.md](doc/thirdparty-licensing.md) | **第三方原生库**：manifest、封装工件布局、`keel-native` 消费方式、许可允许列表与声明生成 |
| [doc/logo/](doc/logo/) | **图标源文件**：`keel-icon-hollow.svg`（≥40px）、`keel-icon-solid.svg`（<40px）、`generate-icon.py`。构建用的 `keel-icon.ico` 由脚本生成到 `keel-classpath-gradle-plugin/src/main/resources/moe/lolosia/keel/gradle/classpath/`，作为没配 `windows { icon = … }` 时的默认应用图标；不要手工编辑 `.ico`，改 SVG 后重跑脚本 |

## 10. 当前待确认

- 泰山派上自建 Maven 的**实际地址、端口、仓库路径与鉴权方式**（找到后写进本机 `local.properties`，并把 `doc/roadmap.md` 的占位符替换掉）。
- capability provider（以 Lua 为示例）的可行性、默认分发形态（外部纯 C 库 vs 静态内嵌）与中间件沙箱边界，见 [doc/capability-design.md](doc/capability-design.md)。
- 目标 Kotlin/Native 版本已固定为 `gradle/libs.versions.toml` 的 `2.4.20`，三个目标的编译已跑通；
  仍需在板子上做一次 `linuxArm64` 运行时验证（`dlopen` / `dlsym` / 事件泵）。
- 单 exe 内嵌（Windows PE 资源 / Linux trailer）已在 `mingwX64` 上端到端跑通；
  `linuxArm64` / `linuxX64` 的 launcher 工件与 trailer 读取仍需在对应平台验证。
- release launcher 工件 `keel-launcher-<target>` 已发布；release Host 的 HTTP 插件响应路径
  仍需在真实路由上做一次冒烟（历史 `ACCESS_VIOLATION` 见 `doc/任务记忆.md`）。

## 11. 文件地图

```
keel-classpath/src/commonMain/kotlin/moe/lolosia/keel/classpath/
  Classpath.kt                        只读资源接口（`of(pluginId)` 默认 fail-closed）
  ClasspathResource.kt                流式资源句柄
  ByteArrayClasspathResource.kt       默认流式句柄
  PrefixedClasspath.kt                `.keel` 的 `resources/` 前缀视图
  EmptyClasspath.kt / ClasspathNotFoundException.kt / ClasspathExt.kt / package.kt
keel-classpath/src/nativeMain/…/classpath/
  DiskClasspath.kt                    目录回退
  ZipClasspath.kt                     内存映像 zip（嵌入模式）
  ZipFileClasspath.kt                 文件型 zip：只读 EOCD / 中央目录 / 目标条目
  FileZipClasspath.kt                 文件型 zip 门面
  package.kt                          工厂函数（`classpathFromZipFile` / `classpathFromDirectory`）
  ZipInflate.kt                       共享 raw deflate
  EmbeddedClasspath.kt / EmbeddedClasspathImage.kt   读取内嵌 `classpath.zip`
  EmbeddedPluginBundle.kt                           单 exe 内嵌 bundle 的原始字节读取（expect + mingw PE 资源 / linux trailer）
  RandomAccessFile.kt                 平台随机读（expect）
keel-classpath/src/{linux,mingwX64}Main/…/classpath/  RandomAccessFile / EmbeddedPluginBundle actual
keel-classpath-full/src/commonMain/…/classpath/full/
  ClasspathRef / ClasspathRegistry / CompositeClasspath / GuardedClasspath / RegistryClasspath
  ClasspathTransport / RemoteClasspath / HostClasspathTransport / PluginClasspathEntry
keel-classpath-full/src/nativeMain/…/classpath/full/DefaultClasspathRegistry.kt
keel-database-api/src/nativeCpp/keel_database.h     数据库能力 C ABI（唯一布局来源）
keel-database-api/src/nativeMain/…/database/        KeelDatabase / Row / QueryResult / DbValue / currentDatabase()
keel-database-mariadb/src/nativeCpp/keel_database_mariadb.c   纯 C 能力包（当前后端仅 select 1）
keel-database-sqlite/src/nativeCpp/keel_database_sqlite.c     纯 C 能力包（sqlite3 amalgamation 静态并入）
keel-plugin-basic-rbac/src/commonMain/…/rbac/api/  RbacReader / RbacAdmin + DTO + ROLE_ID_*（api/ 由 KSP 生成，坐标 keel-plugin-rbac-api）
keel-plugin-basic-biz/src/commonMain/…/basic/biz/api/  UserDirectory / UserAdmin / AccountAdmin / SettingApi + DTO（@PublicApi）
keel-plugin-basic-biz/api/src/commonMain/…/basic/biz/api/  由 KSP 生成（V1 提交制，勿手改）
keel-api/src/commonMain/kotlin/moe/lolosia/keel/api/
  Plugin / PluginContext / PluginMetadata / PluginRegistry / KeelServer / PluginLogger / Scheduler / TaskHandle
  Log.kt                              统一日志门面（of / 六个级别 / install(LogSink)）
  log/                                LogSite / LogSiteKind / LogLevel / LogSink / LogSiteCodec / StdoutLogSink
                                      Ansi / LogLineFormatter（Spring Boot 配色与行格式）
                                      LogRecord / LogError / LogLine / LogPage / LogFormat / LogTextFormat
                                      LogRecordFormat（PLAIN / ANSI / RAW 的共享渲染）/ LogHistory / NoneLogHistory
  annotation/  KeelPluginMain / PublicApi / Singleton / PublicApiHandler / LocalMember / PublicApiProvider
  auth/        Authenticator / Authorizer / AuthnRequest / AuthnReply / AuthzRequest / AuthzReply / AuthzSnapshot
  callback/    CallbackDescriptor / CallbackInvoker / CallbackRegistry
  command/     Command / CommandSender / ConsoleCommandRequest / ConsoleCommandResult
  config/      Config / ConfigScope / ConfigReadRequest / ConfigWriteRequest / package.kt
  event/       Event / EventBus / EventHandler / EventPriority / EventType / Subscription
  error/       KeelErrorPayload / KeelRemoteException / package.kt
  host/        HostLink / HostSubjects / InvalidateRequest / PermissionCatalogReply
               CacheDirReply / DataDirReply / MaterializeRequest / MaterializeReply
               ClasspathNames / ClasspathOp / ClasspathRequest / ClasspathReply
  messaging/   Message / Messenger
  service/     ServiceWire（CBOR）；跨模块调用见 share/ 的 @PublicApi
  share/       PublicApiDirectory / PublicApiDispatcher / PublicApiType / PublicApiKey / PublicApiBridge
               SharedRef / SharedProxy / SharedExport（跨界时自动登记）/ SharedCall 等共享对象线格式
  storage/     PluginStorage / StorageOp / StorageRequest / StorageReply
  web/         Annotations（注解集中例外）/ Context / HttpRequest / HttpResponse / ResponseBody
               UserSnapshot / RouteDescriptor / Routes / GeneratedRoutes / PermissionNode / ApiResult
               ContextProxy / ContextKey / WebContextProxy / WebRequestComponentProxy / ComponentProxy
               （后三组由 keel-plugin-ksp 的 core 模式生成，勿手改）
keel-api/src/nativeMain/…/api/worker/
  PluginWorker.kt                     `@CName` Worker 入口分发（生命周期 / PING / TIMER / HTTP / CONSOLE / SHARED_CALL / AUTHN / AUTHZ）
  NativePluginLogger.kt               插件日志 → Host 收件箱
  PluginContextImpl / PluginServer / PluginRoutes / HostLinkImpl
  PluginClasspath / HostClasspathClient       自身 `.keel` + Host 的远程资源视图
  PluginCapabilities / PluginConfig / PluginConsole / PluginStorageImpl
  WorkerScheduler / WorkerScope / WorkerDispatcher
  RequestContextImplDispatcher / RequestContextImplProvider   由 KSP core 模式生成（勿手改）
keel-api/src/nativeInterop/cinterop/keelabi.def        C ABI 绑定（含 KEEL_CINTEROP）
keel-host/src/commonMain/…/host/Main.kt               CLI 入口与 expect 声明
keel-host/src/commonMain/…/host/HostDirs.kt           dataRoot / cacheRoot 模型
keel-host/src/nativeMain/…/host/HostDirs.native.kt    目录解析（环境变量 / 程序目录 / 用户目录）
keel-host/src/commonMain/…/host/HostLogSink.kt        Host 日志出口（本地时间戳 + 调用点模块列）
keel-host/src/commonMain/…/host/                      LogArchive（expect）/ HostLogSource / Logging / LogWake / Console
keel-host/src/nativeMain/…/host/                      LogArchive.native / LogHub / LogLocalSubscriptions.native
keel-host/src/commonMain/…/host/Banner.kt             启动横幅
keel-host/src/commonMain/…/host/AnsiSupport.kt        ANSI 检测（expect）+ mingw/linux actual
keel-host/src/commonMain/…/host/ConsoleOut.kt         日志行前后的控制台钩子（expect），native 侧接 ConsolePrompt
keel-host/src/nativeMain/…/host/                      Inspect / Plugins（CLI 实现）
keel-host/src/nativeMain/…/host/classpath/HostClasspath.kt
keel-host/src/nativeMain/…/host/console/              HostConsole / ConsolePrompt / ConsoleCommand / HelpCommand / StopCommand / PluginsCommand
keel-host/src/nativeMain/…/host/plugin/
  PluginLoader / LibraryCache / LoadedPlugin / PluginRuntime / HostPlugins / PluginResources
  PluginPackage / FilePluginPackage / BundlePluginPackage / PluginBundle / PluginScan
  KeelPackageManifest / PluginPackageReader / PluginLoadResult / PluginLoadFailure
  LogEvent / HostPump / PluginNames（日志解码与 Host 收件箱消费）
  NativeLibrary(expect) / PluginFs / HostAbi / HostTarget / HostPluginIds / package.native.kt
keel-host/src/nativeMain/…/host/capability/           能力包发现 / open / close / database 选包
keel-host/src/nativeMain/…/host/auth/                 Host 认证授权中间件
keel-host/src/nativeMain/…/host/config/               YAML 配置读写
keel-host/src/nativeMain/…/host/route/                路由表与权限目录
keel-host/src/nativeMain/…/host/shared/               HostShared（@PublicApi 句柄表 / 单例 / 类型路由）
keel-host/src/nativeMain/…/host/storage/              插件命名空间存储
keel-host/src/{linux,mingwX64}Main/…/host/plugin/     NativeLibrary / PluginFs / package actual
keel-launcher/src/commonMain/…/launcher/Main.kt       单文件发行版入口
keel-abi/src/nativeCpp/
  keel_abi.h                          唯一 ABI 布局来源（不透明句柄 + KEEL_CINTEROP 占位分支）
  keel_zstd.h                         bundle 解压的最小声明面（实现见 zstddeclib.c）
  zstddeclib.c                        zstd 单文件解码器（v1.5.6，随静态库编入；插件不引用则不链接）
  keel_platform.h                     线程 / 同步原语（不导出、不进 Kotlin）
  keel_queue.c / keel_event.c / keel_plugin.c / keel_console.c
keel-router-ksp/src/main/…/gradle/router/KeelRouterProcessorProvider.kt   路由处理器（@Controller / @GET → KeelPluginRoutes）
keel-plugin-ksp/src/main/…/ksp/plugin/KeelPluginWorkerProcessor.kt        @KeelPluginMain → Worker 入口；@PublicApi / @Singleton → api 工件 + 分发器
keel-gradle-common/src/main/kotlin/moe/lolosia/keel/gradle/common/
  KeelExtension.kt                     keel { } 根容器（register / find / get），不依赖任何 section 类型
keel-<plugin>-gradle-plugin/src/main/kotlin/moe/lolosia/keel/gradle/
  <section>/*Extension.kt              该插件自己的 section 数据类型
  common/Keel<Section>Dsl.kt           该插件注册到根容器的 DSL 扩展函数（import moe.lolosia.keel.gradle.common.*）
keel-thirdparty-gradle-plugin/src/main/kotlin/moe/lolosia/keel/gradle/thirdparty/
  KeelThirdpartyPlugin.kt              消费接线（zip → 解包 → cinterop → linkerOpts）
  KeelThirdpartyManifest.kt            thirdparty/manifest.json 模型与解析
  KeelThirdparty{CheckManifest,CheckLicenses,Notices,Verify,Publish,Def}Task.kt   门禁与发布
keel-thirdparty/
  manifest.json                       库清单（moe.lolosia.keel.thirdparty）
  gradle/codecs.gradle.kts            源码下载/解压 + clang/cmake 交叉编译（6 个 codec，跨 OS）
  <lib>/<lib>.def + licenses/<lib>/    随库分发的 def 与许可
  build/dist/<lib>/<target>/           生成的 staging（gitignore）
  build/thirdparty/repo/               打包后的 Maven 布局（gitignore）
keel-classpath-gradle-plugin / keel-plugin-gradle-plugin / keel-publish-gradle-plugin / keel-abi-gradle-plugin / keel-logger-gradle-plugin
  src/main/…/gradle/                   各自一个 Gradle 插件（classpath / plugin / publish / abi / logger）
keel-plugin-gradle-plugin/src/main/…/gradle/plugin/
  KeelBundlePlugin / KeelBundleTask / KeelExecutableTask / KeelExecutableExtension / KeelWindowsResourceInjector   单 exe 产出
keel-classpath-gradle-plugin/src/main/…/gradle/classpath/
  KeelStripTask.kt                    release 二进制打包前 strip（导出表不变）
keel-host/src/nativeMain/…/host/plugin/
  BundleArchiveClasspath.kt           `KELB`（zstd 单帧 + 索引）内嵌 bundle 读取
keel-logger-compiler-plugin/src/main/…/logger/
  KeelLoggerRegistrar / KeelLoggerCliProcessor / KeelLoggerIrExtension / LogSiteTransformer / LogSiteResolver / SourcePathIndex
  META-INF/services/                    CompilerPluginRegistrar + CommandLineProcessor
keel-plugin-hello/                     示例插件（单项目形态）
keel-idea-plugin/src/main/kotlin/moe/lolosia/keel/idea/
  KeelPluginDiscovery.kt               静态扫描 `plugin.json` + 构建脚本
  KeelPluginProject.kt                 插件工程模型
  run/
    KeelRunConfigurationType / Factory / Options
    KeelRunConfiguration / KeelRunSettingsEditor / KeelRunProfileState
    KeelRunConfigurationProducer       当前文件 → Keel 运行配置
    KeelGradle.kt                      Wrapper / Host 定位
doc/                                   设计与计划
  任务记忆.md                          长任务交接首页（冷启动先读；只读这一份）
  记忆/实现现状.md                     逐模块实现现状（原任务记忆 §3）
  记忆/验证记录.md                     历史冒烟快照（原任务记忆 §4）
  记忆/踩坑.md                         按主题分组的踩坑（原任务记忆 §7）
```
