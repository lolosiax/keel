package moe.lolosia.keel.orm

/**
 * 精确十进制。
 *
 * 内部只保存规范文本，避免 `Double` 的精度损失；不做算术运算，
 * 运算请在数据库里完成，或显式转成 `BigDecimal` 风格的字符串。
 */
class Decimal(val text: String) {

    override fun toString(): String = text

    override fun equals(other: Any?): Boolean = other is Decimal && other.text == text

    override fun hashCode(): Int = text.hashCode()

    companion object {

        /** 直接用文本构造；不校验格式。 */
        fun of(value: String): Decimal = Decimal(value)

        fun of(value: Long): Decimal = Decimal(value.toString())

        fun of(value: Double): Decimal = Decimal(value.toString())
    }
}
