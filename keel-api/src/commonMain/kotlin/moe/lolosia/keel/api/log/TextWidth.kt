package moe.lolosia.keel.api.log

/**
 * 终端显示宽度。
 *
 * `String.length` 数的是 UTF-16 code unit，直接 `padEnd` 会让 CJK 等宽字符
 * 之后的列错位；模块名、来源列的补齐与对齐一律按本对象计算的列数。
 *
 * 规则：East Asian Wide / Fullwidth 占 2 列，组合附加符与零宽字符占 0 列，其余 1 列。
 */
object TextWidth {

    /** [text] 在等宽终端里占用的列数。 */
    fun of(text: String): Int {
        var columns = 0
        var index = 0
        while (index < text.length) {
            val first = text[index].code
            val codePoint: Int
            if (first in 0xD800..0xDBFF && index + 1 < text.length) {
                val second = text[index + 1].code
                if (second in 0xDC00..0xDFFF) {
                    codePoint = 0x10000 + ((first - 0xD800) shl 10) + (second - 0xDC00)
                    index++
                } else {
                    codePoint = first
                }
            } else {
                codePoint = first
            }
            columns += columnsOf(codePoint)
            index++
        }
        return columns
    }

    /** 按终端列数右补空格；[columns] 小于实际宽度时不截断。 */
    fun padEnd(text: String, columns: Int): String =
        text + " ".repeat((columns - of(text)).coerceAtLeast(0))

    private fun columnsOf(codePoint: Int): Int = when {
        isZeroWidth(codePoint) -> 0
        isWide(codePoint) -> 2
        else -> 1
    }

    private fun isZeroWidth(codePoint: Int): Boolean = when (codePoint) {
        0x00AD, 0x200B, 0x200C, 0x200D, 0xFEFF -> true
        in 0x0300..0x036F -> true
        in 0x1AB0..0x1AFF -> true
        in 0x1DC0..0x1DFF -> true
        in 0x200E..0x200F -> true
        in 0x2060..0x2064 -> true
        in 0x20D0..0x20FF -> true
        in 0xFE00..0xFE0F -> true
        in 0xFE20..0xFE2F -> true
        else -> false
    }

    private fun isWide(codePoint: Int): Boolean = when (codePoint) {
        in 0x1100..0x115F -> true
        in 0x2E80..0x303E -> true
        in 0x3041..0x33FF -> true
        in 0x3400..0x4DBF -> true
        in 0x4E00..0x9FFF -> true
        in 0xA000..0xA4CF -> true
        in 0xA960..0xA97F -> true
        in 0xAC00..0xD7A3 -> true
        in 0xF900..0xFAFF -> true
        in 0xFE10..0xFE19 -> true
        in 0xFE30..0xFE6F -> true
        in 0xFF00..0xFF60 -> true
        in 0xFFE0..0xFFE6 -> true
        in 0x1F300..0x1F64F -> true
        in 0x1F900..0x1F9FF -> true
        in 0x20000..0x2FFFD -> true
        in 0x30000..0x3FFFD -> true
        else -> false
    }
}
