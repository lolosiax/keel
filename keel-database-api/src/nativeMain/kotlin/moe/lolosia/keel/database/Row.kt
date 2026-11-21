package moe.lolosia.keel.database

/**
 * 一行结果，按下标取值。
 *
 * 值在 [QueryResult.next] 返回时已经从结果集复制出来，可以在下一次 `next` 之后继续用。
 * 带 `OrNull` 的访问器在列为 NULL（或类型不符）时返回 null，供 ORM 的行映射使用。
 */
class Row internal constructor(private val values: List<DbValue>) {

    val size: Int get() = values.size

    operator fun get(index: Int): DbValue = values[index]

    /** 文本列；NULL 返回 null。 */
    fun text(index: Int): String? = (values[index] as? DbValue.Text)?.value

    /** 整数列；NULL 或类型不符返回 null。 */
    fun longOrNull(index: Int): Long? = (values[index] as? DbValue.Integer)?.value

    /** 整数列；NULL 或类型不符抛 [IllegalStateException]。 */
    fun long(index: Int): Long = longOrNull(index)
        ?: throw IllegalStateException("第 $index 列不是整数：${values[index]}")

    fun int(index: Int): Int = long(index).toInt()

    /** 浮点列；NULL 或类型不符返回 null。 */
    fun doubleOrNull(index: Int): Double? = (values[index] as? DbValue.Decimal)?.value

    /** 浮点列；NULL 或类型不符抛 [IllegalStateException]。 */
    fun double(index: Int): Double = doubleOrNull(index)
        ?: throw IllegalStateException("第 $index 列不是浮点数：${values[index]}")

    /**
     * 布尔列；NULL 或类型不符返回 null。
     *
     * SQLite 没有布尔类型，驱动会把布尔列读成 [DbValue.Integer]，所以 0 / 非 0 也接受。
     */
    fun booleanOrNull(index: Int): Boolean? = when (val value = values[index]) {
        is DbValue.Bool -> value.value
        is DbValue.Integer -> value.value != 0L
        else -> null
    }

    fun boolean(index: Int): Boolean = booleanOrNull(index)
        ?: throw IllegalStateException("第 $index 列不是布尔：${values[index]}")

    fun bytes(index: Int): ByteArray? = (values[index] as? DbValue.Blob)?.value
}
