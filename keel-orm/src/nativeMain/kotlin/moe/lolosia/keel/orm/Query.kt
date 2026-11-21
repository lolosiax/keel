package moe.lolosia.keel.orm

import moe.lolosia.keel.database.KeelDatabase

/**
 * 一条尚未执行的单表查询。
 *
 * 执行时统一渲染「显式列清单 + 表名 + WHERE + ORDER BY + LIMIT/OFFSET」；
 * 结果按 [EntityMeta.columns] 的位置映射成实体。ORM 保持同步，直接在插件 Worker 上调用。
 */
class Query<T : Any> internal constructor(
    private val database: KeelDatabase,
    private val meta: EntityMeta<T>,
    private val scope: QueryScope<T>,
) {

    fun findList(): List<T> = fetch(scope.limitValue, scope.offsetValue)

    fun findFirst(): T? = fetch(1, null).firstOrNull()

    /** 符合 WHERE 的总行数，不受 LIMIT / OFFSET 影响。 */
    fun findCount(): Long {
        val out = SqlRender(scope.dialect)
        val where = scope.renderWhere(out)
        val sql = buildString {
            append("select count(*) from ").append(out.quote(meta.table))
            if (where.isNotEmpty()) append(" where ").append(where)
        }
        return database.query(sql, out.params).use { result ->
            if (!result.next()) error("count 查询没有返回行")
            result.row?.longOrNull(0) ?: 0L
        }
    }

    /** 先取 total，再取当前页；两次查询之间不做快照隔离。 */
    fun findPage(): Page<T> {
        val total = findCount()
        val items = findList()
        return Page(
            items = items,
            total = total,
            limit = scope.limitValue,
            offset = scope.offsetValue ?: 0,
        )
    }

    private fun fetch(limit: Int?, offset: Int?): List<T> {
        val out = SqlRender(scope.dialect)
        val sql = buildSelect(out, limit, offset)
        return database.query(sql, out.params).use { result ->
            val list = ArrayList<T>()
            while (result.next()) {
                val row = result.row ?: continue
                list += meta.read(row)
            }
            list
        }
    }

    private fun buildSelect(out: SqlRender, limit: Int?, offset: Int?): String = buildString {
        append("select ")
        append(meta.columns.joinToString(", ") { out.quote(it) })
        append(" from ").append(out.quote(meta.table))

        val where = scope.renderWhere(out)
        if (where.isNotEmpty()) append(" where ").append(where)

        if (scope.orders.isNotEmpty()) {
            append(" order by ")
            append(scope.orders.joinToString(", ") { it.render(out) })
        }

        when {
            limit != null -> {
                append(" limit ").append(limit)
                if (offset != null) append(" offset ").append(offset)
            }
            // 单独 OFFSET 在 SQLite / MariaDB 里都要求带 LIMIT
            offset != null -> append(" limit 9223372036854775807 offset ").append(offset)
        }
    }
}
