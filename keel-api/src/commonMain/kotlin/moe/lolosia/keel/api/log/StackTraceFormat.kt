package moe.lolosia.keel.api.log

/**
 * 把 Kotlin/Native 的栈帧改写成 Java 风格：
 *
 * ```
 * kotlin.IllegalStateException: boom
 *     at 0x00007ffc1755aec0  keel-host.exe+0x53aec0  moe.lolosia.keel.host.HostHttp.dispatch
 *     Caused by: kotlin.IllegalArgumentException: inner
 * ```
 *
 * - `kfun:` 转成 `类.方法`（丢掉参数、返回类型、`#internal` 后缀）
 * - `_Z...` 用进程自带的 `__cxa_demangle` 还原，并丢掉模板参数
 * - 保留 `at`，丢掉帧序号、`???`，地址按 16 位对齐
 */
object StackTraceFormat {

    private val FRAME =
        Regex("""^\s*at\s+\d+\s+\S+\s+([0-9a-fA-F]+)\s+(\S+?)\s*\+\s*(\d+)""")

    /** K/N 对外部模块帧会给一个假符号 + 巨大偏移，超过这个值就当作没有符号。 */
    private const val MAX_PLAUSIBLE_OFFSET = 0x1000000L

    /** `_<hex>_N_knbridgeM`：hex 段是「模块坐标 + 源文件路径」。 */
    private val BRIDGE = Regex("""^_([0-9a-fA-F]+)_\d+_knbridge""")

    /** 整条异常链（含 `Caused by:`）的 Java 风格文本。 */
    fun throwable(error: Throwable): String {
        val output = StringBuilder()
        val pending = mutableListOf<String>()

        fun flush() {
            for (line in foldRepeats(pending)) output.appendLine(line)
            pending.clear()
        }

        for (line in error.stackTraceToString().lines()) {
            val match = FRAME.find(line)
            if (match != null) {
                val address = match.groupValues[1].toLongOrNull(16) ?: 0L
                val offset = match.groupValues[3].toLongOrNull() ?: 0L
                val symbol = if (offset > MAX_PLAUSIBLE_OFFSET) "" else match.groupValues[2]
                pending += frame(address, symbol)
                continue
            }
            flush()
            if (line.isNotBlank() && !line.contains("common stack frames skipped")) {
                output.appendLine(line.trimEnd())
            }
        }
        flush()
        return output.toString()
    }

    /**
     * 折叠重复帧：同一帧（模块+符号）只保留第一次出现，其余计数；
     * 段尾补一行 Java 风格的 `... N more`。
     *
     * Ktor 的 `SuspendFunctionGun.loop` / `proceed` 会随拦截器层数反复出现，
     * 但相邻帧并不完全相同（周期不固定），所以按「同名字帧」而不是按周期判断。
     */
    fun foldRepeats(frames: List<String>): List<String> {
        if (frames.size < 2) return frames
        val output = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var dropped = 0
        for (frame in frames) {
            if (seen.add(frame)) output += frame else dropped++
        }
        if (dropped > 0) output += "    ... $dropped more"
        return output
    }

    /**
     * 单行帧：有符号时是 `at 模块名  符号`；没有符号才用 `模块+偏移` 占位。
     *
     * [module] / [rva] 由调用方给定时不再反查地址（跨运行时的远端帧就是这样）。
     */
    fun frame(address: Long, rawSymbol: String, module: String = "", rva: Long = 0L): String {
        val name = symbol(rawSymbol)
        val location = when {
            module.isNotEmpty() -> module to rva
            address != 0L -> platformLocate(address)?.let { it.module to it.rva }
            else -> null
        }
        if (name.isNotEmpty()) {
            return if (location != null) "    at ${location.first.padEnd(28)}$name" else "    at $name"
        }
        return if (location != null) {
            // 没有符号时把偏移放进符号列，不加加号
            "    at ${location.first.padEnd(28)}0x${location.second.toString(16)}"
        } else {
            "    at"
        }
    }

    /** `kfun:` → Java 写法；`_Z` → C++ 写法（丢模板参数）；`_hex_..._knbridge` → 包.类.&lt;knbridge&gt; */
    fun symbol(raw: String): String = when {
        raw.startsWith("kfun:") -> {
            var name = raw.removePrefix("kfun:")
                .substringBefore('(')
                .substringBefore('{')
                .trim()
            name = name.replace('#', '.')
            if (name.endsWith(".internal")) name = name.dropLast(".internal".length)
            name
        }
        raw.startsWith("_Z") -> platformDemangle(raw)?.let(::dropTemplates) ?: raw
        raw.startsWith("_") -> bridgeSymbol(raw) ?: raw
        else -> raw
    }

    /**
     * K/N 的桥接符号形如 `_6d6f...6b74_1_knbridge1`，中间那段十六进制是
     * 「模块坐标 + 源文件绝对路径」；取出 `kotlin/` 之后的部分还原成 `包.类`。
     */
    private fun bridgeSymbol(raw: String): String? {
        val match = BRIDGE.find(raw) ?: return null
        val hex = match.groupValues[1]
        if (hex.length % 2 != 0) return null
        if (!hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        val text = ByteArray(hex.length / 2) {
            hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }.decodeToString().replace('\\', '/')
        val marker = text.indexOf("/kotlin/")
        val path = if (marker >= 0) text.substring(marker + 8) else text
        val file = path.substringAfterLast('/').removeSuffix(".kt")
        if (file.isEmpty()) return null
        val directory = path.substringBeforeLast('/', "")
        val owner = (directory.replace('/', '.') + "." + file).trim('.')
        return "$owner.<knbridge>"
    }

    /** 去掉 `<...>`（支持嵌套）。 */
    private fun dropTemplates(text: String): String {
        val plain = StringBuilder(text.length)
        var depth = 0
        for (ch in text) {
            when {
                ch == '<' -> depth++
                ch == '>' && depth > 0 -> depth--
                depth == 0 -> plain.append(ch)
            }
        }
        return plain.toString()
    }
}
