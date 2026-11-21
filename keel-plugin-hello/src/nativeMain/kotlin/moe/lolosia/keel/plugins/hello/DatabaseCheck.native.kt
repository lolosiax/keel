package moe.lolosia.keel.plugins.hello

import moe.lolosia.keel.api.Log
import moe.lolosia.keel.database.DbValue
import moe.lolosia.keel.database.KeelDatabase
import moe.lolosia.keel.database.currentDatabase

/**
 * 数据库能力自检：`select 1` 验证链路，其余步骤验证 exec / query / 参数绑定 /
 * NULL / BLOB / Unicode / 事务回滚。
 *
 * 没装 database 能力包时只打印一行提示，不影响插件启用；任何一步失败只告警，
 * 让 Host 与其它自检继续跑。
 */
internal actual fun checkDatabase() {
    val database = currentDatabase()
    if (database == null) {
        Log.info("database: 未安装 database 能力包")
        return
    }
    try {
        conformance(database)
    } catch (e: Exception) {
        Log.warn("database: conformance 失败：${e.message}")
    }
}

private fun conformance(database: KeelDatabase) {
    val ping = database.query("select 1").use { result ->
        if (!result.next()) error("select 1 没有返回行")
        result.row?.long(0)
    }

    database.exec("drop table if exists keel_check")
    database.exec(
        "create table keel_check(" +
            "id integer primary key, name text, score real, flag integer, data blob)"
    )
    val inserted = database.exec(
        "insert into keel_check(name, score, flag, data) values (?, ?, ?, ?)",
        listOf(
            DbValue.Text("中文🙂"),
            DbValue.Decimal(1.5),
            DbValue.Bool(true),
            DbValue.Blob(byteArrayOf(1, 2, 3)),
        ),
    )
    val rowId = database.query("select last_insert_rowid()").use { result ->
        if (!result.next()) error("last_insert_rowid 没有返回行")
        result.row?.long(0) ?: error("last_insert_rowid 为空")
    }

    val read = database.query("select name, score, flag, data from keel_check where id = ?", listOf(DbValue.Integer(rowId)))
        .use { result ->
            if (!result.next()) error("按 id 查询没有返回行")
            val row = result.row ?: error("结果行缺失")
            Triple(row.text(0), (row[1] as? DbValue.Decimal)?.value, row.bytes(3))
        }

    database.exec(
        "insert into keel_check(name, score, flag, data) values (?, ?, ?, ?)",
        listOf(DbValue.Null, DbValue.Decimal(0.0), DbValue.Bool(false), DbValue.Null),
    )
    val nullName = database.query("select name from keel_check where name is null").use { result ->
        if (!result.next()) error("NULL 行没有返回")
        result.row?.text(0)
    }

    val before = count(database)
    runCatching {
        database.transaction { tx ->
            tx.exec("insert into keel_check(name) values ('rollback')")
            error("intentional rollback")
        }
    }
    val after = count(database)

    val journal = database.query("pragma journal_mode").use { result ->
        if (!result.next()) error("journal_mode 没有返回行")
        result.row?.text(0) ?: "?"
    }
    Log.info(
        "database: select 1 -> $ping, insert -> $inserted, id=$rowId, " +
            "read=${read.first}/${read.second}/${read.third?.size}, null=${nullName == null}, " +
            "rollback=${before}->$after, journal=$journal",
    )
}

private fun count(database: KeelDatabase): Long =
    database.query("select count(*) from keel_check").use { result ->
        if (!result.next()) error("count 没有返回行")
        result.row?.long(0) ?: 0L
    }
