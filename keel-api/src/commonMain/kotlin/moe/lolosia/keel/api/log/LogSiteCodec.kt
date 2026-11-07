package moe.lolosia.keel.api.log

/**
 * `LogSite` 的跨运行时文本编码。
 *
 * 插件把日志作为 `KEEL_EVENT_LOG` 的 UTF-8 载荷发往 Host；带来源时用
 * 本编码携带全部字段与模块名（Host / 插件名 / 自定义 tag），Host 解析后
 * 直接按模块、类名与行号展示。不带来源（未改写调用）时仍走纯文本。
 */
object LogSiteCodec {

    private const val MARKER = "KLOG2"
    private const val LEGACY_MARKER = "KLOG1"
    private const val SEPARATOR = '\u0001'

    /** 解析结果；[module] 为 null 时由 Host 用插件名兜底。 */
    class Decoded(val site: LogSite, val module: String?, val message: String)

    fun encode(site: LogSite, module: String?, message: String): String = buildString {
        append(MARKER).append(SEPARATOR)
        append(module.orEmpty()).append(SEPARATOR)
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
        if (payload.startsWith("$MARKER$SEPARATOR")) {
            val parts = payload.split(SEPARATOR, limit = 10)
            if (parts.size != 10) return null
            return decodeParts(parts, moduleIndex = 1, siteStart = 2, kindIndex = 8, messageIndex = 9)
        }
        // 兼容没有模块名的旧载荷
        if (payload.startsWith("$LEGACY_MARKER$SEPARATOR")) {
            val parts = payload.split(SEPARATOR, limit = 9)
            if (parts.size != 9) return null
            return decodeParts(parts, moduleIndex = null, siteStart = 1, kindIndex = 7, messageIndex = 8)
        }
        return null
    }

    private fun decodeParts(
        parts: List<String>,
        moduleIndex: Int?,
        siteStart: Int,
        kindIndex: Int,
        messageIndex: Int,
    ): Decoded? {
        val line = parts[siteStart + 2].toIntOrNull() ?: return null
        val kind = LogSiteKind.entries.firstOrNull { it.name == parts[kindIndex] } ?: return null
        return Decoded(
            site = LogSite(
                displayName = parts[siteStart],
                file = parts[siteStart + 1],
                line = line,
                packageName = parts[siteStart + 3].ifEmpty { null },
                className = parts[siteStart + 4].ifEmpty { null },
                functionName = parts[siteStart + 5].ifEmpty { null },
                kind = kind,
            ),
            module = moduleIndex?.let { parts[it].ifEmpty { null } },
            message = parts[messageIndex],
        )
    }
}
