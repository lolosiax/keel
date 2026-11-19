# 数据库能力与 ORM 推进方案

> 状态：讨论稿（2026-11-19）。本文回答三件事：
> `keel-database-api` / `keel-database-mariadb` 是否完善、如何推进；
> 如何实现类似 `backend/orm` 的 ORM；
> 是否需要单独的 `keel-database-gradle-plugin`。
>
> 与代码冲突时以代码为准，并回头修本文。

---

## 0. 结论

`keel-database-api` 的**契约骨架已经成形**，`keel-database-mariadb` **只有外壳**；
真实驱动和 ORM 都还没开始。现在能跑通的是 `select 1` 链路，
还不能承载 rbac / biz 的真实业务。

目标形态分成两层：

```
构建期：@Table 实体 ──KSP──> QSysUser / Mapper
运行期：插件 ──> keel-orm ──> keel-database-api ──C ABI──> mariadb.keel ──> MariaDB
```

构建期靠 KSP 生成类型安全列与行映射；运行期全程同步调用，
不经过 Host 事件泵，也不跨 Kotlin/Native 运行时。

---

## 1. 现状评估

| 模块 | 已具备 | 主要缺口 |
| --- | --- | --- |
| `keel-database-api` | C ABI 头、`KeelDatabase` / `Row` / `QueryResult` / `DbValue`、事务、结果集、三目标 cinterop | 只有最小 ABI；没有 `last_insert_id`、`ping`、批量、列元数据；`Decimal` 是 `Double`；没有 `Instant` / 精确 DECIMAL；没有 `DbContext`；并发下 `last_error` 有竞态 |
| `keel-database-mariadb` | `.keel` 打包、`keel_capability_entry`、池 / 事务 / 结果集骨架、`select 1` | **没有链接 libmariadb**；`backend_exec` / `backend_query` 是 stub，除 `select 1` 全返回 `KEEL_ERR_UNSUPPORTED` |
| Host 能力加载 | 发现、scheme 匹配、ABI 校验、`open` / `close`、`requires` 校验 | `database` 配置仍只读环境变量；没有健康检查 / 重连 / 热替换；没有池指标 |
| rbac / biz | 手写 SQL 仓库、幂等建表、真实接口 | 大量位置映射样板；建表 SQL 内嵌；**没有真库冒烟**；没有版本化迁移 |
| 构建接线 | `requires` 会解析成运行期 `.keel` | `requires: ["database"]` 只影响 `keelRun` 的包解析，不会自动加 `keel-database-api` / ORM KSP 依赖 |

几个具体证据：

- 驱动后端在 [keel_database_mariadb.c](../keel-database-mariadb/src/nativeCpp/keel_database_mariadb.c) 的
  `backend_init` / `backend_exec` / `backend_query`，目前只是 stub。
- 配置仍走环境变量：[DatabaseSettings.kt](../keel-host/src/nativeMain/kotlin/moe/lolosia/keel/host/capability/DatabaseSettings.kt)
  的 `fromEnvironment()`；启动时 [HostPlugins.kt](../keel-host/src/nativeMain/kotlin/moe/lolosia/keel/host/plugin/HostPlugins.kt)
  直接调用 `HostCapabilities.activateDatabase(DatabaseSettings.fromEnvironment())`。
- router KSP 已经识别 `moe.lolosia.keel.database.DbContext`
  （[KeelRouterProcessorProvider.kt](../keel-router-ksp/src/main/kotlin/moe/lolosia/keel/gradle/router/KeelRouterProcessorProvider.kt)），
  但 `keel-database-api` 里**没有这个类型**；生成代码还会把 `Context` 直接传给 `DbContext` 参数，
  所以这条能力目前是断的。
- `requires` 的读取与解析在
  [KeelPluginPackagingPlugin.kt](../keel-plugin-gradle-plugin/src/main/kotlin/moe/lolosia/keel/gradle/plugin/KeelPluginPackagingPlugin.kt)
  的 `readRequires` / `runPlugins` 分支，只用于运行目录组装，不用于编译期依赖。

---

## 2. 推进顺序

### D0：把驱动做成真的（最高优先）

1. **接入 MariaDB Connector/C**
   - 为 `mingwX64` / `linuxX64` / `linuxArm64` 准备头文件与库；交叉编译或 vendor 源码。
   - 链接 Windows 的 `ws2_32/secur32/crypt32`，Linux 的 `ssl/crypto/z/pthread`。
   - 注意 Connector/C 的线程模型：每个 Worker 线程首次调用前 `mysql_thread_init()`，
     线程退出 `mysql_thread_end()`；`mysql_library_init` 全局一次。
   - LGPL 合规：确认动态链接 / 静态链接的分发方式。

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
   - `KEEL_DB_DECIMAL`（文本或定点）、`KEEL_DB_TIMESTAMP`（epoch micros）。
   - Host 从“版本必须完全相等”改成“接受 1..CURRENT，按 `struct_size` 做能力探测”，旧驱动仍可加载。

4. **配置切到 `application.yaml`**
   - `DatabaseSettings` 增加从 Host 配置读取的入口，环境变量保留为覆盖。
   - 日志里不要打印含密码的 URL。

**D0 验收**：真库上 `select 1`、CRUD、事务回滚、两个插件并发、数据库不可达返回 503、
恢复后重连；三个目标都跑一遍。

### D1：ORM 运行时 + KSP

1. `keel-orm`：查询 DSL、SQL 渲染、行映射、CRUD、分页、事务。
2. `keel-orm-ksp`：读 `@Table` / `@Column` / `@Id`，生成 `XxxMeta`、`XxxMapper`、`QXxx`。
3. 构建接线：把 `keel-orm-ksp` 挂到每个 native 目标的 `ksp<target>`，
   并自动加 `keel-orm` / `keel-database-api` 依赖。
4. 先拿 `SysUser` 做垂直切片，迁移 `UserRepository.findById`，跑通 `keelRun` 冒烟，
   再批量迁移其它表。

### D2：高级能力

关联 / join fetch、乐观锁 `@Version`、软删除、审计字段、批量插入、流式结果、
PgSQL 方言、版本化迁移。**不要**一开始就做懒加载和脏检查。

---

## 3. ORM 设计

### 3.1 参考项目是什么

`F:/Develop/Trae/project-template/backend/orm` 本身是 **Ebean 的 querybean（typequery）源码**；
真正的 ORM 是 `io.ebean` + `kapt("io.ebean:kotlin-querybean-generator")`，
实体是 JPA 注解 + Ebean 字节码增强，查询用 `QSysUserEntity` 这种类型安全列。

Ebean 在 Keel 上**不能直接搬**：它依赖 JVM 反射、字节码增强、懒加载、identity map；
Kotlin/Native 没有这些。可复刻的是它的**编译期查询 DSL + 显式 CRUD**，
不是整个 JPA 运行时。

### 3.2 推荐分层

```
keel-orm/                 Kotlin 库：注解 + 查询 AST + SQL 渲染 + 映射接口 + 事务
keel-orm-ksp/             KSP：实体 → Meta / Mapper / Q 类
keel-database-api/        现有 Kotlin 门面 + C ABI
keel-database-mariadb/    真实 C 驱动
```

运行时先全部放 `nativeMain` 也可以，因为 `keel-database-api` 本身就是 nativeMain；
如果希望实体写在 commonMain，再把注解和查询 AST 拆到 commonMain。

### 3.3 实体与生成物

```kotlin
@Table("sys_user")
class SysUser(
    @Id val id: String = "",
    @Column("user_name") val userName: String = "",
    @Column("email") val email: String? = null,
    @Column("is_use") val isUse: Boolean = true,
    @Column("created_at") val createdAt: Instant? = null,
)
```

KSP 生成：

```kotlin
object SysUserMeta : EntityMeta<SysUser> {
    override val table = "sys_user"
    override val columns = listOf("id", "user_name", "email", "is_use", "created_at")

    override fun read(row: RowView) = SysUser(
        id = row.text(0)!!,
        userName = row.text(1)!!,
        email = row.text(2),
        isUse = row.bool(3) ?: true,
        createdAt = row.instantOrNull(4),
    )

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
```

查询：

```kotlin
val page = db.query(QSysUser()) {
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
```

事务：

```kotlin
db.transaction {
    insert(SysUser(id = id, userName = name))
    update(user)
}
```

### 3.4 关键设计约束

- **SQL 只由生成的列名拼**：`QSysUser` 的值是受控列，不接受用户传字符串做 `orderBy`。
- **永远显式列清单，不用 `SELECT *`**：结果集是按位置映射的，
  列顺序必须由 `EntityMeta.columns` 固定。
- **不做懒加载**：关联要么显式 join，要么分两次查询；否则会 N+1，
  且跨 ABI 无法做代理。
- **不做脏检查**：`insert` / `update` 显式调用；`save()` 可以按主键是否为空决定，
  但不要跟踪字段变化。
- **ORM 保持同步**：C ABI 是同步调用，运行在插件 Worker 上。长查询会阻塞该插件的事件泵；
  靠 `READ_TIMEOUT`、分页和短查询控制，不要默认加 `suspend` 假装异步。
- **方言先只做 MariaDB**：反引号、`LIMIT ? OFFSET ?`、`LAST_INSERT_ID()`；
  PgSQL 以后再用 `SqlDialect` 抽象。
- **DDL 不交给 ORM 自动生成**：索引、视图、字符集、种子数据都无法从注解完整表达。
  保留版本化 SQL 迁移；KSP 最多提供 `keelSchemaExport` 做开发期 diff。

---

## 4. 是否需要 `keel-database-gradle-plugin`

要分两件事说：

1. **“生成查询机制”不是 Gradle 插件的职责，是 KSP 的职责。**
   用 Gradle 任务扫源码生成查询类，会破坏增量编译、IDE 跳转和 KMP 多目标；
   正确做法是 `keel-orm-ksp`，Gradle 只负责把处理器挂上去。

2. **“让插件无感接入”值得一个薄 Gradle 插件。** 它应该只做接线：
   - 把 `keel-orm-ksp` 加到每个 `ksp<target>` 配置；
   - 在 `requires` 含 `database` 时自动加 `keel-orm` + `keel-database-api`；
   - 传 KSP 参数（方言、生成包名）；
   - 可选任务：`keelSchemaExport` / `keelSchemaCheck` / `keelOrmCheck`。

**推荐路径：**

- **现在最省事**：直接扩展现有的 `moe.lolosia.keel.plugin`。它已经有 KSP 接线钩子
  （`KeelPluginPackagingPlugin` 的 `configurations.configureEach`），
  只需在同一个分支里多挂一个 `keel-orm-ksp`，并在 `afterEvaluate` 里按 `requires` 加依赖。
- **需要独立插件时**：再拆 `keel-database-gradle-plugin`，
  插件 ID 用 `moe.lolosia.keel.database`，由 `moe.lolosia.keel.plugin`
  在 `requires: ["database"]` 时自动 apply。这样用户不需要额外记一个插件，
  也符合现有 `abi` / `logger` 自动挂载的模式。

**不建议做的事**：把 Ebean 直接移植到 K/N；把 ORM 放进 C 能力包；
用 Gradle 任务代替 KSP；生成 `SELECT *`；在共享池句柄上保存 `last_error`。

---

## 5. 建议的第一步

先做 **D0 的真实驱动 + ABI v2 + 配置接线**，这是所有后续工作的前提；
然后用 `SysUser` 一张表做 **D1 的垂直切片**：

1. 定义 `@Table("sys_user")` 实体；
2. `keel-orm-ksp` 生成 `QSysUser` / Mapper；
3. 替换 [UserRepository.kt](../keel-plugin-basic-biz/src/nativeMain/kotlin/moe/lolosia/keel/plugins/basic/biz/db/UserRepository.kt)
   的 `findById`；
4. `keelRun` 冒烟，对比接口出参不变。

这个切片跑通后，再决定要不要把 Gradle 接线拆成独立的
`keel-database-gradle-plugin`。
