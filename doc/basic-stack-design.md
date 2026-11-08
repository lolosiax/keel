# Keel 基础栈设计：database / rbac / basic-biz

> 本文回答一件事：把 `backend/` 里已经跑通的 **RBAC + 注解式 Router + 请求上下文 + 会话/用户体系**
> 搬进 Keel 时，四个包分别该做成什么形态、边界怎么切、先做什么后做什么。
>
> 相关约束见 [../AGENTS.md](../AGENTS.md) §5 / §6、[abi-design.md](abi-design.md)、
> [authz-design.md](authz-design.md)、[web-design.md](web-design.md)、[codegen-design.md](codegen-design.md)。
> 与代码冲突时以代码为准，并回头修本文。

---

## 0. 结论

| 问题 | 结论 |
| --- | --- |
| `keel-plugin-database` / `keel-plugin-database-mariadb` 是插件吗 | **不是 native 插件，已定名 `keel-database-api` / `keel-database-mariadb`**。前者是 KMP klib（ABI + 插件侧门面），后者是 `kind: capability` 的能力包（有 `plugin.json`，没有 K/N 运行时）。Host 侧加载器放在 `keel-host`，不单独立项。 |
| `keel-plugin-rbac` / `keel-plugin-basic-biz` 是插件吗 | **是** native 插件。默认以 `.keel` 交付，按「`-api` 契约库 + `impl` 插件」两段式；内嵌只作调试 / 单文件发行选项。 |
| RBAC 与 biz 合并还是保留 | **保留两者**，依赖方向单向 `biz → rbac-api`。交叉点用「**绑定表写归 biz、读归 rbac**」裁决，不复制同一份逻辑。 |
| DB 走插件还是库 | **两者都不是**：Host 预留 C ABI，驱动做成 `kind: capability` 的 `.keel` 能力包；业务插件经 C 函数指针同步调用，不经过 Host、不走事件。 |
| 登录态与权限强制点放哪 | **必须放 Host**。Host 只持有 `Authenticator` / `Authorizer` 两个 SPI + 缓存 + fail-closed，不持有任何业务表。 |
| 现在就能做吗 | 不能直接写业务。真插件形态依赖 router KSP / `@ModuleService` codegen / `Config` / 插件→Host 事件 / `ctx.scope`，这些目前都是空的（见 §1.3）。先补 **M1 运行时前置**，再在 M2 落 rbac 与 biz。 |

一句话：**契约在 core，驱动做成能力包，数据在各插件，强制点在 Host，发布形态默认 `.keel`。**

---

## 1. 现状对照

### 1.1 backend 已经有的东西

| 能力 | 落点 | 关键事实 |
| --- | --- | --- |
| 权限规则解析 | `modules/server/.../service/permission/Permissions.kt` | 纯函数：`*` / `a.b.c` / `a.b.*`，`+` 可省，**负号优先，未命中拒绝** |
| 默认权限注册表 | `resources/permissions.yaml` + `PermissionRegistry.kt` | DB 某组无规则时才回退；`extends` 启动时同步进 `sys_role.parent_id` |
| 判定侧解析 | `PermissionResolver.kt` | 组链（`parent_id` 递归）+ 用户直授；`owner` 直接 `+*` |
| 管理侧读写 | `PermissionService.kt` | 保存 = 软删 + 全量插入，跑在同一事务 |
| 请求期缓存 | `util/session/UserInfo.kt` | 用户实体 / 角色 / 规则在**一次请求内只查一次** |
| 权限强制点 | `RouterProcessor` 生成 `call.requirePermission(...)` → `Context.hasPermission` | 未通过 403；`@Permission` 不标 = 只要求登录 |
| 会话 | `SessionManager` + `sys_sessions` | token 就是会话 id；内存缓存 + 定期落库；`Authorization: Bearer <id>` |
| 登录 / 注册 | `LoginService` + `UserService` | bcrypt(cost=10)；验证码自研；注册默认绑 `user` 组 |
| 用户 / 组接口 | `controller/system/*` | 路径与出参形状**以前端 `web/src` 为准** |

### 1.2 Keel 已有的契约面

| 契约 | 现状 |
| --- | --- |
| `web/Annotations.kt` | `@Controller` / `@GET` / `@Path` / `@Query` / `@Header` / `@Body` / `@Permission` 已有 |
| `web/Context.kt` | `plugin` / `request` / `pathParameters` / `userId` / `user` / `data` / `hasPermission`（**非 suspend**） |
| `web/RouteDescriptor.kt` | `method` / `path` / `routeId` / `permissions` |
| `auth/Authorizer.kt` | `fun authorize(userId: String?, permission: String): Boolean`（**非 suspend**） |
| `HostRoutes.kt` | 线性匹配 + `{name}` 段；**没有权限校验** |
| `HostHttp.kt` | 只有 `/healthz`、`/ping` 与 catch-all；**没有登录态** |
| `PluginContextImpl.kt` | `server` / `modules` / `config` / `scope` 都是 `error(...)` |

### 1.3 落差（做基础栈之前必须补的）

按阻塞程度排序（第 1 / 2 / 3 / 4 / 6 项已随 M1 落地，最新状态见 §12；下面是立项时的落差快照）：

1. **`keel-router-ksp` 是空处理器** —— 插件写不了注解路由，基础栈的 HTTP 面不存在。
2. **`ModuleDirectory` / `ServiceCaller` / `@ModuleService` codegen 未实现** —— biz → rbac、第三方 → biz/rbac 的跨插件调用不存在。
3. **`Config` 未实现** —— 数据库连接串、缓存 TTL、白名单都无处读。
4. **`ctx.scope` 未实现** —— 会话定时落库、验证码清理这类后台任务没有合法的执行位置。
5. **插件 → Host 的事件发送 / 反向请求 API 未实现** —— 登出 / 改权限后无法让 Host 清缓存，
   权限目录与 `PluginStorage` 也没法向 Host 取。
6. **Host 没有登录态与权限强制点** —— 有会话也传不进 `Context`。
7. **`Authorizer` / `Context.hasPermission` 是同步签名** —— 跨运行时实现需要 suspend 或改成快照。

> 这不是「设计没想清楚」，而是**顺序问题**：基础栈是第一批真实业务插件，正好把这些空洞全部踩实。
> §12 把它们拍成了有依赖关系的里程碑。

---

## 2. 四种形态与命名规则

Keel 里「插件」已经有事实上的三种形态（见 [capability-design.md](capability-design.md) §3），本文再加一种：

| 形态 | 产物 | 有 K/N 运行时 | 可部署 | 典型用途 |
| --- | --- | --- | --- | --- |
| `native` | K/N 共享库 | 是，独立运行时 | 是（`.keel`） | 业务插件 |
| `capability` | 纯 C 共享库 | 否 | 是（`.keel`，`kind: capability`） | Lua / 编解码 / 驱动 |
| `embedded` | 静态链进 Host 的 Kotlin 模块 | 与 Host 同一个 | 否（随 Host 发布） | 标准发行版的基础栈 |
| `library` | 普通 KMP 库 | 取决于链接方 | 否 | 契约、驱动、工具 |

**命名规则（建议写进 AGENTS.md）**：

- `keel-plugin-<id>`：**一定能被 `PluginLoader` 加载**——有 `plugin.json`、有 `entry`、有 `lib/<target>`。
- 其余一律 `keel-<能力>`：如 `keel-database-api`、`keel-plugin-rbac-api` 之外的 `keel-permission`。
- 一个目录如果叫 `keel-plugin-xxx` 却没有 `plugin.json`，是最容易误导后来者的坑，不要这么命名。

> 定名：用户提出的 `keel-plugin-database` / `keel-plugin-database-mariadb` 落成
> **`keel-database-api` / `keel-database-mariadb`**；`keel-plugin-rbac` / `keel-plugin-basic-biz` 保留原名。

---

## 3. 模块图

```
                       ┌──────────────────────────── Host ────────────────────────────┐
                       │  HTTP(Ktor) · 路由表 · 中间件 · 登录态 · 权限强制点 · 权限目录聚合 │
                       │  Authenticator SPI ←── basic-biz    Authorizer SPI ←── rbac     │
                       │  database 能力加载器：按 scheme 选 .keel，open() 后下发 C ABI 指针 │
                       └───────┬──────────────────────────────────────────────┬─────────┘
                               │ native                                native │
             ┌─────────────────▼─────────────────┐          ┌─────────────────▼─────────────────┐
             │ keel-plugin-basic-biz             │          │ keel-plugin-rbac                  │
             │ 用户 / 会话 / 登录 / 注册 / 头像   │  ──────▶ │ 组 / 规则 / 解析 / 管理接口        │
             │ 菜单 / 运行时设置                  │ rbac-api │ permissions.yaml / 权限目录        │
             └───────────────┬───────────────────┘          └───────────────┬───────────────────┘
                             │                                              │
                             │   同步 C 函数指针（不经过 Host，不进 K/N 运行时）  │
                             └──────────────┬───────────────────────────────┘
                                            ▼
                      ┌──────────────────────────────────────────┐
                      │ keel-database-mariadb.keel  (capability) │  libmariadb + 池 + 事务表
                      │  由 Host 按 database.url 的 scheme 选中   │  纯 C，可 dlclose / 热替换
                      └──────────────────────────────────────────┘
                                            ▲
                      ┌─────────────────────┴────────────────────┐
                      │ keel-database-api (klib)                  │  ABI 头 + 插件侧 Kotlin 门面
                      │ 业务插件唯一的查库依赖                     │  不含驱动、不含加载器
                      └──────────────────────────────────────────┘

契约库：keel-api（core）· keel-plugin-rbac-api · keel-plugin-basic-biz-api · keel-database-api
```

依赖方向（禁止反向）：

```
keel-api  ←  keel-database-api（ABI 头 + 插件侧门面，不含驱动、不含加载器）
keel-api  ←  keel-host（能力包加载器）
keel-api  ←  keel-plugin-rbac-api      ←  keel-plugin-basic-biz-api
keel-api  ←  keel-plugin-rbac-api      ←  keel-plugin-rbac
keel-api / rbac-api / biz-api / database-api  ←  keel-plugin-basic-biz
keel-database-mariadb 只依赖 keel-database-api 的 ABI 头，不依赖任何 Kotlin 工件
```

`keel-plugin-rbac` **不得**依赖 `basic-biz-api`；`basic-biz` 可以依赖 `rbac-api`。

---

## 4. 包的定位

### 4.1 `keel-database-api`（KMP klib：业务插件唯一的查库依赖）

不含驱动、不含 Host 加载器，只回答「怎么调用驱动」：

- `src/nativeCpp/keel_database.h`：稳定的 C ABI（§6.5），Host 与能力包共同遵守；
- `commonMain`：`KeelDatabase` / `Row` / `Tx` / `DbContext` / `DatabaseException`，
  以及参数与行的纯 Kotlin 类型；`DbContext` 在 `keel-api` 的 `Context` 之上加 `db` / `transaction`
  （见 §8.5）；
- `nativeMain`：cinterop + 一个 `internal` 的 C ABI 客户端，把 `KeelPluginApi` 里的函数表指针
  包成上面的 Kotlin 类型；
- **公开面只有 Kotlin 类型**：cinterop 的 `CPointer` 不出现在任何 public 签名里，
  业务插件不需要 `enableCInteropCommonization`，也不需要认识 `keel_database.h`；
- 错误映射：未连上 → 503，SQL 失败 → 500，参数非法 → 400。

它是独立顶层构建（与 `keel-api` 同级），依赖 `keel-api`（为了 `Context`）。
**它不是 `.keel`**：klib 是要被业务插件编译期链接的，放进 `plugins/` 没有意义。

### 4.2 Host 侧能力加载器（放在 `keel-host` 内）

不单独立项，避免再造一个「名字像插件、其实是库」的目录：

- `keel-host/src/nativeMain/.../host/capability/`：扫描 `kind: capability` 的 `.keel`，
  校验 manifest / ABI，`dlopen`、`dlsym("keel_capability_entry")`；
- 按 `provides` / `schemes` 匹配，`open(config)` 后把函数表与句柄填进 `KeelHostApi`，退出时 `close`；
- 通用部分（发现、校验、生命周期）与具体能力无关，后续 Lua / 编解码能力复用同一加载器；
  只有 DB 这一段需要认识 `KeelDbConfig`。

### 4.3 `keel-database-mariadb`（`.keel` 能力包）

- `plugin.json`：`kind: capability`、`provides: ["database"]`、`schemes: ["mysql","mariadb"]`；
- 纯 C/C++ 动态库（或 Rust cdylib），链接 `libmariadb`，实现 `keel_database.h` 的全部函数；
- 内部持有连接池、事务表、错误缓冲；线程安全，可被多个插件 Worker 并发调用；
- 裸 TCP 鉴权预检（从 backend 的 `MySqlAuthProbe` 迁移）——避免驱动在鉴权失败时把进程带走；
- MariaDB 方言注意项：时间列统一 `kotlin.time.Instant`、不要给列加 `_bin` 排序规则、
  不用 `CrudRepository` 自带的 `insert/update`（多语句预处理会 1064）、不支持 `IN (list)`。

用户把它丢进 `plugins/`，Host 按 `database.url` 的 scheme 选中并 `open`；换成 MySQL / PgSQL 时
只换这个包，业务插件与 `keel-database-api` 都不改。

### 4.4 `keel-plugin-rbac` + `keel-plugin-rbac-api`

| 部分 | 内容 |
| --- | --- |
| `-api`（库，所有插件可依赖） | `RbacReader` / `RbacAdmin` / `PermissionCatalog` 接口 + DTO + `ROLE_ID_*` / `OWNER` 常量 + 规则串语法说明 |
| `impl`（native 插件） | 表、规则解析、`permissions.yaml`、管理路由、`Authorizer` 实现 |
| 不拥有 | 用户实体、会话、登录、菜单、设置 |

### 4.5 `keel-plugin-basic-biz` + `keel-plugin-basic-biz-api`

| 部分 | 内容 |
| --- | --- |
| `-api`（库） | `UserDirectory` / `UserAdmin` / `AccountAdmin` / `SettingApi` 接口 + DTO（`UserRow` / `UserListRow` / `LoginResult` / `AuthzSnapshot` 等） |
| `impl`（native 插件） | `sys_user` / `sys_sessions` / `sys_cache` / `sys_user_roles` / `view_user_role`、登录注册验证码、头像、菜单配置、运行时设置 |
| 不拥有 | 组定义、权限规则、权限判定 |

---

## 5. RBAC 与 basic-biz 的交叉处理

### 5.1 为什么不是「合并到 biz」

合并确实更省事：一个插件、一个连接池、没有跨插件写。但它把三件事绑死在一起：

1. `@Permission` 的判定实现与「用户怎么登录」绑死，换授权模型要动 biz；
2. 不用登录体系的部署也得背 RBAC 的表；
3. 第三方只想管角色权限，却被迫依赖整个用户/会话/头像/设置插件。

### 5.2 为什么也不是「只留 rbac」

登录、注册、会话、头像、设置都是身份生命周期，不是授权。把它们塞进 rbac 会让 rbac 变成新的巨石。

### 5.3 裁决：两个插件，一条单向依赖 + 一张表的读写分离

**依赖方向**：`basic-biz → rbac-api`（**永不反向**）。

**交叉点逐条裁决**：

| # | 交叉场景 | backend 现状 | Keel 裁决 |
| --- | --- | --- | --- |
| 1 | 建用户 / 注册要绑默认组 | `UserService.create` 先插 `sys_user` 再插 `sys_user_roles` | **biz 写绑定表**（与用户同插件，可选单事务）；默认组通过 `RbacReader.roleByType("user")` 取，取不到回落常量 `ROLE_ID_USER` |
| 2 | 用户列表要显示组名 | `view_user_role` | 视图归 **biz**（它同时读 `sys_user` 与 `sys_role`）；rbac 不依赖该视图 |
| 3 | `/user/myRole` 要角色 + 生效规则 | biz 调 `PermissionService` | biz 调 `RbacReader.roleOf` + `RbacReader.effectiveRules`，一次请求内各缓存一次 |
| 4 | 删用户要删绑定 | `UserService.delete` 软删绑定 | 同 #1，biz 写绑定表 |
| 5 | 删组要判断「使用中」 | `RoleService.destroy` 统计绑定数 | rbac **只读**查 `sys_user_roles`；这是 rbac 唯一的跨表读 |
| 6 | 用户直授权限 | `/user/permission/*` | 归 **rbac**（表 + 接口）；biz 不碰 |
| 7 | 前端 `isAdmin` / `isOwner` | `UserInfo.roleType` | Host 的 `UserSnapshot.roles` 带组 type；biz 的 `myRole` 同值返回 |
| 8 | 权限节点目录 | `RouterProcessor` 生成 | 每个插件注册自己的节点，Host 聚合，rbac 的 `/permission/catalog` 从 Host 取（见 §7.5） |
| 9 | 组的展示名给用户编辑页 | `/role/list` | 归 **rbac** 的 HTTP 路由；biz 的用户编辑页直接调它 |

**一句话规则**：
> 谁负责这个实体的生命周期，谁写它；判定侧只读。
> 用户的生命周期在 biz，所以 `sys_user_roles` 的写归 biz；授权的生命周期在 rbac，所以角色/规则归 rbac。

### 5.4 部署形态选项（不改代码）

如果发行版希望「少一个插件、少一个连接池」，可以退化为 **两个契约、一个实现**：

- 发布单元只有一个 `keel-plugin-basic-biz`；
- 它同时 `@ModuleServiceProvider` 实现 `RbacReader` / `RbacAdmin` 与 `UserDirectory` / `UserAdmin`；
- 第三方依旧只依赖 `rbac-api` / `biz-api`，`ctx.modules.rbac` 仍可用；
- 代价：rbac 不能单独替换，`requires("rbac")` 变成同一插件的两个服务。

**默认仍按两个插件设计**；§5.4 只是发行时的裁剪开关，不构成第二套架构。

---

## 6. 数据层：Host 预留 C ABI，驱动做成能力包

### 6.1 要解决的两个问题

1. **用户装的是 MariaDB、MySQL 还是 PgSQL，Host 事先不知道**——驱动不能编进 Host，也不能编进业务插件；
2. **业务插件需要同步、低延迟地查库**——不能每条 SQL 走一次事件往返（事务、N+1、结果集类型都会退化）。

两者合起来指向同一个形态：**驱动是可插拔的能力包，能力的实现是纯 C 动态库，Host 只预留 ABI。**

### 6.2 三个产物

| 产物 | 形态 | 内容 | 谁依赖 |
| --- | --- | --- | --- |
| `keel-database-api` | **KMP klib** | C ABI 头 `keel_database.h` + 插件侧 Kotlin 门面（`KeelDatabase` / `Row` / `Tx` / `DbContext`） | 所有要查库的业务插件 |
| Host 能力加载器 | `keel-host` 内的模块 | 发现 `kind: capability`、`schemes` 匹配、ABI 校验、`open` / `close` | Host |
| `keel-database-mariadb` | **`.keel` 能力包**（`kind: capability`） | 纯 C/C++ 动态库（或 Rust cdylib），链接 `libmariadb`；实现 `keel_database.h` | 用户丢进 `plugins/` |
| `keel-database-pgsql`（后续） | 同上 | 同一份 ABI 的 PgSQL 实现 | 用户按需丢 |

`keel-database-api` **不含任何驱动**，也不含 Host 加载器；它就是业务插件查库时要链接的那个 klib。
`keel-database-mariadb` 不含任何 Kotlin，只含「怎么连 MariaDB、怎么把结果集填进 ABI 结构」。

> 命名上不再有裸的 `keel-database`：**`-api` 是插件侧契约，Host 侧加载器放进 `keel-host`**，
> 避免出现一个「既像 API 又像实现、既不像插件又不像库」的中间产物。
> 用户装 MySQL 就丢 `keel-database-mysql`，装 PgSQL 就丢 `keel-database-pgsql`，业务插件一行不改。

### 6.3 为什么插件可以同步调用而不违反线程模型

关键区别在于**调用的是谁**：

- 如果 `keel_db_query` 实现在 **Host Kotlin** 里，插件 Worker 调用它就会进入 Host 运行时——禁止；
- 现在实现落在**能力包的纯 C 动态库**里，插件 Worker 调用的是 C 函数指针，全程不进入任何 Kotlin 运行时。

所以请求路径是：

```
插件 Worker (K/N) --C 函数指针--> keel-database-mariadb (纯 C) --> libmariadb --> MariaDB
```

没有 Host 参与、没有事件泵、没有序列化；延迟就是一次数据库往返。这也是它比「DB 做成 native 插件」
更适合列表 / 事务场景的根本原因。

### 6.4 Host 怎么知道该开哪个驱动

- 能力包的 `plugin.json` 声明：

```json
{
  "id": "database.mariadb",
  "kind": "capability",
  "version": "1.0.0",
  "abiVersion": 1,
  "entry": "lib/<target>/keel_capability_mariadb",
  "provides": ["database"],
  "schemes": ["mysql", "mariadb"]
}
```

- Host 读 `database.url` 的 scheme（`mysql://` / `mariadb://` / `postgres://`），
  在已加载的能力包里找 `schemes` 命中的那个；
- 找到 → `open(config)`，把返回的 `KeelDbApi*` + `KeelDbHandle` 填进 `KeelHostApi`，
  业务插件启动时直接拿到；
- 找不到 → 日志写清「scheme=postgres 没有对应的 database 能力包」，依赖它的插件**拒绝启用**；
- 多个命中 → 要求 `database.capability` 显式指定，否则拒绝启用（不允许静默挑一个）。

`provides: ["database"]` 让业务插件只写 `requires: ["database"]`，不需要知道用户装的是哪个驱动。

**`requires("database")` 要同时做两件事**：写入 `plugin.json`，并给 Gradle 加上
`keel-database-api` 依赖（与 [codegen-design.md](codegen-design.md) §3.1 里 `requires("queue")`
自动加 `-api` 依赖是同一机制）。否则子模块只声明了依赖、却拿不到可调用的 Kotlin 门面，
表现就是「编译期没有 `db` 可用」。

### 6.5 ABI（`keel-database-api/src/nativeCpp/keel_database.h`）

```c
#define KEEL_DB_ABI_VERSION 1u

typedef enum KeelDbType {
    KEEL_DB_NULL = 0, KEEL_DB_TEXT = 1, KEEL_DB_INT = 2,
    KEEL_DB_FLOAT = 3, KEEL_DB_BOOL = 4, KEEL_DB_BLOB = 5
} KeelDbType;

/* TEXT/BLOB 原始字节；INT 8 字节小端补码；FLOAT 8 字节小端 IEEE-754；BOOL 1 字节 */
typedef struct KeelDbValue {
    uint32_t type;
    uint32_t len;
    const uint8_t *bytes;
} KeelDbValue;

typedef struct KeelDbConfig {
    uint32_t struct_size;
    const char *url;                  /* mysql://host:port/db */
    const char *username;
    const char *password;
    uint32_t max_connections;
    uint32_t connect_timeout_ms;
} KeelDbConfig;

typedef struct KeelDbHandleTag KeelDbHandle;   /* 不透明：池或事务 */
typedef struct KeelDbResultTag KeelDbResult;   /* 不透明结果集 */

typedef struct KeelDbApi {
    uint32_t abi_version;
    uint32_t struct_size;

    KeelStatus (*exec)(KeelDbHandle *handle, const char *sql, uint32_t sql_len,
                       const KeelDbValue *params, uint32_t param_count,
                       uint64_t *affected);
    KeelStatus (*query)(KeelDbHandle *handle, const char *sql, uint32_t sql_len,
                        const KeelDbValue *params, uint32_t param_count,
                        KeelDbResult **out);
    int32_t (*next)(KeelDbResult *result, const KeelDbValue **row, uint32_t *column_count);
    void (*result_free)(KeelDbResult *result);

    KeelStatus (*begin)(KeelDbHandle *pool, KeelDbHandle **tx);
    KeelStatus (*commit)(KeelDbHandle *tx);
    KeelStatus (*rollback)(KeelDbHandle *tx);

    KeelStatus (*last_error)(KeelDbHandle *handle, char *buf, uint32_t capacity);
} KeelDbApi;
```

相对早期草案的修正：**事务不是 `uint64` 编号，而是独占连接的事务句柄**。
`exec` / `query` 的第一个参数既可以是池（自动借还连接），也可以是 `begin` 返回的
事务句柄（固定那条连接）；否则「`begin` 之后的查询走哪条连接」无法表达。
`commit` / `rollback` 结束时释放事务句柄。

内存与线程规则（已写进头文件）：

- **参数**由调用方拥有，只在本次调用期间有效；
- **结果集 / 错误串**由能力包拥有，调用方用完调 `result_free` / `last_error`；
- 所有函数可在任意线程调用，能力包内部负责连接池与互斥；
- `struct_size` 只追加字段，`abi_version` 破坏性变更时 +1。

能力包入口复用 `keel_abi.h` 的通用描述符：

```c
const KeelCapabilityDescriptor *keel_capability_entry(void);
```

`open(host, config, config_size, &instance, &api)`：`config` 指向 `KeelDbConfig`，
`config_size` 不匹配时返回 `KEEL_ERR_UNSUPPORTED`；`api` 类型是 `KeelDbApi *`。
`instance` 就是池句柄，Host 原样填进 `KeelHostApi.database_handle`。

### 6.6 Host 与插件的接线

- `keel_abi.h` 的 `KeelHostApi` **追加**两个字段（`struct_size` 保证旧插件忽略）：

```c
void *database;            /* 指向 KeelDbApi 函数表；类型在 keel_database.h */
void *database_handle;     /* 能力包返回的连接池句柄 */
```

- **用 `void *` 而不是 `struct KeelDbApi *`**：`keel-abi` 的 cinterop 不认识 DB 结构体，
  两个 cinterop 包对同一结构体的类型身份也不稳定（`KeelQueueTag` 那道占位定义就是为此）；
  插件侧在 `keel-database-api` 里把它转成 `CPointer<KeelDbApi>`，公开面仍然只有 Kotlin 类型；
- Host 的 C 层在 `keel-host` 的能力加载器里 `dlopen` 能力包、校验 `abi_version`、`open(config)`，
  把函数表与句柄填进 `KeelHostApi`；
- `keel_plugin_bootstrap` 原样拷进 `KeelPluginApi`；
- 插件的 `worker_main` 在第一次进入 Kotlin 时读 `api->database`，经 `PluginCapabilities`
  交给 `keel-database-api`（业务插件只调 `currentDatabase()`）；
- **Host Kotlin 侧也需要这份 ABI**：能力加载器要构造 `KeelDbConfig`，所以 `keel-host`
  额外有一个 `keeldatabase` cinterop（包名 `moe.lolosia.keel.host.database.c`）；
  它只用于加载 / `open`，Host 自己不执行业务 SQL。

> cinterop 只会为「按值使用」的结构体生成 Kotlin 类型；`KeelCapabilityDescriptor`
> 只被指针引用时不会生成。Host 侧因此直接复用 `keel-api` 的 `keelabi` cinterop
> （`keel_abi.h` 是它的主头文件），DB 包只负责 `KeelDbConfig` / `KeelDbApi`。

### 6.6.1 M0 落地状态

- `keel-database-api`：头文件、Kotlin 门面（`KeelDatabase` / `Row` / `QueryResult` /
  `DbValue` / `currentDatabase()`）已完成，`select 1` 在三目标上可编译；
- `keel-host`：`host/capability/` 的能力包发现、scheme 匹配、`open` / `close`、
  `requires: ["database"]` 校验已完成；
- `keel-database-mariadb`：包结构、ABI、连接池生命周期已完成；**尚未链接 libmariadb**，
  当前后端只响应 `select 1`，其余语句返回 `KEEL_ERR_UNSUPPORTED`，
  用于先打通加载与调用链路；
- `database` 段在 M0 从环境变量读取（`KEEL_DB_URL` / `KEEL_DB_USER` /
  `KEEL_DB_PASSWORD` / `KEEL_DB_MAX_CONNECTIONS` / `KEEL_DB_CONNECT_TIMEOUT_MS` /
  `KEEL_DB_CAPABILITY`），`Config` 运行时落地后改为 `application.yaml`。

### 6.7 事务与跨插件

- 单个插件内：`db.transaction { }` 就是 `begin/commit/rollback`，与 backend 的 `ctx.transaction` 同义；
- 跨插件：`tx` 是普通 `uint64`，理论上可以把句柄经由模块 API 传给另一个插件；
  **默认不这么做**——两个插件各自开事务，语义更清楚；
- 如果确实需要「biz 建用户 + rbac 绑角色」原子化，由发起方持有 tx 并显式传递（P2 再定接口），
  或者把这条链路收敛到一个插件里（§5.4 的合并部署形态）。

### 6.8 连接池与配置

```yaml
database:
  url: mysql://127.0.0.1:3306/keel
  username: keel
  password: "***"
  maxConnections: 8
  connectTimeoutMs: 3000
  capability: ""          # 可选；同 scheme 多实现时显式指定
```

- 池在能力包里，**全局一个**；`maxConnections` 是全局额度，不再是每插件额度；
- 连接池懒建、后台重连由能力包负责；Host 只在启动时 `open` 一次、退出时 `close`；
- 口令只出现在 Host 的主线配置里，能力包 `open` 时拿到，不落插件配置、不进日志；
- 数据库不可达不阻断 Host 启动：能力包 `open` 成功但首次连接失败时返回可重试状态，
  业务插件的查询按 `last_error` 返回 503（与 backend 的懒建池语义一致）；
- **M0 的实际读取入口是环境变量**（见 §6.6.1），上面这段 YAML 是 `Config` 运行时落地后的目标形态。

### 6.9 仓库层：sqlx4k 的 KSP 还能不能用

这是这套方案唯一需要额外投入的地方：

- 业务插件不再依赖 `sqlx4k-mysql`（驱动在能力包里），但可以只依赖 `sqlx4k` 的**类型面**；
- **先验证** `sqlx4k` 核心工件是否不含 Rust 静态库（只含 Kotlin 接口 / `ResultSet` / `RowMapper`）。
  若只含类型，就在 `keel-database-api` 里提供一个 `QueryExecutor` 实现，把 `fetchAll` / `execute` /
  `transaction` 落到 C ABI 上；`@Repository` 的 KSP 生成物不用改；
- 若核心工件也带 Rust 静态库、无法拆开，则用 `keel-repository-ksp`（从 sqlx4k 的处理器 fork 一份，
  只改 import 与执行接口）替代；
- 两者的公共约束不变：首参 `QueryExecutor`、方法 `suspend`、每个方法带 `@Query`、不支持 `IN (列表)`。

> M2 之前可以先用**手写仓库**（基本栈只有约 10 张表），把 KSP 适配放到 M3；
> 这样不会被一个构建期工程量卡住业务。

### 6.10 风险

| 风险 | 处理 |
| --- | --- |
| `libmariadb` 交叉编译到 linuxArm64 / mingw | M0 先打通；产物随能力包发布，业务插件零依赖 |
| ABI 内存所有权写错导致跨 CRT 释放 | 头文件里逐函数写明「谁分配、谁释放」；所有字符串用能力包的 allocator |
| 能力包不是线程安全的 | 入口自检 + 文档强制；Host 侧并发压测（两个插件同时查） |
| 能力包崩溃拖垮整个进程 | 纯 C 库仍在同进程内；P2 才考虑独立进程隔离 |
| `dlclose` 时还有连接 | `close()` 先断池再 `dlclose`；Windows 上句柄未释放会 `FreeLibrary` 失败 |
| 换驱动后 SQL 方言差异 | 业务 SQL 只用 `@Query` 的子集；方言相关写法集中在仓库层 |

### 6.11 与「DB 做成 native 插件」的对比

| 维度 | 能力包 + C ABI（本方案） | DB native 插件（事件往返） |
| --- | --- | --- |
| 查询延迟 | 一次 C 调用 + DB 往返 | 额外两次线程唤醒 + 序列化 |
| 事务 | 本地 `begin/commit` | 远程事务句柄 |
| 驱动隔离 | 每个驱动一个 `.keel`，可热替换 | 一个 K/N 插件，不可卸载 |
| 业务插件体积 | 只带 ABI 客户端 | 只带代理 |
| 代码生成 | 需要适配仓库层 | SQL 退化成字符串 |
| 结论 | **采用** | 不采用 |

---

## 7. Host 安全基座

### 7.1 两个 SPI（必须 suspend）

`authz-design.md` 的 `Authorizer` 是同步的；跨运行时实现做不了同步调用。改成：

```kotlin
/** 认证：token → 用户快照。实现方是 keel-plugin-basic-biz。 */
interface Authenticator {
    suspend fun authenticate(token: String): UserSnapshot?
}

/** 授权：userId → 授权快照。实现方是 keel-plugin-rbac。 */
interface Authorizer {
    suspend fun snapshot(userId: String): AuthzSnapshot

    /** 默认实现：按规则串判定；自定义授权模型可覆盖。 */
    suspend fun authorize(userId: String?, permission: String): Boolean =
        userId != null && Permissions.granted(snapshot(userId).rules, permission)
}

@Serializable
data class AuthzSnapshot(
    val roles: List<String>,
    val rules: List<String>,
    val owner: Boolean,
)
```

- `Permissions`（规则解析 / 匹配）进 `keel-api`，因为它是**线格式**，不是角色模型；
- `owner = true` 时 Host 与插件都直接放行，且忽略 `-` 规则（与 backend 一致）。

#### 7.1.1 SPI 注册：内建 subject

Host 调插件必须走事件，所以 SPI 也走事件，不依赖 `ServiceRegistry`：

- `plugin.json` 声明 `provides: ["authn"]` / `["authz"]`；Host 在启用时记下「SPI → 插件 id」；
- 同一个 SPI 只允许一个提供者，第二个声明直接拒绝启用并报冲突；
- Host 侧的实现就是一个 `HostPump.request(pluginId, subject, payload, timeout)`：

| subject | 请求 | 应答 |
| --- | --- | --- |
| `KEEL_SUBJECT_AUTHN` | `{ token }` | `{ user: UserSnapshot? }` |
| `KEEL_SUBJECT_AUTHZ` | `{ userId }` | `{ snapshot: AuthzSnapshot }` |

- 载荷用 CBOR；`KeelErrorPayload` 沿用现有错误信封；
- 提供者未启用 / 已停用 → Host 清缓存，受保护路由 fail-closed 503；
- 仍是 Host → 插件方向，复用现有 `HostPump.request`，M1 不用新造反向请求。

### 7.2 请求期快照

`UserSnapshot` 增加 `rules` / `owner`（或整体换成 `AuthzSnapshot` 嵌在 `Context` 里）：

```kotlin
@Serializable
data class UserSnapshot(
    val id: String,
    val username: String? = null,
    val roles: List<String> = emptyList(),
    val rules: List<String> = emptyList(),
    val owner: Boolean = false,
)
```

这样插件的 `ctx.hasPermission(node)` **保持同步、零往返**：用 `Permissions.granted(user.rules, node)` 本地判定。
Host 仍然只在分发前对**该路由声明的 `@Permission`**做一次判定（用的是同一份快照）。

> 这是对 `authz-design.md` 的一处修正：不把「每次 `ctx.hasPermission` 都回 Host」作为默认路径。
> 自定义授权模型如果不能表达成规则串，再提供 `suspend fun awaitPermission(...)`（P2）。

### 7.3 中间件顺序与白名单

Host 的请求链：

```
HTTP IN 日志 → 认证（Authenticator）→ 路由匹配 → 权限（Authorizer）→ 事件投递 → 插件 handler
```

- 只对 `/api/` 前缀要求登录；
- 白名单从配置读（`auth.whitelistExact` / `auth.whitelistPrefix`），默认沿用 backend 的清单；
- 白名单只免「没登录就 401」，**不免权限**；
- 未启用 `Authenticator` / `Authorizer` 时：
  - 公开路由照常；
  - 带 `@Permission` 的路由 **503**（fail closed），日志写清「未安装授权实现」。

### 7.4 缓存与失效

| 缓存 | 键 | TTL | 失效 |
| --- | --- | --- | --- |
| 认证 | token | 默认 30s | 登出 / 改密 / 删用户 → `AUTH_INVALIDATE` 事件 |
| 授权 | userId | 默认 30s | 角色 / 规则变更 → `AUTHZ_INVALIDATE` 事件 |

- TTL 必须以配置暴露，默认值在「每请求一跳」与「登出后仍可用」之间取折中；
- 在 M1 第 4 项（插件 → Host 事件）落地前，TTL 必须压到 **0–2s**，否则登出不生效；落地后可用默认 30s + 失效事件；
- 插件被停用：清空该插件的缓存，受保护路由立即 503。

### 7.5 权限节点目录的聚合

`@Permission` 现在是编译期扫出来的（backend 的 `RouterProcessor`）。插件化后每个插件各扫各的，
Host 必须聚合，否则管理界面的「权限选择器」看不到别的插件的节点。

方案：

- `keel-router-ksp` 为每个插件生成 `List<PermissionNode>`（`node` / `title` / `controller` / `function`）；
- 随 `ROUTE_REGISTER` 一起发给 Host（`RouteDescriptor` 外再带一份节点表，或独立 `PERMISSION_REGISTER` 事件）；
- Host 存 `node → (pluginId, title)` 的聚合表；
- rbac 的 `/api/permission/catalog` **向 Host 发反向请求**（`KEEL_SUBJECT_PERMISSION_CATALOG`）取聚合结果；
- 同一节点被多个插件声明是**允许**的（OR 语义），标题冲突取首个并记 WARN。

**反向请求**（插件 → Host）是这套方案新增的最小能力：

```
插件 Worker: push(Host inbox, COMMAND{target=0, subject=CATALOG, correlation=N})
             → 在 Worker 上重入事件泵，处理其它事件，直到同 correlation 的 COMPLETE
Host 泵线程: drain 到 target=0 的 COMMAND → 处理 → 向 source 插件 inbox 回 COMPLETE
```

- 只在 Worker 线程调用；等待期间会继续分发其它事件（与 `ServiceCaller.callBlocking` 同一语义）；
- 有超时（默认 3s），超时后迟到的 COMPLETE 丢弃并告警一次；
- 同一机制也承载 §7.4 的 `AUTH_INVALIDATE` / `AUTHZ_INVALIDATE`（单向 post，不等应答）；
- M1 必须落地，否则 rbac 的权限目录与 biz 的失效通知都无从谈起。

### 7.6 会话解析放在 Host 的成本

`Authenticator` 是每请求一跳（缓存未命中时）。这也是「为什么不做成 Host 内嵌认证」的对照：

- 内嵌：零跳，但 Host 要持有 `sys_sessions` 与用户表，容器开始懂业务；
- 插件：一跳 + 缓存，Host 保持干净。

默认用插件形态；内嵌只作调试 / 单文件发行选项（§11.3），两种形态共用同一份实现。

### 7.7 Host 提供的存储 API

biz 的头像、未来的附件都需要落文件，但插件不能直接写 Host 工作目录（AGENTS §6），
也不该为了一个头像引入通用存储能力包。定案：**Host 提供最小存储 API，按插件命名空间隔离**。

```kotlin
interface PluginStorage {
    suspend fun read(path: String): ByteArray?
    suspend fun write(path: String, bytes: ByteArray)
    suspend fun delete(path: String)
}
```

- 挂在 `ctx.server.storage`（`KeelServer` 新增一个成员）；
- 根目录 `<workDir>/plugins/<pluginId>/`；`path` 是相对路径，拒绝 `..`、绝对路径与空段；
- 跨运行时同样走**反向请求**（`KEEL_SUBJECT_STORAGE`，操作 = read / write / delete）；
  不复用 C ABI：Host 的实现是 Kotlin，插件 Worker 不能同步调进来；
- 单对象上限默认 8 MB；v1 不提供目录列举（头像不需要）；
- 插件停用 / 卸载不删数据，重新启用后仍可读；
- 头像路径固定 `image/avatar/<userId>.<ext>`，与 backend 的布局一致。

> 如果将来出现大文件 / 流式需求，再升级成独立的能力包（`keel-capability-storage`），
> `PluginStorage` 的签名不变。

---

## 8. Router 与 Context：从 backend 迁过去

### 8.1 注解与生成物

注解同名同义（`@Controller` / `@GET` / `@POST` / `@PUT` / `@DELETE` / `@PATCH` / `@Path` / `@Query` /
`@Header` / `@Body` / `@Permission`），控制器代码只改 import。

`keel-router-ksp`（现在是空处理器）要生成：

1. `RouteDescriptor` 列表与 `PermissionNode` 列表；
2. `dispatch(ctx, routeId)`：按类型绑定参数并调用 handler；
3. 返回值适配：`@Serializable` → JSON + 信封；`HttpResponse` → 原样；`ResponseBody.Stream` → 流；
4. `registerGeneratedRoutes(router)`：Worker 启动时调用；
5. `@Permission` 提取进描述表，由 Host 在分发前校验。

M1 的 router KSP 可以先复用 backend 的 `RouterProcessor` 思路，但生成物必须只依赖 `keel.api.web`，**不能把 Ktor 类型带进插件**。

### 8.2 信封放插件侧（修正 web-design.md）

`web-design.md` §6 让 Host 的 `EnvelopeInterceptor` 统一包信封。插件化后不成立：

- Host 拿到的是 `HttpRouteResponse` 的字节流，要包信封必须反序列化再序列化；
- 插件已经知道返回类型，KSP 适配器直接包是零成本的；
- Host 还要区分 JSON / 文本 / 流，等于把 backend 的 MIME 判定再写一遍。

**改为**：生成的路由适配器负责 `{code,msg,data}`，规则与 backend 的 `EnvelopeInterceptor` 一致：

- JSON → 包；
- `text/plain` → 不包；
- 顶层已含 `code`/`msg`/`data` → 跳过；
- `HttpResponse(envelope = false)` → 跳过。

Host 只负责原样写回状态码、Content-Type 与字节。

### 8.3 错误契约

把 backend 的 `ApiException`、`ApiResult`、`EmptyResult`、`MessageResult` 迁到 `keel-api`（`web/` 下）：

- handler 抛 `ApiException(status, msg)` → 适配器输出 `{code: status, msg, data: null}` 与对应 HTTP 状态；
- 参数绑定失败 → 400；
- 未知异常 → 500，Host 与插件都记日志（带 traceId）。

### 8.4 参数绑定范围

与 backend 对齐，不扩大：

- `String` / `Int` / `Long` / `Double` / `Boolean` / `Uuid` / 枚举；
- `@Form` 的 `List<String>`、`@FilePart` 的 `List<UploadedFile>`（P1，需要 Host 侧 body 流与 multipart 解析）；
- 不做嵌套泛型绑定；超范围编译期报错，提示手写路由。

### 8.5 Context 四面映射

| backend `Context` | Keel | 说明 |
| --- | --- | --- |
| `ctx.call`（`ApplicationCall`） | **没有** | `ApplicationCall` 不跨运行时；用 `ctx.request`（方法/路径/query/headers/body/remoteAddress/traceId） |
| `ctx.data` | `ctx.data: MutableMap<String, Any?>` | 插件本地，请求结束释放 |
| `ctx.session` / `ctx.userId` / `ctx.user` | `ctx.userId` / `ctx.user: UserSnapshot?` | Host 在分发时从 token 解析并注入 |
| `ctx.db` / `ctx.transaction` | `DbContext`（`keel-database-api`） | 见下 |
| `ctx.hasPermission` | 同名，**同步** | 用 `ctx.user.rules` 本地判定 |

`DbContext` 放在 `keel-database-api`，避免所有插件都被迫认识 sqlx4k：

```kotlin
/** 有数据库的插件用的请求上下文；由插件自己的路由注入表按类型提供。 */
interface DbContext : Context {
    val db: QueryExecutor
    suspend fun <T> transaction(block: suspend (Transaction) -> T): T
}
```

- biz / rbac 的控制器与 `@Service` 方法首参写 `ctx: DbContext`；
- 没有数据库的插件继续用 `Context`，不引入 `keel-database-api`；
- `DbContext` 是请求级的：`db` 来自插件启动时建立的 `Datasource`，`transaction` 直接落到本地驱动。

> 服务层「首参透传 `ctx`」的约定不变；变的只是类型与 import。

---

## 9. 接口面：尽可能暴露

### 9.1 设计原则

1. **粗粒度、面向用例**，不暴露 SQL / 仓库 / 驱动类型；
2. 读写分离：`XxxReader` 给业务方热路径，`XxxAdmin` 给管理侧；
3. 所有 DTO `@Serializable`，只出现在 `-api` 工件里，跨插件用 CBOR；
4. 方法要么 `suspend`，要么纯计算；不传回调 / 函数类型（P2 再扩）；
5. 出错用 `KeelApiException`（带 status），不返回 `null` 表示「没权限」；
6. **HTTP 路由是api 的一个消费者**，不是唯一入口——第三方插件直接调接口，不走 HTTP。

### 9.2 `keel-plugin-rbac-api`

```kotlin
@ModuleService("rbac")
interface RbacReader {
    suspend fun roleOf(userId: String): RoleRef?
    suspend fun rolesOf(userId: String): List<RoleRef>
    suspend fun roleByType(type: String): RoleRef?
    suspend fun effectiveRules(userId: String): List<String>
    suspend fun rulesOfRole(roleId: Long): List<String>
    suspend fun rulesOfUser(userId: String): List<String>
    suspend fun listRoles(): List<RoleRef>
    suspend fun isOwner(userId: String): Boolean
}

@ModuleService("rbac.admin")
interface RbacAdmin {
    suspend fun pageRoles(pageNo: Int = 1, pageSize: Int = 10, keyword: String? = null): RolePage
    suspend fun createRole(req: RoleCreateReq, operatorId: String? = null): RoleRow
    suspend fun updateRole(req: RoleUpdateReq, operatorId: String? = null)
    suspend fun deleteRole(id: Long, operatorId: String? = null)
    suspend fun saveRoleRules(roleId: Long, rules: List<String>, operatorId: String? = null)
    suspend fun saveUserRules(userId: String, rules: List<String>, operatorId: String? = null)
    suspend fun bindRole(userId: String, roleId: Long, operatorId: String? = null)
    suspend fun unbindRole(userId: String, operatorId: String? = null)
}

@ModuleService("rbac.catalog")
interface PermissionCatalog {
    suspend fun nodes(): List<PermissionNode>
}
```

`RoleRef` 只带 `id` / `name` / `type` / `parentId`，不带审计列；
管理界面需要的时间/操作者走 `RolePage` 里的 `RoleRow`（字段名与既有 `/role/*` 出参一致）。
服务 Key 统一登记在 `RbacServices`（`rbac` / `rbac.admin` / `rbac.catalog`），
`ROLE_ID_*` / `ROLE_TYPE_*` 常量在同包的 `package.kt`。

### 9.3 `keel-plugin-basic-biz-api`

```kotlin
@ModuleService("basic.biz.user")
interface UserDirectory {
    suspend fun byId(id: String): UserRow?
    suspend fun byIds(ids: List<String>): List<UserRow>
    suspend fun page(pageNo: Int = 1, pageSize: Int = 10, keyword: String? = null): UserPage
    suspend fun search(keyword: String?, limit: Int = 10): List<UserRow>
}

@ModuleService("basic.biz.user.admin")
interface UserAdmin {
    suspend fun create(req: UserCreateReq, operatorId: String? = null): UserRow
    suspend fun update(req: UserUpdateReq, operatorId: String? = null)
    suspend fun delete(ids: List<String>, operatorId: String? = null)
    suspend fun changePassword(userId: String, origin: String, target: String)
    suspend fun saveAvatar(userId: String, fileName: String?, bytes: ByteArray, operatorId: String? = null)
    suspend fun bindRole(userId: String, roleId: Long, operatorId: String? = null)
}

@ModuleService("basic.biz.account")
interface AccountAdmin {
    suspend fun register(req: RegisterReq): UserRow
    suspend fun createSession(userId: String): SessionToken
    suspend fun revokeSession(token: String)
}

@ModuleService("basic.biz.setting")
interface SettingApi {
    suspend fun snapshot(): SettingSnapshot
    suspend fun setAllowHiddenFiles(allow: Boolean): SettingSnapshot
}
```

- `Authenticator` 的实现**不在** api 里暴露：它只注册给 Host，业务方不需要直接调；
- 登录 / 验证码是 HTTP 面（`/api/login` 等），不提供跨插件接口——别的插件不该替用户登录；
- `AccountAdmin.register` 给「邀请注册」「管理后台建号」这类场景；
- 服务 Key 统一登记在 `BasicBizServices`（服务 id 以 `basic.biz.` 开头，不占用更宽的 `biz` 命名空间）；
  `LoginResult` 只用于 HTTP 出参（字段名与既有 `/api/login` 一致），没有任何 Service 接口返回它。

### 9.4 版本与兼容

- `-api` 工件独立版本，声明兼容的 impl 范围（`plugin.json` 的 `requires` 后续带版本范围）；
- 加方法 = 次版本；改签名 / 删方法 = 主版本；
- DTO 加字段必须给默认值（CBOR 解码对缺失字段用默认值）；
- 跨插件载荷用 CBOR，不用 JSON（见 AGENTS §5）。

---

## 10. 数据表与资源归属

| 资源 | 归属 | 迁移来源 |
| --- | --- | --- |
| `sys_user` | biz | `sql/sys_user.sql` |
| `sys_sessions` | biz | `sql/sys_sessions.sql` |
| `sys_cache` | biz | `sql/sys_cache.sql` |
| `sys_user_roles`（**写**） | biz | `sql/sys_user_roles.sql` |
| `view_user_role` | biz | `sql/view_user_role.sql` |
| `sys_role` | rbac | `sql/sys_role.sql` |
| `sys_role_permission` | rbac | `sql/sys_role_permission.sql` |
| `sys_user_permission` | rbac | `sql/sys_user_permission.sql` |
| `permissions.yaml` | rbac（classpath 资源） | `modules/server/.../resources/permissions.yaml` |
| `sys_user_roles`（**只读**） | rbac | 同上，仅 `countByRoleId` 与组链解析 |
| 菜单 | 无表 | `/api/menu/config` 静态返回 `{"default": true}` |

- 建表脚本由**拥有方**插件随 `.keel` 的 `resources/sql/` 发布，启动时按版本执行（迁移器在 M2 落地）；
- 初始化数据（组 id 1/2/4/5）仍由 `sys_role.sql` 负责，因为代码里有固定引用；
- `permissions.yaml` 的 `extends` 同步仍由 rbac 在启动时执行（幂等）。

---

## 11. 装配与生命周期

### 11.1 启动顺序

```
Host 启动
  → 读主线配置（database / auth / authz）
  → 扫描 plugins/：先加载能力包（database.mariadb），校验 ABI，open(config)，拿到 KeelDbApi* + 句柄
  → 按 requires 拓扑加载 native 插件
  → 启用 rbac：建表/迁移 → 载入 permissions.yaml → 同步组继承 → 注册 Authorizer
  → 启用 biz：建表/迁移 → 注册 Authenticator
  → Host 标记录入完成；开始接流量
```

- **能力包先于 native 插件**：`provides: ["database"]` 必须先就绪，插件才能拿到 C 函数表；
- **顺序由 `requires` 决定**：`biz.requires = ["rbac"]`，两者都 `requires: ["database"]`；
- **没有匹配的能力包 → 依赖它的插件拒绝启用**（错误要写清 scheme 与已安装的包）；
- **能力包在但数据库不可达 → 插件照常启用**，查询返回 503（与 backend 的懒建池语义一致）；
- 任一插件启用失败：只停它自己，Host 与其它插件继续；受保护路由按 fail-closed 返回 503。

### 11.2 停用与卸载

- 停用 biz：清认证缓存；所有 `/api/**`（除白名单）立即 401；biz 的路由 503；
- 停用 rbac：清授权缓存；带 `@Permission` 的路由 503；公开路由与「只要求登录」的路由继续；
- 停用顺序：先 biz 后 rbac（依赖方先停）。

### 11.3 可选：内嵌形态（调试 / 单文件发行）

默认交付是 `.keel`。内嵌形态保留给两种场景：单文件发行（launcher）与本地调试（不起 dylib、断点更直接）。

内嵌插件不是 `dlopen` 出来的，Host 用一个本地注册表按同样顺序调用 `Plugin.onLoad` / `onEnable`；
路由直接进 `HostRoutes`；`ModuleDirectory` 直接返回本地实例（零代理）。
它**不是**主路径，也不承担「绕过运行时前置」的职责——`ModuleDirectory` / `Config` 等契约仍要按 M1 实现，
否则同一份源码打成 `.keel` 后会行为不一致。

---

## 12. 分阶段实施

> 里程碑是**门禁**关系，不是并行工作包：M1 没有全绿之前不写 rbac / biz 的业务代码。

### M0 —— 契约、C ABI 与能力包（不依赖任何跨运行时能力）

- `keel-api`：`Authenticator` / `Authorizer`（suspend + snapshot）、`UserSnapshot.rules/owner`、
  `Permissions`、`PermissionNode`、`ApiException` / `ApiResult`；
- `keel-database-api`（klib）：`keel_database.h`、插件侧 Kotlin 门面、cinterop（公开面只有 Kotlin 类型）；
- `keel-host`：能力包加载器（`dlopen` / ABI 校验 / `schemes` 匹配 / `open` / `close`）；
- `keel-database-mariadb`：纯 C 能力包 + `libmariadb` + 鉴权预检 + 连接池 + 事务表；
- `keel-plugin-rbac-api` / `keel-plugin-basic-biz-api`：DTO 与接口（可以先手写 `ServiceKey`
  与本地实现，codegen 在 M1 补）；
- 验证：`libmariadb` 在 mingw / linuxArm64 / linuxX64 上可交叉编译；能力包可被 `dlopen` 并热替换；
  两个插件并发调用同一能力包不崩。

**验收**：一个最小 `.keel` 能力包 + 一个最小 native 插件，插件通过 C ABI 执行 `select 1`，
三个目标都跑通；未装能力包时插件拒绝启用且错误信息可定位。

**M0 落地状态**：

- `keel-api` 的 auth / 权限 / 信封契约已完成；
- `keel-database-api` + `keel-host` 能力加载器 + `keel-database-mariadb` 的加载与调用链路已跑通
  （见 §6.6.1；驱动尚未接 `libmariadb`）；
- `keel-plugin-rbac-api` / `keel-plugin-basic-biz-api` 的接口、DTO、`ServiceKey` 常量已完成并编译通过，
  包名根分别是 `moe.lolosia.keel.plugins.rbac.api` / `...plugins.basic.biz.api`：
  DTO 在 `dto` 子包、服务接口在 `service` 子包、`ServiceKey` 对象与常量留在包根；
  `@ModuleService` 的 Key / 代理 codegen 仍留到 M1，现在按 `RbacServices` / `BasicBizServices` 手写查找键。

### M1 —— 运行时前置（让「真插件」成为可能）

这一阶段不写业务，只补 Keel 自己缺的洞：

1. **`keel-router-ksp` 实装**：`RouteDescriptor` + `PermissionNode` 生成、参数绑定、
   返回适配（JSON + 信封 / `HttpResponse` / 流）、`registerGeneratedRoutes`；
2. **`@ModuleService` codegen + `ModuleDirectory` / `ServiceCaller` 运行时**：
   Key / Client / Accessor / Dispatcher / 服务注册，CBOR 载荷与 correlation 配对；
3. **`Config` 运行时**：插件读主线 `database` / `auth` / `authz` 段，插件私有配置读写；
4. **插件 → Host 事件与反向请求**：`post`（`AUTH_INVALIDATE` / `AUTHZ_INVALIDATE`）+
   `request`（`PERMISSION_CATALOG` / `STORAGE`），带 correlation、超时与 Worker 重入；
5. **`ctx.scope`**：绑定在 Worker 调度器上的协程作用域（会话定时落库、验证码清理依赖它）；
6. **Host 认证 / 授权中间件**：内建 subject 的 SPI 注册（`provides: authn / authz`）、白名单、缓存、
   fail-closed 503、请求快照注入；
7. **生命周期**：`requires` 拓扑排序、启用顺序 / 停用反序、路由与权限目录的注册 / 注销、
   `plugin.json` 的 `requires` 校验；
8. **能力包生命周期**：`kind: capability` 的发现、`schemes` 匹配、ABI 校验、`open` / `close`、
   `provides` / `requires` 的依赖解析；
9. **`PluginStorage`**：`work/plugins/<id>/` 命名空间、反向请求的 read / write / delete。

**验收**：两个最小插件 `a` / `b`，`a.requires = ["b"]`，`a` 通过 `ctx.modules.b` 完成一次调用；
`b` 注册一条带 `@Permission` 的路由；Host 用桩 `Authenticator` / `Authorizer` 跑通 401 / 403 / 503；
两个包都从 `.keel` 加载，重启后恢复启用状态；`b` 通过能力包完成一次查库。

**M1 落地状态**：

- 第 1 项（router KSP）已完成：`keel-router-ksp` 生成同包的 `KeelPluginRoutes`，
  覆盖 `RouteDescriptor` / `PermissionNode`、`Context` 注入、`@Body` / `@Path` / `@Query` / `@Header` 绑定、
  JSON 信封与 `HttpResponse` / 文本原样返回；`PluginWorker` 在 `onLoad` 之后并入并发布，
  Host 侧由 `HostPermissionCatalog` 聚合节点。冒烟：`hello` 的
  `/api/hello/greeting`、`/api/hello/echo`、`/api/hello/plain` 与手写路由同时可达；
- 第 2 项（`@ModuleService` codegen + 运行时）已完成：`keel-plugin-ksp` 生成
  `<Service>Key` / `<Service>Client` / `PluginContext` 访问器 / `<Service>Dispatcher` / `KeelPluginServices`；
  `PluginServices` 提供本地直调与跨模块调用（CBOR 载荷，`ServiceCaller` 在 Worker 上重入事件泵等待），
  Host 侧由 `HostServices` 维护 `service id → plugin id` 并转发 `SERVICE_CALL`，停用插件时一并撤销。
  冒烟：`hello` 声明 `@ModuleService("hello")`，`onEnable` 经 `ServiceCaller` 走一次 Host 往返
  （生成的分发器执行），日志出现 `服务自检：hello, service-check`，同时 `/api/hello/greeting` 正常；
- 第 3 项（`Config` 运行时）已完成：Host 用 KAML 把 YAML 解析成 `JsonElement`；写入在原始文本上
  按路径替换标量 / 插入缺失键，保留注释与空行（对象 / 序列整体替换时块内注释会丢失，
  流式集合、多行标量、锚点 / 别名在写入时直接报错）。文件布局是
  `<工作目录>/application.yaml` 与 `<工作目录>/config/plugins/<plugin-id>.yaml`；
  插件经 `HostSubjects.CONFIG_READ` / `CONFIG_WRITE` 访问，`ctx.config` 本地缓存快照、
  `ctx.server.config` 读主线，写入由 Host 合并后原子落盘并留 `.bak`。
  冒烟：hello 启动期读到自己的配置、`GET /api/hello/config` 读到主线 `server.name`，
  `POST` 改写后行尾注释与空行不变、缺失的 `repeat` 键被插入；
- 第 4 项（插件 → Host 事件与反向请求）已完成：`PluginContext.host`（`HostLink`）提供
  `post`（单向通知）与 `request`（correlation 配对、3s 超时、Worker 重入事件泵）；
  Host 内置 `PERMISSION_CATALOG`（返回聚合权限节点）与 `AUTH_INVALIDATE` / `AUTHZ_INVALIDATE`
  （清认证 / 授权缓存）。冒烟：hello 在定时任务里取权限目录得到 `hello.greeting`，
  并成功投递一次授权缓存失效通知；
- 第 6 项（Host 认证 / 授权中间件）已完成：`plugin.json` 的 `provides: ["authn"]` / `["authz"]`
  在启用时登记为 SPI 提供者（同一 SPI 冲突则拒绝启用）；Host 经 `HostPump.request` 以 CBOR 请求
  `KEEL_SUBJECT_AUTHN` / `KEEL_SUBJECT_AUTHZ`，插件侧由 `Plugin.authenticator()` / `authorizer()`
  提供实现。认证 token 与授权 userId 按 TTL（默认 30s）缓存，失效事件清缓存，插件停用时
  撤销提供者并清掉它的缓存。`/api/` 前缀按白名单要求登录；带 `@Permission` 的路由在未安装
  授权实现时 fail-closed 503；请求期把 `AuthzSnapshot` 并进 `UserSnapshot` 注入 `Context`。
  冒烟（hello 内置桩）：无 token → 401、`guest` → 403、`hello` / `owner` → 200、
  去掉 `provides` 后受保护路由 → 503；
- 其余 4 项待做（`ctx.scope`、宿主与能力包生命周期、`PluginStorage`）。

### M2 —— rbac 与 biz 落地

- 表与迁移、`permissions.yaml`、组继承同步；
- 仓库层：先用 `keel-database-api` 的 Kotlin 门面**手写**基本栈的约 10 张表；
  `sqlx4k` 类型面接入或 `keel-repository-ksp` 放 M3，不让构建期工程卡住业务；
- rbac：规则解析、`Authorizer` 实现、角色 / 规则 / 目录接口与 HTTP 路由；
- biz：登录 / 注册 / 验证码 / 会话、用户 CRUD、头像、菜单配置、运行时设置、`Authenticator` 实现；
- 交叉点按 §5.3 接线：biz 写绑定表，rbac 只读；`myRole` 走 `RbacReader`；
- HTTP 契约与 `web/src/core` + `web/src/modules/auth` 对齐，前端不改代码即可登录、看菜单、管用户/组/权限。

**验收**：§13 的 1–9、11–13、15–17。

### M3 —— 可选演进

- **第二驱动**（MySQL / PgSQL）实现同一份 ABI，验证「换包不改业务代码」；
- **仓库层 codegen**：`sqlx4k` 类型面接入验证通过就用它，否则 fork `keel-repository-ksp`；
- **能力包热替换**：Windows `FreeLibrary` / Linux `dlclose` 的实测与回滚；
- 自定义授权模型（非规则串）时补 `awaitPermission` 与新的 `Authorizer` 形态；
- 内嵌形态接进 launcher 的单文件发行。

---

## 13. 验收清单

1. [ ] 前端 `web/src/modules/auth` 的登录 / 注册 / 验证码 / 登出不改一行代码可用。
2. [ ] `web/src/core/api/user.ts` 与 `role.ts` 的每个调用点都有对应路由，出参形状一致。
3. [ ] 未登录访问 `/api/user/list` → 401；登录但无权限 → 403；`@Permission` 不标 → 只要求登录。
4. [ ] `owner` 组无视 `-` 规则；`admin` 继承 `user` 继承 `guest` 的规则生效。
5. [ ] 组规则保存后再读回一致；非法规则串返回 400 且不落库。
6. [ ] 停用 rbac 后，带权限路由 503，公开路由与「只要求登录」路由正常。
7. [ ] 停用 biz 后，所有非白名单 `/api/**` 立即 401（认证缓存已清）。
8. [ ] 数据库不可达时进程不崩、启动不阻塞，恢复后自动可用。
9. [ ] 全局连接数不超过 `database.maxConnections`。
10. [ ] 两个 `.keel` 插件按 `requires` 顺序启用、按依赖反序停用；Host 重启后恢复启用状态。
11. [ ] 第三方插件只依赖 `rbac-api` / `biz-api` 就能读写用户与权限，不需要 HTTP。
12. [ ] 权限目录能列出所有插件声明的节点。
13. [ ] 只装 `keel-database-mariadb`、`database.url` 用 `mysql://`，登录与查库正常。
14. [ ] 换成 `keel-database-pgsql` + `postgres://`，业务插件与 `keel-database-api` 零改动。
15. [ ] 未装任何 database 能力包时，rbac / biz 拒绝启用且日志写清 scheme 与已装包。
16. [ ] 同一 scheme 装了两个能力包时，未配 `database.capability` 拒绝启用。
17. [ ] 能力包 `close` 后 `dlclose` 成功，无残留连接、无跨 CRT 释放。 

---

## 14. 待拍板

1. **认证缓存 TTL**：默认 30s + 失效事件，还是 0（每请求一跳，登出即时生效）？
2. **`/api/permission/my` 归属**：rbac 提供，还是 Host 直接用请求快照返回？
3. **注册默认组**：查 `roleByType("user")`，还是固定 `ROLE_ID_USER = 4`？（建议前者，常量兜底）
4. **`keel-database-api` 里放不放 C ABI 客户端**：当前设计是「一个 klib，公开面只有 Kotlin 类型，
   cinterop 与函数表调用是 `internal`」；这样业务插件只加一行依赖。若希望 `-api` 保持纯接口，
   就要再拆一个 `keel-database-client`，代价是每个数据插件多一行依赖。**建议不拆。**
5. **能力包用 C 还是 Rust**：`libmariadb`（C）接入最快；若想复用 sqlx 的连接池/超时语义，
   可以用 Rust cdylib 导出同一份 C ABI，但要多一条 Rust 交叉编译链。
6. **M1 期间是否允许先手写 `ServiceKey` / 代理**，把 `@ModuleService` codegen 后置？
   （允许则 M2 能更早开始，但 codegen 完成后要回头替换掉手写件。）

---

*本文确认基础栈的形态与边界；实现按 §12 的里程碑推进，与代码冲突时以代码为准并回头修本文。*
