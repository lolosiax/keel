# 数据库能力与 ORM 推进方案

> 状态：讨论稿（2026-11-19）。本文回答三件事：
> `keel-database-api` / `keel-database-mariadb` 是否完善、如何推进；
> 如何实现类型安全的 ORM；
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

### D-1：可插拔边界（先定，再写驱动）

现有骨架已经是「能力包 + scheme 选择 + 稳定 Kotlin 门面」，方向是对的。要接 SQLite / PgSQL，
还缺三件必须先定的事：

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
   - 配置：增加 `options`（驱动私有键值或 URL query）——SQLite 的 WAL / `busy_timeout`、
     PgSQL 的 `sslmode`、MariaDB 的 charset 都靠它，避免反复改结构体。
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

另外两条边界：

- **没有数据库插件也必须能跑**：Host 正常启动，`requires: ["database"]` 的插件被拒绝启用，
  `currentDatabase()` 返回 null，其余插件不受影响。补两点：`requireDatabase()` 抛带指引的异常
  （缺包 / 未配置 / open 失败分开报），`requires` 在构建期自动带上 `keel-database-api`。
- **多数据源与 ES**：当前只有一个 `database` 句柄；主库 + 只读副本 + 本地 SQLite 缓存需要命名
  数据源，ABI v2 预留 `databases` / `currentDatabase("name")`，避免以后破坏 `KeelHostApi` 布局。
  Elasticsearch 不是关系库，用独立能力（`provides: ["search"]`）和独立 ABI，不塞进 `database`。

**驱动验收**：一套 C 级 conformance（exec / query / 事务 / 参数绑定 / NULL / BLOB / Unicode /
`last_insert_id` / 错误串），SQLite 与 MariaDB 都要过；方言差异只允许出现在渲染层。

### D0：把驱动做成真的（最高优先，先 SQLite 再 MariaDB）

0. **先做 SQLite（amalgamation）**
   - 无网络依赖，最快把 exec / query / 事务 / 参数绑定 / 结果集整条链路跑通。
   - 驱动内部处理单写串行、`busy_timeout`、WAL 选项；URL 支持
     `sqlite:///绝对路径` 与 `sqlite:相对路径`。

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
Host 与不依赖 database 的插件必须正常启动。

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

### 3.1 参考实现是什么

参考实现的 `querybean`（typequery）只是**类型安全列的生成物**；真正的 ORM 依赖
JVM 反射、字节码增强、懒加载与 identity map，查询用 `QSysUserEntity` 这种类型安全列。

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
class SysUser : AuditedEntity() {
    @Id
    lateinit var id: String

    var userName: String = ""
    var email: String? = null
    var isUse: Boolean = true
    var createdAt: Instant? = null

    /** 子表不建列；参与序列化与显式级联写（saveGraph），不参与行映射 */
    @OneToMany(mappedBy = "userId", cascade = [CascadeType.INSERT, CascadeType.UPDATE, CascadeType.DELETE], orphanRemoval = true)
    var roles: MutableList<SysUserRole> = mutableListOf()
}
```

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
- 集合属性只允许出现在关联上；列属性类型必须是 `String` / 数值 / `Boolean` / `ByteArray` / `Instant`
  这类可映射类型，KSP 遇到无法映射的属性直接报错，不要静默跳过。

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
- 两种重载不冲突：`T` 无法从 lambda 体推断，`db.query { … }` 只命中 Q 入口；
  写 `db.query<SysUser> { … }` 才会命中泛型入口。

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
  }

  object MariaDbDialect : SqlDialect {
      override fun quote(name: String) = "`" + name.replace("`", "``") + "`"
  }

  object SqliteDialect : SqlDialect {
      override fun quote(name: String) = "\"" + name.replace("\"", "\"\"") + "\""
  }
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
- **级联写必须显式**：`insert` / `update` 只写本行、不碰子表；关联增删改统一走
  `saveGraph(entity)`，在事务内按「列表即权威」做 diff（无 id 插入、缺 id 按 `orphanRemoval` 删除、
  有 id 全量 update），不做字段级脏检查；整单删除走 `deleteGraph(entity)`，按 `cascade` 一并软删子行。
- **序列化方向**：`@OneToMany` 列表递归生成元素，子实体只带自己的列与 FK，
  所以正向（父 → 子）能完整往返、反向只传 FK，不成环；禁止给 `@ManyToMany` 的两端都标列表，
  那会重新造出环。
- **不做脏检查**：`insert` / `update` 显式调用；`save()` 可以按主键是否为空决定，
  但不要跟踪字段变化。
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
- **方言先只做 MariaDB**：反引号、`LIMIT ? OFFSET ?`、`LAST_INSERT_ID()`；
  PgSQL 以后再用 `SqlDialect` 抽象。
- **DDL 不交给 ORM 自动生成**：索引、视图、字符集、种子数据都无法从注解完整表达。
  保留版本化 SQL 迁移；KSP 最多提供 `keelSchemaExport` 做开发期 diff。

### 3.5 实体序列化

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

### 3.7 关联查询、批处理与原生 SQL

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
- 部分实体在类内用 `::id.isInitialized` 判断是否新增；keel 不生成，写路径显式 `insert` / `update`。
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
