package moe.lolosia.keel.orm

import moe.lolosia.keel.api.web.Context
import moe.lolosia.keel.database.KeelDatabase
import moe.lolosia.keel.database.database
import kotlin.reflect.KClass

/** 泛型入口：`db.query<SysUser> { ... }`。 */
inline fun <reified T : Any> metaOf(): EntityMeta<T> = EntityMetas.of(T::class)

fun <T : Any> metaOf(type: KClass<T>): EntityMeta<T> = EntityMetas.of(type)

/**
 * 开始一条单表查询。
 *
 * ```kotlin
 * ctx.query<SysUser> {
 *     where {
 *         isUse eq true
 *         or { userName.contains(keyword); email.contains(keyword) }
 *     }
 *     orderBy(createdAt.desc())
 *     limit(20).offset(0)
 * }.findPage()
 * ```
 */
inline fun <reified T : Any> KeelDatabase.query(
    noinline block: QueryScope<T>.() -> Unit = {},
): Query<T> = query(metaOf<T>(), block)

fun <T : Any> KeelDatabase.query(
    meta: EntityMeta<T>,
    block: QueryScope<T>.() -> Unit = {},
): Query<T> = Query(this, meta, QueryScope(meta, OrmRuntime.dialectOrThrow()).apply(block))

/** 插入一行；返回受影响行数。数据库生成的主键用 [lastInsertId] 回填。 */
inline fun <reified T : Any> KeelDatabase.insert(value: T): Long = insert(metaOf<T>(), value)

fun <T : Any> KeelDatabase.insert(meta: EntityMeta<T>, value: T): Long {
    if (meta.insertColumns.isEmpty()) {
        error("实体 ${meta.type.simpleName} 没有可插入的列")
    }
    val out = SqlRender(OrmRuntime.dialectOrThrow())
    val values = meta.writeInsert(value)
    val columns = meta.insertColumns.joinToString(", ") { out.quote(it) }
    val placeholders = values.joinToString(", ") { out.bind(it) }
    return exec(
        "insert into ${out.quote(meta.table)} ($columns) values ($placeholders)",
        out.params,
    )
}

/** 按主键更新一行；只写本表列，不级联。 */
inline fun <reified T : Any> KeelDatabase.update(value: T): Long = update(metaOf<T>(), value)

fun <T : Any> KeelDatabase.update(meta: EntityMeta<T>, value: T): Long {
    if (meta.updateColumns.isEmpty()) {
        error("实体 ${meta.type.simpleName} 除主键外没有可更新的列")
    }
    val out = SqlRender(OrmRuntime.dialectOrThrow())
    val values = meta.writeUpdate(value)
    val assignments = meta.updateColumns.mapIndexed { index, column ->
        "${out.quote(column)} = ${out.bind(values[index])}"
    }.joinToString(", ")
    val id = out.bind(meta.idOf(value))
    return exec(
        "update ${out.quote(meta.table)} set $assignments where ${out.quote(meta.idColumn)} = $id",
        out.params,
    )
}

/** 按主键删除一行；物理删除，软删除是后续能力。 */
inline fun <reified T : Any> KeelDatabase.delete(value: T): Long = delete(metaOf<T>(), value)

fun <T : Any> KeelDatabase.delete(meta: EntityMeta<T>, value: T): Long {
    val out = SqlRender(OrmRuntime.dialectOrThrow())
    val id = out.bind(meta.idOf(value))
    return exec(
        "delete from ${out.quote(meta.table)} where ${out.quote(meta.idColumn)} = $id",
        out.params,
    )
}

/**
 * 读取本连接上一次插入的自增主键。
 *
 * SQLite 的取值是连接级的；MariaDB 在真实驱动补齐后需要 ABI 返回 `last_insert_id`，
 * 当前实现只对单连接驱动（SQLite）可靠。
 */
fun KeelDatabase.lastInsertId(): Long {
    val dialect = OrmRuntime.dialectOrThrow()
    val sql = dialect.lastInsertIdSql
        ?: error("方言 ${dialect.id} 不支持 last_insert_id")
    return query(sql).use { result ->
        if (!result.next()) error("last_insert_id 没有返回行")
        result.row?.long(0) ?: error("last_insert_id 返回 NULL")
    }
}

/** Web 入口：控制器继续只写 `ctx.query<SysUser> { ... }`。 */
inline fun <reified T : Any> Context.query(
    noinline block: QueryScope<T>.() -> Unit,
): Query<T> = database.query(block)

fun <T : Any> Context.query(
    meta: EntityMeta<T>,
    block: QueryScope<T>.() -> Unit,
): Query<T> = database.query(meta, block)

inline fun <reified T : Any> Context.insert(value: T): Long = database.insert(value)

inline fun <reified T : Any> Context.update(value: T): Long = database.update(value)

inline fun <reified T : Any> Context.delete(value: T): Long = database.delete(value)
