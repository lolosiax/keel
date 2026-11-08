package moe.lolosia.keel.database

/**
 * 一行结果，按下标取值。
 *
 * 值在 [QueryResult.next] 返回时已经从结果集复制出来，可以在下一次 `next` 之后继续用。
 */
class Row internal constructor(private val values: List<DbValue>) {

    val size: Int get() = values.size

    operator fun get(index: Int): DbValue = values[index]

    /** 文本列；NULL 返回 null。 */
    fun text(index: Int): String? = (values[index] as? DbValue.Text)?.value

    /** 整数列；NULL 或类型不符抛 [IllegalStateException]。 */
    fun long(index: Int): Long = (values[index] as? DbValue.Integer)?.value
        ?: throw IllegalStateException("第 $index 列不是整数：${values[index]}")

    fun int(index: Int): Int = long(index).toInt()

    fun boolean(index: Int): Boolean = (values[index] as? DbValue.Bool)?.value
        ?: throw IllegalStateException("第 $index 列不是布尔：${values[index]}")

    fun bytes(index: Int): ByteArray? = (values[index] as? DbValue.Blob)?.value
}
