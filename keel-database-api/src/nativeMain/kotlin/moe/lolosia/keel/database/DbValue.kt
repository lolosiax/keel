package moe.lolosia.keel.database

/**
 * 一条 SQL 参数或结果值。
 *
 * 与 C ABI 的 `KeelDbValue` 一一对应：[Integer] 按 8 字节小端补码传输，
 * [Decimal] 按 8 字节小端 IEEE-754 传输。
 */
sealed interface DbValue {

    /** SQL NULL。 */
    data object Null : DbValue

    data class Text(val value: String) : DbValue

    data class Integer(val value: Long) : DbValue

    data class Decimal(val value: Double) : DbValue

    data class Bool(val value: Boolean) : DbValue

    /** 二进制；按引用传递，调用方不要在此之后修改内容。 */
    class Blob(val value: ByteArray) : DbValue
}
