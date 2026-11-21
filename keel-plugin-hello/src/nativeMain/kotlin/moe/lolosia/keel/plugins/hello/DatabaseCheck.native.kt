package moe.lolosia.keel.plugins.hello

import moe.lolosia.keel.api.Log
import moe.lolosia.keel.database.DbValue
import moe.lolosia.keel.database.KeelDatabase
import moe.lolosia.keel.database.currentDatabase
import moe.lolosia.keel.orm.Decimal
import moe.lolosia.keel.orm.delete
import moe.lolosia.keel.orm.insert
import moe.lolosia.keel.orm.lastInsertId
import moe.lolosia.keel.orm.query
import moe.lolosia.keel.orm.update
import kotlin.time.Instant

/**
 * 数据库能力自检：`select 1` 验证链路，其余步骤验证 exec / query / 参数绑定 /
 * NULL / BLOB / Unicode / 事务回滚，以及 ORM 的 CRUD / 条件 / 排序 / 分页 / 事务。
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
        return
    }
    try {
        ormConformance(database)
    } catch (e: Exception) {
        Log.warn("orm: conformance 失败：${e.message}")
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

/**
 * ORM 垂直切片：建表（DDL 不交给 ORM）→ 插入 → 条件 / OR 分组 / 排序 / 分页 →
 * 更新为 NULL → 删除 → 事务回滚。
 */
private fun ormConformance(database: KeelDatabase) {
    database.exec("drop table if exists hell_user")
    database.exec(
        "create table hell_user(" +
            "id integer primary key autoincrement, user_name text not null, email text, " +
            "is_use integer not null default 1, score text, created_at integer)"
    )

    val alice = HellUser().apply {
        userName = "alice"
        email = "alice@example.com"
        score = Decimal.of("12.34")
        createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
    }
    database.insert(alice)
    alice.id = database.lastInsertId()

    val bob = HellUser().apply {
        userName = "bob"
        isUse = false
    }
    database.insert(bob)
    bob.id = database.lastInsertId()

    val found = database.query<HellUser> {
        where { userName eq "alice" }
    }.findFirst()

    val page = database.query<HellUser> {
        orderBy(userName.desc())
        limit(1).offset(1)
    }.findPage()
    val total = database.query<HellUser> { }.findCount()

    val filtered = database.query<HellUser> {
        where {
            isUse eq true
            or {
                userName.contains("ali")
                email.contains("example")
            }
        }
    }.findList()

    val nullEmails = database.query<HellUser> {
        where { email.isNull() }
    }.findList()

    alice.email = null
    database.update(alice)
    val updatedNull = database.query<HellUser> {
        where {
            email.isNull()
            userName eq "alice"
        }
    }.findCount()

    val deleted = database.delete(bob)
    val afterDelete = database.query<HellUser> { }.findCount()

    runCatching {
        database.transaction { tx ->
            tx.insert(HellUser().apply { userName = "rollback" })
            error("intentional rollback")
        }
    }
    val afterRollback = database.query<HellUser> { }.findCount()

    Log.info(
        "orm: insert=${alice.id}/${bob.id}, first=${found?.userName}, " +
            "page=${page.items.map { it.userName }}/${page.total}, total=$total, " +
            "filtered=${filtered.size}, nullEmails=${nullEmails.size}, " +
            "updatedNull=$updatedNull, delete=$deleted->$afterDelete, " +
            "rollback=$afterRollback",
    )
}
