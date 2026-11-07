package moe.lolosia.keel.api.error

import moe.lolosia.keel.api.log.StackTraceFormat
import moe.lolosia.keel.api.log.platformLocate

/** 每层错误最多携带的帧数。 */
private const val MAX_FRAMES = 64

private val FRAME =
    Regex("""^\s*at\s+\d+\s+\S+\s+([0-9a-fA-F]+)\s+(\S+?)\s*\+\s*(\d+)""")

/** K/N 对外部模块帧会给一个假符号 + 巨大偏移，超过这个值就当作没有符号。 */
private const val MAX_PLAUSIBLE_OFFSET = 0x1000000L

/**
 * 把本地异常编码成跨运行时的错误信封。
 *
 * 帧拆成 `模块 + RVA + 原始符号`，接收方据此自行格式化；`Caused by:` 链拆成多层，
 * 每层单独带帧。
 */
fun Throwable.toKeelError(plugin: String = ""): KeelErrorPayload {
    val chain = mutableListOf<KeelErrorEntry>()
    var type = ""
    var message = ""
    var frames = mutableListOf<KeelErrorFrame>()
    var truncated = false

    fun flush() {
        if (type.isEmpty()) return
        chain += KeelErrorEntry(type, message, frames)
        type = ""
        message = ""
        frames = mutableListOf()
    }

    for (line in stackTraceToString().lines()) {
        val match = FRAME.find(line)
        if (match != null) {
            val address = match.groupValues[1].toLongOrNull(16) ?: 0L
            val offset = match.groupValues[3].toLongOrNull() ?: 0L
            val symbol = if (offset > MAX_PLAUSIBLE_OFFSET) "" else match.groupValues[2]
            val location = if (address != 0L) platformLocate(address) else null
            if (frames.size >= MAX_FRAMES) {
                truncated = true
            } else {
                frames += KeelErrorFrame(
                    module = location?.module.orEmpty(),
                    rva = location?.rva ?: 0L,
                    address = address,
                    symbol = symbol,
                )
            }
            continue
        }
        val text = line.trim()
        if (text.isEmpty() || text.contains("common stack frames skipped")) continue
        flush()
        val body = text.removePrefix("Caused by: ")
        type = body.substringBefore(": ").ifBlank { "Throwable" }
        message = if (body.contains(": ")) body.substringAfter(": ") else ""
    }
    flush()
    return KeelErrorPayload(plugin, chain, truncated)
}

/** 把远端错误链按 Java 风格渲染（供日志用；也用于没有结构化数据的兜底）。 */
internal fun renderFrames(frames: List<KeelErrorFrame>): String {
    val lines = frames.map {
        StackTraceFormat.frame(it.address, it.symbol, it.module, it.rva)
    }
    return buildString {
        for (line in StackTraceFormat.foldRepeats(lines)) append(line).append('\n')
    }
}
