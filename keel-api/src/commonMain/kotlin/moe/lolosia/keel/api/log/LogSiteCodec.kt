package moe.lolosia.keel.api.log

/**
 * `LogSite` 的跨运行时文本编码。
 *
 * 插件把日志作为 `KEEL_EVENT_LOG` 的 UTF-8 载荷发往 Host；带来源时用
 * 本编码携带全部字段，Host 解析后可以直接按类名与行号展示。不带来源
 * （未改写调用）时仍走纯文本，保持旧载荷兼容。
 */
object LogSiteCodec {

    private const val MARKER = "KLOG1"
    private const val SEPARATOR = '\u0001'

    /** 解析结果。 */
    class Decoded(val site: LogSite, val message: String)

    fun encode(site: LogSite, message: String): String = buildString {
        append(MARKER).append(SEPARATOR)
        append(site.displayName).append(SEPARATOR)
        append(site.file).append(SEPARATOR)
        append(site.line).append(SEPARATOR)
        append(site.packageName.orEmpty()).append(SEPARATOR)
        append(site.className.orEmpty()).append(SEPARATOR)
        append(site.functionName.orEmpty()).append(SEPARATOR)
        append(site.kind.name).append(SEPARATOR)
        append(message)
    }

    /** 不是本编码的载荷返回 null，调用方按纯文本处理。 */
    fun decode(payload: String): Decoded? {
        if (!payload.startsWith("$MARKER$SEPARATOR")) return null
        val parts = payload.split(SEPARATOR, limit = 9)
        if (parts.size != 9) return null
        val line = parts[3].toIntOrNull() ?: return null
        val kind = LogSiteKind.entries.firstOrNull { it.name == parts[7] } ?: return null
        val site = LogSite(
            displayName = parts[1],
            file = parts[2],
            line = line,
            packageName = parts[4].ifEmpty { null },
            className = parts[5].ifEmpty { null },
            functionName = parts[6].ifEmpty { null },
            kind = kind,
        )
        return Decoded(site, parts[8])
    }
}
