package moe.lolosia.keel.orm

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import moe.lolosia.keel.database.DbValue
import moe.lolosia.keel.database.Row
import kotlin.time.Instant

/**
 * 内置类型映射表。
 *
 * 约定（与 ABI v1 对齐）：
 * - `Instant` 存 epoch 毫秒（INT）；
 * - `Decimal` 存规范十进制文本（TEXT），不经过 Double；
 * - JSON 存 UTF-8 文本（TEXT）；
 * - 枚举按名称存字符串；SQLite 的布尔列读回来是 INT，[BOOL] 已兼容。
 *
 * 生成物直接引用这里的实例（`OrmTypes.TEXT` 等），业务代码不要手写新的映射。
 */
object OrmTypes {

    val TEXT: OrmType<String> = object : OrmType<String> {
        override fun encode(value: String): DbValue = DbValue.Text(value)
        override fun decode(row: Row, index: Int): String = row.text(index) ?: ""
    }

    val INT: OrmType<Long> = object : OrmType<Long> {
        override fun encode(value: Long): DbValue = DbValue.Integer(value)
        override fun decode(row: Row, index: Int): Long = row.longOrNull(index) ?: 0L
    }

    val REAL: OrmType<Double> = object : OrmType<Double> {
        override fun encode(value: Double): DbValue = DbValue.Decimal(value)
        override fun decode(row: Row, index: Int): Double = row.doubleOrNull(index) ?: 0.0
    }

    val BOOL: OrmType<Boolean> = object : OrmType<Boolean> {
        override fun encode(value: Boolean): DbValue = DbValue.Bool(value)
        override fun decode(row: Row, index: Int): Boolean = row.booleanOrNull(index) ?: false
    }

    val BLOB: OrmType<ByteArray> = object : OrmType<ByteArray> {
        override fun encode(value: ByteArray): DbValue = DbValue.Blob(value)
        override fun decode(row: Row, index: Int): ByteArray = row.bytes(index) ?: ByteArray(0)
    }

    val INSTANT: OrmType<Instant> = object : OrmType<Instant> {
        override fun encode(value: Instant): DbValue = DbValue.Integer(value.toEpochMilliseconds())
        override fun decode(row: Row, index: Int): Instant =
            Instant.fromEpochMilliseconds(row.longOrNull(index) ?: 0L)
    }

    val DECIMAL: OrmType<Decimal> = object : OrmType<Decimal> {
        override fun encode(value: Decimal): DbValue = DbValue.Text(value.text)
        override fun decode(row: Row, index: Int): Decimal = Decimal(row.text(index) ?: "0")
    }

    private val json = Json {
        ignoreUnknownKeys = true
    }

    val JSON: OrmType<JsonElement> = object : OrmType<JsonElement> {
        override fun encode(value: JsonElement): DbValue =
            DbValue.Text(json.encodeToString(JsonElement.serializer(), value))

        override fun decode(row: Row, index: Int): JsonElement =
            json.parseToJsonElement(row.text(index) ?: "null")
    }

    /** 枚举列：按名称存字符串，读回时按名称匹配。 */
    inline fun <reified E : Enum<E>> enum(): OrmType<E> = object : OrmType<E> {
        override fun encode(value: E): DbValue = DbValue.Text(value.name)

        override fun decode(row: Row, index: Int): E {
            val name = row.text(index) ?: error("第 $index 列是 NULL，无法映射为枚举")
            return enumValues<E>().firstOrNull { it.name == name }
                ?: error("第 $index 列的值 '$name' 不是 ${E::class.simpleName} 的枚举项")
        }
    }
}
