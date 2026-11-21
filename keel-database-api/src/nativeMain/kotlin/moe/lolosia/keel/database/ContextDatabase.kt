package moe.lolosia.keel.database

import moe.lolosia.keel.api.web.Context

/**
 * 当前插件可用的数据库；没有能力包时抛出带指引的 [DatabaseException]。
 *
 * 控制器签名保持 `fun handler(ctx: Context)`，数据库能力只通过扩展暴露：
 * `ctx.database` / `ctx.transaction { }` / `ctx.execute(...)`；ORM 另加 `ctx.query<T> { }`。
 *
 * @throws DatabaseException 能力包未安装、未配置 URL 或 open 失败（status = -2）
 */
val Context.database: KeelDatabase get() = requireDatabase()

/** 在事务里执行 [block]；正常返回提交，抛异常回滚。 */
fun <T> Context.transaction(block: (KeelDatabase) -> T): T = database.transaction(block)

/** 执行不返回结果集的语句；低层逃生口，业务优先用 ORM / 仓库。 */
fun Context.execute(sql: String, params: List<DbValue> = emptyList()): Long =
    database.exec(sql, params)

/** 查询并返回结果集；调用方负责关闭。低层逃生口，业务优先用 ORM。 */
fun Context.query(sql: String, params: List<DbValue> = emptyList()): QueryResult =
    database.query(sql, params)

/**
 * 取当前插件可用的数据库。
 *
 * Host 目前只下发「有没有能力句柄」，不区分「没装包 / 没配 URL / open 失败」；
 * 三者的排查方向都写在异常消息里。
 */
fun requireDatabase(): KeelDatabase = currentDatabase() ?: throw DatabaseException(
    "当前插件没有可用的数据库：请确认 plugins/ 里已放置 sqlite 或 mariadb 能力包，" +
        "并配置 KEEL_DB_URL（或 application.yaml 的 database.url）后重启",
    status = -2,
)
