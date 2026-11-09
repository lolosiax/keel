package moe.lolosia.keel.plugins.basic.biz.db

import moe.lolosia.keel.api.web.ApiException
import moe.lolosia.keel.database.*

/**
 * 取当前插件的数据库；没有安装 database 能力包时按 503 处理。
 *
 * 业务层不缓存连接池：`currentDatabase()` 已经按能力句柄缓存，插件重新启用时会自动重建。
 */
internal fun database(): KeelDatabase =
    currentDatabase() ?: throw ApiException(503, "数据库不可用：请安装 database 能力包")

/** 查询并逐行映射；结果集由本函数负责关闭。 */
internal fun <T> select(sql: String, params: List<DbValue> = emptyList(), mapper: (Row) -> T): List<T> =
    database().query(sql, params).use { result ->
        val rows = ArrayList<T>()
        while (result.next()) {
            rows += mapper(result.row ?: throw IllegalStateException("结果集返回了空行"))
        }
        rows
    }

/** 查询单行；没有结果返回 null。 */
internal fun <T> selectOne(sql: String, params: List<DbValue> = emptyList(), mapper: (Row) -> T): T? =
    select(sql, params, mapper).firstOrNull()

/** 执行写语句，返回受影响行数。 */
internal fun execute(sql: String, params: List<DbValue> = emptyList()): Long = database().exec(sql, params)

internal fun textParam(value: String?): DbValue = if (value == null) DbValue.Null else DbValue.Text(value)

internal fun longParam(value: Long?): DbValue = if (value == null) DbValue.Null else DbValue.Integer(value)

internal fun intParam(value: Int?): DbValue = if (value == null) DbValue.Null else DbValue.Integer(value.toLong())

/** 整数列；NULL 视为 null（结果集里的 tinyint 也走这里）。 */
internal fun Row.longOrNull(index: Int): Long? = (this[index] as? DbValue.Integer)?.value

internal fun Row.intOrNull(index: Int): Int? = longOrNull(index)?.toInt()

/** 布尔列：驱动返回 BOOL 时直接取，返回整型时按 `!= 0` 解释。 */
internal fun Row.boolOrNull(index: Int): Boolean? = when (val value = this[index]) {
    is DbValue.Bool -> value.value
    is DbValue.Integer -> value.value != 0L
    else -> null
}

/** `is_use` 这类接口上的 `1` / `0`。 */
internal fun Row.flagOrNull(index: Int): Int? = boolOrNull(index)?.let { if (it) 1 else 0 }
