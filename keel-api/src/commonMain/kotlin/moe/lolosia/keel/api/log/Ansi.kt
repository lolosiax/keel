package moe.lolosia.keel.api.log

/**
 * ANSI SGR 控制符。配色以 Spring Boot 为基础：来源列 cyan、模块名按哈希取色；
 * 级别在 Spring 基础上把 TRACE 改成亮灰、DEBUG 改成浅蓝、FATAL 改成红底黑字。
 */
object Ansi {

    const val RESET = "\u001B[0m"

    const val FAINT = 2

    const val BLACK = 30

    const val RED = 31

    const val GREEN = 32

    const val YELLOW = 33

    const val BLUE = 34

    const val MAGENTA = 35

    const val CYAN = 36

    const val BG_RED = 41

    const val BRIGHT_BLACK = 90

    const val BRIGHT_BLUE = 94

    /** Spring `defaults.xml` 里 logger 名的颜色：`%clr(...logger...){cyan}`。 */
    const val LOGGER = CYAN

    /** 模块名用 12 色盘：普通 6 色 + 亮色 6 色。 */
    private val MODULE_PALETTE = intArrayOf(
        RED, GREEN, YELLOW, BLUE, MAGENTA, CYAN,
        91, 92, 93, 94, 95, 96,
    )

    fun wrap(code: String, text: String): String = "\u001B[${code}m$text$RESET"

    fun wrap(code: Int, text: String): String = wrap(code.toString(), text)

    /**
     * 级别配色：TRACE 亮灰、DEBUG 浅蓝（避开类名的 cyan）、INFO 绿、
     * WARN 黄、ERROR 红、FATAL 红底黑字。
     */
    fun level(level: LogLevel): String = when (level) {
        LogLevel.TRACE -> BRIGHT_BLACK.toString()
        LogLevel.DEBUG -> BRIGHT_BLUE.toString()
        LogLevel.INFO -> GREEN.toString()
        LogLevel.WARN -> YELLOW.toString()
        LogLevel.ERROR -> RED.toString()
        LogLevel.FATAL -> "$BLACK;$BG_RED"
    }

    /** 模块颜色：作者在 `plugin.json` 里配了 `color` 就用 truecolor，否则回落到哈希。 */
    fun moduleStyle(module: String): String =
        ModuleColors.resolve(module) ?: hashModule(module).toString()

    /** FNV-1a 哈希取色；同一模块名在任何平台都得到同一颜色。 */
    fun hashModule(module: String): Int {
        var hash = 0x811C9DC5.toInt()
        for (ch in module) {
            hash = (hash xor ch.code) * 0x01000193
        }
        return MODULE_PALETTE[(hash and Int.MAX_VALUE) % MODULE_PALETTE.size]
    }

    /** 解析 `#RRGGBB` / `#RGB`，返回 ANSI truecolor 前景样式；非法返回 null。 */
    fun trueColor(hex: String): String? {
        val raw = hex.trim().removePrefix("#")
        val digits = when (raw.length) {
            3 -> buildString { raw.forEach { append(it).append(it) } }
            6 -> raw
            else -> return null
        }
        if (!digits.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
        val value = digits.toInt(16)
        return "38;2;${(value shr 16) and 0xFF};${(value shr 8) and 0xFF};${value and 0xFF}"
    }
}
