# 数据库能力与 ORM 推进方案

> 状态：讨论稿（2026-11-21）。本文回答三件事：
> `keel-database-api` / `keel-database-mariadb` 是否完善、如何推进；
> 如何实现类型安全的 ORM；
> 是否需要单独的 `keel-database-gradle-plugin`。
>
> 本次更新吸收了三个既有工程的做法：
> `kigers-live-helper` 的外部 DLL 加载、`lolosia-backend` 的第三方源码编译与 `.a` 依赖、
> 以及 `Context` 扩展（不另造 `DbContext`）。
>
> 与代码冲突时以代码为准，并回头修本文。
>
> **落地进度（2026-11-21）**：SQLite 驱动已按 ABI v1 落地——
> `keel-thirdparty` 新增 `sqlite`（amalgamation 3.46.1，三目标静态库；
> `-Pkeel.thirdparty.libs=sqlite` 可只发布该库）；新模块 `keel-database-sqlite`
> （纯 C，静态并入 amalgamation，`.keel` 只带一个动态库；仓库内构建通过 composite
> 自动触发 sqlite 的编译与 staging，单独 clone 该目录时退回 Maven 工件）；
> `keel-plugin-hello` 的数据库自检扩成 exec / query / 参数绑定 / NULL / BLOB / Unicode /
> 事务回滚，`KEEL_DB_URL=sqlite://./check.db` 下已通过。
> `Context.database` / `Context.transaction` / `Context.execute` / `Context.query(sql)` 扩展已加，
> `requireDatabase()` 在缺包 / 未配置 / open 失败时抛 status = -2 的 `DatabaseException`；
> `Row` 补齐 `longOrNull` / `doubleOrNull` / `booleanOrNull`（布尔兼容 SQLite 的 INT）。
> `keel-orm` 运行时已落地：`@Table` / `@Id` / `@Generated` / `@Column` / `@Transient`、
> `EntityMeta` + `EntityMetas` 注册表、`SqlDialect`（`sqlite` / `mariadb`）、
> `OrmType` / `OrmTypes`（Text / Int / Real / Bool / Blob / Instant(毫秒) / Decimal / Json / 枚举）、
> `QueryScope` 的 `where` / `and` / `or` / `eq` / `ne` / `gt` / `ge` / `lt` / `le` /
> `isNull` / `isIn` / `between` / `like` / `contains` / `orderBy` / `limit` / `offset`，
> 以及 `findList` / `findFirst` / `findCount` / `findPage`、`insert` / `update` / `delete` /
> `lastInsertId`。
> ABI v2（`options` / `last_insert_id` / DECIMAL·TIMESTAMP·JSON·UUID）、
> MariaDB 真实驱动、`keel-orm-ksp` 与 Gradle 接线仍未做。

---

## 0. 结论

`keel-database-api` 的**契约骨架已经成形**，`keel-database-mariadb` **只有外壳**；
真实驱动和 ORM 都还没开始。现在能跑通的是 `select 1` 链路，
还不能承载 rbac / biz 的真实业务。

**先做 SQLite，再补 MariaDB。** SQLite 用来把 exec / query / 事务 / 参数绑定 / 结果集
整条链路和 ORM 的方言渲染压实；MariaDB 随后只多网络、鉴权、连接池与 LGPL 动态分发。
`basic-biz` 的多数据库兼容是长期目标，**本次不做迁移**；
SQLite 只用于驱动 / ORM conformance 与测试，`basic-biz` 仍以 MariaDB 为主发布目标。

目标形态分成两层：

```
构建期：@Table 实体 ──KSP──> QSysUser / Mapper
运行期：插件(Context 扩展) ──> keel-orm ──> keel-database-api ──C ABI──> sqlite.keel / mariadb.keel
```

构建期靠 KSP 生成类型安全列与行映射；运行期全程同步调用，
不经过 Host 事件泵，也不跨 Kotlin/Native 运行时。Web 业务只接触 `Context`，
数据库能力通过 `Context.database` / `Context.transaction` / `Context.query<T>` 等扩展提供，
**不新增 `DbContext`**。

---

## 1. 现状评估

| 模块 | 已具备 | 主要缺口 |
| --- | --- | --- |
| `keel-database-api` | C ABI 头、`KeelDatabase` / `Row` / `QueryResult` / `DbValue`、事务、结果集、三目标 cinterop | 只有最小 ABI；没有 `last_insert_id`、`ping`、批量、列元数据；`Decimal` 是 `Double`；没有 `Instant` / 精确 DECIMAL；没有 `Context.database` / `Context.transaction` 扩展；并发下 `last_error` 有竞态 |
| `keel-database-mariadb` | `.keel` 打包、`keel_capability_entry`、池 / 事务 / 结果集骨架、`select 1` | **没有链接 libmariadb**；`backend_exec` / `backend_query` 是 stub，除 `select 1` 全返回 `KEEL_ERR_UNSUPPORTED` |
| `keel-database-sqlite` | 尚未创建 | 需要新增顶层构建：SQLite amalgamation、能力包、三目标 `.keel` |
| `keel-thirdparty` | 6 个 codec 的源码编译 / stage / publish 已跑通 | 还没有 `sqlite` / `mariadb` / `openssl` 条目；消费侧只有 KMP cinterop 模式，缺少「驱动构建读取源码或 `.a`」的 native 消费模式 |
| Host 能力加载 | 发现、scheme 匹配、ABI 校验、`open` / `close`、`requires` 校验 | `database` 配置仍只读环境变量；没有 dialect / features 下发；没有健康检查 / 重连 / 热替换；没有池指标 |
| rbac / biz | 手写 SQL 仓库、幂等建表、真实接口 | 大量位置映射样板；建表 SQL 内嵌且是 MariaDB 方言；basic-biz 多库兼容后置；没有版本化迁移 |
| 构建接线 | `requires` 会解析成运行期 `.keel` | `requires: ["database"]` 只影响 `keelRun` 的包解析，不会自动加 `keel-database-api` / ORM KSP 依赖 |

几个具体证据：

- 驱动后端在 [keel_database_mariadb.c](../keel-database-mariadb/src/nativeCpp/keel_database_mariadb.c) 的
  `backend_init` / `backend_exec` / `backend_query`，目前只是 stub。
- 配置仍走环境变量：[DatabaseSettings.kt](../keel-host/src/nativeMain/kotlin/moe/lolosia/keel/host/capability/DatabaseSettings.kt)
  的 `fromEnvironment()`；启动时 [HostPlugins.kt](../keel-host/src/nativeMain/kotlin/moe/lolosia/keel/host/plugin/HostPlugins.kt)
  直接调用 `HostCapabilities.activateDatabase(DatabaseSettings.fromEnvironment())`。
- router KSP 目前还把 `moe.lolosia.keel.database.DbContext` 当作请求上下文别名
  （[KeelRouterProcessorProvider.kt](../keel-router-ksp/src/main/kotlin/moe/lolosia/keel/gradle/router/KeelRouterProcessorProvider.kt)），
  但 Keel 不新增 `DbContext`；Web 业务统一用 `Context` + 扩展，这一分支应删除。
- `requires` 的读取与解析在
  [KeelPluginPackagingPlugin.kt](../keel-plugin-gradle-plugin/src/main/kotlin/moe/lolosia/keel/gradle/plugin/KeelPluginPackagingPlugin.kt)
  的 `readRequires` / `runPlugins` 分支，只用于运行目录组装，不用于编译期依赖。
- 外部 DLL 的既有做法在 `kigers-live-helper`：CMake 产出 `klive_webview.dll`，
  插件把它复制进资源目录，运行期 `ctx.materialize` 到缓存目录后用
  `LoadLibraryExW(..., LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR)` 加载，依赖的 DLL 从同一目录解析。
  MariaDB 的 LGPL 动态库应复用这套做法，而不是静态并进插件。
- 第三方源码编译与 `.a` 依赖的既有做法在 `lolosia-backend`：
  根工程 `thirdparty` 插件按「库 × 目标」准备 `include/` + `lib/`（仓库 zip 优先，未配置仓库时回退到源码构建脚本）；
  模块把多个 `.a` 写进 `.def` 的 `staticLibraries`，用 `-libraryPath` 指向各自目录；
  自研门面 `liblolosia.a` 由模块现场编译，并 `dependsOn` SDL3 / FreeType / RmlUi 的同步任务。
  这正是数据库驱动构建要复用的模式。

---

## 2. 推进顺序

### D-1：可插拔边界（先定，再写驱动）

现有骨架已经是「能力包 + scheme 选择 + 稳定 Kotlin 门面」，方向是对的。要接 SQLite / PgSQL，
还缺几件必须先定的事：

1. **dialect 必须过边界**
   - 驱动只执行最终 SQL，ORM 负责渲染，所以插件侧必须知道方言。
   - `plugin.json` 增加 `dialect`（`mariadb` / `sqlite` / `pgsql`）与 `features`；
     Host 通过 `KeelHostApi`（ABI v2）把二者下发给插件。
   - 不能只靠 URL scheme 推断：`mysql` 可能是 MariaDB / MySQL / TiDB，`postgres` 可能是 PG /
     CockroachDB。方言由驱动自己声明。
   - `keel-orm` 用 `SqlDialect` 注册表按 id 取实现；未知 dialect 时拒绝启用依赖插件。

2. **ABI v2 补齐驱动差异**
   - 值类型：`KEEL_DB_DECIMAL`（十进制文本）、`KEEL_DB_TIMESTAMP`（epoch micros）、
     `KEEL_DB_JSON`（UTF-8 文本）、`KEEL_DB_UUID`（文本）。驱动把原生类型转成规范文本，
     ORM 只做一层映射。
   - 方法：`last_insert_id`、`ping`，可选 `column_name` / `column_type`。
   - 配置：增加 `options`（`KeelDbOption { key, value }` 数组）——SQLite 的 WAL / `busy_timeout`、
     PgSQL 的 `sslmode`、MariaDB 的 charset 都靠它，避免反复改结构体。
   - `features` 用**字符串列表**（不是位图）；`databases` / `database_count` 预留为 NULL / 0。
   - 版本：从「必须完全相等」改为「接受 1..CURRENT + `struct_size` 探测」。

3. **方言差异显式建模**

   | 能力 | MariaDB | SQLite | PgSQL |
   | --- | --- | --- | --- |
   | 标识符引号 | 反引号 | 双引号 | 双引号 |
   | 占位符 | `?` | `?` | `$n` |
   | 自增回填 | `LAST_INSERT_ID()` | `last_insert_rowid()` | `RETURNING` |
   | UPSERT | `ON DUPLICATE KEY` | `ON CONFLICT` | `ON CONFLICT` |
   | 布尔字面量 | `1` / `0` | `1` / `0` | `TRUE` / `FALSE` |
   | 行级锁 | `FOR UPDATE` | 无 | `FOR UPDATE` |
   | 写并发 | 多写 | 单写（WAL 下仍单写） | 多写 |

   `SqlDialect` 把上表变成方法 / 标志位，ORM 里不出现 `if (dialect == …)`。
   PgSQL 的 `$n` 需要 `placeholder(index)`，不能只提供 `quote(name)`。

4. **第三方原生库要支持「源码 + `.a`」模式**
   - `keel-thirdparty` 现在只有「下载 zip → cinterop」的 B 类消费；
     数据库驱动是 A 类：驱动构建需要拿到 `include/`、`lib/<target>/*.a`，
     也可能像 `liblolosia.a` 一样现场编译自己的门面，并依赖其它 thirdparty 的 `.a`。
   - 参考 `lolosia-backend`：根插件按「库 × 目标」准备目录，仓库可用时拉 zip，
     不可用时回退到源码构建脚本；模块把多个 `.a` 写进 `.def` 的 `staticLibraries`，
     并用 `-libraryPath` 指向各自目录；自研门面独立编译并依赖这些同步任务。
   - Keel 需要给 A 类补一个 native 消费模式（解包 / 暴露 `include` + `lib`，不建 cinterop），
     或者在 `keel-database-gradle-plugin` 里提供等价的配置与 `Sync` 任务。
   - 外部动态库（MariaDB、OpenSSL）复用 `kigers-live-helper` 的做法：
     构建期把 DLL/SO 放进 `.keel` 的资源目录，运行期 `materialize` 后用绝对路径加载；
     Windows 用 `LoadLibraryExW` + `LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR`，
     Linux 用 `dlopen` 并确保 `$ORIGIN` / rpath 能找到同目录依赖。

5. **Web 业务只使用 `Context` 扩展**
   - 不新增 `DbContext`，也不要求控制器换参数类型；router KSP 只识别 `Context`。
   - 数据库能力由服务 / 注册表管理，Web 侧通过扩展使用：
     `Context.database`、`Context.transaction { }`、`Context.query<T> { }`、
     `Context.execute(...)` 等。扩展内部落到 `currentDatabase()` /
     `requireDatabase()`，没有能力包时按 503 给出可定位的错误。
   - 这样与 `lolosia-backend` 的 `ACP.database` / `ACP.transaction` / `ACP.query` 一致，
     也不会让 `keel-api` 依赖 `keel-database-api`。

另外几条边界：

- **没有数据库插件也必须能跑**：Host 正常启动，`requires: ["database"]` 的插件被拒绝启用，
  `currentDatabase()` 返回 null，其余插件不受影响。补两点：`requireDatabase()` 抛带指引的异常
  （缺包 / 未配置 / open 失败分开报），`requires` 在构建期自动带上 `keel-database-api`。
- **basic-biz 多库兼容后置**：它是“basic”，长期应支持多种数据库，但**本次不迁移**。
  SQLite 只用于驱动 / ORM conformance；`basic-biz` 继续跑 MariaDB。
  现有 `Schema.kt` 的 `ON DUPLICATE KEY` / `CREATE OR REPLACE VIEW` / `AUTO_INCREMENT`
  保持不动，等真正做 SQLite 业务支持时再拆方言化迁移。
- **多数据源与 ES**：当前只有一个 `database` 句柄；主库 + 只读副本 + 本地 SQLite 缓存需要命名
  数据源。建议先只保留 `currentDatabase()`，等真实需求出现再在 ABI v2 追加 `databases` /
  `currentDatabase("name")`；不要现在为假想场景扩布局。
  Elasticsearch 不是关系库，用独立能力（`provides: ["search"]`）和独立 ABI，不塞进 `database`。

**驱动验收**：一套 C 级 conformance（exec / query / 事务 / 参数绑定 / NULL / BLOB / Unicode /
`last_insert_id` / 错误串），SQLite 与 MariaDB 都要过；方言差异只允许出现在渲染层。

### D0：把驱动做成真的（最高优先，先 SQLite 再 MariaDB）

0. **先做 SQLite（amalgamation）**
   - 在 `keel-thirdparty/manifest.json` 增加 `sqlite`，由生成侧编译 `sqlite3.a`；
     新建 `keel-database-sqlite`，按 A 类消费 `include/` + `lib/<target>/`，只产出 `.keel`。
   - 无网络依赖，最快把 exec / query / 事务 / 参数绑定 / 结果集整条链路跑通。
   - 驱动内部处理单写串行、`busy_timeout`、WAL 选项；URL 支持
     `sqlite:///绝对路径` 与 `sqlite:相对路径`。
   - SQLite 只作为驱动 / ORM conformance 与测试库；`basic-biz` 继续跑 MariaDB，
     不在本次迁移 SQLite。

1. **接入 MariaDB Connector/C**
   - 为 `mingwX64` / `linuxX64` / `linuxArm64` 准备头文件与库；优先复用
     `keel-thirdparty` / `lolosia-backend` 的源码编译或 zip 解析模式，不在驱动构建里重复写下载逻辑。
   - 如果需要自研门面库（类似 `liblolosia.a`），允许它现场编译并依赖其它 thirdparty 的 `.a`；
     链接顺序写进 `.def` 的 `staticLibraries`。
   - 链接 Windows 的 `ws2_32/secur32/crypt32`，Linux 的 `ssl/crypto/z/pthread`。
   - 注意 Connector/C 的线程模型：每个 Worker 线程首次调用前 `mysql_thread_init()`，
     线程退出 `mysql_thread_end()`；`mysql_library_init` 全局一次。
   - LGPL：**动态链接**，把 `.dll` / `.so` 作为独立文件随 `.keel` 分发；
     加载方式复用 `kigers-live-helper` 的 `LoadLibraryExW` / `dlopen` 模式，并附许可与可替换说明。

2. **实现真实后端**
   - 连接池：懒建、上限、借还、超时、断线重连、`utf8mb4`。
   - 预处理语句：`mysql_stmt_prepare/bind/execute/fetch`；TEXT/BLOB 动态长度；`LIMIT ?` 需要实测。
   - 类型映射：TEXT / INT / FLOAT / BOOL / BLOB；DECIMAL 不要用 double；TIMESTAMP 统一转 epoch micros 或文本。
   - 事务：从池里取独占连接，`autocommit=0`，commit/rollback 后归还；`begin` 只接受池句柄。
   - 错误：`mysql_errno` / `mysql_sqlstate` / `mysql_error`；**错误缓冲改成线程局部**，
     不能放在共享 pool 句柄里，否则两个插件并发时 `last_error` 会互相覆盖。
   - `exec` 返回 affected rows；`query` 的结果集要持有连接直到 `result_free`，
     否则连接池会被提前归还。

3. **ABI v2（只追加字段）**
   - `last_insert_id`、`ping`、可选 `column_name/column_type`。
   - `KEEL_DB_DECIMAL`（十进制文本）、`KEEL_DB_TIMESTAMP`（epoch micros）、
     `KEEL_DB_JSON`（UTF-8 文本）、`KEEL_DB_UUID`（文本）。
   - `KeelDbConfig.options`：驱动私有键值；`plugin.json` 增加 `dialect` 与 `features`，
     由 `KeelHostApi` 下发给插件（见 D-1）。
   - Host 从“版本必须完全相等”改成“接受 1..CURRENT，按 `struct_size` 做能力探测”，旧驱动仍可加载。

4. **配置切到 `application.yaml`**
   - `DatabaseSettings` 增加从 Host 配置读取的入口，环境变量保留为覆盖。
   - 日志里不要打印含密码的 URL。

**D0 验收**：SQLite 与 MariaDB 各跑一遍 `select 1`、CRUD、事务回滚、两个插件并发、
数据库不可达返回 503、恢复后重连；三个目标都跑一遍。不安装任何数据库能力包时，
Host 与不依赖 database 的插件必须正常启动。`basic-biz` 继续在 MariaDB 上冒烟，
不要求 SQLite；SQLite 只验证驱动 / ORM。

### D1：ORM 运行时 + KSP

1. `keel-orm`：查询 DSL、SQL 渲染、行映射、CRUD、分页、事务；
   Web 入口是 `Context` 扩展（`Context.query<T> { }` / `Context.transaction { }`），
   不新增 `DbContext`。
2. `keel-orm-ksp`：读 `@Table` / `@Column` / `@Id`，生成 `XxxMeta`、`XxxMapper`、`QXxx`。
3. 构建接线：把 `keel-orm-ksp` 挂到每个 native 目标的 `ksp<target>`，
   并自动加 `keel-orm` / `keel-database-api` 依赖。
4. **D1 只做单表**：先用最小测试实体做垂直切片，跑通查询 / CRUD / 分页 / 事务；
   `basic-biz` 的仓库迁移后续再说。关联 / 图写入 / 序列化不进 D1。

### D2 及以后：高级能力

关联 / join fetch、`withChildren` / `withRef`、`saveGraph` / `deleteGraph`、
实体序列化、视图 / 子查询 / raw SQL、乐观锁 `@Version`、软删除、审计字段、
批量插入、流式结果、PgSQL 方言、版本化迁移。**不要**一开始就做懒加载和脏检查。

---

## 3. ORM 设计

### 3.1 参考实现是什么

参考实现的 `querybean`（typequery）只是**类型安全列的生成物**；真正的 ORM 依赖
JVM 反射、字节码增强、懒加载与 identity map，查询用 `QSysUserEntity` 这种类型安全列。

Ebean 在 Keel 上**不能直接搬**：它依赖 JVM 反射、字节码增强、懒加载、identity map；
Kotlin/Native 没有这些。可复刻的是它的**编译期查询 DSL + 显式 CRUD**，
不是整个 JPA 运行时。

另外两个已有工程提供了可直接复用的工程模式：

- `kigers-live-helper`：CMake 编译 `klive_capture.dll` / `klive_webview.dll`，
  插件把 DLL 复制到资源目录，运行期 `materialize` 到缓存目录，
  用 `LoadLibraryExW` + `LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR` 加载；
  这解决了 MariaDB / OpenSSL 这类 LGPL 动态库的部署与依赖查找。
- `lolosia-backend`：根 `thirdparty` 插件按「库 × 目标」准备 `include/` + `lib/`，
  仓库可用时拉 zip，不可用时回退源码构建；模块可以现场编译自己的门面 `.a`
  （`liblolosia.a`），并把多个 `.a` 写进 `.def` 的 `staticLibraries` 按顺序链接。
  这解决了数据库驱动「源码 + `.a` 依赖」的构建方式。

### 3.2 推荐分层

```
keel-orm/                 Kotlin 库：注解 + 查询 AST + SQL 渲染 + 映射接口 + 事务
keel-orm-ksp/             KSP：实体 → Meta / Mapper / Q 类
keel-orm-compiler-plugin/ IR：给实体注入 has* 访问器（如 hasId，用于 saveGraph）
keel-database-api/        现有 Kotlin 门面 + C ABI + Context 扩展
keel-database-sqlite/     真实 C 驱动（先做）
keel-database-mariadb/    真实 C 驱动（后做，动态链接）
keel-thirdparty/          第三方原生库生成侧（SQLite / MariaDB / OpenSSL）
```

运行时先全部放 `nativeMain` 也可以，因为 `keel-database-api` 本身就是 nativeMain；
如果希望实体写在 commonMain，再把注解和查询 AST 拆到 commonMain。

Web 侧不换上下文类型，只在 `Context` 上加扩展：

```kotlin
/** 没有能力包 / 未配置 / open 失败时抛出带指引的 503。 */
val Context.database: KeelDatabase get() = requireDatabase()

fun <T> Context.transaction(block: (KeelDatabase) -> T): T = database.transaction(block)

fun <T : Any> Context.query(block: QueryScope<T>.() -> Unit): Query<T> =
    Query(QueryScope(metaOf<T>())).apply(block)

// 低层逃生口；业务优先用 ORM / 仓库
fun Context.execute(sql: String, params: List<DbValue> = emptyList()): Long =
    database.exec(sql, params)
```

这样控制器签名始终是 `fun list(ctx: Context)`；数据库能力由
`keel-database-api` / `keel-orm` 的扩展提供，`keel-api` 不需要知道数据库。

### 3.3 实体与生成物

```kotlin
@Table("sys_user")
class SysUser : AuditedEntity() {
    @Id
    lateinit var id: String

    var userName: String = ""
    var email: String? = null
    var isUse: Boolean = true
    var createdAt: Instant? = null

    /** 子表不建列；Children 带 loaded 状态，未加载与空集合可区分 */
    @OneToMany(mappedBy = "userId", cascade = [CascadeType.INSERT, CascadeType.UPDATE, CascadeType.DELETE], orphanRemoval = true)
    var roles: MutableList<SysUserRole> = Children()
}
```

> 关联（`@OneToMany` / `@ManyToOne`）是 D2 目标，序列化后置；D1 的实体只保留列字段。
> 下面的例子展示最终形态，不表示 D1 就要实现全部关联语义。

实体约定（沿用 Ebean 工程的写法，不要照 JPA + `data class` 改）：

- **普通 `class`、全 `var`、不用 `data class`**：实体可变且常有 `lateinit` 主键，
  `data class` 的 `copy` / `equals` / `hashCode` 会把关联字段卷进去，`lateinit` 也进不了主构造。
- **必须无参可构造**：字段给默认值，主键用 `lateinit var`；业务代码可以 `SysUser()` 先建空对象再赋值，
  生成的 `new()` 也就是 `SysUser()`。
- **列名自动推导，`@Column` 不是必需**：默认 camelCase → snake_case；只有列名与推导不一致，
  或要带 `nullable` / `TEXT` / 长度等 DDL 元数据时才写。列名恰好是 `order` / `index` 这类关键字
  也不需要改名，渲染期按方言加引号（见 §3.4）。`@Table` 显式写表名，不做推导。
- **反向引用拆成「对象 + FK 列」**：`@ManyToOne` / `@OneToOne` 的对象属性**不是列**，
  必须写成 `var master: MasterEntity? = null`（要能表示「未加载」），读写都走真实列 `masterId`。
  这里不能用 `lateinit`：`isInitialized` 只能在声明类内部判断，生成的 mapper 在类外分不清
  「没加载」和「加载了空」。
- **`@OneToMany` 列表不进本表列清单，但参与序列化与显式级联写**（`saveGraph`）；
  `@Transient` 与未标注的引用属性一律不进列清单、不进 serializer。
- **FK 只有一个真源**：`masterId` 是唯一写入值；`master != null` 时写前校验
  `master.id == masterId`，不一致直接抛错，不要静默覆盖。
- **继承**：沿 superclass 链收集字段（`AuditedEntity` 这类 `open` 基类里的 `createdBy` / `createdAt` 等），
  基类字段排在子类之前，`open` / `abstract` 基类自身不生成 meta。
- 集合属性只允许出现在关联上；列属性类型必须是 `String` / 数值 / `Boolean` / `ByteArray` /
  `Instant` / `Decimal` / 枚举 / `JsonElement` 这类可映射类型，
  KSP 遇到无法映射的属性直接报错，不要静默跳过。

#### 3.3.1 `Children<T>` 的定义

`Children<T>` 是 **`MutableList<T>` 的实现**（不是只实现 `List<T>`），
这样实体属性可以声明为 `MutableList<T>`，`withChildren` 能填充，业务也能增删：

```kotlin
/** 生成器/业务只通过这个工厂创建；内部 delegate 不暴露。 */
fun <T> childrenOf(elementType: KClass<*>? = null): Children<T> =
    Children(ArrayList(), elementType)

class Children<T> internal constructor(
    private val delegate: MutableList<T>,
    val elementType: KClass<*>? = null,
) : MutableList<T> by delegate {

    var loaded: Boolean = false
        private set

    /** ORM 填充：替换内容并标记已加载，不经过用户修改路径。 */
    internal fun markLoaded(values: Iterable<T>) {
        delegate.clear()
        delegate.addAll(values)
        loaded = true
    }

    private fun touch() {
        loaded = true
    }

    override fun add(element: T): Boolean = delegate.add(element).also { touch() }
    override fun add(index: Int, element: T) { delegate.add(index, element); touch() }
    override fun addAll(elements: Collection<T>): Boolean = delegate.addAll(elements).also { touch() }
    override fun addAll(index: Int, elements: Collection<T>): Boolean =
        delegate.addAll(index, elements).also { touch() }
    override fun remove(element: T): Boolean = delegate.remove(element).also { touch() }
    override fun removeAt(index: Int): T = delegate.removeAt(index).also { touch() }
    override fun clear() { delegate.clear(); touch() }
    override fun set(index: Int, element: T): T = delegate.set(index, element).also { touch() }
}
```

实现时还需覆盖 `removeAll` / `retainAll` / `listIterator().remove()` 等批量修改入口，
否则用户通过它们改集合不会置 `loaded = true`；也可以统一包一个 `markDirty()` 让所有 mutator 调用。

语义：

- `read()` 生成 `childrenOf<Role>(Role::class)`，`loaded = false`，内容为空。
- `withChildren` 调用 `markLoaded(...)`，`loaded = true`；此时 `isEmpty()` 才表示“确实没有子行”。
- 用户 `add` / `remove` / `clear` / `set` 会经 `touch()` 标记 `loaded = true`。
- 用户直接赋普通 `mutableListOf(...)` 时，ORM 看到“不是 `Children`”，按用户提供的
  已加载数据/权威数据处理。
- `elementType` 用于生成器写入时校验元素类型；用户直接赋值时不做强制。
- 它**不是隐藏类型**：`list is Children<*>` / `list::class` 都能看到。
  想让业务完全无感，需要“实体背景字段 + IR 生成 getter/setter + 私有 loaded 标志”；
  当前先用显式 `Children`，属性类型仍是 `MutableList<T>`，业务不需要 import 它。

KSP 生成（`read` 走「无参构造 + 赋值」，兼容 `lateinit` 与 `var`）：

```kotlin
object SysUserMeta : EntityMeta<SysUser> {
    override val table = "sys_user"
    override val columns = listOf("id", "user_name", "email", "is_use", "created_at")

    override fun new() = SysUser()

    override fun read(row: RowView): SysUser = SysUser().apply {
        id = row.text(0)!!
        userName = row.text(1)!!
        email = row.text(2)
        isUse = row.bool(3) ?: true
        createdAt = row.instantOrNull(4)
    }

    override fun write(v: SysUser) = listOf(
        SqlParam.Text(v.id), SqlParam.Text(v.userName), SqlParam.Text(v.email),
        SqlParam.Bool(v.isUse), SqlParam.Instant(v.createdAt),
    )
}

class QSysUser : QueryRoot<SysUser>(SysUserMeta) {
    val id = text("id")
    val userName = text("user_name")
    val email = text("email")
    val isUse = bool("is_use")
    val createdAt = instant("created_at")
}

// 列同时也是 QueryScope<SysUser> 的扩展属性，供泛型入口使用；无状态，访问即建
val QueryScope<SysUser>.isUse: Col<Boolean> get() = col("is_use")
```

查询，两种入口等价，`QSysUser` 都由运行时在内部创建，调用点不出现 `new`：

```kotlin
// ① 泛型入口：T 显式给出，列走扩展属性，where 栈在 QueryScope 里
val page = db.query<SysUser> {
    where {
        isUse eq true
        or {
            userName.contains(keyword)
            email.contains(keyword)
        }
    }
    orderBy(createdAt.desc())
    limit(20).offset(0)
}.findPage()

// ② Q 入口：T 从 lambda 推断不出来，因此只能用无类型参数的写法
val page = db.query {
    where { isUse eq true }
    orderBy(createdAt.desc())
    limit(20).offset(0)
}.findPage()
```

两个入口对应的生成物：

```kotlin
// keel-orm 运行时
inline fun <reified T : Any> Database.query(block: QueryScope<T>.() -> Unit): Query<T> =
    Query(QueryScope(metaOf<T>())).apply(block)

// keel-orm-ksp 生成：每实体一个，内部 new QSysUser，别名 / join 用实例承载
inline fun Database.query(block: QSysUser.() -> Unit): Query<SysUser> =
    Query(QueryRoot(SysUserMeta, QSysUser::class)).apply(block)
```

- `QSysUser` **必须是实例**：`where { or { … } }` 的嵌套分组要在对象上维护「当前表达式栈」，
  将来的 join 别名也挂在它上面，共享 `object` 会被并发查询或同一查询里的子查询互相污染。
  列本身只是这些字段的初始值，真正的状态在 `QueryScope` / `QueryRoot` 里。
- 泛型入口之所以能「不用 new」，是因为把状态搬进了 per-query 的 `QueryScope`，
  `QueryScope<T>.列名` 只是按列名取值的无状态访问器。
- **落地差异（2026-11-21）**：`QSysUser` 实例入口已取消。多个实体时
  `db.query { ... }` 的 lambda 接收者无法从参数推断，会产生重载歧义；
  只保留泛型入口 `db.query<SysUser> { ... }` + 生成的 `QueryScope<SysUser>.列`
  扩展属性。列的声明仍是受控值（`Col<T>` 携带 `OrmType`），不接受字符串列名。

事务：

```kotlin
db.transaction {
    insert(SysUser().apply {
        id = newId
        userName = name
    })
    update(user)
}
```

### 3.4 关键设计约束

- **SQL 只由生成的列名拼**：`QSysUser` 的值是受控列，不接受用户传字符串做 `orderBy`。
- **标识符一律按方言加引号**：`order` / `index` / `select` 这类关键字不需要改名，渲染时统一处理。
  引号字符由方言决定，内部同字符双写转义：
  ```kotlin
  interface SqlDialect {
      fun quote(name: String): String
      fun placeholder(index: Int): String = "?"
  }

  object MariaDbDialect : SqlDialect {
      override fun quote(name: String) = "`" + name.replace("`", "``") + "`"
  }

  object SqliteDialect : SqlDialect {
      override fun quote(name: String) = "\"" + name.replace("\"", "\"\"") + "\""
  }

  // PgSQL 以后补：override fun placeholder(index: Int) = "$" + index
  ```
  渲染结果形如 ``SELECT `id`, `order` FROM `shop_order` WHERE `order` > ?``（MariaDB）。
  引号只在渲染期加：生成物与 serializer 仍用原始列名 / 属性名，JSON 不受影响。
- **`@Column("item_order")` 只用于物理列名确实不同**，或需要 `nullable` / `TEXT` / 长度等元数据；
  不要为了躲关键字去改列名。
- **永远显式列清单，不用 `SELECT *`**：结果集是按位置映射的，
  列顺序必须由 `EntityMeta.columns` 固定。
- **不做懒加载**：关联要么显式 join，要么分两次查询；否则会 N+1，且跨 ABI 无法做代理。
  反向引用保持 `null` 表示未加载；需要子集时显式 `db.query<T> { … }.withChildren(T::roles)`。
- **空列表 ≠ 没有子行**：`read()` 只映射列，`@OneToMany` 列表保持构造时的空列表。
  业务代码不得用 `children.isEmpty()` 判断是否存在子记录，必须走显式加载或 `count` 查询。
  **已定**：`@OneToMany` 用特殊集合子类 `Children<T>`（实现 `MutableList<T>`，带 `loaded` 背景字段）。
  `read()` 保留未加载的 `Children()`；`withChildren` 写入并标记 loaded；
  用户直接赋普通 `MutableList`（或元素类型不是查询器指定的类型）视为用户显式修改，
  按“已加载 / 用户数据”处理，不做猜测。`isEmpty()` 只在 `loaded == true` 时有业务意义。
- **级联写必须显式**：`insert` / `update` 只写本行、不碰子表；关联增删改统一走
  `saveGraph(entity)`，不做字段级脏检查；整单删除走 `deleteGraph(entity)`，按 `cascade` 一并软删子行。
  **已定**：实体主键是 `lateinit` 时，KSP 在类外无法判断「无 id」；
  优先由 **IR 编译器插件**给实体类注入 `hasId` 这类成员访问器
  （如 `val hasId get() = this::id.isInitialized`），`saveGraph` 调用生成的 `has*`；
  IR 不可行时退化为子实体主键可空或显式 `inserts/updates/deletes`。
  `saveGraph` 本身放 D2。
- **序列化方向（后置）**：`@OneToMany` 列表递归生成元素，子实体只带自己的列与 FK，
  所以正向（父 → 子）能完整往返、反向只传 FK，不成环；禁止给 `@ManyToMany` 的两端都标列表，
  那会重新造出环。当前阶段不实现 serializer。
- **不做脏检查**：`insert` / `update` 显式调用；不依赖 `save()` 自动判断，
  也不跟踪字段变化。
- **不提供 `T.createInstance()`**：Kotlin/Native 没有 `KClass.createInstance()`，也没有运行时反射；
  好在实体约定已经要求「无参可构造」（默认值 + `lateinit` 主键），所以 `new()` 由 KSP 直接生成
  `SysUser()`，`read(row)` 用「构造后逐字段赋值」，`idOf(v)` 取主键。泛型入口只解析 meta，不解析构造：
  ```kotlin
  // keel-orm-ksp 汇总生成（聚合所有 @Table，键是 KClass，K/N 的 ::class 不需要反射）
  object EntityMetas {
      private val all: Map<KClass<*>, EntityMeta<*>> = mapOf(SysUser::class to SysUserMeta)
      @Suppress("UNCHECKED_CAST")
      fun <T : Any> of(type: KClass<T>): EntityMeta<T> = all.getValue(type) as EntityMeta<T>
  }
  inline fun <reified T : Any> metaOf(): EntityMeta<T> = EntityMetas.of(T::class)
  ```
  真正的「按 id 取一个空壳」需求用 `findRef(id)` 之类的显式 API 表达，同样走生成的 meta，
  不要试图在运行时反射构造对象。
- **ORM 保持同步**：C ABI 是同步调用，运行在插件 Worker 上。长查询会阻塞该插件的事件泵；
  靠 `READ_TIMEOUT`、分页和短查询控制，不要默认加 `suspend` 假装异步。
- **Web 侧只暴露 `Context` 扩展**：`Context.database` / `Context.transaction { }` /
  `Context.query<T> { }` 属于 `keel-database-api` / `keel-orm` 的扩展，不新增 `DbContext`；
  控制器参数始终是 `Context`，router KSP 不再识别 `DB_CONTEXT`。
- **方言先做 SQLite + MariaDB**：引号、`LIMIT ? OFFSET ?`、自增回填、
  `ON CONFLICT` / `ON DUPLICATE KEY` 都由 `SqlDialect` 渲染；PgSQL 以后补 `$n` 占位符。
  不允许 ORM 内部出现 `if (dialect == …)`。
- **basic-biz 多库兼容**：业务仓库只依赖 ORM / 参数绑定；DDL 按方言分目录或生成方言化语句。
  SQLite 先作为 conformance 驱动，MariaDB 随后补；不要把 `Schema.kt` 的 MariaDB 写法扩散到新仓库。
- **DDL 不交给 ORM 自动生成**：索引、视图、字符集、种子数据都无法从注解完整表达。
  保留版本化 SQL 迁移；KSP 最多提供 `keelSchemaExport` 做开发期 diff。

### 3.5 实体序列化（后置，本次不做）

> 本文保留设计记录，但当前阶段先实现查询 / CRUD / 事务；序列化不阻塞 ORM。
> 不强制用 DTO 传递数据，只有需要变更返回值格式时才引入 DTO。

实体不是 `data class`，也没有「主构造参数 + 默认值」这套形状，`@Serializable` 编译器插件对它**不生效**
（该插件只处理主构造里的属性，实体属性都在类体里）。所以序列化能力由 `keel-orm-ksp` 生成，
用 kotlinx.serialization 的**低级 API**写 `KSerializer`，不依赖序列化编译器插件：

```kotlin
object SysUserSerializer : KSerializer<SysUser> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("moe.lolosia.keel.orm.SysUser") {
        element<String>("id")                                  // 非 optional：缺了直接抛
        element<String>("userName")
        element<String?>("email", isOptional = true)
        element<Boolean>("isUse")
        element<Long?>("createdAtMicros", isOptional = true)   // Instant 统一映射 epoch micros
    }

    override fun serialize(encoder: Encoder, value: SysUser) {
        val out = encoder.beginStructure(descriptor)
        out.encodeStringElement(descriptor, 0, value.id)
        out.encodeStringElement(descriptor, 1, value.userName)
        out.encodeNullableSerializableElement(descriptor, 2, String.serializer(), value.email)
        out.encodeBooleanElement(descriptor, 3, value.isUse)
        out.encodeNullableSerializableElement(descriptor, 4, Long.serializer(), value.createdAt?.toEpochMicros())
        out.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): SysUser {
        val input = decoder.beginStructure(descriptor)
        val value = SysUser()
        while (true) {
            when (val index = input.decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break
                0 -> value.id = input.decodeStringElement(descriptor, 0)
                1 -> value.userName = input.decodeStringElement(descriptor, 1)
                2 -> value.email = input.decodeNullableSerializableElement(descriptor, 2, String.serializer())
                3 -> value.isUse = input.decodeBooleanElement(descriptor, 3)
                4 -> value.createdAt = input.decodeNullableSerializableElement(descriptor, 4, Long.serializer())
                    ?.let(::instantFromEpochMicros)
                else -> throw SerializationException("unknown element index $index")
            }
        }
        input.endStructure(descriptor)
        return value
    }
}
```

注册与用法（`@Serializable` 缺失时 `encodeToString(value)` 的隐式解析不成立，调用点要显式传 serializer）：

```kotlin
object EntitySerializers {
    val module: SerializersModule = SerializersModule {
        contextual(SysUser::class, SysUserSerializer)
    }
}

val EntityJson = Json { serializersModule = EntitySerializers.module }

EntityJson.encodeToString(SysUserSerializer, user)
EntityJson.decodeFromString(SysUserSerializer, text)
```

注册可以**按编译单元聚合**：`keel-orm-ksp` 汇总当前模块所有 `@Table`（`aggregating = true`），
生成一份 `OrmSerializers.module` 和一行安装函数，做法与 `KeelPluginRoutes` 相同：

```kotlin
fun JsonBuilder.installOrmSerializers() {
    serializersModule = serializersModule + OrmSerializers.module
}

val EntityJson = Json { installOrmSerializers() }
```

但这行安装函数的边界要说清楚：

- kotlinx.serialization **没有进程级全局注册表**，`Json.Default` 不能被改；
  `SerializersModule` 只能挂到具体的 `Json` / `Cbor` 实例上，所以「一行安装」的位置是 format 的 builder。
- 它让**按 `KClass` 查 contextual serializer** 生效（`OrmSerializers.of(SysUser::class)`），
  但不会让 `Json.encodeToString(entity)` 突然可用：后者在编译期由序列化插件把 `serializer<T>()`
  替换成具体 serializer，非 `@Serializable` 类型直接编译失败，不会回落到模块查找。
  想要透明写法必须加薄 IR 重写，见上面的可选项。
- 不加 IR 时用生成的 helper，调用点就不用写 serializer：

```kotlin
inline fun <reified T : Any> Json.encodeEntity(value: T): String =
    encodeToString(
        requireNotNull(serializersModule.getContextual(T::class)) { "not an ORM entity: ${T::class}" },
        value,
    )

inline fun <reified T : Any> Json.decodeEntity(text: String): T =
    decodeFromString(
        requireNotNull(serializersModule.getContextual(T::class)) { "not an ORM entity: ${T::class}" },
        text,
    )

EntityJson.encodeEntity(user)          // T 从实参推断
EntityJson.decodeEntity<SysUser>(text) // 解码需要显式类型参数
```

- 聚合只覆盖**当前编译模块**的实体；跨模块共享的实体（例如 impl 定义、被别的插件引用）
  要在声明方生成 serializer，消费方拿不到自动生成的那份。

- **为什么不用 IR**：完整的序列化编译器插件要生成 `$serializer`、接管 `serializer<T>()` 内在函数，
  还要处理默认值、多态、泛型，等于 fork kotlinx.serialization；`keel-logger-compiler-plugin` 那套
  IR 基础设施不适合承担这份语义。
- **可选的薄 IR**：只把 `serializer<SysUser>()` / `encodeToString<T>(value)` 解析到生成的
  `SysUserSerializer`，省掉调用点显式传参。收益有限，等真正有痛感再做，不做也不影响功能。
- **序列化范围**：列 + `@OneToMany` 列表生成元素；`@ManyToOne` / `@OneToOne` 对象与 `@Transient`
  不生成元素（反向方向只传 FK）。子实体 serializer 同样只带自己的列，所以正向递归不成环。
- **实体 JSON 不是 API 契约**：对外仍用 `@Serializable` 的 DTO（字段名可控、可加 `@SerialName`）；
  实体序列化只用于缓存 / 快照 / 调试导出。
- **格式**：HTTP 用 Json；跨 ABI 热路径仍用 Protobuf / CBOR，同一份 serializer 可直接给 `Cbor`。
- 实例化永远走 `SysUser()` + 赋值，不要为了序列化把实体改回 `data class`。

### 3.6 反序列化路径与请求体边界

`/user/edit` 这类入口按设计不该直接反序列化成实体；如果确实要，路径是：

```kotlin
val entity = EntityJson.decodeEntity<SysUser>(json)   // 等价于 decodeFromString(SysUserSerializer, json)
```

生成的反序列化器执行顺序：

1. `SysUser()` 无参构造，字段取默认值（`roles` 是构造时给的 `mutableListOf()`，`lateinit id` 未初始化）；
2. `decodeElementIndex` 按 descriptor 里的元素名逐个匹配并赋值，关联属性不在 descriptor 里；
3. 缺字段：非 optional 的（`id`、`userName`）在结束结构时抛 `MissingFieldException`，
   optional 的保持默认值；
4. 类型不匹配（`id: null`、`isUse: "true"`）抛 `SerializationException`，不会把脏值带进实体。

两个必须显式决定的策略：

- **未知键**：`Json` 默认 `ignoreUnknownKeys = false`，客户端多传一个 `passwordConfirm` 就报错。
  实体侧建议 `ignoreUnknownKeys = true`；但 HTTP 入口应改用 DTO 的严格校验。
- **关联数组**：`@OneToMany` 会生成元素，反序列化后列表会被填充；但**不会写子表**——
  `insert` / `update` 不碰子表，关联变更走 `saveGraph` 或在服务层事务里显式处理。
  `@ManyToOne` 对象不在 descriptor 里，反向方向只认 FK 列。

因此：

- 请求体一律用 DTO（`@Serializable data class UserEditReq(...)`），服务层再映射到实体；
  DTO 同时是字段白名单（`deleted` / `createdAt` / `isUse` 这类不允许客户端写）。
- 同一个实体 serializer 不能同时服务「读库行」和「创建请求」：`id` 在读库行里必有，
  在创建请求里通常没有，而 descriptor 只有一个 optional 语义。
- `Instant` 的线格式要统一：实体 serializer 用 epoch micros（与 ABI 一致），
  Web 前端多为 ISO-8601 / 毫秒，转换放在 DTO 层。
- 反向引用不进 serializer，因此正向递归不会成环；但 `@ManyToMany` 两端都生成列表会成环，
  这类聚合读模型走 DTO 或专门的查询结果。

### 3.7 关联查询、批处理与原生 SQL（D2）

参考实现（Ebean 一类）的做法，供对照：

- to-one：`fetch("customer")` 渲染成 LEFT JOIN；
- to-many：`fetchQuery("items")` 或懒加载批次，发**第二条 `IN` 查询**，避免 join 出笛卡尔积；
- 子查询：`where().in("id", subQuery)`；
- 视图：`@DbView` 映射成只读实体；
- 原生 SQL：`sqlQuery(...).setParameter(...)`，值全部走参数绑定。

keel 没有懒加载，对应能力全部显式化：

1. **to-one**：`withRef(ShopOrder::category)` → 第二条 `id IN (...)` 批量补全；
   `join` 只在需要按关联列过滤 / 排序时使用。
2. **to-many**：`withChildren(ShopOrder::items)` → 先收集当前页父 id，再
   `WHERE order_id IN (...)`，固定 1 + 1 次查询；超过 `batchSize`（默认 500）自动分块。
3. **手写两段式**（A 筛 10 个 id，再 B `in` 这 10 个 id）：
   ```kotlin
   val ids = db.query<ShopOrder> {
       where { status eq OrderStatus.Paid }
       orderBy(paidAt.desc())
       limit(10)
       select(ShopOrder::id)
   }.findIds()

   val items = db.query<ShopOrderItem> {
       where { orderId isIn ids }
   }.findList()
   ```
4. **子查询**：同一条 SQL 内完成，不做两段式：
   ```kotlin
   db.query<ShopOrderItem> {
       where {
           orderId isIn subQuery<ShopOrder> {
               where { status eq OrderStatus.Paid }
               select(ShopOrder::id)
           }
       }
   }.findList()
   ```
5. **视图**：`@View("v_order_stat")` 生成只读 meta，查询与序列化和表一致；
   `insert` / `update` / `delete` 在编译期拒绝。
6. **原生 SQL**：保留口子，但必须带绑定：
   ```kotlin
   db.rawQuery<OrderStat>(
       "SELECT o.id, SUM(i.price_cents * i.quantity) AS total " +
           "FROM shop_order o JOIN shop_order_item i ON i.order_id = o.id " +
           "WHERE o.created_at >= ? GROUP BY o.id LIMIT ?",
       bind(since), bind(50),
   ) { row -> OrderStat(row.text(0)!!, row.long(1) ?: 0L) }

   db.rawExec("UPDATE shop_order SET status = ? WHERE id = ?", bind(status), bind(id))
   ```

注入边界（DSL 与 raw 都适用）：

- 值只走 `?` 绑定；`IN` 的占位符由 DSL 生成并自动分块，绝不拼值；
- 标识符（列 / 表 / 排序）只接受生成的列对象或白名单枚举，不提供 `orderBy(String)`；
- 动态条件用 `SqlFragment` 组合，禁止字符串插值把请求参数塞进 SQL；
- 集合为空时 `isIn` 渲染 `FALSE`（`isNotIn` 渲染 `TRUE`），不生成非法的 `IN ()`。

---

## 4. 是否需要 `keel-database-gradle-plugin`

要分两件事说：

1. **“生成查询机制”不是 Gradle 插件的职责，是 KSP 的职责。**
   用 Gradle 任务扫源码生成查询类，会破坏增量编译、IDE 跳转和 KMP 多目标；
   正确做法是 `keel-orm-ksp`，Gradle 只负责把处理器挂上去。

2. **“让插件无感接入”值得一个独立的薄 Gradle 插件。**
   现在的工具链已经统一到 `keel { }` 根容器：`keel-gradle-common` 只提供
   `register` / `find` / `get`，section 由各插件在 apply 时注册。
   因此 `keel-database-gradle-plugin` 应该注册 `keel { database { } }`，
   而不是再造一个顶层扩展。它只做接线：
   - 依赖同一份 `keel-gradle-common`（类身份必须唯一）；
   - 把 `keel-orm-ksp` 加到每个 `ksp<target>` 配置；
   - 在 `requires` 含 `database` 时自动加 `keel-orm` + `keel-database-api`；
   - 传 KSP 参数（方言、生成包名）；
   - 可选任务：`keelSchemaExport` / `keelSchemaCheck` / `keelOrmCheck`。

**推荐形态：**

```kotlin
import moe.lolosia.keel.gradle.common.*

plugins {
    id("moe.lolosia.keel.plugin")
    id("moe.lolosia.keel.database")
}

keel {
    database {
        dialect = "sqlite"          // 或 mariadb
        orm { enabled = true }
        // schemaDir = "src/commonMain/resources/sql"
    }
}
```

- **独立插件**，由 `moe.lolosia.keel.plugin` 在 `requires: ["database"]` 时自动 apply，
  也可以显式 apply；与 `abi` / `logger` 的自动挂载方式一致。
- **不把驱动编译塞进这个插件**：SQLite / MariaDB 的 C 构建属于各自的
  `keel-database-*` 构建，原生库来自 `keel-thirdparty`（源码或 zip + `.a`）。
- **不在 Gradle 任务里生成查询代码**；KSP 负责生成，Gradle 只负责挂载和依赖。
- 如果暂时不做独立插件，至少不要把 ORM / 数据库逻辑继续塞进
  `moe.lolosia.keel.plugin` 的打包流程；`doc/thirdparty-licensing.md` §7 已经给出同样的结论。

**不建议做的事**：把 Ebean 直接移植到 K/N；把 ORM 放进 C 能力包；
用 Gradle 任务代替 KSP；生成 `SELECT *`；在共享池句柄上保存 `last_error`；
把 MariaDB Connector/C 静态并进插件。

---

## 5. 建议的第一步

先做 **SQLite 驱动 + `keel-thirdparty` 的 native 消费模式 + ABI v2 + `Context` 扩展**，
这是所有后续工作的前提；然后用 `SysUser` 一张表做 **D1 的垂直切片**：

1. 在 `keel-thirdparty` 增加 `sqlite`，让 `keel-database-sqlite` 能拿到 `include/` + `sqlite3.a`；
2. 实现 SQLite 能力包，跑通 conformance；
3. 在 `keel-database-api` 加 `Context.database` / `Context.transaction` 扩展，
   控制器仍只写 `Context`；
4. 在测试插件里定义一个最小 `@Table` 实体，`keel-orm-ksp` 生成 `Q` / Mapper；
5. 用 SQLite 跑通查询 / CRUD / 分页 / 事务，验证 ORM 与方言渲染；
6. `keelRun` 对比出参不变。

这个切片跑通后，再补 MariaDB 动态库与方言化 DDL；不要先做关联 / 序列化 / `saveGraph`，
也暂不迁移 `basic-biz` 到 SQLite。

---

## 6. 历史写法归纳与能力要求

> 从既有 Kotlin 后端工程的实体用法归纳而来（Q 类裸用等旧写法不计）。
> 这里只保留**结论**，用来确定 keel ORM 要提供哪些能力。

### 6.1 实体形态

| 形态 | 写法 | 关联 | 序列化 |
| --- | --- | --- | --- |
| 可变实体（主力） | 普通 `class` + 继承审计基类；`lateinit var` 主键 / 带默认值的 `var`；表名显式、列名推导 | 有 | 字段注解控制出参，或用内部 View 类裁剪 |
| 扁平行（KMP） | `data class` + 主键注解，字段全在主构造，无关联 | 无 | 单独写 DTO |

keel 采用可变实体；扁平行只适合 DTO / 只读视图，不作为 ORM 实体。

### 6.2 反向引用的三种历史写法

1. 只保留关系字段，FK 列被注释掉，靠关系读写；
2. 关系字段 + 独立 FK 列（FK 列 `insertable = false, updatable = false`）；
3. `@Transient` 派生对象 + setter 回写 FK。

keel 取 **2 的变体**：FK 列唯一真源，关系对象可空、只用于显式加载。

### 6.3 子集合的三种历史写法

- `MutableList` + 空列表 + 级联删除；
- `List` + 空列表 + getter 内 `sortedBy`；
- 可空 `List?` + `?.sortedBy`。

keel 统一 `MutableList` + 默认空列表；排序用显式 order 列 + `orderBy`，不在 getter 里隐式排序。

### 6.4 列与字段约定

- 列名默认 camelCase → snake_case；显式列名只用于差异名，也存在全显式列的写法。
- 主键：UUID → String；另有自增 / 生成主键的需求。
- 审计、逻辑删除、时间戳集中在基类。
- 时间统一 `Instant`，线格式 epoch micros。
- JSON 列、枚举字符串列、位掩码 / 自定义类型转换器都实际出现过。
- 部分实体在类内用 `::id.isInitialized` 判断是否新增；keel 由 IR 插件生成 `hasId` 等访问器，
  业务侧不需要手写 `isInitialized`，写路径仍显式 `insert` / `update`。
- HTTP 请求体普遍是 DTO / Map，实体主要在响应侧返回。

### 6.5 keel ORM 需要提供的能力

1. **继承字段收集**：沿 superclass 链收集列，支持审计 / 软删除基类。
2. **实体形态**：无参可构造、`lateinit` 主键、`var` 字段；不要求 `data class`。
3. **列映射**：camel→snake 推导、可选 `@Column`、显式 `@Table`；
   UUID → String、`Instant`、枚举值、JSON 列、自定义转换器。
4. **关联元数据**：接受 `@OneToMany` / `@ManyToOne` / `@OneToOne` / `@Transient`；
   FK 列唯一真源；反向对象可空。
5. **显式加载与级联**：不做懒加载 / Proxy；`withChildren` 显式取子集；
   `saveGraph` / `deleteGraph` 是唯一的级联入口；空列表 ≠ 无子行。
6. **软删除**：识别 `deleted` 列，默认查询过滤，`delete()` 转 update；可配置退出。
7. **审计填充**：写时自动填 `createdAt` / `updatedAt`（`@WhenCreated` / `@WhenModified` 等价物），
   `createdBy` / `updatedBy` 由调用方传入。
8. **序列化**：KSP 生成 `KSerializer`；列 + `@OneToMany` 生成元素，反向只传 FK；
   实体序列化只用于缓存 / 快照 / 调试，HTTP 入口仍用 DTO。
9. **写入语义**：`insert` / `update` 单行，`save` 不做脏检查；主键非空时无法靠 null 判断新增，
   一律显式调用。
10. **多数据库兼容（长期）**：SQLite / MariaDB 共用 ORM 与仓库接口；DDL / upsert / 自增回填由
    `SqlDialect` 或方言化迁移处理；Web 业务只写 `Context` 扩展，不写数据库方言判断。
    本次只做 SQLite 的驱动 / ORM 验证，不迁移 `basic-biz`。

---

## 7. 决策记录与剩余问题

以下条目多数已经拍板，保留决策与待办；未标“已定”的仍需要确认：

1. **SQLite 的业务范围（已定）**
   - 本次**不让** `basic-biz` 跑 SQLite；SQLite 只做驱动 / ORM conformance 与测试。
   - `basic-biz` 继续以 MariaDB 为主发布目标；多库兼容后置，`Schema.kt` 暂不拆。
   - 将来若做 SQLite 业务支持，再单独做方言化迁移。

2. **`saveGraph` 的新增判定（已定）**
   - 优先用 IR 编译器插件给实体注入 `hasId` 等 `has*` 成员访问器
     （`val hasId get() = this::id.isInitialized`），`saveGraph` 调用这些访问器；
   - KSP 不能在类外检查 `lateinit`，所以不把该逻辑放进 KSP；
   - IR 方案不可行时，退化为子实体主键可空或显式 `inserts/updates/deletes`；
   - `saveGraph` 仍放 D2 实现。

3. **`@OneToMany` 的加载状态（已定）**
   - 使用 `Children<T>`（实现 `MutableList<T>`，带 `loaded` 背景字段）；
   - `read()` 不填充，`withChildren` 填充并标记 loaded；
   - 用户赋普通 `MutableList` 或元素类型不是查询器指定类型时，视为用户显式修改；
   - D2 实现，D1 不暴露子集合。

4. **实体序列化（已定：本次不做）**
   - 先生成数据库查询系统；序列化后置；
   - 不强制使用 DTO 传递数据；只有需要变更返回值格式时才引入 DTO；
   - 实体序列化只作为后续缓存 / 快照 / 调试导出的可选能力，不阻塞 ORM。

5. **ABI v2 的边界（已定）**
   - `options` 用 `KeelDbOption { const char *key; const char *value; }` 数组；
     数组与字符串由 Host 持有，**只在 `open` 调用期间有效**，能力包需要时自行复制。
   - `features` 用字符串列表（`const char *const *` + `count`），不用位图。
   - 多数据源预留 `databases` / `database_count` 字段，当前固定 NULL / 0，等真实需求再实现。
   - 值类型按 D-1：DECIMAL 文本、TIMESTAMP epoch micros、JSON UTF-8、UUID 文本。
   - 版本协商：接受 1..CURRENT + `struct_size` 探测。

6. **MariaDB 动态库的部署（已定）**
   - `.keel` 的 `lib/<target>/` 放能力入口库与全部依赖：
     `libmariadb.dll` / `libmariadb.so`、OpenSSL 等；`plugin.json.lib[target]` 只指向入口库。
   - Host 的 `CapabilityLoader` 需要把整个 `lib/<target>/` 目录物化到
     `$cacheRoot/<id>/<version>/lib/<target>/`，不能只复制入口文件。
   - Windows 用 `LoadLibraryExW` + `LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR`；
     Linux 编译期加 `-Wl,-rpath,'$ORIGIN'`，`dlopen` 依赖同目录解析。
   - `licenses/` 与 `plugin.json.licenses` 随 `.keel` 携带；LGPL 说明可替换该动态库。

7. **`keel-thirdparty` 的发布形态（已定）**
   - 每个库 / 目标可配置发布形态：`NATIVE`（`.a` / `.so` + 头文件 zip）、
     `KLIB`（K/N 版本绑定的 klib）、`BOTH`。
   - 默认 `NATIVE`；A 类数据库驱动只消费 `NATIVE`，B 类可选 `KLIB` 加速。
   - 坐标区分：`<group>:<lib>-<target>:<version>@zip`（native）与
     `<group>:<lib>-klib-<kotlinVersion>-<target>:<version>`（klib）。
   - 消费侧增加 native 模式（只解包 `include/` + `lib/`，不建 cinterop），
     放在 `keel-thirdparty-gradle-plugin` 或 `keel-database-gradle-plugin` 均可；
     源码构建失败时允许回退到已有产物（参考 `lolosia-backend`）。

8. **ORM 类型映射（已定）**
   - `DECIMAL` 用自定义 `Decimal`（内部保存规范字符串，精确、不走 Double）；
   - `JSON` 先映射为 `JsonElement`；需要强类型时再上 Kotlin 可序列化对象或自定义机制；
   - 枚举默认按名称存字符串；位掩码用 `Long`；自定义转换器后置；
   - D1 支持 `String` / 数值 / `Boolean` / `ByteArray` / `Instant` / `Decimal` /
     枚举 / `JsonElement`，其它类型显式报错，不要静默跳过。

9. **事务嵌套与迁移（已定）**
   - v1 **不支持嵌套事务 / savepoint**；`KeelDatabase` 记录 `isTransaction`，
     `Context.transaction` 在事务句柄上再次调用时直接报错，避免隐式提交 / 回滚错乱。
   - 迁移归**插件**所有：脚本放 `resources/sql/<dialect>/V*.sql`，
     插件启用时由迁移器执行并记录版本；Host 只提供 classpath / storage / config。
   - 驱动不执行迁移，也不理解业务表；`keel-database-*` 只负责 SQL 执行。
   - savepoint / 嵌套事务后置，不在 D1。

10. **router KSP 的 `DbContext` 分支（已定，待代码修改）**
    - 删除 `DB_CONTEXT` 常量与 `isContext` 中的分支；
    - 控制器只接受 `Context`，数据库能力走 `Context.database` / `Context.query` 扩展；
    - 错误提示改成“只支持 `Context` / `@Body` / `@Path` / `@Query` / `@Header`”；
    - 这是 D1 前的必做小改动，避免生成代码继续指向不存在的类型。

---

## 8. 实现缺口检查清单

以下是文档已定、但代码里还不存在的部分；按依赖顺序排列：

1. **新模块 `keel-database-sqlite`**
   - 顶层构建、`settings.gradle.kts` / `buildAll` / `publishAll` / `AGENTS.md` 表都要补。
2. **`keel-thirdparty` 的 SQLite 条目与 native 消费模式**
   - `manifest.json` 加 `sqlite`；发布形态 `NATIVE` / `KLIB` / `BOTH` 落 DSL；
   - A 类消费：只解包 `include/` + `lib/`，不建 cinterop；允许源码编译门面 `.a`。
3. **Host `CapabilityLoader` 的目录物化**
   - 当前只复制入口库；MariaDB 需要把整个 `lib/<target>/` 复制到缓存目录，
     并用平台搜索路径加载依赖 DLL / SO。
4. **ABI v2 的 Host/插件接线**
   - `KeelDbConfig.options`、`KeelHostApi` 的 `dialect` / `features` / `databases`；
   - `KeelDbValue` 增加 DECIMAL / TIMESTAMP / JSON / UUID；
   - `plugin.json` 增加 `dialect` / `features`，Host 解析并下发。
5. **`Context` 数据库扩展**
   - `Context.database` / `Context.transaction` / `Context.query<T>` / `Context.execute`；
   - `requireDatabase()` 的缺包 / 未配置 / open 失败三种错误。
6. **router KSP 清理 `DB_CONTEXT`**
   - 删除常量、识别分支与错误提示里的 `DbContext`。
7. **ORM 三件套**
   - `keel-orm` 运行时、`keel-orm-ksp` 生成器、`keel-orm-compiler-plugin`（has* 访问器）；
   - Gradle 接线：挂 `keel-orm-ksp` 到所有 `ksp<target>`，按 `requires` 加依赖。
8. **`Children<T>` 与 `hasId`**
   - `Children<T>` 实现 `MutableList<T>` + `loaded` 状态；
   - IR 插件给实体注入 `hasId` / `has*`；IR 不可行时回退可空主键或显式 diff。
9. **类型映射**
   - `Decimal`（精确字符串）、`Instant`、`JsonElement`、枚举 / `Long` 位掩码；
   - 自定义转换器与强类型 JSON 后置。
10. **迁移器位置**
    - 建议在 `keel-database-api` 提供 `MigrationRunner`，插件侧按
      `resources/sql/<dialect>/V*.sql` 调用；Host 只提供 classpath / storage / config。
11. **跨模块实体限制**
    - `EntityMetas` 只聚合当前 KSP 模块的实体；跨模块共享实体需要在声明方生成 meta / serializer，
      消费方通过 api 工件获得。
    - 还要确认实体放 `commonMain` 还是 `nativeMain`：注解与查询 AST 必须跟实体同源集可见；
      `keel-database-api` 目前是 nativeMain，若实体进 commonMain，需要拆注解 / AST 到 commonMain。
    - 这两点在 D1 前定。
12. **测试**
    - C 级 conformance（SQLite / MariaDB 各一套）；
    - ORM 最小测试插件（实体 + 查询 + 分页 + 事务），不依赖 basic-biz 迁移。
