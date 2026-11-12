package moe.lolosia.keel.plugins.console

import moe.lolosia.keel.api.PluginContext
import moe.lolosia.keel.api.host.HostSubjects

/**
 * 窗口输入行的规范化与转发。
 *
 * 只处理 Enter 提交的整行：去行尾 CR/LF、丢空行、按 C 侧行长上限截断（UTF-8 字符边界）。
 * 转发到 `HostSubjects.CONSOLE_INPUT` 后与真实 stdin 走同一条缓冲和命令分发。
 */
internal object ConsoleInput {

    /** 与 `keel_console.c` 的 `KEEL_CONSOLE_LINE` 对齐：1024 字节含结尾 '\0'。 */
    private const val MAX_BYTES = 1023

    fun normalize(raw: String): String? {
        val line = raw.trimEnd('\r', '\n')
        if (line.isBlank()) return null
        return truncate(line, MAX_BYTES)
    }

    fun forward(context: PluginContext, line: String) {
        context.host.post(HostSubjects.CONSOLE_INPUT, line.encodeToByteArray())
    }

    /** 按字节数截断，但不把多字节字符（含代理对）劈开。 */
    private fun truncate(text: String, maxBytes: Int): String {
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val current = text[index]
            val paired = current.isHighSurrogate() &&
                index + 1 < text.length &&
                text[index + 1].isLowSurrogate()
            val size = when {
                current.code < 0x80 -> 1
                current.code < 0x800 -> 2
                paired -> 4
                else -> 3
            }
            if (bytes + size > maxBytes) break
            bytes += size
            index += if (paired) 2 else 1
        }
        return text.substring(0, index)
    }
}
