package moe.lolosia.keel.plugins.rbac

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 数据库会话时区固定 UTC（能力包连接时执行 `SET time_zone = '+00:00'`）。
 *
 * 因此写 `timestamp` 列的文本必须是 UTC 墙钟；读回来的值也按 UTC 解释后再转展示时区。
 */
private val DB_ZONE = TimeZone.UTC

/** 展示时间固定用东八区（固定偏移，不依赖 tzdb），与前端表格的 `yyyy-MM-dd HH:mm:ss` 对齐。 */
private val DISPLAY_ZONE = TimeZone.of("+08:00")

/** 写 `timestamp` 列的时间文本：UTC 墙钟。 */
internal fun nowDb(): String = epochMsToText(Clock.System.now().toEpochMilliseconds(), DB_ZONE)

/** epoch 毫秒 → `yyyy-MM-dd HH:mm:ss`（东八区）。 */
internal fun epochMsToDisplay(epochMs: Long): String = epochMsToText(epochMs, DISPLAY_ZONE)

/** 库里的 UTC 时间文本 → 东八区展示时间；无法解析时原样返回。 */
internal fun displayTime(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val epoch = parseDbTime(raw) ?: return raw
    return epochMsToDisplay(epoch)
}

/** 解析 `yyyy-MM-dd HH:mm:ss`（UTC，可带小数秒）；失败返回 null。 */
internal fun parseDbTime(raw: String): Long? {
    val text = raw.trim().replace(' ', 'T').let { if (it.length > 19) it.substring(0, 19) else it }
    return runCatching { LocalDateTime.parse(text).toInstant(DB_ZONE).toEpochMilliseconds() }.getOrNull()
}

private fun epochMsToText(epochMs: Long, zone: TimeZone): String {
    val local = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)
    fun pad(value: Int): String = if (value < 10) "0$value" else value.toString()
    return buildString {
        append(local.year)
        append('-').append(pad(local.month.ordinal + 1))
        append('-').append(pad(local.day))
        append(' ').append(pad(local.hour))
        append(':').append(pad(local.minute))
        append(':').append(pad(local.second))
    }
}
