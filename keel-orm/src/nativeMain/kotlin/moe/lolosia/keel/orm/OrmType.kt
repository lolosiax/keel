package moe.lolosia.keel.orm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import moe.lolosia.keel.database.DbValue
import moe.lolosia.keel.database.Row
import kotlin.time.Instant

/**
 * 一个 Kotlin 类型与 `KeelDbValue` 之间的双向映射。
 *
 * 生成的 meta / 列对象都持有具体实例；值只走参数绑定，不拼 SQL。
 */
interface OrmType<T> {

    /** Kotlin 值 → SQL 参数。 */
    fun encode(value: T): DbValue

    /** 结果行的第 [index] 列 → Kotlin 值；列不是 NULL 时使用。 */
    fun decode(row: Row, index: Int): T
}

/** 可空属性的写参数：null 落成 SQL NULL。 */
fun <T> OrmType<T>.encodeOrNull(value: T?): DbValue =
    if (value == null) DbValue.Null else encode(value)

/** 可空属性的读映射：列是 NULL 时返回 null。 */
fun <T> OrmType<T>.decodeOrNull(row: Row, index: Int): T? =
    if (row[index] is DbValue.Null) null else decode(row, index)
