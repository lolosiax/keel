package moe.lolosia.keel.database

/**
 * 一条 SQL 参数或结果值。
 *
 * 与 C ABI 的 `KeelDbValue` 一一对应：[Integer] / [Timestamp] 按 8 字节小端补码传输，
 * [Float] 按 8 字节小端 IEEE-754 传输；[Decimal] 始终是规范十进制文本，不经过 Double。
 */
sealed interface DbValue {

    /** SQL NULL。 */
    data object Null : DbValue

    data class Text(val value: String) : DbValue

    data class Integer(val value: Long) : DbValue

    /** 浮点列（SQL 的 FLOAT / DOUBLE）。 */
    data class Float(val value: Double) : DbValue

    /** 精确十进制；内容是驱动给出的规范文本，例如 `12.3400`。 */
    data class Decimal(val value: String) : DbValue

    /** 时间戳；值为 epoch 微秒（UTC）。 */
    data class Timestamp(val micros: Long) : DbValue

    data class Json(val value: String) : DbValue

    data class Uuid(val value: String) : DbValue

    data class Bool(val value: Boolean) : DbValue

    /** 二进制；按引用传递，调用方不要在此之后修改内容。 */
    class Blob(val value: ByteArray) : DbValue
}
