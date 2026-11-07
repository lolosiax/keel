# Keel 阶段计划：DEMO → P0 → P1 → P2

> 本文回答三件事：**要做成什么**、**先做什么**、**每一阶段怎样算做完**。
> 可行性依据与硬约束见 [前序讨论.md](前序讨论.md)；工程约定见 [../AGENTS.md](../AGENTS.md)。

---

## 0. 一句话定位

**Keel 是一个 Kotlin/Native 的模块化 Web 框架**：Host 负责容器能力（生命周期、路由、上下文、调度），
插件以动态库或单文件内嵌的形式交付，业务功能按插件拆分、独立升级。

类比：Tomcat 之于 WAR、Bukkit 之于插件。区别在于没有 JVM 的类加载器与卸载语义，
所有跨运行时交互都要显式建模（见前序讨论 §2）。

### 非目标

- **不做 JVM 版本**：没有 `ClassLoader`、没有反射、没有 fat jar。
- **不承诺物理卸载**：卸载 = 停止 + 释放句柄；地址空间回收靠进程重启兜底。
- **不做通用序列化器的自动深遍历**：跨插件对象图用两阶段批量协议（P1），不做「透明远程对象」。

---

## 1. 架构总览

```
                ┌─────────────────────────────────────────────┐
                │                launcher（可选）              │
                │  单文件：Host + 插件清单 + 插件原生库 + 资源   │
                └──────────────────────┬──────────────────────┘
                                       │ 静态链接 / 解包回退
┌──────────────────────────────────────▼──────────────────────────────────────┐
│ host：插件加载器 · 生命周期 · 消息总线 · 调度器 · Ktor 引擎 · 上下文注入      │
└───────┬───────────────────────────────┬───────────────────────────────┬──────┘
        │ C ABI（blittable + 不透明句柄）│                               │
┌───────▼────────┐              ┌───────▼────────┐              ┌───────▼────────┐
│ plugin: hello  │              │ plugin: echo   │              │ plugin: ...    │
│ 独立 K/N 运行时 │              │ 独立 K/N 运行时 │              │                │
│ 专属 Worker + 队列              │ 专属 Worker + 队列              │                │
└───────┬────────┘              └───────┬────────┘              └───────┬────────┘
        └────────────── 依赖 keel-api（构建期 Maven 坐标）──────────────┘

keel-classpath-gradle-plugin / keel-plugin-gradle-plugin / keel-publish-gradle-plugin
keel-router-ksp / keel-plugin-ksp（构建期，不在运行时图里）
  ├─ moe.lolosia.keel.publish    按目标打包并发布插件到自建 Maven
  ├─ moe.lolosia.keel.classpath  resources → classpath.zip → 嵌入可执行文件
  ├─ moe.lolosia.keel.plugin     插件资源 → .keel 包
  ├─ keel-router-ksp             控制器注解 → 路由注册表（无反射）
  └─ keel-plugin-ksp             @KeelPluginMain → Worker 入口
```

**仓库即架构**：`keel-api` / `keel-host` / `keel-launcher` / `keel-plugin-*` 是顶层独立 Gradle 构建，
每个 Gradle 插件与 KSP 处理器也各占一个顶层构建。拆分方式见 [../AGENTS.md](../AGENTS.md) §1。

---

## 2. DEMO —— 先证明「能跑」

**目的**：用最小代码验证整条链路成立，不追求功能完整。任何一项失败都要在进入 P0 前重新评估架构。

### 范围

1. **Gradle 工具链**
   - `moe.lolosia.keel.publish`：把插件工程的原生库（按 `mingwX64` / `linuxArm64` / `linuxX64`）与 `plugin.json` 打成工件，发布到自建 Maven。
   - `moe.lolosia.keel.classpath`：把 `src/commonMain/resources` 打成 `classpath.zip`（先不做嵌入）。
   - `moe.lolosia.keel.plugin` + `keel-plugin-ksp`：从 `@ModuleService` 接口生成客户端 / 分发器 / 访问器
     （见 [codegen-design.md](codegen-design.md)）；DEMO 先支持单个服务、值参数 / 返回值。
   - `keel-router-ksp`：生成 `@Controller` 路由表（DEMO 先支持 GET + `@Path` / `@Query` + DTO 返回）。
2. **插件加载器（Host）**
   - 从指定目录扫描 `*/plugin.json` + 原生库；`dlopen` / `LoadLibrary` 后取
     **`keel_plugin_bootstrap`**（C 符号），由它创建 Worker 线程并在新线程上首次进入插件运行时。
   - Host 线程**不直接调用**插件的任何 `@CName`（线程 / 运行时模型见 [abi-design.md](abi-design.md) §2）。
   - 生命周期：`load → enable → disable`，`disable` 后不 unmap。
   - **容错**：目录里混入残缺插件、版本不符、bootstrap 缺失时只记日志，不拖垮 Host。
3. **事件泵与消息 API**
   - C shim：有界队列 + mutex/cond + 唤醒；`KeelEvent` 头 + 二进制载荷；载荷由发送方归还。
   - 句柄协议最小版：`StableRef → handle`，`retain/release`，归零时向拥有者投递 `DISPOSE`。
   - 两个示例插件互相投递 request / complete，应答在发起方 Worker 上处理。
4. **调度器**
   - `schedule(delay, period, task)` + `TaskHandle.cancel()`；在插件 Worker 上执行。
5. **两个示例插件**
   - `hello`：打印生命周期。
   - `echo`：暴露一个 `EchoApi` 服务；`hello` 通过 `ctx.echo` 直接调用（零样板验证）。
6. **配置**
   - Host `application.yaml` + 每插件 `config/plugins/<id>.yaml`；
   - KAML 读、Host 侧文本编辑写，保留注释与空行（见 [config-design.md](config-design.md)）。
7. **Web 路由**
   - Host 单端口 + 同一中间件；插件 `@Controller` / `@GET` 注册路由，返回 DTO 或流
     （见 [web-design.md](web-design.md)）。
8. **实机验证**
   - Windows（开发机）+ 泰山派 RK3566（`linuxArm64`）各跑一次；记录启动、加载、消息往返、调度精度。

### 验收标准

- [ ] `gradlew -p keel-*-gradle-plugin publish` 把构建期工具发布到自建 Maven，另一个干净目录能只靠 Maven 依赖构建出 `echo` 插件。
- [ ] `gradlew -p keel-plugin-hello build` 产出 `keel-plugin-hello-<target>`；DEMO 新增的 `keel-plugin-echo` 同理。
- [ ] Host 启动后自动加载 `plugins/` 下两个插件，日志能看到 `load → enable`，退出时 `disable`。
- [ ] 残缺插件（缺字段 / 错误 bootstrap / 架构不符）不会导致 Host 崩溃，错误可定位。
- [ ] Host 线程与插件 Worker 互不进入对方运行时（启动时用断言 / 日志验证）。
- [ ] `hello → echo → hello` 调用成功，`ctx.echo` 由 KSP 生成，调用点没有手写代理 / 编解码。
- [ ] 应答在发起方 Worker 上处理，回调也在发起方 Worker 上执行。
- [ ] 周期任务 100ms 连续运行 10 分钟，取消后不再触发；RK3566 上记录实际抖动。
- [ ] 同一进程反复 `disable → enable` 50 次不崩、内存不单调暴涨（用 Private Commit 观察）。
- [ ] 改已有配置键 / 新增键后，YAML 注释、空行与键顺序不变。
- [ ] 插件只写 `@Controller` / `@GET` 就能在 Host 端口提供服务，中间件与 `@Permission` 生效。

### DEMO 明确不做

- 不做热替换、不做插件依赖排序、不做权限、不做插件级中间件 / SSE / WebSocket。
- 不做 launcher；先用 Host 可执行文件 + 外部 `plugins/` 目录。

---

## 3. P0 —— 最小可用框架

**目的**：让「写一个业务插件并部署」成为可重复的日常操作。

### 交付物

1. **插件生命周期完整化**
   - `plugin.json` 增加 `apiVersion`、`dependencies`、`enabledByDefault`、`provides` / `requires`。
   - 依赖拓扑排序；循环依赖报错；被依赖方未满足时拒绝启用而不是半启动。
   - 加载顺序与失败回滚：任一插件 enable 失败，已启用的依赖方回到 disable。
2. **Ktor 集成**
   - Host 启动 Ktor（CIO）引擎；插件通过 API 注册路由。
   - `RequestContext` 从 `ApplicationCall` 提取（用户、角色、路径参数、追踪 ID），以句柄形式传给插件。
   - `keel-router-ksp` 实现第一版：`@Controller` / `@GET` 等注解 → 生成路由注册表；不依赖反射。
3. **配置与日志**
   - 全局配置 + 每插件配置（宿主深合并默认值，支持环境变量覆盖）。
   - 统一日志门面；插件日志带 `pluginId` 前缀。
4. **资源与打包**
   - `keel-classpath` 独立库：zip / 目录 / 前缀视图，普通 Kotlin 项目可直接用。
   - `keel-classpath-full`：Host 注册表、`ClasspathRef`、租约与卸载取消。
   - 普通 exe/dll/so 继续 `classpath.zip` + `windres` / `objcopy` 嵌入。
   - 插件改为 `.keel` 包：`plugin.json` + `lib/<target>` + `resources/`；资源不解包，
     插件与 Host 都按 zip 读取；插件之间只能通过 `ctx.classpath.of(id)` 访问。
     见 [classpath-design.md](classpath-design.md)。
5. **Launcher 第一版**
   - 单文件分发：Host + 选定插件静态链接；插件清单作为资源内嵌。
   - 支持 `--plugin-dir`：运行时额外加载「手抖放进来」的插件；启动失败只跳过该插件。
   - CLI：`list` / `enable <id>` / `disable <id>` / `load <path>`。
6. **运维兜底**
   - systemd 单元 + 定时重启；Windows 侧的重命名-替换-重启删除流程写进脚本。
   - 监控：进程 Private Commit、插件队列深度、消息延迟、任务积压。

### 验收标准

- [ ] 三个平台都能从零构建出 Host 与至少两个插件，并在 RK3566 上通过 systemd 常驻。
- [ ] 新增一个「带路由的业务插件」只需复制 `keel-plugin-hello` 模板 + 改 `plugin.json`，不需要动 Host。
- [ ] 路由由 KSP 生成，运行期无反射；未标注的控制器不会被注册。
- [ ] 插件配置错误时只影响该插件；Host 与其它插件继续服务。
- [ ] Launcher 单文件在三个平台启动成功；`--plugin-dir` 加入的插件能被列出、启停。
- [ ] 定时重启后所有插件恢复启用状态；旧版本文件被清理。

---

## 4. P1 —— 生产可用

**目的**：把「能跑」提升到「敢放线上」：隔离、可观测、可升级、可取消。

### 交付物

1. **消息与序列化协议**
   - 统一 Protobuf / CBOR 载荷；定义 channel 命名与版本协商。
   - 两阶段批量快照序列化（前序讨论 §4.5）：先按模块分组收集句柄，再批量 `serialize_batch`，往返次数 = 涉及模块数。
2. **异步与取消**
   - 跨插件请求带超时；取消显式传播到接收方 Worker（`invokeOnCancellation` 对应 C ABI）。
   - 队列背压：满队列拒绝 + 可观测指标，不无限堆积。
3. **上下文与权限**
   - 账户、RBAC、租户等上下文进入 `Context`；插件只能看到被授权的字段。
   - core 提供 `@Permission` / `ctx.hasPermission` / `Authorizer` SPI；
     RBAC 实现放在 `keel-plugin-rbac`，Host 在路由分发前校验，无实现时 fail closed
     （见 [authz-design.md](authz-design.md)）。
4. **隔离与健康**
   - 单插件异常不扩散：Worker 崩溃 / 队列卡死时的熔断与重启策略。
   - 健康检查：插件自报状态 + 队列积压 + 消息超时率。
5. **升级流程**
   - 插件版本替换：新运行时实例加载 → 切换流量 → 旧实例 disable（不 unmap）→ 定时重启回收。
   - 兼容性检查：`apiVersion` 主版本不匹配拒绝加载，并给出可读错误。
6. **Launcher 增强**
   - 能内存加载就不落盘；不能时统一解包目录（按内容哈希命名）与启动清理。
   - 插件签名 / 校验和，拒绝被篡改的插件。
7. **可观测性**
   - 结构化日志、指标导出（Prometheus 文本格式即可）、请求级追踪 ID 贯穿 Host 与插件。

### 验收标准

- [ ] 消息往返 P99 在 RK3566 上有基线数据，并在 CI 中做回归阈值检查。
- [ ] 客户端断开后，插件侧任务在可配置超时内停止，相关句柄全部释放（泄漏检测脚本通过）。
- [ ] 单插件死循环/崩溃不影响 Host 路由；熔断后能自动恢复。
- [ ] 插件升级全过程零停机（或可配置的短暂 drain），失败可回滚到旧版本。
- [ ] 权限不足的插件无法读取未授权上下文，越权访问有审计日志。

---

## 5. P2 —— 生态与规模化

**目的**：让外部开发者能独立写插件、发布插件，并支持多节点部署。

### 交付物

1. **开发者体验**
   - 插件模板工程（`gradle init` 或独立模板仓库），一条命令生成可构建、可发布的骨架。
   - 文档站：API 参考、生命周期图、消息协议、发布流程、常见错误。
   - 本地开发容器：Host + 热重载插件（disable → 重新加载新版本）。
2. **插件仓库**
   - 插件索引（Maven 坐标 + `plugin.json` 元数据 + 校验和 + 兼容的 `apiVersion`）。
   - Host 侧 `plugin install <id>@<version>`，带签名校验与依赖解析。
3. **多语言插件**
   - 稳定 C ABI SDK（C header + Rust / C++ 绑定），非 Kotlin 插件也能接入。
   - 明确 ABI 版本策略与符号可见性。
4. **多节点**
   - 插件分发与版本一致性；节点间服务发现；按能力路由。
5. **性能与容量**
   - 基准套件：消息吞吐、调度抖动、内存增长、启动时间；给出各目标平台的参考数据。
   - 性能预算写进文档，回归即报警。
6. **能力插件（capability provider）**
   - 接口在 `keel-api`，实现按需安装；纯 C 动态库、稳定 ABI、可卸载，不引入第二个 K/N 运行时。
   - 首个示例：`keel-plugin-lua` 提供 Lua 中间件引擎，脚本插件以 `requires("lua")` 依赖它。
   - 后续同构接入 WASM、编解码、存储、认证等能力。设计见 [capability-design.md](capability-design.md)。

### 验收标准

- [ ] 外部开发者只读文档即可在一天内写出并发布一个插件（以真实第三方试用为准）。
- [ ] 插件市场能完成「搜索 → 安装 → 启用 → 升级 → 卸载」闭环。
- [ ] 一个非 Kotlin 插件能通过 C ABI 完成完整生命周期与消息往返。
- [ ] 两节点部署时，同一插件版本一致，路由与消息不串台。
- [ ] 安装 / 卸载 capability 插件能启用 / 停用对应引擎；缺失时依赖它的插件拒绝启用且错误清晰。

---

## 6. 与现有 `backend/modules` 的迁移映射

拆分原则：**能力留在 Host，业务进插件**。

| 现有模块 | 去向 | 说明 |
| --- | --- | --- |
| `modules/protocol` | `keel-protocol`（`keel-api` 之上） | 跨插件 DTO / 信封，保持与现有契约兼容 |
| `modules/core` | `keel-host` 内部 | classpath、日志、文件存储、HTTP 客户端等基础设施 |
| `modules/codecs` | `keel-plugin-media-codecs` | 图像编解码依赖 C 静态库，适合独立发布与升级 |
| `modules/server` | Host + 若干系统插件 | 路由/拦截器/会话留 Host；用户、权限、文件、媒体、番组等按业务拆插件 |
| `modules/local` | `keel-plugin-local-service` | 本机 dispatcher 与指标采集 |
| `modules/client` / `ui` | 不在本仓库 | 客户端与渲染栈不参与插件化 |
| `modules/app-desktop` | `keel-host` + `keel-launcher` | 入口拆分：开发用 Host，发行用 Launcher |

迁移顺序建议：`codecs`（边界清晰）→ `local`（依赖少）→ 媒体库与文件管理（有状态）→ 用户 / 权限（影响面大）。

---

## 7. 开放问题（进入 P0 前需拍板）

1. 泰山派上自建 Maven 的地址、仓库路径、鉴权方式与备份策略（DEMO 的硬前置）。
2. Launcher 在三个平台能否内存加载插件动态库；若不能，解包目录放在哪、何时清理。
3. 插件 `id` 是否允许点分命名空间（`system.users`），以及和目录名、Maven `artifactId` 的对应规则。
4. `plugin.json` 的 `apiVersion` 语义：取 Host 主版本，还是独立的 ABI 版本号。
5. 插件之间是否允许同步调用；若允许，默认超时与「调用链最大深度」的取值。
6. 配置格式统一为 JSON 还是允许 YAML（现有后端用 `application.yaml` + 内嵌 JSON 默认值）。
7. 是否需要「插件只读 Host 时间 / 随机数 / 环境」等基础能力的最小面，避免每个插件自带一份实现。

---

## 8. 里程碑依赖关系

```
DEMO ──> P0 ──> P1 ──> P2
  │       │       │
  │       │       └─ 依赖：消息协议稳定、插件生命周期稳定
  │       └─ 依赖：DEMO 证明运行时安全、Maven 可用
  └─ 失败则回到架构评审：进程隔离（架构 A）或混合（架构 C）
```

**门禁**：DEMO 的验收清单没有全绿之前，不开始 P0 的功能开发；P0 的「插件模板 + 一条路由」跑不通之前，不拆现有业务模块。
