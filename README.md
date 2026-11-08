<div align="center">

<img src="doc/logo/keel-icon-solid.svg" width="112" alt="Keel" />

# Keel

**Kotlin/Native 模块化 Web 框架**

提供组件化插接能力：组件可独立构建、动态装卸与在线热更新，宿主统一管理生命周期、路由与跨运行时边界。

中文 · [English](README.en.md)

[快速开始](#快速开始) · [架构](#架构) · [编写插件](#编写插件) · [构建任务](#构建任务) · [延伸阅读](#延伸阅读)

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.20-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Kotlin/Native](https://img.shields.io/badge/Kotlin%2FNative-3%20targets-7F52FF)](https://kotlinlang.org/docs/native-overview.html)
[![Ktor](https://img.shields.io/badge/Ktor-3.4.2-087CFA?logo=ktor&logoColor=white)](https://ktor.io)
[![KSP](https://img.shields.io/badge/KSP-2.3.12-4285F4)](https://github.com/google/ksp)
[![Gradle](https://img.shields.io/badge/Gradle-9.5.1-02303A?logo=gradle&logoColor=white)](https://gradle.org)
[![License](https://img.shields.io/badge/License-MIT-3DA639)](LICENSE)

</div>

- **目标平台**：`mingwX64`、`linuxArm64`、`linuxX64`，不提供 JVM 目标
- **交付形态**：宿主可执行文件与若干 `.keel` 插件包；单文件发行版将宿主、插件与资源装配为一个可执行文件
- **运行时模型**：宿主与每个插件各有一个 Kotlin/Native 运行时，通过 C ABI 的事件队列与不透明句柄通信
- **构建方式**：每个顶层目录都是独立的 Gradle 构建，由根构建以 composite build 聚合

> 代码风格与硬性约定见 [AGENTS.md](AGENTS.md) · 分阶段目标见 [doc/roadmap.md](doc/roadmap.md) · 第三方组件许可见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)

## 设计目标

Keel 的核心能力是组件化插接：组件以独立部署单元交付，可以在运行时添加、删除与热更新。Kotlin/Native 没有 JVM 的类加载器，动态库无法安全卸载，Kotlin 对象与协程不能跨动态库传递；Keel 以这些事实为前提设计插件体系：

1. **容器与业务分离**：宿主负责端口、路由、生命周期、上下文、调度与日志，插件只实现业务能力。
2. **插件独立交付**：每个插件产出 `.keel` 包，包含清单、按目标划分的动态库与资源，可单独安装、启停与升级。
3. **边界显式化**：跨运行时只允许定宽整数、指针、C 结构体与纯 C 函数指针，所有异步能力退化为事件投递与回调通知。
4. **失败可定位**：单个插件加载、启动或请求失败不扩散到宿主与其它插件，错误链跨运行时保留创建点与抛出点。
5. **代码生成前移**：路由表与 Worker 入口在构建期生成，运行期不依赖反射。

## 特性

**宿主与生命周期**

- 扫描插件目录，校验清单，解出动态库，注册资源，加载并启动插件
- 单个插件失败隔离为一条加载记录，不中断其它插件
- 控制台支持查看、启用、停用、安装与卸载插件
- 优雅停机先停止 HTTP，再逐个投递 `SHUTDOWN`，最后清空收件箱
- 看门狗进程与崩溃处理器在异常退出时保存现场与报告

**跨运行时通信**

- 每个插件拥有独立事件泵线程与有界收件箱
- 事件携带 `kind`、`source`、`target`、`correlation`、`subject`、`status` 与载荷
- 宿主以 `COMMAND` 发起请求，插件以相同 `correlation` 回 `COMPLETE`
- 载荷由发送方分配并附带归还函数，接收方复制后归还
- 句柄是引用计数表中的槽位，引用归零时向拥有者投递 `DISPOSE`

**Web 服务**

- 宿主独占 Ktor CIO 引擎与端口，插件不创建服务器
- 插件在启用时注册路由描述表，宿主以 method 与 path 匹配
- 支持精确段与 `{name}` 路径参数
- 请求以 `HttpRequest` 快照跨边界传递，响应以 `HttpRouteResponse` 返回
- HTTP 入站与出站日志复用统一日志格式，包含客户端地址、状态码、耗时与截断后的请求体与响应体

**资源层**

- `.keel` 资源不解包，按 zip 中央目录随机读取
- 资源层提供自身优先、缺失回落宿主的组合视图
- 跨插件访问只暴露带代次的不透明引用
- 卸载后旧引用读取立即失败

**日志与诊断**

- `Log` 统一日志门面，提供六个级别与结构化调用点
- 编译器插件在 IR 阶段注入类名、文件、行号、包与函数
- 插件日志编码为 `LOG` 事件回投宿主，由宿主统一渲染
- 异常创建点与抛出点记录在侧表，输出 `Created at`、`Throwed at` 与 `Printed at`
- `--inspect` 读取插件包清单与资源条目，并验证卸载后读取失效

**构建工具链**

- `moe.lolosia.keel.classpath`：合并资源并嵌入可执行文件
- `moe.lolosia.keel.plugin`：打包 `.keel`，自动挂载 ABI、日志与 KSP
- `moe.lolosia.keel.abi`：解出 C ABI 静态库并注入链接参数
- `moe.lolosia.keel.logger`：把日志编译器插件挂到 Kotlin/Native 编译
- `moe.lolosia.keel.publish`：注册自建 Maven 发布仓库
- `keel-plugin-ksp`：从 `@KeelPluginMain` 生成 Worker 入口

## 架构

### 总览

```text
                          ┌──────────────────────────────────────────────┐
                          │              keel-launcher                   │
                          │   单文件发行：宿主、插件清单、原生库与资源   │
                          └──────────────────────┬───────────────────────┘
                                                 │ 静态链接，必要时解包回退
┌────────────────────────────────────────────────▼──────────────────────────────────────────────────┐
│ keel-host                                                                                         │
│                                                                                                   │
│  CLI / 控制台        插件加载器         生命周期          路由表            Ktor CIO              │
│  ───────────        ──────────         ────────          ──────            ────────               │
│  参数解析            .keel 扫描         START            method + path     单端口                 │
│  命令分发            manifest 校验      COMPLETE         routeId           中间件                 │
│  优雅停机            动态库解包         SHUTDOWN         插件归属          访问日志               │
│                      资源注册                                                                     │
│                                                                                                   │
│  事件泵线程                                                                                       │
│  Host 收件箱 → 日志汇总 / 事件转发 / 请求应答 / 定时唤醒                                          │
└──────────┬────────────────────────────────┬────────────────────────────────┬──────────────────────┘
           │ C ABI                          │ C ABI                          │ C ABI
           │ 事件队列 + 不透明句柄          │                                │
┌──────────▼───────────────┐   ┌────────────▼─────────────┐   ┌──────────────▼──────────────┐
│ keel-plugin-hello        │   │ keel-plugin-<id>         │   │ keel-plugin-...             │
│ 独立 Kotlin/Native 运行时│   │ 独立 Kotlin/Native 运行时│   │ 独立 Kotlin/Native 运行时   │
│ Worker 线程              │   │ Worker 线程              │   │ Worker 线程                 │
│ 收件箱 256 条            │   │ 收件箱 256 条            │   │ 收件箱 256 条               │
│ 生命周期 / 路由 / 调度器 │   │ 生命周期 / 路由 / 调度器 │   │ 生命周期 / 路由 / 调度器    │
└──────────────────────────┘   └──────────────────────────┘   └─────────────────────────────┘

构建期工具
  moe.lolosia.keel.classpath    资源合并与可执行文件嵌入
  moe.lolosia.keel.plugin       .keel 打包
  moe.lolosia.keel.abi          C ABI 静态库注入
  moe.lolosia.keel.logger       日志调用点改写
  moe.lolosia.keel.publish      发布仓库注册
  keel-router-ksp               路由表生成
  keel-plugin-ksp               Worker 入口生成
```

### 组件职责

| 组件 | 职责 |
| --- | --- |
| `keel-api` | 插件契约：生命周期、上下文、路由、调度、日志、事件与消息 |
| `keel-host` | 宿主：插件加载、生命周期推进、事件泵、Ktor 引擎、路由表、控制台与停机 |
| `keel-launcher` | 单文件发行版入口：静态链接宿主，插件装配仍在实现 |
| `keel-abi` | C ABI 头文件与每目标静态库：队列、事件、bootstrap、控制台与崩溃处理 |
| `keel-classpath` | 只读资源层：zip、目录、前缀视图与流式读取 |
| `keel-classpath-full` | 宿主注册表、带代次的资源引用与跨模块代理 |
| `keel-plugin-*` | 业务插件，顶层平铺，每个插件一个独立 Gradle 构建 |
| `keel-*-gradle-plugin` | 构建期 Gradle 插件：资源嵌入、`.keel` 打包、ABI 注入、日志挂载与发布 |
| `keel-router-ksp` | 路由表代码生成器 |
| `keel-plugin-ksp` | 插件 Worker 入口代码生成器 |

### 运行模型

宿主与每个插件都是独立的 Kotlin/Native 运行时。宿主通过 `dlopen` 或 `LoadLibrary` 打开插件动态库，只解析 `keel_plugin_bootstrap` 与清单声明的 Worker 入口地址，不直接调用插件导出的 Kotlin 函数。`keel_plugin_bootstrap` 由 C 实现，负责创建 Worker 线程、收件箱与事件泵。Worker 线程收到 `START` 后才第一次进入插件 Kotlin 运行时，构造 `@KeelPluginMain` 标注的实例并推进生命周期。宿主线程与插件线程因此不会同时进入对方的运行时。

每个插件的事件泵线程是该插件唯一的 Kotlin 执行入口。宿主投递事件到插件收件箱，泵线程取出事件并分发；插件返回结果时，把 `COMPLETE` 投回宿主收件箱。宿主主线程周期性消费宿主收件箱，按 `correlation` 完成挂起的请求。队列为定长环形缓冲，插件收件箱与宿主收件箱各 256 条，满时拒绝入队，不做无限堆积。

### 生命周期

| 阶段 | 执行者 | 行为 |
| --- | --- | --- |
| 扫描 | 宿主 | 按文件名排序列出插件目录中的 `.keel` |
| 校验 | 宿主 | 检查 `id`、`abiVersion`、`entry`、当前目标的库条目与重复 id |
| 解包 | 宿主 | 把动态库写入缓存目录，按 `id/version` 分层 |
| 注册资源 | 宿主 | 把包内 `resources/` 注册进 classpath 注册表 |
| 加载 | 宿主 | `dlopen` 或 `LoadLibrary`，解析 bootstrap 与入口 |
| 启动 | C 与插件 Worker | bootstrap 创建线程与队列，投递 `START`，执行 `onLoad` 与 `onEnable` |
| 服务 | 插件 Worker | 处理 HTTP、控制台、定时任务与健康检查事件 |
| 停用 | 插件 Worker 与宿主 | 插件执行 `onDisable` 并清空调度器，宿主清理该插件的路由 |
| 停机 | 宿主 | 停止 HTTP，逐个投递 `SHUTDOWN`，清空收件箱，进程退出 |

### 跨运行时边界

跨边界的唯一载体是 C ABI。头文件 [`keel_abi.h`](keel-abi/src/nativeCpp/keel_abi.h) 是结构体布局与符号导出的唯一来源。Kotlin 侧通过 cinterop 生成绑定，C 侧实现队列、事件释放、bootstrap 与平台线程原语。每个二进制各自链接一份静态库，队列与句柄表因此天然按二进制隔离。

| 不变量 | 约束 |
| --- | --- |
| 单线程单运行时 | 一个线程只能进入一个 Kotlin/Native 运行时 |
| 宿主不直接调用插件 | 宿主只解析插件入口地址，由 bootstrap 在事件泵线程上调用 |
| 对象不跨边界 | Kotlin 对象留在拥有者运行时，另一边持有句柄与本地代理 |
| 协程不跨边界 | `suspend` 只在本地使用，完成结果通过事件回投 |
| 回调使用标识 | 注册数字回调 id，调用时投递事件，由拥有者查表执行 |
| 载荷所有权明确 | 发送方分配并挂载归还函数，接收方复制后归还 |
| 热路径使用二进制编码 | 目标格式为 CBOR 或 Protobuf，HTTP 边界当前使用 JSON |
| 卸载不回收地址空间 | 卸载只释放引用与停止事件泵，动态库在进程重启时回收 |
| 缺失即拒绝 | 权限、资源或服务不可用时返回失败，不降级为放行 |

### 请求处理流程

```text
客户端
  │
  │ HTTP 请求
  ▼
Ktor CIO 单端口
  │ 1. 匹配 method 与 path
  │ 2. 提取 HttpRequest 快照
  ▼
Host 路由表 ──────────────► 命中 pluginId 与 routeId
  │
  │ 3. 投递 COMMAND subject=HTTP correlation=N
  ▼
插件 Worker 线程
  │ 4. 解码 HttpRouteRequest
  │ 5. 查本地路由表
  │ 6. 执行 handler
  ▼
COMPLETE correlation=N + HttpRouteResponse
  │
  ▼
Host 事件泵 ──────────────► 唤醒挂起的 Ktor 协程
  │
  ▼
客户端 ◄────────────────── HTTP 响应
```

Ktor 的 handler 运行在宿主协程中。它不直接调用插件，而是通过 `HostPump.request` 把请求放入通道并挂起；泵线程负责投递与回投；挂起由 `CompletableDeferred` 完成。`ApplicationCall`、`Continuation` 与 Ktor 上下文始终留在宿主运行时。

### 插件加载流程

```text
plugins/
  │ 扫描 *.keel
  ▼
PluginLoader
  │ 1. 读取包根 plugin.json
  │ 2. 校验 id、abiVersion、entry 与当前目标库
  │ 3. 解出动态库到 <cache>/<id>/<version>/
  │ 4. 注册 resources/ 到 classpath 注册表
  │ 5. dlopen 或 LoadLibrary
  ▼
keel_plugin_bootstrap
  │ 创建 Worker 线程与 256 条收件箱
  ▼
Worker 线程首次进入插件运行时
  │ START
  │ 构造 @KeelPluginMain 实例
  │ onLoad → onEnable
  ▼
COMPLETE
  │
  ▼
宿主记录 plugin_id，插件进入可服务状态
```

### 资源与卸载语义

`.keel` 是 zip 包，包含根 `plugin.json`、按目标划分的动态库与 `resources/` 目录。资源不解包，宿主与插件按中央目录随机读取。宿主为每个插件维护带代次的 `ClasspathRef`，插件卸载时代次失效，旧引用读取会抛出 `ClasspathNotFoundException`。跨插件访问只能获得不透明引用，真实包路径保留在宿主。

Kotlin/Native 运行时无法安全卸载。`dlclose` 之后 TLS 与 GC 可能延迟崩溃，因此卸载只停止事件泵、注销路由与资源引用，动态库继续留在进程地址空间，最终由进程重启回收。

### 日志与诊断

`Log` 是统一日志门面。`moe.lolosia.keel.logger` 在 IR 阶段把 `Log.info(...)` 改写为携带调用点的形式，来源包含类名、文件、行号、包与函数。插件日志编码为 `LOG` 事件投回宿主，由宿主按同一行格式渲染。终端支持 ANSI 时按级别、模块名与来源类名着色；模块名默认使用清单中的插件名，`plugin.json.color` 可以指定插件模块列的 truecolor。

同一个编译器插件还为异常构造与 `throw` 插桩，记录创建点与抛出点。日志渲染时在异常头后输出 `Created at`、`Throwed at` 与 `Printed at`，跨运行时通过 `KeelErrorEntry` 传递创建时间与抛出点，宿主不再追加载体栈。

## 快速开始

### 环境要求

- JDK 21，用于运行 Gradle 与 KSP 处理器；产物本身不依赖 JVM
- 首次构建会下载 Kotlin/Native 工具链到 `~/.konan`
- 在 Windows 上构建 `linuxArm64` 或 `linuxX64` 需要 WSL2 与对应的 `objcopy`
- 使用 `.keel` 打包与控制台插件管理不需要额外服务

### 构建宿主与示例插件

```powershell
gradlew :keel-host:linkDebugExecutableMingwX64
gradlew :keel-plugin-hello:keelPackage
```

产物位置：

```text
keel-host/build/bin/mingwX64/debugExecutable/keel-host.exe
keel-plugin-hello/build/keel/keel-plugin-hello-0.1.0-SNAPSHOT.keel
```

构建全部独立构建：

```powershell
gradlew buildAll
```

### 启动宿主

```powershell
.\keel-host\build\bin\mingwX64\debugExecutable\keel-host.exe `
    --plugins .\keel-plugin-hello\build\keel `
    --http=7180 `
    --serve
```

`--plugins` 指向包含 `.keel` 的目录，缓存目录默认为该目录下的 `.cache`。宿主会打印启动横幅、插件加载结果、健康检查结果与 HTTP 监听地址。停止时在控制台输入 `stop`，或按 `Ctrl+C` 触发优雅停机。

### 冒烟验证

```powershell
curl.exe -s http://127.0.0.1:7180/healthz
curl.exe -s http://127.0.0.1:7180/ping
curl.exe -s http://127.0.0.1:7180/hello
curl.exe -s -X POST -H "Content-Type: application/json" --data '{"name":"keel","count":1}' http://127.0.0.1:7180/echo
```

预期结果：

```text
ok
ok
hello from keel plugin
{"name":"keel","count":1}
```

`/healthz` 由宿主直接响应，不经过插件。`/ping` 走完整的事件泵往返：宿主投递 `PING`，插件回 `COMPLETE`，宿主再返回 HTTP 响应。

### 控制台命令

```text
help
plugins list
plugins info hello
plugins disable hello
plugins enable hello
plugins remove hello
stop
```

`help`、`stop` 与 `plugins` 是宿主内置命令。其它输入按插件启用顺序交给插件的 `onCommand`，插件返回 `false` 时继续询问下一个插件。

### 一次性验证

```powershell
.\keel-host\build\bin\mingwX64\debugExecutable\keel-host.exe `
    --plugins .\keel-plugin-hello\build\keel `
    --once
```

`--once` 在插件启动与健康检查后退出，适合自动化流水线。

### 检查插件包

```powershell
.\keel-host\build\bin\mingwX64\debugExecutable\keel-host.exe `
    --inspect .\keel-plugin-hello\build\keel\keel-plugin-hello-0.1.0-SNAPSHOT.keel
```

`--inspect` 读取清单与资源条目，并验证卸载后资源读取立即失效。

## 编写插件

### 1. 创建独立构建

在顶层新建 `keel-plugin-<id>/` 目录，并为它准备独立的 `settings.gradle.kts`、`build.gradle.kts`、源码与资源。插件构建必须声明依赖的构建期插件：

```kotlin
pluginManagement {
    includeBuild("../keel-plugin-gradle-plugin")
    includeBuild("../keel-abi-gradle-plugin")
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "keel-plugin-hello"
```

在仓库内开发时，还需要把新目录加入根 [`settings.gradle.kts`](settings.gradle.kts) 的 `includeBuild` 列表，根构建才能替换 Maven 坐标并聚合任务。

### 2. 配置构建脚本

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("moe.lolosia.keel.plugin")
}

group = "moe.lolosia.keel.plugins"
version = libs.versions.keel.get()

keelPlugin {
    target("mingwX64")
}

kotlin {
    mingwX64 { binaries.sharedLib { baseName = "keel-plugin-hello" } }
    linuxArm64 { binaries.sharedLib { baseName = "keel-plugin-hello" } }
    linuxX64 { binaries.sharedLib { baseName = "keel-plugin-hello" } }

    sourceSets {
        commonMain.dependencies {
            api("moe.lolosia.keel:keel-api:${libs.versions.keel.get()}")
        }
    }
}
```

`moe.lolosia.keel.plugin` 自动应用 ABI 静态库注入、日志编译器插件与 KSP，并注册 `keelPackage` 任务。插件工程只需要保留生命周期实现与 `plugin.json`。

### 3. 编写插件清单

清单放在 `src/commonMain/resources/plugin.json`，打包时复制到 `.keel` 根部：

```json
{
  "id": "hello",
  "name": "Hello Plugin",
  "color": "#FF8800",
  "version": "0.1.0",
  "abiVersion": 1,
  "entry": "keel_plugin_worker_main",
  "lib": {
    "mingwX64": "lib/mingwX64/keel_plugin_hello.dll"
  },
  "resources": "resources/",
  "requires": [],
  "authors": ["lolosia"],
  "description": "最小示例插件"
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 全局唯一标识，建议使用小写点分或短横线命名 |
| `version` | 是 | 插件版本，参与缓存目录分层 |
| `abiVersion` | 是 | 与宿主 ABI 兼容的主版本，当前为 `1` |
| `entry` | 是 | Worker 入口符号名，由 KSP 生成 |
| `lib` | 是 | 目标名到包内动态库路径的映射 |
| `resources` | 否 | 资源在包内的前缀，默认 `resources/` |
| `name` | 否 | 展示名，用于日志模块列 |
| `color` | 否 | 日志模块列的 truecolor，支持 `#RRGGBB` 与 `#RGB` |
| `requires` | 否 | 依赖的插件 id，当前只读取，尚未参与加载顺序 |
| `authors` | 否 | 作者列表 |
| `sha256` | 否 | 动态库或包的校验值，当前只读取不校验 |

### 4. 实现生命周期

```kotlin
package moe.lolosia.keel.plugins.hello

import moe.lolosia.keel.api.*
import moe.lolosia.keel.api.annotation.KeelPluginMain
import moe.lolosia.keel.api.command.*
import moe.lolosia.keel.api.web.*

@KeelPluginMain
class HelloPlugin : Plugin {

    private lateinit var context: PluginContext

    override fun onLoad(context: PluginContext) {
        this.context = context
        context.routes.register(RouteDescriptor("GET", "/hello", "hello#hello")) { _, _ ->
            HttpResponse(body = ResponseBody.Text("hello from keel plugin"))
        }
        context.routes.register(RouteDescriptor("POST", "/echo", "hello#echo")) { request, _ ->
            HttpResponse(
                body = ResponseBody.Text(
                    text = request.body.decodeToString(),
                    contentType = "application/json; charset=utf-8",
                ),
            )
        }
        Log.info("loaded ${context.metadata.id}")
    }

    override fun onEnable() {
        Log.info("enabled")
        var ticks = 0
        var handle: TaskHandle? = null
        handle = context.scheduler.schedule(0, 1_000) {
            ticks++
            Log.info("tick $ticks")
            if (ticks == 3) handle?.cancel()
        }
    }

    override fun onDisable() {
        Log.info("disabled")
    }

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: List<String>,
    ): Boolean {
        if (label != "hello") return false
        sender.sendMessage("hello ${args.joinToString(" ")}")
        return true
    }
}
```

`@KeelPluginMain` 标注的类必须实现 `Plugin` 并提供无参构造器。KSP 会生成固定的 C 入口 `keel_plugin_worker_main`，由 bootstrap 在事件泵线程上调用，开发者不需要手写入口胶水代码。

### 5. 打包与安装

```powershell
gradlew :keel-plugin-hello:keelPackage
```

产物结构：

```text
keel-plugin-hello-0.1.0-SNAPSHOT.keel
  plugin.json
  lib/mingwX64/keel_plugin_hello.dll
  resources/hello.txt
```

把 `.keel` 放入 `--plugins` 指定的目录，或在控制台执行：

```text
plugins install <keel 路径>
```

宿主会复制包、分配 `plugin_id` 并启动插件。`plugin_id` 只在当前进程内有效，持久化标识一律使用清单中的 `id`。

### 6. 注册路由

当前路由由插件在 `onLoad` 中手工注册：

```kotlin
context.routes.register(
    RouteDescriptor(
        method = "GET",
        path = "/user/{id}",
        routeId = "hello#user",
        permissions = listOf("hello.user.read"),
    ),
) { request, pathParameters ->
    val id = pathParameters["id"]
    HttpResponse(body = ResponseBody.Bytes(ByteArray(0)))
}
```

路由匹配支持精确段与 `{name}` 段。请求进入插件时携带 `HttpRequest` 快照，字段如下：

| 字段 | 说明 |
| --- | --- |
| `method` | HTTP 方法 |
| `path` | 请求路径 |
| `query` | 查询参数，值为列表 |
| `headers` | 请求头 |
| `body` | 原始请求体字节 |
| `remoteAddress` | 客户端地址，当前未由宿主填充 |
| `traceId` | 追踪标识，当前未由宿主填充 |

响应使用 `HttpResponse`：

| 字段 | 说明 |
| --- | --- |
| `status` | HTTP 状态码 |
| `headers` | 契约字段：响应头，当前线格式尚未携带 |
| `body` | `ResponseBody.Empty`、`Text`、`Bytes` 或 `Stream` |
| `envelope` | 契约字段：是否套用 JSON 信封，当前尚未接入 |

注解式控制器由 `keel-router-ksp` 生成，注解包括 `@Controller`、`@GET`、`@POST`、`@PUT`、`@DELETE`、`@PATCH`、`@Path`、`@Query`、`@Header`、`@Body` 与 `@Permission`。当前处理器尚未生成路由表，业务插件应使用手工注册。`RouteDescriptor.permissions` 已进入契约，权限判定尚未接入路由分发。

### 7. 使用调度器

```kotlin
val handle = context.scheduler.schedule(delayMillis = 100, periodMillis = 1_000) {
    Log.info("periodic task")
}
handle.cancel()
```

调度器绑定插件 Worker 线程。延时与周期任务只在该线程执行，取消不会提前唤醒事件泵，最多空跑一次。长耗时任务应拆成多次短任务，避免占住事件泵。

### 8. 读取资源

`keel-classpath` 可以直接读取目录或 zip：

```kotlin
val classpath = classpathFromDirectory("src/commonMain/resources")
val text = classpath.read("hello.txt")?.decodeToString()
```

插件上下文中的 `ctx.classpath` 已声明为同一个只读接口。插件自身资源读取与跨插件远程读取仍在接入。宿主侧注册表、`ClasspathRef` 与代次失效已经可用，可直接用于宿主资源与开发期验证。

### 9. 输出日志

```kotlin
Log.info("plugin started")
Log.warn("configuration is missing")
Log.error("request failed", cause = exception)
```

`Log` 会经过编译器插件改写，日志行包含类名与行号。`context.logger` 保留为兼容入口，不带调用点信息。插件模块列默认使用 `plugin.json.name`，缺失时使用 `id`。

## 构建期工具链

| 工具 | 类型 | 作用 |
| --- | --- | --- |
| `moe.lolosia.keel.classpath` | Gradle 插件 | 把各源集资源合并为 `classpath.zip`，经 `windres` 或 `objcopy` 嵌入可执行文件 |
| `moe.lolosia.keel.plugin` | Gradle 插件 | 生成 `.keel` 包，自动应用 ABI、日志与 KSP |
| `moe.lolosia.keel.abi` | Gradle 插件 | 解出 `libkeel_abi.a` 与头文件，挂接链接参数与导出列表 |
| `moe.lolosia.keel.logger` | Gradle 插件 | 把日志编译器插件挂到 Kotlin/Native 编译 |
| `moe.lolosia.keel.publish` | Gradle 插件 | 注册自建 Maven 发布仓库 |
| `keel-plugin-ksp` | KSP 处理器 | 从 `@KeelPluginMain` 生成 `keel_plugin_worker_main` |
| `keel-router-ksp` | KSP 处理器 | 从控制器注解生成路由表 |

### 资源嵌入

```kotlin
keelClasspath {
    target("mingwX64")
    compressed = false
    windows {
        productName = "Keel Modular Web Framework"
    }
}
```

普通的可执行文件、动态库与共享库使用这条链路。插件不使用 `classpath.zip`，资源作为 `.keel` 包的一部分交付。

### 插件打包

```kotlin
keelPlugin {
    target("mingwX64")
    buildType = "RELEASE"
    autoAddClasspathFull = true
}
```

`buildType` 决定打包 `DEBUG` 还是 `RELEASE` 动态库。`target` 为空时按宿主平台选择 `mingwX64`、`linuxArm64` 或 `linuxX64`。

### ABI 注入

```kotlin
keelAbi {
    export("my_worker_entry")
}
```

默认导出 `keel_plugin_bootstrap`、`keel_plugin_shutdown` 与 `keel_plugin_worker_main`。只有使用自定义 Worker 入口名时才需要追加导出符号。Windows 上 Kotlin 的 `@CName` 符号不会自动进入 PE 导出表，打包过程依赖 ABI 插件生成的 `.def` 文件。

### 日志改写

```kotlin
keelLogger {
    enabled = true
    exceptionTrace = true
}
```

`exceptionTrace` 控制是否为异常构造与 `throw` 插桩。关闭后日志不再输出创建点与抛出点。

## 命令行

| 参数 | 说明 |
| --- | --- |
| `--plugins <目录>` | 扫描目录中的 `.keel` 并启动插件 |
| `--once` | 启动与健康检查后退出 |
| `--serve` | 常驻运行，直到控制台 `stop` 或中断信号 |
| `--serve-ms=<毫秒>` | 运行固定时长后优雅停机 |
| `--http=<端口>` | 启动 Ktor CIO 监听端口 |
| `--inspect <路径>` | 检查单个 `.keel` 的清单与资源 |
| `--watchdog` | 内部看门狗进程入口，通常由宿主自动拉起 |

`--plugins` 的第二个位置参数是缓存目录。缺省值为 `<插件目录>/.cache`，动态库按 `<id>/<version>/` 存放。

## 构建任务

| 任务 | 说明 |
| --- | --- |
| `gradlew buildAll` | 构建所有独立 Gradle 构建 |
| `gradlew :keel-host:linkDebugExecutableMingwX64` | 构建 Windows 调试宿主 |
| `gradlew :keel-host:linkReleaseExecutableLinuxArm64` | 构建 arm64 发布宿主 |
| `gradlew :keel-host:linkReleaseExecutableLinuxX64` | 构建 x64 发布宿主 |
| `gradlew :keel-launcher:linkReleaseExecutableMingwX64` | 构建 Windows 单文件发行版入口 |
| `gradlew :keel-plugin-hello:keelPackage` | 打包示例插件 |
| `gradlew :keel-abi:keelAbiPackage` | 打包 ABI 头文件与静态库 |
| `gradlew :keel-api:build` | 构建插件契约 |
| `gradlew :keel-plugin-hello:build` | 验证消费方能够编译 |

单独构建某个目录时，跨目录依赖通过 Maven 坐标获取。本地验证可以用 `--include-build=../keel-api` 之类的参数提供被依赖工程，根 composite 构建会自动完成替换。

### 发布

发布地址与凭据只写入本机 `local.properties`：

```properties
keel.repo=http://<maven-host>/repository/keel
keel.username=<user>
keel.password=<password>
```

发布顺序如下：

```text
构建期插件与 KSP 处理器
  → keel-abi
  → keel-classpath
  → keel-api
  → keel-classpath-full
  → keel-host
  → 业务插件
  → keel-launcher
```

构建期工具各自在自己的 `build.gradle.kts` 中配置发布仓库。功能插件应用 `moe.lolosia.keel.publish`，不要在业务构建中手写 `publishing {}`。修改 `keel-api` 后必须确认所有消费方重新编译。

## 部署形态

运行期只需要宿主可执行文件与插件目录：

```text
/opt/keel/
  keel-host
  plugins/
    hello-0.1.0.keel
  work/
```

以 `--http=<端口> --plugins <目录> --serve` 常驻。宿主不依赖 JVM，也不要求目标机安装 Kotlin 运行时。停止时发送中断信号或在控制台输入 `stop`，宿主会先停止 HTTP，再逐个停用插件。

systemd 单元可以直接以宿主可执行文件作为 `ExecStart`，工作目录指向部署目录；容器部署时挂载宿主、插件目录与端口即可。启动失败、插件加载失败与请求错误都会写入日志文件，日志按日切分在工作目录的 `work/log` 下。

## 目录结构

```text
keel/
├── build.gradle.kts                 根构建，仅聚合 includeBuild
├── settings.gradle.kts              composite build 列表
├── gradle/libs.versions.toml        唯一版本来源
├── keel-abi/                        C ABI 头文件、事件队列、bootstrap 与静态库
├── keel-abi-gradle-plugin/          ABI 静态库注入
├── keel-api/                        插件契约与插件侧运行时
├── keel-classpath/                  只读资源层
├── keel-classpath-full/             宿主注册表与跨模块资源引用
├── keel-classpath-gradle-plugin/    资源合并与可执行文件嵌入
├── keel-host/                       宿主
├── keel-launcher/                   单文件发行版入口
├── keel-logger-compiler-plugin/     日志调用点 IR 改写
├── keel-logger-gradle-plugin/       挂载日志编译器插件
├── keel-plugin-gradle-plugin/       .keel 打包
├── keel-plugin-hello/               示例插件
├── keel-plugin-ksp/                 Worker 入口生成
├── keel-publish-gradle-plugin/      发布仓库注册
├── keel-router-ksp/                 路由表生成
└── doc/                             设计与计划
```

顶层目录都是独立 Gradle 构建。每个目录都可以单独 clone 出来构建，跨目录依赖从 Maven 获取。依赖方向为 `keel-api ← keel-host ← keel-launcher` 与 `keel-api ← keel-plugin-*`，构建期工具独立于运行时。任何目录都不得读取兄弟目录中的源文件，跨目录只能通过 Maven 坐标或构建产物。

## 当前状态

Keel 处于 `0.1.0-SNAPSHOT`。宿主、C ABI、插件加载、HTTP 路由、日志与打包链路已经可以运行，以下能力仍在实现中。

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| 插件扫描、校验、加载与启停 | 可用 | `.keel` 目录、动态库缓存与失败隔离 |
| C ABI 事件泵与请求应答 | 可用 | 队列、`correlation`、健康检查、日志、HTTP 与控制台 |
| 跨运行时句柄与回调 | 契约已定义 | 宿主句柄表与 `CallbackRegistry` 尚未接入 |
| 插件依赖排序 | 契约字段已定义 | `plugin.json.requires` 尚未参与加载顺序 |
| 单端口 HTTP 与手工路由 | 可用 | `ctx.routes.register`、路径参数与 JSON 线格式 |
| 调度器 | 可用 | 延时、周期与取消，绑定 Worker 线程 |
| 日志调用点与异常诊断 | 可用 | IR 改写、配色、创建点与抛出点 |
| `.keel` 打包与控制台插件管理 | 可用 | `keelPackage`、`plugins` 子命令与 `--inspect` |
| 资源层 | 部分可用 | 宿主侧注册表可用，插件侧资源读取未接入 |
| 跨模块服务调用 | 契约已定义 | `ModuleDirectory` 与生成的客户端未接入运行时 |
| 配置读写 | 契约已定义 | `Config` 尚未接入运行时 |
| 注解路由生成 | 规划中 | `@Controller` 与 `@GET` 已定义，`keel-router-ksp` 尚未生成 |
| 权限判定 | 契约已定义 | `@Permission`、`Authorizer` 与路由 `permissions` 尚未接入分发 |
| JSON 信封与响应头 | 契约已定义 | `HttpResponse.envelope` 与 `headers` 尚未进入线格式 |
| 流式响应 | 契约已定义 | `ResponseBody.Stream` 尚未接入 HTTP 分发 |
| 单文件内嵌插件 | 入口已就位 | `keel-launcher` 目前转发到宿主入口 |
| 发布产物打包 | 未实现 | `moe.lolosia.keel.publish` 只注册仓库，按目标归档与发布未实现 |
| 自动化测试 | 未接入 | 以编译、`--inspect` 与启动冒烟验证 |

## 延伸阅读

| 文档 | 内容 |
| --- | --- |
| [AGENTS.md](AGENTS.md) | 代码风格、构建边界与硬性约定 |
| [doc/roadmap.md](doc/roadmap.md) | DEMO、P0、P1、P2 的目标与验收标准 |
| [doc/abi-design.md](doc/abi-design.md) | C ABI、Worker、句柄、事件泵与内存规则 |
| [doc/web-design.md](doc/web-design.md) | 单端口路由、请求上下文、中间件与流式响应 |
| [doc/classpath-design.md](doc/classpath-design.md) | 资源命名空间、`.keel` 包与卸载语义 |
| [doc/config-design.md](doc/config-design.md) | 配置分层、注释保留与插件配置隔离 |
| [doc/authz-design.md](doc/authz-design.md) | 权限契约与 RBAC 插件边界 |
| [doc/codegen-design.md](doc/codegen-design.md) | 零样板模块互访与代码生成 |
| [doc/capability-design.md](doc/capability-design.md) | 能力插件与稳定 C ABI |
| [doc/前序讨论.md](doc/前序讨论.md) | 可行性讨论、硬约束与风险清单 |

## 许可证

Keel 使用 [MIT License](LICENSE)。第三方组件的名称、版本、许可与用途见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。
