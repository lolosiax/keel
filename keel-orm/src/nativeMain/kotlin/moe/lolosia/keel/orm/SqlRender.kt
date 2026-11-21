package moe.lolosia.keel.orm

import moe.lolosia.keel.database.DbValue

/**
 * 一次渲染所需的方言与参数收集器。
 *
 * 所有值都经 [bind] 变成占位符；[params] 的顺序就是占位符顺序。
 */
class SqlRender(val dialect: SqlDialect) {

    val params: MutableList<DbValue> = mutableListOf()

    fun quote(name: String): String = dialect.quote(name)

    fun bind(value: DbValue): String {
        params += value
        return dialect.placeholder(params.size)
    }
}
