package moe.lolosia.keel.api.error

/** 错误文本最多携带的字符数；超出时截断并标记。 */
private const val MAX_TRACE_CHARS = 32 * 1024

/**
 * 把本地异常编码成跨运行时的错误信封。
 *
 * 用 [Throwable.stackTraceToString] 捕获完整现场（含 `Caused by:` 链），
 * 只去掉与 [KeelErrorPayload.type] / [message] 重复的首行。
 */
fun Throwable.toKeelError(plugin: String = ""): KeelErrorPayload {
    val full = stackTraceToString()
    val header = toString()
    val lines = full.lines()
    val body = (if (lines.isNotEmpty() && lines.first() == header) lines.drop(1) else lines)
        .joinToString("\n")
    val truncated = body.length > MAX_TRACE_CHARS
    return KeelErrorPayload(
        plugin = plugin,
        type = header.substringBefore(':').ifBlank { "Throwable" },
        message = message ?: "",
        trace = if (truncated) body.take(MAX_TRACE_CHARS) else body,
        truncated = truncated,
    )
}
